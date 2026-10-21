// Enumerates NNAPI devices (Android 10+) through dlopen, so HyperXR reports the accelerators the
// driver actually exposes instead of assuming every SoC has an NPU reachable through NNAPI.
#include <jni.h>
#include <dlfcn.h>
#include <string>
#include <vector>

namespace {
struct ANeuralNetworksDevice;
using GetDeviceCount = int (*)(uint32_t*);
using GetDevice = int (*)(uint32_t, ANeuralNetworksDevice**);
using GetName = int (*)(const ANeuralNetworksDevice*, const char**);
using GetType = int (*)(const ANeuralNetworksDevice*, int32_t*);
using GetFeatureLevel = int (*)(const ANeuralNetworksDevice*, int64_t*);

const char* typeName(int32_t t) {
    switch (t) {
        case 1: return "other";
        case 2: return "cpu";
        case 3: return "gpu";
        case 4: return "accelerator";
        default: return "unknown";
    }
}
}  // namespace

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_hyperxr_diagnostics_NnapiNative_nativeDevices(JNIEnv* env, jclass) {
    std::vector<std::string> rows;
    void* lib = dlopen("libneuralnetworks.so", RTLD_NOW | RTLD_LOCAL);
    if (lib) {
        auto count = reinterpret_cast<GetDeviceCount>(dlsym(lib, "ANeuralNetworks_getDeviceCount"));
        auto get = reinterpret_cast<GetDevice>(dlsym(lib, "ANeuralNetworks_getDevice"));
        auto name = reinterpret_cast<GetName>(dlsym(lib, "ANeuralNetworksDevice_getName"));
        auto type = reinterpret_cast<GetType>(dlsym(lib, "ANeuralNetworksDevice_getType"));
        auto level = reinterpret_cast<GetFeatureLevel>(dlsym(lib, "ANeuralNetworksDevice_getFeatureLevel"));
        uint32_t n = 0;
        if (count && get && name && type && count(&n) == 0) {
            for (uint32_t i = 0; i < n; i++) {
                ANeuralNetworksDevice* d = nullptr;
                if (get(i, &d) != 0 || !d) continue;
                const char* nm = "?";
                int32_t ty = 0;
                int64_t fl = 0;
                name(d, &nm);
                type(d, &ty);
                if (level) level(d, &fl);
                rows.push_back(std::string(nm) + "|" + typeName(ty) + "|" + std::to_string(fl));
            }
        }
        dlclose(lib);
    }
    jobjectArray arr = env->NewObjectArray(static_cast<jsize>(rows.size()), env->FindClass("java/lang/String"), nullptr);
    for (size_t i = 0; i < rows.size(); i++) {
        jstring s = env->NewStringUTF(rows[i].c_str());
        env->SetObjectArrayElement(arr, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return arr;
}
