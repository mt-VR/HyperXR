package com.hyperxr.media

import kotlin.math.roundToInt

/**
 * 3D out of an ordinary photo, after PhoneXR's SpatialPhoto (Samrat Beketov, who brought PhoneXR
 * into HyperMagic): the depth network says how near each point is, and the photo is drawn twice,
 * once for each eye, with near things moved a little sideways, the way the two eyes really see
 * them. The result is one side-by-side picture that any VR viewer (and HyperMagic's gallery)
 * shows with depth.
 */
object StereoFromDepth {
    /** How far the nearest point moves, as a part of the width. Beyond this the eyes start to ache. */
    const val PARALLAX = 0.016f
    /** Depth that stays put: the picture sits half in front of the window and half behind it. */
    const val FOCUS = 0.45f

    /**
     * Depth map ([w] x [h], any scale, larger = nearer) brought to 0..1 between its 2nd and 98th
     * percentiles, so a few wild values do not flatten the rest.
     */
    fun normalize(depth: FloatArray): FloatArray {
        val sorted = depth.copyOf().also { it.sort() }
        val lo = sorted[(sorted.size * 0.02f).toInt()]
        val hi = sorted[((sorted.size - 1) * 0.98f).toInt()]
        val range = (hi - lo).takeIf { it > 1e-6f } ?: 1f
        return FloatArray(depth.size) { ((depth[it] - lo) / range).coerceIn(0f, 1f) }
    }

    /**
     * The pixels of the two eyes. [source] is ARGB, [width] x [height]; [near] is 0..1 (1 = nearest),
     * [depthW] x [depthH], stretched over the photo. Every pixel moves sideways by how near it is;
     * where a near thing uncovers what was behind it, the gap takes the farther side's colour,
     * which is what was hidden there anyway.
     */
    fun eyes(source: IntArray, width: Int, height: Int, near: FloatArray, depthW: Int, depthH: Int): Pair<IntArray, IntArray> {
        val left = IntArray(width * height)
        val right = IntArray(width * height)
        val leftDepth = FloatArray(width)
        val rightDepth = FloatArray(width)
        val shift = (width * PARALLAX / 2).coerceAtLeast(1f)
        val row = FloatArray(width)
        for (y in 0 until height) {
            depthRow(near, depthW, depthH, y, height, width, row)
            leftDepth.fill(-1f); rightDepth.fill(-1f)
            val base = y * width
            for (x in 0 until width) {
                val n = row[x]
                val move = ((n - FOCUS) * shift).roundToInt()
                val colour = source[base + x]
                // nearer pixels win the place they land on: they are what hides the rest
                val toLeft = x + move
                if (toLeft in 0 until width && n > leftDepth[toLeft]) { leftDepth[toLeft] = n; left[base + toLeft] = colour }
                val toRight = x - move
                if (toRight in 0 until width && n > rightDepth[toRight]) { rightDepth[toRight] = n; right[base + toRight] = colour }
            }
            fillGaps(left, leftDepth, base, width)
            fillGaps(right, rightDepth, base, width)
        }
        return left to right
    }

    private fun fillGaps(pixels: IntArray, depths: FloatArray, base: Int, width: Int) {
        var x = 0
        while (x < width) {
            if (depths[x] >= 0f) { x++; continue }
            var end = x
            while (end < width && depths[end] < 0f) end++
            val before = if (x > 0) x - 1 else -1
            val after = if (end < width) end else -1
            val fill = when {
                before >= 0 && after >= 0 -> if (depths[before] <= depths[after]) pixels[base + before] else pixels[base + after]
                before >= 0 -> pixels[base + before]
                after >= 0 -> pixels[base + after]
                else -> 0
            }
            for (hole in x until end) pixels[base + hole] = fill
            x = end
        }
    }

    private fun depthRow(depth: FloatArray, dw: Int, dh: Int, y: Int, height: Int, width: Int, out: FloatArray) {
        val sy = ((y + 0.5f) * dh / height - 0.5f).coerceIn(0f, dh - 1f)
        val y0 = sy.toInt(); val y1 = (y0 + 1).coerceAtMost(dh - 1); val ty = sy - y0
        for (x in 0 until width) {
            val sx = ((x + 0.5f) * dw / width - 0.5f).coerceIn(0f, dw - 1f)
            val x0 = sx.toInt(); val x1 = (x0 + 1).coerceAtMost(dw - 1); val tx = sx - x0
            val top = depth[y0 * dw + x0] * (1 - tx) + depth[y0 * dw + x1] * tx
            val bottom = depth[y1 * dw + x0] * (1 - tx) + depth[y1 * dw + x1] * tx
            out[x] = top * (1 - ty) + bottom * ty
        }
    }
}
