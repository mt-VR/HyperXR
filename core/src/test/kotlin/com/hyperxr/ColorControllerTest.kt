package com.hyperxr

import com.hyperxr.color.BlobFinder
import com.hyperxr.color.ColorCalibration
import com.hyperxr.color.ColorControllerModel
import com.hyperxr.color.ColorMarker
import com.hyperxr.color.ColorMatch
import com.hyperxr.color.ColorPoseSolver
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ColorControllerTest {
    private fun marker(h: Float, x: Float, y: Float, z: Float = 0f) = ColorMarker(h, 0.8f, 0.8f, x = x, y = y, z = z, radius = 0.008f)

    /** Four spots on a controller face, not in a square (so no turn of it looks the same). */
    private val model = ColorControllerModel(listOf(
        marker(0f, -0.03f, 0.02f), marker(120f, 0.035f, 0.025f), marker(240f, 0.02f, -0.03f), marker(60f, -0.025f, -0.015f, 0.01f),
    ))

    private fun observe(m: ColorControllerModel, rot: Quat, pos: Vec3, use: List<Int>, noise: Float = 0f, rnd: Random = Random(1)): List<ColorPoseSolver.Obs> =
        use.map { i ->
            val mk = m.markers[i]
            val p = rot.rotate(Vec3(mk.x, mk.y, mk.z)).add(pos)
            val d = -p.z
            ColorPoseSolver.Obs(i, p.x / d + noise * (rnd.nextFloat() - 0.5f), -p.y / d + noise * (rnd.nextFloat() - 0.5f), mk.radius / d)
        }

    @Test fun fullPoseFromThreeOrFourSpots() {
        val rnd = Random(7)
        var worst = 0f; var worstAng = 0f
        repeat(40) {
            val rot = Quat.fromAxisAngle(rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f, rnd.nextFloat() - 0.5f + 1e-3f, 0.5f * (rnd.nextFloat() - 0.5f)).normalize()
            val pos = Vec3(0.3f * (rnd.nextFloat() - 0.5f), 0.3f * (rnd.nextFloat() - 0.5f), -0.35f - 0.3f * rnd.nextFloat())
            for (use in listOf(listOf(0, 1, 2, 3), listOf(0, 1, 2))) {
                val r = ColorPoseSolver.Result()
                // the last pose is near the true one, as when tracking frame to frame
                val prior = Quat.fromAxisAngle(0f, 1f, 0f, 0.1f).mul(rot, Quat())
                assertTrue(ColorPoseSolver.solve(model, observe(model, rot, pos, use), prior, pos.copy().add(Vec3(0.01f, 0f, 0.01f)), r))
                assertEquals(6, r.dof)
                worst = maxOf(worst, r.position.copy().sub(pos).length())
                worstAng = maxOf(worstAng, r.rotation.angleTo(rot))
            }
        }
        assertTrue("position off by $worst m", worst < 0.005f)
        assertTrue("rotation off by $worstAng rad", worstAng < 0.05f)
    }

    @Test fun findsThePoseWithoutAPriorToo() {
        val rot = Quat.fromAxisAngle(0f, 0f, 1f, 0.7f)
        val pos = Vec3(0.05f, -0.1f, -0.45f)
        val r = ColorPoseSolver.Result()
        assertTrue(ColorPoseSolver.solve(model, observe(model, rot, pos, listOf(0, 1, 2, 3)), null, null, r))
        assertTrue(r.position.copy().sub(pos).length() < 0.005f)
        assertTrue(r.rotation.angleTo(rot) < 0.05f)
    }

    @Test fun noisySpotsStayClose() {
        val rot = Quat.fromAxisAngle(1f, 0.3f, 0f, 0.3f).normalize()
        val pos = Vec3(0f, 0f, -0.4f)
        val r = ColorPoseSolver.Result()
        // about one pixel of noise in a 320-pixel-wide picture
        assertTrue(ColorPoseSolver.solve(model, observe(model, rot, pos, listOf(0, 1, 2, 3), 0.006f), rot, pos, r))
        assertTrue(r.position.copy().sub(pos).length() < 0.03f)
    }

    @Test fun fewerSpotsGivePosition() {
        val rot = Quat()
        val pos = Vec3(0.04f, 0.02f, -0.5f)
        val two = ColorPoseSolver.Result()
        assertTrue(ColorPoseSolver.solve(model, observe(model, rot, pos, listOf(0, 1)), null, null, two))
        assertEquals(5, two.dof)
        assertTrue(two.position.copy().sub(pos).length() < 0.02f)
        val one = ColorPoseSolver.Result()
        assertTrue(ColorPoseSolver.solve(model, observe(model, rot, pos, listOf(2)), rot, null, one))
        assertEquals(3, one.dof)
        assertTrue(one.position.copy().sub(pos).length() < 0.02f)
    }

    @Test fun blobsAreTheLargestOfEachColour() {
        val w = 40; val h = 30
        val c = ByteArray(w * h)
        fun box(x0: Int, y0: Int, s: Int, k: Int) { for (y in y0 until y0 + s) for (x in x0 until x0 + s) c[y * w + x] = k.toByte() }
        box(4, 4, 4, 1); box(30, 20, 2, 1)   // a big spot and a speck of the same colour
        box(20, 10, 5, 2)
        c[0] = 3                              // one pixel: too small
        val out = Array(3) { BlobFinder.Blob() }
        BlobFinder.find(c, w, h, 3, out)
        assertTrue(out[0].found); assertEquals(16, out[0].area)
        assertEquals(6f / w, out[0].x, 1e-4f); assertEquals(6f / h, out[0].y, 1e-4f)
        assertTrue(out[1].found); assertEquals(22.5f / w, out[1].x, 1e-4f)
        assertFalse(out[2].found)
    }

    @Test fun coloursAreSampledAndMatched() {
        // a red patch around hue 0, across the wrap
        val patch = IntArray(25) { i -> if (i % 2 == 0) 0xFFE01010.toInt() else 0xFFE0101C.toInt() }
        val m = ColorMatch.sample(patch)
        assertTrue(ColorMatch.hueDistance(m.h, 0f) < 6f)
        val hsv = FloatArray(3)
        ColorMatch.hsv(0.85f, 0.1f, 0.05f, hsv); assertTrue(ColorMatch.matches(hsv, m))
        ColorMatch.hsv(0.1f, 0.8f, 0.1f, hsv); assertFalse(ColorMatch.matches(hsv, m))
        ColorMatch.hsv(0.5f, 0.5f, 0.5f, hsv); assertFalse(ColorMatch.matches(hsv, m))
        // a white spot goes by brightness
        val white = ColorMatch.sample(IntArray(9) { 0xFFF0F0F0.toInt() })
        ColorMatch.hsv(0.92f, 0.93f, 0.95f, hsv); assertTrue(ColorMatch.matches(hsv, white))
        ColorMatch.hsv(0.3f, 0.3f, 0.3f, hsv); assertFalse(ColorMatch.matches(hsv, white))
    }

    @Test fun aPixelGoesToTheClosestMatchingMarker() {
        val ms = listOf(ColorMarker(10f, 0.8f, 0.8f, hueTol = 20f, x = 0f, y = 0f), ColorMarker(25f, 0.8f, 0.8f, hueTol = 20f, x = 0f, y = 0f), ColorMarker(200f, 0.8f, 0.8f, x = 0f, y = 0f))
        assertEquals(2, ColorMatch.classify(floatArrayOf(22f, 0.9f, 0.9f), ms))
        assertEquals(1, ColorMatch.classify(floatArrayOf(5f, 0.9f, 0.9f), ms))
        assertEquals(3, ColorMatch.classify(floatArrayOf(205f, 0.9f, 0.9f), ms))
        assertEquals(0, ColorMatch.classify(floatArrayOf(120f, 0.9f, 0.9f), ms))
    }

    @Test fun calibrationGetsTheScaleFromTheFirstTwoSpots() {
        val red = ColorMarker(0f, 0.8f, 0.8f, x = 0f, y = 0f)
        // 10 cm apart, seen at 40 cm: 0.25 in normalised coordinates
        val taps = listOf(ColorCalibration.Tap(red, -0.125f, 0f, 0.02f), ColorCalibration.Tap(red.copy(h = 120f), 0.125f, 0f, 0.02f), ColorCalibration.Tap(red.copy(h = 240f), 0f, 0.1f, 0.02f))
        val m = ColorCalibration.build(taps, 0.10f, floatArrayOf(0f, -0.3f), 0)
        assertNotNull(m); m!!
        val d = Vec3(m.markers[1].x - m.markers[0].x, m.markers[1].y - m.markers[0].y, 0f).length()
        assertEquals(0.10f, d, 1e-4f)
        assertEquals(0.008f, m.markers[0].radius, 1e-4f)
        // the third spot is below the others (image y down -> model y negative)
        assertTrue(m.markers[2].y < m.markers[0].y)
        // the pointer was tapped above: aim goes up in model space
        assertFalse(m.aimForward)
        assertTrue(m.aim(Vec3()).y > 0.9f)
        assertTrue(ColorCalibration.build(taps, 0.1f, null, 1)!!.aimForward)
    }
}
