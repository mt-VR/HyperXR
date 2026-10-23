package com.hyperxr.rendering

import android.opengl.GLES30
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandState
import com.hyperxr.handtracking.TrackingState

/**
 * The hands as a headset draws them: a thin light outline and a see-through dark fill, the
 * forearm fading out below the wrist. No glow, and every fragment outside the hand
 * is discarded right away, so it is cheap to draw.
 *
 * One pass per hand, no intermediate texture: the joints are projected onto a plane at the hand's
 * depth, a quad covering them is drawn on that plane, and its fragment shader evaluates the hand
 * shape as a signed distance field: the palm as a polygon (wrist, thumb base and the four
 * knuckles), each finger as rounded segments and the forearm as a capsule below the wrist. The
 * parts are joined with a smooth minimum, so knuckles and the thumb web flow into each other.
 */
class HandAura {
    private var prog = 0
    private var uProj = 0; private var uJoints = 0; private var uRadius = 0; private var uAlpha = 0; private var uPx = 0; private var uU = 0
    private var screenProg = 0
    private var sJoints = 0; private var sRadius = 0; private var sAlpha = 0; private var sPx = 0; private var sU = 0; private var sViewport = 0
    private var vao = 0
    private var vbo = 0
    private val quad = Gl.floatBuffer(4 * 3)
    private val view = FloatArray(HandJoint.COUNT * 3)
    private val plane = FloatArray(HandJoint.COUNT * 2)

    /** 0..1 overall strength (the settings slider). */
    var opacity = 1f

    fun init() {
        prog = Gl.compile(VS, FS, "hand-aura")
        uProj = GLES30.glGetUniformLocation(prog, "uProj")
        uJoints = GLES30.glGetUniformLocation(prog, "uJ")
        uRadius = GLES30.glGetUniformLocation(prog, "uR")
        uAlpha = GLES30.glGetUniformLocation(prog, "uAlpha")
        uPx = GLES30.glGetUniformLocation(prog, "uPx")
        uU = GLES30.glGetUniformLocation(prog, "uU")
        screenProg = Gl.compile(SCREEN_VS, FS, "hand-aura-screen")
        sJoints = GLES30.glGetUniformLocation(screenProg, "uJ"); sRadius = GLES30.glGetUniformLocation(screenProg, "uR")
        sAlpha = GLES30.glGetUniformLocation(screenProg, "uAlpha"); sPx = GLES30.glGetUniformLocation(screenProg, "uPx")
        sU = GLES30.glGetUniformLocation(screenProg, "uU"); sViewport = GLES30.glGetUniformLocation(screenProg, "uViewport")
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
        GLES30.glGenBuffers(1, ids, 0); vbo = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 4 * 3 * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0)
        GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 12, 0)
        GLES30.glBindVertexArray(0)
    }

    /** Draws the usable hands over the eye image in [outFbo] (viewport [outX], [outY], [outW] x [outH]). */
    fun render(hands: List<HandState>, eye: EyeView, outFbo: Int, outW: Int, outH: Int, outX: Int = 0, outY: Int = 0) {
        var drawn = false
        for (h in hands) {
            if (!h.isUsable) continue
            if (!project(h, eye)) continue
            if (!drawn) {
                GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
                GLES30.glViewport(outX, outY, outW, outH)
                GLES30.glDisable(GLES30.GL_DEPTH_TEST)
                GLES30.glDisable(GLES30.GL_CULL_FACE)
                GLES30.glEnable(GLES30.GL_BLEND)
                // premultiplied: the glow adds light, the fill darkens nothing
                GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
                GLES30.glUseProgram(prog)
                GLES30.glUniformMatrix4fv(uProj, 1, false, eye.proj, 0)
                GLES30.glBindVertexArray(vao)
                GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
                drawn = true
            }
            // size of one screen pixel on the hand's plane, for antialiasing the outline
            val z = -planeZ
            GLES30.glUniform1f(uPx, 2f * z / (eye.proj[5] * outH))
            GLES30.glUniform1f(uU, 1f)
            GLES30.glUniform2fv(uJoints, HandJoint.COUNT, plane, 0)
            for (i in 0 until 5) radii[i] = RADII[i] * scale
            GLES30.glUniform1fv(uRadius, 5, radii, 0)
            // a hand briefly unseen is still the hand: only slightly dimmer, so it does not seem to vanish
            val fade = if (h.state == TrackingState.LOST_PREDICTING) 0.85f else 1f
            GLES30.glUniform1f(uAlpha, opacity.coerceIn(0f, 1f) * fade)
            quad.position(0)
            GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 4 * 3 * 4, quad)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        if (!drawn) return
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    private var planeZ = -0.4f
    private var scale = 1f
    private val radii = FloatArray(5)

    /**
     * Joints to view space, then onto the plane through the palm (z = planeZ) along the eye rays,
     * so the shape drawn on the plane covers the hand exactly as seen from this eye. False when
     * the hand is behind or too close to the eye.
     */
    private fun project(h: HandState, eye: EyeView): Boolean {
        val m = eye.view
        for (i in 0 until HandJoint.COUNT) {
            val p = h.joints[i]
            view[i * 3] = m[0] * p.x + m[4] * p.y + m[8] * p.z + m[12]
            view[i * 3 + 1] = m[1] * p.x + m[5] * p.y + m[9] * p.z + m[13]
            view[i * 3 + 2] = m[2] * p.x + m[6] * p.y + m[10] * p.z + m[14]
        }
        planeZ = (view[HandJoint.WRIST * 3 + 2] + view[HandJoint.MIDDLE_MCP * 3 + 2]) / 2
        if (planeZ > -0.06f) return false
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until HandJoint.COUNT) {
            val z = minOf(view[i * 3 + 2], -0.03f)
            val s = planeZ / z
            val x = view[i * 3] * s; val y = view[i * 3 + 1] * s
            plane[i * 2] = x; plane[i * 2 + 1] = y
            minX = minOf(minX, x); maxX = maxOf(maxX, x); minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        // finger thickness follows the hand's size (a child's hand, a hand nearer than the plane)
        val pw = kotlin.math.hypot(plane[HandJoint.INDEX_MCP * 2] - plane[HandJoint.PINKY_MCP * 2], plane[HandJoint.INDEX_MCP * 2 + 1] - plane[HandJoint.PINKY_MCP * 2 + 1])
        scale = (pw / 0.062f).coerceIn(0.75f, 1.5f)
        // room for the scaled finger thickness, the round fingertip and the outline
        val margin = 0.022f * scale + 0.004f
        minX -= margin; minY -= margin; maxX += margin; maxY += margin
        // the forearm part of the outline runs below the wrist
        val wx = plane[HandJoint.WRIST * 2]; val wy = plane[HandJoint.WRIST * 2 + 1]
        val ax = plane[HandJoint.MIDDLE_MCP * 2] - wx; val ay = plane[HandJoint.MIDDLE_MCP * 2 + 1] - wy
        val al = kotlin.math.sqrt(ax * ax + ay * ay).coerceAtLeast(1e-4f)
        val fx = wx - ax / al * 0.1f; val fy = wy - ay / al * 0.1f
        minX = minOf(minX, fx - 0.04f); maxX = maxOf(maxX, fx + 0.04f); minY = minOf(minY, fy - 0.04f); maxY = maxOf(maxY, fy + 0.04f)
        quad.clear()
        quad.put(minX).put(minY).put(planeZ)
        quad.put(maxX).put(minY).put(planeZ)
        quad.put(minX).put(maxY).put(planeZ)
        quad.put(maxX).put(maxY).put(planeZ)
        quad.flip()
        return true
    }

    /**
     * Mixed reality: draws a hand straight over where the camera image shows it. [imagePx] are the
     * 21 joints already mapped to this eye's viewport pixels (x right, y up), so the outline has
     * exactly the size and place of the real hand, whatever depth the 3D tracking estimated.
     */
    fun renderScreen(imagePx: FloatArray, lostFade: Float, outFbo: Int, outW: Int, outH: Int, outX: Int = 0, outY: Int = 0) {
        // hand size in pixels: knuckle span of a 6.2 cm reference palm
        val span = kotlin.math.hypot(imagePx[HandJoint.INDEX_MCP * 2] - imagePx[HandJoint.PINKY_MCP * 2], imagePx[HandJoint.INDEX_MCP * 2 + 1] - imagePx[HandJoint.PINKY_MCP * 2 + 1])
        val palmLen = kotlin.math.hypot(imagePx[HandJoint.MIDDLE_MCP * 2] - imagePx[0], imagePx[HandJoint.MIDDLE_MCP * 2 + 1] - imagePx[1])
        // a hand seen edge-on has a narrow span: the palm's length (~1.45 spans) keeps the scale
        val unitsPerMetre = maxOf(span / 0.062f, palmLen / 0.09f).coerceAtLeast(1f)
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE; var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in 0 until HandJoint.COUNT) {
            minX = minOf(minX, imagePx[i * 2]); maxX = maxOf(maxX, imagePx[i * 2]); minY = minOf(minY, imagePx[i * 2 + 1]); maxY = maxOf(maxY, imagePx[i * 2 + 1])
        }
        val margin = 0.03f * unitsPerMetre + 3f
        // the forearm runs below the wrist, away from the middle knuckle
        val ax = imagePx[HandJoint.MIDDLE_MCP * 2] - imagePx[0]; val ay = imagePx[HandJoint.MIDDLE_MCP * 2 + 1] - imagePx[1]
        val al = kotlin.math.hypot(ax, ay).coerceAtLeast(1e-3f)
        val fx = imagePx[0] - ax / al * 0.1f * unitsPerMetre; val fy = imagePx[1] - ay / al * 0.1f * unitsPerMetre
        minX = minOf(minX, fx) - margin; maxX = maxOf(maxX, fx) + margin; minY = minOf(minY, fy) - margin; maxY = maxOf(maxY, fy) + margin
        if (maxX < 0f || maxY < 0f || minX > outW || minY > outH) return
        quad.clear()
        quad.put(minX).put(minY).put(0f); quad.put(maxX).put(minY).put(0f); quad.put(minX).put(maxY).put(0f); quad.put(maxX).put(maxY).put(0f)
        quad.flip()
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, outFbo)
        GLES30.glViewport(outX, outY, outW, outH)
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        GLES30.glUseProgram(screenProg)
        GLES30.glUniform2f(sViewport, outW.toFloat(), outH.toFloat())
        GLES30.glUniform1f(sPx, 1f)
        GLES30.glUniform1f(sU, unitsPerMetre)
        GLES30.glUniform2fv(sJoints, HandJoint.COUNT, imagePx, 0)
        for (i in 0 until 5) radii[i] = RADII[i] * unitsPerMetre
        GLES30.glUniform1fv(sRadius, 5, radii, 0)
        GLES30.glUniform1f(sAlpha, opacity.coerceIn(0f, 1f) * lostFade)
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 4 * 3 * 4, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    fun release() {
        if (prog != 0) GLES30.glDeleteProgram(prog)
        if (screenProg != 0) GLES30.glDeleteProgram(screenProg)
        screenProg = 0
        if (vbo != 0) GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0)
        if (vao != 0) GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        prog = 0; vbo = 0; vao = 0
    }

    companion object {
        /** Finger thickness (half-width, metres): thumb, index, middle, ring, pinky. */
        private val RADII = floatArrayOf(0.0105f, 0.0095f, 0.0098f, 0.0092f, 0.0082f)

        private const val VS = """#version 300 es
layout(location=0) in vec3 aPos;     // view space, on the hand plane
uniform mat4 uProj;
out vec2 vP;
void main() { vP = aPos.xy; gl_Position = uProj * vec4(aPos, 1.0); }"""

        /** Same shape, in the eye's pixels (mixed reality, over the camera image). */
        private const val SCREEN_VS = """#version 300 es
layout(location=0) in vec3 aPos;     // viewport pixels
uniform vec2 uViewport;
out vec2 vP;
void main() { vP = aPos.xy; gl_Position = vec4(aPos.xy / uViewport * 2.0 - 1.0, 0.0, 1.0); }"""

        private const val FS = """#version 300 es
precision highp float;
in vec2 vP;
uniform vec2 uJ[21];       // joints projected on the hand plane (metres)
uniform float uR[5];       // finger half-widths
uniform float uAlpha;
uniform float uPx;         // one screen pixel in shape units
uniform float uU;          // shape units per metre of hand (1 on the hand plane; pixels per metre on screen)
out vec4 fragColor;

float segRound(vec2 p, vec2 a, vec2 b, float r) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-10), 0.0, 1.0);
    return length(pa - ba * h) - r;
}
float smin(float a, float b, float k) {
    float h = clamp(0.5 + 0.5 * (b - a) / k, 0.0, 1.0);
    return mix(b, a, h) - k * h * (1.0 - h);
}
// palm: wrist, thumb base, index, middle, ring and pinky knuckles; the landmarks sit on the
// palm's bones, so the polygon is pushed out from its centre to reach the palm's real edge
float palm(vec2 p) {
    vec2 v[6];
    v[0] = uJ[0]; v[1] = uJ[1]; v[2] = uJ[5]; v[3] = uJ[9]; v[4] = uJ[13]; v[5] = uJ[17];
    vec2 ctr = (v[0] + v[1] + v[2] + v[3] + v[4] + v[5]) / 6.0;
    for (int i = 0; i < 6; i++) v[i] = ctr + (v[i] - ctr) * (i == 3 ? 1.04 : 1.12);
    float d = dot(p - v[0], p - v[0]);
    float s = 1.0;
    for (int i = 0, j = 5; i < 6; j = i, i++) {
        vec2 e = v[j] - v[i], w = p - v[i];
        vec2 b = w - e * clamp(dot(w, e) / max(dot(e, e), 1e-10), 0.0, 1.0);
        d = min(d, dot(b, b));
        bvec3 c = bvec3(p.y >= v[i].y, p.y < v[j].y, e.x * w.y > e.y * w.x);
        if (all(c) || all(not(c))) s = -s;
    }
    return s * sqrt(d) - 0.009 * uR[2] / 0.0098;
}
float hand(vec2 p) {
    float d = palm(p);
    // the wrist carries on into the forearm, so the outline runs down off the hand like a sleeve
    vec2 ax = uJ[9] - uJ[0];
    float len = max(length(ax), 1e-4);
    vec2 down = -ax / len;
    float wristR = 0.38 * length(uJ[5] - uJ[17]) + 0.006 * uU;
    d = smin(d, segRound(p, uJ[0] + down * 0.01 * uU, uJ[0] + down * 0.09 * uU, wristR), 0.012 * uU);
    // the thumb's base runs from the wrist, filling the web between thumb and palm
    d = smin(d, segRound(p, uJ[0], uJ[1], uR[0] * 1.25), 0.010 * uU);
    for (int f = 0; f < 5; f++) {
        int b = f == 0 ? 1 : 1 + f * 4;   // thumb 1-4, index 5-8, ... pinky 17-20
        float r = uR[f];
        d = smin(d, segRound(p, uJ[b], uJ[b + 1], r * 1.05), 0.008 * uU);
        d = smin(d, segRound(p, uJ[b + 1], uJ[b + 2], r), 0.006 * uU);
        d = smin(d, segRound(p, uJ[b + 2], uJ[b + 3], r * 0.95), 0.006 * uU);
    }
    return d;
}
void main() {
    float d = hand(vP);
    float aa = max(uPx, 1e-4);
    // outside the hand: nothing to draw (most of the quad), so it costs nothing more
    if (d > aa * 1.5) discard;
    // like a headset's hands: a thin light outline and a see-through dark fill, no glow
    float lineW = max(0.0010 * uU, 1.3 * aa);
    float inside = clamp(0.5 - d / aa, 0.0, 1.0);
    float rim = 1.0 - smoothstep(lineW * 0.5, lineW * 0.5 + aa, abs(d + lineW * 0.5));
    // the forearm fades out a few centimetres below the wrist
    vec2 ax = uJ[9] - uJ[0];
    float below = -dot(vP - uJ[0], ax / max(length(ax), 1e-4));
    float keep = 1.0 - smoothstep(0.01 * uU, 0.08 * uU, below);
    float fillA = inside * (1.0 - rim) * 0.32;
    float lineA = rim * 0.85;
    float a = (lineA + fillA) * keep * uAlpha;
    vec3 c = vec3(0.92) * lineA * keep * uAlpha;   // premultiplied: light line, black fill
    fragColor = vec4(c, a);
}"""
    }
}
