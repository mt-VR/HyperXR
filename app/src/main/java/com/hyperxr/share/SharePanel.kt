package com.hyperxr.share

import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme

/** "Compartilhar tela": record, stream and send the 16:9 view to other screens. */
class SharePanel(private val share: ScreenShare, private val activity: android.app.Activity) : XrPanel(1.25f, 1.15f, title = "Compartilhar tela 16:9") {
    private var t = 0f
    private var saved = ""

    private var lastSummary = ""
    override fun update(dt: Float) {
        t += dt
        if (t < 0.5f) return
        t = 0f
        val s = share.summary + share.error + share.stream.clients + share.displays.count
        if (s != lastSummary) { lastSummary = s; dirty = true }
    }

    override fun draw(c: PanelCanvas) {
        c.paragraph("Tudo que sai do HyperMagic para fora sai numa imagem única em 16:9, sem as duas lentes do Cardboard e sem distorção, em alta resolução.", 24f)
        val summary = share.summary
        if (summary.isNotEmpty()) c.paragraph(summary, 28f, XrTheme.accent2, bold = true)
        if (share.error.isNotEmpty()) c.paragraph(share.error, 24f, XrTheme.danger)
        if (saved.isNotEmpty()) c.paragraph(saved, 24f, XrTheme.accent2)
        else if (share.lastPairedSave.isNotEmpty() && share.recorder == null) c.paragraph("Cópia 16:9 salva em Filmes/HyperMagic: ${share.lastPairedSave}", 24f, XrTheme.accent2)

        // record
        val rec = share.recorder != null
        c.button("rec", if (rec) "Parar e salvar" else "Gravar vídeo", c.pad, c.y, 360f, 80f, accent = !rec) {
            if (rec) share.stopRecording { name -> saved = name?.let { "Salvo em Filmes/HyperMagic: $it" } ?: "A gravação falhou"; dirty = true }
            else {
                if (share.microphone && activity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                    activity.runOnUiThread { activity.requestPermissions(arrayOf(android.Manifest.permission.RECORD_AUDIO), 78) }
                saved = ""; share.startRecording()
            }
        }
        var x = c.pad + 380f
        for (q in listOf(720, 1080, 1440)) {
            c.button("q$q", "${q}p", x, c.y, 150f, 80f, accent = share.quality == q, enabled = !rec) { share.quality = q }
            x += 160f
        }
        c.y += 100f
        c.toggle("mic", "Gravar o microfone junto", share.microphone) { share.microphone = it }
        c.toggle("steady", "Câmera estabilizada (suaviza o balanço da cabeça)", share.steady) { share.steady = it }

        // browser stream
        val streaming = share.stream.running
        c.toggle("stream", "Transmitir para o navegador (mesmo Wi-Fi)", streaming) { on -> if (on) share.stream.start() else share.stream.stop() }
        if (streaming) c.paragraph("Abra no PC, TV ou outro celular: " + (share.stream.url() ?: "conecte o Wi-Fi") + "  (720p)", 24f, XrTheme.text)

        // external recorders
        val ext = if (share.externalRecordingDetector.supported) "Ao gravar a tela com outro app, gravar junto uma cópia 16:9 (a tela continua em lentes; a cópia vai para Filmes/HyperMagic, sem microfone)"
        else "Cópia 16:9 da gravação de tela de outro app (precisa do Android 15; neste celular use Gravar vídeo)"
        c.toggle("ext", ext, share.externalRecording16x9) { share.externalRecording16x9 = it }

        c.paragraph(if (share.displays.count > 0) "Tela externa conectada: mostrando a imagem 16:9." else "Telas externas (HDMI/USB-C, Miracast ou transmissão que ofereça segunda tela) recebem a imagem 16:9 sozinhas.", 22f)
    }
}
