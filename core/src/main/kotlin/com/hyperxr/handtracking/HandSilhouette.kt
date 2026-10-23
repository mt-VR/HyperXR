package com.hyperxr.handtracking

import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The hand's shape in the camera picture, from the tracked landmarks: each finger as capsules of
 * a finger's width, the palm as the polygon of wrist, thumb base and knuckles, plus a short piece
 * of the wrist. Used to keep the AI skin mask to the hand itself, so the background around it
 * (or a skin-coloured wall) never shows over the UI.
 */
object HandSilhouette {
    private val chains = arrayOf(intArrayOf(1, 2, 3, 4), intArrayOf(5, 6, 7, 8), intArrayOf(9, 10, 11, 12), intArrayOf(13, 14, 15, 16), intArrayOf(17, 18, 19, 20))
    private val palm = intArrayOf(0, 1, 5, 9, 13, 17)

    /**
     * Adds (max) the hand of [image] (normalised x, y, z per joint) into [out] (size x size, 0..255).
     * [aspect] is the picture's width / height (the mask is the picture squeezed to a square).
     */
    fun raster(image: FloatArray, aspect: Float, size: Int, out: ByteArray) {
        fun px(j: Int) = image[j * 3] * aspect
        fun py(j: Int) = image[j * 3 + 1]
        val pl = dist(px(0), py(0), px(9), py(9)).coerceAtLeast(1e-3f)
        val rFinger = 0.11f * pl; val rThumb = 0.13f * pl; val grow = 0.10f * pl; val soft = 0.05f * pl
        // a short piece of the wrist, away from the middle knuckle
        val ax = px(0) - px(9); val ay = py(0) - py(9)
        val wx = px(0) + ax * 0.35f; val wy = py(0) + ay * 0.35f
        val rWrist = 0.30f * pl
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (j in 0 until HandJoint.COUNT) { minX = min(minX, px(j)); maxX = max(maxX, px(j)); minY = min(minY, py(j)); maxY = max(maxY, py(j)) }
        minX = min(minX, wx); maxX = max(maxX, wx); minY = min(minY, wy); maxY = max(maxY, wy)
        val m = 0.35f * pl
        val x0 = ((minX - m) / aspect * size).toInt().coerceIn(0, size - 1); val x1 = ((maxX + m) / aspect * size).toInt().coerceIn(0, size - 1)
        val y0 = ((minY - m) * size).toInt().coerceIn(0, size - 1); val y1 = ((maxY + m) * size).toInt().coerceIn(0, size - 1)
        val vx = FloatArray(palm.size) { px(palm[it]) }; val vy = FloatArray(palm.size) { py(palm[it]) }
        for (y in y0..y1) for (x in x0..x1) {
            val qx = (x + 0.5f) / size * aspect; val qy = (y + 0.5f) / size
            // signed distance outside the shape (negative inside)
            var d = polygon(qx, qy, vx, vy) - grow
            d = min(d, segment(qx, qy, px(0), py(0), wx, wy) - rWrist)
            for ((f, c) in chains.withIndex()) {
                val r = if (f == 0) rThumb else rFinger
                for (k in 0 until c.size - 1) d = min(d, segment(qx, qy, px(c[k]), py(c[k]), px(c[k + 1]), py(c[k + 1])) - r)
            }
            if (d >= soft) continue
            val v = (((soft - d) / (2 * soft)).coerceIn(0f, 1f) * 255f).toInt()
            val i = y * size + x
            if (v > (out[i].toInt() and 0xff)) out[i] = v.toByte()
        }
    }

    private fun dist(ax: Float, ay: Float, bx: Float, by: Float): Float { val dx = ax - bx; val dy = ay - by; return sqrt(dx * dx + dy * dy) }

    private fun segment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val ux = bx - ax; val uy = by - ay
        val l2 = ux * ux + uy * uy
        val h = if (l2 < 1e-12f) 0f else (((px - ax) * ux + (py - ay) * uy) / l2).coerceIn(0f, 1f)
        return dist(px, py, ax + ux * h, ay + uy * h)
    }

    /** Signed distance to a polygon (negative inside). */
    private fun polygon(px: Float, py: Float, vx: FloatArray, vy: FloatArray): Float {
        var d = Float.MAX_VALUE; var inside = false
        var j = vx.size - 1
        for (i in vx.indices) {
            d = min(d, segment(px, py, vx[i], vy[i], vx[j], vy[j]))
            if ((vy[i] > py) != (vy[j] > py) && px < (vx[j] - vx[i]) * (py - vy[i]) / (vy[j] - vy[i]) + vx[i]) inside = !inside
            j = i
        }
        return if (inside) -d else d
    }
}
