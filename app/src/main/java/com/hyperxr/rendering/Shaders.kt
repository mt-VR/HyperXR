package com.hyperxr.rendering

/** GLSL ES 3.00 sources. Kept small: mobile GPUs pay for every ALU op twice in stereo. */
object Shaders {
    const val LIT_VS = """#version 300 es
layout(location=0) in vec3 aPos;
layout(location=1) in vec3 aNormal;
layout(location=2) in vec2 aUv;
uniform mat4 uMvp;
uniform mat4 uModel;
uniform mat4 uLightVP;
out vec3 vNormal;
out vec2 vUv;
out vec3 vWorld;
out vec4 vShadow;
void main() {
    vec4 w = uModel * vec4(aPos, 1.0);
    vWorld = w.xyz;
    vShadow = uLightVP * w;
    vNormal = mat3(uModel) * aNormal;
    vUv = aUv;
    gl_Position = uMvp * vec4(aPos, 1.0);
}"""

    const val LIT_FS = """#version 300 es
precision mediump float;
precision mediump sampler2DShadow;
in vec3 vNormal;
in vec2 vUv;
in vec3 vWorld;
in vec4 vShadow;
uniform sampler2DShadow uShadowMap;
uniform int uShadowOn;
uniform float uGloss;   // 0 matte .. 1 polished
uniform float uMetal;   // 0 dielectric .. 1 metal (tinted highlights)
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
    // shadow map with 4-tap PCF (soft contact shadows on the bench)
    float lit = 1.0;
    if (uShadowOn == 1 && diff > 0.0) {
        vec3 s = vShadow.xyz / vShadow.w * 0.5 + 0.5;
        if (s.x > 0.0 && s.x < 1.0 && s.y > 0.0 && s.y < 1.0 && s.z < 1.0) {
            float bias = 0.0015 + 0.003 * (1.0 - diff);
            vec2 t = vec2(1.0 / 2048.0);
            lit = 0.25 * (texture(uShadowMap, vec3(s.xy + vec2(-t.x, -t.y), s.z - bias)) +
                          texture(uShadowMap, vec3(s.xy + vec2( t.x, -t.y), s.z - bias)) +
                          texture(uShadowMap, vec3(s.xy + vec2(-t.x,  t.y), s.z - bias)) +
                          texture(uShadowMap, vec3(s.xy + vec2( t.x,  t.y), s.z - bias)));
        }
    }
    // hemispheric ambient: sky from above, darker bounce from below
    vec3 amb = mix(uAmbient * 0.55, uAmbient, n.y * 0.5 + 0.5);
    vec3 v = normalize(uEye - vWorld);
    float ndv = max(dot(n, v), 0.0);
    float rim = pow(1.0 - ndv, 3.0) * 0.18;
    // Blinn-Phong highlight with Schlick fresnel; metals tint the highlight with their colour
    vec3 hv = normalize(l + v);
    float shininess = mix(8.0, 160.0, uGloss);
    float spec = pow(max(dot(n, hv), 0.0), shininess) * (shininess + 8.0) / 25.0;
    float fres = 0.04 + 0.96 * pow(1.0 - ndv, 5.0);
    vec3 specColor = mix(vec3(mix(0.04, 1.0, fres)), base.rgb, uMetal) * uGloss;
    vec3 albedo = base.rgb * (1.0 - 0.85 * uMetal);
    vec3 c = albedo * (amb + uLightColor * diff * lit) + specColor * spec * uLightColor * lit * diff + rim * (1.0 - uMetal) + base.rgb * amb * uMetal * 0.6;
    fragColor = vec4(c, base.a);
}"""

    /** Depth-only pass for the shadow map. */
    const val DEPTH_VS = """#version 300 es
layout(location=0) in vec3 aPos;
uniform mat4 uMvp;
void main() { gl_Position = uMvp * vec4(aPos, 1.0); }"""

    const val DEPTH_FS = """#version 300 es
precision mediump float;
out vec4 fragColor;
void main() { fragColor = vec4(1.0); }"""

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

    /**
     * The user's real hands over the virtual UI: the camera image where the hand mask (NPU
     * segmentation, in CPU-image coordinates like the depth map) says there is a hand.
     */
    const val HANDCUT_FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec2 vUv;
in vec2 vDepthUv;
uniform samplerExternalOES uTex;
uniform sampler2D uMask;
out vec4 fragColor;
void main() {
    float a = smoothstep(0.30, 0.60, texture(uMask, vDepthUv).r);
    if (a < 0.01) discard;
    fragColor = vec4(texture(uTex, vUv).rgb, a);
}"""

    /** Darkens the whole view (dimmed passthrough behind the UI). */
    const val DIM_VS = """#version 300 es
layout(location=0) in vec3 aPos;
void main() { gl_Position = vec4(aPos.xy, 0.999, 1.0); }"""

    const val DIM_FS = """#version 300 es
precision mediump float;
uniform float uDim;
out vec4 fragColor;
void main() { fragColor = vec4(0.0, 0.0, 0.0, uDim); }"""

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
uniform vec4 uRect;
out vec4 fragColor;
void main() {
    vec3 d = normalize(vDir);
    float u = 0.5 + atan(d.x, -d.z) / 6.2831853;
    float v = 0.5 - asin(clamp(d.y, -1.0, 1.0)) / 3.1415926;
    vec2 uv = uRect.xy + vec2(u, v) * uRect.zw;
    // explicit gradients: the atan seam would otherwise pick the smallest mip level there
    vec2 g = vec2(1.0 / 6.2831853, 1.0 / 3.1415926) * uRect.zw * length(fwidth(d));
    fragColor = vec4(textureGrad(uTex, uv, vec2(g.x, 0.0), vec2(0.0, g.y)).rgb, 1.0);
}"""

    /** Same for a video frame (SurfaceTexture); [uTexMatrix] maps top-left-origin UVs to it. */
    const val PANO_OES_FS = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
in vec3 vDir;
uniform samplerExternalOES uTex;
uniform vec4 uRect;
uniform mat4 uTexMatrix;
out vec4 fragColor;
void main() {
    vec3 d = normalize(vDir);
    float u = 0.5 + atan(d.x, -d.z) / 6.2831853;
    float v = 0.5 - asin(clamp(d.y, -1.0, 1.0)) / 3.1415926;
    vec2 uv = uRect.xy + vec2(u, v) * uRect.zw;
    fragColor = vec4(texture(uTex, (uTexMatrix * vec4(uv, 0.0, 1.0)).xy).rgb, 1.0);
}"""

    const val FULLSCREEN_VS = """#version 300 es
layout(location=0) in vec3 aPos;
out vec2 vUv;
void main() { vUv = aPos.xy * 0.5 + 0.5; gl_Position = vec4(aPos.xy, 0.0, 1.0); }"""

    /**
     * Lens distortion (Brown-Conrady radial) from an eye texture to one half of the screen, with
     * rotational timewarp: [uWarp] turns a view direction of the head as it is now into the
     * frame the eye texture was rendered with, so the image follows a head turn that happened
     * after rendering (sampled from the gyroscope right before this pass).
     */
    const val DISTORT_FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uLensCenter;   // in output-viewport UV
uniform vec2 uScale;        // output UV -> lens tangent units
uniform vec2 uScaleIn;      // tangent units -> eye texture UV
uniform vec2 uK;            // k1, k2
uniform float uChroma;
uniform mat3 uWarp;
out vec4 fragColor;
vec2 warp(vec2 t, float scale) {
    float r2 = dot(t, t);
    return t * (1.0 + uK.x * r2 + uK.y * r2 * r2) * scale;
}
vec2 toTex(vec2 tan) {
    vec3 r = uWarp * vec3(tan, -1.0);
    return (r.xy / max(-r.z, 1e-3)) * uScaleIn + 0.5;
}
void main() {
    vec2 t = (vUv - uLensCenter) * uScale;
    vec2 w = warp(t, 1.0);
    // the lens edge stays fixed on the screen; the image inside it is what moves
    vec2 edge = w * uScaleIn + 0.5;
    if (any(lessThan(edge, vec2(0.0))) || any(greaterThan(edge, vec2(1.0)))) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    vec2 g = toTex(w);
    vec2 rUv = toTex(warp(t, 1.0 - uChroma));
    vec2 bUv = toTex(warp(t, 1.0 + uChroma));
    fragColor = vec4(texture(uTex, rUv).r, texture(uTex, g).g, texture(uTex, bUv).b, 1.0);
}"""

    const val COPY_FS = """#version 300 es
precision mediump float;
in vec2 vUv;
uniform sampler2D uTex;
out vec4 fragColor;
void main() { fragColor = vec4(texture(uTex, vUv).rgb, 1.0); }"""

    /** Copy without lens distortion, with the same rotational timewarp as [DISTORT_FS]. */
    const val COPY_WARP_FS = """#version 300 es
precision highp float;
in vec2 vUv;
uniform sampler2D uTex;
uniform vec2 uTan;          // tangent half-extent of the eye texture
uniform mat3 uWarp;
out vec4 fragColor;
void main() {
    vec3 r = uWarp * vec3((vUv * 2.0 - 1.0) * uTan, -1.0);
    vec2 uv = (r.xy / max(-r.z, 1e-3)) / uTan * 0.5 + 0.5;
    fragColor = vec4(texture(uTex, uv).rgb, 1.0);
}"""
}
