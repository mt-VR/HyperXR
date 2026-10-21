package com.hyperxr.rendering

import android.graphics.BitmapFactory
import android.opengl.GLES30
import com.hyperxr.gltf.GltfModel
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Interleaved (pos3, normal3, uv2) indexed mesh in a VAO. */
class GpuMesh(vertices: FloatArray, indices: IntArray) {
    val vao: Int
    private val vbo: Int
    private val ibo: Int
    val indexCount = indices.size
    /** Bounding sphere in model space, used for frustum culling. */
    val boundCenter = FloatArray(3)
    var boundRadius = 0f

    init {
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
        GLES30.glGenBuffers(1, ids, 0); vbo = ids[0]
        GLES30.glGenBuffers(1, ids, 0); ibo = ids[0]
        GLES30.glBindVertexArray(vao)
        val vb = Gl.floatBuffer(vertices.size).put(vertices).also { it.position(0) }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, vertices.size * 4, vb, GLES30.GL_STATIC_DRAW)
        val ib = Gl.intBuffer(indices.size).put(indices).also { it.position(0) }
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, indices.size * 4, ib, GLES30.GL_STATIC_DRAW)
        val stride = 8 * 4
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glBindVertexArray(0)

        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var minZ = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE; var maxZ = -Float.MAX_VALUE
        var i = 0
        while (i < vertices.size) {
            minX = minOf(minX, vertices[i]); maxX = maxOf(maxX, vertices[i])
            minY = minOf(minY, vertices[i + 1]); maxY = maxOf(maxY, vertices[i + 1])
            minZ = minOf(minZ, vertices[i + 2]); maxZ = maxOf(maxZ, vertices[i + 2])
            i += 8
        }
        boundCenter[0] = (minX + maxX) / 2; boundCenter[1] = (minY + maxY) / 2; boundCenter[2] = (minZ + maxZ) / 2
        val dx = maxX - minX; val dy = maxY - minY; val dz = maxZ - minZ
        boundRadius = sqrt(dx * dx + dy * dy + dz * dz) / 2
    }

    fun draw() {
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_INT, 0)
    }

    fun release() {
        GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        GLES30.glDeleteBuffers(2, intArrayOf(vbo, ibo), 0)
    }

    companion object {
        private class Builder {
            val v = ArrayList<Float>()
            val i = ArrayList<Int>()
            fun vert(x: Float, y: Float, z: Float, nx: Float, ny: Float, nz: Float, u: Float, w: Float): Int {
                v.addAll(listOf(x, y, z, nx, ny, nz, u, w)); return v.size / 8 - 1
            }
            fun build() = GpuMesh(v.toFloatArray(), i.toIntArray())
        }

        /** Unit cube centred at the origin (1 m edge). */
        fun cube(): GpuMesh {
            val b = Builder()
            val faces = arrayOf(
                floatArrayOf(0f, 0f, 1f, 1f, 0f, 0f, 0f, 1f, 0f), floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f),
                floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f), floatArrayOf(-1f, 0f, 0f, 0f, 0f, 1f, 0f, 1f, 0f),
                floatArrayOf(0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, -1f), floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f),
            )
            for (f in faces) {
                val n = f.sliceArray(0..2); val r = f.sliceArray(3..5); val u = f.sliceArray(6..8)
                val base = b.v.size / 8
                for ((su, sv) in listOf(-1f to -1f, 1f to -1f, 1f to 1f, -1f to 1f)) {
                    b.vert(
                        0.5f * (n[0] + su * r[0] + sv * u[0]), 0.5f * (n[1] + su * r[1] + sv * u[1]), 0.5f * (n[2] + su * r[2] + sv * u[2]),
                        n[0], n[1], n[2], (su + 1) / 2, 1 - (sv + 1) / 2,
                    )
                }
                b.i.addAll(listOf(base, base + 1, base + 2, base, base + 2, base + 3))
            }
            return b.build()
        }

        /** UV sphere of radius 0.5. [inward] flips winding/normals for sky domes. */
        fun sphere(rings: Int = 24, segments: Int = 32, inward: Boolean = false, radius: Float = 0.5f): GpuMesh {
            val b = Builder()
            for (r in 0..rings) {
                val phi = PI * r / rings
                for (s in 0..segments) {
                    val th = 2 * PI * s / segments
                    val x = (sin(phi) * sin(th)).toFloat(); val y = cos(phi).toFloat(); val z = (sin(phi) * cos(th)).toFloat()
                    val sign = if (inward) -1f else 1f
                    b.vert(x * radius, y * radius, z * radius, x * sign, y * sign, z * sign, s.toFloat() / segments, r.toFloat() / rings)
                }
            }
            for (r in 0 until rings) for (s in 0 until segments) {
                val a = r * (segments + 1) + s; val c = a + segments + 1
                if (inward) b.i.addAll(listOf(a, a + 1, c, c, a + 1, c + 1)) else b.i.addAll(listOf(a, c, a + 1, c, c + 1, a + 1))
            }
            return b.build()
        }

        /** 1x1 plane in XZ facing +Y. */
        fun plane(uvRepeat: Float = 1f): GpuMesh {
            val b = Builder()
            b.vert(-0.5f, 0f, -0.5f, 0f, 1f, 0f, 0f, 0f)
            b.vert(-0.5f, 0f, 0.5f, 0f, 1f, 0f, 0f, uvRepeat)
            b.vert(0.5f, 0f, 0.5f, 0f, 1f, 0f, uvRepeat, uvRepeat)
            b.vert(0.5f, 0f, -0.5f, 0f, 1f, 0f, uvRepeat, 0f)
            b.i.addAll(listOf(0, 1, 2, 0, 2, 3))
            return b.build()
        }

        /** 1x1 quad in XY facing +Z, UV origin top-left (matches Bitmap orientation). */
        fun quad(): GpuMesh {
            val b = Builder()
            b.vert(-0.5f, -0.5f, 0f, 0f, 0f, 1f, 0f, 1f)
            b.vert(0.5f, -0.5f, 0f, 0f, 0f, 1f, 1f, 1f)
            b.vert(0.5f, 0.5f, 0f, 0f, 0f, 1f, 1f, 0f)
            b.vert(-0.5f, 0.5f, 0f, 0f, 0f, 1f, 0f, 0f)
            b.i.addAll(listOf(0, 1, 2, 0, 2, 3))
            return b.build()
        }

        /** Cylinder of radius 0.5, height 1, along Y. */
        fun cylinder(segments: Int = 24): GpuMesh {
            val b = Builder()
            for (s in 0..segments) {
                val th = 2 * PI * s / segments
                val x = sin(th).toFloat(); val z = cos(th).toFloat()
                b.vert(x * 0.5f, -0.5f, z * 0.5f, x, 0f, z, s.toFloat() / segments, 1f)
                b.vert(x * 0.5f, 0.5f, z * 0.5f, x, 0f, z, s.toFloat() / segments, 0f)
            }
            for (s in 0 until segments) {
                val a = s * 2
                b.i.addAll(listOf(a, a + 2, a + 1, a + 1, a + 2, a + 3))
            }
            for (cap in listOf(-0.5f, 0.5f)) {
                val ny = if (cap > 0) 1f else -1f
                val center = b.vert(0f, cap, 0f, 0f, ny, 0f, 0.5f, 0.5f)
                val first = b.v.size / 8
                for (s in 0..segments) {
                    val th = 2 * PI * s / segments
                    b.vert((sin(th) * 0.5).toFloat(), cap, (cos(th) * 0.5).toFloat(), 0f, ny, 0f, 0.5f + sin(th).toFloat() / 2, 0.5f + cos(th).toFloat() / 2)
                }
                for (s in 0 until segments) {
                    if (cap > 0) b.i.addAll(listOf(center, first + s, first + s + 1)) else b.i.addAll(listOf(center, first + s + 1, first + s))
                }
            }
            return b.build()
        }
    }
}

/** A glTF model uploaded to the GPU: one GpuMesh per primitive placed by its node transform. */
class GpuModel(model: GltfModel) {
    class Part(val mesh: GpuMesh, val world: FloatArray, val color: FloatArray, val texture: Int, val alphaCutoff: Float, val blend: Boolean, val doubleSided: Boolean)

    val parts = ArrayList<Part>()
    val boundsMin = floatArrayOf(model.boundsMin.x, model.boundsMin.y, model.boundsMin.z)
    val boundsMax = floatArrayOf(model.boundsMax.x, model.boundsMax.y, model.boundsMax.z)
    private val textures = ArrayList<Int>()

    init {
        val imageTextures = model.images.map { img ->
            if (img.bytes.isEmpty()) 0 else {
                val opts = BitmapFactory.Options().apply { inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888 }
                val bmp = BitmapFactory.decodeByteArray(img.bytes, 0, img.bytes.size, opts)
                if (bmp == null) 0 else {
                    // Mobile memory budget: cap textures at 2048 px.
                    val scaled = if (maxOf(bmp.width, bmp.height) > 2048) {
                        val s = 2048f / maxOf(bmp.width, bmp.height)
                        android.graphics.Bitmap.createScaledBitmap(bmp, (bmp.width * s).toInt(), (bmp.height * s).toInt(), true)
                    } else bmp
                    val t = Gl.uploadBitmap(scaled, mipmap = true)
                    Gl.repeatTexture(t)
                    if (scaled !== bmp) scaled.recycle()
                    bmp.recycle()
                    textures += t
                    t
                }
            }
        }
        val meshCache = HashMap<Pair<Int, Int>, GpuMesh>()
        for (inst in model.instances) {
            val mesh = model.meshes[inst.mesh]
            for ((pi, prim) in mesh.primitives.withIndex()) {
                val gpu = meshCache.getOrPut(inst.mesh to pi) {
                    val n = prim.positions.size / 3
                    val uvs = prim.uvs
                    val v = FloatArray(n * 8)
                    for (k in 0 until n) {
                        v[k * 8] = prim.positions[k * 3]; v[k * 8 + 1] = prim.positions[k * 3 + 1]; v[k * 8 + 2] = prim.positions[k * 3 + 2]
                        v[k * 8 + 3] = prim.normals[k * 3]; v[k * 8 + 4] = prim.normals[k * 3 + 1]; v[k * 8 + 5] = prim.normals[k * 3 + 2]
                        if (uvs != null) { v[k * 8 + 6] = uvs[k * 2]; v[k * 8 + 7] = uvs[k * 2 + 1] }
                    }
                    GpuMesh(v, prim.indices)
                }
                val mat = model.materials.getOrNull(prim.material)
                parts += Part(
                    gpu, inst.world,
                    mat?.baseColor ?: floatArrayOf(0.8f, 0.8f, 0.8f, 1f),
                    mat?.baseColorImage?.let { imageTextures.getOrNull(it) } ?: 0,
                    if (mat?.alphaMode == "MASK") mat.alphaCutoff else 0f,
                    mat?.alphaMode == "BLEND",
                    mat?.doubleSided ?: false,
                )
            }
        }
    }

    fun release() {
        parts.map { it.mesh }.distinct().forEach { it.release() }
        textures.forEach { Gl.deleteTexture(it) }
    }
}
