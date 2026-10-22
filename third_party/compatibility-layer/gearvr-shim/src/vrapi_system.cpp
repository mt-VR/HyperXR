// Initialization, properties and system calls of the VrApi layer.
#include "vrapi_internal.h"

#include <GLES3/gl3.h>
#include <sys/system_properties.h>

#include <cmath>
#include <cstring>
#include <unistd.h>
#include <thread>
#include <memory>

namespace compatibility-layer {

Global &
global()
{
	static Global instance;
	return instance;
}

bool
backend_ready(bool wait)
{
	Global &state = global();
	if (!state.backend_ready.valid()) {
		return false;
	}
	if (!wait && state.backend_ready.wait_for(std::chrono::seconds(0)) != std::future_status::ready) {
		return false;
	}
	return state.backend_ready.get();
}

namespace {

// Which headset the game should believe it runs on: debug.compatibility_layer.vrapi.product = gearvr | go | quest,
// or by the game's build when it is not set.
Product
configured_product()
{
	char value[PROP_VALUE_MAX] = {};
	__system_property_get("debug.compatibility_layer.vrapi.product", value);
	if (std::strcmp(value, "quest") == 0) {
		return Product::quest;
	}
	if (std::strcmp(value, "go") == 0) {
		return Product::oculus_go;
	}
	if (std::strcmp(value, "gearvr") == 0) {
		return Product::gear_vr;
	}
	// Not set: a 64-bit game is a Quest game (there never was a 64-bit Gear VR), so it gets both
	// hands as tracked Touch controllers; a 32-bit one is Gear VR with its one pointer.
	return sizeof(void *) == 8 ? Product::quest : Product::gear_vr;
}

// Runs a JNI call on the current thread, attaching it to the VM for the call if needed.
template <typename Function>
void
with_env(Function function)
{
	Global &state = global();
	if (state.vm == nullptr) {
		return;
	}
	JNIEnv *env = nullptr;
	bool attached = false;
	if (state.vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
		if (state.vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
			return;
		}
		attached = true;
	}
	function(env);
	if (attached) {
		state.vm->DetachCurrentThread();
	}
}

// Gear VR's quit menu and "return home" both ended up leaving the game.
void
finish_activity()
{
	with_env([](JNIEnv *env) {
		jobject activity = global().activity;
		if (activity == nullptr) {
			return;
		}
		jclass type = env->GetObjectClass(activity);
		jmethodID finish = env->GetMethodID(type, "finish", "()V");
		if (finish != nullptr) {
			env->CallVoidMethod(activity, finish);
		}
		if (env->ExceptionCheck()) {
			env->ExceptionClear();
		}
		env->DeleteLocalRef(type);
	});
}

} // namespace

} // namespace compatibility-layer

using namespace compatibility-layer;

extern "C" {

const char *
vrapi_GetVersionString()
{
	VRAPI_TRACE("vrapi_GetVersionString");
	return "1.1.50.0-Compatibility-Layer";
}

double
vrapi_GetTimeInSeconds()
{
	VRAPI_TRACE("vrapi_GetTimeInSeconds");
	return XrBackend::monotonic_seconds();
}

ovrInitializeStatus
vrapi_Initialize(const ovrInitParms *initParms)
{
	VRAPI_TRACE("vrapi_Initialize");
	if (initParms == nullptr || initParms->Type != VRAPI_STRUCTURE_TYPE_INIT_PARMS ||
	    initParms->Java.Vm == nullptr || initParms->Java.ActivityObject == nullptr) {
		return VRAPI_INITIALIZE_UNKNOWN_ERROR;
	}
	Global &state = global();
	std::lock_guard<std::recursive_mutex> guard(state.lock);
	if (state.initialized) {
		return VRAPI_INITIALIZE_SUCCESS;
	}
	VRAPI_LOG("vrapi_Initialize: game built with VrApi %d.%d.%d.%d, graphics 0x%x", initParms->ProductVersion,
	          initParms->MajorVersion, initParms->MinorVersion, initParms->PatchVersion,
	          static_cast<unsigned>(initParms->GraphicsAPI));
	if ((initParms->GraphicsAPI & VRAPI_GRAPHICS_API_TYPE_OPENGL_ES) == 0) {
		VRAPI_WARN("Only OpenGL ES games are supported, Vulkan is not");
		return VRAPI_INITIALIZE_DEVICE_NOT_SUPPORTED;
	}

	state.vm = initParms->Java.Vm;
	with_env([&](JNIEnv *env) { state.activity = env->NewGlobalRef(initParms->Java.ActivityObject); });
	// Compatibility-Layer Runtime starts in the background. The Oculus driver returned at once, and games are
	// written for that: a NativeActivity game's main thread waits for this thread to take the resume
	// and window events, while connecting to the runtime needs that main thread — waiting here would
	// block both. vrapi_EnterVrMode, which comes once the window is there, waits for it instead.
	// Its own thread, with no looper: for a NativeActivity, Monado's instance creation pumps the
	// calling thread's looper and would hand the game's window events to the game while waiting.
	// The thread stays for the life of the process: the Compatibility-Layer loader unloads and reloads the runtime
	// while starting, and a thread that ran runtime code must not exit and run its destructors then.
	JavaVM *vm = state.vm;
	jobject activity = state.activity;
	auto started = std::make_shared<std::promise<bool>>();
	state.backend_ready = started->get_future().share();
	std::thread([vm, activity, started] {
		const bool up = global().backend.initialize(vm, activity);
		if (!up) {
			VRAPI_WARN("Compatibility-Layer runtime is not available");
		}
		started->set_value(up);
		for (;;) {
			pause();
		}
	}).detach();
	state.product = configured_product();
	state.api_minor_version = initParms->MinorVersion;
	state.initialized = true;
	return VRAPI_INITIALIZE_SUCCESS;
}

void
vrapi_Shutdown()
{
	VRAPI_TRACE("vrapi_Shutdown");
	Global &state = global();
	std::lock_guard<std::recursive_mutex> guard(state.lock);
	if (!state.initialized) {
		return;
	}
	release_swapchain_copies();
	backend_ready(true);
	state.backend.shutdown();
	with_env([&](JNIEnv *env) { env->DeleteGlobalRef(state.activity); });
	state.activity = nullptr;
	state.initialized = false;
}

void
vrapi_SetPropertyInt(const ovrJava *, const ovrProperty, const int)
{
	VRAPI_TRACE("vrapi_SetPropertyInt");}

void
vrapi_SetPropertyFloat(const ovrJava *, const ovrProperty, const float)
{
	VRAPI_TRACE("vrapi_SetPropertyFloat");}

bool
vrapi_GetPropertyInt(const ovrJava *, const ovrProperty propType, int *intVal)
{
	VRAPI_TRACE("vrapi_GetPropertyInt");
	if (intVal == nullptr) {
		return false;
	}
	switch (propType) {
	case VRAPI_ACTIVE_INPUT_DEVICE_ID: *intVal = static_cast<int>(kRightDevice); return true;
	case VRAPI_DEVICE_EMULATION_MODE: *intVal = VRAPI_DEVICE_EMULATION_MODE_NONE; return true;
	case VRAPI_FOVEATION_LEVEL: *intVal = 0; return true;
	case VRAPI_DYNAMIC_FOVEATION_ENABLED: *intVal = 0; return true;
	default: return false;
	}
}

int
vrapi_GetSystemPropertyInt(const ovrJava *, const ovrSystemProperty propType)
{
	VRAPI_TRACE("vrapi_GetSystemPropertyInt");
	Global &state = global();
	const XrBackend &backend = state.backend;
	// Until the runtime is up the size is not known yet: a Quest-like default (copied into the
	// runtime's own swapchain at submit, so any size works).
	const bool up = backend_ready(false);
	const int eye_width = up && backend.recommended_width() != 0 ? static_cast<int>(backend.recommended_width()) : 1536;
	const int eye_height = up && backend.recommended_height() != 0 ? static_cast<int>(backend.recommended_height()) : 1536;
	switch (propType) {
	case VRAPI_SYS_PROP_DEVICE_TYPE:
		switch (state.product) {
		case Product::quest: return VRAPI_DEVICE_TYPE_OCULUSQUEST;
		case Product::oculus_go: return kDeviceOculusGo;
		case Product::gear_vr: return kDeviceGearVr;
		}
		return kDeviceGearVr;
	case VRAPI_SYS_PROP_MAX_FULLSPEED_FRAMEBUFFER_SAMPLES: return 4;
	case VRAPI_SYS_PROP_DISPLAY_PIXELS_WIDE: return eye_width * 2;
	case VRAPI_SYS_PROP_DISPLAY_PIXELS_HIGH: return eye_height;
	case VRAPI_SYS_PROP_DISPLAY_REFRESH_RATE: return 60;
	case VRAPI_SYS_PROP_SUGGESTED_EYE_TEXTURE_WIDTH: return eye_width;
	case VRAPI_SYS_PROP_SUGGESTED_EYE_TEXTURE_HEIGHT: return eye_height;
	case VRAPI_SYS_PROP_DEVICE_REGION: return VRAPI_DEVICE_REGION_UNSPECIFIED;
	case VRAPI_SYS_PROP_DOMINANT_HAND: return VRAPI_HAND_RIGHT;
	case VRAPI_SYS_PROP_HAS_ORIENTATION_TRACKING: return VRAPI_TRUE;
	case VRAPI_SYS_PROP_HAS_POSITION_TRACKING: return state.product == Product::quest ? VRAPI_TRUE : VRAPI_FALSE;
	case VRAPI_SYS_PROP_NUM_SUPPORTED_DISPLAY_REFRESH_RATES: return 1;
	case VRAPI_SYS_PROP_NUM_SUPPORTED_SWAPCHAIN_FORMATS: return 2;
	case VRAPI_SYS_PROP_FOVEATION_AVAILABLE: return VRAPI_FALSE;
	default: return 0;
	}
}

float
vrapi_GetSystemPropertyFloat(const ovrJava *, const ovrSystemProperty propType)
{
	VRAPI_TRACE("vrapi_GetSystemPropertyFloat");
	switch (propType) {
	// Gear VR rendered 90 degrees per eye; the eye matrices carry the runtime's real FOV.
	case VRAPI_SYS_PROP_SUGGESTED_EYE_FOV_DEGREES_X: return 90.0f;
	case VRAPI_SYS_PROP_SUGGESTED_EYE_FOV_DEGREES_Y: return 90.0f;
	case VRAPI_SYS_PROP_DISPLAY_REFRESH_RATE: return 60.0f;
	default: return 0.0f;
	}
}

int
vrapi_GetSystemPropertyFloatArray(const ovrJava *, const ovrSystemProperty propType, float *values,
                                  int numArrayValues)
{
	VRAPI_TRACE("vrapi_GetSystemPropertyFloatArray");
	if (propType == VRAPI_SYS_PROP_SUPPORTED_DISPLAY_REFRESH_RATES && values != nullptr && numArrayValues > 0) {
		values[0] = 60.0f;
		return 1;
	}
	return 0;
}

int
vrapi_GetSystemPropertyInt64Array(const ovrJava *, const ovrSystemProperty propType, int64_t *values,
                                  int numArrayValues)
{
	VRAPI_TRACE("vrapi_GetSystemPropertyInt64Array");
	if (propType != VRAPI_SYS_PROP_SUPPORTED_SWAPCHAIN_FORMATS || values == nullptr) {
		return 0;
	}
	const int64_t formats[] = {GL_SRGB8_ALPHA8, GL_RGBA8};
	int count = 0;
	for (; count < numArrayValues && count < 2; count++) {
		values[count] = formats[count];
	}
	return count;
}

const char *
vrapi_GetSystemPropertyString(const ovrJava *, const ovrSystemProperty)
{
	VRAPI_TRACE("vrapi_GetSystemPropertyString");
	return nullptr;
}

int
vrapi_GetSystemStatusInt(const ovrJava *, const ovrSystemStatus statusType)
{
	VRAPI_TRACE("vrapi_GetSystemStatusInt");
	switch (statusType) {
	case VRAPI_SYS_STATUS_MOUNTED: return VRAPI_TRUE;
	case VRAPI_SYS_STATUS_APP_FRAMES_PER_SECOND: return 60;
	default: return 0;
	}
}

float
vrapi_GetSystemStatusFloat(const ovrJava *, const ovrSystemStatus)
{
	VRAPI_TRACE("vrapi_GetSystemStatusFloat");
	return 0.0f;
}

bool
vrapi_ShowSystemUI(const ovrJava *, const ovrSystemUIType type)
{
	VRAPI_TRACE("vrapi_ShowSystemUI");
	if (type == VRAPI_SYS_UI_CONFIRM_QUIT_MENU) {
		finish_activity();
		return true;
	}
	return false;
}

void
vrapi_ShowFatalError(const ovrJava *, const char *title, const char *message, const char *fileName,
                     const unsigned int lineNumber)
{
	VRAPI_TRACE("vrapi_ShowFatalError");
	VRAPI_WARN("Fatal error from game: %s: %s (%s:%u)", title ? title : "", message ? message : "",
	           fileName ? fileName : "", lineNumber);
}

// Tracking spaces. A phone has no Guardian and no floor, so floor spaces sit kEyeHeight lower.
ovrTrackingSpace
vrapi_GetTrackingSpace(ovrMobile *ovr)
{
	VRAPI_TRACE("vrapi_GetTrackingSpace");
	return ovr != nullptr ? ovr->tracking_space : VRAPI_TRACKING_SPACE_LOCAL;
}

ovrResult
vrapi_SetTrackingSpace(ovrMobile *ovr, ovrTrackingSpace whichSpace)
{
	VRAPI_TRACE("vrapi_SetTrackingSpace");
	if (ovr == nullptr) {
		return ovrError_InvalidParameter;
	}
	ovr->tracking_space = whichSpace;
	return ovrSuccess;
}

ovrPosef
vrapi_LocateTrackingSpace(ovrMobile *ovr, ovrTrackingSpace target)
{
	VRAPI_TRACE("vrapi_LocateTrackingSpace");
	ovrPosef pose{};
	pose.Orientation.w = 1.0f;
	if (ovr == nullptr) {
		return pose;
	}
	const bool target_floor = target == VRAPI_TRACKING_SPACE_LOCAL_FLOOR || target == VRAPI_TRACKING_SPACE_STAGE;
	pose.Position.y = (target_floor ? -kEyeHeight : 0.0f) + floor_offset(ovr);
	return pose;
}

ovrPosef
vrapi_GetTrackingTransform(ovrMobile *, ovrTrackingTransform whichTransform)
{
	VRAPI_TRACE("vrapi_GetTrackingTransform");
	ovrPosef pose{};
	pose.Orientation.w = 1.0f;
	if (whichTransform == VRAPI_TRACKING_TRANSFORM_SYSTEM_CENTER_FLOOR_LEVEL) {
		pose.Position.y = -kEyeHeight;
	}
	return pose;
}

void
vrapi_SetTrackingTransform(ovrMobile *, ovrPosef)
{
	VRAPI_TRACE("vrapi_SetTrackingTransform");}

void
vrapi_RecenterPose(ovrMobile *)
{
	VRAPI_TRACE("vrapi_RecenterPose");}

ovrResult
vrapi_GetBoundaryGeometry(ovrMobile *, const uint32_t, uint32_t *pointsCountOutput, ovrVector3f *)
{
	VRAPI_TRACE("vrapi_GetBoundaryGeometry");
	if (pointsCountOutput != nullptr) {
		*pointsCountOutput = 0;
	}
	return ovrSuccess_BoundaryInvalid;
}

ovrResult
vrapi_GetBoundaryOrientedBoundingBox(ovrMobile *, ovrPosef *pose, ovrVector3f *scale)
{
	VRAPI_TRACE("vrapi_GetBoundaryOrientedBoundingBox");
	if (pose != nullptr) {
		*pose = {};
		pose->Orientation.w = 1.0f;
	}
	if (scale != nullptr) {
		*scale = {0, 0, 0};
	}
	return ovrSuccess_BoundaryInvalid;
}

ovrResult
vrapi_TestPointIsInBoundary(ovrMobile *, const ovrVector3f, bool *pointInsideBoundary, ovrBoundaryTriggerResult *result)
{
	VRAPI_TRACE("vrapi_TestPointIsInBoundary");
	if (pointInsideBoundary != nullptr) {
		*pointInsideBoundary = true;
	}
	if (result != nullptr) {
		*result = {};
	}
	return ovrSuccess_BoundaryInvalid;
}

ovrResult
vrapi_GetBoundaryTriggerState(ovrMobile *, const ovrTrackedDeviceTypeId, ovrBoundaryTriggerResult *result)
{
	VRAPI_TRACE("vrapi_GetBoundaryTriggerState");
	if (result != nullptr) {
		*result = {};
	}
	return ovrSuccess_BoundaryInvalid;
}

ovrResult
vrapi_RequestBoundaryVisible(ovrMobile *, const bool)
{
	VRAPI_TRACE("vrapi_RequestBoundaryVisible");
	return ovrSuccess;
}

ovrResult
vrapi_GetBoundaryVisible(ovrMobile *, bool *visible)
{
	VRAPI_TRACE("vrapi_GetBoundaryVisible");
	if (visible != nullptr) {
		*visible = false;
	}
	return ovrSuccess;
}

// Performance hints have nothing to drive on a regular phone.
ovrResult
vrapi_SetClockLevels(ovrMobile *, const int32_t, const int32_t)
{
	VRAPI_TRACE("vrapi_SetClockLevels");
	return ovrSuccess;
}

ovrResult
vrapi_SetPerfThread(ovrMobile *, const ovrPerfThreadType, const uint32_t)
{
	VRAPI_TRACE("vrapi_SetPerfThread");
	return ovrSuccess;
}

ovrResult
vrapi_SetExtraLatencyMode(ovrMobile *, const ovrExtraLatencyMode)
{
	VRAPI_TRACE("vrapi_SetExtraLatencyMode");
	return ovrSuccess;
}

ovrHmdColorDesc
vrapi_GetHmdColorDesc(ovrMobile *)
{
	VRAPI_TRACE("vrapi_GetHmdColorDesc");
	ovrHmdColorDesc description{};
	description.ColorSpace = VRAPI_COLORSPACE_REC_709;
	return description;
}

ovrResult
vrapi_SetClientColorDesc(ovrMobile *, const ovrHmdColorDesc *)
{
	VRAPI_TRACE("vrapi_SetClientColorDesc");
	return ovrSuccess;
}

ovrResult
vrapi_SetDisplayRefreshRate(ovrMobile *, const float refreshRate)
{
	VRAPI_TRACE("vrapi_SetDisplayRefreshRate");
	return std::fabs(refreshRate - 60.0f) < 1.0f ? ovrSuccess : ovrError_InvalidParameter;
}

// Vulkan games are not supported: vrapi_Initialize already rejected them.
ovrResult
vrapi_GetInstanceExtensionsVulkan(char *, uint32_t *extensionNamesSize)
{
	VRAPI_TRACE("vrapi_GetInstanceExtensionsVulkan");
	if (extensionNamesSize != nullptr) {
		*extensionNamesSize = 0;
	}
	return ovrError_NotImplemented;
}

ovrResult
vrapi_GetDeviceExtensionsVulkan(char *, uint32_t *extensionNamesSize)
{
	VRAPI_TRACE("vrapi_GetDeviceExtensionsVulkan");
	if (extensionNamesSize != nullptr) {
		*extensionNamesSize = 0;
	}
	return ovrError_NotImplemented;
}

ovrResult
vrapi_CreateSystemVulkan(ovrSystemCreateInfoVulkan *)
{
	VRAPI_TRACE("vrapi_CreateSystemVulkan");
	return ovrError_NotImplemented;
}

void
vrapi_DestroySystemVulkan()
{
	VRAPI_TRACE("vrapi_DestroySystemVulkan");}

VkImage
vrapi_GetTextureSwapChainBufferVulkan(ovrTextureSwapChain *, int)
{
	VRAPI_TRACE("vrapi_GetTextureSwapChainBufferVulkan");
	return VK_NULL_HANDLE;
}

ovrResult
vrapi_GetTextureSwapChainBufferFoveationVulkan(ovrTextureSwapChain *, int, VkImage *image, uint32_t *imageWidth,
                                               uint32_t *imageHeight)
{
	VRAPI_TRACE("vrapi_GetTextureSwapChainBufferFoveationVulkan");
	if (image != nullptr) {
		*image = VK_NULL_HANDLE;
	}
	if (imageWidth != nullptr) {
		*imageWidth = 0;
	}
	if (imageHeight != nullptr) {
		*imageHeight = 0;
	}
	return ovrError_NotImplemented;
}

} // extern "C"

// Exports of the real libvrapi.so that are missing from the public headers. Games only need the
// symbols to resolve; none of these features exist on a phone.
VRAPI_EXTRA_EXPORT void
VRAPI_LOADER_API_LEVEL_V3_marker() __asm__("_Z26VRAPI_LOADER_API_LEVEL_V33v");
void
VRAPI_LOADER_API_LEVEL_V3_marker()
{}

VRAPI_EXTRA_EXPORT const char *
ovr_GetLocalPreferenceValueForKey(const char *, const char *defaultKeyValue)
{
	VRAPI_TRACE("ovr_GetLocalPreferenceValueForKey");
	return defaultKeyValue;
}

VRAPI_EXTRA_EXPORT void
ovr_SetLocalPreferenceValueForKey(const char *, const char *)
{
	VRAPI_TRACE("ovr_SetLocalPreferenceValueForKey");}

VRAPI_EXTRA_EXPORT bool
vrapi_ReturnToHome(const ovrJava *)
{
	VRAPI_TRACE("vrapi_ReturnToHome");
	finish_activity();
	return true;
}

VRAPI_EXTRA_EXPORT bool
vrapi_ShowSystemUIWithExtra(const ovrJava *java, const ovrSystemUIType type, const char *)
{
	VRAPI_TRACE("vrapi_ShowSystemUIWithExtra");
	return vrapi_ShowSystemUI(java, type);
}

VRAPI_EXTRA_EXPORT void
vrapi_SetRemoteEmulation(const ovrJava *, const bool)
{
	VRAPI_TRACE("vrapi_SetRemoteEmulation");}

#define VRAPI_UNSUPPORTED(name)                                                                                        \
	VRAPI_EXTRA_EXPORT int name()                                                                                  \
	{                                                                                                              \
		return ovrError_NotImplemented;                                                                        \
	}

VRAPI_UNSUPPORTED(vrapi_AnchorKeyboardOverlay)
VRAPI_UNSUPPORTED(vrapi_BeginCaptureSection)
VRAPI_UNSUPPORTED(vrapi_BodyConfigBodyTracking)
VRAPI_UNSUPPORTED(vrapi_BodyGetPose)
VRAPI_UNSUPPORTED(vrapi_BodyGetSkeleton)
VRAPI_UNSUPPORTED(vrapi_BodyGetState)
VRAPI_UNSUPPORTED(vrapi_BodyOverridePoseInputs)
VRAPI_UNSUPPORTED(vrapi_BodySetOffsetPoseInputs)
VRAPI_UNSUPPORTED(vrapi_CaptureScalar)
VRAPI_UNSUPPORTED(vrapi_CloseKeyboardOverlay)
VRAPI_UNSUPPORTED(vrapi_EndCaptureSection)
VRAPI_UNSUPPORTED(vrapi_EnumerateSupportedTrackedObjectTypes)
VRAPI_UNSUPPORTED(vrapi_EnumerateSupportedTrackedObjectTypes_V1)
VRAPI_UNSUPPORTED(vrapi_GetFrameMetric)
VRAPI_UNSUPPORTED(vrapi_SendTrexTestCommand)
VRAPI_UNSUPPORTED(vrapi_SetAADTLutData)
VRAPI_UNSUPPORTED(vrapi_SetAndroidSurfaceParameters)
VRAPI_UNSUPPORTED(vrapi_ShowKeyboardOverlay)
VRAPI_UNSUPPORTED(vrapi_StartTrackingObjectType)
VRAPI_UNSUPPORTED(vrapi_StartTrackingObjectType_V1)
VRAPI_UNSUPPORTED(vrapi_StopTrackingObjectType)
VRAPI_UNSUPPORTED(vrapi_StopTrackingObjectType_V1)
VRAPI_UNSUPPORTED(vrapi_UpdateFoveation)
VRAPI_UNSUPPORTED(vrapi_UpdateHmdInfo)
