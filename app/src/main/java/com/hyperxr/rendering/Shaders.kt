package com.hyperxr.rendering

/** GLSL ES 3.00 sources. Kept small: mobile GPUs pay for every ALU op twice in stereo. */
object Shaders {
    const val LIT_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec2 aUv;
uniform mat4 uMvp;
uniform mat4 uModel;
out vec3 vNormal;
out vec2 vUv;
out vec3 vWorld;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vNormal = mat3(uModel) * aNormal;
    vUv = aUv;
    gl_Position = uMvp * vec4(aPos, 1.0);
}"""

    const val LIT_FS = """#version 300 es
precision mediump float;
in vec3 vNormal;
in vec2 vUv;
in vec3 vWorld;
uniform vec4 uColor;
uniform sampler2D uTex;
uniform int uHasTex;
uniform int uUnlit;
uniform vec3 uLightDir;
uniform vec3 uLightColor;
uniform vec3 uAmbient;
uniform vec3 uEye;
uniform float uAlphaCutoff;
out vec4 fragColor;
void main() {
    vec4 base = uColor;
    if (uHasTex == 1) base *= texture(uTex, vUv);
    if (base.a < uAlphaCutoff) discard;
    if (uUnlit == 1) { fragColor = base; return; }
    vec3 n = normalize(vNormal);
    if (!gl_FrontFacing) n = -n;
    vec3 l = normalize(-uLightDir);
    float diff = max(dot(n, l), 0.0);
    // hemispheric ambient: sky from above, darker bounce from below
    vec3 amb = mix(uAmbient * 0.55, uAmbient, n.y * 0.5 + 0.5);
    vec3 v = normalize(uEye - vWorld);
    float rim = pow(1.0 - max(dot(n, v), 0.0), 3.0) * 0.18;
    vec3 c = base.rgb * (amb + uLightColor * diff) + rim;
    fragColor = vec4(c, base.a);
}"""

    /** Textured world quad (panels, icons, labels) with rounded corners and hover glow. */
    const val QUAD_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec2 aUv;
uniform mat4 uMvp;
out vec2 vUv;
void main() { vUv = aUv; gl_Position = uMvp * vec4(aPos, 1.0); }"""

    const val QUAD_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
uniform float uAlpha;
uniform float uRadius;
uniform float uAspect;
uniform float uGlow;
uniform vec4 uTint;
out vec4 fragColor;
void main() {
    vec2 p = (vUv - 0.5) * vec2(uAspect, 1.0);
    vec2 h = vec2(uAspect, 1.0) * 0.5 - uRadius;
    float d = length(max(abs(p) - h, 0.0)) - uRadius;
    float aa = fwidth(d);
    float mask = 1.0 - smoothstep(-aa, aa, d);
    vec4 c = texture(uTex, vUv) * uTint;
    float glow = uGlow * (1.0 - smoothstep(0.0, 0.06, abs(d + 0.012)));
    fragColor = vec4(c.rgb + glow, c.a * mask * uAlpha + glow * 0.6 * mask);
}"""

    /** Camera image from SurfaceTexture / ARCore (GL_TEXTURE_EXTERNAL_OES). */
    const val OES_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec2 aUv;
uniform mat4 uMvp;
uniform mat4 uTexMatrix;
out vec2 vUv;
void main() { vUv = (uTexMatrix * vec4(aUv, 0.0, 1.0)).xy; gl_Position = uMvp * vec4(aPos, 1.0); }"""

    const val OES_FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vUv;
uniform samplerExternalOES uTex;
uniform float uAlpha;
out vec4 fragColor;
void main() { fragColor = vec4(texture(uTex, vUv).rgb, uAlpha); }"""

    /** Camera image covering the whole eye viewport (centre crop to the eye aspect). */
    const val OES_FULL_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uTexMatrix;
uniform vec2 uCrop;
out vec2 vUv;
void main() {
    vec2 uv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);   // top-left origin like the camera quad
    uv = 0.5 + (uv - 0.5) * uCrop;
    vUv = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
    gl_Position = vec4(aPos.xy, 0.999, 1.0);
}"""

    /**
     * Boundary warning ("Guardian"): the camera image over the VR scene, shown where real obstacles
     * are close. Depth (centimetres in an R8 texture) shares the camera's view-normalized mapping.
     */
    const val GUARD_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uTexMatrix;
uniform mat4 uDepthMatrix;
uniform vec2 uCrop;
out vec2 vUv;
out vec2 vDepthUv;
void main() {
    vec2 uv = vec2(aPos.x * 0.5 + 0.5, 0.5 - aPos.y * 0.5);
    uv = 0.5 + (uv - 0.5) * uCrop;
    vUv = (uTexMatrix * vec4(uv, 0.0, 1.0)).xy;
    vDepthUv = (uDepthMatrix * vec4(uv, 0.0, 1.0)).xy;
    gl_Position = vec4(aPos.xy, 0.0, 1.0);
}"""

    const val GUARD_FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vUv;
in vec2 vDepthUv;
uniform samplerExternalOES uTex;
uniform sampler2D uDepth;
uniform float uWarn;     // metres: fading starts
uniform float uFull;     // metres: fully visible
uniform float uGlobal;   // overall fade from the nearest obstacle
out vec4 fragColor;
void main() {
    float cm = texture(uDepth, vDepthUv).r * 255.0;
    float valid = step(0.5, cm) * step(cm, 254.5);
    float local = valid * (1.0 - smoothstep(uFull, uWarn, cm / 100.0));
    float a = max(local, uGlobal * 0.6);
    if (a < 0.01) discard;
    vec3 cam = texture(uTex, vUv).rgb;
    // a faint violet edge where the warning begins, so the boundary reads even in dark rooms
    vec3 tint = mix(cam, vec3(0.62, 0.55, 1.0), 0.18 * (1.0 - local));
    fragColor = vec4(tint, a);
}"""

    /** Gradient sky for the procedural studio environment. */
    const val SKY_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec2 aUv;
uniform mat4 uMvp;
out vec3 vDir;
void main() { vDir = aPos; gl_Position = (uMvp * vec4(aPos, 1.0)).xyww; }"""

    const val SKY_FS = """#version 300 es
precision mediump float;
in vec3 vDir;
uniform vec3 uTop;
uniform vec3 uHorizon;
uniform vec3 uBottom;
uniform vec3 uSunDir;
out vec4 fragColor;
void main() {
    vec3 d = normalize(vDir);
    vec3 c = d.y > 0.0 ? mix(uHorizon, uTop, pow(d.y, 0.6)) : mix(uHorizon, uBottom, pow(-d.y, 0.4));
    float sun = pow(max(dot(d, -uSunDir), 0.0), 400.0);
    fragColor = vec4(c + sun * vec3(1.0, 0.9, 0.7), 1.0);
}"""

    /** Equirectangular 360° panorama on an inverted sphere. */
    const val PANO_FS = """#version 300 es
precision mediump float;
in vec3 vDir;
uniform sampler2D uTex;
out vec4 fragColor;
void main() {
    vec3 d = normalize(vDir);
    float u = 0.5 + atan(d.x, -d.z) / 6.2831853;
    float v = 0.5 - asin(clamp(d.y, -1.0, 1.0)) / 3.1415926;
    fragColor = vec4(texture(uTex, vec2(u, v)).rgb, 1.0);
}"""

    /** Hand aura field: capsule SDF per bone, max-blended into a single-channel target. */
    const val AURA_FIELD_VS = """#version 300 es
layout(location=0) in vec3 aPos;      // view-space position on the bone billboard
layout(location=1) in vec3 aNormal;   // packed: segment endpoint A (view space)
layout(location=2) in vec2 aUv;       // radius, aura width
layout(location=3) in vec3 aB;        // segment endpoint B (view space)
uniform mat4 uProj;
out vec3 vP; out vec3 vA; out vec3 vB; out vec2 vR;
void main() { vP = aPos; vA = aNormal; vB = aB; vR = aUv; gl_Position = uProj * vec4(aPos, 1.0); }"""

    const val AURA_FIELD_FS = """#version 300 es
precision highp float;
in vec3 vP; in vec3 vA; in vec3 vB; in vec2 vR;
out vec4 fragColor;
void main() {
    vec3 ab = vB - vA;
    float t = clamp(dot(vP - vA, ab) / max(dot(ab, ab), 1e-8), 0.0, 1.0);
    // distance in the billboard plane approximates the capsule silhouette distance
    vec3 c = vA + ab * t;
    float d = length((vP - c).xy);
    // 0.5 at the hand surface, 1 deep inside, 0 at the outer edge of the aura
    float v = clamp(0.5 + (vR.x - d) / (2.0 * vR.y), 0.0, 1.0);
    fragColor = vec4(v, 0.0, 0.0, 1.0);
}"""

    const val FULLSCREEN_VS = """#version 300 es
layout(location=0) in vec3 aPos;
out vec2 vUv;
void main() { vUv = aPos.xy * 0.5 + 0.5; gl_Position = vec4(aPos.xy, 0.0, 1.0); }"""

    /** Black aura composite: soft dark rim, translucent black interior, nothing outside. */
    const val AURA_COMPOSITE_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uField;
uniform float uOpacity;
out vec4 fragColor;
void main() {
    float v = texture(uField, vUv).r;
    float rim = smoothstep(0.0, 0.5, v);
    float a = v <= 0.5 ? 0.9 * rim * rim : mix(0.9, 0.38, smoothstep(0.5, 0.72, v));
    fragColor = vec4(0.0, 0.0, 0.0, a * uOpacity);
}"""

    /** Lens distortion (Brown-Conrady radial) from an eye texture to one half of the screen. */
    const val DISTORT_FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uLensCenter;   // in output-viewport UV
uniform vec2 uScale;        // output UV -> lens tangent units
uniform vec2 uScaleIn;      // tangent units -> eye texture UV
uniform vec2 uK;            // k1, k2
uniform float uChroma;
out vec4 fragColor;
vec2 warp(vec2 t, float scale) {
    float r2 = dot(t, t);
    return t * (1.0 + uK.x * r2 + uK.y * r2 * r2) * scale;
}
void main() {
    vec2 t = (vUv - uLensCenter) * uScale;
    vec2 g = warp(t, 1.0) * uScaleIn + 0.5;
    if (any(lessThan(g, vec2(0.0))) || any(greaterThan(g, vec2(1.0)))) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    vec2 rUv = warp(t, 1.0 - uChroma) * uScaleIn + 0.5;
    vec2 bUv = warp(t, 1.0 + uChroma) * uScaleIn + 0.5;
    fragColor = vec4(texture(uTex, rUv).r, texture(uTex, g).g, texture(uTex, bUv).b, 1.0);
}"""

    const val COPY_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
out vec4 fragColor;
void main() { fragColor = vec4(texture(uTex, vUv).rgb, 1.0); }"""
}
