package com.hyperxr.rendering

import android.opengl.GLES30
import com.hyperxr.settings.GraphicsSettings
import kotlin.math.tan

/**
 * Side-by-side output for phone viewers (VR Box / Cardboard-style). Each eye renders into its own
 * texture with a symmetric frustum; the composite pass applies radial lens pre-distortion centred
 * on the physical lens position derived from the lens separation and the screen DPI.
 */
class StereoCompositor {
    private var distortProg = 0
    private var copyProg = 0
    private var vao = 0
    private val eyes = arrayOfNulls<RenderTarget>(2)
    private val u = HashMap<String, Int>()

    var screenW = 0; private set
    var screenH = 0; private set
    private var xdpi = 400f
    var eyeW = 0; private set
    var eyeH = 0; private set

    fun init() {
        distortProg = Gl.compile(Shaders.FULLSCREEN_VS, Shaders.DISTORT_FS, "distort")
        copyProg = Gl.compile(Shaders.FULLSCREEN_VS, Shaders.COPY_FS, "copy")
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
        GLES30.glBindVertexArray(vao)
        FullscreenTriangle.bind()
        GLES30.glBindVertexArray(0)
        for (n in listOf("uTex", "uLensCenter", "uScale", "uScaleIn", "uK", "uChroma")) u[n] = GLES30.glGetUniformLocation(distortProg, n)
    }

    fun resize(width: Int, height: Int, xdpi: Float, g: GraphicsSettings) {
        screenW = width; screenH = height; this.xdpi = xdpi
        val scale = g.renderScale.coerceIn(0.4f, 1.2f)
        val w = ((width / 2) * scale).toInt().coerceAtLeast(64)
        val h = (height * scale).toInt().coerceAtLeast(64)
        if (w != eyeW || h != eyeH) {
            eyes.forEach { it?.release() }
            eyes[0] = RenderTarget(w, h)
            eyes[1] = RenderTarget(w, h)
            eyeW = w; eyeH = h
        }
    }

    fun eyeTarget(i: Int): RenderTarget = eyes[i]!!

    /** Vertical tangent extent of the eye frustum (symmetric). */
    fun tanHalfFov(g: GraphicsSettings): Float = tan(Math.toRadians(g.fovDegrees / 2.0).toFloat())

    fun aspect(): Float = eyeW.toFloat() / eyeH

    /** Tangents the eye textures were rendered with this frame (set by the engine). */
    var eyeTanH = 1f
    var eyeTanV = 1f

    fun composite(g: GraphicsSettings) {
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, screenW, screenH)
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glBindVertexArray(vao)
        val halfW = screenW / 2
        // Lens centre offset from the half-screen centre, in output UV units.
        val pxPerMm = xdpi / 25.4f
        val lensOffsetPx = (g.lensSeparationMm / 2f) * pxPerMm
        val tanV = eyeTanV
        val tanH = eyeTanH
        for (eye in 0..1) {
            GLES30.glViewport(eye * halfW, 0, halfW, screenH)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, eyes[eye]!!.texture)
            if (g.lensDistortion) {
                GLES30.glUseProgram(distortProg)
                // left eye lens sits right of its half-screen centre (towards the nose) and vice versa
                val centerPx = if (eye == 0) halfW - lensOffsetPx else lensOffsetPx
                GLES30.glUniform2f(u.getValue("uLensCenter"), (centerPx / halfW).coerceIn(0.2f, 0.8f), 0.5f)
                // map output UV to tangent space so that the viewport width spans ~2*tanH
                GLES30.glUniform2f(u.getValue("uScale"), 2f * tanH, 2f * tanV)
                GLES30.glUniform2f(u.getValue("uScaleIn"), 1f / (2f * tanH), 1f / (2f * tanV))
                GLES30.glUniform2f(u.getValue("uK"), g.distortionK1, g.distortionK2)
                GLES30.glUniform1f(u.getValue("uChroma"), 0.006f)
                GLES30.glUniform1i(u.getValue("uTex"), 0)
            } else {
                GLES30.glUseProgram(copyProg)
            }
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
    }

    fun release() { eyes.forEach { it?.release() }; eyes.fill(null); eyeW = 0; eyeH = 0 }
}
