// HyperXR's libretro frontend: runs one emulator core (an embedded .so built from source, see
// native/libretro/README.md) on the caller's thread. Video goes straight into the ANativeWindow of
// the XR window's SurfaceTexture (no copies through Java), audio is returned to Java per frame,
// input is a joypad bitmask set from Java. libretro callbacks carry no user data, so there is one
// core at a time (the emulator panel enforces it).
#include <jni.h>
#include <dlfcn.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <atomic>
#include <cstdarg>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>
#include "libretro.h"

#define LOG_TAG "HyperXR-Retro"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

struct Core {
    void* handle = nullptr;
    void (*init)() = nullptr;
    void (*deinit)() = nullptr;
    void (*get_system_info)(retro_system_info*) = nullptr;
    void (*get_system_av_info)(retro_system_av_info*) = nullptr;
    void (*set_environment)(retro_environment_t) = nullptr;
    void (*set_video_refresh)(retro_video_refresh_t) = nullptr;
    void (*set_audio_sample)(retro_audio_sample_t) = nullptr;
    void (*set_audio_sample_batch)(retro_audio_sample_batch_t) = nullptr;
    void (*set_input_poll)(retro_input_poll_t) = nullptr;
    void (*set_input_state)(retro_input_state_t) = nullptr;
    void (*set_controller_port_device)(unsigned, unsigned) = nullptr;
    void (*reset)() = nullptr;
    void (*run)() = nullptr;
    size_t (*serialize_size)() = nullptr;
    bool (*serialize)(void*, size_t) = nullptr;
    bool (*unserialize)(const void*, size_t) = nullptr;
    bool (*load_game)(const retro_game_info*) = nullptr;
    void (*unload_game)() = nullptr;
    void* (*get_memory_data)(unsigned) = nullptr;
    size_t (*get_memory_size)(unsigned) = nullptr;
};

Core core;
bool gameLoaded = false;
std::string systemDir, saveDir;
std::vector<uint8_t> romData;
std::string romPath;
retro_pixel_format pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
retro_system_av_info av {};
ANativeWindow* window = nullptr;
int winW = 0, winH = 0;
std::atomic<uint32_t> buttons {0};
std::vector<int16_t> audio;
size_t audioMax = 0;

void logCb(enum retro_log_level level, const char* fmt, ...) {
    char buf[1024];
    va_list ap; va_start(ap, fmt); vsnprintf(buf, sizeof buf, fmt, ap); va_end(ap);
    __android_log_print(level >= RETRO_LOG_ERROR ? ANDROID_LOG_ERROR : level == RETRO_LOG_WARN ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, LOG_TAG, "%s", buf);
}

bool environment(unsigned cmd, void* data) {
    switch (cmd) {
        case RETRO_ENVIRONMENT_GET_SYSTEM_DIRECTORY: *static_cast<const char**>(data) = systemDir.c_str(); return true;
        case RETRO_ENVIRONMENT_GET_SAVE_DIRECTORY: *static_cast<const char**>(data) = saveDir.c_str(); return true;
        case RETRO_ENVIRONMENT_SET_PIXEL_FORMAT: {
            auto f = *static_cast<const retro_pixel_format*>(data);
            if (f > RETRO_PIXEL_FORMAT_RGB565) return false;
            pixelFormat = f; return true;
        }
        case RETRO_ENVIRONMENT_GET_LOG_INTERFACE: static_cast<retro_log_callback*>(data)->log = logCb; return true;
        case RETRO_ENVIRONMENT_GET_CAN_DUPE: *static_cast<bool*>(data) = true; return true;
        case RETRO_ENVIRONMENT_GET_INPUT_BITMASKS: return true;
        case RETRO_ENVIRONMENT_SET_GEOMETRY: av.geometry = *static_cast<const retro_game_geometry*>(data); return true;
        case RETRO_ENVIRONMENT_SET_SYSTEM_AV_INFO: av = *static_cast<const retro_system_av_info*>(data); return true;
        case RETRO_ENVIRONMENT_GET_AUDIO_VIDEO_ENABLE: if (data) *static_cast<int*>(data) = 3; return true;
        case RETRO_ENVIRONMENT_GET_VARIABLE_UPDATE: *static_cast<bool*>(data) = false; return true;
        // accepted and ignored: the defaults of each option are used
        case RETRO_ENVIRONMENT_SET_VARIABLES:
        case RETRO_ENVIRONMENT_SET_INPUT_DESCRIPTORS:
        case RETRO_ENVIRONMENT_SET_CONTROLLER_INFO:
        case RETRO_ENVIRONMENT_SET_SUPPORT_NO_GAME:
        case RETRO_ENVIRONMENT_SET_PERFORMANCE_LEVEL:
        case RETRO_ENVIRONMENT_SET_MEMORY_MAPS:
        case RETRO_ENVIRONMENT_SET_SUBSYSTEM_INFO:
        case RETRO_ENVIRONMENT_SET_SERIALIZATION_QUIRKS:
            return true;
        default: return false;
    }
}

inline uint32_t rgbx(uint32_t r, uint32_t g, uint32_t b) { return 0xff000000u | (b << 16) | (g << 8) | r; }

void videoRefresh(const void* data, unsigned width, unsigned height, size_t pitch) {
    if (!data || !window || width == 0 || height == 0) return;  // NULL = duplicate frame
    if ((int) width != winW || (int) height != winH) {
        ANativeWindow_setBuffersGeometry(window, (int) width, (int) height, WINDOW_FORMAT_RGBX_8888);
        winW = (int) width; winH = (int) height;
    }
    ANativeWindow_Buffer buf;
    if (ANativeWindow_lock(window, &buf, nullptr) != 0) return;
    unsigned w = width < (unsigned) buf.width ? width : (unsigned) buf.width;
    unsigned h = height < (unsigned) buf.height ? height : (unsigned) buf.height;
    auto* dst = static_cast<uint32_t*>(buf.bits);
    for (unsigned y = 0; y < h; y++) {
        const uint8_t* row = static_cast<const uint8_t*>(data) + y * pitch;
        uint32_t* out = dst + (size_t) y * buf.stride;
        switch (pixelFormat) {
            case RETRO_PIXEL_FORMAT_XRGB8888: {
                auto* s = reinterpret_cast<const uint32_t*>(row);
                for (unsigned x = 0; x < w; x++) { uint32_t p = s[x]; out[x] = rgbx((p >> 16) & 0xff, (p >> 8) & 0xff, p & 0xff); }
                break;
            }
            case RETRO_PIXEL_FORMAT_RGB565: {
                auto* s = reinterpret_cast<const uint16_t*>(row);
                for (unsigned x = 0; x < w; x++) {
                    uint32_t p = s[x], r = (p >> 11) & 31, g = (p >> 5) & 63, b = p & 31;
                    out[x] = rgbx((r << 3) | (r >> 2), (g << 2) | (g >> 4), (b << 3) | (b >> 2));
                }
                break;
            }
            default: {  // 0RGB1555
                auto* s = reinterpret_cast<const uint16_t*>(row);
                for (unsigned x = 0; x < w; x++) {
                    uint32_t p = s[x], r = (p >> 10) & 31, g = (p >> 5) & 31, b = p & 31;
                    out[x] = rgbx((r << 3) | (r >> 2), (g << 3) | (g >> 2), (b << 3) | (b >> 2));
                }
            }
        }
    }
    ANativeWindow_unlockAndPost(window);
}

void audioSample(int16_t l, int16_t r) {
    if (audio.size() + 2 <= audioMax) { audio.push_back(l); audio.push_back(r); }
}

size_t audioBatch(const int16_t* data, size_t frames) {
    size_t n = frames * 2;
    if (audio.size() + n > audioMax) n = (audioMax - audio.size()) & ~size_t(1);
    audio.insert(audio.end(), data, data + n);
    return frames;
}

void inputPoll() {}

int16_t inputState(unsigned port, unsigned device, unsigned, unsigned id) {
    if (port != 0 || (device & RETRO_DEVICE_MASK) != RETRO_DEVICE_JOYPAD) return 0;
    uint32_t b = buttons.load(std::memory_order_relaxed);
    if (id == RETRO_DEVICE_ID_JOYPAD_MASK) return (int16_t) (b & 0xffff);
    return id < 16 ? (int16_t) ((b >> id) & 1) : 0;
}

template <typename T> bool sym(T& out, const char* name) {
    out = reinterpret_cast<T>(dlsym(core.handle, name));
    if (!out) LOGE("core lacks %s", name);
    return out != nullptr;
}

void closeAll() {
    if (core.handle) {
        if (gameLoaded && core.unload_game) core.unload_game();
        if (core.deinit) core.deinit();
        dlclose(core.handle);
    }
    core = Core();
    gameLoaded = false;
    romData.clear(); romData.shrink_to_fit();
    if (window) { ANativeWindow_release(window); window = nullptr; }
    winW = winH = 0;
    pixelFormat = RETRO_PIXEL_FORMAT_0RGB1555;
}

std::string str(JNIEnv* env, jstring s) {
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string r(c);
    env->ReleaseStringUTFChars(s, c);
    return r;
}

bool readFile(const std::string& path, std::vector<uint8_t>& out) {
    FILE* f = fopen(path.c_str(), "rb");
    if (!f) return false;
    fseek(f, 0, SEEK_END); long n = ftell(f); fseek(f, 0, SEEK_SET);
    if (n < 0) { fclose(f); return false; }
    out.resize((size_t) n);
    bool ok = n == 0 || fread(out.data(), 1, (size_t) n, f) == (size_t) n;
    fclose(f);
    return ok;
}

bool writeFile(const std::string& path, const void* data, size_t n) {
    FILE* f = fopen(path.c_str(), "wb");
    if (!f) return false;
    bool ok = fwrite(data, 1, n, f) == n;
    return fclose(f) == 0 && ok;
}

}  // namespace

extern "C" {

JNIEXPORT jboolean JNICALL Java_com_hyperxr_emulators_RetroNative_open(JNIEnv* env, jobject, jstring lib, jstring sys, jstring save) {
    closeAll();
    std::string name = str(env, lib);
    // the cores export the same retro_* names: RTLD_LOCAL keeps each one to itself
    core.handle = dlopen(name.c_str(), RTLD_NOW | RTLD_LOCAL);
    if (!core.handle) { LOGE("dlopen %s: %s", name.c_str(), dlerror()); return JNI_FALSE; }
    bool ok = sym(core.init, "retro_init") && sym(core.deinit, "retro_deinit") && sym(core.get_system_info, "retro_get_system_info")
        && sym(core.get_system_av_info, "retro_get_system_av_info") && sym(core.set_environment, "retro_set_environment")
        && sym(core.set_video_refresh, "retro_set_video_refresh") && sym(core.set_audio_sample, "retro_set_audio_sample")
        && sym(core.set_audio_sample_batch, "retro_set_audio_sample_batch") && sym(core.set_input_poll, "retro_set_input_poll")
        && sym(core.set_input_state, "retro_set_input_state") && sym(core.set_controller_port_device, "retro_set_controller_port_device")
        && sym(core.reset, "retro_reset") && sym(core.run, "retro_run") && sym(core.serialize_size, "retro_serialize_size")
        && sym(core.serialize, "retro_serialize") && sym(core.unserialize, "retro_unserialize") && sym(core.load_game, "retro_load_game")
        && sym(core.unload_game, "retro_unload_game") && sym(core.get_memory_data, "retro_get_memory_data")
        && sym(core.get_memory_size, "retro_get_memory_size");
    if (!ok) { dlclose(core.handle); core = Core(); return JNI_FALSE; }
    systemDir = str(env, sys); saveDir = str(env, save);
    core.set_environment(environment);
    core.set_video_refresh(videoRefresh);
    core.set_audio_sample(audioSample);
    core.set_audio_sample_batch(audioBatch);
    core.set_input_poll(inputPoll);
    core.set_input_state(inputState);
    core.init();
    return JNI_TRUE;
}

/** "name|version|extensions|need_fullpath" */
JNIEXPORT jstring JNICALL Java_com_hyperxr_emulators_RetroNative_systemInfo(JNIEnv* env, jobject) {
    if (!core.handle) return nullptr;
    retro_system_info i {};
    core.get_system_info(&i);
    std::string s = std::string(i.library_name ? i.library_name : "") + "|" + (i.library_version ? i.library_version : "") + "|"
        + (i.valid_extensions ? i.valid_extensions : "") + "|" + (i.need_fullpath ? "1" : "0");
    return env->NewStringUTF(s.c_str());
}

JNIEXPORT jboolean JNICALL Java_com_hyperxr_emulators_RetroNative_loadGame(JNIEnv* env, jobject, jstring path) {
    if (!core.handle) return JNI_FALSE;
    retro_system_info si {};
    core.get_system_info(&si);
    romPath = str(env, path);
    retro_game_info g {};
    g.path = romPath.c_str();
    if (!si.need_fullpath) {
        if (!readFile(romPath, romData)) { LOGE("cannot read %s", romPath.c_str()); return JNI_FALSE; }
        g.data = romData.data(); g.size = romData.size();
    }
    if (!core.load_game(&g)) { LOGE("core refused %s", romPath.c_str()); return JNI_FALSE; }
    gameLoaded = true;
    core.set_controller_port_device(0, RETRO_DEVICE_JOYPAD);
    core.get_system_av_info(&av);
    LOGI("game loaded: %ux%u @ %.3f fps, %.0f Hz", av.geometry.base_width, av.geometry.base_height, av.timing.fps, av.timing.sample_rate);
    return JNI_TRUE;
}

/** [base width, base height, aspect, fps, sample rate] */
JNIEXPORT jdoubleArray JNICALL Java_com_hyperxr_emulators_RetroNative_avInfo(JNIEnv* env, jobject) {
    double aspect = av.geometry.aspect_ratio > 0 ? av.geometry.aspect_ratio
        : (av.geometry.base_height ? (double) av.geometry.base_width / av.geometry.base_height : 4.0 / 3.0);
    double v[5] = { (double) av.geometry.base_width, (double) av.geometry.base_height, aspect, av.timing.fps, av.timing.sample_rate };
    jdoubleArray a = env->NewDoubleArray(5);
    env->SetDoubleArrayRegion(a, 0, 5, v);
    return a;
}

JNIEXPORT void JNICALL Java_com_hyperxr_emulators_RetroNative_setSurface(JNIEnv* env, jobject, jobject surface) {
    if (window) { ANativeWindow_release(window); window = nullptr; }
    winW = winH = 0;
    if (surface) window = ANativeWindow_fromSurface(env, surface);
}

JNIEXPORT void JNICALL Java_com_hyperxr_emulators_RetroNative_setButtons(JNIEnv*, jobject, jint mask) {
    buttons.store((uint32_t) mask, std::memory_order_relaxed);
}

/** Runs one frame; its audio (interleaved stereo) goes into [out]; returns the sample count. */
JNIEXPORT jint JNICALL Java_com_hyperxr_emulators_RetroNative_runFrame(JNIEnv* env, jobject, jshortArray out) {
    if (!gameLoaded) return 0;
    audioMax = (size_t) env->GetArrayLength(out);
    audio.clear();
    if (audio.capacity() < audioMax) audio.reserve(audioMax);
    core.run();
    if (!audio.empty()) env->SetShortArrayRegion(out, 0, (jsize) audio.size(), audio.data());
    return (jint) audio.size();
}

JNIEXPORT void JNICALL Java_com_hyperxr_emulators_RetroNative_reset(JNIEnv*, jobject) { if (gameLoaded) core.reset(); }

/** Battery-backed save memory (the game's own saves) to/from a file. */
JNIEXPORT jboolean JNICALL Java_com_hyperxr_emulators_RetroNative_saveRam(JNIEnv* env, jobject, jstring path) {
    if (!gameLoaded) return JNI_FALSE;
    size_t n = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void* d = core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    if (!n || !d) return JNI_FALSE;
    return writeFile(str(env, path), d, n) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_hyperxr_emulators_RetroNative_loadRam(JNIEnv* env, jobject, jstring path) {
    if (!gameLoaded) return JNI_FALSE;
    size_t n = core.get_memory_size(RETRO_MEMORY_SAVE_RAM);
    void* d = core.get_memory_data(RETRO_MEMORY_SAVE_RAM);
    std::vector<uint8_t> f;
    if (!n || !d || !readFile(str(env, path), f)) return JNI_FALSE;
    memcpy(d, f.data(), f.size() < n ? f.size() : n);
    return JNI_TRUE;
}

/** Save states (the whole machine at this instant). */
JNIEXPORT jboolean JNICALL Java_com_hyperxr_emulators_RetroNative_saveState(JNIEnv* env, jobject, jstring path) {
    if (!gameLoaded) return JNI_FALSE;
    size_t n = core.serialize_size();
    if (!n) return JNI_FALSE;
    std::vector<uint8_t> buf(n);
    if (!core.serialize(buf.data(), n)) return JNI_FALSE;
    return writeFile(str(env, path), buf.data(), n) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL Java_com_hyperxr_emulators_RetroNative_loadState(JNIEnv* env, jobject, jstring path) {
    if (!gameLoaded) return JNI_FALSE;
    std::vector<uint8_t> buf;
    if (!readFile(str(env, path), buf) || buf.empty()) return JNI_FALSE;
    return core.unserialize(buf.data(), buf.size()) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL Java_com_hyperxr_emulators_RetroNative_close(JNIEnv*, jobject) { closeAll(); }

}  // extern "C"
