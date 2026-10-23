package com.hyperxr.rendering

import android.opengl.GLES30
import com.hyperxr.handtracking.HandJoint
import com.hyperxr.handtracking.HandState
import com.hyperxr.handtracking.TrackingState

/**
 * The hands as a lilac outline: a thin bright line along the hand's silhouette with a short lilac
 * glow, nothing inside, open at the bottom where it runs down past the wrist.
 *
 * One pass per hand, no intermediate texture: the joints are projected onto a plane at the hand's
 * depth, a quad covering them is drawn on that plane, and its fragment shader evaluates the hand
 * shape as a signed distance field: the palm as a polygon (wrist, thumb base and the four
 * knuckles), each finger as rounded segments and the forearm as a capsule below the wrist. The
 * parts are joined with a smooth minimum, so knuckles and the thumb web flow into each other.
 */
class HandAura {
    private var prog = 0
    private var uProj = 0; private var uJoints = 0; private var uRadius = 0; private var uAlpha = 0; private var uPx = 0
    private var vao = 0
    private var vbo = 0
    private val quad = Gl.floatBuffer(4 * 3)
    private val view = FloatArray(HandJoint.COUNT * 3)
    private val plane = FloatArray(HandJoint.COUNT * 2)

    /** 0..1 overall strength (the settings slider). */
    var opacity = 0.8f

    fun init() {
        prog = Gl.compile(VS, FS, "hand-aura")
        uProj = GLES30.glGetUniformLocation(prog, "uProj")
        uJoints = GLES30.glGetUniformLocation(prog, "uJ")
        uRadius = GLES30.glGetUniformLocation(prog, "uR")
        uAlpha = GLES30.glGetUniformLocation(prog, "uAlpha")
        uPx = GLES30.glGetUniformLocation(prog, "uPx")
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
            GLES30.glUniform2fv(uJoints, HandJoint.COUNT, plane, 0)
            GLES30.glUniform1fv(uRadius, 5, RADII, 0)
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
        // room for the finger thickness and the glow
        val margin = 0.03f
        minX -= margin; minY -= margin; maxX += margin; maxY += margin
        // the forearm part of the outline runs below the wrist
        val wx = plane[HandJoint.WRIST * 2]; val wy = plane[HandJoint.WRIST * 2 + 1]
        val ax = plane[HandJoint.MIDDLE_MCP * 2] - wx; val ay = plane[HandJoint.MIDDLE_MCP * 2 + 1] - wy
        val al = kotlin.math.sqrt(ax * ax + ay * ay).coerceAtLeast(1e-4f)
        val fx = wx - ax / al * 0.1f; val fy = wy - ay / al * 0.1f
        minX = minOf(minX, fx - 0.06f); maxX = maxOf(maxX, fx + 0.06f); minY = minOf(minY, fy - 0.06f); maxY = maxOf(maxY, fy + 0.06f)
        quad.clear()
        quad.put(minX).put(minY).put(planeZ)
        quad.put(maxX).put(minY).put(planeZ)
        quad.put(minX).put(maxY).put(planeZ)
        quad.put(maxX).put(maxY).put(planeZ)
        quad.flip()
        return true
    }

    fun release() {
        if (prog != 0) GLES30.glDeleteProgram(prog)
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

        private const val FS = """#version 300 es
precision highp float;
in vec2 vP;
uniform vec2 uJ[21];       // joints projected on the hand plane (metres)
uniform float uR[5];       // finger half-widths
uniform float uAlpha;
uniform float uPx;         // one screen pixel on the plane
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
// palm: wrist, thumb base, index, middle, ring and pinky knuckles
float palm(vec2 p) {
    vec2 v[6];
    v[0] = uJ[0]; v[1] = uJ[1]; v[2] = uJ[5]; v[3] = uJ[9]; v[4] = uJ[13]; v[5] = uJ[17];
    float d = dot(p - v[0], p - v[0]);
    float s = 1.0;
    for (int i = 0, j = 5; i < 6; j = i, i++) {
        vec2 e = v[j] - v[i], w = p - v[i];
        vec2 b = w - e * clamp(dot(w, e) / max(dot(e, e), 1e-10), 0.0, 1.0);
        d = min(d, dot(b, b));
        bvec3 c = bvec3(p.y >= v[i].y, p.y < v[j].y, e.x * w.y > e.y * w.x);
        if (all(c) || all(not(c))) s = -s;
    }
    return s * sqrt(d) - 0.009;
}
float hand(vec2 p) {
    float d = palm(p);
    // the wrist carries on into the forearm, so the outline runs down off the hand like a sleeve
    vec2 ax = uJ[9] - uJ[0];
    float len = max(length(ax), 1e-4);
    vec2 down = -ax / len;
    float wristR = 0.38 * length(uJ[5] - uJ[17]) + 0.006;
    d = smin(d, segRound(p, uJ[0] + down * 0.01, uJ[0] + down * 0.09, wristR), 0.012);
    for (int f = 0; f < 5; f++) {
        int b = f == 0 ? 1 : 1 + f * 4;   // thumb 1-4, index 5-8, ... pinky 17-20
        float r = uR[f];
        d = smin(d, segRound(p, uJ[b], uJ[b + 1], r), 0.004);
        d = smin(d, segRound(p, uJ[b + 1], uJ[b + 2], r), 0.004);
        d = smin(d, segRound(p, uJ[b + 2], uJ[b + 3], r * 0.95), 0.004);
    }
    return d;
}
void main() {
    float d = hand(vP);
    float aa = max(uPx, 1e-4);
    // only the outline: a thin bright core with a short lilac glow on both sides, nothing inside
    float lineW = max(0.0009, 1.2 * aa);
    float core = 1.0 - smoothstep(lineW * 0.5, lineW * 0.5 + aa, abs(d));
    float glow = exp(-abs(d) / 0.0032);
    // open at the bottom: the lines fade out a few centimetres below the wrist
    vec2 ax = uJ[9] - uJ[0];
    float below = -dot(vP - uJ[0], ax / max(length(ax), 1e-4));
    float keep = 1.0 - smoothstep(0.015, 0.06, below);
    vec3 white = vec3(0.93, 0.85, 1.0);
    vec3 lilac = vec3(0.72, 0.48, 1.0);
    float a = clamp(core + glow * 0.55, 0.0, 1.0) * keep * uAlpha;
    vec3 c = (white * core + lilac * glow * 0.95) * keep * uAlpha;
    fragColor = vec4(c, a);
}"""
    }
}
