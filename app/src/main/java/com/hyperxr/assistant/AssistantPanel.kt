package com.hyperxr.assistant

import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import com.hyperxr.xr.UrlEditor

/** Chat with the assistant: speak or type, see the answers, download or switch the model. */
class AssistantPanel(
    private val a: Assistant,
    private val listen: () -> Unit,
    private val openKeyboard: (com.hyperxr.windows.KeyTarget) -> Unit,
    private val closeKeyboard: () -> Unit,
) : XrPanel(1.3f, 1.15f, title = "Assistente") {
    private var seen = -1
    private var showModels = false
    private val editor = UrlEditor({ text -> closeKeyboard(); a.ask(text) }, { dirty = true })

    override fun update(dt: Float) { if (a.version != seen) { seen = a.version; dirty = true } }

    override fun draw(c: PanelCanvas) {
        val model = a.activeModel()
        val dl = a.downloading
        if (showModels || (model == null && a.messages.isEmpty())) { drawModels(c, model, dl); return }

        c.label(model?.let { "IA no celular: ${it.label}" } ?: "Sem modelo: só comandos simples", c.pad, c.y, 22f, XrTheme.textDim)
        c.button("models", "Modelo e voz", c.w - c.pad - 260f, c.y - 8f, 260f, 56f) { showModels = true }
        c.y += 64f
        // conversation, newest at the bottom; older messages scroll off the top
        val bottom = c.h - 130f
        val shown = synchronized(a.messages) { a.messages.takeLast(6).toList() }
        var y = c.y
        val heights = shown.map { estimate(it.text) }
        var skip = 0
        while (heights.drop(skip).sum() > bottom - y && skip < shown.size - 1) skip++
        for (m in shown.drop(skip)) {
            val x = if (m.fromUser) c.w * 0.3f else c.pad
            val color = if (m.fromUser) XrTheme.text else XrTheme.accent2
            y += c.label(m.text, x, y, 26f, color, bold = m.fromUser, maxWidth = c.w * 0.7f - c.pad) + 18f
        }
        if (shown.isEmpty()) c.paragraph("Pergunte qualquer coisa sobre o HyperXR ou peça: \"abre o YouTube e escurece o fundo\", \"deixa mais fluido\", \"por que minha mão está pequena?\", \"salva este espaço como estudo\".", 26f)
        if (a.status.isNotEmpty()) c.label(a.status, c.pad, bottom - 36f, 24f, XrTheme.accent2)

        if (editor.active) {
            c.label(editor.text + "|", c.pad, c.h - 116f, 28f, XrTheme.text, maxWidth = c.w - 2 * c.pad - 260f)
            c.button("send", "Enviar", c.w - c.pad - 240f, c.h - 110f, 240f, 80f, accent = true) { closeKeyboard(); val t = editor.text; editor.cancel(); a.ask(t) }
        } else {
            val bw = (c.w - 2 * c.pad - 20f) / 2
            c.button("talk", "Falar", c.pad, c.h - 110f, bw, 80f, accent = true, enabled = !a.busy) { listen() }
            c.button("type", "Digitar", c.pad + bw + 20f, c.h - 110f, bw, 80f, enabled = !a.busy) { editor.begin(""); openKeyboard(editor) }
        }
    }

    private fun estimate(text: String) = 34f * (1 + text.length / 46) + 18f

    private fun drawModels(c: PanelCanvas, model: AssistantModel?, dl: AssistantModel?) {
        c.paragraph("A IA roda no próprio celular, sem internet depois do download. A voz vira texto pelo reconhecimento do Google; o texto vai para o modelo junto com o estado do HyperXR (janelas, apps, ambientes, ajustes, rastreamento).", 24f)
        val suggested = a.suggested()
        for (m in AssistantBrain.MODELS) {
            val y = c.y
            val have = a.installed(m)
            val title = m.label + (if (m == suggested) " · indicado para este celular" else "")
            c.label(title, c.pad, y, 28f, XrTheme.text, bold = true, maxWidth = c.w - 2 * c.pad - 300f)
            c.label("${m.bytes / 1_000_000} MB · ${m.note}", c.pad, y + 40f, 22f, XrTheme.textDim, maxWidth = c.w - 2 * c.pad - 300f)
            when {
                dl == m -> c.label("Baixando ${(a.progress * 100).toInt()}%", c.w - c.pad - 280f, y + 20f, 26f, XrTheme.accent2)
                have -> {
                    c.button("use:${m.id}", if (model == m) "Em uso" else "Usar", c.w - c.pad - 280f, y, 130f, 70f, accent = model == m) { a.modelId = m.id }
                    c.button("del:${m.id}", "Apagar", c.w - c.pad - 140f, y, 140f, 70f) { a.delete(m) }
                }
                else -> c.button("get:${m.id}", "Baixar", c.w - c.pad - 280f, y, 280f, 70f, accent = m == suggested, enabled = dl == null) { a.download(m) }
            }
            c.y = y + 100f
        }
        c.toggle("speak", "Ler as respostas em voz alta", a.speak) { a.speak = it }
        c.toggle("gpu", "Usar a GPU (mais rápido, disputa com o VR)", a.useGpu) { a.useGpu = it }
        c.paragraph("Modelos Qwen 2.5 (Alibaba, licença Apache 2.0) no formato MediaPipe, baixados do Hugging Face (litert-community).", 20f)
        c.button("back", "Conversar", c.w - c.pad - 280f, c.h - 110f, 280f, 80f, accent = true) { showModels = false; if (a.messages.isEmpty()) a.hello() }
    }
}
