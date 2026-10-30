package com.hyperxr

import com.hyperxr.headtracking.LeanTracker
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class LeanTrackerTest {
    private val dt = 1f / 200f

    @Test
    fun leanForwardMovesTheViewThenItSettles() {
        val t = LeanTracker()
        // 0.2 s accelerating forward (-z) at 1.5 m/s², 0.2 s braking
        repeat(40) { t.update(0f, 0f, -1.5f, dt) }
        repeat(40) { t.update(0f, 0f, 1.5f, dt) }
        val peak = t.offset[2]
        assertTrue("moved forward: $peak", peak < -0.02f)
        repeat(1200) { t.update(0f, 0f, 0f, dt) }   // 6 s still
        assertTrue("eased back home: ${t.offset[2]}", abs(t.offset[2]) < 0.005f)
    }

    @Test
    fun sensorBiasDoesNotDrift() {
        val t = LeanTracker()
        repeat(4000) { t.update(0.08f, 0f, -0.06f, dt) }   // 20 s of a small constant bias
        assertTrue("no drift: ${t.offset.joinToString()}", t.offset.all { abs(it) < 0.02f })
    }
}
