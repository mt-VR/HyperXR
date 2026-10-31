package com.hyperxr.xr

import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import com.hyperxr.launcher.AppCategory
import com.hyperxr.launcher.AppEntry
import com.hyperxr.settings.XrThemeStyle
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * The library (home) laid out the way each theme's system arranges its home: the structure only
 * (where the shelves, docks, rails and tiles go, their shapes and proportions). Icons, glyphs,
 * names and drawings are HyperMagic's own; nothing is taken from those systems' screens.
 *
 *  - Steam Frame: a dark dashboard, a shelf of tall cover cards (the first one big), category
 *    pills, a quick-access column on the right and a bar of buttons at the bottom.
 *  - Google Daydream: a light, airy row of big white cards you page through, tabs on top, dots.
 *  - Pico OS: dark glass, a header with the time, a grid of rounded icons, a floating pill dock.
 *  - Gear VR: a curved board with a 5 x 2 grid of wide tiles, side buttons, a bottom row.
 *  - Fulldive VR: big coloured category tabs and large tiles, two rows.
 *  - Material 3: a navigation rail, a search bar, tonal cards and a floating action button.
 *  - visionOS: round icons floating in offset rows over frosted glass, a vertical pill of tabs.
 */
class ThemedHomePanel(private val a: LauncherActions) : XrPanel(1.9f, 1.06f, title = "") {
    private enum class Shelf(val label: String, val glyph: String) { ALL("Tudo", "apps"), GAMES("Jogos", "sports_esports"), APPS("Apps", "grid_view"), WORLDS("Ambientes", "public") }

    private var shelf = Shelf.ALL
    private var page = 0
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val clock = SimpleDateFormat("HH:mm", Locale.getDefault())
    private var minute = ""
    override val scrollable: Boolean get() = true
    private var scrollAcc = 0f

    init { closable = false; priority = 0 }

    private val style: XrThemeStyle get() = com.hyperxr.HyperXRApp.instance.settings.current.launcher.theme

    override fun scrollBy(dyPx: Float) {
        scrollAcc += dyPx
        while (scrollAcc > 140f) { page++; scrollAcc -= 140f; dirty = true }
        while (scrollAcc < -140f) { page = maxOf(0, page - 1); scrollAcc += 140f; dirty = true }
    }

    override fun update(dt: Float) {
        val m = clock.format(Date())
        if (m != minute) { minute = m; dirty = true }
    }

    private fun apps(): List<AppEntry> = a.allApps().filter {
        when (shelf) {
            Shelf.ALL -> true
            Shelf.GAMES -> it.category == AppCategory.HYPERXR_GAME || it.category == AppCategory.OPENXR || it.category == AppCategory.CARDBOARD
            Shelf.WORLDS -> it.category == AppCategory.ENVIRONMENT
            Shelf.APPS -> it.category != AppCategory.HYPERXR_GAME && it.category != AppCategory.OPENXR && it.category != AppCategory.CARDBOARD && it.category != AppCategory.ENVIRONMENT
        }
    }.let { list ->
        val rec = com.hyperxr.HyperXRApp.instance.settings.current.launcher.recents
        list.sortedWith(compareBy({ if (a.isPinned(it)) 0 else 1 }, { rec.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }))
    }

    private fun open(id: String) { a.allApps().firstOrNull { it.id == id }?.let { a.launch(it) } }

    /** A page of [per] apps (the page index wraps to the last one). */
    private fun pageOf(list: List<AppEntry>, per: Int): List<AppEntry> {
        val pages = maxOf(1, (list.size + per - 1) / per)
        page = page.coerceIn(0, pages - 1)
        return list.drop(page * per).take(per)
    }
    private fun pages(list: List<AppEntry>, per: Int) = maxOf(1, (list.size + per - 1) / per)

    override fun paintBackground(c: PanelCanvas) {
        val r = RectF(0f, 0f, c.w, c.h)
        val radius = XrTheme.PANEL_RADIUS_M * pixelsPerMeter
        paint.shader = when (style) {
            XrThemeStyle.VAPOR -> LinearGradient(0f, 0f, 0f, c.h, intArrayOf(Color.rgb(26, 33, 46), Color.rgb(14, 18, 27)), null, Shader.TileMode.CLAMP)
            XrThemeStyle.BOSQUE -> LinearGradient(0f, 0f, 0f, c.h, intArrayOf(Color.argb(235, 250, 248, 243), Color.argb(235, 236, 231, 220)), null, Shader.TileMode.CLAMP)
            XrThemeStyle.NEVOA -> LinearGradient(0f, 0f, c.w, c.h, intArrayOf(Color.argb(150, 240, 242, 246), Color.argb(120, 214, 219, 228)), null, Shader.TileMode.CLAMP)
            XrThemeStyle.SALAO -> LinearGradient(0f, 0f, 0f, c.h, intArrayOf(Color.argb(235, 40, 40, 44), Color.argb(235, 24, 24, 27)), null, Shader.TileMode.CLAMP)
            else -> LinearGradient(0f, 0f, 0f, c.h, intArrayOf(XrTheme.panelTop, XrTheme.panelBottom), null, Shader.TileMode.CLAMP)
        }
        c.canvas.drawRoundRect(r, radius, radius, paint)
        paint.shader = null
    }

    override fun draw(c: PanelCanvas) {
        when (style) {
            XrThemeStyle.VAPOR -> drawDashboard(c)
            XrThemeStyle.BOSQUE -> drawCardRow(c)
            XrThemeStyle.ORBITA -> drawGlassDock(c)
            XrThemeStyle.SALAO -> drawWideTiles(c)
            XrThemeStyle.MERGULHO -> drawBigTiles(c)
            XrThemeStyle.TONAL -> drawRailCards(c)
            XrThemeStyle.NEVOA -> drawRoundIcons(c)
            else -> drawRailCards(c)
        }
    }

    // ------------------------------------------------------------------ pieces
    private fun icon(c: PanelCanvas, app: AppEntry, r: RectF, corner: Float) {
        c.canvas.save()
        c.canvas.clipPath(Path().apply { addRoundRect(r, corner, corner, Path.Direction.CW) })
        c.canvas.drawBitmap(a.appIcon(app, 192), null, r, bmp)
        c.canvas.restore()
    }

    private fun hoverOutline(c: PanelCanvas, id: String, r: RectF, corner: Float, color: Int = XrTheme.accent) {
        if (!isHovered(id)) return
        paint.style = Paint.Style.STROKE; paint.strokeWidth = 5f; paint.color = color
        c.canvas.drawRoundRect(r, corner, corner, paint)
        paint.style = Paint.Style.FILL
    }

    private fun appRegion(r: RectF, app: AppEntry) {
        addRegion(Region(r, "app:${app.id}", { a.launch(app) }, { a.togglePin(app); dirty = true }))
    }

    private fun fill(c: PanelCanvas, r: RectF, corner: Float, color: Int) { paint.color = color; c.canvas.drawRoundRect(r, corner, corner, paint) }

    private fun glyph(c: PanelCanvas, name: String, r: RectF, color: Int) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = android.graphics.PorterDuffColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN) }
        // there is no forward arrow glyph: the back arrow, mirrored
        if (name == "arrow_forward") {
            c.canvas.save(); c.canvas.scale(-1f, 1f, r.centerX(), r.centerY())
            c.canvas.drawBitmap(Glyphs.get("arrow_back"), null, r, p)
            c.canvas.restore()
        } else c.canvas.drawBitmap(Glyphs.get(name), null, r, p)
    }

    /** A clickable round or pill button with a glyph (and an optional label to its right). */
    private fun pillButton(c: PanelCanvas, id: String, glyphName: String, label: String?, r: RectF, bg: Int, fg: Int, corner: Float = r.height() / 2, action: () -> Unit) {
        fill(c, r, corner, if (isHovered(id)) XrTheme.buttonHover else bg)
        val g = r.height() * 0.5f
        val gx = if (label == null) r.centerX() - g / 2 else r.left + r.height() * 0.3f
        glyph(c, glyphName, RectF(gx, r.centerY() - g / 2, gx + g, r.centerY() + g / 2), fg)
        if (label != null) c.label(label, gx + g + 14f, r.centerY() - 16f, 28f, fg, maxWidth = r.right - gx - g - 20f, maxLines = 1)
        addRegion(Region(r, id, { action(); dirty = true }))
    }

    private fun shelfTabs(c: PanelCanvas, x0: Float, y: Float, w: Float, h: Float, gap: Float, selBg: Int, bg: Int, selFg: Int, fg: Int, corner: Float) {
        var x = x0
        for (s in Shelf.entries) {
            val r = RectF(x, y, x + w, y + h)
            fill(c, r, corner, if (s == shelf) selBg else if (isHovered("shelf:${s.name}")) XrTheme.buttonHover else bg)
            c.label(s.label, x + 22f, y + h / 2 - 17f, 30f, if (s == shelf) selFg else fg, bold = s == shelf, maxWidth = w - 30f, maxLines = 1)
            addRegion(Region(r, "shelf:${s.name}", { shelf = s; page = 0; dirty = true }))
            x += w + gap
        }
    }

    private fun pager(c: PanelCanvas, list: List<AppEntry>, per: Int, cx: Float, y: Float, dot: Int, dotOn: Int) {
        val n = pages(list, per); if (n <= 1) return
        val step = 30f
        var x = cx - (n - 1) * step / 2
        for (i in 0 until n) { paint.color = if (i == page) dotOn else dot; c.canvas.drawCircle(x, y, if (i == page) 9f else 7f, paint); x += step }
        val left = RectF(cx - n * step / 2 - 90f, y - 34f, cx - n * step / 2 - 22f, y + 34f)
        val right = RectF(cx + n * step / 2 + 22f, y - 34f, cx + n * step / 2 + 90f, y + 34f)
        pillButton(c, "prev", "arrow_back", null, left, Color.TRANSPARENT, dotOn) { page = maxOf(0, page - 1) }
        pillButton(c, "next", "arrow_forward", null, right, Color.TRANSPARENT, dotOn) { page++ }
    }

    // ------------------------------------------------------------------ Steam Frame: dashboard
    private fun drawDashboard(c: PanelCanvas) {
        val list = apps()
        val dim = Color.rgb(150, 164, 184)
        c.label("Biblioteca", 60f, 44f, 46f, Color.WHITE, bold = true)
        c.label("$minute   ${batteryText()}", c.w - 400f, 54f, 30f, dim, maxWidth = 330f, maxLines = 1)
        shelfTabs(c, 60f, 120f, 210f, 56f, 14f, XrTheme.accent, Color.rgb(36, 46, 62), Color.WHITE, dim, 8f)
        // the shelf: one big cover, then smaller ones, all 3:4
        val shelfApps = pageOf(list, 6)
        var x = 60f
        val top = 210f
        for ((i, app) in shelfApps.withIndex()) {
            val w = if (i == 0) 330f else 200f
            val h = if (i == 0) 440f else 267f
            val r = RectF(x, top, x + w, top + h)
            fill(c, r, 10f, Color.rgb(32, 41, 56))
            icon(c, app, RectF(r.left + w * 0.15f, r.top + h * 0.12f, r.right - w * 0.15f, r.top + h * 0.12f + w * 0.7f), 14f)
            c.label(com.hyperxr.i18n.I18n.t(app.title), r.left + 14f, r.bottom - 64f, if (i == 0) 32f else 25f, Color.WHITE, maxWidth = w - 28f, maxLines = 2)
            hoverOutline(c, "app:${app.id}", r, 10f)
            appRegion(r, app)
            x += w + 18f
            if (x > c.w - 380f) break
        }
        // quick access on the right
        val qx = c.w - 330f
        fill(c, RectF(qx, 210f, c.w - 50f, 650f), 10f, Color.rgb(24, 31, 43))
        c.label("Acesso rápido", qx + 24f, 228f, 28f, dim)
        val quick = listOf(Triple("q:settings", "settings", "Ajustes") to { open("hx:settings") },
            Triple("q:windows", "dashboard", "Janelas") to { a.toggleWindows() },
            Triple("q:worlds", "public", "Ambientes") to { open("hx:store") },
            Triple("q:people", "group", "Pessoas") to { open("hx:people") })
        var qy = 284f
        for ((t, act) in quick) { pillButton(c, t.first, t.second, t.third, RectF(qx + 20f, qy, c.w - 70f, qy + 76f), Color.rgb(36, 46, 62), Color.WHITE, 8f, act); qy += 88f }
        // bottom bar
        val by = c.h - 120f
        fill(c, RectF(60f, by, c.w - 50f, by + 84f), 8f, Color.rgb(20, 26, 37))
        var bx = 80f
        for ((id, g) in listOf("b:home" to "apps", "b:prev" to "arrow_back", "b:next" to "arrow_forward", "b:search" to "search")) {
            pillButton(c, id, g, null, RectF(bx, by + 10f, bx + 64f, by + 74f), Color.TRANSPARENT, Color.WHITE, 8f) {
                when (id) { "b:prev" -> page = maxOf(0, page - 1); "b:next" -> page++; "b:home" -> { shelf = Shelf.ALL; page = 0 }; else -> {} }
            }
            bx += 80f
        }
        c.label("${list.size} itens · página ${page + 1}/${pages(list, 6)}", c.w - 520f, by + 26f, 26f, dim, maxWidth = 440f, maxLines = 1)
    }

    // ------------------------------------------------------------------ Daydream: a row of light cards
    private fun drawCardRow(c: PanelCanvas) {
        val list = apps()
        val ink = Color.rgb(43, 42, 38); val soft = Color.rgb(110, 104, 92)
        // tabs centred on top, underline on the selected one
        var tx = c.w / 2 - Shelf.entries.size * 180f / 2
        for (s in Shelf.entries) {
            val r = RectF(tx, 40f, tx + 180f, 110f)
            c.label(s.label, tx + 40f, 56f, 32f, if (s == shelf) ink else soft, bold = s == shelf, maxWidth = 140f, maxLines = 1)
            if (s == shelf) fill(c, RectF(tx + 40f, 102f, tx + 140f, 108f), 3f, XrTheme.accent)
            addRegion(Region(r, "shelf:${s.name}", { shelf = s; page = 0; dirty = true }))
            tx += 180f
        }
        val cards = pageOf(list, 4)
        val cw = 360f; val gap = 36f
        var x = c.w / 2 - (cards.size * cw + (cards.size - 1) * gap) / 2
        for (app in cards) {
            val r = RectF(x, 170f, x + cw, 760f)
            paint.color = Color.argb(40, 0, 0, 0); c.canvas.drawRoundRect(RectF(r.left + 6f, r.top + 10f, r.right + 6f, r.bottom + 12f), 36f, 36f, paint)
            fill(c, r, 36f, Color.WHITE)
            icon(c, app, RectF(r.left + 70f, r.top + 70f, r.right - 70f, r.top + 70f + cw - 140f), 40f)
            c.label(com.hyperxr.i18n.I18n.t(app.title), r.left + 30f, r.top + cw + 10f, 34f, ink, bold = true, maxWidth = cw - 60f, maxLines = 2)
            c.label(app.category.name.lowercase().replaceFirstChar { it.uppercase() }, r.left + 30f, r.bottom - 70f, 26f, soft, maxWidth = cw - 60f, maxLines = 1)
            hoverOutline(c, "app:${app.id}", r, 36f)
            appRegion(r, app)
            x += cw + gap
        }
        pager(c, list, 4, c.w / 2, c.h - 120f, Color.rgb(200, 194, 180), ink)
    }

    // ------------------------------------------------------------------ Pico OS: icon grid + floating dock
    private fun drawGlassDock(c: PanelCanvas) {
        val list = apps()
        c.label(minute, 70f, 40f, 54f, XrTheme.text, bold = true)
        c.label(batteryText(), 70f, 110f, 26f, XrTheme.textDim, maxWidth = 300f, maxLines = 1)
        shelfTabs(c, c.w - 4 * 196f - 50f, 60f, 182f, 62f, 14f, XrTheme.accent, Color.argb(60, 255, 255, 255), Color.rgb(10, 20, 30), XrTheme.text, 31f)
        val cols = 6; val rows = 2; val s = 170f
        val gx = (c.w - cols * s - (cols - 1) * 70f) / 2
        for ((i, app) in pageOf(list, cols * rows).withIndex()) {
            val x = gx + (i % cols) * (s + 70f); val y = 190f + (i / cols) * (s + 100f)
            val r = RectF(x, y, x + s, y + s)
            icon(c, app, r, 44f)
            hoverOutline(c, "app:${app.id}", r, 44f, Color.WHITE)
            c.label(com.hyperxr.i18n.I18n.t(app.title), x - 20f, y + s + 12f, 25f, XrTheme.text, maxWidth = s + 40f, maxLines = 1)
            appRegion(RectF(x - 20f, y - 10f, x + s + 20f, y + s + 60f), app)
        }
        pager(c, list, cols * rows, c.w / 2, c.h - 190f, Color.argb(80, 255, 255, 255), XrTheme.accent)
        // the dock: a floating pill of round buttons
        val dw = 760f; val dock = RectF(c.w / 2 - dw / 2, c.h - 130f, c.w / 2 + dw / 2, c.h - 40f)
        fill(c, dock, 45f, Color.argb(200, 30, 36, 50))
        var bx = dock.left + 30f
        for ((id, g) in listOf("d:home" to "apps", "d:store" to "public", "d:people" to "group", "d:windows" to "dashboard", "d:camera" to "photo_camera", "d:settings" to "settings")) {
            pillButton(c, id, g, null, RectF(bx, dock.top + 13f, bx + 64f, dock.top + 77f), Color.argb(50, 255, 255, 255), XrTheme.text) {
                when (id) { "d:home" -> { shelf = Shelf.ALL; page = 0 }; "d:store" -> open("hx:store"); "d:people" -> open("hx:people"); "d:windows" -> a.toggleWindows(); "d:camera" -> open("hx:camera_share"); else -> open("hx:settings") }
            }
            bx += 120f
        }
    }

    // ------------------------------------------------------------------ Gear VR: 5 x 2 wide tiles, side buttons
    private fun drawWideTiles(c: PanelCanvas) {
        val list = apps()
        val dim = Color.rgb(160, 160, 168)
        // side buttons on the left
        var sy = 130f
        for (s in Shelf.entries) {
            pillButton(c, "shelf:${s.name}", s.glyph, s.label, RectF(40f, sy, 300f, sy + 84f), if (s == shelf) XrTheme.accent else Color.rgb(52, 52, 58), Color.WHITE, 14f) { shelf = s; page = 0 }
            sy += 100f
        }
        c.label(shelf.label, 340f, 40f, 44f, Color.WHITE, bold = true)
        // the board: 5 x 2 wide tiles with the name under them
        val cols = 5; val tw = 270f; val th = 170f; val gx = 340f
        for ((i, app) in pageOf(list, cols * 2).withIndex()) {
            val x = gx + (i % cols) * (tw + 26f); val y = 130f + (i / cols) * (th + 100f)
            val r = RectF(x, y, x + tw, y + th)
            fill(c, r, 12f, Color.rgb(58, 58, 64))
            icon(c, app, RectF(r.centerX() - 60f, r.centerY() - 60f, r.centerX() + 60f, r.centerY() + 60f), 18f)
            hoverOutline(c, "app:${app.id}", r, 12f, Color.WHITE)
            c.label(com.hyperxr.i18n.I18n.t(app.title), x, y + th + 12f, 26f, Color.WHITE, maxWidth = tw, maxLines = 1)
            appRegion(RectF(x, y, x + tw, y + th + 50f), app)
        }
        // bottom row of buttons
        val by = c.h - 120f
        var bx = 340f
        for ((id, g, l) in listOf(Triple("b:settings", "settings", "Ajustes"), Triple("b:gallery", "landscape", "Galeria"), Triple("b:windows", "dashboard", "Janelas"))) {
            pillButton(c, id, g, l, RectF(bx, by, bx + 250f, by + 76f), Color.rgb(52, 52, 58), Color.WHITE, 14f) {
                when (id) { "b:settings" -> open("hx:settings"); "b:gallery" -> open("hx:gallery"); else -> a.toggleWindows() }
            }
            bx += 270f
        }
        pager(c, list, cols * 2, c.w - 220f, by + 38f, Color.rgb(90, 90, 98), Color.WHITE)
        c.label(minute, c.w - 160f, 50f, 34f, dim)
    }

    // ------------------------------------------------------------------ Fulldive VR: coloured tabs, big tiles
    private fun drawBigTiles(c: PanelCanvas) {
        val list = apps()
        val tabColors = intArrayOf(Color.rgb(255, 176, 32), Color.rgb(78, 196, 230), Color.rgb(140, 120, 255), Color.rgb(90, 210, 140))
        var tx = 60f
        for ((i, s) in Shelf.entries.withIndex()) {
            val r = RectF(tx, 40f, tx + 400f, 140f)
            fill(c, r, 26f, if (s == shelf) tabColors[i] else Color.argb(60, 255, 255, 255))
            glyph(c, s.glyph, RectF(tx + 28f, 64f, tx + 80f, 116f), if (s == shelf) Color.rgb(20, 18, 50) else Color.WHITE)
            c.label(s.label, tx + 100f, 66f, 36f, if (s == shelf) Color.rgb(20, 18, 50) else Color.WHITE, bold = true, maxWidth = 280f, maxLines = 1)
            addRegion(Region(r, "shelf:${s.name}", { shelf = s; page = 0; dirty = true }))
            tx += 420f
        }
        val cols = 4; val tw = 400f; val th = 300f
        for ((i, app) in pageOf(list, cols * 2).withIndex()) {
            val x = 60f + (i % cols) * (tw + 20f); val y = 180f + (i / cols) * (th + 24f)
            val r = RectF(x, y, x + tw, y + th)
            fill(c, r, 26f, Color.argb(255, 58 + (i * 23) % 40, 52 + (i * 17) % 30, 140 + (i * 29) % 60))
            icon(c, app, RectF(r.centerX() - 80f, r.top + 40f, r.centerX() + 80f, r.top + 200f), 30f)
            c.label(com.hyperxr.i18n.I18n.t(app.title), r.left + 24f, r.bottom - 74f, 32f, Color.WHITE, bold = true, maxWidth = tw - 48f, maxLines = 1)
            hoverOutline(c, "app:${app.id}", r, 26f, XrTheme.accent)
            appRegion(r, app)
        }
        pager(c, list, cols * 2, c.w / 2, c.h - 50f, Color.argb(90, 255, 255, 255), XrTheme.accent)
    }

    // ------------------------------------------------------------------ Material 3: rail, search, tonal cards, FAB
    private fun drawRailCards(c: PanelCanvas) {
        val list = apps()
        val rail = 170f
        var ry = 120f
        for (s in Shelf.entries) {
            val sel = s == shelf
            val ind = RectF(rail / 2 - 56f, ry, rail / 2 + 56f, ry + 64f)
            if (sel) fill(c, ind, 32f, XrTheme.button)
            glyph(c, s.glyph, RectF(ind.centerX() - 22f, ind.centerY() - 22f, ind.centerX() + 22f, ind.centerY() + 22f), if (sel) XrTheme.text else XrTheme.textDim)
            c.label(s.label, 20f, ry + 76f, 24f, if (sel) XrTheme.text else XrTheme.textDim, bold = sel, maxWidth = rail - 40f, maxLines = 1)
            addRegion(Region(RectF(0f, ry - 10f, rail, ry + 120f), "shelf:${s.name}", { shelf = s; page = 0; dirty = true }))
            ry += 160f
        }
        // search bar
        val sb = RectF(rail + 30f, 40f, c.w - 60f, 124f)
        fill(c, sb, 42f, XrTheme.tile)
        glyph(c, "search", RectF(sb.left + 30f, sb.centerY() - 20f, sb.left + 70f, sb.centerY() + 20f), XrTheme.textDim)
        c.label("Buscar na biblioteca", sb.left + 90f, sb.centerY() - 18f, 30f, XrTheme.textDim, maxWidth = 500f, maxLines = 1)
        c.label(minute, sb.right - 130f, sb.centerY() - 18f, 30f, XrTheme.text)
        // tonal cards, 4 x 2
        val cols = 4; val cw = (c.w - rail - 90f - (cols - 1) * 24f) / cols; val ch = 300f
        for ((i, app) in pageOf(list, cols * 2).withIndex()) {
            val x = rail + 30f + (i % cols) * (cw + 24f); val y = 160f + (i / cols) * (ch + 24f)
            val r = RectF(x, y, x + cw, y + ch)
            fill(c, r, XrTheme.TILE_RADIUS, if (isHovered("app:${app.id}")) XrTheme.buttonHover else XrTheme.tile)
            icon(c, app, RectF(x + 28f, y + 28f, x + 148f, y + 148f), 34f)
            c.label(com.hyperxr.i18n.I18n.t(app.title), x + 28f, y + 170f, 32f, XrTheme.text, bold = true, maxWidth = cw - 56f, maxLines = 2)
            appRegion(r, app)
        }
        pager(c, list, cols * 2, (rail + c.w) / 2, c.h - 50f, XrTheme.button, XrTheme.accent)
        // floating action button: the store
        val fab = RectF(c.w - 230f, c.h - 150f, c.w - 50f, c.h - 50f)
        fill(c, fab, 28f, XrTheme.accent)
        glyph(c, "storefront", RectF(fab.left + 28f, fab.centerY() - 22f, fab.left + 72f, fab.centerY() + 22f), XrTheme.panelBottom)
        c.label("Loja", fab.left + 86f, fab.centerY() - 18f, 30f, XrTheme.panelBottom, bold = true)
        addRegion(Region(fab, "fab", { open("hx:store") }))
    }

    // ------------------------------------------------------------------ visionOS: round icons in offset rows
    private fun drawRoundIcons(c: PanelCanvas) {
        val list = apps()
        // a vertical pill of tabs on the left
        val pill = RectF(30f, c.h / 2 - 230f, 130f, c.h / 2 + 230f)
        fill(c, pill, 50f, Color.argb(150, 255, 255, 255))
        var py = pill.top + 30f
        for (s in Shelf.entries) {
            val r = RectF(pill.left + 14f, py, pill.right - 14f, py + 72f)
            if (s == shelf) fill(c, r, 36f, Color.argb(220, 255, 255, 255))
            glyph(c, s.glyph, RectF(r.centerX() - 22f, r.centerY() - 22f, r.centerX() + 22f, r.centerY() + 22f), Color.rgb(30, 31, 35))
            addRegion(Region(r, "shelf:${s.name}", { shelf = s; page = 0; dirty = true }))
            py += 104f
        }
        // round icons, rows offset by half an icon
        val d = 180f; val cols = 6
        for ((i, app) in pageOf(list, cols * 3 - 1).withIndex()) {
            val row = if (i < cols) 0 else if (i < 2 * cols - 1) 1 else 2
            val col = when (row) { 0 -> i; 1 -> i - cols; else -> i - (2 * cols - 1) }
            val off = if (row == 1) d * 0.6f else 0f
            val x = 260f + off + col * (d * 1.2f); val y = 70f + row * (d + 90f)
            val r = RectF(x, y, x + d, y + d)
            paint.color = Color.argb(60, 0, 0, 0); c.canvas.drawCircle(r.centerX() + 4f, r.centerY() + 8f, d / 2, paint)
            icon(c, app, r, d / 2)
            if (isHovered("app:${app.id}")) {
                hoverOutline(c, "app:${app.id}", r, d / 2, Color.WHITE)
            }
            c.label(com.hyperxr.i18n.I18n.t(app.title), x - 20f, y + d + 10f, 24f, Color.rgb(30, 31, 35), maxWidth = d + 40f, maxLines = 1)
            appRegion(RectF(x - 10f, y - 10f, x + d + 10f, y + d + 50f), app)
        }
        pager(c, list, cols * 3 - 1, c.w / 2 + 60f, c.h - 50f, Color.argb(90, 0, 0, 0), Color.rgb(30, 31, 35))
    }
}
