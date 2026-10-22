// Shared state of the VrApi layer.
//
// libvrapi.so from the Oculus Mobile SDK is only a loader: it looks for the Oculus system driver
// that shipped on Samsung phones and refuses to start without it. This library replaces it and
// serves the same exports from Compatibility-Layer, so a Gear VR game starts on any phone with CompatibilityLayer.
// Structures come from the SDK headers (VrApi 1.1.50), their layout must match the game's.
#pragma once

#define OVR_VRAPI_ENABLE_EXPORT
#include "VrApi.h"
#include "VrApi_Helpers.h"
#include "VrApi_Input.h"
#include "VrApi_SystemUtils.h"
#include "VrApi_Vulkan.h"

#include "xr_backend.h"

#include <android/log.h>

#include <array>
#include <cstdint>
#include <future>
#include <mutex>
#include <vector>

#define VRAPI_LOG(...) __android_log_print(ANDROID_LOG_INFO, "Compatibility-Layer-VrApi", __VA_ARGS__)
#define VRAPI_WARN(...) __android_log_print(ANDROID_LOG_WARN, "Compatibility-Layer-VrApi", __VA_ARGS__)
// Logs the first calls of every VrApi function, so a crashing game shows where it stopped.
#define VRAPI_TRACE(name)                                                                                              \
	do {                                                                                                           \
		static int calls = 0;                                                                                  \
		if (calls < 3) {                                                                                       \
			calls++;                                                                                       \
			__android_log_print(ANDROID_LOG_DEBUG, "Compatibility-Layer-VrApi", "call %s", name);                     \
		}                                                                                                      \
	} while (0)

// Exports that the real libvrapi.so has but the public headers do not declare.
#define VRAPI_EXTRA_EXPORT extern "C" __attribute__((visibility("default")))

namespace compatibility-layer {

// Gear VR device ids from older Oculus Mobile SDKs (the 1.50 header only lists Quest).
constexpr int kDeviceGearVr = 5; // Galaxy S8, anywhere in 0..63 means Gear VR
constexpr int kDeviceOculusGo = 64;

enum class Product
{
	gear_vr,
	oculus_go,
	quest,
};

// Controller ids handed to the game. Gear VR mode shows only the right one.
constexpr ovrDeviceID kRightDevice = 1;
constexpr ovrDeviceID kLeftDevice = 2;

// Height of the floor below eye level for floor-based tracking spaces; a phone has no floor.
constexpr float kEyeHeight = 1.6f;

struct Global
{
	std::recursive_mutex lock;
	XrBackend backend;
	JavaVM *vm = nullptr;
	jobject activity = nullptr; // global reference
	bool initialized = false;
	// Compatibility-Layer Runtime is connected in the background (see vrapi_Initialize): true once it is up.
	std::shared_future<bool> backend_ready;
	Product product = Product::gear_vr;
	int32_t api_minor_version = 0;
};

Global &global();

// Whether Compatibility-Layer Runtime is up; with [wait], waits for the background start to finish.
bool backend_ready(bool wait);

// ovrTextureSwapChain from VrApi.h. Images live in the game's GL context; at submit time the
// requested image is copied into an Compatibility-Layer swapchain, which frees the game to pick any index.
struct ChainCopy
{
	Swapchain *xr = nullptr;
	bool raw = false; // bytes are copied without sRGB conversion
};

} // namespace compatibility-layer

struct ovrTextureSwapChain
{
	ovrTextureType type = VRAPI_TEXTURE_TYPE_2D;
	int64_t format = 0;
	int width = 0;
	int height = 0;
	int levels = 1;
	int array_size = 1;
	std::vector<unsigned int> textures;
	std::vector<bool> owned;
	ovrTextureSamplerState sampler{};
	// One Compatibility-Layer swapchain per eye slot, created on first submit of the current session.
	std::array<compatibility-layer::ChainCopy, 2> copies{};
};

struct ovrMobile
{
	EGLDisplay display = EGL_NO_DISPLAY;
	EGLContext context = EGL_NO_CONTEXT;
	unsigned int mode_flags = 0;
	ovrTrackingSpace tracking_space = VRAPI_TRACKING_SPACE_LOCAL;

	// The Compatibility-Layer frame that vrapi_GetPredictedDisplayTime / WaitFrame opened. frame_real is false
	// while the session is not running yet and the frame only exists on the VrApi side.
	std::mutex wait_lock;
	bool frame_waited = false;
	bool frame_real = false;
	uint64_t frame_index = 0;
	compatibility-layer::FrameTiming timing;

	// Eye offsets from the head and FOVs from the last tracking query.
	std::array<compatibility-layer::Eye, 2> eye_offsets{};
	bool eyes_known = false;

	std::array<bool, 2> back_down{};
	bool focused = false;
	bool visible = false;
};

namespace compatibility-layer {

// Swapchains (vrapi_swapchain.cpp).
void release_swapchain_copies(); // before the session goes away
bool copy_eye_image(ovrTextureSwapChain *chain, int index, int eye, const ovrRectf &rect, bool raw,
                    EyeImage &out);
bool is_real_chain(const ovrTextureSwapChain *chain);

// Math shared by tracking and input.
Pose compose(const Pose &parent, const Pose &child);
Pose inverse(const Pose &pose);
ovrPosef to_ovr(const Pose &pose);
Pose from_ovr(const ovrPosef &pose);
ovrRigidBodyPosef rigid_body(const Pose &pose, double time);
float floor_offset(const ovrMobile *ovr);

} // namespace compatibility-layer
