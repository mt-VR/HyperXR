package com.hyperxr.rendering

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.Matrix
import com.hyperxr.math.Mat4
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3

/** One mesh/model instance to draw this frame. Instances are pooled by [FrameScene]. */
class DrawItem {
    var mesh: GpuMesh? = null
    var model: GpuModel? = null
    var texture = 0
    var cameraTexture = false
    val color = floatArrayOf(1f, 1f, 1f, 1f)
    var unlit = false
    var transparent = false
    var doubleSided = false
    val matrix = FloatArray(16)
}

/** A flat textured rectangle in world space (UI panel, icon, label, web view). */
class PanelItem {
    var texture = 0
    var oes = false
    val pose = Pose()
    var width = 1f
    var height = 1f
    var alpha = 1f
    var radius = 0.06f
    var glow = 0f
    val tint = floatArrayOf(1f, 1f, 1f, 1f)
    var overlay = false
}

enum class Background { COLOR, ENVIRONMENT, PASSTHROUGH }

class FrameScene {
    private val drawPool = ArrayList<DrawItem>()
    private val panelPool = ArrayList<PanelItem>()
    var drawCount = 0; private set
    var panelCount = 0; private set
    val lightDir = floatArrayOf(-0.4f, -1f, -0.3f)
    val lightColor = floatArrayOf(1f, 0.97f, 0.92f)
    val ambient = floatArrayOf(0.32f, 0.33f, 0.4f)
    val clear = floatArrayOf(0.05f, 0.05f, 0.08f, 1f)
    var background = Background.ENVIRONMENT

    fun reset() { drawCount = 0; panelCount = 0 }
    fun addDraw(): DrawItem {
        if (drawCount == drawPool.size) drawPool += DrawItem()
        return drawPool[drawCount++].also { it.mesh = null; it.model = null; it.texture = 0; it.cameraTexture = false; it.unlit = false; it.transparent = false; it.doubleSided = false }
    }
    fun addPanel(): PanelItem {
        if (panelCount == panelPool.size) panelPool += PanelItem()
        return panelPool[panelCount++].also { it.oes = false; it.alpha = 1f; it.glow = 0f; it.radius = 0.06f; it.overlay = false; it.tint.fill(1f) }
    }
    fun draw(i: Int) = drawPool[i]
    fun panel(i: Int) = panelPool[i]
}

/** Per-eye camera parameters. */
class EyeView {
    val view = FloatArray(16)
    val proj = FloatArray(16)
    val viewProj = FloatArray(16)
    val position = Vec3()
    /** Frustum planes (a,b,c,d) x6 extracted from viewProj. */
    val planes = FloatArray(24)

    fun update() {
        Matrix.multiplyMM(viewProj, 0, proj, 0, view, 0)
        val m = viewProj
        fun plane(i: Int, a: Float, b: Float, c: Float, d: Float) {
            val l = kotlin.math.sqrt(a * a + b * b + c * c)
            planes[i * 4] = a / l; planes[i * 4 + 1] = b / l; planes[i * 4 + 2] = c / l; planes[i * 4 + 3] = d / l
        }
        plane(0, m[3] + m[0], m[7] + m[4], m[11] + m[8], m[15] + m[12])
        plane(1, m[3] - m[0], m[7] - m[4], m[11] - m[8], m[15] - m[12])
        plane(2, m[3] + m[1], m[7] + m[5], m[11] + m[9], m[15] + m[13])
        plane(3, m[3] - m[1], m[7] - m[5], m[11] - m[9], m[15] - m[13])
        plane(4, m[3] + m[2], m[7] + m[6], m[11] + m[10], m[15] + m[14])
        plane(5, m[3] - m[2], m[7] - m[6], m[11] - m[10], m[15] - m[14])
    }

    fun sphereVisible(x: Float, y: Float, z: Float, r: Float): Boolean {
        for (i in 0 until 6) {
            if (planes[i * 4] * x + planes[i * 4 + 1] * y + planes[i * 4 + 2] * z + planes[i * 4 + 3] < -r) return false
        }
        return true
    }
}

/** Camera image (OES) source shared by MR backends (Camera2 or ARCore). */
interface CameraImageSource {
    val textureId: Int
    /** Transform from quad UV to camera texture UV (rotation / crop). */
    val texMatrix: FloatArray
    /** Horizontal and vertical field of view of the image, radians. */
    val fovX: Float
    val fovY: Float
    /** Width/height of the area that quad UVs 0..1 cover through [texMatrix]. */
    val imageAspect: Float
    val hasFrame: Boolean
}

class SceneRenderer {
    private lateinit var lit: Program
    private lateinit var quadProg: Program
    private lateinit var oesProg: Program
    private lateinit var skyProg: Program
    private lateinit var panoProg: Program
    private lateinit var oesFullProg: Program
    private var fullVao = 0
    /** Fill each eye with the camera image (true) or keep its real angular size (false). */
    var passthroughFill = true
    lateinit var cube: GpuMesh; private set
    lateinit var sphere: GpuMesh; private set
    lateinit var plane: GpuMesh; private set
    lateinit var cylinder: GpuMesh; private set
    lateinit var quad: GpuMesh; private set
    private lateinit var skySphere: GpuMesh
    private lateinit var floorMesh: GpuMesh
    private var gridTexture = 0
    private var whiteTexture = 0

    var environment: EnvironmentVisual = EnvironmentVisual.Studio
    var visibleDraws = 0; private set

    private val mvp = FloatArray(16)
    private val tmp = FloatArray(16)
    private val identity = Mat4.identity(FloatArray(16))

    class Program(val id: Int) {
        private val cache = HashMap<String, Int>()
        fun u(name: String): Int = cache.getOrPut(name) { GLES30.glGetUniformLocation(id, name) }
    }

    sealed class EnvironmentVisual {
        object Studio : EnvironmentVisual()
        class Panorama(val texture: Int) : EnvironmentVisual()
        class Model(val model: GpuModel, val transform: FloatArray) : EnvironmentVisual()
        object None : EnvironmentVisual()
    }

    fun init() {
        lit = Program(Gl.compile(Shaders.LIT_VS, Shaders.LIT_FS, "lit"))
        quadProg = Program(Gl.compile(Shaders.QUAD_VS, Shaders.QUAD_FS, "quad"))
        oesProg = Program(Gl.compile(Shaders.OES_VS, Shaders.OES_FS, "oes"))
        skyProg = Program(Gl.compile(Shaders.SKY_VS, Shaders.SKY_FS, "sky"))
        panoProg = Program(Gl.compile(Shaders.SKY_VS, Shaders.PANO_FS, "pano"))
        oesFullProg = Program(Gl.compile(Shaders.OES_FULL_VS, Shaders.OES_FS, "oes-full"))
        fullVao = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]
        GLES30.glBindVertexArray(fullVao)
        FullscreenTriangle.bind()
        GLES30.glBindVertexArray(0)
        cube = GpuMesh.cube(); sphere = GpuMesh.sphere(); plane = GpuMesh.plane(); cylinder = GpuMesh.cylinder(); quad = GpuMesh.quad()
        skySphere = GpuMesh.sphere(16, 24, inward = true, radius = 50f)
        floorMesh = GpuMesh.plane(40f)
        gridTexture = Gl.uploadBitmap(gridBitmap(), mipmap = true).also { Gl.repeatTexture(it) }
        whiteTexture = Gl.uploadBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
    }

    /** Procedural floor grid texture (own work). */
    private fun gridBitmap(): Bitmap {
        val n = 256
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(28, 30, 40))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 120, 110, 255); strokeWidth = 3f }
        c.drawLine(0f, 0f, n.toFloat(), 0f, p)
        c.drawLine(0f, 0f, 0f, n.toFloat(), p)
        p.color = Color.argb(60, 200, 120, 255); p.strokeWidth = 1.5f
        c.drawLine(0f, n / 2f, n.toFloat(), n / 2f, p)
        c.drawLine(n / 2f, 0f, n / 2f, n.toFloat(), p)
        return b
    }

    fun meshFor(id: Int): GpuMesh = when (id) {
        2 -> sphere; 3 -> plane; 4 -> cylinder; 5 -> quad; else -> cube
    }

    fun renderBackground(scene: FrameScene, eye: EyeView, camera: CameraImageSource?, headPose: Pose) {
        val c = scene.clear
        GLES30.glClearColor(c[0], c[1], c[2], c[3])
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        when (scene.background) {
            Background.PASSTHROUGH -> when {
                camera == null || !camera.hasFrame -> drawEnvironment(eye, scene)
                passthroughFill -> drawCameraFull(eye, camera)
                else -> drawCamera(eye, camera, headPose)
            }
            Background.ENVIRONMENT -> drawEnvironment(eye, scene)
            Background.COLOR -> Unit
        }
    }

    private fun drawEnvironment(eye: EyeView, scene: FrameScene) {
        when (val env = environment) {
            is EnvironmentVisual.Studio -> {
                drawSky(eye, floatArrayOf(0.10f, 0.08f, 0.24f), floatArrayOf(0.42f, 0.30f, 0.62f), floatArrayOf(0.05f, 0.05f, 0.08f))
                drawMesh(floorMesh, floorMatrix, eye, WHITE, gridTexture, scene, unlit = false)
            }
            is EnvironmentVisual.Panorama -> {
                GLES30.glUseProgram(panoProg.id)
                skyMatrix(eye)
                GLES30.glUniformMatrix4fv(panoProg.u("uMvp"), 1, false, mvp, 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, env.texture)
                GLES30.glUniform1i(panoProg.u("uTex"), 0)
                GLES30.glDepthMask(false)
                skySphere.draw()
                GLES30.glDepthMask(true)
            }
            is EnvironmentVisual.Model -> {
                drawSky(eye, floatArrayOf(0.45f, 0.6f, 0.85f), floatArrayOf(0.8f, 0.85f, 0.9f), floatArrayOf(0.3f, 0.3f, 0.3f))
                drawModel(env.model, env.transform, eye, floatArrayOf(1f, 1f, 1f, 1f), scene)
            }
            EnvironmentVisual.None -> Unit
        }
    }

    private fun skyMatrix(eye: EyeView) {
        // rotation-only view so the sky stays at infinity
        System.arraycopy(eye.view, 0, tmp, 0, 16)
        tmp[12] = 0f; tmp[13] = 0f; tmp[14] = 0f
        Matrix.multiplyMM(mvp, 0, eye.proj, 0, tmp, 0)
    }

    private fun drawSky(eye: EyeView, top: FloatArray, horizon: FloatArray, bottom: FloatArray) {
        GLES30.glUseProgram(skyProg.id)
        skyMatrix(eye)
        GLES30.glUniformMatrix4fv(skyProg.u("uMvp"), 1, false, mvp, 0)
        GLES30.glUniform3fv(skyProg.u("uTop"), 1, top, 0)
        GLES30.glUniform3fv(skyProg.u("uHorizon"), 1, horizon, 0)
        GLES30.glUniform3fv(skyProg.u("uBottom"), 1, bottom, 0)
        GLES30.glUniform3f(skyProg.u("uSunDir"), -0.3f, -0.4f, 0.8f)
        GLES30.glDepthMask(false)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        skySphere.draw()
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glDepthMask(true)
    }

    /**
     * Camera passthrough: the image is placed on a head-locked quad whose angular size equals the
     * camera field of view, so real objects keep (approximately) their real angular size. It is a
     * mono image for both eyes: no stereo depth of the real world.
     */
    private fun drawCamera(eye: EyeView, camera: CameraImageSource, headPose: Pose) {
        GLES30.glUseProgram(oesProg.id)
        val d = 20f
        val w = 2f * d * kotlin.math.tan(camera.fovX / 2)
        val h = 2f * d * kotlin.math.tan(camera.fovY / 2)
        headPose.transformPoint(camForward.set(0f, 0f, -d), camPos)
        Mat4.compose(camPos, headPose.orientation, w, h, 1f, camModel)
        Matrix.multiplyMM(mvp, 0, eye.viewProj, 0, camModel, 0)
        GLES30.glUniformMatrix4fv(oesProg.u("uMvp"), 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(oesProg.u("uTexMatrix"), 1, false, camera.texMatrix, 0)
        GLES30.glUniform1f(oesProg.u("uAlpha"), 1f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camera.textureId)
        GLES30.glUniform1i(oesProg.u("uTex"), 0)
        GLES30.glDepthMask(false)
        quad.draw()
        GLES30.glDepthMask(true)
    }

    private val camForward = Vec3()
    private val camPos = Vec3()
    private val camModel = FloatArray(16)
    private val floorMatrix = Mat4.compose(Vec3(0f, 0f, 0f), Quat(), 60f, 1f, 60f, FloatArray(16))

    /** Camera image stretched over the whole eye with a centre crop that preserves its aspect. */
    private fun drawCameraFull(eye: EyeView, camera: CameraImageSource) {
        val eyeAspect = eye.proj[5] / eye.proj[0]
        val ratio = eyeAspect / camera.imageAspect
        // crop the dimension that overflows so the image covers the viewport without distortion
        val cropU = if (ratio < 1f) ratio else 1f
        val cropV = if (ratio < 1f) 1f else 1f / ratio
        GLES30.glUseProgram(oesFullProg.id)
        GLES30.glUniformMatrix4fv(oesFullProg.u("uTexMatrix"), 1, false, camera.texMatrix, 0)
        GLES30.glUniform2f(oesFullProg.u("uCrop"), cropU, cropV)
        GLES30.glUniform1f(oesFullProg.u("uAlpha"), 1f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camera.textureId)
        GLES30.glUniform1i(oesFullProg.u("uTex"), 0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glBindVertexArray(fullVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    fun renderScene(scene: FrameScene, eye: EyeView) {
        visibleDraws = 0
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        // opaque first, then transparent
        for (pass in 0..1) {
            if (pass == 1) {
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glDepthMask(false)
            }
            for (i in 0 until scene.drawCount) {
                val d = scene.draw(i)
                val isTransparent = d.transparent || d.color[3] < 0.999f
                if ((pass == 1) != isTransparent) continue
                val model = d.model
                if (model != null) {
                    drawModel(model, d.matrix, eye, d.color, scene)
                } else {
                    val mesh = d.mesh ?: continue
                    if (!visible(mesh, d.matrix, eye)) continue
                    if (d.doubleSided) GLES30.glDisable(GLES30.GL_CULL_FACE)
                    drawMesh(mesh, d.matrix, eye, d.color, d.texture, scene, d.unlit)
                    if (d.doubleSided) GLES30.glEnable(GLES30.GL_CULL_FACE)
                }
                visibleDraws++
            }
        }
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private val center = Vec3()
    private val centerOut = Vec3()
    private fun visible(mesh: GpuMesh, m: FloatArray, eye: EyeView): Boolean {
        center.set(mesh.boundCenter[0], mesh.boundCenter[1], mesh.boundCenter[2])
        Mat4.transformPoint(m, center, centerOut)
        val sx = kotlin.math.sqrt(m[0] * m[0] + m[1] * m[1] + m[2] * m[2])
        val sy = kotlin.math.sqrt(m[4] * m[4] + m[5] * m[5] + m[6] * m[6])
        val sz = kotlin.math.sqrt(m[8] * m[8] + m[9] * m[9] + m[10] * m[10])
        return eye.sphereVisible(centerOut.x, centerOut.y, centerOut.z, mesh.boundRadius * maxOf(sx, sy, sz))
    }

    fun drawMesh(mesh: GpuMesh, model: FloatArray, eye: EyeView, color: FloatArray, texture: Int, scene: FrameScene, unlit: Boolean, alphaCutoff: Float = 0f) {
        GLES30.glUseProgram(lit.id)
        Matrix.multiplyMM(mvp, 0, eye.viewProj, 0, model, 0)
        GLES30.glUniformMatrix4fv(lit.u("uMvp"), 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(lit.u("uModel"), 1, false, model, 0)
        GLES30.glUniform4fv(lit.u("uColor"), 1, color, 0)
        GLES30.glUniform1i(lit.u("uHasTex"), if (texture != 0) 1 else 0)
        GLES30.glUniform1i(lit.u("uUnlit"), if (unlit) 1 else 0)
        GLES30.glUniform3fv(lit.u("uLightDir"), 1, scene.lightDir, 0)
        GLES30.glUniform3fv(lit.u("uLightColor"), 1, scene.lightColor, 0)
        GLES30.glUniform3fv(lit.u("uAmbient"), 1, scene.ambient, 0)
        GLES30.glUniform3f(lit.u("uEye"), eye.position.x, eye.position.y, eye.position.z)
        GLES30.glUniform1f(lit.u("uAlphaCutoff"), alphaCutoff)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (texture != 0) texture else whiteTexture)
        GLES30.glUniform1i(lit.u("uTex"), 0)
        mesh.draw()
    }

    private val partMatrix = FloatArray(16)
    private val partColor = FloatArray(4)
    fun drawModel(model: GpuModel, transform: FloatArray, eye: EyeView, tint: FloatArray, scene: FrameScene) {
        for (p in model.parts) {
            Matrix.multiplyMM(partMatrix, 0, transform, 0, p.world, 0)
            if (!visible(p.mesh, partMatrix, eye)) continue
            for (k in 0..3) partColor[k] = p.color[k] * tint[k]
            if (p.doubleSided) GLES30.glDisable(GLES30.GL_CULL_FACE)
            if (p.blend) { GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA) }
            drawMesh(p.mesh, partMatrix, eye, partColor, p.texture, scene, false, p.alphaCutoff)
            if (p.blend) GLES30.glDisable(GLES30.GL_BLEND)
            if (p.doubleSided) GLES30.glEnable(GLES30.GL_CULL_FACE)
        }
    }

    private val panelModel = FloatArray(16)
    fun renderPanels(scene: FrameScene, eye: EyeView, overlayPass: Boolean) {
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        if (overlayPass) GLES30.glDisable(GLES30.GL_DEPTH_TEST) else GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        for (i in 0 until scene.panelCount) {
            val p = scene.panel(i)
            if (p.overlay != overlayPass || p.texture == 0) continue
            Mat4.compose(p.pose.position, p.pose.orientation, p.width, p.height, 1f, panelModel)
            Matrix.multiplyMM(mvp, 0, eye.viewProj, 0, panelModel, 0)
            if (p.oes) {
                GLES30.glUseProgram(oesProg.id)
                GLES30.glUniformMatrix4fv(oesProg.u("uMvp"), 1, false, mvp, 0)
                GLES30.glUniformMatrix4fv(oesProg.u("uTexMatrix"), 1, false, flipY, 0)
                GLES30.glUniform1f(oesProg.u("uAlpha"), p.alpha)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, p.texture)
                GLES30.glUniform1i(oesProg.u("uTex"), 0)
            } else {
                GLES30.glUseProgram(quadProg.id)
                GLES30.glUniformMatrix4fv(quadProg.u("uMvp"), 1, false, mvp, 0)
                GLES30.glUniform1f(quadProg.u("uAlpha"), p.alpha)
                GLES30.glUniform1f(quadProg.u("uRadius"), p.radius / p.height)
                GLES30.glUniform1f(quadProg.u("uAspect"), p.width / p.height)
                GLES30.glUniform1f(quadProg.u("uGlow"), p.glow)
                GLES30.glUniform4fv(quadProg.u("uTint"), 1, p.tint, 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, p.texture)
                GLES30.glUniform1i(quadProg.u("uTex"), 0)
            }
            quad.draw()
        }
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Vertical flip for SurfaceTexture-backed panels (web views): quad UV is top-left origin. */
    private val flipY = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)

    companion object {
        private val WHITE = floatArrayOf(1f, 1f, 1f, 1f)

        /** Gradient used for placeholder icon tiles (no glyph, see docs/ICONS.md). */
        fun placeholderTile(size: Int): Bitmap {
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val p = Paint(Paint.ANTI_ALIAS_FLAG)
            p.shader = LinearGradient(0f, 0f, size.toFloat(), size.toFloat(), Color.rgb(70, 72, 96), Color.rgb(40, 42, 58), Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, size.toFloat(), size.toFloat(), p)
            return b
        }
    }
}
