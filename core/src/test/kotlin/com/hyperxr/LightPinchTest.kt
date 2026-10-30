package com.hyperxr

import com.hyperxr.handtracking.Gesture
import com.hyperxr.handtracking.GestureRecognizer
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LightPinchTest {
    /** An open hand (palm 9 cm) with the thumb tip [gap] metres from the index tip. */
    private fun state(gap: Float): HandState {
        val s = HandState(com.hyperxr.handtracking.Handedness.RIGHT)
        val j = s.joints
        j[HandJoint.WRIST].set(0f, 0f, 0f)
        for ((f, x) in listOf(HandJoint.INDEX_MCP to -0.025f, HandJoint.MIDDLE_MCP to 0f, HandJoint.RING_MCP to 0.02f, HandJoint.PINKY_MCP to 0.038f)) {
            j[f].set(x, 0.09f, 0f)
            for (k in 1..3) j[f + k].set(x, 0.09f + 0.025f * k, 0f)
        }
        j[HandJoint.THUMB_CMC].set(-0.03f, 0.02f, 0f)
        j[HandJoint.THUMB_MCP].set(-0.05f, 0.05f, 0f)
        j[HandJoint.THUMB_IP].set(-0.06f, 0.09f, 0f)
        val tip = j[HandJoint.INDEX_TIP]
        j[HandJoint.THUMB_TIP].set(tip.x - gap, tip.y, tip.z)
        return s
    }

    private fun run(light: Boolean, gaps: List<Float>): Gesture {
        val g = GestureRecognizer().apply { lightPinch = light }
        var last = Gesture.NONE
        for (gap in gaps) { val s = state(gap); g.update(s); last = s.gesture }
        return last
    }

    // relaxed gap 9 cm (ratio 1.0), then the thumb closes to 4.5 cm: halfway, not touching
    private val halfway = List(20) { 0.09f } + List(4) { 0.045f }

    @Test
    fun halfwayClicksWithLightPinch() = assertEquals(Gesture.PINCH, run(true, halfway))

    @Test
    fun halfwayIsNotAPinchWithoutIt() = assertNotEquals(Gesture.PINCH, run(false, halfway))

    @Test
    fun relaxedHandDoesNotClick() = assertNotEquals(Gesture.PINCH, run(true, List(40) { 0.08f + 0.01f * (it % 3) }))
}
