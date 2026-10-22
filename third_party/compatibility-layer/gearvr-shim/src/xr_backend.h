// Compatibility-Layer side of the Gear VR adapter.
//
// Gear VR games talk to VrApi (libvrapi.so). The adapter replaces that library and turns each
// VrApi call into Compatibility-Layer on the Compatibility-Layer runtime (Monado). This file owns everything Compatibility-Layer:
// instance, session, frame loop, eye swapchains, head tracking and controller input.
// The VrApi-facing layer on top of it only converts structures.
#pragma once

#include <EGL/egl.h>
#include <jni.h>

#include <array>
#include <cstdint>
#include <vector>

#define XR_USE_PLATFORM_ANDROID
#define XR_USE_GRAPHICS_API_OPENGL_ES
#define XR_USE_TIMESPEC
// Extension functions (loader init, GLES requirements) are declared only with this set.
#define XR_EXTENSION_PROTOTYPES
#include <time.h>
#include <compatibility_layer/compatibility_layer.h>
#include <compatibility_layer/compatibility_layer_platform.h>

namespace compatibility-layer {

struct Pose
{
	XrQuaternionf orientation{0, 0, 0, 1};
	XrVector3f position{0, 0, 0};
	bool valid = false;
};

struct Eye
{
	Pose pose;
	XrFovf fov{};
};

struct FrameTiming
{
	XrTime predicted_display_time = 0;
	XrDuration predicted_display_period = 0;
	bool should_render = false;
};

// Controller state in the terms VrApi's Gear VR / Touch input uses.
struct Controller
{
	bool active = false;
	Pose grip;
	Pose aim;
	float trigger = 0;
	float squeeze = 0;
	bool primary = false;   // A / X, Gear VR touchpad click
	bool secondary = false; // B / Y, Gear VR back button
	bool menu = false;
	XrVector2f thumbstick{0, 0};
};

class Swapchain
{
public:
	XrSwapchain handle = XR_NULL_HANDLE;
	int32_t width = 0;
	int32_t height = 0;
	int64_t format = 0;
	std::vector<XrSwapchainImageOpenGLESKHR> images;
};

// One eye of a projection layer: which image, which part of it, from where and with what FOV.
struct EyeImage
{
	Swapchain *swapchain = nullptr;
	XrRect2Di rect{};
	Pose pose;
	XrFovf fov{};
};

struct ProjectionLayer
{
	std::array<EyeImage, 2> eyes;
	bool alpha_blend = false;
};

class XrBackend
{
public:
	// Must be called before anything else, with the game's JavaVM and Activity.
	bool initialize(JavaVM *vm, jobject activity);
	void shutdown();
	bool initialized() const { return instance_ != XR_NULL_HANDLE; }

	// Binds the game's EGL context. VrApi receives it in vrapi_EnterVrMode.
	bool begin_session(EGLDisplay display, EGLConfig config, EGLContext context);
	void end_session();
	bool has_session() const { return session_ != XR_NULL_HANDLE; }

	// Handles runtime events; returns false once the runtime asks the game to quit.
	bool poll_events();
	bool session_running() const { return running_; }
	XrSessionState session_state() const { return state_; }

	// vrapi_GetPredictedDisplayTime: waits for the next frame slot and returns its timing.
	FrameTiming wait_frame();
	void begin_frame();

	// VrApi time is CLOCK_MONOTONIC in seconds.
	XrTime to_xr_time(double seconds) const;
	double to_seconds(XrTime time) const;
	static double monotonic_seconds();

	// vrapi_GetPredictedTracking2: head and eye poses at the given display time.
	bool locate_eyes(XrTime time, Pose &head, std::array<Eye, 2> &eyes);

	// vrapi_CreateTextureSwapChain3 / GetTextureSwapChainHandle.
	Swapchain *create_swapchain(int32_t width, int32_t height, int64_t gl_format, uint32_t samples);
	void destroy_swapchain(Swapchain *swapchain);
	uint32_t acquire_image(Swapchain *swapchain);
	void release_image(Swapchain *swapchain);
	// GL formats the runtime accepts for swapchains, known once a session exists.
	const std::vector<int64_t> &swapchain_formats() const { return formats_; }

	// vrapi_SubmitFrame2: projection layers back to front. An empty list ends the frame blank.
	void end_frame(XrTime display_time, const std::vector<ProjectionLayer> &layers);

	// vrapi_GetCurrentInputState / GetInputTrackingState.
	void sync_input(XrTime time);
	const Controller &controller(int hand) const { return controllers_[hand]; }

	uint32_t recommended_width() const { return recommended_width_; }
	uint32_t recommended_height() const { return recommended_height_; }

private:
	bool create_actions();
	Pose locate(XrSpace space, XrTime time) const;

	XrInstance instance_ = XR_NULL_HANDLE;
	XrSystemId system_ = XR_NULL_SYSTEM_ID;
	XrSession session_ = XR_NULL_HANDLE;
	XrSpace local_space_ = XR_NULL_HANDLE;
	XrSpace view_space_ = XR_NULL_HANDLE;
	XrSessionState state_ = XR_SESSION_STATE_UNKNOWN;
	bool running_ = false;
	bool frame_begun_ = false;
	uint32_t recommended_width_ = 0;
	uint32_t recommended_height_ = 0;
	std::vector<int64_t> formats_;

	using TimespecToTime = XrResult (*)(XrInstance, const struct timespec *, XrTime *);
	using TimeToTimespec = XrResult (*)(XrInstance, XrTime, struct timespec *);
	TimespecToTime timespec_to_time_ = nullptr;
	TimeToTimespec time_to_timespec_ = nullptr;

	XrActionSet action_set_ = XR_NULL_HANDLE;
	std::array<XrPath, 2> hands_{};
	XrAction grip_pose_ = XR_NULL_HANDLE;
	XrAction aim_pose_ = XR_NULL_HANDLE;
	XrAction trigger_ = XR_NULL_HANDLE;
	XrAction squeeze_ = XR_NULL_HANDLE;
	XrAction primary_ = XR_NULL_HANDLE;
	XrAction secondary_ = XR_NULL_HANDLE;
	XrAction menu_ = XR_NULL_HANDLE;
	XrAction thumbstick_ = XR_NULL_HANDLE;
	std::array<XrSpace, 2> grip_spaces_{};
	std::array<XrSpace, 2> aim_spaces_{};
	std::array<Controller, 2> controllers_{};
};

} // namespace compatibility-layer
