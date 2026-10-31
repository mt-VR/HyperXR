// HyperXR OpenXR runtime, Android platform layer:
//  - 3DoF head tracking from the game rotation vector + gyroscope prediction (same model as the
//    HyperXR app: landscape phone in a viewer, neck model, recenter on first pose);
//  - viewer configuration from the HyperXR app (content provider) and the display;
//  - compositor: OpenGL ES context shared with the application's, drawing both eyes side by side
//    with radial lens pre-distortion and rotational timewarp into a full-screen surface added on
//    top of the application's activity (com.hyperxr.openxr.RuntimeSurface, loaded from our APK).
#include "hx_runtime.h"

#include <android/log.h>
#include <android/looper.h>
#include <android/native_window_jni.h>
#include <android/sensor.h>
#include <GLES3/gl3.h>
#include <dlfcn.h>
#include <pthread.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <sys/mman.h>
#include <sys/stat.h>
#include <stdio.h>

#define LOG_TAG "HyperMagic-Runtime"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

namespace {

// ------------------------------------------------------------------ JNI
JavaVM* gVm = nullptr;
jobject gContext = nullptr;   // activity when known, else application context (global ref)
bool gIsActivity = false;
pthread_key_t gDetachKey;
pthread_once_t gKeyOnce = PTHREAD_ONCE_INIT;
int gDisplayRotation = 1;     // Surface.ROTATION_90
float gRefreshHz = 60.0f;

void detachThread(void*) { if (gVm) gVm->DetachCurrentThread(); }
void makeKey() { pthread_key_create(&gDetachKey, detachThread); }

/** JNIEnv for the calling thread; threads we attach are detached automatically when they exit. */
JNIEnv* env() {
    if (!gVm) return nullptr;
    JNIEnv* e = nullptr;
    if (gVm->GetEnv(reinterpret_cast<void**>(&e), JNI_VERSION_1_6) == JNI_OK) return e;
    pthread_once(&gKeyOnce, makeKey);
    if (gVm->AttachCurrentThread(&e, nullptr) != JNI_OK) return nullptr;
    pthread_setspecific(gDetachKey, reinterpret_cast<void*>(1));
    return e;
}

bool clearException(JNIEnv* e) {
    if (e->ExceptionCheck()) { e->ExceptionDescribe(); e->ExceptionClear(); return true; }
    return false;
}

/** Path of the APK this library was loaded from (lib dir extracted, or "apk!/lib/..."). */
bool ourApkPath(char* out, size_t cap) {
    Dl_info info;
    if (!dladdr(reinterpret_cast<void*>(&ourApkPath), &info) || !info.dli_fname) return false;
    const char* p = info.dli_fname;
    const char* bang = strstr(p, "!/");
    if (bang) {
        size_t n = static_cast<size_t>(bang - p);
        if (n >= cap) return false;
        memcpy(out, p, n); out[n] = 0;
        return true;
    }
    // .../<package>-<suffix>/lib/<abi>/libhyperxr_openxr.so -> .../<package>-<suffix>/base.apk
    const char* lib = strstr(p, "/lib/");
    if (!lib) return false;
    size_t n = static_cast<size_t>(lib - p);
    if (n + 10 >= cap) return false;
    memcpy(out, p, n);
    memcpy(out + n, "/base.apk", 10);
    return true;
}

/** Loads one of our Java classes through a class loader on our APK (we run in a foreign app). */
jclass loadOurClass(JNIEnv* e, const char* dottedName) {
    char apk[512];
    if (!ourApkPath(apk, sizeof(apk)) || !gContext) return nullptr;
    jclass ctxCls = e->GetObjectClass(gContext);
    jmethodID getCl = e->GetMethodID(ctxCls, "getClassLoader", "()Ljava/lang/ClassLoader;");
    jobject parent = e->CallObjectMethod(gContext, getCl);
    if (clearException(e)) return nullptr;
    jclass pcl = e->FindClass("dalvik/system/PathClassLoader");
    jmethodID ctor = e->GetMethodID(pcl, "<init>", "(Ljava/lang/String;Ljava/lang/ClassLoader;)V");
    jstring jpath = e->NewStringUTF(apk);
    jobject loader = e->NewObject(pcl, ctor, jpath, parent);
    if (clearException(e) || !loader) return nullptr;
    jmethodID loadClass = e->GetMethodID(pcl, "loadClass", "(Ljava/lang/String;)Ljava/lang/Class;");
    jstring jname = e->NewStringUTF(dottedName);
    jobject cls = e->CallObjectMethod(loader, loadClass, jname);
    if (clearException(e) || !cls) { LOGW("cannot load %s from %s", dottedName, apk); return nullptr; }
    return static_cast<jclass>(e->NewGlobalRef(cls));
}

jclass gSurfaceClass = nullptr;
// Direct buffer filled by RuntimeSurface's input capture (layout documented there).
volatile const uint8_t* gInputBuf = nullptr;

// ------------------------------------------------------------------ head tracking
pthread_mutex_t gTrackLock = PTHREAD_MUTEX_INITIALIZER;
XrQuaternionf gDeviceRot = {0, 0, 0, 1};
float gGyro[3] = {0, 0, 0};
int64_t gSampleBootNs = 0;
bool gHaveSample = false;
bool gRecenter = true;
XrQuaternionf gYawOffset = {0, 0, 0, 1};
pthread_t gSensorThread;
volatile bool gSensorRun = false;
ALooper* gSensorLooper = nullptr;
int gTrackerUsers = 0;

int64_t clockNs(clockid_t id) {
    struct timespec ts;
    clock_gettime(id, &ts);
    return static_cast<int64_t>(ts.tv_sec) * 1000000000LL + ts.tv_nsec;
}

void* sensorMain(void*) {
    ALooper* looper = ALooper_prepare(ALOOPER_PREPARE_ALLOW_NON_CALLBACKS);
    gSensorLooper = looper;
    ALooper_acquire(looper);
    ASensorManager* sm = ASensorManager_getInstanceForPackage("com.hyperxr.openxr");
    if (!sm) { LOGW("no sensor manager"); return nullptr; }
    ASensorEventQueue* q = ASensorManager_createEventQueue(sm, looper, 1, nullptr, nullptr);
    const ASensor* rot = ASensorManager_getDefaultSensor(sm, 15 /* GAME_ROTATION_VECTOR */);
    if (!rot) rot = ASensorManager_getDefaultSensor(sm, ASENSOR_TYPE_ROTATION_VECTOR);
    const ASensor* gyro = ASensorManager_getDefaultSensor(sm, ASENSOR_TYPE_GYROSCOPE);
    if (rot) { ASensorEventQueue_enableSensor(q, rot); ASensorEventQueue_setEventRate(q, rot, 5000); }
    if (gyro) { ASensorEventQueue_enableSensor(q, gyro); ASensorEventQueue_setEventRate(q, gyro, 2500); }
    if (!rot) LOGW("no rotation vector sensor: head tracking unavailable");
    ASensorEvent ev[16];
    while (gSensorRun) {
        ALooper_pollOnce(100, nullptr, nullptr, nullptr);
        ssize_t n;
        while ((n = ASensorEventQueue_getEvents(q, ev, 16)) > 0) {
            pthread_mutex_lock(&gTrackLock);
            for (ssize_t i = 0; i < n; i++) {
                const ASensorEvent& e = ev[i];
                if (e.type == 15 || e.type == ASENSOR_TYPE_ROTATION_VECTOR) {
                    float x = e.data[0], y = e.data[1], z = e.data[2];
                    float w = e.data[3];
                    if (!(w > 0 || w < 0)) { float s = 1 - x * x - y * y - z * z; w = s > 0 ? sqrtf(s) : 0; }
                    gDeviceRot = hx::qnorm({x, y, z, w});
                    gSampleBootNs = e.timestamp;
                    gHaveSample = true;
                } else if (e.type == ASENSOR_TYPE_GYROSCOPE) {
                    gGyro[0] = e.data[0]; gGyro[1] = e.data[1]; gGyro[2] = e.data[2];
                }
            }
            pthread_mutex_unlock(&gTrackLock);
        }
    }
    if (rot) ASensorEventQueue_disableSensor(q, rot);
    if (gyro) ASensorEventQueue_disableSensor(q, gyro);
    ASensorManager_destroyEventQueue(sm, q);
    return nullptr;  // the looper reference taken above is released by trackerStop()
}

// ------------------------------------------------------------------ hands
/** Maps content://com.hypermagic.runtime/<name> read-write ([size] bytes), or null. */
void* mapShared(const char* name, size_t size) {
    JNIEnv* e = env();
    if (!e || !gContext) return nullptr;
    jclass ctxCls = e->GetObjectClass(gContext);
    jobject cr = e->CallObjectMethod(gContext, e->GetMethodID(ctxCls, "getContentResolver", "()Landroid/content/ContentResolver;"));
    if (clearException(e) || !cr) return nullptr;
    jclass uriCls = e->FindClass("android/net/Uri");
    char url[96];
    snprintf(url, sizeof(url), "content://com.hypermagic.runtime/%s", name);
    jstring s = e->NewStringUTF(url);
    jobject uri = e->CallStaticObjectMethod(uriCls, e->GetStaticMethodID(uriCls, "parse", "(Ljava/lang/String;)Landroid/net/Uri;"), s);
    jstring mode = e->NewStringUTF("rw");
    jclass crCls = e->GetObjectClass(cr);
    jobject pfd = e->CallObjectMethod(cr, e->GetMethodID(crCls, "openFileDescriptor", "(Landroid/net/Uri;Ljava/lang/String;)Landroid/os/ParcelFileDescriptor;"), uri, mode);
    if (clearException(e) || !pfd) { LOGI("HyperMagic %s not reachable", name); return nullptr; }
    int fd = e->CallIntMethod(pfd, e->GetMethodID(e->GetObjectClass(pfd), "detachFd", "()I"));
    if (clearException(e) || fd < 0) return nullptr;
    struct stat st;
    if (fstat(fd, &st) != 0 || static_cast<size_t>(st.st_size) < size) { close(fd); LOGW("%s: wrong size", name); return nullptr; }
    void* p = mmap(nullptr, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
    close(fd);
    if (p == MAP_FAILED) { LOGW("%s: mmap failed", name); return nullptr; }
    LOGI("%s from HyperMagic mapped", name);
    return p;
}

HxHandShared* mapHands() {
    void* p = mapShared("hands", sizeof(HxHandShared));
    if (p && static_cast<HxHandShared*>(p)->magic != HX_HANDS_MAGIC) { munmap(p, sizeof(HxHandShared)); return nullptr; }
    return static_cast<HxHandShared*>(p);
}

/** Camera frames for passthrough, mapped on first use (retried at most every 2 s). */
HxCameraShared* cameraFrames() {
    static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
    static HxCameraShared* map = nullptr;
    static int64_t lastTryNs = 0;
    pthread_mutex_lock(&lock);
    int64_t now = clockNs(CLOCK_MONOTONIC);
    if (!map && now - lastTryNs > 2000000000LL) {
        lastTryNs = now;
        void* p = mapShared("camera", sizeof(HxCameraShared));
        if (p && static_cast<HxCameraShared*>(p)->magic != HX_CAMERA_MAGIC) { munmap(p, sizeof(HxCameraShared)); p = nullptr; }
        map = static_cast<HxCameraShared*>(p);
    }
    HxCameraShared* m = map;
    pthread_mutex_unlock(&lock);
    return m;
}

// ------------------------------------------------------------------ config
void queryProvider(HxConfig* c) {
    JNIEnv* e = env();
    if (!e || !gContext) return;
    jclass ctxCls = e->GetObjectClass(gContext);
    jmethodID getCr = e->GetMethodID(ctxCls, "getContentResolver", "()Landroid/content/ContentResolver;");
    jobject cr = e->CallObjectMethod(gContext, getCr);
    if (clearException(e) || !cr) return;
    jclass uriCls = e->FindClass("android/net/Uri");
    jmethodID parse = e->GetStaticMethodID(uriCls, "parse", "(Ljava/lang/String;)Landroid/net/Uri;");
    jstring s = e->NewStringUTF("content://com.hypermagic.runtime/viewer");
    jobject uri = e->CallStaticObjectMethod(uriCls, parse, s);
    jclass crCls = e->GetObjectClass(cr);
    jmethodID query = e->GetMethodID(crCls, "query", "(Landroid/net/Uri;[Ljava/lang/String;Ljava/lang/String;[Ljava/lang/String;Ljava/lang/String;)Landroid/database/Cursor;");
    jobject cur = e->CallObjectMethod(cr, query, uri, nullptr, nullptr, nullptr, nullptr);
    if (clearException(e) || !cur) { LOGI("HyperMagic settings not reachable: using default viewer values"); return; }
    jclass curCls = e->GetObjectClass(cur);
    jmethodID moveToFirst = e->GetMethodID(curCls, "moveToFirst", "()Z");
    jmethodID getFloat = e->GetMethodID(curCls, "getFloat", "(I)F");
    jmethodID getInt = e->GetMethodID(curCls, "getInt", "(I)I");
    jmethodID close = e->GetMethodID(curCls, "close", "()V");
    if (e->CallBooleanMethod(cur, moveToFirst)) {
        c->ipdM = e->CallFloatMethod(cur, getFloat, 0) / 1000.0f;
        c->fovDeg = e->CallFloatMethod(cur, getFloat, 1);
        c->k1 = e->CallFloatMethod(cur, getFloat, 2);
        c->k2 = e->CallFloatMethod(cur, getFloat, 3);
        c->lensDistortion = e->CallIntMethod(cur, getInt, 4);
        LOGI("viewer from HyperMagic: ipd %.1f mm, fov %.0f, k1 %.2f, k2 %.2f", c->ipdM * 1000, c->fovDeg, c->k1, c->k2);
    }
    e->CallVoidMethod(cur, close);
    clearException(e);
}

void queryDisplay(HxConfig* c) {
    JNIEnv* e = env();
    if (!e || !gContext) return;
    jclass ctxCls = e->GetObjectClass(gContext);
    jmethodID getRes = e->GetMethodID(ctxCls, "getResources", "()Landroid/content/res/Resources;");
    jobject res = e->CallObjectMethod(gContext, getRes);
    if (clearException(e) || !res) return;
    jmethodID getDm = e->GetMethodID(e->GetObjectClass(res), "getDisplayMetrics", "()Landroid/util/DisplayMetrics;");
    jobject dm = e->CallObjectMethod(res, getDm);
    if (clearException(e) || !dm) return;
    jclass dmCls = e->GetObjectClass(dm);
    int w = e->GetIntField(dm, e->GetFieldID(dmCls, "widthPixels", "I"));
    int h = e->GetIntField(dm, e->GetFieldID(dmCls, "heightPixels", "I"));
    int lo = w < h ? w : h, hi = w < h ? h : w;
    if (lo > 0) { c->eyeWidthPx = hi / 2; c->eyeHeightPx = lo; }
    if (gIsActivity && gSurfaceClass) {
        jmethodID rot = e->GetStaticMethodID(gSurfaceClass, "displayRotation", "(Landroid/app/Activity;)I");
        jmethodID hz = e->GetStaticMethodID(gSurfaceClass, "refreshRate", "(Landroid/app/Activity;)F");
        if (rot) gDisplayRotation = e->CallStaticIntMethod(gSurfaceClass, rot, gContext);
        if (hz) gRefreshHz = e->CallStaticFloatMethod(gSurfaceClass, hz, gContext);
        clearException(e);
    }
    c->refreshHz = gRefreshHz > 20 ? gRefreshHz : 60;
}

}  // namespace

// ------------------------------------------------------------------ compositor
struct HxSwapchainGfx {
    HxSessionGfx* g;
    GLuint tex[3];
    GLsync fence[3];
    uint32_t count;
    uint32_t width, height, array;
    GLenum target;
    bool srgb;
    bool depth;
};

struct HxSessionGfx {
    EGLDisplay dpy;
    EGLConfig cfg;
    EGLContext ctx;
    EGLSurface pbuffer;
    EGLSurface window;
    ANativeWindow* nativeWindow;
    jobject surfaceHelper;     // RuntimeSurface (global ref)
    jobject lastSurface;       // android.view.Surface (global ref) the window was made from
    GLuint progProj, progProjArray, progQuad, progQuadArray, progCamera;
    GLuint vao;
    GLuint cameraTex;          // latest passthrough frame
    GLuint mapTex, lutTex[2];  // passthrough colour style
    GLuint trisTex;            // projected passthrough triangles
    uint32_t styleVersion;
    uint32_t cameraSeq;        // HxCameraShared::seq of the frame in cameraTex
    uint32_t cameraW, cameraH;
    float cameraIntr[4];       // fx, fy, cx, cy
    int64_t cameraTimeNs;
    bool glReady;
    uint64_t frames;
};

namespace {

struct Saved { EGLDisplay d; EGLSurface draw, read; EGLContext c; };
Saved saveCurrent() { return {eglGetCurrentDisplay(), eglGetCurrentSurface(EGL_DRAW), eglGetCurrentSurface(EGL_READ), eglGetCurrentContext()}; }
void restore(const Saved& s, EGLDisplay ours) {
    if (s.c != EGL_NO_CONTEXT) eglMakeCurrent(s.d, s.draw, s.read, s.c);
    else eglMakeCurrent(ours, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
}

const char* kVs = R"(#version 300 es
out vec2 vUv;
void main() {
    vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
    vUv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
})";

// Shared prelude: lens pre-distortion from the output pixel to a direction in the current eye.
const char* kCommon = R"(
precision highp float;
precision highp sampler2DArray;
in vec2 vUv;
uniform vec2 uEyeTan;   // display tangents (h, v) of each eye
uniform vec2 uK;        // k1, k2 (0, 0 = no lens correction)
uniform int uSrgb;
out vec4 fragColor;
vec3 eyeDir() {
    vec2 t = (vUv * 2.0 - 1.0) * uEyeTan;
    float r2 = dot(t, t);
    t *= 1.0 + uK.x * r2 + uK.y * r2 * r2;
    return normalize(vec3(t, -1.0));
}
vec3 toSrgb(vec3 c) {
    vec3 lo = c * 12.92;
    vec3 hi = 1.055 * pow(max(c, vec3(0.0)), vec3(1.0 / 2.4)) - 0.055;
    return mix(hi, lo, vec3(lessThan(c, vec3(0.0031308))));
}
)";

// Projection layer: rotational timewarp (uDelta) + reprojection into the layer's own frustum.
const char* kProj = R"(
uniform mat3 uDelta;    // current eye direction -> rendered view direction
uniform vec4 uFovTan;   // tan(left), tan(right), tan(down), tan(up)
uniform vec4 uRect;     // sub-image offset.xy and extent.xy in texture UV
uniform int uKeepAlpha; // over passthrough: the image's alpha decides how much camera shows
SAMPLER_DECL
void main() {
    vec4 none = vec4(0.0, 0.0, 0.0, uKeepAlpha == 1 ? 0.0 : 1.0);
    vec3 d = uDelta * eyeDir();
    if (d.z > -1e-4) { fragColor = none; return; }
    vec2 p = d.xy / -d.z;
    vec2 uv = vec2((p.x - uFovTan.x) / (uFovTan.y - uFovTan.x), (p.y - uFovTan.z) / (uFovTan.w - uFovTan.z));
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) { fragColor = none; return; }
    vec4 c = SAMPLE(uRect.xy + uv * uRect.zw);
    if (uSrgb == 1) c.rgb = toSrgb(c.rgb);
    fragColor = vec4(c.rgb, uKeepAlpha == 1 ? c.a : 1.0);
})";

// Passthrough: the phone's camera frame, treated as infinitely far (rotation only): the current
// eye direction is turned into the camera's frame and projected with its pinhole intrinsics.
const char* kCamera = R"(
uniform mat3 uEyeRot;      // current eye orientation (stage)
uniform mat3 uCamRotInv;   // stage -> camera at capture time
uniform vec4 uIntr;        // fx, fy, cx, cy (pixels, image y down)
uniform vec2 uSize;        // frame size, pixels
uniform float uOpacity;
// colour style: 0 none, 1 luminance map (256 entries), 2 brightness/contrast/saturation, 3 colour cube(s)
uniform int uColorMode;
uniform sampler2D uMap;
uniform vec3 uBCS;          // brightness (-100..100), contrast, saturation
uniform highp sampler3D uLutA, uLutB;
uniform int uLutHasA;       // 0: A is the camera's own colour
uniform float uLutWeight, uLutRes;
// projected passthrough (XR_FB_triangle_mesh): the camera only on these triangles (stage space)
uniform int uProjected, uTriCount;
uniform highp sampler2D uTris;  // 3 texels (xyz) per triangle
uniform vec3 uEyePos, uCamPos;
SAMPLER_DECL
vec3 cube(highp sampler3D lut, vec3 c) {
    // texel centres: 0 and 1 land on the first and last entries
    return texture(lut, c * ((uLutRes - 1.0) / uLutRes) + 0.5 / uLutRes).rgb;
}
void main() {
    vec3 wd = uEyeRot * eyeDir();
    vec3 d = uCamRotInv * wd;
    if (uProjected == 1) {
        // nearest triangle on the eye's ray (Moller-Trumbore); the camera looks at that very point
        float best = 1e9;
        for (int i = 0; i < 256; i++) {
            if (i >= uTriCount) break;
            vec3 a = texelFetch(uTris, ivec2(i * 3, 0), 0).xyz;
            vec3 e1 = texelFetch(uTris, ivec2(i * 3 + 1, 0), 0).xyz - a;
            vec3 e2 = texelFetch(uTris, ivec2(i * 3 + 2, 0), 0).xyz - a;
            vec3 p = cross(wd, e2);
            float det = dot(e1, p);
            if (abs(det) < 1e-8) continue;
            vec3 t0 = uEyePos - a;
            float u = dot(t0, p) / det;
            if (u < 0.0 || u > 1.0) continue;
            vec3 q = cross(t0, e1);
            float v = dot(wd, q) / det;
            if (v < 0.0 || u + v > 1.0) continue;
            float t = dot(e2, q) / det;
            if (t > 0.0 && t < best) best = t;
        }
        if (best > 1e8) discard;
        d = uCamRotInv * (uEyePos + wd * best - uCamPos);
    }
    if (d.z > -1e-4) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    vec2 px = vec2(uIntr.z + uIntr.x * d.x / -d.z, uIntr.w - uIntr.y * d.y / -d.z);
    vec2 uv = px / uSize;
    if (any(lessThan(uv, vec2(0.0))) || any(greaterThan(uv, vec2(1.0)))) { fragColor = vec4(0.0, 0.0, 0.0, 1.0); return; }
    vec3 c = SAMPLE(uv).rgb;
    float a = 1.0;
    float y = dot(c, vec3(0.299, 0.587, 0.114));
    if (uColorMode == 1) {
        vec4 m = texture(uMap, vec2((y * 255.0 + 0.5) / 256.0, 0.5));
        c = m.rgb; a = m.a;
    } else if (uColorMode == 2) {
        c = (c - 0.5) * uBCS.y + 0.5 + uBCS.x / 100.0;
        c = mix(vec3(dot(c, vec3(0.299, 0.587, 0.114))), c, uBCS.z);
    } else if (uColorMode == 3) {
        vec3 from = uLutHasA == 1 ? cube(uLutA, clamp(c, 0.0, 1.0)) : c;
        c = mix(from, cube(uLutB, clamp(c, 0.0, 1.0)), uLutWeight);
    }
    fragColor = vec4(clamp(c, 0.0, 1.0) * uOpacity * a, 1.0);
})";

// Quad, cylinder and equirect layers: the ray from the current eye, in the layer's space, hits a
// plane, a vertical cylinder or a sphere (the same steps as hxlayers::hit, which the tests check).
const char* kQuad = R"(
uniform mat3 uEyeRot;      // current eye orientation (stage)
uniform vec3 uEyePos;
uniform mat3 uQuadRotInv;  // stage -> layer
uniform vec3 uQuadPos;
uniform vec2 uQuadSize;
uniform int uShape;        // 0 quad, 1 cylinder, 2 equirect
uniform float uRadius, uCentral, uAspect, uUpper, uLower;
uniform vec4 uRect;
uniform int uBlend;
SAMPLER_DECL
void main() {
    vec3 d = uQuadRotInv * (uEyeRot * eyeDir());
    vec3 o = uQuadRotInv * (uEyePos - uQuadPos);
    vec2 h;
    if (uShape == 0) {
        if (abs(d.z) < 1e-6) discard;
        float s = -o.z / d.z;
        if (s <= 0.0) discard;
        h = (o + d * s).xy / uQuadSize + 0.5;
    } else if (uShape == 1) {
        float a = dot(d.xz, d.xz);
        if (a < 1e-9) discard;
        float b = 2.0 * dot(o.xz, d.xz);
        float c = dot(o.xz, o.xz) - uRadius * uRadius;
        float disc = b * b - 4.0 * a * c;
        if (disc < 0.0) discard;
        float t = c > 0.0 ? (-b - sqrt(disc)) / (2.0 * a) : (-b + sqrt(disc)) / (2.0 * a);
        if (t <= 0.0) discard;
        vec3 p = o + d * t;
        h = vec2(atan(p.x, -p.z) / uCentral + 0.5, p.y / (uRadius * uCentral / uAspect) + 0.5);
    } else {
        vec3 p = d;
        if (uRadius > 0.0) {
            float a = dot(d, d), b = 2.0 * dot(o, d), c = dot(o, o) - uRadius * uRadius;
            float disc = b * b - 4.0 * a * c;
            if (disc < 0.0) discard;
            float t = (-b + sqrt(disc)) / (2.0 * a);
            if (t <= 0.0) discard;
            p = o + d * t;
        }
        p = normalize(p);
        h = vec2(atan(p.x, -p.z) / uCentral + 0.5, (asin(clamp(p.y, -1.0, 1.0)) - uLower) / (uUpper - uLower));
    }
    if (any(lessThan(h, vec2(0.0))) || any(greaterThan(h, vec2(1.0)))) discard;
    vec4 c = SAMPLE(uRect.xy + h * uRect.zw);
    if (uSrgb == 1) c.rgb = toSrgb(c.rgb);
    fragColor = vec4(c.rgb, uBlend == 1 ? c.a : 1.0);
})";

GLuint compile(GLenum type, const char* const* parts, int n) {
    GLuint s = glCreateShader(type);
    glShaderSource(s, n, parts, nullptr);
    glCompileShader(s);
    GLint ok = 0;
    glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
    if (!ok) { char log[1024]; glGetShaderInfoLog(s, sizeof(log), nullptr, log); LOGW("shader: %s", log); }
    return s;
}

GLuint program(const char* body, bool array) {
    const char* header = "#version 300 es\n";
    const char* sampler = array ? "#define SAMPLER_DECL uniform sampler2DArray uTex; uniform float uSlice;\n#define SAMPLE(uv) texture(uTex, vec3(uv, uSlice))\n"
                                : "#define SAMPLER_DECL uniform sampler2D uTex;\n#define SAMPLE(uv) texture(uTex, uv)\n";
    const char* fs[] = {header, sampler, kCommon, body};
    GLuint v = compile(GL_VERTEX_SHADER, &kVs, 1);
    GLuint f = compile(GL_FRAGMENT_SHADER, fs, 4);
    GLuint p = glCreateProgram();
    glAttachShader(p, v); glAttachShader(p, f);
    glLinkProgram(p);
    glDeleteShader(v); glDeleteShader(f);
    GLint ok = 0;
    glGetProgramiv(p, GL_LINK_STATUS, &ok);
    if (!ok) { char log[1024]; glGetProgramInfoLog(p, sizeof(log), nullptr, log); LOGW("link: %s", log); }
    return p;
}

void mat3FromQuat(XrQuaternionf q, float* m) {  // column-major
    float x = q.x, y = q.y, z = q.z, w = q.w;
    m[0] = 1 - 2 * (y * y + z * z); m[1] = 2 * (x * y + w * z);     m[2] = 2 * (x * z - w * y);
    m[3] = 2 * (x * y - w * z);     m[4] = 1 - 2 * (x * x + z * z); m[5] = 2 * (y * z + w * x);
    m[6] = 2 * (x * z + w * y);     m[7] = 2 * (y * z - w * x);     m[8] = 1 - 2 * (x * x + y * y);
}

/** Creates/updates the EGL window surface from the RuntimeSurface helper's current Surface. */
void updateWindow(HxSessionGfx* g) {
    JNIEnv* e = env();
    if (!e || !g->surfaceHelper) return;
    jclass cls = e->GetObjectClass(g->surfaceHelper);
    jmethodID getSurface = e->GetMethodID(cls, "getSurface", "()Landroid/view/Surface;");
    jobject s = e->CallObjectMethod(g->surfaceHelper, getSurface);
    if (clearException(e)) return;
    bool same = (s == nullptr && g->lastSurface == nullptr) || (s && g->lastSurface && e->IsSameObject(s, g->lastSurface));
    if (same) { if (s) e->DeleteLocalRef(s); return; }
    if (g->window != EGL_NO_SURFACE) { eglDestroySurface(g->dpy, g->window); g->window = EGL_NO_SURFACE; }
    if (g->nativeWindow) { ANativeWindow_release(g->nativeWindow); g->nativeWindow = nullptr; }
    if (g->lastSurface) { e->DeleteGlobalRef(g->lastSurface); g->lastSurface = nullptr; }
    if (!s) return;
    g->lastSurface = e->NewGlobalRef(s);
    g->nativeWindow = ANativeWindow_fromSurface(e, s);
    e->DeleteLocalRef(s);
    if (g->nativeWindow) {
        g->window = eglCreateWindowSurface(g->dpy, g->cfg, g->nativeWindow, nullptr);
        if (g->window == EGL_NO_SURFACE) LOGW("eglCreateWindowSurface failed: 0x%x", eglGetError());
        else LOGI("compositor surface ready (%dx%d)", ANativeWindow_getWidth(g->nativeWindow), ANativeWindow_getHeight(g->nativeWindow));
    }
}

void initGl(HxSessionGfx* g) {
    if (g->glReady) return;
    g->progProj = program(kProj, false);
    g->progProjArray = program(kProj, true);
    g->progQuad = program(kQuad, false);
    g->progQuadArray = program(kQuad, true);
    g->progCamera = program(kCamera, false);
    glGenVertexArrays(1, &g->vao);
    glGenTextures(1, &g->cameraTex);
    glGenTextures(1, &g->mapTex);
    glGenTextures(2, g->lutTex);
    glGenTextures(1, &g->trisTex);
    g->glReady = true;
}

void bindLayerTexture(GLuint prog, HxSwapchainGfx* sc, uint32_t index, uint32_t slice, const XrRect2Di& r) {
    glActiveTexture(GL_TEXTURE0);
    glBindTexture(sc->target, sc->tex[index]);
    glTexParameteri(sc->target, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(sc->target, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(sc->target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(sc->target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    glUniform1i(glGetUniformLocation(prog, "uTex"), 0);
    if (sc->target == GL_TEXTURE_2D_ARRAY) glUniform1f(glGetUniformLocation(prog, "uSlice"), static_cast<float>(slice));
    glUniform4f(glGetUniformLocation(prog, "uRect"), static_cast<float>(r.offset.x) / sc->width, static_cast<float>(r.offset.y) / sc->height,
                static_cast<float>(r.extent.width) / sc->width, static_cast<float>(r.extent.height) / sc->height);
    glUniform1i(glGetUniformLocation(prog, "uSrgb"), sc->srgb ? 1 : 0);
}

void waitFence(HxSwapchainGfx* sc, uint32_t index) {
    if (sc->fence[index]) {
        glWaitSync(sc->fence[index], 0, GL_TIMEOUT_IGNORED);
        glDeleteSync(sc->fence[index]);
        sc->fence[index] = nullptr;
    }
}

}  // namespace

namespace hxp {

int64_t nowNs() { return clockNs(CLOCK_MONOTONIC); }

void init(void* vm, void* context) {
    if (!vm || !context) return;
    gVm = static_cast<JavaVM*>(vm);
    JNIEnv* e = env();
    if (!e) return;
    jobject ctx = static_cast<jobject>(context);
    jclass activityCls = e->FindClass("android/app/Activity");
    bool isActivity = activityCls && e->IsInstanceOf(ctx, activityCls);
    clearException(e);
    // prefer the activity (needed for the overlay); keep an application context otherwise
    if (gContext && gIsActivity && !isActivity) return;
    if (gContext) e->DeleteGlobalRef(gContext);
    gContext = e->NewGlobalRef(ctx);
    gIsActivity = isActivity;
    if (!gSurfaceClass) gSurfaceClass = loadOurClass(e, "com.hyperxr.openxr.RuntimeSurface");
}

void loadConfig(HxConfig* c) {
    c->ipdM = 0.063f; c->fovDeg = 95; c->k1 = 0.22f; c->k2 = 0.24f; c->eyeHeightM = 1.6f;
    c->eyeWidthPx = 1200; c->eyeHeightPx = 1080; c->refreshHz = 60; c->lensDistortion = 1;
    queryDisplay(c);
    queryProvider(c);
}

void trackerStart() {
    pthread_mutex_lock(&gTrackLock);
    bool start = gTrackerUsers++ == 0;
    gRecenter = true;
    pthread_mutex_unlock(&gTrackLock);
    if (!start) return;
    gSensorRun = true;
    pthread_create(&gSensorThread, nullptr, sensorMain, nullptr);
}

void trackerStop() {
    pthread_mutex_lock(&gTrackLock);
    bool stop = --gTrackerUsers == 0;
    pthread_mutex_unlock(&gTrackLock);
    if (!stop) return;
    gSensorRun = false;
    if (gSensorLooper) ALooper_wake(gSensorLooper);
    pthread_join(gSensorThread, nullptr);
    if (gSensorLooper) { ALooper_release(gSensorLooper); gSensorLooper = nullptr; }
}

void readInput(HxInput* out) {
    memset(out, 0, sizeof(*out));
    const volatile uint8_t* b = gInputBuf;
    if (!b) return;
    auto i32 = [b](int off) { int32_t v; memcpy(&v, const_cast<const uint8_t*>(b) + off, 4); return v; };
    auto f32 = [b](int off) { float v; memcpy(&v, const_cast<const uint8_t*>(b) + off, 4); return v; };
    out->buttons = static_cast<uint32_t>(i32(0));
    out->stick[0][0] = f32(4); out->stick[0][1] = f32(8);
    out->stick[1][0] = f32(12); out->stick[1][1] = f32(16);
    out->trigger[0] = f32(20); out->trigger[1] = f32(24);
    out->squeeze[0] = f32(28); out->squeeze[1] = f32(32);
    out->touch = i32(36);
}

void recenter() { pthread_mutex_lock(&gTrackLock); gRecenter = true; pthread_mutex_unlock(&gTrackLock); }

/**
 * Hands from HyperMagic: the app's shared file, opened through its provider (any thread; the
 * mapping is made once and kept for the life of the process).
 */
void passthroughWanted() {
    if (HxCameraShared* c = cameraFrames()) __atomic_store_n(&c->readerWantsNs, nowNs(), __ATOMIC_RELAXED);
}

/** The hands file, mapped on first use (retried at most every 2 s). */
HxHandShared* handsMap() {
    static pthread_mutex_t lock = PTHREAD_MUTEX_INITIALIZER;
    static HxHandShared* map = nullptr;
    static int64_t lastTryNs = 0;
    pthread_mutex_lock(&lock);
    if (!map && nowNs() - lastTryNs > 2000000000LL) {
        lastTryNs = nowNs();
        map = mapHands();
    }
    HxHandShared* m = map;
    pthread_mutex_unlock(&lock);
    return m;
}

void haptic(int hand, float amplitude, int64_t durationNs) {
    HxHandShared* m = handsMap();
    if (!m) return;
    m->hapticHand = static_cast<uint32_t>(hand);
    m->hapticAmplitude = amplitude;
    m->hapticDurationMs = static_cast<int32_t>(durationNs / 1000000);
    __atomic_fetch_add(&m->hapticSeq, 1u, __ATOMIC_RELEASE);
}

bool readHands(HxHandShared* out, bool wantsHands) {
    HxHandShared* m = handsMap();
    if (!m) return false;
    int64_t now = nowNs();
    __atomic_store_n(&m->readerAliveNs, now, __ATOMIC_RELAXED);
    if (wantsHands) __atomic_store_n(&m->readerWantsHandsNs, now, __ATOMIC_RELAXED);
    // sequence lock: copy until the writer was not in the middle of an update
    for (int tries = 0; tries < 8; tries++) {
        uint32_t s1 = __atomic_load_n(&m->seq, __ATOMIC_ACQUIRE);
        if (s1 & 1u) continue;
        memcpy(out, m, sizeof(HxHandShared));
        __atomic_thread_fence(__ATOMIC_ACQUIRE);
        if (__atomic_load_n(&m->seq, __ATOMIC_RELAXED) == s1) return out->magic == HX_HANDS_MAGIC;
    }
    return false;
}

bool headPose(int64_t timeNs, const HxConfig& cfg, XrPosef* out) {
    pthread_mutex_lock(&gTrackLock);
    if (!gHaveSample) { pthread_mutex_unlock(&gTrackLock); return false; }
    XrQuaternionf q = gDeviceRot;
    float w[3] = {gGyro[0], gGyro[1], gGyro[2]};
    int64_t sample = gSampleBootNs;
    pthread_mutex_unlock(&gTrackLock);
    // sensor timestamps are CLOCK_BOOTTIME; XrTime is CLOCK_MONOTONIC
    int64_t targetBoot = timeNs + (clockNs(CLOCK_BOOTTIME) - clockNs(CLOCK_MONOTONIC));
    int64_t ahead = targetBoot - sample;
    if (ahead < 0) ahead = 0;
    if (ahead > 60000000) ahead = 60000000;
    float dt = static_cast<float>(ahead) / 1e9f;
    float mag = sqrtf(w[0] * w[0] + w[1] * w[1] + w[2] * w[2]);
    if (mag * dt > 1e-9f) q = hx::qnorm(hx::qmul(q, hx::qaxis(w[0], w[1], w[2], mag * dt)));
    // Android world (X east, Y north, Z up) -> OpenXR (Y up, -Z forward); landscape device frame.
    const float hp = static_cast<float>(M_PI) / 2;
    XrQuaternionf worldFix = hx::qaxis(1, 0, 0, -hp);
    XrQuaternionf deviceFix = gDisplayRotation == 3 /* ROTATION_270 */ ? hx::qaxis(0, 0, 1, hp) : hx::qaxis(0, 0, 1, -hp);
    XrQuaternionf head = hx::qmul(hx::qmul(worldFix, q), deviceFix);
    pthread_mutex_lock(&gTrackLock);
    if (gRecenter) { gYawOffset = hx::qconj(hx::yawOnly(head)); gRecenter = false; }
    XrQuaternionf yaw = gYawOffset;
    pthread_mutex_unlock(&gTrackLock);
    out->orientation = hx::qnorm(hx::qmul(yaw, head));
    // neck model: the eyes are ~8 cm in front of and ~7.5 cm above the neck pivot
    XrVector3f eyeFromNeck = hx::qrot(out->orientation, {0, 0.075f, -0.08f});
    out->position = {eyeFromNeck.x, cfg.eyeHeightM - 0.075f + eyeFromNeck.y, 0.08f + eyeFromNeck.z};
    return true;
}

XrResult gfxCreateSession(const void* binding, HxSessionGfx** out) {
    auto* b = static_cast<const XrGraphicsBindingOpenGLESAndroidKHR*>(binding);
    if (!b->display || !b->context) return XR_ERROR_GRAPHICS_DEVICE_INVALID;
    HxSessionGfx* g = static_cast<HxSessionGfx*>(calloc(1, sizeof(HxSessionGfx)));
    if (!g) return XR_ERROR_OUT_OF_MEMORY;
    g->dpy = b->display;
    const EGLint attribs[] = {EGL_RENDERABLE_TYPE, 0x40 /* EGL_OPENGL_ES3_BIT */, EGL_SURFACE_TYPE, EGL_WINDOW_BIT | EGL_PBUFFER_BIT,
                              EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE};
    EGLint n = 0;
    if (!eglChooseConfig(g->dpy, attribs, &g->cfg, 1, &n) || n < 1) { free(g); return XR_ERROR_GRAPHICS_DEVICE_INVALID; }
    const EGLint ctxAttribs[] = {EGL_CONTEXT_CLIENT_VERSION, 3, EGL_NONE};
    // shares objects (the swapchain textures) with the application's context
    g->ctx = eglCreateContext(g->dpy, g->cfg, b->context, ctxAttribs);
    if (g->ctx == EGL_NO_CONTEXT) { LOGW("eglCreateContext failed: 0x%x", eglGetError()); free(g); return XR_ERROR_GRAPHICS_DEVICE_INVALID; }
    const EGLint pb[] = {EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE};
    g->pbuffer = eglCreatePbufferSurface(g->dpy, g->cfg, pb);
    g->window = EGL_NO_SURFACE;
    JNIEnv* e = env();
    if (e && gContext && gIsActivity && gSurfaceClass) {
        jmethodID create = e->GetStaticMethodID(gSurfaceClass, "create", "(Landroid/app/Activity;)Lcom/hyperxr/openxr/RuntimeSurface;");
        jobject helper = create ? e->CallStaticObjectMethod(gSurfaceClass, create, gContext) : nullptr;
        if (!clearException(e) && helper) g->surfaceHelper = e->NewGlobalRef(helper);
        jmethodID ib = e->GetStaticMethodID(gSurfaceClass, "inputBuffer", "()Ljava/nio/ByteBuffer;");
        jobject buf = ib ? e->CallStaticObjectMethod(gSurfaceClass, ib) : nullptr;
        if (!clearException(e) && buf) {
            // the buffer is a static field of the class: it lives as long as the process
            gInputBuf = static_cast<const uint8_t*>(e->GetDirectBufferAddress(buf));
            e->DeleteLocalRef(buf);
        } else clearException(e);
    }
    if (!g->surfaceHelper) LOGW("no activity/overlay: frames are accepted but not shown");
    *out = g;
    return XR_SUCCESS;
}

void gfxDestroySession(HxSessionGfx* g) {
    if (!g) return;
    Saved s = saveCurrent();
    eglMakeCurrent(g->dpy, g->pbuffer, g->pbuffer, g->ctx);
    if (g->glReady) {
        glDeleteProgram(g->progProj); glDeleteProgram(g->progProjArray);
        glDeleteProgram(g->progQuad); glDeleteProgram(g->progQuadArray); glDeleteProgram(g->progCamera);
        glDeleteVertexArrays(1, &g->vao);
        glDeleteTextures(1, &g->cameraTex);
        glDeleteTextures(1, &g->mapTex);
        glDeleteTextures(2, g->lutTex);
        glDeleteTextures(1, &g->trisTex);
    }
    restore(s, g->dpy);
    if (g->window != EGL_NO_SURFACE) eglDestroySurface(g->dpy, g->window);
    if (g->nativeWindow) ANativeWindow_release(g->nativeWindow);
    if (g->pbuffer != EGL_NO_SURFACE) eglDestroySurface(g->dpy, g->pbuffer);
    eglDestroyContext(g->dpy, g->ctx);
    JNIEnv* e = env();
    if (e && g->surfaceHelper) {
        jmethodID destroy = e->GetMethodID(e->GetObjectClass(g->surfaceHelper), "destroy", "()V");
        if (destroy) e->CallVoidMethod(g->surfaceHelper, destroy);
        clearException(e);
        e->DeleteGlobalRef(g->surfaceHelper);
    }
    if (e && g->lastSurface) e->DeleteGlobalRef(g->lastSurface);
    free(g);
}

XrResult gfxEnumerateFormats(int64_t* formats, uint32_t capacity, uint32_t* count) {
    // preferred first: sRGB colour, linear colour, then depth formats
    const int64_t all[] = {GL_SRGB8_ALPHA8, GL_RGBA8, GL_DEPTH_COMPONENT16, GL_DEPTH_COMPONENT24, GL_DEPTH24_STENCIL8, GL_DEPTH_COMPONENT32F};
    uint32_t n = sizeof(all) / sizeof(all[0]);
    *count = n;
    for (uint32_t i = 0; i < n && i < capacity; i++) formats[i] = all[i];
    return XR_SUCCESS;
}

XrResult gfxCreateSwapchain(HxSessionGfx* g, const XrSwapchainCreateInfo* ci, HxSwapchainGfx** out, uint32_t* imageCount) {
    HxSwapchainGfx* sc = static_cast<HxSwapchainGfx*>(calloc(1, sizeof(HxSwapchainGfx)));
    if (!sc) return XR_ERROR_OUT_OF_MEMORY;
    sc->g = g;
    sc->count = (ci->createFlags & XR_SWAPCHAIN_CREATE_STATIC_IMAGE_BIT) ? 1 : 3;
    sc->width = ci->width; sc->height = ci->height; sc->array = ci->arraySize;
    sc->target = ci->arraySize > 1 ? GL_TEXTURE_2D_ARRAY : GL_TEXTURE_2D;
    sc->srgb = ci->format == GL_SRGB8_ALPHA8;
    sc->depth = ci->format != GL_SRGB8_ALPHA8 && ci->format != GL_RGBA8;
    Saved s = saveCurrent();
    eglMakeCurrent(g->dpy, g->pbuffer, g->pbuffer, g->ctx);
    glGenTextures(static_cast<GLsizei>(sc->count), sc->tex);
    for (uint32_t i = 0; i < sc->count; i++) {
        glBindTexture(sc->target, sc->tex[i]);
        GLsizei levels = static_cast<GLsizei>(ci->mipCount);
        if (sc->target == GL_TEXTURE_2D_ARRAY) glTexStorage3D(sc->target, levels, static_cast<GLenum>(ci->format), ci->width, ci->height, ci->arraySize);
        else glTexStorage2D(sc->target, levels, static_cast<GLenum>(ci->format), ci->width, ci->height);
        glTexParameteri(sc->target, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(sc->target, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    }
    GLenum err = glGetError();
    glBindTexture(sc->target, 0);
    glFlush();  // the textures must exist before the application's context uses them
    restore(s, g->dpy);
    if (err != GL_NO_ERROR) {
        LOGW("swapchain texture creation failed: 0x%x", err);
        gfxDestroySwapchain(sc);
        return XR_ERROR_RUNTIME_FAILURE;
    }
    *out = sc;
    *imageCount = sc->count;
    return XR_SUCCESS;
}

void gfxDestroySwapchain(HxSwapchainGfx* sc) {
    if (!sc) return;
    HxSessionGfx* g = sc->g;
    Saved s = saveCurrent();
    eglMakeCurrent(g->dpy, g->pbuffer, g->pbuffer, g->ctx);
    for (uint32_t i = 0; i < sc->count; i++) if (sc->fence[i]) glDeleteSync(sc->fence[i]);
    glDeleteTextures(static_cast<GLsizei>(sc->count), sc->tex);
    restore(s, g->dpy);
    free(sc);
}

XrResult gfxEnumerateImages(HxSwapchainGfx* sc, uint32_t capacity, uint32_t* count, XrSwapchainImageBaseHeader* images) {
    *count = sc->count;
    if (capacity == 0) return XR_SUCCESS;
    if (capacity < sc->count || !images) return XR_ERROR_SIZE_INSUFFICIENT;
    auto* out = reinterpret_cast<XrSwapchainImageOpenGLESKHR*>(images);
    for (uint32_t i = 0; i < sc->count; i++) {
        if (out[i].type != XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR) return XR_ERROR_VALIDATION_FAILURE;
        out[i].image = sc->tex[i];
    }
    return XR_SUCCESS;
}

void gfxReleaseImage(HxSwapchainGfx* sc, uint32_t index) {
    // The application's context is current here (it just rendered the image): fence its commands
    // so the compositor's context waits for them on the GPU instead of blocking the CPU.
    if (eglGetCurrentContext() == EGL_NO_CONTEXT || index >= sc->count) return;
    if (sc->fence[index]) glDeleteSync(sc->fence[index]);
    sc->fence[index] = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
    glFlush();
}

/** A layer's own blend factors (HxComposeView::blend): turns blending on with them; false if it has none. */
bool applyCustomBlend(const int f[4]) {
    if (f[0] < 0) return false;
    static const GLenum gl[6] = {GL_ZERO, GL_ONE, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA, GL_DST_ALPHA, GL_ONE_MINUS_DST_ALPHA};
    glEnable(GL_BLEND);
    glBlendFuncSeparate(gl[f[0]], gl[f[1]], gl[f[2]], gl[f[3]]);
    return true;
}

/** Uploads the passthrough's colour style (map, cubes) when it changed. */
void updateStyle(HxSessionGfx* g, const HxPassthroughState& pt) {
    if (pt.version == g->styleVersion) return;
    g->styleVersion = pt.version;
    auto params = [](GLenum target) {
        glTexParameteri(target, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
        glTexParameteri(target, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
        glTexParameteri(target, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
        glTexParameteri(target, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
        if (target == GL_TEXTURE_3D) glTexParameteri(target, GL_TEXTURE_WRAP_R, GL_CLAMP_TO_EDGE);
    };
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    if (pt.colorMode == HX_COLOR_MAP && pt.map) {
        glBindTexture(GL_TEXTURE_2D, g->mapTex);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, 256, 1, 0, GL_RGBA, GL_UNSIGNED_BYTE, pt.map);
        params(GL_TEXTURE_2D);
    }
    if (pt.colorMode == HX_COLOR_LUT && pt.lutB && pt.lutRes > 1) {
        const uint8_t* cubes[2] = {pt.lutA, pt.lutB};
        for (int i = 0; i < 2; i++) {
            if (!cubes[i]) continue;
            glBindTexture(GL_TEXTURE_3D, g->lutTex[i]);
            glTexImage3D(GL_TEXTURE_3D, 0, GL_RGBA8, pt.lutRes, pt.lutRes, pt.lutRes, 0, GL_RGBA, GL_UNSIGNED_BYTE, cubes[i]);
            params(GL_TEXTURE_3D);
        }
    }
}

/** Uploads the newest passthrough frame into g->cameraTex; false when there is none yet. */
bool updateCameraTexture(HxSessionGfx* g) {
    HxCameraShared* c = cameraFrames();
    if (!c) return false;
    uint32_t seq = __atomic_load_n(&c->seq, __ATOMIC_ACQUIRE);
    if (seq == 0) return false;
    if (seq == g->cameraSeq) return true;
    uint32_t idx = c->latest & 1u, w = c->width, h = c->height;
    if (w == 0 || h == 0 || w > HX_CAMERA_MAX_W || h > HX_CAMERA_MAX_H) return g->cameraSeq != 0;
    glBindTexture(GL_TEXTURE_2D, g->cameraTex);
    glPixelStorei(GL_UNPACK_ALIGNMENT, 4);
    if (w != g->cameraW || h != g->cameraH) {
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, w, h, 0, GL_RGBA, GL_UNSIGNED_BYTE, c->pixels[idx]);
        g->cameraW = w; g->cameraH = h;
    } else {
        glTexSubImage2D(GL_TEXTURE_2D, 0, 0, 0, w, h, GL_RGBA, GL_UNSIGNED_BYTE, c->pixels[idx]);
    }
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_LINEAR);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
    glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
    g->cameraIntr[0] = c->fx; g->cameraIntr[1] = c->fy; g->cameraIntr[2] = c->cx; g->cameraIntr[3] = c->cy;
    g->cameraTimeNs = c->timeNs;
    g->cameraSeq = seq;
    return true;
}

void gfxCompose(HxSessionGfx* g, const HxConfig& cfg, const XrPosef& head, const HxComposeView* views, uint32_t viewCount,
                const HxComposeQuad* quads, uint32_t quadCount, const HxPassthroughState& passthrough) {
    updateWindow(g);
    if (g->window == EGL_NO_SURFACE) return;
    Saved s = saveCurrent();
    if (!eglMakeCurrent(g->dpy, g->window, g->window, g->ctx)) { restore(s, g->dpy); return; }
    initGl(g);
    EGLint w = 0, h = 0;
    eglQuerySurface(g->dpy, g->window, EGL_WIDTH, &w);
    eglQuerySurface(g->dpy, g->window, EGL_HEIGHT, &h);
    for (uint32_t i = 0; i < viewCount; i++) waitFence(views[i].swapchain, views[i].imageIndex);
    for (uint32_t i = 0; i < quadCount; i++) waitFence(quads[i].swapchain, quads[i].imageIndex);

    glBindFramebuffer(GL_FRAMEBUFFER, 0);
    glDisable(GL_DEPTH_TEST);
    glDisable(GL_SCISSOR_TEST);
    glDisable(GL_CULL_FACE);
    glViewport(0, 0, w, h);
    glClearColor(0, 0, 0, 1);
    glClear(GL_COLOR_BUFFER_BIT);
    glBindVertexArray(g->vao);
    int half = w / 2;
    float tanV = tanf(cfg.fovDeg * 0.5f * static_cast<float>(M_PI) / 180.0f);
    float tanH = tanV * static_cast<float>(half) / static_cast<float>(h > 0 ? h : 1);
    float k1 = cfg.lensDistortion ? cfg.k1 : 0, k2 = cfg.lensDistortion ? cfg.k2 : 0;
    float m[9];
    bool camera = passthrough.on && updateCameraTexture(g);
    // the camera rides with the head: it looked where the head looked when the frame was taken
    XrPosef cameraHead = head;
    if (camera && !headPose(g->cameraTimeNs, cfg, &cameraHead)) cameraHead = head;
    for (int eye = 0; eye < 2; eye++) {
        glViewport(eye * half, 0, half, h);
        XrPosef eyePose = hx::compose(head, XrPosef{{0, 0, 0, 1}, {(eye == 0 ? -0.5f : 0.5f) * cfg.ipdM, 0, 0}});
        if (camera) {
            glUseProgram(g->progCamera);
            glDisable(GL_BLEND);
            glUniform2f(glGetUniformLocation(g->progCamera, "uEyeTan"), tanH, tanV);
            glUniform2f(glGetUniformLocation(g->progCamera, "uK"), k1, k2);
            mat3FromQuat(eyePose.orientation, m);
            glUniformMatrix3fv(glGetUniformLocation(g->progCamera, "uEyeRot"), 1, GL_FALSE, m);
            mat3FromQuat(hx::qconj(cameraHead.orientation), m);
            glUniformMatrix3fv(glGetUniformLocation(g->progCamera, "uCamRotInv"), 1, GL_FALSE, m);
            glUniform4f(glGetUniformLocation(g->progCamera, "uIntr"), g->cameraIntr[0], g->cameraIntr[1], g->cameraIntr[2], g->cameraIntr[3]);
            glUniform2f(glGetUniformLocation(g->progCamera, "uSize"), static_cast<float>(g->cameraW), static_cast<float>(g->cameraH));
            glUniform1f(glGetUniformLocation(g->progCamera, "uOpacity"), passthrough.opacity);
            glUniform1i(glGetUniformLocation(g->progCamera, "uSrgb"), 0);
            updateStyle(g, passthrough);
            glUniform1i(glGetUniformLocation(g->progCamera, "uColorMode"), passthrough.colorMode);
            glUniform3f(glGetUniformLocation(g->progCamera, "uBCS"), passthrough.brightness, passthrough.contrast, passthrough.saturation);
            glUniform1i(glGetUniformLocation(g->progCamera, "uLutHasA"), passthrough.lutA ? 1 : 0);
            glUniform1f(glGetUniformLocation(g->progCamera, "uLutWeight"), passthrough.lutWeight);
            glUniform1f(glGetUniformLocation(g->progCamera, "uLutRes"), static_cast<float>(passthrough.lutRes > 1 ? passthrough.lutRes : 2));
            glActiveTexture(GL_TEXTURE1); glBindTexture(GL_TEXTURE_2D, g->mapTex);
            glUniform1i(glGetUniformLocation(g->progCamera, "uMap"), 1);
            glActiveTexture(GL_TEXTURE2); glBindTexture(GL_TEXTURE_3D, g->lutTex[0]);
            glUniform1i(glGetUniformLocation(g->progCamera, "uLutA"), 2);
            glActiveTexture(GL_TEXTURE3); glBindTexture(GL_TEXTURE_3D, g->lutTex[1]);
            glUniform1i(glGetUniformLocation(g->progCamera, "uLutB"), 3);
            // projected surfaces only, when no layer shows the whole view
            bool projectedOnly = !passthrough.reconstruction && passthrough.projectedCount > 0;
            glUniform1i(glGetUniformLocation(g->progCamera, "uProjected"), projectedOnly ? 1 : 0);
            glUniform1i(glGetUniformLocation(g->progCamera, "uTriCount"), projectedOnly ? passthrough.projectedCount : 0);
            glUniform3f(glGetUniformLocation(g->progCamera, "uEyePos"), eyePose.position.x, eyePose.position.y, eyePose.position.z);
            XrVector3f camPos = hx::vadd(cameraHead.position, hx::qrot(cameraHead.orientation, XrVector3f{0, 0, -0.08f}));
            glUniform3f(glGetUniformLocation(g->progCamera, "uCamPos"), camPos.x, camPos.y, camPos.z);
            glActiveTexture(GL_TEXTURE4);
            glBindTexture(GL_TEXTURE_2D, g->trisTex);
            if (projectedOnly && eye == 0) {
                static float texels[256 * 3 * 4];
                for (int t = 0; t < passthrough.projectedCount * 3; t++) {
                    texels[t * 4] = passthrough.projected[t * 3]; texels[t * 4 + 1] = passthrough.projected[t * 3 + 1];
                    texels[t * 4 + 2] = passthrough.projected[t * 3 + 2]; texels[t * 4 + 3] = 1;
                }
                glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA32F, passthrough.projectedCount * 3, 1, 0, GL_RGBA, GL_FLOAT, texels);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
                glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
            }
            glUniform1i(glGetUniformLocation(g->progCamera, "uTris"), 4);
            glActiveTexture(GL_TEXTURE0);
            glBindTexture(GL_TEXTURE_2D, g->cameraTex);
            glUniform1i(glGetUniformLocation(g->progCamera, "uTex"), 0);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        if (viewCount == 2) {
            const HxComposeView& v = views[eye];
            GLuint prog = v.swapchain->target == GL_TEXTURE_2D_ARRAY ? g->progProjArray : g->progProj;
            glUseProgram(prog);
            // over the camera, the app's alpha says how much of the room shows through
            bool over = camera && v.blendAlpha;
            if (applyCustomBlend(v.blend)) over = true;
            else if (over) { glEnable(GL_BLEND); glBlendFunc(v.premultiplied ? GL_ONE : GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); }
            else glDisable(GL_BLEND);
            glUniform1i(glGetUniformLocation(prog, "uKeepAlpha"), over ? 1 : 0);
            glUniform2f(glGetUniformLocation(prog, "uEyeTan"), tanH, tanV);
            glUniform2f(glGetUniformLocation(prog, "uK"), k1, k2);
            // timewarp: where the app rendered this view vs where the eye looks now
            mat3FromQuat(hx::qmul(hx::qconj(v.pose.orientation), eyePose.orientation), m);
            glUniformMatrix3fv(glGetUniformLocation(prog, "uDelta"), 1, GL_FALSE, m);
            glUniform4f(glGetUniformLocation(prog, "uFovTan"), tanf(v.fov.angleLeft), tanf(v.fov.angleRight), tanf(v.fov.angleDown), tanf(v.fov.angleUp));
            bindLayerTexture(prog, v.swapchain, v.imageIndex, v.arraySlice, v.rect);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        for (uint32_t i = 0; i < quadCount; i++) {
            const HxComposeQuad& q = quads[i];
            if (q.eye >= 0 && q.eye != eye) continue;  // eyeVisibility (stereo 3D panels and videos)
            GLuint prog = q.swapchain->target == GL_TEXTURE_2D_ARRAY ? g->progQuadArray : g->progQuad;
            glUseProgram(prog);
            bool custom = applyCustomBlend(q.blend);
            if (custom) {}
            else if (q.blendAlpha) { glEnable(GL_BLEND); glBlendFunc(GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA); } else glDisable(GL_BLEND);
            glUniform2f(glGetUniformLocation(prog, "uEyeTan"), tanH, tanV);
            glUniform2f(glGetUniformLocation(prog, "uK"), k1, k2);
            mat3FromQuat(eyePose.orientation, m);
            glUniformMatrix3fv(glGetUniformLocation(prog, "uEyeRot"), 1, GL_FALSE, m);
            glUniform3f(glGetUniformLocation(prog, "uEyePos"), eyePose.position.x, eyePose.position.y, eyePose.position.z);
            mat3FromQuat(hx::qconj(q.pose.orientation), m);
            glUniformMatrix3fv(glGetUniformLocation(prog, "uQuadRotInv"), 1, GL_FALSE, m);
            glUniform3f(glGetUniformLocation(prog, "uQuadPos"), q.pose.position.x, q.pose.position.y, q.pose.position.z);
            glUniform2f(glGetUniformLocation(prog, "uQuadSize"), q.size.width, q.size.height);
            glUniform1i(glGetUniformLocation(prog, "uShape"), q.shape);
            glUniform1f(glGetUniformLocation(prog, "uRadius"), q.radius);
            glUniform1f(glGetUniformLocation(prog, "uCentral"), q.centralAngle);
            glUniform1f(glGetUniformLocation(prog, "uAspect"), q.aspectRatio);
            glUniform1f(glGetUniformLocation(prog, "uUpper"), q.upperAngle);
            glUniform1f(glGetUniformLocation(prog, "uLower"), q.lowerAngle);
            glUniform1i(glGetUniformLocation(prog, "uBlend"), (q.blendAlpha || custom) ? 1 : 0);
            bindLayerTexture(prog, q.swapchain, q.imageIndex, q.arraySlice, q.rect);
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
    }
    glDisable(GL_BLEND);
    glBindVertexArray(0);
    eglSwapBuffers(g->dpy, g->window);
    g->frames++;
    restore(s, g->dpy);
}

}  // namespace hxp
