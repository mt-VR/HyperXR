package com.hyperxr.steamvr

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Wire format between the phone and the HyperXR SteamVR driver on the PC (mirrored in
 * pc/common/hxvr_protocol.h). One TCP connection, little endian, each message framed as
 * u32 length (type byte + payload) + u8 type + payload. The PC normally opens the connection
 * (Windows firewall allows outgoing connections without prompts); the phone also tries, and
 * both sides keep the PC-initiated one when two exist.
 */
object HxvrProtocol {
    const val PORT = 9944
    const val MAGIC = 0x52565848  // "HXVR"
    const val VERSION = 1

    const val HELLO: Byte = 1
    const val CONFIG: Byte = 2
    const val VIDEO: Byte = 3
    const val HAPTIC: Byte = 4
    const val IDR_REQUEST: Byte = 5
    const val TRACKING: Byte = 10

    /** Controller button bits (Quest Touch layout: primary = A/X, secondary = B/Y). */
    const val BTN_PRIMARY = 1 shl 0
    const val BTN_SECONDARY = 1 shl 1
    const val BTN_MENU = 1 shl 4
    const val BTN_SYSTEM = 1 shl 5
    const val BTN_STICK_CLICK = 1 shl 6
    const val BTN_STICK_TOUCH = 1 shl 7
    const val BTN_TRIGGER_TOUCH = 1 shl 8
    const val BTN_GRIP_TOUCH = 1 shl 9
    const val BTN_THUMBREST_TOUCH = 1 shl 10

    /** Controller flags. */
    const val CTRL_CONNECTED = 1
    const val CTRL_HAND_TRACKING = 2
    const val CTRL_POSE_VALID = 4

    data class Hello(val eyeWidth: Int, val eyeHeight: Int, val refreshHz: Int, val tanLeft: Float, val tanRight: Float,
                     val tanUp: Float, val tanDown: Float, val ipd: Float, val name: String, val version: Int = VERSION)

    data class Config(val eyeWidth: Int, val eyeHeight: Int, val refreshHz: Int, val bitrateKbps: Int, val codec: Int, val restartPending: Boolean)

    data class VideoHeader(val frameId: Long, val trackingNs: Long, val qx: Float, val qy: Float, val qz: Float, val qw: Float, val keyFrame: Boolean)

    data class Haptic(val hand: Int, val duration: Float, val frequency: Float, val amplitude: Float)

    class Controller {
        var flags = 0
        val pose = FloatArray(7).also { it[6] = 1f }  // px py pz qx qy qz qw
        var buttons = 0
        var trigger = 0f
        var grip = 0f
        var joyX = 0f
        var joyY = 0f
        val curls = FloatArray(5)  // thumb, index, middle, ring, pinky: 0 open .. 1 closed
    }

    class Tracking {
        var timeNs = 0L
        val head = FloatArray(7).also { it[6] = 1f }
        val controllers = arrayOf(Controller(), Controller())
    }

    const val TRACKING_SIZE = 8 + 28 + 2 * (1 + 28 + 4 + 16 + 20)

    private fun buf(n: Int) = ByteBuffer.allocate(5 + n).order(ByteOrder.LITTLE_ENDIAN)
    private fun ByteBuffer.head(type: Byte, n: Int): ByteBuffer { putInt(n + 1); put(type); return this }

    fun hello(h: Hello): ByteArray {
        val name = h.name.toByteArray(Charsets.UTF_8).take(255).toByteArray()
        val n = 4 + 2 + 2 + 2 + 2 + 4 * 5 + 1 + name.size
        return buf(n).head(HELLO, n).apply {
            putInt(MAGIC); putShort(h.version.toShort()); putShort(h.eyeWidth.toShort()); putShort(h.eyeHeight.toShort()); putShort(h.refreshHz.toShort())
            putFloat(h.tanLeft); putFloat(h.tanRight); putFloat(h.tanUp); putFloat(h.tanDown); putFloat(h.ipd)
            put(name.size.toByte()); put(name)
        }.array()
    }

    fun tracking(t: Tracking): ByteArray = buf(TRACKING_SIZE).head(TRACKING, TRACKING_SIZE).apply {
        putLong(t.timeNs)
        t.head.forEach { putFloat(it) }
        for (c in t.controllers) {
            put(c.flags.toByte()); c.pose.forEach { putFloat(it) }; putInt(c.buttons)
            putFloat(c.trigger); putFloat(c.grip); putFloat(c.joyX); putFloat(c.joyY)
            c.curls.forEach { putFloat(it) }
        }
    }.array()

    fun idrRequest(): ByteArray = buf(0).head(IDR_REQUEST, 0).array()

    // ---- the PC side's messages (decoded on the phone; encoders kept for tests and tools)
    fun config(c: Config): ByteArray = buf(12).head(CONFIG, 12).apply {
        putShort(c.eyeWidth.toShort()); putShort(c.eyeHeight.toShort()); putShort(c.refreshHz.toShort()); putInt(c.bitrateKbps)
        put(c.codec.toByte()); put(if (c.restartPending) 1 else 0)
    }.array()

    fun video(v: VideoHeader, data: ByteArray): ByteArray {
        val n = 8 + 8 + 16 + 1 + data.size
        return buf(n).head(VIDEO, n).apply {
            putLong(v.frameId); putLong(v.trackingNs); putFloat(v.qx); putFloat(v.qy); putFloat(v.qz); putFloat(v.qw)
            put(if (v.keyFrame) 1 else 0); put(data)
        }.array()
    }

    fun parseHello(p: ByteBuffer): Hello? {
        p.order(ByteOrder.LITTLE_ENDIAN)
        if (p.remaining() < 33 || p.int != MAGIC) return null
        val version = p.short.toInt(); val w = p.short.toInt() and 0xffff; val h = p.short.toInt() and 0xffff; val hz = p.short.toInt() and 0xffff
        val l = p.float; val r = p.float; val u = p.float; val d = p.float; val ipd = p.float
        val len = p.get().toInt() and 0xff
        val name = ByteArray(minOf(len, p.remaining())).also { p.get(it) }
        return Hello(w, h, hz, l, r, u, d, ipd, String(name, Charsets.UTF_8), version)
    }

    fun parseTracking(p: ByteBuffer): Tracking? {
        p.order(ByteOrder.LITTLE_ENDIAN)
        if (p.remaining() < TRACKING_SIZE) return null
        return Tracking().apply {
            timeNs = p.long
            for (i in 0 until 7) head[i] = p.float
            for (c in controllers) {
                c.flags = p.get().toInt() and 0xff
                for (i in 0 until 7) c.pose[i] = p.float
                c.buttons = p.int
                c.trigger = p.float; c.grip = p.float; c.joyX = p.float; c.joyY = p.float
                for (i in 0 until 5) c.curls[i] = p.float
            }
        }
    }

    fun parseConfig(p: ByteBuffer): Config? {
        p.order(ByteOrder.LITTLE_ENDIAN)
        if (p.remaining() < 12) return null
        return Config(p.short.toInt() and 0xffff, p.short.toInt() and 0xffff, p.short.toInt() and 0xffff, p.int, p.get().toInt(), p.get().toInt() != 0)
    }

    /** Video header; the H.264 data is what remains in [p] afterwards. */
    fun parseVideo(p: ByteBuffer): VideoHeader? {
        p.order(ByteOrder.LITTLE_ENDIAN)
        if (p.remaining() < 33) return null
        return VideoHeader(p.long, p.long, p.float, p.float, p.float, p.float, p.get().toInt() != 0)
    }

    fun parseHaptic(p: ByteBuffer): Haptic? {
        p.order(ByteOrder.LITTLE_ENDIAN)
        if (p.remaining() < 13) return null
        return Haptic(p.get().toInt(), p.float, p.float, p.float)
    }
}
