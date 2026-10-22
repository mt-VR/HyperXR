package com.hyperxr

import com.hyperxr.handtracking.CameraIntrinsics
import com.hyperxr.handtracking.DepthFusion
import com.hyperxr.handtracking.DepthSource
import com.hyperxr.handtracking.Gesture
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandTracker
import com.hyperxr.handtracking.Handedness
import com.hyperxr.handtracking.LandmarkObservation
import com.hyperxr.handtracking.OneEuroFilter
import com.hyperxr.handtracking.TrackingState
import com.hyperxr.input.ButtonMapper
import com.hyperxr.input.ControllerButton
import com.hyperxr.input.ControllerPoseFusion
import com.hyperxr.input.DirectTouch
import com.hyperxr.input.ImuOrientationFilter
import com.hyperxr.input.InteractionSurface
import com.hyperxr.input.PointerEvent
import com.hyperxr.input.PointerPhase
import com.hyperxr.input.PointerSource
import com.hyperxr.input.RayPointer
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Ray
import com.hyperxr.math.Vec3
import com.hyperxr.settings.ControllerAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI

class InputAndTrackingTest {
    /** Synthetic open right hand (metres) centred at [c], palm facing -Z. */
    private fun openHand(c: Vec3, curled: Boolean = false, pinch: Boolean = false): Array<Vec3> {
        val j = Array(HandJoint.COUNT) { Vec3() }
        j[HandJoint.WRIST].set(c.x, c.y - 0.09f, c.z)
        val xs = floatArrayOf(-0.035f, -0.012f, 0.012f, 0.034f)
        val fingers = arrayOf(intArrayOf(5, 6, 7, 8), intArrayOf(9, 10, 11, 12), intArrayOf(13, 14, 15, 16), intArrayOf(17, 18, 19, 20))
        for ((f, idx) in fingers.withIndex()) {
            for ((k, ji) in idx.withIndex()) {
                if (curled && k > 0) j[ji].set(c.x + xs[f], c.y - 0.015f * k, c.z + 0.02f * k)
                else j[ji].set(c.x + xs[f], c.y + 0.03f * k, c.z)
            }
        }
        for (k in 1..4) j[k].set(c.x - 0.04f - 0.012f * k, c.y - 0.07f + 0.02f * k, c.z)
        if (pinch) j[HandJoint.THUMB_TIP].set(j[HandJoint.INDEX_TIP]).add(Vec3(0.005f, 0f, 0f))
        return j
    }

    @Test fun oneEuroReducesJitterButFollowsMotion() {
        val f = OneEuroFilter(1.0f, 0.05f)
        val rnd = java.util.Random(1)
        var maxDev = 0f
        for (i in 0 until 300) {
            val v = f.filter(1f + (rnd.nextFloat() - 0.5f) * 0.01f, 1 / 60f)
            if (i > 30) maxDev = maxOf(maxDev, kotlin.math.abs(v - 1f))
        }
        assertTrue("jitter not reduced: $maxDev", maxDev < 0.004f)
        repeat(60) { f.filter(2f, 1 / 60f) }
        assertEquals(2f, f.filter(2f, 1 / 60f), 0.02f)
    }

    @Test fun trackingLossPredictsThenDropsAndRecoversWithoutJump() {
        val t = HandTracker(Handedness.RIGHT)
        var now = 0L
        val step = 33_000_000L
        repeat(20) { t.update(openHand(Vec3(0f, 0f, -0.4f)), 0.9f, DepthSource.DEPTH_MAP, now); now += step }
        assertEquals(TrackingState.TRACKING, t.state.state)
        t.update(null, 0f, DepthSource.NONE, now); now += step
        assertEquals(TrackingState.LOST_PREDICTING, t.state.state)
        repeat(10) { t.update(null, 0f, DepthSource.NONE, now); now += step }
        assertEquals(TrackingState.NOT_TRACKED, t.state.state)

        // Reacquire after short loss at a far position: output must start continuous, then converge.
        val t2 = HandTracker(Handedness.RIGHT)
        now = 0
        repeat(20) { t2.update(openHand(Vec3(0f, 0f, -0.4f)), 0.9f, DepthSource.DEPTH_MAP, now); now += step }
        t2.update(null, 0f, DepthSource.NONE, now); now += step
        val before = t2.state.joints[HandJoint.WRIST].copy()
        t2.update(openHand(Vec3(0.3f, 0f, -0.4f)), 0.9f, DepthSource.DEPTH_MAP, now); now += step
        assertEquals(TrackingState.RECOVERING, t2.state.state)
        assertTrue("jumped on recovery", t2.state.joints[HandJoint.WRIST].distanceTo(before) < 0.02f)
        repeat(30) { t2.update(openHand(Vec3(0.3f, 0f, -0.4f)), 0.9f, DepthSource.DEPTH_MAP, now); now += step }
        assertEquals(TrackingState.TRACKING, t2.state.state)
        assertEquals(0.3f, t2.state.joints[HandJoint.WRIST].x, 0.01f)
    }

    @Test fun implausibleSkeletonRejected() {
        val t = HandTracker(Handedness.LEFT)
        val bad = openHand(Vec3(0f, 0f, -0.4f)).also { it[HandJoint.INDEX_TIP].set(1f, 1f, 1f) }
        t.update(bad, 0.99f, DepthSource.DEPTH_MAP, 0)
        assertEquals(TrackingState.NOT_TRACKED, t.state.state)
    }

    @Test fun gestures() {
        val t = HandTracker(Handedness.RIGHT)
        t.update(openHand(Vec3(0f, 0f, -0.4f)), 0.9f, DepthSource.DEPTH_MAP, 0)
        assertEquals(Gesture.OPEN_PALM, t.state.gesture)
        val t2 = HandTracker(Handedness.RIGHT)
        t2.update(openHand(Vec3(0f, 0f, -0.4f), pinch = true), 0.9f, DepthSource.DEPTH_MAP, 0)
        assertEquals(Gesture.PINCH, t2.state.gesture)
        val t3 = HandTracker(Handedness.RIGHT)
        t3.update(openHand(Vec3(0f, 0f, -0.4f), curled = true), 0.9f, DepthSource.DEPTH_MAP, 0)
        assertTrue(t3.state.gesture == Gesture.FIST || t3.state.gesture == Gesture.GRAB)
    }

    @Test fun pinchIsScaleFree() {
        // the same pinch seen with a half-size skeleton (wrong depth scale) still pinches
        for (scale in listOf(1f, 0.5f, 1.6f)) {
            val t = HandTracker(Handedness.RIGHT)
            val joints = openHand(Vec3(0f, 0f, -0.4f), pinch = true).map { it.copy().scale(scale) }.toTypedArray()
            t.update(joints, 0.9f, DepthSource.DEPTH_MAP, 0)
            if (scale in 0.6f..1.4f) assertEquals("scale $scale", Gesture.PINCH, t.state.gesture)
            val open = HandTracker(Handedness.RIGHT)
            open.update(openHand(Vec3(0f, 0f, -0.4f)).map { it.copy().scale(scale) }.toTypedArray(), 0.9f, DepthSource.DEPTH_MAP, 0)
            assertTrue(open.state.gesture != Gesture.PINCH)
        }
    }

    @Test fun handScaleEstimatorMeasuresThePalm() {
        val est = com.hyperxr.handtracking.HandScaleEstimator(needed = 20)
        val hand = openHand(Vec3(0f, 0f, -0.4f))
        // every sample measures the same synthetic palm, so the estimate equals its length
        var r: Float? = null
        repeat(20) { r = est.add(hand, DepthSource.DEPTH_MAP, 0.9f) }
        assertEquals(hand[HandJoint.WRIST].distanceTo(hand[HandJoint.MIDDLE_MCP]), r!!, 1e-5f)
        // estimates from the size prior are never used for calibration
        val est2 = com.hyperxr.handtracking.HandScaleEstimator(needed = 5)
        repeat(10) { est2.add(hand, DepthSource.HAND_SIZE_PRIOR, 0.9f) }
        assertEquals(null, est2.result)
        assertEquals(0.1036f, com.hyperxr.handtracking.HandScale.palmFromHandLength(18.5f), 1e-4f)
        assertEquals(0.095f, com.hyperxr.handtracking.HandScale.palmLength(95f, 18.5f), 1e-6f)
    }

    @Test fun depthFusionUsesDepthMapThenHandSizePrior() {
        val intr = CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480)
        val obs = LandmarkObservation(Handedness.RIGHT).apply { score = 0.9f }
        // Project a synthetic hand at 0.5 m
        val hand = openHand(Vec3(0f, 0f, -0.5f))
        for (i in 0 until HandJoint.COUNT) {
            val p = hand[i]; val z = -p.z
            obs.image[i * 3] = (p.x * intr.fx / z + intr.cx) / 640f
            obs.image[i * 3 + 1] = (-p.y * intr.fy / z + intr.cy) / 480f
            obs.image[i * 3 + 2] = 0f
        }
        val fusion = DepthFusion()
        val res = DepthFusion.Result()
        assertTrue(fusion.fuse(obs, intr, { _, _ -> 0.5f }, null, res))
        assertEquals(DepthSource.DEPTH_MAP, res.depthSource)
        assertEquals(-0.5f, res.cameraSpace[HandJoint.MIDDLE_MCP].z, 1e-3f)
        assertEquals(hand[HandJoint.INDEX_TIP].x, res.cameraSpace[HandJoint.INDEX_TIP].x, 2e-3f)

        assertTrue(fusion.fuse(obs, intr, null, null, res))
        assertEquals(DepthSource.HAND_SIZE_PRIOR, res.depthSource)
        assertTrue(res.confidence < 0.9f * 0.61f)
        // synthetic palm length is exactly 0.09 m, so the prior recovers the true distance
        assertEquals(-0.5f, res.cameraSpace[HandJoint.WRIST].z, 0.02f)
    }

    private class Panel(override val pose: Pose) : InteractionSurface {
        override val surfaceId = "panel"
        override val widthMeters = 0.4f
        override val heightMeters = 0.3f
        val events = mutableListOf<PointerEvent>()
        override fun onPointerEvent(event: PointerEvent) { events += event }
        fun phases() = events.map { it.phase }.filter { it != PointerPhase.HOVER && it != PointerPhase.MOVE }
    }

    @Test fun directTouchPressReleaseWithHysteresisAndDebounce() {
        val panel = Panel(Pose(Vec3(0f, 0f, -0.5f)))
        val dt = DirectTouch(0)
        var now = 0L
        fun at(z: Float) { dt.update(Vec3(0.05f, 0.02f, -0.5f + z), false, listOf(panel), now); now += 11_000_000L }
        at(0.09f); at(0.05f); at(0.01f); at(0.005f)  // press when crossing 8 mm
        assertEquals(listOf(PointerPhase.DOWN), panel.phases())
        val down = panel.events.first { it.phase == PointerPhase.DOWN }
        assertEquals(0.625f, down.u, 1e-3f)
        assertEquals(0.4333f, down.v, 1e-3f)
        at(0.012f) // inside hysteresis band: still pressed
        assertEquals(listOf(PointerPhase.DOWN), panel.phases())
        at(0.03f) // release
        assertEquals(listOf(PointerPhase.DOWN, PointerPhase.UP), panel.phases())
        at(0.005f) // re-press too soon (debounce)
        assertEquals(listOf(PointerPhase.DOWN, PointerPhase.UP), panel.phases())
        now += 200_000_000L
        at(0.03f); at(0.005f)
        assertEquals(listOf(PointerPhase.DOWN, PointerPhase.UP, PointerPhase.DOWN), panel.phases())
    }

    @Test fun directTouchIgnoresPressFromBehind() {
        val panel = Panel(Pose(Vec3(0f, 0f, -0.5f)))
        val dt = DirectTouch(0)
        dt.update(Vec3(0f, 0f, -0.52f), false, listOf(panel), 0)
        dt.update(Vec3(0f, 0f, -0.51f), false, listOf(panel), 20_000_000)
        assertTrue(panel.phases().none { it == PointerPhase.DOWN })
    }

    @Test fun rayPointerCapturesDragAndEdgeTriggers() {
        val panel = Panel(Pose(Vec3(0f, 0f, -1f)))
        val p = RayPointer(1, PointerSource.JOYCON)
        val ray = Ray(Vec3(0f, 0f, 0f), Vec3(0f, 0f, -1f))
        p.update(ray, false, listOf(panel), 0)
        p.update(ray, true, listOf(panel), 1)
        p.update(ray, true, listOf(panel), 2) // held: no second DOWN
        // ray leaves the panel while held: still captured
        p.update(Ray(Vec3(0f, 0f, 0f), Vec3(0.6f, 0f, -0.8f)), true, listOf(panel), 3)
        p.update(ray, false, listOf(panel), 4)
        assertEquals(listOf(PointerPhase.DOWN, PointerPhase.UP), panel.phases())
        assertTrue(panel.events.any { it.phase == PointerPhase.MOVE && it.u > 1f })
    }

    private fun trackedHand(at: Vec3): com.hyperxr.handtracking.HandState {
        val h = com.hyperxr.handtracking.HandState(Handedness.RIGHT)
        h.state = TrackingState.TRACKING
        val joints = openHand(at)
        for (i in joints.indices) h.joints[i].set(joints[i])
        return h
    }

    @Test fun handDrivesControllerPoseWithoutImu() {
        val fusion = ControllerPoseFusion()
        val head = Pose(Vec3(0f, 1.6f, 0f))
        val hand = trackedHand(Vec3(0.25f, 1.35f, -0.45f))
        var t = 0L
        repeat(30) { fusion.update(null, hand, head, rightSide = true, nowNs = t, dtSeconds = 0.016f); t += 16_000_000L }
        assertEquals("hand", fusion.orientationSource)
        assertTrue(fusion.positionFromHand)
        val r = fusion.ray()
        // the ray points away from the shoulder through the hand: forward and slightly down/right
        assertTrue(r.direction.z < -0.8f)
        assertTrue(r.direction.x > 0f)
        // hand lost for one frame: pose is held, not snapped to the head fallback
        val before = r.direction.copy()
        fusion.update(null, com.hyperxr.handtracking.HandState(Handedness.RIGHT), head, true, t, 0.016f)
        assertTrue(fusion.positionFromHand)
        assertEquals(before.x, fusion.ray().direction.x, 1e-4f)
    }

    @Test fun rayStabilizerRemovesJitter() {
        val s = com.hyperxr.input.RayStabilizer()
        val rnd = java.util.Random(3)
        val out = Ray()
        var t = 0L
        var maxDev = 0f
        repeat(240) { i ->
            val noisy = Ray(Vec3((rnd.nextFloat() - 0.5f) * 0.01f, 1.3f, -0.4f), Vec3((rnd.nextFloat() - 0.5f) * 0.04f, 0f, -1f).normalize())
            s.update(noisy, t, out)
            if (i > 60) maxDev = maxOf(maxDev, kotlin.math.abs(out.direction.x))
            t += 16_000_000L
        }
        assertTrue("direction jitter $maxDev", maxDev < 0.012f)
    }

    @Test fun imuDriftIsPulledTowardsTheHandRay() {
        val fusion = ControllerPoseFusion().apply { useImu = true; imuCorrectionRate = 2f }
        val head = Pose(Vec3(0f, 1.6f, 0f))
        val hand = trackedHand(Vec3(0.2f, 1.38f, -0.5f))
        // IMU says the controller points 40 degrees to the left (accumulated drift)
        val drifted = Quat.fromAxisAngle(0f, 1f, 0f, 0.7f)
        fusion.recenter(Quat(), head)
        var t = 0L
        repeat(300) { fusion.update(drifted, hand, head, true, t, 0.016f); t += 16_000_000L }
        val handDir = Vec3.sub(hand.joints[HandJoint.INDEX_MCP], Vec3(0.17f, 1.38f, 0.05f)).normalize()
        val d = fusion.ray().direction
        assertTrue("converged: ${d.dot(handDir)}", d.dot(handDir) > 0.99f)
        assertEquals("imu+hand", fusion.orientationSource)
    }

    @Test fun buttonMapperNoRepeatsAndReleasesOnDisconnect() {
        val m = ButtonMapper(mapOf(ControllerButton.R to ControllerAction.SELECT, ControllerButton.A to ControllerAction.SELECT, ControllerButton.L to ControllerAction.BACK))
        assertEquals(true, m.onButton(ControllerButton.R, true)?.pressed)
        assertNull(m.onButton(ControllerButton.R, true)) // key repeat
        assertNull(m.onButton(ControllerButton.A, true)) // same action already held
        assertNull(m.onButton(ControllerButton.R, false))
        assertEquals(false, m.onButton(ControllerButton.A, false)?.pressed)
        m.onButton(ControllerButton.L, true)
        val released = m.releaseAll()
        assertEquals(listOf(ControllerAction.BACK), released.map { it.action })
        assertFalse(m.isActionHeld(ControllerAction.BACK))
    }

    @Test fun imuFilterAndRecenterAlignRayWithHead() {
        val f = ImuOrientationFilter()
        // Controller lying flat; gravity measured on +Y. Then it yaws 90 degrees left over one second.
        f.update(0f, 0f, 0f, 0f, 9.81f, 0f, 0.01f)
        repeat(100) { f.update(0f, (PI / 2).toFloat(), 0f, 0f, 9.81f, 0f, 0.01f) }
        val fwd = f.orientation.rotate(Vec3(0f, 0f, -1f))
        assertEquals(-1f, fwd.x, 0.02f)

        // IMU mode without hand: calibrated so the controller points where the head looks
        val fusion = ControllerPoseFusion().apply { useImu = true }
        val head = Pose(Vec3(0f, 1.6f, 0f), Quat.fromAxisAngle(0f, 1f, 0f, -0.3f))
        fusion.update(f.orientation, null, head, rightSide = true, nowNs = 0, dtSeconds = 0.016f)
        val ray = fusion.ray()
        val headFwd = head.forward()
        assertEquals(headFwd.x, ray.direction.x, 1e-3f)
        assertEquals(headFwd.z, ray.direction.z, 1e-3f)
        assertFalse(fusion.positionFromHand)
        assertEquals("imu", fusion.orientationSource)
    }
}
