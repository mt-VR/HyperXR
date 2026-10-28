package com.hyperxr.viewers

/**
 * RealityLenses: the phone VR headsets HyperXR knows, each with the optics it is calibrated
 * with (field of view, lens centres, radial distortion, screen-to-lens distance) and the way you
 * click in it. Picking one sets the renderer, the OpenXR runtime and the Cardboard/Daydream
 * viewer file at once.
 *
 * Where the numbers come from is stated per viewer ([source]): Google's published Cardboard
 * profiles are exact; the rest are estimates from the maker's specifications (lens size, FOV)
 * and the usual values for that kind of lens, meant as a starting point for the fine tuning in
 * Gráficos.
 */
object ViewerCatalog {
    enum class Button(val label: String) {
        TOUCH("Botão ou alavanca que toca a tela"),
        MAGNET("Ímã na lateral (use as mãos ou um controle)"),
        REMOTE("Controle Bluetooth que acompanha"),
        CONTROLLER("Controle próprio (Daydream / Gear VR)"),
        TOUCHPAD("Touchpad na lateral (só funciona com o app da Samsung)"),
        NONE("Sem botão: use as mãos ou um controle"),
    }

    enum class Source(val label: String) { OFFICIAL("Perfil oficial do fabricante"), ESTIMATED("Estimado pelas especificações") }

    data class Viewer(
        val id: String,
        val brand: String,
        val name: String,
        val year: Int,
        /** Full field of view per eye, as HyperXR renders it (degrees). */
        val fovDeg: Float,
        /** Distance between the lens centres (mm); for adjustable headsets, the middle of the range. */
        val lensSeparationMm: Float,
        val k1: Float,
        val k2: Float,
        val screenToLensMm: Float,
        val trayToLensMm: Float,
        val button: Button,
        val adjustableIpd: Boolean,
        /** Phones it takes, inches. */
        val phoneMin: Float,
        val phoneMax: Float,
        val source: Source = Source.ESTIMATED,
        val note: String = "",
    ) {
        val title get() = "$brand $name"
    }

    private fun v(id: String, brand: String, name: String, year: Int, fov: Float, sep: Float, k1: Float, k2: Float, button: Button, adj: Boolean,
                  phoneMin: Float = 4.7f, phoneMax: Float = 6.5f, s2l: Float = 42f, tray: Float = 35f, source: Source = Source.ESTIMATED, note: String = "") =
        Viewer(id, brand, name, year, fov, sep, k1, k2, s2l, tray, button, adj, phoneMin, phoneMax, source, note)

    val all: List<Viewer> = listOf(
        // ---- Google
        v("cardboard-v1", "Google", "Cardboard v1", 2014, 80f, 60f, 0.441f, 0.156f, Button.MAGNET, false, 4f, 5.5f, s2l = 42f, source = Source.OFFICIAL,
            note = "O ímã não clica no HyperMagic: use as mãos, um controle ou toque na tela"),
        v("cardboard-v2", "Google", "Cardboard v2 (I/O 2015)", 2015, 100f, 64f, 0.34f, 0.55f, Button.TOUCH, false, 4f, 6f, s2l = 39f, source = Source.OFFICIAL),
        v("daydream-2016", "Google", "Daydream View (2016)", 2016, 90f, 62f, 0.385f, 0.593f, Button.CONTROLLER, false, 5f, 6f, s2l = 39f,
            note = "O controle Daydream funciona pelo HyperMagic Daydream (Joy-Con) nos apps GVR; aqui use as mãos ou outro controle"),
        v("daydream-2017", "Google", "Daydream View (2017)", 2017, 100f, 62f, 0.385f, 0.593f, Button.CONTROLLER, false, 5f, 6.3f, s2l = 39f),
        v("cardboard-generic", "Genérico", "Óculos de papelão (Cardboard)", 2015, 90f, 62f, 0.34f, 0.55f, Button.TOUCH, false, 4f, 6f, s2l = 39f),
        // ---- Samsung
        v("gearvr-2015", "Samsung", "Gear VR (SM-R322)", 2015, 96f, 62f, 0.28f, 0.22f, Button.TOUCHPAD, false, 5.1f, 5.7f, s2l = 39f,
            note = "O conector USB do Gear VR não é usado: o touchpad não chega ao HyperMagic"),
        v("gearvr-2016", "Samsung", "Gear VR (SM-R323)", 2016, 101f, 62f, 0.28f, 0.22f, Button.TOUCHPAD, false, 5.1f, 5.7f, s2l = 39f),
        v("gearvr-2017", "Samsung", "Gear VR com controle (SM-R324/R325)", 2017, 101f, 62f, 0.28f, 0.22f, Button.CONTROLLER, false, 5.1f, 6.3f, s2l = 39f),
        // ---- VR Box
        v("vrbox-1", "VR Box", "VR Box (1.0)", 2015, 85f, 60f, 0.22f, 0.24f, Button.TOUCH, true, 4.7f, 6f, note = "O padrão do HyperMagic"),
        v("vrbox-2", "VR Box", "VR Box 2.0", 2016, 90f, 62f, 0.24f, 0.26f, Button.TOUCH, true, 4.7f, 6f),
        v("vrbox-3", "VR Box", "VR Box 3.0 (com fone)", 2017, 95f, 62f, 0.25f, 0.27f, Button.REMOTE, true, 4.7f, 6.2f),
        v("vrbox-remote", "VR Box", "VR Box com controle Bluetooth", 2016, 90f, 62f, 0.24f, 0.26f, Button.REMOTE, true, 4.7f, 6f,
            note = "O controle do VR Box funciona em modo jogo, teclado ou mouse"),
        // ---- VR Shinecon
        v("shinecon-g01", "VR Shinecon", "SC-G01", 2016, 85f, 61f, 0.23f, 0.22f, Button.NONE, true, 4.7f, 6f),
        v("shinecon-g02", "VR Shinecon", "SC-G02", 2016, 88f, 62f, 0.24f, 0.22f, Button.NONE, true, 4.7f, 6f),
        v("shinecon-g02ed", "VR Shinecon", "SC-G02ED", 2017, 88f, 62f, 0.24f, 0.22f, Button.NONE, true, 4.7f, 6f),
        v("shinecon-g03", "VR Shinecon", "SC-G03", 2017, 90f, 62f, 0.25f, 0.23f, Button.TOUCH, true, 4.7f, 6f),
        v("shinecon-g04", "VR Shinecon", "SC-G04", 2017, 95f, 62f, 0.26f, 0.24f, Button.NONE, true, 4.7f, 6f),
        v("shinecon-g04a", "VR Shinecon", "SC-G04A", 2018, 95f, 62f, 0.26f, 0.24f, Button.TOUCH, true, 4.7f, 6f),
        v("shinecon-g04bs", "VR Shinecon", "SC-G04BS (com fone)", 2018, 95f, 62f, 0.26f, 0.24f, Button.TOUCH, true, 4.7f, 6f),
        v("shinecon-g04e", "VR Shinecon", "SC-G04E (com fone)", 2018, 95f, 62f, 0.26f, 0.24f, Button.TOUCH, true, 4.7f, 6.2f),
        v("shinecon-g05", "VR Shinecon", "SC-G05", 2018, 100f, 63f, 0.28f, 0.26f, Button.TOUCH, true, 4.7f, 6.5f),
        v("shinecon-g05a", "VR Shinecon", "SC-G05A", 2018, 100f, 63f, 0.28f, 0.26f, Button.TOUCH, true, 4.7f, 6.5f),
        v("shinecon-g06", "VR Shinecon", "SC-G06 (com fone)", 2018, 100f, 63f, 0.28f, 0.26f, Button.TOUCH, true, 4.7f, 6.5f),
        v("shinecon-g06a", "VR Shinecon", "SC-G06A", 2019, 100f, 63f, 0.28f, 0.26f, Button.TOUCH, true, 4.7f, 6.5f),
        v("shinecon-g06b", "VR Shinecon", "SC-G06B", 2019, 100f, 63f, 0.28f, 0.26f, Button.TOUCH, true, 4.7f, 6.5f),
        v("shinecon-g06e", "VR Shinecon", "SC-G06E (com fone)", 2019, 100f, 63f, 0.28f, 0.26f, Button.TOUCH, true, 4.7f, 6.5f),
        v("shinecon-g07e", "VR Shinecon", "SC-G07E", 2019, 105f, 63f, 0.3f, 0.28f, Button.TOUCH, true, 4.7f, 6.7f),
        v("shinecon-g10", "VR Shinecon", "SC-G10", 2020, 105f, 63f, 0.3f, 0.28f, Button.TOUCH, true, 5f, 7f),
        v("shinecon-g13", "VR Shinecon", "SC-G13", 2020, 108f, 63f, 0.31f, 0.29f, Button.TOUCH, true, 5f, 7f),
        v("shinecon-g15", "VR Shinecon", "SC-G15", 2021, 110f, 63f, 0.32f, 0.3f, Button.TOUCH, true, 5f, 7.2f),
        // ---- BOBOVR
        v("bobovr-z4", "BOBOVR", "Z4", 2016, 110f, 63f, 0.32f, 0.3f, Button.TOUCH, true, 4.7f, 6.2f),
        v("bobovr-z5", "BOBOVR", "Z5", 2017, 110f, 63f, 0.33f, 0.31f, Button.REMOTE, true, 4.7f, 6.2f, note = "Anunciado como 120°; o HyperMagic usa até 110°"),
        v("bobovr-z6", "BOBOVR", "Z6", 2018, 110f, 63f, 0.33f, 0.31f, Button.TOUCH, true, 4.7f, 6.2f),
        // ---- other brands
        v("zeiss-vrone", "Zeiss", "VR One Plus", 2016, 100f, 62f, 0.3f, 0.2f, Button.NONE, false, 4.7f, 5.5f),
        v("merge-vr", "Merge", "Merge VR", 2016, 90f, 62f, 0.3f, 0.22f, Button.TOUCH, true, 4.7f, 6f),
        v("homido-v2", "Homido", "Homido V2", 2016, 100f, 62f, 0.3f, 0.24f, Button.NONE, true, 4.7f, 6f),
        v("viewmaster", "Mattel", "View-Master VR", 2015, 90f, 62f, 0.4f, 0.2f, Button.TOUCH, false, 4.7f, 5.5f),
        v("mi-vr-play", "Xiaomi", "Mi VR Play", 2016, 93f, 62f, 0.27f, 0.24f, Button.TOUCH, false, 4.7f, 5.7f),
        v("mi-vr-play2", "Xiaomi", "Mi VR Play 2", 2017, 93f, 62f, 0.27f, 0.24f, Button.TOUCH, false, 4.7f, 5.7f),
        v("mojing-3plus", "Baofeng", "Mojing III Plus", 2015, 96f, 62f, 0.3f, 0.24f, Button.NONE, true, 4.7f, 6f),
        v("destek-v4", "Destek", "V4", 2018, 103f, 63f, 0.3f, 0.28f, Button.TOUCH, true, 4.7f, 6.5f),
        v("destek-v5", "Destek", "V5", 2019, 110f, 63f, 0.32f, 0.3f, Button.TOUCH, true, 4.7f, 6.8f),
        v("utopia-360", "Utopia", "Utopia 360", 2017, 100f, 62f, 0.3f, 0.26f, Button.REMOTE, true, 4.5f, 6f),
        v("fiit-2n", "Fiit VR", "2N", 2018, 95f, 62f, 0.27f, 0.25f, Button.TOUCH, true, 4.7f, 6.5f),
        v("irusu-monster", "Irusu", "Monster", 2019, 105f, 63f, 0.3f, 0.28f, Button.TOUCH, true, 4.7f, 6.7f),
        v("dscvr", "I Am Cardboard", "DSCVR", 2016, 90f, 62f, 0.34f, 0.5f, Button.TOUCH, false, 4f, 6f, s2l = 39f),
    )

    const val DEFAULT_ID = "vrbox-1"

    fun byId(id: String): Viewer? = all.firstOrNull { it.id == id }

    /** Brands in catalogue order (for the filter chips). */
    val brands: List<String> get() = all.map { it.brand }.distinct()

    /** Matching a search typed by the user: brand, model, year. */
    fun search(q: String, brand: String? = null): List<Viewer> {
        val t = q.trim().lowercase()
        return all.filter { (brand == null || it.brand == brand) && (t.isEmpty() || "${it.brand} ${it.name} ${it.year}".lowercase().contains(t)) }
    }
}
