package com.hyperxr.input

import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Ray
import com.hyperxr.math.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Pointing with a controller's gyroscope (DualSense, DualShock 4, Joy-Con, Switch Pro...), like a
 * gyro mouse: turning the controller left and right turns the pointer, tilting it up and down
 * raises and lowers it. It needs no absolute orientation and no knowledge of how the driver
 * orders its axes beyond "x is the controller's left-right": the accelerometer gives which way is
 * up in the controller (gravity), the rotation about that axis is the turn, the rotation about
 * the controller's left-right axis is the tilt. Where "straight ahead" is comes from the head when
 * it is recentred (Home, or Recenter in the menu); gyros drift slowly, so recentring fixes it.
 */
class GyroPointer {
    /** Pointer angles from straight ahead, radians: yaw positive to the left, pitch positive up. */
    @Volatile var yaw = 0f; private set
    @Volatile var pitch = 0f; private set
    /** Head heading the pointer is relative to. */
    @Volatile var reference = 0f; private set
    @Volatile var recenterRequested = true
    /** A gyro sample arrived recently (the controller really reports motion). */
    @Volatile var lastSampleNs = 0L; private set

    var sensitivity = 1f
    var invertX = false
    var invertY = false

    private val up = Vec3(0f, 1f, 0f)
    private var hasUp = false
    private val right = Vec3()

    /** Accelerometer (any unit; only its direction is used). */
    fun onAccel(ax: Float, ay: Float, az: Float) {
        val l = sqrt(ax * ax + ay * ay + az * az)
        if (l < 1e-3f) return
        val nx = ax / l; val ny = ay / l; val nz = az / l
        if (!hasUp) { up.set(nx, ny, nz); hasUp = true; return }
        // low-pass: gravity, not the shakes of the hand
        up.set(up.x + (nx - up.x) * 0.05f, up.y + (ny - up.y) * 0.05f, up.z + (nz - up.z) * 0.05f).normalize()
    }

    /** Gyroscope in rad/s (counter-clockwise positive about each axis), [dt] seconds since the last one. */
    fun onGyro(gx: Float, gy: Float, gz: Float, dt: Float, nowNs: Long) {
        lastSampleNs = nowNs
        if (dt <= 0f || dt > 0.1f) return
        val u = if (hasUp) up else DEFAULT_UP
        // turn: rotation about up (counter-clockwise seen from above = to the left)
        val turn = gx * u.x + gy * u.y + gz * u.z
        // tilt: rotation about the controller's left-right axis made horizontal
        val d = u.x
        right.set(1f - d * u.x, -d * u.y, -d * u.z)
        val rl = right.length()
        val tilt = if (rl > 0.2f) (gx * right.x + gy * right.y + gz * right.z) / rl else 0f
        val s = sensitivity
        yaw += turn * dt * s * (if (invertX) -1f else 1f)
        pitch = (pitch + tilt * dt * s * (if (invertY) -1f else 1f)).coerceIn(-MAX_PITCH, MAX_PITCH)
    }

    /** Straight ahead becomes where the head looks now (heading only). */
    fun recenter(head: Pose) {
        val f = head.orientation.rotate(Vec3(0f, 0f, -1f))
        reference = atan2(-f.x, -f.z)
        yaw = 0f; pitch = 0f
        recenterRequested = false
    }

    fun active(nowNs: Long) = lastSampleNs != 0L && nowNs - lastSampleNs < 500_000_000L

    /** The pointing direction in the world. */
    fun direction(out: Vec3): Vec3 {
        val h = reference + yaw
        val cp = cos(pitch)
        return out.set(-sin(h) * cp, sin(pitch), -cos(h) * cp)
    }

    /** Orientation whose -z is [direction], level (no roll). */
    fun orientation(out: Quat): Quat {
        Quat.fromAxisAngle(0f, 1f, 0f, reference + yaw, out)
        return out.mul(Quat.fromAxisAngle(1f, 0f, 0f, pitch), out)
    }

    fun ray(origin: Vec3, out: Ray): Ray { out.origin.set(origin); direction(out.direction); return out }

    companion object {
        const val MAX_PITCH = 1.4f
        private val DEFAULT_UP = Vec3(0f, 1f, 0f)
    }
}
