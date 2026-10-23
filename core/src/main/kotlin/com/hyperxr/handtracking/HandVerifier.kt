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
    var acquirePresence = 0.75f
    /** A confirmed hand is kept down to this score (motion blur lowers it for a frame or two). */
    var keepPresence = 0.3f
    var confirmFrames = 2
    /** A score this high confirms a new hand at once (no waiting for a second frame). */
    var instantPresence = 0.85f
    /** Depth from the palm's size can be 20% off: a stretched arm must never count as "too far". */
    var maxReachMeters = 1.3f
    var minReachMeters = 0.08f

    /** Last rejection, for the hand test screen ("pé?", "longe demais"...), empty when none. */
    @Volatile var lastRejection = ""; private set
    @Volatile var rejections = 0; private set

    /**
     * A hand confirmed this recently comes back with the keep score, not the acquisition score:
     * one bad frame (blur, a missed detection) used to drop it until it was held still again.
     */
    var graceNs = 600_000_000L

    private val streak = IntArray(2)
    private val confirmed = BooleanArray(2)
    private val gatedThisFrame = BooleanArray(2)
    private val lastConfirmedNs = LongArray(2)

    /** Set by [gate] when the only hand changed side (handedness flip): the side it came from, else -1. */
    var swappedFrom = -1; private set

    fun reset() { streak.fill(0); confirmed.fill(false); lastConfirmedNs.fill(0L) }

    /** Call before the frame's gate() calls. */
    fun beginFrame() { gatedThisFrame.fill(false); swappedFrom = -1 }

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
     * be used this frame. [onlyHand]: this is the frame's single hand, so a confirmed hand that
     * just changed side (left/right flip) keeps its confirmation instead of starting over.
     */
    fun gate(side: Int, presence: Float, nowNs: Long = System.nanoTime(), onlyHand: Boolean = false): Boolean {
        gatedThisFrame[side] = true
        val p = if (presence.isNaN()) 1f else presence
        fun ok(): Boolean { confirmed[side] = true; lastConfirmedNs[side] = nowNs; return true }
        if (confirmed[side]) {
            if (p >= keepPresence) return ok()
            confirmed[side] = false; streak[side] = 0
            reject("presença de mão baixa (%.2f)".format(p))
            return false
        }
        val other = 1 - side
        val recent = { s: Int -> lastConfirmedNs[s] != 0L && nowNs - lastConfirmedNs[s] < graceNs }
        // the same hand, briefly lost: back at once
        if (recent(side) && p >= keepPresence) { streak[side] = confirmFrames; return ok() }
        // the same hand, now called the other side
        if (onlyHand && (confirmed[other] || recent(other)) && !gatedThisFrame[other] && p >= keepPresence) {
            confirmed[other] = false; lastConfirmedNs[other] = 0L; streak[other] = 0
            swappedFrom = other
            streak[side] = confirmFrames
            return ok()
        }
        if (p >= instantPresence) { streak[side] = confirmFrames; return ok() }
        if (p >= acquirePresence) {
            streak[side]++
            if (streak[side] >= confirmFrames) return ok()
            return false
        }
        streak[side] = 0
        if (p >= 0.2f) reject("não parece uma mão (%.2f)".format(p))
        return false
    }

}
