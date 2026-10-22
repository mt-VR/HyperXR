package com.hyperxr

import com.hyperxr.input.BoundaryMonitor
import com.hyperxr.input.DragDrop
import com.hyperxr.input.RadialMenu
import com.hyperxr.math.Pose
import com.hyperxr.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GesturesTest {
    @Test fun dragEndsOnlyWhenItsOwnPointerReleases() {
        val d = DragDrop<String>()
        d.begin("https://example.com", pointer = 5)
        assertNull(d.update(0, false, "other"))   // a different pointer
        assertNull(d.update(5, true, "windowA"))  // still held
        val drop = d.update(5, false, "windowB")!!
        assertEquals("https://example.com", drop.payload)
        assertEquals("windowB", drop.target)
        assertTrue(!d.active)
    }

    private val head = Pose(Vec3(0f, 1.6f, 0f))

    @Test fun radialMenuOpensOnHoldAndConfirmsByDirection() {
        val m = RadialMenu(listOf("back", "home", "keyboard", "capture"))
        val p = Vec3(0.1f, 1.3f, -0.4f)
        var t = 0L
        repeat(40) { assertNull(m.update(true, p, head, overTarget = false, nowNs = t)); t += 16_000_000L }
        assertEquals(RadialMenu.Phase.OPEN, m.phase)
        // move the hand right (item 1 is at 3 o'clock) while pinching, then release
        val right = m.plane.transformPoint(Vec3(0.06f, 0f, 0f))
        m.update(true, right, head, false, t); t += 16_000_000L
        assertEquals(1, m.selected)
        assertEquals(1, m.update(false, right, head, false, t))
        assertEquals(RadialMenu.Phase.IDLE, m.phase)
    }

    @Test fun radialMenuCancelsInCentreAndIgnoresPinchesOnButtons() {
        val m = RadialMenu(listOf("a", "b", "c", "d"))
        val p = Vec3(0f, 1.3f, -0.4f)
        var t = 0L
        repeat(40) { m.update(true, p, head, overTarget = true, nowNs = t); t += 16_000_000L }
        assertEquals(RadialMenu.Phase.IDLE, m.phase) // that pinch was clicking a button
        repeat(40) { m.update(true, p, head, overTarget = false, nowNs = t); t += 16_000_000L }
        assertNull(m.update(false, p, head, false, t)) // released in the centre: cancel
        // a quick pinch-drag never opens the menu
        m.update(true, p, head, false, t); t += 16_000_000L
        m.update(true, Vec3(0.1f, 1.3f, -0.4f), head, false, t)
        assertEquals(RadialMenu.Phase.IDLE, m.phase)
    }

    @Test fun radialSectors() {
        val m = RadialMenu(listOf("up", "right", "down", "left"))
        assertEquals(0, m.sectorOf(0f, 1f))
        assertEquals(1, m.sectorOf(1f, 0f))
        assertEquals(2, m.sectorOf(0f, -1f))
        assertEquals(3, m.sectorOf(-1f, 0.1f))
    }

    @Test fun boundaryNearestIgnoresFloorAndNoise() {
        val w = 10; val h = 10
        val img = ByteArray(w * h) { 250.toByte() } // everything 2.5 m away
        for (x in 0 until w) for (y in 8 until 10) img[y * w + x] = 20 // floor close to the feet
        assertEquals(2.5f, BoundaryMonitor.nearest(img, w, h), 1e-6f)
        for (x in 3..6) for (y in 2..5) img[y * w + x] = 50 // a wall 0.5 m ahead
        assertEquals(0.5f, BoundaryMonitor.nearest(img, w, h), 1e-6f)
        assertEquals(0f, BoundaryMonitor.fade(2.5f), 0f)
        assertEquals(1f, BoundaryMonitor.fade(0.3f), 0f)
        assertTrue(BoundaryMonitor.fade(0.6f) in 0.3f..0.9f)
        assertTrue(BoundaryMonitor.nearest(ByteArray(100), w, h).isNaN()) // no depth data
    }
}
