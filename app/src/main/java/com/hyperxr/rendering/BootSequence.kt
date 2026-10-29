package com.hyperxr.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.opengl.GLES30
import android.opengl.GLUtils
import android.opengl.Matrix
import com.hyperxr.math.Pose
import kotlin.math.exp
import kotlin.math.sqrt

/**
 * HyperXR's boot, drawn around the user in the headset like an OS starting up:
 *  1. hyperspace - stars stream past from straight ahead, slowing down;
 *  2. the HX logo is drawn by a sweep of light, with a soft glow;
 *  3. "HyperMagic" comes into focus and a thin bar fills as the system gets ready;
 *  4. the logo flares, everything opens up and the home environment shows through.
 * Everything is procedural except the logo texture; the scene is skipped while the boot covers it.
 */
class BootSequence(private val context: Context) {
    private var skyProg = 0
    private var quadProg = 0
    private var logoTex = 0
    private var textTex = 0
    private var logoAspect = 1f
    private var textAspect = 4f
    private var vao = 0
    private var vbo = 0
    private val verts = Gl.floatBuffer(4 * 5)
    private val invVp = FloatArray(16)
    private val rotView = FloatArray(16)
    private val tmp = FloatArray(16)

    private var startNs = 0L
    private var anchored = false
    private val fwd = FloatArray(3)
    private val origin = FloatArray(3)
    /** 0..1: how far the system is from ready (the bar waits on it near the end). */
    @Volatile var readiness = 0f
    var finished = false; private set

    /** Seconds since the first frame. */
    private val t get() = if (startNs == 0L) 0f else (System.nanoTime() - startNs) / 1e9f

    fun init() {
        skyProg = Gl.compile(SKY_VS, SKY_FS, "boot-sky")
        quadProg = Gl.compile(QUAD_VS, QUAD_FS, "boot-quad")
        val logo = BitmapFactory.decodeResource(context.resources, com.hyperxr.R.drawable.hx_logo, BitmapFactory.Options().apply { inScaled = false })
        logoAspect = logo.width.toFloat() / logo.height
        logoTex = upload(logo)
        val text = wordmark()
        textAspect = text.width.toFloat() / text.height
        textTex = upload(text)
        val ids = IntArray(1)
        GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
        GLES30.glGenBuffers(1, ids, 0); vbo = ids[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, 4 * 5 * 4, null, GLES30.GL_DYNAMIC_DRAW)
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 20, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 2, GLES30.GL_FLOAT, false, 20, 12)
        GLES30.glBindVertexArray(0)
    }

    private fun wordmark(): Bitmap {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xffffffff.toInt(); textSize = 150f; letterSpacing = 0.42f
            typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        }
        val w = (p.measureText("HYPERXR") + 120).toInt(); val h = 240
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { Canvas(it).drawText("HYPERXR", 60f, 175f, p) }
    }

    private fun upload(b: Bitmap): Int {
        val tex = Gl.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex)
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        b.recycle()
        return tex
    }

    /** Called once per frame before the eyes: anchors the boot where the user looks first. */
    fun frame(head: Pose) {
        if (finished) return
        if (startNs == 0L) startNs = System.nanoTime()
        if (!anchored) {
            val f = head.forward()
            // level heading: the logo stands upright in front, whatever the first pitch
            val l = sqrt(f.x * f.x + f.z * f.z).coerceAtLeast(1e-4f)
            fwd[0] = f.x / l; fwd[1] = 0f; fwd[2] = f.z / l
            origin[0] = head.position.x; origin[1] = head.position.y; origin[2] = head.position.z
            anchored = true
        }
        if (t > END_S + (1f - readiness).coerceAtLeast(0f) * 2.5f && t > END_S) finished = true
    }

    /** True while the boot hides the scene completely (the scene need not be drawn). */
    val opaque get() = !finished && t < EXIT_S

    /** Overall opacity over the scene. */
    private fun cover(): Float = when {
        t < EXIT_S -> 1f
        else -> 1f - smooth((t - EXIT_S) / (END_S - EXIT_S))
    }

    fun render(eye: EyeView) {
        if (finished || !anchored) return
        val time = t
        val cover = cover()
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glEnable(GLES30.GL_BLEND)
        GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)   // premultiplied

        // sky: rotation-only view, so the stars sit at infinity
        System.arraycopy(eye.view, 0, rotView, 0, 16)
        rotView[12] = 0f; rotView[13] = 0f; rotView[14] = 0f
        Matrix.multiplyMM(tmp, 0, eye.proj, 0, rotView, 0)
        Matrix.invertM(invVp, 0, tmp, 0)
        GLES30.glUseProgram(skyProg)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(skyProg, "uInvVP"), 1, false, invVp, 0)
        GLES30.glUniform3f(GLES30.glGetUniformLocation(skyProg, "uFwd"), fwd[0], fwd[1], fwd[2])
        GLES30.glUniform1f(GLES30.glGetUniformLocation(skyProg, "uTime"), time)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(skyProg, "uWarp"), warpTravel(time))
        GLES30.glUniform1f(GLES30.glGetUniformLocation(skyProg, "uSpeed"), warpSpeed(time))
        GLES30.glUniform1f(GLES30.glGetUniformLocation(skyProg, "uCover"), cover)
        GLES30.glUniform1f(GLES30.glGetUniformLocation(skyProg, "uFlare"), flare(time))
        GLES30.glBindVertexArray(0)
        FullscreenTriangle.bind()
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // logo, wordmark and bar on a plane 3 m ahead
        GLES30.glUseProgram(quadProg)
        GLES30.glUniformMatrix4fv(GLES30.glGetUniformLocation(quadProg, "uVP"), 1, false, eye.viewProj, 0)
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(quadProg, "uTex"), 0)
        val uMode = GLES30.glGetUniformLocation(quadProg, "uMode")
        val uA = GLES30.glGetUniformLocation(quadProg, "uA")
        val uB = GLES30.glGetUniformLocation(quadProg, "uB")
        val uAlpha = GLES30.glGetUniformLocation(quadProg, "uAlpha")

        // logo: revealed by a sweep between 0.9 s and 2.4 s, flaring and growing on the way out
        val reveal = smooth((time - 0.9f) / 1.5f)
        val exit = smooth((time - EXIT_S) / (END_S - EXIT_S))
        val logoW = 1.7f * (1f + 0.18f * exit)
        if (reveal > 0f) {
            quad(0f, 0.25f, logoW, logoW / logoAspect)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, logoTex)
            GLES30.glUniform1i(uMode, 0)
            GLES30.glUniform1f(uA, -0.25f + reveal * 1.6f)          // sweep edge
            GLES30.glUniform1f(uB, 0.55f + 0.35f * kotlin.math.sin(time * 2.2f).let { it * it } + 1.8f * flare(time))  // glow
            GLES30.glUniform1f(uAlpha, cover)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        // wordmark: comes into focus (from blurred mip levels) and rises a little
        val focus = smooth((time - 2.0f) / 0.9f)
        if (focus > 0f) {
            val tw = 1.35f
            quad(0f, -0.42f + 0.04f * (1f - focus), tw, tw / textAspect)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, textTex)
            GLES30.glUniform1i(uMode, 1)
            GLES30.glUniform1f(uA, (1f - focus) * 4.5f)              // blur (mip bias)
            GLES30.glUniform1f(uB, 0f)
            GLES30.glUniform1f(uAlpha, focus * cover * (1f - exit))
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        // progress: a hairline that fills with time and readiness
        val barIn = smooth((time - 0.6f) / 0.5f)
        if (barIn > 0f) {
            quad(0f, -0.68f, 0.9f, 0.012f)
            GLES30.glUniform1i(uMode, 2)
            GLES30.glUniform1f(uA, minOf(smooth((time - 0.6f) / 2.6f) * 0.85f + readiness * 0.15f, 1f))
            GLES30.glUniform1f(uB, time)
            GLES30.glUniform1f(uAlpha, barIn * cover * (1f - exit))
            GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES30.glBindVertexArray(0)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glDepthMask(true)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    /** A quad centred [cx], [cy] metres off the boot's centre line, [w] x [h], 3 m ahead. */
    private fun quad(cx: Float, cy: Float, w: Float, h: Float) {
        val rx = -fwd[2]; val rz = fwd[0]          // right = forward x up
        val px = origin[0] + fwd[0] * DIST + rx * cx; val py = origin[1] + cy; val pz = origin[2] + fwd[2] * DIST + rz * cx
        verts.clear()
        fun v(sx: Float, sy: Float, u: Float, vv: Float) { verts.put(px + rx * sx * w / 2).put(py + sy * h / 2).put(pz + rz * sx * w / 2).put(u).put(vv) }
        v(-1f, -1f, 0f, 1f); v(1f, -1f, 1f, 1f); v(-1f, 1f, 0f, 0f); v(1f, 1f, 1f, 0f)
        verts.flip()
        GLES30.glBufferSubData(GLES30.GL_ARRAY_BUFFER, 0, 4 * 5 * 4, verts)
    }

    /** Hyperspace: fast at first, easing to a drift as the logo forms. */
    private fun warpSpeed(t: Float) = 0.15f + 3.2f * exp(-t * 1.6f)
    private fun warpTravel(t: Float) = 0.15f * t + 2f * (1f - exp(-t * 1.6f))
    private fun flare(t: Float): Float { val x = (t - EXIT_S - 0.15f) / 0.35f; return exp(-x * x) }
    private fun smooth(x: Float): Float { val c = x.coerceIn(0f, 1f); return c * c * (3f - 2f * c) }

    fun release() {
        if (skyProg != 0) GLES30.glDeleteProgram(skyProg)
        if (quadProg != 0) GLES30.glDeleteProgram(quadProg)
        GLES30.glDeleteTextures(2, intArrayOf(logoTex, textTex), 0)
        GLES30.glDeleteBuffers(1, intArrayOf(vbo), 0); GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0)
        skyProg = 0; quadProg = 0
    }

    companion object {
        private const val DIST = 3f
        private const val EXIT_S = 3.5f
        private const val END_S = 4.3f

        private const val SKY_VS = """#version 300 es
layout(location=0) in vec3 aPos;
out vec2 vNdc;
void main() { vNdc = aPos.xy; gl_Position = vec4(aPos.xy, 0.999, 1.0); }"""

        private const val SKY_FS = """#version 300 es
precision highp float;
in vec2 vNdc;
uniform mat4 uInvVP;
uniform vec3 uFwd;
uniform float uTime;
uniform float uWarp;    // distance travelled
uniform float uSpeed;   // current warp speed (streak length)
uniform float uCover;
uniform float uFlare;
out vec4 fragColor;

float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
float hash3(vec3 p) { return fract(sin(dot(p, vec3(127.1, 311.7, 74.7))) * 43758.5453); }
float noise3(vec3 p) {
    vec3 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
    float a = mix(mix(hash3(i), hash3(i + vec3(1, 0, 0)), f.x), mix(hash3(i + vec3(0, 1, 0)), hash3(i + vec3(1, 1, 0)), f.x), f.y);
    float b = mix(mix(hash3(i + vec3(0, 0, 1)), hash3(i + vec3(1, 0, 1)), f.x), mix(hash3(i + vec3(0, 1, 1)), hash3(i + vec3(1, 1, 1)), f.x), f.y);
    return mix(a, b, f.z);
}
void main() {
    vec4 w = uInvVP * vec4(vNdc, 1.0, 1.0);
    vec3 d = normalize(w.xyz / w.w);
    vec3 right = normalize(cross(uFwd, vec3(0.0, 1.0, 0.0)));
    vec3 up = cross(right, uFwd);
    float c = dot(d, uFwd);
    float ang = acos(clamp(c, -1.0, 1.0));                 // 0 ahead .. pi behind
    float az = atan(dot(d, up), dot(d, right));             // around the forward axis
    // stars on rings around the forward axis, flowing outward (log of the angle) = flying ahead
    float r = log(max(ang, 1e-3)) * 2.2 - uWarp * 3.0;
    vec3 col = vec3(0.0);
    for (int layer = 0; layer < 3; layer++) {
        float fl = float(layer);
        float cells = 48.0 + 40.0 * fl;
        vec2 g = vec2(az / 6.2831853 * cells, r * (1.0 + 0.35 * fl) + fl * 7.13);
        vec2 id = floor(g), f = fract(g);
        float h = hash(id + fl * 19.7);
        if (h > 0.62) {
            vec2 s = vec2(hash(id + 3.1), hash(id + 7.7));
            float dx = (f.x - s.x) * 1.0;
            float len = 0.02 + uSpeed * 0.32;               // streak along the flow
            float dy = f.y - s.y;
            float along = clamp(-dy / len, 0.0, 1.0);
            float dist = length(vec2(dx * 1.6, dy + along * len));
            float star = smoothstep(0.06, 0.0, dist) * (1.0 - along * 0.8);
            float tw = 0.6 + 0.4 * sin(uTime * (2.0 + 3.0 * h) + h * 40.0);
            vec3 tint = mix(vec3(0.65, 0.75, 1.0), vec3(1.0, 0.85, 0.95), hash(id + 1.3));
            col += tint * star * tw * (0.6 + 0.6 * h) * smoothstep(0.02, 0.25, ang);
        }
    }
    // nebula: slow, deep blue and violet, brightest around the way ahead
    // (3D noise on the direction: no seam anywhere around you)
    vec3 np = d * 2.2 - uFwd * uWarp * 0.5 + vec3(uTime * 0.02, 0.0, 0.0);
    float n = noise3(np * 2.0) * 0.6 + noise3(np * 5.0) * 0.3 + noise3(np * 11.0) * 0.1;
    vec3 neb = mix(vec3(0.02, 0.03, 0.09), vec3(0.16, 0.08, 0.30), n) * (0.5 + 0.8 * exp(-ang * 1.2));
    // the light behind the logo, and the flare when the system opens
    float core = exp(-ang * ang * 30.0);
    vec3 glow = vec3(0.35, 0.45, 1.0) * core * 0.35 + vec3(0.8, 0.9, 1.0) * uFlare * exp(-ang * ang * 6.0) * 0.9;
    vec3 rgb = neb + col + glow;
    fragColor = vec4(rgb * uCover, uCover);
}"""

        private const val QUAD_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec2 aUv;
uniform mat4 uVP;
out vec2 vUv;
void main() { vUv = aUv; gl_Position = uVP * vec4(aPos, 1.0); }"""

        private const val QUAD_FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
uniform int uMode;      // 0 logo, 1 wordmark, 2 progress bar
uniform float uA;       // logo: sweep edge / text: blur / bar: fill
uniform float uB;       // logo: glow strength / bar: time
uniform float uAlpha;
out vec4 fragColor;
void main() {
    if (uMode == 0) {
        vec4 tx = texture(uTex, vUv);   // premultiplied: the logo's own colours
        float a = tx.a;
        // the sweep runs left to right
        float s = vUv.x;
        float shown = smoothstep(uA, uA - 0.12, s);
        float band = exp(-pow((s - uA) / 0.035, 2.0));
        // glow from blurred mip levels: only where the logo has been drawn
        float halo = textureLod(uTex, vUv, 4.0).a * 0.55 + textureLod(uTex, vUv, 6.0).a * 0.8;
        vec3 line = tx.rgb * 1.25 * shown + vec3(0.75, 0.88, 1.0) * a * band * 2.2;
        vec3 glow = vec3(0.45, 0.55, 1.0) * halo * uB * shown;
        float alpha = clamp(a * shown + a * band, 0.0, 1.0);
        fragColor = vec4((line + glow) * uAlpha, alpha * uAlpha);
    } else if (uMode == 1) {
        float a = texture(uTex, vUv, uA).a;
        fragColor = vec4(vec3(0.95, 0.97, 1.0) * a * uAlpha * 1.3, a * uAlpha);
    } else {
        float filled = step(vUv.x, uA);
        float edge = exp(-pow((vUv.x - uA) / 0.02, 2.0));
        float shimmer = 0.75 + 0.25 * sin(vUv.x * 40.0 - uB * 6.0);
        vec3 c = vec3(0.55, 0.68, 1.0) * filled * shimmer + vec3(0.9, 0.95, 1.0) * edge;
        float a = max(filled * 0.9, edge) + 0.12;
        fragColor = vec4(c * uAlpha, a * uAlpha);
    }
}"""
    }
}
