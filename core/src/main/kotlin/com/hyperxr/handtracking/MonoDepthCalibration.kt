package com.hyperxr.handtracking

import com.hyperxr.math.Pose
import com.hyperxr.math.Vec3
import kotlin.math.abs

/**
 * Turns a monocular depth network's output (Depth Anything V2: relative inverse depth, larger =
 * nearer, unknown scale and shift) into metric heights above a known table.
 *
 * The table is the ruler: for pixels where the camera sees the table (away from the hands), the
 * true distance is known from the table plane, so inverse depth = a * disparity + b is fitted on
 * them, locally around the hand, every frame. The fingertip's disparity then gives its metric
 * distance, and so its height above the table. Because the fit is redone on every frame, the
 * network's own scale drift does not matter.
 */
object MonoDepthCalibration {
    class Fit(val a: Float, val b: Float, val samples: Int, val rmsInv: Float)

    /**
     * Least squares of inv = a * d + b, twice: the second time without samples farther than
     * 2.5 sigma from the first fit (objects on the table, the other hand's shadow...).
     */
    fun fit(disp: FloatArray, inv: FloatArray, n: Int, minSamples: Int = 24): Fit? {
        if (n < minSamples) return null
        val keep = BooleanArray(n) { true }
        var a = 0.0; var b = 0.0; var rms = 0.0; var used = 0
        repeat(2) { pass ->
            var sx = 0.0; var sy = 0.0; var sxx = 0.0; var sxy = 0.0; var m = 0
            for (i in 0 until n) if (keep[i]) {
                val x = disp[i].toDouble(); val y = inv[i].toDouble()
                sx += x; sy += y; sxx += x * x; sxy += x * y; m++
            }
            if (m < minSamples) return null
            val den = m * sxx - sx * sx
            if (abs(den) < 1e-12) return null
            a = (m * sxy - sx * sy) / den; b = (sy - a * sx) / m
            var e2 = 0.0
            for (i in 0 until n) if (keep[i]) { val r = inv[i] - (a * disp[i] + b); e2 += r * r }
            rms = kotlin.math.sqrt(e2 / m); used = m
            if (pass == 0) for (i in 0 until n) if (keep[i] && abs(inv[i] - (a * disp[i] + b)) > 2.5 * rms + 1e-9) keep[i] = false
        }
        // nearer must mean larger disparity; anything else is not a usable fit
        if (a <= 0.0) return null
        return Fit(a.toFloat(), b.toFloat(), used, rms.toFloat())
    }

    /**
     * Camera-axis distance (metres) at which the ray through normalized image point (nx, ny)
     * meets the level table at height [tableY], or NaN when it does not (above the horizon,
     * behind the camera). [cam] is the camera pose in tracking space (-Z forward, +Y up).
     */
    fun tableDepth(nx: Float, ny: Float, intr: CameraIntrinsics, cam: Pose, tableY: Float, dir: Vec3 = Vec3()): Float {
        val u = nx * intr.width; val v = ny * intr.height
        // ray in camera space with z = -1, i.e. 1 metre along the axis
        cam.orientation.rotate(dir.set((u - intr.cx) / intr.fx, -(v - intr.cy) / intr.fy, -1f), dir)
        if (dir.y > -1e-4f) return Float.NaN
        val t = (tableY - cam.position.y) / dir.y
        return if (t > 0.05f && t < 3f) t else Float.NaN
    }

    /** Height above the table of the point the camera sees at (nx, ny) at axis distance [z]. */
    fun heightAt(nx: Float, ny: Float, z: Float, intr: CameraIntrinsics, cam: Pose, tableY: Float, dir: Vec3 = Vec3()): Float {
        val u = nx * intr.width; val v = ny * intr.height
        cam.orientation.rotate(dir.set((u - intr.cx) / intr.fx * z, -(v - intr.cy) / intr.fy * z, -z), dir)
        return cam.position.y + dir.y - tableY
    }
}
