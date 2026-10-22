package com.hyperxr.openxr

import android.app.Activity
import com.hyperxr.runtimebroker.RuntimeProbe

/** JNI entry points of app/src/main/cpp/openxr_bridge.cpp. */
object OpenXrNative {
    init { System.loadLibrary("hyperxr") }

    /** Initialises the Khronos loader and asks the active runtime for its real properties. */
    fun probe(activity: Activity): RuntimeProbe {
        val text = try { nativeProbe(activity) } catch (t: Throwable) { "ok=false\nerror=${t.message}" }
        val map = text.lineSequence().filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
        return RuntimeProbe(
            ok = map["ok"] == "true",
            runtimeName = map["runtimeName"].orEmpty(),
            runtimeVersion = map["runtimeVersion"].orEmpty(),
            systemName = map["systemName"].orEmpty(),
            extensions = map["extensions"].orEmpty().split(',').filter { it.isNotBlank() },
            error = map["error"],
        )
    }

    @JvmStatic external fun nativeProbe(activity: Activity): String
    @JvmStatic external fun nativeCreateSession(activity: Activity, errorOut: Array<String?>): Long
    @JvmStatic external fun nativeBeginFrame(handle: Long, out: FloatArray): Int
    @JvmStatic external fun nativeEndFrame(handle: Long, rendered: Boolean)
    @JvmStatic external fun nativeSessionState(handle: Long): String
    @JvmStatic external fun nativeRequestExit(handle: Long)
    @JvmStatic external fun nativeDestroySession(handle: Long)
    /** Drives a GLES session on the HyperXR runtime (libhyperxr_openxr.so) for [seconds]; returns a report. */
    @JvmStatic external fun nativeRuntimeSelfTest(activity: Activity, seconds: Int): String
}
