package com.hyperxr.settings

/**
 * Colours and shapes of the XR panels. Each style is HyperMagic's own palette; some take the
 * general feel of another XR system (a dark gaming dashboard, a calm light launcher, dark glass
 * with a cyan accent...), never its logos, names or artwork. Colours are ARGB.
 */
data class ThemePalette(
    val panelTop: Int, val panelBottom: Int, val bar: Int, val border: Int,
    val text: Int, val textDim: Int,
    val accent: Int, val accentPressed: Int, val accent2: Int,
    val button: Int, val buttonHover: Int, val buttonPressed: Int, val buttonDisabled: Int,
    val tile: Int, val danger: Int, val ok: Int,
    /** Panel corner radius, metres. */
    val panelRadiusM: Float,
    /** Controls: corner radius as a fraction of their height (0.5 = pill). */
    val controlRounding: Float,
    /** Cards and tiles: corner radius in pixels at 900 px per metre. */
    val tileRadius: Float,
)

enum class XrThemeStyle(val label: String, val description: String) {
    HYPERMAGIC("HyperMagic", "Vidro verde-azulado, o visual de sempre"),
    VAPOR("Vapor", "Painel escuro azul-grafite de console de jogos, cantos quase retos, destaque azul elétrico"),
    BOSQUE("Bosque", "Claro e calmo, papel quente com verde musgo, cantos bem arredondados"),
    ORBITA("Órbita", "Vidro escuro com destaque ciano, pílulas e cantos grandes"),
    SALAO("Salão", "Carvão de sala de estar à noite, azul suave, grade de blocos"),
    MERGULHO("Mergulho", "Índigo profundo com destaque âmbar, blocos grandes"),
    TONAL("Tonal", "Superfícies tonais roxas, cantos grandes e botões pílula (escuro)"),
    NEVOA("Névoa", "Vidro claro e fosco, texto escuro, tudo arredondado"),
    CONTRASTE("Alto contraste", "Preto e branco com amarelo: o mais legível"),
    ;

    val palette: ThemePalette get() = XrThemes.of(this)
}

object XrThemes {
    private fun argb(a: Int, r: Int, g: Int, b: Int) = (a shl 24) or (r shl 16) or (g shl 8) or b
    private fun rgb(hex: Int) = (0xFF shl 24) or hex

    fun of(s: XrThemeStyle): ThemePalette = when (s) {
        XrThemeStyle.HYPERMAGIC -> ThemePalette(
            argb(246, 61, 90, 99), argb(246, 47, 72, 80), argb(250, 40, 61, 68), argb(40, 255, 255, 255),
            rgb(0xF6F9FA), rgb(0xB8C9CE), rgb(0x3484F6), rgb(0x60A0FF), rgb(0x78B6FF),
            rgb(0x4C676F), rgb(0x5C7981), rgb(0x708D95), rgb(0x3C545C), rgb(0x4A656D), rgb(0xEC5454), rgb(0x4CCE8C),
            0.04f, 0.5f, 22f,
        )
        XrThemeStyle.VAPOR -> ThemePalette(
            argb(250, 30, 38, 52), argb(250, 22, 28, 40), argb(255, 16, 21, 31), argb(60, 120, 160, 210),
            rgb(0xEEF3F8), rgb(0x93A4B8), rgb(0x2196F3), rgb(0x56B2FF), rgb(0x6BC2FF),
            rgb(0x2C3A4E), rgb(0x37485F), rgb(0x445873), rgb(0x232E3E), rgb(0x2A374A), rgb(0xE5534B), rgb(0x5BC36C),
            0.012f, 0.18f, 8f,
        )
        XrThemeStyle.BOSQUE -> ThemePalette(
            argb(248, 246, 243, 236), argb(248, 236, 232, 222), argb(252, 228, 223, 211), argb(50, 90, 80, 60),
            rgb(0x2B2A26), rgb(0x5E5A50), rgb(0x3C7A56), rgb(0x2F6446), rgb(0x2E6B8F),
            rgb(0xE0DACB), rgb(0xD5CEBD), rgb(0xC9C1AE), rgb(0xECE8DE), rgb(0xE5E0D3), rgb(0xB3402F), rgb(0x2F7D4E),
            0.06f, 0.5f, 34f,
        )
        XrThemeStyle.ORBITA -> ThemePalette(
            argb(226, 26, 30, 40), argb(226, 18, 21, 30), argb(236, 14, 17, 25), argb(70, 120, 220, 255),
            rgb(0xF2F6FF), rgb(0x9EA9BF), rgb(0x22C4E6), rgb(0x5ED8F2), rgb(0x7FE3F7),
            rgb(0x2A3142), rgb(0x343D52), rgb(0x404B63), rgb(0x20263A), rgb(0x262D3D), rgb(0xFF5C6C), rgb(0x3ED598),
            0.05f, 0.5f, 30f,
        )
        XrThemeStyle.SALAO -> ThemePalette(
            argb(244, 38, 38, 42), argb(244, 30, 30, 34), argb(250, 24, 24, 27), argb(45, 255, 255, 255),
            rgb(0xF4F4F5), rgb(0xA6A6AD), rgb(0x4A9FE8), rgb(0x76B8F0), rgb(0x8CC4F2),
            rgb(0x3A3A40), rgb(0x46464D), rgb(0x55555D), rgb(0x2E2E33), rgb(0x34343A), rgb(0xE5584F), rgb(0x55C27A),
            0.025f, 0.3f, 14f,
        )
        XrThemeStyle.MERGULHO -> ThemePalette(
            argb(246, 36, 34, 92), argb(246, 26, 24, 70), argb(252, 20, 18, 56), argb(55, 190, 180, 255),
            rgb(0xF5F3FF), rgb(0xB4AEDB), rgb(0xFFB020), rgb(0xFFC85A), rgb(0xFFD27A),
            rgb(0x3A3780), rgb(0x46439A), rgb(0x5450B0), rgb(0x2C2A66), rgb(0x332F7A), rgb(0xFF6B6B), rgb(0x5AD18F),
            0.035f, 0.35f, 26f,
        )
        XrThemeStyle.TONAL -> ThemePalette(
            argb(250, 33, 31, 38), argb(250, 29, 27, 32), argb(252, 20, 18, 24), argb(50, 202, 196, 208),
            rgb(0xE6E0E9), rgb(0xCAC4D0), rgb(0xD0BCFF), rgb(0xE8DDFF), rgb(0xCCC2DC),
            rgb(0x4A4458), rgb(0x564F66), rgb(0x635B74), rgb(0x2B2930), rgb(0x36343B), rgb(0xF2B8B5), rgb(0x8CD9A4),
            0.07f, 0.5f, 36f,
        )
        XrThemeStyle.NEVOA -> ThemePalette(
            argb(214, 236, 238, 242), argb(214, 222, 225, 231), argb(226, 210, 214, 222), argb(90, 255, 255, 255),
            rgb(0x1C1D21), rgb(0x55585F), rgb(0x0A6CDB), rgb(0x0858B4), rgb(0x0A6CDB),
            rgb(0xFFFFFF), rgb(0xF2F4F8), rgb(0xE2E6EE), rgb(0xE9EBEF), rgb(0xF6F7FA), rgb(0xC9302C), rgb(0x1E8A4C),
            0.08f, 0.5f, 40f,
        )
        XrThemeStyle.CONTRASTE -> ThemePalette(
            argb(255, 0, 0, 0), argb(255, 0, 0, 0), argb(255, 0, 0, 0), argb(255, 255, 255, 255),
            rgb(0xFFFFFF), rgb(0xE6E6E6), rgb(0xFFD400), rgb(0xFFE666), rgb(0xFFE000),
            rgb(0x1A1A1A), rgb(0x333333), rgb(0x4D4D4D), rgb(0x0D0D0D), rgb(0x141414), rgb(0xFF5555), rgb(0x55FF88),
            0.02f, 0.25f, 10f,
        )
    }

    /** WCAG contrast ratio of two opaque colours. */
    fun contrast(a: Int, b: Int): Double {
        fun lum(c: Int): Double {
            fun ch(v: Int): Double { val s = v / 255.0; return if (s <= 0.03928) s / 12.92 else Math.pow((s + 0.055) / 1.055, 2.4) }
            return 0.2126 * ch((c shr 16) and 255) + 0.7152 * ch((c shr 8) and 255) + 0.0722 * ch(c and 255)
        }
        val la = lum(a); val lb = lum(b)
        return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
    }
}
