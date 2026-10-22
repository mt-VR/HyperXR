package com.hyperxr.shell

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import com.hyperxr.BuildConfig
import com.hyperxr.diagnostics.Diagnostics
import rikka.shizuku.Shizuku

enum class ShizukuState { NOT_INSTALLED, NOT_RUNNING, NO_PERMISSION, READY }

/** App-side access to [ShellService] through Shizuku. */
object ShellBridge {
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4711

    @Volatile var service: IShellService? = null
        private set
    private var binding = false
    private val waiters = ArrayList<(IShellService?) -> Unit>()

    fun state(context: Context): ShizukuState {
        val installed = try { context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0); true } catch (e: PackageManager.NameNotFoundException) { false }
        val running = try { Shizuku.pingBinder() } catch (t: Throwable) { false }
        return when {
            !running && !installed -> ShizukuState.NOT_INSTALLED
            !running -> ShizukuState.NOT_RUNNING
            Shizuku.isPreV11() -> ShizukuState.NOT_RUNNING
            Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED -> ShizukuState.NO_PERMISSION
            else -> ShizukuState.READY
        }
    }

    /** Shows Shizuku's permission dialog; [onResult] receives true when granted. */
    fun requestPermission(onResult: (Boolean) -> Unit) {
        val l = object : Shizuku.OnRequestPermissionResultListener {
            override fun onRequestPermissionResult(requestCode: Int, grantResult: Int) {
                if (requestCode != REQUEST_CODE) return
                Shizuku.removeRequestPermissionResultListener(this)
                onResult(grantResult == PackageManager.PERMISSION_GRANTED)
            }
        }
        Shizuku.addRequestPermissionResultListener(l)
        try { Shizuku.requestPermission(REQUEST_CODE) } catch (t: Throwable) { Shizuku.removeRequestPermissionResultListener(l); onResult(false) }
    }

    private var argsCache: Shizuku.UserServiceArgs? = null

    /**
     * The service version changes with every install: Shizuku reuses a running service of the same
     * version, which after an update would still run the old code.
     */
    private fun args(context: Context): Shizuku.UserServiceArgs = argsCache ?: run {
        val installed = try { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime } catch (t: Throwable) { 0L }
        Shizuku.UserServiceArgs(ComponentName(BuildConfig.APPLICATION_ID, ShellService::class.java.name))
            .daemon(false)
            .processNameSuffix("shell")
            .debuggable(BuildConfig.DEBUG)
            .version(((installed / 1000L) % Int.MAX_VALUE).toInt().coerceAtLeast(1))
            .also { argsCache = it }
    }

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val s = if (binder != null && binder.pingBinder()) IShellService.Stub.asInterface(binder) else null
            service = s
            binding = false
            Diagnostics.log("Shizuku shell service: ${s?.let { runCatching { it.info() }.getOrNull() } ?: "unavailable"}")
            val w = synchronized(waiters) { waiters.toList().also { waiters.clear() } }
            w.forEach { it(s) }
        }

        override fun onServiceDisconnected(name: ComponentName?) { service = null }
    }

    /** Binds the shell service (once) and calls [ready] with it, or with null on failure. */
    fun withService(context: Context, ready: (IShellService?) -> Unit) {
        service?.let { if (it.asBinder().pingBinder()) { ready(it); return } }
        if (state(context) != ShizukuState.READY) { ready(null); return }
        synchronized(waiters) { waiters += ready }
        if (binding) return
        binding = true
        try { Shizuku.bindUserService(args(context), connection) } catch (t: Throwable) {
            binding = false
            Diagnostics.log("Shizuku bind failed: ${t.message}")
            val w = synchronized(waiters) { waiters.toList().also { waiters.clear() } }
            w.forEach { it(null) }
        }
    }

    fun unbind() {
        argsCache?.let { a -> try { Shizuku.unbindUserService(a, connection, true) } catch (_: Throwable) {} }
        service = null
    }
}
