#include "xr_backend.h"

#include <android/log.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <string>

#include <unistd.h>

#define LOG(...) __android_log_print(ANDROID_LOG_INFO, "Compatibility-Layer-GearVR", __VA_ARGS__)
#define FAIL(result, what) (LOG("%s failed: %d", what, static_cast<int>(result)), false)

namespace compatibility-layer {

namespace {

XrPath
path(XrInstance instance, const char *text)
{
	XrPath value = XR_NULL_PATH;
	xrStringToPath(instance, text, &value);
	return value;
}

XrAction
make_action(XrActionSet set, XrActionType type, const char *name, const char *localized, const XrPath *hands)
{
	XrActionCreateInfo info{XR_TYPE_ACTION_CREATE_INFO};
	info.actionType = type;
	std::strncpy(info.actionName, name, XR_MAX_ACTION_NAME_SIZE - 1);
	std::strncpy(info.localizedActionName, localized, XR_MAX_LOCALIZED_ACTION_NAME_SIZE - 1);
	info.countSubactionPaths = 2;
	info.subactionPaths = hands;
	XrAction action = XR_NULL_HANDLE;
	xrCreateAction(set, &info, &action);
	return action;
}

// Loaders are not required to export extension functions, so they are looked up at runtime.
template <typename Function>
Function
lookup(XrInstance instance, const char *name)
{
	PFN_xrVoidFunction function = nullptr;
	xrGetInstanceProcAddr(instance, name, &function);
	return reinterpret_cast<Function>(function);
}

#if defined(__arm__)
std::string
java_string(JNIEnv *env, jobject value)
{
	if (value == nullptr) {
		return {};
	}
	const char *chars = env->GetStringUTFChars(static_cast<jstring>(value), nullptr);
	std::string result = chars != nullptr ? chars : "";
	env->ReleaseStringUTFChars(static_cast<jstring>(value), chars);
	return result;
}

// The runtime broker always answers with the runtime's primary (64-bit) library folder, which a
// 32-bit game cannot load. Monado installs its 32-bit build next to it (multiArch), so the loader
// is pointed there through XR_RUNTIME_JSON.
void
use_32bit_runtime(JavaVM *vm, jobject activity)
{
	if (getenv("XR_RUNTIME_JSON") != nullptr) {
		return;
	}
	JNIEnv *env = nullptr;
	bool attached = false;
	if (vm->GetEnv(reinterpret_cast<void **>(&env), JNI_VERSION_1_6) != JNI_OK) {
		if (vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
			return;
		}
		attached = true;
	}
	std::string library;
	std::string cache;
	const char *packages[] = {"org.freedesktop.monado.compatibility_layer_runtime.out_of_process",
	                          "org.freedesktop.monado.compatibility_layer_runtime.in_process"};
	jclass context_class = env->GetObjectClass(activity);
	jobject manager = env->CallObjectMethod(
	    activity, env->GetMethodID(context_class, "getPackageManager", "()Landroid/content/pm/PackageManager;"));
	jclass manager_class = env->GetObjectClass(manager);
	jmethodID get_info = env->GetMethodID(manager_class, "getApplicationInfo",
	                                      "(Ljava/lang/String;I)Landroid/content/pm/ApplicationInfo;");
	for (const char *package : packages) {
		jstring name = env->NewStringUTF(package);
		jobject info = env->CallObjectMethod(manager, get_info, name, 0);
		env->DeleteLocalRef(name);
		if (env->ExceptionCheck()) {
			env->ExceptionClear();
			continue;
		}
		jclass info_class = env->GetObjectClass(info);
		std::string source = java_string(
		    env, env->GetObjectField(info, env->GetFieldID(info_class, "sourceDir", "Ljava/lang/String;")));
		std::string candidate = source.substr(0, source.find_last_of('/')) + "/lib/arm/libcompatibility_layer_monado.so";
		if (access(candidate.c_str(), R_OK) == 0) {
			library = candidate;
			break;
		}
	}
	jobject cache_dir =
	    env->CallObjectMethod(activity, env->GetMethodID(context_class, "getCacheDir", "()Ljava/io/File;"));
	if (cache_dir != nullptr) {
		jclass file_class = env->GetObjectClass(cache_dir);
		cache = java_string(env, env->CallObjectMethod(cache_dir, env->GetMethodID(file_class, "getAbsolutePath",
		                                                                          "()Ljava/lang/String;")));
	}
	if (env->ExceptionCheck()) {
		env->ExceptionClear();
	}
	if (attached) {
		vm->DetachCurrentThread();
	}
	if (library.empty() || cache.empty()) {
		LOG("No 32-bit Compatibility-Layer runtime found; reinstall the runtime built with multiArch");
		return;
	}
	const std::string manifest = cache + "/compatibility_layer_runtime32.json";
	FILE *file = fopen(manifest.c_str(), "w");
	if (file == nullptr) {
		return;
	}
	fprintf(file, "{\"file_format_version\": \"1.0.0\", \"runtime\": {\"name\": \"Compatibility-Layer\", \"library_path\": \"%s\"}}\n",
	        library.c_str());
	fclose(file);
	setenv("XR_RUNTIME_JSON", manifest.c_str(), 1);
	LOG("32-bit runtime: %s", library.c_str());
}
#endif

} // namespace

bool
XrBackend::initialize(JavaVM *vm, jobject activity)
{
#if defined(__arm__)
	use_32bit_runtime(vm, activity);
#endif
	if (instance_ != XR_NULL_HANDLE) {
		return true;
	}
	// The loader may only be initialized once per process.
	static bool loader_ready = false;
	XrResult result = XR_SUCCESS;
	if (!loader_ready) {
		XrLoaderInitInfoAndroidKHR loader{XR_TYPE_LOADER_INIT_INFO_ANDROID_KHR};
		loader.applicationVM = vm;
		loader.applicationContext = activity;
		auto initialize_loader = lookup<XrResult (*)(const XrLoaderInitInfoBaseHeaderKHR *)>(
		    XR_NULL_HANDLE, "xrInitializeLoaderKHR");
		if (initialize_loader == nullptr) {
			LOG("Compatibility-Layer loader has no xrInitializeLoaderKHR");
			return false;
		}
		result = initialize_loader(reinterpret_cast<const XrLoaderInitInfoBaseHeaderKHR *>(&loader));
		if (XR_FAILED(result)) {
			return FAIL(result, "xrInitializeLoaderKHR");
		}
		loader_ready = true;
	}

	uint32_t available_count = 0;
	xrEnumerateInstanceExtensionProperties(nullptr, 0, &available_count, nullptr);
	std::vector<XrExtensionProperties> available(available_count, {XR_TYPE_EXTENSION_PROPERTIES});
	xrEnumerateInstanceExtensionProperties(nullptr, available_count, &available_count, available.data());
	bool has_timespec = false;
	for (const XrExtensionProperties &extension : available) {
		has_timespec |= std::strcmp(extension.extensionName, XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME) == 0;
	}

	std::vector<const char *> extensions = {XR_KHR_ANDROID_CREATE_INSTANCE_EXTENSION_NAME,
	                                        XR_KHR_OPENGL_ES_ENABLE_EXTENSION_NAME};
	if (has_timespec) {
		extensions.push_back(XR_KHR_CONVERT_TIMESPEC_TIME_EXTENSION_NAME);
	}
	XrInstanceCreateInfoAndroidKHR android{XR_TYPE_INSTANCE_CREATE_INFO_ANDROID_KHR};
	android.applicationVM = vm;
	android.applicationActivity = activity;

	XrInstanceCreateInfo info{XR_TYPE_INSTANCE_CREATE_INFO};
	info.next = &android;
	std::strcpy(info.applicationInfo.applicationName, "Compatibility-Layer Gear VR adapter");
	std::strcpy(info.applicationInfo.engineName, "VrApi");
	info.applicationInfo.apiVersion = XR_MAKE_VERSION(1, 0, 0);
	info.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
	info.enabledExtensionNames = extensions.data();
	result = xrCreateInstance(&info, &instance_);
	if (XR_FAILED(result)) {
		instance_ = XR_NULL_HANDLE;
		return FAIL(result, "xrCreateInstance");
	}
	if (has_timespec) {
		timespec_to_time_ = lookup<TimespecToTime>(instance_, "xrConvertTimespecTimeToTimeKHR");
		time_to_timespec_ = lookup<TimeToTimespec>(instance_, "xrConvertTimeToTimespecTimeKHR");
	}

	XrSystemGetInfo system{XR_TYPE_SYSTEM_GET_INFO};
	system.formFactor = XR_FORM_FACTOR_HEAD_MOUNTED_DISPLAY;
	result = xrGetSystem(instance_, &system, &system_);
	if (XR_FAILED(result)) {
		return FAIL(result, "xrGetSystem");
	}

	uint32_t count = 0;
	xrEnumerateViewConfigurationViews(instance_, system_, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, 0, &count,
	                                  nullptr);
	std::vector<XrViewConfigurationView> views(count, {XR_TYPE_VIEW_CONFIGURATION_VIEW});
	xrEnumerateViewConfigurationViews(instance_, system_, XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO, count, &count,
	                                  views.data());
	if (!views.empty()) {
		recommended_width_ = views[0].recommendedImageRectWidth;
		recommended_height_ = views[0].recommendedImageRectHeight;
	}

	// Compatibility-Layer requires this query before a GLES session can be created.
	XrGraphicsRequirementsOpenGLESKHR requirements{XR_TYPE_GRAPHICS_REQUIREMENTS_OPENGL_ES_KHR};
	auto graphics_requirements = lookup<XrResult (*)(XrInstance, XrSystemId, XrGraphicsRequirementsOpenGLESKHR *)>(
	    instance_, "xrGetOpenGLESGraphicsRequirementsKHR");
	if (graphics_requirements == nullptr) {
		LOG("Runtime does not support OpenGL ES");
		return false;
	}
	graphics_requirements(instance_, system_, &requirements);

	hands_ = {path(instance_, "/user/hand/left"), path(instance_, "/user/hand/right")};
	LOG("Compatibility-Layer ready, eye buffer %ux%u", recommended_width_, recommended_height_);
	return create_actions();
}

void
XrBackend::shutdown()
{
	end_session();
	if (instance_ != XR_NULL_HANDLE) {
		xrDestroyInstance(instance_);
	}
	*this = XrBackend();
}

bool
XrBackend::create_actions()
{
	XrActionSetCreateInfo info{XR_TYPE_ACTION_SET_CREATE_INFO};
	std::strcpy(info.actionSetName, "gearvr");
	std::strcpy(info.localizedActionSetName, "Gear VR");
	XrResult result = xrCreateActionSet(instance_, &info, &action_set_);
	if (XR_FAILED(result)) {
		return FAIL(result, "xrCreateActionSet");
	}

	grip_pose_ = make_action(action_set_, XR_ACTION_TYPE_POSE_INPUT, "grip_pose", "Grip", hands_.data());
	aim_pose_ = make_action(action_set_, XR_ACTION_TYPE_POSE_INPUT, "aim_pose", "Aim", hands_.data());
	trigger_ = make_action(action_set_, XR_ACTION_TYPE_FLOAT_INPUT, "trigger", "Trigger", hands_.data());
	squeeze_ = make_action(action_set_, XR_ACTION_TYPE_FLOAT_INPUT, "squeeze", "Squeeze", hands_.data());
	primary_ = make_action(action_set_, XR_ACTION_TYPE_BOOLEAN_INPUT, "primary", "Primary", hands_.data());
	secondary_ = make_action(action_set_, XR_ACTION_TYPE_BOOLEAN_INPUT, "secondary", "Secondary", hands_.data());
	menu_ = make_action(action_set_, XR_ACTION_TYPE_BOOLEAN_INPUT, "menu", "Menu", hands_.data());
	thumbstick_ = make_action(action_set_, XR_ACTION_TYPE_VECTOR2F_INPUT, "thumbstick", "Thumbstick", hands_.data());

	// Compatibility-Layer presents Joy-Con and hands as Oculus Touch controllers.
	const char *profile = "/interaction_profiles/oculus/touch_controller";
	std::vector<XrActionSuggestedBinding> bindings = {
	    {grip_pose_, path(instance_, "/user/hand/left/input/grip/pose")},
	    {grip_pose_, path(instance_, "/user/hand/right/input/grip/pose")},
	    {aim_pose_, path(instance_, "/user/hand/left/input/aim/pose")},
	    {aim_pose_, path(instance_, "/user/hand/right/input/aim/pose")},
	    {trigger_, path(instance_, "/user/hand/left/input/trigger/value")},
	    {trigger_, path(instance_, "/user/hand/right/input/trigger/value")},
	    {squeeze_, path(instance_, "/user/hand/left/input/squeeze/value")},
	    {squeeze_, path(instance_, "/user/hand/right/input/squeeze/value")},
	    {primary_, path(instance_, "/user/hand/left/input/x/click")},
	    {primary_, path(instance_, "/user/hand/right/input/a/click")},
	    {secondary_, path(instance_, "/user/hand/left/input/y/click")},
	    {secondary_, path(instance_, "/user/hand/right/input/b/click")},
	    {menu_, path(instance_, "/user/hand/left/input/menu/click")},
	    {thumbstick_, path(instance_, "/user/hand/left/input/thumbstick")},
	    {thumbstick_, path(instance_, "/user/hand/right/input/thumbstick")},
	};
	XrInteractionProfileSuggestedBinding suggested{XR_TYPE_INTERACTION_PROFILE_SUGGESTED_BINDING};
	suggested.interactionProfile = path(instance_, profile);
	suggested.countSuggestedBindings = static_cast<uint32_t>(bindings.size());
	suggested.suggestedBindings = bindings.data();
	result = xrSuggestInteractionProfileBindings(instance_, &suggested);
	if (XR_FAILED(result)) {
		return FAIL(result, "xrSuggestInteractionProfileBindings");
	}
	return true;
}

bool
XrBackend::begin_session(EGLDisplay display, EGLConfig config, EGLContext context)
{
	XrGraphicsBindingOpenGLESAndroidKHR binding{XR_TYPE_GRAPHICS_BINDING_OPENGL_ES_ANDROID_KHR};
	binding.display = display;
	binding.config = config;
	binding.context = context;
	XrSessionCreateInfo info{XR_TYPE_SESSION_CREATE_INFO};
	info.next = &binding;
	info.systemId = system_;
	XrResult result = xrCreateSession(instance_, &info, &session_);
	if (XR_FAILED(result)) {
		return FAIL(result, "xrCreateSession");
	}

	XrReferenceSpaceCreateInfo space{XR_TYPE_REFERENCE_SPACE_CREATE_INFO};
	space.poseInReferenceSpace.orientation.w = 1;
	space.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_LOCAL;
	xrCreateReferenceSpace(session_, &space, &local_space_);
	space.referenceSpaceType = XR_REFERENCE_SPACE_TYPE_VIEW;
	xrCreateReferenceSpace(session_, &space, &view_space_);

	for (int hand = 0; hand < 2; hand++) {
		XrActionSpaceCreateInfo action_space{XR_TYPE_ACTION_SPACE_CREATE_INFO};
		action_space.poseInActionSpace.orientation.w = 1;
		action_space.subactionPath = hands_[hand];
		action_space.action = grip_pose_;
		xrCreateActionSpace(session_, &action_space, &grip_spaces_[hand]);
		action_space.action = aim_pose_;
		xrCreateActionSpace(session_, &action_space, &aim_spaces_[hand]);
	}

	XrSessionActionSetsAttachInfo attach{XR_TYPE_SESSION_ACTION_SETS_ATTACH_INFO};
	attach.countActionSets = 1;
	attach.actionSets = &action_set_;
	xrAttachSessionActionSets(session_, &attach);

	uint32_t count = 0;
	xrEnumerateSwapchainFormats(session_, 0, &count, nullptr);
	formats_.assign(count, 0);
	xrEnumerateSwapchainFormats(session_, count, &count, formats_.data());
	return true;
}

void
XrBackend::end_session()
{
	if (session_ == XR_NULL_HANDLE) {
		return;
	}
	if (running_) {
		xrRequestExitSession(session_);
		// Let the runtime walk the session through STOPPING before it is destroyed.
		for (int attempt = 0; attempt < 100 && running_ && poll_events(); attempt++) {
		}
	}
	xrDestroySession(session_);
	session_ = XR_NULL_HANDLE;
	local_space_ = view_space_ = XR_NULL_HANDLE;
	grip_spaces_ = {};
	aim_spaces_ = {};
	state_ = XR_SESSION_STATE_UNKNOWN;
	running_ = false;
	frame_begun_ = false;
}

bool
XrBackend::poll_events()
{
	XrEventDataBuffer event{XR_TYPE_EVENT_DATA_BUFFER};
	while (xrPollEvent(instance_, &event) == XR_SUCCESS) {
		if (event.type == XR_TYPE_EVENT_DATA_SESSION_STATE_CHANGED) {
			const auto *changed = reinterpret_cast<XrEventDataSessionStateChanged *>(&event);
			state_ = changed->state;
			LOG("session state %d", static_cast<int>(state_));
			if (state_ == XR_SESSION_STATE_READY) {
				XrSessionBeginInfo begin{XR_TYPE_SESSION_BEGIN_INFO};
				begin.primaryViewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
				running_ = XR_SUCCEEDED(xrBeginSession(session_, &begin));
			} else if (state_ == XR_SESSION_STATE_STOPPING) {
				xrEndSession(session_);
				running_ = false;
			} else if (state_ == XR_SESSION_STATE_EXITING || state_ == XR_SESSION_STATE_LOSS_PENDING) {
				running_ = false;
				return false;
			}
		} else if (event.type == XR_TYPE_EVENT_DATA_INSTANCE_LOSS_PENDING) {
			return false;
		}
		event = {XR_TYPE_EVENT_DATA_BUFFER};
	}
	return true;
}

FrameTiming
XrBackend::wait_frame()
{
	FrameTiming timing;
	if (!running_) {
		return timing;
	}
	XrFrameWaitInfo wait{XR_TYPE_FRAME_WAIT_INFO};
	XrFrameState state{XR_TYPE_FRAME_STATE};
	if (XR_SUCCEEDED(xrWaitFrame(session_, &wait, &state))) {
		timing.predicted_display_time = state.predictedDisplayTime;
		timing.predicted_display_period = state.predictedDisplayPeriod;
		timing.should_render = state.shouldRender == XR_TRUE;
	}
	return timing;
}

void
XrBackend::begin_frame()
{
	if (!running_ || frame_begun_) {
		return;
	}
	XrFrameBeginInfo info{XR_TYPE_FRAME_BEGIN_INFO};
	frame_begun_ = XR_SUCCEEDED(xrBeginFrame(session_, &info));
}

double
XrBackend::monotonic_seconds()
{
	struct timespec now{};
	clock_gettime(CLOCK_MONOTONIC, &now);
	return static_cast<double>(now.tv_sec) + static_cast<double>(now.tv_nsec) * 1e-9;
}

XrTime
XrBackend::to_xr_time(double seconds) const
{
	auto nanoseconds = static_cast<int64_t>(seconds * 1e9);
	if (timespec_to_time_ == nullptr) {
		return nanoseconds;
	}
	struct timespec value{};
	value.tv_sec = static_cast<time_t>(nanoseconds / 1000000000);
	value.tv_nsec = static_cast<long>(nanoseconds % 1000000000);
	XrTime time = 0;
	return XR_SUCCEEDED(timespec_to_time_(instance_, &value, &time)) ? time : nanoseconds;
}

double
XrBackend::to_seconds(XrTime time) const
{
	struct timespec value{};
	if (time_to_timespec_ == nullptr || XR_FAILED(time_to_timespec_(instance_, time, &value))) {
		return static_cast<double>(time) * 1e-9;
	}
	return static_cast<double>(value.tv_sec) + static_cast<double>(value.tv_nsec) * 1e-9;
}

bool
XrBackend::locate_eyes(XrTime time, Pose &head, std::array<Eye, 2> &eyes)
{
	head = locate(view_space_, time);
	XrViewLocateInfo info{XR_TYPE_VIEW_LOCATE_INFO};
	info.viewConfigurationType = XR_VIEW_CONFIGURATION_TYPE_PRIMARY_STEREO;
	info.displayTime = time;
	info.space = local_space_;
	XrViewState state{XR_TYPE_VIEW_STATE};
	std::array<XrView, 2> views{{{XR_TYPE_VIEW}, {XR_TYPE_VIEW}}};
	uint32_t count = 0;
	if (XR_FAILED(xrLocateViews(session_, &info, &state, 2, &count, views.data())) || count != 2) {
		return false;
	}
	bool valid = (state.viewStateFlags & XR_VIEW_STATE_ORIENTATION_VALID_BIT) != 0;
	for (int eye = 0; eye < 2; eye++) {
		eyes[eye].pose.orientation = views[eye].pose.orientation;
		eyes[eye].pose.position = views[eye].pose.position;
		eyes[eye].pose.valid = valid;
		eyes[eye].fov = views[eye].fov;
	}
	return valid;
}

Pose
XrBackend::locate(XrSpace space, XrTime time) const
{
	Pose pose;
	XrSpaceLocation location{XR_TYPE_SPACE_LOCATION};
	if (XR_SUCCEEDED(xrLocateSpace(space, local_space_, time, &location)) &&
	    (location.locationFlags & XR_SPACE_LOCATION_ORIENTATION_VALID_BIT) != 0) {
		pose.orientation = location.pose.orientation;
		pose.position = location.pose.position;
		pose.valid = true;
	}
	return pose;
}

Swapchain *
XrBackend::create_swapchain(int32_t width, int32_t height, int64_t gl_format, uint32_t samples)
{
	XrSwapchainCreateInfo info{XR_TYPE_SWAPCHAIN_CREATE_INFO};
	info.usageFlags = XR_SWAPCHAIN_USAGE_COLOR_ATTACHMENT_BIT | XR_SWAPCHAIN_USAGE_SAMPLED_BIT;
	info.format = gl_format;
	info.sampleCount = samples == 0 ? 1 : samples;
	info.width = width;
	info.height = height;
	info.faceCount = 1;
	info.arraySize = 1;
	info.mipCount = 1;

	auto *swapchain = new Swapchain();
	swapchain->width = width;
	swapchain->height = height;
	swapchain->format = gl_format;
	XrResult result = xrCreateSwapchain(session_, &info, &swapchain->handle);
	if (XR_FAILED(result)) {
		LOG("xrCreateSwapchain %dx%d format 0x%llx failed: %d", width, height,
		    static_cast<long long>(gl_format), static_cast<int>(result));
		delete swapchain;
		return nullptr;
	}
	uint32_t count = 0;
	xrEnumerateSwapchainImages(swapchain->handle, 0, &count, nullptr);
	swapchain->images.assign(count, {XR_TYPE_SWAPCHAIN_IMAGE_OPENGL_ES_KHR});
	xrEnumerateSwapchainImages(swapchain->handle, count, &count,
	                           reinterpret_cast<XrSwapchainImageBaseHeader *>(swapchain->images.data()));
	return swapchain;
}

void
XrBackend::destroy_swapchain(Swapchain *swapchain)
{
	if (swapchain == nullptr) {
		return;
	}
	xrDestroySwapchain(swapchain->handle);
	delete swapchain;
}

uint32_t
XrBackend::acquire_image(Swapchain *swapchain)
{
	uint32_t index = 0;
	XrSwapchainImageAcquireInfo acquire{XR_TYPE_SWAPCHAIN_IMAGE_ACQUIRE_INFO};
	xrAcquireSwapchainImage(swapchain->handle, &acquire, &index);
	XrSwapchainImageWaitInfo wait{XR_TYPE_SWAPCHAIN_IMAGE_WAIT_INFO};
	wait.timeout = XR_INFINITE_DURATION;
	xrWaitSwapchainImage(swapchain->handle, &wait);
	return index;
}

void
XrBackend::release_image(Swapchain *swapchain)
{
	XrSwapchainImageReleaseInfo release{XR_TYPE_SWAPCHAIN_IMAGE_RELEASE_INFO};
	xrReleaseSwapchainImage(swapchain->handle, &release);
}

void
XrBackend::end_frame(XrTime display_time, const std::vector<ProjectionLayer> &layers)
{
	if (!frame_begun_) {
		return;
	}
	frame_begun_ = false;

	// Views are stored up front so layer structures can point into a vector that no longer grows.
	std::vector<XrCompositionLayerProjectionView> views;
	std::vector<XrCompositionLayerProjection> projections;
	views.reserve(layers.size() * 2);
	projections.reserve(layers.size());
	for (const ProjectionLayer &source : layers) {
		if (source.eyes[0].swapchain == nullptr || source.eyes[1].swapchain == nullptr) {
			continue;
		}
		for (const EyeImage &eye : source.eyes) {
			XrCompositionLayerProjectionView view{XR_TYPE_COMPOSITION_LAYER_PROJECTION_VIEW};
			view.pose.orientation = eye.pose.orientation;
			view.pose.position = eye.pose.position;
			view.fov = eye.fov;
			view.subImage.swapchain = eye.swapchain->handle;
			view.subImage.imageRect = eye.rect;
			views.push_back(view);
		}
		XrCompositionLayerProjection layer{XR_TYPE_COMPOSITION_LAYER_PROJECTION};
		layer.space = local_space_;
		layer.layerFlags = source.alpha_blend ? XR_COMPOSITION_LAYER_BLEND_TEXTURE_SOURCE_ALPHA_BIT : 0;
		layer.viewCount = 2;
		layer.views = &views[views.size() - 2];
		projections.push_back(layer);
	}

	std::vector<const XrCompositionLayerBaseHeader *> headers;
	for (const XrCompositionLayerProjection &layer : projections) {
		headers.push_back(reinterpret_cast<const XrCompositionLayerBaseHeader *>(&layer));
	}

	XrFrameEndInfo info{XR_TYPE_FRAME_END_INFO};
	info.displayTime = display_time;
	info.environmentBlendMode = XR_ENVIRONMENT_BLEND_MODE_OPAQUE;
	// A frame without layers is still ended, just with nothing to show.
	info.layerCount = static_cast<uint32_t>(headers.size());
	info.layers = headers.empty() ? nullptr : headers.data();
	XrResult result = xrEndFrame(session_, &info);
	if (XR_FAILED(result)) {
		LOG("xrEndFrame failed: %d", static_cast<int>(result));
	}
}

void
XrBackend::sync_input(XrTime time)
{
	if (!running_) {
		return;
	}
	XrActiveActionSet active{action_set_, XR_NULL_PATH};
	XrActionsSyncInfo sync{XR_TYPE_ACTIONS_SYNC_INFO};
	sync.countActiveActionSets = 1;
	sync.activeActionSets = &active;
	xrSyncActions(session_, &sync);

	for (int hand = 0; hand < 2; hand++) {
		Controller &controller = controllers_[hand];
		XrActionStateGetInfo get{XR_TYPE_ACTION_STATE_GET_INFO};
		get.subactionPath = hands_[hand];

		XrActionStateFloat value{XR_TYPE_ACTION_STATE_FLOAT};
		get.action = trigger_;
		xrGetActionStateFloat(session_, &get, &value);
		controller.trigger = value.currentState;
		get.action = squeeze_;
		xrGetActionStateFloat(session_, &get, &value);
		controller.squeeze = value.currentState;

		XrActionStateBoolean pressed{XR_TYPE_ACTION_STATE_BOOLEAN};
		get.action = primary_;
		xrGetActionStateBoolean(session_, &get, &pressed);
		controller.primary = pressed.currentState == XR_TRUE;
		get.action = secondary_;
		xrGetActionStateBoolean(session_, &get, &pressed);
		controller.secondary = pressed.currentState == XR_TRUE;
		get.action = menu_;
		xrGetActionStateBoolean(session_, &get, &pressed);
		controller.menu = pressed.currentState == XR_TRUE;

		XrActionStateVector2f stick{XR_TYPE_ACTION_STATE_VECTOR2F};
		get.action = thumbstick_;
		xrGetActionStateVector2f(session_, &get, &stick);
		controller.thumbstick = stick.currentState;

		XrActionStatePose pose{XR_TYPE_ACTION_STATE_POSE};
		get.action = grip_pose_;
		xrGetActionStatePose(session_, &get, &pose);
		controller.active = pose.isActive == XR_TRUE;
		controller.grip = locate(grip_spaces_[hand], time);
		controller.aim = locate(aim_spaces_[hand], time);
	}
}

} // namespace compatibility-layer
