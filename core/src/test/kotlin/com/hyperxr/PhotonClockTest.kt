package com.hyperxr

import com.hyperxr.headtracking.PhotonClock
import org.junit.Assert.assertEquals
import org.junit.Test

class PhotonClockTest {
    private val p = 16_666_667L

    @Test fun targetIsLockedToVsync() {
        val c = PhotonClock()
        c.periodNs = p
        c.onVsync(1_000_000_000L)
        repeat(20) { c.onRendered(5_000_000L) }
        // anywhere early in the cycle the frame makes the next vsync: same photon time
        val a = c.photon(1_000_000_000L + 1_000_000L)
        val b = c.photon(1_000_000_000L + 9_000_000L)
        assertEquals(a, b)
        assertEquals(1_000_000_000L + p + p + p / 2, a)
        // too late for this vsync: one period later, not a wobble inside the period
        val late = c.photon(1_000_000_000L + 13_000_000L)
        assertEquals(a + p, late)
    }

    @Test fun withoutVsyncFallsBackToTwoPeriods() {
        val c = PhotonClock()
        assertEquals(500L + 2 * c.periodNs, c.photon(500L))
    }

    @Test fun slowGpuMovesTheTargetBack() {
        val c = PhotonClock()
        c.periodNs = p
        val v = 1_000_000_000L
        c.onVsync(v)
        repeat(40) { c.onRendered(20_000_000L) }
        val t = c.photon(v + 1_000_000L)
        // ready at ~21 ms: picked up at the second vsync (33.3 ms), shown a period and a half later
        assertEquals(v + 2 * p + p + p / 2, t)
    }
}
