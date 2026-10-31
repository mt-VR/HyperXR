package com.hyperxr.input

import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandState
import com.hyperxr.math.Pose
import com.hyperxr.math.Ray
import com.hyperxr.math.Vec3

/**
 * The hand's short pointer: a short visible ray from the index knuckle that aims along the
 * shoulder-to-hand line (the line barely moves while the fingers close, unlike the finger itself),
 * clicked with a plain pinch.
 *
 * The pinch is the thumb-index gap over the palm length with two thresholds (closing below
 * [pinchOn] presses, opening above [pinchOff] releases): no latch, no frame counting. While a
 * pinch starts the hand drifts a little, so the aim of a click is the aim from [lookbackNs]
 * earlier, and a held pinch moves from there with the hand (drags still work).
 */
class ShortRayPointer(
    var pinchOn: Float = 0.40f,
    var pinchOff: Float = 0.55f,
    var lookbackNs: Long = 120_000_000L,
) {
    /** Length of the visible ray, metres. */
    val length = 0.15f

    var pressed = false; private set
    /** 0 (open) .. 1 (about to click), for the ray's colour. */
    var strength = 0f; private set

    private val size = 16
    private val dirs = Array(size) { Vec3() }
    private val times = LongArray(size)
    private var count = 0
    private var slot = 0
    private val frozen = Vec3()
    private val ref = Vec3()
    private val tmp = Vec3()

    fun reset() { pressed = false; count = 0; strength = 0f }

    /** Updates the pinch from the gap ratio (NaN: no hand); returns whether it is pressed. */
    fun pinch(ratio: Float): Boolean {
        if (!ratio.isFinite()) { pressed = false; strength = 0f; return false }
        pressed = if (pressed) ratio < pinchOff else ratio < pinchOn
        strength = (1f - (ratio - pinchOn) / (1f - pinchOn)).coerceIn(0f, 1f)
        return pressed
    }

    private fun past(t: Long, out: Vec3): Vec3 {
        var best = (slot - 1 + size) % size
        for (k in 0 until count) {
            val i = (slot - 1 - k + size) % size
            best = i
            if (times[i] <= t) break
        }
        return out.set(dirs[best])
    }

    /**
     * The ray of [hand] into [out] (null when the hand is not usable): origin at the index
     * knuckle, direction from the shoulder through the hand, held at the pre-pinch aim while pressed.
     */
    fun update(hand: HandState, head: Pose, rightHand: Boolean, now: Long, out: Ray): Ray? {
        if (!hand.isUsable) { reset(); return null }
        val wasPressed = pressed
        pinch(hand.pinchRatio)
        val r = HandRay.compute(hand, head, rightHand, out) ?: run { reset(); return null }
        dirs[slot].set(r.direction); times[slot] = now
        slot = (slot + 1) % size
        if (count < size) count++
        if (pressed && !wasPressed) { past(now - lookbackNs, frozen); ref.set(r.direction) }
        if (pressed) r.direction.set(frozen).add(tmp.set(r.direction).sub(ref)).normalize()
        r.origin.set(hand.joints[HandJoint.INDEX_MCP])
        return r
    }
}
