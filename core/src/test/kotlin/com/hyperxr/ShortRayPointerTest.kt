package com.hyperxr

import com.hyperxr.input.ShortRayPointer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ShortRayPointerTest {
    @Test fun plainPinchWithHysteresisAndNoLatch() {
        val p = ShortRayPointer()
        assertFalse(p.pinch(0.9f))
        assertFalse(p.pinch(0.45f))           // between the thresholds: not yet
        assertTrue(p.pinch(0.35f))            // closed: click
        assertTrue(p.pinch(0.50f))            // opening a little keeps it
        assertFalse(p.pinch(0.60f))           // one open frame releases it (no latch)
        assertTrue(p.pinch(0.30f))
        assertFalse(p.pinch(Float.NaN))       // hand lost: released
        assertEquals(0f, p.strength, 0f)
    }
}
