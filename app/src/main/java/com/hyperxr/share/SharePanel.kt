package com.hyperxr.share

import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme

/**
 * Câmera: four big tiles like a headset's camera app (mirror to other screens, take a photo,
 * record, stream live), the recent captures underneath, and the settings behind "Configurações".
 * Everything leaves HyperMagic as one 16:9 picture, without the two lenses.
 */
class SharePanel(private val share: ScreenShare, private val activity: android.app.Activity, private val onPhoto: () -> Unit = {}) : XrPanel(1.4f, 1.0f, title = "") {
    private var t = 0f
    private var saved = ""
    private var settings = false
    private var thumbs: List<android.graphics.Bitmap> = emptyList()
    private var thumbsAt = 0L
    private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)

    private var lastSummary = ""
    override fun update(dt: Float) {
        t += dt
        if (t < 0.5f) return
        t = 0f
        val s = share.summary + share.error + share.stream.clients + share.displays.count
        if (s != lastSummary) { lastSummary = s; dirty = true }
        if (System.currentTimeMillis() - thumbsAt > 3000) { thumbsAt = System.currentTimeMillis(); loadThumbs() }
    }

    private fun loadThumbs() = com.hyperxr.files.mediaIo.execute {
        val dir = java.io.File(activity.filesDir, "captures")
        val files = dir.listFiles { f -> f.name.endsWith(".jpg") }?.sortedByDescending { it.lastModified() }?.take(8) ?: emptyList()
        val list = files.mapNotNull { f ->
            android.graphics.BitmapFactory.decodeFile(f.path, android.graphics.BitmapFactory.Options().apply { inSampleSize = 8 })
        }
        if (list.size != thumbs.size) { thumbs = list; dirty = true }
    }

    private fun tile(c: PanelCanvas, id: String, label: String, glyph: String, x: Float, top: Float, w: Float, h: Float, active: Boolean, enabled: Boolean = true, action: () -> Unit) {
        val r = android.graphics.RectF(x, top, x + w, top + h)
        val hover = isHovered(id); val press = isPressed(id)
        paint.color = when {
            active -> android.graphics.Color.rgb(242, 243, 245)
            press -> XrTheme.buttonPressed
            hover -> XrTheme.buttonHover
            else -> XrTheme.button
        }
        c.canvas.drawRoundRect(r, 26f, 26f, paint)
        val g = com.hyperxr.xr.Glyphs.get(glyph)
        val gp = android.graphics.Paint(paint).apply { colorFilter = if (active) android.graphics.PorterDuffColorFilter(android.graphics.Color.rgb(30, 30, 34), android.graphics.PorterDuff.Mode.SRC_IN) else null; alpha = if (enabled) 255 else 90 }
        c.canvas.drawBitmap(g, null, android.graphics.RectF(x + 26f, top + 24f, x + 86f, top + 84f), gp)
        if (active && id == "rec") { paint.color = XrTheme.danger; c.canvas.drawCircle(x + w - 30f, top + 30f, 9f, paint) }
        c.label(label, x + 26f, top + h - 64f, 32f, if (active) android.graphics.Color.rgb(30, 30, 34) else if (enabled) XrTheme.text else XrTheme.textDim, bold = true, maxLines = 1, maxWidth = w - 40f)
        if (enabled) addRegion(Region(r, id, action))
    }

    override fun draw(c: PanelCanvas) {
        c.button("settings", if (settings) "Voltar" else "Configurações", c.w - c.pad - 280f, c.pad - 10f, 280f, 64f, icon = com.hyperxr.xr.Glyphs.get("settings")) { settings = !settings }
        if (settings) { drawSettings(c); return }
        val rec = share.recorder != null
        val streaming = share.stream.running
        val top = c.pad + 80f
        val w = (c.w - 2 * c.pad - 3 * 20f) / 4; val h = 230f
        var x = c.pad
        tile(c, "mirror", "Espelhar", "cast", x, top, w, h, share.displays.count > 0) {
            saved = if (share.displays.count > 0) "Tela externa conectada: ela já mostra a imagem 16:9." else "Conecte uma tela (HDMI/USB-C) ou use o espelhamento do Android: ela recebe a imagem 16:9 sozinha."
        }
        x += w + 20f
        tile(c, "photo", "Tirar foto", "photo_camera", x, top, w, h, false) { onPhoto(); saved = "Foto salva nas capturas"; thumbsAt = 0L }
        x += w + 20f
        tile(c, "rec", if (rec) "Gravando" else "Gravar", "videocam", x, top, w, h, rec) {
            if (rec) share.stopRecording { name -> saved = name?.let { "Salvo em Filmes/HyperMagic: $it" } ?: "A gravação falhou"; dirty = true }
            else {
                if (share.microphone && activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    activity.runOnUiThread { activity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 78) }
                saved = ""; share.startRecording()
            }
        }
        x += w + 20f
        tile(c, "live", if (streaming) "Ao vivo" else "Transmitir ao vivo", "live_tv", x, top, w, h, streaming) { if (streaming) share.stream.stop() else share.stream.start() }
        var y = top + h + 24f
        val line = when {
            streaming -> "Ao vivo: abra " + (share.stream.url() ?: "(conecte o Wi-Fi)") + " no PC, TV ou outro celular"
            share.error.isNotEmpty() -> share.error
            saved.isNotEmpty() -> saved
            share.summary.isNotEmpty() -> share.summary
            else -> ""
        }
        if (line.isNotEmpty()) { c.label(line, c.pad, y, 24f, if (share.error.isNotEmpty()) XrTheme.danger else XrTheme.accent2, maxLines = 1); y += 40f }
        c.label("Recentes", c.pad, y, 30f, XrTheme.text, bold = true)
        y += 48f
        val box = android.graphics.RectF(c.pad, y, c.w - c.pad, c.h - c.pad)
        paint.color = android.graphics.Color.argb(255, 70, 72, 76)
        c.canvas.drawRoundRect(box, 22f, 22f, paint)
        if (thumbs.isEmpty()) {
            c.label("Suas fotos e vídeos aparecerão aqui.", box.centerX() - 230f, box.centerY() - 16f, 26f, XrTheme.textDim, maxWidth = 500f)
            return
        }
        val tw = (box.width() - 5 * 16f) / 4; val th = tw * 9f / 16f
        thumbs.take(8).forEachIndexed { i, b ->
            val tx = box.left + 16f + (i % 4) * (tw + 16f); val ty = box.top + 16f + (i / 4) * (th + 16f)
            if (ty + th > box.bottom) return@forEachIndexed
            c.canvas.save()
            c.canvas.clipPath(android.graphics.Path().apply { addRoundRect(android.graphics.RectF(tx, ty, tx + tw, ty + th), 14f, 14f, android.graphics.Path.Direction.CW) })
            c.canvas.drawBitmap(b, null, android.graphics.RectF(tx, ty, tx + tw, ty + th), paint)
            c.canvas.restore()
        }
    }

    private fun drawSettings(c: PanelCanvas) {
        c.y = c.pad + 80f
        val rec = share.recorder != null
        c.label("Resolução do vídeo", c.pad, c.y, 26f, XrTheme.textDim); c.y += 40f
        var x = c.pad
        for (q in listOf(720, 1080, 1440)) {
            c.button("q$q", "${q}p", x, c.y, 160f, 70f, accent = share.quality == q, enabled = !rec) { share.quality = q }
            x += 170f
        }
        c.y += 90f
        c.toggle("mic", "Incluir o áudio do microfone na gravação", share.microphone) { share.microphone = it }
        c.toggle("steady", "Câmera estabilizada (suaviza o balanço da cabeça)", share.steady) { share.steady = it }
        val ext = if (share.externalRecordingDetector.supported) "Ao gravar a tela com outro app, gravar junto uma cópia 16:9"
        else "Cópia 16:9 da gravação de outro app (precisa do Android 15)"
        c.toggle("ext", ext, share.externalRecording16x9) { share.externalRecording16x9 = it }
        c.paragraph("Tudo sai numa imagem única em 16:9, sem as duas lentes e sem distorção. A transmissão ao vivo funciona para quem está no mesmo Wi-Fi.", 22f)
    }
}
