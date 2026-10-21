// HyperXR LuaJIT bridge.
//
// One LuaVm per running package. Hot data never crosses JNI per entity: Lua writes draw, UI and
// panel commands into direct ByteBuffers shared with Kotlin, and reads input from another one.
// Only resource operations (text textures, models, audio, files, network) go through
// LuaHost.hostCall(), whose arguments travel through a preallocated argument buffer so numeric
// calls do not allocate.
#include <jni.h>
#include <android/log.h>
#include <cstdint>
#include <cstring>
#include <ctime>
#include <string>
#include <unordered_map>

extern "C" {
#include "lua.h"
#include "lualib.h"
#include "lauxlib.h"
#include "luajit.h"
}

#define LOG_TAG "HyperXR-Lua"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kInputFloats = 256;
constexpr int kHeaderFloats = 32;
static_assert(kHeaderFloats > 28, "header must hold the counters written after each frame");
constexpr int kDrawStride = 18;
constexpr int kUiStride = 12;
constexpr int kPanelStride = 14;
constexpr int kMaxArgs = 16;
constexpr int kArgStride = 16;  // int32 tag, int32 pad, double value

enum ArgTag { kNil = 0, kNumber = 1, kBool = 2, kString = 3 };

struct Vm {
    lua_State* L = nullptr;
    int runtimeRef = LUA_NOREF;
    JNIEnv* env = nullptr;  // valid only during a call from Java
    jobject host = nullptr;
    jmethodID hostCall = nullptr;
    jmethodID onLog = nullptr;
    jobjectArray strArgs = nullptr;
    jobjectArray strRets = nullptr;
    float* input = nullptr;
    float* header = nullptr;
    float* draws = nullptr;
    int drawCap = 0;
    float* ui = nullptr;
    int uiCap = 0;
    float* panels = nullptr;
    int panelCap = 0;
    uint8_t* args = nullptr;
    int drawCount = 0, uiCount = 0, panelCount = 0, overflow = 0;
    std::unordered_map<std::string, std::string> sdk;
    std::string lastError;
};

Vm* vmOf(lua_State* L) { return static_cast<Vm*>(lua_touserdata(L, lua_upvalueindex(1))); }

int l_input(lua_State* L) {
    Vm* vm = vmOf(L);
    int i = static_cast<int>(luaL_checkinteger(L, 1));
    lua_pushnumber(L, (i >= 0 && i < kInputFloats) ? vm->input[i] : 0.0);
    return 1;
}

int l_header(lua_State* L) {
    Vm* vm = vmOf(L);
    int i = static_cast<int>(luaL_checkinteger(L, 1));
    if (i >= 0 && i < 24) vm->header[i] = static_cast<float>(luaL_checknumber(L, 2));
    return 0;
}

int l_draw(lua_State* L) {
    Vm* vm = vmOf(L);
    if (vm->drawCount >= vm->drawCap) { vm->overflow++; return 0; }
    float* d = vm->draws + vm->drawCount * kDrawStride;
    for (int i = 0; i < 17; i++) d[i] = static_cast<float>(lua_tonumber(L, i + 1));
    d[17] = 0.f;
    vm->drawCount++;
    return 0;
}

int l_ui(lua_State* L) {
    Vm* vm = vmOf(L);
    if (vm->uiCount >= vm->uiCap) { vm->overflow++; return 0; }
    float* d = vm->ui + vm->uiCount * kUiStride;
    for (int i = 0; i < kUiStride; i++) d[i] = static_cast<float>(lua_tonumber(L, i + 1));
    vm->uiCount++;
    return 0;
}

int l_panel(lua_State* L) {
    Vm* vm = vmOf(L);
    if (vm->panelCount >= vm->panelCap) { vm->overflow++; return 0; }
    float* d = vm->panels + vm->panelCount * kPanelStride;
    for (int i = 0; i < kPanelStride; i++) d[i] = static_cast<float>(lua_tonumber(L, i + 1));
    vm->panelCount++;
    return 0;
}

int l_clock(lua_State* L) {
    timespec ts{};
    clock_gettime(CLOCK_MONOTONIC, &ts);
    lua_pushnumber(L, static_cast<double>(ts.tv_sec) + ts.tv_nsec * 1e-9);
    return 1;
}

int l_log(lua_State* L) {
    Vm* vm = vmOf(L);
    int level = static_cast<int>(luaL_optinteger(L, 1, 1));
    const char* msg = luaL_optstring(L, 2, "");
    __android_log_print(level >= 3 ? ANDROID_LOG_ERROR : level == 2 ? ANDROID_LOG_WARN : ANDROID_LOG_INFO, LOG_TAG, "%s", msg);
    if (vm->env && vm->onLog) {
        jstring s = vm->env->NewStringUTF(msg);
        vm->env->CallVoidMethod(vm->host, vm->onLog, level, s);
        vm->env->DeleteLocalRef(s);
        if (vm->env->ExceptionCheck()) vm->env->ExceptionClear();
    }
    return 0;
}

int l_sdk(lua_State* L) {
    Vm* vm = vmOf(L);
    auto it = vm->sdk.find(luaL_checkstring(L, 1));
    if (it == vm->sdk.end()) { lua_pushnil(L); return 1; }
    lua_pushlstring(L, it->second.data(), it->second.size());
    return 1;
}

// __hx.call(name, ...) -> LuaHost.hostCall(name, argc) with arguments in the shared buffer.
int l_call(lua_State* L) {
    Vm* vm = vmOf(L);
    JNIEnv* env = vm->env;
    if (!env) return luaL_error(L, "host call outside of a frame");
    const char* name = luaL_checkstring(L, 1);
    int argc = lua_gettop(L) - 1;
    if (argc > kMaxArgs) return luaL_error(L, "too many arguments to host call %s", name);
    int strIndex = 0;
    for (int i = 0; i < argc; i++) {
        uint8_t* slot = vm->args + i * kArgStride;
        int32_t tag = kNil;
        double value = 0;
        int idx = i + 2;
        switch (lua_type(L, idx)) {
            case LUA_TNUMBER: tag = kNumber; value = lua_tonumber(L, idx); break;
            case LUA_TBOOLEAN: tag = kBool; value = lua_toboolean(L, idx) ? 1 : 0; break;
            case LUA_TSTRING: {
                size_t len = 0;
                const char* s = lua_tolstring(L, idx, &len);
                if (len > 4 * 1024 * 1024) return luaL_error(L, "string argument too large");
                // JNI NewStringUTF expects modified UTF-8; package strings are UTF-8 text.
                jstring js = env->NewStringUTF(std::string(s, len).c_str());
                env->SetObjectArrayElement(vm->strArgs, strIndex, js);
                env->DeleteLocalRef(js);
                tag = kString;
                value = strIndex++;
                break;
            }
            default: tag = kNil;
        }
        memcpy(slot, &tag, 4);
        memcpy(slot + 8, &value, 8);
    }
    jstring jname = env->NewStringUTF(name);
    jint retc = env->CallIntMethod(vm->host, vm->hostCall, jname, argc);
    env->DeleteLocalRef(jname);
    if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        return luaL_error(L, "host call %s failed", name);
    }
    if (retc < 0) retc = 0;
    if (retc > kMaxArgs) retc = kMaxArgs;
    for (int i = 0; i < retc; i++) {
        uint8_t* slot = vm->args + i * kArgStride;
        int32_t tag;
        double value;
        memcpy(&tag, slot, 4);
        memcpy(&value, slot + 8, 8);
        switch (tag) {
            case kNumber: lua_pushnumber(L, value); break;
            case kBool: lua_pushboolean(L, value != 0); break;
            case kString: {
                auto js = static_cast<jstring>(env->GetObjectArrayElement(vm->strRets, static_cast<int>(value)));
                if (!js) { lua_pushnil(L); break; }
                const char* utf = env->GetStringUTFChars(js, nullptr);
                jsize utfLen = env->GetStringUTFLength(js);
                lua_pushlstring(L, utf, utfLen);
                env->ReleaseStringUTFChars(js, utf);
                env->DeleteLocalRef(js);
                break;
            }
            default: lua_pushnil(L);
        }
    }
    return retc;
}

void pushNative(lua_State* L, Vm* vm) {
    static const luaL_Reg fns[] = {
        {"input", l_input}, {"header", l_header}, {"draw", l_draw}, {"ui", l_ui}, {"panel", l_panel},
        {"clock", l_clock}, {"log", l_log}, {"sdk", l_sdk}, {"call", l_call}, {nullptr, nullptr}};
    lua_newtable(L);
    for (const luaL_Reg* r = fns; r->name; r++) {
        lua_pushlightuserdata(L, vm);
        lua_pushcclosure(L, r->func, 1);
        lua_setfield(L, -2, r->name);
    }
}

std::string jstr(JNIEnv* env, jstring s) {
    if (!s) return {};
    const char* c = env->GetStringUTFChars(s, nullptr);
    std::string out(c);
    env->ReleaseStringUTFChars(s, c);
    return out;
}

template <typename T>
T* directBuffer(JNIEnv* env, jobject buf, int* capBytes) {
    *capBytes = static_cast<int>(env->GetDirectBufferCapacity(buf));
    return static_cast<T*>(env->GetDirectBufferAddress(buf));
}

// Calls runtime[fnName](args...) with a traceback handler; returns false and stores the error.
bool callRuntime(Vm* vm, const char* fn, int nargs, int nresults) {
    lua_State* L = vm->L;
    // stack: args...
    lua_rawgeti(L, LUA_REGISTRYINDEX, vm->runtimeRef);
    lua_getfield(L, -1, fn);
    lua_remove(L, -2);
    lua_insert(L, -(nargs + 1));
    if (lua_pcall(L, nargs, nresults, 0) != 0) {
        vm->lastError = lua_tostring(L, -1) ? lua_tostring(L, -1) : "unknown error";
        lua_pop(L, 1);
        LOGE("runtime.%s failed: %s", fn, vm->lastError.c_str());
        return false;
    }
    return true;
}

}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL
Java_com_hyperxr_luajit_LuaVm_nativeCreate(JNIEnv* env, jclass, jobject host, jobjectArray sdkNames, jobjectArray sdkSources,
                                           jstring id, jstring name, jstring version, jstring mode, jstring entry,
                                           jobjectArray perms, jboolean strict, jint budget, jint memoryMb,
                                           jfloat windowW, jfloat windowH,
                                           jobject input, jobject header, jobject draws, jobject ui, jobject panels, jobject args,
                                           jobjectArray strArgs, jobjectArray strRets) {
    auto* vm = new Vm();
    int cap = 0;
    vm->input = directBuffer<float>(env, input, &cap);
    vm->header = directBuffer<float>(env, header, &cap);
    vm->draws = directBuffer<float>(env, draws, &cap);
    vm->drawCap = cap / (4 * kDrawStride);
    vm->ui = directBuffer<float>(env, ui, &cap);
    vm->uiCap = cap / (4 * kUiStride);
    vm->panels = directBuffer<float>(env, panels, &cap);
    vm->panelCap = cap / (4 * kPanelStride);
    vm->args = directBuffer<uint8_t>(env, args, &cap);
    if (!vm->input || !vm->header || !vm->draws || !vm->ui || !vm->panels || !vm->args || cap < kMaxArgs * kArgStride) {
        LOGE("invalid buffers");
        delete vm;
        return 0;
    }
    vm->host = env->NewGlobalRef(host);
    jclass hostClass = env->GetObjectClass(host);
    vm->hostCall = env->GetMethodID(hostClass, "hostCall", "(Ljava/lang/String;I)I");
    vm->onLog = env->GetMethodID(hostClass, "onLog", "(ILjava/lang/String;)V");
    vm->strArgs = static_cast<jobjectArray>(env->NewGlobalRef(strArgs));
    vm->strRets = static_cast<jobjectArray>(env->NewGlobalRef(strRets));

    jsize n = env->GetArrayLength(sdkNames);
    for (jsize i = 0; i < n; i++) {
        auto k = static_cast<jstring>(env->GetObjectArrayElement(sdkNames, i));
        auto v = static_cast<jstring>(env->GetObjectArrayElement(sdkSources, i));
        vm->sdk[jstr(env, k)] = jstr(env, v);
        env->DeleteLocalRef(k);
        env->DeleteLocalRef(v);
    }

    vm->L = luaL_newstate();
    if (!vm->L) { LOGE("luaL_newstate failed"); delete vm; return 0; }
    luaL_openlibs(vm->L);
    lua_State* L = vm->L;

    auto boot = vm->sdk.find("bootstrap");
    if (boot == vm->sdk.end() || luaL_loadbuffer(L, boot->second.data(), boot->second.size(), "@bootstrap.lua") != 0) {
        vm->lastError = lua_tostring(L, -1) ? lua_tostring(L, -1) : "bootstrap missing";
        LOGE("%s", vm->lastError.c_str());
        lua_close(L);
        delete vm;
        return 0;
    }
    pushNative(L, vm);
    lua_newtable(L);  // config
    auto setStr = [&](const char* key, jstring s) { std::string v = jstr(env, s); lua_pushlstring(L, v.data(), v.size()); lua_setfield(L, -2, key); };
    setStr("id", id); setStr("name", name); setStr("version", version); setStr("mode", mode); setStr("entry", entry);
    lua_pushboolean(L, strict); lua_setfield(L, -2, "strict");
    lua_pushinteger(L, budget); lua_setfield(L, -2, "budget");
    lua_pushinteger(L, memoryMb); lua_setfield(L, -2, "memoryMB");
    lua_newtable(L);
    lua_pushnumber(L, windowW); lua_setfield(L, -2, "w");
    lua_pushnumber(L, windowH); lua_setfield(L, -2, "h");
    lua_setfield(L, -2, "window");
    lua_newtable(L);
    jsize pn = env->GetArrayLength(perms);
    for (jsize i = 0; i < pn; i++) {
        auto p = static_cast<jstring>(env->GetObjectArrayElement(perms, i));
        std::string key = jstr(env, p);
        env->DeleteLocalRef(p);
        lua_pushboolean(L, 1);
        lua_setfield(L, -2, key.c_str());
    }
    lua_setfield(L, -2, "perms");

    vm->env = env;
    int rc = lua_pcall(L, 2, 1, 0);
    vm->env = nullptr;
    if (rc != 0 || !lua_istable(L, -1)) {
        vm->lastError = lua_tostring(L, -1) ? lua_tostring(L, -1) : "bootstrap did not return a runtime";
        LOGE("bootstrap failed: %s", vm->lastError.c_str());
        lua_close(L);
        env->DeleteGlobalRef(vm->host);
        env->DeleteGlobalRef(vm->strArgs);
        env->DeleteGlobalRef(vm->strRets);
        delete vm;
        return 0;
    }
    vm->runtimeRef = luaL_ref(L, LUA_REGISTRYINDEX);
    LOGI("LuaVM created for package (%s)", LUAJIT_VERSION);
    return reinterpret_cast<jlong>(vm);
}

JNIEXPORT jint JNICALL
Java_com_hyperxr_luajit_LuaVm_nativeFrame(JNIEnv* env, jclass, jlong handle, jfloat dt) {
    auto* vm = reinterpret_cast<Vm*>(handle);
    if (!vm) return -1;
    vm->drawCount = vm->uiCount = vm->panelCount = vm->overflow = 0;
    vm->env = env;
    lua_pushnumber(vm->L, dt);
    bool ok = callRuntime(vm, "frame", 1, 0);
    vm->env = nullptr;
    vm->header[24] = static_cast<float>(vm->drawCount);
    vm->header[25] = static_cast<float>(vm->uiCount);
    vm->header[26] = static_cast<float>(vm->panelCount);
    vm->header[27] = static_cast<float>(vm->overflow);
    vm->header[28] = static_cast<float>(lua_gc(vm->L, LUA_GCCOUNT, 0));
    return ok ? vm->drawCount : -1;
}

JNIEXPORT void JNICALL
Java_com_hyperxr_luajit_LuaVm_nativeEvent(JNIEnv* env, jclass, jlong handle, jstring kind, jint id, jint status, jstring data) {
    auto* vm = reinterpret_cast<Vm*>(handle);
    if (!vm) return;
    vm->env = env;
    std::string k = jstr(env, kind), d = jstr(env, data);
    lua_pushstring(vm->L, k.c_str());
    lua_pushinteger(vm->L, id);
    lua_pushinteger(vm->L, status);
    lua_pushlstring(vm->L, d.data(), d.size());
    callRuntime(vm, "event", 4, 0);
    vm->env = nullptr;
}

JNIEXPORT jstring JNICALL
Java_com_hyperxr_luajit_LuaVm_nativeCrashReason(JNIEnv* env, jclass, jlong handle) {
    auto* vm = reinterpret_cast<Vm*>(handle);
    if (!vm) return nullptr;
    lua_rawgeti(vm->L, LUA_REGISTRYINDEX, vm->runtimeRef);
    lua_getfield(vm->L, -1, "isCrashed");
    lua_remove(vm->L, -2);
    std::string reason;
    if (lua_pcall(vm->L, 0, 1, 0) == 0 && lua_isstring(vm->L, -1)) reason = lua_tostring(vm->L, -1);
    lua_pop(vm->L, 1);
    if (reason.empty()) reason = vm->lastError;
    return reason.empty() ? nullptr : env->NewStringUTF(reason.c_str());
}

JNIEXPORT void JNICALL
Java_com_hyperxr_luajit_LuaVm_nativeDestroy(JNIEnv* env, jclass, jlong handle) {
    auto* vm = reinterpret_cast<Vm*>(handle);
    if (!vm) return;
    vm->env = env;
    callRuntime(vm, "shutdown", 0, 0);
    vm->env = nullptr;
    luaL_unref(vm->L, LUA_REGISTRYINDEX, vm->runtimeRef);
    lua_close(vm->L);
    env->DeleteGlobalRef(vm->host);
    env->DeleteGlobalRef(vm->strArgs);
    env->DeleteGlobalRef(vm->strRets);
    delete vm;
}

JNIEXPORT jstring JNICALL
Java_com_hyperxr_luajit_LuaVm_nativeVersion(JNIEnv* env, jclass) {
    return env->NewStringUTF(LUAJIT_VERSION);
}

}  // extern "C"
