package com.hyperxr.xr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffColorFilter
import android.graphics.RectF
import android.graphics.Shader
import com.hyperxr.launcher.AppCategory
import com.hyperxr.launcher.AppEntry
import com.hyperxr.launcher.AppKind
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/*
 * The Meta interface: HyperMagic laid out like Meta Horizon OS. The library is home, floating in
 * front of you with no window around it: the title and search on top, a list of sections on the
 * left, a grid of rounded icons with their names under them in the middle, filter and order on
 * the right. Under it a light bar of round buttons, with the Wi-Fi, the time and the battery below.
 * The layout follows the system's; the logo, glyphs (Material Symbols) and code are HyperMagic's.
 */

private val DARK = Color.rgb(28, 30, 34)
private val dimText = Color.argb(215, 255, 255, 255)

/** A white glyph tinted (the dark glyph inside a white pill or disc). */
private fun tinted(color: Int) = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { colorFilter = PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN) }

/** The Meta library: the home of the Meta interface (and what the Biblioteca button opens over windows). */
class MetaLibraryPanel(private val a: LauncherActions) : XrPanel(1.9f, 1.06f, title = "") {
    private enum class Section(val label: String, val glyph: String) {
        ALL("Tudo", "apps"), APPS("Apps", "grid_view"), GAMES("Jogos", "sports_esports"), WORLDS("Ambientes", "public"), INSTALLED("Baixados", "download")
    }
    private enum class Filter(val label: String) { ALL("Tudo"), PINNED("Fixados"), RUNNING("Abertos") }
    private enum class Order(val label: String) { RECENT("Recentes"), AZ("A a Z"), ZA("Z a A") }

    private var section = Section.ALL
    private var filter = Filter.ALL
    private var order = Order.RECENT
    private var row = 0
    private var note: String? = null
    private var noteT = 0f
    private val search = UrlEditor({ a.closeKeyboard() }, { row = 0; dirty = true })
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val darkGlyph = tinted(DARK)

    init { closable = false; priority = 0 }

    override fun update(dt: Float) {
        if (note != null) { noteT -= dt; if (noteT <= 0f) { note = null; dirty = true } }
    }

    /** No window: a soft dark veil that fades out at the edges, so the room or the environment shows around it. */
    override fun paintBackground(c: PanelCanvas) {
        paint.shader = LinearGradient(0f, 0f, 0f, c.h, intArrayOf(Color.argb(70, 12, 14, 18), Color.argb(120, 12, 14, 18), Color.argb(70, 12, 14, 18)), null, Shader.TileMode.CLAMP)
        c.canvas.drawRoundRect(RectF(0f, 0f, c.w, c.h), 56f, 56f, paint)
        paint.shader = null
    }

    private fun inSection(app: AppEntry): Boolean = when (section) {
        Section.ALL -> true
        Section.APPS -> app.category != AppCategory.HYPERXR_GAME && app.category != AppCategory.OPENXR && app.category != AppCategory.CARDBOARD && app.category != AppCategory.ENVIRONMENT
        Section.GAMES -> app.category == AppCategory.HYPERXR_GAME || app.category == AppCategory.OPENXR || app.category == AppCategory.CARDBOARD
        Section.WORLDS -> app.category == AppCategory.ENVIRONMENT
        Section.INSTALLED -> app.kind == AppKind.ANDROID_PACKAGE || app.kind == AppKind.HYPERX_PACKAGE ||
            (app.category == AppCategory.HYPERXR_GAME && com.hyperxr.launcher.GameInstalls.installed(app.id))
    }

    private fun list(): List<AppEntry> {
        val q = search.text.trim()
        var apps = a.allApps().filter { inSection(it) && (q.isEmpty() || it.title.contains(q, ignoreCase = true)) }
        apps = when (filter) {
            Filter.ALL -> apps
            Filter.PINNED -> apps.filter { a.isPinned(it) }
            Filter.RUNNING -> apps.filter { a.isRunning(it) }
        }
        return when (order) {
            Order.RECENT -> {
                // pinned first (there is no dock: pinning puts an app at the top), then the most
                // recently opened, then the catalog order
                val rec = com.hyperxr.HyperXRApp.instance.settings.current.launcher.recents
                apps.sortedWith(compareBy({ if (a.isPinned(it)) 0 else 1 }, { rec.indexOf(it.id).let { i -> if (i < 0) Int.MAX_VALUE else i } }))
            }
            Order.AZ -> apps.sortedBy { it.title.lowercase() }
            Order.ZA -> apps.sortedByDescending { it.title.lowercase() }
        }
    }

    override fun draw(c: PanelCanvas) {
        val apps = list()
        // title and search, centred
        centred(c, "Biblioteca", 34f, 64f, false)
        val active = search.active
        val sText = if (active) search.text + "|" else search.text.ifEmpty { "Pesquisar" }
        val sw = (sText.length * 16f + 90f).coerceIn(200f, 520f)
        val sr = RectF(c.w / 2 - sw / 2, 122f, c.w / 2 + sw / 2, 176f)
        if (active || isHovered("search")) { paint.color = Color.argb(if (active) 70 else 40, 255, 255, 255); c.canvas.drawRoundRect(sr, 27f, 27f, paint) }
        c.canvas.drawBitmap(Glyphs.get("search"), null, RectF(sr.left + 22f, sr.top + 13f, sr.left + 50f, sr.top + 41f), bmp)
        c.label(sText, sr.left + 62f, sr.top + 11f, 27f, Color.WHITE, maxWidth = sw - 80f, maxLines = 1)
        addRegion(Region(sr, "search", { if (!search.active) { search.begin(search.text); a.openKeyboard(search) } else search.cancel(); dirty = true }))

        // the sections on the left: a white pill marks the one you're in
        val lx = 60f; var ly = 250f
        for (s in Section.entries) {
            val id = "sec:${s.name}"
            val r = RectF(lx, ly, lx + 330f, ly + 72f)
            val sel = s == section
            if (sel) { paint.color = Color.argb(240, 255, 255, 255); c.canvas.drawRoundRect(r, 36f, 36f, paint) }
            else if (isHovered(id)) { paint.color = Color.argb(45, 255, 255, 255); c.canvas.drawRoundRect(r, 36f, 36f, paint) }
            c.canvas.drawBitmap(Glyphs.get(s.glyph), null, RectF(r.left + 22f, r.top + 20f, r.left + 54f, r.top + 52f), if (sel) darkGlyph else bmp)
            c.label(s.label, r.left + 74f, r.top + 19f, 29f, if (sel) DARK else Color.WHITE, maxWidth = 240f, maxLines = 1)
            addRegion(Region(r, id, { section = s; row = 0; dirty = true }))
            ly += 80f
        }
        paint.color = Color.argb(60, 255, 255, 255)
        c.canvas.drawRect(lx + 16f, ly + 12f, lx + 316f, ly + 14f, paint)
        ly += 40f
        // the store, with its coloured square
        val st = RectF(lx, ly, lx + 330f, ly + 72f)
        if (isHovered("store")) { paint.color = Color.argb(45, 255, 255, 255); c.canvas.drawRoundRect(st, 36f, 36f, paint) }
        paint.color = Color.rgb(245, 150, 30)
        val sq = RectF(st.left + 18f, st.top + 16f, st.left + 58f, st.top + 56f)
        c.canvas.drawRoundRect(sq, 10f, 10f, paint)
        c.canvas.drawBitmap(Glyphs.get("storefront"), null, RectF(sq.left + 7f, sq.top + 7f, sq.right - 7f, sq.bottom - 7f), bmp)
        c.label("Loja", st.left + 74f, st.top + 19f, 29f, Color.WHITE, maxWidth = 240f, maxLines = 1)
        addRegion(Region(st, "store", { a.openTarget("loja") }))

        // filter and order on the right: each tap moves to the next choice
        val rx = c.w - 400f
        dropdown(c, "filter", "filter_alt", "Filtro: " + filter.label, rx, 250f) { filter = Filter.entries[(filter.ordinal + 1) % Filter.entries.size]; row = 0 }
        dropdown(c, "order", "sort", "Ordenar: " + order.label, rx, 330f) { order = Order.entries[(order.ordinal + 1) % Order.entries.size]; row = 0 }

        // the grid: five icons a row; the row under the last full one shows faded (there is more)
        val cols = 5; val cell = 176f; val icon = 118f
        val gx0 = (c.w - cols * cell) / 2; val gy0 = 238f; val rowH = 196f
        val rows = (apps.size + cols - 1) / cols
        row = row.coerceIn(0, maxOf(0, rows - 3))
        if (apps.isEmpty()) {
            val msg = if (section == Section.GAMES) "Nenhum jogo baixado. Abra a Loja para baixar." else "Nada encontrado"
            centred(c, msg, gy0 + 60f, 30f, false, dimText)
        }
        for (k in 0 until 4) {
            val r0 = row + k
            for (cIdx in 0 until cols) {
                val i = r0 * cols + cIdx
                if (i >= apps.size) break
                val x = gx0 + cIdx * cell + (cell - icon) / 2
                val y = gy0 + k * rowH
                drawIcon(c, apps[i], x, y, icon, faded = k == 3)
            }
        }
        // the faded row scrolls down; above the grid, back up
        if (rows > row + 3) {
            val fr = RectF(gx0, gy0 + 3 * rowH - 10f, gx0 + cols * cell, c.h)
            addRegion(Region(fr, "down", { row++; dirty = true }))
        }
        if (row > 0) {
            val ur = RectF(c.w / 2 - 40f, gy0 - 44f, c.w / 2 + 40f, gy0 - 6f)
            paint.color = Color.argb(if (isHovered("up")) 90 else 50, 255, 255, 255)
            c.canvas.drawRoundRect(ur, 19f, 19f, paint)
            c.canvas.save(); c.canvas.rotate(180f, ur.centerX(), ur.centerY())
            c.canvas.drawBitmap(Glyphs.get("expand_more"), null, RectF(ur.centerX() - 16f, ur.centerY() - 16f, ur.centerX() + 16f, ur.centerY() + 16f), bmp)
            c.canvas.restore()
            addRegion(Region(ur, "up", { row--; dirty = true }))
        }
        note?.let { centred(c, it, c.h - 52f, 24f, false, XrTheme.accent2) }
    }

    private fun centred(c: PanelCanvas, s: String, top: Float, size: Float, bold: Boolean, color: Int = Color.WHITE) {
        val t = com.hyperxr.i18n.I18n.t(s)
        paint.textSize = size; paint.typeface = if (bold) XrTheme.medium else XrTheme.regular
        val w = paint.measureText(t)
        c.label(s, c.w / 2 - w / 2, top, size, color, bold = bold, maxWidth = w + 8f, maxLines = 1)
    }

    private fun dropdown(c: PanelCanvas, id: String, glyph: String, text: String, x: Float, top: Float, act: () -> Unit) {
        val r = RectF(x, top, x + 340f, top + 64f)
        if (isHovered(id)) { paint.color = Color.argb(45, 255, 255, 255); c.canvas.drawRoundRect(r, 32f, 32f, paint) }
        c.canvas.drawBitmap(Glyphs.get(glyph), null, RectF(r.left + 16f, r.top + 16f, r.left + 48f, r.top + 48f), bmp)
        c.label(text, r.left + 64f, r.top + 16f, 26f, Color.WHITE, maxWidth = 220f, maxLines = 1)
        c.canvas.drawBitmap(Glyphs.get("expand_more"), null, RectF(r.right - 50f, r.top + 16f, r.right - 18f, r.top + 48f), bmp)
        addRegion(Region(r, id, { act(); dirty = true }))
    }

    /** An app: its rounded icon with the name under it (two lines at most) and a pin when it is pinned to the top. */
    private fun drawIcon(c: PanelCanvas, app: AppEntry, x: Float, y: Float, s: Float, faded: Boolean) {
        val id = "app:${app.id}"
        val hover = !faded && isHovered(id); val press = !faded && isPressed(id)
        val g = if (press) -5f else if (hover) 6f else 0f
        val r = RectF(x - g, y - g, x + s + g, y + s + g)
        bmp.alpha = if (faded) 90 else 255
        c.canvas.save()
        c.canvas.clipPath(Path().apply { addRoundRect(r, r.width() * 0.22f, r.width() * 0.22f, Path.Direction.CW) })
        c.canvas.drawBitmap(a.appIcon(app, 192), null, r, bmp)
        c.canvas.restore()
        bmp.alpha = 255
        if (faded) return
        if (hover) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = Color.WHITE
            c.canvas.drawRoundRect(r, r.width() * 0.22f, r.width() * 0.22f, paint); paint.style = Paint.Style.FILL
        }
        // the name, centred under the icon
        val name = com.hyperxr.i18n.I18n.t(app.title)
        val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 25f; typeface = XrTheme.medium; color = Color.WHITE }
        val lw = 170f
        val layout = android.text.StaticLayout.Builder.obtain(name, 0, name.length, tp, lw.toInt())
            .setAlignment(android.text.Layout.Alignment.ALIGN_CENTER).setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).build()
        c.canvas.save(); c.canvas.translate(x + s / 2 - lw / 2, y + s + 12f); layout.draw(c.canvas); c.canvas.restore()
        if (a.isPinned(app)) {
            val py = y + s + 16f + layout.height
            c.canvas.drawBitmap(Glyphs.get("push_pin"), null, RectF(x + s / 2 - 10f, py, x + s / 2 + 10f, py + 20f), bmp)
        }
        addRegion(Region(RectF(x - 20f, y - 10f, x + s + 20f, y + s + 70f), id, { a.launch(app) }, {
            a.togglePin(app)
            note = if (a.isPinned(app)) "${app.title} fixado no topo" else "${app.title} solto do topo"; noteT = 2.5f; dirty = true
        }))
    }
}

/**
 * The Meta interface's bar: round buttons on a clear background, and no dock (pinned apps go to
 * the top of the library). On the left the environment (VR / mixed reality) and the camera; in the
 * middle the HyperMagic logo (Loja), the library in a white disc and the windows; on the right the
 * notifications, Ajustes rápidos and your avatar. Under it the Wi-Fi, the time and the battery.
 */
class MetaDockPanel(private val a: LauncherActions) : XrPanel(1.5f, 0.2f, title = "") {
    init { closable = false; priority = 0 }
    private var minute = -1L
    private var key = ""
    private var battery = ""
    private var wifi = true
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val darkGlyph = tinted(DARK)
    private var logo: Bitmap? = null

    override fun update(dt: Float) {
        val m = System.currentTimeMillis() / 60_000
        val k = "${a.windowCount()}/${a.windowsMinimized()}/${a.vrOn()}/${com.hyperxr.social.Social.unreadTotal}/${a.libraryOpen()}/${a.voiceStatus()}"
        if (m != minute || k != key) {
            minute = m; key = k; battery = batteryText(); dirty = true
            wifi = runCatching {
                val cm = com.hyperxr.HyperXRApp.instance.getSystemService(android.net.ConnectivityManager::class.java)
                cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) == true
            }.getOrDefault(false)
        }
    }

    /** No bar: the buttons float. */
    override fun paintBackground(c: PanelCanvas) {}

    /** The HyperMagic app icon (night-blue disc with the logo), made once from its two layers. */
    private fun logo(): Bitmap = logo ?: run {
        val ctx = com.hyperxr.HyperXRApp.instance
        fun layer(n: String) = ctx.resources.getIdentifier(n, "drawable", ctx.packageName).takeIf { it != 0 }
            ?.let { android.graphics.BitmapFactory.decodeResource(ctx.resources, it, android.graphics.BitmapFactory.Options().apply { inScaled = false }) }
        val out = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val cv = android.graphics.Canvas(out)
        cv.clipPath(Path().apply { addCircle(128f, 128f, 128f, Path.Direction.CW) })
        val full = RectF(0f, 0f, 256f, 256f)
        layer("ic_hx_logo_bg")?.let { cv.drawBitmap(it, null, full, bmp) } ?: cv.drawColor(Color.rgb(16, 20, 40))
        // the foreground has the adaptive icon's margins: drawn a little larger to fill the disc
        layer("ic_hx_logo_fg")?.let { cv.drawBitmap(it, null, RectF(-28f, -28f, 284f, 284f), bmp) }
        out
    }.also { logo = it }

    override fun draw(c: PanelCanvas) {
        hovered = ""
        val s = S; val gap = GAP; val cy = 20f
        val voice = a.voiceStatus()
        // left group, centre group (with the pinned apps), right group
        val leftW = 2 * s + gap
        val centreN = 3
        val centreW = centreN * s + (centreN - 1) * gap
        val rightW = 3 * s + 2 * gap
        // the groups sit at the ends, the left one as far in as the right one is wide
        val left = EDGE + (rightW - leftW)
        val right = c.w - EDGE - rightW
        val centre = (c.w - centreW) / 2
        round(c, "vr", left, cy, s, Glyphs.get(if (a.vrOn()) "landscape" else "vrpano"), if (a.vrOn()) "Realidade mista" else "Modo VR") { a.setVr(!a.vrOn()) }
        round(c, "camera", left + s + gap, cy, s, Glyphs.get("photo_camera"), "Câmera") { a.openTarget("share") }
        var x = centre
        round(c, "home", x, cy, s, null, "Loja", image = logo(), fill = true) { a.openTarget("loja") }; x += s + gap
        // the library: a white disc with the grid in dark
        val lr = RectF(x, cy, x + s, cy + s)
        val lh = isHovered("lib")
        paint.color = if (lh) Color.WHITE else Color.argb(240, 245, 246, 248)
        val grow = if (isPressed("lib")) -4f else if (lh) 4f else 0f
        c.canvas.drawOval(RectF(lr.left - grow, lr.top - grow, lr.right + grow, lr.bottom + grow), paint)
        c.canvas.drawBitmap(Glyphs.get("apps"), null, RectF(lr.left + 24f, lr.top + 24f, lr.right - 24f, lr.bottom - 24f), darkGlyph)
        addRegion(Region(lr, "lib", { a.toggleLibrary(); dirty = true }))
        x += s + gap
        round(c, "win", x, cy, s, Glyphs.get("dashboard"), if (a.windowsMinimized()) "Mostrar janelas" else "Janelas", active = a.windowCount() > 0 && !a.windowsMinimized()) {
            if (a.windowCount() > 0) a.toggleWindows()
        }

        round(c, "bell", right, cy, s, Glyphs.get("notifications"), "Notificações", badge = com.hyperxr.social.Social.unreadTotal) { a.openTarget("messages") }
        round(c, "quick", right + s + gap, cy, s, Glyphs.get("tune"), "Ajustes rápidos") { a.openTarget("quick") }
        // the avatar
        val ax = right + 2 * (s + gap)
        val me = com.hyperxr.social.Social.me
        c.avatar(me, ax + 4f, cy + 4f, s - 8f, online = me != null)
        if (isHovered("lib")) hovered = "Biblioteca"
        if (isHovered("me")) { hovered = "Pessoas"; paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = Color.WHITE; c.canvas.drawCircle(ax + s / 2, cy + s / 2, s / 2, paint); paint.style = Paint.Style.FILL }
        addRegion(Region(RectF(ax, cy, ax + s, cy + s), "me", { a.openTarget("people") }))
        // status line: Wi-Fi, time, battery (or what the voice heard)
        val sy = cy + s + 22f
        if (voice.isNotEmpty()) { label(c, voice, c.w / 2, sy, XrTheme.accent2); return }
        if (hovered.isNotEmpty()) { label(c, hovered, c.w / 2, sy, Color.WHITE); return }
        val time = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        val bat = battery.substringBefore(' ')
        paint.textSize = 24f; paint.typeface = XrTheme.regular
        val tw = paint.measureText(time); val bw = paint.measureText(bat)
        val total = 26f + 14f + tw + 26f + 26f + 10f + bw
        var sx = c.w / 2 - total / 2
        c.canvas.drawBitmap(Glyphs.get(if (wifi) "wifi" else "wifi_off"), null, RectF(sx, sy + 2f, sx + 26f, sy + 28f), bmp); sx += 40f
        c.label(time, sx, sy, 24f, dimText, maxLines = 1, maxWidth = tw + 6f); sx += tw + 26f
        c.canvas.drawBitmap(Glyphs.get("battery_full"), null, RectF(sx, sy + 2f, sx + 26f, sy + 28f), bmp); sx += 36f
        c.label(bat, sx, sy, 24f, dimText, maxLines = 1, maxWidth = bw + 6f)
    }

    private fun label(c: PanelCanvas, s: String, cx: Float, top: Float, color: Int) {
        paint.textSize = 26f; paint.typeface = XrTheme.regular
        val w = paint.measureText(com.hyperxr.i18n.I18n.t(s)).coerceAtMost(c.w - 40f)
        c.label(s, cx - w / 2, top, 26f, color, maxLines = 1, maxWidth = w + 8f)
    }

    /** A round button: a soft dark disc (lighter when pointed at) with a glyph or a picture; pointing at it puts its [name] on the status line. */
    private fun round(c: PanelCanvas, id: String, x: Float, y: Float, s: Float, glyph: Bitmap?, name: String, image: Bitmap? = null,
                      active: Boolean = false, badge: Int = 0, fill: Boolean = false, act: () -> Unit) {
        val hover = isHovered(id); val press = isPressed(id)
        val g = if (press) -4f else if (hover) 4f else 0f
        val r = RectF(x - g, y - g, x + s + g, y + s + g)
        paint.color = if (hover) Color.argb(150, 90, 96, 106) else Color.argb(90, 40, 44, 50)
        c.canvas.drawOval(r, paint)
        if (image != null && fill) {
            c.canvas.drawBitmap(image, null, r, bmp)
            if (hover) { paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = Color.WHITE; c.canvas.drawOval(r, paint); paint.style = Paint.Style.FILL }
        } else if (glyph != null) {
            val k = r.width() * 0.28f
            c.canvas.drawBitmap(glyph, null, RectF(r.left + k, r.top + k, r.right - k, r.bottom - k), bmp)
        }
        if (active) { paint.color = Color.WHITE; c.canvas.drawCircle(x + s / 2, y + s + 8f, 4f, paint) }
        if (badge > 0) {
            paint.color = XrTheme.danger; c.canvas.drawCircle(r.right - 10f, r.top + 10f, 14f, paint)
            c.label(if (badge > 9) "9+" else "$badge", r.right - (if (badge > 9) 22f else 15f), r.top - 4f, 18f, Color.WHITE, bold = true)
        }
        addRegion(Region(RectF(x - 8f, y - 8f, x + s + 8f, y + s + 8f), id, { act(); dirty = true }))
        if (hover) hovered = name
    }

    private var hovered = ""

    private companion object {
        const val S = 88f; const val GAP = 26f; const val EDGE = 70f
    }
}
