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
        com.hyperxr.diagnostics.CrashReporter.install(this)
    }

    companion object {
        lateinit var instance: HyperXRApp
            private set
    }
}
