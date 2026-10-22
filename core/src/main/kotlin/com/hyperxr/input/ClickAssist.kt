package com.hyperxr.input

import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandState
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Ray
import com.hyperxr.math.Vec3

/**
 * Queues screen-tap / viewer-button transitions from the UI thread so every press is seen by the
 * render thread for at least one frame, even when finger down and up happen between two frames
 * (the magnetic/capacitive button of many VR viewers produces very short taps).
 */
class TapLatch {
    private val events = ArrayDeque<Boolean>()
    private var current = false

    @Synchronized fun onDown() { if (events.lastOrNull() != true) events.addLast(true) }
    @Synchronized fun onUp() { events.addLast(false) }

    /** Called once per frame: applies at most one queued transition. */
    @Synchronized fun next(): Boolean {
        if (events.isNotEmpty()) current = events.removeFirst()
        return current
    }
}

/**
 * Gaze dwell click: looking at the same spot of the same target for [dwellNs] produces one
 * press (one frame) followed by a release. It re-arms only after the gaze leaves the target or
 * moves, so holding the gaze never repeats clicks.
 */
class DwellClick(var dwellNs: Long = 1_100_000_000L, var tolerance: Float = 0.035f) {
    private var target: Any? = null
    private var startNs = 0L
    private var startU = 0f
    private var startV = 0f
    private var armed = false
    private var firing = false

    /** 0..1 for the reticle ring. */
    var progress = 0f
        private set

    fun reset() { target = null; armed = false; firing = false; progress = 0f }

    /** Returns true while the synthetic press is held (exactly one frame). */
    fun update(hit: Any?, u: Float, v: Float, now: Long): Boolean {
        if (firing) { firing = false; progress = 0f; return false }
        if (hit == null || dwellNs <= 0) { reset(); return false }
        val moved = kotlin.math.abs(u - startU) > tolerance || kotlin.math.abs(v - startV) > tolerance
        if (hit !== target || moved) {
            target = hit; startNs = now; startU = u; startV = v; armed = true
        }
        if (!armed) { progress = 0f; return false }
        progress = ((now - startNs).toFloat() / dwellNs).coerceIn(0f, 1f)
        if (now - startNs >= dwellNs) {
            armed = false
            firing = true
            return true
        }
        return false
    }
}

/**
 * Far interaction with a tracked hand: a ray from an estimated shoulder through the hand, clicked
 * with a pinch. More forgiving than direct touch when hand depth is only an estimate.
 */
object HandRay {
    private val tmp = Vec3()
    private val yaw = Quat()
    private val right = Vec3()
    private val tilt = Quat()

    /**
     * Upward tilt of the ray (degrees). Shoulder-through-hand points where the arm points; with the
     * hand at a relaxed chest height that is the floor area in front of the user (the dock), so
     * windows at eye level needed the arm raised high. The tilt lets a relaxed hand reach them.
     */
    var pitchOffsetDeg = 25f

    fun compute(hand: HandState, head: Pose, rightHand: Boolean, out: Ray): Ray? {
        if (!hand.isUsable) return null
        ControllerPoseFusion.yawOnly(head.orientation, yaw)
        yaw.rotate(tmp.set(if (rightHand) 0.17f else -0.17f, -0.22f, 0.05f), out.origin).add(head.position)
        val target = hand.joints[HandJoint.INDEX_MCP]
        out.direction.set(target).sub(out.origin)
        if (out.direction.length() < 0.05f) return null
        out.direction.normalize()
        if (pitchOffsetDeg != 0f) {
            // rotate upwards about the horizontal axis perpendicular to the ray
            right.set(-out.direction.z, 0f, out.direction.x) // dir x up: the ray's right-hand side
            if (right.length() > 1e-4f) {
                right.normalize()
                Quat.fromAxisAngle(right.x, right.y, right.z, Math.toRadians(pitchOffsetDeg.toDouble()).toFloat(), tilt)
                tilt.rotate(out.direction, tmp)
                // never tilt past straight up
                if (tmp.y < 0.97f) out.direction.set(tmp).normalize()
            }
        }
        return out
    }
}
