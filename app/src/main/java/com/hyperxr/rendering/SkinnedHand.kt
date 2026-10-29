package com.hyperxr.rendering

import android.content.res.AssetManager
import android.opengl.GLES30
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The human arm and hand of the VR mode: one mesh made by tools/make_hand_mesh.py (a right arm;
 * the left is its mirror), moved on the GPU by 19 bones (linear blend skinning), drawn with a skin
 * shader: light that bleeds red under the skin past the shadow line, a soft two-lobe sheen, pores,
 * redder creases, glossy nails, and a T-shirt sleeve.
 */
class SkinnedHand(assets: AssetManager) {
    class Side(val vao: Int, val vbo: Int, val ibo: Int, val count: Int)

    /** Rest joints of the right arm (21 hand joints, elbow, shoulder), metres. */
    val restJoints: FloatArray
    val right: Side
    val left: Side
    private val program: Int
    private val uViewProj: Int; private val uBones: Int; private val uLightDir: Int; private val uLightColor: Int
    private val uAmbient: Int; private val uEye: Int; private val uSkin: Int; private val uCloth: Int; private val uFade: Int

    init {
        val bytes = assets.open(ASSET).use { it.readBytes() }
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        check(b.int == MAGIC) { "hand mesh: bad file" }
        b.int
        val nv = b.int; val ni = b.int; val nj = b.int
        restJoints = FloatArray(nj * 3) { b.float }
        val verts = FloatArray(nv * STRIDE) { b.float }
        val idx = IntArray(ni) { b.int }
        right = upload(verts, idx)
        // the left arm: mirrored across x, triangles wound the other way
        val lv = verts.copyOf()
        for (i in 0 until nv) { lv[i * STRIDE] = -lv[i * STRIDE]; lv[i * STRIDE + 3] = -lv[i * STRIDE + 3] }
        val li = idx.copyOf()
        for (t in 0 until ni / 3) { val x = li[t * 3 + 1]; li[t * 3 + 1] = li[t * 3 + 2]; li[t * 3 + 2] = x }
        left = upload(lv, li)
        program = Gl.compile(VS, FS, "skin")
        fun u(n: String) = GLES30.glGetUniformLocation(program, n)
        uViewProj = u("uViewProj"); uBones = u("uBones"); uLightDir = u("uLightDir"); uLightColor = u("uLightColor")
        uAmbient = u("uAmbient"); uEye = u("uEye"); uSkin = u("uSkin"); uCloth = u("uCloth"); uFade = u("uFade")
    }

    private fun upload(v: FloatArray, i: IntArray): Side {
        val ids = IntArray(3)
        GLES30.glGenVertexArrays(1, ids, 0); GLES30.glGenBuffers(2, ids, 1)
        GLES30.glBindVertexArray(ids[0])
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, ids[1])
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, v.size * 4, Gl.floatBuffer(v.size).put(v).also { it.position(0) }, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ids[2])
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, i.size * 4, Gl.intBuffer(i.size).put(i).also { it.position(0) }, GLES30.GL_STATIC_DRAW)
        val s = STRIDE * 4
        GLES30.glEnableVertexAttribArray(0); GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, s, 0)
        GLES30.glEnableVertexAttribArray(1); GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, s, 12)
        GLES30.glEnableVertexAttribArray(2); GLES30.glVertexAttribPointer(2, 3, GLES30.GL_FLOAT, false, s, 24)
        GLES30.glEnableVertexAttribArray(3); GLES30.glVertexAttribPointer(3, 4, GLES30.GL_FLOAT, false, s, 36)
        GLES30.glEnableVertexAttribArray(4); GLES30.glVertexAttribPointer(4, 4, GLES30.GL_FLOAT, false, s, 52)
        GLES30.glBindVertexArray(0)
        return Side(ids[0], ids[1], ids[2], i.size)
    }

    /** Draws one arm with its [bones] (BONES column-major 4x4 skinning matrices). GL thread. */
    fun draw(side: Side, bones: FloatArray, eye: EyeView, light: FloatArray, lightColor: FloatArray, ambient: FloatArray,
             skin: FloatArray, cloth: FloatArray, fade: Float) {
        GLES30.glUseProgram(program)
        GLES30.glUniformMatrix4fv(uViewProj, 1, false, eye.viewProj, 0)
        GLES30.glUniformMatrix4fv(uBones, BONES, false, bones, 0)
        GLES30.glUniform3fv(uLightDir, 1, light, 0)
        GLES30.glUniform3fv(uLightColor, 1, lightColor, 0)
        GLES30.glUniform3fv(uAmbient, 1, ambient, 0)
        GLES30.glUniform3f(uEye, eye.position.x, eye.position.y, eye.position.z)
        GLES30.glUniform3fv(uSkin, 1, skin, 0)
        GLES30.glUniform3fv(uCloth, 1, cloth, 0)
        GLES30.glUniform1f(uFade, fade)
        GLES30.glBindVertexArray(side.vao)
        GLES30.glDrawElements(GLES30.GL_TRIANGLES, side.count, GLES30.GL_UNSIGNED_INT, 0)
        GLES30.glBindVertexArray(0)
    }

    companion object {
        const val ASSET = "models/hand_skin.bin"
        const val BONES = 19
        private const val STRIDE = 17
        private const val MAGIC = 0x4D485848

        private const val VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec3 aMat;     // nail, sleeve, ambient occlusion
layout(location=3) in vec4 aBones;
layout(location=4) in vec4 aWeights;
uniform mat4 uViewProj;
uniform mat4 uBones[19];
out vec3 vWorld;
out vec3 vNormal;
out vec3 vMat;
out vec3 vLocal;
void main() {
    mat4 m = uBones[int(aBones.x)] * aWeights.x + uBones[int(aBones.y)] * aWeights.y
           + uBones[int(aBones.z)] * aWeights.z + uBones[int(aBones.w)] * aWeights.w;
    vec4 w = m * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNormal = mat3(m) * aNormal;
    vMat = aMat;
    vLocal = aPos;
    gl_Position = uViewProj * w;
}"""

        private const val FS = """#version 300 es
precision highp float;
in vec3 vWorld;
in vec3 vNormal;
in vec3 vMat;
in vec3 vLocal;
uniform vec3 uLightDir;
uniform vec3 uLightColor;
uniform vec3 uAmbient;
uniform vec3 uEye;
uniform vec3 uSkin;
uniform vec3 uCloth;
uniform float uFade;
out vec4 fragColor;
float hash(vec3 p) { p = fract(p * 0.3183099 + 0.1); p *= 17.0; return fract(p.x * p.y * p.z * (p.x + p.y + p.z)); }
float noise(vec3 x) {
    vec3 i = floor(x); vec3 f = fract(x); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(hash(i), hash(i + vec3(1, 0, 0)), f.x), mix(hash(i + vec3(0, 1, 0)), hash(i + vec3(1, 1, 0)), f.x), f.y),
               mix(mix(hash(i + vec3(0, 0, 1)), hash(i + vec3(1, 0, 1)), f.x), mix(hash(i + vec3(0, 1, 1)), hash(i + vec3(1, 1, 1)), f.x), f.y), f.z);
}
void main() {
    vec3 n = normalize(vNormal);
    if (!gl_FrontFacing) n = -n;
    float nail = vMat.x, cloth = vMat.y, ao = vMat.z;
    // detail in the arm's own (rest) space, so pores and fibres stay on the skin as it moves
    float pores = noise(vLocal * 1100.0) * 0.55 + noise(vLocal * 300.0) * 0.45;
    float blotch = noise(vLocal * 60.0);
    vec3 l = normalize(-uLightDir);
    vec3 v = normalize(uEye - vWorld);
    float ndl = dot(n, l);
    float ndv = max(dot(n, v), 0.0);
    vec3 skin = uSkin * (0.92 + 0.1 * pores + 0.06 * blotch);
    // creases and knuckles a little redder and darker, nails pale pink with a white edge
    skin = mix(skin, skin * vec3(1.02, 0.82, 0.78), (1.0 - ao) * 0.9);
    vec3 nailC = mix(uSkin * vec3(1.05, 0.93, 0.92), vec3(0.97, 0.9, 0.88), 0.55);
    vec3 base = mix(skin, nailC, nail);
    float lam = max(ndl, 0.0);
    float wrap = clamp((ndl + 0.45) / 1.45, 0.0, 1.0);
    // subsurface: light carries on under the skin past the shadow line, reddened by blood
    vec3 diffuse = vec3(lam) + vec3(0.62, 0.2, 0.1) * max(wrap - lam, 0.0) * 1.5;
    vec3 amb = mix(uAmbient * 0.6, uAmbient * 1.15, n.y * 0.5 + 0.5) * (0.35 + 0.65 * ao);
    vec3 h = normalize(l + v);
    float ndh = max(dot(n, h), 0.0);
    float fres = 0.028 + 0.972 * pow(1.0 - ndv, 5.0);
    float spec = (pow(ndh, 24.0) * 0.16 + pow(ndh, 96.0) * (0.22 + 1.4 * nail)) * (0.65 + 0.7 * pores) * (0.4 + fres * 5.0);
    vec3 skinCol = base * (diffuse * uLightColor + amb) + uLightColor * spec * lam * ao;
    // light through the thin edges (fingers against the light) and fine hairs at the rim
    skinCol += base * vec3(1.0, 0.45, 0.35) * pow(1.0 - ndv, 3.0) * 0.16 * (0.5 + 0.5 * max(-ndl, 0.0) + 0.5 * lam);
    vec3 fab = uCloth * (0.88 + 0.24 * noise(vLocal * 1600.0));
    vec3 clothCol = fab * (wrap * 0.9 * uLightColor + amb) + fab * pow(1.0 - ndv, 2.0) * 0.3;
    vec3 c = mix(skinCol, clothCol, cloth);
    fragColor = vec4(c * uFade, 1.0);
}"""
    }
}
