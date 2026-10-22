package com.hyperxr.windows

import android.content.Context
import android.view.KeyEvent
import com.hyperxr.shell.IShellService
import com.hyperxr.shell.ShellBridge
import java.util.concurrent.Executors

/**
 * Any installed Android app running inside a HyperXR window (flat or curved). The display is created
 * by the Shizuku shell service on our texture's Surface; input is injected with that display id.
 * Binder calls run on one ordered background thread so touch streams keep their order and never
 * block the render thread.
 */
class AndroidAppWindow(
    private val context: Context,
    val packageName: String,
    val component: String,
    title: String,
    widthPx: Int = 1280,
    heightPx: Int = 800,
) : SpatialWindow(title, VirtualScreen(widthPx, heightPx), 1.6f) {
    private val io = Executors.newSingleThreadExecutor()
    @Volatile private var shell: IShellService? = null
    @Volatile private var displayId = -1

    init {
        status = "Abrindo…"
        screen.onSurface = { surface ->
            ShellBridge.withService(context) { s ->
                if (s == null) { status = "Shizuku indisponível: abra o Shizuku e conceda permissão ao HyperXR"; return@withService }
                io.execute {
                    try {
                        val id = s.createDisplay(surface, screen.widthPx, screen.heightPx, DPI, "hyperxr-$packageName")
                        if (id < 0) { status = "O shell não conseguiu criar o display virtual"; return@execute }
                        shell = s; displayId = id
                        val err = s.startApp(component, id)
                        status = if (err.isEmpty()) null else "Falha ao abrir: $err"
                    } catch (t: Throwable) {
                        status = "Erro do Shizuku: ${t.message}"
                    }
                }
            }
        }
    }

    override fun injectTouch(action: Int, x: Float, y: Float, downTimeMs: Long, eventTimeMs: Long) {
        val s = shell ?: return
        val id = displayId
        io.execute { runCatching { s.injectMotion(id, action, x, y, downTimeMs, eventTimeMs) } }
    }

    override fun typeText(text: String) {
        val s = shell ?: return
        io.execute { runCatching { s.injectText(displayId, text) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { status = it } }
    }

    override fun sendKey(keyCode: Int) {
        val s = shell ?: return
        io.execute { runCatching { s.injectKey(displayId, keyCode) } }
    }

    override fun back() = sendKey(KeyEvent.KEYCODE_BACK)

    override fun openUrl(url: String) {
        val s = shell ?: return
        io.execute { runCatching { s.openUrl(displayId, url) }.getOrNull()?.takeIf { it.isNotBlank() }?.let { status = it } }
    }

    override fun close() {
        visible = false
        val s = shell
        val id = displayId
        io.execute {
            if (s != null && id >= 0) runCatching { s.releaseDisplay(id) }
        }
        io.shutdown()
    }

    companion object { const val DPI = 200 }
}
