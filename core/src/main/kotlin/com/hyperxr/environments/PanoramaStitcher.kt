package com.hyperxr.environments

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Builds a 360 panorama of the user's room from passthrough camera frames and the camera's
 * orientation: every panorama pixel takes the camera pixel that saw its direction closest to a
 * frame's centre (sharper than averaging frames that never align perfectly).
 *
 * Mapping is the one the environment shader samples: u = 0.5 + atan2(x, -z) / 2pi,
 * v = 0.5 - asin(y) / pi (+Y up, -Z ahead). Camera: pixel (u, v) looks along
 * ((u - cx) / fx, -(v - cy) / fy, -1) rotated by the camera orientation, as in hand tracking.
 */
class PanoramaStitcher(val width: Int = 2048, val height: Int = width / 2) {
    val color = IntArray(width * height)
    val weight = FloatArray(width * height)
    var frames = 0; private set

    private val sinLat = FloatArray(height) { sin(lat(it)) }
    private val cosLat = FloatArray(height) { cos(lat(it)) }
    private val sinLon = FloatArray(width) { sin(lon(it)) }
    private val cosLon = FloatArray(width) { cos(lon(it)) }

    private fun lat(row: Int) = ((0.5 - (row + 0.5) / height) * PI).toFloat()
    private fun lon(col: Int) = (((col + 0.5) / width - 0.5) * 2 * PI).toFloat()

    /**
     * Adds one frame: [px] ARGB, [fw] x [fh], intrinsics in this frame's pixels, [q] the camera
     * orientation (x, y, z, w) in the room. Returns how many panorama pixels improved.
     */
    fun add(px: IntArray, fw: Int, fh: Int, fx: Float, fy: Float, cx: Float, cy: Float, q: FloatArray): Int {
        // rows of the camera->world rotation; world->camera is its transpose
        val qn = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        val x = q[0] / qn; val y = q[1] / qn; val z = q[2] / qn; val w = q[3] / qn
        val m00 = 1 - 2 * (y * y + z * z); val m01 = 2 * (x * y - z * w); val m02 = 2 * (x * z + y * w)
        val m10 = 2 * (x * y + z * w); val m11 = 1 - 2 * (x * x + z * z); val m12 = 2 * (y * z - x * w)
        val m20 = 2 * (x * z - y * w); val m21 = 2 * (y * z + x * w); val m22 = 1 - 2 * (x * x + y * y)

        // which part of the panorama this frame can touch: its border directions
        var latMin = Float.MAX_VALUE; var latMax = -Float.MAX_VALUE
        val lonC = atan2(-m02, m22)                     // longitude of the optical axis (0, 0, -1)
        var dLonMin = 0f; var dLonMax = 0f
        val steps = 24
        for (i in 0..steps * 4) {
            val t = (i % steps).toFloat() / steps
            val (u, v) = when (i / steps) { 0 -> t * fw to 0f; 1 -> fw.toFloat() to t * fh; 2 -> (1 - t) * fw to fh.toFloat(); else -> 0f to (1 - t) * fh }
            val a = (u - cx) / fx; val b = -(v - cy) / fy
            var dx = m00 * a + m01 * b - m02; var dy = m10 * a + m11 * b - m12; var dz = m20 * a + m21 * b - m22
            val n = sqrt(dx * dx + dy * dy + dz * dz); dx /= n; dy /= n; dz /= n
            val la = asin(dy.coerceIn(-1f, 1f))
            latMin = min(latMin, la); latMax = max(latMax, la)
            var dl = atan2(dx, -dz) - lonC
            while (dl > PI) dl -= (2 * PI).toFloat(); while (dl < -PI) dl += (2 * PI).toFloat()
            dLonMin = min(dLonMin, dl); dLonMax = max(dLonMax, dl)
        }
        // a pole inside the frame: every longitude, up to that pole
        val upInside = projects(m10, m11, m12, fw, fh, fx, fy, cx, cy)
        val downInside = projects(-m10, -m11, -m12, fw, fh, fx, fy, cx, cy)
        if (upInside) latMax = (PI / 2).toFloat()
        if (downInside) latMin = (-PI / 2).toFloat()
        val fullLon = upInside || downInside || dLonMax - dLonMin > PI
        val rowTop = ((0.5 - latMax / PI) * height).toInt().coerceIn(0, height - 1)
        val rowBottom = ((0.5 - latMin / PI) * height).toInt().coerceIn(0, height - 1)
        val colFrom: Int; val colCount: Int
        if (fullLon) { colFrom = 0; colCount = width } else {
            colFrom = ((((lonC + dLonMin) / (2 * PI)) + 0.5) * width).toInt() - 2
            colCount = ((dLonMax - dLonMin) / (2 * PI) * width).toInt() + 5
        }

        val feather = 0.5f * min(fw, fh)
        var improved = 0
        for (row in max(0, rowTop - 1)..min(height - 1, rowBottom + 1)) {
            val sl = sinLat[row]; val cl = cosLat[row]
            for (k in 0 until colCount) {
                val col = Math.floorMod(colFrom + k, width)
                // world direction of this panorama pixel
                val wx = sinLon[col] * cl; val wy = sl; val wz = -cosLon[col] * cl
                // into the camera (transpose)
                val cx3 = m00 * wx + m10 * wy + m20 * wz
                val cy3 = m01 * wx + m11 * wy + m21 * wz
                val cz3 = m02 * wx + m12 * wy + m22 * wz
                if (cz3 > -1e-3f) continue
                val iu = cx + fx * (cx3 / -cz3)
                val iv = cy - fy * (cy3 / -cz3)
                if (iu < 0f || iv < 0f || iu > fw - 1 || iv > fh - 1) continue
                val wgt = min(min(iu, fw - 1 - iu), min(iv, fh - 1 - iv)) / feather + 1e-3f
                val i = row * width + col
                if (wgt <= weight[i]) continue
                weight[i] = wgt
                color[i] = px[iv.toInt() * fw + iu.toInt()] or (0xff shl 24)
                improved++
            }
        }
        frames++
        return improved
    }

    /** Whether the camera-space direction (px, py, pz) lands inside the frame (used for the poles: world up is (m10, m11, m12)). */
    private fun projects(px: Float, py: Float, pz: Float, fw: Int, fh: Int, fx: Float, fy: Float, cx: Float, cy: Float): Boolean =
        pz < -1e-3f && (cx + fx * (px / -pz)) in 0f..fw.toFloat() && (cy - fy * (py / -pz)) in 0f..fh.toFloat()

    /** Fraction of the sphere already seen (rows weighted by their real area). */
    fun coverage(): Float {
        var seen = 0.0; var all = 0.0
        for (row in 0 until height) {
            val a = cosLat[row].toDouble()
            var n = 0
            for (col in 0 until width) if (weight[row * width + col] > 0f) n++
            seen += a * n; all += a * width
        }
        return (seen / all).toFloat()
    }

    /**
     * The finished panorama: holes filled along each row from their seen neighbours, rows never
     * seen (usually the ceiling and the floor right below) continued from the nearest seen row.
     */
    fun result(): IntArray {
        val out = color.copyOf()
        val rowSeen = BooleanArray(height)
        for (row in 0 until height) {
            val base = row * width
            val seenCols = (0 until width).filter { weight[base + it] > 0f }
            if (seenCols.isEmpty()) continue
            rowSeen[row] = true
            if (seenCols.size == width) continue
            for ((idx, a) in seenCols.withIndex()) {
                val b = seenCols[(idx + 1) % seenCols.size]
                val gap = Math.floorMod(b - a, width).let { if (it == 0) width else it }
                for (s in 1 until gap) {
                    val t = s.toFloat() / gap
                    out[base + Math.floorMod(a + s, width)] = mix(out[base + a], out[base + b], t)
                }
            }
        }
        val firstSeen = rowSeen.indexOfFirst { it }
        if (firstSeen < 0) return out.also { it.fill(0xff202024.toInt()) }
        val lastSeen = rowSeen.indexOfLast { it }
        // toward the poles: the nearest seen row, fading to that row's average colour
        val avgTop = average(out, firstSeen); val avgBottom = average(out, lastSeen)
        for (row in 0 until firstSeen) {
            val t = 1f - row.toFloat() / firstSeen.coerceAtLeast(1)
            for (col in 0 until width) out[row * width + col] = mix(out[firstSeen * width + col], avgTop, t)
        }
        for (row in lastSeen + 1 until height) {
            val t = (row - lastSeen).toFloat() / (height - 1 - lastSeen).coerceAtLeast(1)
            for (col in 0 until width) out[row * width + col] = mix(out[lastSeen * width + col], avgBottom, t)
        }
        // inner rows never seen (rare): copy the row above
        for (row in firstSeen..lastSeen) if (!rowSeen[row]) System.arraycopy(out, (row - 1) * width, out, row * width, width)
        return out
    }

    private fun average(px: IntArray, row: Int): Int {
        var r = 0L; var g = 0L; var b = 0L
        for (col in 0 until width) { val p = px[row * width + col]; r += (p shr 16) and 0xff; g += (p shr 8) and 0xff; b += p and 0xff }
        return (0xff shl 24) or ((r / width).toInt() shl 16) or ((g / width).toInt() shl 8) or (b / width).toInt()
    }

    private fun mix(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = ((((a shr s) and 0xff) * (1 - t) + ((b shr s) and 0xff) * t).toInt().coerceIn(0, 255)) shl s
        return (0xff shl 24) or ch(16) or ch(8) or ch(0)
    }

    companion object {
        /** Horizontal/vertical angle covered, for UI hints. */
        fun fovDegrees(f: Float, size: Int) = Math.toDegrees(2.0 * atan2(size / 2.0, f.toDouble())).toFloat()
    }
}
