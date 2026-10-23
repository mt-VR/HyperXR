package com.hyperxr.xr

import com.hyperxr.R

import android.graphics.Paint
import android.graphics.RectF
import android.view.KeyEvent
import com.hyperxr.browser.BrowserPrompt
import com.hyperxr.browser.BrowserStore
import com.hyperxr.browser.BrowserWindow
import com.hyperxr.browser.UrlInput
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import com.hyperxr.windows.KeyTarget

/** What browser panels need from the launcher. */
interface BrowserUiHost {
    fun openKeyboard(target: KeyTarget)
    fun closeKeyboard()
    fun openBookmarks(w: BrowserWindow)
    fun closeWindow(w: com.hyperxr.windows.SpatialWindow)
    fun resize(w: com.hyperxr.windows.SpatialWindow, factor: Float)
}

/** Text being typed into the address bar (the XR keyboard types here instead of into the page). */
class UrlEditor(private val onSubmit: (String) -> Unit, private val onChange: () -> Unit) : KeyTarget {
    var text = ""; private set
    var active = false; private set

    fun begin(initial: String) { text = initial; active = true; onChange() }
    fun cancel() { active = false; onChange() }

    override fun typeText(text: String) { if (active) { this.text += text; onChange() } }

    override fun sendKey(keyCode: Int) {
        if (!active) return
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> if (text.isNotEmpty()) text = text.dropLast(1)
            KeyEvent.KEYCODE_ENTER -> { active = false; onSubmit(text) }
        }
        onChange()
    }
}

/**
 * Browser chrome under the page: navigation, address bar with load progress, bookmarks, tabs,
 * desktop/mobile mode, curvature, size and close. Dragging empty space moves the window.
 */
/**
 * Wolvic's own icons (MPL-2.0, see native/WOLVIC_ICONS.md), rasterised once per size for the
 * Canvas-drawn panels.
 */
object WolvicIcons {
    private val cache = HashMap<Long, android.graphics.Bitmap>()

    fun get(resId: Int, sizePx: Int = 64): android.graphics.Bitmap = cache.getOrPut(resId.toLong() shl 16 or sizePx.toLong()) {
        val ctx = com.hyperxr.HyperXRApp.instance
        val d = androidx.core.content.ContextCompat.getDrawable(ctx, resId)!!
        val b = android.graphics.Bitmap.createBitmap(sizePx, sizePx, android.graphics.Bitmap.Config.ARGB_8888)
        d.setBounds(0, 0, sizePx, sizePx)
        d.draw(android.graphics.Canvas(b))
        b
    }
}

/**
 * The browser's bar under the window, laid out like Wolvic's: round icon buttons (back, forward,
 * reload/stop, home), the address as a pill, bookmark buttons; then the tabs as chips with a
 * new-tab button, and the window actions on the right.
 */
class BrowserBar(val w: BrowserWindow, private val host: BrowserUiHost) : XrPanel(1.4f, 0.25f, title = "") {
    val editor = UrlEditor({ typed -> w.openUrl(UrlInput.resolve(typed)); host.closeKeyboard() }, { dirty = true })
    private var lastStatus: String? = null
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)

    init { closable = false }

    override fun update(dt: Float) {
        if (w.changed || w.status != lastStatus) { w.changed = false; lastStatus = w.status; dirty = true }
    }

    private fun icon(id: Int) = WolvicIcons.get(id)

    override fun draw(c: PanelCanvas) {
        val t = w.active
        val gap = 12f
        val s1 = 76f
        var x = c.pad
        var y = 18f
        fun round(id: String, res: Int, enabled: Boolean = true, accent: Boolean = false, size: Float = s1, act: () -> Unit) {
            c.roundIconButton(id, icon(res), x, y, size, enabled, accent) { act() }; x += size + gap
        }
        round("back", R.drawable.wolvic_ic_back, enabled = t?.canBack == true || w.fullscreen) { w.goBack() }
        round("fwd", R.drawable.wolvic_ic_forward, enabled = t?.canForward == true) { w.goForward() }
        val loading = (t?.progress ?: 100) < 100
        round("reload", if (loading) R.drawable.wolvic_ic_clear else R.drawable.wolvic_ic_reload) { if (loading) w.stop() else w.reload() }
        round("home", R.drawable.wolvic_ic_home) { w.openUrl(BrowserWindow.HOME) }
        // address pill
        val right = c.w - c.pad - 2 * (s1 + gap)
        val urlW = right - x - gap
        val shown = when {
            editor.active -> editor.text + "|"
            t == null -> ""
            UrlInput.display(t.url).isEmpty() -> ""
            t.title.isNotBlank() -> UrlInput.display(t.url) + "  ·  " + t.title
            else -> t.url
        }
        val insecure = t != null && !t.secure && t.url.startsWith("http:")
        c.button("url", shown.ifEmpty { "Pesquisar ou digitar endereço" }, x, y, urlW, s1, accent = editor.active,
            icon = icon(if (insecure) R.drawable.wolvic_ic_security_state_insecure else R.drawable.wolvic_ic_search)) {
            if (editor.active) editor.cancel() else { editor.begin(if (UrlInput.display(t?.url ?: "").isEmpty()) "" else t?.url ?: ""); host.openKeyboard(editor) }
        }
        if (loading) {
            bar.color = XrTheme.accent2
            c.canvas.drawRoundRect(RectF(x + 30f, y + s1 - 8f, x + 30f + (urlW - 60f) * (t?.progress ?: 0) / 100f, y + s1 - 3f), 3f, 3f, bar)
        }
        x += urlW + gap
        val starred = t != null && w.store.isBookmarked(t.url)
        round("star", if (starred) R.drawable.wolvic_ic_bookmarked else R.drawable.wolvic_ic_bookmark, accent = starred) {
            t?.let { w.store.toggleBookmark(it.title, it.url) }; dirty = true
        }
        round("favs", R.drawable.wolvic_ic_bookmarks) { host.openBookmarks(w) }

        // second row: tab chips + new tab, then window actions on the right
        x = c.pad; y += s1 + 16f
        val s2 = 62f
        val tabs = w.tabs.toList()
        val actionsW = 5 * (s2 + gap) + 150f
        val tabArea = c.w - 2 * c.pad - actionsW - (s2 + gap) - 20f
        val shownTabs = tabs.take(4)
        val tabW = if (shownTabs.isEmpty()) 0f else minOf(260f, (tabArea - (if (tabs.size > 4) 60f else 0f)) / shownTabs.size - gap)
        shownTabs.forEachIndexed { i, tab ->
            val label = tab.title.ifBlank { UrlInput.display(tab.url) }.ifBlank { "Nova aba" }
            c.button("tab$i", label, x, y, tabW, s2, accent = tab === t, onLong = { w.close(tab) }) { w.select(tab) }
            x += tabW + gap
        }
        if (tabs.size > 4) { c.label("+${tabs.size - 4}", x, y + 16f, 28f, XrTheme.textDim); x += 60f }
        round("newtab", R.drawable.wolvic_ic_newtab, size = s2) { w.openInNewTab(BrowserWindow.HOME) }
        x = c.w - c.pad - actionsW + gap
        round("desk", if (t?.desktop == true) R.drawable.wolvic_ic_ua_desktop else R.drawable.wolvic_ic_ua_default, accent = t?.desktop == true, size = s2) {
            w.setDesktop(t?.desktop != true)
        }
        c.button("curve", if (w.curved) "Plano" else "Curvar", x, y, 150f, s2) { w.curved = !w.curved; dirty = true }; x += 150f + gap
        round("smaller", R.drawable.wolvic_ic_minus, size = s2) { host.resize(w, 1f / 1.15f) }
        round("bigger", R.drawable.wolvic_ic_plus, size = s2) { host.resize(w, 1.15f) }
        round("closetab", R.drawable.wolvic_ic_clear, enabled = tabs.size > 1, size = s2) { t?.let { w.close(it) } }
        round("close", R.drawable.wolvic_ic_window_exit, accent = true, size = s2) { host.closeWindow(w) }
        w.status?.let { c.label(it, c.pad, y + s2 + 4f, 22f, XrTheme.accent2, maxWidth = c.w - 2 * c.pad) }
    }
}

/** A page dialog (alert, confirm, <select>, prompt) as an XR panel; text answers use the keyboard. */
class PromptPanel(val prompt: BrowserPrompt, private val host: BrowserUiHost) : XrPanel(1.0f, 0.2f + 0.085f * minOf(prompt.options.size, 8) + 0.12f, title = prompt.title) {
    private var page = 0
    init { priority = 2 }
    private val text = object : KeyTarget {
        var value = prompt.defaultText
        override fun typeText(text: String) { value += text; dirty = true }
        override fun sendKey(keyCode: Int) {
            when (keyCode) {
                KeyEvent.KEYCODE_DEL -> value = value.dropLast(1)
                KeyEvent.KEYCODE_ENTER -> answer(0)
            }
            dirty = true
        }
    }

    init { onClose = { prompt.respond(-1); host.closeKeyboard() } }

    private fun answer(i: Int) {
        prompt.respond(i, text.value)
        visible = false
        host.closeKeyboard()
    }

    override fun draw(c: PanelCanvas) {
        if (prompt.message.isNotBlank()) c.paragraph(prompt.message.take(400), 28f, XrTheme.text)
        if (prompt.kind == BrowserPrompt.Kind.TEXT) {
            c.row("field", text.value + "|", accent = true) { host.openKeyboard(text) }
        }
        if (prompt.kind == BrowserPrompt.Kind.CHOICE) {
            val per = 8
            val pages = maxOf(1, (prompt.options.size + per - 1) / per)
            prompt.options.withIndex().drop(page * per).take(per).forEach { (i, label) ->
                c.row("opt$i", label.ifBlank { "(vazio)" }, accent = i == prompt.selected) { answer(i) }
            }
            if (pages > 1) {
                val bw = (c.w - 2 * c.pad - 20f) / 2
                c.button("prev", "Anteriores", c.pad, c.y, bw, 70f, enabled = page > 0) { page--; dirty = true }
                c.button("next", "Próximas", c.pad + bw + 20f, c.y, bw, 70f, enabled = page < pages - 1) { page++; dirty = true }
            }
        } else {
            val n = prompt.options.size
            val bw = (c.w - 2 * c.pad - (n - 1) * 20f) / n
            prompt.options.forEachIndexed { i, label ->
                c.button("b$i", label, c.pad + i * (bw + 20f), c.h - 110f, bw, 80f, accent = i == 0) { answer(i) }
            }
        }
    }
}

/** Bookmarks, quick sites and recent history; picking one opens it in the browser window. */
class BookmarksPanel(private val w: BrowserWindow, private val store: BrowserStore) : XrPanel(1.2f, 1.0f, title = "Favoritos e histórico") {
    private var tab = 0
    private var page = 0
    private val quick = listOf(
        BrowserStore.Entry("YouTube", "https://m.youtube.com"), BrowserStore.Entry("Twitch", "https://m.twitch.tv"),
        BrowserStore.Entry("TikTok", "https://www.tiktok.com"), BrowserStore.Entry("WhatsApp Web", "https://web.whatsapp.com"),
        BrowserStore.Entry("Wikipédia", "https://pt.wikipedia.org"), BrowserStore.Entry("Google", "https://www.google.com"),
        BrowserStore.Entry("Reddit", "https://www.reddit.com"), BrowserStore.Entry("GitHub", "https://github.com"),
    )

    override fun draw(c: PanelCanvas) {
        val names = listOf("Favoritos", "Sites rápidos", "Histórico")
        val bw = (c.w - 2 * c.pad - 2 * 14f) / 3
        names.forEachIndexed { i, n -> c.button("t$i", n, c.pad + i * (bw + 14f), c.y, bw, 70f, accent = tab == i) { tab = i; page = 0; dirty = true } }
        c.y += 90f
        val list = when (tab) { 0 -> synchronized(store) { store.bookmarks.toList() }; 1 -> quick; else -> synchronized(store) { store.history.toList() } }
        if (list.isEmpty()) {
            c.paragraph(if (tab == 0) "Nenhum favorito ainda. Use \"Favoritar\" na barra do navegador." else "Histórico vazio.")
            return
        }
        val per = 6
        val pages = maxOf(1, (list.size + per - 1) / per)
        page = page.coerceIn(0, pages - 1)
        list.drop(page * per).take(per).forEachIndexed { i, e ->
            c.row("e$i", e.title, detail = UrlInput.display(e.url)) { w.openUrl(e.url); visible = false }
        }
        val by = c.h - 100f
        c.button("prev", "Anterior", c.pad, by, 200f, 74f, enabled = page > 0) { page--; dirty = true }
        if (tab == 2) c.button("clear", "Limpar histórico", c.w / 2 - 160f, by, 320f, 74f) { store.clearHistory(); dirty = true }
        c.button("next", "Próxima", c.w - c.pad - 200f, by, 200f, 74f, enabled = page < pages - 1) { page++; dirty = true }
    }
}
