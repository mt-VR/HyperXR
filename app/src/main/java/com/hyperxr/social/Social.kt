package com.hyperxr.social

import android.content.Context
import com.hyperxr.diagnostics.Diagnostics
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

data class Profile(
    val id: String, val username: String, val displayName: String, val color: Int, val status: String,
    val xp: Int, val level: Int, val playing: String, val lastSeenMs: Long,
) {
    val name: String get() = displayName.ifBlank { username }
    val online: Boolean get() = System.currentTimeMillis() - lastSeenMs < 150_000

    /** "Online agora", "Jogando Escalada", "Visto há 8 horas". */
    fun activity(): String = when {
        online && playing.isNotBlank() -> "Jogando $playing"
        online -> "Online agora"
        else -> "Última atividade " + ago(lastSeenMs)
    }

    companion object {
        fun from(o: JSONObject) = Profile(
            o.getString("id"), o.optString("username"), o.optString("display_name"), o.optInt("color"), o.optString("status"),
            o.optInt("xp"), o.optInt("level", 1), o.optString("playing"), parseTime(o.optString("last_seen")),
        )
    }
}

data class Friend(val profile: Profile, val state: State) {
    enum class State { FRIEND, REQUEST_IN, REQUEST_OUT }
}

data class Message(val id: Long, val sender: String, val receiver: String, val body: String, val createdMs: Long, val read: Boolean) {
    companion object {
        fun from(o: JSONObject) = Message(o.getLong("id"), o.getString("sender"), o.getString("receiver"), o.getString("body"),
            parseTime(o.optString("created_at")), !o.isNull("read_at"))
    }
}

internal fun parseTime(s: String): Long = runCatching { Instant.parse(s.replace(" ", "T").let { if (it.endsWith("Z") || it.contains('+')) it else it + "Z" }).toEpochMilli() }.getOrDefault(0L)

internal fun ago(ms: Long): String {
    if (ms <= 0) return "desconhecida"
    val m = (System.currentTimeMillis() - ms) / 60_000
    return when {
        m < 1 -> "agora"
        m < 60 -> "há $m min"
        m < 60 * 24 -> "há ${m / 60} hora" + if (m / 60 > 1) "s" else ""
        else -> "há ${m / 1440} dia" + if (m / 1440 > 1) "s" else ""
    }
}

/**
 * HyperMagic's social side (the Pessoas app): your profile, friends and friend requests, direct
 * messages and who is online, kept live through Supabase Realtime. Calls and screen sharing are in
 * [Calls]; their signalling arrives on the same personal channel. Shared by the 2D settings and XR.
 */
object Social {
    lateinit var sb: Supabase; private set
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()

    @Volatile var me: Profile? = null; private set
    @Volatile var friends: List<Friend> = emptyList(); private set
    @Volatile var unread: Map<String, Int> = emptyMap(); private set
    @Volatile var lastError: String? = null; private set
    @Volatile var started = false; private set
    private val conversations = HashMap<String, MutableList<Message>>()
    private var heartbeat: java.util.concurrent.ScheduledFuture<*>? = null
    @Volatile private var playing = ""

    fun init(ctx: Context) {
        if (::sb.isInitialized) return
        sb = Supabase(ctx.applicationContext)
        Calls.init(ctx.applicationContext)
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.forEach { runCatching { it() } }

    /** Runs [work] off the UI/GL threads; errors land in [lastError]. */
    fun run(work: () -> Unit) = io.execute {
        try { work(); lastError = null } catch (t: Throwable) { lastError = t.message ?: t.toString(); Diagnostics.log("Social: $t") }
        changed()
    }

    /** After sign-in (or at app start when already signed in): profile, friends, live updates. */
    fun start() = run {
        if (!sb.signedIn) return@run
        if (started && me != null) { refreshFriends(); refreshUnread(); return@run }
        val uid = sb.userId!!
        me = sb.select("profiles?id=eq.$uid&select=*").optJSONObject(0)?.let(Profile::from)
        if (me == null) return@run  // needs a username first (createProfile)
        refreshFriends()
        refreshUnread()
        sb.realtime().subscribe("user:$uid", listOf(
            Triple("INSERT", "messages", "receiver=eq.$uid"),
            Triple("*", "friendships", "addressee=eq.$uid"),
            Triple("UPDATE", "friendships", "requester=eq.$uid"),
        )) { event, payload -> onEvent(event, payload) }
        heartbeat?.cancel(false)
        heartbeat = io.scheduleWithFixedDelay({ runCatching { touch() } }, 0, 60, TimeUnit.SECONDS)
        started = true
    }

    private fun touch() {
        val uid = sb.userId ?: return
        sb.update("profiles?id=eq.$uid", JSONObject().put("last_seen", Instant.now().toString()).put("playing", playing))
        // friends' online state comes from their last_seen
        refreshFriends()
        changed()
    }

    private fun onEvent(event: String, payload: JSONObject) {
        when (event) {
            "postgres_changes" -> {
                val table = payload.optString("table")
                val record = payload.optJSONObject("record") ?: JSONObject()
                if (table == "messages" && payload.optString("type") == "INSERT") {
                    val m = Message.from(record)
                    synchronized(conversations) { conversations[m.sender]?.let { if (it.none { x -> x.id == m.id }) it.add(m) } }
                    unread = unread + (m.sender to (unread[m.sender] ?: 0) + 1)
                    lastIncoming = m
                    // the conversation is open: it is read right away
                    if (m.sender == openChat) loadConversation(m.sender)
                    changed()
                } else run { refreshFriends() }
            }
            "signal" -> Calls.onSignal(payload)
        }
    }

    /** The friend whose conversation is on screen. */
    @Volatile var openChat: String? = null

    /** Newest message that arrived live (the launcher shows a notification). */
    @Volatile var lastIncoming: Message? = null

    fun setPlaying(title: String) {
        if (title == playing) return
        playing = title
        if (me != null) run { touch() }
    }

    // ------------------------------------------------------------------ account

    fun createProfile(username: String, displayName: String) = run {
        val uid = sb.userId ?: error("Entre na conta primeiro")
        val u = username.trim().lowercase()
        require(Regex("^[a-z0-9_.]{3,20}$").matches(u)) { "Nome de usuário: 3 a 20 letras minúsculas, números, _ ou ." }
        sb.insert("profiles", JSONObject().put("id", uid).put("username", u).put("display_name", displayName.trim())
            .put("color", (u.hashCode() and 0x7fffffff) % 360), upsert = true)
        me = null
        start()
    }

    fun updateStatus(status: String) = run {
        val uid = sb.userId ?: return@run
        sb.update("profiles?id=eq.$uid", JSONObject().put("status", status.take(80)))
        me = me?.copy(status = status.take(80))
    }

    fun signOut() {
        heartbeat?.cancel(false)
        sb.signOut()
        me = null; friends = emptyList(); unread = emptyMap(); started = false
        synchronized(conversations) { conversations.clear() }
        changed()
    }

    // ------------------------------------------------------------------ friends

    fun refreshFriends() {
        val uid = sb.userId ?: return
        val rows = sb.select("friendships?or=(requester.eq.$uid,addressee.eq.$uid)&select=*")
        val ids = (0 until rows.length()).map { rows.getJSONObject(it) }.map { if (it.getString("requester") == uid) it.getString("addressee") else it.getString("requester") }
        if (ids.isEmpty()) { friends = emptyList(); return }
        val profiles = sb.select("profiles?id=in.(${ids.joinToString(",")})&select=*").let { a -> (0 until a.length()).map { Profile.from(a.getJSONObject(it)) } }.associateBy { it.id }
        friends = (0 until rows.length()).mapNotNull { i ->
            val r = rows.getJSONObject(i)
            val other = if (r.getString("requester") == uid) r.getString("addressee") else r.getString("requester")
            val p = profiles[other] ?: return@mapNotNull null
            val state = when {
                r.getString("status") == "accepted" -> Friend.State.FRIEND
                r.getString("requester") == uid -> Friend.State.REQUEST_OUT
                else -> Friend.State.REQUEST_IN
            }
            Friend(p, state)
        }.sortedWith(compareBy<Friend>({ it.state != Friend.State.REQUEST_IN }, { !it.profile.online }, { it.profile.name.lowercase() }))
    }

    fun addFriend(username: String) = run {
        val uid = sb.userId ?: error("Entre na conta primeiro")
        val u = username.trim().lowercase().removePrefix("@")
        val p = sb.select("profiles?username=eq.$u&select=*").optJSONObject(0)?.let(Profile::from) ?: error("Ninguém com o usuário @$u")
        require(p.id != uid) { "Esse é você" }
        // if they already asked us, accepting is enough
        if (friends.any { it.profile.id == p.id && it.state == Friend.State.REQUEST_IN }) accept(p.id)
        else sb.insert("friendships", JSONObject().put("requester", uid).put("addressee", p.id))
        refreshFriends()
        com.hyperxr.progress.ProgressStore.record(com.hyperxr.progress.Activity.AddedFriend)
    }

    private fun accept(otherId: String) {
        val uid = sb.userId ?: return
        sb.update("friendships?requester=eq.$otherId&addressee=eq.$uid", JSONObject().put("status", "accepted"))
    }

    fun acceptFriend(otherId: String) = run { accept(otherId); refreshFriends() }

    fun removeFriend(otherId: String) = run {
        val uid = sb.userId ?: return@run
        sb.delete("friendships?or=(and(requester.eq.$uid,addressee.eq.$otherId),and(requester.eq.$otherId,addressee.eq.$uid))")
        refreshFriends()
    }

    fun profile(id: String): Profile? = friends.firstOrNull { it.profile.id == id }?.profile ?: me?.takeIf { it.id == id }

    // ------------------------------------------------------------------ messages

    fun conversation(friendId: String): List<Message> = synchronized(conversations) { conversations[friendId]?.toList() } ?: emptyList()

    fun loadConversation(friendId: String) = run {
        val uid = sb.userId ?: return@run
        val a = sb.select("messages?or=(and(sender.eq.$uid,receiver.eq.$friendId),and(sender.eq.$friendId,receiver.eq.$uid))&order=created_at.desc&limit=60&select=*")
        val list = (0 until a.length()).map { Message.from(a.getJSONObject(it)) }.reversed().toMutableList()
        synchronized(conversations) { conversations[friendId] = list }
        if (list.any { it.sender == friendId && !it.read }) {
            sb.update("messages?sender=eq.$friendId&receiver=eq.$uid&read_at=is.null", JSONObject().put("read_at", Instant.now().toString()))
        }
        unread = unread - friendId
    }

    fun send(friendId: String, body: String) = run {
        val uid = sb.userId ?: error("Entre na conta primeiro")
        val text = body.trim().take(2000)
        if (text.isEmpty()) return@run
        val row = sb.insert("messages", JSONObject().put("sender", uid).put("receiver", friendId).put("body", text)).optJSONObject(0)
        if (row != null) synchronized(conversations) { conversations.getOrPut(friendId) { mutableListOf() }.add(Message.from(row)) }
        com.hyperxr.progress.ProgressStore.record(com.hyperxr.progress.Activity.SentMessage)
    }

    private fun refreshUnread() {
        val uid = sb.userId ?: return
        val a = sb.select("messages?receiver=eq.$uid&read_at=is.null&select=sender")
        unread = (0 until a.length()).map { a.getJSONObject(it).getString("sender") }.groupingBy { it }.eachCount()
    }

    val unreadTotal: Int get() = unread.values.sum()

    // ------------------------------------------------------------------ XP (engagement)

    /** Mirrors the local level to the profile, so friends see it. */
    fun syncProgress(xp: Int, level: Int) {
        if (me == null) return
        run {
            val uid = sb.userId ?: return@run
            sb.update("profiles?id=eq.$uid", JSONObject().put("xp", xp).put("level", level))
            me = me?.copy(xp = xp, level = level)
        }
    }
}
