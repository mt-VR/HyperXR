package com.hyperxr.daydream

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Turns gamepad state into a Daydream controller: 3DoF orientation (the controller's own gyro
 * when the kernel exposes it, otherwise where the head looks), touchpad from the stick, button
 * events, and recentering by holding HOME - like the real controller.
 */
class DaydreamController(
    /** Device-frame axis feeding each Daydream axis (+X right, +Y up, +Z back): 1-based, negative flips. */
    var axisMap: IntArray = intArrayOf(1, 2, 3),
) {
    class Input(
        val timestampNs: Long,
        val buttons: Int,              // bits (1 shl DaydreamProtocol.BUTTON_*)
        val stickX: Float, val stickY: Float,
        val gyro: FloatArray? = null,  // rad/s, device frame
        val accel: FloatArray? = null, // m/s², device frame
        val head: FloatArray? = null,  // head orientation x, y, z, w (used without a gyro)
    )

    var recenterHoldNs = 800_000_000L
    var touchDeadzone = 0.18f
    /** Tilt correction toward gravity, per second (complementary filter). */
    var tiltGain = 1.5f

    private var q = floatArrayOf(0f, 0f, 0f, 1f)   // controller in its gravity-aligned start frame
    private var yawRef = floatArrayOf(0f, 0f, 0f, 1f)
    private var lastNs = 0L
    private var lastButtons = 0
    private var touching = false
    private var homeSinceNs = -1L
    private var homeConsumed = false

    /** Set when a recenter happened in the last [update]; the service sends onControllerRecentered. */
    var recentered = false
        private set

    fun map(v: FloatArray): FloatArray = FloatArray(3) { i -> val a = axisMap[i]; v[abs(a) - 1] * if (a < 0) -1f else 1f }

    fun update(input: Input): ControllerFrame {
        recentered = false
        val f = ControllerFrame(input.timestampNs)
        val dt = if (lastNs == 0L) 0f else ((input.timestampNs - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = input.timestampNs

        val gyro = input.gyro?.let(::map)
        val accel = input.accel?.let(::map)
        if (gyro != null) {
            integrate(gyro, dt)
            if (accel != null) correctTilt(accel, dt)
            f.gyro = gyro
            if (accel != null) f.accel = accel
        } else if (input.head != null) {
            q = input.head.copyOf()
        }

        // HOME: a short press is the home button, holding it recenters (the press is then swallowed)
        val homeBit = 1 shl DaydreamProtocol.BUTTON_HOME
        var buttons = input.buttons
        if (buttons and homeBit != 0) {
            if (homeSinceNs < 0) { homeSinceNs = input.timestampNs; homeConsumed = false }
            if (!homeConsumed && input.timestampNs - homeSinceNs >= recenterHoldNs) { recenter(); homeConsumed = true }
            buttons = buttons and homeBit.inv()
        } else if (homeSinceNs >= 0) {
            val shortPress = !homeConsumed
            homeSinceNs = -1
            if (shortPress) { f.buttons += ControllerFrame.Button(DaydreamProtocol.BUTTON_HOME, true); f.buttons += ControllerFrame.Button(DaydreamProtocol.BUTTON_HOME, false) }
        }
        val changed = buttons xor (lastButtons and homeBit.inv())
        for (id in 1..8) if (changed and (1 shl id) != 0) f.buttons += ControllerFrame.Button(id, buttons and (1 shl id) != 0)
        lastButtons = buttons

        // touchpad: the stick's position on a 0..1 pad, y down; touching while out of the deadzone
        val r = sqrt(input.stickX * input.stickX + input.stickY * input.stickY)
        val x = (0.5f + 0.5f * input.stickX).coerceIn(0f, 1f)
        val y = (0.5f - 0.5f * input.stickY).coerceIn(0f, 1f)
        if (r > touchDeadzone) {
            f.touch = ControllerFrame.Touch(if (touching) DaydreamProtocol.TOUCH_MOVE else DaydreamProtocol.TOUCH_DOWN, x, y)
            touching = true
        } else if (touching) {
            f.touch = ControllerFrame.Touch(DaydreamProtocol.TOUCH_UP, 0.5f, 0.5f)
            touching = false
        }

        f.orientation = orientation()
        return f
    }

    /** Orientation as the app sees it: yaw relative to the last recenter. */
    fun orientation(): FloatArray = Quat.norm(Quat.mul(Quat.conj(yawRef), q))

    /** Points the controller's current heading straight ahead. */
    fun recenter() { yawRef = Quat.yawOnly(q); recentered = true }

    private fun integrate(w: FloatArray, dt: Float) {
        val ang = sqrt(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]) * dt
        if (ang < 1e-9f) return
        val s = sin(ang / 2) / (ang / dt)
        q = Quat.norm(Quat.mul(q, floatArrayOf(w[0] * s, w[1] * s, w[2] * s, cos(ang / 2))))
    }

    /** Nudges the estimate so that the measured "up" (accel at rest) matches the world's +Y. */
    private fun correctTilt(a: FloatArray, dt: Float) {
        val n = sqrt(a[0] * a[0] + a[1] * a[1] + a[2] * a[2])
        if (n < 8.3f || n > 11.3f) return                 // moving: accel is not just gravity
        val up = Quat.rotate(q, floatArrayOf(a[0] / n, a[1] / n, a[2] / n))
        // axis = up x (0,1,0), angle between them
        val ax = -up[2]; val az = up[0]
        val s = sqrt(ax * ax + az * az)
        if (s < 1e-6f) return
        val angle = atan2(s, up[1]) * (tiltGain * dt).coerceAtMost(1f)
        val k = sin(angle / 2) / s
        q = Quat.norm(Quat.mul(floatArrayOf(ax * k, 0f, az * k, cos(angle / 2)), q))
    }
}

/** Small quaternion helpers on (x, y, z, w) arrays. */
object Quat {
    fun mul(a: FloatArray, b: FloatArray) = floatArrayOf(
        a[3] * b[0] + a[0] * b[3] + a[1] * b[2] - a[2] * b[1],
        a[3] * b[1] - a[0] * b[2] + a[1] * b[3] + a[2] * b[0],
        a[3] * b[2] + a[0] * b[1] - a[1] * b[0] + a[2] * b[3],
        a[3] * b[3] - a[0] * b[0] - a[1] * b[1] - a[2] * b[2])
    fun conj(q: FloatArray) = floatArrayOf(-q[0], -q[1], -q[2], q[3])
    fun norm(q: FloatArray): FloatArray {
        val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        return if (n < 1e-9f) floatArrayOf(0f, 0f, 0f, 1f) else floatArrayOf(q[0] / n, q[1] / n, q[2] / n, q[3] / n)
    }
    fun rotate(q: FloatArray, v: FloatArray): FloatArray {
        val p = mul(mul(q, floatArrayOf(v[0], v[1], v[2], 0f)), conj(q))
        return floatArrayOf(p[0], p[1], p[2])
    }
    fun axisAngle(x: Float, y: Float, z: Float, angle: Float): FloatArray {
        val s = sin(angle / 2); return floatArrayOf(x * s, y * s, z * s, cos(angle / 2))
    }
    /** Rotation about +Y keeping only the heading of q (its -Z axis on the floor). */
    fun yawOnly(q: FloatArray): FloatArray {
        val f = rotate(q, floatArrayOf(0f, 0f, -1f))
        return axisAngle(0f, 1f, 0f, atan2(-f[0], -f[2]))
    }
}
