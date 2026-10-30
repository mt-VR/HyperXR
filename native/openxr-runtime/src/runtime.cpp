// HyperXR OpenXR runtime: instance, system, session, spaces, swapchains, frame loop and actions.
//
// Scope (honest): a single 3DoF head-mounted system (phone in a VR Box viewer), stereo views,
// OpenGL ES graphics or headless sessions, projection + quad layers. Input: a gamepad / Joy-Cons
// and the screen touch, captured by the runtime's window inside the app, drive the app's actions
// through the Touch or simple controller profile; controller poses follow the head (no 6DoF).
#include "hx_runtime.h"

#include <openxr/openxr_loader_negotiation.h>
#include <openxr/openxr_reflection.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

#if defined(__GNUC__)
#define HX_EXPORT extern "C" __attribute__((visibility("default")))
#else
#define HX_EXPORT extern "C"
#endif

namespace {

// ------------------------------------------------------------------ objects
enum Magic : uint32_t {
    MAGIC_INSTANCE = 0x48584931, MAGIC_SESSION = 0x48585331, MAGIC_SPACE = 0x48585031,
    MAGIC_SWAPCHAIN = 0x48585731, MAGIC_ACTIONSET = 0x48584131, MAGIC_ACTION = 0x48584331,
    MAGIC_FOVEATION = 0x48584631,
};

constexpr XrSystemId SYSTEM_ID = 1;
constexpr int MAX_EVENTS = 32;
constexpr int MAX_PATHS = 512;
constexpr int MAX_PATH_LEN = XR_MAX_PATH_LENGTH;
constexpr int MAX_IMAGES = 3;
constexpr int MAX_LAYERS = 16;
constexpr int MAX_BINDINGS = 512;

struct Instance;
struct Session;

struct Instance {
    uint32_t magic;
    pthread_mutex_t lock;
    XrVersion apiVersion;
    bool extGles, extTimespec, extHeadless, extLocalFloor;
    uint32_t extMeta;  // Meta / Pico / EXT extensions enabled by the app (bit per kExtensions entry)
    bool gfxRequirementsQueried;
    bool systemQueried;
    char appName[XR_MAX_APPLICATION_NAME_SIZE];
    XrEventDataBuffer events[MAX_EVENTS];
    int eventHead, eventCount;
    char paths[MAX_PATHS][MAX_PATH_LEN];
    int pathCount;
    Session* session;
    int actionSets;  // live action sets
    HxConfig cfg;
    struct Binding { XrPath profile; void* action; XrPath path; } bindings[MAX_BINDINGS];
    int bindingCount;
};

struct ActionSet;
/** Value of an action for one slot: left hand, right hand, or any (no subaction path). */
struct ActionValue { float v[2]; bool active; bool changed; XrTime lastChange; };
struct Action {
    uint32_t magic;
    ActionSet* set;
    XrActionType type;
    char name[XR_MAX_ACTION_NAME_SIZE];
    XrPath subactions[8];
    uint32_t subactionCount;
    ActionValue state[3];
};

struct ActionSet {
    uint32_t magic;
    Instance* instance;
    char name[XR_MAX_ACTION_SET_NAME_SIZE];
    char localized[XR_MAX_LOCALIZED_ACTION_SET_NAME_SIZE];
    uint32_t priority;
    bool attached;
    int actionCount;
};

struct Session {
    uint32_t magic;
    Instance* instance;
    XrSessionState state;
    bool running;
    bool exitRequested;
    bool headless;
    bool actionsAttached;
    ActionSet* attachedSets[16];
    uint32_t attachedCount;
    HxSessionGfx* gfx;
    // frame loop
    pthread_mutex_t frameLock;
    pthread_cond_t frameCond;
    bool frameWaited;       // xrWaitFrame returned, xrBeginFrame not yet called
    bool frameBegun;        // xrBeginFrame called, xrEndFrame not yet
    int64_t lastWaitNs;
    int64_t lastPredicted;
    uint64_t frameCount;
    XrPosef lastHead;
    int liveSwapchains;
    int liveSpaces;
    XrPath profile;          // interaction profile driving both hands (XR_NULL_PATH: none)
    HxInput input;           // snapshot taken by xrSyncActions
};

/** XR_FB_foveation profile: kept only so its handle is valid (the whole image is always drawn). */
struct Foveation { uint32_t magic; };

struct Space {
    uint32_t magic;
    Session* session;
    XrReferenceSpaceType type;  // 0 for action spaces
    Action* action;
    XrPath subaction;
    XrPosef offset;             // pose in the reference / action space
};

struct Swapchain {
    uint32_t magic;
    Session* session;
    HxSwapchainGfx* gfx;
    uint32_t imageCount;
    uint32_t width, height, arraySize;
    // image cycling: acquire order is round robin; at most imageCount - 1... spec allows all
    uint32_t nextAcquire;
    int acquired[MAX_IMAGES];   // queue of acquired, not yet waited/released indices
    int acquiredCount;
    int waitedIndex;            // index waited and not released, -1 if none
    int lastReleased;           // most recently released image (what the compositor shows)
    bool staticImage;
    bool staticReleased;
};

pthread_mutex_t gGlobalLock = PTHREAD_MUTEX_INITIALIZER;
Instance* gInstance = nullptr;  // OpenXR on Android: one instance per process is plenty

// Live-handle registry: a handle is valid only while registered, so validating a destroyed handle
// never touches freed memory, and destroying a parent can find and free its children.
constexpr int MAX_HANDLES = 2048;
struct HandleEntry { void* ptr; uint32_t magic; void* owner; };
HandleEntry gHandles[MAX_HANDLES];
pthread_mutex_t gHandleLock = PTHREAD_MUTEX_INITIALIZER;

bool registerHandle(void* p, uint32_t magic, void* owner) {
    pthread_mutex_lock(&gHandleLock);
    for (auto& e : gHandles) {
        if (!e.ptr) { e.ptr = p; e.magic = magic; e.owner = owner; pthread_mutex_unlock(&gHandleLock); return true; }
    }
    pthread_mutex_unlock(&gHandleLock);
    return false;
}

void unregisterHandle(void* p) {
    pthread_mutex_lock(&gHandleLock);
    for (auto& e : gHandles) if (e.ptr == p) { e.ptr = nullptr; e.magic = 0; e.owner = nullptr; }
    pthread_mutex_unlock(&gHandleLock);
}

bool isLive(const void* p, uint32_t magic) {
    if (!p) return false;
    pthread_mutex_lock(&gHandleLock);
    bool found = false;
    for (auto& e : gHandles) if (e.ptr == p && e.magic == magic) { found = true; break; }
    pthread_mutex_unlock(&gHandleLock);
    return found;
}

/** Removes and returns one live child of [owner] with [magic], or null. */
void* takeChild(void* owner, uint32_t magic) {
    pthread_mutex_lock(&gHandleLock);
    for (auto& e : gHandles) {
        if (e.ptr && e.owner == owner && e.magic == magic) {
            void* p = e.ptr; e.ptr = nullptr; e.magic = 0; e.owner = nullptr;
            pthread_mutex_unlock(&gHandleLock);
            return p;
        }
    }
    pthread_mutex_unlock(&gHandleLock);
    return nullptr;
}

/** Calls [fn] for every live child of [owner] with [magic]. */
template <typename F> void forEachChild(void* owner, uint32_t magic, F fn) {
    void* found[MAX_HANDLES];
    int n = 0;
    pthread_mutex_lock(&gHandleLock);
    for (auto& e : gHandles) if (e.ptr && e.owner == owner && e.magic == magic) found[n++] = e.ptr;
    pthread_mutex_unlock(&gHandleLock);
    for (int i = 0; i < n; i++) fn(found[i]);
}

template <typename T> T* alloc(uint32_t magic, void* owner = nullptr) {
    T* p = static_cast<T*>(calloc(1, sizeof(T)));
    if (!p) return nullptr;
    p->magic = magic;
    if (!registerHandle(p, magic, owner)) { free(p); return nullptr; }
    return p;
}
template <typename T> void release(T* p) { if (p) { unregisterHandle(p); p->magic = 0; free(p); } }

Instance* asInstance(XrInstance h) {
    Instance* i = reinterpret_cast<Instance*>(h);
    return (i && i == gInstance && isLive(i, MAGIC_INSTANCE)) ? i : nullptr;
}
Session* asSession(XrSession h) {
    Session* s = reinterpret_cast<Session*>(h);
    if (!s || !gInstance || gInstance->session != s || !isLive(s, MAGIC_SESSION)) return nullptr;
    return s;
}
template <typename T> T* asObj(uint64_t h, uint32_t magic) {
    T* p = reinterpret_cast<T*>(h);
    return isLive(p, magic) ? p : nullptr;
}
Space* asSpace(XrSpace h) { return asObj<Space>(reinterpret_cast<uint64_t>(h), MAGIC_SPACE); }
Swapchain* asSwapchain(XrSwapchain h) { return asObj<Swapchain>(reinterpret_cast<uint64_t>(h), MAGIC_SWAPCHAIN); }
ActionSet* asActionSet(XrActionSet h) { return asObj<ActionSet>(reinterpret_cast<uint64_t>(h), MAGIC_ACTIONSET); }
Action* asAction(XrAction h) { return asObj<Action>(reinterpret_cast<uint64_t>(h), MAGIC_ACTION); }

bool actionAttachedTo(Session* s, Action* a) {
    for (uint32_t i = 0; i < s->attachedCount; i++) if (s->attachedSets[i] == a->set) return true;
    return false;
}

/** Forgets suggested bindings of a destroyed action (its memory may be reused by a new one). */
void dropBindings(Instance* inst, const void* action) {
    pthread_mutex_lock(&inst->lock);
    int kept = 0;
    for (int i = 0; i < inst->bindingCount; i++) if (inst->bindings[i].action != action) inst->bindings[kept++] = inst->bindings[i];
    inst->bindingCount = kept;
    pthread_mutex_unlock(&inst->lock);
}

void copyStr(char* dst, size_t cap, const char* src) {
    size_t n = strlen(src);
    if (n >= cap) n = cap - 1;
    memcpy(dst, src, n);
    dst[n] = 0;
}

/** Two-call idiom for strings. */
XrResult outString(const char* s, uint32_t capacity, uint32_t* countOut, char* buffer) {
    if (!countOut) return XR_ERROR_VALIDATION_FAILURE;
    uint32_t need = static_cast<uint32_t>(strlen(s)) + 1;
    *countOut = need;
    if (capacity == 0) return XR_SUCCESS;
    if (capacity < need || !buffer) return XR_ERROR_SIZE_INSUFFICIENT;
    memcpy(buffer, s, need);
    return XR_SUCCESS;
}

// ------------------------------------------------------------------ events
void pushEvent(Instance* inst, const void* ev, size_t size) {
    pthread_mutex_lock(&inst->lock);
    if (inst->eventCount == MAX_EVENTS) {
        // queue full: drop the oldest and report the loss as the spec requires
        inst->eventHead = (inst->eventHead + 1) % MAX_EVENTS;
        inst->eventCount--;
    }
    int idx = (inst->eventHead + inst->eventCount) % MAX_EVENTS;
    memset(&inst->events[idx], 0, sizeof(XrEventDataBuffer));
    memcpy(&inst->events[idx], ev, size);
    inst->eventCount++;
    pthread_mutex_unlock(&inst->lock);
}

void setState(Session* s, XrSessionState st) {
    if (s->state == st) return;
    s->state = st;
    XrEventDataSessionStateChanged ev{XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED};
    ev.session = reinterpret_cast<XrSession>(s);
    ev.state = st;
    ev.time = hxp::nowNs();
    pushEvent(s->instance, &ev, sizeof(ev));
}

// ------------------------------------------------------------------ paths
XrResult stringToPath(Instance* inst, const char* str, XrPath* out) {
    size_t n = strlen(str);
    if (n == 0 || n >= MAX_PATH_LEN || str[0] != '/' || str[n - 1] == '/') return XR_ERROR_PATH_FORMAT_INVALID;
    for (size_t i = 0; i < n; i++) {
        char c = str[i];
        bool ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.' || c == '/';
        if (!ok) return XR_ERROR_PATH_FORMAT_INVALID;
        if (c == '/' && i + 1 < n && str[i + 1] == '/') return XR_ERROR_PATH_FORMAT_INVALID;
        if (c == '.' && (i == 0 || str[i - 1] == '/')) {
            // "." and ".." components are not allowed
            size_t j = i; while (j < n && str[j] == '.') j++;
            if (j == n || str[j] == '/') return XR_ERROR_PATH_FORMAT_INVALID;
        }
    }
    pthread_mutex_lock(&inst->lock);
    for (int i = 0; i < inst->pathCount; i++) {
        if (strcmp(inst->paths[i], str) == 0) { *out = static_cast<XrPath>(i + 1); pthread_mutex_unlock(&inst->lock); return XR_SUCCESS; }
    }
    if (inst->pathCount == MAX_PATHS) { pthread_mutex_unlock(&inst->lock); return XR_ERROR_PATH_COUNT_EXCEEDED; }
    copyStr(inst->paths[inst->pathCount], MAX_PATH_LEN, str);
    *out = static_cast<XrPath>(++inst->pathCount);
    pthread_mutex_unlock(&inst->lock);
    return XR_SUCCESS;
}

const char* pathString(Instance* inst, XrPath p) {
    if (p == XR_NULL_PATH || p > static_cast<XrPath>(inst->pathCount)) return nullptr;
    return inst->paths[p - 1];
}

bool validName(const char* s, size_t cap) {
    size_t n = strnlen(s, cap);
    if (n == 0 || n == cap) return false;
    for (size_t i = 0; i < n; i++) {
        char c = s[i];
        if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '_' || c == '.')) return false;
    }
    return true;
}


// ------------------------------------------------------------------ input sources
// Profiles the app may have suggested bindings for, in order of preference. Every one is driven
// by the same physical input (gamepad / Joy-Cons + screen touch).
const char* const kProfiles[] = {
    "/interaction_profiles/oculus/touch_controller",
    "/interaction_profiles/meta/touch_controller_plus",
    "/interaction_profiles/meta/touch_pro_controller",
    "/interaction_profiles/facebook/touch_controller_pro",
    "/interaction_profiles/bytedance/pico4_controller",
    "/interaction_profiles/bytedance/pico_neo3_controller",
    "/interaction_profiles/bytedance/pico_g3_controller",
    "/interaction_profiles/khr/simple_controller",
};

bool profileSupported(const char* p) {
    for (const char* k : kProfiles) if (strcmp(k, p) == 0) return true;
    return false;
}

enum SrcKind { SRC_NONE, SRC_BOOL, SRC_FLOAT, SRC_VEC2, SRC_POSE, SRC_HAPTIC };

/** Hand of a /user/hand/... path: 0 left, 1 right, -1 other. */
int handOf(const char* p, const char** rest) {
    if (strncmp(p, "/user/hand/left", 15) == 0 && (p[15] == 0 || p[15] == '/')) { if (rest) *rest = p + 15; return 0; }
    if (strncmp(p, "/user/hand/right", 16) == 0 && (p[16] == 0 || p[16] == '/')) { if (rest) *rest = p + 16; return 1; }
    return -1;
}

/**
 * Reads one input source path (e.g. /user/hand/right/input/trigger/value) from [in]. Returns its
 * kind (SRC_NONE when this hardware has no such source) and value in v[0] (v[1] for vectors).
 */
SrcKind evalSource(const char* path, bool simple, const HxInput& in, int* handOut, float v[2]) {
    const char* rest = nullptr;
    int h = handOf(path, &rest);
    *handOut = h;
    v[0] = v[1] = 0;
    if (h < 0) return SRC_NONE;
    if (strcmp(rest, "/output/haptic") == 0) return SRC_HAPTIC;
    if (strncmp(rest, "/input/", 7) != 0) return SRC_NONE;
    char id[64]; const char* comp = "";
    const char* q = rest + 7;
    const char* slash = strchr(q, '/');
    size_t n = slash ? static_cast<size_t>(slash - q) : strlen(q);
    if (n >= sizeof(id)) return SRC_NONE;
    memcpy(id, q, n); id[n] = 0;
    if (slash) comp = slash + 1;
    auto btn = [&](uint32_t bit) { v[0] = (in.buttons & bit) ? 1.f : 0.f; return SRC_BOOL; };
    // the screen touch (VR Box button) acts as the right trigger / select
    float trig = in.trigger[h];
    if (h == 1 && in.touch && trig < 1.f) trig = 1.f;
    if (strcmp(id, "grip") == 0 || strcmp(id, "aim") == 0) return strcmp(comp, "pose") == 0 ? SRC_POSE : SRC_NONE;
    if (simple) {
        if (strcmp(id, "select") == 0 && strcmp(comp, "click") == 0) { v[0] = trig > 0.5f ? 1.f : 0.f; return SRC_BOOL; }
        if (strcmp(id, "menu") == 0 && strcmp(comp, "click") == 0) return btn(HX_BTN_MENU);
        return SRC_NONE;
    }
    if (strcmp(id, "trigger") == 0) {
        if (!*comp || strcmp(comp, "value") == 0) { v[0] = trig; return SRC_FLOAT; }
        if (strcmp(comp, "click") == 0) { v[0] = trig > 0.5f ? 1.f : 0.f; return SRC_BOOL; }
        if (strcmp(comp, "touch") == 0 || strcmp(comp, "proximity") == 0) { v[0] = trig > 0.05f ? 1.f : 0.f; return SRC_BOOL; }
        return SRC_NONE;
    }
    if (strcmp(id, "squeeze") == 0) {
        if (!*comp || strcmp(comp, "value") == 0) { v[0] = in.squeeze[h]; return SRC_FLOAT; }
        if (strcmp(comp, "click") == 0) { v[0] = in.squeeze[h] > 0.5f ? 1.f : 0.f; return SRC_BOOL; }
        return SRC_NONE;
    }
    if (strcmp(id, "thumbstick") == 0) {
        float x = in.stick[h][0], y = in.stick[h][1];
        if (!*comp) { v[0] = x; v[1] = y; return SRC_VEC2; }
        if (strcmp(comp, "x") == 0) { v[0] = x; return SRC_FLOAT; }
        if (strcmp(comp, "y") == 0) { v[0] = y; return SRC_FLOAT; }
        uint32_t thumb = h == 0 ? HX_BTN_THUMB_L : HX_BTN_THUMB_R;
        if (strcmp(comp, "click") == 0) return btn(thumb);
        if (strcmp(comp, "touch") == 0) { v[0] = ((in.buttons & thumb) || x * x + y * y > 0.01f) ? 1.f : 0.f; return SRC_BOOL; }
        return SRC_NONE;
    }
    if (strcmp(comp, "click") != 0 && strcmp(comp, "touch") != 0) return SRC_NONE;
    if (h == 1 && strcmp(id, "a") == 0) return btn(HX_BTN_A);
    if (h == 1 && strcmp(id, "b") == 0) return btn(HX_BTN_B);
    if (h == 0 && strcmp(id, "x") == 0) return btn(HX_BTN_X);
    if (h == 0 && strcmp(id, "y") == 0) return btn(HX_BTN_Y);
    if (h == 0 && strcmp(id, "menu") == 0 && strcmp(comp, "click") == 0) return btn(HX_BTN_MENU);
    if (h == 1 && strcmp(id, "system") == 0 && strcmp(comp, "click") == 0) return btn(HX_BTN_SYSTEM);
    if (strcmp(id, "thumbrest") == 0 && strcmp(comp, "touch") == 0) { v[0] = 0; return SRC_BOOL; }
    return SRC_NONE;
}

bool isSimpleProfile(Instance* inst, XrPath profile) {
    const char* p = pathString(inst, profile);
    return p && strcmp(p, "/interaction_profiles/khr/simple_controller") == 0;
}

/** Evaluates [a] for slot 0 (left), 1 (right) or 2 (any hand) under the session's profile. */
ActionValue evalAction(Session* s, Action* a, int slot) {
    ActionValue r{};
    Instance* inst = s->instance;
    if (s->profile == XR_NULL_PATH) return r;
    bool simple = isSimpleProfile(inst, s->profile);
    float best = -1;
    for (int i = 0; i < inst->bindingCount; i++) {
        const auto& b = inst->bindings[i];
        if (b.profile != s->profile || b.action != a) continue;
        const char* path = pathString(inst, b.path);
        if (!path) continue;
        int hand; float v[2];
        SrcKind k = evalSource(path, simple, s->input, &hand, v);
        if (k == SRC_NONE || (slot < 2 && hand != slot)) continue;
        bool compatible = (a->type == XR_ACTION_TYPE_BOOLEAN_INPUT && (k == SRC_BOOL || k == SRC_FLOAT)) ||
                          (a->type == XR_ACTION_TYPE_FLOAT_INPUT && (k == SRC_BOOL || k == SRC_FLOAT)) ||
                          (a->type == XR_ACTION_TYPE_VECTOR2F_INPUT && k == SRC_VEC2) ||
                          (a->type == XR_ACTION_TYPE_POSE_INPUT && k == SRC_POSE) ||
                          (a->type == XR_ACTION_TYPE_VIBRATION_OUTPUT && k == SRC_HAPTIC);
        if (!compatible) continue;
        r.active = true;
        // combine several sources: boolean OR, largest magnitude for float and vector
        if (a->type == XR_ACTION_TYPE_BOOLEAN_INPUT) { if (v[0] > 0.5f) r.v[0] = 1.f; }
        else if (a->type == XR_ACTION_TYPE_FLOAT_INPUT) { if (fabsf(v[0]) > best) { best = fabsf(v[0]); r.v[0] = v[0]; } }
        else if (a->type == XR_ACTION_TYPE_VECTOR2F_INPUT) { float m = v[0] * v[0] + v[1] * v[1]; if (m > best) { best = m; r.v[0] = v[0]; r.v[1] = v[1]; } }
    }
    return r;
}

/** Slot for a subaction path: 0 left, 1 right, 2 none, 3 a path this runtime never drives. */
int slotOf(Instance* inst, XrPath sub) {
    if (sub == XR_NULL_PATH) return 2;
    const char* p = pathString(inst, sub);
    if (!p) return 3;
    if (strcmp(p, "/user/hand/left") == 0) return 0;
    if (strcmp(p, "/user/hand/right") == 0) return 1;
    return 3;
}

/** Controller pose in stage space: no controller tracking exists, so each hand rides with the head. */
bool controllerPose(Session* s, int hand, XrTime time, XrPosef* out) {
    XrPosef head;
    if (!hxp::headPose(time, s->instance->cfg, &head)) return false;
    XrQuaternionf yaw = hx::yawOnly(head.orientation);
    XrVector3f off = {hand == 0 ? -0.17f : 0.17f, -0.32f, -0.30f};
    out->position = hx::vadd(head.position, hx::qrot(yaw, off));
    out->orientation = head.orientation;  // aim follows the gaze: point by looking
    return true;
}

// ------------------------------------------------------------------ poses
/** Pose of a space in stage space at [time]. */
bool spaceInStage(Space* sp, XrTime time, XrPosef* out, bool* tracked) {
    Instance* inst = sp->session->instance;
    *tracked = true;
    switch (sp->type) {
        case XR_REFERENCE_SPACE_TYPE_STAGE:
        case XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR:
            *out = sp->offset;
            return true;
        case XR_REFERENCE_SPACE_TYPE_LOCAL: {
            XrPosef local = hx::identity();
            local.position.y = inst->cfg.eyeHeightM;
            *out = hx::compose(local, sp->offset);
            return true;
        }
        case XR_REFERENCE_SPACE_TYPE_VIEW: {
            XrPosef head;
            if (!hxp::headPose(time, inst->cfg, &head)) { *tracked = false; return false; }
            *out = hx::compose(head, sp->offset);
            return true;
        }
        default: {
            // action spaces: the hand of the space's subaction path, or the first hand bound
            Session* s = sp->session;
            Action* a = sp->action;
            *tracked = false;
            if (!a || !isLive(a, MAGIC_ACTION) || s->profile == XR_NULL_PATH) return false;
            int slot = slotOf(inst, sp->subaction);
            if (slot == 3) return false;
            int hand = -1;
            for (int h = 0; h < 2 && hand < 0; h++) if ((slot == 2 || slot == h) && evalAction(s, a, h).active) hand = h;
            if (hand < 0) return false;
            XrPosef c;
            if (!controllerPose(s, hand, time, &c)) return false;
            *out = hx::compose(c, sp->offset);
            *tracked = true;
            return true;
        }
    }
}

XrFovf eyeFov(const HxConfig& cfg) {
    float v = cfg.fovDeg * 0.5f * static_cast<float>(M_PI) / 180.0f;
    float aspect = static_cast<float>(cfg.eyeWidthPx) / static_cast<float>(cfg.eyeHeightPx > 0 ? cfg.eyeHeightPx : 1);
    float h = atanf(tanf(v) * aspect);
    return {-h, h, v, -v};
}

// ------------------------------------------------------------------ instance
XRAPI_ATTR XrResult XRAPI_CALL EnumerateApiLayerProperties(uint32_t capacity, uint32_t* count, XrApiLayerProperties*) {
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    *count = 0;
    (void)capacity;
    return XR_SUCCESS;
}

struct ExtDesc { const char* name; uint32_t version; };
const ExtDesc kExtensions[] = {
#ifdef __ANDROID__
    {XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME, XR_KHR_android_create_instance_SPEC_VERSION},
    {XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME, XR_KHR_opengl_es_enable_SPEC_VERSION},
#endif
    {XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME, XR_KHR_convert_timespec_time_SPEC_VERSION},
    {XR_MND_HEADLESS_EXTENSION_NAME, XR_MND_headless_SPEC_VERSION},
    {XR_EXT_LOCAL_FLOOR_EXTENSION_NAME, XR_EXT_local_floor_SPEC_VERSION},
    // Meta (Quest) apps: the extensions the Meta XR SDK and Unity/Unreal's Quest support ask for.
    // They are honoured as far as a phone in a viewer can: one refresh rate (the screen's), the
    // phone's colour space, foveation and swapchain state accepted (the full image is always drawn).
    {XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME, XR_FB_display_refresh_rate_SPEC_VERSION},
    {XR_FB_COLOR_SPACE_EXTENSION_NAME, XR_FB_color_space_SPEC_VERSION},
    {XR_FB_SWAPCHAIN_UPDATE_STATE_EXTENSION_NAME, XR_FB_swapchain_update_state_SPEC_VERSION},
    {XR_FB_FOVEATION_EXTENSION_NAME, XR_FB_foveation_SPEC_VERSION},
    {XR_FB_FOVEATION_CONFIGURATION_EXTENSION_NAME, XR_FB_foveation_configuration_SPEC_VERSION},
    {XR_FB_COMPOSITION_LAYER_SETTINGS_EXTENSION_NAME, XR_FB_composition_layer_settings_SPEC_VERSION},
    {XR_FB_COMPOSITION_LAYER_IMAGE_LAYOUT_EXTENSION_NAME, XR_FB_composition_layer_image_layout_SPEC_VERSION},
    {XR_FB_TOUCH_CONTROLLER_PRO_EXTENSION_NAME, XR_FB_touch_controller_pro_SPEC_VERSION},
    {XR_META_TOUCH_CONTROLLER_PLUS_EXTENSION_NAME, XR_META_touch_controller_plus_SPEC_VERSION},
    {XR_EXT_PERFORMANCE_SETTINGS_EXTENSION_NAME, XR_EXT_performance_settings_SPEC_VERSION},
    // Pico apps: the Pico 4 / Neo 3 / G3 controller profiles (driven like the Touch ones)
    {XR_BD_CONTROLLER_INTERACTION_EXTENSION_NAME, XR_BD_controller_interaction_SPEC_VERSION},
#ifdef __ANDROID__
    {XR_KHR_ANDROID_THREAD_SETTINGS_EXTENSION_NAME, XR_KHR_android_thread_settings_SPEC_VERSION},
    {XR_FB_SWAPCHAIN_UPDATE_STATE_OPENGL_ES_EXTENSION_NAME, XR_FB_swapchain_update_state_opengl_es_SPEC_VERSION},
#endif
};
constexpr uint32_t kExtensionCount = sizeof(kExtensions) / sizeof(kExtensions[0]);
static_assert(kExtensionCount <= 32, "Instance::extMeta has one bit per extension");

XRAPI_ATTR XrResult XRAPI_CALL EnumerateInstanceExtensionProperties(const char* layerName, uint32_t capacity, uint32_t* count, XrExtensionProperties* props) {
    if (layerName && layerName[0]) return XR_ERROR_API_LAYER_NOT_PRESENT;
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    *count = kExtensionCount;
    if (capacity == 0) return XR_SUCCESS;
    if (capacity < kExtensionCount || !props) return XR_ERROR_SIZE_INSUFFICIENT;
    for (uint32_t i = 0; i < kExtensionCount; i++) {
        if (props[i].type != XR_TYPE_EXTENSION_PROPERTIES) return XR_ERROR_VALIDATION_FAILURE;
        copyStr(props[i].extensionName, XR_MAX_EXTENSION_NAME_SIZE, kExtensions[i].name);
        props[i].extensionVersion = kExtensions[i].version;
    }
    return XR_SUCCESS;
}

#ifdef __ANDROID__
XRAPI_ATTR XrResult XRAPI_CALL InitializeLoaderKHR(const XrLoaderInitInfoBaseHeaderKHR* info) {
    if (!info || info->type != XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR) return XR_ERROR_VALIDATION_FAILURE;
    auto* a = reinterpret_cast<const XrLoaderInitInfoAndroidKHR*>(info);
    hxp::init(a->applicationVM, a->applicationContext);
    return XR_SUCCESS;
}
#endif

XRAPI_ATTR XrResult XRAPI_CALL CreateInstance(const XrInstanceCreateInfo* ci, XrInstance* out) {
    if (!ci || !out || ci->type != XR_TYPE_INSTANCE_CREATE_INFO) return XR_ERROR_VALIDATION_FAILURE;
    XrVersion v = ci->applicationInfo.apiVersion;
    if (XR_VERSION_MAJOR(v) != 1 || XR_VERSION_MINOR(v) > 1) return XR_ERROR_API_VERSION_UNSUPPORTED;
    if (ci->applicationInfo.applicationName[0] == 0) return XR_ERROR_NAME_INVALID;
    if (ci->enabledApiLayerCount > 0) return XR_ERROR_API_LAYER_NOT_PRESENT;
    bool gles = false, timespec = false, headless = false, localFloor = false;
    uint32_t extBits = 0;
    for (uint32_t i = 0; i < ci->enabledExtensionCount; i++) {
        const char* e = ci->enabledExtensionNames[i];
        bool known = false;
        for (uint32_t k = 0; k < kExtensionCount; k++) if (strcmp(e, kExtensions[k].name) == 0) { known = true; extBits |= 1u << k; }
        if (!known) return XR_ERROR_EXTENSION_NOT_PRESENT;
        if (strcmp(e, "XR_KHR_opengl_es_enable") == 0) gles = true;
        if (strcmp(e, XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME) == 0) timespec = true;
        if (strcmp(e, XR_MND_HEADLESS_EXTENSION_NAME) == 0) headless = true;
        if (strcmp(e, XR_EXT_LOCAL_FLOOR_EXTENSION_NAME) == 0) localFloor = true;
    }
#ifdef __ANDROID__
    for (auto* n = reinterpret_cast<const XrBaseInStructure*>(ci->next); n; n = n->next) {
        if (n->type == XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR) {
            auto* a = reinterpret_cast<const XrInstanceCreateInfoAndroidKHR*>(n);
            hxp::init(a->applicationVM, a->applicationActivity);
        }
    }
#endif
    pthread_mutex_lock(&gGlobalLock);
    if (gInstance) { pthread_mutex_unlock(&gGlobalLock); return XR_ERROR_LIMIT_REACHED; }
    Instance* inst = alloc<Instance>(MAGIC_INSTANCE, nullptr);
    if (!inst) { pthread_mutex_unlock(&gGlobalLock); return XR_ERROR_OUT_OF_MEMORY; }
    pthread_mutex_init(&inst->lock, nullptr);
    inst->apiVersion = v;
    inst->extGles = gles; inst->extTimespec = timespec; inst->extHeadless = headless; inst->extLocalFloor = localFloor;
    inst->extMeta = extBits;
    copyStr(inst->appName, sizeof(inst->appName), ci->applicationInfo.applicationName);
    hxp::loadConfig(&inst->cfg);
    gInstance = inst;
    pthread_mutex_unlock(&gGlobalLock);
    hxp::trackerStart();
    *out = reinterpret_cast<XrInstance>(inst);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL DestroySession(XrSession);

XRAPI_ATTR XrResult XRAPI_CALL DestroyInstance(XrInstance h) {
    Instance* inst = asInstance(h);
    if (!inst) return XR_ERROR_HANDLE_INVALID;
    if (inst->session) DestroySession(reinterpret_cast<XrSession>(inst->session));
    while (void* c = takeChild(inst, MAGIC_ACTIONSET)) {
        while (void* a = takeChild(c, MAGIC_ACTION)) { static_cast<Action*>(a)->magic = 0; free(a); }
        static_cast<ActionSet*>(c)->magic = 0; free(c);
    }
    hxp::trackerStop();
    pthread_mutex_lock(&gGlobalLock);
    gInstance = nullptr;
    pthread_mutex_unlock(&gGlobalLock);
    pthread_mutex_destroy(&inst->lock);
    release(inst);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetInstanceProperties(XrInstance h, XrInstanceProperties* p) {
    if (!asInstance(h)) return XR_ERROR_HANDLE_INVALID;
    if (!p || p->type != XR_TYPE_INSTANCE_PROPERTIES) return XR_ERROR_VALIDATION_FAILURE;
    p->runtimeVersion = XR_MAKE_VERSION(0, 1, 0);
    copyStr(p->runtimeName, XR_MAX_RUNTIME_NAME_SIZE, "HyperMagic Runtime");
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL PollEvent(XrInstance h, XrEventDataBuffer* ev) {
    Instance* inst = asInstance(h);
    if (!inst) return XR_ERROR_HANDLE_INVALID;
    if (!ev || ev->type != XR_TYPE_EVENT_DATA_BUFFER) return XR_ERROR_VALIDATION_FAILURE;
    pthread_mutex_lock(&inst->lock);
    if (inst->eventCount == 0) { pthread_mutex_unlock(&inst->lock); return XR_EVENT_UNAVAILABLE; }
    memcpy(ev, &inst->events[inst->eventHead], sizeof(XrEventDataBuffer));
    inst->eventHead = (inst->eventHead + 1) % MAX_EVENTS;
    inst->eventCount--;
    pthread_mutex_unlock(&inst->lock);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL ResultToString(XrInstance h, XrResult value, char buffer[XR_MAX_RESULT_STRING_SIZE]) {
    if (!asInstance(h)) return XR_ERROR_HANDLE_INVALID;
    const char* name = nullptr;
    switch (value) {
#define HX_RESULT_CASE(n, v) case n: name = #n; break;
        XR_LIST_ENUM_XrResult(HX_RESULT_CASE)
#undef HX_RESULT_CASE
        default: break;
    }
    if (name) { copyStr(buffer, XR_MAX_RESULT_STRING_SIZE, name); return XR_SUCCESS; }
    // unknown values are reported generically, as the spec requires
    char tmp[XR_MAX_RESULT_STRING_SIZE];
    copyStr(tmp, sizeof(tmp), value < 0 ? "XR_UNKNOWN_FAILURE_" : "XR_UNKNOWN_SUCCESS_");
    int n = static_cast<int>(strlen(tmp));
    long long v = value < 0 ? -static_cast<long long>(value) : static_cast<long long>(value);
    char digits[24]; int d = 0;
    do { digits[d++] = static_cast<char>('0' + v % 10); v /= 10; } while (v && d < 23);
    while (d && n < XR_MAX_RESULT_STRING_SIZE - 1) tmp[n++] = digits[--d];
    tmp[n] = 0;
    copyStr(buffer, XR_MAX_RESULT_STRING_SIZE, tmp);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL StructureTypeToString(XrInstance h, XrStructureType value, char buffer[XR_MAX_STRUCTURE_NAME_SIZE]) {
    if (!asInstance(h)) return XR_ERROR_HANDLE_INVALID;
    const char* name = nullptr;
    switch (value) {
#define HX_ST_CASE(n, v) case n: name = #n; break;
        XR_LIST_ENUM_XrStructureType(HX_ST_CASE)
#undef HX_ST_CASE
        default: break;
    }
    copyStr(buffer, XR_MAX_STRUCTURE_NAME_SIZE, name ? name : "XR_UNKNOWN_STRUCTURE_TYPE");
    return XR_SUCCESS;
}

// ------------------------------------------------------------------ system
XRAPI_ATTR XrResult XRAPI_CALL GetSystem(XrInstance h, const XrSystemGetInfo* info, XrSystemId* out) {
    Instance* inst = asInstance(h);
    if (!inst) return XR_ERROR_HANDLE_INVALID;
    if (!info || !out || info->type != XR_TYPE_SYSTEM_GET_INFO) return XR_ERROR_VALIDATION_FAILURE;
    if (info->formFactor == XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY) { inst->systemQueried = true; *out = SYSTEM_ID; return XR_SUCCESS; }
    if (info->formFactor == XR_FORM_FACTOR_HANDHELD_DISPLAY) return XR_ERROR_FORM_FACTOR_UNSUPPORTED;
    return XR_ERROR_VALIDATION_FAILURE;
}

XRAPI_ATTR XrResult XRAPI_CALL GetSystemProperties(XrInstance h, XrSystemId sys, XrSystemProperties* p) {
    if (!asInstance(h)) return XR_ERROR_HANDLE_INVALID;
    if (sys != SYSTEM_ID) return XR_ERROR_SYSTEM_INVALID;
    if (!p || p->type != XR_TYPE_SYSTEM_PROPERTIES) return XR_ERROR_VALIDATION_FAILURE;
    p->systemId = SYSTEM_ID;
    p->vendorId = 0x4858;  // "HX"
    copyStr(p->systemName, XR_MAX_SYSTEM_NAME_SIZE, "HyperMagic VR Box (3DoF)");
    p->graphicsProperties.maxLayerCount = MAX_LAYERS;
    p->graphicsProperties.maxSwapchainImageWidth = 4096;
    p->graphicsProperties.maxSwapchainImageHeight = 4096;
    p->trackingProperties.orientationTracking = XR_TRUE;
    p->trackingProperties.positionTracking = XR_FALSE;
    for (auto* n = reinterpret_cast<XrBaseOutStructure*>(p->next); n; n = n->next) {
        if (n->type == XR_TYPE_SYSTEM_COLOR_SPACE_PROPERTIES_FB) reinterpret_cast<XrSystemColorSpacePropertiesFB*>(n)->colorSpace = XR_COLOR_SPACE_REC709_FB;
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL EnumerateEnvironmentBlendModes(XrInstance h, XrSystemId sys, XrViewConfigurationType vct, uint32_t capacity, uint32_t* count, XrEnvironmentBlendMode* modes) {
    if (!asInstance(h)) return XR_ERROR_HANDLE_INVALID;
    if (sys != SYSTEM_ID) return XR_ERROR_SYSTEM_INVALID;
    if (vct != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    *count = 1;
    if (capacity == 0) return XR_SUCCESS;
    if (!modes) return XR_ERROR_VALIDATION_FAILURE;
    modes[0] = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL EnumerateViewConfigurations(XrInstance h, XrSystemId sys, uint32_t capacity, uint32_t* count, XrViewConfigurationType* types) {
    if (!asInstance(h)) return XR_ERROR_HANDLE_INVALID;
    if (sys != SYSTEM_ID) return XR_ERROR_SYSTEM_INVALID;
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    *count = 1;
    if (capacity == 0) return XR_SUCCESS;
    if (!types) return XR_ERROR_VALIDATION_FAILURE;
    types[0] = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetViewConfigurationProperties(XrInstance h, XrSystemId sys, XrViewConfigurationType vct, XrViewConfigurationProperties* p) {
    if (!asInstance(h)) return XR_ERROR_HANDLE_INVALID;
    if (sys != SYSTEM_ID) return XR_ERROR_SYSTEM_INVALID;
    if (vct != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    if (!p || p->type != XR_TYPE_VIEW_CONFIGURATION_PROPERTIES) return XR_ERROR_VALIDATION_FAILURE;
    p->viewConfigurationType = vct;
    p->fovMutable = XR_FALSE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL EnumerateViewConfigurationViews(XrInstance h, XrSystemId sys, XrViewConfigurationType vct, uint32_t capacity, uint32_t* count, XrViewConfigurationView* views) {
    Instance* inst = asInstance(h);
    if (!inst) return XR_ERROR_HANDLE_INVALID;
    if (sys != SYSTEM_ID) return XR_ERROR_SYSTEM_INVALID;
    if (vct != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    *count = 2;
    if (capacity == 0) return XR_SUCCESS;
    if (capacity < 2 || !views) return XR_ERROR_SIZE_INSUFFICIENT;
    for (int i = 0; i < 2; i++) {
        if (views[i].type != XR_TYPE_VIEW_CONFIGURATION_VIEW) return XR_ERROR_VALIDATION_FAILURE;
        views[i].recommendedImageRectWidth = static_cast<uint32_t>(inst->cfg.eyeWidthPx);
        views[i].recommendedImageRectHeight = static_cast<uint32_t>(inst->cfg.eyeHeightPx);
        views[i].maxImageRectWidth = 4096;
        views[i].maxImageRectHeight = 4096;
        views[i].recommendedSwapchainSampleCount = 1;
        views[i].maxSwapchainSampleCount = 4;
    }
    return XR_SUCCESS;
}

#ifdef __ANDROID__
XRAPI_ATTR XrResult XRAPI_CALL GetOpenGLESGraphicsRequirementsKHR(XrInstance h, XrSystemId sys, XrGraphicsRequirementsOpenGLESKHR* req) {
    Instance* inst = asInstance(h);
    if (!inst) return XR_ERROR_HANDLE_INVALID;
    if (sys != SYSTEM_ID) return XR_ERROR_SYSTEM_INVALID;
    if (!req || req->type != XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR) return XR_ERROR_VALIDATION_FAILURE;
    req->minApiVersionSupported = XR_MAKE_VERSION(3, 0, 0);
    req->maxApiVersionSupported = XR_MAKE_VERSION(3, 2, 0);
    inst->gfxRequirementsQueried = true;
    return XR_SUCCESS;
}
#endif

XRAPI_ATTR XrResult XRAPI_CALL ConvertTimespecTimeToTimeKHR(XrInstance h, const struct timespec* ts, XrTime* t) {
    Instance* inst = asInstance(h);
    if (!inst) return XR_ERROR_HANDLE_INVALID;
    if (!inst->extTimespec) return XR_ERROR_FUNCTION_UNSUPPORTED;
    if (!ts || !t) return XR_ERROR_VALIDATION_FAILURE;
    *t = static_cast<XrTime>(ts->tv_sec) * 1000000000LL + ts->tv_nsec;  // XrTime is CLOCK_MONOTONIC
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL ConvertTimeToTimespecTimeKHR(XrInstance h, XrTime t, struct timespec* ts) {
    Instance* inst = asInstance(h);
    if (!inst) return XR_ERROR_HANDLE_INVALID;
    if (!inst->extTimespec) return XR_ERROR_FUNCTION_UNSUPPORTED;
    if (!ts || t <= 0) return XR_ERROR_TIME_INVALID;
    ts->tv_sec = static_cast<time_t>(t / 1000000000LL);
    ts->tv_nsec = static_cast<long>(t % 1000000000LL);
    return XR_SUCCESS;
}

// ------------------------------------------------------------------ session
XRAPI_ATTR XrResult XRAPI_CALL CreateSession(XrInstance h, const XrSessionCreateInfo* ci, XrSession* out) {
    Instance* inst = asInstance(h);
    if (!inst) return XR_ERROR_HANDLE_INVALID;
    if (!ci || !out || ci->type != XR_TYPE_SESSION_CREATE_INFO) return XR_ERROR_VALIDATION_FAILURE;
    if (ci->systemId != SYSTEM_ID) return XR_ERROR_SYSTEM_INVALID;
    if (inst->session) return XR_ERROR_LIMIT_REACHED;
    const XrBaseInStructure* binding = nullptr;
    for (auto* n = reinterpret_cast<const XrBaseInStructure*>(ci->next); n; n = n->next) {
#ifdef __ANDROID__
        if (n->type == XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR) binding = n;
#endif
    }
    bool headless = false;
    if (!binding) {
        if (!inst->extHeadless) return XR_ERROR_GRAPHICS_DEVICE_INVALID;
        headless = true;
    } else {
        if (!inst->extGles) return XR_ERROR_VALIDATION_FAILURE;
        if (!inst->gfxRequirementsQueried) return XR_ERROR_GRAPHICS_REQUIREMENTS_CALL_MISSING;
    }
    Session* s = alloc<Session>(MAGIC_SESSION, inst);
    if (!s) return XR_ERROR_OUT_OF_MEMORY;
    s->instance = inst;
    s->headless = headless;
    s->state = XR_SESSION_STATE_UNKNOWN;
    s->lastHead = hx::identity();
    pthread_mutex_init(&s->frameLock, nullptr);
    pthread_cond_init(&s->frameCond, nullptr);
    if (!headless) {
        XrResult r = hxp::gfxCreateSession(binding, &s->gfx);
        if (XR_FAILED(r)) { release(s); return r; }
    }
    inst->session = s;
    setState(s, XR_SESSION_STATE_IDLE);
    setState(s, XR_SESSION_STATE_READY);
    *out = reinterpret_cast<XrSession>(s);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL DestroySession(XrSession h) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    Instance* inst = s->instance;
    // child handles die with the session
    while (void* c = takeChild(s, MAGIC_SWAPCHAIN)) { Swapchain* sc = static_cast<Swapchain*>(c); hxp::gfxDestroySwapchain(sc->gfx); sc->magic = 0; free(sc); }
    while (void* c = takeChild(s, MAGIC_SPACE)) { static_cast<Space*>(c)->magic = 0; free(c); }
    while (void* c = takeChild(s, MAGIC_FOVEATION)) { static_cast<Foveation*>(c)->magic = 0; free(c); }
    if (s->gfx) hxp::gfxDestroySession(s->gfx);
    pthread_mutex_destroy(&s->frameLock);
    pthread_cond_destroy(&s->frameCond);
    inst->session = nullptr;
    // drop queued events that reference the destroyed session
    pthread_mutex_lock(&inst->lock);
    int kept = 0;
    XrEventDataBuffer tmp[MAX_EVENTS];
    for (int i = 0; i < inst->eventCount; i++) {
        XrEventDataBuffer& e = inst->events[(inst->eventHead + i) % MAX_EVENTS];
        if (e.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED && reinterpret_cast<XrEventDataSessionStateChanged&>(e).session == h) continue;
        tmp[kept++] = e;
    }
    for (int i = 0; i < kept; i++) inst->events[i] = tmp[i];
    inst->eventHead = 0; inst->eventCount = kept;
    pthread_mutex_unlock(&inst->lock);
    release(s);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL BeginSession(XrSession h, const XrSessionBeginInfo* bi) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!bi || bi->type != XR_TYPE_SESSION_BEGIN_INFO) return XR_ERROR_VALIDATION_FAILURE;
    if (!s->headless && bi->primaryViewConfigurationType != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    if (s->running) return XR_ERROR_SESSION_RUNNING;
    if (s->state != XR_SESSION_STATE_READY) return XR_ERROR_SESSION_NOT_READY;
    s->running = true;
    s->exitRequested = false;
    s->frameCount = 0;
    setState(s, XR_SESSION_STATE_SYNCHRONIZED);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL EndSession(XrSession h) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!s->running) return XR_ERROR_SESSION_NOT_RUNNING;
    if (s->state != XR_SESSION_STATE_STOPPING) return XR_ERROR_SESSION_NOT_STOPPING;
    s->running = false;
    pthread_mutex_lock(&s->frameLock);
    s->frameWaited = false; s->frameBegun = false;
    pthread_cond_broadcast(&s->frameCond);
    pthread_mutex_unlock(&s->frameLock);
    setState(s, XR_SESSION_STATE_IDLE);
    if (s->exitRequested) setState(s, XR_SESSION_STATE_EXITING);
    else setState(s, XR_SESSION_STATE_READY);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL RequestExitSession(XrSession h) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!s->running) return XR_ERROR_SESSION_NOT_RUNNING;
    s->exitRequested = true;
    if (s->state == XR_SESSION_STATE_FOCUSED) setState(s, XR_SESSION_STATE_VISIBLE);
    if (s->state == XR_SESSION_STATE_VISIBLE) setState(s, XR_SESSION_STATE_SYNCHRONIZED);
    setState(s, XR_SESSION_STATE_STOPPING);
    return XR_SUCCESS;
}

// ------------------------------------------------------------------ spaces
XRAPI_ATTR XrResult XRAPI_CALL EnumerateReferenceSpaces(XrSession h, uint32_t capacity, uint32_t* count, XrReferenceSpaceType* types) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    bool floor = s->instance->extLocalFloor || XR_VERSION_MINOR(s->instance->apiVersion) >= 1;
    XrReferenceSpaceType all[4] = {XR_REFERENCE_SPACE_TYPE_VIEW, XR_REFERENCE_SPACE_TYPE_LOCAL, XR_REFERENCE_SPACE_TYPE_STAGE, XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR};
    uint32_t n = floor ? 4 : 3;
    *count = n;
    if (capacity == 0) return XR_SUCCESS;
    if (capacity < n || !types) return XR_ERROR_SIZE_INSUFFICIENT;
    for (uint32_t i = 0; i < n; i++) types[i] = all[i];
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL CreateReferenceSpace(XrSession h, const XrReferenceSpaceCreateInfo* ci, XrSpace* out) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!ci || !out || ci->type != XR_TYPE_REFERENCE_SPACE_CREATE_INFO) return XR_ERROR_VALIDATION_FAILURE;
    bool floor = s->instance->extLocalFloor || XR_VERSION_MINOR(s->instance->apiVersion) >= 1;
    switch (ci->referenceSpaceType) {
        case XR_REFERENCE_SPACE_TYPE_VIEW: case XR_REFERENCE_SPACE_TYPE_LOCAL: case XR_REFERENCE_SPACE_TYPE_STAGE: break;
        case XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR: if (floor) break; return XR_ERROR_REFERENCE_SPACE_UNSUPPORTED;
        default: return XR_ERROR_REFERENCE_SPACE_UNSUPPORTED;
    }
    if (!hx::poseValid(ci->poseInReferenceSpace)) return XR_ERROR_POSE_INVALID;
    Space* sp = alloc<Space>(MAGIC_SPACE, s);
    if (!sp) return XR_ERROR_OUT_OF_MEMORY;
    sp->session = s;
    sp->type = ci->referenceSpaceType;
    sp->offset = ci->poseInReferenceSpace;
    s->liveSpaces++;
    *out = reinterpret_cast<XrSpace>(sp);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetReferenceSpaceBoundsRect(XrSession h, XrReferenceSpaceType t, XrExtent2Df* bounds) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!bounds) return XR_ERROR_VALIDATION_FAILURE;
    if (t != XR_REFERENCE_SPACE_TYPE_VIEW && t != XR_REFERENCE_SPACE_TYPE_LOCAL && t != XR_REFERENCE_SPACE_TYPE_STAGE && t != XR_REFERENCE_SPACE_TYPE_LOCAL_FLOOR)
        return XR_ERROR_REFERENCE_SPACE_UNSUPPORTED;
    bounds->width = 0; bounds->height = 0;
    return XR_SPACE_BOUNDS_UNAVAILABLE;  // no guardian is configured for a 3DoF viewer
}

XRAPI_ATTR XrResult XRAPI_CALL CreateActionSpace(XrSession h, const XrActionSpaceCreateInfo* ci, XrSpace* out) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!ci || !out || ci->type != XR_TYPE_ACTION_SPACE_CREATE_INFO) return XR_ERROR_VALIDATION_FAILURE;
    Action* a = asAction(ci->action);
    if (!a) return XR_ERROR_HANDLE_INVALID;
    if (a->type != XR_ACTION_TYPE_POSE_INPUT) return XR_ERROR_ACTION_TYPE_MISMATCH;
    if (!hx::poseValid(ci->poseInActionSpace)) return XR_ERROR_POSE_INVALID;
    Space* sp = alloc<Space>(MAGIC_SPACE, s);
    if (!sp) return XR_ERROR_OUT_OF_MEMORY;
    sp->session = s;
    sp->type = static_cast<XrReferenceSpaceType>(0);
    sp->action = a;
    sp->subaction = ci->subactionPath;
    sp->offset = ci->poseInActionSpace;
    s->liveSpaces++;
    *out = reinterpret_cast<XrSpace>(sp);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL DestroySpace(XrSpace h) {
    Space* sp = asSpace(h);
    if (!sp) return XR_ERROR_HANDLE_INVALID;
    sp->session->liveSpaces--;
    release(sp);
    return XR_SUCCESS;
}

XrResult locate(Space* sp, Space* base, XrTime time, XrSpaceLocation* loc) {
    XrPosef a, b;
    bool ta, tb;
    bool okA = spaceInStage(sp, time, &a, &ta);
    bool okB = spaceInStage(base, time, &b, &tb);
    XrSpaceVelocity* vel = nullptr;
    for (auto* n = reinterpret_cast<XrBaseOutStructure*>(loc->next); n; n = n->next)
        if (n->type == XR_TYPE_SPACE_VELOCITY) vel = reinterpret_cast<XrSpaceVelocity*>(n);
    if (vel) vel->velocityFlags = 0;
    if (!okA || !okB) { loc->locationFlags = 0; loc->pose = hx::identity(); return XR_SUCCESS; }
    loc->pose = hx::compose(hx::inverse(b), a);
    // orientation is tracked; position comes from a neck model (and controllers ride with the
    // head), so it is valid but not tracked
    bool viewInvolved = sp->type == XR_REFERENCE_SPACE_TYPE_VIEW || base->type == XR_REFERENCE_SPACE_TYPE_VIEW ||
                        sp->action != nullptr || base->action != nullptr;
    loc->locationFlags = XR_SPACE_LOCATION_ORIENTATION_VALID_BIT | XR_SPACE_LOCATION_POSITION_VALID_BIT |
                         XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT | (viewInvolved ? 0 : XR_SPACE_LOCATION_POSITION_TRACKED_BIT);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL LocateSpace(XrSpace h, XrSpace baseH, XrTime time, XrSpaceLocation* loc) {
    Space* sp = asSpace(h);
    Space* base = asSpace(baseH);
    if (!sp || !base) return XR_ERROR_HANDLE_INVALID;
    if (!loc || loc->type != XR_TYPE_SPACE_LOCATION) return XR_ERROR_VALIDATION_FAILURE;
    if (time <= 0) return XR_ERROR_TIME_INVALID;
    return locate(sp, base, time, loc);
}

XRAPI_ATTR XrResult XRAPI_CALL LocateSpaces(XrSession h, const XrSpacesLocateInfo* info, XrSpaceLocations* out) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || !out || info->type != XR_TYPE_SPACES_LOCATE_INFO || out->type != XR_TYPE_SPACE_LOCATIONS) return XR_ERROR_VALIDATION_FAILURE;
    if (info->time <= 0) return XR_ERROR_TIME_INVALID;
    if (out->locationCount != info->spaceCount || !out->locations) return XR_ERROR_VALIDATION_FAILURE;
    Space* base = asSpace(info->baseSpace);
    if (!base) return XR_ERROR_HANDLE_INVALID;
    for (uint32_t i = 0; i < info->spaceCount; i++) {
        Space* sp = asSpace(info->spaces[i]);
        if (!sp) return XR_ERROR_HANDLE_INVALID;
        XrSpaceLocation l{XR_TYPE_SPACE_LOCATION};
        locate(sp, base, info->time, &l);
        out->locations[i].locationFlags = l.locationFlags;
        out->locations[i].pose = l.pose;
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL LocateViews(XrSession h, const XrViewLocateInfo* info, XrViewState* state, uint32_t capacity, uint32_t* count, XrView* views) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || !state || !count || info->type != XR_TYPE_VIEW_LOCATE_INFO || state->type != XR_TYPE_VIEW_STATE) return XR_ERROR_VALIDATION_FAILURE;
    if (info->viewConfigurationType != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    if (info->displayTime <= 0) return XR_ERROR_TIME_INVALID;
    Space* base = asSpace(info->space);
    if (!base) return XR_ERROR_HANDLE_INVALID;
    *count = 2;
    if (capacity == 0) return XR_SUCCESS;
    if (capacity < 2 || !views) return XR_ERROR_SIZE_INSUFFICIENT;
    const HxConfig& cfg = s->instance->cfg;
    XrPosef head, b;
    bool tb;
    if (!hxp::headPose(info->displayTime, cfg, &head) || !spaceInStage(base, info->displayTime, &b, &tb)) {
        state->viewStateFlags = 0;
        for (int i = 0; i < 2; i++) { views[i].pose = hx::identity(); views[i].fov = eyeFov(cfg); }
        return XR_SUCCESS;
    }
    XrPosef baseInv = hx::inverse(b);
    for (int i = 0; i < 2; i++) {
        if (views[i].type != XR_TYPE_VIEW) return XR_ERROR_VALIDATION_FAILURE;
        XrPosef eye = hx::identity();
        eye.position.x = (i == 0 ? -0.5f : 0.5f) * cfg.ipdM;
        views[i].pose = hx::compose(baseInv, hx::compose(head, eye));
        views[i].fov = eyeFov(cfg);
    }
    state->viewStateFlags = XR_VIEW_STATE_ORIENTATION_VALID_BIT | XR_VIEW_STATE_POSITION_VALID_BIT | XR_VIEW_STATE_ORIENTATION_TRACKED_BIT;
    return XR_SUCCESS;
}

// ------------------------------------------------------------------ swapchains
XRAPI_ATTR XrResult XRAPI_CALL EnumerateSwapchainFormats(XrSession h, uint32_t capacity, uint32_t* count, int64_t* formats) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    if (s->headless) { *count = 0; return XR_SUCCESS; }
    int64_t tmp[16];
    uint32_t n = 0;
    hxp::gfxEnumerateFormats(tmp, 16, &n);
    *count = n;
    if (capacity == 0) return XR_SUCCESS;
    if (capacity < n || !formats) return XR_ERROR_SIZE_INSUFFICIENT;
    for (uint32_t i = 0; i < n; i++) formats[i] = tmp[i];
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL CreateSwapchain(XrSession h, const XrSwapchainCreateInfo* ci, XrSwapchain* out) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!ci || !out || ci->type != XR_TYPE_SWAPCHAIN_CREATE_INFO) return XR_ERROR_VALIDATION_FAILURE;
    if (s->headless) return XR_ERROR_FUNCTION_UNSUPPORTED;
    if (ci->faceCount != 1 && ci->faceCount != 6) return XR_ERROR_VALIDATION_FAILURE;
    if (ci->width == 0 || ci->height == 0 || ci->arraySize == 0 || ci->mipCount == 0 || ci->sampleCount == 0) return XR_ERROR_VALIDATION_FAILURE;
    if (ci->width > 4096 || ci->height > 4096) return XR_ERROR_SIZE_INSUFFICIENT;
    if (ci->faceCount == 6) return XR_ERROR_FEATURE_UNSUPPORTED;
    int64_t fmts[16]; uint32_t nf = 0;
    hxp::gfxEnumerateFormats(fmts, 16, &nf);
    bool known = false;
    for (uint32_t i = 0; i < nf; i++) if (fmts[i] == ci->format) known = true;
    if (!known) return XR_ERROR_SWAPCHAIN_FORMAT_UNSUPPORTED;
    Swapchain* sc = alloc<Swapchain>(MAGIC_SWAPCHAIN, s);
    if (!sc) return XR_ERROR_OUT_OF_MEMORY;
    XrResult r = hxp::gfxCreateSwapchain(s->gfx, ci, &sc->gfx, &sc->imageCount);
    if (XR_FAILED(r)) { release(sc); return r; }
    sc->session = s;
    sc->width = ci->width; sc->height = ci->height; sc->arraySize = ci->arraySize;
    sc->waitedIndex = -1;
    sc->lastReleased = -1;
    sc->staticImage = (ci->createFlags & XR_SWAPCHAIN_CREATE_STATIC_IMAGE_BIT) != 0;
    s->liveSwapchains++;
    *out = reinterpret_cast<XrSwapchain>(sc);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL DestroySwapchain(XrSwapchain h) {
    Swapchain* sc = asSwapchain(h);
    if (!sc) return XR_ERROR_HANDLE_INVALID;
    hxp::gfxDestroySwapchain(sc->gfx);
    sc->session->liveSwapchains--;
    release(sc);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL EnumerateSwapchainImages(XrSwapchain h, uint32_t capacity, uint32_t* count, XrSwapchainImageBaseHeader* images) {
    Swapchain* sc = asSwapchain(h);
    if (!sc) return XR_ERROR_HANDLE_INVALID;
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    return hxp::gfxEnumerateImages(sc->gfx, capacity, count, images);
}

XRAPI_ATTR XrResult XRAPI_CALL AcquireSwapchainImage(XrSwapchain h, const XrSwapchainImageAcquireInfo* info, uint32_t* index) {
    Swapchain* sc = asSwapchain(h);
    if (!sc) return XR_ERROR_HANDLE_INVALID;
    if (!index || (info && info->type != XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO)) return XR_ERROR_VALIDATION_FAILURE;
    if (sc->staticImage && sc->staticReleased) return XR_ERROR_CALL_ORDER_INVALID;
    int busy = sc->acquiredCount + (sc->waitedIndex >= 0 ? 1 : 0);
    if (busy >= static_cast<int>(sc->imageCount)) return XR_ERROR_CALL_ORDER_INVALID;
    uint32_t i = sc->nextAcquire;
    sc->nextAcquire = (sc->nextAcquire + 1) % sc->imageCount;
    sc->acquired[sc->acquiredCount++] = static_cast<int>(i);
    *index = i;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL WaitSwapchainImage(XrSwapchain h, const XrSwapchainImageWaitInfo* info) {
    Swapchain* sc = asSwapchain(h);
    if (!sc) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO) return XR_ERROR_VALIDATION_FAILURE;
    if (sc->acquiredCount == 0 || sc->waitedIndex >= 0) return XR_ERROR_CALL_ORDER_INVALID;
    // images are never held by the compositor beyond xrEndFrame, so they are always ready
    sc->waitedIndex = sc->acquired[0];
    for (int k = 1; k < sc->acquiredCount; k++) sc->acquired[k - 1] = sc->acquired[k];
    sc->acquiredCount--;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL ReleaseSwapchainImage(XrSwapchain h, const XrSwapchainImageReleaseInfo* info) {
    Swapchain* sc = asSwapchain(h);
    if (!sc) return XR_ERROR_HANDLE_INVALID;
    if (info && info->type != XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO) return XR_ERROR_VALIDATION_FAILURE;
    if (sc->waitedIndex < 0) return XR_ERROR_CALL_ORDER_INVALID;
    hxp::gfxReleaseImage(sc->gfx, static_cast<uint32_t>(sc->waitedIndex));
    sc->lastReleased = sc->waitedIndex;
    sc->waitedIndex = -1;
    if (sc->staticImage) sc->staticReleased = true;
    return XR_SUCCESS;
}

// ------------------------------------------------------------------ frame loop
int64_t periodNs(const Instance* inst) {
    float hz = inst->cfg.refreshHz > 20 ? inst->cfg.refreshHz : 60.0f;
    return static_cast<int64_t>(1e9 / hz);
}

void sleepUntil(int64_t t) {
    int64_t now = hxp::nowNs();
    if (t <= now) return;
    struct timespec ts;
    ts.tv_sec = static_cast<time_t>((t - now) / 1000000000LL);
    ts.tv_nsec = static_cast<long>((t - now) % 1000000000LL);
    nanosleep(&ts, nullptr);
}

XRAPI_ATTR XrResult XRAPI_CALL WaitFrame(XrSession h, const XrFrameWaitInfo* info, XrFrameState* fs) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if ((info && info->type != XR_TYPE_FRAME_WAIT_INFO) || !fs || fs->type != XR_TYPE_FRAME_STATE) return XR_ERROR_VALIDATION_FAILURE;
    if (!s->running) return XR_ERROR_SESSION_NOT_RUNNING;
    int64_t period = periodNs(s->instance);
    // Only one frame may be in flight between xrWaitFrame and xrBeginFrame: a second wait blocks.
    pthread_mutex_lock(&s->frameLock);
    while (s->frameWaited && s->running) pthread_cond_wait(&s->frameCond, &s->frameLock);
    pthread_mutex_unlock(&s->frameLock);
    if (!s->running) return XR_ERROR_SESSION_NOT_RUNNING;
    // Pace to the display: the compositor swaps with vsync, so one period between frames.
    if (s->lastWaitNs) sleepUntil(s->lastWaitNs + period - 1000000);
    int64_t now = hxp::nowNs();
    s->lastWaitNs = now;
    // app render (~1 period) + composite and scan-out (~1 period)
    XrTime predicted = now + 2 * period;
    if (predicted <= s->lastPredicted) predicted = s->lastPredicted + 1;
    s->lastPredicted = predicted;
    fs->predictedDisplayTime = predicted;
    fs->predictedDisplayPeriod = period;
    fs->shouldRender = (s->state == XR_SESSION_STATE_VISIBLE || s->state == XR_SESSION_STATE_FOCUSED) ? XR_TRUE : XR_FALSE;
    pthread_mutex_lock(&s->frameLock);
    s->frameWaited = true;
    pthread_mutex_unlock(&s->frameLock);
    // After the first synchronized frame the session becomes visible and focused.
    if (s->state == XR_SESSION_STATE_SYNCHRONIZED && s->frameCount > 0 && !s->exitRequested) {
        setState(s, XR_SESSION_STATE_VISIBLE);
        setState(s, XR_SESSION_STATE_FOCUSED);
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL BeginFrame(XrSession h, const XrFrameBeginInfo* info) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (info && info->type != XR_TYPE_FRAME_BEGIN_INFO) return XR_ERROR_VALIDATION_FAILURE;
    if (!s->running) return XR_ERROR_SESSION_NOT_RUNNING;
    pthread_mutex_lock(&s->frameLock);
    if (!s->frameWaited) { pthread_mutex_unlock(&s->frameLock); return XR_ERROR_CALL_ORDER_INVALID; }
    bool discarded = s->frameBegun;
    s->frameWaited = false;
    s->frameBegun = true;
    pthread_cond_broadcast(&s->frameCond);
    pthread_mutex_unlock(&s->frameLock);
    return discarded ? XR_FRAME_DISCARDED : XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL EndFrame(XrSession h, const XrFrameEndInfo* info) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_FRAME_END_INFO) return XR_ERROR_VALIDATION_FAILURE;
    if (!s->running) return XR_ERROR_SESSION_NOT_RUNNING;
    if (!s->frameBegun) return XR_ERROR_CALL_ORDER_INVALID;
    if (info->displayTime <= 0) return XR_ERROR_TIME_INVALID;
    if (info->environmentBlendMode != XR_ENVIRONMENT_BLEND_MODE_OPAQUE) return XR_ERROR_ENVIRONMENT_BLEND_MODE_UNSUPPORTED;
    if (info->layerCount > MAX_LAYERS) return XR_ERROR_LAYER_LIMIT_EXCEEDED;
    if (info->layerCount > 0 && !info->layers) return XR_ERROR_VALIDATION_FAILURE;

    HxComposeView views[2];
    uint32_t viewCount = 0;
    HxComposeQuad quads[MAX_LAYERS];
    uint32_t quadCount = 0;
    const HxConfig& cfg = s->instance->cfg;
    for (uint32_t i = 0; i < info->layerCount; i++) {
        const XrCompositionLayerBaseHeader* l = info->layers[i];
        if (!l) return XR_ERROR_LAYER_INVALID;
        Space* sp = asSpace(l->space);
        if (!sp) return XR_ERROR_HANDLE_INVALID;
        XrPosef spacePose; bool tracked;
        bool located = spaceInStage(sp, info->displayTime, &spacePose, &tracked);
        if (l->type == XR_TYPE_COMPOSITION_LAYER_PROJECTION) {
            auto* p = reinterpret_cast<const XrCompositionLayerProjection*>(l);
            if (p->viewCount != 2 || !p->views) return XR_ERROR_VALIDATION_FAILURE;
            if (viewCount != 0) continue;  // one projection layer is composited (the first)
            for (uint32_t v = 0; v < 2; v++) {
                const XrCompositionLayerProjectionView& pv = p->views[v];
                if (pv.type != XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW) return XR_ERROR_VALIDATION_FAILURE;
                Swapchain* sc = asSwapchain(pv.subImage.swapchain);
                if (!sc) return XR_ERROR_HANDLE_INVALID;
                if (sc->lastReleased < 0) return XR_ERROR_LAYER_INVALID;
                if (pv.subImage.imageArrayIndex >= sc->arraySize) return XR_ERROR_VALIDATION_FAILURE;
                const XrRect2Di& r = pv.subImage.imageRect;
                if (r.offset.x < 0 || r.offset.y < 0 || r.extent.width <= 0 || r.extent.height <= 0 ||
                    static_cast<uint32_t>(r.offset.x + r.extent.width) > sc->width || static_cast<uint32_t>(r.offset.y + r.extent.height) > sc->height)
                    return XR_ERROR_SWAPCHAIN_RECT_INVALID;
                if (!hx::poseValid(pv.pose)) return XR_ERROR_POSE_INVALID;
                if (!located) continue;
                HxComposeView& cv = views[v];
                cv.swapchain = sc->gfx;
                cv.imageIndex = static_cast<uint32_t>(sc->lastReleased);
                cv.arraySlice = pv.subImage.imageArrayIndex;
                cv.rect = r;
                cv.pose = hx::compose(spacePose, pv.pose);
                cv.fov = pv.fov;
            }
            if (located) viewCount = 2;
        } else if (l->type == XR_TYPE_COMPOSITION_LAYER_QUAD) {
            auto* q = reinterpret_cast<const XrCompositionLayerQuad*>(l);
            Swapchain* sc = asSwapchain(q->subImage.swapchain);
            if (!sc) return XR_ERROR_HANDLE_INVALID;
            if (sc->lastReleased < 0) return XR_ERROR_LAYER_INVALID;
            if (!hx::poseValid(q->pose)) return XR_ERROR_POSE_INVALID;
            if (!located) continue;
            HxComposeQuad& cq = quads[quadCount++];
            cq.swapchain = sc->gfx;
            cq.imageIndex = static_cast<uint32_t>(sc->lastReleased);
            cq.arraySlice = q->subImage.imageArrayIndex;
            cq.rect = q->subImage.imageRect;
            cq.pose = hx::compose(spacePose, q->pose);
            cq.size = q->size;
            cq.headLocked = 0;
            cq.blendAlpha = (q->layerFlags & XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT) != 0;
        } else {
            return XR_ERROR_LAYER_INVALID;
        }
    }
    if (s->gfx) {
        XrPosef head;
        // late latch: the newest head pose for timewarp, predicted to this frame's display time
        if (!hxp::headPose(info->displayTime, cfg, &head)) head = s->lastHead;
        s->lastHead = head;
        hxp::gfxCompose(s->gfx, cfg, head, views, viewCount, quads, quadCount);
    }
    s->frameCount++;
    pthread_mutex_lock(&s->frameLock);
    s->frameBegun = false;
    pthread_mutex_unlock(&s->frameLock);
    return XR_SUCCESS;
}

// ------------------------------------------------------------------ paths & actions
XRAPI_ATTR XrResult XRAPI_CALL StringToPath(XrInstance h, const char* str, XrPath* out) {
    Instance* inst = asInstance(h);
    if (!inst) return XR_ERROR_HANDLE_INVALID;
    if (!str || !out) return XR_ERROR_VALIDATION_FAILURE;
    return stringToPath(inst, str, out);
}

XRAPI_ATTR XrResult XRAPI_CALL PathToString(XrInstance h, XrPath path, uint32_t capacity, uint32_t* count, char* buffer) {
    Instance* inst = asInstance(h);
    if (!inst) return XR_ERROR_HANDLE_INVALID;
    const char* s = pathString(inst, path);
    if (!s) return XR_ERROR_PATH_INVALID;
    return outString(s, capacity, count, buffer);
}

XRAPI_ATTR XrResult XRAPI_CALL CreateActionSet(XrInstance h, const XrActionSetCreateInfo* ci, XrActionSet* out) {
    Instance* inst = asInstance(h);
    if (!inst) return XR_ERROR_HANDLE_INVALID;
    if (!ci || !out || ci->type != XR_TYPE_ACTION_SET_CREATE_INFO) return XR_ERROR_VALIDATION_FAILURE;
    if (!validName(ci->actionSetName, XR_MAX_ACTION_SET_NAME_SIZE)) return XR_ERROR_PATH_FORMAT_INVALID;
    if (strnlen(ci->localizedActionSetName, XR_MAX_LOCALIZED_ACTION_SET_NAME_SIZE) == 0) return XR_ERROR_LOCALIZED_NAME_INVALID;
    ActionSet* set = alloc<ActionSet>(MAGIC_ACTIONSET, inst);
    if (!set) return XR_ERROR_OUT_OF_MEMORY;
    set->instance = inst;
    copyStr(set->name, sizeof(set->name), ci->actionSetName);
    copyStr(set->localized, sizeof(set->localized), ci->localizedActionSetName);
    set->priority = ci->priority;
    inst->actionSets++;
    *out = reinterpret_cast<XrActionSet>(set);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL DestroyActionSet(XrActionSet h) {
    ActionSet* set = asActionSet(h);
    if (!set) return XR_ERROR_HANDLE_INVALID;
    set->instance->actionSets--;
    // actions belong to their set and are destroyed with it
    while (void* a = takeChild(set, MAGIC_ACTION)) { dropBindings(set->instance, a); static_cast<Action*>(a)->magic = 0; free(a); }
    release(set);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL CreateAction(XrActionSet h, const XrActionCreateInfo* ci, XrAction* out) {
    ActionSet* set = asActionSet(h);
    if (!set) return XR_ERROR_HANDLE_INVALID;
    if (!ci || !out || ci->type != XR_TYPE_ACTION_CREATE_INFO) return XR_ERROR_VALIDATION_FAILURE;
    if (set->attached) return XR_ERROR_ACTIONSETS_ALREADY_ATTACHED;
    if (!validName(ci->actionName, XR_MAX_ACTION_NAME_SIZE)) return XR_ERROR_PATH_FORMAT_INVALID;
    if (strnlen(ci->localizedActionName, XR_MAX_LOCALIZED_ACTION_NAME_SIZE) == 0) return XR_ERROR_LOCALIZED_NAME_INVALID;
    switch (ci->actionType) {
        case XR_ACTION_TYPE_BOOLEAN_INPUT: case XR_ACTION_TYPE_FLOAT_INPUT: case XR_ACTION_TYPE_VECTOR2F_INPUT:
        case XR_ACTION_TYPE_POSE_INPUT: case XR_ACTION_TYPE_VIBRATION_OUTPUT: break;
        default: return XR_ERROR_VALIDATION_FAILURE;
    }
    if (ci->countSubactionPaths > 8) return XR_ERROR_PATH_UNSUPPORTED;
    Action* a = alloc<Action>(MAGIC_ACTION, set);
    if (!a) return XR_ERROR_OUT_OF_MEMORY;
    a->set = set;
    a->type = ci->actionType;
    copyStr(a->name, sizeof(a->name), ci->actionName);
    for (uint32_t i = 0; i < ci->countSubactionPaths; i++) {
        if (!pathString(set->instance, ci->subactionPaths[i])) { release(a); return XR_ERROR_PATH_INVALID; }
        a->subactions[i] = ci->subactionPaths[i];
    }
    a->subactionCount = ci->countSubactionPaths;
    set->actionCount++;
    *out = reinterpret_cast<XrAction>(a);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL DestroyAction(XrAction h) {
    Action* a = asAction(h);
    if (!a) return XR_ERROR_HANDLE_INVALID;
    dropBindings(a->set->instance, a);
    release(a);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL SuggestInteractionProfileBindings(XrInstance h, const XrInteractionProfileSuggestedBinding* b) {
    Instance* inst = asInstance(h);
    if (!inst) return XR_ERROR_HANDLE_INVALID;
    if (!b || b->type != XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING) return XR_ERROR_VALIDATION_FAILURE;
    const char* profile = pathString(inst, b->interactionProfile);
    if (!profile) return XR_ERROR_PATH_INVALID;
    if (strncmp(profile, "/interaction_profiles/", 22) != 0) return XR_ERROR_PATH_UNSUPPORTED;
    if (b->countSuggestedBindings == 0 || !b->suggestedBindings) return XR_ERROR_VALIDATION_FAILURE;
    for (uint32_t i = 0; i < b->countSuggestedBindings; i++) {
        Action* a = asAction(b->suggestedBindings[i].action);
        if (!a) return XR_ERROR_HANDLE_INVALID;
        if (a->set->attached) return XR_ERROR_ACTIONSETS_ALREADY_ATTACHED;
        const char* binding = pathString(inst, b->suggestedBindings[i].binding);
        if (!binding || strncmp(binding, "/user/", 6) != 0) return XR_ERROR_PATH_UNSUPPORTED;
    }
    // Profiles this runtime drives keep their bindings; a new suggestion replaces the old one.
    if (!profileSupported(profile)) return XR_SUCCESS;
    pthread_mutex_lock(&inst->lock);
    int kept = 0;
    for (int i = 0; i < inst->bindingCount; i++)
        if (inst->bindings[i].profile != b->interactionProfile) inst->bindings[kept++] = inst->bindings[i];
    inst->bindingCount = kept;
    for (uint32_t i = 0; i < b->countSuggestedBindings && inst->bindingCount < MAX_BINDINGS; i++)
        inst->bindings[inst->bindingCount++] = {b->interactionProfile, asAction(b->suggestedBindings[i].action), b->suggestedBindings[i].binding};
    pthread_mutex_unlock(&inst->lock);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL AttachSessionActionSets(XrSession h, const XrSessionActionSetsAttachInfo* info) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO || info->countActionSets == 0 || !info->actionSets) return XR_ERROR_VALIDATION_FAILURE;
    if (s->actionsAttached) return XR_ERROR_ACTIONSETS_ALREADY_ATTACHED;
    if (info->countActionSets > 16) return XR_ERROR_LIMIT_REACHED;
    for (uint32_t i = 0; i < info->countActionSets; i++) if (!asActionSet(info->actionSets[i])) return XR_ERROR_HANDLE_INVALID;
    for (uint32_t i = 0; i < info->countActionSets; i++) {
        ActionSet* set = asActionSet(info->actionSets[i]);
        set->attached = true;
        s->attachedSets[s->attachedCount++] = set;
    }
    s->actionsAttached = true;
    // the most preferred profile the app has bindings for (for actions of the attached sets)
    Instance* inst = s->instance;
    s->profile = XR_NULL_PATH;
    for (const char* name : kProfiles) {
        XrPath p;
        if (XR_FAILED(stringToPath(inst, name, &p))) continue;
        for (int i = 0; i < inst->bindingCount && s->profile == XR_NULL_PATH; i++) {
            const auto& bd = inst->bindings[i];
            if (bd.profile != p || !isLive(bd.action, MAGIC_ACTION)) continue;
            if (actionAttachedTo(s, static_cast<Action*>(bd.action))) s->profile = p;
        }
        if (s->profile != XR_NULL_PATH) break;
    }
    XrEventDataInteractionProfileChanged ev{XR_TYPE_EVENT_DATA_INTERACTION_PROFILE_CHANGED};
    ev.session = h;
    pushEvent(s->instance, &ev, sizeof(ev));
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetCurrentInteractionProfile(XrSession h, XrPath topLevel, XrInteractionProfileState* st) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!st || st->type != XR_TYPE_INTERACTION_PROFILE_STATE) return XR_ERROR_VALIDATION_FAILURE;
    if (!s->actionsAttached) return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    const char* p = pathString(s->instance, topLevel);
    if (!p || strncmp(p, "/user/", 6) != 0) return XR_ERROR_PATH_UNSUPPORTED;
    st->interactionProfile = handOf(p, nullptr) >= 0 && strchr(p + 11, '/') == nullptr ? s->profile : XR_NULL_PATH;
    return XR_SUCCESS;
}

bool actionAttached(Session* s, Action* a) { return actionAttachedTo(s, a); }

template <typename State> XrResult actionState(XrSession h, const XrActionStateGetInfo* info, State* st, XrStructureType stType, XrActionType want) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || !st || info->type != XR_TYPE_ACTION_STATE_GET_INFO || st->type != stType) return XR_ERROR_VALIDATION_FAILURE;
    Action* a = asAction(info->action);
    if (!a) return XR_ERROR_HANDLE_INVALID;
    if (a->type != want) return XR_ERROR_ACTION_TYPE_MISMATCH;
    if (!actionAttached(s, a)) return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    if (info->subactionPath != XR_NULL_PATH) {
        bool found = false;
        for (uint32_t i = 0; i < a->subactionCount; i++) if (a->subactions[i] == info->subactionPath) found = true;
        if (!found) return XR_ERROR_PATH_UNSUPPORTED;
    }
    int slot = slotOf(s->instance, info->subactionPath);
    const ActionValue& v = slot < 3 ? a->state[slot] : ActionValue{};
    st->isActive = v.active ? XR_TRUE : XR_FALSE;
    return XR_SUCCESS;
}

/** The synced value behind a successful actionState() call. */
const ActionValue& synced(XrSession h, const XrActionStateGetInfo* info) {
    static const ActionValue none{};
    Session* s = asSession(h);
    Action* a = asAction(info->action);
    int slot = slotOf(s->instance, info->subactionPath);
    return slot < 3 ? a->state[slot] : none;
}

XRAPI_ATTR XrResult XRAPI_CALL GetActionStateBoolean(XrSession h, const XrActionStateGetInfo* i, XrActionStateBoolean* st) {
    XrResult r = actionState(h, i, st, XR_TYPE_ACTION_STATE_BOOLEAN, XR_ACTION_TYPE_BOOLEAN_INPUT);
    if (XR_SUCCEEDED(r)) {
        const ActionValue& v = synced(h, i);
        st->currentState = v.active && v.v[0] > 0.5f; st->changedSinceLastSync = v.active && v.changed; st->lastChangeTime = v.lastChange;
    }
    return r;
}
XRAPI_ATTR XrResult XRAPI_CALL GetActionStateFloat(XrSession h, const XrActionStateGetInfo* i, XrActionStateFloat* st) {
    XrResult r = actionState(h, i, st, XR_TYPE_ACTION_STATE_FLOAT, XR_ACTION_TYPE_FLOAT_INPUT);
    if (XR_SUCCEEDED(r)) {
        const ActionValue& v = synced(h, i);
        st->currentState = v.active ? v.v[0] : 0; st->changedSinceLastSync = v.active && v.changed; st->lastChangeTime = v.lastChange;
    }
    return r;
}
XRAPI_ATTR XrResult XRAPI_CALL GetActionStateVector2f(XrSession h, const XrActionStateGetInfo* i, XrActionStateVector2f* st) {
    XrResult r = actionState(h, i, st, XR_TYPE_ACTION_STATE_VECTOR2F, XR_ACTION_TYPE_VECTOR2F_INPUT);
    if (XR_SUCCEEDED(r)) {
        const ActionValue& v = synced(h, i);
        st->currentState = v.active ? XrVector2f{v.v[0], v.v[1]} : XrVector2f{0, 0};
        st->changedSinceLastSync = v.active && v.changed; st->lastChangeTime = v.lastChange;
    }
    return r;
}
XRAPI_ATTR XrResult XRAPI_CALL GetActionStatePose(XrSession h, const XrActionStateGetInfo* i, XrActionStatePose* st) {
    return actionState(h, i, st, XR_TYPE_ACTION_STATE_POSE, XR_ACTION_TYPE_POSE_INPUT);
}

XRAPI_ATTR XrResult XRAPI_CALL SyncActions(XrSession h, const XrActionsSyncInfo* info) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_ACTIONS_SYNC_INFO) return XR_ERROR_VALIDATION_FAILURE;
    for (uint32_t i = 0; i < info->countActiveActionSets; i++) {
        ActionSet* set = asActionSet(info->activeActionSets[i].actionSet);
        if (!set) return XR_ERROR_HANDLE_INVALID;
        bool attached = false;
        for (uint32_t k = 0; k < s->attachedCount; k++) if (s->attachedSets[k] == set) attached = true;
        if (!attached) return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    }
    bool focused = s->state == XR_SESSION_STATE_FOCUSED;
    if (focused) hxp::readInput(&s->input);
    XrTime now = hxp::nowNs();
    // every attached action is updated; those outside the active sets (or unfocused) go inactive
    for (uint32_t k = 0; k < s->attachedCount; k++) {
        ActionSet* set = s->attachedSets[k];
        bool activeSet = false;
        for (uint32_t i = 0; i < info->countActiveActionSets; i++) if (asActionSet(info->activeActionSets[i].actionSet) == set) activeSet = true;
        forEachChild(set, MAGIC_ACTION, [&](void* p) {
            Action* a = static_cast<Action*>(p);
            for (int slot = 0; slot < 3; slot++) {
                ActionValue nv = (focused && activeSet) ? evalAction(s, a, slot) : ActionValue{};
                ActionValue& old = a->state[slot];
                bool changed = nv.active && old.active && (nv.v[0] != old.v[0] || nv.v[1] != old.v[1]);
                nv.changed = changed;
                nv.lastChange = changed ? now : (nv.active && !old.active ? now : old.lastChange);
                old = nv;
            }
        });
    }
    if (!focused) return XR_SESSION_NOT_FOCUSED;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL EnumerateBoundSourcesForAction(XrSession h, const XrBoundSourcesForActionEnumerateInfo* info, uint32_t capacity, uint32_t* count, XrPath* paths) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || !count || info->type != XR_TYPE_BOUND_SOURCES_FOR_ACTION_ENUMERATE_INFO) return XR_ERROR_VALIDATION_FAILURE;
    Action* a = asAction(info->action);
    if (!a) return XR_ERROR_HANDLE_INVALID;
    if (!actionAttached(s, a)) return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    XrPath found[32];
    uint32_t n = 0;
    Instance* inst = s->instance;
    bool simple = isSimpleProfile(inst, s->profile);
    for (int i = 0; i < inst->bindingCount && n < 32; i++) {
        const auto& b = inst->bindings[i];
        if (s->profile == XR_NULL_PATH || b.profile != s->profile || b.action != a) continue;
        int hand; float v[2];
        const char* path = pathString(inst, b.path);
        if (path && evalSource(path, simple, s->input, &hand, v) != SRC_NONE) found[n++] = b.path;
    }
    *count = n;
    if (capacity == 0) return XR_SUCCESS;
    if (capacity < n || !paths) return XR_ERROR_SIZE_INSUFFICIENT;
    for (uint32_t i = 0; i < n; i++) paths[i] = found[i];
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetInputSourceLocalizedName(XrSession h, const XrInputSourceLocalizedNameGetInfo* info, uint32_t capacity, uint32_t* count, char* buffer) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_INPUT_SOURCE_LOCALIZED_NAME_GET_INFO) return XR_ERROR_VALIDATION_FAILURE;
    if (!s->actionsAttached) return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    const char* p = pathString(s->instance, info->sourcePath);
    if (!p) return XR_ERROR_PATH_INVALID;
    int hand = handOf(p, nullptr);
    const char* name = hand == 0 ? "Controle esquerdo (Joy-Con/gamepad)" : hand == 1 ? "Controle direito (Joy-Con/gamepad)" : "Entrada";
    return outString(name, capacity, count, buffer);
}

XRAPI_ATTR XrResult XRAPI_CALL ApplyHapticFeedback(XrSession h, const XrHapticActionInfo* info, const XrHapticBaseHeader* fb) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || !fb || info->type != XR_TYPE_HAPTIC_ACTION_INFO) return XR_ERROR_VALIDATION_FAILURE;
    Action* a = asAction(info->action);
    if (!a) return XR_ERROR_HANDLE_INVALID;
    if (a->type != XR_ACTION_TYPE_VIBRATION_OUTPUT) return XR_ERROR_ACTION_TYPE_MISMATCH;
    if (!actionAttached(s, a)) return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    if (s->state != XR_SESSION_STATE_FOCUSED) return XR_SESSION_NOT_FOCUSED;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL StopHapticFeedback(XrSession h, const XrHapticActionInfo* info) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_HAPTIC_ACTION_INFO) return XR_ERROR_VALIDATION_FAILURE;
    Action* a = asAction(info->action);
    if (!a) return XR_ERROR_HANDLE_INVALID;
    if (a->type != XR_ACTION_TYPE_VIBRATION_OUTPUT) return XR_ERROR_ACTION_TYPE_MISMATCH;
    if (!actionAttached(s, a)) return XR_ERROR_ACTIONSET_NOT_ATTACHED;
    if (s->state != XR_SESSION_STATE_FOCUSED) return XR_SESSION_NOT_FOCUSED;
    return XR_SUCCESS;
}

// ------------------------------------------------------------------ Meta (FB) and EXT extensions
/** The phone's one display rate (HyperMagic sets the screen to its highest rate itself). */
float displayHz(const Instance* inst) { return inst->cfg.refreshHz > 20 ? inst->cfg.refreshHz : 60.0f; }

XRAPI_ATTR XrResult XRAPI_CALL EnumerateDisplayRefreshRatesFB(XrSession h, uint32_t capacity, uint32_t* count, float* rates) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    *count = 1;
    if (capacity == 0) return XR_SUCCESS;
    if (!rates) return XR_ERROR_VALIDATION_FAILURE;
    rates[0] = displayHz(s->instance);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetDisplayRefreshRateFB(XrSession h, float* rate) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!rate) return XR_ERROR_VALIDATION_FAILURE;
    *rate = displayHz(s->instance);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL RequestDisplayRefreshRateFB(XrSession h, float rate) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    // 0 means "the system's choice"; any other rate must be one we listed
    if (rate == 0.0f || fabsf(rate - displayHz(s->instance)) < 0.5f) return XR_SUCCESS;
    return XR_ERROR_DISPLAY_REFRESH_RATE_UNSUPPORTED_FB;
}

XRAPI_ATTR XrResult XRAPI_CALL EnumerateColorSpacesFB(XrSession h, uint32_t capacity, uint32_t* count, XrColorSpaceFB* spaces) {
    if (!asSession(h)) return XR_ERROR_HANDLE_INVALID;
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    *count = 1;
    if (capacity == 0) return XR_SUCCESS;
    if (!spaces) return XR_ERROR_VALIDATION_FAILURE;
    spaces[0] = XR_COLOR_SPACE_REC709_FB;  // a phone screen: sRGB primaries
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL SetColorSpaceFB(XrSession h, XrColorSpaceFB space) {
    if (!asSession(h)) return XR_ERROR_HANDLE_INVALID;
    // the app's colours are shown as they are; asking for another gamut is allowed but changes nothing
    return space == XR_COLOR_SPACE_UNMANAGED_FB || space == XR_COLOR_SPACE_REC709_FB || space == XR_COLOR_SPACE_REC2020_FB ||
           space == XR_COLOR_SPACE_RIFT_CV1_FB || space == XR_COLOR_SPACE_RIFT_S_FB || space == XR_COLOR_SPACE_QUEST_FB ||
           space == XR_COLOR_SPACE_P3_FB || space == XR_COLOR_SPACE_ADOBE_RGB_FB ? XR_SUCCESS : XR_ERROR_COLOR_SPACE_UNSUPPORTED_FB;
}

XRAPI_ATTR XrResult XRAPI_CALL UpdateSwapchainFB(XrSwapchain h, const XrSwapchainStateBaseHeaderFB* state) {
    if (!asSwapchain(h)) return XR_ERROR_HANDLE_INVALID;
    if (!state) return XR_ERROR_VALIDATION_FAILURE;
    return XR_SUCCESS;  // foveation and sampler state: the whole image is always used as drawn
}

XRAPI_ATTR XrResult XRAPI_CALL GetSwapchainStateFB(XrSwapchain h, XrSwapchainStateBaseHeaderFB* state) {
    if (!asSwapchain(h)) return XR_ERROR_HANDLE_INVALID;
    if (!state) return XR_ERROR_VALIDATION_FAILURE;
    if (state->type == XR_TYPE_SWAPCHAIN_STATE_FOVEATION_FB) {
        auto* f = reinterpret_cast<XrSwapchainStateFoveationFB*>(state);
        f->flags = 0; f->profile = XR_NULL_HANDLE;
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL CreateFoveationProfileFB(XrSession h, const XrFoveationProfileCreateInfoFB* ci, XrFoveationProfileFB* out) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!ci || ci->type != XR_TYPE_FOVEATION_PROFILE_CREATE_INFO_FB || !out) return XR_ERROR_VALIDATION_FAILURE;
    Foveation* f = alloc<Foveation>(MAGIC_FOVEATION, s);
    if (!f) return XR_ERROR_OUT_OF_MEMORY;
    *out = reinterpret_cast<XrFoveationProfileFB>(f);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL DestroyFoveationProfileFB(XrFoveationProfileFB h) {
    Foveation* f = asObj<Foveation>(reinterpret_cast<uint64_t>(h), MAGIC_FOVEATION);
    if (!f) return XR_ERROR_HANDLE_INVALID;
    release(f);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL PerfSettingsSetPerformanceLevelEXT(XrSession h, XrPerfSettingsDomainEXT domain, XrPerfSettingsLevelEXT level) {
    if (!asSession(h)) return XR_ERROR_HANDLE_INVALID;
    if (domain != XR_PERF_SETTINGS_DOMAIN_CPU_EXT && domain != XR_PERF_SETTINGS_DOMAIN_GPU_EXT) return XR_ERROR_VALIDATION_FAILURE;
    return XR_SUCCESS;  // Android decides the clocks; the hint is accepted
}

#ifdef __ANDROID__
XRAPI_ATTR XrResult XRAPI_CALL SetAndroidApplicationThreadKHR(XrSession h, XrAndroidThreadTypeKHR type, uint32_t tid) {
    if (!asSession(h)) return XR_ERROR_HANDLE_INVALID;
    if (type < XR_ANDROID_THREAD_TYPE_APPLICATION_MAIN_KHR || type > XR_ANDROID_THREAD_TYPE_RENDERER_WORKER_KHR) return XR_ERROR_VALIDATION_FAILURE;
    return XR_SUCCESS;
}
#endif

// ------------------------------------------------------------------ dispatch
XRAPI_ATTR XrResult XRAPI_CALL GetInstanceProcAddr(XrInstance instance, const char* name, PFN_xrVoidFunction* function);

struct Entry { const char* name; PFN_xrVoidFunction fn; int ext; const char* extName = nullptr; };
enum { EXT_NONE = 0, EXT_GLES, EXT_TIMESPEC, EXT_V11 };

#define HX_FN(n, e) {"xr" #n, reinterpret_cast<PFN_xrVoidFunction>(n), e}
#define HX_EXT(n, ext) {"xr" #n, reinterpret_cast<PFN_xrVoidFunction>(n), EXT_NONE, ext}
const Entry kEntries[] = {
    HX_FN(GetInstanceProcAddr, EXT_NONE),
    HX_FN(EnumerateApiLayerProperties, EXT_NONE),
    HX_FN(EnumerateInstanceExtensionProperties, EXT_NONE),
    HX_FN(CreateInstance, EXT_NONE),
    HX_FN(DestroyInstance, EXT_NONE),
    HX_FN(GetInstanceProperties, EXT_NONE),
    HX_FN(PollEvent, EXT_NONE),
    HX_FN(ResultToString, EXT_NONE),
    HX_FN(StructureTypeToString, EXT_NONE),
    HX_FN(GetSystem, EXT_NONE),
    HX_FN(GetSystemProperties, EXT_NONE),
    HX_FN(EnumerateEnvironmentBlendModes, EXT_NONE),
    HX_FN(CreateSession, EXT_NONE),
    HX_FN(DestroySession, EXT_NONE),
    HX_FN(EnumerateReferenceSpaces, EXT_NONE),
    HX_FN(CreateReferenceSpace, EXT_NONE),
    HX_FN(GetReferenceSpaceBoundsRect, EXT_NONE),
    HX_FN(CreateActionSpace, EXT_NONE),
    HX_FN(LocateSpace, EXT_NONE),
    HX_FN(DestroySpace, EXT_NONE),
    HX_FN(EnumerateViewConfigurations, EXT_NONE),
    HX_FN(GetViewConfigurationProperties, EXT_NONE),
    HX_FN(EnumerateViewConfigurationViews, EXT_NONE),
    HX_FN(EnumerateSwapchainFormats, EXT_NONE),
    HX_FN(CreateSwapchain, EXT_NONE),
    HX_FN(DestroySwapchain, EXT_NONE),
    HX_FN(EnumerateSwapchainImages, EXT_NONE),
    HX_FN(AcquireSwapchainImage, EXT_NONE),
    HX_FN(WaitSwapchainImage, EXT_NONE),
    HX_FN(ReleaseSwapchainImage, EXT_NONE),
    HX_FN(BeginSession, EXT_NONE),
    HX_FN(EndSession, EXT_NONE),
    HX_FN(RequestExitSession, EXT_NONE),
    HX_FN(WaitFrame, EXT_NONE),
    HX_FN(BeginFrame, EXT_NONE),
    HX_FN(EndFrame, EXT_NONE),
    HX_FN(LocateViews, EXT_NONE),
    HX_FN(StringToPath, EXT_NONE),
    HX_FN(PathToString, EXT_NONE),
    HX_FN(CreateActionSet, EXT_NONE),
    HX_FN(DestroyActionSet, EXT_NONE),
    HX_FN(CreateAction, EXT_NONE),
    HX_FN(DestroyAction, EXT_NONE),
    HX_FN(SuggestInteractionProfileBindings, EXT_NONE),
    HX_FN(AttachSessionActionSets, EXT_NONE),
    HX_FN(GetCurrentInteractionProfile, EXT_NONE),
    HX_FN(GetActionStateBoolean, EXT_NONE),
    HX_FN(GetActionStateFloat, EXT_NONE),
    HX_FN(GetActionStateVector2f, EXT_NONE),
    HX_FN(GetActionStatePose, EXT_NONE),
    HX_FN(SyncActions, EXT_NONE),
    HX_FN(EnumerateBoundSourcesForAction, EXT_NONE),
    HX_FN(GetInputSourceLocalizedName, EXT_NONE),
    HX_FN(ApplyHapticFeedback, EXT_NONE),
    HX_FN(StopHapticFeedback, EXT_NONE),
    HX_FN(LocateSpaces, EXT_V11),
    HX_FN(ConvertTimespecTimeToTimeKHR, EXT_TIMESPEC),
    HX_FN(ConvertTimeToTimespecTimeKHR, EXT_TIMESPEC),
#ifdef __ANDROID__
    HX_FN(GetOpenGLESGraphicsRequirementsKHR, EXT_GLES),
    HX_FN(InitializeLoaderKHR, EXT_NONE),
    HX_EXT(SetAndroidApplicationThreadKHR, XR_KHR_ANDROID_THREAD_SETTINGS_EXTENSION_NAME),
#endif
    HX_EXT(EnumerateDisplayRefreshRatesFB, XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME),
    HX_EXT(GetDisplayRefreshRateFB, XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME),
    HX_EXT(RequestDisplayRefreshRateFB, XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME),
    HX_EXT(EnumerateColorSpacesFB, XR_FB_COLOR_SPACE_EXTENSION_NAME),
    HX_EXT(SetColorSpaceFB, XR_FB_COLOR_SPACE_EXTENSION_NAME),
    HX_EXT(UpdateSwapchainFB, XR_FB_SWAPCHAIN_UPDATE_STATE_EXTENSION_NAME),
    HX_EXT(GetSwapchainStateFB, XR_FB_SWAPCHAIN_UPDATE_STATE_EXTENSION_NAME),
    HX_EXT(CreateFoveationProfileFB, XR_FB_FOVEATION_EXTENSION_NAME),
    HX_EXT(DestroyFoveationProfileFB, XR_FB_FOVEATION_EXTENSION_NAME),
    HX_EXT(PerfSettingsSetPerformanceLevelEXT, XR_EXT_PERFORMANCE_SETTINGS_EXTENSION_NAME),
};
#undef HX_FN
#undef HX_EXT

/** Whether the app enabled the extension [name] (functions of extensions it did not enable are unsupported). */
bool extEnabled(const Instance* inst, const char* name) {
    for (uint32_t k = 0; k < kExtensionCount; k++) if (strcmp(kExtensions[k].name, name) == 0) return (inst->extMeta >> k) & 1u;
    return false;
}

XRAPI_ATTR XrResult XRAPI_CALL GetInstanceProcAddr(XrInstance instance, const char* name, PFN_xrVoidFunction* function) {
    if (!function) return XR_ERROR_VALIDATION_FAILURE;
    *function = nullptr;
    if (!name) return XR_ERROR_VALIDATION_FAILURE;
    Instance* inst = nullptr;
    if (instance != XR_NULL_HANDLE) {
        inst = asInstance(instance);
        if (!inst) return XR_ERROR_HANDLE_INVALID;
    } else {
        // only these may be queried without an instance
        if (strcmp(name, "xrEnumerateInstanceExtensionProperties") != 0 && strcmp(name, "xrEnumerateApiLayerProperties") != 0 &&
            strcmp(name, "xrCreateInstance") != 0 && strcmp(name, "xrInitializeLoaderKHR") != 0)
            return XR_ERROR_HANDLE_INVALID;
    }
    for (const Entry& e : kEntries) {
        if (strcmp(e.name, name) != 0) continue;
        if (inst) {
            if (e.ext == EXT_GLES && !inst->extGles) return XR_ERROR_FUNCTION_UNSUPPORTED;
            if (e.ext == EXT_TIMESPEC && !inst->extTimespec) return XR_ERROR_FUNCTION_UNSUPPORTED;
            if (e.ext == EXT_V11 && XR_VERSION_MINOR(inst->apiVersion) < 1) return XR_ERROR_FUNCTION_UNSUPPORTED;
            if (e.extName && !extEnabled(inst, e.extName)) return XR_ERROR_FUNCTION_UNSUPPORTED;
        }
        *function = e.fn;
        return XR_SUCCESS;
    }
    return XR_ERROR_FUNCTION_UNSUPPORTED;
}

}  // namespace

// ------------------------------------------------------------------ loader negotiation
HX_EXPORT XRAPI_ATTR XrResult XRAPI_CALL xrNegotiateLoaderRuntimeInterface(const XrNegotiateLoaderInfo* loaderInfo, XrNegotiateRuntimeRequest* runtimeRequest) {
    if (!loaderInfo || !runtimeRequest || loaderInfo->structType != XR_LOADER_INTERFACE_STRUCT_LOADER_INFO ||
        loaderInfo->structVersion != XR_LOADER_INFO_STRUCT_VERSION || loaderInfo->structSize != sizeof(XrNegotiateLoaderInfo) ||
        runtimeRequest->structType != XR_LOADER_INTERFACE_STRUCT_RUNTIME_REQUEST ||
        runtimeRequest->structVersion != XR_RUNTIME_INFO_STRUCT_VERSION || runtimeRequest->structSize != sizeof(XrNegotiateRuntimeRequest))
        return XR_ERROR_INITIALIZATION_FAILED;
    if (loaderInfo->minInterfaceVersion > XR_CURRENT_LOADER_RUNTIME_VERSION || loaderInfo->maxInterfaceVersion < XR_CURRENT_LOADER_RUNTIME_VERSION)
        return XR_ERROR_INITIALIZATION_FAILED;
    XrVersion ours = XR_MAKE_VERSION(1, 1, 0);
    if (XR_VERSION_MAJOR(loaderInfo->minApiVersion) > 1 || XR_VERSION_MAJOR(loaderInfo->maxApiVersion) < 1) return XR_ERROR_INITIALIZATION_FAILED;
    runtimeRequest->runtimeInterfaceVersion = XR_CURRENT_LOADER_RUNTIME_VERSION;
    runtimeRequest->runtimeApiVersion = ours;
    runtimeRequest->getInstanceProcAddr = GetInstanceProcAddr;
    return XR_SUCCESS;
}

/** Direct entry point (no loader): used by HyperXR's self test and by the host tests. */
HX_EXPORT XRAPI_ATTR XrResult XRAPI_CALL hxGetInstanceProcAddr(XrInstance instance, const char* name, PFN_xrVoidFunction* function) {
    return GetInstanceProcAddr(instance, name, function);
}
