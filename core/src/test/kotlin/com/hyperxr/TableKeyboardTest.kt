package com.hyperxr

import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.TrackingState
import com.hyperxr.handtracking.Handedness
import com.hyperxr.handtracking.HandState
import com.hyperxr.input.PointPush
import com.hyperxr.input.TableDetector
import com.hyperxr.input.TableKeyLayout
import com.hyperxr.input.TablePlacement
import com.hyperxr.input.TableTyping
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

class TableKeyboardTest {
    private val headY = 1.2f  // seated

    // ------------------------------------------------------------------ table detection
    @Test fun findsTheTableAmongFloorWallAndClutter() {
        val rnd = Random(1)
        val pts = FloatArray(3 * 3000); var n = 0
        fun add(x: Float, y: Float, z: Float) { pts[n * 3] = x; pts[n * 3 + 1] = y; pts[n * 3 + 2] = z; n++ }
        repeat(900) { add(rnd.nextFloat() - 0.5f, 0.74f + (rnd.nextGaussian() * 0.006).toFloat(), -0.3f - rnd.nextFloat() * 0.5f) }  // table
        repeat(700) { add(rnd.nextFloat() * 3 - 1.5f, (rnd.nextGaussian() * 0.01).toFloat(), -rnd.nextFloat() * 3) }       // floor
        repeat(600) { add(rnd.nextFloat() * 2 - 1, rnd.nextFloat() * 2, -1.5f) }                                             // wall
        repeat(300) { add(rnd.nextFloat() * 0.3f, 0.74f + rnd.nextFloat() * 0.25f, -0.4f) }                                  // a box on the table
        val t = TableDetector.detect(pts, n, headY)
        assertNotNull(t)
        assertEquals(0.74f, t!!.height, 0.004f)
        assertTrue(t.centroid.z < -0.2f)
    }

    @Test fun noTableWhenOnlyFloorAndWall() {
        val rnd = Random(2)
        val pts = FloatArray(3 * 1000); var n = 0
        repeat(500) { pts[n * 3] = rnd.nextFloat(); pts[n * 3 + 1] = (rnd.nextGaussian() * 0.01).toFloat(); pts[n * 3 + 2] = -rnd.nextFloat() * 2; n++ }
        repeat(500) { pts[n * 3] = rnd.nextFloat(); pts[n * 3 + 1] = rnd.nextFloat() * 2; pts[n * 3 + 2] = -1.5f; n++ }
        assertNull(TableDetector.detect(pts, n, headY))
    }

    // ------------------------------------------------------------------ placement and layout
    @Test fun placementRoundTripsAndFacesTheUser() {
        val head = Pose(Vec3(0.3f, 1.2f, 0.1f), Quat.fromAxisAngle(0f, 1f, 0f, 0.7f))
        val p = TablePlacement(); p.placeInFront(head, 0.74f)
        val w = p.toWorld(0.05f, 0.01f, -0.03f, Vec3())
        val l = p.toLocal(w, Vec3())
        assertEquals(0.05f, l.x, 1e-5f); assertEquals(0.01f, l.y, 1e-5f); assertEquals(-0.03f, l.z, 1e-5f)
        // +z points from the keyboard towards the user
        val towardUser = p.toWorld(0f, 0f, 0.2f, Vec3())
        assertTrue(towardUser.distanceTo(Vec3(head.position.x, 0.74f, head.position.z)) < p.center.distanceTo(Vec3(head.position.x, 0.74f, head.position.z)))
    }

    @Test fun layoutFindsKeysAndGaps() {
        val l = TableKeyLayout()
        val q = l.keys.first { it.label == "q" }
        assertEquals("q", l.keyAt(q.cx, q.cz)?.label)
        assertEquals(TableKeyLayout.Kind.SPACE, l.keyAt(0f, l.depth / 2 - l.rowPitch / 2)?.kind)
        assertNull(l.keyAt(l.width, 0f))
        l.symbols = true
        assertTrue(l.keys.any { it.label == "@" })
    }

    // ------------------------------------------------------------------ typing
    private val layout = TableKeyLayout()
    private val place = TablePlacement().apply { center.set(0f, 0.74f, -0.4f); yaw = 0f; valid = true }

    /**
     * One camera result per call at 30 Hz: the index fingertip at height [indexH] over [key], the
     * middle finger at [middleH] one key to the right; drawn joints, raw and measured tips agree.
     */
    private var resultNs = 0L
    private fun hand(key: String, indexH: Float, middleH: Float = 0.04f, measured: Boolean = true): HandState {
        val k = layout.keys.first { it.label == key }
        val h = HandState(Handedness.RIGHT)
        h.state = TrackingState.TRACKING
        for (j in h.joints) j.set(0f, 0.9f, -0.2f)
        fun put(f: Int, x: Float, z: Float, height: Float) {
            val tip = 4 + f * 4
            place.toWorld(x, height + 0.007f, z, h.joints[tip])
            h.rawTips[f].set(h.joints[tip])
            place.toWorld(x, height + 0.012f, z, h.measuredTips[f])
            h.measuredTipValid[f] = measured
        }
        put(1, k.cx, k.cz, indexH)
        put(2, k.cx + 0.032f, k.cz, middleH)
        put(3, k.cx + 0.064f, k.cz, 0.05f)
        put(0, k.cx - 0.03f, k.cz + 0.1f, 0.05f)
        put(4, k.cx + 0.09f, k.cz, 0.05f)
        resultNs += 33_000_000L
        h.resultNs = resultNs
        return h
    }

    private fun type(t: TableTyping, heights: List<Float>, key: String = "g", measured: Boolean = true): List<String> {
        val typed = ArrayList<String>()
        for (hgt in heights) t.update(arrayOf(null, hand(key, hgt, measured = measured)), place, resultNs).forEach { typed += it.label }
        return typed
    }

    @Test fun aRealTapTypesOnce() {
        // comes down fast, hits the table, rests there, lifts
        assertEquals(listOf("g"), type(TableTyping(layout), listOf(0.05f, 0.045f, 0.03f, 0.015f, 0.002f, 0.002f, 0.002f, 0.003f, 0.002f, 0.03f, 0.05f)))
    }

    @Test fun comingCloseWithoutTappingTypesNothing() {
        // hovers just above the keys, even dipping to 1 cm, but never comes down and stops
        assertEquals(emptyList<String>(), type(TableTyping(layout), listOf(0.05f, 0.04f, 0.03f, 0.025f, 0.02f, 0.016f, 0.013f, 0.011f, 0.010f, 0.011f, 0.012f, 0.03f)))
    }

    @Test fun loweringTheFingerSlowlyOntoTheTableIsNotATap() {
        val slow = (0..30).map { 0.04f - it * 0.0013f }  // 4 cm in a second
        assertEquals(emptyList<String>(), type(TableTyping(layout), slow + listOf(0.001f, 0.001f)))
    }

    @Test fun twoTapsTypeTwice() {
        val tap = listOf(0.045f, 0.03f, 0.015f, 0.002f, 0.002f)
        val lift = listOf(0.02f, 0.035f, 0.05f, 0.05f)
        assertEquals(listOf("e", "e"), type(TableTyping(layout), listOf(0.05f) + tap + lift + tap, key = "e"))
    }

    @Test fun withoutDepthAModelTapIsConfirmedOnTheNextResult() {
        assertEquals(listOf("g"), type(TableTyping(layout), listOf(0.05f, 0.045f, 0.03f, 0.018f, 0.006f, 0.006f, 0.006f, 0.03f), measured = false))
        // the finger bounced straight back up: not confirmed
        assertEquals(emptyList<String>(), type(TableTyping(layout), listOf(0.05f, 0.045f, 0.03f, 0.018f, 0.006f, 0.006f, 0.03f, 0.05f), measured = false))
    }

    @Test fun onlyEnabledFingersType() {
        val t = TableTyping(layout).apply { enabled = setOf(2) }  // middle only
        assertEquals(emptyList<String>(), type(t, listOf(0.05f, 0.045f, 0.03f, 0.015f, 0.002f, 0.002f)))
    }

    @Test fun neighbourFingerOnTheTableDoesNotTypeFromTheSameTouch() {
        val t = TableTyping(layout)
        val typed = ArrayList<String>()
        for ((i, m) in listOf(0.05f, 0.045f, 0.03f, 0.015f, 0.002f, 0.002f, 0.002f).zip(listOf(0.05f, 0.045f, 0.03f, 0.016f, 0.004f, 0.004f, 0.004f)))
            t.update(arrayOf(null, hand("t", i, middleH = m)), place, resultNs).forEach { typed += it.label }
        assertEquals(listOf("t"), typed)
    }

    @Test fun withMeasurementNoiseHoveringDoesNotTypeAndTapsStillDo() {
        val rnd = java.util.Random(5)
        fun noisy(h: Float, sd: Float) = h + (rnd.nextGaussian() * sd).toFloat()
        for ((sd, measured) in listOf(0.002f to true, 0.004f to false)) {
            // 10 s hovering 2-3 cm above the keys
            val hover = (0 until 300).map { noisy(0.025f + 0.005f * kotlin.math.sin(it * 0.2f), sd) }
            val falseKeys = type(TableTyping(layout), hover, measured = measured).size
            // 20 taps
            var taps = 0
            val t = TableTyping(layout)
            repeat(20) {
                val seq = listOf(0.05f, 0.045f, 0.03f, 0.015f, 0.003f, 0.003f, 0.003f, 0.02f, 0.04f, 0.05f, 0.05f).map { noisy(it, sd) }
                taps += type(t, seq, measured = measured).size
            }
            println("noise ${sd * 1000} mm (${if (measured) "Depth API" else "model"}): $falseKeys false keys in 10 s hovering, $taps/20 taps typed")
            assertTrue("false keys $falseKeys", falseKeys == 0)
            assertTrue("taps $taps", taps >= 18)
        }
    }

    /** Taps with the AI depth verdict for the index fingertip ([nnTop] = its top above the table). */
    private fun typeWithAi(heights: List<Float>, nnTop: Float): List<String> {
        val t = TableTyping(layout)
        val typed = ArrayList<String>()
        for (hgt in heights) {
            val h = hand("g", hgt, measured = false)
            // the network answers on every frame, about one frame late
            h.nnTipHeight.fill(Float.NaN); h.nnTipHeight[1] = if (hgt < 0.01f) nnTop else 0.05f
            h.nnNs = h.resultNs
            t.update(arrayOf(null, h), place, resultNs).forEach { typed += it.label }
        }
        return typed
    }

    @Test fun aiDepthConfirmsARealTapAndRejectsATapInTheAir() {
        val tap = listOf(0.05f, 0.045f, 0.03f, 0.015f, 0.005f, 0.005f, 0.005f, 0.03f)
        assertEquals(listOf("g"), typeWithAi(tap, nnTop = 0.012f))      // finger top 1.2 cm: on the table
        assertEquals(emptyList<String>(), typeWithAi(tap, nnTop = 0.045f)) // the network sees it 4.5 cm up
    }

    @Test fun hoverHighlightsTheKeyUnderTheFingertip() {
        val t = TableTyping(layout)
        t.update(arrayOf(null, hand("k", 0.03f)), place, 0L)
        assertTrue(t.hovered.contains(layout.keys.first { it.label == "k" }.id))
    }

    // ------------------------------------------------------------------ point and push
    private fun pointingHand(tip: Vec3): Array<Vec3> {
        val j = Array(HandJoint.COUNT) { Vec3() }
        val w = Vec3(tip.x, tip.y - 0.02f, tip.z + 0.17f)
        j[HandJoint.WRIST].set(w)
        // index straight forward from its knuckle
        j[HandJoint.INDEX_MCP].set(tip.x, tip.y - 0.01f, tip.z + 0.08f)
        j[HandJoint.INDEX_PIP].set(tip.x, tip.y - 0.006f, tip.z + 0.05f)
        j[HandJoint.INDEX_DIP].set(tip.x, tip.y - 0.003f, tip.z + 0.025f)
        j[HandJoint.INDEX_TIP].set(tip)
        // the other fingers folded back towards the palm
        for ((n, f) in intArrayOf(HandJoint.MIDDLE_MCP, HandJoint.RING_MCP, HandJoint.PINKY_MCP).withIndex()) {
            val dx = -0.02f * (n + 1)
            j[f].set(tip.x + dx, tip.y - 0.01f, tip.z + 0.085f)
            j[f + 1].set(tip.x + dx, tip.y - 0.03f, tip.z + 0.07f)
            j[f + 2].set(tip.x + dx, tip.y - 0.035f, tip.z + 0.1f)
            j[f + 3].set(tip.x + dx, tip.y - 0.03f, tip.z + 0.12f)
        }
        j[HandJoint.THUMB_CMC].set(tip.x + 0.02f, tip.y - 0.02f, tip.z + 0.15f)
        j[HandJoint.THUMB_MCP].set(tip.x + 0.04f, tip.y - 0.01f, tip.z + 0.12f)
        j[HandJoint.THUMB_IP].set(tip.x + 0.06f, tip.y, tip.z + 0.1f)
        j[HandJoint.THUMB_TIP].set(tip.x + 0.08f, tip.y + 0.01f, tip.z + 0.09f)
        return j
    }

    @Test fun pointingPoseIsRecognisedAndAnOpenHandIsNot() {
        assertTrue(PointPush.isPointingPose(pointingHand(Vec3(0f, 1.3f, -0.4f))))
        val open = pointingHand(Vec3(0f, 1.3f, -0.4f))
        for (f in intArrayOf(HandJoint.MIDDLE_MCP, HandJoint.RING_MCP, HandJoint.PINKY_MCP)) {
            val m = open[f]
            open[f + 1].set(m.x, m.y + 0.004f, m.z - 0.03f); open[f + 2].set(m.x, m.y + 0.007f, m.z - 0.055f); open[f + 3].set(m.x, m.y + 0.01f, m.z - 0.075f)
        }
        assertFalse(PointPush.isPointingPose(open))
    }

    @Test fun aQuickPushClicksSlowDriftDoesNotAndPullingBackReleases() {
        val eye = Vec3(0f, 1.6f, 0f)
        val pp = PointPush()
        var t = 0L
        fun step(z: Float): Boolean { t += 16_000_000L; return pp.update(pointingHand(Vec3(0f, 1.3f, z)), eye, false, t) }
        // hold still, then drift forward slowly (2 cm/s for 2 s = 4 cm): no click
        repeat(10) { step(-0.4f) }
        var z = -0.4f
        var clicked = false
        repeat(125) { z -= 0.02f * 0.016f; if (step(z)) clicked = true }
        assertFalse("slow drift clicked", clicked)
        // a 3 cm push in 100 ms: click
        repeat(6) { z -= 0.005f; if (step(z)) clicked = true }
        assertTrue("push did not click", clicked)
        assertTrue(pp.pressed)
        // hold and move sideways (dragging): stays pressed
        repeat(10) { t += 16_000_000L; pp.update(pointingHand(Vec3(0.01f * it, 1.3f, z)), eye, false, t) }
        assertTrue(pp.pressed)
        // pull back 3 cm: released
        repeat(6) { z += 0.005f; step(z) }
        assertFalse(pp.pressed)
    }

    @Test fun aFingertipOnTheBorderStaysOnItsKey() {
        val l = TableKeyLayout()
        val f = l.keys.first { it.label == "f" }; val g = l.keys.first { it.label == "g" }
        // just past the f/g border, on the g side, coming from f: still f
        val x = f.x1 + 0.004f
        assertEquals("f", l.keyAtSticky(x, f.cz, f)?.label)
        assertEquals("g", l.keyAtSticky(g.cx, g.cz, f)?.label)
    }
}
