package com.hyperxr.social

import android.content.Context
import com.hyperxr.diagnostics.Diagnostics
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Small Supabase client: Auth (e-mail and password, or anonymous when the project allows it),
 * PostgREST for the tables in supabase/schema.sql, and the Realtime websocket (Phoenix protocol)
 * for live messages and for call signalling over broadcast. The publishable key is meant to be in
 * the app; what each user may read or write is decided by the row level security in the schema.
 */
class Supabase(private val context: Context) {
    companion object {
        const val DEFAULT_URL = "https://htbxrwovaasxyxeqahpv.supabase.co"
        const val DEFAULT_KEY = "sb_publishable_ARo8ZBHUV79N6fOiXmCnnQ_RRrxPYVs"
        private val JSON = "application/json".toMediaType()
    }

    private val prefs = context.getSharedPreferences("supabase", Context.MODE_PRIVATE)
    val url: String get() = prefs.getString("url", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_URL
    val key: String get() = prefs.getString("key", null)?.takeIf { it.isNotBlank() } ?: DEFAULT_KEY
    fun setServer(url: String, key: String) { prefs.edit().putString("url", url.trim().trimEnd('/')).putString("key", key.trim()).apply(); signOut() }

    val http: OkHttpClient = OkHttpClient.Builder().connectTimeout(15, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS)
        .pingInterval(25, TimeUnit.SECONDS).build()

    // ------------------------------------------------------------------ auth

    @Volatile var accessToken: String? = prefs.getString("access", null); private set
    private var refreshToken: String? = prefs.getString("refresh", null)
    private var expiresAt: Long = prefs.getLong("expires", 0)
    val userId: String? get() = prefs.getString("user", null)
    val email: String? get() = prefs.getString("email", null)
    val signedIn: Boolean get() = userId != null && refreshToken != null

    private fun saveSession(o: JSONObject) {
        accessToken = o.getString("access_token")
        refreshToken = o.optString("refresh_token", refreshToken ?: "")
        expiresAt = System.currentTimeMillis() + o.optLong("expires_in", 3600) * 1000
        val user = o.optJSONObject("user")
        prefs.edit().putString("access", accessToken).putString("refresh", refreshToken).putLong("expires", expiresAt)
            .apply { if (user != null) { putString("user", user.getString("id")); putString("email", user.optString("email", "")) } }.apply()
    }

    fun signOut() {
        accessToken = null; refreshToken = null; expiresAt = 0
        prefs.edit().remove("access").remove("refresh").remove("expires").remove("user").remove("email").apply()
        realtime?.close(); realtime = null
    }

    /** Which sign-in kinds the project has switched on. */
    fun authSettings(): JSONObject = JSONObject(call(Request.Builder().url("$url/auth/v1/settings").header("apikey", key).get().build()))

    /** Creates the account. Returns false when the project wants the e-mail confirmed first. */
    fun signUp(email: String, password: String): Boolean {
        val o = JSONObject(call(post("$url/auth/v1/signup", JSONObject().put("email", email).put("password", password), auth = false)))
        if (o.has("access_token")) { saveSession(o); return true }
        return false
    }

    fun signIn(email: String, password: String) {
        saveSession(JSONObject(call(post("$url/auth/v1/token?grant_type=password", JSONObject().put("email", email).put("password", password), auth = false))))
    }

    fun signInAnonymously() {
        saveSession(JSONObject(call(post("$url/auth/v1/signup", JSONObject(), auth = false))))
    }

    /** A valid access token, refreshed when it is about to expire. */
    @Synchronized fun token(): String {
        val rt = refreshToken ?: throw IOException("Entre na sua conta em Pessoas")
        if (accessToken == null || System.currentTimeMillis() > expiresAt - 60_000) {
            saveSession(JSONObject(call(post("$url/auth/v1/token?grant_type=refresh_token", JSONObject().put("refresh_token", rt), auth = false))))
        }
        return accessToken!!
    }

    // ------------------------------------------------------------------ REST

    private fun post(u: String, body: Any, auth: Boolean = true, prefer: String? = null): Request =
        Request.Builder().url(u).header("apikey", key).apply {
            if (auth) header("Authorization", "Bearer ${token()}")
            if (prefer != null) header("Prefer", prefer)
        }.post(body.toString().toRequestBody(JSON)).build()

    private fun call(r: Request): String = http.newCall(r).execute().use { res: Response ->
        val text = res.body?.string().orEmpty()
        if (!res.isSuccessful) {
            val msg = runCatching { JSONObject(text).let { it.optString("msg").ifEmpty { it.optString("message").ifEmpty { it.optString("error_description").ifEmpty { it.optString("error") } } } } }.getOrNull()
            throw IOException(friendly(res.code, msg ?: text.take(200)))
        }
        text
    }

    private fun friendly(code: Int, msg: String): String = when {
        msg.contains("Invalid login credentials") -> "E-mail ou senha errados"
        msg.contains("Email not confirmed") -> "Confirme o e-mail pelo link que chegou na sua caixa de entrada"
        msg.contains("already registered") -> "Esse e-mail já tem conta: entre com ele"
        msg.contains("Anonymous sign-ins are disabled") -> "O login sem e-mail está desligado neste servidor"
        msg.contains("relation") && msg.contains("does not exist") || code == 404 -> "O banco ainda não foi preparado: rode supabase/schema.sql no painel do Supabase"
        msg.contains("duplicate key") && msg.contains("username") -> "Esse nome de usuário já existe"
        else -> "Servidor: $msg ($code)"
    }

    /** GET /rest/v1/[path] (PostgREST query string included). */
    fun select(path: String): JSONArray = JSONArray(call(Request.Builder().url("$url/rest/v1/$path").header("apikey", key)
        .header("Authorization", "Bearer ${token()}").get().build()))

    fun insert(table: String, row: JSONObject, upsert: Boolean = false): JSONArray = JSONArray(call(post("$url/rest/v1/$table", row,
        prefer = "return=representation" + if (upsert) ",resolution=merge-duplicates" else "")).ifEmpty { "[]" })

    fun update(pathWithFilter: String, row: JSONObject) {
        call(Request.Builder().url("$url/rest/v1/$pathWithFilter").header("apikey", key).header("Authorization", "Bearer ${token()}")
            .patch(row.toString().toRequestBody(JSON)).build())
    }

    fun delete(pathWithFilter: String) {
        call(Request.Builder().url("$url/rest/v1/$pathWithFilter").header("apikey", key).header("Authorization", "Bearer ${token()}").delete().build())
    }

    // ------------------------------------------------------------------ Realtime

    @Volatile private var realtime: Realtime? = null

    fun realtime(): Realtime = realtime ?: synchronized(this) { realtime ?: Realtime().also { realtime = it } }

    /**
     * One websocket for all channels. Channels rejoin by themselves after a drop. [onEvent] gets
     * (event, payload) for broadcasts and postgres_changes on that channel.
     */
    inner class Realtime {
        private val ref = AtomicInteger(1)
        private val channels = ConcurrentHashMap<String, Pair<JSONObject, (String, JSONObject) -> Unit>>()
        private val io = Executors.newSingleThreadScheduledExecutor()
        @Volatile private var ws: WebSocket? = null
        @Volatile private var closed = false
        @Volatile var connected = false; private set

        init {
            // Phoenix heartbeat keeps the socket and its channels alive
            io.scheduleWithFixedDelay({ send(JSONObject().put("topic", "phoenix").put("event", "heartbeat").put("payload", JSONObject()).put("ref", ref.getAndIncrement().toString())) }, 25, 25, TimeUnit.SECONDS)
            io.execute { connect() }
        }

        private fun connect() {
            if (ws != null || closed) return
            val wsUrl = url.replaceFirst("https://", "wss://").replaceFirst("http://", "ws://") + "/realtime/v1/websocket?apikey=$key&vsn=1.0.0"
            ws = http.newWebSocket(Request.Builder().url(wsUrl).build(), object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { connected = true; channels.forEach { (t, c) -> join(t, c.first) } }
                override fun onMessage(webSocket: WebSocket, text: String) { dispatch(text) }
                override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) { lost(webSocket, "falha: ${t.message}") }
                override fun onClosed(webSocket: WebSocket, code: Int, reason: String) { lost(webSocket, "fechado $code") }
            })
        }

        private fun lost(socket: WebSocket, why: String) {
            if (socket !== ws) return
            connected = false; ws = null
            if (closed) return
            Diagnostics.log("Realtime: $why; reconectando")
            io.schedule({ connect() }, 3, TimeUnit.SECONDS)
        }

        private fun dispatch(text: String) {
            val m = runCatching { JSONObject(text) }.getOrNull() ?: return
            val payload = m.optJSONObject("payload") ?: JSONObject()
            val handler = channels[m.optString("topic").removePrefix("realtime:")]?.second ?: return
            when (m.optString("event")) {
                "broadcast" -> handler(payload.optString("event"), payload.optJSONObject("payload") ?: JSONObject())
                "postgres_changes" -> handler("postgres_changes", payload.optJSONObject("data") ?: payload)
            }
        }

        private fun join(topic: String, config: JSONObject) {
            val token = runCatching { token() }.getOrNull()
            send(JSONObject().put("topic", "realtime:$topic").put("event", "phx_join").put("ref", ref.getAndIncrement().toString())
                .put("payload", JSONObject().put("config", config).apply { if (token != null) put("access_token", token) }))
        }

        /** Listens on [topic]; [tables] adds live INSERT/UPDATE rows of those tables matching the filters. */
        fun subscribe(topic: String, tables: List<Triple<String, String, String>> = emptyList(), onEvent: (String, JSONObject) -> Unit) {
            val pg = JSONArray()
            tables.forEach { (event, table, filter) ->
                pg.put(JSONObject().put("event", event).put("schema", "public").put("table", table).apply { if (filter.isNotEmpty()) put("filter", filter) })
            }
            val config = JSONObject().put("broadcast", JSONObject().put("self", false).put("ack", false))
                .put("presence", JSONObject().put("key", "")).put("postgres_changes", pg)
            channels[topic] = config to onEvent
            if (connected) join(topic, config)
        }

        fun unsubscribe(topic: String) {
            if (channels.remove(topic) != null) send(JSONObject().put("topic", "realtime:$topic").put("event", "phx_leave").put("payload", JSONObject()).put("ref", ref.getAndIncrement().toString()))
        }

        /** Sends a broadcast to everyone on [topic] (they must be subscribed to it). */
        fun broadcast(topic: String, event: String, payload: JSONObject) {
            send(JSONObject().put("topic", "realtime:$topic").put("event", "broadcast").put("ref", ref.getAndIncrement().toString())
                .put("payload", JSONObject().put("type", "broadcast").put("event", event).put("payload", payload)))
        }

        /** Broadcast to a channel this phone is not listening on (joins it briefly). */
        fun sendTo(topic: String, event: String, payload: JSONObject) {
            if (!channels.containsKey(topic)) {
                val config = JSONObject().put("broadcast", JSONObject().put("self", false))
                channels[topic] = config to { _, _ -> }
                if (connected) join(topic, config)
            }
            broadcast(topic, event, payload)
        }

        private fun send(o: JSONObject) { ws?.send(o.toString()) }

        fun close() { closed = true; ws?.close(1000, "bye"); io.shutdownNow() }
    }
}
