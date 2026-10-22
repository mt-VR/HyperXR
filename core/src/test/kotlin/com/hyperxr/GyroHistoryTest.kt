package com.hyperxr

import com.hyperxr.headtracking.GyroHistory
import com.hyperxr.math.Quat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GyroHistoryTest {
    private fun angle(q: Quat) = 2f * kotlin.math.acos(kotlin.math.abs(q.w).coerceAtMost(1f))

    @Test fun constantTurnBetweenTwoTimes() {
        val g = GyroHistory()
        // 1 rad/s about Y, sampled at 500 Hz for 0.4 s
        for (i in 0..200) g.add(1_000_000_000L + i * 2_000_000L, 0f, 1f, 0f)
        val d = Quat()
        assertTrue(g.delta(1_100_000_000L, 1_300_000_000L, d))
        assertEquals(0.2f, angle(d), 1e-3f)
        assertTrue(d.y > 0f)
        // interpolated between samples
        assertTrue(g.delta(1_100_000_000L, 1_101_000_000L, d))
        assertEquals(0.001f, angle(d), 1e-4f)
    }

    @Test fun extrapolatesPastTheLastSample() {
        val g = GyroHistory()
        for (i in 0..100) g.add(i * 2_000_000L, 2f, 0f, 0f)
        val d = Quat()
        // 20 ms beyond the newest sample at 2 rad/s: 0.04 rad more
        assertTrue(g.delta(100 * 2_000_000L, 100 * 2_000_000L + 20_000_000L, d))
        assertEquals(0.04f, angle(d), 1e-3f)
        // extrapolation is capped (a stalled sensor must not spin the view)
        assertTrue(g.delta(100 * 2_000_000L, 100 * 2_000_000L + 500_000_000L, d))
        assertEquals(0.12f, angle(d), 1e-3f)
    }
}
