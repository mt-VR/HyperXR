package com.hyperxr

import com.hyperxr.handtracking.CameraIntrinsics
import com.hyperxr.handtracking.MonoDepthCalibration
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

class MonoDepthCalibrationTest {
    // a head camera 45 cm above the table, looking 50 degrees down
    private val intr = CameraIntrinsics(500f, 500f, 320f, 240f, 640, 480)
    private val cam = Pose(Vec3(0f, 1.2f, 0f), Quat.fromAxisAngle(1f, 0f, 0f, -0.87f))
    private val tableY = 0.75f

    /** A network that sees true inverse depth with its own unknown scale/shift plus noise. */
    private fun network(invZ: Float, rnd: Random) = (invZ - 0.4f) / 0.37f + (rnd.nextGaussian() * 0.02).toFloat()

    @Test fun fittingOnTheTableRecoversFingertipHeights() {
        val rnd = Random(4)
        val disp = FloatArray(400); val inv = FloatArray(400); var n = 0
        // table pixels around the hand, some of them on a book lying on the table (outliers)
        for (gy in 0 until 20) for (gx in 0 until 20) {
            val nx = 0.3f + gx * 0.02f; val ny = 0.55f + gy * 0.02f
            val z = MonoDepthCalibration.tableDepth(nx, ny, intr, cam, tableY)
            if (z.isNaN()) continue
            val book = gx > 15 && gy > 15
            val seenZ = if (book) z * 0.93f else z
            disp[n] = network(1f / seenZ, rnd); inv[n] = 1f / z; n++
        }
        val fit = MonoDepthCalibration.fit(disp, inv, n)
        assertNotNull(fit)
        // fingertips at 0, 1 and 3 cm above the table, seen at the middle of the image
        for (h in listOf(0f, 0.01f, 0.03f)) {
            val nx = 0.5f; val ny = 0.7f
            // where along that ray the point at height h is
            val zTable = MonoDepthCalibration.tableDepth(nx, ny, intr, cam, tableY)
            val zTip = MonoDepthCalibration.tableDepth(nx, ny, intr, cam, tableY + h)
            val d = (1f / zTip - 0.4f) / 0.37f
            val z = 1f / (fit!!.a * d + fit.b)
            val got = MonoDepthCalibration.heightAt(nx, ny, z, intr, cam, tableY)
            println("fingertip ${h * 100} cm above the table: measured ${"%.1f".format(got * 100)} cm (table at ${"%.2f".format(zTable)} m)")
            assertEquals(h, got, 0.006f)
        }
    }

    @Test fun noFitWithoutEnoughTableOrWithInvertedDepth() {
        assertNull(MonoDepthCalibration.fit(FloatArray(5), FloatArray(5), 5))
        val d = FloatArray(40) { it.toFloat() }; val inv = FloatArray(40) { 3f - it * 0.05f }
        assertNull(MonoDepthCalibration.fit(d, inv, 40))
    }
}
