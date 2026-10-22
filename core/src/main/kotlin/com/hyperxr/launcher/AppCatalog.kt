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
    val runtime: String = "HyperXR",
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
    fun isProvided(key: String?) = key != null && key in PROVIDED
}

object BuiltinApps {
    /** Package ids that earlier versions bundled and that are no longer part of HyperXR. */
    val RETIRED_PACKAGES = listOf("com.hyperxr.stillshot", "com.hyperxr.forgelab", "com.hyperxr.primalrush")

    val all: List<AppEntry> = listOf(
        AppEntry("hx:browser", "Navegador", AppKind.WEB, AppCategory.SYSTEM, "browser", "https://duckduckgo.com"),
        AppEntry("hx:settings", "Configurações", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "settings", "settings"),
        AppEntry("hx:files", "Arquivos", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "files", "files",
            limitations = "Lists HyperXR storage (.hyperx packages, environments, captures)"),
        AppEntry("hx:gallery", "Galeria", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "gallery", "gallery"),
        AppEntry("hx:camera_share", "Captura", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "camera_share", "capture",
            limitations = "Captures the rendered eye buffer; camera pixels only when MR is active"),
        AppEntry("hx:store", "HyperXR Environments", AppKind.SYSTEM_PANEL, AppCategory.ENVIRONMENT, "store", "environments"),
        AppEntry("hx:system_messenger", "Mensagens do sistema", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "system_messenger", "messages"),
        AppEntry("hx:help_center", "Central de ajuda", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "help_center", "help"),
        AppEntry("hx:tv", "TV", AppKind.SYSTEM_PANEL, AppCategory.WEB, "tv", "tv"),
        AppEntry("hx:xrapps", "Apps XR", AppKind.SYSTEM_PANEL, AppCategory.SYSTEM, "remote_desktop", "xrapps"),
        AppEntry("hx:androidapps", "Apps Android", AppKind.SYSTEM_PANEL, AppCategory.ANDROID, null, "androidapps",
            limitations = "Abre qualquer app instalado numa janela plana ou curva; requer o Shizuku"),
        AppEntry("hx:clock", "Relógio", AppKind.HYPERX_PACKAGE, AppCategory.SYSTEM, "clock", "com.hyperxr.clock"),
        AppEntry("hx:calculator", "Calculadora", AppKind.HYPERX_PACKAGE, AppCategory.SYSTEM, "calculator", "com.hyperxr.calculator"),
        AppEntry("hx:calendar", "Calendário", AppKind.HYPERX_PACKAGE, AppCategory.SYSTEM, "calendar", "com.hyperxr.calendar"),
        AppEntry("hx:remote_desktop", "Área de trabalho remota", AppKind.UNAVAILABLE, AppCategory.SYSTEM, "remote_desktop",
            limitations = "Not implemented yet: needs a streaming protocol (e.g. VNC/RDP client) and a host component"),
        AppEntry("game:handlab", "Hand Physics Lab", AppKind.SYSTEM_PANEL, AppCategory.HYPERXR_GAME, null, "handlab",
            runtime = "HyperXR (nativo)", compatibility = "Realidade mista ou VR",
            limitations = "Física de corpos rígidos numa bancada à sua frente; use as mãos (ou o cursor do Joy-Con)"),
        AppEntry("web:youtube", "YouTube", AppKind.WEB, AppCategory.WEB, null, "https://m.youtube.com"),
        AppEntry("web:twitch", "Twitch", AppKind.WEB, AppCategory.WEB, null, "https://m.twitch.tv"),
        AppEntry("web:tiktok", "TikTok", AppKind.WEB, AppCategory.WEB, null, "https://www.tiktok.com"),
        AppEntry("web:whatsapp", "WhatsApp Web", AppKind.WEB, AppCategory.WEB, "whatsapp", "https://web.whatsapp.com",
            limitations = "WhatsApp Web targets desktop browsers; may require desktop mode or the external browser"),
        AppEntry("web:facebook", "Facebook", AppKind.WEB, AppCategory.WEB, "facebook", "https://m.facebook.com"),
        AppEntry("web:instagram", "Instagram", AppKind.WEB, AppCategory.WEB, "instagram", "https://www.instagram.com"),
    )

    fun byId(id: String): AppEntry? = all.firstOrNull { it.id == id }
}
