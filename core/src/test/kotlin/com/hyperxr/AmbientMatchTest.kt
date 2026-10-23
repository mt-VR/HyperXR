package com.hyperxr

import com.hyperxr.emulators.EmulatorCatalog
import com.hyperxr.emulators.RetroPad
import com.hyperxr.environments.AmbientMatch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AmbientMatchTest {
    @Test fun nightStreetIsPurpleButNotBlack() {
        val l = AmbientMatch.lightFor(30 / 255f, 8 / 255f, 62 / 255f)
        assertTrue("blue dominates", l.ambient[2] > l.ambient[0] && l.ambient[0] > l.ambient[1])
        assertTrue("still visible ${l.ambient.toList()}", l.ambient.max() >= 0.2f)
    }

    @Test fun sunsetIsWarm() {
        val l = AmbientMatch.lightFor(161 / 255f, 127 / 255f, 102 / 255f)
        assertTrue(l.lightColor[0] > l.lightColor[2])
        assertTrue(l.ambient[0] > l.ambient[2])
    }

    @Test fun clockPicksTheEnvironment() {
        assertEquals("bundled:night_street", AmbientMatch.forTime(23, 10))
        assertEquals("bundled:night_street", AmbientMatch.forTime(2))
        assertEquals("bundled:vr_studio", AmbientMatch.forTime(10, 30))
        assertEquals("bundled:penthouse_sunset", AmbientMatch.forTime(18))
        assertEquals("bundled:future_plaza", AmbientMatch.forTime(8))
        // every minute of the day maps to one of the four
        val ids = (0 until 24 * 60).map { AmbientMatch.forTime(it / 60, it % 60) }.toSet()
        assertEquals(4, ids.size)
    }

    @Test fun romsGoToTheRightCore() {
        assertEquals("nes", EmulatorCatalog.forFile("Jogo.NES")?.id)
        assertEquals("gb", EmulatorCatalog.forFile("a.gbc")?.id)
        assertEquals("gba", EmulatorCatalog.forFile("b.gba")?.id)
        assertNull(EmulatorCatalog.forFile("c.iso"))
        assertTrue(EmulatorCatalog.credits().all { it.isNotBlank() })
        assertEquals((1 shl RetroPad.RIGHT) or (1 shl RetroPad.UP), RetroPad.directions(0.9f, -0.8f))
        assertEquals(0, RetroPad.directions(0.2f, 0.3f))
    }
}
