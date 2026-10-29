package com.hyperxr.rendering

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import com.hyperxr.launcher.IconLibrary

/**
 * Store covers (16:9) made from an app's own icon layers: its gradient stretched as the backdrop
 * with a soft light, the glyph large on the right and the title in bold on the left. Apps without
 * an icon get a colour from their name. Nothing here is someone else's art.
 */
object CoverArt {
    private val cache = HashMap<String, Bitmap>()

    fun cover(ctx: Context, key: String?, title: String, w: Int = 640, h: Int = 360): Bitmap = synchronized(cache) {
        cache.getOrPut("$key|$title|$w") { draw(ctx, key, title, w, h) }
    }

    private fun draw(ctx: Context, key: String?, title: String, w: Int, h: Int): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        // games with their own logo art (tools/make_logos.py)
        if (key != null) decode(ctx, "cover_$key")?.let { art -> c.drawBitmap(art, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), p); return out }
        val provided = IconLibrary.isProvided(key)
        val bg = if (provided) decode(ctx, IconLibrary.bgResource(key!!)) else null
        if (bg != null) c.drawBitmap(bg, null, RectF(0f, 0f, w.toFloat(), h.toFloat()), p)
        else {
            val hue = ((title.hashCode() and 0x7fffffff) % 360).toFloat()
            val a = Color.HSVToColor(floatArrayOf(hue, 0.65f, 0.85f))
            val b = Color.HSVToColor(floatArrayOf((hue + 40f) % 360f, 0.8f, 0.35f))
            p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), a, b, Shader.TileMode.CLAMP)
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
            p.shader = null
        }
        // light from the upper right and a darker base for the title
        p.shader = RadialGradient(w * 0.78f, h * 0.2f, w * 0.7f, Color.argb(90, 255, 255, 255), Color.TRANSPARENT, Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = LinearGradient(0f, h * 0.45f, 0f, h.toFloat(), Color.TRANSPARENT, Color.argb(170, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), p)
        p.shader = null
        val fg = if (provided) decode(ctx, IconLibrary.fgResource(key!!)) else null
        if (fg != null) {
            val s = h * 1.05f
            c.save(); c.rotate(-8f, w * 0.75f, h * 0.45f)
            c.drawBitmap(fg, null, RectF(w - s * 0.82f, -h * 0.05f, w - s * 0.82f + s, -h * 0.05f + s), p)
            c.restore()
        }
        // title: up to two lines, bold with a shadow
        val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
            textSize = h * 0.16f; setShadowLayer(h * 0.03f, 0f, h * 0.01f, Color.argb(200, 0, 0, 0))
        }
        val t = title.uppercase()
        val layout = android.text.StaticLayout.Builder.obtain(t, 0, t.length, tp, (w * 0.62f).toInt())
            .setMaxLines(2).setEllipsize(android.text.TextUtils.TruncateAt.END).setLineSpacing(0f, 0.9f).build()
        c.save(); c.translate(w * 0.06f, h * 0.92f - layout.height); layout.draw(c); c.restore()
        return out
    }

    private fun decode(ctx: Context, name: String): Bitmap? {
        val id = ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
        if (id == 0) return null
        return BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inScaled = false })
    }
}
