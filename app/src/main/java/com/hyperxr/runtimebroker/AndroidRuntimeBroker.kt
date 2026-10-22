package com.hyperxr.runtimebroker

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import com.hyperxr.diagnostics.Diagnostics

/**
 * HyperXR Runtime Broker. It does not replace the Khronos runtime broker or the loader: it
 * discovers what they would use, reports capabilities and errors, and arbitrates sessions.
 *
 * Discovery sources:
 *  - The Khronos broker content providers (installable `org.khronos.openxr.runtime_broker`
 *    and the system broker), which report the active runtime per ABI.
 *  - Installed services handling `org.khronos.openxr.OpenXRRuntimeService` (how Monado and other
 *    runtimes advertise themselves).
 */
class AndroidRuntimeBroker(private val context: Context) {
    @Volatile
    var lastProbe: RuntimeProbe? = null

    fun discover(): List<RuntimeDescriptor> {
        val out = LinkedHashMap<String, RuntimeDescriptor>()
        for (authority in BROKER_AUTHORITIES) {
            queryBroker(authority)?.let { out[it.packageName] = it }
        }
        val pm = context.packageManager
        val services = try {
            if (Build.VERSION.SDK_INT >= 33) pm.queryIntentServices(Intent(RUNTIME_SERVICE), PackageManager.ResolveInfoFlags.of(PackageManager.GET_META_DATA.toLong()))
            else @Suppress("DEPRECATION") pm.queryIntentServices(Intent(RUNTIME_SERVICE), PackageManager.GET_META_DATA)
        } catch (t: Throwable) { emptyList() }
        for (ri in services) {
            val si = ri.serviceInfo ?: continue
            if (out.containsKey(si.packageName)) continue
            val label = si.applicationInfo.loadLabel(pm).toString()
            val so = si.metaData?.getString("org.khronos.openxr.OpenXRRuntime.SoFilename")
                ?: si.metaData?.getString("org.khronos.openxr.OpenXRRuntime")
            val version = try { pm.getPackageInfo(si.packageName, 0).versionName } catch (e: Exception) { null }
            out[si.packageName] = RuntimeDescriptor(si.packageName, label, RuntimeSource.SERVICE_DISCOVERY, so, si.applicationInfo.nativeLibraryDir, version)
        }
        return out.values.toList()
    }

    /** Queries `content://<authority>/openxr/1/abi/<abi>/runtimes/active/0`. */
    private fun queryBroker(authority: String): RuntimeDescriptor? {
        val abi = Build.SUPPORTED_ABIS.firstOrNull() ?: return null
        val uri = Uri.parse("content://$authority/openxr/1/abi/$abi/runtimes/active/0")
        return try {
            context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (!c.moveToFirst()) return null
                fun col(name: String) = c.getColumnIndex(name).takeIf { it >= 0 }?.let { c.getString(it) }
                val pkg = col("package_name") ?: return null
                val label = try {
                    context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
                } catch (e: Exception) { pkg }
                RuntimeDescriptor(pkg, label, RuntimeSource.BROKER_ACTIVE, col("so_filename"), col("native_lib_dir"))
            }
        } catch (t: Throwable) {
            Diagnostics.log("Runtime broker $authority unavailable: ${t.javaClass.simpleName}")
            null
        }
    }

    fun brokerInstalled(): Boolean = BROKER_AUTHORITIES.any { context.packageManager.resolveContentProvider(it, 0) != null }

    /** Which package holds each broker authority (null = free), to explain install conflicts. */
    fun brokerOwners(): Map<String, String?> = BROKER_AUTHORITIES.associateWith { auth ->
        try { context.packageManager.resolveContentProvider(auth, 0)?.packageName } catch (_: Throwable) { null }
    }

    companion object {
        const val RUNTIME_SERVICE = "org.khronos.openxr.OpenXRRuntimeService"
        val BROKER_AUTHORITIES = listOf("org.khronos.openxr.runtime_broker", "org.khronos.openxr.system_runtime_broker")
        const val MONADO_PROJECT_URL = "https://gitlab.freedesktop.org/monado/monado"
        const val BROKER_PROJECT_URL = "https://github.com/KhronosGroup/OpenXR-SDK-Source/tree/main/src/loader"
    }
}
