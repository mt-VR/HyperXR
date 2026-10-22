package com.hyperxr.shell

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.SystemClock
import android.view.InputDevice
import android.view.InputEvent
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import kotlin.system.exitProcess

/**
 * Shizuku UserService. It runs in its own process with the shell UID, which is allowed to own
 * virtual displays that host other apps' activities, to start activities on them and to inject
 * input (the same capabilities `scrcpy --new-display` relies on).
 *
 * The app never gains these rights itself: it only talks to this service over Binder, and the
 * service exists only after the user granted HyperXR permission in Shizuku.
 */
class ShellService() : IShellService.Stub() {
    private var context: Context? = null
    private val displays = HashMap<Int, VirtualDisplay>()

    /** Shizuku v13+ passes a Context to this constructor. */
    constructor(context: Context) : this() { this.context = context }

    override fun destroy() {
        displays.values.forEach { it.release() }
        displays.clear()
        exitProcess(0)
    }

    override fun info(): String = "uid=${android.os.Process.myUid()} sdk=${Build.VERSION.SDK_INT}"

    @Volatile private var lastError = ""
    override fun lastError(): String = lastError

    /**
     * Context that identifies as the shell package. This process runs with the shell uid but its
     * context reports the HyperXR package, and system services reject a package name that does
     * not belong to the calling uid (DisplayManager: "packageName must match the calling uid").
     * scrcpy uses the same technique for its virtual displays.
     */
    private class ShellContext(base: Context) : android.content.ContextWrapper(base) {
        override fun getPackageName(): String = SHELL_PACKAGE
        override fun getOpPackageName(): String = SHELL_PACKAGE
        override fun getApplicationContext(): Context = this
        @androidx.annotation.RequiresApi(31)
        override fun getAttributionSource(): android.content.AttributionSource =
            android.content.AttributionSource.Builder(android.os.Process.myUid()).setPackageName(SHELL_PACKAGE).build()
        @Suppress("WrongConstant")
        override fun getSystemService(name: String): Any? = super.getSystemService(name)
    }

    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun systemContext(): Context? = try {
        val at = Class.forName("android.app.ActivityThread")
        val current = at.getMethod("currentActivityThread").invoke(null) ?: at.getMethod("systemMain").invoke(null)
        at.getMethod("getSystemContext").invoke(current) as Context
    } catch (t: Throwable) { context }

    /** A DisplayManager bound to the shell identity (its constructor is hidden). */
    @SuppressLint("PrivateApi", "DiscouragedPrivateApi")
    private fun shellDisplayManager(): DisplayManager? {
        val base = systemContext() ?: context ?: return null
        val shell = ShellContext(base)
        return try {
            val ctor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
            ctor.isAccessible = true
            ctor.newInstance(shell)
        } catch (t: Throwable) {
            lastError = "DisplayManager: ${t.javaClass.simpleName}: ${t.message}"
            shell.getSystemService(DisplayManager::class.java)
        }
    }

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int, name: String): Int {
        lastError = ""
        val dm = shellDisplayManager() ?: run { lastError = "sem contexto no serviço"; return -1 }
        // Same flags scrcpy uses for --new-display: a public display that owns its content, takes
        // touch, and (Android 13/14+) is trusted with its own focus so input and IME work there.
        var flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
            FLAG_SUPPORTS_TOUCH or FLAG_ROTATES_WITH_CONTENT or FLAG_DESTROY_CONTENT_ON_REMOVAL
        val attempts = ArrayList<Int>()
        if (Build.VERSION.SDK_INT >= 33) {
            var full = flags or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
            if (Build.VERSION.SDK_INT >= 34) full = full or FLAG_OWN_FOCUS or FLAG_DEVICE_DISPLAY_GROUP
            attempts += full
            attempts += flags or FLAG_TRUSTED
        }
        attempts += flags
        val errors = ArrayList<String>()
        for (f in attempts) {
            try {
                val vd = dm.createVirtualDisplay(name, width, height, dpi, surface, f)
                if (vd == null) { errors += "flags 0x${Integer.toHexString(f)}: null"; continue }
                displays[vd.display.displayId] = vd
                return vd.display.displayId
            } catch (t: Throwable) {
                errors += "flags 0x${Integer.toHexString(f)}: ${t.javaClass.simpleName}: ${t.message}"
            }
        }
        lastError = errors.joinToString(" | ")
        return -1
    }

    override fun startApp(component: String, displayId: Int): String {
        if (!component.matches(Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$"))) return "componente inválido"
        val cmd = arrayListOf("am", "start", "--display", displayId.toString(), "--activity-multiple-task")
        // fullscreen on the virtual display (Android 10+ also accepts freeform/pinned there)
        if (Build.VERSION.SDK_INT >= 29) { cmd += "--windowingMode"; cmd += "1" }
        cmd += listOf("-n", component)
        val out = run(cmd)
        val failed = out.contains("Error", ignoreCase = true) || out.contains("Exception") || out.contains("Warning: Activity not started")
        if (failed) lastError = out.trim()
        return if (failed) out.trim() else ""
    }

    override fun injectMotion(displayId: Int, action: Int, x: Float, y: Float, downTime: Long, eventTime: Long): Boolean {
        val ev = MotionEvent.obtain(downTime, eventTime, action, x, y, 0)
        ev.source = InputDevice.SOURCE_TOUCHSCREEN
        return try { setDisplayId(ev, displayId) && inject(ev) } finally { ev.recycle() }
    }

    override fun injectKey(displayId: Int, keyCode: Int): Boolean {
        val now = SystemClock.uptimeMillis()
        var ok = true
        for (action in intArrayOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val ev = KeyEvent(now, now, action, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0, InputDevice.SOURCE_KEYBOARD)
            ok = ok && setDisplayId(ev, displayId) && inject(ev)
        }
        return ok
    }

    override fun injectText(displayId: Int, text: String): String {
        // `input text` treats %s as space; keep only printable characters it can type.
        val safe = text.filter { it.code in 0x20..0x7e }.replace("%", "%%").replace(" ", "%s")
        if (safe.isEmpty()) return "somente caracteres ASCII podem ser digitados por injeção"
        return run(listOf("input", "-d", displayId.toString(), "text", safe))
    }

    override fun openUrl(displayId: Int, url: String): String {
        val uri = runCatching { java.net.URI(url) }.getOrNull()
        if (uri == null || (uri.scheme != "https" && uri.scheme != "http")) return "somente links http(s)"
        return run(listOf("am", "start", "--display", displayId.toString(), "-a", "android.intent.action.VIEW", "-d", url))
            .let { out -> if (out.contains("Error", ignoreCase = true)) out.trim() else "" }
    }

    override fun releaseDisplay(displayId: Int) {
        displays.remove(displayId)?.release()
    }

    // ---------------------------------------------------------------- hidden APIs (shell process)
    private fun setDisplayId(ev: InputEvent, displayId: Int): Boolean = try {
        InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType).invoke(ev, displayId)
        true
    } catch (t: Throwable) { false }

    @SuppressLint("PrivateApi")
    private fun inject(ev: InputEvent): Boolean = try {
        val manager = if (Build.VERSION.SDK_INT >= 34) {
            Class.forName("android.hardware.input.InputManagerGlobal").getMethod("getInstance").invoke(null)
        } else {
            Class.forName("android.hardware.input.InputManager").getMethod("getInstance").invoke(null)
        }
        val m = manager.javaClass.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
        m.invoke(manager, ev, 0) as Boolean // INJECT_INPUT_EVENT_MODE_ASYNC
    } catch (t: Throwable) { false }

    private fun run(cmd: List<String>): String = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (t: Throwable) { "Error: ${t.message}" }

    companion object {
        // Hidden DisplayManager flags (values from AOSP DisplayManager.java).
        const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        const val FLAG_ROTATES_WITH_CONTENT = 1 shl 7
        const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val FLAG_TRUSTED = 1 shl 10
        const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13
        const val FLAG_OWN_FOCUS = 1 shl 14
        const val FLAG_DEVICE_DISPLAY_GROUP = 1 shl 15
        const val SHELL_PACKAGE = "com.android.shell"
    }
}
