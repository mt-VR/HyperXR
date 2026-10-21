package com.hyperxr.rendering

import android.opengl.GLES30
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandState
import com.hyperxr.math.Vec3
import java.nio.FloatBuffer

/**
 * Black hand aura. Pass 1 renders every bone as a view-facing billboard whose fragment shader
 * evaluates a capsule distance field; MAX blending into a low-resolution R8 target gives the union
 * of all capsules (so joints have no seams). Pass 2 maps that field to a soft black rim with a
 * translucent black interior and blends it over the eye image.
 */
class HandAuraRenderer {
    private var fieldProg = 0
    private var compositeProg = 0
    private var uProj = 0
    private var uField = 0
    private var uOpacity = 0
    private var vao = 0
    private var vbo = 0
    private var fsVao = 0
    private var target: RenderTarget? = null
    private val maxBones = HandJoint.BONES.size + 4
    // per vertex: pos3, A3, uv2 (radius,width), B3 = 11 floats; 6 vertices per bone; 2 hands
    private val data: FloatBuffer = Gl.floatBuffer(maxBones * 2 * 6 * 11)
    private val a = Vec3(); private val b = Vec3(); private val tmp = Vec3()

    var opacity = 0.45f

    fun init() {
        fieldProg = Gl.compile(Shaders.AURA_FIELD_VS, Shaders.AURA_FIELD_FS, "aura-field")
        compositeProg = Gl.compile(Shaders.FULLSCREEN_VS, Shaders.AURA_COMPOSITE_FS, "aura-composite")
        uProj = GLES30.glGetUniformLocation(fieldProg, "uProj")
        uField = GLES30.glGetUniformLocation(compositeProg, "uField")
        uOpacity = GLES30.glGetUniformLocation(compositeProg, "uOpacity")
        val ids = IntArray(2)
        GLES30.glGenVertexArrays(2, ids, 0); vao = ids[0]; fsVao = ids[1]
        GLES30.glGenBuffers(1, ids, 0); vbo = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, data.capacity() * 4, null, GLES30.GL_DYNAMIC_DRAW)
        val stride = 11 * 4
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, stride, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, stride, 12)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 2, GLES30.GL_FLOAT, false, stride, 24)
        GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribPointer(3, 3, GLES30.GL_FLOAT, false, stride, 32)
        GLES30.glBindVertexArray(fsVao)
        FullscreenTriangle.bind()
        GLES30.glBindVertexArray(0)
    }

    private fun ensureTarget(w: Int, h: Int) {
        val tw = maxOf(64, w / 2); val th = maxOf(64, h / 2)
        val t = target
        if (t == null || t.width != tw || t.height != th) {
            t?.release()
            target = RenderTarget(tw, th, depth = false, format = GLES30.GL_R8)
        }
    }

    /** Draws the aura of the usable hands over the currently bound eye framebuffer. */
    fun render(hands: List<HandState>, eye: EyeView, outFbo: Int, outW: Int, outH: Int, outX: Int = 0, outY: Int = 0) {
        data.clear()
        var vertices = 0
        for (hand in hands) {
            if (!hand.isUsable) continue
            val fade = hand.confidence.coerceIn(0.3f, 1f)
            for (bone in HandJoint.BONES) {
                toView(eye, hand.joints[bone[0]], a)
                toView(eye, hand.joints[bone[1]], b)
                if (a.z > -0.05f && b.z > -0.05f) continue
                val r = (HandJoint.radiusFor(bone[0]) + HandJoint.radiusFor(bone[1])) * 0.5f
                vertices += billboard(r, 0.012f * fade + 0.004f)
            }
            // palm fill: fan of capsules across the palm so the interior is covered
            for ((i0, i1) in PALM) {
                toView(eye, hand.joints[i0], a)
                toView(eye, hand.joints[i1], b)
                vertices += billboard(0.024f, 0.012f)
            }
        }
        if (vertices == 0) return
        data.flip()
        val prevFbo = outFbo
        val tgt = run { ensureTarget(outW, outH); target!! }

        tgt.bind()
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendEquation(GLES30.GL_MAX)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
        GLES30.glUseProgram(fieldProg)
        GLES30.glUniformMatrix4fv(uProj, 1, false, eye.proj, 0)
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, data.limit() * 4, data)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, vertices)
        GLES30.glBlendEquation(GLES30.GL_FUNC_ADD)

        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, prevFbo)
        GLES30.glViewport(outX, outY, outW, outH)
        GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(compositeProg)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tgt.texture)
        GLES30.glUniform1i(uField, 0)
        GLES30.glUniform1f(uOpacity, (opacity / 0.45f).coerceIn(0f, 2f))
        GLES30.glBindVertexArray(fsVao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    private fun toView(eye: EyeView, p: Vec3, out: Vec3) {
        val m = eye.view
        out.set(m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12], m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13], m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14])
    }

    /** Emits a quad (2 triangles) covering segment a-b expanded by radius+width, facing the viewer. */
    private fun billboard(radius: Float, width: Float): Int {
        val ext = radius + width
        var dx = b.x - a.x; var dy = b.y - a.y
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len < 1e-5f) { dx = 1f; dy = 0f } else { dx /= len; dy /= len }
        val nx = -dy; val ny = dx
        // corners: a - dir*ext +- n*ext, b + dir*ext +- n*ext (in the view plane at each endpoint depth)
        fun v(px: Float, py: Float, pz: Float) {
            data.put(px).put(py).put(pz).put(a.x).put(a.y).put(a.z).put(radius).put(width).put(b.x).put(b.y).put(b.z)
        }
        val a0x = a.x - dx * ext + nx * ext; val a0y = a.y - dy * ext + ny * ext
        val a1x = a.x - dx * ext - nx * ext; val a1y = a.y - dy * ext - ny * ext
        val b0x = b.x + dx * ext + nx * ext; val b0y = b.y + dy * ext + ny * ext
        val b1x = b.x + dx * ext - nx * ext; val b1y = b.y + dy * ext - ny * ext
        if (data.remaining() < 66) return 0
        v(a0x, a0y, a.z); v(a1x, a1y, a.z); v(b0x, b0y, b.z)
        v(a1x, a1y, a.z); v(b1x, b1y, b.z); v(b0x, b0y, b.z)
        return 6
    }

    companion object {
        private val PALM = listOf(
            HandJoint.WRIST to HandJoint.MIDDLE_MCP, HandJoint.WRIST to HandJoint.INDEX_MCP,
            HandJoint.WRIST to HandJoint.RING_MCP, HandJoint.WRIST to HandJoint.PINKY_MCP,
        )
    }
}

/** Shared fullscreen triangle vertex data (attribute 0). */
object FullscreenTriangle {
    private var vbo = 0
    fun bind() {
        if (vbo == 0) {
            val ids = IntArray(1)
            GLES30.glGenBuffers(1, ids, 0); vbo = ids[0]
            val v = Gl.floatBuffer(9).put(floatArrayOf(-1f, -1f, 0f, 3f, -1f, 0f, -1f, 3f, 0f)).also { it.position(0) }
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 36, v, GLES30.GL_STATIC_DRAW)
        }
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
    }

    fun reset() { vbo = 0 }
}
