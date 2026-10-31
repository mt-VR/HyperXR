package com.hyperxr.spatialui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.hyperxr.input.InteractionSurface
import com.hyperxr.input.PointerEvent
import com.hyperxr.input.PointerPhase
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.rendering.FrameScene
import com.hyperxr.rendering.PanelSurface

/**
 * HyperXR's look for spatial panels, like a headset's system panels: deep teal-slate glass,
 * lighter rounded tiles, pill buttons, white text, blue only for what is on or selected, and the
 * window bar (title, close, minimise) along the bottom edge with a grab handle under it.
 */
object XrTheme {
    // the current style's colours and shapes (Settings > Launcher > Tema); set by [apply]
    var panelTop = Color.argb(246, 61, 90, 99)
    var panelBottom = Color.argb(246, 47, 72, 80)
    var bar = Color.argb(250, 40, 61, 68)
    var border = Color.argb(40, 255, 255, 255)
    var text = Color.rgb(246, 249, 250)
    var textDim = Color.rgb(184, 201, 206)
    var accent = Color.rgb(52, 132, 246)
    var accentPressed = Color.rgb(96, 160, 255)
    var accent2 = Color.rgb(120, 182, 255)
    var button = Color.argb(255, 76, 103, 111)
    var buttonHover = Color.argb(255, 92, 121, 129)
    var buttonPressed = Color.argb(255, 112, 141, 149)
    var buttonDisabled = Color.argb(255, 60, 84, 92)
    var tile = Color.argb(255, 74, 101, 109)
    var danger = Color.rgb(236, 84, 84)
    var ok = Color.rgb(76, 206, 140)
    /** Corner radius of panels in metres (also used by the quad shader). */
    var PANEL_RADIUS_M = 0.04f
    /** Controls are pills: radius as a fraction of the control height. */
    var CONTROL_ROUNDING = 0.5f
    /** Rounded tiles (cards, big toggles): radius in pixels at 900 px per metre. */
    var TILE_RADIUS = 22f
    /** Bumped when the style changes: panels redraw themselves. */
    @Volatile var version = 0; private set
    private var style: com.hyperxr.settings.XrThemeStyle? = null

    /** Switches to [s] (cheap when it is already the current one: call it every frame). */
    fun apply(s: com.hyperxr.settings.XrThemeStyle) {
        if (s == style) return
        style = s
        val p = s.palette
        panelTop = p.panelTop; panelBottom = p.panelBottom; bar = p.bar; border = p.border
        text = p.text; textDim = p.textDim; accent = p.accent; accentPressed = p.accentPressed; accent2 = p.accent2
        button = p.button; buttonHover = p.buttonHover; buttonPressed = p.buttonPressed; buttonDisabled = p.buttonDisabled
        tile = p.tile; danger = p.danger; ok = p.ok
        PANEL_RADIUS_M = p.panelRadiusM; CONTROL_ROUNDING = p.controlRounding; TILE_RADIUS = p.tileRadius
        version++
    }
    val regular: Typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    val medium: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}

/**
 * A spatial window rendered with Android Canvas. Subclasses implement [draw] in immediate mode,
 * registering clickable regions through [PanelCanvas.button] etc. The texture is re-uploaded only
 * when the panel is dirty.
 */
abstract class XrPanel(
    override var widthMeters: Float,
    override var heightMeters: Float,
    private val ppm: Float = 900f,
    var title: String = "",
) : InteractionSurface {
    override val surfaceId: String = javaClass.simpleName + "@" + System.identityHashCode(this)
    override val pose = Pose()
    override val acceptsGrab: Boolean get() = true
    var visible = true
    var closable = true
    /** Panels and windows are above the launcher icons for pointing (see InteractionSurface). */
    override val hitPriority: Int get() = priority
    var priority = 1
    var onClose: (() -> Unit)? = null
    val pixelsPerMeter: Float get() = ppm
    val pxW get() = (widthMeters * ppm).toInt()
    val pxH get() = (heightMeters * ppm).toInt()
    private var surface: PanelSurface? = null
    @Volatile var dirty = true
    private val regions = ArrayList<Region>()
    private var hoverRegion: Region? = null
    private var pressRegion: Region? = null
    private var dragging = false
    private val dragOffset = Vec3()
    private var dragDistance = 0f
    var hoverPx = -1f; var hoverPy = -1f
    var lastPointerPhase: PointerPhase? = null

    class Region(val rect: RectF, val id: String, val action: (() -> Unit)?, val longAction: (() -> Unit)? = null)

    protected abstract fun draw(c: PanelCanvas)

    /** The panel's backdrop; apps with their own look (YouTube XR) paint their own. */
    protected open fun paintBackground(c: PanelCanvas) = c.background()

    /** Called every frame; override for animated content (then set dirty = true when it changes). */
    open fun update(dt: Float) {}

    fun isHovered(id: String) = hoverRegion?.id == id
    fun isPressed(id: String) = pressRegion?.id == id

    /** Called by the minimise button of the window bar (null: no minimise button). */
    var onMinimize: (() -> Unit)? = null
    /** Whether the panel has the window bar along its bottom edge (title, close, minimise) and the grab handle. */
    open val windowBar: Boolean get() = closable || title.isNotEmpty()
    private var handleTex = 0
    private var langVersion = -1
    private var themeVersion = -1

    /** Renders when dirty and submits the quad to the frame (GL thread). */
    open fun submit(scene: FrameScene) {
        if (!visible) return
        var s = surface
        if (s == null) { s = PanelSurface(pxW, pxH); surface = s }
        if (s.widthPx != pxW || s.heightPx != pxH) { s.resize(pxW, pxH); dirty = true }
        if (langVersion != com.hyperxr.i18n.I18n.version) { langVersion = com.hyperxr.i18n.I18n.version; dirty = true }
        if (themeVersion != XrTheme.version) { themeVersion = XrTheme.version; dirty = true }
        if (dirty) {
            s.begin()
            renderTo(s.canvas)
            s.upload()
            dirty = false
        }
        val p = scene.addPanel()
        p.texture = s.texture
        p.pose.set(pose)
        p.width = widthMeters; p.height = heightMeters
        p.radius = XrTheme.PANEL_RADIUS_M
        if (windowBar) {
            // the grab handle: a white pill a little under the window
            if (handleTex == 0) handleTex = com.hyperxr.rendering.Gl.uploadBitmap(Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) })
            val hnd = scene.addPanel()
            hnd.texture = handleTex
            hnd.pose.set(pose)
            pose.orientation.rotate(handleOffset.set(0f, -(heightMeters / 2 + 0.032f), 0f), handleOffset)
            hnd.pose.position.add(handleOffset)
            hnd.width = (widthMeters * 0.14f).coerceIn(0.08f, 0.16f); hnd.height = 0.011f
            hnd.radius = 0.0055f
            hnd.alpha = if (dragging || isHovered(TITLE_BAR)) 1f else 0.82f
        }
    }
    private val handleOffset = Vec3()

    /** Draws the whole panel (background, window bar, content) into [canvas], pxW x pxH. Any thread that owns the panel. */
    fun renderTo(canvas: Canvas) {
        regions.clear()
        val full = PanelCanvas(canvas, pxW.toFloat(), pxH.toFloat(), this, ppm)
        paintBackground(full)
        val bar = windowBar
        if (bar) full.windowBar(title, closable, onMinimize)
        // content above the bar; it starts at the top padding
        val pc = if (bar) PanelCanvas(canvas, pxW.toFloat(), pxH.toFloat() - PanelCanvas.BAR_PX, this, ppm) else full
        pc.y = pc.pad
        draw(pc)
    }

    /** For previews: points at ([px], [py]) as if hovered, so hover looks can be checked. */
    fun hoverAt(px: Float, py: Float) { hoverPx = px; hoverPy = py; hoverRegion = regions.lastOrNull { it.rect.contains(px, py) } }

    /** GL thread. Subclasses holding players/bitmaps free them here too. */
    open fun release() { surface?.release(); surface = null; if (handleTex != 0) { com.hyperxr.rendering.Gl.deleteTexture(handleTex); handleTex = 0 } }

    internal fun addRegion(r: Region) { regions += r }

    private var downTimeNs = 0L
    private var longFired = false
    private var downPy = 0f
    private var lastPy = 0f
    private var scrolling = false

    /** Panels with more content than fits (the library, lists) scroll when dragged. */
    open val scrollable: Boolean get() = false

    /**
     * Scrolls by [dyPx] panel pixels: positive shows what is further down (the content moves up
     * with the finger). Dragging, the Joy-Con stick and the thumb sliding along the index all land here.
     */
    open fun scrollBy(dyPx: Float) {}

    override fun onPointerEvent(event: PointerEvent) {
        val px = event.u * pxW
        val py = event.v * pxH
        hoverPx = px; hoverPy = py
        lastPointerPhase = event.phase
        val hit = regions.lastOrNull { it.rect.contains(px, py) }
        when (event.phase) {
            // Regions are rebuilt on every redraw, so they are matched by id, never by identity.
            PointerPhase.HOVER -> if (hit?.id != hoverRegion?.id) { hoverRegion = hit; dirty = true } else hoverRegion = hit
            PointerPhase.EXIT -> { if (hoverRegion != null || pressRegion != null) dirty = true; hoverRegion = null; pressRegion = null }
            PointerPhase.DOWN -> {
                downTimeNs = event.timestampNs; longFired = false; dirty = true
                downPy = py; lastPy = py; scrolling = false
                when {
                    // a fist grabs the window wherever it points (and never clicks)
                    event.grab -> { pressRegion = null; dragging = true }
                    // a hand's pinch only clicks: windows no longer move by accident
                    event.source == com.hyperxr.input.PointerSource.HAND_RAY -> pressRegion = hit
                    // controller / gaze: grab the title bar (or empty space) to move the window
                    else -> { pressRegion = hit; if (hit == null || hit.id == TITLE_BAR) dragging = true }
                }
            }
            PointerPhase.MOVE -> {
                // dragged up or down past a few millimetres: the content scrolls and nothing is clicked
                if (scrollable && !dragging && pressRegion?.id != TITLE_BAR) {
                    if (!scrolling && kotlin.math.abs(py - downPy) > SCROLL_SLOP_PX) { scrolling = true; pressRegion = null; longFired = true; dirty = true }
                    if (scrolling) { scrollBy(lastPy - py); dirty = true }
                    lastPy = py
                    if (scrolling) return
                }
                val pr = pressRegion
                if (pr?.longAction != null && !longFired && event.timestampNs - downTimeNs > 600_000_000L && pr.rect.contains(px, py)) {
                    longFired = true; pr.longAction.invoke(); dirty = true
                }
                if (pr != null && !pr.rect.contains(px, py) && pr.id != TITLE_BAR) { pressRegion = null; dirty = true }
            }
            PointerPhase.UP -> {
                if (scrolling) { scrolling = false; pressRegion = null; dragging = false; dirty = true; return }
                val pr = pressRegion
                if (pr != null && hit != null && pr.id == hit.id && !longFired) hit.action?.invoke()
                pressRegion = null; dragging = false; dirty = true
            }
            PointerPhase.CANCEL -> { pressRegion = null; dragging = false; dirty = true }
        }
    }

    val isDragging: Boolean get() = dragging

    /**
     * Moves the window along the pointer ray while dragged, keeping it facing the user. With a
     * hand (or controller) ray the window also follows the hand in depth: reaching out pushes it
     * away, pulling the hand in brings it closer, so it can be put anywhere in the real room.
     * [hand] is where the hand is (the pinch point) when the ray itself starts at the eyes; a gaze
     * ray has no hand and keeps its distance.
     */
    fun drag(rayOrigin: Vec3, rayDir: Vec3, head: Pose, hand: Vec3? = null) {
        if (!dragging) return
        val reach = (hand ?: rayOrigin).distanceTo(head.position)
        if (dragDistance == 0f) {
            dragDistance = rayOrigin.distanceTo(pose.position).coerceIn(0.3f, 4f)
            dragReach = reach
        }
        val distance = (dragDistance + (reach - dragReach) * PUSH_GAIN).coerceIn(0.3f, 5f)
        val target = Vec3(rayOrigin.x + rayDir.x * distance, rayOrigin.y + rayDir.y * distance, rayOrigin.z + rayDir.z * distance)
        pose.position.lerp(target, 0.5f)
        faceUser(head.position)
    }

    private var dragReach = 0f

    fun endDrag() { dragDistance = 0f }

    /** Starts moving this panel as if it had been grabbed (a fist on the window it carries). */
    fun beginGrab() { dragging = true }

    /** Ends a grab started with [beginGrab]. */
    fun endGrab() { dragging = false; dragDistance = 0f }

    fun faceUser(eye: Vec3) {
        val dx = pose.position.x - eye.x
        val dz = pose.position.z - eye.z
        val yaw = kotlin.math.atan2(-dx, -dz)
        Quat.fromAxisAngle(0f, 1f, 0f, yaw, pose.orientation)
    }

    /** Places the window [distance] m in front of the head yaw, slightly below eye level. */
    fun placeInFront(head: Pose, distance: Float = 1.3f, yOffset: Float = -0.05f, yawOffsetDeg: Float = 0f) {
        val f = head.orientation.rotate(Vec3(0f, 0f, -1f))
        val yaw = kotlin.math.atan2(-f.x, -f.z) + Math.toRadians(yawOffsetDeg.toDouble()).toFloat()
        pose.position.set(head.position.x - kotlin.math.sin(yaw) * distance, head.position.y + yOffset, head.position.z - kotlin.math.cos(yaw) * distance)
        Quat.fromAxisAngle(0f, 1f, 0f, yaw, pose.orientation)
    }

    companion object {
        const val TITLE_BAR = "__title"
        /** How far a press must move before it scrolls instead of clicking (panel pixels, ~2.5 cm). */
        const val SCROLL_SLOP_PX = 22f
        /** Window depth change per metre of hand reach while dragging. */
        const val PUSH_GAIN = 4f
    }
}

/** Immediate-mode drawing helpers bound to one panel render. */
class PanelCanvas(val canvas: Canvas, val w: Float, val h: Float, private val panel: XrPanel, private val ppm: Float = 900f) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val pad = 36f
    var y = 0f
    /** Where content starts below the top edge (the window bar is at the bottom now). */
    val titleHeight = 24f

    fun background() {
        val r = XrTheme.PANEL_RADIUS_M * ppm
        fill.shader = LinearGradient(0f, 0f, 0f, h, XrTheme.panelTop, XrTheme.panelBottom, Shader.TileMode.CLAMP)
        canvas.drawRoundRect(RectF(0f, 0f, w, h), r, r, fill)
        fill.shader = null
        // hairline rim catches the light like the edge of a glass pane
        fill.style = Paint.Style.STROKE; fill.strokeWidth = 2f; fill.color = XrTheme.border
        canvas.drawRoundRect(RectF(1f, 1f, w - 1f, h - 1f), r - 1f, r - 1f, fill)
        fill.style = Paint.Style.FILL
        y = pad
    }

    /**
     * The window bar along the bottom edge: a darker band with the title in the middle, and the
     * close and minimise buttons as small discs on the left. Grabbing the band moves the window.
     */
    fun windowBar(title: String, closable: Boolean, onMinimize: (() -> Unit)?) {
        val top = h - BAR_PX
        val r = XrTheme.PANEL_RADIUS_M * ppm
        canvas.save()
        canvas.clipPath(android.graphics.Path().apply { addRoundRect(RectF(0f, 0f, w, h), r, r, android.graphics.Path.Direction.CW) })
        fill.color = XrTheme.bar
        canvas.drawRect(0f, top, w, h, fill)
        fill.color = Color.argb(30, 255, 255, 255)
        canvas.drawRect(0f, top, w, top + 2f, fill)
        canvas.restore()
        panel.addRegion(XrPanel.Region(RectF(0f, top, w, h), XrPanel.TITLE_BAR, null))
        if (title.isNotEmpty()) {
            text.textSize = 25f; text.typeface = XrTheme.medium; text.color = XrTheme.text
            val t = TextUtilsEllipsize(com.hyperxr.i18n.I18n.t(title), text, w - 340f)
            canvas.drawText(t, (w - text.measureText(t)) / 2, top + BAR_PX / 2 - (text.descent() + text.ascent()) / 2, text)
        }
        val d = 50f
        var x = 16f
        val cy = top + (BAR_PX - d) / 2
        fun disc(id: String, act: () -> Unit, glyph: (RectF) -> Unit) {
            val rr = RectF(x, cy, x + d, cy + d)
            val hover = panel.isHovered(id); val press = panel.isPressed(id)
            fill.color = when { press -> XrTheme.buttonPressed; hover -> if (id == "__close") XrTheme.danger else XrTheme.buttonHover; else -> XrTheme.button }
            canvas.drawOval(rr, fill)
            fill.color = Color.WHITE; fill.strokeWidth = 4f; fill.strokeCap = Paint.Cap.ROUND
            glyph(rr)
            panel.addRegion(XrPanel.Region(RectF(rr.left - 6f, top, rr.right + 6f, h), id, act))
            x += d + 12f
        }
        if (closable) disc("__close", { panel.visible = false; panel.onClose?.invoke() }) { rr ->
            canvas.drawLine(rr.centerX() - 9f, rr.centerY() - 9f, rr.centerX() + 9f, rr.centerY() + 9f, fill)
            canvas.drawLine(rr.centerX() + 9f, rr.centerY() - 9f, rr.centerX() - 9f, rr.centerY() + 9f, fill)
        }
        if (onMinimize != null) disc("__min", onMinimize) { rr -> canvas.drawLine(rr.centerX() - 10f, rr.centerY(), rr.centerX() + 10f, rr.centerY(), fill) }
        fill.strokeCap = Paint.Cap.BUTT
    }

    /**
     * A big toggle tile like a quick-settings card: rounded, lighter than the panel, an icon at
     * the top left, a title and a line under it; blue when [on].
     */
    fun tile(id: String, title: String, detail: String, x: Float, top: Float, tw: Float, th: Float, glyph: Bitmap? = null,
             on: Boolean = false, enabled: Boolean = true, action: () -> Unit) {
        val r = RectF(x, top, x + tw, top + th)
        val hover = panel.isHovered(id); val press = panel.isPressed(id)
        fill.color = when {
            !enabled -> XrTheme.buttonDisabled
            on && press -> XrTheme.accentPressed
            on -> XrTheme.accent
            press -> XrTheme.buttonPressed
            hover -> XrTheme.buttonHover
            else -> XrTheme.tile
        }
        canvas.drawRoundRect(r, XrTheme.TILE_RADIUS, XrTheme.TILE_RADIUS, fill)
        if (glyph != null) canvas.drawBitmap(glyph, null, RectF(x + 22f, top + 20f, x + 58f, top + 56f), bmpPaint)
        val ty = top + th - (if (detail.isNotEmpty()) 64f else 44f)
        label(title, x + 22f, ty, 28f, XrTheme.text, bold = true, maxWidth = tw - 40f, maxLines = 1)
        if (detail.isNotEmpty()) label(detail, x + 22f, ty + 36f, 21f, if (on) Color.argb(220, 255, 255, 255) else XrTheme.textDim, maxWidth = tw - 40f, maxLines = 1)
        if (enabled) panel.addRegion(XrPanel.Region(r, id, action))
    }

    /** A small pill with only a glyph (the quick-settings row of shortcuts); blue when [on]. */
    fun pill(id: String, glyph: Bitmap, x: Float, top: Float, pw: Float, ph: Float, on: Boolean = false, action: () -> Unit) {
        val r = RectF(x, top, x + pw, top + ph)
        val hover = panel.isHovered(id); val press = panel.isPressed(id)
        fill.color = when { on && press -> XrTheme.accentPressed; on -> XrTheme.accent; press -> XrTheme.buttonPressed; hover -> XrTheme.buttonHover; else -> XrTheme.tile }
        canvas.drawRoundRect(r, ph / 2, ph / 2, fill)
        val g = ph * 0.5f
        canvas.drawBitmap(glyph, null, RectF(r.centerX() - g / 2, r.centerY() - g / 2, r.centerX() + g / 2, r.centerY() + g / 2), bmpPaint)
        panel.addRegion(XrPanel.Region(r, id, action))
    }

    /**
     * A slider like the volume one: a pill track, the blue part up to the value and a white knob
     * with the glyph. Pointing at it and clicking sets the value where you point.
     */
    fun slider(id: String, value: Float, x: Float, top: Float, sw: Float, sh: Float, glyph: Bitmap?, onChange: (Float) -> Unit) {
        val r = RectF(x, top, x + sw, top + sh)
        fill.color = XrTheme.tile
        canvas.drawRoundRect(r, sh / 2, sh / 2, fill)
        val v = value.coerceIn(0f, 1f)
        val kx = x + sh / 2 + (sw - sh) * v
        fill.color = XrTheme.accent
        canvas.drawRoundRect(RectF(x, top, kx + sh / 2, top + sh), sh / 2, sh / 2, fill)
        val hover = panel.isHovered(id)
        fill.color = Color.WHITE
        canvas.drawCircle(kx, top + sh / 2, sh / 2 + (if (hover) 4f else 1f), fill)
        if (glyph != null) {
            val g = sh * 0.56f
            val dark = Paint(bmpPaint).apply { colorFilter = android.graphics.PorterDuffColorFilter(Color.rgb(40, 60, 68), android.graphics.PorterDuff.Mode.SRC_IN) }
            canvas.drawBitmap(glyph, null, RectF(kx - g / 2, top + sh / 2 - g / 2, kx + g / 2, top + sh / 2 + g / 2), dark)
        }
        panel.addRegion(XrPanel.Region(RectF(x, top - 10f, x + sw, top + sh + 10f), id, {
            val px = panel.hoverPx
            onChange(((px - x - sh / 2) / (sw - sh)).coerceIn(0f, 1f))
        }))
    }

    /** Draws single or multi-line text; returns its height. */
    fun label(text0: String, x: Float, top: Float, size: Float, color: Int = XrTheme.text, bold: Boolean = false, maxWidth: Float = w - x - pad,
              maxLines: Int = Int.MAX_VALUE): Float {
        val s = com.hyperxr.i18n.I18n.t(text0)
        text.textSize = size; text.color = color; text.typeface = if (bold) XrTheme.medium else XrTheme.regular
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, text, maxWidth.toInt().coerceAtLeast(10))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false)
            .setMaxLines(maxLines).setEllipsize(if (maxLines == Int.MAX_VALUE) null else android.text.TextUtils.TruncateAt.END).build()
        canvas.save(); canvas.translate(x, top); layout.draw(canvas); canvas.restore()
        return layout.height.toFloat()
    }

    /** Flowing text at the cursor. */
    fun paragraph(s: String, size: Float = 30f, color: Int = XrTheme.textDim, bold: Boolean = false) {
        y += label(s, pad, y, size, color, bold) + 14f
    }

    fun heading(s: String) { y += 8f; y += label(s, pad, y, 36f, XrTheme.text, true) + 12f }

    fun button(id: String, s: String, x: Float, top: Float, bw: Float, bh: Float = 84f, accent: Boolean = false,
               icon: Bitmap? = null, enabled: Boolean = true, onLong: (() -> Unit)? = null, action: () -> Unit) {
        val r = RectF(x, top, x + bw, top + bh)
        val hover = panel.isHovered(id); val press = panel.isPressed(id)
        fill.color = when {
            !enabled -> XrTheme.buttonDisabled
            press && accent -> XrTheme.accentPressed
            press -> XrTheme.buttonPressed
            accent -> XrTheme.accent
            hover -> XrTheme.buttonHover
            else -> XrTheme.button
        }
        val cr = bh * XrTheme.CONTROL_ROUNDING
        canvas.drawRoundRect(r, cr, cr, fill)
        if (hover && enabled && !press) {
            fill.style = Paint.Style.STROKE; fill.strokeWidth = 3f; fill.color = Color.argb(90, 255, 255, 255)
            canvas.drawRoundRect(r, cr, cr, fill)
            fill.style = Paint.Style.FILL
        }
        var tx = x + 26f
        if (icon != null) {
            val s2 = bh - 20f
            canvas.drawBitmap(icon, null, RectF(x + 10f, top + 10f, x + 10f + s2, top + 10f + s2), bmpPaint)
            tx = x + 26f + s2
        }
        text.textSize = 30f; text.typeface = XrTheme.medium; text.color = if (enabled) XrTheme.text else XrTheme.textDim
        val ty = top + bh / 2 - (text.descent() + text.ascent()) / 2
        canvas.drawText(TextUtilsEllipsize(com.hyperxr.i18n.I18n.t(s), text, bw - (tx - x) - 20f), tx, ty, text)
        if (enabled) panel.addRegion(XrPanel.Region(r, id, action, onLong))
    }

    /** Full-width button at the cursor. */
    fun row(id: String, s: String, accent: Boolean = false, icon: Bitmap? = null, enabled: Boolean = true, detail: String? = null, action: () -> Unit) {
        val bh = if (detail != null) 110f else 84f
        button(id, s, pad, y, w - 2 * pad, bh, accent, icon, enabled, null, action)
        if (detail != null) {
            text.textSize = 24f; text.typeface = XrTheme.regular; text.color = XrTheme.textDim
            val tx = pad + 26f + (if (icon != null) bh - 20f else 0f)
            canvas.drawText(TextUtilsEllipsize(com.hyperxr.i18n.I18n.t(detail), text, w - 2 * pad - (tx - pad) - 20f), tx, y + bh - 18f, text)
        }
        y += bh + 14f
    }

    fun toggle(id: String, s: String, value: Boolean, onChange: (Boolean) -> Unit) {
        row(id, s + if (value) "   ●  ligado" else "   ○  desligado", accent = value) { onChange(!value) }
    }

    fun chip(s0: String, x: Float, top: Float, color: Int): Float {
        val s = com.hyperxr.i18n.I18n.t(s0)
        text.textSize = 24f; text.typeface = XrTheme.medium
        val tw = text.measureText(s)
        fill.color = color
        canvas.drawRoundRect(RectF(x, top, x + tw + 28f, top + 40f), 12f, 12f, fill)
        text.color = Color.WHITE
        canvas.drawText(s, x + 14f, top + 28f, text)
        return tw + 28f
    }

    /**
     * App cell of a launcher grid: the icon as a rounded square, the name under it; the cell lights
     * up when hovered and shrinks a little while pressed (like the Quest library).
     */
    fun appCell(id: String, icon: Bitmap, label: String, x: Float, top: Float, cw: Float, ch: Float,
                onLong: (() -> Unit)? = null, action: () -> Unit) {
        val r = RectF(x, top, x + cw, top + ch)
        val hover = panel.isHovered(id); val press = panel.isPressed(id)
        if (hover || press) {
            fill.color = if (press) XrTheme.buttonPressed else XrTheme.buttonHover
            canvas.drawRoundRect(r, 22f, 22f, fill)
        }
        val labelH = 40f
        var s = minOf(cw - 24f, ch - labelH - 20f)
        if (press) s *= 0.94f
        val ix = x + (cw - s) / 2; val iy = top + 10f + (ch - labelH - 10f - s) / 2
        canvas.save()
        val clip = android.graphics.Path().apply { addRoundRect(RectF(ix, iy, ix + s, iy + s), s * 0.22f, s * 0.22f, android.graphics.Path.Direction.CW) }
        canvas.clipPath(clip)
        canvas.drawBitmap(icon, null, RectF(ix, iy, ix + s, iy + s), bmpPaint)
        canvas.restore()
        text.textSize = 24f; text.typeface = XrTheme.medium; text.color = XrTheme.text
        val t = TextUtilsEllipsize(com.hyperxr.i18n.I18n.t(label), text, cw - 10f)
        canvas.drawText(t, x + (cw - text.measureText(t)) / 2, top + ch - 12f, text)
        panel.addRegion(XrPanel.Region(r, id, action, onLong))
    }

    /**
     * Round button with a monochrome glyph (browser bar, Wolvic style): a soft disc that lights up
     * when hovered, the glyph dimmed when disabled.
     */
    fun roundIconButton(id: String, glyph: Bitmap, x: Float, top: Float, size: Float, enabled: Boolean = true, accent: Boolean = false, action: () -> Unit) {
        val r = RectF(x, top, x + size, top + size)
        val hover = panel.isHovered(id); val press = panel.isPressed(id)
        fill.color = when {
            !enabled -> XrTheme.buttonDisabled
            press && accent -> XrTheme.accentPressed
            press -> XrTheme.buttonPressed
            accent -> XrTheme.accent
            hover -> XrTheme.buttonHover
            else -> XrTheme.button
        }
        canvas.drawOval(r, fill)
        val inset = size * (if (press) 0.30f else 0.27f)
        val p = Paint(bmpPaint).apply { alpha = if (enabled) 255 else 90 }
        canvas.drawBitmap(glyph, null, RectF(x + inset, top + inset, x + size - inset, top + size - inset), p)
        if (enabled) panel.addRegion(XrPanel.Region(r, id, action))
    }

    /** Square button showing only an app icon (taskbar). */
    fun iconButton(id: String, icon: Bitmap, x: Float, top: Float, size: Float, active: Boolean = false, action: () -> Unit) {
        val r = RectF(x, top, x + size, top + size)
        val hover = panel.isHovered(id); val press = panel.isPressed(id)
        if (hover || press) {
            fill.color = if (press) XrTheme.buttonPressed else XrTheme.buttonHover
            canvas.drawRoundRect(r, size * 0.28f, size * 0.28f, fill)
        }
        val inset = size * (if (press) 0.16f else 0.12f)
        val ir = RectF(x + inset, top + inset, x + size - inset, top + size - inset)
        canvas.save()
        canvas.clipPath(android.graphics.Path().apply { addRoundRect(ir, ir.width() * 0.24f, ir.width() * 0.24f, android.graphics.Path.Direction.CW) })
        canvas.drawBitmap(icon, null, ir, bmpPaint)
        canvas.restore()
        if (active) {
            fill.color = XrTheme.text
            canvas.drawRoundRect(RectF(r.centerX() - 10f, r.bottom - 5f, r.centerX() + 10f, r.bottom - 1f), 2f, 2f, fill)
        }
        panel.addRegion(XrPanel.Region(r, id, action))
    }

    fun image(b: Bitmap, x: Float, top: Float, iw: Float, ih: Float) = canvas.drawBitmap(b, null, RectF(x, top, x + iw, top + ih), bmpPaint)

    fun divider() {
        fill.color = Color.argb(40, 255, 255, 255)
        canvas.drawRect(pad, y, w - pad, y + 2f, fill)
        y += 18f
    }

    companion object {
        /** Height of the window bar along the bottom edge, pixels. */
        const val BAR_PX = 78f
        fun TextUtilsEllipsize(s: String, p: TextPaint, max: Float): String =
            android.text.TextUtils.ellipsize(s, p, max.coerceAtLeast(10f), android.text.TextUtils.TruncateAt.END).toString()
    }
}
