package com.hyperxr

import com.hyperxr.ocean.LightningPath
import com.hyperxr.ocean.OceanWaves
import com.hyperxr.ocean.RoomScan
import com.hyperxr.ocean.RoomShape
import com.hyperxr.ocean.Sinking
import com.hyperxr.ocean.StormClock
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class OceanModelTest {
    @Test fun wavesFollowDeepWaterDispersionAndNeverFold() {
        for (storm in floatArrayOf(0f, 0.5f, 1f)) {
            val w = OceanWaves.pack(storm)
            var qka = 0f
            for (i in 0 until OceanWaves.COUNT) {
                val o = i * OceanWaves.STRIDE
                val k = w[o + 2]
                assertEquals(kotlin.math.sqrt(9.81f * k), w[o + 5], 1e-4f)
                qka += w[o + 4] * k * w[o + 3]
            }
            assertTrue("crests must not loop: $qka", qka < 1f)
            // the surface stays a surface: the Jacobian never goes negative
            val p = FloatArray(3)
            var minJ = 9f
            for (x in 0 until 60) for (z in 0 until 60) minJ = minOf(minJ, OceanWaves.displace(w, x * 1.7f, z * 1.3f, 3.1f, p))
            assertTrue("storm $storm jacobian $minJ", minJ > 0f)
        }
    }

    @Test fun stormSeaIsHigherThanCalm() {
        val calm = OceanWaves.maxHeight(OceanWaves.pack(0f))
        val storm = OceanWaves.maxHeight(OceanWaves.pack(1f))
        assertTrue(calm in 0.3f..1.5f)
        assertTrue(storm > calm * 3)
    }

    @Test fun heightAtFindsTheSurfaceOverAFixedPoint() {
        val w = OceanWaves.pack(0.7f)
        val p = FloatArray(3)
        // the displaced point lands somewhere; asking for the height there gives its height back
        OceanWaves.displace(w, 12f, -4f, 2f, p)
        assertEquals(p[1], OceanWaves.heightAt(w, p[0], p[2], 2f), 0.02f)
    }

    @Test fun lightningFlashesThenThunderFollowsAtTheSpeedOfSound() {
        val c = StormClock(3)
        var strike: StormClock.Strike? = null
        var t = 0f
        while (strike == null && t < 60f) { strike = c.update(0.05f, true); t += 0.05f }
        assertTrue(strike != null)
        assertTrue(c.flash() > 0.2f)
        assertEquals(strike!!.time + strike.distance / 343f, strike.thunderAt, 1e-4f)
        repeat(40) { c.update(0.05f, true) }
        assertTrue(c.flash() < 0.05f)
        // off: no strikes
        val q = StormClock(4)
        repeat(2000) { assertTrue(q.update(0.05f, false) == null) }
    }

    @Test fun boltGoesFromCloudToSea() {
        val paths = LightningPath.build(5)
        val main = paths[0]
        assertEquals(900f, main[1], 1e-3f)
        assertEquals(0f, main[main.size - 2], 1e-3f)
        assertTrue(paths.size >= 4)
    }

    @Test fun floorAndWallsFromDepthPoints() {
        val scan = RoomScan()
        val rnd = java.util.Random(1)
        val pts = FloatArray(3 * 9000)
        var n = 0
        fun put(x: Float, y: Float, z: Float) { pts[n * 3] = x; pts[n * 3 + 1] = y; pts[n * 3 + 2] = z; n++ }
        // floor at y = 0 (eyes at 1.6), a table top at 0.75, walls 2.5 m away
        repeat(4000) { put(rnd.nextFloat() * 4 - 2, (rnd.nextGaussian() * 0.01).toFloat(), rnd.nextFloat() * 4 - 2) }
        repeat(1500) { put(rnd.nextFloat() - 0.5f, 0.75f, rnd.nextFloat() - 1.5f) }
        repeat(3000) { val a = rnd.nextFloat() * 6.283f; put(2.5f * kotlin.math.cos(a), 0.5f + rnd.nextFloat() * 1.5f, 2.5f * kotlin.math.sin(a)) }
        repeat(500) { put(rnd.nextFloat() * 4 - 2, 2.6f, rnd.nextFloat() * 4 - 2) }
        scan.add(pts, n)
        val floor = scan.floorY(1.6f)
        assertEquals(0f, floor, 0.05f)
        assertEquals(2.5f, scan.wallDistance(0f, 0f, floor), 0.1f)
        assertEquals(2.6f, scan.ceilingY(1.6f), 0.05f)
        assertTrue(RoomScan().floorY(1.6f).isNaN())
    }

    @Test fun roomShapeHitsFloorWallOrCeiling() {
        // straight down from 1.6 m: the floor at 1.6 m
        assertEquals(1.6f, RoomShape.distance(0f, 1.6f, 0f, 0f, -1f, 0f, 0f, 2.6f, 2.5f), 1e-4f)
        // level: the wall
        assertEquals(2.5f, RoomShape.distance(0f, 1.6f, 0f, 0f, 0f, -1f, 0f, 2.6f, 2.5f), 1e-4f)
        // up: the ceiling, 1 m above
        assertEquals(1f, RoomShape.distance(0f, 1.6f, 0f, 0f, 1f, 0f, 0f, 2.6f, 2.5f), 1e-4f)
    }

    @Test fun sinkingEasesInAndOut() {
        assertEquals(0f, Sinking.progress(0f, 5f), 1e-6f)
        assertEquals(1f, Sinking.progress(5f, 5f), 1e-6f)
        assertEquals(0.5f, Sinking.progress(2.5f, 5f), 1e-6f)
        assertTrue(Sinking.progress(0.5f, 5f) < 0.01f)
        assertTrue(abs(Sinking.progress(4.5f, 5f) - 1f) < 0.01f)
    }

    @Test fun skyLooksRightForTheTimeOfDay() {
        val s = com.hyperxr.ocean.SkyState()
        com.hyperxr.ocean.SkyLook.compute(1f, 0f, 0f, s)
        assertTrue(s.sunDir[1] > 0.8f)                       // midday: sun high
        assertTrue(s.zenith[2] > s.zenith[0])                // blue sky
        assertTrue(s.night == 0f)
        com.hyperxr.ocean.SkyLook.compute(0.2f, 0f, 0f, s)
        assertTrue(s.horizon[0] > s.horizon[2])              // sunset: warm horizon
        com.hyperxr.ocean.SkyLook.compute(0f, 0f, 0f, s)
        assertTrue(s.night > 0.9f && s.sunDir[1] > 0.5f)     // night: the moon lights from above
        com.hyperxr.ocean.SkyLook.compute(1f, 1f, 0f, s)
        assertTrue(s.cloud > 0.9f && s.ambient < 0.6f)       // storm: overcast and darker
        val d = kotlin.math.sqrt(s.sunDir[0] * s.sunDir[0] + s.sunDir[1] * s.sunDir[1] + s.sunDir[2] * s.sunDir[2])
        assertEquals(1f, d, 1e-4f)
    }
}
