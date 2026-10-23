package com.hyperxr

import com.hyperxr.handtracking.HandHold
import com.hyperxr.handtracking.HandJoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HandHoldTest {
    private fun hand(cx: Float, cy: Float) = FloatArray(HandJoint.COUNT * 3) { i -> when (i % 3) { 0 -> cx + (i % 7) * 0.01f; 1 -> cy + (i % 5) * 0.01f; else -> 0f } }

    @Test fun aHandInTheMiddleIsKeptLongAndOneAtTheEdgeGoesQuickly() {
        assertFalse(HandHold.nearEdge(hand(0.45f, 0.5f)))
        assertTrue(HandHold.nearEdge(hand(0.95f, 0.5f)))
        assertTrue(HandHold.nearEdge(hand(0.5f, 0.01f)))
        assertEquals(HandHold.INSIDE_NS, HandHold.timeoutNs(false))
        assertTrue(HandHold.timeoutNs(true) < HandHold.timeoutNs(false) / 2)
    }
}
