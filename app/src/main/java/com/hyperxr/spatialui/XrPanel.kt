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

/** HyperXR visual identity for spatial panels: deep indigo glass with violet/pink accents. */
object XrTheme {
    // Neutral graphite like Horizon OS panels: flat translucent grey glass, lighter grey controls,
    // blue only for primary/selected actions.
    val panelTop = Color.argb(240, 42, 43, 46)
    val panelBottom = Color.argb(240, 32, 33, 36)
    val border = Color.argb(46, 255, 255, 255)
    val text = Color.rgb(242, 243, 245)
    val textDim = Color.rgb(166, 169, 175)
    val accent = Color.rgb(40, 116, 240)
    val accentPressed = Color.rgb(84, 148, 255)
    val accent2 = Color.rgb(84, 148, 255)
    val button = Color.argb(255, 58, 60, 64)
    val buttonHover = Color.argb(255, 78, 80, 86)
    val buttonPressed = Color.argb(255, 104, 107, 114)
    val buttonDisabled = Color.argb(255, 44, 45, 48)
    val danger = Color.rgb(232, 72, 72)
    val ok = Color.rgb(70, 200, 130)
    /** Corner radius of panels in metres (also used by the quad shader). */
    const val PANEL_RADIUS_M = 0.045f
    /** Rounded-square controls: radius as a fraction of the control height. */
    const val CONTROL_ROUNDING = 0.32f
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
    val title: String = "",
) : InteractionSurface {
    override val surfaceId: String = javaClass.simpleName + "@" + System.identityHashCode(this)
    override val pose = Pose()
    var visible = true
    var closable = true
    /** Panels and windows are above the launcher icons for pointing (see InteractionSurface). */
    override val hitPriority: Int get() = priority
    var priority = 1
    var onClose: (() -> Unit)? = null
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

    /** Called every frame; override for animated content (then set dirty = true when it changes). */
    open fun update(dt: Float) {}

    fun isHovered(id: String) = hoverRegion?.id == id
    fun isPressed(id: String) = pressRegion?.id == id

    /** Renders when dirty and submits the quad to the frame (GL thread). */
    fun submit(scene: FrameScene) {
        if (!visible) return
        var s = surface
        if (s == null) { s = PanelSurface(pxW, pxH); surface = s }
        if (s.widthPx != pxW || s.heightPx != pxH) { s.resize(pxW, pxH); dirty = true }
        if (dirty) {
            s.begin()
            regions.clear()
            val pc = PanelCanvas(s.canvas, pxW.toFloat(), pxH.toFloat(), this, ppm)
            pc.background()
            if (title.isNotEmpty() || closable) pc.titleBar(title, closable)
            draw(pc)
            s.upload()
            dirty = false
        }
        val p = scene.addPanel()
        p.texture = s.texture
        p.pose.set(pose)
        p.width = widthMeters; p.height = heightMeters
        p.radius = XrTheme.PANEL_RADIUS_M
    }

    fun release() { surface?.release(); surface = null }

    internal fun addRegion(r: Region) { regions += r }

    private var downTimeNs = 0L
    private var longFired = false

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
                pressRegion = hit; downTimeNs = event.timestampNs; longFired = false; dirty = true
                // grab the title bar (or empty space) to move the window
                if (hit == null || hit.id == TITLE_BAR) dragging = true
            }
            PointerPhase.MOVE -> {
                val pr = pressRegion
                if (pr?.longAction != null && !longFired && event.timestampNs - downTimeNs > 600_000_000L && pr.rect.contains(px, py)) {
                    longFired = true; pr.longAction.invoke(); dirty = true
                }
                if (pr != null && !pr.rect.contains(px, py) && pr.id != TITLE_BAR) { pressRegion = null; dirty = true }
            }
            PointerPhase.UP -> {
                val pr = pressRegion
                if (pr != null && hit != null && pr.id == hit.id && !longFired) hit.action?.invoke()
                pressRegion = null; dragging = false; dirty = true
            }
            PointerPhase.CANCEL -> { pressRegion = null; dragging = false; dirty = true }
        }
    }

    val isDragging: Boolean get() = dragging

    /** Moves the window along the pointer ray while dragged, keeping it facing the user. */
    fun drag(rayOrigin: Vec3, rayDir: Vec3, head: Pose) {
        if (!dragging) return
        if (dragDistance == 0f) dragDistance = rayOrigin.distanceTo(pose.position).coerceIn(0.5f, 3f)
        val target = Vec3(rayOrigin.x + rayDir.x * dragDistance, rayOrigin.y + rayDir.y * dragDistance, rayOrigin.z + rayDir.z * dragDistance)
        pose.position.lerp(target, 0.5f)
        faceUser(head.position)
    }

    fun endDrag() { dragDistance = 0f }

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

    companion object { const val TITLE_BAR = "__title" }
}

/** Immediate-mode drawing helpers bound to one panel render. */
class PanelCanvas(val canvas: Canvas, val w: Float, val h: Float, private val panel: XrPanel, private val ppm: Float = 900f) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)
    private val bmpPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    val pad = 36f
    var y = 0f
    val titleHeight = 92f

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

    fun titleBar(title: String, closable: Boolean) {
        panel.addRegion(XrPanel.Region(RectF(0f, 0f, w - 120f, titleHeight), XrPanel.TITLE_BAR, null))
        label(title, pad, 30f, 42f, XrTheme.text, bold = true)
        // drag handle
        fill.color = Color.argb(80, 255, 255, 255)
        canvas.drawRoundRect(RectF(w / 2 - 60f, 14f, w / 2 + 60f, 22f), 4f, 4f, fill)
        if (closable) {
            val r = RectF(w - 104f, 18f, w - 24f, 76f)
            val hover = panel.isHovered("__close")
            fill.color = if (hover) XrTheme.danger else XrTheme.button
            canvas.drawRoundRect(r, r.height() * XrTheme.CONTROL_ROUNDING, r.height() * XrTheme.CONTROL_ROUNDING, fill)
            fill.color = Color.WHITE; fill.strokeWidth = 5f
            canvas.drawLine(r.centerX() - 11f, r.centerY() - 11f, r.centerX() + 11f, r.centerY() + 11f, fill)
            canvas.drawLine(r.centerX() + 11f, r.centerY() - 11f, r.centerX() - 11f, r.centerY() + 11f, fill)
            panel.addRegion(XrPanel.Region(r, "__close", { panel.visible = false; panel.onClose?.invoke() }))
        }
        y = titleHeight + 12f
    }

    /** Draws single or multi-line text; returns its height. */
    fun label(s: String, x: Float, top: Float, size: Float, color: Int = XrTheme.text, bold: Boolean = false, maxWidth: Float = w - x - pad): Float {
        text.textSize = size; text.color = color; text.typeface = if (bold) XrTheme.medium else XrTheme.regular
        val layout = StaticLayout.Builder.obtain(s, 0, s.length, text, maxWidth.toInt().coerceAtLeast(10))
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).setIncludePad(false).build()
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
        canvas.drawText(TextUtilsEllipsize(s, text, bw - (tx - x) - 20f), tx, ty, text)
        if (enabled) panel.addRegion(XrPanel.Region(r, id, action, onLong))
    }

    /** Full-width button at the cursor. */
    fun row(id: String, s: String, accent: Boolean = false, icon: Bitmap? = null, enabled: Boolean = true, detail: String? = null, action: () -> Unit) {
        val bh = if (detail != null) 110f else 84f
        button(id, s, pad, y, w - 2 * pad, bh, accent, icon, enabled, null, action)
        if (detail != null) {
            text.textSize = 24f; text.typeface = XrTheme.regular; text.color = XrTheme.textDim
            val tx = pad + 26f + (if (icon != null) bh - 20f else 0f)
            canvas.drawText(TextUtilsEllipsize(detail, text, w - 2 * pad - (tx - pad) - 20f), tx, y + bh - 18f, text)
        }
        y += bh + 14f
    }

    fun toggle(id: String, s: String, value: Boolean, onChange: (Boolean) -> Unit) {
        row(id, s + if (value) "   ●  ligado" else "   ○  desligado", accent = value) { onChange(!value) }
    }

    fun chip(s: String, x: Float, top: Float, color: Int): Float {
        text.textSize = 24f; text.typeface = XrTheme.medium
        val tw = text.measureText(s)
        fill.color = color
        canvas.drawRoundRect(RectF(x, top, x + tw + 28f, top + 40f), 12f, 12f, fill)
        text.color = Color.WHITE
        canvas.drawText(s, x + 14f, top + 28f, text)
        return tw + 28f
    }

    fun image(b: Bitmap, x: Float, top: Float, iw: Float, ih: Float) = canvas.drawBitmap(b, null, RectF(x, top, x + iw, top + ih), bmpPaint)

    fun divider() {
        fill.color = Color.argb(40, 255, 255, 255)
        canvas.drawRect(pad, y, w - pad, y + 2f, fill)
        y += 18f
    }

    companion object {
        fun TextUtilsEllipsize(s: String, p: TextPaint, max: Float): String =
            android.text.TextUtils.ellipsize(s, p, max.coerceAtLeast(10f), android.text.TextUtils.TruncateAt.END).toString()
    }
}
