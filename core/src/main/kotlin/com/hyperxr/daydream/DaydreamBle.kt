package com.hyperxr.daydream

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The Daydream controller's Bluetooth LE report, so a phone can stand in for one: Google VR
 * Services pairs with a peripheral that advertises service 0xFE55 and notifies 20-byte reports on
 * characteristic 00000001-1000-1000-8000-00805f9b34fb. Layout (from the public reverse
 * engineering in mrdoob's daydream-controller.js), most significant bit first: time (9 bits),
 * sequence (5), orientation x/y/z, acceleration x/y/z, gyroscope x/y/z (13-bit signed each),
 * touch x/y (8 bits each), then the buttons in the low 5 bits of byte 18.
 */
object DaydreamBle {
    const val SERVICE = "0000fe55-0000-1000-8000-00805f9b34fb"
    const val REPORT = "00000001-1000-1000-8000-00805f9b34fb"
    const val CLIENT_CONFIG = "00002902-0000-1000-8000-00805f9b34fb"
    const val DEVICE_INFO = "0000180a-0000-1000-8000-00805f9b34fb"
    const val MANUFACTURER = "00002a29-0000-1000-8000-00805f9b34fb"
    const val MODEL = "00002a24-0000-1000-8000-00805f9b34fb"
    const val BATTERY_SERVICE = "0000180f-0000-1000-8000-00805f9b34fb"
    const val BATTERY_LEVEL = "00002a19-0000-1000-8000-00805f9b34fb"
    const val NAME = "Daydream controller"

    const val CLICK = 0x01; const val HOME = 0x02; const val APP = 0x04; const val VOL_DOWN = 0x08; const val VOL_UP = 0x10

    const val ORI_SCALE = (2 * PI / 4095.0).toFloat()
    const val ACC_SCALE = (8 * 9.8 / 4095.0).toFloat()
    const val GYRO_SCALE = (2048.0 / 180.0 * PI / 4095.0).toFloat()

    /** One report: orientation as a rotation vector (axis * angle, radians), m/s², rad/s, touch 0..1. */
    class State {
        var time = 0; var seq = 0
        val orientation = FloatArray(3); val accel = FloatArray(3); val gyro = FloatArray(3)
        /** Finger on the touchpad; when false the report carries (0, 0), as the controller does. */
        var touching = false
        var touchX = 0f; var touchY = 0f
        var buttons = 0
    }

    fun encode(s: State, out: ByteArray = ByteArray(20)): ByteArray {
        out.fill(0)
        var bit = 0
        fun put(value: Int, bits: Int) {
            for (k in bits - 1 downTo 0) {
                if ((value ushr k) and 1 != 0) out[bit ushr 3] = (out[bit ushr 3].toInt() or (0x80 ushr (bit and 7))).toByte()
                bit++
            }
        }
        fun signed(v: Float, scale: Float) = ((v / scale).roundToInt().coerceIn(-4096, 4095)) and 0x1fff
        put(s.time and 0x1ff, 9)
        put(s.seq and 0x1f, 5)
        for (v in s.orientation) put(signed(v, ORI_SCALE), 13)
        for (v in s.accel) put(signed(v, ACC_SCALE), 13)
        for (v in s.gyro) put(signed(v, GYRO_SCALE), 13)
        val tx = if (s.touching) (s.touchX * 255f).roundToInt().coerceIn(1, 255) else 0
        val ty = if (s.touching) (s.touchY * 255f).roundToInt().coerceIn(1, 255) else 0
        put(tx, 8); put(ty, 8)
        out[18] = (out[18].toInt() or (s.buttons and 0x1f)).toByte()
        return out
    }

    /** The parser of the reverse-engineered format, for checking [encode]. */
    fun decode(d: ByteArray): State {
        fun u(i: Int) = d[i].toInt() and 0xff
        fun sx(v: Int) = (v shl 19) shr 19
        val s = State()
        s.buttons = u(18) and 0x1f
        s.time = (u(0) shl 1) or ((u(1) and 0x80) ushr 7)
        s.seq = (u(1) and 0x7c) ushr 2
        // daydream-controller.js reads (byte3 & 0x80) >> 5 here, dropping two of the three low bits;
        // the fields are packed back to back, so x's low bits are byte3's top three
        s.orientation[0] = sx((u(1) and 0x03) shl 11 or (u(2) shl 3) or ((u(3) and 0xe0) ushr 5)) * ORI_SCALE
        s.orientation[1] = sx((u(3) and 0x1f) shl 8 or u(4)) * ORI_SCALE
        s.orientation[2] = sx((u(5) shl 5) or ((u(6) and 0xf8) ushr 3)) * ORI_SCALE
        s.accel[0] = sx((u(6) and 0x07) shl 10 or (u(7) shl 2) or ((u(8) and 0xc0) ushr 6)) * ACC_SCALE
        s.accel[1] = sx((u(8) and 0x3f) shl 7 or ((u(9) and 0xfe) ushr 1)) * ACC_SCALE
        s.accel[2] = sx((u(9) and 0x01) shl 12 or (u(10) shl 4) or ((u(11) and 0xf0) ushr 4)) * ACC_SCALE
        s.gyro[0] = sx((u(11) and 0x0f) shl 9 or (u(12) shl 1) or ((u(13) and 0x80) ushr 7)) * GYRO_SCALE
        s.gyro[1] = sx((u(13) and 0x7f) shl 6 or ((u(14) and 0xfc) ushr 2)) * GYRO_SCALE
        s.gyro[2] = sx((u(14) and 0x03) shl 11 or (u(15) shl 3) or ((u(16) and 0xe0) ushr 5)) * GYRO_SCALE
        val tx = ((u(16) and 0x1f) shl 3) or ((u(17) and 0xe0) ushr 5)
        val ty = ((u(17) and 0x1f) shl 3) or ((u(18) and 0xe0) ushr 5)
        s.touching = tx != 0 || ty != 0
        s.touchX = tx / 255f; s.touchY = ty / 255f
        return s
    }

    /**
     * A phone held like the controller (portrait, screen up, its top pointing forward) to the
     * controller's axes (x right, y up, z towards the user): (x, y, z)phone -> (x, z, -y).
     */
    fun phoneToController(v: FloatArray, out: FloatArray = FloatArray(3)): FloatArray {
        val x = v[0]; val y = v[1]; val z = v[2]
        out[0] = x; out[1] = z; out[2] = -y
        return out
    }

    /** Rotation vector (axis * angle) of the unit quaternion (x, y, z, w), the shortest way round. */
    fun rotationVector(qx: Float, qy: Float, qz: Float, qw: Float, out: FloatArray = FloatArray(3)): FloatArray {
        var x = qx; var y = qy; var z = qz; var w = qw
        if (w < 0f) { x = -x; y = -y; z = -z; w = -w }
        val s = sqrt(x * x + y * y + z * z)
        if (s < 1e-7f) { out.fill(0f); return out }
        val angle = 2f * acos(w.coerceIn(-1f, 1f))
        val k = angle / s
        out[0] = x * k; out[1] = y * k; out[2] = z * k
        return out
    }
}
