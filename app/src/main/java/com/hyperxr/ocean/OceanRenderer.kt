package com.hyperxr.ocean

import android.graphics.Bitmap
import android.opengl.GLES30
import android.opengl.Matrix
import com.hyperxr.rendering.EyeView
import com.hyperxr.rendering.FullscreenTriangle
import com.hyperxr.rendering.Gl
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Draws the Ocean app's world with its own shaders (GL thread):
 *  - the sky: colour by sun height, sun disk and glow, moving clouds, stars at night, the
 *    lightning's light;
 *  - the sea: a radial grid around the viewer (fine near, coarse far, out to the horizon) moved
 *    by the Gerstner waves on the GPU, with fine ripples from a tiling normal map, Fresnel
 *    reflection of the sky, light through the wave crests, the sun's glitter, foam where crests
 *    break, and haze toward the horizon;
 *  - the room: the camera photo projected onto the room's depth, which sinks below the surface;
 *  - lightning bolts, rain streaks and the depth sensor's points.
 * Sky and sea are tone-mapped (ACES) from linear light, so the sun can be much brighter than the
 * clouds; the room photo is drawn as it was taken.
 */
class OceanRenderer {
    class Look {
        val sunDir = floatArrayOf(0f, 0.5f, -0.86f)
        val sunColor = floatArrayOf(3f, 2.9f, 2.7f)
        val zenith = floatArrayOf(0.18f, 0.38f, 0.75f)
        val horizon = floatArrayOf(0.62f, 0.75f, 0.9f)
        val cloudLit = floatArrayOf(1f, 1f, 1f)
        val cloudShade = floatArrayOf(0.4f, 0.42f, 0.48f)
        val deep = floatArrayOf(0.0f, 0.04f, 0.07f)
        val scatter = floatArrayOf(0.0f, 0.3f, 0.28f)
        var cloud = 0.3f
        var night = 0f
        var flash = 0f
        var rain = 0f
        var exposure = 1f
        var fog = 0.0002f
        var ambient = 1f
    }

    val look = Look()
    var waves = OceanWaves.pack(0f)
    var seaLevel = 0f
    var time = 0f
    /** Waves are calmed within this distance of the viewer (they would wash over the eyes). */
    var calmRadius = 18f

    private var skyProg = 0; private var seaProg = 0; private var roomProg = 0; private var rainProg = 0; private var boltProg = 0; private var pointProg = 0
    private var skyVao = 0; private var seaVao = 0; private var roomVao = 0; private var emptyVao = 0; private var boltVao = 0; private var pointVao = 0
    private var seaVbo = 0; private var seaIbo = 0; private var seaCount = 0
    private var roomVbo = 0; private var roomIbo = 0; private var roomCount = 0; private var roomTex = 0
    private var boltVbo = 0; private var boltCount = 0
    private var pointVbo = 0; private var pointCount = 0
    private var noiseTex = 0
    private val uniformCache = HashMap<Int, HashMap<String, Int>>()

    // per eye
    private val farProj = FloatArray(16)
    private val farVp = FloatArray(16)
    private val rotView = FloatArray(16)
    private val rotVp = FloatArray(16)
    private val invRotVp = FloatArray(16)

    // the room
    var roomVisible = false
    /** How far the room has gone down (metres). */
    var roomDrop = 0f
    var roomLight = 1f
    var boltIntensity = 0f
    var rainCount = 0
    var wind = floatArrayOf(1.5f, 0.6f)
    var pointsVisible = false

    fun init() {
        if (skyProg != 0) return
        skyProg = Gl.compile(SKY_VS, SKY_FS, "ocean-sky")
        seaProg = Gl.compile(SEA_VS, SEA_FS, "ocean-sea")
        roomProg = Gl.compile(ROOM_VS, ROOM_FS, "ocean-room")
        rainProg = Gl.compile(RAIN_VS, RAIN_FS, "ocean-rain")
        boltProg = Gl.compile(BOLT_VS, BOLT_FS, "ocean-bolt")
        pointProg = Gl.compile(POINT_VS, POINT_FS, "ocean-points")
        val ids = IntArray(6)
        GLES30.glGenVertexArrays(6, ids, 0)
        skyVao = ids[0]; seaVao = ids[1]; roomVao = ids[2]; emptyVao = ids[3]; boltVao = ids[4]; pointVao = ids[5]
        GLES30.glBindVertexArray(skyVao); FullscreenTriangle.bind(); GLES30.glBindVertexArray(0)
        buildSea()
        // raw RGBA: the alpha channel is data (foam), not transparency, so no Bitmap premultiplying
        val size = 256
        noiseTex = Gl.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noiseTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, size, size, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, OceanTextures.noise(size))
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        Gl.repeatTexture(noiseTex)
    }

    fun release() {
        for (p in intArrayOf(skyProg, seaProg, roomProg, rainProg, boltProg, pointProg)) if (p != 0) GLES30.glDeleteProgram(p)
        skyProg = 0; seaProg = 0; roomProg = 0; rainProg = 0; boltProg = 0; pointProg = 0
        GLES30.glDeleteVertexArrays(6, intArrayOf(skyVao, seaVao, roomVao, emptyVao, boltVao, pointVao), 0)
        GLES30.glDeleteBuffers(7, intArrayOf(seaVbo, seaIbo, roomVbo, roomIbo, boltVbo, pointVbo, 0), 0)
        seaVbo = 0; seaIbo = 0; roomVbo = 0; roomIbo = 0; boltVbo = 0; pointVbo = 0
        Gl.deleteTexture(noiseTex); Gl.deleteTexture(roomTex); noiseTex = 0; roomTex = 0
        uniformCache.clear()
    }

    private fun u(prog: Int, name: String): Int = uniformCache.getOrPut(prog) { HashMap() }.getOrPut(name) { GLES30.glGetUniformLocation(prog, name) }

    // ------------------------------------------------------------------ geometry
    /**
     * Rings around the viewer, each a little wider than the last (6% growth): ~3 cm apart at the
     * feet, hundreds of metres at the horizon, so detail is spent where it can be seen.
     */
    private fun buildSea() {
        val rings = 150; val segs = 160
        val v = FloatArray((rings + 1) * segs * 2 + 2)
        var o = 0
        v[o++] = 0f; v[o++] = 0f
        val r0 = 0.25f; val growth = (6000f / r0).pow(1f / rings)
        for (i in 0..rings) {
            val r = r0 * growth.pow(i)
            for (j in 0 until segs) {
                val a = (2 * PI * j / segs).toFloat()
                v[o++] = cos(a) * r; v[o++] = sin(a) * r
            }
        }
        val idx = IntArray(segs * 3 + rings * segs * 6)
        var k = 0
        for (j in 0 until segs) { idx[k++] = 0; idx[k++] = 1 + (j + 1) % segs; idx[k++] = 1 + j }
        for (i in 0 until rings) for (j in 0 until segs) {
            val a = 1 + i * segs + j; val b = 1 + i * segs + (j + 1) % segs
            val c = a + segs; val d = b + segs
            idx[k++] = a; idx[k++] = b; idx[k++] = c
            idx[k++] = b; idx[k++] = d; idx[k++] = c
        }
        seaCount = k
        val ids = IntArray(2); GLES30.glGenBuffers(2, ids, 0); seaVbo = ids[0]; seaIbo = ids[1]
        GLES30.glBindVertexArray(seaVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, seaVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v.size * 4, fb(v), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 2, GLES30.GL_FLOAT, false, 8, 0)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, seaIbo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, idx.size * 4, ib(idx), GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
    }

    /**
     * The room from a camera photo: [positions] (x, y, z per grid vertex, world metres, row-major
     * [gw] x [gh]) and the photo itself. Triangles across depth jumps (an edge of furniture against
     * the far wall) are left out instead of being stretched.
     */
    fun setRoom(photo: Bitmap, positions: FloatArray, gw: Int, gh: Int, eyeX: Float, eyeY: Float, eyeZ: Float) {
        Gl.deleteTexture(roomTex)
        roomTex = Gl.uploadBitmap(photo, mipmap = true)
        val v = FloatArray(gw * gh * 5)
        for (y in 0 until gh) for (x in 0 until gw) {
            val i = y * gw + x
            v[i * 5] = positions[i * 3]; v[i * 5 + 1] = positions[i * 3 + 1]; v[i * 5 + 2] = positions[i * 3 + 2]
            v[i * 5 + 3] = x / (gw - 1f); v[i * 5 + 4] = y / (gh - 1f)
        }
        fun dist(i: Int): Float {
            val dx = positions[i * 3] - eyeX; val dy = positions[i * 3 + 1] - eyeY; val dz = positions[i * 3 + 2] - eyeZ
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
        val idx = ArrayList<Int>(gw * gh * 6)
        for (y in 0 until gh - 1) for (x in 0 until gw - 1) {
            val a = y * gw + x; val b = a + 1; val c = a + gw; val d = c + 1
            val ds = floatArrayOf(dist(a), dist(b), dist(c), dist(d))
            if (ds.max() / ds.min().coerceAtLeast(0.05f) > 1.6f) continue
            idx += a; idx += c; idx += b; idx += b; idx += c; idx += d
        }
        roomCount = idx.size
        if (roomVbo == 0) { val ids = IntArray(2); GLES30.glGenBuffers(2, ids, 0); roomVbo = ids[0]; roomIbo = ids[1] }
        GLES30.glBindVertexArray(roomVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, roomVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v.size * 4, fb(v), GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, roomIbo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, roomCount * 4, ib(idx.toIntArray()), GLES30.GL_STATIC_DRAW)
        GLES30.glBindVertexArray(0)
    }

    fun clearRoom() { roomCount = 0; roomVisible = false }
    val hasRoom get() = roomCount > 0

    /**
     * A lightning bolt as glowing ribbons: [paths] from [LightningPath] placed at [base] (world),
     * seen from [eye]; a thin bright core and a wide soft glow.
     */
    fun setBolt(paths: List<FloatArray>, baseX: Float, baseY: Float, baseZ: Float, eyeX: Float, eyeY: Float, eyeZ: Float, distance: Float) {
        val out = ArrayList<Float>()
        val core = distance * 0.0035f
        for ((pi, p) in paths.withIndex()) {
            val n = p.size / 3
            val main = if (pi == 0) 1f else 0.55f
            for (layer in 0..1) {
                val w = if (layer == 0) core * main else core * main * 7f
                val a = if (layer == 0) 1f else 0.18f
                for (i in 0 until n - 1) {
                    val ax = baseX + p[i * 3]; val ay = baseY + p[i * 3 + 1]; val az = baseZ + p[i * 3 + 2]
                    val bx = baseX + p[i * 3 + 3]; val by = baseY + p[i * 3 + 4]; val bz = baseZ + p[i * 3 + 5]
                    // side vector: across the segment, facing the viewer
                    var tx = (ay - eyeY) * (bz - az) - (az - eyeZ) * (by - ay)
                    var ty = (az - eyeZ) * (bx - ax) - (ax - eyeX) * (bz - az)
                    var tz = (ax - eyeX) * (by - ay) - (ay - eyeY) * (bx - ax)
                    val l = sqrt(tx * tx + ty * ty + tz * tz).coerceAtLeast(1e-4f)
                    tx = tx / l * w / 2; ty = ty / l * w / 2; tz = tz / l * w / 2
                    // fades towards the branch tips
                    val fa = a * (1f - i.toFloat() / n * (if (pi == 0) 0.3f else 0.9f))
                    val fb = a * (1f - (i + 1f) / n * (if (pi == 0) 0.3f else 0.9f))
                    fun vtx(x: Float, y: Float, z: Float, e: Float, al: Float) { out += x; out += y; out += z; out += e; out += al }
                    vtx(ax - tx, ay - ty, az - tz, -1f, fa); vtx(ax + tx, ay + ty, az + tz, 1f, fa); vtx(bx - tx, by - ty, bz - tz, -1f, fb)
                    vtx(ax + tx, ay + ty, az + tz, 1f, fa); vtx(bx + tx, by + ty, bz + tz, 1f, fb); vtx(bx - tx, by - ty, bz - tz, -1f, fb)
                }
            }
        }
        val v = out.toFloatArray()
        boltCount = v.size / 5
        if (boltVbo == 0) { val ids = IntArray(1); GLES30.glGenBuffers(1, ids, 0); boltVbo = ids[0] }
        GLES30.glBindVertexArray(boltVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, boltVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v.size * 4, fb(v), GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
        GLES30.glEnableVertexAttribArray(1)
        GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
        GLES30.glBindVertexArray(0)
    }

    /** The depth sensor's points (x, y, z each), drawn as dots coloured by distance. */
    fun setPoints(p: FloatArray, n: Int) {
        pointCount = n
        if (n == 0) return
        if (pointVbo == 0) { val ids = IntArray(1); GLES30.glGenBuffers(1, ids, 0); pointVbo = ids[0] }
        GLES30.glBindVertexArray(pointVao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, pointVbo)
        val buf = ByteBuffer.allocateDirect(n * 12).order(ByteOrder.nativeOrder()).asFloatBuffer().put(p, 0, n * 3).also { it.position(0) }
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, n * 12, buf, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glBindVertexArray(0)
    }

    // ------------------------------------------------------------------ drawing
    private fun eyeMatrices(eye: EyeView) {
        // the sea reaches the horizon: same lens, far plane at 8 km (depth is not used for it)
        System.arraycopy(eye.proj, 0, farProj, 0, 16)
        val n = 0.1f; val f = 8000f
        farProj[10] = -(f + n) / (f - n); farProj[14] = -2f * f * n / (f - n)
        Matrix.multiplyMM(farVp, 0, farProj, 0, eye.view, 0)
        System.arraycopy(eye.view, 0, rotView, 0, 16)
        rotView[12] = 0f; rotView[13] = 0f; rotView[14] = 0f
        Matrix.multiplyMM(rotVp, 0, farProj, 0, rotView, 0)
        Matrix.invertM(invRotVp, 0, rotVp, 0)
    }

    private fun lookUniforms(p: Int) {
        val l = look
        GLES30.glUniform3fv(u(p, "uSunDir"), 1, l.sunDir, 0)
        GLES30.glUniform3fv(u(p, "uSunColor"), 1, l.sunColor, 0)
        GLES30.glUniform3fv(u(p, "uZenith"), 1, l.zenith, 0)
        GLES30.glUniform3fv(u(p, "uHorizon"), 1, l.horizon, 0)
        GLES30.glUniform3fv(u(p, "uCloudLit"), 1, l.cloudLit, 0)
        GLES30.glUniform3fv(u(p, "uCloudShade"), 1, l.cloudShade, 0)
        GLES30.glUniform1f(u(p, "uCloud"), l.cloud)
        GLES30.glUniform1f(u(p, "uNight"), l.night)
        GLES30.glUniform1f(u(p, "uFlash"), l.flash)
        GLES30.glUniform1f(u(p, "uExposure"), l.exposure)
        GLES30.glUniform1f(u(p, "uTime"), time)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noiseTex)
        GLES30.glUniform1i(u(p, "uNoise"), 0)
    }

    private fun waveUniforms(p: Int) {
        GLES30.glUniform4fv(u(p, "uW"), OceanWaves.COUNT * 2, waves, 0)
        GLES30.glUniform1f(u(p, "uSea"), seaLevel)
        GLES30.glUniform1f(u(p, "uCalm"), calmRadius)
    }

    /** Sky, bolt, sea and the sinking room: the whole background of one eye. */
    fun drawBackground(eye: EyeView) {
        eyeMatrices(eye)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)

        // sky
        GLES30.glUseProgram(skyProg)
        lookUniforms(skyProg)
        GLES30.glUniformMatrix4fv(u(skyProg, "uInvVP"), 1, false, invRotVp, 0)
        GLES30.glBindVertexArray(skyVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // lightning (behind the sea's horizon)
        if (boltIntensity > 0.01f && boltCount > 0) {
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
            GLES30.glUseProgram(boltProg)
            GLES30.glUniformMatrix4fv(u(boltProg, "uVP"), 1, false, farVp, 0)
            GLES30.glUniform1f(u(boltProg, "uIntensity"), boltIntensity)
            GLES30.glBindVertexArray(boltVao)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, boltCount)
            GLES30.glDisable(GLES30.GL_BLEND)
        }

        // sea: depth-tested against itself so a near crest hides the trough behind it
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glUseProgram(seaProg)
        lookUniforms(seaProg)
        waveUniforms(seaProg)
        GLES30.glUniformMatrix4fv(u(seaProg, "uVP"), 1, false, farVp, 0)
        val e = eye.position
        // the grid moves with the viewer in 2 m steps (no crawling of the vertices)
        GLES30.glUniform3f(u(seaProg, "uCenter"), floor(e.x / 2f) * 2f, seaLevel, floor(e.z / 2f) * 2f)
        GLES30.glUniform3f(u(seaProg, "uEye"), e.x, e.y, e.z)
        GLES30.glUniform3fv(u(seaProg, "uDeep"), 1, look.deep, 0)
        GLES30.glUniform3fv(u(seaProg, "uScatter"), 1, look.scatter, 0)
        GLES30.glUniform1f(u(seaProg, "uRain"), look.rain)
        GLES30.glUniform1f(u(seaProg, "uFog"), look.fog)
        GLES30.glUniform1f(u(seaProg, "uAmbient"), look.ambient)
        GLES30.glUniform1f(u(seaProg, "uMaxH"), OceanWaves.maxHeight(waves).coerceAtLeast(0.2f))
        GLES30.glBindVertexArray(seaVao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, seaCount, GLES30.GL_UNSIGNED_INT, 0)
        // the sea's depth is in its own far-plane range: cleared before anything else uses depth
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)

        // the room, going down (depth-tested against itself only, then cleared for the scene)
        if (roomVisible && roomCount > 0) {
            GLES30.glUseProgram(roomProg)
            waveUniforms(roomProg)
            GLES30.glUniformMatrix4fv(u(roomProg, "uVP"), 1, false, eye.viewProj, 0)
            GLES30.glUniform1f(u(roomProg, "uDrop"), roomDrop)
            GLES30.glUniform1f(u(roomProg, "uLight"), roomLight)
            GLES30.glUniform1f(u(roomProg, "uTime"), time)
            GLES30.glUniform3f(u(roomProg, "uEye"), e.x, e.y, e.z)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, roomTex)
            GLES30.glUniform1i(u(roomProg, "uTex"), 0)
            GLES30.glBindVertexArray(roomVao)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, roomCount, GLES30.GL_UNSIGNED_INT, 0)
            GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        }
        GLES30.glBindVertexArray(0)
    }

    /** Rain and the depth points, after the 3D scene (depth-tested against it). */
    fun drawOverlay(eye: EyeView) {
        val e = eye.position
        if (pointsVisible && pointCount > 0) {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthMask(false)
            GLES30.glUseProgram(pointProg)
            GLES30.glUniformMatrix4fv(u(pointProg, "uVP"), 1, false, eye.viewProj, 0)
            GLES30.glUniform3f(u(pointProg, "uEye"), e.x, e.y, e.z)
            GLES30.glUniform1f(u(pointProg, "uTime"), time)
            GLES30.glBindVertexArray(pointVao)
            GLES30.glDrawArrays(GLES30.GL_POINTS, 0, pointCount)
        }
        if (rainCount > 0) {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthMask(false)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            GLES30.glUseProgram(rainProg)
            GLES30.glUniformMatrix4fv(u(rainProg, "uVP"), 1, false, eye.viewProj, 0)
            GLES30.glUniform3f(u(rainProg, "uCam"), e.x, e.y, e.z)
            GLES30.glUniform1f(u(rainProg, "uTime"), time)
            GLES30.glUniform2f(u(rainProg, "uWind"), wind[0], wind[1])
            GLES30.glUniform1f(u(rainProg, "uSea"), seaLevel)
            GLES30.glUniform1f(u(rainProg, "uLight"), (look.ambient * 0.6f + look.flash).coerceIn(0.15f, 1.5f))
            GLES30.glBindVertexArray(emptyVao)
            GLES30.glDrawArrays(GLES30.GL_LINES, 0, rainCount * 2)
            GLES30.glDisable(GLES30.GL_BLEND)
        }
        GLES30.glBindVertexArray(0)
    }

    private fun fb(a: FloatArray) = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(a).also { it.position(0) }
    private fun ib(a: IntArray) = ByteBuffer.allocateDirect(a.size * 4).order(ByteOrder.nativeOrder()).asIntBuffer().put(a).also { it.position(0) }

    companion object {
        /** Sky colour for a direction, shared by the sky and the sea's reflections. */
        private const val SKY_FN = """
uniform vec3 uSunDir;
uniform vec3 uSunColor;
uniform vec3 uZenith;
uniform vec3 uHorizon;
uniform vec3 uCloudLit;
uniform vec3 uCloudShade;
uniform float uCloud;
uniform float uNight;
uniform float uFlash;
uniform float uExposure;
uniform float uTime;
uniform sampler2D uNoise;

vec3 sky(vec3 d, bool withSun) {
    float h = d.y;
    float y = max(h, 0.0);
    vec3 c = mix(uHorizon, uZenith, pow(y, 0.5));
    c = mix(c, uHorizon * 1.05, exp(-y * 22.0) * 0.35);
    float sd = max(dot(d, uSunDir), 0.0);
    vec3 glow = uSunColor * (pow(sd, 48.0) * 0.5 + pow(sd, 5.0) * 0.12);
    vec3 disk = withSun ? uSunColor * smoothstep(0.99955, 0.9997, sd) * 40.0 : vec3(0.0);
    float cl = 0.0;
    if (h > 0.0) {
        vec2 uv = d.xz / (h + 0.1) * 0.11 + vec2(uTime * 0.0018, uTime * 0.0009);
        float n = texture(uNoise, uv).r * 0.62 + texture(uNoise, uv * 2.7 + vec2(0.37, 0.11)).r * 0.38;
        cl = smoothstep(1.0 - uCloud, 1.0 - uCloud * 0.55 + 0.12, n) * smoothstep(0.0, 0.1, h);
        float thick = smoothstep(0.45, 0.95, n) * uCloud;
        vec3 cc = mix(uCloudLit + uSunColor * 0.3 * pow(sd, 3.0), uCloudShade, thick);
        c = mix(c, cc, cl);
        if (withSun && uNight > 0.01) {
            vec3 q = floor(d * 420.0);
            float r = fract(sin(dot(q, vec3(12.9898, 78.233, 37.719))) * 43758.5453);
            c += vec3(smoothstep(0.9982, 1.0, r) * uNight * (1.0 - cl) * 2.0);
        }
    } else {
        c = uHorizon * 0.8;
    }
    c += uFlash * (0.35 + cl) * vec3(0.55, 0.6, 0.78);
    return c + (glow + disk) * (1.0 - cl * 0.92);
}

vec3 tonemap(vec3 x) {
    x *= uExposure;
    vec3 a = (x * (2.51 * x + 0.03)) / (x * (2.43 * x + 0.59) + 0.14);
    return pow(clamp(a, 0.0, 1.0), vec3(1.0 / 2.2));
}
"""

        const val SKY_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uInvVP;
out vec3 vRay;
void main() {
    vec4 p = uInvVP * vec4(aPos.xy, 1.0, 1.0);
    vRay = p.xyz / p.w;
    gl_Position = vec4(aPos.xy, 0.9999, 1.0);
}"""

        const val SKY_FS = """#version 300 es
precision highp float;
in vec3 vRay;
out vec4 o;
$SKY_FN
void main() { o = vec4(tonemap(sky(normalize(vRay), true)), 1.0); }"""

        /** The waves, also used by the room to hide what is under the water. */
        private const val WAVE_FN = """
uniform vec4 uW[24];
uniform float uSea;
uniform float uCalm;
// near the viewer the long waves are calmed (they would wash over the eyes); short ones keep
// their full size, so the water there is as rough as anywhere else and does not turn to a mirror
float waveScale(float dist, float lambda) {
    float calm = mix(0.25, 1.0, smoothstep(uCalm * 0.25, uCalm, dist));
    return mix(1.0, calm, smoothstep(4.0, 12.0, lambda));
}
float waveHeight(vec2 xz, float t, float dist) {
    float h = 0.0;
    for (int i = 0; i < 12; i++) {
        vec4 a = uW[i * 2]; vec4 b = uW[i * 2 + 1];
        h += a.w * waveScale(dist, 6.2831853 / a.z) * sin(a.z * dot(a.xy, xz) - b.y * t + b.z);
    }
    return uSea + h;
}
"""

        const val SEA_VS = """#version 300 es
layout(location=0) in vec2 aPos;
uniform mat4 uVP;
uniform vec3 uCenter;
uniform vec3 uEye;
uniform float uTime;
$WAVE_FN
out vec3 vWorld;
out vec3 vNormal;
out float vJ;
void main() {
    vec2 xz = uCenter.xz + aPos;
    float dist = length(xz - uEye.xz);
    vec3 p = vec3(xz.x, 0.0, xz.y);
    vec3 n = vec3(0.0, 1.0, 0.0);
    float j = 1.0;
    for (int i = 0; i < 12; i++) {
        vec4 a = uW[i * 2]; vec4 b = uW[i * 2 + 1];
        float lambda = 6.2831853 / a.z;
        float amp = a.w * waveScale(dist, lambda) * (1.0 - smoothstep(3.0 * lambda, 5.5 * lambda, dist));
        float th = a.z * dot(a.xy, xz) - b.y * uTime + b.z;
        float c = cos(th); float sn = sin(th);
        p.x += b.x * amp * a.x * c;
        p.z += b.x * amp * a.y * c;
        p.y += amp * sn;
        float ka = a.z * amp;
        n.x -= a.x * ka * c;
        n.z -= a.y * ka * c;
        n.y -= b.x * ka * sn;
        j -= b.x * ka * sn;
    }
    p.y += uCenter.y;
    vWorld = p;
    vNormal = n;
    vJ = j;
    gl_Position = uVP * vec4(p, 1.0);
}"""

        const val SEA_FS = """#version 300 es
precision highp float;
in vec3 vWorld;
in vec3 vNormal;
in float vJ;
uniform vec3 uEye;
uniform vec3 uDeep;
uniform vec3 uScatter;
uniform float uRain;
uniform float uFog;
uniform float uAmbient;
uniform float uMaxH;
uniform float uSea;
out vec4 o;
$SKY_FN
vec2 ripple(vec2 uv) { return texture(uNoise, uv).gb * 2.0 - 1.0; }
void main() {
    vec3 toEye = uEye - vWorld;
    float dist = length(toEye);
    vec3 V = toEye / dist;
    vec2 uv = vWorld.xz;
    // fine ripples from the tiling normal map, three scales drifting in different directions
    float near = 1.0 - smoothstep(15.0, 400.0, dist);
    vec2 r = ripple(uv * 0.045 + uTime * vec2(0.012, 0.007)) * 0.55
           + ripple(uv * 0.13 - uTime * vec2(0.009, 0.016)) * 0.35 * near
           + ripple(uv * 0.6 + uTime * vec2(0.035, -0.028)) * (0.22 + uRain * 0.6) * near;
    // raindrops: quick small rings
    r += ripple(uv * 2.1 + vec2(uTime * 0.9, -uTime * 0.7)) * uRain * 0.35 * (1.0 - smoothstep(4.0, 40.0, dist));
    vec3 N = normalize(normalize(vNormal) + vec3(r.x, 0.0, r.y) * 0.26);
    // far away the surface averages out: calmer normals instead of glittering noise
    N = normalize(mix(N, vec3(0.0, 1.0, 0.0), smoothstep(300.0, 3000.0, dist) * 0.7));
    float ndv = max(dot(N, V), 0.0);
    float F = 0.02 + 0.98 * pow(1.0 - ndv, 5.0);
    vec3 R = reflect(-V, N);
    R.y = abs(R.y);
    vec3 refl = sky(R, false);
    // light under the surface: deep colour, and the sun shining through thin crests
    float height = clamp((vWorld.y - uSea) / uMaxH * 0.5 + 0.5, 0.0, 1.0);
    vec3 H = normalize(uSunDir + N * 0.45);
    float through = pow(clamp(dot(V, -H), 0.0, 1.0), 4.0) * height;
    float sunUp = clamp(uSunDir.y * 3.0, 0.0, 1.0);
    vec3 body = uDeep * uAmbient + uScatter * (through * 1.4 * sunUp + height * 0.12) * (uSunColor / 3.0 + uAmbient * 0.2);
    vec3 c = mix(body, refl, F);
    // the sun's glitter: sparkles on the facets that mirror the sun, weighted by Fresnel like the
    // rest of the reflection (a bright path towards a low sun, little when looking straight down)
    float rs = max(dot(R, uSunDir), 0.0);
    c += uSunColor * (pow(rs, 1400.0) * 70.0 + pow(rs, 160.0) * 2.2) * F * (1.0 - uCloud * 0.85) * clamp(uSunDir.y * 6.0 + 0.4, 0.0, 1.0);
    // foam where crests break, broken up by noise
    float fn = texture(uNoise, uv * 0.21 + uTime * 0.01).a;
    float foam = smoothstep(0.62, 0.12, vJ) * smoothstep(0.25, 0.75, fn);
    foam += smoothstep(0.86, 0.98, fn) * smoothstep(0.75, 1.0, height) * (uMaxH > 2.0 ? 0.5 : 0.0);
    c = mix(c, vec3(0.9, 0.95, 0.97) * (uAmbient * 0.8 + 0.2) + uFlash * 0.3, clamp(foam, 0.0, 0.9));
    c += uFlash * 0.12 * F;
    // haze toward the horizon
    vec3 hz = sky(normalize(vec3(-V.x, 0.015, -V.z)), false);
    c = mix(c, hz, 1.0 - exp(-dist * uFog));
    o = vec4(tonemap(c), 1.0);
}"""

        const val ROOM_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec2 aUv;
uniform mat4 uVP;
uniform float uDrop;
out vec2 vUv;
out vec3 vWorld;
void main() {
    vec3 p = aPos - vec3(0.0, uDrop, 0.0);
    vUv = aUv;
    vWorld = p;
    gl_Position = uVP * vec4(p, 1.0);
}"""

        const val ROOM_FS = """#version 300 es
precision highp float;
in vec2 vUv;
in vec3 vWorld;
uniform sampler2D uTex;
uniform float uLight;
uniform float uTime;
uniform vec3 uEye;
$WAVE_FN
out vec4 o;
void main() {
    float w = waveHeight(vWorld.xz, uTime, length(vWorld.xz - uEye.xz));
    float above = vWorld.y - w;
    if (above < 0.0) discard;
    vec3 c = texture(uTex, vUv).rgb * uLight;
    // wet and foamy right at the waterline
    c = mix(c, vec3(0.92, 0.96, 1.0), smoothstep(0.07, 0.0, above) * 0.8);
    c *= mix(0.75, 1.0, smoothstep(0.0, 0.35, above));
    o = vec4(c, 1.0);
}"""

        const val RAIN_VS = """#version 300 es
uniform mat4 uVP;
uniform vec3 uCam;
uniform float uTime;
uniform vec2 uWind;
uniform float uSea;
out float vA;
float hash(float n) { return fract(sin(n) * 43758.5453123); }
void main() {
    const float R = 12.0;
    const float H = 14.0;
    int id = gl_VertexID / 2;
    int end = gl_VertexID - id * 2;
    float f = float(id);
    vec3 h = vec3(hash(f * 1.17 + 0.3), hash(f * 2.31 + 1.7), hash(f * 3.73 + 4.1));
    float speed = 8.5 + h.y * 2.5;
    float y = uCam.y + H * 0.55 - mod(h.y * H + uTime * speed, H);
    vec2 base = (h.xz * 2.0 - 1.0) * R;
    // drops stay where they are in the world as the viewer moves (wrapped around the viewer)
    vec2 rel = (fract((base - uCam.xz) / (2.0 * R) + 0.5) - 0.5) * 2.0 * R;
    vec3 vel = vec3(uWind.x, -speed, uWind.y);
    vec3 p = vec3(uCam.x + rel.x, y, uCam.z + rel.y);
    if (end == 1) p -= normalize(vel) * (0.35 + h.x * 0.25);
    float d = length(p - uCam);
    vA = (end == 1 ? 0.15 : 1.0) * smoothstep(R, R * 0.45, d) * smoothstep(0.25, 1.0, d) * step(uSea, p.y);
    gl_Position = uVP * vec4(p, 1.0);
}"""

        const val RAIN_FS = """#version 300 es
precision mediump float;
in float vA;
uniform float uLight;
out vec4 o;
void main() { o = vec4(vec3(0.78, 0.83, 0.92) * uLight, vA * 0.32); }"""

        const val BOLT_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec2 aEdge;
uniform mat4 uVP;
out vec2 vEdge;
void main() { vEdge = aEdge; gl_Position = uVP * vec4(aPos, 1.0); }"""

        const val BOLT_FS = """#version 300 es
precision mediump float;
in vec2 vEdge;
uniform float uIntensity;
out vec4 o;
void main() {
    float a = (1.0 - vEdge.x * vEdge.x) * vEdge.y * uIntensity;
    o = vec4(vec3(0.82, 0.88, 1.0), clamp(a, 0.0, 1.0));
}"""

        const val POINT_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uVP;
uniform vec3 uEye;
out vec3 vColor;
// near = warm, far = cool (like a depth camera's false colour)
vec3 ramp(float t) {
    return clamp(vec3(1.6 - abs(t * 3.2 - 0.4), 1.6 - abs(t * 3.2 - 1.6), 1.6 - abs(t * 3.2 - 2.8)) - 0.6, 0.0, 1.0) * 1.3;
}
void main() {
    float d = length(aPos - uEye);
    vColor = ramp(clamp(d / 4.0, 0.0, 1.0));
    gl_Position = uVP * vec4(aPos, 1.0);
    gl_PointSize = clamp(14.0 / max(d, 0.3), 2.0, 9.0);
}"""

        const val POINT_FS = """#version 300 es
precision mediump float;
in vec3 vColor;
out vec4 o;
void main() {
    vec2 c = gl_PointCoord * 2.0 - 1.0;
    if (dot(c, c) > 1.0) discard;
    o = vec4(vColor, 1.0);
}"""
    }
}

/** The tiling textures the sea and sky sample (made once on the CPU). */
object OceanTextures {
    /**
     * RGBA bytes. R: cloud noise (fbm of wrapped value noise); G, B: ripple normal x, z (a sum of
     * waves with whole-number frequencies, so it tiles); A: foam noise.
     */
    fun noise(size: Int): ByteBuffer {
        val rnd = java.util.Random(21)
        val clouds = fbm(size, rnd, intArrayOf(4, 8, 16, 32, 64), floatArrayOf(0.5f, 0.25f, 0.13f, 0.07f, 0.05f))
        val foam = fbm(size, rnd, intArrayOf(8, 16, 32, 64), floatArrayOf(0.45f, 0.3f, 0.15f, 0.1f))
        // ripples: 40 waves, frequencies 1..12 cycles per tile, random directions
        val n = 40
        val kx = IntArray(n); val ky = IntArray(n); val amp = FloatArray(n); val ph = FloatArray(n)
        for (i in 0 until n) {
            var a: Int; var b: Int
            do { a = rnd.nextInt(25) - 12; b = rnd.nextInt(25) - 12 } while (a == 0 && b == 0)
            kx[i] = a; ky[i] = b
            val f = sqrt((a * a + b * b).toFloat())
            amp[i] = 1f / (f + 2f)
            ph[i] = rnd.nextFloat() * 6.2831855f
        }
        val gx = FloatArray(size * size); val gy = FloatArray(size * size)
        var maxG = 1e-6f
        for (y in 0 until size) for (x in 0 until size) {
            var dx = 0f; var dy = 0f
            for (i in 0 until n) {
                val th = 6.2831855f * (kx[i] * x + ky[i] * y) / size + ph[i]
                val c = cos(th) * amp[i]
                dx += c * kx[i]; dy += c * ky[i]
            }
            gx[y * size + x] = dx; gy[y * size + x] = dy
            maxG = maxOf(maxG, kotlin.math.abs(dx), kotlin.math.abs(dy))
        }
        val out = ByteBuffer.allocateDirect(size * size * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until size * size) {
            out.put((clouds[i] * 255).toInt().coerceIn(0, 255).toByte())
            out.put(((-gx[i] / maxG * 0.5f + 0.5f) * 255).toInt().coerceIn(0, 255).toByte())
            out.put(((-gy[i] / maxG * 0.5f + 0.5f) * 255).toInt().coerceIn(0, 255).toByte())
            out.put((foam[i] * 255).toInt().coerceIn(0, 255).toByte())
        }
        out.position(0)
        return out
    }

    /** Fractal noise from value-noise lattices that wrap around, normalised to 0..1. */
    private fun fbm(size: Int, rnd: java.util.Random, cells: IntArray, weights: FloatArray): FloatArray {
        val out = FloatArray(size * size)
        for ((o, g) in cells.withIndex()) {
            val lat = FloatArray(g * g) { rnd.nextFloat() }
            for (y in 0 until size) for (x in 0 until size) {
                val fx = x.toFloat() * g / size; val fy = y.toFloat() * g / size
                val x0 = fx.toInt() % g; val y0 = fy.toInt() % g
                val x1 = (x0 + 1) % g; val y1 = (y0 + 1) % g
                var tx = fx - floor(fx); var ty = fy - floor(fy)
                tx = tx * tx * (3 - 2 * tx); ty = ty * ty * (3 - 2 * ty)
                val a = lat[y0 * g + x0] + (lat[y0 * g + x1] - lat[y0 * g + x0]) * tx
                val b = lat[y1 * g + x0] + (lat[y1 * g + x1] - lat[y1 * g + x0]) * tx
                out[y * size + x] += (a + (b - a) * ty) * weights[o]
            }
        }
        var lo = Float.MAX_VALUE; var hi = -Float.MAX_VALUE
        for (v in out) { lo = minOf(lo, v); hi = maxOf(hi, v) }
        for (i in out.indices) out[i] = (out[i] - lo) / (hi - lo)
        return out
    }
}
