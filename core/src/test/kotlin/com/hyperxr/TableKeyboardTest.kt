package com.hyperxr

import com.hyperxr.handtracking.HandJoint
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

    /** A hand whose fingertips hover 4 cm above the table, with the index tip at the given key. */
    private fun hand(key: String, indexH: Float, middleH: Float = 0.04f): Array<Vec3> {
        val k = layout.keys.first { it.label == key }
        val j = Array(HandJoint.COUNT) { Vec3(0f, 0.9f, -0.2f) }
        val t = place.toWorld(k.cx, indexH + 0.007f, k.cz, Vec3())
        j[HandJoint.INDEX_TIP].set(t)
        j[HandJoint.MIDDLE_TIP].set(place.toWorld(k.cx + 0.032f, middleH + 0.007f, k.cz, Vec3()))
        j[HandJoint.RING_TIP].set(place.toWorld(k.cx + 0.064f, 0.05f, k.cz, Vec3()))
        j[HandJoint.THUMB_TIP].set(place.toWorld(k.cx - 0.03f, 0.05f, k.cz + 0.1f, Vec3()))
        return j
    }

    private fun typeSequence(t: TableTyping, frames: List<Array<Vec3>>, startNs: Long = 0L): List<String> {
        val typed = ArrayList<String>()
        frames.forEachIndexed { i, h -> t.update(arrayOf(null, h), booleanArrayOf(false, false), place, startNs + i * 16_000_000L).forEach { typed += it.label } }
        return typed
    }

    @Test fun aTapTypesOneKeyEvenWhileTheFingerRestsOnTheTable() {
        val t = TableTyping(layout)
        val frames = listOf(0.04f, 0.03f, 0.015f, 0.004f, 0.002f, 0.003f, 0.002f, 0.004f, 0.003f, 0.02f, 0.04f).map { hand("g", it) }
        assertEquals(listOf("g"), typeSequence(t, frames))
    }

    @Test fun twoTapsTypeTwiceButJitterAtTheSurfaceDoesNot() {
        val t = TableTyping(layout)
        // down, wobble around the press height (not lifted enough), up, down again
        val hs = listOf(0.04f, 0.005f, 0.016f, 0.008f, 0.018f, 0.006f, 0.04f, 0.045f, 0.045f, 0.045f, 0.005f, 0.04f)
        val typed = typeSequence(t, hs.map { hand("e", it) }, startNs = 0L)
        assertEquals(listOf("e", "e"), typed)
    }

    @Test fun sameKeyBouncingWithinTheLockoutIsDropped() {
        val t = TableTyping(layout)
        t.releaseH = 0.014f; t.minDrop = 0.002f
        // an unrealistically fast bounce (lift and land within 60 ms) on the same key
        val hs = listOf(0.04f, 0.005f, 0.02f, 0.005f)
        assertEquals(listOf("r"), typeSequence(t, hs.map { hand("r", it) }))
    }

    @Test fun neighbourFingerBrushingTheTableIsNotAKey() {
        val t = TableTyping(layout)
        // index and middle land together; the index is lower and wins, the middle is suppressed
        val frames = listOf(hand("t", 0.04f, 0.04f), hand("t", 0.003f, 0.008f), hand("t", 0.002f, 0.006f), hand("t", 0.04f, 0.04f))
        assertEquals(listOf("t"), typeSequence(t, frames))
    }

    @Test fun hoverHighlightsTheKeyUnderTheFingertip() {
        val t = TableTyping(layout)
        t.update(arrayOf(null, hand("k", 0.03f)), booleanArrayOf(false, false), place, 0L)
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
