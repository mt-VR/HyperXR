package com.hyperxr.input

import com.hyperxr.handtracking.HandJoint
import com.hyperxr.math.Vec3
import kotlin.math.exp

/**
 * Point-and-push click: the hand points with the index finger (the other fingers folded, the
 * thumb free), the fingertip aims at a button as seen from the eyes, and a short push of the
 * finger towards it clicks. Holding the push and moving drags (scrolls, moves windows, selects);
 * pulling the finger back releases.
 *
 * The push is measured as the fingertip's distance from the eyes against a baseline that follows
 * the hand slowly, so walking or slowly moving the arm never clicks, only a deliberate forward
 * push does. With the ARCore Depth API the fingertip's distance is measured, without it it is
 * estimated from the hand's size in the image and needs a longer push ([uncertainScale]).
 */
class PointPush {
    /** Forward push (metres) that presses, and how far it must come back to release. */
    var pressM = 0.022f
    var releaseM = 0.010f
    /** With depth from the hand-size prior the thresholds grow by this factor. */
    var uncertainScale = 1.5f
    /** How fast the baseline follows the hand while not pressed (seconds). */
    var baselineTauS = 0.35f
    /** The pointing pose must hold this long before it aims (no flicker from a passing pose). */
    var enterNs = 60_000_000L
    /** ... and may drop for this long without stopping (tracking hiccups). */
    var exitNs = 200_000_000L

    /**
     * Also accept a hand that simply leads with the index (the index tip the farthest from the
     * eyes, the finger straight, not pinching), as people do when they touch a screen, not only
     * the pointing fist.
     */
    var leadingIndexCounts = false

    var pointing = false; private set
    var pressed = false; private set
    /** 0 (no push) .. 1 (pressed), for the visual. */
    var progress = 0f; private set
    val tip = Vec3()

    private var baseline = Float.NaN
    private var pressBaseline = 0f
    private var poseSinceNs = 0L
    private var lostSinceNs = 0L
    private var lastNs = 0L

    fun reset() {
        pointing = false; pressed = false; progress = 0f; baseline = Float.NaN; poseSinceNs = 0L; lostSinceNs = 0L; lastNs = 0L
    }

    /**
     * One frame. [joints] null = hand not usable. Returns [pressed].
     */
    fun update(joints: Array<Vec3>?, eye: Vec3, depthUncertain: Boolean, now: Long): Boolean {
        val dt = if (lastNs == 0L) 0f else ((now - lastNs) / 1e9f).coerceIn(0f, 0.1f)
        lastNs = now
        if (joints == null) { reset(); return false }
        tip.set(joints[HandJoint.INDEX_TIP])

        // pose with hysteresis; a held press keeps the pose (the finger bends a little when pushing)
        val pose = isPointingPose(joints) || (leadingIndexCounts && isIndexLeading(joints, eye))
        if (pose) {
            lostSinceNs = 0L
            if (poseSinceNs == 0L) poseSinceNs = now
            if (!pointing && now - poseSinceNs >= enterNs) { pointing = true; baseline = Float.NaN }
        } else {
            poseSinceNs = 0L
            if (lostSinceNs == 0L) lostSinceNs = now
            if (pointing && !pressed && now - lostSinceNs > exitNs) { pointing = false; progress = 0f }
            if (pressed && now - lostSinceNs > exitNs * 2) { pressed = false; pointing = false; progress = 0f }
        }
        if (!pointing) return false

        val k = if (depthUncertain) uncertainScale else 1f
        val reach = tip.distanceTo(eye)
        if (baseline.isNaN()) baseline = reach
        if (!pressed) {
            val push = reach - baseline
            progress = (push / (pressM * k)).coerceIn(0f, 1f)
            if (push > pressM * k) {
                pressed = true; pressBaseline = baseline; progress = 1f
            } else {
                // follows the hand slowly, and at once when it pulls back (a push starts from there)
                baseline = if (reach < baseline) reach else baseline + (reach - baseline) * (1f - exp(-dt / baselineTauS))
            }
        } else if (reach - pressBaseline < releaseM * k) {
            pressed = false; baseline = reach; progress = 0f
        }
        return pressed
    }

    companion object {
        /**
         * The index is out in front: straight, its tip at least 1.5 cm farther from the eyes than
         * every other fingertip, and away from the thumb (not a pinch).
         */
        fun isIndexLeading(j: Array<Vec3>, eye: Vec3): Boolean {
            val bones = j[HandJoint.INDEX_MCP].distanceTo(j[HandJoint.INDEX_PIP]) + j[HandJoint.INDEX_PIP].distanceTo(j[HandJoint.INDEX_DIP]) +
                j[HandJoint.INDEX_DIP].distanceTo(j[HandJoint.INDEX_TIP])
            if (j[HandJoint.INDEX_MCP].distanceTo(j[HandJoint.INDEX_TIP]) / bones.coerceAtLeast(1e-4f) < 0.8f) return false
            if (j[HandJoint.THUMB_TIP].distanceTo(j[HandJoint.INDEX_TIP]) < 0.035f) return false
            // ahead of the other fingertips, seen from the eyes (the per-finger depth of a phone's
            // tracker is estimated: it counts only as a small part, the reach from the wrist does)
            val wrist = j[HandJoint.WRIST]
            val palm = wrist.distanceTo(j[HandJoint.MIDDLE_MCP]).coerceAtLeast(0.02f)
            val index = wrist.distanceTo(j[HandJoint.INDEX_TIP])
            if (index < palm * 1.45f) return false
            val reach = j[HandJoint.INDEX_TIP].distanceTo(eye)
            var behind = 0
            for (f in intArrayOf(HandJoint.MIDDLE_TIP, HandJoint.RING_TIP, HandJoint.PINKY_TIP)) {
                val shorter = wrist.distanceTo(j[f]) < index - palm * 0.2f
                val nearer = j[f].distanceTo(eye) < reach - 0.015f
                if (shorter || nearer) behind++
            }
            return behind >= 2
        }

        /**
         * Index finger straight, at least two of middle/ring/pinky folded (their tips near the
         * palm). The thumb is ignored: out like a pistol or tucked in, both work.
         */
        fun isPointingPose(j: Array<Vec3>): Boolean {
            val wrist = j[HandJoint.WRIST]
            val palm = wrist.distanceTo(j[HandJoint.MIDDLE_MCP]).coerceAtLeast(0.02f)
            val bones = j[HandJoint.INDEX_MCP].distanceTo(j[HandJoint.INDEX_PIP]) + j[HandJoint.INDEX_PIP].distanceTo(j[HandJoint.INDEX_DIP]) +
                j[HandJoint.INDEX_DIP].distanceTo(j[HandJoint.INDEX_TIP])
            val straight = j[HandJoint.INDEX_MCP].distanceTo(j[HandJoint.INDEX_TIP]) / bones.coerceAtLeast(1e-4f)
            if (straight < 0.86f) return false
            // the index reaches well beyond its knuckle
            if (wrist.distanceTo(j[HandJoint.INDEX_TIP]) < palm * 1.55f) return false
            var folded = 0
            for (f in intArrayOf(HandJoint.MIDDLE_MCP, HandJoint.RING_MCP, HandJoint.PINKY_MCP)) {
                val tipD = wrist.distanceTo(j[f + 3])
                val mcpD = wrist.distanceTo(j[f])
                if (tipD < mcpD * 1.3f) folded++
            }
            return folded >= 2
        }
    }
}
