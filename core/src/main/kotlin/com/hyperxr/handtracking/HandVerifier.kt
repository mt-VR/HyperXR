package com.hyperxr.handtracking

import kotlin.math.sqrt

/**
 * Decides whether what a landmark model found is really the user's hand, for both engines.
 *
 * Measured on photos (docs/HAND_TRACKING.md): the landmark model's hand-presence score is the
 * strongest signal (curtains <= 0.06, the sole of a foot up to 0.73, real hands 0.96-0.99), but a
 * foot can still pass a loose threshold. Three more checks catch what the score lets through:
 *  - size: the model's metric palm (wrist to middle knuckle) is 4-10 cm on real hands and 17 cm
 *    on a foot;
 *  - shape: finger chains and knuckle span within human proportions (garbage fits produce
 *    collapsed or stretched fingers);
 *  - reach: a hand is within arm's length of the head camera; feet seen from standing height and
 *    other people are farther.
 * A new hand must pass with a high score on consecutive frames before it is used; once confirmed
 * it is kept with a lower score so it does not flicker.
 */
class HandVerifier {
    var acquirePresence = 0.8f
    var keepPresence = 0.5f
    var confirmFrames = 2
    /** A score this high confirms a new hand at once (no waiting for a second frame). */
    var instantPresence = 0.9f
    var maxReachMeters = 1.0f
    var minReachMeters = 0.08f

    /** Last rejection, for the hand test screen ("pé?", "longe demais"...), empty when none. */
    @Volatile var lastRejection = ""; private set
    @Volatile var rejections = 0; private set

    private val streak = IntArray(2)
    private val confirmed = BooleanArray(2)
    private val gatedThisFrame = BooleanArray(2)

    fun reset() { streak.fill(0); confirmed.fill(false) }

    /** Call before the frame's gate() calls. */
    fun beginFrame() { gatedThisFrame.fill(false) }

    /**
     * Call after them: a side not looked at this frame lost its hand. A side that was looked at
     * keeps its count even when not yet confirmed (that is how a new hand gets confirmed).
     */
    fun endFrame() { for (i in 0..1) if (!gatedThisFrame[i]) { streak[i] = 0; confirmed[i] = false } }

    private fun reject(reason: String): String { lastRejection = reason; rejections++; return reason }

    /** Shape and size check on the model's metric landmarks (x, y, z per joint). Null = plausible. */
    fun shape(world: FloatArray): String? {
        fun d(a: Int, b: Int): Float {
            val dx = world[a * 3] - world[b * 3]; val dy = world[a * 3 + 1] - world[b * 3 + 1]; val dz = world[a * 3 + 2] - world[b * 3 + 2]
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
        val palm = d(HandJoint.WRIST, HandJoint.MIDDLE_MCP)
        if (palm.isNaN() || palm < 0.035f) return reject("pequeno demais para uma mão")
        if (palm > 0.135f) return reject("grande demais para uma mão (pé?)")
        for (mcp in intArrayOf(HandJoint.INDEX_MCP, HandJoint.MIDDLE_MCP, HandJoint.RING_MCP, HandJoint.PINKY_MCP)) {
            val chain = (d(mcp, mcp + 1) + d(mcp + 1, mcp + 2) + d(mcp + 2, mcp + 3)) / palm
            if (chain < 0.25f || chain > 1.9f) return reject("dedos fora da proporção de uma mão")
        }
        val span = d(HandJoint.INDEX_MCP, HandJoint.PINKY_MCP) / palm
        if (span < 0.12f || span > 1.1f) return reject("palma fora da proporção de uma mão")
        return null
    }

    /** Distance check on the wrist relative to the camera (metres). Null = within reach. */
    fun reach(wristX: Float, wristY: Float, wristZ: Float): String? {
        val dist = sqrt(wristX * wristX + wristY * wristY + wristZ * wristZ)
        if (dist.isNaN()) return null
        if (dist > maxReachMeters) return reject("longe demais para ser sua mão (pé ou outra pessoa?)")
        if (dist < minReachMeters) return reject("perto demais da câmera")
        return null
    }

    /**
     * Presence gate with hysteresis for the hand assigned to [side] (0 left, 1 right); NaN
     * presence (engines that do not report it) counts as passing. Returns true when the hand may
     * be used this frame.
     */
    fun gate(side: Int, presence: Float): Boolean {
        gatedThisFrame[side] = true
        val p = if (presence.isNaN()) 1f else presence
        if (confirmed[side]) {
            if (p >= keepPresence) return true
            confirmed[side] = false; streak[side] = 0
            reject("presença de mão baixa (%.2f)".format(p))
            return false
        }
        if (p >= instantPresence) { confirmed[side] = true; streak[side] = confirmFrames; return true }
        if (p >= acquirePresence) {
            streak[side]++
            if (streak[side] >= confirmFrames) { confirmed[side] = true; return true }
            return false
        }
        streak[side] = 0
        if (p >= 0.2f) reject("não parece uma mão (%.2f)".format(p))
        return false
    }

}
