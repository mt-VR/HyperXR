package com.hyperxr.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.hyperxr.launcher.IconLibrary

/**
 * Handle-based image registry shared by panels (CPU bitmaps) and 3D draws (GL textures, uploaded
 * lazily on the GL thread). Handles are what Lua scripts receive.
 */
class ImageStore {
    private class Entry(var bitmap: Bitmap?, var texture: Int = 0)
    private val entries = HashMap<Int, Entry>()
    private var next = 1

    fun add(bitmap: Bitmap): Int { val id = next++; entries[id] = Entry(bitmap); return id }
    fun bitmap(id: Int): Bitmap? = entries[id]?.bitmap
    fun size(id: Int): Pair<Int, Int> = entries[id]?.bitmap?.let { it.width to it.height } ?: (0 to 0)

    /** GL texture for a handle (GL thread only). */
    fun texture(id: Int): Int {
        val e = entries[id] ?: return 0
        if (e.texture == 0) e.bitmap?.let { e.texture = Gl.uploadBitmap(it, mipmap = true) }
        return e.texture
    }

    fun free(id: Int) {
        entries.remove(id)?.let { e -> Gl.deleteTexture(e.texture); e.bitmap?.recycle() }
    }

    fun clear() {
        entries.values.forEach { Gl.deleteTexture(it.texture); it.bitmap?.recycle() }
        entries.clear()
    }

    val count: Int get() = entries.size
}

object TextRenderer {
    private val regular = Typeface.create("sans-serif", Typeface.NORMAL)
    private val bold = Typeface.create("sans-serif-medium", Typeface.BOLD)

    /** Renders text into a tightly sized bitmap; wraps at [maxWidth] px when > 0. */
    fun render(text: String, sizePx: Float, color: Int, isBold: Boolean = false, maxWidth: Int = 0): Bitmap {
        val paint = TextPaint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG).apply {
            textSize = sizePx.coerceIn(6f, 400f)
            this.color = color
            typeface = if (isBold) bold else regular
        }
        val natural = paint.measureText(text).toInt().coerceAtLeast(1) + 2
        val width = if (maxWidth > 0) minOf(natural, maxWidth) else minOf(natural, 4096)
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setIncludePad(false)
            .setMaxLines(if (maxWidth > 0) 12 else 1)
            .build()
        val w = (0 until layout.lineCount).maxOfOrNull { kotlin.math.ceil(layout.getLineWidth(it)).toInt() }?.coerceAtLeast(1) ?: 1
        val h = layout.height.coerceAtLeast(1)
        val bmp = Bitmap.createBitmap(w + 2, h, Bitmap.Config.ARGB_8888)
        layout.draw(Canvas(bmp))
        return bmp
    }
}

/**
 * Builds launcher icon bitmaps from the central icon library (author-provided bg + fg layers),
 * applying a rounded-square mask. Apps without a provided icon get the neutral placeholder tile.
 */
class IconFactory(private val context: Context) {
    private val cache = HashMap<String, Bitmap>()

    fun icon(key: String?, size: Int = 192): Bitmap = cache.getOrPut("${key}_$size") {
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val mask = Path().apply { addRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), size * 0.22f, size * 0.22f, Path.Direction.CW) }
        c.clipPath(mask)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val dst = RectF(0f, 0f, size.toFloat(), size.toFloat())
        if (IconLibrary.isProvided(key)) {
            decode(IconLibrary.bgResource(key!!))?.let { c.drawBitmap(it, null, dst, paint) }
            decode(IconLibrary.fgResource(key))?.let { c.drawBitmap(it, null, dst, paint) }
        } else {
            c.drawBitmap(SceneRenderer.placeholderTile(size), 0f, 0f, paint)
        }
        out
    }

    private fun decode(name: String): Bitmap? {
        val id = context.resources.getIdentifier(name, "drawable", context.packageName)
        if (id == 0) return null
        return BitmapFactory.decodeResource(context.resources, id, BitmapFactory.Options().apply { inScaled = false })
    }
}

/**
 * Renders panel draw commands (from Lua `hx.ui` or Kotlin system panels) with Android Canvas and
 * uploads the result only when the panel changed.
 */
class PanelSurface(var widthPx: Int, var heightPx: Int) {
    var bitmap: Bitmap = Bitmap.createBitmap(widthPx.coerceIn(8, 4096), heightPx.coerceIn(8, 4096), Bitmap.Config.ARGB_8888)
        private set
    val canvas = Canvas(bitmap)
    var texture = 0
        private set
    private var uploaded = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val rect = RectF()
    private val clipPath = Path()

    fun resize(w: Int, h: Int) {
        if (w == widthPx && h == heightPx) return
        widthPx = w; heightPx = h
        bitmap.recycle()
        bitmap = Bitmap.createBitmap(w.coerceIn(8, 4096), h.coerceIn(8, 4096), Bitmap.Config.ARGB_8888)
        canvas.setBitmap(bitmap)
        uploaded = false
    }

    fun begin() {
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
    }

    fun rect(x: Float, y: Float, w: Float, h: Float, r: Float, g: Float, b: Float, a: Float, radius: Float) {
        paint.shader = null
        paint.xfermode = null
        paint.color = Color.argb((a * 255).toInt().coerceIn(0, 255), (r * 255).toInt().coerceIn(0, 255), (g * 255).toInt().coerceIn(0, 255), (b * 255).toInt().coerceIn(0, 255))
        rect.set(x, y, x + w, y + h)
        if (radius > 0f) canvas.drawRoundRect(rect, radius, radius, paint) else canvas.drawRect(rect, paint)
    }

    fun image(src: Bitmap, x: Float, y: Float, w: Float, h: Float, alpha: Float, radius: Float) {
        paint.color = Color.WHITE
        paint.alpha = (alpha * 255).toInt().coerceIn(0, 255)
        rect.set(x, y, x + w, y + h)
        if (radius > 0f) {
            canvas.save()
            clipPath.reset()
            clipPath.addRoundRect(rect, radius, radius, Path.Direction.CW)
            canvas.clipPath(clipPath)
            canvas.drawBitmap(src, null, rect, paint)
            canvas.restore()
        } else {
            canvas.drawBitmap(src, null, rect, paint)
        }
        paint.alpha = 255
    }

    /** Uploads to GL (GL thread). */
    fun upload() {
        if (!uploaded || texture == 0) {
            if (texture != 0) Gl.deleteTexture(texture)
            texture = Gl.uploadBitmap(bitmap)
            uploaded = true
        } else {
            android.opengl.GLES30.glBindTexture(android.opengl.GLES30.GL_TEXTURE_2D, texture)
            android.opengl.GLUtils.texSubImage2D(android.opengl.GLES30.GL_TEXTURE_2D, 0, 0, 0, bitmap)
        }
    }

    fun release() { Gl.deleteTexture(texture); texture = 0; bitmap.recycle() }

    companion object {
        @Suppress("unused")
        private val CLEAR = PorterDuffXfermode(PorterDuff.Mode.CLEAR)
    }
}
