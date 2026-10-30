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
void gfxCompose(HxSessionGfx*, const HxConfig&, const XrPosef&, const HxComposeView*, uint32_t, const HxComposeQuad*, uint32_t) {}
}  // namespace hxp
