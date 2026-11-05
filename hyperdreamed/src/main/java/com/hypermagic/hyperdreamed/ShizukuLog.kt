package com.hypermagic.hyperdreamed

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import rikka.shizuku.Shizuku

enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

/** HyperDreamed's access to [LogService] through Shizuku (logcat, silent install / uninstall). */
object ShizukuLog {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 5120
    private const val APP_PACKAGE = "com.hypermagic.hyperdreamed"

    @Volatile var service: ILogService? = null
        private set
    private var binding = false
    private val waiters = ArrayList<(ILogService?) -> Unit>()

    fun state(context: Context): ShizukuState {
        val installed = try { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true } catch (_: PackageManager.NameNotFoundException) { false }
        val running = try { Shizuku.pingBinder() } catch (_: Throwable) { false }
        return when {
            !running && !installed -> ShizukuState.NOT_INSTALLED
            !running -> ShizukuState.NOT_RUNNING
            Shizuku.isPreV11() -> ShizukuState.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NO_PERMISSION
            else -> ShizukuState.READY
        }
    }

    fun requestPermission(onResult: (Boolean) -> Unit) {
        val l = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(l)
        try { Shizuku.requestPermission(REQUEST_CODE) } catch (_: Throwable) { Shizuku.removeRequestPermissionResultListener(l); onResult(false) }
    }

    private var argsCache: Shizuku.UserServiceArgs? = null

    private fun args(context: Context): Shizuku.UserServiceArgs = argsCache ?: run {
        val stamp = try { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime } catch (_: Throwable) { 0L }
        Shizuku.UserServiceArgs(ComponentName(APP_PACKAGE, LogService::class.java.name))
            .daemon(false).processNameSuffix("log").debuggable(false)
            .version(((stamp / 1000L) % Int.MAX_VALUE).toInt().coerceAtLeast(1))
            .also { argsCache = it }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            service = if (binder != null && binder.pingBinder()) ILogService.Stub.asInterface(binder) else null
            binding = false
            val w = synchronized(waiters) { waiters.toList().also { waiters.clear() } }
            w.forEach { it(service) }
        }
        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    /** Binds the service (once) and calls [ready] with it, or null when Shizuku is not ready. */
    fun withService(context: Context, ready: (ILogService?) -> Unit) {
        service?.let { if (it.asBinder().pingBinder()) { ready(it); return } }
        if (state(context) != ShizukuState.READY) { ready(null); return }
        synchronized(waiters) { waiters += ready }
        if (binding) return
        binding = true
        try { Shizuku.bindUserService(args(context), connection) } catch (_: Throwable) {
            binding = false
            val w = synchronized(waiters) { waiters.toList().also { waiters.clear() } }
            w.forEach { it(null) }
        }
    }

    fun unbind() {
        argsCache?.let { a -> runCatching { Shizuku.unbindUserService(a, connection, true) } }
        service = null
    }
}
