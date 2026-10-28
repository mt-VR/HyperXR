package com.hyperxr.xr

import android.graphics.Paint
import android.graphics.RectF
import com.hyperxr.handtracking.Gesture
import com.hyperxr.handtracking.Handedness
import com.hyperxr.handtracking.PalmMenuDetector
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme

/**
 * The first time in XR: a short tutorial where each gesture is practised for real. Showing the
 * hands (they are measured meanwhile), three pinches, the palm menu and a closed hand; each step
 * goes on by itself once the gesture is seen, or can be skipped (controller users).
 */
class XrTutorialPanel(private val ctx: XrContext, private val onDone: () -> Unit) : XrPanel(1.15f, 0.66f, title = "Tutorial") {
    private enum class Step(val title: String) {
        WELCOME("Bem-vindo ao HyperXR"), HANDS("Mostre as mãos"), PINCH("Pinça"), PALM("Menu na palma"),
        FIST("Mão fechada"), DOCK("Dock e Modo VR"), DONE("Tudo pronto"),
    }
    private var step = Step.WELCOME
    private var progress = 0f
    private var pinches = 0
    private val wasPinch = BooleanArray(2)
    private var shownProgress = -1
    private var noHandsTime = 0f
    private var placed = false
    private var successFlash = 0f

    init { closable = false }

    private fun next() {
        step = Step.entries[(step.ordinal + 1).coerceAtMost(Step.entries.size - 1)]
        progress = 0f; pinches = 0; noHandsTime = 0f; successFlash = 0.8f
        placed = false
        dirty = true
    }

    override fun update(dt: Float) {
        if (!placed) { placeInFront(ctx.input.head, 1.1f, 0.02f); placed = true }
        val hands = ctx.input.hands
        val usable = hands.count { it.isUsable }
        if (usable == 0) noHandsTime += dt else noHandsTime = 0f
        if (successFlash > 0f) successFlash -= dt
        when (step) {
            Step.HANDS -> {
                // both hands (or the one the phone sees) held in view for a moment; the size is measured meanwhile
                if (usable > 0) progress += dt / (if (usable == 2) 2.5f else 4f)
                val cal = ctx.hands?.calibrationProgress ?: 1f
                if (progress >= 1f && cal >= 0.99f || progress >= 2f) next()
            }
            Step.PINCH -> {
                for (i in 0..1) {
                    val p = hands[i].isUsable && hands[i].gesture == Gesture.PINCH
                    if (p && !wasPinch[i]) { pinches++; dirty = true }
                    wasPinch[i] = p
                }
                progress = pinches / 3f
                if (pinches >= 3) next()
            }
            Step.PALM -> {
                for (i in 0..1) {
                    val h = hands[i]
                    val p = h.isUsable && h.gesture == Gesture.PINCH
                    if (p && !wasPinch[i] && PalmMenuDetector.isPalmUp(h.joints, h.handedness == Handedness.RIGHT, ctx.input.head.position)) next()
                    wasPinch[i] = p
                }
            }
            Step.FIST -> {
                val fist = hands.any { it.isUsable && (it.gesture == Gesture.FIST || it.gesture == Gesture.GRAB) }
                progress = if (fist) progress + dt else (progress - dt * 2f).coerceAtLeast(0f)
                if (progress >= 1f) next()
            }
            else -> Unit
        }
        val shown = (progress * 20).toInt() + pinches * 100 + (if (noHandsTime > 4f) 1000 else 0)
        if (shown != shownProgress) { shownProgress = shown; dirty = true }
    }

    private val bar = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(c: PanelCanvas) {
        val n = Step.entries.size
        c.label("${step.ordinal + 1} de $n", c.w - c.pad - 160f, c.pad, 26f, XrTheme.textDim, maxWidth = 160f)
        c.label(step.title, c.pad, c.pad, 46f, if (successFlash > 0f) XrTheme.accent2 else XrTheme.text, bold = true, maxWidth = c.w - 2 * c.pad - 170f)
        c.y = c.pad + 76f
        val text = when (step) {
            Step.WELCOME -> "Este tutorial curto ensina os gestos na prática. Cada passo avança sozinho quando você acerta o gesto.\n\nPara começar: aponte para o botão abaixo e junte o polegar com o indicador (pinça). Perto do painel, também dá para tocar o botão com a ponta do dedo."
            Step.HANDS -> "Levante as duas mãos abertas na frente do celular, a uns 40 cm do rosto, e deixe-as paradas um instante. O HyperXR mede o tamanho delas para desenhar e acertar a pinça." +
                (ctx.hands?.calibrationProgress?.takeIf { it < 0.99f }?.let { "\n\nMedindo as mãos: ${(it * 100).toInt()}%" } ?: "")
            Step.PINCH -> "Junte e solte as pontas do polegar e do indicador três vezes. É assim que você clica à distância, onde a bolinha do ponteiro estiver.\n\nPinças: $pinches de 3"
            Step.PALM -> "Vire a palma de uma mão para cima, na sua frente, e faça a pinça. Abre o menu em círculo: voltar, recentralizar, teclado, voz, captura, biblioteca e janelas. Mova a mão até a opção e solte."
            Step.FIST -> "Feche a mão e segure fechada por um segundo. Com a mão fechada sobre uma janela, você a segura e move pelo espaço."
            Step.DOCK -> "Embaixo fica a dock: Biblioteca com todos os apps e jogos, os apps fixados, Voz, Ajustes e o botão Modo VR, que troca a câmera por um ambiente virtual (escolha qual no app Ambientes).\n\nO seu óculos e a calibração ficam em RealityLenses, nas configurações 2D."
            Step.DONE -> "Pronto! Divirta-se. Os 11 jogos, o navegador, o YouTube XR e os emuladores estão na Biblioteca.\n\nPara rever este tutorial: configurações 2D > Início > Refazer a configuração inicial e o tutorial."
        }
        c.paragraph(text, 28f)
        // progress of the gesture being practised
        if (step == Step.HANDS || step == Step.PINCH || step == Step.FIST) {
            val y = c.h - 190f
            bar.color = XrTheme.buttonDisabled
            c.canvas.drawRoundRect(RectF(c.pad, y, c.w - c.pad, y + 22f), 11f, 11f, bar)
            bar.color = XrTheme.accent
            c.canvas.drawRoundRect(RectF(c.pad, y, c.pad + (c.w - 2 * c.pad) * progress.coerceIn(0f, 1f), y + 22f), 11f, 11f, bar)
        }
        if (noHandsTime > 4f && step in setOf(Step.HANDS, Step.PINCH, Step.PALM, Step.FIST))
            c.label("Não estou vendo as mãos. Usando um controle? Pule esta etapa.", c.pad, c.h - 150f, 24f, XrTheme.accent2, maxWidth = c.w - 2 * c.pad)
        val by = c.h - 104f
        when (step) {
            Step.WELCOME -> c.button("go", "Começar", c.w - c.pad - 280f, by, 280f, 80f, accent = true) { next() }
            Step.DOCK -> c.button("go", "Entendi", c.w - c.pad - 280f, by, 280f, 80f, accent = true) { next() }
            Step.DONE -> c.button("go", "Concluir", c.w - c.pad - 280f, by, 280f, 80f, accent = true) { visible = false; onDone() }
            else -> c.button("skipstep", "Pular etapa", c.w - c.pad - 280f, by, 280f, 80f) { next() }
        }
        if (step != Step.DONE) c.button("skipall", "Pular tutorial", c.pad, by, 280f, 80f) { visible = false; onDone() }
    }
}
