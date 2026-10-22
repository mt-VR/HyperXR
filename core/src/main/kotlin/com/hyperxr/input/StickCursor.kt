package com.hyperxr.input

import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Ray
import com.hyperxr.math.Vec3
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Joy-Con navigation without motion sensors: the analog stick moves a cursor across the view like
 * a mouse. The cursor is an angular offset from where the head looks, so it stays in view when
 * the head turns; the pointing ray goes from the eyes through it.
 */
class StickCursor(
    /** Degrees per second at full deflection. */
    var speedDegPerSec: Float = 75f,
    var maxYawDeg: Float = 42f,
    var maxPitchDeg: Float = 32f,
    var deadZone: Float = 0.12f,
) {
    var yawDeg = 0f; private set
    var pitchDeg = 0f; private set
    private val q = Quat()
    private val tmp = Quat()
    private val dir = Vec3()

    /** Response curve: precise near the centre, fast at full deflection. */
    fun curve(v: Float): Float {
        val a = abs(v)
        if (a < deadZone) return 0f
        val t = ((a - deadZone) / (1f - deadZone)).coerceIn(0f, 1f)
        return (t * t * 0.6f + t * 0.4f) * if (v < 0) -1f else 1f
    }

    /** [x] right, [y] up, both -1..1. */
    fun update(x: Float, y: Float, dt: Float) {
        yawDeg = (yawDeg + curve(x) * speedDegPerSec * dt).coerceIn(-maxYawDeg, maxYawDeg)
        pitchDeg = (pitchDeg + curve(y) * speedDegPerSec * dt).coerceIn(-maxPitchDeg, maxPitchDeg)
    }

    fun recenter() { yawDeg = 0f; pitchDeg = 0f }

    /** Ray from the eyes through the cursor. */
    fun ray(head: Pose, out: Ray): Ray {
        val yaw = Math.toRadians(-yawDeg.toDouble()).toFloat()
        val pitch = Math.toRadians(pitchDeg.toDouble()).toFloat()
        // offset in head space: yaw around head up, then pitch around head right
        Quat.fromAxisAngle(0f, 1f, 0f, yaw, q)
        q.mul(Quat.fromAxisAngle(1f, 0f, 0f, pitch, tmp), q)
        head.orientation.mul(q, tmp)
        dir.set(0f, 0f, -1f)
        tmp.rotate(dir, out.direction)
        out.origin.set(head.position)
        return out
    }

    /** Head-space direction (for tests / UI). */
    fun localDirection(out: Vec3): Vec3 {
        val yaw = Math.toRadians(yawDeg.toDouble()); val pitch = Math.toRadians(pitchDeg.toDouble())
        return out.set((sin(yaw) * cos(pitch)).toFloat(), sin(pitch).toFloat(), (-cos(yaw) * cos(pitch)).toFloat())
    }
}
