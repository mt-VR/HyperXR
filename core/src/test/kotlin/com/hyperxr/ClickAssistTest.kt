package com.hyperxr

import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandState
import com.hyperxr.handtracking.Handedness
import com.hyperxr.handtracking.TrackingState
import com.hyperxr.input.DwellClick
import com.hyperxr.input.HandRay
import com.hyperxr.input.InteractionSurface
import com.hyperxr.input.PointerEvent
import com.hyperxr.input.PointerPhase
import com.hyperxr.input.PointerSource
import com.hyperxr.input.RayPointer
import com.hyperxr.input.TapLatch
import com.hyperxr.math.Pose
import com.hyperxr.math.Ray
import com.hyperxr.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClickAssistTest {
    private class Panel : InteractionSurface {
        override val surfaceId = "p"
        override val pose = Pose(Vec3(0f, 0f, -1f))
        override val widthMeters = 0.5f
        override val heightMeters = 0.5f
        val phases = mutableListOf<PointerPhase>()
        override fun onPointerEvent(event: PointerEvent) { if (event.phase != PointerPhase.HOVER && event.phase != PointerPhase.MOVE) phases += event.phase }
    }

    @Test fun tapShorterThanAFrameStillClicks() {
        val latch = TapLatch()
        val panel = Panel()
        val p = RayPointer(0, PointerSource.GAZE)
        val ray = Ray(Vec3(), Vec3(0f, 0f, -1f))
        p.update(ray, latch.next(), listOf(panel), 0)
        latch.onDown(); latch.onUp() // both between two frames
        p.update(ray, latch.next(), listOf(panel), 1)
        p.update(ray, latch.next(), listOf(panel), 2)
        p.update(ray, latch.next(), listOf(panel), 3)
        assertEquals(listOf(PointerPhase.DOWN, PointerPhase.UP), panel.phases)
    }

    @Test fun dwellClicksOnceThenRearmsAfterLeaving() {
        val d = DwellClick(dwellNs = 1_000_000_000L)
        val target = Any()
        var presses = 0
        var t = 0L
        repeat(200) { if (d.update(target, 0.5f, 0.5f, t)) presses++; t += 16_000_000L } // ~3.2 s
        assertEquals(1, presses)
        d.update(null, 0f, 0f, t); t += 16_000_000L
        repeat(80) { if (d.update(target, 0.5f, 0.5f, t)) presses++; t += 16_000_000L }
        assertEquals(2, presses)
    }

    @Test fun dwellRestartsWhenGazeMoves() {
        val d = DwellClick(dwellNs = 1_000_000_000L)
        val target = Any()
        var t = 0L
        var pressed = false
        repeat(50) { i -> pressed = pressed || d.update(target, 0.2f + (i % 2) * 0.1f, 0.5f, t); t += 30_000_000L }
        assertFalse(pressed)
        assertTrue(d.progress < 0.1f)
    }

    @Test fun dwellPressDrivesRayPointerClick() {
        val d = DwellClick(dwellNs = 200_000_000L)
        val panel = Panel()
        val p = RayPointer(0, PointerSource.GAZE)
        val ray = Ray(Vec3(), Vec3(0f, 0f, -1f))
        var t = 0L
        repeat(40) {
            p.update(ray, d.update(p.hovered, 0.5f, 0.5f, t), listOf(panel), t)
            t += 16_000_000L
        }
        assertEquals(listOf(PointerPhase.DOWN, PointerPhase.UP), panel.phases)
    }

    @Test fun handRayPointsThroughTheHand() {
        val hand = HandState(Handedness.RIGHT).apply { state = TrackingState.TRACKING }
        hand.joints[HandJoint.INDEX_MCP].set(0.2f, 1.3f, -0.5f)
        val head = Pose(Vec3(0f, 1.6f, 0f))
        val r = HandRay.compute(hand, head, rightHand = true, Ray())
        assertNotNull(r)
        assertTrue(r!!.direction.z < -0.9f)
        assertEquals(0.17f, r.origin.x, 1e-5f)
    }
}
