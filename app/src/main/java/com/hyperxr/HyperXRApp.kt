package com.hyperxr

import android.app.Application
import com.hyperxr.capabilities.AndroidCapabilityProbe
import com.hyperxr.environments.EnvironmentRepository
import com.hyperxr.runtimebroker.AndroidRuntimeBroker
import com.hyperxr.runtimebroker.SessionArbiter
import com.hyperxr.sdkhost.PackageRepository
import com.hyperxr.settings.FileSettingsStorage
import com.hyperxr.settings.SettingsRepository
import com.hyperxr.xrapps.XrAppDiscovery
import java.io.File

/**
 * Process-wide service registry. Each process (main and :game) gets its own instance, so nothing
 * here may rely on in-memory state shared between processes: settings and grants live on disk.
 */
class HyperXRApp : Application() {
    val settings: SettingsRepository by lazy { SettingsRepository(FileSettingsStorage(File(filesDir, "settings.json"))) }
    val capabilities: AndroidCapabilityProbe by lazy { AndroidCapabilityProbe(this) }
    val runtimeBroker: AndroidRuntimeBroker by lazy { AndroidRuntimeBroker(this) }
    val sessionArbiter = SessionArbiter()
    val packages: PackageRepository by lazy { PackageRepository(this) }
    val environments: EnvironmentRepository by lazy { EnvironmentRepository(this) }
    val xrApps: XrAppDiscovery by lazy { XrAppDiscovery(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
        // the Minecraft process belongs to its JVM (own signal handlers, exits by itself)
        if (processName().endsWith(":minecraft")) return
        com.hyperxr.diagnostics.CrashReporter.install(this)
        com.hyperxr.diagnostics.NativeCrashGuard.init(this)
    }

    private fun processName(): String =
        if (android.os.Build.VERSION.SDK_INT >= 28) getProcessName()
        else runCatching { File("/proc/self/cmdline").readText().trimEnd('\u0000') }.getOrDefault("")

    companion object {
        lateinit var instance: HyperXRApp
            private set
    }
}
