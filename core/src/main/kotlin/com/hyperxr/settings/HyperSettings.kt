package com.hyperxr.settings

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Launcher look inside the XR session. Hyper: HyperMagic's own (windows side by side over the
 * dock, the library opens from it). Meta: like Meta Horizon OS (the library is home, in front of
 * you, with a side list and a grid of icons, and a light round-button dock under it). Saved as the
 * names of the modes they replace (Dock, New), so settings from older versions still load.
 */
@Serializable
enum class LauncherMode { @SerialName("DOCK") HYPER, @SerialName("NEW") META }

/** Which immersive mode the user prefers; AUTO means "MR when the device really supports it". */
@Serializable
enum class XrModePreference { AUTO, MIXED_REALITY, VR_SBS, OPENXR_RUNTIME }

@Serializable
enum class HeadTrackingSource { AUTO, ARCORE_6DOF, ROTATION_VECTOR_3DOF, OPENXR_RUNTIME, VISUAL_GYRO }

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
    /** Rotational timewarp: re-aim the finished image with the gyroscope right before it is shown. */
    val timewarp: Boolean = true,
    /** Brown-Conrady radial coefficients for the VR Box lenses. */
    val distortionK1: Float = 0.22f,
    val distortionK2: Float = 0.24f,
    /** Inter-pupillary distance in millimetres. */
    val ipdMm: Float = 63f,
    /** Distance between lens centres of the viewer, in millimetres. */
    val lensSeparationMm: Float = 60f,
    val fovDegrees: Float = 95f,
    /** The headset chosen in RealityLenses (ViewerCatalog id). */
    val viewerId: String = "vrbox-1",
    val screenToLensMm: Float = 42f,
    val trayToLensMm: Float = 35f,
    /** Camera image fills each eye completely (centre crop). Off = true angular size (smaller image). */
    val passthroughFill: Boolean = true,
    /** Stereo (IPD) for virtual content over the mono camera image. Off keeps it aligned with reality. */
    val stereoInMixedReality: Boolean = false,
    /** VR boundary warning: fade the camera in near real obstacles (needs ARCore Depth). */
    val vrBoundary: Boolean = true,
    /** The OS-style boot animation when HyperXR starts. */
    val bootAnimation: Boolean = true,
    /** Distance in metres at which the boundary warning starts. */
    val boundaryDistanceM: Float = 1.0f,
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
    val backend: InferenceBackend = InferenceBackend.GPU,
    val useArCoreDepth: Boolean = true,
    /** Keep the camera at 60 fps even when this phone offers the Depth API only at 30 fps. */
    val prefer60FpsOverDepth: Boolean = false,
    /** How tracked hands are drawn. */
    val handVisual: HandVisual = HandVisual.AURA,
    /** Skin of the VR hands and arms: 0 light .. 4 dark (see [SKIN_TONES]). */
    val skinTone: Int = 1,
    val auraOpacity: Float = 0.8f,
    /** Point with the pinch ball (what is under it, seen from the eyes); false = shoulder ray. */
    val ballPointer: Boolean = true,
    /** Upward tilt of the hand ray (degrees) so a relaxed hand reaches eye-level windows. */
    val rayPitchDeg: Float = 25f,
    /** Kalman process noise (m/s²)²: higher follows fast motion, lower smooths more. */
    val kalmanProcessNoise: Float = 3f,
    val directTouch: Boolean = true,
    /** How a hand clicks from a distance: pinch (default), or point with the index finger and push. */
    val clickGesture: ClickGesture = ClickGesture.PINCH,
    /** Fingers that type on the table keyboard (0 thumb, 1 index, 2 middle, 3 ring, 4 pinky). */
    val tableFingers: Set<Int> = setOf(1, 2),
    /** AI depth (Depth Anything V2 on the NPU) confirms table taps; the model is downloaded once. */
    val aiDepth: Boolean = true,
    /** Real hands drawn over the UI in mixed reality (hand segmentation on the NPU). */
    /** Thumb-index distance as a fraction of the palm length that counts as a pinch. */
    /** Pinch closes below this thumb–index distance / palm length (model landmarks). Renamed so old 0.25 values reset. */
    val pinchClose: Float = 0.38f,
    /** Your hand length, wrist crease to middle fingertip, in cm (measure with a ruler). */
    val handLengthCm: Float = 18.5f,
    /** Measure the palm with ARCore Depth and use it instead of [handLengthCm]. */
    val autoCalibrateHandSize: Boolean = true,
    /** Palm length (wrist to middle-finger base) measured by auto calibration, mm; 0 = not measured yet. */
    val calibratedPalmMm: Float = 0f,
    val filterMinCutoff: Float = 1.2f,
    val filterBeta: Float = 0.02f,
    val predictionMs: Float = 20f,
)

@Serializable
/** The hands are drawn as the lilac aura or not at all (SKELETON and BOTH are read from old settings files and become AURA). */
enum class ClickGesture { POINT_PUSH, PINCH }

enum class HandVisual { AURA, NONE, SKELETON, BOTH }

/** Skin colours of the VR hands (albedo, 0..1) and their names. */
val SKIN_TONES = arrayOf(
    floatArrayOf(0.93f, 0.75f, 0.65f), floatArrayOf(0.85f, 0.64f, 0.53f), floatArrayOf(0.74f, 0.53f, 0.40f),
    floatArrayOf(0.58f, 0.40f, 0.29f), floatArrayOf(0.40f, 0.27f, 0.19f),
)
val SKIN_TONE_NAMES = arrayOf("Clara", "Média clara", "Média", "Morena", "Negra")

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
    /** Look at a button for [dwellMs] to click it (off: it clicked things just by pointing at them). */
    val dwellClick: Boolean = false,
    /** Joy-Con stick cursor speed, degrees per second at full deflection. */
    val stickCursorSpeed: Float = 75f,
    val dwellMs: Int = 1100,
    /** HyperConnect: a second phone as the controller over Bluetooth. */
    val hyperConnect: Boolean = true,
    /** HyperConnect: buzz the controller phone when the pointer reaches a button. */
    val hyperConnectHaptics: Boolean = true,
    /** A controller (or any object) marked with coloured spots, tracked by the camera; calibrated in its own screen. */
    val colorController: Boolean = false,
    /** A held controller shows as a VR controller model in the hand. */
    val controllerModel: Boolean = true,
    /** The hand stays drawn (closed around the controller) while it holds one. */
    val showHandsWithController: Boolean = true,
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
    val mode: LauncherMode = LauncherMode.HYPER,
    val pinned: List<String> = DEFAULT_PINNED,
    val recents: List<String> = emptyList(),
    /** New-mode free placement, appId -> [yawDeg, pitchDeg, distanceM]. */
    val spatialLayout: Map<String, List<Float>> = emptyMap(),
    val environmentId: String = "builtin:studio",
    /** Reopen the windows (browser tabs, Android apps) that were open when XR was last closed. */
    val restoreWindows: Boolean = true,
    /** The built-in 360 environments follow the clock (night street at night, sunset at dusk...). */
    val autoEnvironmentByTime: Boolean = false,
) {
    companion object {
        val DEFAULT_PINNED = listOf(
            "hx:browser", "game:handlab", "hx:gallery", "hx:store", "hx:files", "web:youtube", "hx:settings",
        )
    }
}

/** First run: the setup assistant (2D) and the gesture tutorial (inside XR), each shown once. */
@Serializable
data class OnboardingSettings(
    val wizardDone: Boolean = false,
    val xrTutorialDone: Boolean = false,
)

@Serializable
data class HyperSettings(
    val version: Int = CURRENT_VERSION,
    val xrMode: XrModePreference = XrModePreference.AUTO,
    /** Language of the texts: "pt", "en", "es", "ru", or "" to follow the phone. */
    val language: String = "",
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
    val onboarding: OnboardingSettings = OnboardingSettings(),
) {
    companion object {
        const val CURRENT_VERSION = 5

        /** Settings saved by an older version, brought to the current defaults where they changed. */
        fun migrate(s: HyperSettings): HyperSettings {
            var m = s
            // v2: the hands are only the lilac aura (the skeleton and the old black aura are gone)
            val hv = m.handTracking.handVisual
            if (hv == HandVisual.SKELETON || hv == HandVisual.BOTH)
                m = m.copy(handTracking = m.handTracking.copy(handVisual = HandVisual.AURA))
            if (m.version < 2) m = m.copy(handTracking = m.handTracking.copy(auraOpacity = 0.8f))
            // v3: no automatic click by pointing (it was on by default and clicked by accident)
            if (m.version < 3) m = m.copy(controllers = m.controllers.copy(dwellClick = false))
            // v5: back to the pinch click (point-and-push stays available as an option)
            if (m.version < 5) m = m.copy(handTracking = m.handTracking.copy(clickGesture = ClickGesture.PINCH))
            return if (m.version < CURRENT_VERSION) m.copy(version = CURRENT_VERSION) else m
        }
    }
}
