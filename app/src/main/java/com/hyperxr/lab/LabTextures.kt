package com.hyperxr.lab

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import kotlin.math.floor
import kotlin.math.sin

/** Procedural textures for the lab (all generated on device: no third-party assets). */
object LabTextures {
    private fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 2147483647
        h = (h xor (h ushr 13)) * 1274126177
        return ((h xor (h ushr 16)) and 0xffff) / 65535f
    }

    private fun smooth(x: Float, y: Float, seed: Int): Float {
        val xi = floor(x).toInt(); val yi = floor(y).toInt()
        val fx = x - xi; val fy = y - yi
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
        val a = hash(xi, yi, seed); val b = hash(xi + 1, yi, seed); val c = hash(xi, yi + 1, seed); val d = hash(xi + 1, yi + 1, seed)
        return (a + (b - a) * sx) + ((c + (d - c) * sx) - (a + (b - a) * sx)) * sy
    }

    private fun fbm(x: Float, y: Float, seed: Int): Float {
        var v = 0f; var amp = 0.5f; var f = 1f
        repeat(4) { v += amp * smooth(x * f, y * f, seed + it); amp *= 0.5f; f *= 2f }
        return v
    }

    /**
     * Wood: long grain along U with growth-ring bands warped by noise, pores and a slight sheen
     * variation. [light] / [dark] are the early- and late-wood colours.
     */
    fun wood(w: Int, h: Int, light: Int, dark: Int, seed: Int, ringFreq: Float = 14f): Bitmap {
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val u = x.toFloat() / w; val v = y.toFloat() / h
            val warp = fbm(u * 3f, v * 6f, seed) * 2.2f
            val ring = sin((v * ringFreq + warp) * 6.2831f) * 0.5f + 0.5f
            val grain = fbm(u * 40f, v * 2f, seed + 7) * 0.35f
            val pore = if (hash(x, y, seed + 3) > 0.985f) 0.25f else 0f
            val t = (ring * 0.65f + grain - pore).coerceIn(0f, 1f)
            px[y * w + x] = lerp(light, dark, t)
        }
        return Bitmap.createBitmap(px, w, h, Bitmap.Config.ARGB_8888)
    }

    private fun lerp(a: Int, b: Int, t: Float): Int {
        val r = (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt()
        val g = (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt()
        val bl = (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
        return Color.rgb(r, g, bl)
    }

    /** Soda can label (wraps around the cylinder: U = angle). */
    fun canLabel(main: Int, accent: Int, text: String): Bitmap {
        val b = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.shader = LinearGradient(0f, 0f, 0f, 256f, intArrayOf(Color.rgb(205, 208, 214), main, main, Color.rgb(205, 208, 214)),
            floatArrayOf(0f, 0.12f, 0.88f, 1f), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, 512f, 256f, p)
        p.shader = null
        p.color = accent
        c.drawRect(0f, 150f, 512f, 166f, p)
        p.color = Color.WHITE
        p.textSize = 72f
        p.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        for (k in 0..1) c.drawText(text, 30f + k * 256f, 130f, p)
        return b
    }

    /** Domino: black body with white pips on the large faces. */
    fun domino(): Bitmap {
        val b = Bitmap.createBitmap(128, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(22, 22, 26))
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(235, 235, 230) }
        c.drawRect(12f, 126f, 116f, 130f, p)
        for ((x, y) in listOf(40f to 40f, 88f to 88f, 64f to 64f, 40f to 170f, 88f to 170f, 40f to 215f, 88f to 215f)) c.drawCircle(x, y, 10f, p)
        return b
    }

    /** Toy cube face with a letter. */
    fun toyCube(color: Int, letter: String): Bitmap {
        val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(color)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(60, 0, 0, 0)
        p.style = Paint.Style.STROKE; p.strokeWidth = 8f
        c.drawRect(6f, 6f, 122f, 122f, p)
        p.style = Paint.Style.FILL
        p.color = Color.WHITE
        p.textSize = 84f
        p.typeface = Typeface.create("sans-serif-black", Typeface.BOLD)
        p.textAlign = Paint.Align.CENTER
        c.drawText(letter, 64f, 94f, p)
        return b
    }

    /** Cutting mat on the bench: green with a 1 cm grid (gives scale, like a real workshop mat). */
    fun cuttingMat(): Bitmap {
        val b = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(38, 92, 70))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0..50) {
            p.color = if (i % 5 == 0) Color.argb(150, 230, 240, 235) else Color.argb(70, 230, 240, 235)
            p.strokeWidth = if (i % 5 == 0) 2f else 1f
            val x = i * 512f / 50f
            c.drawLine(x, 0f, x, 256f, p)
            if (i <= 25) { val y = i * 256f / 25f; c.drawLine(0f, y, 512f, y, p) }
        }
        return b
    }
}
