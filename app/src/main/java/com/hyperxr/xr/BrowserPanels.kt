package com.hyperxr.xr

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
class BrowserBar(val w: BrowserWindow, private val host: BrowserUiHost) : XrPanel(1.4f, 0.25f, title = "") {
    val editor = UrlEditor({ typed -> w.openUrl(UrlInput.resolve(typed)); host.closeKeyboard() }, { dirty = true })
    private var lastStatus: String? = null
    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)

    init { closable = false }

    override fun update(dt: Float) {
        if (w.changed || w.status != lastStatus) { w.changed = false; lastStatus = w.status; dirty = true }
    }

    override fun draw(c: PanelCanvas) {
        val t = w.active
        val gap = 10f
        var x = c.pad
        var y = 24f
        fun b(id: String, label: String, width: Float, accent: Boolean = false, enabled: Boolean = true, act: () -> Unit) {
            c.button(id, label, x, y, width, 68f, accent = accent, enabled = enabled) { act() }; x += width + gap
        }
        b("back", "Voltar", 130f, enabled = t?.canBack == true || w.fullscreen) { w.goBack() }
        b("fwd", "Avançar", 140f, enabled = t?.canForward == true) { w.goForward() }
        val loading = (t?.progress ?: 100) < 100
        b("reload", if (loading) "Parar" else "Recarregar", 170f) { if (loading) w.stop() else w.reload() }
        // address bar
        val right = c.w - c.pad - 2 * (170f + gap)
        val urlW = right - x - gap
        val shown = when {
            editor.active -> editor.text + "|"
            t == null -> ""
            t.title.isNotBlank() -> (if (t.secure) "" else "inseguro · ") + UrlInput.display(t.url) + " — " + t.title
            else -> t.url
        }
        c.button("url", shown.ifEmpty { "Pesquisar ou digitar endereço" }, x, y, urlW, 68f, accent = editor.active) {
            if (editor.active) editor.cancel() else { editor.begin(t?.url ?: ""); host.openKeyboard(editor) }
        }
        if (loading) {
            bar.color = XrTheme.accent2
            c.canvas.drawRoundRect(RectF(x + 12f, y + 60f, x + 12f + (urlW - 24f) * (t?.progress ?: 0) / 100f, y + 66f), 3f, 3f, bar)
        }
        x += urlW + gap
        val starred = t != null && w.store.isBookmarked(t.url)
        b("star", if (starred) "Salvo" else "Favoritar", 170f, accent = starred) { t?.let { w.store.toggleBookmark(it.title, it.url) }; dirty = true }
        b("favs", "Favoritos", 170f) { host.openBookmarks(w) }

        // second row: tabs, then window actions
        x = c.pad; y += 68f + 14f
        val tabs = w.tabs.toList()
        val tabW = 175f
        tabs.take(4).forEachIndexed { i, tab ->
            val label = tab.title.ifBlank { UrlInput.display(tab.url) }.ifBlank { "Nova aba" }
            b("tab$i", label, tabW, accent = tab === t) { w.select(tab) }
        }
        if (tabs.size > 4) { c.label("+${tabs.size - 4}", x, y + 18f, 28f, XrTheme.textDim); x += 60f }
        b("newtab", "Nova aba", 150f) { w.openInNewTab(BrowserWindow.HOME) }
        if (tabs.size > 1) b("closetab", "Fechar aba", 160f) { t?.let { w.close(it) } }
        // right-aligned window actions
        val actions = listOf("desk", "curve", "smaller", "bigger", "close")
        val aw = 112f
        x = c.w - c.pad - actions.size * (aw + gap) + gap
        b("desk", if (t?.desktop == true) "Celular" else "Desktop", aw) { w.setDesktop(t?.desktop != true) }
        b("curve", if (w.curved) "Plano" else "Curvar", aw) { w.curved = !w.curved; dirty = true }
        b("smaller", "Menor", aw) { host.resize(w, 1f / 1.15f) }
        b("bigger", "Maior", aw) { host.resize(w, 1.15f) }
        b("close", "Fechar", aw, accent = true) { host.closeWindow(w) }
        w.status?.let { c.label(it, c.pad, y + 74f, 24f, XrTheme.accent2, maxWidth = c.w - 2 * c.pad) }
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
