package com.hyperxr.catgame

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import com.hyperxr.lab.LabTextures
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Procedural textures for the house (generated on the phone: no third-party assets). */
object CatTextures {
    /** Oak floor: planks of varying tone with dark seams. */
    fun floor(): Bitmap {
        val w = 512; val h = 512
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val rnd = Random(7)
        val plankH = h / 8
        for (row in 0 until 8) {
            var x = -rnd.nextInt(200)
            while (x < w) {
                val len = 180 + rnd.nextInt(200)
                val tone = rnd.nextFloat() * 0.25f
                val light = blend(Color.rgb(196, 150, 102), Color.rgb(160, 112, 70), tone)
                val dark = blend(Color.rgb(150, 104, 62), Color.rgb(112, 74, 42), tone)
                val plank = LabTextures.wood(len.coerceAtMost(256), plankH, light, dark, rnd.nextInt(1000), ringFreq = 6f)
                c.drawBitmap(plank, null, android.graphics.Rect(x, row * plankH, x + len, (row + 1) * plankH), null)
                plank.recycle()
                val seam = Paint().apply { color = Color.argb(150, 40, 24, 12); strokeWidth = 2f }
                c.drawLine(x.toFloat(), (row * plankH).toFloat(), x.toFloat(), ((row + 1) * plankH).toFloat(), seam)
                x += len
            }
            c.drawLine(0f, (row * plankH).toFloat(), w.toFloat(), (row * plankH).toFloat(), Paint().apply { color = Color.argb(170, 40, 24, 12); strokeWidth = 2f })
        }
        return b
    }

    /** Old-fashioned wallpaper: cream with soft vertical stripes and small flowers. */
    fun wallpaper(): Bitmap {
        val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(232, 222, 196))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.color = Color.argb(40, 150, 110, 90)
        for (i in 0 until 8) c.drawRect(i * 32f, 0f, i * 32f + 12f, 256f, p)
        val rnd = Random(3)
        for (i in 0 until 16) {
            val x = (i % 4) * 64f + 26f + (i / 4 % 2) * 32f; val y = (i / 4) * 64f + 30f
            p.color = Color.rgb(176, 112, 112)
            for (k in 0 until 5) { val a = k * 1.2566f; c.drawCircle(x + cos(a) * 5, y + sin(a) * 5, 3.6f, p) }
            p.color = Color.rgb(222, 190, 120); c.drawCircle(x, y, 2.4f, p)
            p.color = Color.rgb(110, 140, 100); c.drawOval(x + 4, y + 6, x + 12, y + 10, p)
            if (rnd.nextBoolean()) c.drawOval(x - 12, y + 6, x - 4, y + 10, p)
        }
        return b
    }

    /** White kitchen tiles with grey grout. */
    fun tiles(): Bitmap {
        val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(150, 150, 146))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val rnd = Random(5)
        for (y in 0 until 4) for (x in 0 until 4) {
            val v = 236 + rnd.nextInt(14)
            p.color = Color.rgb(v, v, v - 4)
            c.drawRect(x * 32f + 1.5f, y * 32f + 1.5f, x * 32f + 30.5f, y * 32f + 30.5f, p)
        }
        return b
    }

    /** Woven fabric (sofa, cushions, rug) in [base] with a fine weave and noise. */
    fun fabric(base: Int, seed: Int): Bitmap {
        val n = 128
        val px = IntArray(n * n)
        val rnd = Random(seed)
        for (y in 0 until n) for (x in 0 until n) {
            val weave = if ((x / 2 + y / 2) % 2 == 0) 0.94f else 1.04f
            val k = weave * (0.92f + rnd.nextFloat() * 0.12f)
            px[y * n + x] = Color.rgb((Color.red(base) * k).toInt().coerceIn(0, 255), (Color.green(base) * k).toInt().coerceIn(0, 255), (Color.blue(base) * k).toInt().coerceIn(0, 255))
        }
        return Bitmap.createBitmap(px, n, n, Bitmap.Config.ARGB_8888)
    }

    /** Persian-style rug: border bands and a central medallion. */
    fun rug(): Bitmap {
        val b = Bitmap.createBitmap(512, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(128, 34, 38))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        p.style = Paint.Style.STROKE
        for ((i, col) in listOf(Color.rgb(214, 180, 120), Color.rgb(40, 50, 90), Color.rgb(214, 180, 120)).withIndex()) {
            p.color = col; p.strokeWidth = 10f
            val inset = 14f + i * 16f
            c.drawRect(inset, inset, 512 - inset, 256 - inset, p)
        }
        p.style = Paint.Style.FILL
        p.color = Color.rgb(40, 50, 90)
        val path = Path().apply { moveTo(256f, 70f); lineTo(330f, 128f); lineTo(256f, 186f); lineTo(182f, 128f); close() }
        c.drawPath(path, p)
        p.color = Color.rgb(214, 180, 120); c.drawCircle(256f, 128f, 22f, p)
        val noise = fabric(Color.rgb(128, 128, 128), 11)
        c.drawBitmap(noise, null, android.graphics.Rect(0, 0, 512, 256), Paint().apply { alpha = 40; xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.MULTIPLY) })
        noise.recycle()
        return b
    }

    /** Grandma's dress: small flowers on lavender. */
    fun dress(): Bitmap {
        val b = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(Color.rgb(150, 132, 186))
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 36) {
            val x = (i % 6) * 42f + 20f + (i / 6 % 2) * 21f; val y = (i / 6) * 42f + 20f
            p.color = Color.rgb(240, 232, 240)
            for (k in 0 until 4) { val a = k * 1.5708f + 0.4f; c.drawCircle(x + cos(a) * 4, y + sin(a) * 4, 3f, p) }
            p.color = Color.rgb(240, 200, 90); c.drawCircle(x, y, 2f, p)
        }
        return b
    }

    /** Hand-knitted cardigan: cable stitches. */
    fun knit(base: Int): Bitmap {
        val b = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(base)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(60, 0, 0, 0); strokeWidth = 3f }
        for (x in 0 until 8) for (y in 0 until 16) {
            val cx = x * 16f + 8f; val cy = y * 8f
            c.drawLine(cx - 5, cy, cx, cy + 6, p); c.drawLine(cx + 5, cy, cx, cy + 6, p)
        }
        return b
    }

    /** Book spine colours with a title band. */
    fun book(color: Int): Bitmap {
        val b = Bitmap.createBitmap(64, 128, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        c.drawColor(color)
        val p = Paint().apply { this.color = Color.argb(160, 230, 200, 120) }
        c.drawRect(0f, 20f, 64f, 26f, p); c.drawRect(0f, 100f, 64f, 106f, p)
        return b
    }

    private fun blend(a: Int, b: Int, t: Float) = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt())
}
