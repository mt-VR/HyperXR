// VR mode, head tracking, frame timing and frame submission of the VrApi layer.
//
// Compatibility-Layer wants xrWaitFrame -> xrBeginFrame -> xrEndFrame in strict order, while VrApi games either
// ask for a display time and submit, or call WaitFrame / BeginFrame / SubmitFrame2. Whatever the
// game calls first opens the Compatibility-Layer frame and the submit closes it.
#include "vrapi_internal.h"

#include <GLES3/gl3.h>
#include <unistd.h>

#include <cmath>
#include <deque>

namespace compatibility-layer {

namespace {

constexpr double kFallbackPeriod = 1.0 / 60.0;
constexpr float kDefaultIpd = 0.064f;
constexpr float kNearZ = 0.1f;

XrQuaternionf
multiply(const XrQuaternionf &a, const XrQuaternionf &b)
{
	return {a.w * b.x + a.x * b.w + a.y * b.z - a.z * b.y, a.w * b.y - a.x * b.z + a.y * b.w + a.z * b.x,
	        a.w * b.z + a.x * b.y - a.y * b.x + a.z * b.w, a.w * b.w - a.x * b.x - a.y * b.y - a.z * b.z};
}

XrVector3f
rotate(const XrQuaternionf &q, const XrVector3f &v)
{
	const XrQuaternionf p{v.x, v.y, v.z, 0};
	const XrQuaternionf conjugate{-q.x, -q.y, -q.z, q.w};
	const XrQuaternionf result = multiply(multiply(q, p), conjugate);
	return {result.x, result.y, result.z};
}

std::deque<ovrEventType> &
events()
{
	static std::deque<ovrEventType> queue;
	return queue;
}

// Turns runtime session states into VrApi visibility and focus events.
void
update_session(ovrMobile *ovr)
{
	XrBackend &backend = global().backend;
	backend.poll_events();
	const XrSessionState state = backend.session_state();
	const bool visible = state == XR_SESSION_STATE_VISIBLE || state == XR_SESSION_STATE_FOCUSED;
	const bool focused = state == XR_SESSION_STATE_FOCUSED;
	if (visible != ovr->visible) {
		events().push_back(visible ? VRAPI_EVENT_VISIBILITY_GAINED : VRAPI_EVENT_VISIBILITY_LOST);
		ovr->visible = visible;
	}
	if (focused != ovr->focused) {
		events().push_back(focused ? VRAPI_EVENT_FOCUS_GAINED : VRAPI_EVENT_FOCUS_LOST);
		ovr->focused = focused;
	}
}

std::array<Eye, 2>
eye_offsets(const ovrMobile *ovr)
{
	if (ovr->eyes_known) {
		return ovr->eye_offsets;
	}
	std::array<Eye, 2> eyes{};
	for (int eye = 0; eye < 2; eye++) {
		eyes[eye].pose.position.x = (eye == 0 ? -0.5f : 0.5f) * kDefaultIpd;
		eyes[eye].pose.valid = true;
		// 90 degrees, as Gear VR.
		const auto quarter = static_cast<float>(M_PI_4);
		eyes[eye].fov = {-quarter, quarter, quarter, -quarter};
	}
	return eyes;
}

// Ends the open frame. Layers may be empty, then the runtime shows nothing new.
void
close_frame(ovrMobile *ovr, const std::vector<ProjectionLayer> &layers)
{
	XrBackend &backend = global().backend;
	if (ovr->frame_waited && ovr->frame_real && backend.session_running()) {
		backend.begin_frame();
		backend.end_frame(ovr->timing.predicted_display_time, layers);
	}
	ovr->frame_waited = false;
	ovr->frame_real = false;
}

// Opens an Compatibility-Layer frame for the given VrApi frame index unless one is open already.
// Must be called without the global lock: xrWaitFrame blocks until the next frame slot.
void
open_frame(ovrMobile *ovr, uint64_t index)
{
	Global &state = global();
	XrBackend &backend = state.backend;
	{
		std::lock_guard<std::recursive_mutex> guard(state.lock);
		update_session(ovr);
		// A game that asks far ahead skipped submitting; that frame is closed empty.
		if (ovr->frame_waited && index > ovr->frame_index + 1) {
			close_frame(ovr, {});
		}
		if (ovr->frame_waited) {
			return;
		}
		if (!backend.session_running()) {
			// No frame loop yet: pace the game at 60 Hz until the runtime starts the session.
			ovr->timing.predicted_display_time = backend.to_xr_time(XrBackend::monotonic_seconds() + kFallbackPeriod);
			ovr->timing.predicted_display_period = static_cast<XrDuration>(kFallbackPeriod * 1e9);
			ovr->timing.should_render = false;
			ovr->frame_waited = true;
			ovr->frame_real = false;
			ovr->frame_index = index;
		}
	}
	if (!ovr->frame_real && ovr->frame_waited) {
		usleep(static_cast<useconds_t>(kFallbackPeriod * 1e6));
		return;
	}

	std::lock_guard<std::mutex> wait_guard(ovr->wait_lock);
	{
		std::lock_guard<std::recursive_mutex> guard(state.lock);
		if (ovr->frame_waited) {
			return; // another thread opened it meanwhile
		}
	}
	const FrameTiming timing = backend.wait_frame();
	std::lock_guard<std::recursive_mutex> guard(state.lock);
	ovr->timing = timing;
	if (ovr->timing.predicted_display_period <= 0) {
		ovr->timing.predicted_display_period = static_cast<XrDuration>(kFallbackPeriod * 1e9);
	}
	ovr->frame_waited = true;
	ovr->frame_real = timing.predicted_display_time != 0;
	ovr->frame_index = index;
}

double
display_seconds(const ovrMobile *ovr, uint64_t index)
{
	const XrBackend &backend = global().backend;
	double seconds = backend.to_seconds(ovr->timing.predicted_display_time);
	if (index > ovr->frame_index) {
		seconds += static_cast<double>(index - ovr->frame_index) *
		           static_cast<double>(ovr->timing.predicted_display_period) * 1e-9;
	}
	return seconds;
}

// Head pose at a time plus eye poses relative to the head, refreshing the cached eye offsets.
Pose
track_head(ovrMobile *ovr, double seconds, std::array<Eye, 2> &offsets)
{
	XrBackend &backend = global().backend;
	Pose head;
	head.valid = false;
	std::array<Eye, 2> eyes{};
	const XrTime time = backend.to_xr_time(seconds > 0 ? seconds : XrBackend::monotonic_seconds());
	if (backend.has_session() && backend.locate_eyes(time, head, eyes) && head.valid) {
		const Pose to_head = inverse(head);
		for (int eye = 0; eye < 2; eye++) {
			ovr->eye_offsets[eye].pose = compose(to_head, eyes[eye].pose);
			ovr->eye_offsets[eye].fov = eyes[eye].fov;
		}
		ovr->eyes_known = true;
	}
	offsets = eye_offsets(ovr);
	head.position.y += floor_offset(ovr);
	return head;
}

ovrMatrix4f
projection(const XrFovf &fov)
{
	return ovrMatrix4f_CreateProjection(kNearZ * std::tan(fov.angleLeft), kNearZ * std::tan(fov.angleRight),
	                                    kNearZ * std::tan(fov.angleDown), kNearZ * std::tan(fov.angleUp), kNearZ,
	                                    0.0f);
}

ovrMatrix4f
view_matrix(const Pose &eye)
{
	const ovrPosef pose = to_ovr(inverse(eye));
	return vrapi_GetTransformFromPose(&pose);
}

// Recovers the FOV an eye image was rendered with from its TexCoordsFromTanAngles and TextureRect.
// For a direction (tanX, tanY, -1) the texture coordinate is s = M00 * tanX - M02, t likewise.
bool
fov_from_tan_angles(const ovrMatrix4f &m, const ovrRectf &rect, XrFovf &fov)
{
	if (std::fabs(m.M[0][0]) < 1e-6f || std::fabs(m.M[1][1]) < 1e-6f) {
		return false;
	}
	fov.angleLeft = std::atan((rect.x + m.M[0][2]) / m.M[0][0]);
	fov.angleRight = std::atan((rect.x + rect.width + m.M[0][2]) / m.M[0][0]);
	fov.angleDown = std::atan((rect.y + m.M[1][2]) / m.M[1][1]);
	fov.angleUp = std::atan((rect.y + rect.height + m.M[1][2]) / m.M[1][1]);
	return true;
}

void
add_projection(ovrMobile *ovr, const ovrLayerProjection2 &source, bool first, std::vector<ProjectionLayer> &out)
{
	const std::array<Eye, 2> offsets = eye_offsets(ovr);
	const bool raw = (ovr->mode_flags & VRAPI_MODE_FLAG_FRONT_BUFFER_SRGB) == 0 ||
	                 (source.Header.Flags & VRAPI_FRAME_LAYER_FLAG_INHIBIT_SRGB_FRAMEBUFFER) != 0;
	Pose head = from_ovr(source.HeadPose.Pose);
	head.position.y -= floor_offset(ovr);

	ProjectionLayer layer;
	layer.alpha_blend = !first && source.Header.SrcBlend == VRAPI_FRAME_LAYER_BLEND_SRC_ALPHA;
	for (int eye = 0; eye < 2; eye++) {
		const auto &texture = source.Textures[eye];
		EyeImage &image = layer.eyes[eye];
		if (!copy_eye_image(texture.ColorSwapChain, texture.SwapChainIndex, eye, texture.TextureRect, raw, image)) {
			return;
		}
		image.pose = compose(head, offsets[eye].pose);
		if (!fov_from_tan_angles(texture.TexCoordsFromTanAngles, texture.TextureRect, image.fov)) {
			image.fov = offsets[eye].fov;
		}
	}
	out.push_back(layer);
}

void
warn_layer_once(ovrLayerType2 type)
{
	static uint32_t warned = 0;
	const uint32_t bit = 1u << (static_cast<uint32_t>(type) & 31u);
	if ((warned & bit) == 0) {
		VRAPI_WARN("Layer type %d is not supported and is skipped", static_cast<int>(type));
		warned |= bit;
	}
}

} // namespace

Pose
compose(const Pose &parent, const Pose &child)
{
	Pose result;
	result.orientation = multiply(parent.orientation, child.orientation);
	const XrVector3f offset = rotate(parent.orientation, child.position);
	result.position = {parent.position.x + offset.x, parent.position.y + offset.y, parent.position.z + offset.z};
	result.valid = parent.valid && child.valid;
	return result;
}

Pose
inverse(const Pose &pose)
{
	Pose result;
	result.orientation = {-pose.orientation.x, -pose.orientation.y, -pose.orientation.z, pose.orientation.w};
	const XrVector3f position = rotate(result.orientation, pose.position);
	result.position = {-position.x, -position.y, -position.z};
	result.valid = pose.valid;
	return result;
}

ovrPosef
to_ovr(const Pose &pose)
{
	ovrPosef result{};
	result.Orientation = {pose.orientation.x, pose.orientation.y, pose.orientation.z, pose.orientation.w};
	result.Position = {pose.position.x, pose.position.y, pose.position.z};
	return result;
}

Pose
from_ovr(const ovrPosef &pose)
{
	Pose result;
	result.orientation = {pose.Orientation.x, pose.Orientation.y, pose.Orientation.z, pose.Orientation.w};
	result.position = {pose.Position.x, pose.Position.y, pose.Position.z};
	result.valid = true;
	return result;
}

ovrRigidBodyPosef
rigid_body(const Pose &pose, double time)
{
	ovrRigidBodyPosef result{};
	result.Pose = to_ovr(pose);
	result.TimeInSeconds = time;
	return result;
}

float
floor_offset(const ovrMobile *ovr)
{
	const bool floor = ovr != nullptr && (ovr->tracking_space == VRAPI_TRACKING_SPACE_LOCAL_FLOOR ||
	                                      ovr->tracking_space == VRAPI_TRACKING_SPACE_STAGE);
	return floor ? kEyeHeight : 0.0f;
}

} // namespace compatibility-layer

using namespace compatibility-layer;

extern "C" {

ovrMobile *
vrapi_EnterVrMode(const ovrModeParms *parms)
{
	VRAPI_TRACE("vrapi_EnterVrMode");
	Global &state = global();
	if (parms == nullptr || !state.initialized) {
		return nullptr;
	}
	EGLDisplay display = parms->Display != 0 ? reinterpret_cast<EGLDisplay>(static_cast<uintptr_t>(parms->Display)) : eglGetCurrentDisplay();
	EGLContext context =
	    parms->ShareContext != 0 ? reinterpret_cast<EGLContext>(static_cast<uintptr_t>(parms->ShareContext)) : eglGetCurrentContext();
	if (display == EGL_NO_DISPLAY || context == EGL_NO_CONTEXT) {
		VRAPI_WARN("vrapi_EnterVrMode needs an EGL display and context");
		return nullptr;
	}
	EGLint config_id = 0;
	EGLConfig config = nullptr;
	EGLint found = 0;
	eglQueryContext(display, context, EGL_CONFIG_ID, &config_id);
	const EGLint attributes[] = {EGL_CONFIG_ID, config_id, EGL_NONE};
	if (eglChooseConfig(display, attributes, &config, 1, &found) != EGL_TRUE || found == 0) {
		VRAPI_WARN("EGL config of the game context not found");
		return nullptr;
	}

	// The runtime was started by vrapi_Initialize in the background; the window is here now.
	if (!backend_ready(true)) {
		return nullptr;
	}
	std::lock_guard<std::recursive_mutex> guard(state.lock);
	// Only one activity can be in VR mode; a new one takes over.
	if (state.backend.has_session()) {
		release_swapchain_copies();
		state.backend.end_session();
	}
	if (!state.backend.begin_session(display, config, context)) {
		return nullptr;
	}
	auto *ovr = new ovrMobile();
	ovr->display = display;
	ovr->context = context;
	ovr->mode_flags = parms->Flags;
	update_session(ovr);
	VRAPI_LOG("Entered VR mode");
	return ovr;
}

void
vrapi_LeaveVrMode(ovrMobile *ovr)
{
	VRAPI_TRACE("vrapi_LeaveVrMode");
	if (ovr == nullptr) {
		return;
	}
	Global &state = global();
	std::lock_guard<std::recursive_mutex> guard(state.lock);
	close_frame(ovr, {});
	release_swapchain_copies();
	state.backend.end_session();
	if (ovr->focused) {
		events().push_back(VRAPI_EVENT_FOCUS_LOST);
	}
	if (ovr->visible) {
		events().push_back(VRAPI_EVENT_VISIBILITY_LOST);
	}
	delete ovr;
	VRAPI_LOG("Left VR mode");
}

double
vrapi_GetPredictedDisplayTime(ovrMobile *ovr, long long frameIndex)
{
	VRAPI_TRACE("vrapi_GetPredictedDisplayTime");
	if (ovr == nullptr) {
		return XrBackend::monotonic_seconds();
	}
	const auto index = static_cast<uint64_t>(frameIndex);
	open_frame(ovr, index);
	std::lock_guard<std::recursive_mutex> guard(global().lock);
	return display_seconds(ovr, index);
}

ovrTracking2
vrapi_GetPredictedTracking2(ovrMobile *ovr, double absTimeInSeconds)
{
	VRAPI_TRACE("vrapi_GetPredictedTracking2");
	ovrTracking2 tracking{};
	std::array<Eye, 2> offsets{};
	Pose head;
	if (ovr != nullptr) {
		std::lock_guard<std::recursive_mutex> guard(global().lock);
		head = track_head(ovr, absTimeInSeconds, offsets);
	} else {
		ovrMobile defaults;
		offsets = eye_offsets(&defaults);
	}
	tracking.Status = VRAPI_TRACKING_STATUS_ORIENTATION_TRACKED | VRAPI_TRACKING_STATUS_ORIENTATION_VALID |
	                  VRAPI_TRACKING_STATUS_HMD_CONNECTED;
	tracking.HeadPose = rigid_body(head, absTimeInSeconds);
	for (int eye = 0; eye < 2; eye++) {
		const Pose pose = compose(head, offsets[eye].pose);
		tracking.Eye[eye].ViewMatrix = view_matrix(pose);
		tracking.Eye[eye].ProjectionMatrix = projection(offsets[eye].fov);
	}
	return tracking;
}

ovrTracking
vrapi_GetPredictedTracking(ovrMobile *ovr, double absTimeInSeconds)
{
	VRAPI_TRACE("vrapi_GetPredictedTracking");
	const ovrTracking2 full = vrapi_GetPredictedTracking2(ovr, absTimeInSeconds);
	ovrTracking tracking{};
	tracking.Status = full.Status;
	tracking.HeadPose = full.HeadPose;
	return tracking;
}

ovrResult
vrapi_WaitFrame(ovrMobile *ovr, uint64_t frameIndex)
{
	VRAPI_TRACE("vrapi_WaitFrame");
	if (ovr == nullptr) {
		return ovrError_InvalidParameter;
	}
	open_frame(ovr, frameIndex);
	return ovrSuccess;
}

ovrResult
vrapi_BeginFrame(ovrMobile *ovr, uint64_t frameIndex)
{
	VRAPI_TRACE("vrapi_BeginFrame");
	if (ovr == nullptr) {
		return ovrError_InvalidParameter;
	}
	open_frame(ovr, frameIndex);
	std::lock_guard<std::recursive_mutex> guard(global().lock);
	if (ovr->frame_real && global().backend.session_running()) {
		global().backend.begin_frame();
	}
	return ovrSuccess;
}

ovrResult
vrapi_SubmitFrame2(ovrMobile *ovr, const ovrSubmitFrameDescription2 *frameDescription)
{
	VRAPI_TRACE("vrapi_SubmitFrame2");
	if (ovr == nullptr || frameDescription == nullptr) {
		return ovrError_InvalidParameter;
	}
	open_frame(ovr, frameDescription->FrameIndex);

	Global &state = global();
	std::lock_guard<std::recursive_mutex> guard(state.lock);
	std::vector<ProjectionLayer> layers;
	if (ovr->frame_real && state.backend.session_running()) {
		state.backend.begin_frame();
		for (uint32_t i = 0; i < frameDescription->LayerCount && i < ovrMaxLayerCount; i++) {
			const ovrLayerHeader2 *header = frameDescription->Layers[i];
			if (header == nullptr) {
				continue;
			}
			if (header->Type == VRAPI_LAYER_TYPE_PROJECTION2) {
				add_projection(ovr, *reinterpret_cast<const ovrLayerProjection2 *>(header), layers.empty(), layers);
			} else {
				warn_layer_once(header->Type);
			}
		}
	}
	static unsigned submitted = 0;
	if (submitted++ % 300 == 0) {
		VRAPI_LOG("frame %u: real %d running %d, %u layers from the game, %zu to the runtime", submitted,
		          ovr->frame_real ? 1 : 0, state.backend.session_running() ? 1 : 0, frameDescription->LayerCount,
		          layers.size());
	}
	close_frame(ovr, layers);
	return ovrSuccess;
}

void
vrapi_SubmitFrame(ovrMobile *ovr, const ovrFrameParms *parms)
{
	VRAPI_TRACE("vrapi_SubmitFrame");
	if (ovr == nullptr || parms == nullptr) {
		return;
	}
	// The monolithic layers of the old API are tan-angle eye layers, the same as Projection2.
	ovrSubmitFrameDescription2 description{};
	description.FrameIndex = static_cast<uint64_t>(parms->FrameIndex);
	std::array<ovrLayerProjection2, VRAPI_FRAME_LAYER_TYPE_MAX> converted{};
	std::array<const ovrLayerHeader2 *, VRAPI_FRAME_LAYER_TYPE_MAX> pointers{};
	const int count = std::min(parms->LayerCount, static_cast<int>(VRAPI_FRAME_LAYER_TYPE_MAX));
	for (int i = 0; i < count; i++) {
		const ovrFrameLayer &source = parms->Layers[i];
		ovrLayerProjection2 &layer = converted[i];
		layer.Header.Type = VRAPI_LAYER_TYPE_PROJECTION2;
		layer.Header.Flags = static_cast<uint32_t>(source.Flags);
		layer.Header.SrcBlend = source.SrcBlend;
		layer.Header.DstBlend = source.DstBlend;
		layer.HeadPose = source.Textures[0].HeadPose;
		for (int eye = 0; eye < 2; eye++) {
			layer.Textures[eye].ColorSwapChain = source.Textures[eye].ColorTextureSwapChain;
			layer.Textures[eye].SwapChainIndex = source.Textures[eye].TextureSwapChainIndex;
			layer.Textures[eye].TexCoordsFromTanAngles = source.Textures[eye].TexCoordsFromTanAngles;
			layer.Textures[eye].TextureRect = source.Textures[eye].TextureRect;
		}
		pointers[i] = &layer.Header;
	}
	description.LayerCount = static_cast<uint32_t>(std::max(count, 0));
	description.Layers = pointers.data();
	vrapi_SubmitFrame2(ovr, &description);
}

ovrResult
vrapi_PollEvent(ovrEventHeader *event)
{
	VRAPI_TRACE("vrapi_PollEvent");
	if (event == nullptr) {
		return ovrError_InvalidParameter;
	}
	std::lock_guard<std::recursive_mutex> guard(global().lock);
	std::deque<ovrEventType> &queue = events();
	event->EventType = VRAPI_EVENT_NONE;
	if (!queue.empty()) {
		event->EventType = queue.front();
		queue.pop_front();
	}
	return ovrSuccess;
}

} // extern "C"

VRAPI_EXTRA_EXPORT int
vrapi_GetPredictedTracking3()
{
	VRAPI_TRACE("vrapi_GetPredictedTracking3");
	return ovrError_NotImplemented;
}
