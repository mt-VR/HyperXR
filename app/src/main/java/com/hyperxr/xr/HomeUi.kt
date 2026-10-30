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
 * The Hyper interface's app library (a window opened from the dock), laid out like a headset's App
 * Library: title, search, filter chips and the count, then a grid of wide tiles. Games show their
 * cover art; apps a coloured tile with their icon and name. Long-press a tile to pin it to the dock.
 */
class LibraryHomePanel(private val a: LauncherActions, asWindow: Boolean) : XrPanel(1.5f, 1.0f, title = "") {
    private enum class Section(val label: String) { ALL("Todos"), RECENT("Recentes"), GAMES("Jogos"), APPS("Apps"), WEB("Web"), SYSTEM("Sistema") }

    private var section = Section.ALL
    private var page = 0
    private var scrollAcc = 0f
    override val scrollable: Boolean get() = true

    /** Dragging the grid up shows the next page, down the previous one. */
    override fun scrollBy(dyPx: Float) {
        scrollAcc += dyPx
        val step = 160f
        while (scrollAcc > step) { page++; scrollAcc -= step }
        while (scrollAcc < -step) { page = maxOf(0, page - 1); scrollAcc += step }
        dirty = true
    }
    private var minute = -1L
    private var battery = ""
    private var note: String? = null
    private val search = UrlEditor({ a.closeKeyboard() }, { page = 0; dirty = true })
    private val tile = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    /** Pictures are drawn with their own paint: a fill colour's alpha must never fade them. */
    private val bmp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)

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
        // header: title and count, the clock, search
        c.label("Biblioteca", c.pad, 26f, 44f, XrTheme.text, bold = true, maxWidth = 320f, maxLines = 1)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        c.label(listOf(time, battery.substringBefore(' ')).filter { it.isNotEmpty() }.joinToString("   "), c.pad + 270f, 40f, 24f, XrTheme.textDim, maxWidth = 200f, maxLines = 1)
        val active = search.active
        val sw = 420f
        c.button("search", if (active) search.text + "|" else if (search.text.isNotEmpty()) search.text else "Pesquisar na biblioteca", c.w - c.pad - sw, 22f, sw, 62f,
            accent = active, icon = Glyphs.get("search")) { if (!search.active) { search.begin(search.text); a.openKeyboard(search) } else { search.cancel() } }
        // the filters, all visible: one tap each
        var fx = c.pad
        for (sec in Section.entries) {
            val n = if (sec == section) " (${apps.size})" else ""
            val bw = 40f + (sec.label.length + n.length) * 15f + 20f
            c.button("sec:${sec.name}", sec.label + n, fx, 104f, bw, 56f, accent = sec == section) { section = sec; page = 0 }
            fx += bw + 12f
        }
        // tiles
        val cols = 4; val rows = 4
        val gx = c.pad; val gy = 184f
        val gw = c.w - 2 * c.pad
        val gh = c.h - gy - 96f
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
        // bottom row: the store and VR switch on the left, the pages on the right
        val by = c.h - 76f
        c.button("loja", "Loja", c.pad, by, 170f, 60f, icon = Glyphs.get("storefront")) { a.openTarget("loja") }
        if (a.vrSwitchable()) c.button("vr", if (a.vrOn()) "Modo VR" else "Realidade mista", c.pad + 186f, by, 270f, 60f, accent = a.vrOn(), icon = Glyphs.get("vrpano")) { a.setVr(!a.vrOn()); dirty = true }
        if (pages > 1) {
            c.button("prev", "‹  Anterior", c.w - c.pad - 560f, by, 200f, 60f, enabled = page > 0) { page-- }
            c.label("${page + 1} de $pages", c.w - c.pad - 345f, by + 16f, 25f, XrTheme.textDim, maxWidth = 130f, maxLines = 1)
            c.button("next", "Próxima  ›", c.w - c.pad - 200f, by, 200f, 60f, enabled = page < pages - 1) { page++ }
        }
        note?.let { c.label(it, c.pad + 480f, by + 16f, 22f, XrTheme.accent2, maxWidth = 420f, maxLines = 1) }
    }

    /** A game: its cover. An app: a solid tile with its icon, name and kind. */
    private fun drawTile(c: PanelCanvas, app: AppEntry, x: Float, y: Float, w: Float, h: Float) {
        val id = "app:${app.id}"
        val hover = isHovered(id); val press = isPressed(id)
        val grow = if (press) -4f else if (hover) 4f else 0f
        val r = android.graphics.RectF(x - grow, y - grow, x + w + grow, y + h + grow)
        val ctx = com.hyperxr.HyperXRApp.instance
        val game = app.category == AppCategory.HYPERXR_GAME
        val rad = XrTheme.TILE_RADIUS
        if (game) {
            c.canvas.save()
            c.canvas.clipPath(android.graphics.Path().apply { addRoundRect(r, rad, rad, android.graphics.Path.Direction.CW) })
            c.canvas.drawBitmap(com.hyperxr.rendering.CoverArt.cover(ctx, app.iconKey, app.title), null, r, bmp)
            c.canvas.restore()
        } else {
            tile.color = if (press) XrTheme.buttonPressed else if (hover) XrTheme.buttonHover else XrTheme.tile
            c.canvas.drawRoundRect(r, rad, rad, tile)
            val s = (h * 0.62f).coerceAtMost(w * 0.36f)
            val icon = a.appIcon(app, 128)
            val ir = android.graphics.RectF(r.left + 18f, r.centerY() - s / 2, r.left + 18f + s, r.centerY() + s / 2)
            c.canvas.save()
            c.canvas.clipPath(android.graphics.Path().apply { addRoundRect(ir, s * 0.24f, s * 0.24f, android.graphics.Path.Direction.CW) })
            c.canvas.drawBitmap(icon, null, ir, bmp)
            c.canvas.restore()
            val kind = when {
                app.kind == AppKind.WEB -> "Web"
                app.kind == AppKind.ANDROID_PACKAGE -> "App Android"
                app.category == AppCategory.SYSTEM -> "Sistema"
                else -> "HyperMagic"
            }
            c.label(app.title, ir.right + 16f, r.centerY() - 32f, 27f, XrTheme.text, bold = true, maxLines = 1, maxWidth = r.right - ir.right - 30f)
            c.label(kind, ir.right + 16f, r.centerY() + 4f, 21f, XrTheme.textDim, maxLines = 1, maxWidth = r.right - ir.right - 30f)
        }
        if (hover) {
            tile.style = android.graphics.Paint.Style.STROKE; tile.strokeWidth = 4f; tile.color = android.graphics.Color.WHITE
            c.canvas.drawRoundRect(r, rad, rad, tile); tile.style = android.graphics.Paint.Style.FILL
        }
        addRegion(Region(android.graphics.RectF(x, y, x + w, y + h), id, { a.launch(app) }, {
            a.togglePin(app); note = if (a.isPinned(app)) "${app.title} fixado na dock" else "${app.title} removido da dock"; dirty = true
        }))
    }
}

/**
 * The dock, like a headset's universal menu: a dark rounded bar. On the left a status block with
 * the clock, battery and Wi-Fi (it opens Ajustes rápidos), your avatar and the bell; then solid
 * coloured squares with a white glyph (Missões, Loja, Pessoas, Câmera), your pinned apps, and at
 * the right Ajustes rápidos and the Biblioteca grid. The bar grows with the pinned apps and stays
 * centred; a dot marks what is open, and pointing at an icon shows its name above it.
 */
class TaskbarPanel(private val a: LauncherActions) : XrPanel(1.0f, 0.15f, title = "") {
    init { closable = false; priority = 0 }
    private var minute = -1L
    private var key = ""
    private var battery = ""
    private var wifi = true
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    private val bmp = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)

    private class Item(val id: String, val name: String, val glyph: String?, val color: Int, val icon: android.graphics.Bitmap?, val active: Boolean, val badge: Int, val act: () -> Unit)

    override fun update(dt: Float) {
        val m = System.currentTimeMillis() / 60_000
        val k = "${a.windowCount()}/${a.windowsMinimized()}/${a.mode()}/${a.voiceStatus()}/${a.vrOn()}/${a.pinnedApps().joinToString { it.id }}/${com.hyperxr.social.Social.unreadTotal}/${a.libraryOpen()}"
        if (m != minute || k != key) {
            minute = m; key = k; battery = batteryText(); dirty = true
            wifi = runCatching {
                val cm = com.hyperxr.HyperXRApp.instance.getSystemService(android.net.ConnectivityManager::class.java)
                cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            }.getOrDefault(false)
            // the bar fits its icons
            val want = (layoutWidth() / pixelsPerMeter).coerceIn(0.7f, 1.7f)
            if (kotlin.math.abs(want - widthMeters) > 0.005f) widthMeters = want
        }
    }

    private fun items(): List<Item> {
        val list = ArrayList<Item>()
        list += Item("missions", "Missões", "emoji_events", android.graphics.Color.rgb(47, 124, 246), null, false, 0) { a.openTarget("missions") }
        list += Item("store", "Loja", "storefront", android.graphics.Color.rgb(245, 150, 30), null, false, 0) { a.openTarget("loja") }
        list += Item("people", "Pessoas", "group", android.graphics.Color.rgb(139, 92, 246), null, false, com.hyperxr.social.Social.unreadTotal) { a.openTarget("people") }
        list += Item("camera", "Câmera", "share", android.graphics.Color.rgb(236, 72, 153), null, false, 0) { a.openTarget("share") }
        if (a.windowCount() > 0) list += Item("win", if (a.windowsMinimized()) "Mostrar janelas" else "Esconder janelas", "dashboard", android.graphics.Color.rgb(84, 96, 110), null, !a.windowsMinimized(), 0) { a.toggleWindows() }
        list += Item("|", "", null, 0, null, false, 0) {}
        for (app in a.pinnedApps().take(6)) list += Item("pin:${app.id}", app.title, null, 0, a.appIcon(app, 128), a.isRunning(app), 0) { a.launch(app) }
        if (a.pinnedApps().isNotEmpty()) list += Item("|", "", null, 0, null, false, 0) {}
        list += Item("quick", "Ajustes rápidos", "settings", android.graphics.Color.rgb(20, 170, 150), null, false, 0) { a.openTarget("quick") }
        list += Item("lib", "Biblioteca", "apps", 0, null, a.libraryOpen(), 0) { a.toggleLibrary() }
        return list
    }

    private val statusW = 170f
    private val icon = 84f
    private val gap = 14f
    private fun layoutWidth(): Float {
        var w = 14f + statusW + 16f + 70f + 10f + 56f + 22f
        for (it in items()) w += if (it.id == "|") 22f else icon + gap
        return w + 8f
    }

    override fun paintBackground(c: PanelCanvas) {
        val barTop = 36f   // room above the bar for the name of what you point at
        val r = (c.h - barTop) * 0.26f
        paint.color = android.graphics.Color.argb(240, 26, 32, 36)
        c.canvas.drawRoundRect(android.graphics.RectF(0f, barTop, c.w, c.h), r, r, paint)
        paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 2f; paint.color = android.graphics.Color.argb(36, 255, 255, 255)
        c.canvas.drawRoundRect(android.graphics.RectF(1f, barTop + 1f, c.w - 1f, c.h - 1f), r, r, paint)
        paint.style = android.graphics.Paint.Style.FILL
    }

    override fun draw(c: PanelCanvas) {
        val top = 36f
        val bh = c.h - top
        val voice = a.voiceStatus()
        // status block: clock over battery and Wi-Fi; opens quick settings
        val sr = android.graphics.RectF(12f, top + 12f, 12f + statusW, c.h - 12f)
        val sHover = isHovered("status")
        paint.color = if (sHover) android.graphics.Color.rgb(64, 76, 84) else android.graphics.Color.rgb(46, 56, 62)
        c.canvas.drawRoundRect(sr, 18f, 18f, paint)
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        c.label(time, sr.left + 18f, sr.top + 6f, 30f, XrTheme.text, bold = true, maxLines = 1)
        c.image(Glyphs.get("battery_full"), sr.left + 18f, sr.bottom - 34f, 24f, 24f)
        c.label(battery.substringBefore(' '), sr.left + 44f, sr.bottom - 34f, 19f, XrTheme.textDim, maxLines = 1, maxWidth = 70f)
        c.image(Glyphs.get(if (wifi) "wifi" else "wifi_off"), sr.right - 40f, sr.bottom - 34f, 24f, 24f)
        addRegion(Region(sr, "status", { a.openTarget("quick") }))
        var x = sr.right + 16f
        // avatar and the bell
        val me = com.hyperxr.social.Social.me
        c.avatar(me, x, top + (bh - 64f) / 2, 64f, online = me != null)
        addRegion(Region(android.graphics.RectF(x, top, x + 64f, c.h), "me", { a.openTarget("people") }))
        x += 70f + 10f
        c.roundIconButton("bell", Glyphs.get("notifications"), x, top + (bh - 56f) / 2, 56f) { a.openTarget("messages") }
        x += 56f + 22f
        if (voice.isNotEmpty()) {
            c.label(voice, x, top + bh / 2 - 18f, 28f, XrTheme.accent2, maxWidth = c.w - x - 30f, maxLines = 1)
            return
        }
        var hoverName = ""; var hoverX = 0f
        for (it in items()) {
            if (it.id == "|") {
                paint.color = android.graphics.Color.argb(60, 255, 255, 255)
                c.canvas.drawRoundRect(android.graphics.RectF(x + 8f, top + 26f, x + 11f, c.h - 26f), 2f, 2f, paint)
                x += 22f; continue
            }
            val iy = top + (bh - icon) / 2
            val r = android.graphics.RectF(x, iy, x + icon, iy + icon)
            val hover = isHovered(it.id); val press = isPressed(it.id)
            val k = if (press) 6f else if (hover) -3f else 0f
            val rr = android.graphics.RectF(r.left + k, r.top + k, r.right - k, r.bottom - k)
            when {
                it.icon != null -> {
                    c.canvas.save()
                    c.canvas.clipPath(android.graphics.Path().apply { addRoundRect(rr, rr.width() * 0.24f, rr.width() * 0.24f, android.graphics.Path.Direction.CW) })
                    c.canvas.drawBitmap(it.icon, null, rr, bmp)
                    c.canvas.restore()
                }
                it.color != 0 -> {
                    paint.color = it.color
                    c.canvas.drawRoundRect(rr, rr.width() * 0.24f, rr.width() * 0.24f, paint)
                    val g = rr.width() * 0.5f
                    c.canvas.drawBitmap(Glyphs.get(it.glyph!!), null, android.graphics.RectF(rr.centerX() - g / 2, rr.centerY() - g / 2, rr.centerX() + g / 2, rr.centerY() + g / 2), bmp)
                }
                else -> {
                    if (hover || press) { paint.color = android.graphics.Color.rgb(60, 70, 78); c.canvas.drawRoundRect(rr, rr.width() * 0.24f, rr.width() * 0.24f, paint) }
                    val g = rr.width() * 0.56f
                    c.canvas.drawBitmap(Glyphs.get(it.glyph!!), null, android.graphics.RectF(rr.centerX() - g / 2, rr.centerY() - g / 2, rr.centerX() + g / 2, rr.centerY() + g / 2), bmp)
                }
            }
            if (hover) {
                paint.style = android.graphics.Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = android.graphics.Color.WHITE
                c.canvas.drawRoundRect(rr, rr.width() * 0.24f, rr.width() * 0.24f, paint); paint.style = android.graphics.Paint.Style.FILL
                hoverName = it.name; hoverX = r.centerX()
            }
            if (it.active) { paint.color = android.graphics.Color.WHITE; c.canvas.drawCircle(r.centerX(), c.h - 7f, 4f, paint) }
            if (it.badge > 0) {
                paint.color = XrTheme.danger; c.canvas.drawCircle(r.right - 6f, r.top + 6f, 15f, paint)
                c.label(if (it.badge > 9) "9+" else "${it.badge}", r.right - (if (it.badge > 9) 19f else 12f), r.top - 8f, 19f, android.graphics.Color.WHITE, bold = true)
            }
            val act = it.act
            addRegion(Region(android.graphics.RectF(x - gap / 2, top, x + icon + gap / 2, c.h), it.id, { act(); dirty = true }))
            x += icon + gap
        }
        if (hoverName.isNotEmpty()) {
            val tw = hoverName.length * 13f + 30f
            val l = (hoverX - tw / 2).coerceIn(4f, c.w - tw - 4f)
            paint.color = android.graphics.Color.argb(235, 20, 24, 28)
            c.canvas.drawRoundRect(android.graphics.RectF(l, 0f, l + tw, 32f), 16f, 16f, paint)
            c.label(hoverName, l + 15f, 4f, 21f, XrTheme.text, maxLines = 1, maxWidth = tw)
        }
    }
}
