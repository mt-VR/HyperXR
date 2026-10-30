// HyperXR OpenXR runtime: instance, system, session, spaces, swapchains, frame loop and actions.
//
// Scope (honest): a single 3DoF head-mounted system (phone in a VR Box viewer), stereo views,
// OpenGL ES graphics or headless sessions, projection + quad layers. Input: a gamepad / Joy-Cons
// and the screen touch, captured by the runtime's window inside the app, drive the app's actions
// through the Touch or simple controller profile; controller poses follow the head (no 6DoF).
#include "hx_runtime.h"
#include "hx_handmesh.h"
#include "hx_scene.h"
#include "hx_rendermodel.h"
#include "hx_body.h"

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
    MAGIC_FOVEATION = 0x48584631, MAGIC_HAND = 0x48584831, MAGIC_PASSTHROUGH = 0x48585431,
    MAGIC_PT_LAYER = 0x48584C31, MAGIC_SDP = 0x48584431, MAGIC_BODY = 0x48584A31, MAGIC_LUT = 0x48584D31, MAGIC_DEPTH = 0x48585A31, MAGIC_DEPTH_SC = 0x48585931, MAGIC_EYE = 0x48584531, MAGIC_SNAP = 0x48584E31, MAGIC_ANCHOR_BD = 0x48584231,
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
    uint64_t extMeta[2];  // Meta / Pico / EXT extensions enabled by the app (bit per kExtensions entry)
    XrAsyncRequestIdFB nextRequest;  // XR_FB_spatial_entity and friends: async request ids
    // XR_EXT_future: Pico's asynchronous calls (they all finish at once; the future holds the result)
    struct Future { uint64_t id; int kind; void* owner; XrResult result; int payload; } futures[64];
    uint64_t nextFuture;
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
    int handTrackers;        // live XR_EXT_hand_tracking trackers (HyperMagic keeps the camera on)
    // XR_FB_spatial_entity / XR_FB_scene: anchors and the room (see SceneEntity)
    struct SceneEntity* entities;
    int entityCount;
    bool sceneBuilt, anchorsLoaded;
    hxscene::Room room;
    XrVector2f floorOutline[hxscene::MAX_POLY], ceilingOutline[hxscene::MAX_POLY];
    struct SpaceQuery { XrAsyncRequestIdFB id; int count; int16_t entity[256]; } queries[8];
    int nextQuery;
    XrPath profile;          // interaction profile driving both hands (XR_NULL_PATH: none)
    XrPath handProfile;      // hand_interaction_ext when the app has bindings for it (used alongside)
    XrPath microProfile;     // hand_tracking_microgestures when the app has bindings for it
    struct Micro { bool touching, fired; int64_t startNs; float start[3]; uint32_t pulses; } micro[2];
    HxInput input;           // snapshot taken by xrSyncActions
    HxHandShared hands;      // hands snapshot taken by xrSyncActions
    bool handsOk;
    // XR_META_performance_metrics
    bool metrics;
    int64_t frameWaitNs, appCpuNs, composeNs;
    uint32_t droppedFrames;
};

/** XR_FB_foveation profile: kept only so its handle is valid (the whole image is always drawn). */
struct Foveation { uint32_t magic; };

/** XR_FB_passthrough: the feature (started / paused) and its layers (running / paused, style). */
struct Passthrough { uint32_t magic; Session* session; bool running; };
/** XR_META_passthrough_color_lut: a colour cube, kept as RGBA8. */
struct ColorLut { uint32_t magic; Passthrough* passthrough; int res; uint8_t* rgba; uint32_t version; };
struct PassthroughLayer {
    uint32_t magic; Session* session; Passthrough* passthrough; bool running; float opacity;
    // style (XrPassthroughStyleFB and the colour maps chained to it)
    int colorMode;
    uint8_t map[256 * 4];
    float brightness, contrast, saturation;
    ColorLut* lutA; ColorLut* lutB;
    float lutWeight;
    uint32_t version;
    float keyboardHands[2];
};

/** XR_EXT_hand_tracking tracker: one hand, joints from HyperMagic's camera hand tracking. */
struct HandTracker { uint32_t magic; Session* session; XrHandEXT hand; };

/** A spatial entity: an anchor the app made, or part of the room (XR_FB_spatial_entity). */
struct SceneEntity {
    XrUuidEXT uuid;
    int kind;                // hxscene::Kind
    XrPosef pose;            // stage space
    float minX, minY, maxX, maxY;  // planes: 2D bounding box
    uint32_t enabled;        // bit per XrSpaceComponentTypeFB
    bool stored;             // saved by the app (anchors)
};
constexpr int MAX_ENTITIES = 256;
constexpr int DEAD = -1;     // kind of an entity of a replaced room (its spaces stop locating)

struct Space {
    uint32_t magic;
    Session* session;
    XrReferenceSpaceType type;  // 0 for action spaces
    Action* action;
    XrPath subaction;
    XrPosef offset;             // pose in the reference / action space
    int entity;                 // spatial entity + 1 (0: a reference or action space)
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


// ------------------------------------------------------------------ hands as input
/** Hands older than this are not used (the camera lost them, or HyperMagic stopped). */
constexpr int64_t HAND_STALE_NS = 200000000LL;
/** Where the phone's camera sits relative to the eyes in a viewer: a little in front of them. */
constexpr float CAMERA_FORWARD_M = 0.08f;

/** Look-rotation whose -Z points along [dir] with +Y as close to world up as possible. */
XrQuaternionf lookAlong(XrVector3f dir) {
    hxhands::V z = hxhands::norm({-dir.x, -dir.y, -dir.z}, {0, 0, 1});
    hxhands::V up = fabsf(z.y) > 0.98f ? hxhands::V{0, 0, -1} : hxhands::V{0, 1, 0};
    hxhands::V x = hxhands::norm(hxhands::cross(up, z), {1, 0, 0});
    hxhands::V y = hxhands::cross(z, x);
    hxhands::Q q = hxhands::fromAxes(x, y, z);
    return {q.x, q.y, q.z, q.w};
}

/**
 * One tracked hand in stage space: the 26 OpenXR joints ([joints], in the camera's space) and the
 * camera's pose when they were seen ([camera]); false when the hand is not tracked or too old.
 */
bool handInStage(const HxConfig& cfg, const HxHandShared& h, int side, hxhands::Joint joints[XR_HAND_JOINT_COUNT_EXT], XrPosef* camera) {
    if (!h.tracked[side] || hxp::nowNs() - h.timeNs[side] > HAND_STALE_NS) return false;
    if (!hxhands::toXrJoints(h.joints[side], side == 0, joints)) return false;
    XrPosef head;
    if (!hxp::headPose(h.timeNs[side], cfg, &head)) return false;
    *camera = hx::compose(head, XrPosef{{0, 0, 0, 1}, {0, 0, -CAMERA_FORWARD_M}});
    return true;
}

XrPosef jointPose(const XrPosef& camera, const hxhands::Joint& j) {
    return hx::compose(camera, XrPosef{{j.q.x, j.q.y, j.q.z, j.q.w}, {j.p.x, j.p.y, j.p.z}});
}

/** Meta's aim ray: from about the shoulder through the knuckles (between the index and the thumb). */
XrPosef handAim(const HxConfig& cfg, const HxHandShared& h, int side, const XrPosef& camera, XrTime time) {
    XrPosef headNow;
    if (!hxp::headPose(time, cfg, &headNow)) headNow = camera;
    XrQuaternionf yaw = hx::yawOnly(headNow.orientation);
    XrVector3f shoulder = hx::vadd(headNow.position, hx::qrot(yaw, {side == 0 ? -0.17f : 0.17f, -0.22f, 0.05f}));
    XrPosef knuckles = hx::compose(camera, XrPosef{{0, 0, 0, 1}, {
        (h.joints[side][hxhands::INDEX_MCP][0] + h.joints[side][2][0]) * 0.5f,
        (h.joints[side][hxhands::INDEX_MCP][1] + h.joints[side][2][1]) * 0.5f,
        (h.joints[side][hxhands::INDEX_MCP][2] + h.joints[side][2][2]) * 0.5f}});
    XrVector3f dir = {knuckles.position.x - shoulder.x, knuckles.position.y - shoulder.y, knuckles.position.z - shoulder.z};
    return XrPosef{lookAlong(dir), knuckles.position};
}

/** Closure of the hand 0 (open) .. 1 (fist): how near the middle, ring and little tips are to the palm. */
float graspOf(const HxHandShared& h, int side) {
    hxhands::V wrist = hxhands::v(h.joints[side][0]), mid = hxhands::v(h.joints[side][hxhands::MIDDLE_MCP]);
    float palm = hxhands::len(hxhands::sub(mid, wrist));
    if (palm < 1e-3f) return 0;
    hxhands::V centre = hxhands::lerp(wrist, mid, 0.5f);
    float d = 0;
    static const int tips[3] = {12, 16, 20};
    for (int tip : tips) d += hxhands::len(hxhands::sub(hxhands::v(h.joints[side][tip]), centre));
    float r = d / 3 / palm;  // ~1.4 open, ~0.4 in a fist
    float g = (1.2f - r) / 0.8f;
    return g < 0 ? 0 : (g > 1 ? 1 : g);
}

enum HandPoseKind { HAND_POSE_GRIP, HAND_POSE_AIM, HAND_POSE_PINCH, HAND_POSE_POKE };

/** A hand_interaction_ext pose of a tracked hand, in stage space. */
bool handPose(Session* s, int side, int kind, XrTime time, XrPosef* out) {
    if (!s->handsOk) return false;
    hxhands::Joint j[XR_HAND_JOINT_COUNT_EXT];
    XrPosef camera;
    if (!handInStage(s->instance->cfg, s->hands, side, j, &camera)) return false;
    XrPosef aim = handAim(s->instance->cfg, s->hands, side, camera, time);
    switch (kind) {
        case HAND_POSE_GRIP: *out = jointPose(camera, j[XR_HAND_JOINT_PALM_EXT]); return true;
        case HAND_POSE_AIM: *out = aim; return true;
        case HAND_POSE_PINCH: {
            XrPosef a = jointPose(camera, j[XR_HAND_JOINT_THUMB_TIP_EXT]), b = jointPose(camera, j[XR_HAND_JOINT_INDEX_TIP_EXT]);
            *out = XrPosef{aim.orientation, {(a.position.x + b.position.x) * 0.5f, (a.position.y + b.position.y) * 0.5f, (a.position.z + b.position.z) * 0.5f}};
            return true;
        }
        default: *out = jointPose(camera, j[XR_HAND_JOINT_INDEX_TIP_EXT]); return true;
    }
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
    // the hands themselves (XR_EXT_hand_interaction): pinch, aim, grasp and poke from hand tracking
    "/interaction_profiles/ext/hand_interaction_ext",
    // thumb microgestures on the index finger (XR_META_hand_tracking_microgestures)
    "/interaction_profiles/meta/hand_tracking_microgestures",
};
const char* const kMicroProfile = "/interaction_profiles/meta/hand_tracking_microgestures";
const char* const kHandProfile = "/interaction_profiles/ext/hand_interaction_ext";

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
        if (strcmp(comp, "touch") == 0 || strcmp(comp, "proximity") == 0 || strcmp(comp, "proximity_fb") == 0) { v[0] = trig > 0.05f ? 1.f : 0.f; return SRC_BOOL; }
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
    // XR_FB_touch_controller_proximity: the thumb is near the face buttons / stick when any is used
    if (strcmp(id, "thumb_fb") == 0 && strcmp(comp, "proximity_fb") == 0) {
        uint32_t face = h == 0 ? (HX_BTN_X | HX_BTN_Y | HX_BTN_THUMB_L) : (HX_BTN_A | HX_BTN_B | HX_BTN_THUMB_R);
        v[0] = ((in.buttons & face) || in.stick[h][0] * in.stick[h][0] + in.stick[h][1] * in.stick[h][1] > 0.01f) ? 1.f : 0.f;
        return SRC_BOOL;
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

/**
 * One hand_interaction_ext source from the hands snapshot of the last xrSyncActions: pinch, aim
 * activation and grasp values (and their _ready_ext: the hand is tracked), and the four poses.
 */
SrcKind evalHandSource(const char* path, const HxHandShared& hands, bool handsOk, int* handOut, float v[2]) {
    const char* rest = nullptr;
    int h = handOf(path, &rest);
    *handOut = h;
    v[0] = v[1] = 0;
    if (h < 0 || strncmp(rest, "/input/", 7) != 0) return SRC_NONE;
    const char* q = rest + 7;
    if (strcmp(q, "aim/pose") == 0 || strcmp(q, "grip/pose") == 0 || strcmp(q, "pinch_ext/pose") == 0 || strcmp(q, "poke_ext/pose") == 0)
        return SRC_POSE;
    bool tracked = handsOk && hands.tracked[h] && hxp::nowNs() - hands.timeNs[h] <= HAND_STALE_NS;
    float pinch = tracked ? (hands.pinch[h] < 0 ? 0 : (hands.pinch[h] > 1 ? 1 : hands.pinch[h])) : 0;
    struct { const char* name; float value; } values[] = {
        {"pinch_ext/value", pinch}, {"aim_activate_ext/value", pinch}, {"grasp_ext/value", tracked ? graspOf(hands, h) : 0}};
    for (auto& e : values) if (strcmp(q, e.name) == 0) { v[0] = e.value; return SRC_FLOAT; }
    if (strcmp(q, "pinch_ext/ready_ext") == 0 || strcmp(q, "aim_activate_ext/ready_ext") == 0 || strcmp(q, "grasp_ext/ready_ext") == 0) {
        v[0] = tracked ? 1.f : 0.f;
        return SRC_BOOL;
    }
    return SRC_NONE;
}

enum MicroGesture : uint32_t { MICRO_LEFT = 1, MICRO_RIGHT = 2, MICRO_FORWARD = 4, MICRO_BACKWARD = 8, MICRO_TAP = 16 };

/**
 * Thumb microgestures of one hand from its landmarks (camera space, x right): the thumb tip
 * touching the side of the index finger, then a tap (lift within half a second) or a swipe of
 * 2 cm along the finger (forward / backward) or across it (left / right). Each gesture is one
 * pulse for one xrSyncActions.
 */
uint32_t detectMicro(Session::Micro& m, const HxHandShared& h, int side, int64_t now) {
    hxhands::V mcp = hxhands::v(h.joints[side][hxhands::INDEX_MCP]), dip = hxhands::v(h.joints[side][7]), tip = hxhands::v(h.joints[side][8]);
    hxhands::V thumb = hxhands::v(h.joints[side][4]);
    float palm = hxhands::len(hxhands::sub(hxhands::v(h.joints[side][hxhands::MIDDLE_MCP]), hxhands::v(h.joints[side][0])));
    float scale = palm > 0.02f ? palm / 0.095f : 1;
    // nearest point on the index finger from its knuckle to its last joint
    hxhands::V seg = hxhands::sub(dip, mcp);
    float t = hxhands::dot(hxhands::sub(thumb, mcp), seg) / (hxhands::dot(seg, seg) + 1e-9f);
    t = t < 0 ? 0 : (t > 1 ? 1 : t);
    float d = hxhands::len(hxhands::sub(thumb, hxhands::add(mcp, hxhands::mul(seg, t))));
    uint32_t out = 0;
    if (!m.touching) {
        if (d < 0.025f * scale) { m.touching = true; m.fired = false; m.startNs = now; m.start[0] = thumb.x; m.start[1] = thumb.y; m.start[2] = thumb.z; }
        return 0;
    }
    if (d > 0.035f * scale) {  // lifted
        m.touching = false;
        if (!m.fired && now - m.startNs < 500000000LL) out |= MICRO_TAP;
        return out;
    }
    if (m.fired) return 0;
    hxhands::V along = hxhands::norm(hxhands::sub(tip, mcp), {0, 0, -1});
    hxhands::V across = hxhands::norm(hxhands::sub(hxhands::V{1, 0, 0}, hxhands::mul(along, along.x)), {1, 0, 0});
    hxhands::V moved = hxhands::sub(thumb, hxhands::V{m.start[0], m.start[1], m.start[2]});
    float a = hxhands::dot(moved, along), c = hxhands::dot(moved, across), limit = 0.02f * scale;
    if (fabsf(a) > limit && fabsf(a) >= fabsf(c)) { out |= a > 0 ? MICRO_FORWARD : MICRO_BACKWARD; m.fired = true; }
    else if (fabsf(c) > limit) { out |= c > 0 ? MICRO_RIGHT : MICRO_LEFT; m.fired = true; }
    return out;
}

SrcKind evalMicroSource(const char* path, const Session* s, int* handOut, float v[2]) {
    const char* rest = nullptr;
    int h = handOf(path, &rest);
    *handOut = h;
    v[0] = v[1] = 0;
    if (h < 0) return SRC_NONE;
    struct { const char* path; uint32_t bit; } inputs[] = {
        {"/input/swipe_left_meta/click", MICRO_LEFT}, {"/input/swipe_right_meta/click", MICRO_RIGHT},
        {"/input/swipe_forward_meta/click", MICRO_FORWARD}, {"/input/swipe_backward_meta/click", MICRO_BACKWARD},
        {"/input/tap_thumb_meta/click", MICRO_TAP}};
    for (auto& in : inputs) if (strcmp(rest, in.path) == 0) { v[0] = (s->micro[h].pulses & in.bit) ? 1.f : 0.f; return SRC_BOOL; }
    return SRC_NONE;
}

/** Pose kind of a hand_interaction_ext pose path, or -1. */
int handPoseKind(const char* path) {
    const char* rest = nullptr;
    if (handOf(path, &rest) < 0) return -1;
    if (strcmp(rest, "/input/grip/pose") == 0) return HAND_POSE_GRIP;
    if (strcmp(rest, "/input/aim/pose") == 0) return HAND_POSE_AIM;
    if (strcmp(rest, "/input/pinch_ext/pose") == 0) return HAND_POSE_PINCH;
    if (strcmp(rest, "/input/poke_ext/pose") == 0) return HAND_POSE_POKE;
    return -1;
}

bool isSimpleProfile(Instance* inst, XrPath profile) {
    const char* p = pathString(inst, profile);
    return p && strcmp(p, "/interaction_profiles/khr/simple_controller") == 0;
}

/** Evaluates [a] for slot 0 (left), 1 (right) or 2 (any hand) under the session's profile. */
ActionValue evalAction(Session* s, Action* a, int slot) {
    ActionValue r{};
    Instance* inst = s->instance;
    if (s->profile == XR_NULL_PATH && s->handProfile == XR_NULL_PATH && s->microProfile == XR_NULL_PATH) return r;
    bool simple = isSimpleProfile(inst, s->profile);
    float best = -1;
    // the controller profile and, alongside it, the hands (simultaneous hands and controllers)
    for (int i = 0; i < inst->bindingCount; i++) {
        const auto& b = inst->bindings[i];
        bool hands = b.profile == s->handProfile && s->handProfile != XR_NULL_PATH;
        bool micro = b.profile == s->microProfile && s->microProfile != XR_NULL_PATH;
        if ((b.profile != s->profile && !hands && !micro) || b.action != a) continue;
        const char* path = pathString(inst, b.path);
        if (!path) continue;
        int hand; float v[2];
        SrcKind k = micro ? evalMicroSource(path, s, &hand, v)
                  : hands ? evalHandSource(path, s->hands, s->handsOk, &hand, v) : evalSource(path, simple, s->input, &hand, v);
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
/** Set by spaceInStage when the pose it returned came from a tracked hand (its position is real). */
thread_local bool tPoseFromHand = false;

/** Pose of a space in stage space at [time]. */
bool spaceInStage(Space* sp, XrTime time, XrPosef* out, bool* tracked) {
    Instance* inst = sp->session->instance;
    *tracked = true;
    tPoseFromHand = false;
    if (sp->entity > 0) {
        const SceneEntity& e = sp->session->entities[sp->entity - 1];
        if (!(e.enabled & (1u << XR_SPACE_COMPONENT_TYPE_LOCATABLE_FB))) { *tracked = false; return false; }
        *out = hx::compose(e.pose, sp->offset);
        return true;
    }
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
            if (!a || !isLive(a, MAGIC_ACTION) || (s->profile == XR_NULL_PATH && s->handProfile == XR_NULL_PATH)) return false;
            int slot = slotOf(inst, sp->subaction);
            if (slot == 3) return false;
            // a tracked hand bound through hand_interaction_ext gives its real pose
            if (s->handProfile != XR_NULL_PATH)
                for (int i = 0; i < inst->bindingCount; i++) {
                    const auto& b = inst->bindings[i];
                    if (b.profile != s->handProfile || b.action != a) continue;
                    const char* path = pathString(inst, b.path);
                    int kind = path ? handPoseKind(path) : -1;
                    int h = path ? handOf(path, nullptr) : -1;
                    XrPosef hp;
                    if (kind < 0 || (slot < 2 && h != slot) || !handPose(s, h, kind, time, &hp)) continue;
                    *out = hx::compose(hp, sp->offset);
                    *tracked = true;
                    tPoseFromHand = true;
                    return true;
                }
            if (s->profile == XR_NULL_PATH || s->profile == s->handProfile) return false;
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
bool extEnabled(const Instance* inst, const char* name);
void destroyDepthSwapchain(void* p);
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
    // hands: tracked by HyperMagic with the phone's camera while the game runs (hx_hands.h)
    {XR_EXT_HAND_TRACKING_EXTENSION_NAME, XR_EXT_hand_tracking_SPEC_VERSION},
    // mixed reality: the phone's camera behind the app (HyperMagic sends the frames, hx_camera.h)
    {XR_FB_PASSTHROUGH_EXTENSION_NAME, XR_FB_passthrough_SPEC_VERSION},
    // anchors and the room: anchors keep a fixed pose (a phone has no position tracking to correct
    // them); the room is HyperGuardian's area, or a 4 x 4 m one (hx_scene.h)
    {XR_FB_SPATIAL_ENTITY_EXTENSION_NAME, XR_FB_spatial_entity_SPEC_VERSION},
    {XR_FB_SPATIAL_ENTITY_QUERY_EXTENSION_NAME, XR_FB_spatial_entity_query_SPEC_VERSION},
    {XR_FB_SPATIAL_ENTITY_STORAGE_EXTENSION_NAME, XR_FB_spatial_entity_storage_SPEC_VERSION},
    {XR_FB_SPATIAL_ENTITY_CONTAINER_EXTENSION_NAME, XR_FB_spatial_entity_container_SPEC_VERSION},
    {XR_FB_SCENE_EXTENSION_NAME, XR_FB_scene_SPEC_VERSION},
    {XR_FB_SCENE_CAPTURE_EXTENSION_NAME, XR_FB_scene_capture_SPEC_VERSION},
    {XR_META_SPATIAL_ENTITY_MESH_EXTENSION_NAME, XR_META_spatial_entity_mesh_SPEC_VERSION},
    // passthrough colour: Meta's colour cubes, and the keyboard-hands layer (no tracked keyboard to show)
    {XR_META_PASSTHROUGH_COLOR_LUT_EXTENSION_NAME, XR_META_passthrough_color_lut_SPEC_VERSION},
    {XR_FB_PASSTHROUGH_KEYBOARD_HANDS_EXTENSION_NAME, XR_FB_passthrough_keyboard_hands_SPEC_VERSION},
    // controller models (HyperMagic's own, glTF), per-layer blend factors, and two hints accepted
    {XR_FB_RENDER_MODEL_EXTENSION_NAME, XR_FB_render_model_SPEC_VERSION},
    // Pico (ByteDance) spatial sensing: anchors, the room (scene), its planes and its mesh, with futures
    {XR_EXT_FUTURE_EXTENSION_NAME, XR_EXT_future_SPEC_VERSION},
    {XR_BD_FUTURE_PROGRESS_EXTENSION_NAME, XR_BD_future_progress_SPEC_VERSION},
    {XR_BD_SPATIAL_SENSING_EXTENSION_NAME, XR_BD_spatial_sensing_SPEC_VERSION},
    {XR_BD_SPATIAL_ANCHOR_EXTENSION_NAME, XR_BD_spatial_anchor_SPEC_VERSION},
    {XR_BD_SPATIAL_SCENE_EXTENSION_NAME, XR_BD_spatial_scene_SPEC_VERSION},
    {XR_BD_SPATIAL_MESH_EXTENSION_NAME, XR_BD_spatial_mesh_SPEC_VERSION},
    {XR_BD_SPATIAL_PLANE_EXTENSION_NAME, XR_BD_spatial_plane_SPEC_VERSION},
    // bodies (Meta upper body and full body, Pico): head and hands seen, the rest inferred (hx_body.h)
    {XR_FB_BODY_TRACKING_EXTENSION_NAME, XR_FB_body_tracking_SPEC_VERSION},
    // depth of the room for occlusion; gaze (where the head looks); thumb microgestures from the hand joints
    {XR_META_ENVIRONMENT_DEPTH_EXTENSION_NAME, XR_META_environment_depth_SPEC_VERSION},
    {XR_FB_EYE_TRACKING_SOCIAL_EXTENSION_NAME, XR_FB_eye_tracking_social_SPEC_VERSION},
    {XR_META_HAND_TRACKING_MICROGESTURES_EXTENSION_NAME, XR_META_hand_tracking_microgestures_SPEC_VERSION},
    {XR_META_BODY_TRACKING_FULL_BODY_EXTENSION_NAME, XR_META_body_tracking_full_body_SPEC_VERSION},
    {XR_BD_BODY_TRACKING_EXTENSION_NAME, XR_BD_body_tracking_SPEC_VERSION},
    {XR_FB_COMPOSITION_LAYER_ALPHA_BLEND_EXTENSION_NAME, XR_FB_composition_layer_alpha_blend_SPEC_VERSION},
    {XR_META_LOCAL_DIMMING_EXTENSION_NAME, XR_META_local_dimming_SPEC_VERSION},
    {XR_META_HEADSET_ID_EXTENSION_NAME, XR_META_headset_id_SPEC_VERSION},
    // hands as controllers, and both at once; Touch proximity; haptics as envelopes and PCM
    {XR_EXT_HAND_INTERACTION_EXTENSION_NAME, XR_EXT_hand_interaction_SPEC_VERSION},
    {XR_META_SIMULTANEOUS_HANDS_AND_CONTROLLERS_EXTENSION_NAME, XR_META_simultaneous_hands_and_controllers_SPEC_VERSION},
    {XR_FB_TOUCH_CONTROLLER_PROXIMITY_EXTENSION_NAME, XR_FB_touch_controller_proximity_SPEC_VERSION},
    {XR_FB_HAPTIC_AMPLITUDE_ENVELOPE_EXTENSION_NAME, XR_FB_haptic_amplitude_envelope_SPEC_VERSION},
    {XR_FB_HAPTIC_PCM_EXTENSION_NAME, XR_FB_haptic_pcm_SPEC_VERSION},
    // frame timings, layer sizes, capture-only flags, keyboards, passthrough preferences and events,
    // saving several anchors at once, Android session states
    {XR_META_PERFORMANCE_METRICS_EXTENSION_NAME, XR_META_performance_metrics_SPEC_VERSION},
    {XR_META_RECOMMENDED_LAYER_RESOLUTION_EXTENSION_NAME, XR_META_recommended_layer_resolution_SPEC_VERSION},
    {XR_FB_COMPOSITION_LAYER_SECURE_CONTENT_EXTENSION_NAME, XR_FB_composition_layer_secure_content_SPEC_VERSION},
    {XR_META_AUTOMATIC_LAYER_FILTER_EXTENSION_NAME, XR_META_automatic_layer_filter_SPEC_VERSION},
    {XR_FB_KEYBOARD_TRACKING_EXTENSION_NAME, XR_FB_keyboard_tracking_SPEC_VERSION},
    {XR_META_PASSTHROUGH_PREFERENCES_EXTENSION_NAME, XR_META_passthrough_preferences_SPEC_VERSION},
    {XR_META_PASSTHROUGH_LAYER_RESUMED_EVENT_EXTENSION_NAME, XR_META_passthrough_layer_resumed_event_SPEC_VERSION},
    {XR_FB_SPATIAL_ENTITY_STORAGE_BATCH_EXTENSION_NAME, XR_FB_spatial_entity_storage_batch_SPEC_VERSION},
    {XR_OCULUS_ANDROID_SESSION_STATE_ENABLE_EXTENSION_NAME, XR_OCULUS_android_session_state_enable_SPEC_VERSION},
    // menus and video players of Quest apps: curved panels and 360/180 images
    {XR_KHR_COMPOSITION_LAYER_CYLINDER_EXTENSION_NAME, XR_KHR_composition_layer_cylinder_SPEC_VERSION},
    {XR_KHR_COMPOSITION_LAYER_EQUIRECT2_EXTENSION_NAME, XR_KHR_composition_layer_equirect2_SPEC_VERSION},
    {XR_FB_HAND_TRACKING_AIM_EXTENSION_NAME, XR_FB_hand_tracking_aim_SPEC_VERSION},
    {XR_FB_HAND_TRACKING_MESH_EXTENSION_NAME, XR_FB_hand_tracking_mesh_SPEC_VERSION},
    {XR_FB_HAND_TRACKING_CAPSULES_EXTENSION_NAME, XR_FB_hand_tracking_capsules_SPEC_VERSION},
#ifdef __ANDROID__
    {XR_KHR_ANDROID_THREAD_SETTINGS_EXTENSION_NAME, XR_KHR_android_thread_settings_SPEC_VERSION},
    {XR_FB_SWAPCHAIN_UPDATE_STATE_OPENGL_ES_EXTENSION_NAME, XR_FB_swapchain_update_state_opengl_es_SPEC_VERSION},
#endif
};
constexpr uint32_t kExtensionCount = sizeof(kExtensions) / sizeof(kExtensions[0]);
static_assert(kExtensionCount <= 128, "Instance::extMeta has one bit per extension");

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
    uint64_t extBits[2] = {0, 0};
    for (uint32_t i = 0; i < ci->enabledExtensionCount; i++) {
        const char* e = ci->enabledExtensionNames[i];
        bool known = false;
        for (uint32_t k = 0; k < kExtensionCount; k++) if (strcmp(e, kExtensions[k].name) == 0) { known = true; extBits[k >> 6] |= 1ull << (k & 63); }
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
    inst->extMeta[0] = extBits[0];
    inst->extMeta[1] = extBits[1];
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
        if (n->type == XR_TYPE_SYSTEM_HAND_TRACKING_PROPERTIES_EXT) reinterpret_cast<XrSystemHandTrackingPropertiesEXT*>(n)->supportsHandTracking = XR_TRUE;
        if (n->type == XR_TYPE_SYSTEM_SIMULTANEOUS_HANDS_AND_CONTROLLERS_PROPERTIES_META)
            reinterpret_cast<XrSystemSimultaneousHandsAndControllersPropertiesMETA*>(n)->supportsSimultaneousHandsAndControllers = XR_TRUE;
        if (n->type == XR_TYPE_SYSTEM_KEYBOARD_TRACKING_PROPERTIES_FB)
            reinterpret_cast<XrSystemKeyboardTrackingPropertiesFB*>(n)->supportsKeyboardTracking = XR_TRUE;
        if (n->type == XR_TYPE_SYSTEM_ENVIRONMENT_DEPTH_PROPERTIES_META) {
            reinterpret_cast<XrSystemEnvironmentDepthPropertiesMETA*>(n)->supportsEnvironmentDepth = XR_TRUE;
            reinterpret_cast<XrSystemEnvironmentDepthPropertiesMETA*>(n)->supportsHandRemoval = XR_TRUE;  // the room's depth has no hands in it
        }
        if (n->type == XR_TYPE_SYSTEM_EYE_TRACKING_PROPERTIES_FB) reinterpret_cast<XrSystemEyeTrackingPropertiesFB*>(n)->supportsEyeTracking = XR_TRUE;
        if (n->type == XR_TYPE_SYSTEM_PASSTHROUGH_COLOR_LUT_PROPERTIES_META)
            reinterpret_cast<XrSystemPassthroughColorLutPropertiesMETA*>(n)->maxColorLutResolution = 64;
        if (n->type == XR_TYPE_SYSTEM_BODY_TRACKING_PROPERTIES_FB) reinterpret_cast<XrSystemBodyTrackingPropertiesFB*>(n)->supportsBodyTracking = XR_TRUE;
        if (n->type == XR_TYPE_SYSTEM_PROPERTIES_BODY_TRACKING_FULL_BODY_META)
            reinterpret_cast<XrSystemPropertiesBodyTrackingFullBodyMETA*>(n)->supportsFullBodyTracking = XR_TRUE;
        if (n->type == XR_TYPE_SYSTEM_BODY_TRACKING_PROPERTIES_BD) reinterpret_cast<XrSystemBodyTrackingPropertiesBD*>(n)->supportsBodyTracking = XR_TRUE;
        if (n->type == XR_TYPE_SYSTEM_SPATIAL_SENSING_PROPERTIES_BD) reinterpret_cast<XrSystemSpatialSensingPropertiesBD*>(n)->supportsSpatialSensing = XR_TRUE;
        if (n->type == XR_TYPE_SYSTEM_SPATIAL_ANCHOR_PROPERTIES_BD) reinterpret_cast<XrSystemSpatialAnchorPropertiesBD*>(n)->supportsSpatialAnchor = XR_TRUE;
        if (n->type == XR_TYPE_SYSTEM_SPATIAL_SCENE_PROPERTIES_BD) reinterpret_cast<XrSystemSpatialScenePropertiesBD*>(n)->supportsSpatialScene = XR_TRUE;
        if (n->type == XR_TYPE_SYSTEM_SPATIAL_MESH_PROPERTIES_BD) reinterpret_cast<XrSystemSpatialMeshPropertiesBD*>(n)->supportsSpatialMesh = XR_TRUE;
        if (n->type == XR_TYPE_SYSTEM_SPATIAL_PLANE_PROPERTIES_BD) reinterpret_cast<XrSystemSpatialPlanePropertiesBD*>(n)->supportsSpatialPlane = XR_TRUE;
        if (n->type == XR_TYPE_SYSTEM_RENDER_MODEL_PROPERTIES_FB) reinterpret_cast<XrSystemRenderModelPropertiesFB*>(n)->supportsRenderModelLoading = XR_TRUE;
        if (n->type == XR_TYPE_SYSTEM_HEADSET_ID_PROPERTIES_META) memset(&reinterpret_cast<XrSystemHeadsetIdPropertiesMETA*>(n)->id, 0, sizeof(XrUuidEXT));  // not a Meta headset
        if (n->type == XR_TYPE_SYSTEM_PASSTHROUGH_PROPERTIES_FB) reinterpret_cast<XrSystemPassthroughPropertiesFB*>(n)->supportsPassthrough = XR_TRUE;
        if (n->type == XR_TYPE_SYSTEM_PASSTHROUGH_PROPERTIES2_FB)
            reinterpret_cast<XrSystemPassthroughProperties2FB*>(n)->capabilities = XR_PASSTHROUGH_CAPABILITY_BIT_FB | XR_PASSTHROUGH_CAPABILITY_COLOR_BIT_FB;
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL EnumerateEnvironmentBlendModes(XrInstance h, XrSystemId sys, XrViewConfigurationType vct, uint32_t capacity, uint32_t* count, XrEnvironmentBlendMode* modes) {
    if (!asInstance(h)) return XR_ERROR_HANDLE_INVALID;
    if (sys != SYSTEM_ID) return XR_ERROR_SYSTEM_INVALID;
    if (vct != XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO) return XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED;
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    // OPAQUE first (the preferred one); ALPHA_BLEND shows the phone's camera behind the app
    *count = 2;
    if (capacity == 0) return XR_SUCCESS;
    if (!modes) return XR_ERROR_VALIDATION_FAILURE;
    if (capacity < 2) return XR_ERROR_SIZE_INSUFFICIENT;
    modes[0] = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    modes[1] = XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND;
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
    while (void* c = takeChild(s, MAGIC_HAND)) { static_cast<HandTracker*>(c)->magic = 0; free(c); }
    while (void* c = takeChild(s, MAGIC_PT_LAYER)) { static_cast<PassthroughLayer*>(c)->magic = 0; free(c); }
    while (void* c = takeChild(s, MAGIC_PASSTHROUGH)) {
        while (void* l = takeChild(c, MAGIC_LUT)) { ColorLut* lut = static_cast<ColorLut*>(l); free(lut->rgba); lut->magic = 0; free(lut); }
        static_cast<Passthrough*>(c)->magic = 0;
        free(c);
    }
    while (void* c = takeChild(s, MAGIC_EYE)) { static_cast<uint32_t*>(c)[0] = 0; free(c); }
    while (void* c = takeChild(s, MAGIC_DEPTH)) {
        while (void* sc = takeChild(c, MAGIC_DEPTH_SC)) { destroyDepthSwapchain(sc); }
        static_cast<uint32_t*>(c)[0] = 0;
        free(c);
    }
    while (void* c = takeChild(s, MAGIC_BODY)) { static_cast<uint32_t*>(c)[0] = 0; free(c); }
    while (void* c = takeChild(s, MAGIC_SNAP)) { static_cast<uint32_t*>(c)[0] = 0; free(c); }
    while (void* c = takeChild(s, MAGIC_ANCHOR_BD)) { static_cast<uint32_t*>(c)[0] = 0; free(c); }
    while (void* c = takeChild(s, MAGIC_SDP)) { static_cast<uint32_t*>(c)[0] = 0; free(c); }
    free(s->entities);
    s->entities = nullptr;
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
    bool handA = tPoseFromHand;
    bool okB = spaceInStage(base, time, &b, &tb);
    bool handB = tPoseFromHand;
    XrSpaceVelocity* vel = nullptr;
    for (auto* n = reinterpret_cast<XrBaseOutStructure*>(loc->next); n; n = n->next)
        if (n->type == XR_TYPE_SPACE_VELOCITY) vel = reinterpret_cast<XrSpaceVelocity*>(n);
    if (vel) vel->velocityFlags = 0;
    if (!okA || !okB) { loc->locationFlags = 0; loc->pose = hx::identity(); return XR_SUCCESS; }
    loc->pose = hx::compose(hx::inverse(b), a);
    // orientation is tracked; position comes from a neck model (and controllers ride with the
    // head), so it is valid but not tracked
    bool viewInvolved = sp->type == XR_REFERENCE_SPACE_TYPE_VIEW || base->type == XR_REFERENCE_SPACE_TYPE_VIEW ||
                        (sp->action != nullptr && !handA) || (base->action != nullptr && !handB);
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
    // twice a second: tell HyperMagic a game is running here, and whether it wants hands
    if (s->frameCount % 30 == 0) { HxHandShared tmp; hxp::readHands(&tmp, s->handTrackers > 0); }
    // Pace to the display: the compositor swaps with vsync, so one period between frames.
    if (s->lastWaitNs) sleepUntil(s->lastWaitNs + period - 1000000);
    int64_t now = hxp::nowNs();
    s->lastWaitNs = now;
    // app render (~1 period) + composite and scan-out (~1 period)
    XrTime predicted = now + 2 * period;
    if (predicted <= s->lastPredicted) predicted = s->lastPredicted + 1;
    s->lastPredicted = predicted;
    s->frameWaitNs = hxp::nowNs();
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

/**
 * XR_FB_composition_layer_alpha_blend: the layer's own blend factors ([out] stays all -1 when the
 * layer has none): source and destination for colour, then for alpha (XrBlendFactorFB values).
 */
void customBlend(Instance* inst, const XrCompositionLayerBaseHeader* l, int out[4]) {
    out[0] = out[1] = out[2] = out[3] = -1;
    if (!extEnabled(inst, XR_FB_COMPOSITION_LAYER_ALPHA_BLEND_EXTENSION_NAME)) return;
    for (auto* n = reinterpret_cast<const XrBaseInStructure*>(l->next); n; n = n->next) {
        if (n->type != XR_TYPE_COMPOSITION_LAYER_ALPHA_BLEND_FB) continue;
        auto* b = reinterpret_cast<const XrCompositionLayerAlphaBlendFB*>(n);
        const XrBlendFactorFB f[4] = {b->srcFactorColor, b->dstFactorColor, b->srcFactorAlpha, b->dstFactorAlpha};
        for (int i = 0; i < 4; i++) out[i] = f[i] <= XR_BLEND_FACTOR_ONE_MINUS_DST_ALPHA_FB ? static_cast<int>(f[i]) : -1;
        if (out[0] < 0 || out[1] < 0 || out[2] < 0 || out[3] < 0) out[0] = out[1] = out[2] = out[3] = -1;
    }
}

XRAPI_ATTR XrResult XRAPI_CALL EndFrame(XrSession h, const XrFrameEndInfo* info) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_FRAME_END_INFO) return XR_ERROR_VALIDATION_FAILURE;
    if (!s->running) return XR_ERROR_SESSION_NOT_RUNNING;
    if (!s->frameBegun) return XR_ERROR_CALL_ORDER_INVALID;
    if (info->displayTime <= 0) return XR_ERROR_TIME_INVALID;
    if (info->environmentBlendMode != XR_ENVIRONMENT_BLEND_MODE_OPAQUE && info->environmentBlendMode != XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND)
        return XR_ERROR_ENVIRONMENT_BLEND_MODE_UNSUPPORTED;
    if (info->layerCount > MAX_LAYERS) return XR_ERROR_LAYER_LIMIT_EXCEEDED;
    {
        int64_t now = hxp::nowNs();
        if (s->frameWaitNs) s->appCpuNs = now - s->frameWaitNs;
        if (now > info->displayTime) s->droppedFrames++;  // the frame came too late for its display time
    }
    if (info->layerCount > 0 && !info->layers) return XR_ERROR_VALIDATION_FAILURE;

    HxComposeView views[2] = {};
    uint32_t viewCount = 0;
    HxPassthroughState passthrough = {};
    if (info->environmentBlendMode == XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND) { passthrough.on = 1; passthrough.opacity = 1; passthrough.contrast = passthrough.saturation = 1; }
    HxComposeQuad quads[MAX_LAYERS];
    uint32_t quadCount = 0;
    const HxConfig& cfg = s->instance->cfg;
    for (uint32_t i = 0; i < info->layerCount; i++) {
        const XrCompositionLayerBaseHeader* l = info->layers[i];
        if (!l) return XR_ERROR_LAYER_INVALID;
        if (l->type == XR_TYPE_COMPOSITION_LAYER_PASSTHROUGH_FB) {
            // the camera, drawn behind everything (wherever it is in the list); no space needed
            if (!extEnabled(s->instance, XR_FB_PASSTHROUGH_EXTENSION_NAME)) return XR_ERROR_LAYER_INVALID;
            auto* p = reinterpret_cast<const XrCompositionLayerPassthroughFB*>(l);
            auto* pl = asObj<PassthroughLayer>(reinterpret_cast<uint64_t>(p->layerHandle), MAGIC_PT_LAYER);
            if (!pl || pl->session != s) return XR_ERROR_HANDLE_INVALID;
            if (pl->running && isLive(pl->passthrough, MAGIC_PASSTHROUGH) && pl->passthrough->running && pl->opacity > 0 &&
                pl->opacity >= passthrough.opacity) {
                passthrough.on = 1;
                passthrough.opacity = pl->opacity;
                // this layer's colour style
                passthrough.colorMode = pl->colorMode;
                passthrough.map = pl->map;
                passthrough.brightness = pl->brightness; passthrough.contrast = pl->contrast; passthrough.saturation = pl->saturation;
                passthrough.lutA = pl->lutA ? pl->lutA->rgba : nullptr;
                passthrough.lutB = pl->lutB ? pl->lutB->rgba : nullptr;
                passthrough.lutRes = pl->lutB ? pl->lutB->res : 0;
                passthrough.lutWeight = pl->lutWeight;
                passthrough.version = pl->version * 7919u + (pl->lutA ? pl->lutA->version * 104729u : 0) + (pl->lutB ? pl->lutB->version : 0) +
                                      static_cast<uint32_t>(reinterpret_cast<uintptr_t>(pl));
                if (passthrough.colorMode == HX_COLOR_LUT && !passthrough.lutB) passthrough.colorMode = HX_COLOR_NONE;
            }
            continue;
        }
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
                cv.blendAlpha = (p->layerFlags & XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT) != 0;
                cv.premultiplied = (p->layerFlags & XR_COMPOSITION_LAYER_UNPREMULTIPLIED_ALPHA_BIT) == 0;
                customBlend(s->instance, l, cv.blend);
            }
            if (located) viewCount = 2;
        } else if (l->type == XR_TYPE_COMPOSITION_LAYER_QUAD || l->type == XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR ||
                   l->type == XR_TYPE_COMPOSITION_LAYER_EQUIRECT2_KHR) {
            if (l->type == XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR && !extEnabled(s->instance, XR_KHR_COMPOSITION_LAYER_CYLINDER_EXTENSION_NAME))
                return XR_ERROR_LAYER_INVALID;
            if (l->type == XR_TYPE_COMPOSITION_LAYER_EQUIRECT2_KHR && !extEnabled(s->instance, XR_KHR_COMPOSITION_LAYER_EQUIRECT2_EXTENSION_NAME))
                return XR_ERROR_LAYER_INVALID;
            // the three share eyeVisibility, subImage and pose at the same place after the header
            const XrSwapchainSubImage* sub; XrPosef pose; XrEyeVisibility vis;
            HxComposeQuad cq{};
            if (l->type == XR_TYPE_COMPOSITION_LAYER_QUAD) {
                auto* q = reinterpret_cast<const XrCompositionLayerQuad*>(l);
                sub = &q->subImage; pose = q->pose; vis = q->eyeVisibility;
                cq.shape = HX_SHAPE_QUAD;
                cq.size = q->size;
            } else if (l->type == XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR) {
                auto* c = reinterpret_cast<const XrCompositionLayerCylinderKHR*>(l);
                sub = &c->subImage; pose = c->pose; vis = c->eyeVisibility;
                if (c->radius < 0 || c->centralAngle < 0 || c->centralAngle > 2 * static_cast<float>(M_PI) || !(c->aspectRatio > 0))
                    return XR_ERROR_VALIDATION_FAILURE;
                cq.shape = HX_SHAPE_CYLINDER;
                cq.radius = c->radius; cq.centralAngle = c->centralAngle; cq.aspectRatio = c->aspectRatio;
            } else {
                auto* e = reinterpret_cast<const XrCompositionLayerEquirect2KHR*>(l);
                sub = &e->subImage; pose = e->pose; vis = e->eyeVisibility;
                const float half = static_cast<float>(M_PI) / 2;
                if (e->radius < 0 || e->centralHorizontalAngle < 0 || e->centralHorizontalAngle > 2 * static_cast<float>(M_PI) ||
                    e->upperVerticalAngle > half || e->lowerVerticalAngle < -half || e->lowerVerticalAngle > e->upperVerticalAngle)
                    return XR_ERROR_VALIDATION_FAILURE;
                cq.shape = HX_SHAPE_EQUIRECT;
                cq.radius = e->radius; cq.centralAngle = e->centralHorizontalAngle;
                cq.upperAngle = e->upperVerticalAngle; cq.lowerAngle = e->lowerVerticalAngle;
            }
            if (vis > XR_EYE_VISIBILITY_RIGHT) return XR_ERROR_VALIDATION_FAILURE;
            Swapchain* sc = asSwapchain(sub->swapchain);
            if (!sc) return XR_ERROR_HANDLE_INVALID;
            if (sc->lastReleased < 0) return XR_ERROR_LAYER_INVALID;
            if (!hx::poseValid(pose)) return XR_ERROR_POSE_INVALID;
            if (!located) continue;
            // nothing to draw: a zero-size quad, a flat cylinder, an empty strip of sphere
            if (cq.shape == HX_SHAPE_CYLINDER && (cq.radius <= 0 || cq.centralAngle <= 0)) continue;
            if (cq.shape == HX_SHAPE_EQUIRECT && (cq.centralAngle <= 0 || cq.upperAngle <= cq.lowerAngle)) continue;
            cq.swapchain = sc->gfx;
            cq.imageIndex = static_cast<uint32_t>(sc->lastReleased);
            cq.arraySlice = sub->imageArrayIndex;
            cq.rect = sub->imageRect;
            cq.pose = hx::compose(spacePose, pose);
            cq.headLocked = 0;
            cq.blendAlpha = (l->layerFlags & XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT) != 0;
            cq.eye = vis == XR_EYE_VISIBILITY_LEFT ? 0 : vis == XR_EYE_VISIBILITY_RIGHT ? 1 : -1;
            customBlend(s->instance, l, cq.blend);
            quads[quadCount++] = cq;
        } else {
            return XR_ERROR_LAYER_INVALID;
        }
    }
    // ALPHA_BLEND: the app's alpha decides where the room shows, whatever the layer flags say
    if (info->environmentBlendMode == XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND)
        for (uint32_t v = 0; v < viewCount; v++) views[v].blendAlpha = 1;
    if (s->gfx) {
        XrPosef head;
        // late latch: the newest head pose for timewarp, predicted to this frame's display time
        if (!hxp::headPose(info->displayTime, cfg, &head)) head = s->lastHead;
        s->lastHead = head;
        int64_t c0 = hxp::nowNs();
        hxp::gfxCompose(s->gfx, cfg, head, views, viewCount, quads, quadCount, passthrough);
        s->composeNs = hxp::nowNs() - c0;
    }
    if (passthrough.on) hxp::passthroughWanted();
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
    s->handProfile = s->microProfile = XR_NULL_PATH;
    const char* const alongside[2] = {kHandProfile, kMicroProfile};
    for (int k = 0; k < 2; k++) {
        XrPath hp;
        if (stringToPath(inst, alongside[k], &hp) != XR_SUCCESS) continue;
        for (int i = 0; i < inst->bindingCount; i++) {
            const auto& bd = inst->bindings[i];
            if (bd.profile == hp && isLive(bd.action, MAGIC_ACTION) && actionAttachedTo(s, static_cast<Action*>(bd.action)))
                (k == 0 ? s->handProfile : s->microProfile) = hp;
        }
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
    // hands for hand_interaction_ext (asking for them keeps HyperMagic's camera on)
    if (focused && (s->handProfile != XR_NULL_PATH || s->microProfile != XR_NULL_PATH)) s->handsOk = hxp::readHands(&s->hands, true);
    else s->handsOk = false;
    int64_t handNow = hxp::nowNs();
    for (int h = 0; h < 2; h++) {
        s->micro[h].pulses = 0;
        bool fresh = s->handsOk && s->hands.tracked[h] && handNow - s->hands.timeNs[h] <= HAND_STALE_NS;
        if (s->microProfile != XR_NULL_PATH && fresh) s->micro[h].pulses = detectMicro(s->micro[h], s->hands, h, handNow);
        else if (!fresh) s->micro[h].touching = false;
    }
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
    // a single pulse, an amplitude envelope (Meta) or PCM samples (Meta): HyperMagic rumbles the
    // controller of that hand with the duration and the average strength
    float amplitude = 0;
    int64_t duration = 0;
    if (fb->type == XR_TYPE_HAPTIC_VIBRATION) {
        auto* v = reinterpret_cast<const XrHapticVibration*>(fb);
        amplitude = v->amplitude;
        duration = v->duration <= 0 ? 20000000LL : v->duration;  // XR_MIN_HAPTIC_DURATION: the shortest pulse
    } else if (fb->type == XR_TYPE_HAPTIC_AMPLITUDE_ENVELOPE_VIBRATION_FB && extEnabled(s->instance, XR_FB_HAPTIC_AMPLITUDE_ENVELOPE_EXTENSION_NAME)) {
        auto* e = reinterpret_cast<const XrHapticAmplitudeEnvelopeVibrationFB*>(fb);
        if (e->amplitudeCount > 0 && !e->amplitudes) return XR_ERROR_VALIDATION_FAILURE;
        for (uint32_t i = 0; i < e->amplitudeCount; i++) amplitude += e->amplitudes[i];
        amplitude = e->amplitudeCount ? amplitude / static_cast<float>(e->amplitudeCount) : 0;
        duration = e->duration;
    } else if (fb->type == XR_TYPE_HAPTIC_PCM_VIBRATION_FB && extEnabled(s->instance, XR_FB_HAPTIC_PCM_EXTENSION_NAME)) {
        auto* p = reinterpret_cast<const XrHapticPcmVibrationFB*>(fb);
        if ((p->bufferSize > 0 && !p->buffer) || !p->samplesConsumed || !(p->sampleRate > 0)) return XR_ERROR_VALIDATION_FAILURE;
        float sum = 0;
        for (uint32_t i = 0; i < p->bufferSize; i++) sum += fabsf(p->buffer[i]);
        amplitude = p->bufferSize ? sum / static_cast<float>(p->bufferSize) : 0;
        duration = static_cast<int64_t>(p->bufferSize / p->sampleRate * 1e9f);
        *p->samplesConsumed = p->bufferSize;
    } else {
        return XR_ERROR_VALIDATION_FAILURE;
    }
    if (amplitude < 0) amplitude = 0;
    if (amplitude > 1) amplitude = 1;
    if (duration > 2000000000LL) duration = 2000000000LL;
    int slot = slotOf(s->instance, info->subactionPath);
    if (slot == 3) return XR_ERROR_PATH_UNSUPPORTED;
    for (int h = 0; h < 2; h++) if ((slot == 2 || slot == h) && evalAction(s, a, h).active) hxp::haptic(h, amplitude, duration);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetDeviceSampleRateFB(XrSession h, const XrHapticActionInfo* info, XrDevicePcmSampleRateGetInfoFB* rate) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_HAPTIC_ACTION_INFO || !rate || rate->type != XR_TYPE_DEVICE_PCM_SAMPLE_RATE_STATE_FB) return XR_ERROR_VALIDATION_FAILURE;
    Action* a = asAction(info->action);
    if (!a) return XR_ERROR_HANDLE_INVALID;
    if (a->type != XR_ACTION_TYPE_VIBRATION_OUTPUT) return XR_ERROR_ACTION_TYPE_MISMATCH;
    rate->sampleRate = 2000;  // the rate the samples are averaged at (Touch controllers use 2 kHz too)
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
    int slot = slotOf(s->instance, info->subactionPath);
    for (int hd = 0; hd < 2; hd++) if (slot == 2 || slot == hd) hxp::haptic(hd, 0, 0);
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

// ------------------------------------------------------------------ hands (XR_EXT_hand_tracking)
XRAPI_ATTR XrResult XRAPI_CALL CreateHandTrackerEXT(XrSession h, const XrHandTrackerCreateInfoEXT* ci, XrHandTrackerEXT* out) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!ci || ci->type != XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT || !out) return XR_ERROR_VALIDATION_FAILURE;
    if (ci->hand != XR_HAND_LEFT_EXT && ci->hand != XR_HAND_RIGHT_EXT) return XR_ERROR_VALIDATION_FAILURE;
    if (ci->handJointSet != XR_HAND_JOINT_SET_DEFAULT_EXT) return XR_ERROR_VALIDATION_FAILURE;
    HandTracker* t = alloc<HandTracker>(MAGIC_HAND, s);
    if (!t) return XR_ERROR_OUT_OF_MEMORY;
    t->session = s;
    t->hand = ci->hand;
    s->handTrackers++;
    HxHandShared tmp;
    hxp::readHands(&tmp, true);  // HyperMagic turns the camera on now, not at the first locate
    *out = reinterpret_cast<XrHandTrackerEXT>(t);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL DestroyHandTrackerEXT(XrHandTrackerEXT h) {
    HandTracker* t = asObj<HandTracker>(reinterpret_cast<uint64_t>(h), MAGIC_HAND);
    if (!t) return XR_ERROR_HANDLE_INVALID;
    if (isLive(t->session, MAGIC_SESSION) && t->session->handTrackers > 0) t->session->handTrackers--;
    release(t);
    return XR_SUCCESS;
}

/** The hand meshes, built once (left, right). */
hxhandmesh::Mesh gHandMesh[2];
pthread_once_t gHandMeshOnce = PTHREAD_ONCE_INIT;
void buildHandMeshes() { hxhandmesh::build(true, &gHandMesh[0]); hxhandmesh::build(false, &gHandMesh[1]); }

XRAPI_ATTR XrResult XRAPI_CALL GetHandMeshFB(XrHandTrackerEXT h, XrHandTrackingMeshFB* mesh) {
    HandTracker* t = asObj<HandTracker>(reinterpret_cast<uint64_t>(h), MAGIC_HAND);
    if (!t) return XR_ERROR_HANDLE_INVALID;
    if (!mesh || mesh->type != XR_TYPE_HAND_TRACKING_MESH_FB) return XR_ERROR_VALIDATION_FAILURE;
    pthread_once(&gHandMeshOnce, buildHandMeshes);
    const hxhandmesh::Mesh& m = gHandMesh[t->hand == XR_HAND_LEFT_EXT ? 0 : 1];
    mesh->jointCountOutput = XR_HAND_JOINT_COUNT_EXT;
    mesh->vertexCountOutput = hxhandmesh::kVertices;
    mesh->indexCountOutput = hxhandmesh::kIndices;
    // two-call idiom: all capacities zero asks for the sizes
    if (mesh->jointCapacityInput == 0 && mesh->vertexCapacityInput == 0 && mesh->indexCapacityInput == 0) return XR_SUCCESS;
    if (mesh->jointCapacityInput < XR_HAND_JOINT_COUNT_EXT || mesh->vertexCapacityInput < static_cast<uint32_t>(hxhandmesh::kVertices) ||
        mesh->indexCapacityInput < static_cast<uint32_t>(hxhandmesh::kIndices))
        return XR_ERROR_SIZE_INSUFFICIENT;
    if (!mesh->jointBindPoses || !mesh->jointRadii || !mesh->jointParents || !mesh->vertexPositions || !mesh->vertexNormals ||
        !mesh->vertexUVs || !mesh->vertexBlendIndices || !mesh->vertexBlendWeights || !mesh->indices)
        return XR_ERROR_VALIDATION_FAILURE;
    for (int j = 0; j < XR_HAND_JOINT_COUNT_EXT; j++) {
        const hxhands::Joint& b = m.bind[j];
        mesh->jointBindPoses[j] = XrPosef{{b.q.x, b.q.y, b.q.z, b.q.w}, {b.p.x, b.p.y, b.p.z}};
        mesh->jointRadii[j] = b.radius;
        mesh->jointParents[j] = static_cast<XrHandJointEXT>(m.parents[j]);
    }
    for (int v = 0; v < hxhandmesh::kVertices; v++) {
        mesh->vertexPositions[v] = {m.pos[v].x, m.pos[v].y, m.pos[v].z};
        mesh->vertexNormals[v] = {m.normal[v].x, m.normal[v].y, m.normal[v].z};
        mesh->vertexUVs[v] = {m.uv[v][0], m.uv[v][1]};
        mesh->vertexBlendIndices[v] = {m.blendIndex[v], 0, 0, 0};
        mesh->vertexBlendWeights[v] = {1, 0, 0, 0};
    }
    memcpy(mesh->indices, m.indices, sizeof(m.indices));
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL LocateHandJointsEXT(XrHandTrackerEXT h, const XrHandJointsLocateInfoEXT* info, XrHandJointLocationsEXT* locs) {
    HandTracker* t = asObj<HandTracker>(reinterpret_cast<uint64_t>(h), MAGIC_HAND);
    if (!t) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT || !locs || locs->type != XR_TYPE_HAND_JOINT_LOCATIONS_EXT)
        return XR_ERROR_VALIDATION_FAILURE;
    if (info->time <= 0) return XR_ERROR_TIME_INVALID;
    Space* base = asSpace(info->baseSpace);
    if (!base) return XR_ERROR_HANDLE_INVALID;
    if (locs->jointCount != XR_HAND_JOINT_COUNT_EXT || !locs->jointLocations) return XR_ERROR_VALIDATION_FAILURE;
    XrHandJointVelocitiesEXT* vel = nullptr;
    XrHandTrackingAimStateFB* aim = nullptr;
    XrHandTrackingScaleFB* scale = nullptr;
    XrHandTrackingCapsulesStateFB* capsules = nullptr;
    for (auto* n = reinterpret_cast<XrBaseOutStructure*>(locs->next); n; n = n->next) {
        if (n->type == XR_TYPE_HAND_JOINT_VELOCITIES_EXT) vel = reinterpret_cast<XrHandJointVelocitiesEXT*>(n);
        if (n->type == XR_TYPE_HAND_TRACKING_AIM_STATE_FB) aim = reinterpret_cast<XrHandTrackingAimStateFB*>(n);
        if (n->type == XR_TYPE_HAND_TRACKING_SCALE_FB) scale = reinterpret_cast<XrHandTrackingScaleFB*>(n);
        if (n->type == XR_TYPE_HAND_TRACKING_CAPSULES_STATE_FB) capsules = reinterpret_cast<XrHandTrackingCapsulesStateFB*>(n);
    }
    if (scale) { scale->sensorOutput = 1; scale->currentOutput = scale->overrideHandScale ? scale->overrideValueInput : 1; }
    if (capsules) memset(capsules->capsules, 0, sizeof(capsules->capsules));
    if (vel) {
        if (vel->jointCount != XR_HAND_JOINT_COUNT_EXT || !vel->jointVelocities) return XR_ERROR_VALIDATION_FAILURE;
        for (uint32_t i = 0; i < vel->jointCount; i++) vel->jointVelocities[i].velocityFlags = 0;
    }
    if (aim) { aim->status = 0; aim->aimPose = hx::identity(); aim->pinchStrengthIndex = aim->pinchStrengthMiddle = 0; aim->pinchStrengthRing = aim->pinchStrengthLittle = 0; }
    locs->isActive = XR_FALSE;
    for (uint32_t i = 0; i < locs->jointCount; i++) { locs->jointLocations[i].locationFlags = 0; locs->jointLocations[i].pose = hx::identity(); locs->jointLocations[i].radius = 0; }

    Instance* inst = t->session->instance;
    HxHandShared hands;
    if (!hxp::readHands(&hands, true)) return XR_SUCCESS;
    int side = t->hand == XR_HAND_LEFT_EXT ? 0 : 1;
    if (!hands.tracked[side] || hxp::nowNs() - hands.timeNs[side] > HAND_STALE_NS) return XR_SUCCESS;
    hxhands::Joint joints[XR_HAND_JOINT_COUNT_EXT];
    if (!hxhands::toXrJoints(hands.joints[side], side == 0, joints)) return XR_SUCCESS;
    // hand size against the mesh's bind pose (palm 9.5 cm); an app may override it
    float measured = joints[XR_HAND_JOINT_WRIST_EXT].radius / 0.027f;
    if (scale) {
        scale->sensorOutput = measured;
        scale->currentOutput = measured;
        if (scale->overrideHandScale && scale->overrideValueInput > 0) {
            float k = scale->overrideValueInput / measured;
            hxhands::V wrist = joints[XR_HAND_JOINT_WRIST_EXT].p;
            for (auto& j : joints) { j.p = hxhands::add(wrist, hxhands::mul(hxhands::sub(j.p, wrist), k)); j.radius *= k; }
            scale->currentOutput = scale->overrideValueInput;
        }
    }
    // camera space -> stage: the camera rides with the head, so it is where the head was when
    // the hands were seen; then into the base space
    XrPosef head, basePose;
    bool baseTracked;
    if (!hxp::headPose(hands.timeNs[side], inst->cfg, &head)) return XR_SUCCESS;
    if (!spaceInStage(base, info->time, &basePose, &baseTracked)) return XR_SUCCESS;
    XrPosef camera = hx::compose(head, XrPosef{{0, 0, 0, 1}, {0, 0, -CAMERA_FORWARD_M}});
    XrPosef toBase = hx::compose(hx::inverse(basePose), camera);
    for (uint32_t i = 0; i < XR_HAND_JOINT_COUNT_EXT; i++) {
        XrPosef p{{joints[i].q.x, joints[i].q.y, joints[i].q.z, joints[i].q.w}, {joints[i].p.x, joints[i].p.y, joints[i].p.z}};
        locs->jointLocations[i].pose = hx::compose(toBase, p);
        locs->jointLocations[i].radius = joints[i].radius;
        locs->jointLocations[i].locationFlags = XR_SPACE_LOCATION_ORIENTATION_VALID_BIT | XR_SPACE_LOCATION_POSITION_VALID_BIT |
                                                XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT | XR_SPACE_LOCATION_POSITION_TRACKED_BIT;
    }
    locs->isActive = XR_TRUE;
    if (capsules) {
        for (int b = 0; b < XR_HAND_TRACKING_CAPSULE_COUNT_FB; b++) {
            int ja, jb;
            hxhandmesh::bone(b, &ja, &jb);
            XrHandCapsuleFB& c = capsules->capsules[b];
            c.points[0] = locs->jointLocations[ja].pose.position;
            c.points[1] = locs->jointLocations[jb].pose.position;
            c.radius = joints[jb].radius < joints[ja].radius ? joints[jb].radius : joints[ja].radius;
            c.joint = static_cast<XrHandJointEXT>(ja);
        }
    }
    if (aim) {
        aim->aimPose = hx::compose(hx::inverse(basePose), handAim(inst->cfg, hands, side, camera, info->time));
        float pinch = hands.pinch[side] < 0 ? 0 : (hands.pinch[side] > 1 ? 1 : hands.pinch[side]);
        aim->pinchStrengthIndex = pinch;
        aim->status = XR_HAND_TRACKING_AIM_COMPUTED_BIT_FB | XR_HAND_TRACKING_AIM_VALID_BIT_FB |
                      (pinch > 0.8f ? XR_HAND_TRACKING_AIM_INDEX_PINCHING_BIT_FB : 0);
    }
    return XR_SUCCESS;
}

// ------------------------------------------------------------------ spatial entities and the room
constexpr uint32_t bit(XrSpaceComponentTypeFB c) { return 1u << c; }

/** Components each kind of entity has (all of them enabled from the start). */
uint32_t componentsOf(int kind) {
    switch (kind) {
        case DEAD: return 0;
        case hxscene::ANCHOR: return bit(XR_SPACE_COMPONENT_TYPE_LOCATABLE_FB) | bit(XR_SPACE_COMPONENT_TYPE_STORABLE_FB);
        case hxscene::ROOM: return bit(XR_SPACE_COMPONENT_TYPE_LOCATABLE_FB) | bit(XR_SPACE_COMPONENT_TYPE_SPACE_CONTAINER_FB) |
                                   bit(XR_SPACE_COMPONENT_TYPE_ROOM_LAYOUT_FB);
        // the room's global mesh (XR_META_spatial_entity_mesh); its triangle mesh component is not a
        // bit here (its value is too large) and is always there
        case hxscene::MESH: return bit(XR_SPACE_COMPONENT_TYPE_LOCATABLE_FB) | bit(XR_SPACE_COMPONENT_TYPE_SEMANTIC_LABELS_FB);
        default: return bit(XR_SPACE_COMPONENT_TYPE_LOCATABLE_FB) | bit(XR_SPACE_COMPONENT_TYPE_BOUNDED_2D_FB) |
                        bit(XR_SPACE_COMPONENT_TYPE_SEMANTIC_LABELS_FB);
    }
}

XrAsyncRequestIdFB newRequest(Instance* inst) {
    pthread_mutex_lock(&inst->lock);
    XrAsyncRequestIdFB id = ++inst->nextRequest;
    pthread_mutex_unlock(&inst->lock);
    return id;
}

SceneEntity* addEntity(Session* s, int kind, XrUuidEXT uuid, const XrPosef& pose) {
    if (!s->entities) s->entities = static_cast<SceneEntity*>(calloc(MAX_ENTITIES, sizeof(SceneEntity)));
    if (!s->entities || s->entityCount >= MAX_ENTITIES) return nullptr;
    SceneEntity& e = s->entities[s->entityCount++];
    memset(&e, 0, sizeof(e));
    e.uuid = uuid; e.kind = kind; e.pose = pose;
    e.enabled = componentsOf(kind);
    return &e;
}

int findEntity(Session* s, const XrUuidEXT& u) {
    for (int i = 0; i < s->entityCount; i++) if (memcmp(s->entities[i].uuid.data, u.data, 16) == 0) return i;
    return -1;
}

/** The room from HyperMagic (or the default one) as entities: room, floor, ceiling, walls. */
void buildScene(Session* s) {
    hxscene::Room r;
    memset(&r, 0, sizeof(r));
    if (!hxp::loadRoom(r.x, r.z, hxscene::MAX_POLY, &r.count, &r.height) || !hxscene::valid(r)) { memset(&r, 0, sizeof(r)); hxscene::defaultRoom(&r); }
    if (s->sceneBuilt && memcmp(&r, &s->room, sizeof(r)) == 0) return;  // the same room: same entities
    // the previous room's entities stay in place (spaces point at them by index) but go dead
    for (int i = 0; i < s->entityCount; i++)
        if (s->entities[i].kind != hxscene::ANCHOR) { s->entities[i].kind = DEAD; s->entities[i].enabled = 0; }
    s->room = r;
    hxscene::Plane planes[hxscene::MAX_PLANES];
    int n = hxscene::build(r, planes, s->floorOutline, s->ceilingOutline);
    addEntity(s, hxscene::ROOM, hxscene::sceneUuid(r, hxscene::ROOM, 0), XrPosef{{0, 0, 0, 1}, planes[0].pose.position});
    if (extEnabled(s->instance, XR_META_SPATIAL_ENTITY_MESH_EXTENSION_NAME))
        addEntity(s, hxscene::MESH, hxscene::sceneUuid(r, hxscene::MESH, 0), XrPosef{{0, 0, 0, 1}, planes[0].pose.position});
    for (int i = 0; i < n; i++) {
        SceneEntity* e = addEntity(s, planes[i].kind, hxscene::sceneUuid(r, planes[i].kind, i), planes[i].pose);
        if (!e) break;
        e->minX = planes[i].minX; e->minY = planes[i].minY; e->maxX = planes[i].maxX; e->maxY = planes[i].maxY;
    }
    s->sceneBuilt = true;
}

void ensureScene(Session* s) {
    if (!s->anchorsLoaded) {
        s->anchorsLoaded = true;
        HxSavedAnchor saved[MAX_ENTITIES];
        int n = hxp::loadAnchors(saved, MAX_ENTITIES / 2);
        for (int i = 0; i < n; i++) {
            XrUuidEXT u;
            memcpy(u.data, saved[i].uuid, 16);
            if (findEntity(s, u) >= 0) continue;
            if (SceneEntity* e = addEntity(s, hxscene::ANCHOR, u, saved[i].pose)) e->stored = true;
        }
    }
    if (!s->sceneBuilt) buildScene(s);
}

void persistAnchors(Session* s) {
    HxSavedAnchor out[MAX_ENTITIES];
    int n = 0;
    for (int i = 0; i < s->entityCount; i++) {
        const SceneEntity& e = s->entities[i];
        if (e.kind != hxscene::ANCHOR || !e.stored) continue;
        memcpy(out[n].uuid, e.uuid.data, 16);
        out[n].pose = e.pose;
        n++;
    }
    hxp::saveAnchors(out, n);
}

/** A new XrSpace handle for entity [index] of [s]. */
XrSpace entitySpace(Session* s, int index) {
    Space* sp = alloc<Space>(MAGIC_SPACE, s);
    if (!sp) return XR_NULL_HANDLE;
    sp->session = s;
    sp->offset = hx::identity();
    sp->entity = index + 1;
    s->liveSpaces++;
    return reinterpret_cast<XrSpace>(sp);
}

SceneEntity* entityOf(Space* sp) { return sp && sp->entity > 0 ? &sp->session->entities[sp->entity - 1] : nullptr; }

XRAPI_ATTR XrResult XRAPI_CALL CreateSpatialAnchorFB(XrSession h, const XrSpatialAnchorCreateInfoFB* info, XrAsyncRequestIdFB* requestId) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_SPATIAL_ANCHOR_CREATE_INFO_FB || !requestId) return XR_ERROR_VALIDATION_FAILURE;
    Space* base = asSpace(info->space);
    if (!base || base->session != s) return XR_ERROR_HANDLE_INVALID;
    if (!hx::poseValid(info->poseInSpace)) return XR_ERROR_POSE_INVALID;
    if (info->time <= 0) return XR_ERROR_TIME_INVALID;
    ensureScene(s);
    XrEventDataSpatialAnchorCreateCompleteFB ev{XR_TYPE_EVENT_DATA_SPATIAL_ANCHOR_CREATE_COMPLETE_FB};
    ev.requestId = *requestId = newRequest(s->instance);
    XrPosef basePose; bool tracked;
    if (!spaceInStage(base, info->time, &basePose, &tracked)) {
        ev.result = XR_ERROR_SPACE_COMPONENT_NOT_ENABLED_FB;  // the base space cannot be located now
    } else {
        XrUuidEXT u;
        hxp::randomBytes(u.data, 16);
        u.data[6] = static_cast<uint8_t>((u.data[6] & 0x0f) | 0x40);  // version 4 (random)
        u.data[8] = static_cast<uint8_t>((u.data[8] & 0x3f) | 0x80);
        SceneEntity* e = addEntity(s, hxscene::ANCHOR, u, hx::compose(basePose, info->poseInSpace));
        if (!e) ev.result = XR_ERROR_LIMIT_REACHED;
        else {
            ev.result = XR_SUCCESS;
            ev.space = entitySpace(s, static_cast<int>(e - s->entities));
            ev.uuid = u;
        }
    }
    pushEvent(s->instance, &ev, sizeof(ev));
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetSpaceUuidFB(XrSpace h, XrUuidEXT* uuid) {
    Space* sp = asSpace(h);
    if (!sp) return XR_ERROR_HANDLE_INVALID;
    if (!uuid) return XR_ERROR_VALIDATION_FAILURE;
    SceneEntity* e = entityOf(sp);
    if (!e) return XR_ERROR_VALIDATION_FAILURE;  // reference and action spaces have no UUID
    *uuid = e->uuid;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL EnumerateSpaceSupportedComponentsFB(XrSpace h, uint32_t capacity, uint32_t* count, XrSpaceComponentTypeFB* types) {
    Space* sp = asSpace(h);
    if (!sp) return XR_ERROR_HANDLE_INVALID;
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    SceneEntity* e = entityOf(sp);
    uint32_t mask = e ? componentsOf(e->kind) : 0, n = 0;
    bool mesh = e && e->kind == hxscene::MESH;
    for (uint32_t c = 0; c < 8; c++) if (mask & (1u << c)) n++;
    if (mesh) n++;
    *count = n;
    if (capacity == 0) return XR_SUCCESS;
    if (capacity < n) return XR_ERROR_SIZE_INSUFFICIENT;
    if (!types) return XR_ERROR_VALIDATION_FAILURE;
    n = 0;
    for (uint32_t c = 0; c < 8; c++) if (mask & (1u << c)) types[n++] = static_cast<XrSpaceComponentTypeFB>(c);
    if (mesh) types[n++] = XR_SPACE_COMPONENT_TYPE_TRIANGLE_MESH_META;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL SetSpaceComponentStatusFB(XrSpace h, const XrSpaceComponentStatusSetInfoFB* info, XrAsyncRequestIdFB* requestId) {
    Space* sp = asSpace(h);
    if (!sp) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_SPACE_COMPONENT_STATUS_SET_INFO_FB || !requestId) return XR_ERROR_VALIDATION_FAILURE;
    SceneEntity* e = entityOf(sp);
    bool meshComponent = e && e->kind == hxscene::MESH && info->componentType == XR_SPACE_COMPONENT_TYPE_TRIANGLE_MESH_META;
    if (!meshComponent && (!e || info->componentType > XR_SPACE_COMPONENT_TYPE_SPACE_CONTAINER_FB || !(componentsOf(e->kind) & bit(info->componentType))))
        return XR_ERROR_SPACE_COMPONENT_NOT_SUPPORTED_FB;
    if (meshComponent) {  // always on: report the request as done
        XrEventDataSpaceSetStatusCompleteFB ev{XR_TYPE_EVENT_DATA_SPACE_SET_STATUS_COMPLETE_FB};
        ev.requestId = *requestId = newRequest(sp->session->instance);
        ev.result = info->enabled ? XR_SUCCESS : XR_ERROR_SPACE_COMPONENT_NOT_SUPPORTED_FB;
        ev.space = h; ev.uuid = e->uuid; ev.componentType = info->componentType; ev.enabled = XR_TRUE;
        pushEvent(sp->session->instance, &ev, sizeof(ev));
        return XR_SUCCESS;
    }
    if (info->enabled) e->enabled |= bit(info->componentType); else e->enabled &= ~bit(info->componentType);
    // asking for the state it is already in also completes (apps wait for the event either way)
    XrEventDataSpaceSetStatusCompleteFB ev{XR_TYPE_EVENT_DATA_SPACE_SET_STATUS_COMPLETE_FB};
    ev.requestId = *requestId = newRequest(sp->session->instance);
    ev.result = XR_SUCCESS;
    ev.space = h;
    ev.uuid = e->uuid;
    ev.componentType = info->componentType;
    ev.enabled = info->enabled ? XR_TRUE : XR_FALSE;
    pushEvent(sp->session->instance, &ev, sizeof(ev));
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetSpaceComponentStatusFB(XrSpace h, XrSpaceComponentTypeFB type, XrSpaceComponentStatusFB* status) {
    Space* sp = asSpace(h);
    if (!sp) return XR_ERROR_HANDLE_INVALID;
    if (!status || status->type != XR_TYPE_SPACE_COMPONENT_STATUS_FB) return XR_ERROR_VALIDATION_FAILURE;
    SceneEntity* e = entityOf(sp);
    if (e && e->kind == hxscene::MESH && type == XR_SPACE_COMPONENT_TYPE_TRIANGLE_MESH_META) {
        status->enabled = XR_TRUE; status->changePending = XR_FALSE; return XR_SUCCESS;
    }
    if (!e || type > XR_SPACE_COMPONENT_TYPE_SPACE_CONTAINER_FB || !(componentsOf(e->kind) & bit(type))) return XR_ERROR_SPACE_COMPONENT_NOT_SUPPORTED_FB;
    status->enabled = (e->enabled & bit(type)) ? XR_TRUE : XR_FALSE;
    status->changePending = XR_FALSE;
    return XR_SUCCESS;
}

/** Whether entity [e] passes a query filter (null: everything the app can load). */
bool passes(const SceneEntity& e, const XrSpaceFilterInfoBaseHeaderFB* f) {
    if (e.kind == DEAD) return false;
    if (!f) return e.kind != hxscene::ANCHOR || e.stored;
    if (f->type == XR_TYPE_SPACE_UUID_FILTER_INFO_FB) {
        auto* u = reinterpret_cast<const XrSpaceUuidFilterInfoFB*>(f);
        for (uint32_t i = 0; i < u->uuidCount; i++) if (memcmp(u->uuids[i].data, e.uuid.data, 16) == 0) return true;
        return false;
    }
    if (f->type == XR_TYPE_SPACE_COMPONENT_FILTER_INFO_FB) {
        auto* c = reinterpret_cast<const XrSpaceComponentFilterInfoFB*>(f);
        if (c->componentType == XR_SPACE_COMPONENT_TYPE_TRIANGLE_MESH_META) return e.kind == hxscene::MESH;
        return c->componentType <= XR_SPACE_COMPONENT_TYPE_SPACE_CONTAINER_FB && (componentsOf(e.kind) & bit(c->componentType)) &&
               (e.kind != hxscene::ANCHOR || e.stored);
    }
    return false;
}

XRAPI_ATTR XrResult XRAPI_CALL QuerySpacesFB(XrSession h, const XrSpaceQueryInfoBaseHeaderFB* info, XrAsyncRequestIdFB* requestId) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_SPACE_QUERY_INFO_FB || !requestId) return XR_ERROR_VALIDATION_FAILURE;
    auto* q = reinterpret_cast<const XrSpaceQueryInfoFB*>(info);
    if (q->queryAction != XR_SPACE_QUERY_ACTION_LOAD_FB) return XR_ERROR_VALIDATION_FAILURE;
    if (q->filter && q->filter->type != XR_TYPE_SPACE_UUID_FILTER_INFO_FB && q->filter->type != XR_TYPE_SPACE_COMPONENT_FILTER_INFO_FB)
        return XR_ERROR_VALIDATION_FAILURE;
    ensureScene(s);
    Session::SpaceQuery& rq = s->queries[s->nextQuery];
    s->nextQuery = (s->nextQuery + 1) % 8;
    rq.id = *requestId = newRequest(s->instance);
    rq.count = 0;
    uint32_t limit = q->maxResultCount ? q->maxResultCount : MAX_ENTITIES;
    for (int i = 0; i < s->entityCount && static_cast<uint32_t>(rq.count) < limit; i++) {
        const SceneEntity& e = s->entities[i];
        if (!passes(e, q->filter)) continue;
        if (q->excludeFilter && passes(e, q->excludeFilter)) continue;
        rq.entity[rq.count++] = static_cast<int16_t>(i);
    }
    if (rq.count > 0) {
        XrEventDataSpaceQueryResultsAvailableFB av{XR_TYPE_EVENT_DATA_SPACE_QUERY_RESULTS_AVAILABLE_FB};
        av.requestId = rq.id;
        pushEvent(s->instance, &av, sizeof(av));
    }
    XrEventDataSpaceQueryCompleteFB done{XR_TYPE_EVENT_DATA_SPACE_QUERY_COMPLETE_FB};
    done.requestId = rq.id;
    done.result = XR_SUCCESS;
    pushEvent(s->instance, &done, sizeof(done));
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL RetrieveSpaceQueryResultsFB(XrSession h, XrAsyncRequestIdFB requestId, XrSpaceQueryResultsFB* results) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!results || results->type != XR_TYPE_SPACE_QUERY_RESULTS_FB) return XR_ERROR_VALIDATION_FAILURE;
    Session::SpaceQuery* rq = nullptr;
    for (auto& q : s->queries) if (q.id == requestId && requestId != 0) rq = &q;
    if (!rq) return XR_ERROR_VALIDATION_FAILURE;
    results->resultCountOutput = static_cast<uint32_t>(rq->count);
    if (results->resultCapacityInput == 0) return XR_SUCCESS;
    if (results->resultCapacityInput < static_cast<uint32_t>(rq->count)) return XR_ERROR_SIZE_INSUFFICIENT;
    if (!results->results) return XR_ERROR_VALIDATION_FAILURE;
    for (int i = 0; i < rq->count; i++) {
        results->results[i].space = entitySpace(s, rq->entity[i]);
        results->results[i].uuid = s->entities[rq->entity[i]].uuid;
    }
    rq->id = 0;  // results are handed out once
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL SaveSpaceFB(XrSession h, const XrSpaceSaveInfoFB* info, XrAsyncRequestIdFB* requestId) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_SPACE_SAVE_INFO_FB || !requestId) return XR_ERROR_VALIDATION_FAILURE;
    Space* sp = asSpace(info->space);
    if (!sp || sp->session != s) return XR_ERROR_HANDLE_INVALID;
    SceneEntity* e = entityOf(sp);
    if (!e || !(componentsOf(e->kind) & bit(XR_SPACE_COMPONENT_TYPE_STORABLE_FB))) return XR_ERROR_SPACE_COMPONENT_NOT_SUPPORTED_FB;
    if (!(e->enabled & bit(XR_SPACE_COMPONENT_TYPE_STORABLE_FB))) return XR_ERROR_SPACE_COMPONENT_NOT_ENABLED_FB;
    if (info->location != XR_SPACE_STORAGE_LOCATION_LOCAL_FB) return XR_ERROR_VALIDATION_FAILURE;  // no cloud
    e->stored = true;
    persistAnchors(s);
    XrEventDataSpaceSaveCompleteFB ev{XR_TYPE_EVENT_DATA_SPACE_SAVE_COMPLETE_FB};
    ev.requestId = *requestId = newRequest(s->instance);
    ev.result = XR_SUCCESS; ev.space = info->space; ev.uuid = e->uuid; ev.location = info->location;
    pushEvent(s->instance, &ev, sizeof(ev));
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL EraseSpaceFB(XrSession h, const XrSpaceEraseInfoFB* info, XrAsyncRequestIdFB* requestId) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_SPACE_ERASE_INFO_FB || !requestId) return XR_ERROR_VALIDATION_FAILURE;
    Space* sp = asSpace(info->space);
    if (!sp || sp->session != s) return XR_ERROR_HANDLE_INVALID;
    SceneEntity* e = entityOf(sp);
    if (!e || !(componentsOf(e->kind) & bit(XR_SPACE_COMPONENT_TYPE_STORABLE_FB))) return XR_ERROR_SPACE_COMPONENT_NOT_SUPPORTED_FB;
    if (info->location != XR_SPACE_STORAGE_LOCATION_LOCAL_FB) return XR_ERROR_VALIDATION_FAILURE;
    e->stored = false;
    persistAnchors(s);
    XrEventDataSpaceEraseCompleteFB ev{XR_TYPE_EVENT_DATA_SPACE_ERASE_COMPLETE_FB};
    ev.requestId = *requestId = newRequest(s->instance);
    ev.result = XR_SUCCESS; ev.space = info->space; ev.uuid = e->uuid; ev.location = info->location;
    pushEvent(s->instance, &ev, sizeof(ev));
    return XR_SUCCESS;
}

/** The entity behind [h] if it belongs to [s] and has component [c] enabled; [err] says why not. */
SceneEntity* withComponent(Session* s, XrSpace h, XrSpaceComponentTypeFB c, XrResult* err) {
    Space* sp = asSpace(h);
    if (!sp || sp->session != s) { *err = XR_ERROR_HANDLE_INVALID; return nullptr; }
    SceneEntity* e = entityOf(sp);
    if (!e || !(componentsOf(e->kind) & bit(c))) { *err = XR_ERROR_SPACE_COMPONENT_NOT_SUPPORTED_FB; return nullptr; }
    if (!(e->enabled & bit(c))) { *err = XR_ERROR_SPACE_COMPONENT_NOT_ENABLED_FB; return nullptr; }
    return e;
}

XRAPI_ATTR XrResult XRAPI_CALL GetSpaceContainerFB(XrSession h, XrSpace space, XrSpaceContainerFB* out) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!out || out->type != XR_TYPE_SPACE_CONTAINER_FB) return XR_ERROR_VALIDATION_FAILURE;
    XrResult err;
    if (!withComponent(s, space, XR_SPACE_COMPONENT_TYPE_SPACE_CONTAINER_FB, &err)) return err;
    uint32_t n = 0;
    for (int i = 0; i < s->entityCount; i++) if (s->entities[i].kind >= hxscene::FLOOR) n++;
    out->uuidCountOutput = n;
    if (out->uuidCapacityInput == 0) return XR_SUCCESS;
    if (out->uuidCapacityInput < n) return XR_ERROR_SIZE_INSUFFICIENT;
    if (!out->uuids) return XR_ERROR_VALIDATION_FAILURE;
    n = 0;
    for (int i = 0; i < s->entityCount; i++) if (s->entities[i].kind >= hxscene::FLOOR) out->uuids[n++] = s->entities[i].uuid;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetSpaceBoundingBox2DFB(XrSession h, XrSpace space, XrRect2Df* box) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!box) return XR_ERROR_VALIDATION_FAILURE;
    XrResult err;
    SceneEntity* e = withComponent(s, space, XR_SPACE_COMPONENT_TYPE_BOUNDED_2D_FB, &err);
    if (!e) return err;
    box->offset = {e->minX, e->minY};
    box->extent = {e->maxX - e->minX, e->maxY - e->minY};
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetSpaceBoundingBox3DFB(XrSession h, XrSpace space, XrRect3DfFB* box) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!box) return XR_ERROR_VALIDATION_FAILURE;
    XrResult err;
    if (!withComponent(s, space, XR_SPACE_COMPONENT_TYPE_BOUNDED_3D_FB, &err)) return err;
    return XR_ERROR_SPACE_COMPONENT_NOT_SUPPORTED_FB;  // no furniture volumes: nothing has this component
}

XRAPI_ATTR XrResult XRAPI_CALL GetSpaceSemanticLabelsFB(XrSession h, XrSpace space, XrSemanticLabelsFB* out) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!out || out->type != XR_TYPE_SEMANTIC_LABELS_FB) return XR_ERROR_VALIDATION_FAILURE;
    XrResult err;
    SceneEntity* e = withComponent(s, space, XR_SPACE_COMPONENT_TYPE_SEMANTIC_LABELS_FB, &err);
    if (!e) return err;
    const char* label = e->kind == hxscene::FLOOR ? "FLOOR" : e->kind == hxscene::CEILING ? "CEILING" :
                        e->kind == hxscene::MESH ? "GLOBAL_MESH" : "WALL_FACE";
    uint32_t need = static_cast<uint32_t>(strlen(label)) + 1;
    out->bufferCountOutput = need;
    if (out->bufferCapacityInput == 0) return XR_SUCCESS;
    if (out->bufferCapacityInput < need) return XR_ERROR_SIZE_INSUFFICIENT;
    if (!out->buffer) return XR_ERROR_VALIDATION_FAILURE;
    memcpy(out->buffer, label, need);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetSpaceBoundary2DFB(XrSession h, XrSpace space, XrBoundary2DFB* out) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!out || out->type != XR_TYPE_BOUNDARY_2D_FB) return XR_ERROR_VALIDATION_FAILURE;
    XrResult err;
    SceneEntity* e = withComponent(s, space, XR_SPACE_COMPONENT_TYPE_BOUNDED_2D_FB, &err);
    if (!e) return err;
    // floor and ceiling: the room outline; walls: their rectangle
    XrVector2f rect[4] = {{e->minX, e->minY}, {e->maxX, e->minY}, {e->maxX, e->maxY}, {e->minX, e->maxY}};
    const XrVector2f* v = e->kind == hxscene::FLOOR ? s->floorOutline : e->kind == hxscene::CEILING ? s->ceilingOutline : rect;
    uint32_t n = e->kind == hxscene::WALL ? 4u : static_cast<uint32_t>(s->room.count);
    out->vertexCountOutput = n;
    if (out->vertexCapacityInput == 0) return XR_SUCCESS;
    if (out->vertexCapacityInput < n) return XR_ERROR_SIZE_INSUFFICIENT;
    if (!out->vertices) return XR_ERROR_VALIDATION_FAILURE;
    memcpy(out->vertices, v, sizeof(XrVector2f) * n);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetSpaceRoomLayoutFB(XrSession h, XrSpace space, XrRoomLayoutFB* out) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!out || out->type != XR_TYPE_ROOM_LAYOUT_FB) return XR_ERROR_VALIDATION_FAILURE;
    XrResult err;
    if (!withComponent(s, space, XR_SPACE_COMPONENT_TYPE_ROOM_LAYOUT_FB, &err)) return err;
    uint32_t walls = 0;
    for (int i = 0; i < s->entityCount; i++) {
        const SceneEntity& e = s->entities[i];
        if (e.kind == hxscene::FLOOR) out->floorUuid = e.uuid;
        if (e.kind == hxscene::CEILING) out->ceilingUuid = e.uuid;
        if (e.kind == hxscene::WALL) walls++;
    }
    out->wallUuidCountOutput = walls;
    if (out->wallUuidCapacityInput == 0) return XR_SUCCESS;
    if (out->wallUuidCapacityInput < walls) return XR_ERROR_SIZE_INSUFFICIENT;
    if (!out->wallUuids) return XR_ERROR_VALIDATION_FAILURE;
    walls = 0;
    for (int i = 0; i < s->entityCount; i++) if (s->entities[i].kind == hxscene::WALL) out->wallUuids[walls++] = s->entities[i].uuid;
    return XR_SUCCESS;
}

/** The whole room as one mesh (XR_META_spatial_entity_mesh), in the mesh entity's space: floor and ceiling fans, two triangles per wall, facing into the room. */
XRAPI_ATTR XrResult XRAPI_CALL GetSpaceTriangleMeshMETA(XrSpace h, const XrSpaceTriangleMeshGetInfoMETA* info, XrSpaceTriangleMeshMETA* out) {
    Space* sp = asSpace(h);
    if (!sp) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_SPACE_TRIANGLE_MESH_GET_INFO_META || !out || out->type != XR_TYPE_SPACE_TRIANGLE_MESH_META) return XR_ERROR_VALIDATION_FAILURE;
    SceneEntity* e = entityOf(sp);
    if (!e || e->kind != hxscene::MESH) return XR_ERROR_SPACE_COMPONENT_NOT_SUPPORTED_FB;
    Session* s = sp->session;
    const hxscene::Room& r = s->room;
    const int n = r.count;
    uint32_t verts = static_cast<uint32_t>(2 * (n + 1) + 4 * n), idx = static_cast<uint32_t>(3 * n * 2 + 6 * n);
    out->vertexCountOutput = verts;
    out->indexCountOutput = idx;
    if (out->vertexCapacityInput == 0 && out->indexCapacityInput == 0) return XR_SUCCESS;
    if (out->vertexCapacityInput < verts || out->indexCapacityInput < idx) return XR_ERROR_SIZE_INSUFFICIENT;
    if (!out->vertices || !out->indices) return XR_ERROR_VALIDATION_FAILURE;
    XrVector3f c = e->pose.position;  // the mesh's origin: middle of the floor
    uint32_t v = 0, k = 0;
    // floor (y = 0) and ceiling fans around the middle
    for (int level = 0; level < 2; level++) {
        float y = level == 0 ? 0 : r.height;
        uint32_t centre = v;
        out->vertices[v++] = {0, y, 0};
        for (int i = 0; i < n; i++) out->vertices[v++] = {r.x[i] - c.x, y, r.z[i] - c.z};
        for (int i = 0; i < n; i++) {
            uint32_t a = centre + 1 + static_cast<uint32_t>(i), b = centre + 1 + static_cast<uint32_t>((i + 1) % n);
            out->indices[k++] = centre;
            // the floor faces up, the ceiling down (the outline's turning direction decides the order)
            bool ccwUp = (r.x[i] - c.x) * (r.z[(i + 1) % n] - c.z) - (r.x[(i + 1) % n] - c.x) * (r.z[i] - c.z) < 0;
            bool up = level == 0;
            if (ccwUp == up) { out->indices[k++] = a; out->indices[k++] = b; } else { out->indices[k++] = b; out->indices[k++] = a; }
        }
    }
    // walls: a quad per edge, facing the middle of the room
    for (int i = 0; i < n; i++) {
        int j = (i + 1) % n;
        uint32_t q = v;
        out->vertices[v++] = {r.x[i] - c.x, 0, r.z[i] - c.z};
        out->vertices[v++] = {r.x[j] - c.x, 0, r.z[j] - c.z};
        out->vertices[v++] = {r.x[j] - c.x, r.height, r.z[j] - c.z};
        out->vertices[v++] = {r.x[i] - c.x, r.height, r.z[i] - c.z};
        // the face normal of (q, q+1, q+2) is up x edge; flip when it points away from the middle
        float ex = r.x[j] - r.x[i], ez = r.z[j] - r.z[i];
        float nx = -ez, nz = ex;  // the normal of (q, q+1, q+2)
        float mx = (r.x[i] + r.x[j]) * 0.5f - c.x, mz = (r.z[i] + r.z[j]) * 0.5f - c.z;
        bool inward = nx * -mx + nz * -mz > 0;
        if (inward) {
            out->indices[k++] = q; out->indices[k++] = q + 1; out->indices[k++] = q + 2;
            out->indices[k++] = q; out->indices[k++] = q + 2; out->indices[k++] = q + 3;
        } else {
            out->indices[k++] = q; out->indices[k++] = q + 2; out->indices[k++] = q + 1;
            out->indices[k++] = q; out->indices[k++] = q + 3; out->indices[k++] = q + 2;
        }
    }
    return XR_SUCCESS;
}

/** "Scan the room": there is no scanner, so the room is read again from HyperMagic. */
XRAPI_ATTR XrResult XRAPI_CALL RequestSceneCaptureFB(XrSession h, const XrSceneCaptureRequestInfoFB* info, XrAsyncRequestIdFB* requestId) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_SCENE_CAPTURE_REQUEST_INFO_FB || !requestId) return XR_ERROR_VALIDATION_FAILURE;
    ensureScene(s);
    buildScene(s);
    XrEventDataSceneCaptureCompleteFB ev{XR_TYPE_EVENT_DATA_SCENE_CAPTURE_COMPLETE_FB};
    ev.requestId = *requestId = newRequest(s->instance);
    ev.result = XR_SUCCESS;
    pushEvent(s->instance, &ev, sizeof(ev));
    return XR_SUCCESS;
}

// ------------------------------------------------------------------ controller models (XR_FB_render_model)
const char* const kRenderModelPaths[2] = {"/model_fb/controller/left", "/model_fb/controller/right"};
constexpr XrRenderModelKeyFB kRenderModelKeyBase = 0x48580001;  // + 0 left, + 1 right

XRAPI_ATTR XrResult XRAPI_CALL EnumerateRenderModelPathsFB(XrSession h, uint32_t capacity, uint32_t* count, XrRenderModelPathInfoFB* paths) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    *count = 2;
    if (capacity == 0) return XR_SUCCESS;
    if (capacity < 2) return XR_ERROR_SIZE_INSUFFICIENT;
    if (!paths) return XR_ERROR_VALIDATION_FAILURE;
    for (int i = 0; i < 2; i++) {
        if (paths[i].type != XR_TYPE_RENDER_MODEL_PATH_INFO_FB) return XR_ERROR_VALIDATION_FAILURE;
        XrResult r = stringToPath(s->instance, kRenderModelPaths[i], &paths[i].path);
        if (r != XR_SUCCESS) return r;
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetRenderModelPropertiesFB(XrSession h, XrPath path, XrRenderModelPropertiesFB* props) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!props || props->type != XR_TYPE_RENDER_MODEL_PROPERTIES_FB) return XR_ERROR_VALIDATION_FAILURE;
    int which = -1;
    for (int i = 0; i < 2; i++) {
        XrPath p = XR_NULL_PATH;
        if (stringToPath(s->instance, kRenderModelPaths[i], &p) == XR_SUCCESS && p == path) which = i;
    }
    if (which < 0) return XR_ERROR_PATH_UNSUPPORTED;
    // the model is plain glTF 2.0 (one mesh, one material): it fits both Meta subsets
    props->vendorId = 0x4858;
    copyStr(props->modelName, XR_MAX_RENDER_MODEL_NAME_SIZE_FB, which == 0 ? "HyperMagic Controller Left" : "HyperMagic Controller Right");
    props->modelKey = kRenderModelKeyBase + static_cast<XrRenderModelKeyFB>(which);
    props->modelVersion = 1;
    props->flags = XR_RENDER_MODEL_SUPPORTS_GLTF_2_0_SUBSET_1_BIT_FB | XR_RENDER_MODEL_SUPPORTS_GLTF_2_0_SUBSET_2_BIT_FB;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL LoadRenderModelFB(XrSession h, const XrRenderModelLoadInfoFB* info, XrRenderModelBufferFB* buffer) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_RENDER_MODEL_LOAD_INFO_FB || !buffer || buffer->type != XR_TYPE_RENDER_MODEL_BUFFER_FB)
        return XR_ERROR_VALIDATION_FAILURE;
    if (info->modelKey != kRenderModelKeyBase && info->modelKey != kRenderModelKeyBase + 1) return XR_ERROR_RENDER_MODEL_KEY_INVALID_FB;
    bool left = info->modelKey == kRenderModelKeyBase;
    size_t size = hxrendermodel::glb(left, nullptr, 0);
    if (size == 0) return XR_ERROR_RUNTIME_FAILURE;
    buffer->bufferCountOutput = static_cast<uint32_t>(size);
    if (buffer->bufferCapacityInput == 0) return XR_SUCCESS;
    if (buffer->bufferCapacityInput < size) return XR_ERROR_SIZE_INSUFFICIENT;
    if (!buffer->buffer) return XR_ERROR_VALIDATION_FAILURE;
    return hxrendermodel::glb(left, buffer->buffer, buffer->bufferCapacityInput) == size ? XR_SUCCESS : XR_ERROR_RUNTIME_FAILURE;
}

// ------------------------------------------------------------------ Pico spatial sensing (XR_BD_spatial_*)
enum FutureKind { FUT_START = 1, FUT_QUERY, FUT_ANCHOR, FUT_PERSIST, FUT_UNPERSIST, FUT_CAPTURE };

struct SenseProvider { uint32_t magic; Session* session; XrSenseDataProviderTypeBD type; XrSenseDataProviderStateBD state; };
struct Snapshot { uint32_t magic; Session* session; XrSenseDataProviderTypeBD type; int count; int16_t entity[MAX_ENTITIES]; };
struct AnchorBD { uint32_t magic; Session* session; int entity; };

SenseProvider* asProvider(XrSenseDataProviderBD h) { return asObj<SenseProvider>(reinterpret_cast<uint64_t>(h), MAGIC_SDP); }
Snapshot* asSnapshot(XrSenseDataSnapshotBD h) { return asObj<Snapshot>(reinterpret_cast<uint64_t>(h), MAGIC_SNAP); }
AnchorBD* asAnchorBD(XrAnchorBD h) { return asObj<AnchorBD>(reinterpret_cast<uint64_t>(h), MAGIC_ANCHOR_BD); }

inline uint64_t fid(XrFutureEXT f) { return static_cast<uint64_t>(reinterpret_cast<uintptr_t>(f)); }

/** Snapshots made by queries, until their completion call hands them out (future id -> snapshot). */
struct PendingSnapshot { uint64_t future; Snapshot* snap; };
PendingSnapshot gPendingSnapshots[64];
pthread_mutex_t gPendingLock = PTHREAD_MUTEX_INITIALIZER;
void pendingSnapshot(Instance*, uint64_t future, Snapshot* snap) {
    pthread_mutex_lock(&gPendingLock);
    PendingSnapshot* slot = &gPendingSnapshots[0];
    for (auto& p : gPendingSnapshots) if (p.future == 0) { slot = &p; break; }
    slot->future = future; slot->snap = snap;
    pthread_mutex_unlock(&gPendingLock);
}
Snapshot* takeSnapshot(uint64_t future) {
    Snapshot* r = nullptr;
    pthread_mutex_lock(&gPendingLock);
    for (auto& p : gPendingSnapshots) if (p.future == future && future != 0) { r = p.snap; p.future = 0; p.snap = nullptr; }
    pthread_mutex_unlock(&gPendingLock);
    return r;
}

/** A finished future holding [result] and a [payload] for its completion call. */
XrFutureEXT newFuture(Instance* inst, int kind, void* owner, XrResult result, int payload) {
    pthread_mutex_lock(&inst->lock);
    Instance::Future* slot = &inst->futures[0];
    for (auto& f : inst->futures) if (f.id == 0) { slot = &f; break; }  // all busy: the oldest slot is reused
    slot->id = ++inst->nextFuture;
    slot->kind = kind; slot->owner = owner; slot->result = result; slot->payload = payload;
    XrFutureEXT id = reinterpret_cast<XrFutureEXT>(static_cast<uintptr_t>(slot->id));
    pthread_mutex_unlock(&inst->lock);
    return id;
}

/** Takes the future [id] of [kind] and [owner] (it can be completed once); null when unknown. */
bool takeFuture(Instance* inst, XrFutureEXT id, int kind, void* owner, Instance::Future* out) {
    bool found = false;
    pthread_mutex_lock(&inst->lock);
    for (auto& f : inst->futures)
        if (fid(id) != 0 && f.id == fid(id) && f.kind == kind && f.owner == owner) { *out = f; f.id = 0; found = true; break; }
    pthread_mutex_unlock(&inst->lock);
    return found;
}

XRAPI_ATTR XrResult XRAPI_CALL PollFutureEXT(XrInstance h, const XrFuturePollInfoEXT* info, XrFuturePollResultEXT* result) {
    Instance* inst = asInstance(h);
    if (!inst) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_FUTURE_POLL_INFO_EXT || !result || result->type != XR_TYPE_FUTURE_POLL_RESULT_EXT) return XR_ERROR_VALIDATION_FAILURE;
    bool known = false;
    pthread_mutex_lock(&inst->lock);
    for (auto& f : inst->futures) if (fid(info->future) != 0 && f.id == fid(info->future)) known = true;
    pthread_mutex_unlock(&inst->lock);
    if (!known) return XR_ERROR_FUTURE_INVALID_EXT;
    result->state = XR_FUTURE_STATE_READY_EXT;  // nothing here takes time: every future is ready at once
    for (auto* n = reinterpret_cast<XrBaseOutStructure*>(result->next); n; n = n->next)
        if (n->type == XR_TYPE_FUTURE_POLL_RESULT_PROGRESS_BD) {
            reinterpret_cast<XrFuturePollResultProgressBD*>(n)->isSupported = XR_TRUE;
            reinterpret_cast<XrFuturePollResultProgressBD*>(n)->progressPercentage = 100;
        }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL CancelFutureEXT(XrInstance h, const XrFutureCancelInfoEXT* info) {
    Instance* inst = asInstance(h);
    if (!inst) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_FUTURE_CANCEL_INFO_EXT) return XR_ERROR_VALIDATION_FAILURE;
    bool found = false;
    pthread_mutex_lock(&inst->lock);
    for (auto& f : inst->futures) if (fid(info->future) != 0 && f.id == fid(info->future)) { f.id = 0; found = true; }
    pthread_mutex_unlock(&inst->lock);
    return found ? XR_SUCCESS : XR_ERROR_FUTURE_INVALID_EXT;
}

void providerEvent(SenseProvider* p, bool updated) {
    XrEventDataSenseDataProviderStateChangedBD st{XR_TYPE_EVENT_DATA_SENSE_DATA_PROVIDER_STATE_CHANGED_BD};
    st.provider = reinterpret_cast<XrSenseDataProviderBD>(p);
    st.newState = p->state;
    pushEvent(p->session->instance, &st, sizeof(st));
    if (!updated) return;
    XrEventDataSenseDataUpdatedBD up{XR_TYPE_EVENT_DATA_SENSE_DATA_UPDATED_BD};
    up.provider = reinterpret_cast<XrSenseDataProviderBD>(p);
    pushEvent(p->session->instance, &up, sizeof(up));
}

XRAPI_ATTR XrResult XRAPI_CALL CreateSenseDataProviderBD(XrSession h, const XrSenseDataProviderCreateInfoBD* ci, XrSenseDataProviderBD* out) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!ci || ci->type != XR_TYPE_SENSE_DATA_PROVIDER_CREATE_INFO_BD || !out) return XR_ERROR_VALIDATION_FAILURE;
    struct { XrSenseDataProviderTypeBD type; const char* ext; } kinds[] = {
        {XR_SENSE_DATA_PROVIDER_TYPE_ANCHOR_BD, XR_BD_SPATIAL_ANCHOR_EXTENSION_NAME}, {XR_SENSE_DATA_PROVIDER_TYPE_SCENE_BD, XR_BD_SPATIAL_SCENE_EXTENSION_NAME},
        {XR_SENSE_DATA_PROVIDER_TYPE_MESH_BD, XR_BD_SPATIAL_MESH_EXTENSION_NAME}, {XR_SENSE_DATA_PROVIDER_TYPE_PLANE_BD, XR_BD_SPATIAL_PLANE_EXTENSION_NAME}};
    bool ok = false;
    for (auto& k : kinds) if (k.type == ci->providerType && extEnabled(s->instance, k.ext)) ok = true;
    if (!ok) return XR_ERROR_VALIDATION_FAILURE;
    SenseProvider* p = alloc<SenseProvider>(MAGIC_SDP, s);
    if (!p) return XR_ERROR_OUT_OF_MEMORY;
    p->session = s; p->type = ci->providerType; p->state = XR_SENSE_DATA_PROVIDER_STATE_INITIALIZED_BD;
    *out = reinterpret_cast<XrSenseDataProviderBD>(p);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL StartSenseDataProviderAsyncBD(XrSenseDataProviderBD h, const XrSenseDataProviderStartInfoBD* info, XrFutureEXT* future) {
    SenseProvider* p = asProvider(h);
    if (!p) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_SENSE_DATA_PROVIDER_START_INFO_BD || !future) return XR_ERROR_VALIDATION_FAILURE;
    *future = newFuture(p->session->instance, FUT_START, p->session, XR_SUCCESS, 0);
    ensureScene(p->session);
    p->state = XR_SENSE_DATA_PROVIDER_STATE_RUNNING_BD;
    providerEvent(p, true);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL StartSenseDataProviderCompleteBD(XrSession h, XrFutureEXT future, XrFutureCompletionEXT* completion) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!completion || completion->type != XR_TYPE_FUTURE_COMPLETION_EXT) return XR_ERROR_VALIDATION_FAILURE;
    Instance::Future f;
    if (!takeFuture(s->instance, future, FUT_START, s, &f)) return XR_ERROR_FUTURE_INVALID_EXT;
    completion->futureResult = f.result;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetSenseDataProviderStateBD(XrSenseDataProviderBD h, XrSenseDataProviderStateBD* state) {
    SenseProvider* p = asProvider(h);
    if (!p) return XR_ERROR_HANDLE_INVALID;
    if (!state) return XR_ERROR_VALIDATION_FAILURE;
    *state = p->state;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL StopSenseDataProviderBD(XrSenseDataProviderBD h) {
    SenseProvider* p = asProvider(h);
    if (!p) return XR_ERROR_HANDLE_INVALID;
    p->state = XR_SENSE_DATA_PROVIDER_STATE_STOPPED_BD;
    providerEvent(p, false);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL DestroySenseDataProviderBD(XrSenseDataProviderBD h) {
    SenseProvider* p = asProvider(h);
    if (!p) return XR_ERROR_HANDLE_INVALID;
    release(p);
    return XR_SUCCESS;
}

/** Whether an entity belongs to what a provider of [type] senses (anchors, or the room's planes). */
bool sensedBy(const SceneEntity& e, XrSenseDataProviderTypeBD type) {
    if (e.kind == DEAD || e.kind == hxscene::ROOM) return false;
    return type == XR_SENSE_DATA_PROVIDER_TYPE_ANCHOR_BD ? e.kind == hxscene::ANCHOR : e.kind != hxscene::ANCHOR;
}

XrSemanticLabelBD labelBD(int kind) {
    return kind == hxscene::FLOOR ? XR_SEMANTIC_LABEL_FLOOR_BD : kind == hxscene::CEILING ? XR_SEMANTIC_LABEL_CEILING_BD :
           kind == hxscene::WALL ? XR_SEMANTIC_LABEL_WALL_BD : XR_SEMANTIC_LABEL_UNKNOWN_BD;
}

/** Whether entity [e] passes the filters chained on a query. */
bool passesBD(const SceneEntity& e, const void* chain) {
    for (auto* n = static_cast<const XrBaseInStructure*>(chain); n; n = n->next) {
        if (n->type == XR_TYPE_SENSE_DATA_FILTER_UUID_BD) {
            auto* f = reinterpret_cast<const XrSenseDataFilterUuidBD*>(n);
            bool any = false;
            for (uint32_t i = 0; i < f->uuidCount; i++) if (memcmp(f->uuids[i].data, e.uuid.data, 16) == 0) any = true;
            if (!any) return false;
        } else if (n->type == XR_TYPE_SENSE_DATA_FILTER_SEMANTIC_BD) {
            auto* f = reinterpret_cast<const XrSenseDataFilterSemanticBD*>(n);
            bool any = false;
            for (uint32_t i = 0; i < f->labelCount; i++) if (f->labels[i] == labelBD(e.kind)) any = true;
            if (!any) return false;
        } else if (n->type == XR_TYPE_SENSE_DATA_FILTER_PLANE_ORIENTATION_BD) {
            auto* f = reinterpret_cast<const XrSenseDataFilterPlaneOrientationBD*>(n);
            XrPlaneOrientationBD o = e.kind == hxscene::FLOOR ? XR_PLANE_ORIENTATION_HORIZONTAL_UPWARD_BD :
                                     e.kind == hxscene::CEILING ? XR_PLANE_ORIENTATION_HORIZONTAL_DOWNWARD_BD : XR_PLANE_ORIENTATION_VERTICAL_BD;
            bool any = false;
            for (uint32_t i = 0; i < f->orientationCount; i++) if (f->orientations[i] == o) any = true;
            if (!any) return false;
        }
    }
    return true;
}

XRAPI_ATTR XrResult XRAPI_CALL QuerySenseDataAsyncBD(XrSenseDataProviderBD h, const XrSenseDataQueryInfoBD* info, XrFutureEXT* future) {
    SenseProvider* p = asProvider(h);
    if (!p) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_SENSE_DATA_QUERY_INFO_BD || !future) return XR_ERROR_VALIDATION_FAILURE;
    Session* s = p->session;
    ensureScene(s);
    Snapshot* snap = alloc<Snapshot>(MAGIC_SNAP, s);
    if (!snap) return XR_ERROR_OUT_OF_MEMORY;
    snap->session = s; snap->type = p->type;
    for (int i = 0; i < s->entityCount; i++)
        if (sensedBy(s->entities[i], p->type) && passesBD(s->entities[i], info->next))
            snap->entity[snap->count++] = static_cast<int16_t>(i);
    *future = newFuture(s->instance, FUT_QUERY, p, XR_SUCCESS, 0);
    pendingSnapshot(s->instance, fid(*future), snap);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL QuerySenseDataCompleteBD(XrSenseDataProviderBD h, XrFutureEXT future, XrSenseDataQueryCompletionBD* completion) {
    SenseProvider* p = asProvider(h);
    if (!p) return XR_ERROR_HANDLE_INVALID;
    if (!completion || completion->type != XR_TYPE_SENSE_DATA_QUERY_COMPLETION_BD) return XR_ERROR_VALIDATION_FAILURE;
    Instance::Future f;
    if (!takeFuture(p->session->instance, future, FUT_QUERY, p, &f)) return XR_ERROR_FUTURE_INVALID_EXT;
    Snapshot* snap = takeSnapshot(fid(future));
    completion->futureResult = snap ? XR_SUCCESS : XR_ERROR_RUNTIME_FAILURE;
    completion->snapshot = reinterpret_cast<XrSenseDataSnapshotBD>(snap);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL DestroySenseDataSnapshotBD(XrSenseDataSnapshotBD h) {
    Snapshot* snap = asSnapshot(h);
    if (!snap) return XR_ERROR_HANDLE_INVALID;
    release(snap);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetQueriedSenseDataBD(XrSenseDataSnapshotBD h, XrQueriedSenseDataGetInfoBD* info, XrQueriedSenseDataBD* data) {
    Snapshot* snap = asSnapshot(h);
    if (!snap) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_QUERIED_SENSE_DATA_GET_INFO_BD || !data || data->type != XR_TYPE_QUERIED_SENSE_DATA_BD) return XR_ERROR_VALIDATION_FAILURE;
    data->stateCountOutput = static_cast<uint32_t>(snap->count);
    if (data->stateCapacityInput == 0) return XR_SUCCESS;
    if (data->stateCapacityInput < static_cast<uint32_t>(snap->count)) return XR_ERROR_SIZE_INSUFFICIENT;
    if (!data->states) return XR_ERROR_VALIDATION_FAILURE;
    for (int i = 0; i < snap->count; i++) {
        XrSpatialEntityStateBD& st = data->states[i];
        st.entityId = static_cast<XrSpatialEntityIdBD>(snap->entity[i] + 1);
        st.lastUpdateTime = hxp::nowNs();
        st.uuid = snap->session->entities[snap->entity[i]].uuid;
    }
    return XR_SUCCESS;
}

/** The entity [id] of a snapshot, or null. */
SceneEntity* snapEntity(Snapshot* snap, XrSpatialEntityIdBD id) {
    for (int i = 0; i < snap->count; i++) if (static_cast<XrSpatialEntityIdBD>(snap->entity[i] + 1) == id) return &snap->session->entities[snap->entity[i]];
    return nullptr;
}

/** Components a snapshot's entity has (bit per XrSpatialEntityComponentTypeBD, plane orientation as bit 6). */
uint32_t componentsBD(const SceneEntity& e, XrSenseDataProviderTypeBD type) {
    constexpr uint32_t LOC = 1u << XR_SPATIAL_ENTITY_COMPONENT_TYPE_LOCATION_BD, SEM = 1u << XR_SPATIAL_ENTITY_COMPONENT_TYPE_SEMANTIC_BD,
                       BOX = 1u << XR_SPATIAL_ENTITY_COMPONENT_TYPE_BOUNDING_BOX_2D_BD, POLY = 1u << XR_SPATIAL_ENTITY_COMPONENT_TYPE_POLYGON_BD,
                       MESH = 1u << XR_SPATIAL_ENTITY_COMPONENT_TYPE_TRIANGLE_MESH_BD, ORIENT = 1u << 6;
    if (e.kind == hxscene::ANCHOR) return LOC;
    switch (type) {
        case XR_SENSE_DATA_PROVIDER_TYPE_SCENE_BD: return LOC | SEM | BOX | POLY;
        case XR_SENSE_DATA_PROVIDER_TYPE_PLANE_BD: return LOC | SEM | BOX | POLY | ORIENT;
        case XR_SENSE_DATA_PROVIDER_TYPE_MESH_BD: return LOC | SEM | MESH;
        default: return LOC;
    }
}

uint32_t componentBit(XrSpatialEntityComponentTypeBD c) {
    if (c == XR_SPATIAL_ENTITY_COMPONENT_TYPE_PLANE_ORIENTATION_BD) return 1u << 6;
    return c <= XR_SPATIAL_ENTITY_COMPONENT_TYPE_TRIANGLE_MESH_BD ? 1u << c : 0;
}

XRAPI_ATTR XrResult XRAPI_CALL EnumerateSpatialEntityComponentTypesBD(XrSenseDataSnapshotBD h, XrSpatialEntityIdBD id, uint32_t capacity, uint32_t* count, XrSpatialEntityComponentTypeBD* types) {
    Snapshot* snap = asSnapshot(h);
    if (!snap) return XR_ERROR_HANDLE_INVALID;
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    SceneEntity* e = snapEntity(snap, id);
    if (!e) return XR_ERROR_SPATIAL_ENTITY_ID_INVALID_BD;
    static const XrSpatialEntityComponentTypeBD all[] = {
        XR_SPATIAL_ENTITY_COMPONENT_TYPE_LOCATION_BD, XR_SPATIAL_ENTITY_COMPONENT_TYPE_SEMANTIC_BD, XR_SPATIAL_ENTITY_COMPONENT_TYPE_BOUNDING_BOX_2D_BD,
        XR_SPATIAL_ENTITY_COMPONENT_TYPE_POLYGON_BD, XR_SPATIAL_ENTITY_COMPONENT_TYPE_TRIANGLE_MESH_BD, XR_SPATIAL_ENTITY_COMPONENT_TYPE_PLANE_ORIENTATION_BD};
    uint32_t mask = componentsBD(*e, snap->type), n = 0;
    for (auto c : all) if (mask & componentBit(c)) n++;
    *count = n;
    if (capacity == 0) return XR_SUCCESS;
    if (capacity < n) return XR_ERROR_SIZE_INSUFFICIENT;
    if (!types) return XR_ERROR_VALIDATION_FAILURE;
    n = 0;
    for (auto c : all) if (mask & componentBit(c)) types[n++] = c;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetSpatialEntityUuidBD(XrSenseDataSnapshotBD h, XrSpatialEntityIdBD id, XrUuidEXT* uuid) {
    Snapshot* snap = asSnapshot(h);
    if (!snap) return XR_ERROR_HANDLE_INVALID;
    if (!uuid) return XR_ERROR_VALIDATION_FAILURE;
    SceneEntity* e = snapEntity(snap, id);
    if (!e) return XR_ERROR_SPATIAL_ENTITY_ID_INVALID_BD;
    *uuid = e->uuid;
    return XR_SUCCESS;
}

/** A plane's outline in its own XY plane: the room outline (floor, ceiling) or the wall's rectangle. */
int planeOutline(Session* s, const SceneEntity& e, XrVector2f* out) {
    if (e.kind == hxscene::FLOOR || e.kind == hxscene::CEILING) {
        const XrVector2f* v = e.kind == hxscene::FLOOR ? s->floorOutline : s->ceilingOutline;
        for (int i = 0; i < s->room.count; i++) out[i] = v[i];
        return s->room.count;
    }
    out[0] = {e.minX, e.minY}; out[1] = {e.maxX, e.minY}; out[2] = {e.maxX, e.maxY}; out[3] = {e.minX, e.maxY};
    return 4;
}

XRAPI_ATTR XrResult XRAPI_CALL GetSpatialEntityComponentDataBD(XrSenseDataSnapshotBD h, const XrSpatialEntityComponentGetInfoBD* info, XrSpatialEntityComponentDataBaseHeaderBD* data) {
    Snapshot* snap = asSnapshot(h);
    if (!snap) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_SPATIAL_ENTITY_COMPONENT_GET_INFO_BD || !data) return XR_ERROR_VALIDATION_FAILURE;
    SceneEntity* e = snapEntity(snap, info->entityId);
    if (!e) return XR_ERROR_SPATIAL_ENTITY_ID_INVALID_BD;
    if (!(componentsBD(*e, snap->type) & componentBit(info->componentType))) return XR_ERROR_VALIDATION_FAILURE;
    Session* s = snap->session;
    switch (info->componentType) {
        case XR_SPATIAL_ENTITY_COMPONENT_TYPE_LOCATION_BD: {
            if (data->type != XR_TYPE_SPATIAL_ENTITY_COMPONENT_DATA_LOCATION_BD) return XR_ERROR_VALIDATION_FAILURE;
            const XrSpatialEntityLocationGetInfoBD* where = nullptr;
            for (auto* n = static_cast<const XrBaseInStructure*>(info->next); n; n = n->next)
                if (n->type == XR_TYPE_SPATIAL_ENTITY_LOCATION_GET_INFO_BD) where = reinterpret_cast<const XrSpatialEntityLocationGetInfoBD*>(n);
            Space* base = where ? asSpace(where->baseSpace) : nullptr;
            if (!base) return XR_ERROR_VALIDATION_FAILURE;
            auto* d = reinterpret_cast<XrSpatialEntityComponentDataLocationBD*>(data);
            XrPosef b; bool tb;
            if (!spaceInStage(base, hxp::nowNs(), &b, &tb)) { d->location.locationFlags = 0; return XR_SUCCESS; }
            d->location.pose = hx::compose(hx::inverse(b), e->pose);
            d->location.locationFlags = XR_SPACE_LOCATION_ORIENTATION_VALID_BIT | XR_SPACE_LOCATION_POSITION_VALID_BIT |
                                        XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT | XR_SPACE_LOCATION_POSITION_TRACKED_BIT;
            return XR_SUCCESS;
        }
        case XR_SPATIAL_ENTITY_COMPONENT_TYPE_SEMANTIC_BD: {
            if (data->type != XR_TYPE_SPATIAL_ENTITY_COMPONENT_DATA_SEMANTIC_BD) return XR_ERROR_VALIDATION_FAILURE;
            auto* d = reinterpret_cast<XrSpatialEntityComponentDataSemanticBD*>(data);
            d->labelCountOutput = 1;
            if (d->labelCapacityInput == 0) return XR_SUCCESS;
            if (!d->labels) return XR_ERROR_VALIDATION_FAILURE;
            d->labels[0] = labelBD(e->kind);
            return XR_SUCCESS;
        }
        case XR_SPATIAL_ENTITY_COMPONENT_TYPE_BOUNDING_BOX_2D_BD: {
            if (data->type != XR_TYPE_SPATIAL_ENTITY_COMPONENT_DATA_BOUNDING_BOX_2D_BD) return XR_ERROR_VALIDATION_FAILURE;
            auto* d = reinterpret_cast<XrSpatialEntityComponentDataBoundingBox2DBD*>(data);
            d->boundingBox2D = {{e->minX, e->minY}, {e->maxX - e->minX, e->maxY - e->minY}};
            return XR_SUCCESS;
        }
        case XR_SPATIAL_ENTITY_COMPONENT_TYPE_POLYGON_BD: {
            if (data->type != XR_TYPE_SPATIAL_ENTITY_COMPONENT_DATA_POLYGON_BD) return XR_ERROR_VALIDATION_FAILURE;
            auto* d = reinterpret_cast<XrSpatialEntityComponentDataPolygonBD*>(data);
            XrVector2f v[hxscene::MAX_POLY];
            uint32_t n = static_cast<uint32_t>(planeOutline(s, *e, v));
            d->vertexCountOutput = n;
            if (d->vertexCapacityInput == 0) return XR_SUCCESS;
            if (d->vertexCapacityInput < n) return XR_ERROR_SIZE_INSUFFICIENT;
            if (!d->vertices) return XR_ERROR_VALIDATION_FAILURE;
            memcpy(d->vertices, v, sizeof(XrVector2f) * n);
            return XR_SUCCESS;
        }
        case XR_SPATIAL_ENTITY_COMPONENT_TYPE_TRIANGLE_MESH_BD: {
            if (data->type != XR_TYPE_SPATIAL_ENTITY_COMPONENT_DATA_TRIANGLE_MESH_BD) return XR_ERROR_VALIDATION_FAILURE;
            auto* d = reinterpret_cast<XrSpatialEntityComponentDataTriangleMeshBD*>(data);
            // the plane as a triangle fan around its middle, in its own space (z = 0)
            XrVector2f v[hxscene::MAX_POLY];
            uint32_t n = static_cast<uint32_t>(planeOutline(s, *e, v));
            d->vertexCountOutput = n + 1;
            d->indexCountOutput = n * 3;
            if (d->vertexCapacityInput == 0 && d->indexCapacityInput == 0) return XR_SUCCESS;
            if (d->vertexCapacityInput < n + 1 || d->indexCapacityInput < n * 3) return XR_ERROR_SIZE_INSUFFICIENT;
            if (!d->vertices || !d->indices) return XR_ERROR_VALIDATION_FAILURE;
            float cx = 0, cy = 0;
            for (uint32_t i = 0; i < n; i++) { cx += v[i].x; cy += v[i].y; }
            d->vertices[0] = {cx / n, cy / n, 0};
            for (uint32_t i = 0; i < n; i++) d->vertices[i + 1] = {v[i].x, v[i].y, 0};
            for (uint32_t i = 0; i < n; i++) {
                d->indices[i * 3] = 0;
                d->indices[i * 3 + 1] = static_cast<uint16_t>(1 + i);
                d->indices[i * 3 + 2] = static_cast<uint16_t>(1 + (i + 1) % n);
            }
            return XR_SUCCESS;
        }
        case XR_SPATIAL_ENTITY_COMPONENT_TYPE_PLANE_ORIENTATION_BD: {
            if (data->type != XR_TYPE_SPATIAL_ENTITY_COMPONENT_DATA_PLANE_ORIENTATION_BD) return XR_ERROR_VALIDATION_FAILURE;
            reinterpret_cast<XrSpatialEntityComponentDataPlaneOrientationBD*>(data)->orientation =
                e->kind == hxscene::FLOOR ? XR_PLANE_ORIENTATION_HORIZONTAL_UPWARD_BD :
                e->kind == hxscene::CEILING ? XR_PLANE_ORIENTATION_HORIZONTAL_DOWNWARD_BD : XR_PLANE_ORIENTATION_VERTICAL_BD;
            return XR_SUCCESS;
        }
        default: return XR_ERROR_VALIDATION_FAILURE;
    }
}

AnchorBD* newAnchorBD(Session* s, int entity) {
    AnchorBD* a = alloc<AnchorBD>(MAGIC_ANCHOR_BD, s);
    if (a) { a->session = s; a->entity = entity; }
    return a;
}

XRAPI_ATTR XrResult XRAPI_CALL CreateSpatialEntityAnchorBD(XrSenseDataProviderBD h, const XrSpatialEntityAnchorCreateInfoBD* ci, XrAnchorBD* out) {
    SenseProvider* p = asProvider(h);
    if (!p) return XR_ERROR_HANDLE_INVALID;
    if (!ci || ci->type != XR_TYPE_SPATIAL_ENTITY_ANCHOR_CREATE_INFO_BD || !out) return XR_ERROR_VALIDATION_FAILURE;
    Snapshot* snap = asSnapshot(ci->snapshot);
    if (!snap) return XR_ERROR_HANDLE_INVALID;
    SceneEntity* e = snapEntity(snap, ci->entityId);
    if (!e) return XR_ERROR_SPATIAL_ENTITY_ID_INVALID_BD;
    AnchorBD* a = newAnchorBD(p->session, static_cast<int>(e - p->session->entities));
    if (!a) return XR_ERROR_OUT_OF_MEMORY;
    *out = reinterpret_cast<XrAnchorBD>(a);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL DestroyAnchorBD(XrAnchorBD h) {
    AnchorBD* a = asAnchorBD(h);
    if (!a) return XR_ERROR_HANDLE_INVALID;
    release(a);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetAnchorUuidBD(XrAnchorBD h, XrUuidEXT* uuid) {
    AnchorBD* a = asAnchorBD(h);
    if (!a) return XR_ERROR_HANDLE_INVALID;
    if (!uuid) return XR_ERROR_VALIDATION_FAILURE;
    *uuid = a->session->entities[a->entity].uuid;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL CreateAnchorSpaceBD(XrSession h, const XrAnchorSpaceCreateInfoBD* ci, XrSpace* out) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!ci || ci->type != XR_TYPE_ANCHOR_SPACE_CREATE_INFO_BD || !out) return XR_ERROR_VALIDATION_FAILURE;
    AnchorBD* a = asAnchorBD(ci->anchor);
    if (!a || a->session != s) return XR_ERROR_HANDLE_INVALID;
    if (!hx::poseValid(ci->poseInAnchorSpace)) return XR_ERROR_POSE_INVALID;
    XrSpace sp = entitySpace(s, a->entity);
    if (sp == XR_NULL_HANDLE) return XR_ERROR_OUT_OF_MEMORY;
    asSpace(sp)->offset = ci->poseInAnchorSpace;
    *out = sp;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL CreateSpatialAnchorAsyncBD(XrSenseDataProviderBD h, const XrSpatialAnchorCreateInfoBD* info, XrFutureEXT* future) {
    SenseProvider* p = asProvider(h);
    if (!p) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_SPATIAL_ANCHOR_CREATE_INFO_BD || !future) return XR_ERROR_VALIDATION_FAILURE;
    if (p->type != XR_SENSE_DATA_PROVIDER_TYPE_ANCHOR_BD) return XR_ERROR_VALIDATION_FAILURE;
    Space* base = asSpace(info->space);
    if (!base || base->session != p->session) return XR_ERROR_HANDLE_INVALID;
    if (!hx::poseValid(info->pose)) return XR_ERROR_POSE_INVALID;
    Session* s = p->session;
    ensureScene(s);
    XrPosef b; bool tb;
    XrResult result = XR_SUCCESS;
    int entity = -1;
    if (!spaceInStage(base, info->time > 0 ? info->time : hxp::nowNs(), &b, &tb)) result = XR_ERROR_SPATIAL_SENSING_SERVICE_UNAVAILABLE_BD;
    else {
        XrUuidEXT u;
        hxp::randomBytes(u.data, 16);
        u.data[6] = static_cast<uint8_t>((u.data[6] & 0x0f) | 0x40);
        u.data[8] = static_cast<uint8_t>((u.data[8] & 0x3f) | 0x80);
        SceneEntity* e = addEntity(s, hxscene::ANCHOR, u, hx::compose(b, info->pose));
        if (!e) result = XR_ERROR_LIMIT_REACHED; else entity = static_cast<int>(e - s->entities);
    }
    *future = newFuture(s->instance, FUT_ANCHOR, p, result, entity);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL CreateSpatialAnchorCompleteBD(XrSenseDataProviderBD h, XrFutureEXT future, XrSpatialAnchorCreateCompletionBD* completion) {
    SenseProvider* p = asProvider(h);
    if (!p) return XR_ERROR_HANDLE_INVALID;
    if (!completion || completion->type != XR_TYPE_SPATIAL_ANCHOR_CREATE_COMPLETION_BD) return XR_ERROR_VALIDATION_FAILURE;
    Instance::Future f;
    if (!takeFuture(p->session->instance, future, FUT_ANCHOR, p, &f)) return XR_ERROR_FUTURE_INVALID_EXT;
    completion->futureResult = f.result;
    completion->anchor = XR_NULL_HANDLE;
    if (f.result == XR_SUCCESS) {
        AnchorBD* a = newAnchorBD(p->session, f.payload);
        if (!a) { completion->futureResult = XR_ERROR_OUT_OF_MEMORY; return XR_SUCCESS; }
        completion->anchor = reinterpret_cast<XrAnchorBD>(a);
        completion->uuid = p->session->entities[f.payload].uuid;
    }
    return XR_SUCCESS;
}

XrResult persistBD(XrSenseDataProviderBD h, XrAnchorBD anchor, XrPersistenceLocationBD location, bool store, int kind, XrFutureEXT* future) {
    SenseProvider* p = asProvider(h);
    if (!p) return XR_ERROR_HANDLE_INVALID;
    AnchorBD* a = asAnchorBD(anchor);
    if (!a || a->session != p->session) return XR_ERROR_HANDLE_INVALID;
    if (location != XR_PERSISTENCE_LOCATION_LOCAL_BD) return XR_ERROR_VALIDATION_FAILURE;
    SceneEntity& e = p->session->entities[a->entity];
    XrResult result = XR_SUCCESS;
    if (e.kind != hxscene::ANCHOR) result = XR_ERROR_ANCHOR_NOT_SUPPORTED_FOR_ENTITY_BD;  // the room is HyperMagic's, not the app's
    else { e.stored = store; persistAnchors(p->session); }
    *future = newFuture(p->session->instance, kind, p, result, 0);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL PersistSpatialAnchorAsyncBD(XrSenseDataProviderBD h, const XrSpatialAnchorPersistInfoBD* info, XrFutureEXT* future) {
    if (!info || info->type != XR_TYPE_SPATIAL_ANCHOR_PERSIST_INFO_BD || !future) return asProvider(h) ? XR_ERROR_VALIDATION_FAILURE : XR_ERROR_HANDLE_INVALID;
    return persistBD(h, info->anchor, info->location, true, FUT_PERSIST, future);
}

XRAPI_ATTR XrResult XRAPI_CALL UnpersistSpatialAnchorAsyncBD(XrSenseDataProviderBD h, const XrSpatialAnchorUnpersistInfoBD* info, XrFutureEXT* future) {
    if (!info || info->type != XR_TYPE_SPATIAL_ANCHOR_UNPERSIST_INFO_BD || !future) return asProvider(h) ? XR_ERROR_VALIDATION_FAILURE : XR_ERROR_HANDLE_INVALID;
    return persistBD(h, info->anchor, info->location, false, FUT_UNPERSIST, future);
}

XrResult completeSimple(XrSenseDataProviderBD h, XrFutureEXT future, int kind, XrFutureCompletionEXT* completion) {
    SenseProvider* p = asProvider(h);
    if (!p) return XR_ERROR_HANDLE_INVALID;
    if (!completion || completion->type != XR_TYPE_FUTURE_COMPLETION_EXT) return XR_ERROR_VALIDATION_FAILURE;
    Instance::Future f;
    if (!takeFuture(p->session->instance, future, kind, p, &f)) return XR_ERROR_FUTURE_INVALID_EXT;
    completion->futureResult = f.result;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL PersistSpatialAnchorCompleteBD(XrSenseDataProviderBD h, XrFutureEXT future, XrFutureCompletionEXT* c) { return completeSimple(h, future, FUT_PERSIST, c); }
XRAPI_ATTR XrResult XRAPI_CALL UnpersistSpatialAnchorCompleteBD(XrSenseDataProviderBD h, XrFutureEXT future, XrFutureCompletionEXT* c) { return completeSimple(h, future, FUT_UNPERSIST, c); }

/** "Scan the room" (Pico): there is no scanner; the room is read again from HyperMagic. */
XRAPI_ATTR XrResult XRAPI_CALL CaptureSceneAsyncBD(XrSenseDataProviderBD h, const XrSceneCaptureInfoBD* info, XrFutureEXT* future) {
    SenseProvider* p = asProvider(h);
    if (!p) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_SCENE_CAPTURE_INFO_BD || !future) return XR_ERROR_VALIDATION_FAILURE;
    ensureScene(p->session);
    buildScene(p->session);
    *future = newFuture(p->session->instance, FUT_CAPTURE, p, XR_SUCCESS, 0);
    XrEventDataSenseDataUpdatedBD up{XR_TYPE_EVENT_DATA_SENSE_DATA_UPDATED_BD};
    up.provider = h;
    pushEvent(p->session->instance, &up, sizeof(up));
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL CaptureSceneCompleteBD(XrSenseDataProviderBD h, XrFutureEXT future, XrFutureCompletionEXT* c) { return completeSimple(h, future, FUT_CAPTURE, c); }

// ------------------------------------------------------------------ bodies (Meta and Pico)
/** A body tracker: [joints] is how many joints the app asked for (70 / 84 Meta, 16 / 24 Pico). */
struct BodyTracker { uint32_t magic; Session* session; int joints; bool pico; };

BodyTracker* asBody(uint64_t h) { return asObj<BodyTracker>(h, MAGIC_BODY); }

/** The inferred body at [time], in stage space (hands from HyperMagic when the camera sees them). */
bool bodyAt(Session* s, XrTime time, hxbody::Output* out) {
    hxbody::Input in{};
    if (!hxp::headPose(time, s->instance->cfg, &in.head)) return false;
    in.standingEyeHeight = s->instance->cfg.eyeHeightM;
    HxHandShared hands;
    bool haveHands = hxp::readHands(&hands, true);
    for (int side = 0; side < 2; side++) {
        hxhands::Joint j[XR_HAND_JOINT_COUNT_EXT];
        XrPosef camera;
        in.handTracked[side] = haveHands && handInStage(s->instance->cfg, hands, side, j, &camera);
        if (in.handTracked[side]) for (int k = 0; k < XR_HAND_JOINT_COUNT_EXT; k++) in.hand[side][k] = jointPose(camera, j[k]);
    }
    hxbody::solve(in, out);
    return true;
}

XRAPI_ATTR XrResult XRAPI_CALL CreateBodyTrackerFB(XrSession h, const XrBodyTrackerCreateInfoFB* ci, XrBodyTrackerFB* out) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!ci || ci->type != XR_TYPE_BODY_TRACKER_CREATE_INFO_FB || !out) return XR_ERROR_VALIDATION_FAILURE;
    int joints;
    if (ci->bodyJointSet == XR_BODY_JOINT_SET_DEFAULT_FB) joints = hxbody::kFbJoints;
    else if (ci->bodyJointSet == XR_BODY_JOINT_SET_FULL_BODY_META && extEnabled(s->instance, XR_META_BODY_TRACKING_FULL_BODY_EXTENSION_NAME)) joints = hxbody::kMetaJoints;
    else return XR_ERROR_VALIDATION_FAILURE;
    BodyTracker* b = alloc<BodyTracker>(MAGIC_BODY, s);
    if (!b) return XR_ERROR_OUT_OF_MEMORY;
    b->session = s; b->joints = joints; b->pico = false;
    *out = reinterpret_cast<XrBodyTrackerFB>(b);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL DestroyBodyTrackerFB(XrBodyTrackerFB h) {
    BodyTracker* b = asBody(reinterpret_cast<uint64_t>(h));
    if (!b || b->pico) return XR_ERROR_HANDLE_INVALID;
    release(b);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL LocateBodyJointsFB(XrBodyTrackerFB h, const XrBodyJointsLocateInfoFB* info, XrBodyJointLocationsFB* locs) {
    BodyTracker* b = asBody(reinterpret_cast<uint64_t>(h));
    if (!b || b->pico) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_BODY_JOINTS_LOCATE_INFO_FB || !locs || locs->type != XR_TYPE_BODY_JOINT_LOCATIONS_FB) return XR_ERROR_VALIDATION_FAILURE;
    if (info->time <= 0) return XR_ERROR_TIME_INVALID;
    if (locs->jointCount != static_cast<uint32_t>(b->joints) || !locs->jointLocations) return XR_ERROR_VALIDATION_FAILURE;
    Space* base = asSpace(info->baseSpace);
    if (!base) return XR_ERROR_HANDLE_INVALID;
    locs->isActive = XR_FALSE;
    locs->confidence = 0;
    locs->skeletonChangedCount = 0;
    locs->time = info->time;
    for (uint32_t i = 0; i < locs->jointCount; i++) { locs->jointLocations[i].locationFlags = 0; locs->jointLocations[i].pose = hx::identity(); }
    hxbody::Output body;
    XrPosef basePose; bool bt;
    if (!bodyAt(b->session, info->time, &body) || !spaceInStage(base, info->time, &basePose, &bt)) return XR_SUCCESS;
    XrPosef inv = hx::inverse(basePose);
    for (int i = 0; i < b->joints; i++) {
        locs->jointLocations[i].pose = hx::compose(inv, body.pose[i]);
        locs->jointLocations[i].locationFlags = XR_SPACE_LOCATION_ORIENTATION_VALID_BIT | XR_SPACE_LOCATION_POSITION_VALID_BIT |
            (body.tracked[i] ? XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT | XR_SPACE_LOCATION_POSITION_TRACKED_BIT : 0);
    }
    locs->isActive = XR_TRUE;
    locs->confidence = 0.5f;  // half of it is inferred
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetBodySkeletonFB(XrBodyTrackerFB h, XrBodySkeletonFB* skeleton) {
    BodyTracker* b = asBody(reinterpret_cast<uint64_t>(h));
    if (!b || b->pico) return XR_ERROR_HANDLE_INVALID;
    if (!skeleton || skeleton->type != XR_TYPE_BODY_SKELETON_FB) return XR_ERROR_VALIDATION_FAILURE;
    if (skeleton->jointCount != static_cast<uint32_t>(b->joints) || !skeleton->joints) return XR_ERROR_VALIDATION_FAILURE;
    // the reference pose: standing straight, arms hanging, at the user's height, facing -Z
    hxbody::Input in{};
    in.head = XrPosef{{0, 0, 0, 1}, {0, b->session->instance->cfg.eyeHeightM, 0}};
    in.standingEyeHeight = b->session->instance->cfg.eyeHeightM;
    hxbody::Output body;
    hxbody::solve(in, &body);
    for (int i = 0; i < b->joints; i++) {
        skeleton->joints[i].joint = i;
        skeleton->joints[i].parentJoint = hxbody::parentMeta(i);
        skeleton->joints[i].pose = body.pose[i];
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL CreateBodyTrackerBD(XrSession h, const XrBodyTrackerCreateInfoBD* ci, XrBodyTrackerBD* out) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!ci || ci->type != XR_TYPE_BODY_TRACKER_CREATE_INFO_BD || !out) return XR_ERROR_VALIDATION_FAILURE;
    int joints;
    if (ci->jointSet == XR_BODY_JOINT_SET_BODY_WITHOUT_ARM_BD) joints = 16;
    else if (ci->jointSet == XR_BODY_JOINT_SET_FULL_BODY_JOINTS_BD) joints = hxbody::kBdJoints;
    else return XR_ERROR_VALIDATION_FAILURE;
    BodyTracker* b = alloc<BodyTracker>(MAGIC_BODY, s);
    if (!b) return XR_ERROR_OUT_OF_MEMORY;
    b->session = s; b->joints = joints; b->pico = true;
    *out = reinterpret_cast<XrBodyTrackerBD>(b);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL DestroyBodyTrackerBD(XrBodyTrackerBD h) {
    BodyTracker* b = asBody(reinterpret_cast<uint64_t>(h));
    if (!b || !b->pico) return XR_ERROR_HANDLE_INVALID;
    release(b);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL LocateBodyJointsBD(XrBodyTrackerBD h, const XrBodyJointsLocateInfoBD* info, XrBodyJointLocationsBD* locs) {
    BodyTracker* b = asBody(reinterpret_cast<uint64_t>(h));
    if (!b || !b->pico) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_BODY_JOINTS_LOCATE_INFO_BD || !locs || locs->type != XR_TYPE_BODY_JOINT_LOCATIONS_BD) return XR_ERROR_VALIDATION_FAILURE;
    if (info->time <= 0) return XR_ERROR_TIME_INVALID;
    if (locs->jointLocationCount != static_cast<uint32_t>(b->joints) || !locs->jointLocations) return XR_ERROR_VALIDATION_FAILURE;
    Space* base = asSpace(info->baseSpace);
    if (!base) return XR_ERROR_HANDLE_INVALID;
    locs->allJointPosesTracked = XR_FALSE;
    for (uint32_t i = 0; i < locs->jointLocationCount; i++) { locs->jointLocations[i].locationFlags = 0; locs->jointLocations[i].pose = hx::identity(); }
    hxbody::Output body;
    XrPosef basePose; bool bt;
    if (!bodyAt(b->session, info->time, &body) || !spaceInStage(base, info->time, &basePose, &bt)) return XR_SUCCESS;
    XrPosef joints[hxbody::kBdJoints];
    bool tracked[hxbody::kBdJoints];
    hxbody::toBD(body, joints, tracked);
    XrPosef inv = hx::inverse(basePose);
    for (int i = 0; i < b->joints; i++) {
        locs->jointLocations[i].pose = hx::compose(inv, joints[i]);
        locs->jointLocations[i].locationFlags = XR_SPACE_LOCATION_ORIENTATION_VALID_BIT | XR_SPACE_LOCATION_POSITION_VALID_BIT |
            (tracked[i] ? XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT | XR_SPACE_LOCATION_POSITION_TRACKED_BIT : 0);
    }
    return XR_SUCCESS;
}

// ------------------------------------------------------------------ environment depth (XR_META_environment_depth)
/** The room's depth, drawn for both eyes on every acquire (the walls, floor and ceiling HyperMagic knows). */
struct DepthProvider { uint32_t magic; Session* session; bool running; struct DepthSwapchain* swapchain; uint32_t next; };
struct DepthSwapchain { uint32_t magic; DepthProvider* provider; HxDepthGfx* gfx; uint32_t textures[3]; };
constexpr uint32_t DEPTH_SIZE = 320, DEPTH_IMAGES = 3;
constexpr float DEPTH_NEAR = 0.1f;

void destroyDepthSwapchain(void* p) {
    DepthSwapchain* d = static_cast<DepthSwapchain*>(p);
    hxp::gfxDestroyDepth(d->gfx);
    d->magic = 0;
    free(d);
}

DepthProvider* asDepth(XrEnvironmentDepthProviderMETA h) { return asObj<DepthProvider>(reinterpret_cast<uint64_t>(h), MAGIC_DEPTH); }
DepthSwapchain* asDepthSc(XrEnvironmentDepthSwapchainMETA h) { return asObj<DepthSwapchain>(reinterpret_cast<uint64_t>(h), MAGIC_DEPTH_SC); }

XRAPI_ATTR XrResult XRAPI_CALL CreateEnvironmentDepthProviderMETA(XrSession h, const XrEnvironmentDepthProviderCreateInfoMETA* ci, XrEnvironmentDepthProviderMETA* out) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!ci || ci->type != XR_TYPE_ENVIRONMENT_DEPTH_PROVIDER_CREATE_INFO_META || !out) return XR_ERROR_VALIDATION_FAILURE;
    DepthProvider* d = alloc<DepthProvider>(MAGIC_DEPTH, s);
    if (!d) return XR_ERROR_OUT_OF_MEMORY;
    d->session = s;
    *out = reinterpret_cast<XrEnvironmentDepthProviderMETA>(d);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL DestroyEnvironmentDepthProviderMETA(XrEnvironmentDepthProviderMETA h) {
    DepthProvider* d = asDepth(h);
    if (!d) return XR_ERROR_HANDLE_INVALID;
    while (void* sc = takeChild(d, MAGIC_DEPTH_SC)) destroyDepthSwapchain(sc);
    release(d);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL StartEnvironmentDepthProviderMETA(XrEnvironmentDepthProviderMETA h) {
    DepthProvider* d = asDepth(h);
    if (!d) return XR_ERROR_HANDLE_INVALID;
    if (d->running) return XR_ERROR_CALL_ORDER_INVALID;
    ensureScene(d->session);
    d->running = true;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL StopEnvironmentDepthProviderMETA(XrEnvironmentDepthProviderMETA h) {
    DepthProvider* d = asDepth(h);
    if (!d) return XR_ERROR_HANDLE_INVALID;
    if (!d->running) return XR_ERROR_CALL_ORDER_INVALID;
    d->running = false;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL CreateEnvironmentDepthSwapchainMETA(XrEnvironmentDepthProviderMETA h, const XrEnvironmentDepthSwapchainCreateInfoMETA* ci, XrEnvironmentDepthSwapchainMETA* out) {
    DepthProvider* d = asDepth(h);
    if (!d) return XR_ERROR_HANDLE_INVALID;
    if (!ci || ci->type != XR_TYPE_ENVIRONMENT_DEPTH_SWAPCHAIN_CREATE_INFO_META || !out) return XR_ERROR_VALIDATION_FAILURE;
    if (d->swapchain) return XR_ERROR_LIMIT_REACHED;  // one per provider
    DepthSwapchain* sc = alloc<DepthSwapchain>(MAGIC_DEPTH_SC, d);
    if (!sc) return XR_ERROR_OUT_OF_MEMORY;
    sc->provider = d;
    XrResult r = d->session->gfx || d->session->headless ? hxp::gfxCreateDepth(d->session->gfx, DEPTH_SIZE, DEPTH_IMAGES, &sc->gfx, sc->textures)
                                                         : XR_ERROR_GRAPHICS_DEVICE_INVALID;
    if (r != XR_SUCCESS) { release(sc); return r; }
    d->swapchain = sc;
    *out = reinterpret_cast<XrEnvironmentDepthSwapchainMETA>(sc);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL DestroyEnvironmentDepthSwapchainMETA(XrEnvironmentDepthSwapchainMETA h) {
    DepthSwapchain* sc = asDepthSc(h);
    if (!sc) return XR_ERROR_HANDLE_INVALID;
    sc->provider->swapchain = nullptr;
    unregisterHandle(sc);
    destroyDepthSwapchain(sc);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL EnumerateEnvironmentDepthSwapchainImagesMETA(XrEnvironmentDepthSwapchainMETA h, uint32_t capacity, uint32_t* count, XrSwapchainImageBaseHeader* images) {
    DepthSwapchain* sc = asDepthSc(h);
    if (!sc) return XR_ERROR_HANDLE_INVALID;
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    *count = DEPTH_IMAGES;
    if (capacity == 0) return XR_SUCCESS;
    if (capacity < DEPTH_IMAGES) return XR_ERROR_SIZE_INSUFFICIENT;
    if (!images) return XR_ERROR_VALIDATION_FAILURE;
    // XrSwapchainImageOpenGLESKHR: type, next, image
    struct GlesImage { XrStructureType type; void* next; uint32_t image; };
    auto* out = reinterpret_cast<GlesImage*>(images);
    for (uint32_t i = 0; i < DEPTH_IMAGES; i++) {
        if (out[i].type != XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR) return XR_ERROR_VALIDATION_FAILURE;
        out[i].image = sc->textures[i];
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetEnvironmentDepthSwapchainStateMETA(XrEnvironmentDepthSwapchainMETA h, XrEnvironmentDepthSwapchainStateMETA* state) {
    if (!asDepthSc(h)) return XR_ERROR_HANDLE_INVALID;
    if (!state || state->type != XR_TYPE_ENVIRONMENT_DEPTH_SWAPCHAIN_STATE_META) return XR_ERROR_VALIDATION_FAILURE;
    state->width = DEPTH_SIZE;
    state->height = DEPTH_SIZE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL AcquireEnvironmentDepthImageMETA(XrEnvironmentDepthProviderMETA h, const XrEnvironmentDepthImageAcquireInfoMETA* info, XrEnvironmentDepthImageMETA* image) {
    DepthProvider* d = asDepth(h);
    if (!d) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_ENVIRONMENT_DEPTH_IMAGE_ACQUIRE_INFO_META || !image || image->type != XR_TYPE_ENVIRONMENT_DEPTH_IMAGE_META)
        return XR_ERROR_VALIDATION_FAILURE;
    Space* base = asSpace(info->space);
    if (!base) return XR_ERROR_HANDLE_INVALID;
    if (!d->running || !d->swapchain) return XR_ERROR_CALL_ORDER_INVALID;
    Session* s = d->session;
    XrTime t = info->displayTime > 0 ? info->displayTime : hxp::nowNs();
    XrPosef head, basePose; bool bt;
    if (!hxp::headPose(t, s->instance->cfg, &head) || !spaceInStage(base, t, &basePose, &bt)) return XR_ENVIRONMENT_DEPTH_NOT_AVAILABLE_META;
    // a wide square view per eye (Meta's depth views are wider than the display's)
    const float half = 1.0f;  // 45 degrees each way
    XrPosef eyes[2];
    XrFovf fov[2];
    for (int e = 0; e < 2; e++) {
        eyes[e] = hx::compose(head, XrPosef{{0, 0, 0, 1}, {(e == 0 ? -0.5f : 0.5f) * s->instance->cfg.ipdM, 0, 0}});
        fov[e] = {-atanf(half), atanf(half), -atanf(half), atanf(half)};
    }
    uint32_t index = d->next;
    d->next = (d->next + 1) % DEPTH_IMAGES;
    static float tris[3 * 3 * (4 * hxscene::MAX_POLY)];
    int n = hxscene::roomTriangles(s->room, tris, 4 * hxscene::MAX_POLY * 3);
    hxp::gfxRenderDepth(s->gfx, d->swapchain->gfx, index, eyes, fov, DEPTH_NEAR, tris, n);
    image->swapchainIndex = index;
    image->nearZ = DEPTH_NEAR;
    image->farZ = INFINITY;  // depth = 1 - near / z
    XrPosef inv = hx::inverse(basePose);
    for (int e = 0; e < 2; e++) {
        image->views[e].pose = hx::compose(inv, eyes[e]);
        image->views[e].fov = fov[e];
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL SetEnvironmentDepthHandRemovalMETA(XrEnvironmentDepthProviderMETA h, const XrEnvironmentDepthHandRemovalSetInfoMETA* info) {
    if (!asDepth(h)) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_ENVIRONMENT_DEPTH_HAND_REMOVAL_SET_INFO_META) return XR_ERROR_VALIDATION_FAILURE;
    return XR_SUCCESS;  // the room's depth never has hands in it
}

// ------------------------------------------------------------------ gaze (XR_FB_eye_tracking_social)
/** No camera sees the eyes in a viewer: each eye looks where the head looks (low confidence). */
struct EyeTracker { uint32_t magic; Session* session; };

XRAPI_ATTR XrResult XRAPI_CALL CreateEyeTrackerFB(XrSession h, const XrEyeTrackerCreateInfoFB* ci, XrEyeTrackerFB* out) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!ci || ci->type != XR_TYPE_EYE_TRACKER_CREATE_INFO_FB || !out) return XR_ERROR_VALIDATION_FAILURE;
    EyeTracker* e = alloc<EyeTracker>(MAGIC_EYE, s);
    if (!e) return XR_ERROR_OUT_OF_MEMORY;
    e->session = s;
    *out = reinterpret_cast<XrEyeTrackerFB>(e);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL DestroyEyeTrackerFB(XrEyeTrackerFB h) {
    EyeTracker* e = asObj<EyeTracker>(reinterpret_cast<uint64_t>(h), MAGIC_EYE);
    if (!e) return XR_ERROR_HANDLE_INVALID;
    release(e);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetEyeGazesFB(XrEyeTrackerFB h, const XrEyeGazesInfoFB* info, XrEyeGazesFB* gazes) {
    EyeTracker* e = asObj<EyeTracker>(reinterpret_cast<uint64_t>(h), MAGIC_EYE);
    if (!e) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_EYE_GAZES_INFO_FB || !gazes || gazes->type != XR_TYPE_EYE_GAZES_FB) return XR_ERROR_VALIDATION_FAILURE;
    if (info->time <= 0) return XR_ERROR_TIME_INVALID;
    Space* base = asSpace(info->baseSpace);
    if (!base) return XR_ERROR_HANDLE_INVALID;
    gazes->time = info->time;
    XrPosef head, basePose; bool bt;
    bool ok = hxp::headPose(info->time, e->session->instance->cfg, &head) && spaceInStage(base, info->time, &basePose, &bt);
    for (int i = 0; i < XR_EYE_POSITION_COUNT_FB; i++) {
        gazes->gaze[i].isValid = ok ? XR_TRUE : XR_FALSE;
        gazes->gaze[i].gazeConfidence = ok ? 0.2f : 0;
        gazes->gaze[i].gazePose = ok ? hx::compose(hx::inverse(basePose),
            hx::compose(head, XrPosef{{0, 0, 0, 1}, {(i == 0 ? -0.5f : 0.5f) * e->session->instance->cfg.ipdM, 0, 0}})) : hx::identity();
    }
    return XR_SUCCESS;
}

// ------------------------------------------------------------------ smaller Meta extensions
/** XR_META_performance_metrics counters; the ones a phone cannot measure report no valid value. */
const char* const kPerfCounters[] = {
    "/perfmetrics_meta/app/cpu_frametime",
    "/perfmetrics_meta/app/gpu_frametime",
    "/perfmetrics_meta/app/motion_to_photon_latency",
    "/perfmetrics_meta/compositor/cpu_frametime",
    "/perfmetrics_meta/compositor/gpu_frametime",
    "/perfmetrics_meta/compositor/dropped_frame_count",
    "/perfmetrics_meta/compositor/spacewarp_mode",
    "/perfmetrics_meta/device/cpu_utilization_average",
    "/perfmetrics_meta/device/cpu_utilization_worst",
    "/perfmetrics_meta/device/gpu_utilization",
};
constexpr uint32_t kPerfCounterCount = sizeof(kPerfCounters) / sizeof(kPerfCounters[0]);

XRAPI_ATTR XrResult XRAPI_CALL EnumeratePerformanceMetricsCounterPathsMETA(XrInstance h, uint32_t capacity, uint32_t* count, XrPath* paths) {
    Instance* inst = asInstance(h);
    if (!inst) return XR_ERROR_HANDLE_INVALID;
    if (!count) return XR_ERROR_VALIDATION_FAILURE;
    *count = kPerfCounterCount;
    if (capacity == 0) return XR_SUCCESS;
    if (capacity < kPerfCounterCount) return XR_ERROR_SIZE_INSUFFICIENT;
    if (!paths) return XR_ERROR_VALIDATION_FAILURE;
    for (uint32_t i = 0; i < kPerfCounterCount; i++) {
        XrResult r = stringToPath(inst, kPerfCounters[i], &paths[i]);
        if (r != XR_SUCCESS) return r;
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL SetPerformanceMetricsStateMETA(XrSession h, const XrPerformanceMetricsStateMETA* state) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!state || state->type != XR_TYPE_PERFORMANCE_METRICS_STATE_META) return XR_ERROR_VALIDATION_FAILURE;
    s->metrics = state->enabled == XR_TRUE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetPerformanceMetricsStateMETA(XrSession h, XrPerformanceMetricsStateMETA* state) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!state || state->type != XR_TYPE_PERFORMANCE_METRICS_STATE_META) return XR_ERROR_VALIDATION_FAILURE;
    state->enabled = s->metrics ? XR_TRUE : XR_FALSE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL QueryPerformanceMetricsCounterMETA(XrSession h, XrPath path, XrPerformanceMetricsCounterMETA* counter) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!counter || counter->type != XR_TYPE_PERFORMANCE_METRICS_COUNTER_META) return XR_ERROR_VALIDATION_FAILURE;
    if (!s->metrics) return XR_ERROR_VALIDATION_FAILURE;  // the app must turn the metrics on first
    const char* p = pathString(s->instance, path);
    int which = -1;
    for (uint32_t i = 0; p && i < kPerfCounterCount; i++) if (strcmp(p, kPerfCounters[i]) == 0) which = static_cast<int>(i);
    if (which < 0) return XR_ERROR_PATH_UNSUPPORTED;
    counter->counterFlags = 0;
    counter->counterUnit = XR_PERFORMANCE_METRICS_COUNTER_UNIT_GENERIC_META;
    counter->uintValue = 0;
    counter->floatValue = 0;
    auto ms = [&](int64_t ns) {
        counter->counterFlags = XR_PERFORMANCE_METRICS_COUNTER_ANY_VALUE_VALID_BIT_META | XR_PERFORMANCE_METRICS_COUNTER_FLOAT_VALUE_VALID_BIT_META;
        counter->counterUnit = XR_PERFORMANCE_METRICS_COUNTER_UNIT_MILLISECONDS_META;
        counter->floatValue = static_cast<float>(ns) / 1e6f;
    };
    auto count = [&](uint32_t v) {
        counter->counterFlags = XR_PERFORMANCE_METRICS_COUNTER_ANY_VALUE_VALID_BIT_META | XR_PERFORMANCE_METRICS_COUNTER_UINT_VALUE_VALID_BIT_META;
        counter->uintValue = v;
    };
    switch (which) {
        case 0: ms(s->appCpuNs); break;                                   // from xrWaitFrame to xrEndFrame
        case 2: ms(s->lastPredicted > s->frameWaitNs ? s->lastPredicted - s->frameWaitNs : 0); break;  // pose to display
        case 3: ms(s->composeNs); break;
        case 5: count(s->droppedFrames); break;
        case 6: count(0); break;                                          // no space warp
        default: break;                                                   // GPU times and utilisation: not readable on a phone
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL GetRecommendedLayerResolutionMETA(XrSession h, const XrRecommendedLayerResolutionGetInfoMETA* info, XrRecommendedLayerResolutionMETA* res) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_RECOMMENDED_LAYER_RESOLUTION_GET_INFO_META || !info->layer || !res ||
        res->type != XR_TYPE_RECOMMENDED_LAYER_RESOLUTION_META)
        return XR_ERROR_VALIDATION_FAILURE;
    if (info->predictedDisplayTime <= 0) return XR_ERROR_TIME_INVALID;
    // a projection view is best at the size of one eye of the screen; other layers keep their own
    res->isValid = info->layer->type == XR_TYPE_COMPOSITION_LAYER_PROJECTION ? XR_TRUE : XR_FALSE;
    res->recommendedImageDimensions = {s->instance->cfg.eyeWidthPx, s->instance->cfg.eyeHeightPx};
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL QuerySystemTrackedKeyboardFB(XrSession h, const XrKeyboardTrackingQueryFB* query, XrKeyboardTrackingDescriptionFB* keyboard) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!query || query->type != XR_TYPE_KEYBOARD_TRACKING_QUERY_FB || !keyboard) return XR_ERROR_VALIDATION_FAILURE;
    // no keyboard is tracked by the phone's camera: a Bluetooth keyboard still types into the game
    memset(keyboard, 0, sizeof(*keyboard));
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL CreateKeyboardSpaceFB(XrSession h, const XrKeyboardSpaceCreateInfoFB* ci, XrSpace* out) {
    if (!asSession(h)) return XR_ERROR_HANDLE_INVALID;
    if (!ci || ci->type != XR_TYPE_KEYBOARD_SPACE_CREATE_INFO_FB || !out) return XR_ERROR_VALIDATION_FAILURE;
    return XR_ERROR_FEATURE_UNSUPPORTED;  // there is no tracked keyboard id to make a space for
}

XRAPI_ATTR XrResult XRAPI_CALL GetPassthroughPreferencesMETA(XrSession h, XrPassthroughPreferencesMETA* prefs) {
    if (!asSession(h)) return XR_ERROR_HANDLE_INVALID;
    if (!prefs || prefs->type != XR_TYPE_PASSTHROUGH_PREFERENCES_META) return XR_ERROR_VALIDATION_FAILURE;
    prefs->flags = 0;  // start apps in VR (the user turns the camera on in the game)
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL SaveSpaceListFB(XrSession h, const XrSpaceListSaveInfoFB* info, XrAsyncRequestIdFB* requestId) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_SPACE_LIST_SAVE_INFO_FB || !requestId || (info->spaceCount > 0 && !info->spaces)) return XR_ERROR_VALIDATION_FAILURE;
    if (info->location != XR_SPACE_STORAGE_LOCATION_LOCAL_FB) return XR_ERROR_VALIDATION_FAILURE;
    for (uint32_t i = 0; i < info->spaceCount; i++) {
        Space* sp = asSpace(info->spaces[i]);
        if (!sp || sp->session != s) return XR_ERROR_HANDLE_INVALID;
        SceneEntity* e = entityOf(sp);
        if (!e || !(componentsOf(e->kind) & bit(XR_SPACE_COMPONENT_TYPE_STORABLE_FB))) return XR_ERROR_SPACE_COMPONENT_NOT_SUPPORTED_FB;
    }
    for (uint32_t i = 0; i < info->spaceCount; i++) entityOf(asSpace(info->spaces[i]))->stored = true;
    persistAnchors(s);
    XrEventDataSpaceListSaveCompleteFB ev{XR_TYPE_EVENT_DATA_SPACE_LIST_SAVE_COMPLETE_FB};
    ev.requestId = *requestId = newRequest(s->instance);
    ev.result = XR_SUCCESS;
    pushEvent(s->instance, &ev, sizeof(ev));
    return XR_SUCCESS;
}

/** Hands and controllers already work together here (both drive actions); the calls are accepted. */
XRAPI_ATTR XrResult XRAPI_CALL ResumeSimultaneousHandsAndControllersTrackingMETA(XrSession h, const XrSimultaneousHandsAndControllersTrackingResumeInfoMETA* info) {
    if (!asSession(h)) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_SIMULTANEOUS_HANDS_AND_CONTROLLERS_TRACKING_RESUME_INFO_META) return XR_ERROR_VALIDATION_FAILURE;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL PauseSimultaneousHandsAndControllersTrackingMETA(XrSession h, const XrSimultaneousHandsAndControllersTrackingPauseInfoMETA* info) {
    if (!asSession(h)) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_SIMULTANEOUS_HANDS_AND_CONTROLLERS_TRACKING_PAUSE_INFO_META) return XR_ERROR_VALIDATION_FAILURE;
    return XR_SUCCESS;
}

// ------------------------------------------------------------------ passthrough (XR_FB_passthrough)
XRAPI_ATTR XrResult XRAPI_CALL CreatePassthroughFB(XrSession h, const XrPassthroughCreateInfoFB* ci, XrPassthroughFB* out) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!ci || ci->type != XR_TYPE_PASSTHROUGH_CREATE_INFO_FB || !out) return XR_ERROR_VALIDATION_FAILURE;
    Passthrough* p = alloc<Passthrough>(MAGIC_PASSTHROUGH, s);
    if (!p) return XR_ERROR_OUT_OF_MEMORY;
    p->session = s;
    p->running = (ci->flags & XR_PASSTHROUGH_IS_RUNNING_AT_CREATION_BIT_FB) != 0;
    if (p->running) hxp::passthroughWanted();  // HyperMagic turns the camera on now
    *out = reinterpret_cast<XrPassthroughFB>(p);
    return XR_SUCCESS;
}

Passthrough* asPassthrough(XrPassthroughFB h) { return asObj<Passthrough>(reinterpret_cast<uint64_t>(h), MAGIC_PASSTHROUGH); }
PassthroughLayer* asPtLayer(XrPassthroughLayerFB h) { return asObj<PassthroughLayer>(reinterpret_cast<uint64_t>(h), MAGIC_PT_LAYER); }

/** XR_META_passthrough_layer_resumed_event: a layer shows the camera again. */
void layerResumed(PassthroughLayer* l) {
    if (!extEnabled(l->session->instance, XR_META_PASSTHROUGH_LAYER_RESUMED_EVENT_EXTENSION_NAME)) return;
    XrEventDataPassthroughLayerResumedMETA ev{XR_TYPE_EVENT_DATA_PASSTHROUGH_LAYER_RESUMED_META};
    ev.layer = reinterpret_cast<XrPassthroughLayerFB>(l);
    pushEvent(l->session->instance, &ev, sizeof(ev));
}


XRAPI_ATTR XrResult XRAPI_CALL DestroyPassthroughFB(XrPassthroughFB h) {
    Passthrough* p = asPassthrough(h);
    if (!p) return XR_ERROR_HANDLE_INVALID;
    while (void* c = takeChild(p, MAGIC_LUT)) { ColorLut* lut = static_cast<ColorLut*>(c); free(lut->rgba); lut->magic = 0; free(lut); }
    release(p);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL PassthroughStartFB(XrPassthroughFB h) {
    Passthrough* p = asPassthrough(h);
    if (!p) return XR_ERROR_HANDLE_INVALID;
    p->running = true;
    hxp::passthroughWanted();
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL PassthroughPauseFB(XrPassthroughFB h) {
    Passthrough* p = asPassthrough(h);
    if (!p) return XR_ERROR_HANDLE_INVALID;
    p->running = false;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL CreatePassthroughLayerFB(XrSession h, const XrPassthroughLayerCreateInfoFB* ci, XrPassthroughLayerFB* out) {
    Session* s = asSession(h);
    if (!s) return XR_ERROR_HANDLE_INVALID;
    if (!ci || ci->type != XR_TYPE_PASSTHROUGH_LAYER_CREATE_INFO_FB || !out) return XR_ERROR_VALIDATION_FAILURE;
    Passthrough* p = asPassthrough(ci->passthrough);
    if (!p || p->session != s) return XR_ERROR_HANDLE_INVALID;
    // the whole camera view; keyboard-hands layers are accepted (there is no tracked keyboard, so
    // they show nothing); surfaces projected onto app meshes need XR_FB_triangle_mesh
    bool keyboard = (ci->purpose == XR_PASSTHROUGH_LAYER_PURPOSE_TRACKED_KEYBOARD_HANDS_FB ||
                     ci->purpose == XR_PASSTHROUGH_LAYER_PURPOSE_TRACKED_KEYBOARD_MASKED_HANDS_FB) &&
                    extEnabled(s->instance, XR_FB_PASSTHROUGH_KEYBOARD_HANDS_EXTENSION_NAME);
    if (ci->purpose != XR_PASSTHROUGH_LAYER_PURPOSE_RECONSTRUCTION_FB && !keyboard) return XR_ERROR_FEATURE_UNSUPPORTED;
    PassthroughLayer* l = alloc<PassthroughLayer>(MAGIC_PT_LAYER, s);
    if (!l) return XR_ERROR_OUT_OF_MEMORY;
    l->session = s;
    l->passthrough = p;
    l->running = (ci->flags & XR_PASSTHROUGH_IS_RUNNING_AT_CREATION_BIT_FB) != 0;
    l->opacity = keyboard ? 0 : 1;
    l->contrast = l->saturation = 1;
    *out = reinterpret_cast<XrPassthroughLayerFB>(l);
    if (l->running) layerResumed(l);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL DestroyPassthroughLayerFB(XrPassthroughLayerFB h) {
    PassthroughLayer* l = asPtLayer(h);
    if (!l) return XR_ERROR_HANDLE_INVALID;
    release(l);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL PassthroughLayerPauseFB(XrPassthroughLayerFB h) {
    PassthroughLayer* l = asPtLayer(h);
    if (!l) return XR_ERROR_HANDLE_INVALID;
    l->running = false;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL PassthroughLayerResumeFB(XrPassthroughLayerFB h) {
    PassthroughLayer* l = asPtLayer(h);
    if (!l) return XR_ERROR_HANDLE_INVALID;
    bool was = l->running;
    l->running = true;
    if (!was) layerResumed(l);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL PassthroughLayerSetStyleFB(XrPassthroughLayerFB h, const XrPassthroughStyleFB* style) {
    PassthroughLayer* l = asPtLayer(h);
    if (!l) return XR_ERROR_HANDLE_INVALID;
    if (!style || style->type != XR_TYPE_PASSTHROUGH_STYLE_FB) return XR_ERROR_VALIDATION_FAILURE;
    if (style->textureOpacityFactor < 0 || style->textureOpacityFactor > 1) return XR_ERROR_VALIDATION_FAILURE;
    // opacity and the colour maps are drawn; the edge colour is accepted (the phone's camera gives no edges)
    int mode = HX_COLOR_NONE;
    for (auto* n = reinterpret_cast<const XrBaseInStructure*>(style->next); n; n = n->next) {
        if (n->type == XR_TYPE_PASSTHROUGH_COLOR_MAP_MONO_TO_RGBA_FB) {
            auto* m = reinterpret_cast<const XrPassthroughColorMapMonoToRgbaFB*>(n);
            for (int i = 0; i < 256; i++) {
                const XrColor4f& c = m->textureColorMap[i];
                const float v[4] = {c.r, c.g, c.b, c.a};
                for (int k = 0; k < 4; k++) l->map[i * 4 + k] = static_cast<uint8_t>((v[k] < 0 ? 0 : v[k] > 1 ? 1 : v[k]) * 255.0f + 0.5f);
            }
            mode = HX_COLOR_MAP;
        } else if (n->type == XR_TYPE_PASSTHROUGH_COLOR_MAP_MONO_TO_MONO_FB) {
            auto* m = reinterpret_cast<const XrPassthroughColorMapMonoToMonoFB*>(n);
            for (int i = 0; i < 256; i++) { l->map[i * 4] = l->map[i * 4 + 1] = l->map[i * 4 + 2] = m->textureColorMap[i]; l->map[i * 4 + 3] = 255; }
            mode = HX_COLOR_MAP;
        } else if (n->type == XR_TYPE_PASSTHROUGH_BRIGHTNESS_CONTRAST_SATURATION_FB) {
            auto* b = reinterpret_cast<const XrPassthroughBrightnessContrastSaturationFB*>(n);
            if (b->brightness < -100 || b->brightness > 100 || b->contrast < 0 || b->saturation < 0) return XR_ERROR_VALIDATION_FAILURE;
            l->brightness = b->brightness; l->contrast = b->contrast; l->saturation = b->saturation;
            mode = HX_COLOR_BCS;
        } else if (n->type == XR_TYPE_PASSTHROUGH_COLOR_MAP_LUT_META) {
            auto* m = reinterpret_cast<const XrPassthroughColorMapLutMETA*>(n);
            ColorLut* lut = asObj<ColorLut>(reinterpret_cast<uint64_t>(m->colorLut), MAGIC_LUT);
            if (!lut || m->weight < 0 || m->weight > 1) return lut ? XR_ERROR_VALIDATION_FAILURE : XR_ERROR_HANDLE_INVALID;
            l->lutA = nullptr; l->lutB = lut; l->lutWeight = m->weight;
            mode = HX_COLOR_LUT;
        } else if (n->type == XR_TYPE_PASSTHROUGH_COLOR_MAP_INTERPOLATED_LUT_META) {
            auto* m = reinterpret_cast<const XrPassthroughColorMapInterpolatedLutMETA*>(n);
            ColorLut* a = asObj<ColorLut>(reinterpret_cast<uint64_t>(m->sourceColorLut), MAGIC_LUT);
            ColorLut* b = asObj<ColorLut>(reinterpret_cast<uint64_t>(m->targetColorLut), MAGIC_LUT);
            if (!a || !b) return XR_ERROR_HANDLE_INVALID;
            if (a->res != b->res || m->weight < 0 || m->weight > 1) return XR_ERROR_VALIDATION_FAILURE;
            l->lutA = a; l->lutB = b; l->lutWeight = m->weight;
            mode = HX_COLOR_LUT;
        }
    }
    l->colorMode = mode;
    l->opacity = style->textureOpacityFactor;
    l->version++;
    return XR_SUCCESS;
}

/** Copies a colour cube from the app into RGBA8 (RGB cubes get an opaque alpha). */
XrResult readLut(int channels, uint32_t res, const XrPassthroughColorLutDataMETA& data, uint8_t* out) {
    size_t texels = static_cast<size_t>(res) * res * res;
    if (data.bufferSize != texels * static_cast<size_t>(channels) || !data.buffer) return XR_ERROR_VALIDATION_FAILURE;
    for (size_t i = 0; i < texels; i++) {
        out[i * 4] = data.buffer[i * channels]; out[i * 4 + 1] = data.buffer[i * channels + 1]; out[i * 4 + 2] = data.buffer[i * channels + 2];
        out[i * 4 + 3] = channels == 4 ? data.buffer[i * channels + 3] : 255;
    }
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL CreatePassthroughColorLutMETA(XrPassthroughFB h, const XrPassthroughColorLutCreateInfoMETA* ci, XrPassthroughColorLutMETA* out) {
    Passthrough* p = asPassthrough(h);
    if (!p) return XR_ERROR_HANDLE_INVALID;
    if (!ci || ci->type != XR_TYPE_PASSTHROUGH_COLOR_LUT_CREATE_INFO_META || !out) return XR_ERROR_VALIDATION_FAILURE;
    int channels = ci->channels == XR_PASSTHROUGH_COLOR_LUT_CHANNELS_RGB_META ? 3 : ci->channels == XR_PASSTHROUGH_COLOR_LUT_CHANNELS_RGBA_META ? 4 : 0;
    if (!channels || ci->resolution < 2 || ci->resolution > 64 || (ci->resolution & (ci->resolution - 1))) return XR_ERROR_VALIDATION_FAILURE;
    uint8_t* rgba = static_cast<uint8_t*>(malloc(static_cast<size_t>(ci->resolution) * ci->resolution * ci->resolution * 4));
    if (!rgba) return XR_ERROR_OUT_OF_MEMORY;
    XrResult r = readLut(channels, ci->resolution, ci->data, rgba);
    if (r != XR_SUCCESS) { free(rgba); return r; }
    ColorLut* lut = alloc<ColorLut>(MAGIC_LUT, p);
    if (!lut) { free(rgba); return XR_ERROR_OUT_OF_MEMORY; }
    lut->passthrough = p; lut->res = static_cast<int>(ci->resolution); lut->rgba = rgba; lut->version = 1;
    *out = reinterpret_cast<XrPassthroughColorLutMETA>(lut);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL UpdatePassthroughColorLutMETA(XrPassthroughColorLutMETA h, const XrPassthroughColorLutUpdateInfoMETA* info) {
    ColorLut* lut = asObj<ColorLut>(reinterpret_cast<uint64_t>(h), MAGIC_LUT);
    if (!lut) return XR_ERROR_HANDLE_INVALID;
    if (!info || info->type != XR_TYPE_PASSTHROUGH_COLOR_LUT_UPDATE_INFO_META) return XR_ERROR_VALIDATION_FAILURE;
    // same size and channel count as at creation (the buffer size tells which)
    size_t texels = static_cast<size_t>(lut->res) * lut->res * lut->res;
    int channels = info->data.bufferSize == texels * 3 ? 3 : info->data.bufferSize == texels * 4 ? 4 : 0;
    if (!channels) return XR_ERROR_VALIDATION_FAILURE;
    XrResult r = readLut(channels, static_cast<uint32_t>(lut->res), info->data, lut->rgba);
    if (r == XR_SUCCESS) lut->version++;
    return r;
}

XRAPI_ATTR XrResult XRAPI_CALL DestroyPassthroughColorLutMETA(XrPassthroughColorLutMETA h) {
    ColorLut* lut = asObj<ColorLut>(reinterpret_cast<uint64_t>(h), MAGIC_LUT);
    if (!lut) return XR_ERROR_HANDLE_INVALID;
    // layers still pointing at it stop using it
    forEachChild(lut->passthrough->session, MAGIC_PT_LAYER, [&](void* c) {
        PassthroughLayer* l = static_cast<PassthroughLayer*>(c);
        if (l->lutA == lut || l->lutB == lut) { l->lutA = l->lutB = nullptr; if (l->colorMode == HX_COLOR_LUT) l->colorMode = HX_COLOR_NONE; l->version++; }
    });
    free(lut->rgba);
    release(lut);
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL PassthroughLayerSetKeyboardHandsIntensityFB(XrPassthroughLayerFB h, const XrPassthroughKeyboardHandsIntensityFB* intensity) {
    PassthroughLayer* l = asPtLayer(h);
    if (!l) return XR_ERROR_HANDLE_INVALID;
    if (!intensity || intensity->type != XR_TYPE_PASSTHROUGH_KEYBOARD_HANDS_INTENSITY_FB) return XR_ERROR_VALIDATION_FAILURE;
    if (intensity->leftHandIntensity < 0 || intensity->leftHandIntensity > 1 || intensity->rightHandIntensity < 0 || intensity->rightHandIntensity > 1)
        return XR_ERROR_VALIDATION_FAILURE;
    l->keyboardHands[0] = intensity->leftHandIntensity;
    l->keyboardHands[1] = intensity->rightHandIntensity;
    return XR_SUCCESS;
}

XRAPI_ATTR XrResult XRAPI_CALL CreateGeometryInstanceFB(XrSession h, const XrGeometryInstanceCreateInfoFB* ci, XrGeometryInstanceFB* out) {
    if (!asSession(h)) return XR_ERROR_HANDLE_INVALID;
    if (!ci || ci->type != XR_TYPE_GEOMETRY_INSTANCE_CREATE_INFO_FB || !out) return XR_ERROR_VALIDATION_FAILURE;
    return XR_ERROR_FEATURE_UNSUPPORTED;  // no projected-surface layers (see CreatePassthroughLayerFB)
}

XRAPI_ATTR XrResult XRAPI_CALL DestroyGeometryInstanceFB(XrGeometryInstanceFB) { return XR_ERROR_HANDLE_INVALID; }

XRAPI_ATTR XrResult XRAPI_CALL GeometryInstanceSetTransformFB(XrGeometryInstanceFB, const XrGeometryInstanceTransformFB*) { return XR_ERROR_HANDLE_INVALID; }

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
    HX_EXT(CreateSpatialAnchorFB, XR_FB_SPATIAL_ENTITY_EXTENSION_NAME),
    HX_EXT(GetSpaceUuidFB, XR_FB_SPATIAL_ENTITY_EXTENSION_NAME),
    HX_EXT(EnumerateSpaceSupportedComponentsFB, XR_FB_SPATIAL_ENTITY_EXTENSION_NAME),
    HX_EXT(SetSpaceComponentStatusFB, XR_FB_SPATIAL_ENTITY_EXTENSION_NAME),
    HX_EXT(GetSpaceComponentStatusFB, XR_FB_SPATIAL_ENTITY_EXTENSION_NAME),
    HX_EXT(QuerySpacesFB, XR_FB_SPATIAL_ENTITY_QUERY_EXTENSION_NAME),
    HX_EXT(RetrieveSpaceQueryResultsFB, XR_FB_SPATIAL_ENTITY_QUERY_EXTENSION_NAME),
    HX_EXT(SaveSpaceFB, XR_FB_SPATIAL_ENTITY_STORAGE_EXTENSION_NAME),
    HX_EXT(EraseSpaceFB, XR_FB_SPATIAL_ENTITY_STORAGE_EXTENSION_NAME),
    HX_EXT(GetSpaceContainerFB, XR_FB_SPATIAL_ENTITY_CONTAINER_EXTENSION_NAME),
    HX_EXT(GetSpaceBoundingBox2DFB, XR_FB_SCENE_EXTENSION_NAME),
    HX_EXT(GetSpaceBoundingBox3DFB, XR_FB_SCENE_EXTENSION_NAME),
    HX_EXT(GetSpaceSemanticLabelsFB, XR_FB_SCENE_EXTENSION_NAME),
    HX_EXT(GetSpaceBoundary2DFB, XR_FB_SCENE_EXTENSION_NAME),
    HX_EXT(GetSpaceRoomLayoutFB, XR_FB_SCENE_EXTENSION_NAME),
    HX_EXT(RequestSceneCaptureFB, XR_FB_SCENE_CAPTURE_EXTENSION_NAME),
    HX_EXT(EnumerateRenderModelPathsFB, XR_FB_RENDER_MODEL_EXTENSION_NAME),
    HX_EXT(GetRenderModelPropertiesFB, XR_FB_RENDER_MODEL_EXTENSION_NAME),
    HX_EXT(LoadRenderModelFB, XR_FB_RENDER_MODEL_EXTENSION_NAME),
    HX_EXT(GetDeviceSampleRateFB, XR_FB_HAPTIC_PCM_EXTENSION_NAME),
    HX_EXT(EnumeratePerformanceMetricsCounterPathsMETA, XR_META_PERFORMANCE_METRICS_EXTENSION_NAME),
    HX_EXT(SetPerformanceMetricsStateMETA, XR_META_PERFORMANCE_METRICS_EXTENSION_NAME),
    HX_EXT(GetPerformanceMetricsStateMETA, XR_META_PERFORMANCE_METRICS_EXTENSION_NAME),
    HX_EXT(QueryPerformanceMetricsCounterMETA, XR_META_PERFORMANCE_METRICS_EXTENSION_NAME),
    HX_EXT(GetRecommendedLayerResolutionMETA, XR_META_RECOMMENDED_LAYER_RESOLUTION_EXTENSION_NAME),
    HX_EXT(QuerySystemTrackedKeyboardFB, XR_FB_KEYBOARD_TRACKING_EXTENSION_NAME),
    HX_EXT(CreateKeyboardSpaceFB, XR_FB_KEYBOARD_TRACKING_EXTENSION_NAME),
    HX_EXT(GetPassthroughPreferencesMETA, XR_META_PASSTHROUGH_PREFERENCES_EXTENSION_NAME),
    HX_EXT(SaveSpaceListFB, XR_FB_SPATIAL_ENTITY_STORAGE_BATCH_EXTENSION_NAME),
    HX_EXT(ResumeSimultaneousHandsAndControllersTrackingMETA, XR_META_SIMULTANEOUS_HANDS_AND_CONTROLLERS_EXTENSION_NAME),
    HX_EXT(PauseSimultaneousHandsAndControllersTrackingMETA, XR_META_SIMULTANEOUS_HANDS_AND_CONTROLLERS_EXTENSION_NAME),
    HX_EXT(CreateEnvironmentDepthProviderMETA, XR_META_ENVIRONMENT_DEPTH_EXTENSION_NAME),
    HX_EXT(DestroyEnvironmentDepthProviderMETA, XR_META_ENVIRONMENT_DEPTH_EXTENSION_NAME),
    HX_EXT(StartEnvironmentDepthProviderMETA, XR_META_ENVIRONMENT_DEPTH_EXTENSION_NAME),
    HX_EXT(StopEnvironmentDepthProviderMETA, XR_META_ENVIRONMENT_DEPTH_EXTENSION_NAME),
    HX_EXT(CreateEnvironmentDepthSwapchainMETA, XR_META_ENVIRONMENT_DEPTH_EXTENSION_NAME),
    HX_EXT(DestroyEnvironmentDepthSwapchainMETA, XR_META_ENVIRONMENT_DEPTH_EXTENSION_NAME),
    HX_EXT(EnumerateEnvironmentDepthSwapchainImagesMETA, XR_META_ENVIRONMENT_DEPTH_EXTENSION_NAME),
    HX_EXT(GetEnvironmentDepthSwapchainStateMETA, XR_META_ENVIRONMENT_DEPTH_EXTENSION_NAME),
    HX_EXT(AcquireEnvironmentDepthImageMETA, XR_META_ENVIRONMENT_DEPTH_EXTENSION_NAME),
    HX_EXT(SetEnvironmentDepthHandRemovalMETA, XR_META_ENVIRONMENT_DEPTH_EXTENSION_NAME),
    HX_EXT(CreateEyeTrackerFB, XR_FB_EYE_TRACKING_SOCIAL_EXTENSION_NAME),
    HX_EXT(DestroyEyeTrackerFB, XR_FB_EYE_TRACKING_SOCIAL_EXTENSION_NAME),
    HX_EXT(GetEyeGazesFB, XR_FB_EYE_TRACKING_SOCIAL_EXTENSION_NAME),
    HX_EXT(CreateBodyTrackerFB, XR_FB_BODY_TRACKING_EXTENSION_NAME),
    HX_EXT(DestroyBodyTrackerFB, XR_FB_BODY_TRACKING_EXTENSION_NAME),
    HX_EXT(LocateBodyJointsFB, XR_FB_BODY_TRACKING_EXTENSION_NAME),
    HX_EXT(GetBodySkeletonFB, XR_FB_BODY_TRACKING_EXTENSION_NAME),
    HX_EXT(CreateBodyTrackerBD, XR_BD_BODY_TRACKING_EXTENSION_NAME),
    HX_EXT(DestroyBodyTrackerBD, XR_BD_BODY_TRACKING_EXTENSION_NAME),
    HX_EXT(LocateBodyJointsBD, XR_BD_BODY_TRACKING_EXTENSION_NAME),
    HX_EXT(PollFutureEXT, XR_EXT_FUTURE_EXTENSION_NAME),
    HX_EXT(CancelFutureEXT, XR_EXT_FUTURE_EXTENSION_NAME),
    HX_EXT(CreateSenseDataProviderBD, XR_BD_SPATIAL_SENSING_EXTENSION_NAME),
    HX_EXT(StartSenseDataProviderAsyncBD, XR_BD_SPATIAL_SENSING_EXTENSION_NAME),
    HX_EXT(StartSenseDataProviderCompleteBD, XR_BD_SPATIAL_SENSING_EXTENSION_NAME),
    HX_EXT(GetSenseDataProviderStateBD, XR_BD_SPATIAL_SENSING_EXTENSION_NAME),
    HX_EXT(QuerySenseDataAsyncBD, XR_BD_SPATIAL_SENSING_EXTENSION_NAME),
    HX_EXT(QuerySenseDataCompleteBD, XR_BD_SPATIAL_SENSING_EXTENSION_NAME),
    HX_EXT(DestroySenseDataSnapshotBD, XR_BD_SPATIAL_SENSING_EXTENSION_NAME),
    HX_EXT(GetQueriedSenseDataBD, XR_BD_SPATIAL_SENSING_EXTENSION_NAME),
    HX_EXT(StopSenseDataProviderBD, XR_BD_SPATIAL_SENSING_EXTENSION_NAME),
    HX_EXT(DestroySenseDataProviderBD, XR_BD_SPATIAL_SENSING_EXTENSION_NAME),
    HX_EXT(EnumerateSpatialEntityComponentTypesBD, XR_BD_SPATIAL_SENSING_EXTENSION_NAME),
    HX_EXT(GetSpatialEntityUuidBD, XR_BD_SPATIAL_SENSING_EXTENSION_NAME),
    HX_EXT(GetSpatialEntityComponentDataBD, XR_BD_SPATIAL_SENSING_EXTENSION_NAME),
    HX_EXT(CreateSpatialEntityAnchorBD, XR_BD_SPATIAL_SENSING_EXTENSION_NAME),
    HX_EXT(DestroyAnchorBD, XR_BD_SPATIAL_SENSING_EXTENSION_NAME),
    HX_EXT(GetAnchorUuidBD, XR_BD_SPATIAL_SENSING_EXTENSION_NAME),
    HX_EXT(CreateAnchorSpaceBD, XR_BD_SPATIAL_SENSING_EXTENSION_NAME),
    HX_EXT(CreateSpatialAnchorAsyncBD, XR_BD_SPATIAL_ANCHOR_EXTENSION_NAME),
    HX_EXT(CreateSpatialAnchorCompleteBD, XR_BD_SPATIAL_ANCHOR_EXTENSION_NAME),
    HX_EXT(PersistSpatialAnchorAsyncBD, XR_BD_SPATIAL_ANCHOR_EXTENSION_NAME),
    HX_EXT(PersistSpatialAnchorCompleteBD, XR_BD_SPATIAL_ANCHOR_EXTENSION_NAME),
    HX_EXT(UnpersistSpatialAnchorAsyncBD, XR_BD_SPATIAL_ANCHOR_EXTENSION_NAME),
    HX_EXT(UnpersistSpatialAnchorCompleteBD, XR_BD_SPATIAL_ANCHOR_EXTENSION_NAME),
    HX_EXT(CaptureSceneAsyncBD, XR_BD_SPATIAL_SCENE_EXTENSION_NAME),
    HX_EXT(CaptureSceneCompleteBD, XR_BD_SPATIAL_SCENE_EXTENSION_NAME),
    HX_EXT(GetSpaceTriangleMeshMETA, XR_META_SPATIAL_ENTITY_MESH_EXTENSION_NAME),
    HX_EXT(CreatePassthroughColorLutMETA, XR_META_PASSTHROUGH_COLOR_LUT_EXTENSION_NAME),
    HX_EXT(UpdatePassthroughColorLutMETA, XR_META_PASSTHROUGH_COLOR_LUT_EXTENSION_NAME),
    HX_EXT(DestroyPassthroughColorLutMETA, XR_META_PASSTHROUGH_COLOR_LUT_EXTENSION_NAME),
    HX_EXT(PassthroughLayerSetKeyboardHandsIntensityFB, XR_FB_PASSTHROUGH_KEYBOARD_HANDS_EXTENSION_NAME),
    HX_EXT(CreatePassthroughFB, XR_FB_PASSTHROUGH_EXTENSION_NAME),
    HX_EXT(DestroyPassthroughFB, XR_FB_PASSTHROUGH_EXTENSION_NAME),
    HX_EXT(PassthroughStartFB, XR_FB_PASSTHROUGH_EXTENSION_NAME),
    HX_EXT(PassthroughPauseFB, XR_FB_PASSTHROUGH_EXTENSION_NAME),
    HX_EXT(CreatePassthroughLayerFB, XR_FB_PASSTHROUGH_EXTENSION_NAME),
    HX_EXT(DestroyPassthroughLayerFB, XR_FB_PASSTHROUGH_EXTENSION_NAME),
    HX_EXT(PassthroughLayerPauseFB, XR_FB_PASSTHROUGH_EXTENSION_NAME),
    HX_EXT(PassthroughLayerResumeFB, XR_FB_PASSTHROUGH_EXTENSION_NAME),
    HX_EXT(PassthroughLayerSetStyleFB, XR_FB_PASSTHROUGH_EXTENSION_NAME),
    HX_EXT(CreateGeometryInstanceFB, XR_FB_PASSTHROUGH_EXTENSION_NAME),
    HX_EXT(DestroyGeometryInstanceFB, XR_FB_PASSTHROUGH_EXTENSION_NAME),
    HX_EXT(GeometryInstanceSetTransformFB, XR_FB_PASSTHROUGH_EXTENSION_NAME),
    HX_EXT(CreateHandTrackerEXT, XR_EXT_HAND_TRACKING_EXTENSION_NAME),
    HX_EXT(DestroyHandTrackerEXT, XR_EXT_HAND_TRACKING_EXTENSION_NAME),
    HX_EXT(LocateHandJointsEXT, XR_EXT_HAND_TRACKING_EXTENSION_NAME),
    HX_EXT(GetHandMeshFB, XR_FB_HAND_TRACKING_MESH_EXTENSION_NAME),
};
#undef HX_FN
#undef HX_EXT

/** Whether the app enabled the extension [name] (functions of extensions it did not enable are unsupported). */
bool extEnabled(const Instance* inst, const char* name) {
    for (uint32_t k = 0; k < kExtensionCount; k++) if (strcmp(kExtensions[k].name, name) == 0) return (inst->extMeta[k >> 6] >> (k & 63)) & 1ull;
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
