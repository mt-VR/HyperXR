package com.hyperxr.emulators

/**
 * An open-source emulator core embedded in HyperXR (libretro API, built from the source and commit
 * below by tools/build_libretro_cores.sh). No games are included: the user plays their own ROMs.
 */
data class EmulatorCore(
    val id: String,
    /** Shared library in the APK (jniLibs). */
    val library: String,
    val system: String,
    val coreName: String,
    val extensions: List<String>,
    val authors: String,
    val license: String,
    val licenseUrl: String,
    val sourceUrl: String,
    val commit: String,
)

object EmulatorCatalog {
    val cores = listOf(
        EmulatorCore(
            "nes", "libcore_quicknes.so", "Nintendo (NES)", "QuickNES", listOf("nes"),
            "Shay Green (blargg), kode54 e a equipe libretro",
            "GPL-2.0", "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html",
            "https://github.com/libretro/QuickNES_Core", "26bb785c9deddb66a17717b21bb4e328f03ade32",
        ),
        EmulatorCore(
            "gb", "libcore_gambatte.so", "Game Boy / Game Boy Color", "Gambatte", listOf("gb", "gbc", "dmg"),
            "Sindre Aamås (sinamas) e a equipe libretro",
            "GPL-2.0", "https://www.gnu.org/licenses/old-licenses/gpl-2.0.html",
            "https://github.com/libretro/gambatte-libretro", "d9d6cd06382d1ced30de34d56d3609452323dab1",
        ),
        EmulatorCore(
            "gba", "libcore_mgba.so", "Game Boy Advance", "mGBA", listOf("gba"),
            "Vicki Pfau (endrift) e colaboradores do mGBA",
            "MPL-2.0", "https://www.mozilla.org/MPL/2.0/",
            "https://github.com/libretro/mgba", "7a12d6d4b9acb14c0ae62c9166b6a2f3d08007f6",
        ),
    )

    val extensions: Set<String> = cores.flatMap { it.extensions }.toSet()

    fun forFile(name: String): EmulatorCore? {
        val ext = name.substringAfterLast('.', "").lowercase()
        return cores.firstOrNull { ext in it.extensions }
    }

    /** Credits as shown in the app (one block per core). */
    fun credits(): List<String> = cores.map { c ->
        "${c.coreName} — ${c.system}\n" +
            "Autores: ${c.authors}\n" +
            "Licença: ${c.license} (${c.licenseUrl})\n" +
            "Código-fonte: ${c.sourceUrl} (commit ${c.commit.take(12)})"
    } + listOf(
        "API libretro (libretro.h) — Copyright (C) 2010-2024 The RetroArch team, licença MIT.",
        "Os núcleos são programas separados, carregados pelo HyperXR em tempo de execução; cada um mantém a sua licença. " +
            "O código-fonte exato está nos links acima e o script de compilação em tools/build_libretro_cores.sh. " +
            "Nenhum jogo vem incluído: use apenas ROMs que você tem o direito de usar.",
    )
}

/** libretro joypad bits (RETRO_DEVICE_ID_JOYPAD_*). */
object RetroPad {
    const val B = 0
    const val Y = 1
    const val SELECT = 2
    const val START = 3
    const val UP = 4
    const val DOWN = 5
    const val LEFT = 6
    const val RIGHT = 7
    const val A = 8
    const val X = 9
    const val L = 10
    const val R = 11
    const val L2 = 12
    const val R2 = 13

    /** Direction bits from an analog stick or hat (x right, y down), with a dead zone. */
    fun directions(x: Float, y: Float, dead: Float = 0.45f): Int {
        var m = 0
        if (x > dead) m = m or (1 shl RIGHT)
        if (x < -dead) m = m or (1 shl LEFT)
        if (y > dead) m = m or (1 shl DOWN)
        if (y < -dead) m = m or (1 shl UP)
        return m
    }
}
