package com.hyperxr.rendering

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The passthrough's enhancement as a coarse grid of local colour transforms (out = a * in + b per
 * channel), the way HDRNet-style enhancers work: the expensive look at the image runs on a small
 * copy, and the GPU applies the grid to every full-resolution camera frame. Because the grid is
 * smooth, a grid computed a frame or two ago still fits the frame on screen while the head turns
 * (a per-pixel result that late would smear).
 *
 * [fit] takes the small camera image and, when the NPU produced one, its restored version
 * (Real-ESRGAN: denoised, deblurred), and makes the target the restored image with local tone
 * mapping: dark rooms and shadows lifted, highlights kept below clipping, a little more colour.
 * Each cell's transform is the least-squares fit from the camera image to that target, pulled
 * towards a plain gain so noise cannot bend it.
 */
class ToneGrid(val gw: Int = 16, val gh: Int = 9) {
    /** Per cell, RGB: scale [a] and offset [b], row-major from the top-left cell. */
    val a = FloatArray(gw * gh * 3) { 1f }
    val b = FloatArray(gw * gh * 3)
    private var primed = false

    /** How strongly the image is changed, 0 (off) .. 1. */
    var strength = 1f

    /**
     * [src] and [restored]: RGB 0..1, [w] x [h], row-major from the top-left ([restored] may be
     * null: the camera image itself is the base). [blend]: weight of this fit against the last one
     * (temporal smoothing, so the picture never flickers).
     */
    fun fit(src: FloatArray, restored: FloatArray?, w: Int, h: Int, blend: Float = 0.3f) {
        val base = restored ?: src
        // luminance per cell of the base, and over the whole image
        val cellMean = FloatArray(gw * gh)
        val cellCount = IntArray(gw * gh)
        var total = 0.0
        for (y in 0 until h) {
            val cy = min(gh - 1, y * gh / h)
            for (x in 0 until w) {
                val cx = min(gw - 1, x * gw / w)
                val i = (y * w + x) * 3
                val l = luma(base[i], base[i + 1], base[i + 2])
                cellMean[cy * gw + cx] += l; cellCount[cy * gw + cx]++
                total += l
            }
        }
        val global = (total / (w * h)).toFloat().coerceAtLeast(0.01f)
        for (k in cellMean.indices) cellMean[k] = cellMean[k] / max(1, cellCount[k])
        val local = blur(cellMean)
        // gain per cell: the whole scene towards a comfortable brightness, and dark regions up
        // relative to the scene (shadows show what is in them), within limits
        val gain = FloatArray(gw * gh) { k ->
            val exposure = (TARGET / global).pow(0.8f)
            val shadows = (global / local[k].coerceAtLeast(0.01f)).pow(0.35f)
            (1f + (exposure * shadows - 1f) * strength).coerceIn(0.8f, 3.8f)
        }
        // least-squares fit per cell and channel, from the camera image to the target
        val contrast = 1f + CONTRAST * strength
        val sx = DoubleArray(gw * gh * 3); val sy = DoubleArray(gw * gh * 3)
        val sxx = DoubleArray(gw * gh * 3); val sxy = DoubleArray(gw * gh * 3)
        for (y in 0 until h) {
            val cy = min(gh - 1, y * gh / h)
            for (x in 0 until w) {
                val cx = min(gw - 1, x * gw / w)
                val cell = cy * gw + cx
                val i = (y * w + x) * 3
                val g = gain[cell]
                val l = luma(base[i], base[i + 1], base[i + 2])
                for (c in 0..2) {
                    // target: the base brightened, colour a little stronger, local contrast up
                    // around the cell's own brightness, shoulder above 0.8
                    var t = base[i + c] * g
                    val lt = l * g
                    t = lt + (t - lt) * (1f + SATURATION * strength)
                    val lm = local[cell] * g
                    t = lm + (t - lm) * contrast
                    t = shoulder(t)
                    val s = src[i + c]
                    val k = cell * 3 + c
                    sx[k] += s; sy[k] += t; sxx[k] += s * s; sxy[k] += s * t
                }
            }
        }
        val na = FloatArray(gw * gh * 3); val nb = FloatArray(gw * gh * 3)
        for (cell in 0 until gw * gh) {
            val n = max(1, cellCount[cell]).toDouble()
            for (c in 0..2) {
                val k = cell * 3 + c
                val mx = sx[k] / n; val my = sy[k] / n
                val vx = sxx[k] / n - mx * mx; val cxy = sxy[k] / n - mx * my
                // ridge towards the plain gain: a flat or noisy cell keeps a sensible slope
                val prior = gain[cell].toDouble()
                val slope = ((cxy + RIDGE * prior) / (vx + RIDGE)).coerceIn(0.5, 4.5)
                na[k] = slope.toFloat()
                nb[k] = (my - slope * mx).toFloat().coerceIn(-0.3f, 0.3f)
            }
        }
        val sa = blur3(na); val sb = blur3(nb)
        val wNew = if (primed) blend else 1f
        for (k in a.indices) { a[k] += (sa[k] - a[k]) * wNew; b[k] += (sb[k] - b[k]) * wNew }
        primed = true
    }

    /** The transform a pixel at (u, v) gets (bilinear between cell centres), for tests. */
    fun apply(u: Float, v: Float, rgb: FloatArray, out: FloatArray) {
        val fx = (u * gw - 0.5f).coerceIn(0f, gw - 1f); val fy = (v * gh - 0.5f).coerceIn(0f, gh - 1f)
        val x0 = fx.toInt(); val y0 = fy.toInt(); val x1 = min(gw - 1, x0 + 1); val y1 = min(gh - 1, y0 + 1)
        val tx = fx - x0; val ty = fy - y0
        for (c in 0..2) {
            fun lerp(arr: FloatArray): Float {
                val p00 = arr[(y0 * gw + x0) * 3 + c]; val p10 = arr[(y0 * gw + x1) * 3 + c]
                val p01 = arr[(y1 * gw + x0) * 3 + c]; val p11 = arr[(y1 * gw + x1) * 3 + c]
                return (p00 * (1 - tx) + p10 * tx) * (1 - ty) + (p01 * (1 - tx) + p11 * tx) * ty
            }
            out[c] = (rgb[c] * lerp(a) + lerp(b)).coerceIn(0f, 1f)
        }
    }

    private fun blur(g: FloatArray): FloatArray = FloatArray(g.size) { k ->
        val x = k % gw; val y = k / gw
        var s = 0f; var n = 0
        for (dy in -1..1) for (dx in -1..1) {
            val xx = x + dx; val yy = y + dy
            if (xx in 0 until gw && yy in 0 until gh) { s += g[yy * gw + xx]; n++ }
        }
        s / n
    }

    private fun blur3(g: FloatArray): FloatArray = FloatArray(g.size) { k ->
        val cell = k / 3; val c = k % 3
        val x = cell % gw; val y = cell / gw
        var s = 0f; var n = 0f
        for (dy in -1..1) for (dx in -1..1) {
            val xx = x + dx; val yy = y + dy
            if (xx in 0 until gw && yy in 0 until gh) {
                val w = if (dx == 0 && dy == 0) 2f else 1f
                s += g[(yy * gw + xx) * 3 + c] * w; n += w
            }
        }
        s / n
    }

    companion object {
        /** Mean brightness the scene is brought towards. */
        const val TARGET = 0.46f
        const val SATURATION = 0.15f
        const val CONTRAST = 0.2f
        private const val RIDGE = 0.004

        fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

        /** Linear up to 0.8, then rolls off towards 1 (no clipped highlights). */
        fun shoulder(t: Float): Float = if (t <= 0.8f) max(0f, t) else 0.8f + 0.2f * (1f - exp(-(t - 0.8f) / 0.2f))
    }
}
