package com.hyperxr.questgames

import android.opengl.GLES30
import android.opengl.Matrix
import com.hyperxr.math.Vec3
import com.hyperxr.ocean.OceanTextures
import com.hyperxr.rendering.EyeView
import com.hyperxr.rendering.FullscreenTriangle
import com.hyperxr.rendering.Gl
import com.hyperxr.rendering.ScenePainter

/**
 * The world around a game, painted in one pass behind everything: the sky (gradient, sun,
 * stars, a nebula or clouds) and an endless ground (a neon grid, a plain, or water that mirrors
 * the sky), fading into the fog at the horizon. Each game picks a [Look].
 */
class GameSky(private val host: GameHost) : ScenePainter {
    class Look(
        val zenith: FloatArray, val horizon: FloatArray, val ground: FloatArray,
        /** Towards the sun, game space (it is also where the scene light comes from). */
        val sunDir: FloatArray = floatArrayOf(0.3f, 0.5f, -0.8f),
        val sunColor: FloatArray = floatArrayOf(0f, 0f, 0f),
        /** Sun disc size (radians); 0: only the glow. */
        val sunSize: Float = 0.02f,
        val stars: Float = 0f,
        /** Nebula colour (rgb) and amount. */
        val nebula: FloatArray = floatArrayOf(0f, 0f, 0f, 0f),
        val clouds: Float = 0f,
        val cloudColor: FloatArray = floatArrayOf(1f, 1f, 1f),
        val floor: Int = FLOOR_NONE,
        val floorY: Float = 0f,
        val floorColor: FloatArray = floatArrayOf(0f, 0f, 0f),
        /** Grid line colour (rgb) and spacing (m). */
        val grid: FloatArray = floatArrayOf(0f, 0f, 0f, 1f),
        /** Fog density on the ground (per metre); objects fade into the horizon colour at [objectFog]. */
        val fog: Float = 0.02f,
        val objectFog: Float = fog * 0.5f,
        /** The scene light (from the sun's direction) and the ambient fill. */
        val light: FloatArray = floatArrayOf(1f, 0.97f, 0.92f),
        val ambient: FloatArray = floatArrayOf(0.32f, 0.33f, 0.4f),
    )

    var look: Look = DARK
    /** Ground height override (the plank game's street falls away as you ride up). */
    var floorY = Float.NaN
    /** Grid brightness (pulses with the music). */
    var pulse = 0f

    private var prog = 0
    private var vao = 0
    private var noiseTex = 0
    private val loc = HashMap<String, Int>()
    private val rotVp = FloatArray(16)
    private val rotView = FloatArray(16)
    private val inv = FloatArray(16)
    private val toGame = FloatArray(9)
    private val eyeG = Vec3()
    private val v = Vec3(); private val r = Vec3()

    private fun u(n: String) = loc.getOrPut(n) { GLES30.glGetUniformLocation(prog, n) }

    private fun init() {
        prog = Gl.compile(VS, FS, "game-sky")
        val ids = IntArray(1); GLES30.glGenVertexArrays(1, ids, 0); vao = ids[0]
        GLES30.glBindVertexArray(vao); FullscreenTriangle.bind(); GLES30.glBindVertexArray(0)
        val size = 256
        noiseTex = Gl.genTexture()
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noiseTex)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA, size, size, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, OceanTextures.noise(size))
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR_MIPMAP_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)
        Gl.repeatTexture(noiseTex)
    }

    override val ownsBackground = true

    override fun background(eye: EyeView) {
        if (prog == 0) init()
        // a rotation-only view: the sky is at infinity, the ground is traced from the eye in the shader
        System.arraycopy(eye.view, 0, rotView, 0, 16)
        rotView[12] = 0f; rotView[13] = 0f; rotView[14] = 0f
        Matrix.multiplyMM(rotVp, 0, eye.proj, 0, rotView, 0)
        Matrix.invertM(inv, 0, rotVp, 0)
        // world directions to game directions (columns: where world x, y, z go)
        for (k in 0..2) {
            v.set(if (k == 0) 1f else 0f, if (k == 1) 1f else 0f, if (k == 2) 1f else 0f)
            host.dirToGame(v, r)
            toGame[k * 3] = r.x; toGame[k * 3 + 1] = r.y; toGame[k * 3 + 2] = r.z
        }
        host.toGame(eye.position, eyeG)
        val l = look
        GLES30.glDisable(GLES30.GL_DEPTH_TEST)
        GLES30.glDepthMask(false)
        GLES30.glDisable(GLES30.GL_CULL_FACE)
        GLES30.glDisable(GLES30.GL_BLEND)
        GLES30.glUseProgram(prog)
        GLES30.glUniformMatrix4fv(u("uInvVP"), 1, false, inv, 0)
        GLES30.glUniformMatrix3fv(u("uToGame"), 1, false, toGame, 0)
        GLES30.glUniform3f(u("uEye"), eyeG.x, eyeG.y, eyeG.z)
        GLES30.glUniform3fv(u("uZenith"), 1, l.zenith, 0)
        GLES30.glUniform3fv(u("uHorizon"), 1, l.horizon, 0)
        GLES30.glUniform3fv(u("uGround"), 1, l.ground, 0)
        val s = l.sunDir; val sl = kotlin.math.sqrt(s[0] * s[0] + s[1] * s[1] + s[2] * s[2])
        GLES30.glUniform3f(u("uSunDir"), s[0] / sl, s[1] / sl, s[2] / sl)
        GLES30.glUniform3fv(u("uSunColor"), 1, l.sunColor, 0)
        GLES30.glUniform1f(u("uSunSize"), l.sunSize)
        GLES30.glUniform1f(u("uStars"), l.stars)
        GLES30.glUniform4fv(u("uNebula"), 1, l.nebula, 0)
        GLES30.glUniform1f(u("uClouds"), l.clouds)
        GLES30.glUniform3fv(u("uCloudColor"), 1, l.cloudColor, 0)
        GLES30.glUniform1i(u("uFloor"), l.floor)
        GLES30.glUniform1f(u("uFloorY"), if (floorY.isNaN()) l.floorY else floorY)
        GLES30.glUniform3fv(u("uFloorColor"), 1, l.floorColor, 0)
        GLES30.glUniform4fv(u("uGrid"), 1, l.grid, 0)
        GLES30.glUniform1f(u("uFog"), l.fog)
        GLES30.glUniform1f(u("uPulse"), pulse)
        GLES30.glUniform1f(u("uTime"), host.time)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noiseTex)
        GLES30.glUniform1i(u("uNoise"), 0)
        GLES30.glBindVertexArray(vao)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindVertexArray(0)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
    }

    override fun afterScene(eye: EyeView) {}

    fun release() {
        if (prog != 0) { GLES30.glDeleteProgram(prog); prog = 0 }
        if (vao != 0) { GLES30.glDeleteVertexArrays(1, intArrayOf(vao), 0); vao = 0 }
        Gl.deleteTexture(noiseTex); noiseTex = 0
        loc.clear()
    }

    companion object {
        const val FLOOR_NONE = 0; const val FLOOR_GRID = 1; const val FLOOR_PLAIN = 2; const val FLOOR_WATER = 3

        val DARK = Look(floatArrayOf(0.02f, 0.02f, 0.04f), floatArrayOf(0.05f, 0.05f, 0.08f), floatArrayOf(0.02f, 0.02f, 0.03f))

        private const val VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uInvVP;
uniform mat3 uToGame;
out vec3 vRay;
void main() {
    vec4 p = uInvVP * vec4(aPos.xy, 1.0, 1.0);
    vRay = uToGame * (p.xyz / p.w);
    gl_Position = vec4(aPos.xy, 0.9999, 1.0);
}"""

        private const val FS = """#version 300 es
precision highp float;
in vec3 vRay;
uniform vec3 uEye;
uniform vec3 uZenith, uHorizon, uGround, uSunDir, uSunColor, uCloudColor, uFloorColor;
uniform float uSunSize, uStars, uClouds, uFloorY, uFog, uPulse, uTime;
uniform vec4 uNebula, uGrid;
uniform int uFloor;
uniform sampler2D uNoise;
out vec4 o;

float hash(vec3 p) { p = fract(p * 0.3183099 + 0.1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }

vec3 sky(vec3 d) {
    float h = d.y;
    vec3 c = h >= 0.0 ? mix(uHorizon, uZenith, pow(min(h * 1.2, 1.0), 0.6)) : mix(uHorizon, uGround, pow(min(-h * 3.0, 1.0), 0.5));
    float sd = max(dot(d, uSunDir), 0.0);
    if (uSunSize > 0.0) c += uSunColor * smoothstep(cos(uSunSize), cos(uSunSize * 0.7), sd) * 3.0;
    c += uSunColor * (pow(sd, 6.0) * 0.25 + pow(sd, 48.0) * 0.5);
    if (uNebula.w > 0.0) {
        vec2 q = d.xz / (abs(d.y) + 0.6) * 0.35 + vec2(0.1 * d.y, 0.0);
        float n = texture(uNoise, q).r * 0.65 + texture(uNoise, q * 2.7 + 0.3).r * 0.35;
        n = smoothstep(0.42, 0.85, n);
        c += uNebula.rgb * n * uNebula.w * (0.6 + 0.4 * texture(uNoise, q * 5.0).a);
    }
    if (uStars > 0.0 && h > -0.15) {
        vec3 p = d * 260.0;
        vec3 cell = floor(p);
        float r = hash(cell);
        if (r > 0.985) {
            float k = 1.0 - length(fract(p) - 0.5) * 2.4;
            c += vec3(0.85, 0.9, 1.0) * max(k, 0.0) * (r - 0.985) * 66.0 * uStars * (0.75 + 0.25 * sin(uTime * 2.0 + r * 90.0));
        }
    }
    if (uClouds > 0.0 && h > 0.0) {
        vec2 q = d.xz / (h + 0.12) * 0.12 + vec2(uTime * 0.004, uTime * 0.002);
        float n = texture(uNoise, q).r * 0.6 + texture(uNoise, q * 2.3 + 0.17).r * 0.3 + texture(uNoise, q * 5.1).r * 0.1;
        float cov = smoothstep(0.62 - uClouds * 0.3, 0.95 - uClouds * 0.2, n);
        float lit = 0.75 + 0.25 * smoothstep(0.4, 1.0, n) + pow(sd, 4.0) * 0.4;
        c = mix(c, uCloudColor * lit, cov * smoothstep(0.0, 0.18, h) * 0.95);
    }
    return c;
}

void main() {
    vec3 d = normalize(vRay);
    vec3 c = sky(d);
    if (uFloor > 0 && d.y < -0.0005 && uEye.y > uFloorY) {
        float t = (uFloorY - uEye.y) / d.y;
        vec3 p = uEye + d * t;
        vec3 g;
        if (uFloor == 1) {
            // a glowing grid: thin lines, wider and dimmer far away (no shimmering)
            vec2 q = p.xz / uGrid.w;
            vec2 w = fwidth(q) * 1.2;
            vec2 a = abs(fract(q - 0.5) - 0.5);
            vec2 line = 1.0 - smoothstep(vec2(0.0), w + 0.015, a);
            float l = max(line.x, line.y) / (1.0 + max(w.x, w.y) * 6.0);
            g = uFloorColor + uGrid.rgb * l * (1.0 + uPulse * 1.5);
        } else if (uFloor == 2) {
            float n = texture(uNoise, p.xz * 0.05).r * 0.6 + texture(uNoise, p.xz * 0.37).a * 0.4;
            g = uFloorColor * (0.75 + 0.5 * n);
            float sd = max(-uSunDir.y, 0.0);
            g *= 0.9 + 0.1 * sd;
        } else {
            // water: two drifting ripple layers bend the mirrored sky, Fresnel picks sky or depth
            vec2 q = p.xz * 0.06;
            vec2 r1 = texture(uNoise, q + vec2(uTime * 0.012, uTime * 0.007)).gb;
            vec2 r2 = texture(uNoise, q * 2.3 - vec2(uTime * 0.01, -uTime * 0.013)).gb;
            vec2 rr = (r1 + r2 - 1.0) * 0.18 / (1.0 + t * 0.02);
            vec3 n = normalize(vec3(rr.x, 1.0, rr.y));
            vec3 rd = reflect(d, n);
            rd.y = abs(rd.y);
            float f = 0.02 + 0.98 * pow(1.0 - max(dot(-d, n), 0.0), 5.0);
            vec3 refl = sky(rd);
            g = mix(uFloorColor, refl, clamp(f, 0.0, 1.0));
            float sp = pow(max(dot(rd, uSunDir), 0.0), 380.0);
            g += uSunColor * sp * 6.0;
        }
        float fog = 1.0 - exp(-t * uFog);
        c = mix(g, sky(normalize(vec3(d.x, 0.001, d.z))), fog);
    }
    o = vec4(c, 1.0);
}"""
    }
}
