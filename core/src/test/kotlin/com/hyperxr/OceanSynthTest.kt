package com.hyperxr

import com.hyperxr.ocean.OceanSynth
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sqrt

class OceanSynthTest {
    private fun rms(s: OceanSynth, seconds: Float, rate: Int = 48000): Pair<Float, Float> {
        val n = (seconds * rate).toInt()
        val buf = FloatArray(n * 2)
        s.render(buf, n)
        var sum = 0.0; var peak = 0f
        for (v in buf) { assertTrue(v.isFinite()); sum += v * v; peak = maxOf(peak, kotlin.math.abs(v)) }
        return sqrt(sum / buf.size).toFloat() to peak
    }

    @Test fun seaIsAudibleAndNeverClips() {
        val s = OceanSynth()
        val (r, peak) = rms(s, 12f)
        assertTrue("sea rms $r", r in 0.02f..0.5f)
        assertTrue(peak < 1f)
    }

    @Test fun volumeZeroIsSilent() {
        val s = OceanSynth().also { it.volume = 0f }
        assertTrue(rms(s, 1f).first == 0f)
    }

    @Test fun rainAndThunderAddSound() {
        val calm = rms(OceanSynth(seed = 9).also { it.sea = 0f }, 3f).first
        val rain = rms(OceanSynth(seed = 9).also { it.sea = 0f; it.rain = 1f }, 3f).first
        assertTrue("rain $rain vs $calm", rain > calm + 0.01f)
        val t = OceanSynth(seed = 9).also { it.sea = 0f; it.thunder(600f, 0.1f) }
        val (thunder, peak) = rms(t, 4f)
        assertTrue("thunder $thunder", thunder > 0.02f)
        assertTrue(peak < 1f)
    }
}
