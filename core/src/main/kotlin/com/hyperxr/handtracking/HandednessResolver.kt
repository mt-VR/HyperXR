package com.hyperxr.handtracking

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.sqrt

/**
 * HyperXR's own left/right decision, the same for every engine. Evidence per hand, as log-odds
 * for "right hand":
 *  - chirality of the 3D landmarks: the thumb lies on one side of the plane spanned by the index
 *    and pinky knuckles for a right hand and on the other for a left hand. A rotation of the hand
 *    never changes the sign, only a mirror does. Measured on photos and their mirror images it
 *    agreed with the true hand in 17 of 18 cases (the miss was a weak value);
 *  - the landmark model's own handedness score, when the engine exposes it;
 *  - a weak prior from the side of the image the wrist is on (the right hand is usually right);
 *  - continuity: evidence accumulates on the same hand across frames (matched by wrist position),
 *    so one doubtful frame cannot swap the colours.
 * Two hands in one frame always get different sides.
 * Images are those of the back camera (not mirrored).
 */
class HandednessResolver {
    class Input(
        /** Metric landmarks (x, y, z per joint) or null. */
        val world: FloatArray?,
        /** Wrist in normalized image coordinates. */
        val wristX: Float, val wristY: Float,
        /** Model probability that this is a right hand (non-mirrored image), NaN when unknown. */
        val modelRight: Float,
    )

    private class Track(var x: Float, var y: Float, var evidence: Float, var age: Int)
    private val tracks = ArrayList<Track>(2)

    fun reset() = tracks.clear()

    /** Thumb side of the palm plane in metres: positive for a right hand. */
    fun chirality(w: FloatArray): Float {
        fun v(j: Int, o: Int) = w[j * 3 + o] - w[o]  // relative to the wrist
        val ax = v(HandJoint.INDEX_MCP, 0); val ay = v(HandJoint.INDEX_MCP, 1); val az = v(HandJoint.INDEX_MCP, 2)
        val bx = v(HandJoint.PINKY_MCP, 0); val by = v(HandJoint.PINKY_MCP, 1); val bz = v(HandJoint.PINKY_MCP, 2)
        var nx = ay * bz - az * by; var ny = az * bx - ax * bz; var nz = ax * by - ay * bx
        val len = sqrt(nx * nx + ny * ny + nz * nz)
        if (len < 1e-9f) return 0f
        nx /= len; ny /= len; nz /= len
        // thumb tip relative to the middle of the index and pinky knuckles
        val mx = (w[HandJoint.INDEX_MCP * 3] + w[HandJoint.PINKY_MCP * 3]) / 2
        val my = (w[HandJoint.INDEX_MCP * 3 + 1] + w[HandJoint.PINKY_MCP * 3 + 1]) / 2
        val mz = (w[HandJoint.INDEX_MCP * 3 + 2] + w[HandJoint.PINKY_MCP * 3 + 2]) / 2
        val t = HandJoint.THUMB_TIP * 3
        return nx * (w[t] - mx) + ny * (w[t + 1] - my) + nz * (w[t + 2] - mz)
    }

    /** Evidence of this frame alone (log-odds of "right"). */
    fun frameEvidence(i: Input): Float {
        var e = 0f
        i.world?.let { e += (chirality(it) / 0.012f).coerceIn(-3f, 3f) }
        if (!i.modelRight.isNaN()) e += ((i.modelRight - 0.5f) * 3f).coerceIn(-1.5f, 1.5f)
        e += ((i.wristX - 0.5f) * 1.2f).coerceIn(-0.4f, 0.4f)
        return e
    }

    /** Resolves this frame's hands; returns RIGHT/LEFT per input, in order. */
    fun resolve(hands: List<Input>): List<Handedness> {
        val used = BooleanArray(tracks.size)
        val ev = FloatArray(hands.size)
        val matched = arrayOfNulls<Track>(hands.size)
        for ((k, h) in hands.withIndex()) {
            // continuity: the nearest previous hand within 15% of the image
            var best = -1; var bestD = 0.15f
            for ((t, tr) in tracks.withIndex()) {
                if (used[t]) continue
                val d = hypot(tr.x - h.wristX, tr.y - h.wristY)
                if (d < bestD) { bestD = d; best = t }
            }
            val prior = if (best >= 0) { used[best] = true; matched[k] = tracks[best]; tracks[best].evidence * 0.85f } else 0f
            ev[k] = (prior + frameEvidence(h)).coerceIn(-12f, 12f)
        }
        val out = ev.map { if (it >= 0f) Handedness.RIGHT else Handedness.LEFT }.toMutableList()
        // one left and one right: the less certain of two same-side hands takes the other side
        if (hands.size == 2 && out[0] == out[1]) {
            val flip = if (abs(ev[0]) < abs(ev[1])) 0 else 1
            out[flip] = if (out[flip] == Handedness.RIGHT) Handedness.LEFT else Handedness.RIGHT
            ev[flip] = if (out[flip] == Handedness.RIGHT) maxOf(0.5f, abs(ev[flip])) else -maxOf(0.5f, abs(ev[flip]))
        }
        tracks.clear()
        for ((k, h) in hands.withIndex()) tracks += Track(h.wristX, h.wristY, ev[k], (matched[k]?.age ?: 0) + 1)
        return out
    }
}
