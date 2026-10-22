package com.hyperxr.handtracking

import com.hyperxr.math.Vec3

/**
 * Thumb-on-index microgestures, usable with the hand resting (no arm raised):
 *  - slide the thumb tip along the side of the index finger -> scroll
 *  - tap the thumb on the index finger twice quickly -> back
 *
 * The thumb counts as touching when its tip is within [contactRatio] x palm length of the index
 * finger (MCP->tip segment) and away from the fingertip itself (that is a pinch, handled elsewhere).
 * Positions along the finger are normalised by the finger length, so the gesture is scale-free.
 */
class MicroGestures(
    var contactRatio: Float = 0.22f,
    var releaseRatio: Float = 0.32f,
    var tapMaxNs: Long = 260_000_000L,
    var doubleTapNs: Long = 420_000_000L,
    var tapMaxTravel: Float = 0.08f,
) {
    sealed class Event {
        /** Positive = thumb moved towards the fingertip; in units of index-finger length. */
        data class Scroll(val delta: Float) : Event()
        object DoubleTap : Event()
    }

    var touching = false
        private set
    private var lastS = 0f
    private var downNs = 0L
    private var downS = 0f
    private var travel = 0f
    private var lastTapNs = Long.MIN_VALUE / 2
    private val seg = Vec3()
    private val rel = Vec3()

    fun reset() { touching = false; lastTapNs = Long.MIN_VALUE / 2 }

    fun update(hand: HandState, nowNs: Long): Event? {
        if (!hand.isUsable) { touching = false; return null }
        val j = hand.joints
        val palm = j[HandJoint.WRIST].distanceTo(j[HandJoint.MIDDLE_MCP]).coerceAtLeast(1e-4f)
        val a = j[HandJoint.INDEX_MCP]
        val b = j[HandJoint.INDEX_TIP]
        val thumb = j[HandJoint.THUMB_TIP]
        Vec3.sub(b, a, seg)
        val len2 = seg.lengthSq().coerceAtLeast(1e-8f)
        Vec3.sub(thumb, a, rel)
        val s = (rel.dot(seg) / len2).coerceIn(-0.2f, 1.2f)
        // distance from the thumb tip to the index segment
        val cx = a.x + seg.x * s.coerceIn(0f, 1f); val cy = a.y + seg.y * s.coerceIn(0f, 1f); val cz = a.z + seg.z * s.coerceIn(0f, 1f)
        val dx = thumb.x - cx; val dy = thumb.y - cy; val dz = thumb.z - cz
        val dist = kotlin.math.sqrt(dx * dx + dy * dy + dz * dz) / palm
        val onSide = s in 0.1f..0.82f // near the tip it is a pinch, not a microgesture
        val wasTouching = touching
        touching = if (touching) dist < releaseRatio && s in 0.0f..0.92f else dist < contactRatio && onSide

        if (touching && !wasTouching) {
            downNs = nowNs; downS = s; lastS = s; travel = 0f
            return null
        }
        if (touching) {
            val delta = s - lastS
            lastS = s
            travel += kotlin.math.abs(delta)
            // ignore tiny tremor; report deliberate slides
            return if (kotlin.math.abs(delta) > 0.004f && travel > tapMaxTravel) Event.Scroll(delta) else null
        }
        if (wasTouching) {
            val quick = nowNs - downNs <= tapMaxNs && travel <= tapMaxTravel
            if (quick) {
                if (nowNs - lastTapNs <= doubleTapNs) { lastTapNs = Long.MIN_VALUE / 2; return Event.DoubleTap }
                lastTapNs = nowNs
            }
        }
        return null
    }
}
