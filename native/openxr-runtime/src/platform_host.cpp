// Host (Linux) platform layer for the runtime's unit tests: real clock, configurable fake head
// pose, headless sessions only.
#include "hx_runtime.h"

#include <string.h>

namespace {
XrQuaternionf gHead = {0, 0, 0, 1};
bool gTracked = true;
HxInput gInput = {};
HxHandShared gHands = {};
bool gHandsSet = false;
}

extern "C" void hxTestSetHands(const HxHandShared* h) { if (h) { gHands = *h; gHandsSet = true; } else gHandsSet = false; }
extern "C" int64_t hxTestHandsWanted() { return gHands.readerWantsHandsNs; }
extern "C" void hxTestLastHaptic(uint32_t* seq, int* hand, float* amplitude, int* ms) {
    *seq = gHands.hapticSeq; *hand = static_cast<int>(gHands.hapticHand); *amplitude = gHands.hapticAmplitude; *ms = gHands.hapticDurationMs;
}
int64_t gPassthroughWantedNs = 0;
extern "C" int64_t hxTestPassthroughWanted() { return gPassthroughWantedNs; }
HxSavedAnchor gSaved[64];
int gSavedCount = 0;
float gRoomX[32], gRoomZ[32], gRoomHeight = 0;
int gRoomCount = 0;
extern "C" int hxTestSavedAnchors() { return gSavedCount; }
extern "C" void hxTestSetRoom(const float* x, const float* z, int n, float height) {
    gRoomCount = n; gRoomHeight = height;
    for (int i = 0; i < n && i < 32; i++) { gRoomX[i] = x[i]; gRoomZ[i] = z[i]; }
}

extern "C" void hxTestSetInput(uint32_t buttons, float lx, float ly, float rx, float ry, float lt, float rt, int touch) {
    gInput.buttons = buttons;
    gInput.stick[0][0] = lx; gInput.stick[0][1] = ly; gInput.stick[1][0] = rx; gInput.stick[1][1] = ry;
    gInput.trigger[0] = lt; gInput.trigger[1] = rt;
    gInput.touch = touch;
}

extern "C" void hxTestSetHead(float qx, float qy, float qz, float qw, int tracked) {
    gHead = hx::qnorm({qx, qy, qz, qw});
    gTracked = tracked != 0;
}

namespace hxp {
int64_t nowNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}
void init(void*, void*) {}
void loadConfig(HxConfig* c) {
    c->ipdM = 0.064f; c->fovDeg = 90; c->k1 = 0.22f; c->k2 = 0.24f; c->eyeHeightM = 1.6f;
    c->eyeWidthPx = 1024; c->eyeHeightPx = 1024; c->refreshHz = 60; c->lensDistortion = 1;
}
void trackerStart() {}
void trackerStop() {}
bool headPose(int64_t, const HxConfig& cfg, XrPosef* out) {
    if (!gTracked) return false;
    out->orientation = gHead;
    out->position = {0, cfg.eyeHeightM, 0};
    return true;
}
void recenter() {}
void readInput(HxInput* out) { *out = gInput; }
void passthroughWanted() { gPassthroughWantedNs = nowNs(); }
void haptic(int hand, float amplitude, int64_t durationNs) {
    gHands.hapticSeq++; gHands.hapticHand = static_cast<uint32_t>(hand); gHands.hapticAmplitude = amplitude;
    gHands.hapticDurationMs = static_cast<int32_t>(durationNs / 1000000);
}
int loadAnchors(HxSavedAnchor* out, int capacity) {
    int n = gSavedCount < capacity ? gSavedCount : capacity;
    memcpy(out, gSaved, sizeof(HxSavedAnchor) * n);
    return n;
}
void saveAnchors(const HxSavedAnchor* a, int n) {
    gSavedCount = n < 64 ? n : 64;
    memcpy(gSaved, a, sizeof(HxSavedAnchor) * gSavedCount);
}
bool loadRoom(float* x, float* z, int capacity, int* count, float* height) {
    if (gRoomCount == 0 || gRoomCount > capacity) return false;
    memcpy(x, gRoomX, sizeof(float) * gRoomCount); memcpy(z, gRoomZ, sizeof(float) * gRoomCount);
    *count = gRoomCount; *height = gRoomHeight;
    return true;
}
void randomBytes(void* out, size_t size) {
    static uint64_t state = 0x9e3779b97f4a7c15ull;
    uint8_t* b = static_cast<uint8_t*>(out);
    for (size_t i = 0; i < size; i++) { state ^= state << 13; state ^= state >> 7; state ^= state << 17; b[i] = static_cast<uint8_t>(state); }
}
bool readHands(HxHandShared* out, bool wantsHands) {
    if (!gHandsSet) return false;
    gHands.readerAliveNs = nowNs();
    if (wantsHands) gHands.readerWantsHandsNs = gHands.readerAliveNs;
    *out = gHands;
    return true;
}
XrResult gfxCreateSession(const void*, HxSessionGfx**) { return XR_ERROR_GRAPHICS_DEVICE_INVALID; }
void gfxDestroySession(HxSessionGfx*) {}
XrResult gfxEnumerateFormats(int64_t*, uint32_t, uint32_t* count) { *count = 0; return XR_SUCCESS; }
XrResult gfxCreateSwapchain(HxSessionGfx*, const XrSwapchainCreateInfo*, HxSwapchainGfx**, uint32_t*) { return XR_ERROR_FUNCTION_UNSUPPORTED; }
void gfxDestroySwapchain(HxSwapchainGfx*) {}
XrResult gfxEnumerateImages(HxSwapchainGfx*, uint32_t, uint32_t*, XrSwapchainImageBaseHeader*) { return XR_ERROR_FUNCTION_UNSUPPORTED; }
void gfxReleaseImage(HxSwapchainGfx*, uint32_t) {}
XrResult gfxCreateDepth(HxSessionGfx*, uint32_t, uint32_t count, HxDepthGfx** out, uint32_t* textures) {
    for (uint32_t i = 0; i < count; i++) textures[i] = 100 + i;  // names only: nothing is drawn on the host
    *out = nullptr;
    return XR_SUCCESS;
}
void gfxDestroyDepth(HxDepthGfx*) {}
void gfxRenderDepth(HxSessionGfx*, HxDepthGfx*, uint32_t, const XrPosef*, const XrFovf*, float, const float*, int) {}
void gfxCompose(HxSessionGfx*, const HxConfig&, const XrPosef&, const HxComposeView*, uint32_t, const HxComposeQuad*, uint32_t,
                const HxPassthroughState&) {}
}  // namespace hxp
