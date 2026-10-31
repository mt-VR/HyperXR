// Host tests for the HyperXR OpenXR runtime. Goes through the same entry point the Khronos loader
// uses (xrNegotiateLoaderRuntimeInterface -> xrGetInstanceProcAddr) and checks the behaviour the
// OpenXR specification requires, using a headless session (XR_MND_headless).
#include <math.h>
#include <stdio.h>
#include <string.h>

#include <openxr/openxr.h>
#include <openxr/openxr_loader_negotiation.h>
#include <time.h>

#include "../src/hx_hands.h"
#include "../src/hx_layers.h"

extern "C" XrResult xrNegotiateLoaderRuntimeInterface(const XrNegotiateLoaderInfo*, XrNegotiateRuntimeRequest*);
extern "C" void hxTestSetHead(float qx, float qy, float qz, float qw, int tracked);
extern "C" void hxTestSetHands(const HxHandShared* h);
extern "C" int64_t hxTestHandsWanted();
extern "C" void hxTestLastHaptic(uint32_t* seq, int* hand, float* amplitude, int* ms);
extern "C" int64_t hxTestPassthroughWanted();
extern "C" void hxTestSetInput(uint32_t buttons, float lx, float ly, float rx, float ry, float lt, float rt, int touch);

static int gFailures = 0;
static int gChecks = 0;
#define CHECK(cond) do { gChecks++; if (!(cond)) { gFailures++; printf("FAIL %s:%d: %s\n", __FILE__, __LINE__, #cond); } } while (0)
#define CHECK_RESULT(expr, expected) do { XrResult r_ = (expr); gChecks++; if (r_ != (expected)) { gFailures++; printf("FAIL %s:%d: %s = %d, expected %d\n", __FILE__, __LINE__, #expr, (int)r_, (int)(expected)); } } while (0)
#define NEAR(a, b, eps) (fabs((double)(a) - (double)(b)) <= (eps))

static PFN_xrGetInstanceProcAddr gpa = nullptr;

template <typename F> F fn(XrInstance inst, const char* name) {
    PFN_xrVoidFunction f = nullptr;
    gpa(inst, name, &f);
    return reinterpret_cast<F>(f);
}

#define LOAD(inst, name) auto name = fn<PFN_##name>(inst, #name); CHECK(name != nullptr)

static void testNegotiation() {
    XrNegotiateLoaderInfo li{XR_LOADER_INTERFACE_STRUCT_LOADER_INFO, XR_LOADER_INFO_STRUCT_VERSION, sizeof(XrNegotiateLoaderInfo)};
    li.minInterfaceVersion = 1; li.maxInterfaceVersion = 1;
    li.minApiVersion = XR_MAKE_VERSION(1, 0, 0); li.maxApiVersion = XR_MAKE_VERSION(1, 0x3ff, 0xfff);
    XrNegotiateRuntimeRequest rr{XR_LOADER_INTERFACE_STRUCT_RUNTIME_REQUEST, XR_RUNTIME_INFO_STRUCT_VERSION, sizeof(XrNegotiateRuntimeRequest)};
    CHECK_RESULT(xrNegotiateLoaderRuntimeInterface(&li, &rr), XR_SUCCESS);
    CHECK(rr.runtimeInterfaceVersion == 1);
    CHECK(XR_VERSION_MAJOR(rr.runtimeApiVersion) == 1);
    CHECK(rr.getInstanceProcAddr != nullptr);
    gpa = rr.getInstanceProcAddr;
    // a loader asking for an interface we do not speak is refused
    XrNegotiateLoaderInfo bad = li; bad.minInterfaceVersion = 2; bad.maxInterfaceVersion = 3;
    XrNegotiateRuntimeRequest rr2{XR_LOADER_INTERFACE_STRUCT_RUNTIME_REQUEST, XR_RUNTIME_INFO_STRUCT_VERSION, sizeof(XrNegotiateRuntimeRequest)};
    CHECK_RESULT(xrNegotiateLoaderRuntimeInterface(&bad, &rr2), XR_ERROR_INITIALIZATION_FAILED);
}

static XrInstance createInstance(XrVersion api, const char* const* exts, uint32_t n, XrResult* result) {
    LOAD(XR_NULL_HANDLE, xrCreateInstance);
    XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO};
    strcpy(ci.applicationInfo.applicationName, "hx-runtime-test");
    ci.applicationInfo.apiVersion = api;
    ci.enabledExtensionCount = n;
    ci.enabledExtensionNames = exts;
    XrInstance inst = XR_NULL_HANDLE;
    *result = xrCreateInstance(&ci, &inst);
    return inst;
}

static void testExtensionsAndInstance() {
    LOAD(XR_NULL_HANDLE, xrEnumerateInstanceExtensionProperties);
    uint32_t n = 0;
    CHECK_RESULT(xrEnumerateInstanceExtensionProperties(nullptr, 0, &n, nullptr), XR_SUCCESS);
    CHECK(n >= 3);
    XrExtensionProperties props[128];
    CHECK(n <= 128);
    for (auto& p : props) { p = {XR_TYPE_EXTENSION_PROPERTIES}; }
    uint32_t small = 0;
    CHECK_RESULT(xrEnumerateInstanceExtensionProperties(nullptr, 1, &small, props), n > 1 ? XR_ERROR_SIZE_INSUFFICIENT : XR_SUCCESS);
    CHECK_RESULT(xrEnumerateInstanceExtensionProperties(nullptr, n, &n, props), XR_SUCCESS);
    bool headless = false;
    for (uint32_t i = 0; i < n; i++) if (strcmp(props[i].extensionName, XR_MND_HEADLESS_EXTENSION_NAME) == 0) headless = true;
    CHECK(headless);

    // instance-less queries other than the creation functions are invalid
    PFN_xrVoidFunction f = nullptr;
    CHECK_RESULT(gpa(XR_NULL_HANDLE, "xrGetSystem", &f), XR_ERROR_HANDLE_INVALID);

    XrResult r;
    createInstance(XR_MAKE_VERSION(2, 0, 0), nullptr, 0, &r);
    CHECK(r == XR_ERROR_API_VERSION_UNSUPPORTED);
    const char* unknown[] = {"XR_FAKE_does_not_exist"};
    createInstance(XR_MAKE_VERSION(1, 0, 0), unknown, 1, &r);
    CHECK(r == XR_ERROR_EXTENSION_NOT_PRESENT);
}

static void testInstanceBasics(XrInstance inst) {
    LOAD(inst, xrGetInstanceProperties);
    XrInstanceProperties ip{XR_TYPE_INSTANCE_PROPERTIES};
    CHECK_RESULT(xrGetInstanceProperties(inst, &ip), XR_SUCCESS);
    CHECK(strcmp(ip.runtimeName, "HyperMagic Runtime") == 0);

    LOAD(inst, xrResultToString);
    char buf[XR_MAX_RESULT_STRING_SIZE];
    CHECK_RESULT(xrResultToString(inst, XR_ERROR_SESSION_NOT_RUNNING, buf), XR_SUCCESS);
    CHECK(strcmp(buf, "XR_ERROR_SESSION_NOT_RUNNING") == 0);
    CHECK_RESULT(xrResultToString(inst, static_cast<XrResult>(-999999), buf), XR_SUCCESS);
    CHECK(strcmp(buf, "XR_UNKNOWN_FAILURE_999999") == 0);
    LOAD(inst, xrStructureTypeToString);
    char sbuf[XR_MAX_STRUCTURE_NAME_SIZE];
    CHECK_RESULT(xrStructureTypeToString(inst, XR_TYPE_SESSION_CREATE_INFO, sbuf), XR_SUCCESS);
    CHECK(strcmp(sbuf, "XR_TYPE_SESSION_CREATE_INFO") == 0);

    // an extension function of an extension that was not enabled is unsupported
    PFN_xrVoidFunction f = nullptr;
    CHECK_RESULT(gpa(inst, "xrConvertTimespecTimeToTimeKHR", &f), XR_ERROR_FUNCTION_UNSUPPORTED);
    CHECK(f == nullptr);
    CHECK_RESULT(gpa(inst, "xrNotAFunction", &f), XR_ERROR_FUNCTION_UNSUPPORTED);
}

static void testPaths(XrInstance inst) {
    LOAD(inst, xrStringToPath);
    LOAD(inst, xrPathToString);
    XrPath a = 0, b = 0, c = 0;
    CHECK_RESULT(xrStringToPath(inst, "/user/hand/right", &a), XR_SUCCESS);
    CHECK_RESULT(xrStringToPath(inst, "/user/hand/right", &b), XR_SUCCESS);
    CHECK(a == b && a != XR_NULL_PATH);
    CHECK_RESULT(xrStringToPath(inst, "/user/hand/left", &c), XR_SUCCESS);
    CHECK(c != a);
    CHECK_RESULT(xrStringToPath(inst, "user/hand", &c), XR_ERROR_PATH_FORMAT_INVALID);
    CHECK_RESULT(xrStringToPath(inst, "/User/Hand", &c), XR_ERROR_PATH_FORMAT_INVALID);
    CHECK_RESULT(xrStringToPath(inst, "/user//hand", &c), XR_ERROR_PATH_FORMAT_INVALID);
    CHECK_RESULT(xrStringToPath(inst, "/user/../hand", &c), XR_ERROR_PATH_FORMAT_INVALID);
    CHECK_RESULT(xrStringToPath(inst, "/user/hand/", &c), XR_ERROR_PATH_FORMAT_INVALID);
    uint32_t n = 0;
    CHECK_RESULT(xrPathToString(inst, a, 0, &n, nullptr), XR_SUCCESS);
    CHECK(n == strlen("/user/hand/right") + 1);
    char s[64];
    CHECK_RESULT(xrPathToString(inst, a, 4, &n, s), XR_ERROR_SIZE_INSUFFICIENT);
    CHECK_RESULT(xrPathToString(inst, a, sizeof(s), &n, s), XR_SUCCESS);
    CHECK(strcmp(s, "/user/hand/right") == 0);
    CHECK_RESULT(xrPathToString(inst, 99999, sizeof(s), &n, s), XR_ERROR_PATH_INVALID);
}

static XrSystemId testSystem(XrInstance inst) {
    LOAD(inst, xrGetSystem);
    XrSystemGetInfo gi{XR_TYPE_SYSTEM_GET_INFO};
    gi.formFactor = XR_FORM_FACTOR_HANDHELD_DISPLAY;
    XrSystemId sys = 0;
    CHECK_RESULT(xrGetSystem(inst, &gi, &sys), XR_ERROR_FORM_FACTOR_UNSUPPORTED);
    gi.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    CHECK_RESULT(xrGetSystem(inst, &gi, &sys), XR_SUCCESS);
    CHECK(sys != XR_NULL_SYSTEM_ID);

    LOAD(inst, xrGetSystemProperties);
    XrSystemProperties sp{XR_TYPE_SYSTEM_PROPERTIES};
    CHECK_RESULT(xrGetSystemProperties(inst, sys, &sp), XR_SUCCESS);
    CHECK(sp.trackingProperties.orientationTracking == XR_TRUE);
    CHECK(sp.trackingProperties.positionTracking == XR_FALSE);
    CHECK_RESULT(xrGetSystemProperties(inst, sys + 7, &sp), XR_ERROR_SYSTEM_INVALID);

    LOAD(inst, xrEnumerateViewConfigurations);
    uint32_t n = 0;
    XrViewConfigurationType types[4];
    CHECK_RESULT(xrEnumerateViewConfigurations(inst, sys, 4, &n, types), XR_SUCCESS);
    CHECK(n == 1 && types[0] == XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO);

    LOAD(inst, xrEnumerateViewConfigurationViews);
    XrViewConfigurationView v[2] = {{XR_TYPE_VIEW_CONFIGURATION_VIEW}, {XR_TYPE_VIEW_CONFIGURATION_VIEW}};
    CHECK_RESULT(xrEnumerateViewConfigurationViews(inst, sys, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_MONO, 2, &n, v), XR_ERROR_VIEW_CONFIGURATION_TYPE_UNSUPPORTED);
    CHECK_RESULT(xrEnumerateViewConfigurationViews(inst, sys, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 2, &n, v), XR_SUCCESS);
    CHECK(n == 2 && v[0].recommendedImageRectWidth > 0 && v[1].recommendedImageRectHeight > 0);

    LOAD(inst, xrEnumerateEnvironmentBlendModes);
    XrEnvironmentBlendMode modes[4];
    CHECK_RESULT(xrEnumerateEnvironmentBlendModes(inst, sys, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 4, &n, modes), XR_SUCCESS);
    CHECK(n == 2 && modes[0] == XR_ENVIRONMENT_BLEND_MODE_OPAQUE && modes[1] == XR_ENVIRONMENT_BLEND_MODE_ALPHA_BLEND);
    return sys;
}

static bool nextState(XrInstance inst, XrSessionState* st) {
    static PFN_xrPollEvent poll = nullptr;
    if (!poll) poll = fn<PFN_xrPollEvent>(inst, "xrPollEvent");
    XrEventDataBuffer ev{XR_TYPE_EVENT_DATA_BUFFER};
    while (poll(inst, &ev) == XR_SUCCESS) {
        if (ev.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            *st = reinterpret_cast<XrEventDataSessionStateChanged&>(ev).state;
            return true;
        }
        ev = {XR_TYPE_EVENT_DATA_BUFFER};
    }
    return false;
}

static void testSession(XrInstance inst, XrSystemId sys) {
    LOAD(inst, xrCreateSession);
    LOAD(inst, xrBeginSession);
    LOAD(inst, xrEndSession);
    LOAD(inst, xrRequestExitSession);
    LOAD(inst, xrDestroySession);
    LOAD(inst, xrWaitFrame);
    LOAD(inst, xrBeginFrame);
    LOAD(inst, xrEndFrame);

    XrSessionCreateInfo ci{XR_TYPE_SESSION_CREATE_INFO};
    ci.systemId = sys;
    XrSession s = XR_NULL_HANDLE;
    CHECK_RESULT(xrCreateSession(inst, &ci, &s), XR_SUCCESS);
    XrSessionState st;
    CHECK(nextState(inst, &st) && st == XR_SESSION_STATE_IDLE);
    CHECK(nextState(inst, &st) && st == XR_SESSION_STATE_READY);

    XrFrameState fs{XR_TYPE_FRAME_STATE};
    CHECK_RESULT(xrWaitFrame(s, nullptr, &fs), XR_ERROR_SESSION_NOT_RUNNING);
    XrSessionBeginInfo bi{XR_TYPE_SESSION_BEGIN_INFO};
    bi.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    CHECK_RESULT(xrBeginSession(s, &bi), XR_SUCCESS);
    CHECK_RESULT(xrBeginSession(s, &bi), XR_ERROR_SESSION_RUNNING);
    CHECK(nextState(inst, &st) && st == XR_SESSION_STATE_SYNCHRONIZED);

    // frame loop ordering
    CHECK_RESULT(xrBeginFrame(s, nullptr), XR_ERROR_CALL_ORDER_INVALID);
    XrFrameEndInfo ei{XR_TYPE_FRAME_END_INFO};
    ei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    XrTime last = 0;
    for (int i = 0; i < 4; i++) {
        CHECK_RESULT(xrWaitFrame(s, nullptr, &fs), XR_SUCCESS);
        CHECK(fs.predictedDisplayTime > last);
        CHECK(fs.predictedDisplayPeriod > 0);
        last = fs.predictedDisplayTime;
        CHECK_RESULT(xrBeginFrame(s, nullptr), XR_SUCCESS);
        ei.displayTime = fs.predictedDisplayTime;
        CHECK_RESULT(xrEndFrame(s, &ei), XR_SUCCESS);
    }
    CHECK_RESULT(xrEndFrame(s, &ei), XR_ERROR_CALL_ORDER_INVALID);
    CHECK(nextState(inst, &st) && st == XR_SESSION_STATE_VISIBLE);
    CHECK(nextState(inst, &st) && st == XR_SESSION_STATE_FOCUSED);
    CHECK(fs.shouldRender == XR_TRUE);
    // a begun frame that was never ended is discarded by the next xrBeginFrame
    CHECK_RESULT(xrWaitFrame(s, nullptr, &fs), XR_SUCCESS);
    CHECK_RESULT(xrBeginFrame(s, nullptr), XR_SUCCESS);
    CHECK_RESULT(xrWaitFrame(s, nullptr, &fs), XR_SUCCESS);
    CHECK_RESULT(xrBeginFrame(s, nullptr), XR_FRAME_DISCARDED);
    ei.displayTime = fs.predictedDisplayTime;
    ei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_ADDITIVE;
    CHECK_RESULT(xrEndFrame(s, &ei), XR_ERROR_ENVIRONMENT_BLEND_MODE_UNSUPPORTED);
    ei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    CHECK_RESULT(xrEndFrame(s, &ei), XR_SUCCESS);

    // exit
    CHECK_RESULT(xrEndSession(s), XR_ERROR_SESSION_NOT_STOPPING);
    CHECK_RESULT(xrRequestExitSession(s), XR_SUCCESS);
    CHECK(nextState(inst, &st) && st == XR_SESSION_STATE_VISIBLE);
    CHECK(nextState(inst, &st) && st == XR_SESSION_STATE_SYNCHRONIZED);
    CHECK(nextState(inst, &st) && st == XR_SESSION_STATE_STOPPING);
    CHECK_RESULT(xrEndSession(s), XR_SUCCESS);
    CHECK(nextState(inst, &st) && st == XR_SESSION_STATE_IDLE);
    CHECK(nextState(inst, &st) && st == XR_SESSION_STATE_EXITING);
    CHECK_RESULT(xrDestroySession(s), XR_SUCCESS);
    CHECK_RESULT(xrDestroySession(s), XR_ERROR_HANDLE_INVALID);
}

static void testSpacesAndViews(XrInstance inst, XrSystemId sys) {
    LOAD(inst, xrCreateSession);
    LOAD(inst, xrDestroySession);
    LOAD(inst, xrCreateReferenceSpace);
    LOAD(inst, xrLocateSpace);
    LOAD(inst, xrLocateViews);
    LOAD(inst, xrDestroySpace);
    LOAD(inst, xrEnumerateReferenceSpaces);
    XrSessionCreateInfo ci{XR_TYPE_SESSION_CREATE_INFO};
    ci.systemId = sys;
    XrSession s;
    CHECK_RESULT(xrCreateSession(inst, &ci, &s), XR_SUCCESS);

    uint32_t n = 0;
    XrReferenceSpaceType types[8];
    CHECK_RESULT(xrEnumerateReferenceSpaces(s, 8, &n, types), XR_SUCCESS);
    CHECK(n >= 3);

    XrReferenceSpaceCreateInfo rci{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    rci.poseInReferenceSpace.orientation.w = 1;
    XrSpace local, stage, view;
    rci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL; CHECK_RESULT(xrCreateReferenceSpace(s, &rci, &local), XR_SUCCESS);
    rci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_STAGE; CHECK_RESULT(xrCreateReferenceSpace(s, &rci, &stage), XR_SUCCESS);
    rci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW; CHECK_RESULT(xrCreateReferenceSpace(s, &rci, &view), XR_SUCCESS);
    XrSpace bad;
    rci.poseInReferenceSpace.orientation.w = 3;
    CHECK_RESULT(xrCreateReferenceSpace(s, &rci, &bad), XR_ERROR_POSE_INVALID);

    // LOCAL sits at eye height above STAGE (the floor)
    XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
    CHECK_RESULT(xrLocateSpace(local, stage, 1000, &loc), XR_SUCCESS);
    CHECK(NEAR(loc.pose.position.y, 1.6, 1e-5));
    CHECK(loc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT);
    CHECK_RESULT(xrLocateSpace(local, stage, 0, &loc), XR_ERROR_TIME_INVALID);

    // head turned 90 degrees left: VIEW's forward (-Z) points to -X in LOCAL
    float h = sqrtf(0.5f);
    hxTestSetHead(0, h, 0, h, 1);
    CHECK_RESULT(xrLocateSpace(view, local, 1000, &loc), XR_SUCCESS);
    float fx = -2 * (loc.pose.orientation.x * loc.pose.orientation.z + loc.pose.orientation.w * loc.pose.orientation.y);
    CHECK(NEAR(fx, -1.0, 1e-4));
    CHECK(loc.locationFlags & XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT);
    CHECK(!(loc.locationFlags & XR_SPACE_LOCATION_POSITION_TRACKED_BIT));  // 3DoF: position is a model

    // views are separated by the IPD along the head's right axis
    XrViewLocateInfo vli{XR_TYPE_VIEW_LOCATE_INFO};
    vli.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    vli.displayTime = 1000;
    vli.space = local;
    XrViewState vs{XR_TYPE_VIEW_STATE};
    XrView views[2] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
    CHECK_RESULT(xrLocateViews(s, &vli, &vs, 2, &n, views), XR_SUCCESS);
    CHECK(n == 2);
    float dx = views[1].pose.position.x - views[0].pose.position.x;
    float dz = views[1].pose.position.z - views[0].pose.position.z;
    CHECK(NEAR(sqrtf(dx * dx + dz * dz), 0.064, 1e-4));
    CHECK(NEAR(dz, -0.064, 1e-4));  // head faces -X, so its right axis is -Z
    CHECK(views[0].fov.angleLeft < 0 && views[0].fov.angleRight > 0 && views[0].fov.angleUp > 0 && views[0].fov.angleDown < 0);
    CHECK(vs.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT);

    // lost tracking: no valid flags, never garbage
    hxTestSetHead(0, 0, 0, 1, 0);
    CHECK_RESULT(xrLocateViews(s, &vli, &vs, 2, &n, views), XR_SUCCESS);
    CHECK(vs.viewStateFlags == 0);
    CHECK_RESULT(xrLocateSpace(view, local, 1000, &loc), XR_SUCCESS);
    CHECK(loc.locationFlags == 0);
    hxTestSetHead(0, 0, 0, 1, 1);

    CHECK_RESULT(xrDestroySpace(view), XR_SUCCESS);
    CHECK_RESULT(xrDestroySpace(view), XR_ERROR_HANDLE_INVALID);
    xrDestroySpace(local); xrDestroySpace(stage);
    CHECK_RESULT(xrDestroySession(s), XR_SUCCESS);
}

static void testActions(XrInstance inst, XrSystemId sys) {
    LOAD(inst, xrCreateActionSet);
    LOAD(inst, xrCreateAction);
    LOAD(inst, xrStringToPath);
    LOAD(inst, xrSuggestInteractionProfileBindings);
    LOAD(inst, xrAttachSessionActionSets);
    LOAD(inst, xrSyncActions);
    LOAD(inst, xrGetActionStateBoolean);
    LOAD(inst, xrGetActionStateFloat);
    LOAD(inst, xrCreateSession);
    LOAD(inst, xrDestroySession);
    LOAD(inst, xrGetCurrentInteractionProfile);
    LOAD(inst, xrCreateActionSpace);

    XrActionSetCreateInfo sci{XR_TYPE_ACTION_SET_CREATE_INFO};
    strcpy(sci.actionSetName, "Bad Name");
    strcpy(sci.localizedActionSetName, "Gameplay");
    XrActionSet set;
    CHECK_RESULT(xrCreateActionSet(inst, &sci, &set), XR_ERROR_PATH_FORMAT_INVALID);
    strcpy(sci.actionSetName, "gameplay");
    CHECK_RESULT(xrCreateActionSet(inst, &sci, &set), XR_SUCCESS);

    XrPath right, profile, binding;
    xrStringToPath(inst, "/user/hand/right", &right);
    xrStringToPath(inst, "/interaction_profiles/khr/simple_controller", &profile);
    xrStringToPath(inst, "/user/hand/right/input/select/click", &binding);

    XrActionCreateInfo aci{XR_TYPE_ACTION_CREATE_INFO};
    strcpy(aci.actionName, "select");
    strcpy(aci.localizedActionName, "Select");
    aci.actionType = XR_ACTION_TYPE_BOOLEAN_INPUT;
    aci.countSubactionPaths = 1;
    aci.subactionPaths = &right;
    XrAction select, grip;
    CHECK_RESULT(xrCreateAction(set, &aci, &select), XR_SUCCESS);
    strcpy(aci.actionName, "grip");
    strcpy(aci.localizedActionName, "Grip");
    aci.actionType = XR_ACTION_TYPE_POSE_INPUT;
    CHECK_RESULT(xrCreateAction(set, &aci, &grip), XR_SUCCESS);

    XrActionSuggestedBinding sb{select, binding};
    XrInteractionProfileSuggestedBinding ipsb{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    ipsb.interactionProfile = profile;
    ipsb.countSuggestedBindings = 1;
    ipsb.suggestedBindings = &sb;
    CHECK_RESULT(xrSuggestInteractionProfileBindings(inst, &ipsb), XR_SUCCESS);

    XrSessionCreateInfo ci{XR_TYPE_SESSION_CREATE_INFO};
    ci.systemId = sys;
    XrSession s;
    CHECK_RESULT(xrCreateSession(inst, &ci, &s), XR_SUCCESS);

    XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
    gi.action = select;
    XrActionStateBoolean b{XR_TYPE_ACTION_STATE_BOOLEAN};
    CHECK_RESULT(xrGetActionStateBoolean(s, &gi, &b), XR_ERROR_ACTIONSET_NOT_ATTACHED);

    XrSessionActionSetsAttachInfo ai{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    ai.countActionSets = 1;
    ai.actionSets = &set;
    CHECK_RESULT(xrAttachSessionActionSets(s, &ai), XR_SUCCESS);
    CHECK_RESULT(xrAttachSessionActionSets(s, &ai), XR_ERROR_ACTIONSETS_ALREADY_ATTACHED);
    CHECK_RESULT(xrCreateAction(set, &aci, &grip), XR_ERROR_ACTIONSETS_ALREADY_ATTACHED);

    XrActiveActionSet active{set, XR_NULL_PATH};
    XrActionsSyncInfo si{XR_TYPE_ACTIONS_SYNC_INFO};
    si.countActiveActionSets = 1;
    si.activeActionSets = &active;
    CHECK_RESULT(xrSyncActions(s, &si), XR_SESSION_NOT_FOCUSED);  // session not running yet

    CHECK_RESULT(xrGetActionStateBoolean(s, &gi, &b), XR_SUCCESS);
    CHECK(b.isActive == XR_FALSE && b.currentState == XR_FALSE);
    XrActionStateFloat f{XR_TYPE_ACTION_STATE_FLOAT};
    CHECK_RESULT(xrGetActionStateFloat(s, &gi, &f), XR_ERROR_ACTION_TYPE_MISMATCH);
    XrPath left;
    xrStringToPath(inst, "/user/hand/left", &left);
    gi.subactionPath = left;
    CHECK_RESULT(xrGetActionStateBoolean(s, &gi, &b), XR_ERROR_PATH_UNSUPPORTED);

    XrInteractionProfileState ips{XR_TYPE_INTERACTION_PROFILE_STATE};
    CHECK_RESULT(xrGetCurrentInteractionProfile(s, right, &ips), XR_SUCCESS);
    CHECK(ips.interactionProfile == profile);  // the only profile the app suggested

    XrActionSpaceCreateInfo asci{XR_TYPE_ACTION_SPACE_CREATE_INFO};
    asci.action = select;
    asci.poseInActionSpace.orientation.w = 1;
    XrSpace sp;
    CHECK_RESULT(xrCreateActionSpace(s, &asci, &sp), XR_ERROR_ACTION_TYPE_MISMATCH);
    asci.action = grip;
    CHECK_RESULT(xrCreateActionSpace(s, &asci, &sp), XR_SUCCESS);

    CHECK_RESULT(xrDestroySession(s), XR_SUCCESS);
}

/** Gamepad/touch input reaching the app's actions through the Touch controller profile. */
static void testActionInput(XrInstance inst, XrSystemId sys) {
    LOAD(inst, xrCreateActionSet); LOAD(inst, xrCreateAction); LOAD(inst, xrStringToPath);
    LOAD(inst, xrSuggestInteractionProfileBindings); LOAD(inst, xrAttachSessionActionSets); LOAD(inst, xrSyncActions);
    LOAD(inst, xrGetActionStateBoolean); LOAD(inst, xrGetActionStateFloat); LOAD(inst, xrGetActionStateVector2f);
    LOAD(inst, xrGetActionStatePose); LOAD(inst, xrCreateSession); LOAD(inst, xrDestroySession); LOAD(inst, xrBeginSession);
    LOAD(inst, xrWaitFrame); LOAD(inst, xrBeginFrame); LOAD(inst, xrEndFrame); LOAD(inst, xrGetCurrentInteractionProfile);
    LOAD(inst, xrCreateActionSpace); LOAD(inst, xrCreateReferenceSpace); LOAD(inst, xrLocateSpace); LOAD(inst, xrDestroyActionSet);
    LOAD(inst, xrEnumerateBoundSourcesForAction);

    XrActionSetCreateInfo sci{XR_TYPE_ACTION_SET_CREATE_INFO};
    strcpy(sci.actionSetName, "game"); strcpy(sci.localizedActionSetName, "Game");
    XrActionSet set;
    CHECK_RESULT(xrCreateActionSet(inst, &sci, &set), XR_SUCCESS);
    XrPath hands[2], touch, simple;
    xrStringToPath(inst, "/user/hand/left", &hands[0]);
    xrStringToPath(inst, "/user/hand/right", &hands[1]);
    xrStringToPath(inst, "/interaction_profiles/oculus/touch_controller", &touch);
    xrStringToPath(inst, "/interaction_profiles/khr/simple_controller", &simple);
    auto make = [&](const char* name, XrActionType t) {
        XrActionCreateInfo aci{XR_TYPE_ACTION_CREATE_INFO};
        strcpy(aci.actionName, name); strcpy(aci.localizedActionName, name);
        aci.actionType = t; aci.countSubactionPaths = 2; aci.subactionPaths = hands;
        XrAction a = XR_NULL_HANDLE;
        CHECK_RESULT(xrCreateAction(set, &aci, &a), XR_SUCCESS);
        return a;
    };
    XrAction fire = make("fire", XR_ACTION_TYPE_FLOAT_INPUT);
    XrAction jump = make("jump", XR_ACTION_TYPE_BOOLEAN_INPUT);
    XrAction move = make("move", XR_ACTION_TYPE_VECTOR2F_INPUT);
    XrAction aim = make("aim", XR_ACTION_TYPE_POSE_INPUT);
    auto path = [&](const char* p) { XrPath x; xrStringToPath(inst, p, &x); return x; };
    XrActionSuggestedBinding tb[] = {
        {fire, path("/user/hand/right/input/trigger/value")}, {fire, path("/user/hand/left/input/trigger/value")},
        {jump, path("/user/hand/right/input/a/click")}, {move, path("/user/hand/left/input/thumbstick")},
        {aim, path("/user/hand/right/input/aim/pose")}, {aim, path("/user/hand/left/input/aim/pose")},
    };
    XrInteractionProfileSuggestedBinding ipsb{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    ipsb.interactionProfile = touch; ipsb.countSuggestedBindings = 6; ipsb.suggestedBindings = tb;
    CHECK_RESULT(xrSuggestInteractionProfileBindings(inst, &ipsb), XR_SUCCESS);
    XrActionSuggestedBinding sbind[] = {{jump, path("/user/hand/right/input/select/click")}};
    ipsb.interactionProfile = simple; ipsb.countSuggestedBindings = 1; ipsb.suggestedBindings = sbind;
    CHECK_RESULT(xrSuggestInteractionProfileBindings(inst, &ipsb), XR_SUCCESS);

    XrSessionCreateInfo ci{XR_TYPE_SESSION_CREATE_INFO};
    ci.systemId = sys;
    XrSession s;
    CHECK_RESULT(xrCreateSession(inst, &ci, &s), XR_SUCCESS);
    XrSessionActionSetsAttachInfo ai{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    ai.countActionSets = 1; ai.actionSets = &set;
    CHECK_RESULT(xrAttachSessionActionSets(s, &ai), XR_SUCCESS);
    XrInteractionProfileState ips{XR_TYPE_INTERACTION_PROFILE_STATE};
    CHECK_RESULT(xrGetCurrentInteractionProfile(s, hands[1], &ips), XR_SUCCESS);
    CHECK(ips.interactionProfile == touch);  // Touch preferred over simple

    // run until focused
    XrSessionBeginInfo bi{XR_TYPE_SESSION_BEGIN_INFO};
    bi.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    CHECK_RESULT(xrBeginSession(s, &bi), XR_SUCCESS);
    XrFrameState fs{XR_TYPE_FRAME_STATE};
    XrFrameEndInfo ei{XR_TYPE_FRAME_END_INFO};
    ei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    for (int i = 0; i < 3; i++) {
        xrWaitFrame(s, nullptr, &fs); xrBeginFrame(s, nullptr);
        ei.displayTime = fs.predictedDisplayTime; xrEndFrame(s, &ei);
    }
    XrActiveActionSet active{set, XR_NULL_PATH};
    XrActionsSyncInfo si{XR_TYPE_ACTIONS_SYNC_INFO};
    si.countActiveActionSets = 1; si.activeActionSets = &active;

    hxTestSetInput(0, 0.5f, -1.0f, 0, 0, 0.25f, 0.0f, 0);
    CHECK_RESULT(xrSyncActions(s, &si), XR_SUCCESS);
    XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
    XrActionStateFloat f{XR_TYPE_ACTION_STATE_FLOAT};
    gi.action = fire;
    CHECK_RESULT(xrGetActionStateFloat(s, &gi, &f), XR_SUCCESS);
    CHECK(f.isActive && NEAR(f.currentState, 0.25, 1e-6));  // any hand: the largest value
    gi.subactionPath = hands[1];
    CHECK_RESULT(xrGetActionStateFloat(s, &gi, &f), XR_SUCCESS);
    CHECK(f.isActive && f.currentState == 0);
    XrActionStateVector2f v{XR_TYPE_ACTION_STATE_VECTOR2F};
    gi.action = move; gi.subactionPath = hands[0];
    CHECK_RESULT(xrGetActionStateVector2f(s, &gi, &v), XR_SUCCESS);
    CHECK(v.isActive && NEAR(v.currentState.x, 0.5, 1e-6) && NEAR(v.currentState.y, -1.0, 1e-6));
    gi.subactionPath = hands[1];  // nothing bound on the right stick
    CHECK_RESULT(xrGetActionStateVector2f(s, &gi, &v), XR_SUCCESS);
    CHECK(!v.isActive);

    // A pressed, then the screen touch pulls the right trigger
    XrActionStateBoolean b{XR_TYPE_ACTION_STATE_BOOLEAN};
    gi.action = jump; gi.subactionPath = XR_NULL_PATH;
    CHECK_RESULT(xrGetActionStateBoolean(s, &gi, &b), XR_SUCCESS);
    CHECK(b.isActive && !b.currentState && !b.changedSinceLastSync);
    hxTestSetInput(1u /*A*/, 0, 0, 0, 0, 0, 0, 1);
    CHECK_RESULT(xrSyncActions(s, &si), XR_SUCCESS);
    CHECK_RESULT(xrGetActionStateBoolean(s, &gi, &b), XR_SUCCESS);
    CHECK(b.currentState && b.changedSinceLastSync && b.lastChangeTime > 0);
    gi.action = fire; gi.subactionPath = hands[1];
    CHECK_RESULT(xrGetActionStateFloat(s, &gi, &f), XR_SUCCESS);
    CHECK(NEAR(f.currentState, 1.0, 1e-6) && f.changedSinceLastSync);
    CHECK_RESULT(xrSyncActions(s, &si), XR_SUCCESS);
    CHECK_RESULT(xrGetActionStateFloat(s, &gi, &f), XR_SUCCESS);
    CHECK(!f.changedSinceLastSync);

    uint32_t nsrc = 0;
    XrBoundSourcesForActionEnumerateInfo bsi{XR_TYPE_BOUND_SOURCES_FOR_ACTION_ENUMERATE_INFO};
    bsi.action = fire;
    CHECK_RESULT(xrEnumerateBoundSourcesForAction(s, &bsi, 0, &nsrc, nullptr), XR_SUCCESS);
    CHECK(nsrc == 2);

    // the aim pose rides with the head, right hand to the right and below the eyes
    XrActionStatePose ps{XR_TYPE_ACTION_STATE_POSE};
    gi.action = aim; gi.subactionPath = hands[1];
    CHECK_RESULT(xrGetActionStatePose(s, &gi, &ps), XR_SUCCESS);
    CHECK(ps.isActive);
    XrActionSpaceCreateInfo asci{XR_TYPE_ACTION_SPACE_CREATE_INFO};
    asci.action = aim; asci.subactionPath = hands[1]; asci.poseInActionSpace.orientation.w = 1;
    XrSpace aimSpace, view;
    CHECK_RESULT(xrCreateActionSpace(s, &asci, &aimSpace), XR_SUCCESS);
    XrReferenceSpaceCreateInfo rci{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    rci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW; rci.poseInReferenceSpace.orientation.w = 1;
    CHECK_RESULT(xrCreateReferenceSpace(s, &rci, &view), XR_SUCCESS);
    hxTestSetHead(0, 0, 0, 1, 1);
    XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
    CHECK_RESULT(xrLocateSpace(aimSpace, view, fs.predictedDisplayTime, &loc), XR_SUCCESS);
    CHECK((loc.locationFlags & XR_SPACE_LOCATION_ORIENTATION_TRACKED_BIT) && (loc.locationFlags & XR_SPACE_LOCATION_POSITION_VALID_BIT));
    CHECK(loc.pose.position.x > 0.1f && loc.pose.position.y < -0.2f && loc.pose.position.z < -0.2f);

    hxTestSetInput(0, 0, 0, 0, 0, 0, 0, 0);
    CHECK_RESULT(xrDestroySession(s), XR_SUCCESS);
    CHECK_RESULT(xrDestroyActionSet(set), XR_SUCCESS);
}

static int64_t monoNs() {
    struct timespec ts;
    clock_gettime(CLOCK_MONOTONIC, &ts);
    return (int64_t)ts.tv_sec * 1000000000LL + ts.tv_nsec;
}

/** A right hand held out in front, palm down, fingers forward (camera space: -z forward, y up). */
static void rightHandPalmDown(float lm[21][3]) {
    const float wrist[3] = {0.10f, -0.30f, -0.30f};
    // thumb on the left of a right hand seen from above; index .. little from left to right
    const float fingerX[4] = {-0.030f, -0.010f, 0.010f, 0.030f};
    for (int k = 0; k < 3; k++) lm[0][k] = wrist[k];
    for (int t = 0; t < 4; t++) { lm[1 + t][0] = wrist[0] - 0.03f - 0.012f * t; lm[1 + t][1] = wrist[1]; lm[1 + t][2] = wrist[2] - 0.02f - 0.025f * t; }
    for (int f = 0; f < 4; f++)
        for (int j = 0; j < 4; j++) {
            float* p = lm[5 + f * 4 + j];
            p[0] = wrist[0] + fingerX[f]; p[1] = wrist[1]; p[2] = wrist[2] - 0.09f - 0.03f * j;
        }
}

static void testHands() {
    const char* exts[] = {XR_MND_HEADLESS_EXTENSION_NAME, XR_EXT_HAND_TRACKING_EXTENSION_NAME};
    XrResult r;
    XrInstance inst = createInstance(XR_MAKE_VERSION(1, 0, 0), exts, 2, &r);
    CHECK(r == XR_SUCCESS);
    if (r != XR_SUCCESS) return;
    LOAD(inst, xrGetSystem);
    LOAD(inst, xrGetSystemProperties);
    LOAD(inst, xrCreateSession);
    LOAD(inst, xrDestroySession);
    LOAD(inst, xrDestroyInstance);
    LOAD(inst, xrCreateReferenceSpace);
    LOAD(inst, xrCreateHandTrackerEXT);
    LOAD(inst, xrDestroyHandTrackerEXT);
    LOAD(inst, xrLocateHandJointsEXT);
    XrSystemGetInfo sgi{XR_TYPE_SYSTEM_GET_INFO};
    sgi.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId sys = 0;
    CHECK_RESULT(xrGetSystem(inst, &sgi, &sys), XR_SUCCESS);
    XrSystemHandTrackingPropertiesEXT htp{XR_TYPE_SYSTEM_HAND_TRACKING_PROPERTIES_EXT};
    XrSystemProperties sp{XR_TYPE_SYSTEM_PROPERTIES};
    sp.next = &htp;
    CHECK_RESULT(xrGetSystemProperties(inst, sys, &sp), XR_SUCCESS);
    CHECK(htp.supportsHandTracking == XR_TRUE);
    XrSessionCreateInfo ci{XR_TYPE_SESSION_CREATE_INFO};
    ci.systemId = sys;
    XrSession s = XR_NULL_HANDLE;
    CHECK_RESULT(xrCreateSession(inst, &ci, &s), XR_SUCCESS);
    XrReferenceSpaceCreateInfo rci{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    rci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_STAGE;
    rci.poseInReferenceSpace.orientation.w = 1;
    XrSpace stage;
    CHECK_RESULT(xrCreateReferenceSpace(s, &rci, &stage), XR_SUCCESS);
    hxTestSetHead(0, 0, 0, 1, 1);

    XrHandTrackerCreateInfoEXT hci{XR_TYPE_HAND_TRACKER_CREATE_INFO_EXT};
    hci.handJointSet = XR_HAND_JOINT_SET_DEFAULT_EXT;
    hci.hand = (XrHandEXT)7;
    XrHandTrackerEXT bad;
    CHECK_RESULT(xrCreateHandTrackerEXT(s, &hci, &bad), XR_ERROR_VALIDATION_FAILURE);
    XrHandTrackerEXT left, right;
    hci.hand = XR_HAND_LEFT_EXT;
    CHECK_RESULT(xrCreateHandTrackerEXT(s, &hci, &left), XR_SUCCESS);
    hci.hand = XR_HAND_RIGHT_EXT;
    CHECK_RESULT(xrCreateHandTrackerEXT(s, &hci, &right), XR_SUCCESS);

    XrHandJointLocationEXT joints[XR_HAND_JOINT_COUNT_EXT];
    XrHandJointLocationsEXT locs{XR_TYPE_HAND_JOINT_LOCATIONS_EXT};
    locs.jointCount = XR_HAND_JOINT_COUNT_EXT;
    locs.jointLocations = joints;
    XrHandJointsLocateInfoEXT li{XR_TYPE_HAND_JOINTS_LOCATE_INFO_EXT};
    li.baseSpace = stage;
    li.time = monoNs();

    // no HyperMagic: no hands, but no error
    hxTestSetHands(nullptr);
    CHECK_RESULT(xrLocateHandJointsEXT(right, &li, &locs), XR_SUCCESS);
    CHECK(locs.isActive == XR_FALSE && joints[XR_HAND_JOINT_WRIST_EXT].locationFlags == 0);

    HxHandShared h{};
    h.magic = HX_HANDS_MAGIC;
    rightHandPalmDown(h.joints[1]);
    h.tracked[1] = 1; h.confidence[1] = 0.9f; h.pinch[1] = 0.95f; h.timeNs[1] = monoNs();
    hxTestSetHands(&h);
    CHECK_RESULT(xrLocateHandJointsEXT(right, &li, &locs), XR_SUCCESS);
    CHECK(locs.isActive == XR_TRUE);
    CHECK(hxTestHandsWanted() > 0);
    // camera 8 cm in front of the eyes (1.6 m), head straight: wrist at (0.10, 1.30, -0.38)
    XrPosef w = joints[XR_HAND_JOINT_WRIST_EXT].pose;
    CHECK(NEAR(w.position.x, 0.10, 1e-4) && NEAR(w.position.y, 1.30, 1e-4) && NEAR(w.position.z, -0.38, 1e-4));
    CHECK(joints[XR_HAND_JOINT_INDEX_TIP_EXT].locationFlags & XR_SPACE_LOCATION_POSITION_TRACKED_BIT);
    CHECK(NEAR(joints[XR_HAND_JOINT_INDEX_TIP_EXT].pose.position.z, -0.38 - 0.18, 1e-4));
    // OpenXR joint axes: +Y out of the back of the hand (up, palm down), +Z back along the finger
    auto axis = [](XrQuaternionf q, float x, float y, float z, float out[3]) {
        float tx = 2 * (q.y * z - q.z * y), ty = 2 * (q.z * x - q.x * z), tz = 2 * (q.x * y - q.y * x);
        out[0] = x + q.w * tx + (q.y * tz - q.z * ty); out[1] = y + q.w * ty + (q.z * tx - q.x * tz); out[2] = z + q.w * tz + (q.x * ty - q.y * tx);
    };
    float up[3], back[3];
    axis(joints[XR_HAND_JOINT_INDEX_PROXIMAL_EXT].pose.orientation, 0, 1, 0, up);
    axis(joints[XR_HAND_JOINT_INDEX_PROXIMAL_EXT].pose.orientation, 0, 0, 1, back);
    CHECK(up[1] > 0.99f);
    CHECK(back[2] > 0.99f);
    axis(joints[XR_HAND_JOINT_PALM_EXT].pose.orientation, 0, 1, 0, up);
    CHECK(up[1] > 0.99f);
    CHECK(joints[XR_HAND_JOINT_PALM_EXT].radius > joints[XR_HAND_JOINT_INDEX_TIP_EXT].radius);
    // the left hand is not tracked
    CHECK_RESULT(xrLocateHandJointsEXT(left, &li, &locs), XR_SUCCESS);
    CHECK(locs.isActive == XR_FALSE);
    // hands too old are dropped
    h.timeNs[1] = monoNs() - 500000000LL;
    hxTestSetHands(&h);
    CHECK_RESULT(xrLocateHandJointsEXT(right, &li, &locs), XR_SUCCESS);
    CHECK(locs.isActive == XR_FALSE);
    // a mirrored (left) hand gets the back of the hand up too
    HxHandShared lh = h;
    for (int j = 0; j < 21; j++) { lh.joints[0][j][0] = -h.joints[1][j][0]; lh.joints[0][j][1] = h.joints[1][j][1]; lh.joints[0][j][2] = h.joints[1][j][2]; }
    lh.tracked[0] = 1; lh.timeNs[0] = monoNs(); lh.tracked[1] = 0;
    hxTestSetHands(&lh);
    CHECK_RESULT(xrLocateHandJointsEXT(left, &li, &locs), XR_SUCCESS);
    CHECK(locs.isActive == XR_TRUE);
    axis(joints[XR_HAND_JOINT_MIDDLE_PROXIMAL_EXT].pose.orientation, 0, 1, 0, up);
    CHECK(up[1] > 0.99f);
    // validation
    locs.jointCount = 21;
    CHECK_RESULT(xrLocateHandJointsEXT(left, &li, &locs), XR_ERROR_VALIDATION_FAILURE);
    locs.jointCount = XR_HAND_JOINT_COUNT_EXT;
    li.time = 0;
    CHECK_RESULT(xrLocateHandJointsEXT(left, &li, &locs), XR_ERROR_TIME_INVALID);
    CHECK_RESULT(xrDestroyHandTrackerEXT(left), XR_SUCCESS);
    CHECK_RESULT(xrDestroyHandTrackerEXT(left), XR_ERROR_HANDLE_INVALID);
    hxTestSetHands(nullptr);
    CHECK_RESULT(xrDestroySession(s), XR_SUCCESS);  // frees the right tracker
    CHECK_RESULT(xrDestroyInstance(inst), XR_SUCCESS);
}

/** Geometry of the cylinder and equirect layers (the compositor's shader does the same steps). */
static void testLayerShapes() {
    const float pi = 3.14159265f;
    float uv[2];
    // a cylinder of radius 2 m spanning 90 degrees, 1 m tall (width = 2 * pi/2 = pi m, aspect pi)
    HxComposeQuad cyl{};
    cyl.shape = HX_SHAPE_CYLINDER; cyl.radius = 2; cyl.centralAngle = pi / 2; cyl.aspectRatio = pi;
    float o[3] = {0, 0, 0};
    float ahead[3] = {0, 0, -1};
    CHECK(hxlayers::hit(cyl, o, ahead, uv) && NEAR(uv[0], 0.5, 1e-5) && NEAR(uv[1], 0.5, 1e-5));
    float right30[3] = {sinf(pi / 6), 0, -cosf(pi / 6)};  // 30 deg right: u = 30/90 + 0.5
    CHECK(hxlayers::hit(cyl, o, right30, uv) && NEAR(uv[0], 0.5 + 1.0 / 3.0, 1e-4));
    float right60[3] = {sinf(pi / 3), 0, -cosf(pi / 3)};  // outside the 90 degree arc
    CHECK(!hxlayers::hit(cyl, o, right60, uv));
    float up[3] = {0, 0.4f, -2};  // 0.4 m up at the wall (height 1 m): v = 0.9
    CHECK(hxlayers::hit(cyl, o, up, uv) && NEAR(uv[1], 0.9, 1e-4));
    float behind[3] = {0, 0, 1};
    CHECK(!hxlayers::hit(cyl, o, behind, uv));
    float off[3] = {0.5f, 0, 0};  // eye 0.5 m to the right still reaches the wall ahead
    CHECK(hxlayers::hit(cyl, off, ahead, uv) && uv[0] > 0.5f);
    // equirect 180 x 180, infinitely far: straight ahead is the centre, 45 deg up is v = 0.75
    HxComposeQuad eq{};
    eq.shape = HX_SHAPE_EQUIRECT; eq.radius = 0; eq.centralAngle = pi; eq.upperAngle = pi / 2; eq.lowerAngle = -pi / 2;
    CHECK(hxlayers::hit(eq, o, ahead, uv) && NEAR(uv[0], 0.5, 1e-5) && NEAR(uv[1], 0.5, 1e-5));
    float up45[3] = {0, 1, -1};
    CHECK(hxlayers::hit(eq, o, up45, uv) && NEAR(uv[1], 0.75, 1e-4));
    CHECK(!hxlayers::hit(eq, o, behind, uv));  // a 180 degree video has nothing behind
    // an infinitely far sphere ignores where the eye is; a 10 m one does not
    float far[3] = {5, 0, 0};
    float uvFar[2];
    CHECK(hxlayers::hit(eq, far, ahead, uvFar) && NEAR(uvFar[0], 0.5, 1e-5));
    eq.radius = 10;
    CHECK(hxlayers::hit(eq, far, ahead, uvFar) && uvFar[0] > 0.5f);
    // full 360: behind is the left and right edges
    eq.radius = 0; eq.centralAngle = 2 * pi;
    float justRightOfBehind[3] = {0.001f, 0, 1};
    CHECK(hxlayers::hit(eq, o, justRightOfBehind, uv) && uv[0] > 0.99f);
    // quad: 1 x 1 m, 2 m ahead
    HxComposeQuad quad{};
    quad.shape = HX_SHAPE_QUAD; quad.size = {1, 1};
    float oq[3] = {0, 0, 2};
    CHECK(hxlayers::hit(quad, oq, ahead, uv) && NEAR(uv[0], 0.5, 1e-5));
}

/** The runtime accepts cylinder / equirect2 layers only from apps that enabled them, and checks their numbers. */
static void testLayerValidation() {
    const char* exts[] = {XR_MND_HEADLESS_EXTENSION_NAME, XR_KHR_COMPOSITION_LAYER_CYLINDER_EXTENSION_NAME};
    XrResult r;
    XrInstance inst = createInstance(XR_MAKE_VERSION(1, 0, 0), exts, 2, &r);
    CHECK(r == XR_SUCCESS);
    if (r != XR_SUCCESS) return;
    LOAD(inst, xrGetSystem); LOAD(inst, xrCreateSession); LOAD(inst, xrDestroySession); LOAD(inst, xrDestroyInstance);
    LOAD(inst, xrBeginSession); LOAD(inst, xrWaitFrame); LOAD(inst, xrBeginFrame); LOAD(inst, xrEndFrame);
    LOAD(inst, xrCreateReferenceSpace);
    XrSystemGetInfo sgi{XR_TYPE_SYSTEM_GET_INFO};
    sgi.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId sys = 0;
    xrGetSystem(inst, &sgi, &sys);
    XrSessionCreateInfo ci{XR_TYPE_SESSION_CREATE_INFO};
    ci.systemId = sys;
    XrSession s = XR_NULL_HANDLE;
    CHECK_RESULT(xrCreateSession(inst, &ci, &s), XR_SUCCESS);
    XrSessionBeginInfo bi{XR_TYPE_SESSION_BEGIN_INFO};
    bi.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    CHECK_RESULT(xrBeginSession(s, &bi), XR_SUCCESS);
    XrReferenceSpaceCreateInfo rci{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    rci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    rci.poseInReferenceSpace.orientation.w = 1;
    XrSpace local;
    CHECK_RESULT(xrCreateReferenceSpace(s, &rci, &local), XR_SUCCESS);
    XrFrameState fs{XR_TYPE_FRAME_STATE};
    CHECK_RESULT(xrWaitFrame(s, nullptr, &fs), XR_SUCCESS);
    CHECK_RESULT(xrBeginFrame(s, nullptr), XR_SUCCESS);

    XrCompositionLayerCylinderKHR cyl{XR_TYPE_COMPOSITION_LAYER_CYLINDER_KHR};
    cyl.space = local;
    cyl.pose.orientation.w = 1;
    cyl.radius = 2; cyl.centralAngle = 1; cyl.aspectRatio = 2;
    cyl.subImage.swapchain = reinterpret_cast<XrSwapchain>(static_cast<uintptr_t>(0x1234));
    const XrCompositionLayerBaseHeader* layers[1] = {reinterpret_cast<const XrCompositionLayerBaseHeader*>(&cyl)};
    XrFrameEndInfo ei{XR_TYPE_FRAME_END_INFO};
    ei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    ei.displayTime = fs.predictedDisplayTime;
    ei.layerCount = 1; ei.layers = layers;
    CHECK_RESULT(xrEndFrame(s, &ei), XR_ERROR_HANDLE_INVALID);  // accepted as a layer; the swapchain is bogus
    cyl.aspectRatio = 0;
    CHECK_RESULT(xrEndFrame(s, &ei), XR_ERROR_VALIDATION_FAILURE);
    cyl.aspectRatio = 2; cyl.centralAngle = 7;
    CHECK_RESULT(xrEndFrame(s, &ei), XR_ERROR_VALIDATION_FAILURE);
    // equirect2 was not enabled by this app
    XrCompositionLayerEquirect2KHR eq{XR_TYPE_COMPOSITION_LAYER_EQUIRECT2_KHR};
    eq.space = local;
    eq.pose.orientation.w = 1;
    eq.centralHorizontalAngle = 3.14f; eq.upperVerticalAngle = 1; eq.lowerVerticalAngle = -1;
    layers[0] = reinterpret_cast<const XrCompositionLayerBaseHeader*>(&eq);
    CHECK_RESULT(xrEndFrame(s, &ei), XR_ERROR_LAYER_INVALID);
    ei.layerCount = 0;
    CHECK_RESULT(xrEndFrame(s, &ei), XR_SUCCESS);
    CHECK_RESULT(xrDestroySession(s), XR_SUCCESS);
    CHECK_RESULT(xrDestroyInstance(inst), XR_SUCCESS);

    // with equirect2 enabled its numbers are checked
    const char* exts2[] = {XR_MND_HEADLESS_EXTENSION_NAME, XR_KHR_COMPOSITION_LAYER_EQUIRECT2_EXTENSION_NAME};
    inst = createInstance(XR_MAKE_VERSION(1, 0, 0), exts2, 2, &r);
    CHECK(r == XR_SUCCESS);
    if (r != XR_SUCCESS) return;
    CHECK_RESULT(xrCreateSession(inst, &ci, &s), XR_SUCCESS);
    CHECK_RESULT(xrBeginSession(s, &bi), XR_SUCCESS);
    CHECK_RESULT(xrCreateReferenceSpace(s, &rci, &local), XR_SUCCESS);
    CHECK_RESULT(xrWaitFrame(s, nullptr, &fs), XR_SUCCESS);
    CHECK_RESULT(xrBeginFrame(s, nullptr), XR_SUCCESS);
    eq.space = local;
    eq.subImage.swapchain = reinterpret_cast<XrSwapchain>(static_cast<uintptr_t>(0x1234));
    ei.displayTime = fs.predictedDisplayTime;
    ei.layerCount = 1;
    CHECK_RESULT(xrEndFrame(s, &ei), XR_ERROR_HANDLE_INVALID);
    eq.lowerVerticalAngle = 1.5f;  // below the upper angle is required
    CHECK_RESULT(xrEndFrame(s, &ei), XR_ERROR_VALIDATION_FAILURE);
    eq.lowerVerticalAngle = -1; eq.eyeVisibility = (XrEyeVisibility)9;
    CHECK_RESULT(xrEndFrame(s, &ei), XR_ERROR_VALIDATION_FAILURE);
    ei.layerCount = 0;
    CHECK_RESULT(xrEndFrame(s, &ei), XR_SUCCESS);
    CHECK_RESULT(xrDestroySession(s), XR_SUCCESS);
    CHECK_RESULT(xrDestroyInstance(inst), XR_SUCCESS);
}

static void testHandInteraction() {
    const char* exts[] = {XR_MND_HEADLESS_EXTENSION_NAME, XR_EXT_HAND_INTERACTION_EXTENSION_NAME};
    XrResult r;
    XrInstance inst = createInstance(XR_MAKE_VERSION(1, 0, 0), exts, 2, &r);
    CHECK(r == XR_SUCCESS);
    if (r != XR_SUCCESS) return;
    LOAD(inst, xrGetSystem); LOAD(inst, xrGetSystemProperties); LOAD(inst, xrCreateSession); LOAD(inst, xrDestroySession);
    LOAD(inst, xrDestroyInstance); LOAD(inst, xrBeginSession); LOAD(inst, xrWaitFrame); LOAD(inst, xrBeginFrame); LOAD(inst, xrEndFrame);
    LOAD(inst, xrCreateActionSet); LOAD(inst, xrCreateAction); LOAD(inst, xrStringToPath); LOAD(inst, xrSuggestInteractionProfileBindings);
    LOAD(inst, xrAttachSessionActionSets); LOAD(inst, xrSyncActions); LOAD(inst, xrGetActionStateFloat); LOAD(inst, xrGetActionStateBoolean);
    LOAD(inst, xrCreateActionSpace); LOAD(inst, xrCreateReferenceSpace); LOAD(inst, xrLocateSpace); LOAD(inst, xrApplyHapticFeedback);
    LOAD(inst, xrStopHapticFeedback);
    XrSystemGetInfo sgi{XR_TYPE_SYSTEM_GET_INFO};
    sgi.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId sys = 0;
    xrGetSystem(inst, &sgi, &sys);

    XrActionSetCreateInfo sci{XR_TYPE_ACTION_SET_CREATE_INFO};
    strcpy(sci.actionSetName, "hands"); strcpy(sci.localizedActionSetName, "Hands");
    XrActionSet set;
    CHECK_RESULT(xrCreateActionSet(inst, &sci, &set), XR_SUCCESS);
    XrPath handPaths[2];
    xrStringToPath(inst, "/user/hand/left", &handPaths[0]);
    xrStringToPath(inst, "/user/hand/right", &handPaths[1]);
    auto make = [&](const char* name, XrActionType t) {
        XrActionCreateInfo aci{XR_TYPE_ACTION_CREATE_INFO};
        strcpy(aci.actionName, name); strcpy(aci.localizedActionName, name);
        aci.actionType = t; aci.countSubactionPaths = 2; aci.subactionPaths = handPaths;
        XrAction a = XR_NULL_HANDLE;
        CHECK_RESULT(xrCreateAction(set, &aci, &a), XR_SUCCESS);
        return a;
    };
    XrAction select = make("select", XR_ACTION_TYPE_FLOAT_INPUT);
    XrAction ready = make("ready", XR_ACTION_TYPE_BOOLEAN_INPUT);
    XrAction grasp = make("grasp", XR_ACTION_TYPE_FLOAT_INPUT);
    XrAction poke = make("poke", XR_ACTION_TYPE_POSE_INPUT);
    XrAction buzz = make("buzz", XR_ACTION_TYPE_VIBRATION_OUTPUT);
    auto path = [&](const char* p) { XrPath x; xrStringToPath(inst, p, &x); return x; };
    XrActionSuggestedBinding hb[] = {
        {select, path("/user/hand/right/input/pinch_ext/value")}, {ready, path("/user/hand/right/input/pinch_ext/ready_ext")},
        {grasp, path("/user/hand/right/input/grasp_ext/value")}, {poke, path("/user/hand/right/input/poke_ext/pose")},
    };
    XrInteractionProfileSuggestedBinding ipsb{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    ipsb.interactionProfile = path("/interaction_profiles/ext/hand_interaction_ext"); ipsb.countSuggestedBindings = 4; ipsb.suggestedBindings = hb;
    CHECK_RESULT(xrSuggestInteractionProfileBindings(inst, &ipsb), XR_SUCCESS);
    XrActionSuggestedBinding tb[] = {
        {select, path("/user/hand/right/input/trigger/value")},
        {buzz, path("/user/hand/right/output/haptic")}, {buzz, path("/user/hand/left/output/haptic")},
    };
    ipsb.interactionProfile = path("/interaction_profiles/oculus/touch_controller"); ipsb.countSuggestedBindings = 3; ipsb.suggestedBindings = tb;
    CHECK_RESULT(xrSuggestInteractionProfileBindings(inst, &ipsb), XR_SUCCESS);

    XrSessionCreateInfo ci{XR_TYPE_SESSION_CREATE_INFO};
    ci.systemId = sys;
    XrSession s;
    CHECK_RESULT(xrCreateSession(inst, &ci, &s), XR_SUCCESS);
    XrSessionActionSetsAttachInfo ai{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    ai.countActionSets = 1; ai.actionSets = &set;
    CHECK_RESULT(xrAttachSessionActionSets(s, &ai), XR_SUCCESS);
    XrSessionBeginInfo bi{XR_TYPE_SESSION_BEGIN_INFO};
    bi.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    CHECK_RESULT(xrBeginSession(s, &bi), XR_SUCCESS);
    XrFrameState fs{XR_TYPE_FRAME_STATE};
    XrFrameEndInfo ei{XR_TYPE_FRAME_END_INFO};
    ei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    for (int i = 0; i < 3; i++) { xrWaitFrame(s, nullptr, &fs); xrBeginFrame(s, nullptr); ei.displayTime = fs.predictedDisplayTime; xrEndFrame(s, &ei); }
    XrActiveActionSet active{set, XR_NULL_PATH};
    XrActionsSyncInfo si{XR_TYPE_ACTIONS_SYNC_INFO};
    si.countActiveActionSets = 1; si.activeActionSets = &active;
    XrActionStateGetInfo gi{XR_TYPE_ACTION_STATE_GET_INFO};
    XrActionStateFloat f{XR_TYPE_ACTION_STATE_FLOAT};
    XrActionStateBoolean b{XR_TYPE_ACTION_STATE_BOOLEAN};

    // a tracked right hand pinching 0.7 with the fingers curled into a fist
    hxTestSetHead(0, 0, 0, 1, 1);
    hxTestSetInput(0, 0, 0, 0, 0, 0, 0, 0);
    HxHandShared h{};
    h.magic = HX_HANDS_MAGIC;
    rightHandPalmDown(h.joints[1]);
    static const int curled[3] = {12, 16, 20};
    for (int tip : curled) for (int k = 0; k < 3; k++) h.joints[1][tip][k] = h.joints[1][9][k] * 0.5f + h.joints[1][0][k] * 0.5f;
    h.tracked[1] = 1; h.pinch[1] = 0.7f; h.timeNs[1] = monoNs();
    hxTestSetHands(&h);
    CHECK_RESULT(xrSyncActions(s, &si), XR_SUCCESS);
    gi.action = select;
    CHECK_RESULT(xrGetActionStateFloat(s, &gi, &f), XR_SUCCESS);
    CHECK(f.isActive && NEAR(f.currentState, 0.7, 1e-5));  // the pinch beats the idle trigger
    gi.action = ready;
    CHECK_RESULT(xrGetActionStateBoolean(s, &gi, &b), XR_SUCCESS);
    CHECK(b.isActive && b.currentState == XR_TRUE);
    gi.action = grasp;
    CHECK_RESULT(xrGetActionStateFloat(s, &gi, &f), XR_SUCCESS);
    CHECK(f.currentState > 0.9f);
    // the poke pose is the index tip: 8 cm camera offset, 1.6 m eyes, tip 18 cm past the wrist
    XrActionSpaceCreateInfo asci{XR_TYPE_ACTION_SPACE_CREATE_INFO};
    asci.action = poke; asci.subactionPath = handPaths[1]; asci.poseInActionSpace.orientation.w = 1;
    XrSpace pokeSpace, stage;
    CHECK_RESULT(xrCreateActionSpace(s, &asci, &pokeSpace), XR_SUCCESS);
    XrReferenceSpaceCreateInfo rci{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    rci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_STAGE; rci.poseInReferenceSpace.orientation.w = 1;
    CHECK_RESULT(xrCreateReferenceSpace(s, &rci, &stage), XR_SUCCESS);
    XrSpaceLocation loc{XR_TYPE_SPACE_LOCATION};
    CHECK_RESULT(xrLocateSpace(pokeSpace, stage, monoNs(), &loc), XR_SUCCESS);
    CHECK((loc.locationFlags & XR_SPACE_LOCATION_POSITION_TRACKED_BIT) && NEAR(loc.pose.position.z, -0.38 - 0.18, 1e-4));
    // the hand lost: not ready, no pinch; the Touch trigger takes over
    h.tracked[1] = 0;
    hxTestSetHands(&h);
    hxTestSetInput(0, 0, 0, 0, 0, 0, 0.4f, 0);
    CHECK_RESULT(xrSyncActions(s, &si), XR_SUCCESS);
    gi.action = select;
    CHECK_RESULT(xrGetActionStateFloat(s, &gi, &f), XR_SUCCESS);
    CHECK(NEAR(f.currentState, 0.4, 1e-5));
    gi.action = ready;
    CHECK_RESULT(xrGetActionStateBoolean(s, &gi, &b), XR_SUCCESS);
    CHECK(b.currentState == XR_FALSE);
    // haptics: a pulse reaches HyperMagic for the right controller; other kinds are refused
    XrHapticActionInfo hai{XR_TYPE_HAPTIC_ACTION_INFO};
    hai.action = buzz; hai.subactionPath = handPaths[1];
    XrHapticVibration vib{XR_TYPE_HAPTIC_VIBRATION};
    vib.amplitude = 0.6f; vib.duration = 50000000; vib.frequency = XR_FREQUENCY_UNSPECIFIED;
    uint32_t seq0, seq; int hand, ms; float amp;
    hxTestLastHaptic(&seq0, &hand, &amp, &ms);
    CHECK_RESULT(xrApplyHapticFeedback(s, &hai, reinterpret_cast<XrHapticBaseHeader*>(&vib)), XR_SUCCESS);
    hxTestLastHaptic(&seq, &hand, &amp, &ms);
    CHECK(seq == seq0 + 1 && hand == 1 && NEAR(amp, 0.6, 1e-6) && ms == 50);
    CHECK_RESULT(xrStopHapticFeedback(s, &hai), XR_SUCCESS);
    hxTestLastHaptic(&seq, &hand, &amp, &ms);
    CHECK(amp == 0);
    XrHapticBaseHeader other{XR_TYPE_HAPTIC_AMPLITUDE_ENVELOPE_VIBRATION_FB};
    CHECK_RESULT(xrApplyHapticFeedback(s, &hai, &other), XR_ERROR_VALIDATION_FAILURE);

    hxTestSetHands(nullptr);
    hxTestSetInput(0, 0, 0, 0, 0, 0, 0, 0);
    CHECK_RESULT(xrDestroySession(s), XR_SUCCESS);
    CHECK_RESULT(xrDestroyInstance(inst), XR_SUCCESS);
}

int main() {
    testNegotiation();
    if (!gpa) { printf("negotiation failed\n"); return 1; }
    testExtensionsAndInstance();
    const char* exts[] = {XR_MND_HEADLESS_EXTENSION_NAME, XR_EXT_LOCAL_FLOOR_EXTENSION_NAME};
    XrResult r;
    XrInstance inst = createInstance(XR_MAKE_VERSION(1, 0, 0), exts, 2, &r);
    CHECK(r == XR_SUCCESS);
    if (r != XR_SUCCESS) return 1;
    XrResult second;
    createInstance(XR_MAKE_VERSION(1, 0, 0), exts, 2, &second);
    CHECK(second == XR_ERROR_LIMIT_REACHED);
    testInstanceBasics(inst);
    testPaths(inst);
    XrSystemId sys = testSystem(inst);
    testSession(inst, sys);
    testSpacesAndViews(inst, sys);
    testActions(inst, sys);
    testActionInput(inst, sys);
    LOAD(inst, xrDestroyInstance);
    CHECK_RESULT(xrDestroyInstance(inst), XR_SUCCESS);
    testHands();
    testLayerShapes();
    testLayerValidation();
    testHandInteraction();
    printf("%d checks, %d failures\n", gChecks, gFailures);
    return gFailures == 0 ? 0 : 1;
}
