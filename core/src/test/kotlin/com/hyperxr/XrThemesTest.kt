package com.hyperxr

import com.hyperxr.settings.XrThemeStyle
import com.hyperxr.settings.XrThemes
import org.junit.Assert.assertTrue
import org.junit.Test

class XrThemesTest {
    private fun opaque(c: Int) = c or (0xFF shl 24)

    @Test fun everyThemeIsReadable() {
        for (t in XrThemeStyle.entries) {
            val p = t.palette
            for (bg in listOf(p.panelTop, p.panelBottom)) {
                val text = XrThemes.contrast(opaque(p.text), opaque(bg))
                val dim = XrThemes.contrast(opaque(p.textDim), opaque(bg))
                assertTrue("${t.name} text $text", text >= 6.5)
                assertTrue("${t.name} dim text $dim", dim >= 4.3)
            }
            val onButton = XrThemes.contrast(opaque(p.text), opaque(p.button))
            assertTrue("${t.name} button $onButton", onButton >= 4.5)
            assertTrue("${t.name} radius", p.panelRadiusM in 0.005f..0.1f && p.controlRounding in 0.1f..0.5f)
        }
    }
}
