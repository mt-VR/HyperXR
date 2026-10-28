package com.hyperxr.social

import android.content.Context
import android.graphics.Bitmap
import android.media.AudioManager
import android.view.Surface
import com.hyperxr.diagnostics.Diagnostics
import org.json.JSONObject
import org.webrtc.AudioTrack
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.SurfaceTextureHelper
import org.webrtc.VideoSink
import org.webrtc.VideoSource
import org.webrtc.VideoTrack
import org.webrtc.audio.JavaAudioDeviceModule
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Voice calls and screen sharing between HyperMagic users. Signalling (offer, answer, ICE, hang
 * up) goes over each user's Supabase Realtime channel; audio and video go straight between the
 * phones with WebRTC (STUN only: on some mobile networks a direct path can't be found and the call
 * fails to connect, which needs a TURN relay server). The shared screen is HyperMagic's 16:9
 * spectator view of the scene (the same one recordings use), so no Android screen-capture prompt.
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

    /** The XR renderer: draws the spectator view into [Surface]s it is given (GL thread work inside). */
    interface ScreenOutput {
        fun attach(surface: Surface, width: Int, height: Int)
        fun detach(surface: Surface)
    }
    @Volatile var screenOutput: ScreenOutput? = null

    private lateinit var ctx: Context
    private val io = Executors.newSingleThreadScheduledExecutor()
    private val listeners = CopyOnWriteArrayList<() -> Unit>()
    private var factory: PeerConnectionFactory? = null
    private var egl: EglBase? = null
    private var pc: PeerConnection? = null
    private var audio: AudioTrack? = null
    private var callId = ""
    private var pendingOffer: JSONObject? = null
    private val pendingIce = ArrayList<IceCandidate>()
    private var remoteSet = false
    private var screenSource: VideoSource? = null
    private var screenTrack: VideoTrack? = null
    private var screenHelper: SurfaceTextureHelper? = null
    private var screenSurface: Surface? = null
    private var ringTimeout: java.util.concurrent.ScheduledFuture<*>? = null

    fun init(c: Context) { ctx = c }
    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }
    private fun changed() = listeners.forEach { runCatching { it() } }

    private fun factory(): PeerConnectionFactory {
        factory?.let { return it }
        PeerConnectionFactory.initialize(PeerConnectionFactory.InitializationOptions.builder(ctx).createInitializationOptions())
        val e = EglBase.create().also { egl = it }
        return PeerConnectionFactory.builder()
            .setVideoEncoderFactory(DefaultVideoEncoderFactory(e.eglBaseContext, true, true))
            .setVideoDecoderFactory(DefaultVideoDecoderFactory(e.eglBaseContext))
            .setAudioDeviceModule(JavaAudioDeviceModule.builder(ctx).setUseHardwareAcousticEchoCanceler(true).setUseHardwareNoiseSuppressor(true).createAudioDeviceModule())
            .createPeerConnectionFactory().also { factory = it }
    }

    private fun signal(to: String, o: JSONObject) {
        val me = Social.me ?: return
        o.put("from", me.id).put("name", me.name).put("call", callId)
        Social.sb.realtime().sendTo("user:$to", "signal", o)
    }

    // ------------------------------------------------------------------ outgoing / incoming

    fun call(friend: Profile) = io.execute {
        if (state != State.IDLE) return@execute
        if (Social.me == null) { info = "Entre na sua conta em Pessoas"; changed(); return@execute }
        callId = UUID.randomUUID().toString()
        peerId = friend.id; peerName = friend.name; state = State.OUTGOING; info = "Chamando ${friend.name}…"
        runCatching { com.hyperxr.progress.ProgressStore.record(com.hyperxr.progress.Activity.MadeCall) }
        changed()
        try {
            val p = newPeer(friend.id)
            p.addTransceiver(MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO, RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.SEND_RECV))
            p.createOffer(created { offer ->
                p.setLocalDescription(applied { signal(friend.id, JSONObject().put("type", "offer").put("sdp", offer.description)) }, offer)
            }, MediaConstraints())
            ringTimeout = io.schedule({ if (state == State.OUTGOING) { info = "${friend.name} não atendeu"; hangUp() } }, 45, TimeUnit.SECONDS)
        } catch (t: Throwable) { fail(t) }
    }

    /** From Social: a message on this user's channel. */
    fun onSignal(o: JSONObject) = io.execute {
        val from = o.optString("from")
        when (o.optString("type")) {
            "offer" -> when {
                state == State.IDLE -> {
                    callId = o.optString("call"); peerId = from; peerName = o.optString("name"); pendingOffer = o
                    state = State.INCOMING; info = "${peerName} está te ligando"
                    ringTimeout = io.schedule({ if (state == State.INCOMING) reset("Chamada perdida de $peerName") }, 45, TimeUnit.SECONDS)
                    changed()
                }
                from != peerId -> {
                    // already in a call: tell them we're busy
                    val saved = callId; callId = o.optString("call")
                    signal(from, JSONObject().put("type", "busy")); callId = saved
                }
                else -> Unit
            }
            "answer" -> if (from == peerId && o.optString("call") == callId) {
                pc?.setRemoteDescription(applied { remoteSet = true; flushIce(); active() }, SessionDescription(SessionDescription.Type.ANSWER, o.getString("sdp")))
            }
            "ice" -> if (from == peerId && o.optString("call") == callId) {
                val c = IceCandidate(o.optString("mid"), o.optInt("index"), o.getString("candidate"))
                if (remoteSet) pc?.addIceCandidate(c) else synchronized(pendingIce) { pendingIce += c }
            }
            "busy" -> if (from == peerId) reset("$peerName está em outra chamada")
            "bye", "decline" -> if (from == peerId && o.optString("call") == callId) reset(if (o.optString("type") == "decline") "$peerName recusou" else "Chamada encerrada")
            "screen" -> if (from == peerId) { if (!o.optBoolean("on")) { remoteScreen = null; remoteScreenSeq++ }; changed() }
        }
    }

    fun accept() = io.execute {
        val offer = pendingOffer ?: return@execute
        val from = peerId ?: return@execute
        ringTimeout?.cancel(false)
        try {
            val p = newPeer(from)
            p.setRemoteDescription(applied {
                remoteSet = true; flushIce()
                // our side of the video line may send too (screen sharing without renegotiating)
                p.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.direction = RtpTransceiver.RtpTransceiverDirection.SEND_RECV
                p.createAnswer(created { answer ->
                    p.setLocalDescription(applied { signal(from, JSONObject().put("type", "answer").put("sdp", answer.description)); active() }, answer)
                }, MediaConstraints())
            }, SessionDescription(SessionDescription.Type.OFFER, offer.getString("sdp")))
        } catch (t: Throwable) { fail(t) }
    }

    fun decline() = io.execute {
        peerId?.let { signal(it, JSONObject().put("type", "decline")) }
        reset("")
    }

    fun hangUp() = io.execute {
        peerId?.let { signal(it, JSONObject().put("type", "bye")) }
        reset("Chamada encerrada")
    }

    fun toggleMute() = io.execute { muted = !muted; audio?.setEnabled(!muted); changed() }

    private fun active() {
        if (state == State.ACTIVE) return
        ringTimeout?.cancel(false)
        state = State.ACTIVE; startedAtMs = System.currentTimeMillis(); info = "Em chamada com $peerName"
        changed()
    }

    private fun newPeer(to: String): PeerConnection {
        val f = factory()
        val am = ctx.getSystemService(AudioManager::class.java)
        am?.mode = AudioManager.MODE_IN_COMMUNICATION
        @Suppress("DEPRECATION")
        if (am != null) am.isSpeakerphoneOn = true
        val cfg = PeerConnection.RTCConfiguration(listOf(
            PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer(),
            PeerConnection.IceServer.builder("stun:stun.cloudflare.com:3478").createIceServer(),
        )).apply { sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN }
        val p = f.createPeerConnection(cfg, object : PeerConnection.Observer {
            override fun onIceCandidate(c: IceCandidate) {
                signal(to, JSONObject().put("type", "ice").put("candidate", c.sdp).put("mid", c.sdpMid).put("index", c.sdpMLineIndex))
            }
            override fun onConnectionChange(s: PeerConnection.PeerConnectionState) {
                if (s == PeerConnection.PeerConnectionState.FAILED) io.execute { reset("Não deu para conectar com $peerName (rede bloqueou a ligação direta)") }
                if (s == PeerConnection.PeerConnectionState.DISCONNECTED) { info = "Reconectando…"; changed() }
                if (s == PeerConnection.PeerConnectionState.CONNECTED) { info = "Em chamada com $peerName"; changed() }
            }
            override fun onTrack(t: RtpTransceiver) {
                (t.receiver.track() as? VideoTrack)?.addSink(screenSink)
            }
            override fun onSignalingChange(s: PeerConnection.SignalingState) {}
            override fun onIceConnectionChange(s: PeerConnection.IceConnectionState) {}
            override fun onIceConnectionReceivingChange(b: Boolean) {}
            override fun onIceGatheringChange(s: PeerConnection.IceGatheringState) {}
            override fun onIceCandidatesRemoved(c: Array<out IceCandidate>) {}
            override fun onAddStream(s: MediaStream) {}
            override fun onRemoveStream(s: MediaStream) {}
            override fun onDataChannel(d: DataChannel) {}
            override fun onRenegotiationNeeded() {}
            override fun onAddTrack(r: RtpReceiver, s: Array<out MediaStream>) {}
        }) ?: error("WebRTC não abriu")
        val src = f.createAudioSource(MediaConstraints())
        audio = f.createAudioTrack("hx-audio", src).also { p.addTrack(it, listOf("hx")) }
        pc = p; remoteSet = false
        return p
    }

    private fun flushIce() {
        val list = synchronized(pendingIce) { pendingIce.toList().also { pendingIce.clear() } }
        list.forEach { pc?.addIceCandidate(it) }
    }

    private fun fail(t: Throwable) {
        Diagnostics.log("Call: $t")
        reset("Erro na chamada: ${t.message}")
    }

    private fun reset(message: String) {
        ringTimeout?.cancel(false)
        stopShareInternal()
        runCatching { pc?.close() }; pc = null; audio = null
        pendingOffer = null; synchronized(pendingIce) { pendingIce.clear() }; remoteSet = false
        state = State.IDLE; peerId = null; muted = false; remoteScreen = null; remoteScreenSeq++
        info = message
        ctx.getSystemService(AudioManager::class.java)?.mode = AudioManager.MODE_NORMAL
        changed()
    }

    // ------------------------------------------------------------------ screen sharing

    fun toggleShare() = io.execute { if (sharing) { stopShareInternal(); changed() } else startShare() }

    private fun startShare() {
        val p = pc ?: return
        val out = screenOutput ?: run { info = "Abra o HyperMagic no modo XR para mostrar a tela"; changed(); return }
        val f = factory()
        val w = 1280; val h = 720
        val helper = SurfaceTextureHelper.create("hx-screen", egl!!.eglBaseContext) ?: return
        helper.setTextureSize(w, h)
        val src = f.createVideoSource(true)
        src.capturerObserver.onCapturerStarted(true)
        helper.startListening { frame -> src.capturerObserver.onFrameCaptured(frame) }
        val track = f.createVideoTrack("hx-screen", src)
        val sender = p.transceivers.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.sender
        if (sender == null) { helper.dispose(); src.dispose(); info = "Esta chamada não tem vídeo"; changed(); return }
        sender.setTrack(track, false)
        val surface = Surface(helper.surfaceTexture)
        out.attach(surface, w, h)
        screenHelper = helper; screenSource = src; screenTrack = track; screenSurface = surface
        sharing = true
        peerId?.let { signal(it, JSONObject().put("type", "screen").put("on", true)) }
        changed()
    }

    private fun stopShareInternal() {
        if (!sharing) return
        screenSurface?.let { s -> screenOutput?.detach(s) }
        pc?.transceivers?.firstOrNull { it.mediaType == MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO }?.sender?.setTrack(null, false)
        // the renderer lets go of the surface on its next frame before it is destroyed
        val helper = screenHelper; val src = screenSource; val track = screenTrack; val surface = screenSurface
        io.schedule({ runCatching { helper?.stopListening(); track?.dispose(); src?.dispose(); helper?.dispose(); surface?.release() } }, 300, TimeUnit.MILLISECONDS)
        screenHelper = null; screenSource = null; screenTrack = null; screenSurface = null
        sharing = false
        peerId?.let { signal(it, JSONObject().put("type", "screen").put("on", false)) }
    }

    /** The other side's shared screen as bitmaps, up to ~12 per second, for the XR panel. */
    private val screenSink = object : VideoSink {
        private var last = 0L
        private var bmp: Bitmap? = null
        override fun onFrame(frame: org.webrtc.VideoFrame) {
            val now = System.nanoTime()
            if (now - last < 80_000_000L) return
            last = now
            val i420 = frame.buffer.toI420() ?: return
            try {
                val w = i420.width; val h = i420.height
                val b = bmp?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                val ok = com.hyperxr.handtracking.YuvNative.available && com.hyperxr.handtracking.YuvNative.convert(
                    i420.dataY, i420.dataU, i420.dataV, i420.strideY, 1, i420.strideU, 1, 1, frame.rotation == 180, b)
                if (ok) {
                    // two bitmaps alternate so the panel never reads one while it is written
                    bmp = remoteScreen
                    remoteScreen = b; remoteScreenSeq++
                    changed()
                }
            } finally { i420.release() }
        }
    }

    // ------------------------------------------------------------------ helpers

    private inline fun created(crossinline onCreated: (SessionDescription) -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) { io.execute { onCreated(d) } }
        override fun onSetSuccess() {}
        override fun onCreateFailure(e: String) { io.execute { fail(IllegalStateException(e)) } }
        override fun onSetFailure(e: String) { io.execute { fail(IllegalStateException(e)) } }
    }

    private inline fun applied(crossinline onSet: () -> Unit) = object : SdpObserver {
        override fun onCreateSuccess(d: SessionDescription) {}
        override fun onSetSuccess() { io.execute { onSet() } }
        override fun onCreateFailure(e: String) { io.execute { fail(IllegalStateException(e)) } }
        override fun onSetFailure(e: String) { io.execute { fail(IllegalStateException(e)) } }
    }
}
