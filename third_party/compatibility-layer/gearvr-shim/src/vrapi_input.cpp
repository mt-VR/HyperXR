// Controllers of the VrApi layer.
//
// Compatibility-Layer exposes Joy-Con and tracked hands as Oculus Touch controllers. In Gear VR mode the right
// one becomes the Gear VR Controller: trigger, touchpad (stick + A click) and back (B). In Quest
// mode both hands are reported as Touch controllers.
#include "vrapi_internal.h"

#include <cmath>

namespace compatibility-layer {

namespace {

constexpr uint16_t kTrackpadMaxX = 299;
constexpr uint16_t kTrackpadMaxY = 199;
constexpr float kStickDeadZone = 0.1f;

bool
gear_vr_style()
{
	return global().product != Product::quest;
}

// Device ids in enumeration order, and the Compatibility-Layer hand behind each (0 left, 1 right).
int
hand_of(ovrDeviceID id)
{
	if (id == kRightDevice) {
		return 1;
	}
	if (id == kLeftDevice && !gear_vr_style()) {
		return 0;
	}
	return -1;
}

void
fill_remote_caps(ovrInputTrackedRemoteCapabilities &caps)
{
	const int hand = hand_of(caps.Header.DeviceID);
	const uint32_t side = hand == 1 ? ovrControllerCaps_RightHand : ovrControllerCaps_LeftHand;
	caps.Header.Type = ovrControllerType_TrackedRemote;
	caps.HapticSamplesMax = 0;
	caps.HapticSampleDurationMS = 0;
	caps.Reserved4 = 0;
	caps.Reserved5 = 0;
	if (gear_vr_style()) {
		caps.ControllerCapabilities = ovrControllerCaps_HasOrientationTracking | ovrControllerCaps_ModelGearVR |
		                              ovrControllerCaps_HasTrackpad | side;
		caps.ButtonCapabilities = ovrButton_A | ovrButton_Enter | ovrButton_Back;
		caps.TrackpadMaxX = kTrackpadMaxX;
		caps.TrackpadMaxY = kTrackpadMaxY;
		caps.TrackpadSizeX = 38.0f;
		caps.TrackpadSizeY = 38.0f;
		caps.TouchCapabilities = ovrTouch_TrackPad;
	} else {
		caps.ControllerCapabilities = ovrControllerCaps_HasOrientationTracking |
		                              ovrControllerCaps_HasPositionTracking | ovrControllerCaps_ModelOculusTouch |
		                              ovrControllerCaps_HasJoystick | ovrControllerCaps_HasAnalogIndexTrigger |
		                              ovrControllerCaps_HasAnalogGripTrigger | side;
		caps.ButtonCapabilities = hand == 1 ? (ovrButton_A | ovrButton_B | ovrButton_RThumb)
		                                    : (ovrButton_X | ovrButton_Y | ovrButton_LThumb | ovrButton_Enter);
		caps.ButtonCapabilities |= ovrButton_Trigger | ovrButton_GripTrigger | ovrButton_Joystick;
		caps.TrackpadMaxX = 0;
		caps.TrackpadMaxY = 0;
		caps.TrackpadSizeX = 0;
		caps.TrackpadSizeY = 0;
		caps.TouchCapabilities = ovrTouch_Joystick | ovrTouch_IndexTrigger;
	}
}

void
fill_remote_state(ovrMobile *ovr, int hand, const Controller &controller, ovrInputStateTrackedRemote &state)
{
	const XrVector2f stick = controller.thumbstick;
	const bool stick_moved = std::hypot(stick.x, stick.y) >= kStickDeadZone;
	const bool trigger = controller.trigger > 0.5f;

	state.Buttons = 0;
	state.Touches = 0;
	state.BatteryPercentRemaining = 100;
	state.RecenterCount = 0;
	state.Reserved = 0;
	state.IndexTrigger = controller.trigger;
	state.GripTrigger = controller.squeeze;
	state.JoystickNoDeadZone = {stick.x, stick.y};
	state.Joystick = stick_moved ? state.JoystickNoDeadZone : ovrVector2f{0, 0};

	if (gear_vr_style()) {
		// The Gear VR back flag is set for one query when the button comes up after a short press.
		const bool back = controller.secondary;
		if (!back && ovr->back_down[hand]) {
			state.Buttons |= ovrButton_Back;
		}
		ovr->back_down[hand] = back;

		if (trigger) {
			state.Buttons |= ovrButton_A | ovrButton_Trigger;
		}
		if (controller.primary) {
			state.Buttons |= ovrButton_Enter;
		}
		state.TrackpadStatus = stick_moved || controller.primary ? 1 : 0;
		state.TrackpadPosition.x = (stick.x + 1.0f) * 0.5f * kTrackpadMaxX;
		state.TrackpadPosition.y = (1.0f - stick.y) * 0.5f * kTrackpadMaxY;
		if (state.TrackpadStatus != 0) {
			state.Touches |= ovrTouch_TrackPad;
		}
		return;
	}

	state.TrackpadStatus = 0;
	state.TrackpadPosition = {0, 0};
	if (hand == 1) {
		state.Buttons |= controller.primary ? ovrButton_A : 0;
		state.Buttons |= controller.secondary ? ovrButton_B : 0;
	} else {
		state.Buttons |= controller.primary ? ovrButton_X : 0;
		state.Buttons |= controller.secondary ? ovrButton_Y : 0;
		state.Buttons |= controller.menu ? ovrButton_Enter : 0;
	}
	if (trigger) {
		state.Buttons |= ovrButton_Trigger;
	}
	if (controller.squeeze > 0.5f) {
		state.Buttons |= ovrButton_GripTrigger;
	}
	if (stick_moved) {
		state.Touches |= ovrTouch_Joystick;
	}
	if (controller.trigger > 0.0f) {
		state.Touches |= ovrTouch_IndexTrigger;
	}
}

} // namespace

} // namespace compatibility-layer

using namespace compatibility-layer;

extern "C" {

ovrResult
vrapi_EnumerateInputDevices(ovrMobile *ovr, const uint32_t index, ovrInputCapabilityHeader *capsHeader)
{
	VRAPI_TRACE("vrapi_EnumerateInputDevices");
	if (ovr == nullptr || capsHeader == nullptr) {
		return ovrError_InvalidParameter;
	}
	const uint32_t count = gear_vr_style() ? 1 : 2;
	if (index >= count) {
		return ovrError_NoDevice;
	}
	capsHeader->Type = ovrControllerType_TrackedRemote;
	capsHeader->DeviceID = index == 0 ? kRightDevice : kLeftDevice;
	return ovrSuccess;
}

ovrResult
vrapi_GetInputDeviceCapabilities(ovrMobile *ovr, ovrInputCapabilityHeader *capsHeader)
{
	VRAPI_TRACE("vrapi_GetInputDeviceCapabilities");
	if (ovr == nullptr || capsHeader == nullptr) {
		return ovrError_InvalidParameter;
	}
	if (hand_of(capsHeader->DeviceID) < 0) {
		return ovrError_NoDevice;
	}
	if (capsHeader->Type != ovrControllerType_TrackedRemote) {
		return ovrError_InvalidParameter;
	}
	fill_remote_caps(*reinterpret_cast<ovrInputTrackedRemoteCapabilities *>(capsHeader));
	return ovrSuccess;
}

ovrResult
vrapi_SetHapticVibrationSimple(ovrMobile *, const ovrDeviceID, const float)
{
	VRAPI_TRACE("vrapi_SetHapticVibrationSimple");
	return ovrSuccess;
}

ovrResult
vrapi_SetHapticVibrationBuffer(ovrMobile *, const ovrDeviceID, const ovrHapticBuffer *)
{
	VRAPI_TRACE("vrapi_SetHapticVibrationBuffer");
	return ovrSuccess;
}

ovrResult
vrapi_GetCurrentInputState(ovrMobile *ovr, const ovrDeviceID deviceID, ovrInputStateHeader *inputState)
{
	VRAPI_TRACE("vrapi_GetCurrentInputState");
	if (ovr == nullptr || inputState == nullptr) {
		return ovrError_InvalidParameter;
	}
	const int hand = hand_of(deviceID);
	if (hand < 0) {
		return ovrError_NoDevice;
	}
	if (inputState->ControllerType != ovrControllerType_TrackedRemote) {
		return ovrError_InvalidParameter;
	}
	Global &state = global();
	std::lock_guard<std::recursive_mutex> guard(state.lock);
	const double now = XrBackend::monotonic_seconds();
	state.backend.sync_input(state.backend.to_xr_time(now));
	auto &remote = *reinterpret_cast<ovrInputStateTrackedRemote *>(inputState);
	remote.Header.TimeInSeconds = now;
	fill_remote_state(ovr, hand, state.backend.controller(hand), remote);
	return ovrSuccess;
}

ovrResult
vrapi_GetInputTrackingState(ovrMobile *ovr, const ovrDeviceID deviceID, const double absTimeInSeconds,
                            ovrTracking *tracking)
{
	VRAPI_TRACE("vrapi_GetInputTrackingState");
	if (ovr == nullptr || tracking == nullptr) {
		return ovrError_InvalidParameter;
	}
	const int hand = hand_of(deviceID);
	if (hand < 0) {
		return ovrError_NoDevice;
	}
	Global &state = global();
	std::lock_guard<std::recursive_mutex> guard(state.lock);
	const double time = absTimeInSeconds > 0 ? absTimeInSeconds : XrBackend::monotonic_seconds();
	state.backend.sync_input(state.backend.to_xr_time(time));
	const Controller &controller = state.backend.controller(hand);
	// The Gear VR Controller reports a pointing pose; Touch reports the grip.
	Pose pose = gear_vr_style() ? controller.aim : controller.grip;
	pose.position.y += floor_offset(ovr);

	*tracking = {};
	if (pose.valid) {
		tracking->Status = VRAPI_TRACKING_STATUS_ORIENTATION_TRACKED | VRAPI_TRACKING_STATUS_ORIENTATION_VALID |
		                   VRAPI_TRACKING_STATUS_POSITION_TRACKED | VRAPI_TRACKING_STATUS_POSITION_VALID;
	}
	tracking->HeadPose = rigid_body(pose, time);
	return ovrSuccess;
}

void
vrapi_RecenterInputPose(ovrMobile *, const ovrDeviceID)
{
	VRAPI_TRACE("vrapi_RecenterInputPose");}

// Hand tracking has no VrApi mapping yet: hands already arrive as controllers.
ovrResult
vrapi_GetHandPose(ovrMobile *, const ovrDeviceID, const double, ovrHandPoseHeader *)
{
	VRAPI_TRACE("vrapi_GetHandPose");
	return ovrError_NoDevice;
}

ovrResult
vrapi_GetHandSkeleton(ovrMobile *, const ovrHandedness, ovrHandSkeletonHeader *)
{
	VRAPI_TRACE("vrapi_GetHandSkeleton");
	return ovrError_NotImplemented;
}

ovrResult
vrapi_GetHandMesh(ovrMobile *, const ovrHandedness, ovrHandMeshHeader *)
{
	VRAPI_TRACE("vrapi_GetHandMesh");
	return ovrError_NotImplemented;
}

} // extern "C"

VRAPI_EXTRA_EXPORT ovrResult
vrapi_GetCurrentInputState2(ovrMobile *ovr, const ovrDeviceID deviceID, ovrInputStateHeader *inputState)
{
	VRAPI_TRACE("vrapi_GetCurrentInputState2");
	return vrapi_GetCurrentInputState(ovr, deviceID, inputState);
}
