package com.hyperxr.questgames

import android.graphics.Bitmap
import com.hyperxr.rendering.GpuMesh
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.math.sqrt

/** Shapes the games need beyond the renderer's cube, sphere and cylinder. */
object GameMeshes {
    private class B {
        val v = ArrayList<Float>(); val i = ArrayList<Int>()
        fun vert(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, w: Float): Int { v += x; v += y; v += z; v += nx; v += ny; v += nz; v += u; v += w; return v.size / 8 - 1 }
        fun build() = GpuMesh(v.toFloatArray(), i.toIntArray())
    }

    /** A 1 m cube with rounded edges ([r]: edge radius, metres): the rhythm blocks, gloves, pads. */
    fun roundedBox(r: Float = 0.1f): GpuMesh {
        val b = B()
        val inner = 0.5f - r
        // face coordinates bunched at the edges, where the rounding is
        val k = 4
        val coords = ArrayList<Float>()
        for (j in 0..k) coords += -0.5f + r * (1f - cos(j / k.toFloat() * PI.toFloat() / 2f))
        for (j in k downTo 0) coords += 0.5f - r * (1f - cos(j / k.toFloat() * PI.toFloat() / 2f))
        val n = coords.size
        val faces = arrayOf(
            intArrayOf(0, 1, 2, 1), intArrayOf(0, 1, 2, -1), intArrayOf(2, 1, 0, 1), intArrayOf(2, 1, 0, -1), intArrayOf(0, 2, 1, 1), intArrayOf(0, 2, 1, -1),
        )
        val p = FloatArray(3)
        for (f in faces) {
            val base = b.v.size / 8
            for (a in 0 until n) for (c in 0 until n) {
                p[f[0]] = coords[a]; p[f[1]] = coords[c]; p[f[2]] = 0.5f * f[3]
                val qx = p[0].coerceIn(-inner, inner); val qy = p[1].coerceIn(-inner, inner); val qz = p[2].coerceIn(-inner, inner)
                var dx = p[0] - qx; var dy = p[1] - qy; var dz = p[2] - qz
                val l = sqrt(dx * dx + dy * dy + dz * dz).coerceAtLeast(1e-6f)
                dx /= l; dy /= l; dz /= l
                b.vert(qx + dx * r, qy + dy * r, qz + dz * r, dx, dy, dz, coords[a] + 0.5f, 0.5f - coords[c])
            }
            for (a in 0 until n - 1) for (c in 0 until n - 1) {
                val i0 = base + a * n + c; val i1 = i0 + 1; val i2 = i0 + n; val i3 = i2 + 1
                // winding so the face looks outwards: depends on which axes the face spans
                val flip = (f[3] > 0) == (f[0] == 0 && f[1] == 1 || f[0] == 2 && f[1] == 0 || f[0] == 1 && f[1] == 2)
                if (flip) { b.i += i0; b.i += i2; b.i += i1; b.i += i1; b.i += i2; b.i += i3 }
                else { b.i += i0; b.i += i1; b.i += i2; b.i += i1; b.i += i3; b.i += i2 }
            }
        }
        return b.build()
    }

    /** A ring in the XZ plane: [major] radius 0.5, tube radius [minor]. */
    fun torus(minor: Float = 0.04f, seg: Int = 48, side: Int = 10): GpuMesh {
        val b = B()
        for (s in 0..seg) {
            val a = 2 * PI.toFloat() * s / seg
            val ca = cos(a); val sa = sin(a)
            for (t in 0..side) {
                val c = 2 * PI.toFloat() * t / side
                val nx = cos(c) * ca; val ny = sin(c); val nz = cos(c) * sa
                b.vert(ca * 0.5f + nx * minor, ny * minor, sa * 0.5f + nz * minor, nx, ny, nz, s / seg.toFloat(), t / side.toFloat())
            }
        }
        for (s in 0 until seg) for (t in 0 until side) {
            val i0 = s * (side + 1) + t; val i1 = i0 + side + 1
            b.i += i0; b.i += i0 + 1; b.i += i1; b.i += i1; b.i += i0 + 1; b.i += i1 + 1
        }
        return b.build()
    }

    /** A flat arrow head pointing +Y in the XY plane (1 wide, 0.5 tall), facing +Z. */
    fun chevron(): GpuMesh {
        val b = B()
        b.vert(-0.5f, -0.25f, 0f, 0f, 0f, 1f, 0f, 1f)
        b.vert(0.5f, -0.25f, 0f, 0f, 0f, 1f, 1f, 1f)
        b.vert(0f, 0.25f, 0f, 0f, 0f, 1f, 0.5f, 0f)
        b.i += 0; b.i += 1; b.i += 2
        return b.build()
    }

    /** A cone along +Y (base radius 0.5 at y = -0.5, tip at 0.5). */
    fun cone(seg: Int = 24): GpuMesh {
        val b = B()
        for (s in 0..seg) {
            val a = 2 * PI.toFloat() * s / seg
            val x = sin(a); val z = cos(a)
            b.vert(x * 0.5f, -0.5f, z * 0.5f, x * 0.89f, 0.45f, z * 0.89f, s / seg.toFloat(), 1f)
            b.vert(0f, 0.5f, 0f, x * 0.89f, 0.45f, z * 0.89f, s / seg.toFloat(), 0f)
        }
        for (s in 0 until seg) { val a = s * 2; b.i += a; b.i += a + 2; b.i += a + 1 }
        val c = b.vert(0f, -0.5f, 0f, 0f, -1f, 0f, 0.5f, 0.5f)
        val first = b.v.size / 8
        for (s in 0..seg) { val a = 2 * PI.toFloat() * s / seg; b.vert(sin(a) * 0.5f, -0.5f, cos(a) * 0.5f, 0f, -1f, 0f, 0.5f, 0.5f) }
        for (s in 0 until seg) { b.i += c; b.i += first + s + 1; b.i += first + s }
        return b.build()
    }
}

/**
 * Materials painted on the CPU once (512 px, tiling): wood planks, grass, the ring canvas,
 * concrete, paper screens, brushed metal. Seeded, so they look the same every time.
 */
object GameTextures {
    const val SIZE = 512

    fun make(name: String): Bitmap {
        val px = IntArray(SIZE * SIZE)
        when (name) {
            "wood" -> planks(px, intArrayOf(0x9A6A3E, 0xA87645, 0x8C5E35, 0xB07F4B), 6, 11)
            "wood-dark" -> planks(px, intArrayOf(0x4A2E1C, 0x553522, 0x3F2617, 0x5C3B25), 6, 3)
            "floor" -> planks(px, intArrayOf(0xC9A27A, 0xD2AE86, 0xBF9670, 0xD8B790), 8, 5)
            "grass" -> grass(px, 0x4E9A3A, 0x6CB447, 17)
            "felt" -> grass(px, 0x2F8C46, 0x3AA055, 23)
            "moss" -> grass(px, 0x4F7A2C, 0x7CA43C, 29)
            "canvas" -> canvas(px)
            "concrete" -> speckle(px, 0x8D8F94, 0.18f, 31)
            "white" -> speckle(px, 0xF2F3F5, 0.035f, 37)
            "paper" -> shoji(px)
            "metal" -> brushed(px)
            "rock" -> speckle(px, 0x7A7468, 0.3f, 41)
            "sand" -> speckle(px, 0xD9C18E, 0.14f, 43)
            "facade" -> facade(px)
            "bark" -> bark(px)
            else -> speckle(px, 0xBBBBBB, 0.1f, 1)
        }
        return Bitmap.createBitmap(px, SIZE, SIZE, Bitmap.Config.ARGB_8888)
    }

    // ---------------------------------------------------------------- noise (wraps at the tile edge)
    private class Lattice(val cx: Int, val cy: Int, seed: Int) {
        val v = FloatArray(cx * cy).also { a -> val r = java.util.Random(seed.toLong()); for (i in a.indices) a[i] = r.nextFloat() }
        fun at(x: Float, y: Float): Float {
            val fx = x * cx; val fy = y * cy
            val x0 = floor(fx).toInt(); val y0 = floor(fy).toInt()
            var tx = fx - x0; var ty = fy - y0
            tx = tx * tx * (3 - 2 * tx); ty = ty * ty * (3 - 2 * ty)
            fun g(i: Int, j: Int) = v[Math.floorMod(j, cy) * cx + Math.floorMod(i, cx)]
            val a = g(x0, y0) + (g(x0 + 1, y0) - g(x0, y0)) * tx
            val c = g(x0, y0 + 1) + (g(x0 + 1, y0 + 1) - g(x0, y0 + 1)) * tx
            return a + (c - a) * ty
        }
    }

    private fun fbm(ls: List<Lattice>, x: Float, y: Float): Float { var s = 0f; var w = 0.5f; var t = 0f; for (l in ls) { s += l.at(x, y) * w; t += w; w *= 0.5f }; return s / t }

    private fun rgb(c: Int, k: Float): Int {
        val r = (((c shr 16) and 255) * k).toInt().coerceIn(0, 255)
        val g = (((c shr 8) and 255) * k).toInt().coerceIn(0, 255)
        val b = ((c and 255) * k).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun mixC(a: Int, b: Int, t: Float): Int {
        fun ch(s: Int) = (((a shr s) and 255) * (1 - t) + ((b shr s) and 255) * t).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    /** Planks along v: tone per plank, long grain, dark seams and staggered ends. */
    private fun planks(px: IntArray, tones: IntArray, count: Int, seed: Int) {
        val grain = listOf(Lattice(4, 64, seed), Lattice(8, 128, seed + 1), Lattice(16, 256, seed + 2))
        val knot = Lattice(6, 12, seed + 3)
        val r = java.util.Random(seed.toLong())
        val ends = FloatArray(count) { r.nextFloat() }
        val tone = IntArray(count) { tones[r.nextInt(tones.size)] }
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val u = x / SIZE.toFloat(); val v = y / SIZE.toFloat()
            val p = (u * count).toInt().coerceIn(0, count - 1)
            val inP = u * count - p
            val g = fbm(grain, u, v)
            val rings = 0.5f + 0.5f * sin((g * 30f + u * 40f) * 1f)
            var k = 0.82f + 0.25f * g + 0.08f * rings
            val kn = knot.at(u, v); if (kn > 0.82f) k *= 0.75f + (1f - kn) * 1.2f
            // seams: between planks and at each plank's end
            val seam = minOf(inP, 1 - inP) * SIZE / count
            val ev = abs(((v + ends[p]) % 1f) - 0.5f) * SIZE
            if (seam < 1.5f || ev > SIZE / 2f - 1.5f) k *= 0.45f
            else if (seam < 3f) k *= 0.8f
            px[y * SIZE + x] = rgb(tone[p], k)
        }
    }

    private fun grass(px: IntArray, dark: Int, light: Int, seed: Int) {
        val ls = listOf(Lattice(8, 8, seed), Lattice(32, 32, seed + 1), Lattice(128, 128, seed + 2), Lattice(256, 256, seed + 3))
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val n = fbm(ls, x / SIZE.toFloat(), y / SIZE.toFloat())
            px[y * SIZE + x] = mixC(dark, light, ((n - 0.3f) * 1.6f).coerceIn(0f, 1f))
        }
    }

    private fun speckle(px: IntArray, base: Int, amount: Float, seed: Int) {
        val ls = listOf(Lattice(6, 6, seed), Lattice(24, 24, seed + 1), Lattice(96, 96, seed + 2), Lattice(256, 256, seed + 3))
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val n = fbm(ls, x / SIZE.toFloat(), y / SIZE.toFloat())
            px[y * SIZE + x] = rgb(base, 1f + (n - 0.5f) * 2f * amount)
        }
    }

    /** The boxing ring mat: light blue canvas with a fine weave and a wide border band. */
    private fun canvas(px: IntArray) {
        val ls = listOf(Lattice(8, 8, 51), Lattice(64, 64, 52))
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val n = fbm(ls, x / SIZE.toFloat(), y / SIZE.toFloat())
            val weave = if ((x / 2 + y / 2) % 2 == 0) 1f else 0.94f
            px[y * SIZE + x] = rgb(0x5C8FD6, (0.85f + n * 0.25f) * weave)
        }
    }

    /** Paper panels in a dark wooden lattice (the dojo's walls). */
    private fun shoji(px: IntArray) {
        val ls = listOf(Lattice(16, 16, 61), Lattice(128, 128, 62))
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val gx = x % (SIZE / 4); val gy = y % (SIZE / 3)
            val frame = gx < 10 || gy < 10 || x % (SIZE / 4) in (SIZE / 8 - 3)..(SIZE / 8 + 3)
            val n = fbm(ls, x / SIZE.toFloat(), y / SIZE.toFloat())
            px[y * SIZE + x] = if (frame) rgb(0x3B2416, 0.9f + n * 0.2f) else rgb(0xF1E6CF, 0.92f + n * 0.12f)
        }
    }

    /** A tower's face: glass in a concrete grid, some windows lit, some with blinds (tile = 4 x 4 windows). */
    private fun facade(px: IntArray) {
        val ls = listOf(Lattice(4, 4, 81), Lattice(32, 32, 82))
        val r = java.util.Random(83)
        val tone = IntArray(16) { r.nextInt(4) }
        val cell = SIZE / 4
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val gx = x % cell; val gy = y % cell
            val frame = gx < 10 || gy < 14
            val n = fbm(ls, x / SIZE.toFloat(), y / SIZE.toFloat())
            val w = tone[(y / cell) * 4 + x / cell]
            px[y * SIZE + x] = when {
                frame -> rgb(0xA9ABAF, 0.9f + n * 0.2f)
                // sky reflected in the glass, brighter at the top of each pane
                w == 0 -> rgb(0xE8D9A8, 0.85f + n * 0.2f)
                w == 1 -> rgb(0x8C9097, 0.9f + n * 0.15f)
                else -> mixC(0x3E5874, 0x8FB3D6, (1f - gy / cell.toFloat()) * 0.6f + n * 0.3f)
            }
        }
    }

    /** Tree bark: deep vertical furrows. */
    private fun bark(px: IntArray) {
        val ls = listOf(Lattice(12, 3, 91), Lattice(24, 6, 92), Lattice(64, 16, 93))
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val n = fbm(ls, x / SIZE.toFloat(), y / SIZE.toFloat())
            val furrow = 0.5f + 0.5f * sin(x / SIZE.toFloat() * 2f * PI.toFloat() * 14f + n * 9f)
            px[y * SIZE + x] = rgb(0x5A4030, 0.55f + furrow * 0.35f + n * 0.3f)
        }
    }

    private fun brushed(px: IntArray) {
        val ls = listOf(Lattice(2, 128, 71), Lattice(4, 256, 72))
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            val n = fbm(ls, x / SIZE.toFloat(), y / SIZE.toFloat())
            px[y * SIZE + x] = rgb(0xB8BCC4, 0.85f + n * 0.3f)
        }
    }
}
