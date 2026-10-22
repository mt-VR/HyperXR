package com.hyperxr.xr

import com.hyperxr.launcher.AppCategory
import com.hyperxr.launcher.AppEntry
import com.hyperxr.launcher.AppKind
import com.hyperxr.settings.LauncherMode
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Battery text for status rows ("71%", "71% carregando"). */
internal fun batteryText(): String {
    val i = com.hyperxr.HyperXRApp.instance.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) ?: return ""
    val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
    val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
    val plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
    if (level < 0) return ""
    return "${level * 100 / scale.coerceAtLeast(1)}%" + if (plugged) " carregando" else ""
}

/**
 * The app library (the home of New mode, a window in Dock mode), laid out like the Quest library:
 * status row, title, background tabs (camera, dimmed camera, environments), search, categories on
 * the left and a paged grid of app icons. Long-press an app to pin it to the taskbar.
 */
class LibraryHomePanel(private val a: LauncherActions, asWindow: Boolean) : XrPanel(1.5f, 1.0f, title = "") {
    private enum class Section(val label: String) { ALL("Todos"), APPS("Apps"), GAMES("Jogos e XR"), WEB("Web"), SYSTEM("Sistema") }

    private var section = Section.ALL
    private var page = 0
    private var minute = -1L
    private var battery = ""
    private var note: String? = null
    private val search = UrlEditor({ a.closeKeyboard() }, { page = 0; dirty = true })

    init { closable = asWindow; if (!asWindow) priority = 0 }

    override fun update(dt: Float) {
        val m = System.currentTimeMillis() / 60_000
        if (m != minute) { minute = m; battery = batteryText(); dirty = true }
    }

    private fun matches(app: AppEntry): Boolean {
        val q = search.text.trim()
        if (q.isNotEmpty() && !app.title.contains(q, ignoreCase = true)) return false
        return when (section) {
            Section.ALL -> true
            Section.APPS -> app.kind == AppKind.ANDROID_PACKAGE && app.category == AppCategory.ANDROID || app.kind == AppKind.SYSTEM_PANEL && app.category != AppCategory.SYSTEM
            Section.GAMES -> app.category == AppCategory.HYPERXR_GAME || app.category == AppCategory.OPENXR || app.category == AppCategory.CARDBOARD
            Section.WEB -> app.kind == AppKind.WEB || app.category == AppCategory.WEB
            Section.SYSTEM -> app.category == AppCategory.SYSTEM || app.category == AppCategory.ENVIRONMENT
        }
    }

    override fun draw(c: PanelCanvas) {
        // status row
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val status = listOf(battery, time, a.trackingDescription).filter { it.isNotEmpty() }.joinToString("    ")
        c.label(status, c.w / 2 - 260f, 22f, 24f, XrTheme.textDim, maxWidth = 520f)
        c.label("Biblioteca", c.w / 2 - 110f, 60f, 44f, XrTheme.text, bold = true, maxWidth = 400f)
        // background tabs, as one pill group
        val options = a.backgroundOptions()
        val tabW = 168f
        val groupW = options.size * tabW + 12f
        var tx = c.w / 2 - groupW / 2
        fill.color = XrTheme.buttonDisabled
        c.canvas.drawRoundRect(android.graphics.RectF(tx, 128f, tx + groupW, 192f), 32f, 32f, fill)
        tx += 6f
        for ((id, label) in options) {
            c.button("bg:$id", label, tx, 134f, tabW, 52f, accent = a.backgroundChoice() == id) { a.setBackgroundChoice(id); dirty = true }
            tx += tabW
        }
        // search
        val sw = 460f
        val active = search.active
        c.button("search", if (active) search.text + "|" else if (search.text.isNotEmpty()) search.text else "Pesquisar", c.w / 2 - sw / 2, 208f, sw, 58f, accent = active) {
            if (!search.active) { search.begin(search.text); a.openKeyboard(search) }
        }
        if (search.text.isNotEmpty() && !active) c.button("clear", "Limpar", c.w / 2 + sw / 2 + 10f, 208f, 120f, 58f) { search.begin(""); search.cancel(); page = 0 }
        // categories
        var sy = 296f
        for (s in Section.entries) {
            c.button("sec:${s.name}", s.label, c.pad, sy, 230f, 60f, accent = section == s) { section = s; page = 0 }
            sy += 68f
        }
        c.button("store", "Ambientes", c.pad, sy + 20f, 230f, 60f) { a.openTarget("environments") }
        c.button("files", "Arquivos", c.pad, sy + 88f, 230f, 60f) { a.openTarget("files") }
        // grid
        val apps = a.allApps().filter(::matches)
        val cols = 5; val rows = 3
        val gx = c.pad + 300f
        val gw = c.w - gx - 90f
        val gy = 290f
        val cellW = gw / cols
        val cellH = (c.h - gy - 30f) / rows
        val perPage = cols * rows
        val pages = maxOf(1, (apps.size + perPage - 1) / perPage)
        page = page.coerceIn(0, pages - 1)
        if (apps.isEmpty()) c.label("Nada encontrado", gx + 20f, gy + 20f, 30f, XrTheme.textDim)
        apps.drop(page * perPage).take(perPage).forEachIndexed { i, app ->
            val x = gx + (i % cols) * cellW
            val y = gy + (i / cols) * cellH
            c.appCell("app:${app.id}", a.appIcon(app, 192), app.title, x + 6f, y + 4f, cellW - 12f, cellH - 8f, onLong = {
                a.togglePin(app); note = if (a.isPinned(app)) "${app.title} fixado na barra" else "${app.title} removido da barra"; dirty = true
            }) { a.launch(app) }
        }
        // paging arrows at the sides of the grid
        if (pages > 1) {
            c.button("prev", "‹", gx - 70f, gy + cellH * 1.5f - 40f, 56f, 80f, enabled = page > 0) { page-- }
            c.button("next", "›", c.w - 76f, gy + cellH * 1.5f - 40f, 56f, 80f, enabled = page < pages - 1) { page++ }
            c.label("${page + 1}/$pages", c.w - 82f, gy + cellH * 1.5f + 52f, 22f, XrTheme.textDim)
        }
        note?.let { c.label(it, c.pad, c.h - 60f, 22f, XrTheme.accent2, maxWidth = 260f) }
    }

    private companion object { val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
}

/**
 * Taskbar under everything, like Quest's universal bar: clock and battery, pinned apps as icons,
 * the library, open windows, quick settings and the Dock/New switch.
 */
class TaskbarPanel(private val a: LauncherActions) : XrPanel(1.7f, 0.13f, title = "") {
    init { closable = false; priority = 0 }
    private var minute = -1L
    private var key = ""
    private var battery = ""

    override fun update(dt: Float) {
        val m = System.currentTimeMillis() / 60_000
        val k = "${a.windowCount()}/${a.windowsMinimized()}/${a.mode()}/${a.pinnedApps().joinToString { it.id }}"
        if (m != minute || k != key) { minute = m; key = k; battery = batteryText(); dirty = true }
    }

    override fun draw(c: PanelCanvas) {
        val h = c.h
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        c.label(time, c.pad, 16f, 36f, XrTheme.text, bold = true)
        if (battery.isNotEmpty()) c.label(battery, c.pad, 62f, 22f, XrTheme.textDim)
        // pinned apps in the middle, as many as fit between the clock and the right-hand buttons
        val size = h - 20f
        val gap = 6f
        val libW = 170f
        val leftEdge = c.pad + 150f
        val rightEdge = c.w - c.pad - 440f
        val fit = ((rightEdge - leftEdge - libW - gap) / (size + gap)).toInt().coerceAtLeast(0)
        val pinned = a.pinnedApps().take(fit)
        val total = libW + gap + pinned.size * (size + gap)
        var x = (leftEdge + rightEdge) / 2 - total / 2
        c.button("lib", "Biblioteca", x, 14f, libW, size - 8f, accent = a.libraryOpen()) { a.toggleLibrary() }
        x += libW + gap
        for (app in pinned) {
            c.iconButton("pin:${app.id}", a.appIcon(app, 128), x, 10f, size, active = a.isRunning(app)) { a.launch(app) }
            x += size + gap
        }
        // right side
        var rx = c.w - c.pad
        fun right(id: String, label: String, w: Float, accent: Boolean = false, act: () -> Unit) {
            rx -= w
            c.button(id, label, rx, 14f, w, size - 8f, accent = accent) { act(); dirty = true }
            rx -= gap
        }
        right("qs", "Ajustes", 130f) { a.openTarget("settings") }
        right("mode", if (a.mode() == LauncherMode.DOCK) "Dock" else "New", 100f) {
            a.setMode(if (a.mode() == LauncherMode.DOCK) LauncherMode.NEW else LauncherMode.DOCK)
        }
        val n = a.windowCount()
        if (n > 0) right("win", if (a.windowsMinimized()) "Janelas ($n)" else "Ocultar", 160f, accent = a.windowsMinimized()) { a.toggleWindows() }
    }
}

