// Compatibility-Layer as the VrApi system driver: unpatched VrApi games run on it.
//
// The libvrapi.so inside a Gear VR / Quest game is only a loader. vrapi_Initialize opens the package
// com.oculus.systemdriver, calls com.oculus.systemdriver.DriverLoader.load64 (load32 in 32-bit games,
// load64Ext/load32Ext with one more argument) and gets back the address of a function that returns the
// driver's function for a name ("vrapi_Initialize", "vrapi_SubmitFrame2", …). Every vrapi_* call of the
// game then goes to that function. Compatibility-Layer's own VrApi (the same code as the drop-in libvrapi.so) is
// that driver here, so the game's APK stays as it is.

#include <dlfcn.h>
#include <jni.h>
#include <android/log.h>

#include <cstring>
#include <string>

namespace {

void *
self()
{
	Dl_info info = {};
	if (dladdr(reinterpret_cast<void *>(&self), &info) == 0 || info.dli_fname == nullptr) {
		return nullptr;
	}
	return dlopen(info.dli_fname, RTLD_NOW | RTLD_NOLOAD);
}

// The loader asks for every VrApi function by its exported name.
void *
get_proc(const char *name)
{
	static void *handle = self();
	if (handle == nullptr || name == nullptr) {
		return nullptr;
	}
	void *function = dlsym(handle, name);
	// Older loaders (1.1.2x) also ask for work-in-progress names such as vrapi_SubmitFrame2_temp.
	if (function == nullptr) {
		const char *suffix = std::strstr(name, "_temp");
		if (suffix != nullptr && suffix[5] == '\0') {
			function = dlsym(handle, std::string(name, suffix).c_str());
		}
	}
	if (function == nullptr) {
		__android_log_print(ANDROID_LOG_WARN, "Compatibility-Layer-VrApi", "driver: no %s", name);
	}
	return function;
}

} // namespace

extern "C" __attribute__((visibility("default"))) JNIEXPORT jlong JNICALL
Java_com_oculus_systemdriver_DriverLoader_procAddress(JNIEnv *, jclass)
{
	__android_log_print(ANDROID_LOG_INFO, "Compatibility-Layer-VrApi", "Compatibility-Layer VrApi driver loaded");
	return static_cast<jlong>(reinterpret_cast<intptr_t>(&get_proc));
}
