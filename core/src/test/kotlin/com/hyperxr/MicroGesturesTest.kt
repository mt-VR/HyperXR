package com.hyperxr

import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandState
import com.hyperxr.handtracking.Handedness
import com.hyperxr.handtracking.MicroGestures
import com.hyperxr.handtracking.TrackingState
import com.hyperxr.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicroGesturesTest {
    /** Resting hand: palm 9 cm, index finger along +Y from (0.01, 0.09, 0) to (0.01, 0.18, 0). */
    private fun hand(thumbAlong: Float?, scale: Float = 1f): HandState {
        val h = HandState(Handedness.RIGHT)
        h.state = TrackingState.TRACKING
        h.joints[HandJoint.WRIST].set(0f, 0f, 0f)
        h.joints[HandJoint.MIDDLE_MCP].set(0f, 0.09f, 0f)
        h.joints[HandJoint.INDEX_MCP].set(0.01f, 0.09f, 0f)
        h.joints[HandJoint.INDEX_TIP].set(0.01f, 0.18f, 0f)
        // thumb on the side of the index (1 cm away) at the given fraction, or far away
        if (thumbAlong != null) h.joints[HandJoint.THUMB_TIP].set(0.02f, 0.09f + 0.09f * thumbAlong, 0f)
        else h.joints[HandJoint.THUMB_TIP].set(-0.06f, 0.06f, 0.03f)
        for (j in h.joints) j.scale(scale)
        return h
    }

    @Test fun slidingTheThumbScrolls() {
        for (scale in listOf(1f, 0.5f)) { // scale-free: works with a mis-scaled skeleton too
            val m = MicroGestures()
            var t = 0L
            var total = 0f
            for (k in 0..20) {
                val e = m.update(hand(0.25f + k * 0.025f, scale), t)
                if (e is MicroGestures.Event.Scroll) total += e.delta
                t += 16_000_000L
            }
            assertTrue("scale $scale total $total", total > 0.3f)
        }
    }

    @Test fun doubleTapGoesBackButSingleTapAndSlideDoNot() {
        val m = MicroGestures()
        var t = 0L
        fun frame(along: Float?) = m.update(hand(along), t).also { t += 16_000_000L }
        val events = mutableListOf<MicroGestures.Event?>()
        repeat(5) { events += frame(null) }
        repeat(4) { events += frame(0.5f) }   // tap 1
        repeat(6) { events += frame(null) }
        repeat(4) { events += frame(0.5f) }   // tap 2
        repeat(3) { events += frame(null) }
        assertEquals(1, events.count { it == MicroGestures.Event.DoubleTap })
        // a single tap followed by a long pause is not a double tap
        repeat(4) { events += frame(0.5f) }
        repeat(60) { events += frame(null) }
        repeat(4) { events += frame(0.5f) }
        repeat(3) { events += frame(null) }
        assertEquals(1, events.count { it == MicroGestures.Event.DoubleTap })
    }

    @Test fun pinchAtTheFingertipIsNotAMicrogesture() {
        val m = MicroGestures()
        var t = 0L
        repeat(20) { assertEquals(null, m.update(hand(1.0f), t)); t += 16_000_000L }
        assertTrue(!m.touching)
    }
}
