package com.hyperxr.handtracking

import com.hyperxr.math.Vec3
import kotlin.math.sqrt

/**
 * "Palm toward the face" opens a small menu on that hand (like looking at a watch): the palm
 * normal must point at the eyes, the fingers must be open (not a pinch or a fist) and the hand
 * within reach. It has to hold for a moment to open and stay away for a moment to close, so
 * hands passing through the view do not flicker it.
 */
class PalmMenuDetector(
    var openSeconds: Float = 0.35f,
    var closeSeconds: Float = 0.3f,
    /** cos of the largest angle between the palm normal and the direction to the eyes. */
    var minFacing: Float = 0.7f,
) {
    var active = false; private set
    private var timer = 0f

    fun reset() { active = false; timer = 0f }

    /** One frame: [joints] in world metres, [head] the eye position. Returns [active]. */
    fun update(joints: Array<Vec3>, right: Boolean, head: Vec3, pinchStrength: Float, dt: Float): Boolean {
        val want = isPalmMenuPose(joints, right, head, pinchStrength, minFacing)
        if (want == active) timer = 0f
        else {
            timer += dt
            if (timer >= if (want) openSeconds else closeSeconds) { active = want; timer = 0f }
        }
        return active
    }

    companion object {
        /** Unit normal out of the palm (toward what the palm faces). */
        fun palmNormal(j: Array<Vec3>, right: Boolean, out: Vec3): Vec3 {
            val w = j[HandJoint.WRIST]; val a = j[HandJoint.INDEX_MCP]; val b = j[HandJoint.PINKY_MCP]
            val ax = a.x - w.x; val ay = a.y - w.y; val az = a.z - w.z
            val bx = b.x - w.x; val by = b.y - w.y; val bz = b.z - w.z
            // right hand: (index - wrist) x (pinky - wrist) leaves the palm; mirrored for the left
            var nx = ay * bz - az * by; var ny = az * bx - ax * bz; var nz = ax * by - ay * bx
            if (!right) { nx = -nx; ny = -ny; nz = -nz }
            val l = sqrt(nx * nx + ny * ny + nz * nz)
            return if (l < 1e-9f) out.set(0f, 0f, 0f) else out.set(nx / l, ny / l, nz / l)
        }

        /** Fingers stretched: at least three fingertips well beyond their middle joint from the wrist. */
        fun fingersOpen(j: Array<Vec3>): Boolean {
            val w = j[HandJoint.WRIST]
            var open = 0
            for (tip in intArrayOf(HandJoint.INDEX_TIP, HandJoint.MIDDLE_TIP, HandJoint.RING_TIP, HandJoint.PINKY_TIP)) {
                if (j[tip].distanceTo(w) > j[tip - 2].distanceTo(w) * 1.12f) open++
            }
            return open >= 3
        }

        fun isPalmMenuPose(j: Array<Vec3>, right: Boolean, head: Vec3, pinchStrength: Float, minFacing: Float = 0.7f): Boolean {
            if (pinchStrength > 0.5f || !fingersOpen(j)) return false
            val c = palmCenter(j, Vec3())
            val dx = head.x - c.x; val dy = head.y - c.y; val dz = head.z - c.z
            val d = sqrt(dx * dx + dy * dy + dz * dz)
            if (d < 0.12f || d > 0.75f) return false
            val n = palmNormal(j, right, Vec3())
            return (n.x * dx + n.y * dy + n.z * dz) / d >= minFacing
        }

        fun palmCenter(j: Array<Vec3>, out: Vec3): Vec3 {
            val w = j[HandJoint.WRIST]; val m = j[HandJoint.MIDDLE_MCP]
            return out.set((w.x + m.x) / 2, (w.y + m.y) / 2, (w.z + m.z) / 2)
        }
    }
}

/** Measuring with the hands: the distance between the two pinch points (thumb and index tips). */
object HandMeasure {
    fun pinchPoint(j: Array<Vec3>, out: Vec3): Vec3 {
        val t = j[HandJoint.THUMB_TIP]; val i = j[HandJoint.INDEX_TIP]
        return out.set((t.x + i.x) / 2, (t.y + i.y) / 2, (t.z + i.z) / 2)
    }

    /** "8 mm", "34,5 cm", "1,27 m" (Brazilian decimal comma). */
    fun format(meters: Float): String = when {
        meters < 0.01f -> "${Math.round(meters * 1000)} mm"
        meters < 1f -> String.format(java.util.Locale.ROOT, "%.1f cm", meters * 100).replace('.', ',')
        else -> String.format(java.util.Locale.ROOT, "%.2f m", meters).replace('.', ',')
    }
}
