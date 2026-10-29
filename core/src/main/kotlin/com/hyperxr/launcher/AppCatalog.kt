package com.hyperxr.launcher

/** Categories used by the XR apps launcher and the spatial launcher. */
enum class AppCategory { SYSTEM, CARDBOARD, OPENXR, HYPERXR_GAME, ENVIRONMENT, WEB, ANDROID, RECENT_INSTALL }

/** How an entry is started. Each kind maps to a real code path in the app module. */
enum class AppKind {
    /** Built-in HyperXR system panel implemented natively (settings, files, ...). */
    SYSTEM_PANEL,
    /** A `.hyperx` package executed by the LuaJIT game runtime. */
    HYPERX_PACKAGE,
    /** Web application opened in a WebView panel. */
    WEB,
    /** Installed Android package launched via its launch intent (Cardboard / OpenXR / regular). */
    ANDROID_PACKAGE,
    /** Feature exists in the catalog but is not implemented yet; shown disabled with a reason. */
    UNAVAILABLE,
}

/**
 * One launchable entry. [iconKey] refers to the central icon library ([IconLibrary]);
 * Android packages carry their own icon loaded from PackageManager instead.
 */
data class AppEntry(
    val id: String,
    val title: String,
    val kind: AppKind,
    val category: AppCategory,
    val iconKey: String?,
    val target: String = "",
    val runtime: String = "HyperMagic",
    val compatibility: String = "",
    val limitations: String = "",
    val installed: Boolean = true,
)

/**
 * Central icon library. Every icon used by the launcher is resolved here so assets can be
 * swapped in one place. Icons are the author's own artwork (adaptive-style bg + fg layers).
 * Entries mapped to null have no suitable provided icon yet and render a neutral placeholder;
 * they are listed in docs/ICONS.md as pending.
 */
object IconLibrary {
    /** Base names of the provided icon layers: `ic_app_<name>_bg` / `ic_app_<name>_fg`. */
    val PROVIDED = listOf(
        "browser", "calculator", "calendar", "camera_share", "clock", "facebook", "files", "gallery",
        "help_center", "instagram", "remote_desktop", "settings", "store", "system_messenger", "tv", "whatsapp",
    )

    fun bgResource(key: String) = "ic_app_${key}_bg"
    fun fgResource(key: String) = "ic_app_${key}_fg"
    /**
     * Icons for apps the author has not drawn one for: established open icon sets, not drawn by
     * the assistant. HyperXR's own apps use Google's Material Symbols (Apache-2.0) and brands
     * their official logo from Simple Icons (CC0), white on a gradient like the provided icons.
     */
    val LIBRARY = listOf(
        "importapk", "opengames", "youtube", "emulators", "androidapps", "handlab", "appworld", "assistant",
        "screenshare", "workspaces", "reality", "catgame", "twitch", "tiktok", "hyperx_package", "ocean", "guardian",
        "qg_rhythm", "qg_fruit", "qg_boxing", "qg_plank", "qg_golf", "qg_slowtime", "qg_drones", "qg_pingpong", "qg_fishing", "qg_diorama", "qg_climb", "qg_macacada", "qg_babuino", "qg_expediente",
        "mc_questcraft", "mc_gorillacraft", "shop", "people", "missions",
    )

    fun isProvided(key: String?) = key != null && (key in PROVIDED || key in LIBRARY)
}

object BuiltinApps {
    /** Package ids that earlier versions bundled and that are no longer part of HyperXR. */
    val RETIRED_PACKAGES = listOf("com.hyperxr.stillshot", "com.hyperxr.forgelab", "com.hyperxr.primalrush")

    val all: List<AppEntry> = listOf(
        AppEntry("hx:loja", "Loja", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "shop", "loja",
            runtime = "HyperMagic", compatibility = "Jogos HyperMagic, OpenXR, Cardboard, Minecraft e apps",
            limitations = "Jogos OpenXR vêm das páginas dos autores e passam pelo Corrigir APK"),
        AppEntry("hx:browser", "Navegador", AppKind.WEB, AppCategory.SYSTEM, "browser", com.hyperxr.browser.UrlInput.HOME),
        AppEntry("hx:people", "Pessoas", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "people", "people",
            runtime = "HyperMagic + Supabase", compatibility = "Amigos, mensagens, chamadas de voz e mostrar a tela",
            limitations = "Precisa de internet e de uma conta (Configurações 2D > Pessoas)"),
        AppEntry("hx:missions", "Missões", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "missions", "missions",
            runtime = "HyperMagic", compatibility = "Nível, XP, missões diárias, sequência de dias e conquistas"),
        AppEntry("hx:settings", "Configurações", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "settings", "settings"),
        AppEntry("hx:files", "Arquivos", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "files", "files",
            limitations = "Lists HyperMagic storage (.hyperx packages, environments, captures)"),
        AppEntry("hx:gallery", "Galeria", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "gallery", "gallery"),
        AppEntry("hx:camera_share", "Captura", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "camera_share", "capture",
            limitations = "Captures the rendered eye buffer; camera pixels only when MR is active"),
        AppEntry("hx:store", "Ambientes", AppKind.SYSTEM_PANEL, AppCategory.ENVIRONMENT, "store", "environments"),
        AppEntry("hx:system_messenger", "Mensagens do sistema", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "system_messenger", "messages"),
        AppEntry("hx:help_center", "Central de ajuda", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "help_center", "help"),
        AppEntry("hx:tv", "TV", AppKind.SYSTEM_PANEL, AppCategory.WEB, "tv", "tv"),
        AppEntry("hx:xrapps", "Apps XR", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "remote_desktop", "xrapps"),
        AppEntry("hx:importapk", "Corrigir APK", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "importapk", "importapk",
            runtime = "HyperMagic", compatibility = "Jogos OpenXR, de Quest e apps Daydream/Cardboard",
            limitations = "Escolha um APK do celular; o HyperMagic diz se roda e corrige para o runtime HyperMagic"),
        AppEntry("hx:opengames", "Jogos open source", AppKind.SYSTEM_PANEL, AppCategory.HYPERXR_GAME, "opengames", "opengames",
            runtime = "OpenXR (via Corrigir APK) ou Cardboard", compatibility = "Busca no GitHub e no itch.io, só licenças open source",
            limitations = "Jogos feitos para o Quest podem usar recursos que o HyperMagic ainda não tem"),
        AppEntry("hx:youtube", "YouTube XR", AppKind.SYSTEM_PANEL, AppCategory.WEB, "youtube", "youtube",
            runtime = "HyperMagic (nativo, sem página web)", compatibility = "Busca, categorias, vídeos até 4K/8K 60 fps, ao vivo e 360",
            limitations = "Sem login: sem inscrições, histórico ou comentários da sua conta"),
        AppEntry("hx:emulators", "Emuladores", AppKind.SYSTEM_PANEL, AppCategory.HYPERXR_GAME, "emulators", "emulators",
            runtime = "Núcleos libretro open source embutidos", compatibility = "NES (QuickNES), Game Boy/Color (Gambatte), GBA (mGBA)",
            limitations = "Nenhum jogo incluído: use as suas próprias ROMs"),
        AppEntry("hx:androidapps", "Apps Android", AppKind.SYSTEM_PANEL, AppCategory.ANDROID, "androidapps", "androidapps",
            limitations = "Abre qualquer app instalado numa janela plana ou curva; requer o Shizuku"),
        AppEntry("hx:clock", "Relógio", AppKind.HYPERX_PACKAGE, AppCategory.SYSTEM, "clock", "com.hyperxr.clock"),
        AppEntry("hx:calculator", "Calculadora", AppKind.HYPERX_PACKAGE, AppCategory.SYSTEM, "calculator", "com.hyperxr.calculator"),
        AppEntry("hx:calendar", "Calendário", AppKind.HYPERX_PACKAGE, AppCategory.SYSTEM, "calendar", "com.hyperxr.calendar"),
        AppEntry("hx:remote_desktop", "Área de trabalho remota", AppKind.UNAVAILABLE, AppCategory.SYSTEM, "remote_desktop",
            limitations = "Not implemented yet: needs a streaming protocol (e.g. VNC/RDP client) and a host component"),
        AppEntry("game:handlab", "Hand Physics Lab", AppKind.SYSTEM_PANEL, AppCategory.HYPERXR_GAME, "handlab", "handlab",
            runtime = "HyperMagic (nativo)", compatibility = "Realidade mista ou VR",
            limitations = "Física de corpos rígidos numa bancada à sua frente; use as mãos (ou o cursor do Joy-Con)"),
        AppEntry("game:ocean", "Oceano", AppKind.SYSTEM_PANEL, AppCategory.HYPERXR_GAME, "ocean", "ocean",
            runtime = "HyperMagic (nativo)", compatibility = "Realidade mista (ARCore Depth para medir a sala) ou VR",
            limitations = "Sua sala afunda e vira mar aberto; chuva, trovões, luz e volume num menu rápido. Sem sensor de profundidade, o chão é estimado pela altura"),
        AppEntry("hx:guardian", "HyperGuardian", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "guardian", "guardian",
            runtime = "HyperMagic", compatibility = "ARCore com posição (6DoF); o modo automático também precisa do ARCore Depth",
            limitations = "Manual: desenhe a área com a mão. Automático: paredes pela profundidade, com proteção para cama, móveis e o próprio corpo"),
        AppEntry("hx:appworld", "App-to-World", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "appworld", "appworld",
            runtime = "HyperMagic", compatibility = "Apps Android como objetos no espaço",
            limitations = "Música e notificações precisam do acesso às notificações; arquivos, do acesso ao armazenamento"),
        AppEntry("hx:assistant", "Assistente", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "assistant", "assistant",
            runtime = "IA no celular (MediaPipe LLM)", compatibility = "Voz (reconhecimento do Google) ou texto",
            limitations = "Baixa um modelo Qwen 2.5 uma vez (550 MB ou 1,6 GB); depois funciona offline"),
        AppEntry("hx:share", "Compartilhar tela", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "screenshare", "share",
            runtime = "HyperMagic", compatibility = "Vídeo 16:9, navegador, telas externas",
            limitations = "Gravação de outros apps em 16:9 só no Android 15 ou mais novo"),
        AppEntry("hx:workspaces", "Espaços de trabalho", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "workspaces", "workspaces",
            runtime = "HyperMagic", compatibility = "Salva e reabre conjuntos de janelas",
            limitations = "Guarda abas do navegador, apps Android e web apps; não guarda o estado dentro dos apps"),
        AppEntry("hx:reality", "Mundo real", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "reality", "reality",
            runtime = "HyperMagic", compatibility = "Janela com a câmera dentro do VR",
            limitations = "Precisa da câmera ligada (hand tracking ou realidade mista)"),
        AppEntry("game:catgame", "Gato vs. Vovó", AppKind.SYSTEM_PANEL, AppCategory.HYPERXR_GAME, "catgame", "catgame",
            runtime = "HyperMagic (nativo)", compatibility = "VR com Joy-Cons",
            limitations = "Você é um gato na casa da vovó: atrapalhe as tarefas e acerte-a com objetos antes que ela termine"),
        AppEntry("game:questcraft", "QuestCraft", AppKind.SYSTEM_PANEL, AppCategory.HYPERXR_GAME, "mc_questcraft", "mc_questcraft",
            runtime = "Minecraft Java Edition (launcher do QuestCraft portado)", compatibility = "Mãos ou controle, pelo runtime OpenXR do HyperMagic",
            limitations = "Precisa de uma conta Microsoft com o Minecraft Java; instale e entre na conta em Configurações 2D > Minecraft"),
        AppEntry("game:gorillacraft", "GorillaCraft", AppKind.SYSTEM_PANEL, AppCategory.HYPERXR_GAME, "mc_gorillacraft", "mc_gorillacraft",
            runtime = "Minecraft Java Edition + ViveMonkeCraft", compatibility = "Movimento de gorila com as mãos, ajustado para VR Box",
            limitations = "Precisa de uma conta Microsoft com o Minecraft Java; instale e entre na conta em Configurações 2D > Minecraft"),
    ) + com.hyperxr.questgames.QuestGameCatalog.games.map { g ->
        AppEntry("game:${g.id}", g.title, AppKind.SYSTEM_PANEL, AppCategory.HYPERXR_GAME, "qg_${g.id}", g.id,
            runtime = "HyperMagic (nativo)", compatibility = if (g.controller) "Precisa de controle (Joy-Con, HyperConnect ou gamepad)" else if (g.id in setOf("climb", "macacada", "babuino", "expediente", "slowtime")) "Mãos ou controle" else "Só as mãos (hand tracking)",
            limitations = "${g.howTo}. Inspirado em ${g.inspiredBy}; modelos 3D da Kenney (CC0)")
    } + listOf(
        AppEntry("web:youtube", "YouTube", AppKind.WEB, AppCategory.WEB, "youtube", "https://m.youtube.com"),
        AppEntry("web:twitch", "Twitch", AppKind.WEB, AppCategory.WEB, "twitch", "https://m.twitch.tv"),
        AppEntry("web:tiktok", "TikTok", AppKind.WEB, AppCategory.WEB, "tiktok", "https://www.tiktok.com"),
        AppEntry("web:whatsapp", "WhatsApp Web", AppKind.WEB, AppCategory.WEB, "whatsapp", "https://web.whatsapp.com",
            limitations = "WhatsApp Web targets desktop browsers; may require desktop mode or the external browser"),
        AppEntry("web:facebook", "Facebook", AppKind.WEB, AppCategory.WEB, "facebook", "https://m.facebook.com"),
        AppEntry("web:instagram", "Instagram", AppKind.WEB, AppCategory.WEB, "instagram", "https://www.instagram.com"),
    )

    fun byId(id: String): AppEntry? = all.firstOrNull { it.id == id }
}
