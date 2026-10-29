package com.hyperxr.handtracking

import com.hyperxr.math.Vec3
import kotlin.math.abs

/**
 * Which hand a tracked hand really is, from its own 3D shape and where it is, not only from the
 * detector's left/right label (which flips when palm and back look alike to it).
 *
 * Shape: a right and a left hand are mirror images. Across the knuckles, (index - wrist) x (pinky -
 * wrist) points out of the palm of a right hand and out of the back of a left one; fingers bend
 * towards the palm and the thumb sits on the palm side, so measuring those along that normal tells
 * the two apart. A flat, open hand says little, so the place counts too: in front of your eyes a
 * hand to the right of your head is nearly always your right hand.
 */
object HandSide {
    private val a = Vec3(); private val b = Vec3(); private val n = Vec3(); private val t = Vec3()

    /** Positive for a right hand, negative for a left one, about -1..1; near 0 when the shape doesn't say. */
    @Synchronized
    fun chirality(j: Array<Vec3>): Float {
        val w = j[HandJoint.WRIST]
        a.set(j[HandJoint.INDEX_MCP]).sub(w)
        b.set(j[HandJoint.PINKY_MCP]).sub(w)
        a.cross(b, n)
        val palmLen = t.set(j[HandJoint.MIDDLE_MCP]).sub(w).length()
        if (n.length() < 1e-6f || palmLen < 0.02f) return 0f
        n.normalize()
        var s = 0f
        // the four fingers: how far each fingertip bends off its knuckle, along the normal
        for ((mcp, tip) in FINGERS) {
            val l = t.set(j[tip]).sub(j[mcp]).length()
            if (l > 1e-4f) s += t.dot(n) / l
        }
        // the thumb's tip on the palm side of the knuckle plane
        s += 1.5f * t.set(j[HandJoint.THUMB_TIP]).sub(w).dot(n) / palmLen
        return (s / 3f).coerceIn(-1f, 1f)
    }

    private val FINGERS = arrayOf(
        HandJoint.INDEX_MCP to HandJoint.INDEX_TIP, HandJoint.MIDDLE_MCP to HandJoint.MIDDLE_TIP,
        HandJoint.RING_MCP to HandJoint.RING_TIP, HandJoint.PINKY_MCP to HandJoint.PINKY_TIP,
    )

    /**
     * Keeps one decision per tracked hand and changes it only when the evidence stays on the other
     * side for a while, so an arm never jumps from shoulder to shoulder on one bad frame.
     */
    class Decider {
        /** The decision: true = right hand. */
        var right = true; private set
        private var known = false
        private var against = 0f

        /**
         * [label] is the detector's word for it; [lateral] is how far the wrist is to the right of
         * the head, metres (negative: left). Returns the decision.
         */
        fun update(label: Boolean, joints: Array<Vec3>, lateral: Float, dt: Float): Boolean {
            val shape = chirality(joints)
            val place = (lateral / 0.18f).coerceIn(-1f, 1f)
            val evidence = 0.9f * shape + 0.8f * place + 0.35f * (if (label) 1f else -1f)
            val says = evidence > 0f
            if (!known) { right = says; known = true; against = 0f; return right }
            if (says != right && abs(evidence) > 0.25f) {
                against += dt * (0.5f + abs(evidence))
                if (against > 0.18f) { right = says; against = 0f }
            } else against = (against - dt).coerceAtLeast(0f)
            return right
        }

        fun reset() { known = false; against = 0f }
    }

    /** Two hands decided the same side: the one further to that side keeps it. Returns the two decisions. */
    fun separate(rightA: Boolean, lateralA: Float, rightB: Boolean, lateralB: Float): Pair<Boolean, Boolean> {
        if (rightA != rightB) return rightA to rightB
        return if (lateralA >= lateralB) true to false else false to true
    }
}
