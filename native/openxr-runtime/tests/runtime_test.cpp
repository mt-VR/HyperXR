// Host tests for the HyperXR OpenXR runtime. Goes through the same entry point the Khronos loader
// uses (xrNegotiateLoaderRuntimeInterface -> xrGetInstanceProcAddr) and checks the behaviour the
// OpenXR specification requires, using a headless session (XR_MND_headless).
#include <math.h>
#include <stdio.h>
#include <string.h>

#include <openxr/openxr.h>
#include <openxr/openxr_loader_negotiation.h>

extern "C" XrResult xrNegotiateLoaderRuntimeInterface(const XrNegotiateLoaderInfo*, XrNegotiateRuntimeRequest*);
extern "C" void hxTestSetHead(float qx, float qy, float qz, float qw, int tracked);
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
    XrExtensionProperties props[32];
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
    CHECK(n == 1 && modes[0] == XR_ENVIRONMENT_BLEND_MODE_OPAQUE);
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

/** Quest (Meta XR) and Pico apps: their extensions and the Pico controller profile. */
static void testMetaAndPico() {
    const char* exts[] = {XR_MND_HEADLESS_EXTENSION_NAME, XR_FB_DISPLAY_REFRESH_RATE_EXTENSION_NAME, XR_FB_COLOR_SPACE_EXTENSION_NAME,
                          XR_FB_SWAPCHAIN_UPDATE_STATE_EXTENSION_NAME, XR_FB_FOVEATION_EXTENSION_NAME, XR_FB_FOVEATION_CONFIGURATION_EXTENSION_NAME,
                          XR_EXT_PERFORMANCE_SETTINGS_EXTENSION_NAME, XR_BD_CONTROLLER_INTERACTION_EXTENSION_NAME, XR_META_TOUCH_CONTROLLER_PLUS_EXTENSION_NAME};
    XrResult r;
    XrInstance inst = createInstance(XR_MAKE_VERSION(1, 0, 0), exts, sizeof(exts) / sizeof(exts[0]), &r);
    CHECK(r == XR_SUCCESS);
    if (r != XR_SUCCESS) return;
    LOAD(inst, xrGetSystem);
    LOAD(inst, xrGetSystemProperties);
    LOAD(inst, xrCreateSession);
    LOAD(inst, xrDestroySession);
    LOAD(inst, xrDestroyInstance);
    LOAD(inst, xrEnumerateDisplayRefreshRatesFB);
    LOAD(inst, xrGetDisplayRefreshRateFB);
    LOAD(inst, xrRequestDisplayRefreshRateFB);
    LOAD(inst, xrEnumerateColorSpacesFB);
    LOAD(inst, xrSetColorSpaceFB);
    LOAD(inst, xrCreateFoveationProfileFB);
    LOAD(inst, xrDestroyFoveationProfileFB);
    LOAD(inst, xrPerfSettingsSetPerformanceLevelEXT);
    LOAD(inst, xrStringToPath);
    LOAD(inst, xrCreateActionSet);
    LOAD(inst, xrCreateAction);
    LOAD(inst, xrSuggestInteractionProfileBindings);
    LOAD(inst, xrAttachSessionActionSets);
    LOAD(inst, xrGetCurrentInteractionProfile);

    XrSystemGetInfo sgi{XR_TYPE_SYSTEM_GET_INFO};
    sgi.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId sys = 0;
    CHECK_RESULT(xrGetSystem(inst, &sgi, &sys), XR_SUCCESS);
    XrSystemColorSpacePropertiesFB csp{XR_TYPE_SYSTEM_COLOR_SPACE_PROPERTIES_FB};
    XrSystemProperties sp{XR_TYPE_SYSTEM_PROPERTIES};
    sp.next = &csp;
    CHECK_RESULT(xrGetSystemProperties(inst, sys, &sp), XR_SUCCESS);
    CHECK(csp.colorSpace == XR_COLOR_SPACE_REC709_FB);

    XrSessionCreateInfo ci{XR_TYPE_SESSION_CREATE_INFO};
    ci.systemId = sys;
    XrSession s = XR_NULL_HANDLE;
    CHECK_RESULT(xrCreateSession(inst, &ci, &s), XR_SUCCESS);

    uint32_t n = 0;
    CHECK_RESULT(xrEnumerateDisplayRefreshRatesFB(s, 0, &n, nullptr), XR_SUCCESS);
    CHECK(n == 1);
    float rate = 0, current = 0;
    CHECK_RESULT(xrEnumerateDisplayRefreshRatesFB(s, 1, &n, &rate), XR_SUCCESS);
    CHECK_RESULT(xrGetDisplayRefreshRateFB(s, &current), XR_SUCCESS);
    CHECK(rate > 20 && current == rate);
    CHECK_RESULT(xrRequestDisplayRefreshRateFB(s, rate), XR_SUCCESS);
    CHECK_RESULT(xrRequestDisplayRefreshRateFB(s, 0.0f), XR_SUCCESS);
    CHECK_RESULT(xrRequestDisplayRefreshRateFB(s, 1234.0f), XR_ERROR_DISPLAY_REFRESH_RATE_UNSUPPORTED_FB);

    XrColorSpaceFB cs[4];
    CHECK_RESULT(xrEnumerateColorSpacesFB(s, 4, &n, cs), XR_SUCCESS);
    CHECK(n >= 1 && cs[0] == XR_COLOR_SPACE_REC709_FB);
    CHECK_RESULT(xrSetColorSpaceFB(s, XR_COLOR_SPACE_QUEST_FB), XR_SUCCESS);

    XrFoveationProfileCreateInfoFB fci{XR_TYPE_FOVEATION_PROFILE_CREATE_INFO_FB};
    XrFoveationProfileFB fov = XR_NULL_HANDLE;
    CHECK_RESULT(xrCreateFoveationProfileFB(s, &fci, &fov), XR_SUCCESS);
    CHECK_RESULT(xrDestroyFoveationProfileFB(fov), XR_SUCCESS);
    CHECK_RESULT(xrDestroyFoveationProfileFB(fov), XR_ERROR_HANDLE_INVALID);
    CHECK_RESULT(xrCreateFoveationProfileFB(s, &fci, &fov), XR_SUCCESS);  // left for the session to clean up
    CHECK_RESULT(xrPerfSettingsSetPerformanceLevelEXT(s, XR_PERF_SETTINGS_DOMAIN_GPU_EXT, XR_PERF_SETTINGS_LEVEL_BOOST_EXT), XR_SUCCESS);

    // a Pico 4 app's bindings are driven like the Touch ones
    XrActionSetCreateInfo sci{XR_TYPE_ACTION_SET_CREATE_INFO};
    strcpy(sci.actionSetName, "pico"); strcpy(sci.localizedActionSetName, "Pico");
    XrActionSet set;
    CHECK_RESULT(xrCreateActionSet(inst, &sci, &set), XR_SUCCESS);
    XrActionCreateInfo aci{XR_TYPE_ACTION_CREATE_INFO};
    strcpy(aci.actionName, "trigger"); strcpy(aci.localizedActionName, "Trigger");
    aci.actionType = XR_ACTION_TYPE_FLOAT_INPUT;
    XrAction trig;
    CHECK_RESULT(xrCreateAction(set, &aci, &trig), XR_SUCCESS);
    XrPath pico, binding, right;
    xrStringToPath(inst, "/interaction_profiles/bytedance/pico4_controller", &pico);
    xrStringToPath(inst, "/user/hand/right/input/trigger/value", &binding);
    xrStringToPath(inst, "/user/hand/right", &right);
    XrActionSuggestedBinding sb{trig, binding};
    XrInteractionProfileSuggestedBinding ipsb{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
    ipsb.interactionProfile = pico; ipsb.countSuggestedBindings = 1; ipsb.suggestedBindings = &sb;
    CHECK_RESULT(xrSuggestInteractionProfileBindings(inst, &ipsb), XR_SUCCESS);
    XrSessionActionSetsAttachInfo ai{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
    ai.countActionSets = 1; ai.actionSets = &set;
    CHECK_RESULT(xrAttachSessionActionSets(s, &ai), XR_SUCCESS);
    XrInteractionProfileState ips{XR_TYPE_INTERACTION_PROFILE_STATE};
    CHECK_RESULT(xrGetCurrentInteractionProfile(s, right, &ips), XR_SUCCESS);
    CHECK(ips.interactionProfile == pico);

    CHECK_RESULT(xrDestroySession(s), XR_SUCCESS);
    CHECK_RESULT(xrDestroyInstance(inst), XR_SUCCESS);

    // an extension the app did not enable has no functions
    XrInstance plain = createInstance(XR_MAKE_VERSION(1, 0, 0), exts, 1, &r);
    CHECK(r == XR_SUCCESS);
    PFN_xrVoidFunction f = nullptr;
    CHECK_RESULT(gpa(plain, "xrGetDisplayRefreshRateFB", &f), XR_ERROR_FUNCTION_UNSUPPORTED);
    xrDestroyInstance(plain);
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
    testMetaAndPico();
    printf("%d checks, %d failures\n", gChecks, gFailures);
    return gFailures == 0 ? 0 : 1;
}
