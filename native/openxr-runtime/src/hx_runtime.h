// HyperXR OpenXR runtime: internal interfaces.
//
// The runtime is loaded by the Khronos OpenXR loader into the application's own process (no IPC),
// so it is written without the C++ standard library: it must not depend on a libc++_shared.so
// that the host application may not have, or may have in another version.
#pragma once

#include <stdint.h>
#include <stddef.h>
#include <math.h>

#ifdef __ANDROID__
#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <jni.h>
#include <EGL/egl.h>
#endif
#define XR_USE_TIMESPEC
#include <time.h>
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#include "hx_hands.h"
#include "hx_camera.h"

// ------------------------------------------------------------------ math
namespace hx {

inline XrQuaternionf qmul(XrQuaternionf a, XrQuaternionf b) {
    return {a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y,
            a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
            a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w,
            a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z};
}
inline XrQuaternionf qconj(XrQuaternionf q) { return {-q.x, -q.y, -q.z, q.w}; }
inline XrQuaternionf qnorm(XrQuaternionf q) {
    float n = sqrtf(q.x * q.x + q.y * q.y + q.z * q.z + q.w * q.w);
    if (n < 1e-12f) return {0, 0, 0, 1};
    return {q.x / n, q.y / n, q.z / n, q.w / n};
}
inline XrQuaternionf qaxis(float ax, float ay, float az, float angle) {
    float n = sqrtf(ax * ax + ay * ay + az * az);
    if (n < 1e-12f) return {0, 0, 0, 1};
    float s = sinf(angle / 2) / n;
    return {ax * s, ay * s, az * s, cosf(angle / 2)};
}
inline XrVector3f qrot(XrQuaternionf q, XrVector3f v) {
    // t = 2 * cross(q.xyz, v); v' = v + w*t + cross(q.xyz, t)
    float tx = 2 * (q.y * v.z - q.z * v.y), ty = 2 * (q.z * v.x - q.x * v.z), tz = 2 * (q.x * v.y - q.y * v.x);
    return {v.x + q.w * tx + (q.y * tz - q.z * ty), v.y + q.w * ty + (q.z * tx - q.x * tz), v.z + q.w * tz + (q.x * ty - q.y * tx)};
}
inline XrVector3f vadd(XrVector3f a, XrVector3f b) { return {a.x + b.x, a.y + b.y, a.z + b.z}; }
inline XrPosef identity() { XrPosef p; p.orientation = {0, 0, 0, 1}; p.position = {0, 0, 0}; return p; }
/** a ∘ b: b expressed in a's frame, mapped to a's parent. */
inline XrPosef compose(XrPosef a, XrPosef b) {
    XrPosef r;
    r.orientation = qnorm(qmul(a.orientation, b.orientation));
    r.position = vadd(a.position, qrot(a.orientation, b.position));
    return r;
}
inline XrPosef inverse(XrPosef a) {
    XrPosef r;
    r.orientation = qconj(a.orientation);
    XrVector3f p = qrot(r.orientation, a.position);
    r.position = {-p.x, -p.y, -p.z};
    return r;
}
/** Rotation about world up that keeps only the heading of q (its -Z axis projected on the floor). */
inline XrQuaternionf yawOnly(XrQuaternionf q) {
    XrVector3f f = qrot(q, {0, 0, -1});
    return qaxis(0, 1, 0, atan2f(-f.x, -f.z));
}
inline bool poseValid(const XrPosef& p) {
    float n = p.orientation.x * p.orientation.x + p.orientation.y * p.orientation.y + p.orientation.z * p.orientation.z + p.orientation.w * p.orientation.w;
    return fabsf(n - 1.0f) < 0.01f && isfinite(p.position.x) && isfinite(p.position.y) && isfinite(p.position.z);
}

}  // namespace hx

// ------------------------------------------------------------------ platform layer
/** Viewer description; on Android it comes from the HyperXR app settings (content provider). */
struct HxConfig {
    float ipdM;          // interpupillary distance
    float fovDeg;        // vertical field of view per eye
    float k1, k2;        // radial lens distortion
    float eyeHeightM;    // standing eye height (stage space)
    int eyeWidthPx;      // recommended per-eye image size
    int eyeHeightPx;
    float refreshHz;
    int lensDistortion;  // 0/1
};

/**
 * Controller input as the runtime sees it inside the app: a gamepad / Joy-Cons (Android input
 * events captured by the runtime's window) and the screen touch (the VR Box button).
 */
enum : uint32_t {
    HX_BTN_A = 1u << 0, HX_BTN_B = 1u << 1, HX_BTN_X = 1u << 2, HX_BTN_Y = 1u << 3,
    HX_BTN_MENU = 1u << 4, HX_BTN_SYSTEM = 1u << 5, HX_BTN_THUMB_L = 1u << 6, HX_BTN_THUMB_R = 1u << 7,
};
struct HxInput {
    uint32_t buttons;     // HX_BTN_*
    float stick[2][2];    // [left/right][x, y], y up
    float trigger[2];
    float squeeze[2];
    int touch;            // screen touched
};

/** A saved spatial anchor: its UUID and where it is (stage space). */
struct HxSavedAnchor {
    uint8_t uuid[16];
    XrPosef pose;
};

struct HxSwapchainGfx;  // platform swapchain data
struct HxSessionGfx;    // platform compositor data

/** One projection view handed to the compositor. */
struct HxComposeView {
    HxSwapchainGfx* swapchain;
    uint32_t imageIndex;
    uint32_t arraySlice;
    XrRect2Di rect;
    XrPosef pose;        // pose the app rendered with (stage space)
    XrFovf fov;
    int blendAlpha;      // drawn over what is behind it (passthrough) with the image's alpha
    int premultiplied;   // the alpha is already multiplied into the colour (OpenXR's default)
    int blend[4];        // XR_FB_composition_layer_alpha_blend factors (XrBlendFactorFB), -1: none
};

/** Passthrough for one frame: the phone's camera behind the app's layers, and its colour style. */
enum HxColorMode : int { HX_COLOR_NONE = 0, HX_COLOR_MAP = 1, HX_COLOR_BCS = 2, HX_COLOR_LUT = 3 };
struct HxPassthroughState {
    int on;
    float opacity;
    int colorMode;               // HxColorMode
    const uint8_t* map;          // HX_COLOR_MAP: 256 RGBA8 entries indexed by the camera's luminance
    float brightness, contrast, saturation;  // HX_COLOR_BCS (Meta's units: -100..100, x, x)
    const uint8_t* lutA;         // HX_COLOR_LUT: RGBA8 cube of lutRes^3 (null: the camera's own colour)
    const uint8_t* lutB;
    int lutRes;
    float lutWeight;             // mix from A to B
    uint32_t version;            // changes whenever the map or a LUT's contents change
};

/** Shape of a non-projection layer. */
enum HxLayerShape : int { HX_SHAPE_QUAD = 0, HX_SHAPE_CYLINDER = 1, HX_SHAPE_EQUIRECT = 2 };

/** A quad, cylinder or equirect layer handed to the compositor. */
struct HxComposeQuad {
    HxSwapchainGfx* swapchain;
    uint32_t imageIndex;
    uint32_t arraySlice;
    XrRect2Di rect;
    XrPosef pose;        // stage space, or head space when headLocked
    XrExtent2Df size;    // quad: width and height in metres
    int headLocked;
    int blendAlpha;
    int shape;           // HxLayerShape
    int eye;             // -1 both, 0 left only, 1 right only (eyeVisibility)
    // cylinder: radius, central angle, width / height; equirect: radius (0 = infinitely far),
    // central horizontal angle, upper and lower vertical angles
    float radius, centralAngle, aspectRatio, upperAngle, lowerAngle;
    int blend[4];        // XR_FB_composition_layer_alpha_blend factors (XrBlendFactorFB), -1: none
};

namespace hxp {
int64_t nowNs();                                   // XrTime clock (CLOCK_MONOTONIC)
void init(void* vm, void* context);                // JavaVM*, jobject (Android); no-op elsewhere
void loadConfig(HxConfig* out);
void trackerStart();
void trackerStop();
/** Head pose in stage space (floor at y = 0) predicted for [timeNs]; returns false when untracked. */
bool headPose(int64_t timeNs, const HxConfig& cfg, XrPosef* out);
void recenter();
/** Latest controller input (zeros when nothing is connected). */
void readInput(HxInput* out);
/**
 * Consistent copy of the hands HyperMagic publishes (hx_hands.h); false when the app's hand
 * bridge is not reachable. [wantsHands]: tell the app a game has a hand tracker, so it keeps the
 * camera on (it turns it off a few seconds after the last call that wanted hands).
 */
bool readHands(HxHandShared* out, bool wantsHands);
/** A vibration for the controller of [hand] (amplitude 0 stops it), played by HyperMagic. */
void haptic(int hand, float amplitude, int64_t durationNs);
/** The app shows passthrough this frame: HyperMagic keeps the camera frames coming. */
void passthroughWanted();
/** Spatial anchors the app saved (XR_FB_spatial_entity_storage), kept in the app's own files. */
int loadAnchors(HxSavedAnchor* out, int capacity);
void saveAnchors(const HxSavedAnchor* anchors, int count);
/** The room outline HyperMagic knows (HyperGuardian's area, floor x/z in metres); false: none. */
bool loadRoom(float* x, float* z, int capacity, int* count, float* height);
void randomBytes(void* out, size_t size);

// Graphics. All return XrResult; the host build only supports headless sessions.
XrResult gfxCreateSession(const void* binding, HxSessionGfx** out);
void gfxDestroySession(HxSessionGfx* g);
XrResult gfxEnumerateFormats(int64_t* formats, uint32_t capacity, uint32_t* count);
XrResult gfxCreateSwapchain(HxSessionGfx* g, const XrSwapchainCreateInfo* ci, HxSwapchainGfx** out, uint32_t* imageCount);
void gfxDestroySwapchain(HxSwapchainGfx* s);
XrResult gfxEnumerateImages(HxSwapchainGfx* s, uint32_t capacity, uint32_t* count, XrSwapchainImageBaseHeader* images);
/** Called when the app releases an image (rendering into it is complete on the app's context). */
void gfxReleaseImage(HxSwapchainGfx* s, uint32_t index);
/** Composites one frame (projection views + quads) to the viewer, predicted head pose [head]. */
void gfxCompose(HxSessionGfx* g, const HxConfig& cfg, const XrPosef& head, const HxComposeView* views, uint32_t viewCount,
                const HxComposeQuad* quads, uint32_t quadCount, const HxPassthroughState& passthrough);
}  // namespace hxp
