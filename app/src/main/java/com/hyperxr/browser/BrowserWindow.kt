package com.hyperxr.browser

import android.app.Activity
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.view.KeyCharacterMap
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.Surface
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.webkit.URLUtil
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.windows.SpatialWindow
import com.hyperxr.windows.VirtualScreen
import org.mozilla.geckoview.AllowOrDeny
import org.mozilla.geckoview.GeckoDisplay
import org.mozilla.geckoview.GeckoResult
import org.mozilla.geckoview.GeckoRuntime
import org.mozilla.geckoview.GeckoSession
import org.mozilla.geckoview.GeckoSession.PromptDelegate
import org.mozilla.geckoview.GeckoSessionSettings
import org.mozilla.geckoview.ScreenLength
import org.mozilla.geckoview.WebResponse
import java.util.concurrent.CopyOnWriteArrayList

/** A question from a page (alert, confirm, <select>, prompt) answered in XR. */
class BrowserPrompt(
    val kind: Kind,
    val title: String,
    val message: String,
    val options: List<String> = emptyList(),
    val selected: Int = -1,
    val defaultText: String = "",
    private val answer: (index: Int, text: String?) -> Unit,
) {
    enum class Kind { ALERT, CONFIRM, CHOICE, TEXT }
    @Volatile var done = false; private set
    /** index -1 = dismissed. */
    fun respond(index: Int, text: String? = null) { if (done) return; done = true; answer(index, text) }
}

/**
 * The XR browser: GeckoView sessions (one per tab) rendering directly into this window's texture,
 * the same architecture Wolvic uses. Input arrives as real touch events (a pinch is a finger),
 * text goes through Gecko's own InputConnection, and page dialogs/selects become XR prompts.
 * All Gecko calls run on the UI thread.
 */
class BrowserWindow(
    private val activity: Activity,
    private val host: ViewGroup,
    private val runtime: GeckoRuntime,
    startUrl: String,
    desktop: Boolean,
    val store: BrowserStore,
) : SpatialWindow("Navegador", VirtualScreen(GeckoEngine.WIDTH, GeckoEngine.HEIGHT), 1.25f) {

    inner class Tab(val session: GeckoSession, var desktop: Boolean) {
        @Volatile var url = ""
        @Volatile var title = ""
        @Volatile var canBack = false
        @Volatile var canForward = false
        @Volatile var progress = 100
        @Volatile var secure = false
    }

    val tabs = CopyOnWriteArrayList<Tab>()
    @Volatile var active: Tab? = null; private set
    @Volatile var fullscreen = false; private set
    /** Something shown in the browser bar changed (url, title, progress, tabs). */
    @Volatile var changed = true
    var onPrompt: ((BrowserPrompt) -> Unit)? = null
    var onFullscreen: ((Boolean) -> Unit)? = null

    private var display: GeckoDisplay? = null
    private var surface: Surface? = null
    private var inputConnection: InputConnection? = null
    /** Gecko's text input needs a View to talk to; this one is never visible. */
    private val inputView = View(activity).apply { isFocusable = true; isFocusableInTouchMode = true; alpha = 0f }

    init {
        status = "Carregando…"
        activity.runOnUiThread {
            host.addView(inputView, ViewGroup.LayoutParams(1, 1))
            newTab(startUrl, desktop)
        }
        screen.onSurface = { s -> activity.runOnUiThread { surface = s; attachDisplay() } }
    }

    // ---------------------------------------------------------------- tabs
    private fun settingsFor(desktop: Boolean) = GeckoSessionSettings.Builder()
        .userAgentMode(if (desktop) GeckoSessionSettings.USER_AGENT_MODE_DESKTOP else GeckoSessionSettings.USER_AGENT_MODE_MOBILE)
        .viewportMode(if (desktop) GeckoSessionSettings.VIEWPORT_MODE_DESKTOP else GeckoSessionSettings.VIEWPORT_MODE_MOBILE)
        .suspendMediaWhenInactive(true)
        .useTrackingProtection(true)
        .build()

    /** UI thread. */
    fun newTab(url: String, desktop: Boolean = active?.desktop ?: false): Tab {
        val tab = Tab(GeckoSession(settingsFor(desktop)), desktop)
        setup(tab)
        tab.session.open(runtime)
        tab.url = url
        tab.session.loadUri(url)
        tabs += tab
        switchTo(tab)
        return tab
    }

    /** UI thread. */
    fun switchTo(tab: Tab) {
        val old = active
        if (old === tab) return
        if (old != null) {
            display?.let { d -> d.surfaceDestroyed(); old.session.releaseDisplay(d) }
            display = null
            old.session.setFocused(false)
            old.session.setActive(false)
        }
        active = tab
        inputConnection = null
        tab.session.textInput.setView(inputView)
        tab.session.setActive(true)
        tab.session.setFocused(true)
        attachDisplay()
        changed = true
    }

    /** UI thread. */
    fun closeTab(tab: Tab) {
        val i = tabs.indexOf(tab)
        if (i < 0) return
        if (active === tab) {
            display?.let { d -> d.surfaceDestroyed(); tab.session.releaseDisplay(d) }
            display = null
            active = null
        }
        tabs.remove(tab)
        tab.session.close()
        if (tabs.isEmpty()) newTab(HOME) else if (active == null) switchTo(tabs[(i - 1).coerceAtLeast(0)])
        changed = true
    }

    fun setDesktop(on: Boolean) = activity.runOnUiThread {
        val t = active ?: return@runOnUiThread
        if (t.desktop == on) return@runOnUiThread
        // user agent and viewport are fixed per session: reopen the page in a new session
        val url = t.url
        val nt = newTab(url, on)
        tabs.remove(nt); tabs.add(tabs.indexOf(t).coerceAtLeast(0), nt)
        closeTab(t)
        switchTo(nt)
    }

    private fun attachDisplay() {
        val t = active ?: return
        val s = surface ?: return
        if (display != null) return
        display = t.session.acquireDisplay().also {
            it.surfaceChanged(GeckoDisplay.SurfaceInfo.Builder(s).size(screen.widthPx, screen.heightPx).build())
        }
    }

    // ---------------------------------------------------------------- delegates
    private fun setup(tab: Tab) {
        val s = tab.session
        s.navigationDelegate = object : GeckoSession.NavigationDelegate {
            override fun onLocationChange(session: GeckoSession, url: String?, perms: MutableList<GeckoSession.PermissionDelegate.ContentPermission>, hasUserGesture: Boolean) {
                tab.url = url ?: ""; changed = true
            }
            override fun onCanGoBack(session: GeckoSession, canGoBack: Boolean) { tab.canBack = canGoBack; changed = true }
            override fun onCanGoForward(session: GeckoSession, canGoForward: Boolean) { tab.canForward = canGoForward; changed = true }
            override fun onNewSession(session: GeckoSession, uri: String): GeckoResult<GeckoSession> {
                // target=_blank / window.open: a new tab; Gecko opens the session we return
                val nt = Tab(GeckoSession(settingsFor(tab.desktop)), tab.desktop)
                setup(nt)
                nt.url = uri
                tabs += nt
                inputView.post { switchTo(nt) }
                return GeckoResult.fromValue(nt.session)
            }
        }
        s.progressDelegate = object : GeckoSession.ProgressDelegate {
            override fun onPageStart(session: GeckoSession, url: String) {
                tab.progress = 5; changed = true
                if (tab === active) status = "Carregando ${UrlInput.display(url)}…"
            }
            override fun onProgressChange(session: GeckoSession, progress: Int) { tab.progress = progress; changed = true }
            override fun onPageStop(session: GeckoSession, success: Boolean) {
                tab.progress = 100; changed = true
                if (tab === active) status = null
                if (success) store.visit(tab.title, tab.url)
            }
            override fun onSecurityChange(session: GeckoSession, info: GeckoSession.ProgressDelegate.SecurityInformation) {
                tab.secure = info.isSecure; changed = true
            }
        }
        s.contentDelegate = object : GeckoSession.ContentDelegate {
            override fun onTitleChange(session: GeckoSession, title: String?) {
                tab.title = title ?: ""; changed = true
                store.updateTitle(tab.url, tab.title)
            }
            override fun onContextMenu(session: GeckoSession, screenX: Int, screenY: Int, element: GeckoSession.ContentDelegate.ContextElement) {
                // long pinch on a link: pick it up for drag-and-drop to another window
                val link = element.linkUri ?: return
                if (!link.startsWith("http")) return
                suppressUntilUp = true
                val now = android.os.SystemClock.uptimeMillis()
                val ev = fingerEvent(now, now, MotionEvent.ACTION_CANCEL, screenX.toFloat(), screenY.toFloat())
                session.panZoomController.onTouchEvent(ev)
                ev.recycle()
                onLinkDrag?.invoke(link, lastPointerId)
            }
            override fun onFullScreen(session: GeckoSession, fullScreen: Boolean) {
                fullscreen = fullScreen; changed = true
                onFullscreen?.invoke(fullScreen)
            }
            override fun onCloseRequest(session: GeckoSession) { closeTab(tab) }
            override fun onCrash(session: GeckoSession) = recover(tab)
            override fun onKill(session: GeckoSession) = recover(tab)
            override fun onExternalResponse(session: GeckoSession, response: WebResponse) = download(response)
        }
        s.textInput.setDelegate(object : GeckoSession.TextInputDelegate {
            override fun showSoftInput(session: GeckoSession) { inputConnection = null; onKeyboardRequest?.invoke() }
            override fun restartInput(session: GeckoSession, reason: Int) { inputConnection = null }
        })
        s.permissionDelegate = object : GeckoSession.PermissionDelegate {
            override fun onContentPermissionRequest(session: GeckoSession, perm: GeckoSession.PermissionDelegate.ContentPermission): GeckoResult<Int> {
                val allow = perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_AUDIBLE ||
                    perm.permission == GeckoSession.PermissionDelegate.PERMISSION_AUTOPLAY_INAUDIBLE ||
                    perm.permission == GeckoSession.PermissionDelegate.PERMISSION_MEDIA_KEY_SYSTEM_ACCESS ||
                    perm.permission == GeckoSession.PermissionDelegate.PERMISSION_PERSISTENT_STORAGE
                return GeckoResult.fromValue(if (allow) GeckoSession.PermissionDelegate.ContentPermission.VALUE_ALLOW else GeckoSession.PermissionDelegate.ContentPermission.VALUE_DENY)
            }
        }
        s.promptDelegate = prompts
    }

    private fun recover(tab: Tab) {
        status = "A página travou; reabrindo"
        Diagnostics.log("Gecko content process lost on ${tab.url}")
        val url = tab.url
        val wasActive = tab === active
        val nt = newTab(url.ifBlank { HOME }, tab.desktop)
        tabs.remove(nt); tabs.add(tabs.indexOf(tab).coerceAtLeast(0), nt)
        closeTab(tab)
        if (wasActive) switchTo(nt)
    }

    private val prompts = object : PromptDelegate {
        private fun <T> ask(build: ((Int, String?) -> Unit) -> BrowserPrompt?, result: GeckoResult<PromptDelegate.PromptResponse>, toResponse: (Int, String?) -> PromptDelegate.PromptResponse): GeckoResult<PromptDelegate.PromptResponse> {
            val p = build { i, t -> activity.runOnUiThread { result.complete(toResponse(i, t)) } }
            val cb = onPrompt
            if (p == null || cb == null) return GeckoResult.fromValue(toResponse(-1, null))
            cb(p)
            return result
        }

        override fun onAlertPrompt(session: GeckoSession, prompt: PromptDelegate.AlertPrompt): GeckoResult<PromptDelegate.PromptResponse> =
            ask<Unit>({ a -> BrowserPrompt(BrowserPrompt.Kind.ALERT, prompt.title ?: "Mensagem da página", prompt.message ?: "", listOf("OK"), answer = a) },
                GeckoResult()) { _, _ -> prompt.dismiss() }

        override fun onButtonPrompt(session: GeckoSession, prompt: PromptDelegate.ButtonPrompt): GeckoResult<PromptDelegate.PromptResponse> =
            ask<Unit>({ a -> BrowserPrompt(BrowserPrompt.Kind.CONFIRM, prompt.title ?: "Confirmar", prompt.message ?: "", listOf("OK", "Cancelar"), answer = a) },
                GeckoResult()) { i, _ ->
                when (i) {
                    0 -> prompt.confirm(PromptDelegate.ButtonPrompt.Type.POSITIVE)
                    1 -> prompt.confirm(PromptDelegate.ButtonPrompt.Type.NEGATIVE)
                    else -> prompt.dismiss()
                }
            }

        override fun onTextPrompt(session: GeckoSession, prompt: PromptDelegate.TextPrompt): GeckoResult<PromptDelegate.PromptResponse> =
            ask<Unit>({ a -> BrowserPrompt(BrowserPrompt.Kind.TEXT, prompt.title ?: "Entrada", prompt.message ?: "", listOf("OK", "Cancelar"), defaultText = prompt.defaultValue ?: "", answer = a) },
                GeckoResult()) { i, t -> if (i == 0) prompt.confirm(t ?: "") else prompt.dismiss() }

        override fun onChoicePrompt(session: GeckoSession, prompt: PromptDelegate.ChoicePrompt): GeckoResult<PromptDelegate.PromptResponse> {
            // flatten option groups; separators and disabled entries are not selectable
            val flat = ArrayList<PromptDelegate.ChoicePrompt.Choice>()
            fun add(cs: Array<PromptDelegate.ChoicePrompt.Choice>?) { cs?.forEach { c -> if (c.items != null) add(c.items) else if (!c.separator && !c.disabled) flat += c } }
            add(prompt.choices)
            val sel = flat.indexOfFirst { it.selected }
            return ask<Unit>({ a -> BrowserPrompt(BrowserPrompt.Kind.CHOICE, prompt.title ?: "Escolha", prompt.message ?: "", flat.map { it.label }, sel, answer = a) },
                GeckoResult()) { i, _ -> if (i in flat.indices) prompt.confirm(flat[i]) else prompt.dismiss() }
        }

        override fun onPopupPrompt(session: GeckoSession, prompt: PromptDelegate.PopupPrompt): GeckoResult<PromptDelegate.PromptResponse> =
            // logins and "open in new window" links need popups; they open as tabs
            GeckoResult.fromValue(prompt.confirm(AllowOrDeny.ALLOW))
    }

    private fun download(response: WebResponse) {
        val url = response.uri
        try { response.body?.close() } catch (_: Throwable) {}
        if (!url.startsWith("http")) { status = "Download não suportado: $url"; return }
        try {
            val name = URLUtil.guessFileName(url, response.headers["Content-Disposition"], response.headers["Content-Type"])
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            (activity.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            status = "Baixando $name para Downloads"
        } catch (t: Throwable) {
            status = "Falha no download: ${t.message}"
        }
    }

    // ---------------------------------------------------------------- SpatialWindow
    override fun injectTouch(action: Int, x: Float, y: Float, downTimeMs: Long, eventTimeMs: Long) {
        activity.runOnUiThread {
            val s = active?.session ?: return@runOnUiThread
            val ev = fingerEvent(downTimeMs, eventTimeMs, action, x, y)
            s.panZoomController.onTouchEvent(ev)
            ev.recycle()
        }
    }

    private fun connection(): InputConnection? {
        inputConnection?.let { return it }
        val s = active?.session ?: return null
        val info = EditorInfo()
        return s.textInput.onCreateInputConnection(info).also { inputConnection = it }
    }

    override fun typeText(text: String) {
        activity.runOnUiThread {
            val ic = connection()
            if (ic == null) { status = "Toque num campo de texto da página primeiro"; return@runOnUiThread }
            ic.commitText(text, 1)
        }
    }

    override fun sendKey(keyCode: Int) {
        activity.runOnUiThread {
            val s = active?.session ?: return@runOnUiThread
            val now = android.os.SystemClock.uptimeMillis()
            s.textInput.onKeyDown(keyCode, KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
            s.textInput.onKeyUp(keyCode, KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0))
        }
    }

    override fun scroll(deltaPx: Float) {
        activity.runOnUiThread { active?.session?.panZoomController?.scrollBy(ScreenLength.zero(), ScreenLength.fromPixels(deltaPx.toDouble())) }
    }

    fun goBack() = activity.runOnUiThread {
        val t = active ?: return@runOnUiThread
        if (fullscreen) t.session.exitFullScreen() else if (t.canBack) t.session.goBack()
    }
    fun goForward() = activity.runOnUiThread { active?.session?.goForward() }
    fun stop() = activity.runOnUiThread { active?.session?.stop() }
    override fun back() = goBack()
    override fun openUrl(url: String) { activity.runOnUiThread { active?.let { it.url = url; it.session.loadUri(url) } ?: newTab(url) } }
    fun openInNewTab(url: String) = activity.runOnUiThread { newTab(url) }
    fun select(tab: Tab) = activity.runOnUiThread { switchTo(tab) }
    fun close(tab: Tab) = activity.runOnUiThread { closeTab(tab) }
    override val supportsReload: Boolean get() = true
    override fun reload() { activity.runOnUiThread { active?.session?.reload() } }

    override fun openExternally() {
        val url = active?.url ?: return
        activity.runOnUiThread { runCatching { activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
    }

    override fun close() {
        visible = false
        activity.runOnUiThread {
            val t = active
            if (t != null) display?.let { d -> d.surfaceDestroyed(); t.session.releaseDisplay(d) }
            display = null
            tabs.forEach { it.session.close() }
            tabs.clear()
            active = null
            host.removeView(inputView)
        }
    }

    companion object { const val HOME = "https://duckduckgo.com" }
}
