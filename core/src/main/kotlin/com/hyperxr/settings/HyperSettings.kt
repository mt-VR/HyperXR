package com.hyperxr.settings

import kotlinx.serialization.Serializable

/** Launcher presentation inside the XR session. */
@Serializable
enum class LauncherMode { DOCK, NEW }

/** Which immersive mode the user prefers; AUTO means "MR when the device really supports it". */
@Serializable
enum class XrModePreference { AUTO, MIXED_REALITY, VR_SBS, OPENXR_RUNTIME }

@Serializable
enum class HeadTrackingSource { AUTO, ARCORE_6DOF, ROTATION_VECTOR_3DOF, OPENXR_RUNTIME }

@Serializable
enum class InferenceBackend { AUTO, CPU, GPU, NNAPI }

@Serializable
enum class ControllerAction { SELECT, BACK, MENU, GRAB, RECENTER, NONE }

@Serializable
data class GraphicsSettings(
    /** Fraction of the native per-eye resolution used for eye buffers. */
    val renderScale: Float = 0.85f,
    val targetFps: Int = 60,
    val msaaSamples: Int = 2,
    val lensDistortion: Boolean = true,
    /** Brown-Conrady radial coefficients for the VR Box lenses. */
    val distortionK1: Float = 0.22f,
    val distortionK2: Float = 0.24f,
    /** Inter-pupillary distance in millimetres. */
    val ipdMm: Float = 63f,
    /** Distance between lens centres of the viewer, in millimetres. */
    val lensSeparationMm: Float = 60f,
    val fovDegrees: Float = 95f,
    val theme: String = "aurora",
)

@Serializable
data class PerformanceSettings(
    val sustainedPerformanceMode: Boolean = true,
    val dynamicResolution: Boolean = true,
    val thermalThrottleAware: Boolean = true,
    val handInferenceHz: Int = 30,
    val lowLatencyMode: Boolean = true,
)

@Serializable
data class HeadTrackingSettings(
    val source: HeadTrackingSource = HeadTrackingSource.AUTO,
    /** Motion-to-photon prediction horizon applied to rotation, milliseconds. */
    val predictionMs: Float = 16f,
    val smoothing: Float = 0.0f,
    val recenterOnStart: Boolean = true,
)

@Serializable
data class HandTrackingSettings(
    val enabled: Boolean = true,
    val backend: InferenceBackend = InferenceBackend.AUTO,
    val useArCoreDepth: Boolean = true,
    val showAura: Boolean = true,
    val auraOpacity: Float = 0.45f,
    val directTouch: Boolean = true,
    val pinchThreshold: Float = 0.025f,
    val filterMinCutoff: Float = 1.2f,
    val filterBeta: Float = 0.02f,
    val predictionMs: Float = 20f,
)

@Serializable
data class ControllerSettings(
    val joyConEnabled: Boolean = true,
    val buttonR: ControllerAction = ControllerAction.SELECT,
    val buttonL: ControllerAction = ControllerAction.BACK,
    val buttonZR: ControllerAction = ControllerAction.GRAB,
    val buttonZL: ControllerAction = ControllerAction.GRAB,
    val buttonPlus: ControllerAction = ControllerAction.MENU,
    val buttonHome: ControllerAction = ControllerAction.RECENTER,
    val screenTapSelects: Boolean = true,
    val gazeDwellMs: Int = 0,
)

@Serializable
data class AudioSettings(
    val masterVolume: Float = 1f,
    val spatialAudio: Boolean = true,
    val lowLatencyOutput: Boolean = true,
    val uiSounds: Boolean = true,
)

@Serializable
data class AccessibilitySettings(
    val textScale: Float = 1f,
    val highContrast: Boolean = false,
    val reduceMotion: Boolean = false,
    val seatedMode: Boolean = false,
    val oneHandedMode: Boolean = false,
)

@Serializable
data class NetworkSettings(
    val allowEnvironmentDownloads: Boolean = true,
    val downloadsOnWifiOnly: Boolean = true,
    val webAppsDesktopMode: Boolean = false,
)

@Serializable
data class RuntimeSettings(
    val preferredRuntimePackage: String? = null,
    val preferMonado: Boolean = true,
    val allowOpenXrSession: Boolean = true,
)

@Serializable
data class LauncherSettings(
    val mode: LauncherMode = LauncherMode.DOCK,
    val pinned: List<String> = DEFAULT_PINNED,
    val recents: List<String> = emptyList(),
    /** New-mode free placement, appId -> [yawDeg, pitchDeg, distanceM]. */
    val spatialLayout: Map<String, List<Float>> = emptyMap(),
    val environmentId: String = "builtin:studio",
) {
    companion object {
        val DEFAULT_PINNED = listOf(
            "hx:browser", "hx:gallery", "hx:store", "hx:files", "web:youtube", "hx:settings",
        )
    }
}

@Serializable
data class HyperSettings(
    val version: Int = CURRENT_VERSION,
    val xrMode: XrModePreference = XrModePreference.AUTO,
    val launcher: LauncherSettings = LauncherSettings(),
    val graphics: GraphicsSettings = GraphicsSettings(),
    val performance: PerformanceSettings = PerformanceSettings(),
    val headTracking: HeadTrackingSettings = HeadTrackingSettings(),
    val handTracking: HandTrackingSettings = HandTrackingSettings(),
    val controllers: ControllerSettings = ControllerSettings(),
    val audio: AudioSettings = AudioSettings(),
    val accessibility: AccessibilitySettings = AccessibilitySettings(),
    val network: NetworkSettings = NetworkSettings(),
    val runtime: RuntimeSettings = RuntimeSettings(),
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}
