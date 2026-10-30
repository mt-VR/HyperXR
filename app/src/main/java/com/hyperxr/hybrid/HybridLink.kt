package com.hyperxr.hybrid

import com.hyperxr.diagnostics.Diagnostics
import java.io.BufferedInputStream
import java.io.DataInputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * HybridMagic's connection to the PC (hybridmagic/ in the project; the wire format is in
 * hybridmagic/protocol.py). The PC broadcasts a beacon, this phone answers it, and the PC
 * connects to this phone: an outgoing connection on the PC, which the Windows firewall allows
 * without a rule, and no IP to type on either side.
 *
 * One request at a time: [request] sends a camera frame and waits for that frame's hands.
 */
object HybridLink {
    const val DISCOVERY_PORT = 9955
    const val TCP_PORT = 9956
    private const val VERSION = 1
    private const val HELLO: Byte = 1
    private const val INFO: Byte = 2
    private const val FRAME: Byte = 3
    private const val HANDS: Byte = 4
    const val JOINTS = 21

    /** One hand from the PC: joints in frame pixels (x, y per joint) and per-joint confidence. */
    class Hand(val presence: Float, val joints: FloatArray, val conf: FloatArray)
    class Reply(val seq: Int, val pcMs: Float, val hands: List<Hand>)

    @Volatile var pcName = ""; private set
    @Volatile var connected = false; private set
    @Volatile private var running = false
    private var name = "celular"
    private var socket: Socket? = null
    private var out: OutputStream? = null
    private val replies = LinkedBlockingQueue<Reply>(8)
    private var users = 0
    private var server: ServerSocket? = null
    private var discovery: DatagramSocket? = null

    /** Starts listening for the PC (idempotent; each start needs a [stop]). */
    @Synchronized fun start(phoneName: String) {
        users++
        if (running) return
        running = true
        name = phoneName
        Thread({ discoveryLoop() }, "hybrid-discovery").apply { isDaemon = true }.start()
        Thread({ acceptLoop() }, "hybrid-accept").apply { isDaemon = true }.start()
    }

    @Synchronized fun stop() {
        users = (users - 1).coerceAtLeast(0)
        if (users > 0 || !running) return
        running = false
        runCatching { discovery?.close() }
        runCatching { server?.close() }
        drop()
    }

    private fun discoveryLoop() {
        try {
            val ds = DatagramSocket(null).apply { reuseAddress = true; broadcast = true; soTimeout = 1000; bind(InetSocketAddress(DISCOVERY_PORT)) }
            discovery = ds
            val buf = ByteArray(256)
            while (running) {
                val p = DatagramPacket(buf, buf.size)
                try { ds.receive(p) } catch (_: SocketTimeoutException) { continue }
                if (!String(p.data, 0, p.length).startsWith("HYBRIDMAGIC-PC")) continue
                if (connected) continue
                val reply = "HYBRIDMAGIC-PHONE $VERSION $name".toByteArray()
                runCatching { ds.send(DatagramPacket(reply, reply.size, p.socketAddress)) }
            }
        } catch (t: Throwable) {
            if (running) Diagnostics.log("HybridMagic discovery: $t")
        }
    }

    private fun acceptLoop() {
        try {
            val ss = ServerSocket().apply { reuseAddress = true; bind(InetSocketAddress(TCP_PORT)) }
            server = ss
            while (running) {
                val s = ss.accept()
                adopt(s)
            }
        } catch (t: Throwable) {
            if (running) Diagnostics.log("HybridMagic accept: $t")
        }
    }

    @Synchronized private fun adopt(s: Socket) {
        drop()
        s.tcpNoDelay = true
        socket = s
        out = s.getOutputStream()
        connected = true
        Diagnostics.log("HybridMagic: PC connected from ${s.inetAddress.hostAddress}")
        val nm = name.toByteArray()
        send(HELLO, ByteBuffer.allocate(2 + nm.size).order(ByteOrder.LITTLE_ENDIAN).putShort(VERSION.toShort()).put(nm).array())
        Thread({ readLoop(s) }, "hybrid-read").apply { isDaemon = true }.start()
    }

    @Synchronized private fun drop() {
        runCatching { socket?.close() }
        socket = null; out = null
        if (connected) Diagnostics.log("HybridMagic: PC disconnected")
        connected = false; pcName = ""
    }

    private fun send(kind: Byte, payload: ByteArray): Boolean {
        val o = out ?: return false
        return try {
            val head = ByteBuffer.allocate(5).order(ByteOrder.LITTLE_ENDIAN).putInt(payload.size + 1).put(kind).array()
            synchronized(o) { o.write(head); o.write(payload); o.flush() }
            true
        } catch (t: Throwable) { drop(); false }
    }

    private fun readLoop(s: Socket) {
        try {
            val input = DataInputStream(BufferedInputStream(s.getInputStream(), 1 shl 15))
            val head = ByteArray(4)
            while (running && socket === s) {
                input.readFully(head)
                val len = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).int
                if (len <= 0 || len > (1 shl 20)) throw IllegalStateException("bad length $len")
                val body = ByteArray(len)
                input.readFully(body)
                val p = ByteBuffer.wrap(body, 1, len - 1).slice().order(ByteOrder.LITTLE_ENDIAN)
                when (body[0]) {
                    INFO -> { p.short; pcName = String(body, 3, len - 3) }
                    HANDS -> parseHands(p)?.let { replies.offer(it) }
                }
            }
        } catch (_: Throwable) {}
        synchronized(this) { if (socket === s) drop() }
    }

    private fun parseHands(p: ByteBuffer): Reply? {
        if (p.remaining() < 9) return null
        val seq = p.int; val ms = p.float; val n = p.get().toInt() and 0xff
        val hands = ArrayList<Hand>(n)
        repeat(n) {
            if (p.remaining() < 4 + JOINTS * 12) return null
            val presence = p.float
            val joints = FloatArray(JOINTS * 2); val conf = FloatArray(JOINTS)
            for (j in 0 until JOINTS) { joints[j * 2] = p.float; joints[j * 2 + 1] = p.float; conf[j] = p.float }
            hands += Hand(presence, joints, conf)
        }
        return Reply(seq, ms, hands)
    }

    /**
     * Sends one JPEG frame and waits up to [timeoutMs] for its hands; null when the PC is not
     * there or too slow (the caller then tracks on the phone).
     */
    fun request(seq: Int, width: Int, height: Int, jpeg: ByteArray, len: Int, timeoutMs: Long): Reply? {
        if (!connected) return null
        replies.clear()  // late answers to older frames
        val payload = ByteBuffer.allocate(8 + len).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(seq).putShort(width.toShort()).putShort(height.toShort()).put(jpeg, 0, len).array()
        if (!send(FRAME, payload)) return null
        val deadline = System.nanoTime() + timeoutMs * 1_000_000
        while (true) {
            val left = deadline - System.nanoTime()
            if (left <= 0) return null
            val r = replies.poll(left, TimeUnit.NANOSECONDS) ?: return null
            if (r.seq == seq) return r
        }
    }
}
