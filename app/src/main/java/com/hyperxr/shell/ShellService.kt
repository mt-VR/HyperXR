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

    override fun createDisplay(surface: Surface, width: Int, height: Int, dpi: Int, name: String): Int {
        val ctx = context ?: return -1
        val dm = ctx.getSystemService(DisplayManager::class.java) ?: return -1
        val base = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or FLAG_SUPPORTS_TOUCH or FLAG_ROTATES_WITH_CONTENT or
            FLAG_DESTROY_CONTENT_ON_REMOVAL
        // Android 13+: a trusted display in its own group behaves like a real screen (focus, IME, unlocked).
        val attempts = if (Build.VERSION.SDK_INT >= 33) {
            listOf(base or FLAG_TRUSTED or FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED, base)
        } else listOf(base)
        for (flags in attempts) {
            try {
                val vd = dm.createVirtualDisplay(name, width, height, dpi, surface, flags) ?: continue
                displays[vd.display.displayId] = vd
                return vd.display.displayId
            } catch (t: Throwable) {
                // try the next, less privileged flag set
            }
        }
        return -1
    }

    override fun startApp(component: String, displayId: Int): String {
        if (!component.matches(Regex("^[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+$"))) return "componente inválido"
        return run(listOf("am", "start", "--display", displayId.toString(), "--activity-multiple-task", "-n", component))
            .let { out -> if (out.contains("Error", ignoreCase = true) || out.contains("Exception")) out.trim() else "" }
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
    }
}
