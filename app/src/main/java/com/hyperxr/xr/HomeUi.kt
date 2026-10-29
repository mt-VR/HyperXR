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
 * The app library (the home of New mode, a window in Dock mode), laid out like a headset's App
 * Library: title, search, filter chips and the count, then a grid of wide tiles. Games show their
 * cover art; apps a coloured tile with their icon and name. Long-press a tile to pin it to the dock.
 */
class LibraryHomePanel(private val a: LauncherActions, asWindow: Boolean) : XrPanel(1.5f, 1.0f, title = "") {
    private enum class Section(val label: String) { ALL("Todos"), RECENT("Recentes"), GAMES("Jogos"), APPS("Apps"), WEB("Web"), SYSTEM("Sistema") }

    private var section = Section.ALL
    private var page = 0
    private var minute = -1L
    private var battery = ""
    private var note: String? = null
    private val search = UrlEditor({ a.closeKeyboard() }, { page = 0; dirty = true })
    private val tile = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)

    init { closable = asWindow; if (!asWindow) priority = 0 }

    override fun update(dt: Float) {
        val m = System.currentTimeMillis() / 60_000
        if (m != minute) { minute = m; battery = batteryText(); dirty = true }
    }

    private fun matches(app: AppEntry): Boolean {
        val q = search.text.trim()
        if (q.isNotEmpty() && !app.title.contains(q, ignoreCase = true)) return false
        return when (section) {
            Section.ALL, Section.RECENT -> true
            Section.APPS -> app.kind == AppKind.ANDROID_PACKAGE && app.category == AppCategory.ANDROID || app.kind == AppKind.SYSTEM_PANEL && app.category != AppCategory.SYSTEM && app.category != AppCategory.HYPERXR_GAME
            Section.GAMES -> app.category == AppCategory.HYPERXR_GAME || app.category == AppCategory.OPENXR || app.category == AppCategory.CARDBOARD
            Section.WEB -> app.kind == AppKind.WEB || app.category == AppCategory.WEB
            Section.SYSTEM -> app.category == AppCategory.SYSTEM || app.category == AppCategory.ENVIRONMENT
        }
    }

    override fun draw(c: PanelCanvas) {
        val all = a.allApps()
        val apps = if (section == Section.RECENT) {
            val byId = all.associateBy { it.id }
            com.hyperxr.HyperXRApp.instance.settings.current.launcher.recents.mapNotNull { byId[it] }.filter(::matches)
        } else all.filter(::matches)
        // header: title, search, chips, count
        c.label("Biblioteca", c.pad, 30f, 40f, XrTheme.text, bold = true, maxWidth = 300f)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        c.label(listOf(time, battery).filter { it.isNotEmpty() }.joinToString("  ·  "), c.pad + 250f, 42f, 22f, XrTheme.textDim, maxWidth = 260f, maxLines = 1)
        val active = search.active
        val sw = 300f
        c.button("search", if (active) search.text + "|" else if (search.text.isNotEmpty()) search.text else "Pesquisar", c.w - c.pad - 520f - sw, 24f, sw, 60f,
            accent = active, icon = Glyphs.get("search")) { if (!search.active) { search.begin(search.text); a.openKeyboard(search) } else { search.cancel() } }
        c.button("filter", section.label, c.w - c.pad - 500f, 24f, 230f, 60f, icon = Glyphs.get("expand_more")) {
            section = Section.entries[(section.ordinal + 1) % Section.entries.size]; page = 0
        }
        c.button("count", "Instalados (${all.size})", c.w - c.pad - 255f, 24f, 255f, 60f) { section = Section.ALL; page = 0 }
        if (a.vrSwitchable()) c.button("vr", if (a.vrOn()) "Modo VR" else "Realidade mista", c.pad, c.h - 84f, 280f, 62f, accent = a.vrOn(), icon = a.icons().icon("vrmode", 96)) { a.setVr(!a.vrOn()); dirty = true }
        c.button("loja", "Loja", c.pad + 296f, c.h - 84f, 180f, 62f, icon = a.icons().icon("shop", 96)) { a.openTarget("loja") }
        // tiles
        val cols = 4; val rows = 4
        val gx = c.pad; val gy = 110f
        val gw = c.w - 2 * c.pad - 70f
        val gh = c.h - gy - 100f
        val gap = 18f
        val cellW = (gw - gap * (cols - 1)) / cols
        val cellH = (gh - gap * (rows - 1)) / rows
        val perPage = cols * rows
        val pages = maxOf(1, (apps.size + perPage - 1) / perPage)
        page = page.coerceIn(0, pages - 1)
        if (apps.isEmpty()) c.label(if (section == Section.GAMES) "Nenhum jogo baixado. Abra a Loja para baixar." else "Nada encontrado", gx + 20f, gy + 20f, 30f, XrTheme.textDim)
        apps.drop(page * perPage).take(perPage).forEachIndexed { i, app ->
            val x = gx + (i % cols) * (cellW + gap)
            val y = gy + (i / cols) * (cellH + gap)
            drawTile(c, app, x, y, cellW, cellH)
        }
        // scroll bar and page arrows on the right, like the library's
        if (pages > 1) {
            c.button("prev", "▲", c.w - c.pad - 56f, gy, 56f, 80f, enabled = page > 0) { page-- }
            c.button("next", "▼", c.w - c.pad - 56f, gy + gh - 80f, 56f, 80f, enabled = page < pages - 1) { page++ }
            tile.color = XrTheme.buttonDisabled
            c.canvas.drawRoundRect(android.graphics.RectF(c.w - c.pad - 32f, gy + 96f, c.w - c.pad - 24f, gy + gh - 96f), 4f, 4f, tile)
            val span = gh - 192f
            tile.color = XrTheme.text
            val t0 = gy + 96f + span * page / pages
            c.canvas.drawRoundRect(android.graphics.RectF(c.w - c.pad - 32f, t0, c.w - c.pad - 24f, t0 + span / pages), 4f, 4f, tile)
        }
        note?.let { c.label(it, c.pad + 500f, c.h - 70f, 22f, XrTheme.accent2, maxWidth = 600f, maxLines = 1) }
    }

    /** A game: its cover. An app: its colours as a tile, the icon on the left, the name. */
    private fun drawTile(c: PanelCanvas, app: AppEntry, x: Float, y: Float, w: Float, h: Float) {
        val id = "app:${app.id}"
        val hover = isHovered(id); val press = isPressed(id)
        val grow = if (press) -4f else if (hover) 5f else 0f
        val r = android.graphics.RectF(x - grow, y - grow, x + w + grow, y + h + grow)
        val ctx = com.hyperxr.HyperXRApp.instance
        val game = app.category == AppCategory.HYPERXR_GAME
        c.canvas.save()
        c.canvas.clipPath(android.graphics.Path().apply { addRoundRect(r, 20f, 20f, android.graphics.Path.Direction.CW) })
        if (game) {
            c.canvas.drawBitmap(com.hyperxr.rendering.CoverArt.cover(ctx, app.iconKey, app.title), null, r, tile)
        } else {
            val icon = a.appIcon(app, 128)
            // the icon itself, blurred and stretched, makes the tile's colour
            c.canvas.drawBitmap(icon, android.graphics.Rect(8, 8, 120, 120), r, tile)
            tile.color = android.graphics.Color.argb(40, 0, 0, 0); c.canvas.drawRect(r, tile)
            val s = h * 0.5f
            c.canvas.drawBitmap(icon, null, android.graphics.RectF(r.left + 18f, r.centerY() - s / 2, r.left + 18f + s, r.centerY() + s / 2), tile)
            c.label(app.title, r.left + 30f + s, r.centerY() - 17f, 28f, android.graphics.Color.WHITE, bold = true, maxLines = 1, maxWidth = w - s - 44f)
        }
        c.canvas.restore()
        if (hover) {
            tile.style = android.graphics.Paint.Style.STROKE; tile.strokeWidth = 4f; tile.color = android.graphics.Color.WHITE
            c.canvas.drawRoundRect(r, 20f, 20f, tile); tile.style = android.graphics.Paint.Style.FILL
        }
        addRegion(Region(android.graphics.RectF(x, y, x + w, y + h), id, { a.launch(app) }, {
            a.togglePin(app); note = if (a.isPinned(app)) "${app.title} fixado na dock" else "${app.title} removido da dock"; dirty = true
        }))
    }
}

/**
 * The dock: a dark pill like a headset's universal bar. On the left the clock, battery and your
 * avatar; then the library, the store, people, the camera and missions as coloured icons; your
 * pinned apps; and on the right the VR switch, voice and settings. A dot marks what is open.
 */
class TaskbarPanel(private val a: LauncherActions) : XrPanel(1.3f, 0.13f, title = "") {
    init { closable = false; priority = 0 }
    private var minute = -1L
    private var key = ""
    private var battery = ""
    private val pill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    override fun update(dt: Float) {
        val m = System.currentTimeMillis() / 60_000
        val k = "${a.windowCount()}/${a.windowsMinimized()}/${a.mode()}/${a.voiceStatus()}/${a.vrOn()}/${a.pinnedApps().joinToString { it.id }}/${com.hyperxr.social.Social.unreadTotal}/${a.libraryOpen()}"
        if (m != minute || k != key) { minute = m; key = k; battery = batteryText(); dirty = true }
    }

    override fun paintBackground(c: PanelCanvas) {
        // a rounded pill of dark glass with a soft rim
        val rr = c.h / 2
        pill.shader = android.graphics.LinearGradient(0f, 0f, 0f, c.h, android.graphics.Color.argb(245, 40, 42, 48), android.graphics.Color.argb(245, 26, 27, 31), android.graphics.Shader.TileMode.CLAMP)
        c.canvas.drawRoundRect(android.graphics.RectF(0f, 0f, c.w, c.h), rr, rr, pill)
        pill.shader = null
        pill.style = android.graphics.Paint.Style.STROKE; pill.strokeWidth = 2f; pill.color = android.graphics.Color.argb(50, 255, 255, 255)
        c.canvas.drawRoundRect(android.graphics.RectF(1f, 1f, c.w - 1f, c.h - 1f), rr, rr, pill)
        pill.style = android.graphics.Paint.Style.FILL
    }

    override fun draw(c: PanelCanvas) {
        val h = c.h
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        c.label(time, 40f, 16f, 30f, XrTheme.text, bold = true)
        if (battery.isNotEmpty()) c.label(battery, 40f, 56f, 20f, XrTheme.textDim, maxLines = 1, maxWidth = 130f)
        val me = com.hyperxr.social.Social.me
        c.avatar(me, 160f, (h - 64f) / 2, 64f, online = me != null)
        addRegion(Region(android.graphics.RectF(160f, 0f, 224f, h), "me", { a.openTarget("people") }))
        val size = h - 26f
        val gap = 10f
        val voice = a.voiceStatus()
        var x = 250f
        fun sys(id: String, iconKey: String, active: Boolean = false, badge: Int = 0, act: () -> Unit) {
            c.iconButton(id, a.icons().icon(iconKey, 128), x, 13f, size, active = active) { act(); dirty = true }
            if (badge > 0) {
                pill.color = XrTheme.danger; c.canvas.drawCircle(x + size - 10f, 22f, 14f, pill)
                c.label(if (badge > 9) "9+" else "$badge", x + size - (if (badge > 9) 22f else 16f), 8f, 20f, android.graphics.Color.WHITE, bold = true)
            }
            x += size + gap
        }
        if (voice.isNotEmpty()) {
            c.label(voice, x, 34f, 28f, XrTheme.accent2, maxWidth = c.w - x - 40f, maxLines = 1)
            return
        }
        sys("lib", "library", active = a.libraryOpen()) { a.toggleLibrary() }
        sys("store", "shop") { a.openTarget("loja") }
        sys("people", "people", badge = com.hyperxr.social.Social.unreadTotal) { a.openTarget("people") }
        sys("camera", "camera_share") { a.openTarget("share") }
        sys("missions", "missions") { a.openTarget("missions") }
        // a thin separator, then the pinned apps
        pill.color = android.graphics.Color.argb(60, 255, 255, 255)
        c.canvas.drawRoundRect(android.graphics.RectF(x, 26f, x + 3f, h - 26f), 2f, 2f, pill)
        x += 3f + gap
        val rightW = 3 * (size + gap) + (if (a.windowCount() > 0) size + gap else 0f) + 20f
        val fit = ((c.w - rightW - x) / (size + gap)).toInt().coerceAtLeast(0)
        for (app in a.pinnedApps().take(fit)) {
            c.iconButton("pin:${app.id}", a.appIcon(app, 128), x, 13f, size, active = a.isRunning(app)) { a.launch(app) }
            x += size + gap
        }
        // right side
        x = c.w - rightW + 10f
        if (a.windowCount() > 0) sys("win", "workspaces", active = !a.windowsMinimized()) { a.toggleWindows() }
        if (a.vrSwitchable()) sys("vr", "vrmode", active = a.vrOn()) { a.setVr(!a.vrOn()) } else x += size + gap
        sys("voice", "voice") { a.startVoice() }
        sys("qs", "settings") { a.openTarget("settings") }
    }
}
