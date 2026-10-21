package com.hyperxr.capabilities

import com.hyperxr.settings.XrModePreference

/** Tri-state support flag: we never report "supported" for something we did not actually probe. */
enum class Support { SUPPORTED, UNSUPPORTED, UNKNOWN, NEEDS_PERMISSION, NEEDS_INSTALL }

data class Capability(val support: Support, val detail: String = "") {
    val ok: Boolean get() = support == Support.SUPPORTED

    companion object {
        fun yes(detail: String = "") = Capability(Support.SUPPORTED, detail)
        fun no(detail: String) = Capability(Support.UNSUPPORTED, detail)
        fun unknown(detail: String) = Capability(Support.UNKNOWN, detail)
    }
}

data class CameraInfo(
    val id: String,
    val facingBack: Boolean,
    val hardwareLevel: String,
    val maxFps: Int,
    val depthOutput: Boolean,
    /** True when sensor timestamps share the clock with SystemClock.elapsedRealtimeNanos. */
    val realtimeTimestamps: Boolean,
)

data class InferenceAccelerator(val name: String, val type: String, val backend: String)

data class DeviceCapabilities(
    val androidSdk: Int = 0,
    val androidRelease: String = "",
    val manufacturer: String = "",
    val model: String = "",
    val socManufacturer: String = "",
    val socModel: String = "",
    val abis: List<String> = emptyList(),
    val glesVersion: String = "",
    val vulkan: Capability = Capability.unknown("not probed"),
    val rotationVector: Capability = Capability.unknown("not probed"),
    val gyroscope: Capability = Capability.unknown("not probed"),
    val accelerometer: Capability = Capability.unknown("not probed"),
    val cameras: List<CameraInfo> = emptyList(),
    val cameraPermission: Boolean = false,
    val arCore: Capability = Capability.unknown("not probed"),
    val arCoreDepth: Capability = Capability.unknown("not probed"),
    val openXrRuntime: Capability = Capability.unknown("not probed"),
    val nnapi: Capability = Capability.unknown("not probed"),
    val accelerators: List<InferenceAccelerator> = emptyList(),
    val gpuDelegate: Capability = Capability.unknown("not probed"),
    val bluetooth: Capability = Capability.unknown("not probed"),
    val joyConsConnected: Int = 0,
    val inputDeviceSensors: Capability = Capability.unknown("not probed"),
    val lowLatencyAudio: Capability = Capability.unknown("not probed"),
    val proAudio: Capability = Capability.unknown("not probed"),
    val audioSampleRate: Int = 0,
    val audioFramesPerBuffer: Int = 0,
    val sustainedPerformance: Capability = Capability.unknown("not probed"),
    val displayRefreshHz: Float = 0f,
    val displayWidthPx: Int = 0,
    val displayHeightPx: Int = 0,
    val displayXdpi: Float = 0f,
) {
    val hasBackCamera: Boolean get() = cameras.any { it.facingBack }
}

/** Final immersive mode actually started, plus the honest reasons behind it. */
enum class XrMode { MIXED_REALITY_ARCORE, MIXED_REALITY_CAMERA, VR_SBS, OPENXR_RUNTIME }

data class XrModeDecision(val mode: XrMode, val headTracking: String, val reasons: List<String>)

/**
 * Decides which immersive mode to launch. MR is the default when the hardware really allows it:
 *  - ARCore available            -> MR with 6DoF head pose and camera image from ARCore.
 *  - back camera + permission    -> MR "camera see-through" with 3DoF rotation (mono video, flagged as such).
 *  - otherwise                   -> VR SBS. We never pretend passthrough exists.
 */
object XrModeResolver {
    fun resolve(caps: DeviceCapabilities, pref: XrModePreference): XrModeDecision {
        val reasons = mutableListOf<String>()
        val threeDof = caps.rotationVector.ok || caps.gyroscope.ok
        val headTracking3 = if (caps.rotationVector.ok) "3DoF (rotation vector)" else if (caps.gyroscope.ok) "3DoF (gyroscope)" else "none"

        if (pref == XrModePreference.OPENXR_RUNTIME) {
            if (caps.openXrRuntime.ok) return XrModeDecision(XrMode.OPENXR_RUNTIME, "OpenXR runtime", listOf("Runtime: ${caps.openXrRuntime.detail}"))
            reasons += "OpenXR runtime requested but not available: ${caps.openXrRuntime.detail}"
        }

        val wantMr = pref == XrModePreference.AUTO || pref == XrModePreference.MIXED_REALITY
        if (wantMr) {
            if (!caps.hasBackCamera) {
                reasons += "No back camera found: mixed reality is not possible on this device"
            } else if (!caps.cameraPermission) {
                reasons += "Camera permission not granted: mixed reality disabled"
            } else if (caps.arCore.ok) {
                reasons += "ARCore available: camera passthrough with 6DoF head tracking"
                if (!caps.arCoreDepth.ok) reasons += "ARCore Depth API unavailable: no occlusion, hand depth uses size prior"
                return XrModeDecision(XrMode.MIXED_REALITY_ARCORE, "6DoF (ARCore)", reasons)
            } else if (threeDof) {
                reasons += "ARCore unavailable (${caps.arCore.detail}): camera passthrough with $headTracking3 only"
                reasons += "Passthrough is a mono camera feed, not stereo reprojection"
                return XrModeDecision(XrMode.MIXED_REALITY_CAMERA, headTracking3, reasons)
            } else {
                reasons += "No orientation sensors: cannot keep virtual content anchored over the camera image"
            }
        }
        if (!threeDof) reasons += "No rotation sensors: head tracking unavailable, content follows the screen"
        return XrModeDecision(XrMode.VR_SBS, headTracking3, reasons)
    }
}
