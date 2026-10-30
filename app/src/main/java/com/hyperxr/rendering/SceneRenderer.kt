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
import com.hyperxr.media.StereoLayout

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
    /** 0 = matte, 1 = polished (specular highlight size/strength). */
    var gloss = 0f
    /** 0 = dielectric, 1 = metal (highlight takes the base colour). */
    var metal = 0f
    /** Drawn into the shadow map when the scene has a shadow region. */
    var castShadow = false
    /**
     * Drawn after the UI panels (hands, the pinch ball): the panels are blended quads drawn after
     * the 3D scene, so anything in the normal pass that is transparent (or simply drawn before
     * them) would end up covered by icons and windows even when it is closer to the eyes.
     */
    var late = false
    /** Glow: drawn last, added to what is behind, edges fading (lasers, sabres, sparks). */
    var additive = false
    /** Texture repeats across the mesh (tiling floors and walls). */
    val uvScale = floatArrayOf(1f, 1f)
    val matrix = FloatArray(16)
}

/** A flat textured rectangle in world space (UI panel, icon, label, web view). */
class PanelItem {
    var texture = 0
    var oes = false
    /** OES only: the producer's texture transform (SurfaceTexture), already flipped for top-left UVs. */
    var texMatrix: FloatArray? = null
    val pose = Pose()
    var width = 1f
    var height = 1f
    var alpha = 1f
    var radius = 0.06f
    var glow = 0f
    val tint = floatArrayOf(1f, 1f, 1f, 1f)
    var overlay = false
    /** 0 = flat quad; otherwise a cylinder section of this radius curved towards the viewer. */
    var curvature = 0f
}

enum class Background { COLOR, ENVIRONMENT, PASSTHROUGH }

/**
 * Content that draws with its own shaders (the Ocean app): a whole background in place of the
 * environment or the camera, and effects after the 3D scene (rain), before the panels.
 */
interface ScenePainter {
    val ownsBackground: Boolean
    fun background(eye: EyeView)
    fun afterScene(eye: EyeView)
}

class FrameScene {
    private val drawPool = ArrayList<DrawItem>()
    private val panelPool = ArrayList<PanelItem>()
    var drawCount = 0; private set
    var panelCount = 0; private set
    val lightDir = floatArrayOf(-0.4f, -1f, -0.3f)
    val lightColor = floatArrayOf(1f, 0.97f, 0.92f)
    val ambient = floatArrayOf(0.32f, 0.33f, 0.4f)
    val clear = floatArrayOf(0.05f, 0.05f, 0.08f, 1f)
    /** Distance fog: rgb and density per metre (0 = none; cleared by [reset]). */
    val fog = floatArrayOf(0f, 0f, 0f, 0f)
    var background = Background.ENVIRONMENT
    /** Region covered by the shadow map (content sets it every frame; radius 0 = no shadows). */
    val shadowCenter = Vec3()
    var shadowRadius = 0f
    /** Set by content every frame that wants to paint (cleared by [reset]). */
    var painter: ScenePainter? = null

    fun reset() { drawCount = 0; panelCount = 0; shadowRadius = 0f; painter = null; fog[3] = 0f }
    fun addDraw(): DrawItem {
        if (drawCount == drawPool.size) drawPool += DrawItem()
        return drawPool[drawCount++].also { it.mesh = null; it.model = null; it.texture = 0; it.cameraTexture = false; it.unlit = false; it.transparent = false; it.doubleSided = false; it.gloss = 0f; it.metal = 0f; it.castShadow = false; it.late = false; it.additive = false; it.uvScale[0] = 1f; it.uvScale[1] = 1f }
    }
    fun addPanel(): PanelItem {
        if (panelCount == panelPool.size) panelPool += PanelItem()
        return panelPool[panelCount++].also { it.oes = false; it.texMatrix = null; it.alpha = 1f; it.glow = 0f; it.radius = 0.06f; it.overlay = false; it.tint.fill(1f); it.curvature = 0f }
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
    /** 0 = left, 1 = right (picks the half of a stereo 360 frame). */
    var index = 0

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
    /** Tangent of the half field of view (horizontal) of that area, from the real camera intrinsics. */
    val regionTanX: Float
    /** Tangent of the half field of view (vertical) of that area. */
    val regionTanY: Float
    val hasFrame: Boolean
    /** View UV (top-left origin) -> UV in the CPU image the hand pipeline sees (raw orientation). */
    val cpuMatrix: FloatArray
}

class SceneRenderer {
    /** NPU passthrough enhancement, when on (XrEngine sets it up). */
    var enhancer: PassthroughEnhancer? = null
    private lateinit var lit: Program
    private lateinit var quadProg: Program
    private lateinit var oesProg: Program
    private lateinit var skyProg: Program
    private lateinit var panoProg: Program
    private lateinit var panoOesProg: Program
    private lateinit var oesFullProg: Program
    private lateinit var guardProg: Program
    private lateinit var dimProg: Program
    /** 0..1: how much the camera image is darkened behind the UI ("dimmed MR"). */
    @Volatile var passthroughDim = 0f
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

    /**
     * A 360 photo or video wrapped around the user (gallery / files). While set it replaces the
     * environment and the camera image. [yaw] turns the frame centre to where the user looked.
     */
    class Immersive(val texture: Int, val oes: Boolean, val texMatrix: FloatArray?, @Volatile var layout: StereoLayout, @Volatile var yaw: Float)
    @Volatile var immersive: Immersive? = null
    private val yawRot = FloatArray(16)
    private val rect = FloatArray(4)
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
        litLoc = LitLocations(lit)
        quadProg = Program(Gl.compile(Shaders.QUAD_VS, Shaders.QUAD_FS, "quad"))
        oesProg = Program(Gl.compile(Shaders.OES_VS, Shaders.OES_FS, "oes"))
        skyProg = Program(Gl.compile(Shaders.SKY_VS, Shaders.SKY_FS, "sky"))
        panoProg = Program(Gl.compile(Shaders.SKY_VS, Shaders.PANO_FS, "pano"))
        panoOesProg = Program(Gl.compile(Shaders.SKY_VS, Shaders.PANO_OES_FS, "pano-oes"))
        oesFullProg = Program(Gl.compile(Shaders.OES_FULL_VS, Shaders.OES_FS, "oes-full"))
        guardProg = Program(Gl.compile(Shaders.GUARD_VS, Shaders.GUARD_FS, "guard"))
        dimProg = Program(Gl.compile(Shaders.DIM_VS, Shaders.DIM_FS, "dim"))
        fullVao = IntArray(1).also { GLES30.glGenVertexArrays(1, it, 0) }[0]
        GLES30.glBindVertexArray(fullVao)
        FullscreenTriangle.bind()
        GLES30.glBindVertexArray(0)
        cube = GpuMesh.cube(); sphere = GpuMesh.sphere(); plane = GpuMesh.plane(); cylinder = GpuMesh.cylinder(); quad = GpuMesh.quad()
        skySphere = GpuMesh.sphere(16, 24, inward = true, radius = 50f)
        floorMesh = GpuMesh.plane(40f)
        gridTexture = Gl.uploadBitmap(gridBitmap(), mipmap = true).also { Gl.repeatTexture(it) }
        ensureShadow()
        whiteTexture = Gl.uploadBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
    }

    /** Procedural floor grid texture (own work). */
    private fun gridBitmap(): Bitmap {
        val n = 256
        val b = Bitmap.createBitmap(n, n, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(30, 31, 34))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 150, 154, 162); strokeWidth = 3f }
        c.drawLine(0f, 0f, n.toFloat(), 0f, p)
        c.drawLine(0f, 0f, 0f, n.toFloat(), p)
        p.color = Color.argb(50, 150, 154, 162); p.strokeWidth = 1.5f
        c.drawLine(0f, n / 2f, n.toFloat(), n / 2f, p)
        c.drawLine(n / 2f, 0f, n / 2f, n.toFloat(), p)
        return b
    }

    fun meshFor(id: Int): GpuMesh = when (id) {
        2 -> sphere; 3 -> plane; 4 -> cylinder; 5 -> quad; else -> cube
    }

    /** Uniform locations of the lit program, looked up once (drawMesh runs hundreds of times a frame). */
    private class LitLocations(p: Program) {
        val gloss = p.u("uGloss"); val metal = p.u("uMetal"); val shadowOn = p.u("uShadowOn"); val lightVP = p.u("uLightVP")
        val shadowMap = p.u("uShadowMap"); val mvp = p.u("uMvp"); val model = p.u("uModel"); val color = p.u("uColor")
        val hasTex = p.u("uHasTex"); val unlit = p.u("uUnlit"); val lightDir = p.u("uLightDir"); val lightColor = p.u("uLightColor")
        val ambient = p.u("uAmbient"); val eye = p.u("uEye"); val alphaCutoff = p.u("uAlphaCutoff"); val tex = p.u("uTex")
        val additive = p.u("uAdditive"); val fog = p.u("uFog"); val uvScale = p.u("uUvScale")
    }
    private lateinit var litLoc: LitLocations
    private class QuadLocations(p: Program) {
        val mvp = p.u("uMvp"); val alpha = p.u("uAlpha"); val radius = p.u("uRadius"); val aspect = p.u("uAspect")
        val glow = p.u("uGlow"); val tint = p.u("uTint"); val tex = p.u("uTex")
    }
    private var quadLoc: QuadLocations? = null
    /** Counts eye renders: the lit program's per-eye uniforms are sent once per pass, not per draw. */
    private var pass = 0
    private var litPass = -1
    private var litEye: EyeView? = null

    fun renderBackground(scene: FrameScene, eye: EyeView, camera: CameraImageSource?, headPose: Pose) {
        pass++
        val c = scene.clear
        GLES30.glClearColor(c[0], c[1], c[2], c[3])
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        immersive?.let { drawImmersive(eye, it); return }
        scene.painter?.let { if (it.ownsBackground) { it.background(eye); restoreState(); return } }
        when (scene.background) {
            Background.PASSTHROUGH -> {
                when {
                    camera == null || !camera.hasFrame -> drawEnvironment(eye, scene)
                    passthroughFill -> drawCameraFull(eye, camera)
                    else -> drawCamera(eye, camera, headPose)
                }
                if (passthroughDim > 0f) drawDim(passthroughDim)
            }
            Background.ENVIRONMENT -> drawEnvironment(eye, scene)
            Background.COLOR -> Unit
        }
    }

    /** The state the scene passes expect, after a painter used its own. */
    fun restoreState() {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(0)
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
                GLES30.glUniform4f(panoProg.u("uRect"), 0f, 0f, 1f, 1f)
                drawSkySphere()
            }
            is EnvironmentVisual.Model -> {
                drawSky(eye, floatArrayOf(0.45f, 0.6f, 0.85f), floatArrayOf(0.8f, 0.85f, 0.9f), floatArrayOf(0.3f, 0.3f, 0.3f))
                drawModel(env.model, env.transform, eye, floatArrayOf(1f, 1f, 1f, 1f), scene)
            }
            EnvironmentVisual.None -> Unit
        }
    }

    private fun drawImmersive(eye: EyeView, m: Immersive) {
        val prog = if (m.oes) panoOesProg else panoProg
        GLES30.glUseProgram(prog.id)
        skyMatrix(eye)
        Matrix.setRotateM(yawRot, 0, Math.toDegrees(m.yaw.toDouble()).toFloat(), 0f, 1f, 0f)
        Matrix.multiplyMM(tmp, 0, mvp, 0, yawRot, 0)
        GLES30.glUniformMatrix4fv(prog.u("uMvp"), 1, false, tmp, 0)
        // which part of the frame this eye sees: (offset u, offset v, scale u, scale v)
        when (m.layout) {
            StereoLayout.MONO -> { rect[0] = 0f; rect[1] = 0f; rect[2] = 1f; rect[3] = 1f }
            StereoLayout.TOP_BOTTOM -> { rect[0] = 0f; rect[1] = 0.5f * eye.index; rect[2] = 1f; rect[3] = 0.5f }
            StereoLayout.LEFT_RIGHT -> { rect[0] = 0.5f * eye.index; rect[1] = 0f; rect[2] = 0.5f; rect[3] = 1f }
        }
        GLES30.glUniform4fv(prog.u("uRect"), 1, rect, 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        if (m.oes) {
            GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, m.texture)
            GLES30.glUniformMatrix4fv(prog.u("uTexMatrix"), 1, false, m.texMatrix ?: identity, 0)
        } else GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, m.texture)
        GLES30.glUniform1i(prog.u("uTex"), 0)
        drawSkySphere()
    }

    /**
     * The sky sphere is projected onto the far plane (depth exactly 1.0, see SKY_VS), so it needs
     * LEQUAL against the cleared depth: with the default LESS every fragment was rejected, which
     * left 360 environments and 360 photos black.
     */
    private fun drawSkySphere() {
        GLES30.glDepthMask(false)
        GLES30.glDepthFunc(GLES30.GL_LEQUAL)
        skySphere.draw()
        GLES30.glDepthFunc(GLES30.GL_LESS)
        GLES30.glDepthMask(true)
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
        drawSkySphere()
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
        enhancer?.bind(oesProg) ?: GLES30.glUniform1f(oesProg.u("uEnhance"), 0f)
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
    private fun drawDim(amount: Float) {
        GLES30.glUseProgram(dimProg.id)
        GLES30.glUniform1f(dimProg.u("uDim"), amount.coerceIn(0f, 1f))
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glBindVertexArray(fullVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    private fun drawCameraFull(eye: EyeView, camera: CameraImageSource) {
        val (cropU, cropV) = fillCrop(eye.proj[5] / eye.proj[0], camera)
        GLES30.glUseProgram(oesFullProg.id)
        GLES30.glUniformMatrix4fv(oesFullProg.u("uTexMatrix"), 1, false, camera.texMatrix, 0)
        GLES30.glUniform2f(oesFullProg.u("uCrop"), cropU, cropV)
        GLES30.glUniform1f(oesFullProg.u("uAlpha"), 1f)
        enhancer?.bind(oesFullProg) ?: GLES30.glUniform1f(oesFullProg.u("uEnhance"), 0f)
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

    /** The camera image over everything drawn so far, at [alpha] (HyperGuardian's safety view). */
    fun drawCameraOverlay(eye: EyeView, camera: CameraImageSource, alpha: Float) {
        val (cropU, cropV) = fillCrop(eye.proj[5] / eye.proj[0], camera)
        GLES30.glUseProgram(oesFullProg.id)
        GLES30.glUniformMatrix4fv(oesFullProg.u("uTexMatrix"), 1, false, camera.texMatrix, 0)
        GLES30.glUniform2f(oesFullProg.u("uCrop"), cropU, cropV)
        GLES30.glUniform1f(oesFullProg.u("uAlpha"), alpha.coerceIn(0f, 1f))
        GLES30.glUniform1f(oesFullProg.u("uEnhance"), 0f)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camera.textureId)
        GLES30.glUniform1i(oesFullProg.u("uTex"), 0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glBindVertexArray(fullVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    /**
     * Boundary warning over the VR scene: blends the camera in where depth says an obstacle is
     * closer than [warnM], and everywhere by [global] (0..1) as the nearest obstacle approaches.
     */
    fun drawBoundary(eye: EyeView, camera: CameraImageSource, depthTex: Int, depthMatrix: FloatArray, warnM: Float, fullM: Float, global: Float) {
        val (cropU, cropV) = fillCrop(eye.proj[5] / eye.proj[0], camera)
        GLES30.glUseProgram(guardProg.id)
        GLES30.glUniformMatrix4fv(guardProg.u("uTexMatrix"), 1, false, camera.texMatrix, 0)
        GLES30.glUniformMatrix4fv(guardProg.u("uDepthMatrix"), 1, false, depthMatrix, 0)
        GLES30.glUniform2f(guardProg.u("uCrop"), cropU, cropV)
        GLES30.glUniform1f(guardProg.u("uWarn"), warnM)
        GLES30.glUniform1f(guardProg.u("uFull"), fullM)
        GLES30.glUniform1f(guardProg.u("uGlobal"), global)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, camera.textureId)
        GLES30.glUniform1i(guardProg.u("uTex"), 0)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTex)
        GLES30.glUniform1i(guardProg.u("uDepth"), 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
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
        // opaque first, then transparent, then glow
        for (pass in 0..2) {
            if (pass == 1) {
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glDepthMask(false)
            } else if (pass == 2) GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE)
            for (i in 0 until scene.drawCount) {
                val d = scene.draw(i)
                if (d.late) continue
                val p = if (d.additive) 2 else if (d.transparent || d.color[3] < 0.999f) 1 else 0
                if (p != pass) continue
                val model = d.model
                if (model != null) {
                    drawModel(model, d.matrix, eye, d.color, scene, d.gloss, d.metal, blended = pass > 0)
                } else {
                    val mesh = d.mesh ?: continue
                    if (!visible(mesh, d.matrix, eye)) continue
                    if (d.doubleSided) GLES30.glDisable(GLES30.GL_CULL_FACE)
                    drawMesh(mesh, d.matrix, eye, d.color, d.texture, scene, d.unlit, gloss = d.gloss, metal = d.metal, additive = d.additive, uvScale = d.uvScale)
                    if (d.doubleSided) GLES30.glEnable(GLES30.GL_CULL_FACE)
                }
                visibleDraws++
            }
        }
        GLES30.glDepthMask(true)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    /** Hands and other [DrawItem.late] items, after the non-overlay panels, depth-tested. */
    fun renderLate(scene: FrameScene, eye: EyeView) {
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        for (pass in 0..1) {
            if (pass == 1) {
                GLES30.glEnable(GLES30.GL_BLEND)
                GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
            }
            for (i in 0 until scene.drawCount) {
                val d = scene.draw(i)
                if (!d.late) continue
                val isTransparent = d.transparent || d.color[3] < 0.999f
                if ((pass == 1) != isTransparent) continue
                val mesh = d.mesh ?: continue
                drawMesh(mesh, d.matrix, eye, d.color, d.texture, scene, d.unlit, gloss = d.gloss, metal = d.metal)
            }
        }
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

    fun drawMesh(mesh: GpuMesh, model: FloatArray, eye: EyeView, color: FloatArray, texture: Int, scene: FrameScene, unlit: Boolean, alphaCutoff: Float = 0f,
                 gloss: Float = 0f, metal: Float = 0f, additive: Boolean = false, uvScale: FloatArray = ONE_UV) {
        val L = litLoc
        GLES30.glUseProgram(lit.id)
        // uniforms keep their values in the program: what is the same for the whole eye is sent once
        if (litPass != pass || litEye !== eye) {
            litPass = pass; litEye = eye
            GLES30.glUniform1i(L.shadowOn, if (shadowActive) 1 else 0)
            GLES30.glUniformMatrix4fv(L.lightVP, 1, false, lightVP, 0)
            GLES30.glUniform1i(L.shadowMap, 1)
            GLES30.glUniform3fv(L.lightDir, 1, scene.lightDir, 0)
            GLES30.glUniform3fv(L.lightColor, 1, scene.lightColor, 0)
            GLES30.glUniform3fv(L.ambient, 1, scene.ambient, 0)
            GLES30.glUniform3f(L.eye, eye.position.x, eye.position.y, eye.position.z)
            GLES30.glUniform1i(L.tex, 0)
            GLES30.glUniform4fv(L.fog, 1, scene.fog, 0)
        }
        GLES30.glUniform1i(L.additive, if (additive) 1 else 0)
        GLES30.glUniform2f(L.uvScale, uvScale[0], uvScale[1])
        GLES30.glUniform1f(L.gloss, gloss)
        GLES30.glUniform1f(L.metal, metal)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex) // always a depth texture: drivers dislike an empty shadow sampler
        Matrix.multiplyMM(mvp, 0, eye.viewProj, 0, model, 0)
        GLES30.glUniformMatrix4fv(L.mvp, 1, false, mvp, 0)
        GLES30.glUniformMatrix4fv(L.model, 1, false, model, 0)
        GLES30.glUniform4fv(L.color, 1, color, 0)
        GLES30.glUniform1i(L.hasTex, if (texture != 0) 1 else 0)
        GLES30.glUniform1i(L.unlit, if (unlit) 1 else 0)
        GLES30.glUniform1f(L.alphaCutoff, alphaCutoff)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, if (texture != 0) texture else whiteTexture)
        mesh.draw()
    }

    // ---------------------------------------------------------------- shadow map
    private var shadowFbo = 0
    private var shadowTex = 0
    private var shadowActive = false
    private val lightVP = FloatArray(16)
    private val lightView = FloatArray(16)
    private val lightProj = FloatArray(16)
    private lateinit var depthProg: Program
    private val SHADOW_SIZE = 2048

    private fun ensureShadow() {
        if (shadowFbo != 0) return
        depthProg = Program(Gl.compile(Shaders.DEPTH_VS, Shaders.DEPTH_FS, "depth"))
        shadowTex = Gl.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, shadowTex)
        GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, 1, GLES30.GL_DEPTH_COMPONENT24, SHADOW_SIZE, SHADOW_SIZE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_MODE, GLES30.GL_COMPARE_REF_TO_TEXTURE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_COMPARE_FUNC, GLES30.GL_LEQUAL)
        shadowFbo = IntArray(1).also { GLES30.glGenFramebuffers(1, it, 0) }[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_DEPTH_ATTACHMENT, GLES30.GL_TEXTURE_2D, shadowTex, 0)
        GLES30.glDrawBuffers(1, intArrayOf(GLES30.GL_NONE), 0)
        GLES30.glReadBuffer(GLES30.GL_NONE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * Renders the depth of shadow casters around [FrameScene.shadowCenter] from the scene light
     * (once per frame, before the eyes). The caller binds its eye target afterwards.
     */
    fun renderShadows(scene: FrameScene) {
        shadowActive = false
        val r = scene.shadowRadius
        if (r <= 0f) return
        ensureShadow()
        val c = scene.shadowCenter
        val l = scene.lightDir
        val len = kotlin.math.sqrt(l[0] * l[0] + l[1] * l[1] + l[2] * l[2])
        val dx = l[0] / len; val dy = l[1] / len; val dz = l[2] / len
        val dist = 3f * r
        val upX = if (kotlin.math.abs(dy) > 0.95f) 1f else 0f
        val upY = if (kotlin.math.abs(dy) > 0.95f) 0f else 1f
        Matrix.setLookAtM(lightView, 0, c.x - dx * dist, c.y - dy * dist, c.z - dz * dist, c.x, c.y, c.z, upX, upY, 0f)
        Matrix.orthoM(lightProj, 0, -r, r, -r, r, 0.01f, dist * 2f)
        Matrix.multiplyMM(lightVP, 0, lightProj, 0, lightView, 0)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, shadowFbo)
        GLES30.glViewport(0, 0, SHADOW_SIZE, SHADOW_SIZE)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(true)
        GLES30.glClear(GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glEnable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glPolygonOffset(1.5f, 3f)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glUseProgram(depthProg.id)
        val uMvp = depthProg.u("uMvp")
        for (i in 0 until scene.drawCount) {
            val d = scene.draw(i)
            if (!d.castShadow) continue
            val model = d.model
            if (model != null) {
                for (p in model.parts) {
                    Matrix.multiplyMM(partMatrix, 0, d.matrix, 0, p.world, 0)
                    Matrix.multiplyMM(mvp, 0, lightVP, 0, partMatrix, 0)
                    GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
                    p.mesh.draw()
                }
                continue
            }
            val mesh = d.mesh ?: continue
            Matrix.multiplyMM(mvp, 0, lightVP, 0, d.matrix, 0)
            GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            mesh.draw()
        }
        GLES30.glDisable(GLES30.GL_POLYGON_OFFSET_FILL)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        shadowActive = true
    }

    private val partMatrix = FloatArray(16)
    private val partColor = FloatArray(4)
    fun drawModel(model: GpuModel, transform: FloatArray, eye: EyeView, tint: FloatArray, scene: FrameScene, gloss: Float = 0f, metal: Float = 0f, blended: Boolean = false) {
        for (p in model.parts) {
            Matrix.multiplyMM(partMatrix, 0, transform, 0, p.world, 0)
            if (!visible(p.mesh, partMatrix, eye)) continue
            for (k in 0..3) partColor[k] = p.color[k] * tint[k]
            if (p.doubleSided) GLES30.glDisable(GLES30.GL_CULL_FACE)
            // inside the transparent pass blending is already on: leave it on
            if (p.blend && !blended) { GLES30.glEnable(GLES30.GL_BLEND); GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA) }
            drawMesh(p.mesh, partMatrix, eye, partColor, p.texture, scene, false, p.alphaCutoff, gloss, metal)
            if (p.blend && !blended) GLES30.glDisable(GLES30.GL_BLEND)
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
            // off-screen for this eye (icons behind you, windows to the side): nothing to draw
            val pr = 0.5f * kotlin.math.sqrt(p.width * p.width + p.height * p.height) + (if (p.curvature > 0f) 0.1f else 0f)
            if (!eye.sphereVisible(p.pose.position.x, p.pose.position.y, p.pose.position.z, pr)) continue
            val mesh = if (p.curvature > 0f) curvedMesh(p.width, p.height, p.curvature) else quad
            if (p.curvature > 0f) Mat4.compose(p.pose.position, p.pose.orientation, 1f, 1f, 1f, panelModel)
            else Mat4.compose(p.pose.position, p.pose.orientation, p.width, p.height, 1f, panelModel)
            Matrix.multiplyMM(mvp, 0, eye.viewProj, 0, panelModel, 0)
            if (p.oes) {
                GLES30.glUseProgram(oesProg.id)
                GLES30.glUniformMatrix4fv(oesProg.u("uMvp"), 1, false, mvp, 0)
                GLES30.glUniformMatrix4fv(oesProg.u("uTexMatrix"), 1, false, p.texMatrix ?: flipY, 0)
                GLES30.glUniform1f(oesProg.u("uAlpha"), p.alpha)
                GLES30.glUniform1f(oesProg.u("uEnhance"), 0f)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, p.texture)
                GLES30.glUniform1i(oesProg.u("uTex"), 0)
            } else {
                val q = quadLoc ?: QuadLocations(quadProg).also { quadLoc = it }
                GLES30.glUseProgram(quadProg.id)
                GLES30.glUniformMatrix4fv(q.mvp, 1, false, mvp, 0)
                GLES30.glUniform1f(q.alpha, p.alpha)
                GLES30.glUniform1f(q.radius, p.radius / p.height)
                GLES30.glUniform1f(q.aspect, p.width / p.height)
                GLES30.glUniform1f(q.glow, p.glow)
                GLES30.glUniform4fv(q.tint, 1, p.tint, 0)
                GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
                GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, p.texture)
                GLES30.glUniform1i(q.tex, 0)
            }
            mesh.draw()
        }
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
    }

    private val curvedCache = HashMap<Long, GpuMesh>()
    /** Cylinder-section meshes are cached per (width, height, radius) in centimetres. */
    private fun curvedMesh(w: Float, h: Float, r: Float): GpuMesh {
        val key = ((w * 100).toLong() shl 40) or ((h * 100).toLong() shl 20) or (r * 100).toLong()
        return curvedCache.getOrPut(key) { GpuMesh.curved(w, h, r) }
    }

    /** Vertical flip for SurfaceTexture-backed panels (web views): quad UV is top-left origin. */
    private val flipY = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 1f, 0f, 1f)

    companion object {
        private val ONE_UV = floatArrayOf(1f, 1f)
        private val WHITE = floatArrayOf(1f, 1f, 1f, 1f)

        /** Centre crop (u, v scale) that makes the camera image cover an eye of [eyeAspect]. */
        fun fillCrop(eyeAspect: Float, camera: CameraImageSource): Pair<Float, Float> {
            val ratio = eyeAspect / camera.imageAspect
            return if (ratio < 1f) ratio to 1f else 1f to 1f / ratio
        }

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
