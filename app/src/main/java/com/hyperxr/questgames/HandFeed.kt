package com.hyperxr.questgames

import com.hyperxr.handtracking.OneEuroFilter
import com.hyperxr.math.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Steadies a tracked hand (or the controller) for the games where your hands move your body
 * (gorilla, climbing): a phone camera's hand tracking shakes by millimetres and now and then jumps,
 * and with locomotion every millimetre of shake moves the world.
 *
 *  - One Euro filter per axis: still when the hand is still, no lag when it swings.
 *  - A jump faster than [maxSpeed] (a mis-detection) is ignored for that frame.
 *  - A hand the camera loses stays where it was (relative to the head) for [holdS] seconds, so a
 *    hand resting on the ground below the camera's view keeps holding instead of letting go.
 */
class HandFeed(private val holdS: Float = 0.45f, private val maxSpeed: Float = 7f) {
    private val f = Array(3) { OneEuroFilter(minCutoff = 1.6f, beta = 0.9f, dCutoff = 1.2f) }
    private val last = FloatArray(3)
    private val headAtLast = FloatArray(3)
    private var lostFor = Float.MAX_VALUE
    private var have = false
    private var jumps = 0
    /** The steadied position this frame. */
    val out = FloatArray(3)
    /** True while the position is held from before (the hand is not seen right now). */
    var holding = false; private set

    /**
     * [p] is the tracked position (null: not tracked now), [head] the head position in the same
     * space. Returns [out], or null when there is no hand to use.
     */
    fun update(p: Vec3?, head: Vec3, dt: Float): FloatArray? {
        if (p != null && have && dt > 0f && lostFor <= holdS) {
            val dx = p.x - last[0]; val dy = p.y - last[1]; val dz = p.z - last[2]
            // a hand can't teleport: a jump is a detection error, held like a lost frame (unless
            // the hand stays there: then it really is there)
            if (sqrt(dx * dx + dy * dy + dz * dz) / (dt + lostFor) > maxSpeed && jumps < 3) { jumps++; return hold(head, dt) }
            if (jumps >= 3) for (k in f) k.reset()
        }
        if (p == null) return hold(head, dt)
        jumps = 0
        if (!have || lostFor > holdS) for (k in f) k.reset()
        out[0] = f[0].filter(p.x, dt); out[1] = f[1].filter(p.y, dt); out[2] = f[2].filter(p.z, dt)
        out.copyInto(last); headAtLast[0] = head.x; headAtLast[1] = head.y; headAtLast[2] = head.z
        have = true; lostFor = 0f; holding = false
        return out
    }

    private fun hold(head: Vec3, dt: Float): FloatArray? {
        lostFor += dt
        if (!have || lostFor > holdS) { holding = false; return null }
        // the hand stays put relative to the body: it moves with the head
        out[0] = last[0] + head.x - headAtLast[0]; out[1] = last[1] + head.y - headAtLast[1]; out[2] = last[2] + head.z - headAtLast[2]
        holding = true
        return out
    }

    fun reset() { have = false; lostFor = Float.MAX_VALUE; holding = false; jumps = 0 }

    companion object {
        /**
         * Your reach for the gorilla: a phone camera sees your hands only ~25 degrees below your
         * eyes, so a hand low in its view is taken further down (the angle below the horizon
         * times [downGain], up to nearly straight down) and further out ([reachGain]), and the
         * ground is in reach with the hands still in view. [v] is hand minus head; changed in place.
         */
        fun gorillaReach(v: FloatArray, reachGain: Float = 1.45f, downGain: Float = 2.1f) {
            val h = sqrt(v[0] * v[0] + v[2] * v[2])
            val len = sqrt(h * h + v[1] * v[1])
            if (len < 1e-5f) return
            var a = atan2(v[1], h)
            if (a < 0f) a = max(a * downGain, -1.48f)
            val l = len * reachGain
            val hx = if (h > 1e-5f) v[0] / h else 0f; val hz = if (h > 1e-5f) v[2] / h else 0f
            v[0] = hx * cos(a) * l; v[1] = sin(a) * l; v[2] = hz * cos(a) * l
        }
    }
}
