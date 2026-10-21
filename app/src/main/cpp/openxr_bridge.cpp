// HyperXR OpenXR bridge: runtime probe and a GLES session (instance, system, session, spaces,
// swapchains, frame loop). Runs against whichever runtime the Khronos loader selects through the
// Android runtime broker (e.g. Monado, or a vendor runtime). Never assumed to exist: every call
// reports its XrResult so the Runtime Broker can show the real failure.
#include <jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <cstring>
#include <sstream>
#include <string>
#include <vector>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>

#define LOG_TAG "HyperXR-OpenXR"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

JavaVM* gVm = nullptr;
bool gLoaderInitialized = false;

std::string resultName(XrInstance inst, XrResult r) {
    char buf[XR_MAX_RESULT_STRING_SIZE] = {};
    if (inst != XR_NULL_HANDLE && XR_SUCCEEDED(xrResultToString(inst, r, buf))) return buf;
    return "XrResult(" + std::to_string(static_cast<int>(r)) + ")";
}

bool initLoader(JNIEnv* env, jobject context, std::string& err) {
    if (gLoaderInitialized) return true;
    env->GetJavaVM(&gVm);
    PFN_xrInitializeLoaderKHR initializeLoader = nullptr;
    XrResult r = xrGetInstanceProcAddr(XR_NULL_HANDLE, "xrInitializeLoaderKHR", reinterpret_cast<PFN_xrVoidFunction*>(&initializeLoader));
    if (XR_FAILED(r) || !initializeLoader) { err = "xrInitializeLoaderKHR unavailable: " + resultName(XR_NULL_HANDLE, r); return false; }
    XrLoaderInitInfoAndroidKHR info{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
    info.applicationVM = gVm;
    info.applicationContext = context;
    r = initializeLoader(reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR*>(&info));
    if (XR_FAILED(r)) { err = "loader init failed: " + resultName(XR_NULL_HANDLE, r); return false; }
    gLoaderInitialized = true;
    return true;
}

std::vector<std::string> enumerateExtensions() {
    uint32_t count = 0;
    std::vector<std::string> out;
    if (XR_FAILED(xrEnumerateInstanceExtensionProperties(nullptr, 0, &count, nullptr)) || count == 0) return out;
    std::vector<XrExtensionProperties> props(count, {XR_TYPE_EXTENSION_PROPERTIES});
    if (XR_FAILED(xrEnumerateInstanceExtensionProperties(nullptr, count, &count, props.data()))) return out;
    for (auto& p : props) out.emplace_back(p.extensionName);
    return out;
}

bool has(const std::vector<std::string>& v, const char* s) {
    for (auto& e : v) if (e == s) return true;
    return false;
}

XrResult createInstance(JNIEnv* env, jobject activity, const std::vector<std::string>& available, bool needGles, XrInstance* out,
                        std::vector<const char*>& enabled) {
    enabled.clear();
    enabled.push_back(XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME);
    if (needGles) enabled.push_back(XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME);
    if (has(available, XR_EXT_HAND_TRACKING_EXTENSION_NAME)) enabled.push_back(XR_EXT_HAND_TRACKING_EXTENSION_NAME);
    XrInstanceCreateInfoAndroidKHR android{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
    android.applicationVM = gVm;
    android.applicationActivity = activity;
    XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO};
    ci.next = &android;
    strncpy(ci.applicationInfo.applicationName, "HyperXR", XR_MAX_APPLICATION_NAME_SIZE - 1);
    ci.applicationInfo.applicationVersion = 1;
    strncpy(ci.applicationInfo.engineName, "HyperXR Engine", XR_MAX_ENGINE_NAME_SIZE - 1);
    ci.applicationInfo.engineVersion = 1;
    ci.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 34);
    ci.enabledExtensionCount = static_cast<uint32_t>(enabled.size());
    ci.enabledExtensionNames = enabled.data();
    return xrCreateInstance(&ci, out);
}

// ------------------------------------------------------------------ session
struct Swapchain {
    XrSwapchain handle = XR_NULL_HANDLE;
    int32_t width = 0, height = 0;
    std::vector<XrSwapchainImageOpenGLESKHR> images;
};

struct Session {
    XrInstance instance = XR_NULL_HANDLE;
    XrSystemId system = XR_NULL_SYSTEM_ID;
    XrSession session = XR_NULL_HANDLE;
    XrSpace space = XR_NULL_HANDLE;
    XrSessionState state = XR_SESSION_STATE_UNKNOWN;
    bool running = false;
    bool exitRequested = false;
    Swapchain swapchains[2];
    XrView views[2] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
    XrFrameState frameState{XR_TYPE_FRAME_STATE};
    bool frameBegun = false;
    uint32_t acquired[2] = {0, 0};
    std::string error;
};

bool fail(Session* s, const char* what, XrResult r) {
    s->error = std::string(what) + ": " + resultName(s->instance, r);
    LOGE("%s", s->error.c_str());
    return false;
}

bool createSession(JNIEnv* env, jobject activity, Session* s) {
    std::string err;
    if (!initLoader(env, activity, err)) { s->error = err; return false; }
    auto available = enumerateExtensions();
    if (!has(available, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME)) { s->error = "runtime lacks XR_KHR_opengl_es_enable"; return false; }
    std::vector<const char*> enabled;
    XrResult r = createInstance(env, activity, available, true, &s->instance, enabled);
    if (XR_FAILED(r)) return fail(s, "xrCreateInstance", r);

    XrSystemGetInfo sgi{XR_TYPE_SYSTEM_GET_INFO};
    sgi.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    r = xrGetSystem(s->instance, &sgi, &s->system);
    if (XR_FAILED(r)) return fail(s, "xrGetSystem", r);

    PFN_xrGetOpenGLESGraphicsRequirementsKHR getReq = nullptr;
    xrGetInstanceProcAddr(s->instance, "xrGetOpenGLESGraphicsRequirementsKHR", reinterpret_cast<PFN_xrVoidFunction*>(&getReq));
    if (!getReq) { s->error = "xrGetOpenGLESGraphicsRequirementsKHR missing"; return false; }
    XrGraphicsRequirementsOpenGLESKHR req{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
    r = getReq(s->instance, s->system, &req);
    if (XR_FAILED(r)) return fail(s, "graphics requirements", r);

    EGLDisplay display = eglGetCurrentDisplay();
    EGLContext context = eglGetCurrentContext();
    if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT) { s->error = "no current EGL context on this thread"; return false; }
    EGLint configId = 0;
    eglQueryContext(display, context, EGL_CONFIG_ID, &configId);
    EGLint attribs[] = {EGL_CONFIG_ID, configId, EGL_NONE};
    EGLConfig config = nullptr;
    EGLint numConfigs = 0;
    eglChooseConfig(display, attribs, &config, 1, &numConfigs);

    XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
    binding.display = display;
    binding.config = config;
    binding.context = context;
    XrSessionCreateInfo sci{XR_TYPE_SESSION_CREATE_INFO};
    sci.next = &binding;
    sci.systemId = s->system;
    r = xrCreateSession(s->instance, &sci, &s->session);
    if (XR_FAILED(r)) return fail(s, "xrCreateSession", r);

    XrReferenceSpaceCreateInfo rsci{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
    rsci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
    rsci.poseInReferenceSpace.orientation.w = 1.f;
    r = xrCreateReferenceSpace(s->session, &rsci, &s->space);
    if (XR_FAILED(r)) return fail(s, "xrCreateReferenceSpace", r);

    uint32_t viewCount = 0;
    xrEnumerateViewConfigurationViews(s->instance, s->system, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &viewCount, nullptr);
    if (viewCount != 2) { s->error = "primary stereo view configuration unavailable"; return false; }
    XrViewConfigurationView vcv[2] = {{XR_TYPE_VIEW_CONFIGURATION_VIEW}, {XR_TYPE_VIEW_CONFIGURATION_VIEW}};
    xrEnumerateViewConfigurationViews(s->instance, s->system, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 2, &viewCount, vcv);

    uint32_t formatCount = 0;
    xrEnumerateSwapchainFormats(s->session, 0, &formatCount, nullptr);
    std::vector<int64_t> formats(formatCount);
    xrEnumerateSwapchainFormats(s->session, formatCount, &formatCount, formats.data());
    int64_t chosen = 0;
    for (int64_t f : formats) if (f == GL_SRGB8_ALPHA8) chosen = f;
    if (!chosen) for (int64_t f : formats) if (f == GL_RGBA8) chosen = f;
    if (!chosen && !formats.empty()) chosen = formats[0];

    for (int eye = 0; eye < 2; eye++) {
        XrSwapchainCreateInfo swci{XR_TYPE_SWAPCHAIN_CREATE_INFO};
        swci.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
        swci.format = chosen;
        swci.sampleCount = 1;
        swci.width = vcv[eye].recommendedImageRectWidth;
        swci.height = vcv[eye].recommendedImageRectHeight;
        swci.faceCount = 1;
        swci.arraySize = 1;
        swci.mipCount = 1;
        Swapchain& sc = s->swapchains[eye];
        r = xrCreateSwapchain(s->session, &swci, &sc.handle);
        if (XR_FAILED(r)) return fail(s, "xrCreateSwapchain", r);
        sc.width = static_cast<int32_t>(swci.width);
        sc.height = static_cast<int32_t>(swci.height);
        uint32_t imageCount = 0;
        xrEnumerateSwapchainImages(sc.handle, 0, &imageCount, nullptr);
        sc.images.assign(imageCount, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
        xrEnumerateSwapchainImages(sc.handle, imageCount, &imageCount, reinterpret_cast<XrSwapchainImageBaseHeader*>(sc.images.data()));
    }
    LOGI("OpenXR session created (%dx%d per eye)", s->swapchains[0].width, s->swapchains[0].height);
    return true;
}

void destroySession(Session* s) {
    for (auto& sc : s->swapchains) if (sc.handle) xrDestroySwapchain(sc.handle);
    if (s->space) xrDestroySpace(s->space);
    if (s->session) xrDestroySession(s->session);
    if (s->instance) xrDestroyInstance(s->instance);
    delete s;
}

void pollEvents(Session* s) {
    XrEventDataBuffer ev{XR_TYPE_EVENT_DATA_BUFFER};
    while (xrPollEvent(s->instance, &ev) == XR_SUCCESS) {
        if (ev.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
            auto* sc = reinterpret_cast<XrEventDataSessionStateChanged*>(&ev);
            s->state = sc->state;
            if (sc->state == XR_SESSION_STATE_READY) {
                XrSessionBeginInfo bi{XR_TYPE_SESSION_BEGIN_INFO};
                bi.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                XrResult r = xrBeginSession(s->session, &bi);
                s->running = XR_SUCCEEDED(r);
                if (!s->running) fail(s, "xrBeginSession", r);
            } else if (sc->state == XR_SESSION_STATE_STOPPING) {
                xrEndSession(s->session);
                s->running = false;
            } else if (sc->state == XR_SESSION_STATE_EXITING || sc->state == XR_SESSION_STATE_LOSS_PENDING) {
                s->exitRequested = true;
                s->running = false;
            }
        } else if (ev.type == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) {
            s->exitRequested = true;
        }
        ev = {XR_TYPE_EVENT_DATA_BUFFER};
    }
}

}  // namespace

extern "C" {

// Returns "key=value" lines describing what the runtime really reports.
JNIEXPORT jstring JNICALL
Java_com_hyperxr_openxr_OpenXrNative_nativeProbe(JNIEnv* env, jclass, jobject activity) {
    std::ostringstream out;
    std::string err;
    if (!initLoader(env, activity, err)) {
        out << "ok=false\nerror=" << err << "\n";
        return env->NewStringUTF(out.str().c_str());
    }
    auto exts = enumerateExtensions();
    out << "extensions=";
    for (size_t i = 0; i < exts.size(); i++) out << (i ? "," : "") << exts[i];
    out << "\n";
    if (exts.empty()) {
        out << "ok=false\nerror=no OpenXR runtime responded (no extensions enumerated)\n";
        return env->NewStringUTF(out.str().c_str());
    }
    XrInstance inst = XR_NULL_HANDLE;
    std::vector<const char*> enabled;
    XrResult r = createInstance(env, activity, exts, has(exts, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME), &inst, enabled);
    if (XR_FAILED(r)) {
        out << "ok=false\nerror=xrCreateInstance " << resultName(XR_NULL_HANDLE, r) << "\n";
        return env->NewStringUTF(out.str().c_str());
    }
    XrInstanceProperties ip{XR_TYPE_INSTANCE_PROPERTIES};
    if (XR_SUCCEEDED(xrGetInstanceProperties(inst, &ip))) {
        out << "runtimeName=" << ip.runtimeName << "\n";
        out << "runtimeVersion=" << XR_VERSION_MAJOR(ip.runtimeVersion) << "." << XR_VERSION_MINOR(ip.runtimeVersion) << "."
            << XR_VERSION_PATCH(ip.runtimeVersion) << "\n";
    }
    XrSystemGetInfo sgi{XR_TYPE_SYSTEM_GET_INFO};
    sgi.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
    XrSystemId sys = XR_NULL_SYSTEM_ID;
    r = xrGetSystem(inst, &sgi, &sys);
    if (XR_SUCCEEDED(r)) {
        XrSystemProperties sp{XR_TYPE_SYSTEM_PROPERTIES};
        XrSystemHandTrackingPropertiesEXT hand{XR_TYPE_SYSTEM_HAND_TRACKING_PROPERTIES_EXT};
        if (has(exts, XR_EXT_HAND_TRACKING_EXTENSION_NAME)) sp.next = &hand;
        xrGetSystemProperties(inst, sys, &sp);
        out << "systemName=" << sp.systemName << "\n";
        out << "orientationTracking=" << (sp.trackingProperties.orientationTracking ? "true" : "false") << "\n";
        out << "positionTracking=" << (sp.trackingProperties.positionTracking ? "true" : "false") << "\n";
        out << "handTracking=" << (hand.supportsHandTracking ? "true" : "false") << "\n";
        out << "ok=true\n";
    } else {
        out << "ok=false\nerror=xrGetSystem " << resultName(inst, r) << " (no HMD form factor available)\n";
    }
    xrDestroyInstance(inst);
    return env->NewStringUTF(out.str().c_str());
}

// Must be called on the GL thread with the renderer's EGL context current.
JNIEXPORT jlong JNICALL
Java_com_hyperxr_openxr_OpenXrNative_nativeCreateSession(JNIEnv* env, jclass, jobject activity, jobjectArray errorOut) {
    auto* s = new Session();
    if (!createSession(env, activity, s)) {
        jstring e = env->NewStringUTF(s->error.c_str());
        env->SetObjectArrayElement(errorOut, 0, e);
        destroySession(s);
        return 0;
    }
    return reinterpret_cast<jlong>(s);
}

/**
 * Polls events and, when the session is running, begins a frame. out (28 floats):
 * per eye i (stride 14): pos xyz, quat xyzw, fov left/right/up/down (radians), texture id, width, height.
 * Returns: -1 exit requested, 0 nothing to render this frame, 1 render both eyes then call endFrame.
 */
JNIEXPORT jint JNICALL
Java_com_hyperxr_openxr_OpenXrNative_nativeBeginFrame(JNIEnv* env, jclass, jlong handle, jfloatArray out) {
    auto* s = reinterpret_cast<Session*>(handle);
    pollEvents(s);
    if (s->exitRequested) return -1;
    if (!s->running) return 0;
    s->frameState = {XR_TYPE_FRAME_STATE};
    XrFrameWaitInfo wi{XR_TYPE_FRAME_WAIT_INFO};
    if (XR_FAILED(xrWaitFrame(s->session, &wi, &s->frameState))) return 0;
    XrFrameBeginInfo bi{XR_TYPE_FRAME_BEGIN_INFO};
    if (XR_FAILED(xrBeginFrame(s->session, &bi))) return 0;
    s->frameBegun = true;
    if (!s->frameState.shouldRender) return 0;

    XrViewLocateInfo li{XR_TYPE_VIEW_LOCATE_INFO};
    li.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
    li.displayTime = s->frameState.predictedDisplayTime;
    li.space = s->space;
    XrViewState vs{XR_TYPE_VIEW_STATE};
    uint32_t n = 0;
    s->views[0] = {XR_TYPE_VIEW};
    s->views[1] = {XR_TYPE_VIEW};
    if (XR_FAILED(xrLocateViews(s->session, &li, &vs, 2, &n, s->views)) ||
        !(vs.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT)) return 0;

    float buf[28];
    for (int eye = 0; eye < 2; eye++) {
        Swapchain& sc = s->swapchains[eye];
        XrSwapchainImageAcquireInfo ai{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
        xrAcquireSwapchainImage(sc.handle, &ai, &s->acquired[eye]);
        XrSwapchainImageWaitInfo wait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
        wait.timeout = XR_INFINITE_DURATION;
        xrWaitSwapchainImage(sc.handle, &wait);
        const XrView& v = s->views[eye];
        float* o = buf + eye * 14;
        o[0] = v.pose.position.x; o[1] = v.pose.position.y; o[2] = v.pose.position.z;
        o[3] = v.pose.orientation.x; o[4] = v.pose.orientation.y; o[5] = v.pose.orientation.z; o[6] = v.pose.orientation.w;
        o[7] = v.fov.angleLeft; o[8] = v.fov.angleRight; o[9] = v.fov.angleUp; o[10] = v.fov.angleDown;
        o[11] = static_cast<float>(sc.images[s->acquired[eye]].image);
        o[12] = static_cast<float>(sc.width);
        o[13] = static_cast<float>(sc.height);
    }
    env->SetFloatArrayRegion(out, 0, 28, buf);
    return 1;
}

JNIEXPORT void JNICALL
Java_com_hyperxr_openxr_OpenXrNative_nativeEndFrame(JNIEnv*, jclass, jlong handle, jboolean rendered) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (!s->frameBegun) return;
    s->frameBegun = false;
    XrCompositionLayerProjectionView pv[2] = {{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}, {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}};
    XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
    const XrCompositionLayerBaseHeader* layers[1] = {reinterpret_cast<XrCompositionLayerBaseHeader*>(&layer)};
    XrFrameEndInfo ei{XR_TYPE_FRAME_END_INFO};
    ei.displayTime = s->frameState.predictedDisplayTime;
    ei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
    if (rendered && s->frameState.shouldRender) {
        for (int eye = 0; eye < 2; eye++) {
            XrSwapchainImageReleaseInfo ri{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
            xrReleaseSwapchainImage(s->swapchains[eye].handle, &ri);
            pv[eye].pose = s->views[eye].pose;
            pv[eye].fov = s->views[eye].fov;
            pv[eye].subImage.swapchain = s->swapchains[eye].handle;
            pv[eye].subImage.imageRect.extent = {s->swapchains[eye].width, s->swapchains[eye].height};
        }
        layer.space = s->space;
        layer.viewCount = 2;
        layer.views = pv;
        ei.layerCount = 1;
        ei.layers = layers;
    }
    xrEndFrame(s->session, &ei);
}

JNIEXPORT jstring JNICALL
Java_com_hyperxr_openxr_OpenXrNative_nativeSessionState(JNIEnv* env, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    std::string st = "state=" + std::to_string(static_cast<int>(s->state)) + " running=" + (s->running ? "1" : "0");
    if (!s->error.empty()) st += " error=" + s->error;
    return env->NewStringUTF(st.c_str());
}

JNIEXPORT void JNICALL
Java_com_hyperxr_openxr_OpenXrNative_nativeRequestExit(JNIEnv*, jclass, jlong handle) {
    auto* s = reinterpret_cast<Session*>(handle);
    if (s->running) xrRequestExitSession(s->session);
}

JNIEXPORT void JNICALL
Java_com_hyperxr_openxr_OpenXrNative_nativeDestroySession(JNIEnv*, jclass, jlong handle) {
    if (handle) destroySession(reinterpret_cast<Session*>(handle));
}

}  // extern "C"
