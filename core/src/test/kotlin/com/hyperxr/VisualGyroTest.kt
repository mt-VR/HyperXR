package com.hyperxr

import com.hyperxr.headtracking.GrayImage
import com.hyperxr.headtracking.VisualGyro
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.floor

/**
 * The computational gyroscope on synthetic camera images: a textured "room" (random grey tiles
 * of 5 degrees on a sphere) seen through a 160x120 camera at known orientations.
 */
class VisualGyroTest {
    private val w = 160; private val h = 120; private val f = 130f

    private fun tile(lat: Int, lon: Int): Int {
        var x = lat * 73856093 xor lon * 19349663
        x = x xor (x ushr 13); x *= 0x5bd1e995; x = x xor (x ushr 15)
        return 40 + ((x ushr 8) and 0xff) * 170 / 255
    }

    /** Camera image for world_from_camera [o] (camera: x right, y down, z forward). */
    private fun render(o: Quat, blob: Pair<Int, Int>? = null): GrayImage {
        val img = GrayImage(w, h)
        val b = Vec3(); val d = Vec3()
        for (y in 0 until h) for (x in 0 until w) {
            b.set((x - w / 2f) / f, (y - h / 2f) / f, 1f).normalize()
            o.rotate(b, d)
            val lat = Math.toDegrees(asin(d.y.coerceIn(-1f, 1f).toDouble())).toFloat()
            val lon = Math.toDegrees(atan2(d.x.toDouble(), d.z.toDouble())).toFloat()
            var v = tile(floor(lat / 5f).toInt(), floor(lon / 5f).toInt())
            // a "hand" moving across the picture: must not be taken for rotation
            if (blob != null && (x - blob.first) * (x - blob.first) + (y - blob.second) * (y - blob.second) < 18 * 18) v = 200 - ((x + y) % 7) * 20
            img.px[y * w + x] = v.toByte()
        }
        return img
    }

    private val camToHead = Quat.fromAxisAngle(1f, 0f, 0f, Math.PI.toFloat())

    /** Head orientation the tracker should report: the camera's turn since the start, in head axes. */
    private fun expected(o0: Quat, o: Quat): Quat {
        val rel = o0.conjugate(Quat()).mul(o, Quat())
        return camToHead.mul(rel, Quat()).mul(camToHead.conjugate(Quat()), Quat())
    }

    private fun run(path: List<Quat>, blob: Boolean = false): Pair<Float, VisualGyro> {
        val g = VisualGyro(f, f, w / 2f, h / 2f)
        var worst = 0f
        for ((i, o) in path.withIndex()) {
            g.process(render(o, if (blob) (20 + i * 6) to 60 else null), i * 33_333_333L)
            worst = maxOf(worst, VisualGyro.angleDeg(g.orientation, expected(path[0], o)))
        }
        return worst to g
    }

    private fun yawPitch(yawDeg: Float, pitchDeg: Float): Quat =
        Quat.fromAxisAngle(0f, 1f, 0f, Math.toRadians(yawDeg.toDouble()).toFloat())
            .mul(Quat.fromAxisAngle(1f, 0f, 0f, Math.toRadians(pitchDeg.toDouble()).toFloat()), Quat())

    @Test fun followsATurnOfTheHead() {
        // 2.5 deg/frame yaw (75 deg/s at 30 fps) with some pitch, for 24 frames
        val path = (0..24).map { i -> yawPitch(i * 2.5f, i * 0.8f) }
        val (err, g) = run(path)
        assertTrue("worst error $err deg", err < 1.5f)
        assertTrue(g.inliers >= 8)
    }

    @Test fun fastTurnStaysLocked() {
        // 6 deg per frame = 180 deg/s at 30 fps
        val path = (0..12).map { i -> yawPitch(i * 6f, 0f) }
        val (err, _) = run(path)
        assertTrue("worst error $err deg", err < 2.5f)
    }

    @Test fun movingHandIsNotRotation() {
        val path = (0..20).map { yawPitch(0f, 0f) }  // head still, a "hand" sweeps the view
        val (err, _) = run(path, blob = true)
        assertTrue("worst error $err deg", err < 0.5f)
    }

    @Test fun lookingBackClosesTheLoop() {
        // look 40 degrees right and come back: the keyframe map pulls the drift back
        val out = (0..16).map { i -> yawPitch(i * 2.5f, 0f) }
        val back = (15 downTo 0).map { i -> yawPitch(i * 2.5f, 0f) }
        val path = out + back
        val g = VisualGyro(f, f, w / 2f, h / 2f)
        for ((i, o) in path.withIndex()) g.process(render(o), i * 33_333_333L)
        val err = VisualGyro.angleDeg(g.orientation, expected(path[0], path.last()))
        assertTrue("error back at the start $err deg", err < 1f)
        assertTrue(g.keyframeCount >= 2)
    }

    @Test fun gravityLevelsTheView() {
        val g = VisualGyro(f, f, w / 2f, h / 2f)
        g.reset(Quat.fromAxisAngle(1f, 0f, 0f, 0.3f))  // believes it is pitched 17 degrees
        repeat(400) { g.applyGravity(Vec3(0f, 9.8f, 0f), 0.02f) }  // the accelerometer says level
        val up = g.orientation.conjugate(Quat()).rotate(Vec3(0f, 1f, 0f))
        assertTrue("up after correction $up", up.y > 0.999f)
    }
}
