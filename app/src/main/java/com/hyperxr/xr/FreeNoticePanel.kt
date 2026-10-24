package com.hyperxr.xr

import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.ui2d.FREE_APP_NOTICE

/** Shown in front of you every time the VR mode starts: HyperXR is free, nobody may sell it. */
class FreeNoticePanel(private val onOk: () -> Unit) : XrPanel(1.0f, 0.42f, title = "") {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    init { closable = false }

    override fun paintBackground(c: PanelCanvas) {
        fill.color = Color.rgb(198, 40, 40)
        c.canvas.drawRoundRect(RectF(0f, 0f, c.w, c.h), 48f, 48f, fill)
        fill.style = Paint.Style.STROKE; fill.strokeWidth = 8f; fill.color = Color.WHITE
        c.canvas.drawRoundRect(RectF(6f, 6f, c.w - 6f, c.h - 6f), 44f, 44f, fill)
        fill.style = Paint.Style.FILL
        c.y = c.pad
    }

    override fun draw(c: PanelCanvas) {
        c.y += 10f
        c.y += c.label(FREE_APP_NOTICE, c.pad + 10f, c.y, 50f, Color.WHITE, bold = true, maxWidth = c.w - 2 * c.pad - 20f) + 24f
        c.button("ok", "Entendi", c.w / 2 - 150f, c.y, 300f, 84f) { visible = false; onOk() }
    }
}
