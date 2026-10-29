package com.hyperxr.steamvr

import android.content.Context
import android.graphics.SurfaceTexture
import android.net.wifi.WifiManager
import android.media.MediaCodec
import android.media.MediaFormat
import android.view.Surface
import com.hyperxr.diagnostics.Diagnostics
import java.io.DataInputStream
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.NoRouteToHostException
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * The phone end of SteamVR streaming: keeps one TCP connection to the HyperXR driver (dialing the
 * PC and accepting the PC's own connection, preferring the PC's), sends the hello and tracking,
 * and decodes the H.264 video into [surfaceTexture].
 *
 * The PC is also found without typing: the HyperMagic PC app and the driver broadcast a beacon on
 * UDP [DISCOVERY_PORT]; this phone answers it (so the PC learns this phone's address and dials it,
 * which the Windows firewall allows) and dials the beacon's sender too.
 */
class SteamVrClient(private val context: Context, private val pcIp: String, private val hello: HxvrProtocol.Hello, private val surfaceTexture: SurfaceTexture) {
    @Volatile var state = "Procurando o PC na rede…"; private set
    /** The PC whose beacon arrived, when one did. */
    @Volatile var foundPc: String? = null; private set
    @Volatile var connected = false; private set
    @Volatile var config: HxvrProtocol.Config? = null; private set
    @Volatile var framesDecoded = 0L; private set
    @Volatile var bytesReceived = 0L; private set
    var onHaptic: ((HxvrProtocol.Haptic) -> Unit)? = null

    /** Video header per frame id (the decoder's presentation time), for the reprojection. */
    val headers = ConcurrentHashMap<Long, HxvrProtocol.VideoHeader>()

    @Volatile private var running = true
    @Volatile private var socket: Socket? = null
    private var server: ServerSocket? = null
    private var discovery: DatagramSocket? = null
    private val pendingTracking = AtomicReference<ByteArray?>(null)
    private val sendLock = Object()
    private var decoder: MediaCodec? = null
    private var decoderSize = 0 to 0
    private val surface = Surface(surfaceTexture)

    fun start() {
        Thread({ dialLoop() }, "hxvr-dial").start()
        Thread({ acceptLoop() }, "hxvr-accept").start()
        Thread({ sendLoop() }, "hxvr-send").start()
        Thread({ discoveryLoop() }, "hxvr-discovery").start()
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        runCatching { discovery?.close() }
        runCatching { socket?.close() }
        synchronized(sendLock) { sendLock.notifyAll() }
        releaseDecoder()
        surface.release()
    }

    /** Latest tracking replaces any not yet sent: stale poses are worthless. */
    fun sendTracking(t: HxvrProtocol.Tracking) {
        pendingTracking.set(HxvrProtocol.tracking(t))
        synchronized(sendLock) { sendLock.notifyAll() }
    }

    fun requestKeyFrame() = send(HxvrProtocol.idrRequest())

    private fun send(msg: ByteArray) {
        val s = socket ?: return
        try { synchronized(s) { s.getOutputStream().write(msg) } } catch (_: Throwable) { drop(s) }
    }

    private fun sendLoop() {
        while (running) {
            val msg = pendingTracking.getAndSet(null)
            if (msg == null) { synchronized(sendLock) { if (pendingTracking.get() == null && running) sendLock.wait(50) }; continue }
            send(msg)
        }
    }

    // ---------------------------------------------------------------- connection
    private fun dialLoop() {
        while (running) {
            val targets = listOfNotNull(pcIp.trim().ifBlank { null }, foundPc).distinct()
            if (socket == null && targets.isEmpty()) state = "Procurando o PC na rede… Abra o HyperMagic PC no computador, no mesmo Wi-Fi deste celular."
            for (ip in targets) {
                if (socket != null) break
                try {
                    val s = Socket()
                    s.connect(InetSocketAddress(ip, HxvrProtocol.PORT), 1500)
                    adopt(s, fromPc = false)
                } catch (t: Throwable) {
                    if (socket == null) state = dialError(ip, t)
                }
            }
            Thread.sleep(1000)
        }
    }

    /** What the failed dial says about the cause, for the person to fix it. */
    private fun dialError(ip: String, t: Throwable): String {
        val msg = t.message.orEmpty()
        val found = foundPc != null
        return when {
            t is ConnectException && ("ECONNREFUSED" in msg || "refused" in msg) ->
                "O PC $ip respondeu, mas nada ouve na porta 9944: o SteamVR está fechado ou o driver HyperMagic não carregou (veja o HyperMagic PC)."
            t is SocketTimeoutException || "ETIMEDOUT" in msg -> if (found)
                "O PC $ip foi achado, mas o Firewall do Windows bloqueia a conexão. No HyperMagic PC, toque em Liberar no firewall; enquanto isso o PC tenta chamar este celular."
            else
                "Sem resposta de $ip: o PC está em outra rede, desligado, ou o Firewall do Windows bloqueia (HyperMagic PC > Liberar no firewall)."
            t is NoRouteToHostException || "EHOSTUNREACH" in msg || "ENETUNREACH" in msg ->
                "$ip não é alcançável daqui: o celular e o PC precisam estar no mesmo Wi-Fi (e o roteador sem isolamento de clientes)."
            else -> "Não conectou em $ip: ${t.javaClass.simpleName} $msg"
        }
    }

    /** Answers the PC's beacon with this phone's name and remembers where it came from. */
    private fun discoveryLoop() {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        // some Wi-Fi drivers drop broadcasts unless an app holds this lock
        val lock = runCatching { wifi?.createMulticastLock("hxvr-discovery")?.apply { setReferenceCounted(false); acquire() } }.getOrNull()
        try {
            val ds = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 1000; bind(InetSocketAddress(DISCOVERY_PORT)) }
            discovery = ds
            val buf = ByteArray(256)
            val reply = "HXVR-PHONE ${hello.name}".toByteArray()
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                try { ds.receive(p) } catch (_: SocketTimeoutException) { continue }
                if (!String(p.data, 0, p.length).startsWith("HXVR-PC")) continue
                val from = p.address.hostAddress ?: continue
                if (foundPc != from) { foundPc = from; Diagnostics.log("SteamVR: PC found at $from") }
                runCatching { ds.send(DatagramPacket(reply, reply.size, p.socketAddress)) }
            }
        } catch (t: Throwable) {
            if (running) Diagnostics.log("SteamVR discovery: $t")
        } finally {
            runCatching { lock?.release() }
        }
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket(HxvrProtocol.PORT).also { server = it }
            while (running) {
                val s = ss.accept()
                adopt(s, fromPc = true)
            }
        } catch (t: Throwable) {
            if (running) {
                Diagnostics.log("SteamVR accept: $t")
                state = "A porta 9944 deste celular está ocupada: feche e abra o SteamVR do HyperMagic de novo."
            }
        }
    }

    /** The PC's own connection wins over ours when both exist. */
    @Synchronized private fun adopt(s: Socket, fromPc: Boolean) {
        val current = socket
        if (current != null) {
            if (!fromPc) { s.close(); return }
            runCatching { current.close() }
        }
        s.tcpNoDelay = true
        s.receiveBufferSize = 4 shl 20
        socket = s
        connected = true
        state = "Conectado ao PC"
        s.getOutputStream().write(HxvrProtocol.hello(hello))
        Thread({ readLoop(s) }, "hxvr-read").start()
    }

    private fun drop(s: Socket) {
        runCatching { s.close() }
        synchronized(this) { if (socket === s) { socket = null; connected = false; state = "Conexão perdida; tentando de novo…" } }
    }

    private fun readLoop(s: Socket) {
        val input = DataInputStream(s.getInputStream().buffered(1 shl 16))
        val head = ByteArray(4)
        var buf = ByteArray(1 shl 16)
        try {
            while (running && socket === s) {
                input.readFully(head)
                val len = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).int
                if (len <= 0 || len > (16 shl 20)) throw IllegalStateException("bad length $len")
                if (buf.size < len) buf = ByteArray(len)
                input.readFully(buf, 0, len)
                bytesReceived += len + 4
                val p = ByteBuffer.wrap(buf, 1, len - 1).slice().order(ByteOrder.LITTLE_ENDIAN)
                when (buf[0]) {
                    HxvrProtocol.CONFIG -> HxvrProtocol.parseConfig(p)?.let { onConfig(it) }
                    HxvrProtocol.VIDEO -> HxvrProtocol.parseVideo(p)?.let { onVideo(it, p) }
                    HxvrProtocol.HAPTIC -> HxvrProtocol.parseHaptic(p)?.let { onHaptic?.invoke(it) }
                }
            }
        } catch (_: Throwable) {}
        drop(s)
    }

    private fun onConfig(c: HxvrProtocol.Config) {
        config = c
        state = if (c.restartPending) "O SteamVR está reiniciando para a tela deste celular…" else "Recebendo o SteamVR"
        if (c.restartPending) return
        val size = c.eyeWidth * 2 to c.eyeHeight
        if (decoder == null || decoderSize != size || decoderCodec != c.codec) startDecoder(size.first, size.second, c.codec)
    }

    // ---------------------------------------------------------------- video
    private var decoderCodec = -1

    private fun startDecoder(w: Int, h: Int, codec: Int) {
        releaseDecoder()
        val mime = SteamVrPlanner.mime(if (codec == SteamVrProfiles.CODEC_HEVC) codec else SteamVrProfiles.CODEC_H264)
        try {
            val fmt = MediaFormat.createVideoFormat(mime, w, h).apply {
                if (android.os.Build.VERSION.SDK_INT >= 30) setInteger(MediaFormat.KEY_LOW_LATENCY, 1)
                setInteger(MediaFormat.KEY_PRIORITY, 0)
                setInteger(MediaFormat.KEY_OPERATING_RATE, Short.MAX_VALUE.toInt())
                // vendor low-latency switches (ignored where unknown): Qualcomm, Samsung Exynos, MediaTek, Kirin
                setInteger("vendor.qti-ext-dec-low-latency.enable", 1)
                setInteger("vendor.qti-ext-dec-picture-order.enable", 1)
                setInteger("vendor.rtc-ext-dec-low-latency.enable", 1)
                setInteger("vendor.low-latency.enable", 1)
                setInteger("vdec-lowlatency", 1)
            }
            val name = SteamVrPlanner.decoder(mime, w, h, 60)
            val d = if (name != null) MediaCodec.createByCodecName(name) else MediaCodec.createDecoderByType(mime)
            d.configure(fmt, surface, null, 0)
            d.start()
            decoder = d; decoderSize = w to h; decoderCodec = codec
            waitingKey = true
            Thread({ drainLoop(d) }, "hxvr-decode").apply { priority = Thread.MAX_PRIORITY }.start()
            requestKeyFrame()
        } catch (t: Throwable) {
            Diagnostics.log("SteamVR decoder: $t")
            state = "Este celular não decodifica ${w}x$h (${if (codec == SteamVrProfiles.CODEC_HEVC) "HEVC" else "H.264"}); use o perfil Leve"
        }
    }

    private fun releaseDecoder() {
        val d = decoder ?: return
        decoder = null
        runCatching { d.stop() }; runCatching { d.release() }
    }

    private var waitingKey = true

    private fun onVideo(v: HxvrProtocol.VideoHeader, data: ByteBuffer) {
        val d = decoder ?: return
        // after a loss nothing decodes correctly until the next key frame
        if (waitingKey && !v.keyFrame) return
        waitingKey = false
        headers[v.frameId] = v
        if (headers.size > 120) headers.keys.removeAll { it < v.frameId - 60 }
        try {
            val i = d.dequeueInputBuffer(8_000)
            if (i < 0) { waitingKey = true; requestKeyFrame(); return }
            val buf = d.getInputBuffer(i)!!
            buf.clear()
            val n = data.remaining()
            if (n > buf.capacity()) { d.queueInputBuffer(i, 0, 0, 0, 0); waitingKey = true; requestKeyFrame(); return }
            buf.put(data)
            d.queueInputBuffer(i, 0, n, v.frameId, if (v.keyFrame) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0)
        } catch (t: Throwable) {
            Diagnostics.log("SteamVR decode input: $t")
            waitingKey = true
        }
    }

    /**
     * Shows only the newest decoded frame: when two are ready (Wi-Fi burst), the older one is
     * dropped instead of adding a frame of latency.
     */
    private fun drainLoop(d: MediaCodec) {
        val info = MediaCodec.BufferInfo()
        try {
            while (running && decoder === d) {
                var o = d.dequeueOutputBuffer(info, 20_000)
                if (o < 0) continue
                while (true) {
                    val next = d.dequeueOutputBuffer(info, 0)
                    if (next < 0) break
                    d.releaseOutputBuffer(o, false)
                    o = next
                }
                d.releaseOutputBuffer(o, true)
                framesDecoded++
            }
        } catch (_: Throwable) {}
    }

    companion object {
        /** UDP port of the PC's beacon (pc/common/discovery.h). */
        const val DISCOVERY_PORT = 9945
    }
}
