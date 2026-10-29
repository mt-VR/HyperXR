package com.hyperxr.browser

import android.app.Activity
import com.hyperxr.diagnostics.Diagnostics
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoRuntimeSettings

/**
 * One GeckoView runtime per process (Mozilla's engine, the same one Wolvic uses for XR browsing).
 * Pages render straight into the Surface of a window's texture, like Wolvic does, so there is no
 * hidden Android View or screen capture in between.
 *
 * One GeckoView per APK (it is ~150 MB per ABI): arm64 in the main APK, 32-bit ARM in the 32-bit
 * one; anywhere else the WebView fallback is used.
 */
object GeckoEngine {
    /** Virtual screen of every browser window (also the size reported to pages). */
    const val WIDTH = 1600
    const val HEIGHT = 1000
    /** CSS pixel density: 1600 px / 1.5 = 1066 CSS px wide, a comfortable tablet-like layout. */
    const val DENSITY = 1.5f

    @Volatile private var runtime: GeckoRuntime? = null
    @Volatile var error: String? = null; private set

    /** The engine's native library is in this APK for the ABI the app runs as (arm64 or 32-bit ARM builds). */
    val supported: Boolean by lazy {
        runCatching { java.io.File(com.hyperxr.HyperXRApp.instance.applicationInfo.nativeLibraryDir, "libxul.so").exists() }.getOrDefault(false)
    }

    /** UI thread. Returns null (with [error]) when the engine cannot run here. */
    fun runtime(activity: Activity): GeckoRuntime? {
        runtime?.let { return it }
        if (!supported) { error = "GeckoView não está incluído para este processador"; return null }
        return try {
            val settings = GeckoRuntimeSettings.Builder()
                .displayDensityOverride(DENSITY)
                .screenSizeOverride(WIDTH, HEIGHT)
                .javaScriptEnabled(true)
                .consoleOutput(false)
                .loginAutofillEnabled(true)
                .build()
            GeckoRuntime.create(activity.applicationContext, settings).also { runtime = it }
        } catch (t: Throwable) {
            error = "${t.javaClass.simpleName}: ${t.message}"
            Diagnostics.log("GeckoView unavailable: $error")
            null
        }
    }
}
