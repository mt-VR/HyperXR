package com.hyperxr.guardian

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme

/** HyperGuardian's settings: the mode, drawing the area, the warning distance. */
class GuardianPanel(private val g: HyperGuardian) : XrPanel(1.15f, 1.05f, title = "HyperGuardian") {
    private val icons = HashMap<String, Bitmap?>()
    private var t = 0f
    private var lastStatus = ""
    private var note = ""

    private fun icon(name: String): Bitmap? = icons.getOrPut(name) {
        val app = com.hyperxr.HyperXRApp.instance
        val id = app.resources.getIdentifier("gd_$name", "drawable", app.packageName)
        if (id == 0) null else BitmapFactory.decodeResource(app.resources, id)
    }

    override fun update(dt: Float) {
        t += dt
        if (t < 0.4f) return
        t = 0f
        val s = g.status + g.drawing + g.area.tracing / 10
        if (s != lastStatus) { lastStatus = s; dirty = true }
    }

    override fun draw(c: PanelCanvas) {
        c.paragraph(g.status, 28f, if (g.cameraAmount > 0.5f) XrTheme.danger else XrTheme.accent2, bold = true)
        if (note.isNotEmpty()) c.paragraph(note, 24f, XrTheme.text)

        // the mode
        val bw = (c.w - 2 * c.pad - 24f) / 3
        var x = c.pad
        for ((m, label, ic) in listOf(Triple(HyperGuardian.Mode.OFF, "Desligado", "power_settings_new"), Triple(HyperGuardian.Mode.MANUAL, "Manual", "pan_tool"), Triple(HyperGuardian.Mode.AUTO, "Automático", "sensors"))) {
            c.button("m$m", label, x, c.y, bw, 90f, accent = g.mode == m, icon = icon(ic)) { g.mode = m; note = ""; dirty = true }
            x += bw + 12f
        }
        c.y += 108f

        when (g.mode) {
            HyperGuardian.Mode.OFF -> c.paragraph("Ligue um modo para o HyperGuardian proteger você em todos os apps do HyperXR. " +
                "Ele precisa da posição do celular pelo ARCore (6DoF).", 24f)
            HyperGuardian.Mode.MANUAL -> {
                c.paragraph("Manual: você desenha a sua área no chão. Aponte para o chão com a mão e faça a pinça; contorne a área " +
                    "andando ou apontando e volte ao ponto de partida. Perto da borda aparece uma grade; fora da área, a câmera mostra o quarto até você voltar.", 24f)
                if (g.drawing) {
                    c.button("finish", "Concluir área", c.pad, c.y, 330f, 84f, accent = true, icon = icon("gesture")) {
                        note = if (g.finishDrawing()) "Área salva." else "Área pequena demais (mínimo 1 m²): desenhe de novo."
                        dirty = true
                    }
                    c.button("cancel", "Cancelar", c.pad + 346f, c.y, 240f, 84f) { g.cancelDrawing(); note = ""; dirty = true }
                    c.y += 100f
                    c.paragraph("Pontos desenhados: ${g.area.tracing}", 24f)
                } else {
                    c.button("draw", if (g.area.valid) "Desenhar de novo" else "Desenhar área", c.pad, c.y, 360f, 84f, accent = !g.area.valid, icon = icon("gesture")) {
                        g.startDrawing(); note = ""; dirty = true
                    }
                    if (g.area.valid) c.button("clear", "Apagar área", c.pad + 376f, c.y, 300f, 84f, icon = icon("delete")) { g.clearArea(); note = "Área apagada."; dirty = true }
                    c.y += 100f
                    if (g.area.valid) c.paragraph("A área fica salva. Ela vale a partir de onde o HyperXR começou: comece no mesmo lugar, olhando para a mesma direção (ou desenhe de novo).", 22f)
                }
            }
            HyperGuardian.Mode.AUTO -> {
                c.paragraph("Automático: o sensor de profundidade mapeia paredes e móveis enquanto você olha em volta. Perto de uma parede " +
                    "a grade aparece nela; mais perto, a câmera mostra o quarto.", 24f)
                c.paragraph("Proteções: seus braços, mãos e pernas não contam como obstáculo; o chão e o teto são ignorados; " +
                    "cama, sofá e mesa (até 1,1 m) são móveis baixos, em laranja, que avisam só bem perto e não puxam a câmera sozinhos, " +
                    "e não contam quando você já está ao lado deles; algo que se mexeu (pessoa, porta, animal) some do mapa quando o sensor vê através do lugar.", 22f)
                c.button("rescan", "Medir de novo", c.pad, c.y, 330f, 84f, icon = icon("sensors")) { g.resetMap(); note = "Mapa apagado: olhe em volta."; dirty = true }
                c.y += 100f
            }
        }

        // warning distance
        c.label("Aviso a partir de", c.pad, c.y + 22f, 26f, XrTheme.textDim)
        x = c.pad + 260f
        for (d in floatArrayOf(0.4f, 0.6f, 0.9f)) {
            c.button("d$d", "%.1f m".format(d).replace('.', ','), x, c.y, 150f, 72f, accent = kotlin.math.abs(g.distance - d) < 0.01f) { g.distance = d; dirty = true }
            x += 162f
        }
        c.y += 90f
    }

    override fun release() { icons.values.forEach { it?.recycle() }; icons.clear(); super.release() }
}
