// Self test of the HyperXR OpenXR runtime, run from the HyperXR app: loads libhyperxr_openxr.so
// and negotiates with it exactly like the Khronos loader does, then drives a real OpenXR GLES
// session for a few seconds (world-locked markers drawn with scissored clears, no shaders) and
// reports what happened. This exercises the runtime on the device without installing a broker.
#include <jni.h>
#include <android/log.h>
#include <EGL/egl.h>
#include <GLES3/gl3.h>
#include <dlfcn.h>
#include <math.h>
#include <string.h>
#include <time.h>

#include <string>
#include <vector>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#include <openxr/openxr.h>
#include <openxr/openxr_platform.h>
#include <openxr/openxr_loader_negotiation.h>

#define LOG_TAG "HyperXR-RuntimeTest"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {

struct Report {
    std::string text;
    void line(const std::string& s) { text += s + "\n"; LOGI("%s", s.c_str()); }
};

const char* stateName(XrSessionState s) {
    switch (s) {
        case XR_SESSION_STATE_IDLE: return "IDLE";
        case XR_SESSION_STATE_READY: return "READY";
        case XR_SESSION_STATE_SYNCHRONIZED: return "SYNCHRONIZED";
        case XR_SESSION_STATE_VISIBLE: return "VISIBLE";
        case XR_SESSION_STATE_FOCUSED: return "FOCUSED";
        case XR_SESSION_STATE_STOPPING: return "STOPPING";
        case XR_SESSION_STATE_LOSS_PENDING: return "LOSS_PENDING";
        case XR_SESSION_STATE_EXITING: return "EXITING";
        default: return "UNKNOWN";
    }
}

XrVector3f rotate(XrQuaternionf q, XrVector3f v) {
    float tx = 2 * (q.y * v.z - q.z * v.y), ty = 2 * (q.z * v.x - q.x * v.z), tz = 2 * (q.x * v.y - q.y * v.x);
    return {v.x + q.w * tx + (q.y * tz - q.z * ty), v.y + q.w * ty + (q.z * tx - q.x * tz), v.z + q.w * tz + (q.x * ty - q.y * tx)};
}

struct Marker { XrVector3f p; float r, g, b; float size; };

std::vector<Marker> makeScene() {
    std::vector<Marker> m;
    // a ring of coloured markers around the user at three heights, 2 m away
    for (int i = 0; i < 24; i++) {
        float a = static_cast<float>(i) / 24.0f * 6.2831853f;
        for (int k = -1; k <= 1; k++) {
            float hue = static_cast<float>(i) / 24.0f;
            float r = fabsf(hue * 6 - 3) - 1, g = 2 - fabsf(hue * 6 - 2), b = 2 - fabsf(hue * 6 - 4);
            m.push_back({{2.0f * sinf(a), 0.35f * k, -2.0f * cosf(a)}, fminf(fmaxf(r, 0), 1), fminf(fmaxf(g, 0), 1), fminf(fmaxf(b, 0), 1), 0.12f});
        }
    }
    // floor grid 1.6 m below the eyes
    for (int x = -4; x <= 4; x++)
        for (int z = -4; z <= 4; z++) m.push_back({{static_cast<float>(x), -1.6f, static_cast<float>(z)}, 0.55f, 0.5f, 0.9f, 0.06f});
    // the "front" marker: white, straight ahead
    m.push_back({{0, 0, -1.5f}, 1, 1, 1, 0.1f});
    return m;
}

void drawView(const XrView& view, int w, int h, const std::vector<Marker>& scene) {
    glDisable(GL_SCISSOR_TEST);
    glViewport(0, 0, w, h);
    glClearColor(0.05f, 0.05f, 0.12f, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glEnable(GL_SCISSOR_TEST);
    XrQuaternionf inv = {-view.pose.orientation.x, -view.pose.orientation.y, -view.pose.orientation.z, view.pose.orientation.w};
    float tl = tanf(view.fov.angleLeft), tr = tanf(view.fov.angleRight), td = tanf(view.fov.angleDown), tu = tanf(view.fov.angleUp);
    for (const Marker& mk : scene) {
        XrVector3f d = {mk.p.x - view.pose.position.x, mk.p.y - view.pose.position.y, mk.p.z - view.pose.position.z};
        XrVector3f v = rotate(inv, d);
        if (v.z > -0.2f) continue;
        float x = v.x / -v.z, y = v.y / -v.z;
        float u = (x - tl) / (tr - tl), vv = (y - td) / (tu - td);
        int s = static_cast<int>(mk.size / -v.z / (tu - td) * h);
        if (s < 2) s = 2;
        int px = static_cast<int>(u * w) - s / 2, py = static_cast<int>(vv * h) - s / 2;
        if (px + s < 0 || py + s < 0 || px > w || py > h) continue;
        glScissor(px, py, s, s);
        glClearColor(mk.r, mk.g, mk.b, 1);
        glClear(GL_COLOR_BUFFER_BIT);
    }
    glDisable(GL_SCISSOR_TEST);
}

int64_t nowNs() { timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts); return ts.tv_sec * 1000000000LL + ts.tv_nsec; }

#define XR_CHECK(expr) do { XrResult r_ = (expr); if (XR_FAILED(r_)) { rep.line(std::string("FALHOU: ") + #expr + " = " + std::to_string(static_cast<int>(r_))); goto cleanup; } } while (0)

std::string runTest(JNIEnv* env, jobject activity, int seconds) {
    Report rep;
    JavaVM* vm = nullptr;
    env->GetJavaVM(&vm);

    // EGL context of the "application"
    EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
    eglInitialize(dpy, nullptr, nullptr);
    const EGLint cfgAttr[] = {EGL_RENDERABLE_TYPE, 0x40, EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLConfig cfg;
    EGLint n = 0;
    eglChooseConfig(dpy, cfgAttr, &cfg, 1, &n);
    const EGLint ctxAttr[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxAttr);
    const EGLint pb[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    EGLSurface surf = eglCreatePbufferSurface(dpy, cfg, pb);
    eglMakeCurrent(dpy, surf, surf, ctx);

    XrInstance instance = XR_NULL_HANDLE;
    XrSession session = XR_NULL_HANDLE;
    XrSpace local = XR_NULL_HANDLE;
    XrSwapchain swapchains[2] = {XR_NULL_HANDLE, XR_NULL_HANDLE};
    std::vector<XrSwapchainImageOpenGLESKHR> images[2];
    GLuint fbo = 0;
    uint32_t eyeW = 0, eyeH = 0;
    PFN_xrGetInstanceProcAddr gpa = nullptr;
    void* lib = dlopen("libhyperxr_openxr.so", RTLD_NOW | RTLD_LOCAL);
    if (!lib) { rep.line(std::string("Biblioteca do runtime não carregou: ") + dlerror()); return rep.text; }
    {
        auto negotiate = reinterpret_cast<XrResult (*)(const XrNegotiateLoaderInfo*, XrNegotiateRuntimeRequest*)>(dlsym(lib, "xrNegotiateLoaderRuntimeInterface"));
        if (!negotiate) { rep.line("xrNegotiateLoaderRuntimeInterface não exportado"); return rep.text; }
        XrNegotiateLoaderInfo li{XR_LOADER_INTERFACE_STRUCT_LOADER_INFO, XR_LOADER_INFO_STRUCT_VERSION, sizeof(XrNegotiateLoaderInfo)};
        li.minInterfaceVersion = 1; li.maxInterfaceVersion = 1;
        li.minApiVersion = XR_MAKE_VERSION(1, 0, 0); li.maxApiVersion = XR_MAKE_VERSION(1, 0x3ff, 0xfff);
        XrNegotiateRuntimeRequest rr{XR_LOADER_INTERFACE_STRUCT_RUNTIME_REQUEST, XR_RUNTIME_INFO_STRUCT_VERSION, sizeof(XrNegotiateRuntimeRequest)};
        XR_CHECK(negotiate(&li, &rr));
        gpa = rr.getInstanceProcAddr;
        rep.line("Negociação com o loader: OK (interface " + std::to_string(rr.runtimeInterfaceVersion) + ", API " +
                 std::to_string(XR_VERSION_MAJOR(rr.runtimeApiVersion)) + "." + std::to_string(XR_VERSION_MINOR(rr.runtimeApiVersion)) + ")");
    }
    {
#define LOAD(name) PFN_##name name = nullptr; gpa(instance, #name, reinterpret_cast<PFN_xrVoidFunction*>(&name))
        LOAD(xrCreateInstance);
        const char* exts[] = {XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME, XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME};
        XrInstanceCreateInfoAndroidKHR android{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
        android.applicationVM = vm;
        android.applicationActivity = activity;
        XrInstanceCreateInfo ci{XR_TYPE_INSTANCE_CREATE_INFO};
        ci.next = &android;
        strcpy(ci.applicationInfo.applicationName, "HyperXR runtime test");
        ci.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
        ci.enabledExtensionCount = 2;
        ci.enabledExtensionNames = exts;
        XR_CHECK(xrCreateInstance(&ci, &instance));
    }
    {
        LOAD(xrGetInstanceProperties); LOAD(xrGetSystem); LOAD(xrGetSystemProperties); LOAD(xrEnumerateViewConfigurationViews);
        LOAD(xrGetOpenGLESGraphicsRequirementsKHR); LOAD(xrCreateSession); LOAD(xrCreateReferenceSpace); LOAD(xrEnumerateSwapchainFormats);
        LOAD(xrCreateSwapchain); LOAD(xrEnumerateSwapchainImages); LOAD(xrPollEvent); LOAD(xrBeginSession); LOAD(xrEndSession);
        LOAD(xrWaitFrame); LOAD(xrBeginFrame); LOAD(xrEndFrame); LOAD(xrLocateViews); LOAD(xrAcquireSwapchainImage);
        LOAD(xrWaitSwapchainImage); LOAD(xrReleaseSwapchainImage); LOAD(xrRequestExitSession);
        XrInstanceProperties ip{XR_TYPE_INSTANCE_PROPERTIES};
        XR_CHECK(xrGetInstanceProperties(instance, &ip));
        XrSystemGetInfo sgi{XR_TYPE_SYSTEM_GET_INFO};
        sgi.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
        XrSystemId sys;
        XR_CHECK(xrGetSystem(instance, &sgi, &sys));
        XrSystemProperties sp{XR_TYPE_SYSTEM_PROPERTIES};
        XR_CHECK(xrGetSystemProperties(instance, sys, &sp));
        rep.line(std::string("Runtime: ") + ip.runtimeName + " · sistema: " + sp.systemName);
        XrViewConfigurationView vcv[2] = {{XR_TYPE_VIEW_CONFIGURATION_VIEW}, {XR_TYPE_VIEW_CONFIGURATION_VIEW}};
        uint32_t nv = 0;
        XR_CHECK(xrEnumerateViewConfigurationViews(instance, sys, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 2, &nv, vcv));
        eyeW = vcv[0].recommendedImageRectWidth; eyeH = vcv[0].recommendedImageRectHeight;
        rep.line("Imagem recomendada por olho: " + std::to_string(eyeW) + "x" + std::to_string(eyeH));
        XrGraphicsRequirementsOpenGLESKHR req{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
        XR_CHECK(xrGetOpenGLESGraphicsRequirementsKHR(instance, sys, &req));

        XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
        binding.display = dpy; binding.config = cfg; binding.context = ctx;
        XrSessionCreateInfo sci{XR_TYPE_SESSION_CREATE_INFO};
        sci.next = &binding;
        sci.systemId = sys;
        XR_CHECK(xrCreateSession(instance, &sci, &session));
        XrReferenceSpaceCreateInfo rci{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
        rci.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
        rci.poseInReferenceSpace.orientation.w = 1;
        XR_CHECK(xrCreateReferenceSpace(session, &rci, &local));

        uint32_t nf = 0;
        int64_t formats[16];
        XR_CHECK(xrEnumerateSwapchainFormats(session, 16, &nf, formats));
        int64_t fmt = nf > 0 ? formats[0] : GL_RGBA8;
        for (int eye = 0; eye < 2; eye++) {
            XrSwapchainCreateInfo sc{XR_TYPE_SWAPCHAIN_CREATE_INFO};
            sc.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
            sc.format = fmt; sc.sampleCount = 1; sc.width = eyeW; sc.height = eyeH; sc.faceCount = 1; sc.arraySize = 1; sc.mipCount = 1;
            XR_CHECK(xrCreateSwapchain(session, &sc, &swapchains[eye]));
            uint32_t ni = 0;
            XR_CHECK(xrEnumerateSwapchainImages(swapchains[eye], 0, &ni, nullptr));
            images[eye].assign(ni, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
            XR_CHECK(xrEnumerateSwapchainImages(swapchains[eye], ni, &ni, reinterpret_cast<XrSwapchainImageBaseHeader*>(images[eye].data())));
        }
        glGenFramebuffers(1, &fbo);
        std::vector<Marker> scene = makeScene();

        std::string states;
        bool running = false, exiting = false, exitRequested = false;
        int64_t start = nowNs(), firstFrame = 0, lastFrame = 0;
        uint64_t frames = 0, rendered = 0;
        while (!exiting && nowNs() - start < (static_cast<int64_t>(seconds) + 4) * 1000000000LL) {
            XrEventDataBuffer ev{XR_TYPE_EVENT_DATA_BUFFER};
            while (xrPollEvent(instance, &ev) == XR_SUCCESS) {
                if (ev.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
                    XrSessionState st = reinterpret_cast<XrEventDataSessionStateChanged&>(ev).state;
                    states += std::string(states.empty() ? "" : " → ") + stateName(st);
                    if (st == XR_SESSION_STATE_READY && !running) {
                        XrSessionBeginInfo bi{XR_TYPE_SESSION_BEGIN_INFO};
                        bi.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                        XR_CHECK(xrBeginSession(session, &bi));
                        running = true;
                    } else if (st == XR_SESSION_STATE_STOPPING) {
                        XR_CHECK(xrEndSession(session));
                        running = false;
                    } else if (st == XR_SESSION_STATE_EXITING) {
                        exiting = true;
                    }
                }
                ev = {XR_TYPE_EVENT_DATA_BUFFER};
            }
            if (!running) { struct timespec t = {0, 10000000}; nanosleep(&t, nullptr); continue; }
            if (!exitRequested && nowNs() - start > static_cast<int64_t>(seconds) * 1000000000LL) {
                XR_CHECK(xrRequestExitSession(session));
                exitRequested = true;
                continue;
            }
            XrFrameState fs{XR_TYPE_FRAME_STATE};
            XR_CHECK(xrWaitFrame(session, nullptr, &fs));
            XR_CHECK(xrBeginFrame(session, nullptr));
            XrCompositionLayerProjectionView pviews[2] = {{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}, {XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW}};
            XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
            const XrCompositionLayerBaseHeader* layers[1] = {reinterpret_cast<XrCompositionLayerBaseHeader*>(&layer)};
            XrFrameEndInfo ei{XR_TYPE_FRAME_END_INFO};
            ei.displayTime = fs.predictedDisplayTime;
            ei.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
            if (fs.shouldRender) {
                XrViewLocateInfo vli{XR_TYPE_VIEW_LOCATE_INFO};
                vli.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
                vli.displayTime = fs.predictedDisplayTime;
                vli.space = local;
                XrViewState vs{XR_TYPE_VIEW_STATE};
                XrView views[2] = {{XR_TYPE_VIEW}, {XR_TYPE_VIEW}};
                uint32_t vc = 0;
                XR_CHECK(xrLocateViews(session, &vli, &vs, 2, &vc, views));
                if (vs.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT) {
                    for (int eye = 0; eye < 2; eye++) {
                        uint32_t idx = 0;
                        XR_CHECK(xrAcquireSwapchainImage(swapchains[eye], nullptr, &idx));
                        XrSwapchainImageWaitInfo wi{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
                        wi.timeout = XR_INFINITE_DURATION;
                        XR_CHECK(xrWaitSwapchainImage(swapchains[eye], &wi));
                        glBindFramebuffer(GL_FRAMEBUFFER, fbo);
                        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, images[eye][idx].image, 0);
                        drawView(views[eye], static_cast<int>(eyeW), static_cast<int>(eyeH), scene);
                        XR_CHECK(xrReleaseSwapchainImage(swapchains[eye], nullptr));
                        pviews[eye].pose = views[eye].pose;
                        pviews[eye].fov = views[eye].fov;
                        pviews[eye].subImage.swapchain = swapchains[eye];
                        pviews[eye].subImage.imageRect = {{0, 0}, {static_cast<int32_t>(eyeW), static_cast<int32_t>(eyeH)}};
                    }
                    glBindFramebuffer(GL_FRAMEBUFFER, 0);
                    layer.space = local;
                    layer.viewCount = 2;
                    layer.views = pviews;
                    ei.layerCount = 1;
                    ei.layers = layers;
                    rendered++;
                }
            }
            XR_CHECK(xrEndFrame(session, &ei));
            int64_t t = nowNs();
            if (!firstFrame) firstFrame = t;
            lastFrame = t;
            frames++;
        }
        rep.line("Estados da sessão: " + states);
        if (frames > 1) {
            double avg = static_cast<double>(lastFrame - firstFrame) / 1e6 / static_cast<double>(frames - 1);
            char buf[160];
            snprintf(buf, sizeof(buf), "Quadros: %llu (%llu com imagem) · intervalo médio %.2f ms (%.0f fps)",
                     static_cast<unsigned long long>(frames), static_cast<unsigned long long>(rendered), avg, avg > 0 ? 1000.0 / avg : 0.0);
            rep.line(buf);
        } else {
            rep.line("Nenhum quadro completo");
        }
        rep.line(exiting ? "Sessão encerrada normalmente (EXITING)." : "Tempo esgotado antes de EXITING.");
    }
cleanup:
    {
        if (fbo) glDeleteFramebuffers(1, &fbo);
        PFN_xrDestroyInstance destroy = nullptr;
        if (instance && gpa) gpa(instance, "xrDestroyInstance", reinterpret_cast<PFN_xrVoidFunction*>(&destroy));
        if (destroy) destroy(instance);  // destroys the session, spaces and swapchains with it
        eglMakeCurrent(dpy, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
        eglDestroySurface(dpy, surf);
        eglDestroyContext(dpy, ctx);
    }
    (void)session; (void)local;
    return rep.text;
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_hyperxr_openxr_OpenXrNative_nativeRuntimeSelfTest(JNIEnv* env, jclass, jobject activity, jint seconds) {
    std::string r = runTest(env, activity, seconds);
    return env->NewStringUTF(r.c_str());
}
