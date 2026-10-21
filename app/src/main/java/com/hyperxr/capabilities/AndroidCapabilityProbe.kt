package com.hyperxr.capabilities

import android.Manifest
import android.app.ActivityManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.media.AudioManager
import android.os.Build
import android.os.PowerManager
import android.view.InputDevice
import android.view.WindowManager
import com.google.ar.core.ArCoreApk
import com.hyperxr.diagnostics.NnapiNative
import com.hyperxr.joycon.JoyConManager
import com.hyperxr.runtimebroker.AndroidRuntimeBroker

/**
 * Probes what the device really offers. Every field is measured, never assumed: when a probe
 * cannot run (missing permission, API too old) the capability says so.
 */
class AndroidCapabilityProbe(private val context: Context) {

    @Volatile
    var last: DeviceCapabilities = DeviceCapabilities()
        private set

    fun probe(runtimeBroker: AndroidRuntimeBroker? = null): DeviceCapabilities {
        val pm = context.packageManager
        val sensors = context.getSystemService(SensorManager::class.java)
        val am = context.getSystemService(ActivityManager::class.java)
        val audio = context.getSystemService(AudioManager::class.java)
        val power = context.getSystemService(PowerManager::class.java)
        val display = context.getSystemService(WindowManager::class.java).defaultDisplay
        val metrics = context.resources.displayMetrics

        fun sensor(type: Int, name: String): Capability {
            val s = sensors.getDefaultSensor(type) ?: return Capability.no("$name not present")
            return Capability.yes("${s.name} (${s.vendor}), min delay ${s.minDelay} µs")
        }

        val accelerators = try {
            NnapiNative.devices().map { row ->
                val p = row.split('|')
                InferenceAccelerator(p.getOrElse(0) { "?" }, p.getOrElse(1) { "?" }, "NNAPI feature level ${p.getOrElse(2) { "?" }}")
            }
        } catch (t: Throwable) {
            emptyList()
        }
        val nnapi = when {
            Build.VERSION.SDK_INT < 27 -> Capability.no("NNAPI requires Android 8.1")
            Build.VERSION.SDK_INT < 29 -> Capability.unknown("NNAPI present; device enumeration requires Android 10")
            accelerators.isEmpty() -> Capability.no("No NNAPI devices reported by the driver")
            accelerators.any { it.type == "accelerator" } -> Capability.yes("${accelerators.count { it.type == "accelerator" }} dedicated accelerator(s)" +
                if (Build.VERSION.SDK_INT >= 35) " (NNAPI is deprecated on Android 15+)" else "")
            else -> Capability(Support.SUPPORTED, "NNAPI devices: ${accelerators.joinToString { it.type }} (no dedicated NPU exposed)")
        }

        val camPermission = context.checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        val cameras = try { probeCameras() } catch (t: Throwable) { emptyList() }

        val arCore = try {
            when (val a = ArCoreApk.getInstance().checkAvailability(context)) {
                ArCoreApk.Availability.SUPPORTED_INSTALLED -> Capability.yes("Google Play Services for AR installed")
                ArCoreApk.Availability.SUPPORTED_APK_TOO_OLD, ArCoreApk.Availability.SUPPORTED_NOT_INSTALLED ->
                    Capability(Support.NEEDS_INSTALL, "Device supports ARCore; install/update Google Play Services for AR")
                ArCoreApk.Availability.UNKNOWN_CHECKING, ArCoreApk.Availability.UNKNOWN_TIMED_OUT, ArCoreApk.Availability.UNKNOWN_ERROR ->
                    Capability.unknown("ARCore availability: $a")
                else -> Capability.no("ARCore not supported on this device ($a)")
            }
        } catch (t: Throwable) {
            Capability.unknown("ARCore check failed: ${t.message}")
        }
        // Depth support is only known once a session exists; the ARCore tracker updates it at runtime.
        val arDepth = if (arCore.ok) last.arCoreDepth.takeIf { it.support != Support.UNKNOWN }
            ?: Capability.unknown("Checked when an ARCore session starts") else Capability.no("Requires ARCore")

        val joyCons = JoyConManager.connectedJoyCons()
        val bt = context.getSystemService(BluetoothManager::class.java)?.adapter
        val bluetooth = when {
            bt == null -> Capability.no("No Bluetooth adapter")
            !pm.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) -> Capability.no("Bluetooth feature missing")
            else -> try {
                if (bt.isEnabled) Capability.yes("Adapter enabled") else Capability.no("Bluetooth is turned off")
            } catch (e: SecurityException) {
                Capability(Support.NEEDS_PERMISSION, "BLUETOOTH_CONNECT not granted (adapter state unknown)")
            }
        }
        val inputSensors = if (Build.VERSION.SDK_INT >= 31) {
            val withSensors = InputDevice.getDeviceIds().toList().mapNotNull { InputDevice.getDevice(it) }
                .count { it.sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null }
            if (withSensors > 0) Capability.yes("$withSensors input device(s) expose a gyroscope")
            else Capability.no("No connected controller exposes IMU data to Android")
        } else Capability.no("Controller IMU access requires Android 12")

        val runtime = runtimeBroker?.let { rb ->
            val list = rb.discover()
            if (list.isEmpty()) Capability.no("No OpenXR runtime installed (e.g. Monado)")
            else Capability.yes(list.joinToString { it.label + if (it.source.name == "BROKER_ACTIVE") " (active)" else "" })
        } ?: last.openXrRuntime

        val caps = DeviceCapabilities(
            androidSdk = Build.VERSION.SDK_INT,
            androidRelease = Build.VERSION.RELEASE,
            manufacturer = Build.MANUFACTURER,
            model = Build.MODEL,
            socManufacturer = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MANUFACTURER else "",
            socModel = if (Build.VERSION.SDK_INT >= 31) Build.SOC_MODEL else Build.HARDWARE,
            abis = Build.SUPPORTED_ABIS.toList(),
            glesVersion = am.deviceConfigurationInfo.glEsVersion,
            vulkan = if (pm.hasSystemFeature(PackageManager.FEATURE_VULKAN_HARDWARE_VERSION)) Capability.yes("Vulkan hardware present") else Capability.no("No Vulkan"),
            rotationVector = sensor(Sensor.TYPE_GAME_ROTATION_VECTOR, "Game rotation vector"),
            gyroscope = sensor(Sensor.TYPE_GYROSCOPE, "Gyroscope"),
            accelerometer = sensor(Sensor.TYPE_ACCELEROMETER, "Accelerometer"),
            cameras = cameras,
            cameraPermission = camPermission,
            arCore = arCore,
            arCoreDepth = arDepth,
            openXrRuntime = runtime,
            nnapi = nnapi,
            accelerators = accelerators,
            gpuDelegate = Capability.unknown("Measured by the hand-tracking benchmark"),
            bluetooth = bluetooth,
            joyConsConnected = joyCons.size,
            inputDeviceSensors = inputSensors,
            lowLatencyAudio = if (pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_LOW_LATENCY)) Capability.yes() else Capability.no("Not declared by device"),
            proAudio = if (pm.hasSystemFeature(PackageManager.FEATURE_AUDIO_PRO)) Capability.yes() else Capability.no("Not declared by device"),
            audioSampleRate = audio.getProperty(AudioManager.PROPERTY_OUTPUT_SAMPLE_RATE)?.toIntOrNull() ?: 0,
            audioFramesPerBuffer = audio.getProperty(AudioManager.PROPERTY_OUTPUT_FRAMES_PER_BUFFER)?.toIntOrNull() ?: 0,
            sustainedPerformance = if (power.isSustainedPerformanceModeSupported) Capability.yes() else Capability.no("Not supported"),
            displayRefreshHz = display.refreshRate,
            displayWidthPx = maxOf(metrics.widthPixels, metrics.heightPixels),
            displayHeightPx = minOf(metrics.widthPixels, metrics.heightPixels),
            displayXdpi = metrics.xdpi,
        )
        last = caps
        return caps
    }

    fun updateDepthSupport(c: Capability) { last = last.copy(arCoreDepth = c) }

    private fun probeCameras(): List<CameraInfo> {
        val cm = context.getSystemService(CameraManager::class.java)
        return cm.cameraIdList.map { id ->
            val ch = cm.getCameraCharacteristics(id)
            val level = when (ch.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL)) {
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "LEVEL_3"
                CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
                else -> "?"
            }
            val fps = ch.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.maxOfOrNull { it.upper } ?: 0
            val caps = ch.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES)?.toList().orEmpty()
            CameraInfo(
                id = id,
                facingBack = ch.get(CameraCharacteristics.LENS_FACING) == CameraMetadata.LENS_FACING_BACK,
                hardwareLevel = level,
                maxFps = fps,
                depthOutput = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_DEPTH_OUTPUT in caps,
                realtimeTimestamps = ch.get(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE) ==
                    CameraMetadata.SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME,
            )
        }
    }
}
