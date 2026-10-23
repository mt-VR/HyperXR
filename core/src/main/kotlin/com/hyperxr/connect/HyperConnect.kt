package com.hyperxr.connect

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * HyperConnect: a second phone as the controller, over Bluetooth (RFCOMM). The controller phone
 * sends its orientation (Android's gyroscope + accelerometer fusion), the raw gyroscope and
 * accelerometer, its touchscreen (touchpad and buttons) and volume keys; HyperXR answers with
 * vibrations. Little-endian frames: 'H' 'C' version type length(u16) payload.
 */
object HyperConnectProtocol {
    const val SERVICE_UUID = "6f1c2a52-8d4e-4b8a-9f33-487970657243"
    const val SERVICE_NAME = "HyperConnect"
    const val VERSION = 1

    const val TYPE_STATE = 1
    const val TYPE_HELLO = 2
    const val TYPE_VIBRATE = 3

    // buttons
    const val TRIGGER = 1; const val CLICK = 2; const val BACK = 4; const val MENU = 8
    const val HOME = 16; const val GRIP = 32; const val VOL_UP = 64; const val VOL_DOWN = 128

    class State(
        val seq: Int,
        val timestampNs: Long,
        val orientation: FloatArray,   // x, y, z, w: phone frame -> Android world (east, north, up)
        val gyro: FloatArray,          // rad/s, phone frame
        val accel: FloatArray,         // m/s², phone frame
        val buttons: Int,
        val touching: Boolean,
        val touchX: Float, val touchY: Float,  // 0..1 on the touchpad, y down
        val battery: Int,
    )
    class Hello(val name: String, val leftHanded: Boolean)
    class Vibrate(val ms: Int, val amplitude: Int)

    private const val HEADER = 6
    private const val STATE_BYTES = 2 + 8 + 16 + 12 + 12 + 2 + 1 + 8 + 1

    private fun frame(type: Int, payload: ByteArray): ByteArray = ByteBuffer.allocate(HEADER + payload.size).order(ByteOrder.LITTLE_ENDIAN)
        .put('H'.code.toByte()).put('C'.code.toByte()).put(VERSION.toByte()).put(type.toByte()).putShort(payload.size.toShort()).put(payload).array()

    fun encode(s: State): ByteArray {
        val b = ByteBuffer.allocate(STATE_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        b.putShort(s.seq.toShort()).putLong(s.timestampNs)
        for (i in 0..3) b.putFloat(s.orientation[i])
        for (i in 0..2) b.putFloat(s.gyro[i])
        for (i in 0..2) b.putFloat(s.accel[i])
        b.putShort(s.buttons.toShort()).put(if (s.touching) 1 else 0).putFloat(s.touchX).putFloat(s.touchY).put(s.battery.coerceIn(0, 100).toByte())
        return frame(TYPE_STATE, b.array())
    }

    fun encode(h: Hello): ByteArray {
        val name = h.name.toByteArray(Charsets.UTF_8).take(60).toByteArray()
        return frame(TYPE_HELLO, ByteArrayOutputStream().apply { write(if (h.leftHanded) 1 else 0); write(name) }.toByteArray())
    }

    fun encode(v: Vibrate): ByteArray = frame(TYPE_VIBRATE, ByteBuffer.allocate(3).order(ByteOrder.LITTLE_ENDIAN)
        .putShort(v.ms.coerceIn(0, 2000).toShort()).put(v.amplitude.coerceIn(1, 255).toByte()).array())

    /** Splits a byte stream into messages, skipping garbage until the next "HC" header. */
    class Decoder {
        private var buf = ByteArray(1024)
        private var len = 0

        fun feed(data: ByteArray, n: Int = data.size): List<Any> {
            if (len + n > buf.size) buf = buf.copyOf(maxOf(buf.size * 2, len + n))
            System.arraycopy(data, 0, buf, len, n); len += n
            val out = ArrayList<Any>()
            var p = 0
            while (len - p >= HEADER) {
                if (buf[p] != 'H'.code.toByte() || buf[p + 1] != 'C'.code.toByte()) { p++; continue }
                val size = (buf[p + 4].toInt() and 0xff) or ((buf[p + 5].toInt() and 0xff) shl 8)
                if (size > 4096) { p++; continue }
                if (len - p < HEADER + size) break
                val type = buf[p + 3].toInt()
                val bb = ByteBuffer.wrap(buf, p + HEADER, size).order(ByteOrder.LITTLE_ENDIAN)
                runCatching { decode(type, bb, size) }.getOrNull()?.let { out += it }
                p += HEADER + size
            }
            System.arraycopy(buf, p, buf, 0, len - p); len -= p
            return out
        }

        private fun decode(type: Int, b: ByteBuffer, size: Int): Any? = when (type) {
            TYPE_STATE -> if (size < STATE_BYTES) null else State(
                seq = b.short.toInt() and 0xffff, timestampNs = b.long,
                orientation = FloatArray(4) { b.float }, gyro = FloatArray(3) { b.float }, accel = FloatArray(3) { b.float },
                buttons = b.short.toInt() and 0xffff, touching = b.get().toInt() != 0, touchX = b.float, touchY = b.float,
                battery = b.get().toInt() and 0xff)
            TYPE_HELLO -> { val left = b.get().toInt() != 0; val bytes = ByteArray(size - 1); b.get(bytes); Hello(String(bytes, Charsets.UTF_8), left) }
            TYPE_VIBRATE -> Vibrate(b.short.toInt() and 0xffff, b.get().toInt() and 0xff)
            else -> null
        }
    }
}

/**
 * The controller phone as a 3DoF pointer: its orientation turned into HyperXR's frame (+Y up,
 * -Z ahead), pointing out of the phone's top edge, its heading aligned with the head at connect
 * time and whenever HOME is held; the ray starts where the hand holding it is (a simple arm
 * model), and dragging on the touchpad scrolls.
 */
class PhoneControllerModel {
    var leftHanded = false
    var recenterHoldNs = 700_000_000L

    /** Results of the last [update]. */
    val rayOrigin = FloatArray(3)
    val rayDirection = floatArrayOf(0f, 0f, -1f)
    val orientation = floatArrayOf(0f, 0f, 0f, 1f)
    var select = false; private set
    var back = false; private set
    var menu = false; private set
    var grip = false; private set
    /** -1..1 like a stick: finger moving up the pad = negative (natural scrolling). */
    var scroll = 0f; private set
    /** HOME tapped (not held): the view's recenter. */
    var homeTapped = false; private set
    var recentered = false; private set

    private var yaw = floatArrayOf(0f, 0f, 0f, 1f)
    private var aligned = false
    private var homeSince = -1L
    private var homeUsed = false
    private var lastTouchY = Float.NaN
    private var lastNs = 0L

    /** Android world (east, north, up) -> HyperXR world (x east, y up, z south). */
    private val world = Q.axis(1f, 0f, 0f, (-PI / 2).toFloat())
    /** Controller frame (-Z out of the phone's top, +Y out of the screen) -> phone frame. */
    private val grip0 = Q.axis(1f, 0f, 0f, (PI / 2).toFloat())

    fun reset() { aligned = false; homeSince = -1; lastTouchY = Float.NaN; lastNs = 0L }

    fun update(s: HyperConnectProtocol.State, headPos: FloatArray, headQ: FloatArray, nowNs: Long) {
        recentered = false; homeTapped = false
        val raw = Q.mul(Q.mul(world, Q.norm(s.orientation)), grip0)
        if (!aligned) { align(raw, headQ); aligned = true }

        val home = s.buttons and HyperConnectProtocol.HOME != 0
        if (home) {
            if (homeSince < 0) { homeSince = nowNs; homeUsed = false }
            if (!homeUsed && nowNs - homeSince >= recenterHoldNs) { align(raw, headQ); homeUsed = true }
        } else if (homeSince >= 0) { if (!homeUsed) homeTapped = true; homeSince = -1 }

        val q = Q.norm(Q.mul(yaw, raw))
        System.arraycopy(q, 0, orientation, 0, 4)
        val d = Q.rotate(q, floatArrayOf(0f, 0f, -1f))
        System.arraycopy(d, 0, rayDirection, 0, 3)
        // the hand: beside and below the head (heading only), then a short forearm along the ray
        val side = if (leftHanded) -1f else 1f
        val body = Q.rotate(Q.yawOnly(headQ), floatArrayOf(0.18f * side, -0.42f, -0.12f))
        for (i in 0..2) rayOrigin[i] = headPos[i] + body[i] + d[i] * 0.18f

        select = s.buttons and (HyperConnectProtocol.TRIGGER or HyperConnectProtocol.CLICK or HyperConnectProtocol.VOL_DOWN) != 0
        back = s.buttons and HyperConnectProtocol.BACK != 0
        menu = s.buttons and HyperConnectProtocol.MENU != 0
        grip = s.buttons and (HyperConnectProtocol.GRIP or HyperConnectProtocol.VOL_UP) != 0

        val dt = if (lastNs == 0L) 0f else ((nowNs - lastNs) / 1e9f).coerceIn(1e-3f, 0.2f)
        lastNs = nowNs
        scroll = if (s.touching && !lastTouchY.isNaN() && dt > 0f) ((s.touchY - lastTouchY) / dt * 0.9f).coerceIn(-1f, 1f) else 0f
        lastTouchY = if (s.touching) s.touchY else Float.NaN
    }

    /** Turns the controller's heading to the head's heading. */
    private fun align(raw: FloatArray, headQ: FloatArray) {
        val c = heading(raw); val h = heading(headQ)
        yaw = Q.axis(0f, 1f, 0f, h - c)
        recentered = true
    }

    private fun heading(q: FloatArray): Float { val f = Q.rotate(q, floatArrayOf(0f, 0f, -1f)); return atan2(-f[0], -f[2]) }
}

/** Quaternion helpers on (x, y, z, w). */
internal object Q {
    fun mul(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2])
    fun norm(q: FloatArray): FloatArray { val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]); return if (n < 1e-9f) floatArrayOf(0f, 0f, 0f, 1f) else floatArrayOf(q[0] / n, q[1] / n, q[2] / n, q[3] / n) }
    fun conj(q: FloatArray) = floatArrayOf(-q[0], -q[1], -q[2], q[3])
    fun rotate(q: FloatArray, v: FloatArray): FloatArray { val p = mul(mul(q, floatArrayOf(v[0], v[1], v[2], 0f)), conj(q)); return floatArrayOf(p[0], p[1], p[2]) }
    fun axis(x: Float, y: Float, z: Float, a: Float): FloatArray { val s = sin(a / 2); return floatArrayOf(x * s, y * s, z * s, cos(a / 2)) }
    fun yawOnly(q: FloatArray): FloatArray { val f = rotate(q, floatArrayOf(0f, 0f, -1f)); return axis(0f, 1f, 0f, atan2(-f[0], -f[2])) }
}
