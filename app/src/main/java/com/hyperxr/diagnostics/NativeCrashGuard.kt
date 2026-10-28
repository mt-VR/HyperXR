package com.hyperxr.diagnostics

import android.content.Context
import android.content.SharedPreferences

/**
 * Protects HyperXR from native code that crashes the whole app on some phones (an NPU driver
 * that segfaults while TensorFlow Lite prepares a model, a GPU driver that dies on a shader...):
 * such a crash cannot be caught, so the next option in the list would never be tried.
 *
 * Before a risky native step a marker is written to disk (synchronously); it is removed when the
 * step ends, normally or with an exception. A marker still there at the next start means the app
 * died inside that step: it is blocked on this phone (for this app version) and the code moves
 * on to the next option instead of crashing again. Several steps can be pending at once (the hand
 * engine starts on its own thread while a game draws).
 */
object NativeCrashGuard {
    private const val PENDING = "pending_set"
    private var prefs: SharedPreferences? = null
    private var version = 0L
    private val lock = Any()

    fun init(context: Context) {
        val p = context.getSharedPreferences("native_guard", Context.MODE_PRIVATE)
        prefs = p
        version = runCatching {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
        }.getOrDefault(0L)
        val pending = p.getStringSet(PENDING, emptySet()).orEmpty().toSet()
        if (pending.isEmpty()) return
        // the app died inside these steps last time
        val blocked = p.getStringSet(blockedKey(), emptySet()).orEmpty().toMutableSet().apply { addAll(pending) }
        p.edit().putStringSet(blockedKey(), blocked).remove(PENDING).commit()
        // a delegate's compilation cache may be what crashes: start it over
        runCatching { context.cacheDir.listFiles()?.filter { it.name.startsWith("hx") }?.forEach { it.delete() } }
        Diagnostics.log("Native crash guard: ${pending.joinToString()} crashed last time, now skipped on this phone")
    }

    private fun blockedKey() = "blocked_v$version"

    fun blocked(step: String): Boolean = prefs?.getStringSet(blockedKey(), emptySet())?.contains(step) == true

    /** Everything blocked on this phone, for the diagnostics screen. */
    fun blockedSteps(): Set<String> = prefs?.getStringSet(blockedKey(), emptySet()).orEmpty()

    /** Marks [step] as running (until [end]): for things that span several frames. */
    fun begin(step: String) = synchronized(lock) {
        val p = prefs ?: return
        val s = p.getStringSet(PENDING, emptySet()).orEmpty().toMutableSet()
        if (s.add(step)) p.edit().putStringSet(PENDING, s).commit()
    }

    fun end(step: String) = synchronized(lock) {
        val p = prefs ?: return
        val s = p.getStringSet(PENDING, emptySet()).orEmpty().toMutableSet()
        if (s.remove(step)) p.edit().putStringSet(PENDING, s).commit()
    }

    /** Runs [block] with the marker for [step] on disk. */
    fun <T> run(step: String, block: () -> T): T {
        begin(step)
        try { return block() } finally { end(step) }
    }

    /** Forget everything blocked (after a driver update, from the settings). */
    fun reset() { prefs?.edit()?.remove(blockedKey())?.commit() }
}
