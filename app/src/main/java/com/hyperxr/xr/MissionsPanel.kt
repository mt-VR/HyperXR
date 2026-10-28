package com.hyperxr.xr

import android.graphics.Paint
import android.graphics.RectF
import com.hyperxr.progress.Progression
import com.hyperxr.progress.ProgressStore
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme

/** Missões: level and XP, the day streak, today's three missions and the achievements. */
class MissionsPanel : XrPanel(1.3f, 1.0f, title = "Missões") {
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val listener: (com.hyperxr.progress.Outcome) -> Unit = { dirty = true }
    init { ProgressStore.addListener(listener) }
    override fun release() { ProgressStore.removeListener(listener); super.release() }

    private fun bar(c: PanelCanvas, x: Float, top: Float, w: Float, h: Float, f: Float, color: Int) {
        p.color = XrTheme.buttonDisabled; c.canvas.drawRoundRect(RectF(x, top, x + w, top + h), h / 2, h / 2, p)
        p.color = color; c.canvas.drawRoundRect(RectF(x, top, x + w * f.coerceIn(0f, 1f).coerceAtLeast(h / w), top + h), h / 2, h / 2, p)
    }

    override fun draw(c: PanelCanvas) {
        val s = ProgressStore.today()
        val level = Progression.level(s.xp)
        val (inLevel, size) = Progression.levelProgress(s.xp)
        c.label("Nível $level", c.pad, c.y, 54f, XrTheme.text, bold = true)
        c.label("${s.xp} XP no total · faltam ${size - inLevel} para o nível ${level + 1}", c.pad + 260f, c.y + 18f, 26f, XrTheme.textDim, maxLines = 1)
        bar(c, c.pad, c.y + 80f, c.w - 2 * c.pad, 22f, inLevel / size.toFloat(), XrTheme.accent)
        c.y += 128f
        c.label(if (s.streak > 0) "Sequência: ${s.streak} dia${if (s.streak > 1) "s" else ""} seguidos. Volte amanhã para ganhar mais XP." else "Entre todo dia para manter a sequência.",
            c.pad, c.y, 26f, XrTheme.accent2, maxLines = 1)
        c.y += 56f
        c.label("Missões de hoje", c.pad, c.y, 34f, XrTheme.text, bold = true)
        c.y += 54f
        for (m in s.missions) {
            c.label(m.title, c.pad, c.y, 28f, if (m.claimed) XrTheme.ok else XrTheme.text, maxLines = 1, maxWidth = c.w * 0.55f)
            c.label(if (m.claimed) "Feita · +${m.xp} XP" else "${m.progress}/${m.goal} · +${m.xp} XP", c.w - c.pad - 280f, c.y, 26f, XrTheme.textDim, maxLines = 1, maxWidth = 280f)
            bar(c, c.pad, c.y + 42f, c.w - 2 * c.pad, 12f, m.progress / m.goal.toFloat(), if (m.claimed) XrTheme.ok else XrTheme.accent2)
            c.y += 80f
        }
        c.y += 10f
        c.label("Conquistas (${s.achievements.size}/${Progression.ACHIEVEMENTS.size})", c.pad, c.y, 34f, XrTheme.text, bold = true)
        c.y += 54f
        val cols = 3
        val cw = (c.w - 2 * c.pad) / cols
        Progression.ACHIEVEMENTS.forEachIndexed { i, a ->
            val x = c.pad + (i % cols) * cw
            val y = c.y + (i / cols) * 70f
            if (y > c.h - 60f) return@forEachIndexed
            val got = a.id in s.achievements
            p.color = if (got) XrTheme.accent else XrTheme.buttonDisabled
            c.canvas.drawRoundRect(RectF(x, y, x + cw - 14f, y + 60f), 16f, 16f, p)
            c.label(a.title, x + 16f, y + 6f, 22f, if (got) XrTheme.text else XrTheme.textDim, bold = true, maxLines = 1, maxWidth = cw - 40f)
            c.label(a.description, x + 16f, y + 32f, 18f, XrTheme.textDim, maxLines = 1, maxWidth = cw - 40f)
        }
    }
}
