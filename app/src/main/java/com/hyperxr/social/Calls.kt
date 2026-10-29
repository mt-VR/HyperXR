package com.hyperxr.social

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.hyperxr.diagnostics.Diagnostics
import org.json.JSONObject
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.WebExtension
import java.io.ByteArrayOutputStream
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Voice calls and screen sharing between HyperMagic users, with the WebRTC of Firefox's engine
 * (GeckoView, already in the app for the browser): a hidden page (assets/callext) holds the
 * connection and plays the other person's voice; this object does the signalling over each
 * user's Supabase Realtime channel and talks to the page through native messaging.
 *
 * Audio goes straight between the phones (STUN only: on some mobile networks no direct path
 * exists and the call can't connect without a TURN relay). The shared screen is HyperMagic's 16:9
 * spectator view sent as JPEG frames (about 8 per second) on the call's data channel.
 */
object Calls {
    enum class State { IDLE, OUTGOING, INCOMING, ACTIVE }

    @Volatile var state = State.IDLE; private set
    @Volatile var peerId: String? = null; private set
    @Volatile var peerName: String = ""; private set
    @Volatile var sharing = false; private set
    @Volatile var muted = false; private set
    @Volatile var remoteScreen: Bitmap? = null; private set
    @Volatile var remoteScreenSeq = 0; private set
    @Volatile var info = ""; private set
    @Volatile var startedAtMs = 0L; private set

    /** The XR renderer gives frames of its 16:9 view while [wantFrames] is true. */
    @Volatile var wantFrames = false; private set

    private lateinit var ctx: Context
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val encoder = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var callId = ""
    private var pendingOffer: JSONObject? = null
    private var ringTimeout: java.util.concurrent.ScheduledFuture<*>? = null
    // the call page
    private var session: GeckoSession? = null
    private var port: WebExtension.Port? = null
    @Volatile private var ready = false
    private val queue = ArrayList<JSONObject>()
    @Volatile private var encoding = false

    fun init(c: Context) { ctx = c }

    private var host: java.lang.ref.WeakReference<Activity>? = null

    /** The activity that can start GeckoView; the call page itself loads only when a call starts or rings. */
    fun setHost(activity: Activity) { host = java.lang.ref.WeakReference(activity) }

    private fun ensurePage() { main.post { host?.get()?.let { attach(it) } } }
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.forEach { runCatching { it() } }

    // ------------------------------------------------------------------ the call page

    /**
     * Starts the hidden call page (UI thread; GeckoView needs an Activity once for its runtime).
     * GeckoResult callbacks run on the thread that made the result, here the UI thread.
     */
    @android.annotation.SuppressLint("WrongThread")
    @androidx.annotation.UiThread
    fun attach(activity: Activity) {
        if (session != null) return
        val runtime = com.hyperxr.browser.GeckoEngine.runtime(activity) ?: run { info = "Chamadas precisam do motor do navegador (GeckoView)"; return }
        runtime.webExtensionController.ensureBuiltIn("resource://android/assets/callext/", "calls@hypermagic").accept({ ext ->
            if (ext == null) return@accept
            ext.setMessageDelegate(object : WebExtension.MessageDelegate {
                override fun onConnect(p: WebExtension.Port) {
                    port = p
                    p.setDelegate(object : WebExtension.PortDelegate {
                        override fun onPortMessage(message: Any, p: WebExtension.Port) { (message as? JSONObject)?.let { m -> io.execute { fromPage(m) } } }
                        override fun onDisconnect(p: WebExtension.Port) { if (port === p) { port = null; ready = false } }
                    })
                }
            }, "hxcall")
            val s = GeckoSession(GeckoSessionSettings.Builder().usePrivateMode(false).build())
            s.permissionDelegate = object : GeckoSession.PermissionDelegate {
                override fun onMediaPermissionRequest(session: GeckoSession, uri: String, video: Array<out GeckoSession.PermissionDelegate.MediaSource>?,
                                                      audio: Array<out GeckoSession.PermissionDelegate.MediaSource>?, callback: GeckoSession.PermissionDelegate.MediaCallback) {
                    val mic = audio?.firstOrNull()
                    if (mic != null) callback.grant(null as String?, mic.id) else callback.reject()
                }
                override fun onAndroidPermissionsRequest(session: GeckoSession, permissions: Array<out String>?, callback: GeckoSession.PermissionDelegate.Callback) {
                    val ok = permissions.orEmpty().all { ctx.checkSelfPermission(it) == android.content.pm.PackageManager.PERMISSION_GRANTED }
                    if (ok) callback.grant() else callback.reject()
                }
            }
            s.open(runtime)
            s.setActive(true)
            s.loadUri(ext.metaData.baseUrl + "call.html")
            session = s
        }, { e -> Diagnostics.log("Call page: $e"); info = "Não deu para preparar as chamadas: ${e?.message}"; GeckoResult.fromValue(null) })
    }

    private fun toPage(o: JSONObject) {
        main.post {
            val p = port
            if (p == null || !ready) { synchronized(queue) { queue += o } ; return@post }
            p.postMessage(o)
        }
    }

    private fun fromPage(m: JSONObject) {
        when (m.optString("type")) {
            "ready" -> { ready = true; main.post { val q = synchronized(queue) { queue.toList().also { queue.clear() } }; q.forEach { port?.postMessage(it) } } }
            "offer", "answer" -> peerId?.let { signal(it, JSONObject().put("type", m.getString("type")).put("sdp", m.getString("sdp"))) }
            "ice" -> peerId?.let { signal(it, JSONObject().put("type", "ice").put("candidate", m.optString("candidate")).put("mid", m.optString("mid")).put("index", m.optInt("index"))) }
            "state" -> when (m.optString("state")) {
                "connected" -> { if (state != State.ACTIVE) active() else { info = "Em chamada com $peerName"; changed() } }
                "disconnected" -> { info = "Reconectando…"; changed() }
                "failed" -> reset("Não deu para conectar com $peerName (a rede bloqueou a ligação direta)")
            }
            "frame" -> runCatching {
                val bytes = Base64.decode(m.getString("jpeg"), Base64.DEFAULT)
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { remoteScreen = it; remoteScreenSeq++; changed() }
            }
            "screenOff" -> { remoteScreen = null; remoteScreenSeq++; changed() }
            "error" -> { Diagnostics.log("Call page error: ${m.optString("text")}"); info = "Erro na chamada: ${m.optString("text")}"; changed() }
            "log" -> Diagnostics.log("Call page: ${m.optString("text")}")
        }
    }

    private fun signal(to: String, o: JSONObject) {
        val me = Social.me ?: return
        o.put("from", me.id).put("name", me.name).put("call", callId)
        Social.sb.realtime().sendTo("user:$to", "signal", o)
    }

    private fun micAllowed() = ctx.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) == android.content.pm.PackageManager.PERMISSION_GRANTED

    // ------------------------------------------------------------------ outgoing / incoming

    fun call(friend: Profile) = io.execute {
        if (state != State.IDLE) return@execute
        if (Social.me == null) { info = "Entre na sua conta em Pessoas"; changed(); return@execute }
        if (!micAllowed()) { info = "Permita o microfone (Configurações 2D > Permissões) para ligar"; changed(); return@execute }
        callId = UUID.randomUUID().toString()
        peerId = friend.id; peerName = friend.name; state = State.OUTGOING; info = "Chamando ${friend.name}…"
        runCatching { com.hyperxr.progress.ProgressStore.record(com.hyperxr.progress.Activity.MadeCall) }
        changed()
        audio(true)
        ensurePage()
        toPage(JSONObject().put("type", "call"))
        ringTimeout = io.schedule({ if (state == State.OUTGOING) { info = "${friend.name} não atendeu"; hangUp() } }, 45, TimeUnit.SECONDS)
    }

    /** From Social: a message on this user's channel. */
    fun onSignal(o: JSONObject) = io.execute {
        val from = o.optString("from")
        when (o.optString("type")) {
            "offer" -> when {
                state == State.IDLE -> {
                    callId = o.optString("call"); peerId = from; peerName = o.optString("name"); pendingOffer = o
                    state = State.INCOMING; info = "$peerName está te ligando"
                    ensurePage()
                    ringTimeout = io.schedule({ if (state == State.INCOMING) reset("Chamada perdida de $peerName") }, 45, TimeUnit.SECONDS)
                    changed()
                }
                from != peerId -> {
                    val saved = callId; callId = o.optString("call")
                    val me = Social.me
                    if (me != null) Social.sb.realtime().sendTo("user:$from", "signal", JSONObject().put("type", "busy").put("from", me.id).put("name", me.name).put("call", callId))
                    callId = saved
                }
                else -> Unit
            }
            "answer" -> if (from == peerId && o.optString("call") == callId) toPage(JSONObject().put("type", "answer").put("sdp", o.getString("sdp")))
            "ice" -> if (from == peerId && o.optString("call") == callId) toPage(JSONObject().put("type", "ice").put("candidate", o.getString("candidate")).put("mid", o.optString("mid")).put("index", o.optInt("index")))
            "busy" -> if (from == peerId) reset("$peerName está em outra chamada")
            "bye", "decline" -> if (from == peerId && o.optString("call") == callId) reset(if (o.optString("type") == "decline") "$peerName recusou" else "Chamada encerrada")
        }
    }

    fun accept() = io.execute {
        val offer = pendingOffer ?: return@execute
        if (!micAllowed()) { info = "Permita o microfone (Configurações 2D > Permissões) para atender"; changed(); return@execute }
        ringTimeout?.cancel(false)
        audio(true)
        info = "Conectando…"; changed()
        toPage(JSONObject().put("type", "offer").put("sdp", offer.getString("sdp")))
    }

    fun decline() = io.execute {
        peerId?.let { signal(it, JSONObject().put("type", "decline")) }
        reset("")
    }

    fun hangUp() = io.execute {
        peerId?.let { signal(it, JSONObject().put("type", "bye")) }
        reset("Chamada encerrada")
    }

    fun toggleMute() = io.execute { muted = !muted; toPage(JSONObject().put("type", "mute").put("on", muted)); changed() }

    private fun active() {
        ringTimeout?.cancel(false)
        state = State.ACTIVE; startedAtMs = System.currentTimeMillis(); info = "Em chamada com $peerName"
        changed()
    }

    private fun audio(call: Boolean) {
        val am = ctx.getSystemService(AudioManager::class.java) ?: return
        am.mode = if (call) AudioManager.MODE_IN_COMMUNICATION else AudioManager.MODE_NORMAL
        @Suppress("DEPRECATION")
        am.isSpeakerphoneOn = call
    }

    private fun reset(message: String) {
        ringTimeout?.cancel(false)
        if (sharing) { sharing = false; wantFrames = false }
        toPage(JSONObject().put("type", "hangup"))
        pendingOffer = null
        state = State.IDLE; peerId = null; muted = false; remoteScreen = null; remoteScreenSeq++
        info = message
        audio(false)
        changed()
    }

    // ------------------------------------------------------------------ screen sharing

    fun toggleShare() = io.execute {
        if (state != State.ACTIVE) return@execute
        sharing = !sharing
        wantFrames = sharing
        if (!sharing) toPage(JSONObject().put("type", "screenOff"))
        info = if (sharing) "Mostrando a sua tela para $peerName" else "Em chamada com $peerName"
        changed()
    }

    /** GL thread, while [wantFrames]: one 16:9 frame (the bitmap is ours to recycle). */
    fun offerFrame(frame: Bitmap) {
        if (!sharing || encoding) { frame.recycle(); return }
        encoding = true
        encoder.execute {
            try {
                val small = Bitmap.createScaledBitmap(frame, 960, 540, true)
                val out = ByteArrayOutputStream(64 * 1024)
                small.compress(Bitmap.CompressFormat.JPEG, 60, out)
                if (small !== frame) small.recycle()
                toPage(JSONObject().put("type", "frame").put("jpeg", Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)))
            } catch (t: Throwable) { Diagnostics.log("Screen frame: $t") }
            finally { frame.recycle(); encoding = false }
        }
    }
}
