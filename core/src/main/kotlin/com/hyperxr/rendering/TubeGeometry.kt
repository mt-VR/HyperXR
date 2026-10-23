package com.hyperxr.rendering

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A round tube along a polyline (strokes drawn in the air): interleaved pos3, normal3, uv2
 * vertices and triangle indices, rings oriented with parallel transport so the tube never twists.
 * Ends are capped with a fan so a stroke reads as a solid line from any side.
 */
object TubeGeometry {
    class Mesh(val vertices: FloatArray, val indices: IntArray)

    fun build(points: FloatArray, radius: Float, sides: Int = 6): Mesh? {
        val n = points.size / 3
        if (n < 2) return null
        fun p(i: Int, k: Int) = points[i * 3 + k]
        val verts = FloatArray((n * sides + 2) * 8)
        val idx = ArrayList<Int>(n * sides * 6 + sides * 6)
        // first frame: any normal perpendicular to the first segment
        var tx = p(1, 0) - p(0, 0); var ty = p(1, 1) - p(0, 1); var tz = p(1, 2) - p(0, 2)
        norm3(tx, ty, tz).let { tx = it[0]; ty = it[1]; tz = it[2] }
        // n = t x up (or t x x when the stroke starts vertically)
        val c0 = if (kotlin.math.abs(ty) < 0.9f) cross(tx, ty, tz, 0f, 1f, 0f) else cross(tx, ty, tz, 1f, 0f, 0f)
        val n0 = norm3(c0[0], c0[1], c0[2])
        var nx = n0[0]; var ny = n0[1]; var nz = n0[2]
        var length = 0f
        for (i in 0 until n) {
            // tangent: average of the neighbouring segments
            val a = if (i == 0) 0 else i - 1; val b = if (i == n - 1) n - 1 else i + 1
            var ux = p(b, 0) - p(a, 0); var uy = p(b, 1) - p(a, 1); var uz = p(b, 2) - p(a, 2)
            val ul = sqrt(ux * ux + uy * uy + uz * uz)
            if (ul > 1e-6f) { ux /= ul; uy /= ul; uz /= ul } else { ux = tx; uy = ty; uz = tz }
            // parallel transport: remove the new tangent's part from the old normal
            val d = nx * ux + ny * uy + nz * uz
            nx -= ux * d; ny -= uy * d; nz -= uz * d
            norm3(nx, ny, nz).let { nx = it[0]; ny = it[1]; nz = it[2] }
            val bnv = cross(ux, uy, uz, nx, ny, nz)
            tx = ux; ty = uy; tz = uz
            if (i > 0) length += dist(points, i - 1, i)
            for (s in 0 until sides) {
                val ang = (2 * PI * s / sides).toFloat()
                val cx = cos(ang); val sx = sin(ang)
                val ox = nx * cx + bnv[0] * sx; val oy = ny * cx + bnv[1] * sx; val oz = nz * cx + bnv[2] * sx
                val o = (i * sides + s) * 8
                verts[o] = p(i, 0) + ox * radius; verts[o + 1] = p(i, 1) + oy * radius; verts[o + 2] = p(i, 2) + oz * radius
                verts[o + 3] = ox; verts[o + 4] = oy; verts[o + 5] = oz
                verts[o + 6] = s.toFloat() / sides; verts[o + 7] = length
            }
            if (i > 0) for (s in 0 until sides) {
                val s1 = (s + 1) % sides
                val a0 = (i - 1) * sides + s; val a1 = (i - 1) * sides + s1; val b0 = i * sides + s; val b1 = i * sides + s1
                idx += a0; idx += b0; idx += a1; idx += a1; idx += b0; idx += b1
            }
        }
        // caps: centre vertices at both ends
        for ((k, i) in intArrayOf(0, n - 1).withIndex()) {
            val c = n * sides + k
            val o = c * 8
            verts[o] = p(i, 0); verts[o + 1] = p(i, 1); verts[o + 2] = p(i, 2)
            val sgn = if (k == 0) -1f else 1f
            verts[o + 3] = tx * sgn; verts[o + 4] = ty * sgn; verts[o + 5] = tz * sgn
            for (s in 0 until sides) {
                val s1 = (s + 1) % sides
                if (k == 0) { idx += c; idx += s1; idx += s } else { idx += c; idx += i * sides + s; idx += i * sides + s1 }
            }
        }
        return Mesh(verts, idx.toIntArray())
    }

    private fun dist(p: FloatArray, a: Int, b: Int): Float {
        val dx = p[b * 3] - p[a * 3]; val dy = p[b * 3 + 1] - p[a * 3 + 1]; val dz = p[b * 3 + 2] - p[a * 3 + 2]
        return sqrt(dx * dx + dy * dy + dz * dz)
    }
    private fun cross(ax: Float, ay: Float, az: Float, bx: Float, by: Float, bz: Float) = floatArrayOf(ay * bz - az * by, az * bx - ax * bz, ax * by - ay * bx)
    private fun norm3(x: Float, y: Float, z: Float): FloatArray { val l = sqrt(x * x + y * y + z * z); return if (l < 1e-9f) floatArrayOf(1f, 0f, 0f) else floatArrayOf(x / l, y / l, z / l) }
}

/** Turns a moving fingertip into a smooth stroke: points only every [step] metres, eased. */
class StrokeSampler(var step: Float = 0.006f, var smoothing: Float = 0.45f) {
    val points = ArrayList<Float>()
    private var sx = 0f; private var sy = 0f; private var sz = 0f
    private var started = false

    fun reset() { points.clear(); started = false }

    /** Adds the fingertip's position; true when the stroke got a new point. */
    fun add(x: Float, y: Float, z: Float): Boolean {
        if (!started) { sx = x; sy = y; sz = z; started = true; points += x; points += y; points += z; return true }
        sx += (x - sx) * (1 - smoothing); sy += (y - sy) * (1 - smoothing); sz += (z - sz) * (1 - smoothing)
        val n = points.size
        val dx = sx - points[n - 3]; val dy = sy - points[n - 2]; val dz = sz - points[n - 1]
        if (dx * dx + dy * dy + dz * dz < step * step) return false
        points += sx; points += sy; points += sz
        return true
    }

    fun toArray(): FloatArray = points.toFloatArray()
}
