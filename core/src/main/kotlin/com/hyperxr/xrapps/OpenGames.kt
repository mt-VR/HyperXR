package com.hyperxr.xrapps

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** An open-source VR game or app (OpenXR or Cardboard) with its licence and where to get it. */
data class OpenGame(
    val name: String,
    val repo: String,
    val description: String,
    val license: String,
    val kind: Kind,
    val stars: Int = 0,
    val updated: String = "",
    /** What the user must provide (game data), empty when it runs as is. */
    val needs: String = "",
    /** A release of [repo] with this tag instead of the latest one (games HyperMagic builds itself). */
    val releaseTag: String = "",
    /** Built for the HyperMagic runtime already: installs as it is, no APK fixing needed. */
    val ready: Boolean = false,
) {
    enum class Kind { OPENXR, CARDBOARD }
    val url: String get() = "https://github.com/$repo"
}

/** A release file that can be installed. */
data class ApkAsset(val name: String, val url: String, val bytes: Long, val tag: String)

/**
 * Finding open-source VR games: a list checked by hand (licence read from each repository), plus
 * a live GitHub search run on the phone. Only projects under an open-source licence are shown;
 * repositories without one are not open source, whatever their README says.
 */
object OpenGames {
    val OPEN_LICENSES = setOf(
        "GPL-2.0", "GPL-3.0", "LGPL-2.1", "LGPL-3.0", "AGPL-3.0", "MIT", "Apache-2.0", "BSD-2-Clause", "BSD-3-Clause",
        "MPL-2.0", "ISC", "Zlib", "Unlicense", "CC0-1.0", "BSL-1.0", "EPL-2.0",
    )

    /** Checked on 2026-10-23: licence file read from each repository. Not tested on a phone. */
    val CURATED = listOf(
        OpenGame("Open Saber", "mt-VR/HyperXR", "O jogo de ritmo com sabres de luz: corte os blocos na direção da seta. Port do Open Saber (Godot 4, OpenXR) compilado do código-fonte para o runtime do HyperMagic; as músicas vêm do BeatSaver, no menu do jogo",
            "MIT", OpenGame.Kind.OPENXR, needs = "dois controles com posição (Joy-Cons, controle por cor ou HyperConnect)", releaseTag = "opensaber-v0.5.0", ready = true),
        OpenGame("Godot XR Tools Demo", "mt-VR/HyperXR", "O playground de VR do Godot: ande, escale, pegue e jogue objetos, atire e voe por vários mundinhos. Compilado do código-fonte para o runtime do HyperMagic",
            "MIT", OpenGame.Kind.OPENXR, needs = "controles com posição (Joy-Cons, controle por cor ou HyperConnect) ou as mãos", releaseTag = "xrtools-v4.4.0", ready = true),
        OpenGame("Quest DOOM", "CommunityPokeOrg/quest-doom", "DOOM em VR com OpenXR (doomgeneric); tem versão para celular comum", "GPL-2.0",
            OpenGame.Kind.OPENXR, needs = ""),
        OpenGame("QuestZDoom", "DrBeef/QuestZDoom", "GZDoom em VR: DOOM, Heretic, Hexen e mods", "GPL-3.0",
            OpenGame.Kind.OPENXR, needs = "um WAD (o Freedoom é livre)"),
        OpenGame("Quake3Quest", "DrBeef/ioq3quest", "Quake III Arena em VR (ioquake3)", "GPL-2.0",
            OpenGame.Kind.OPENXR, needs = "os arquivos .pk3 do Quake III (ou da demo)"),
        OpenGame("Quake2Quest", "DrBeef/Quake2Quest", "Quake II em VR (Yamagi Quake II)", "GPL-2.0",
            OpenGame.Kind.OPENXR, needs = "os dados do Quake II (ou da demo)"),
        OpenGame("OpenMW VR", "tomups/openmw-vr-quest", "OpenMW (motor livre de Morrowind) em VR", "GPL-3.0",
            OpenGame.Kind.OPENXR, needs = "os dados do Morrowind"),
        OpenGame("Godot XR Tools (demo)", "GodotVR/godot-xr-tools", "Kit e demo de VR do Godot: andar, pegar objetos, teleporte", "MIT",
            OpenGame.Kind.OPENXR),
        OpenGame("Hello Cardboard", "googlevr/cardboard", "Exemplo oficial do SDK Cardboard: ache o objeto e toque", "Apache-2.0",
            OpenGame.Kind.CARDBOARD),
    )

    /** GitHub search queries per kind (sorted by stars on the API side). */
    fun queries(kind: OpenGame.Kind): List<String> = when (kind) {
        OpenGame.Kind.OPENXR -> listOf("topic:openxr topic:android", "topic:openxr topic:quest game", "openxr android game in:name,description,topics")
        OpenGame.Kind.CARDBOARD -> listOf("topic:google-cardboard-vr", "topic:cardboard-vr", "topic:cardboard game", "cardboard vr android game in:name,description")
    }

    fun searchUrl(query: String, perPage: Int = 30) =
        "https://api.github.com/search/repositories?q=" + java.net.URLEncoder.encode(query, "UTF-8") + "&sort=stars&order=desc&per_page=$perPage"

    fun latestReleaseUrl(repo: String) = "https://api.github.com/repos/$repo/releases/latest"

    /** The release a game's APK comes from: its own tag, or the latest. */
    fun releaseUrl(g: OpenGame) = if (g.releaseTag.isNotEmpty()) "https://api.github.com/repos/${g.repo}/releases/tags/${g.releaseTag}" else latestReleaseUrl(g.repo)

    private val json = Json { ignoreUnknownKeys = true }

    /** Repositories of a search result that have an open-source licence. */
    fun parseSearch(text: String, kind: OpenGame.Kind): List<OpenGame> {
        val items = json.parseToJsonElement(text).jsonObject["items"] as? JsonArray ?: return emptyList()
        return items.mapNotNull { e ->
            val o = e.jsonObject
            val lic = (o["license"] as? JsonObject)?.get("spdx_id")?.jsonPrimitive?.content ?: return@mapNotNull null
            if (lic !in OPEN_LICENSES) return@mapNotNull null
            if ((o["archived"]?.jsonPrimitive?.content) == "true" && (o["stargazers_count"]?.jsonPrimitive?.intOrNull ?: 0) < 5) return@mapNotNull null
            val full = o["full_name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            OpenGame(
                name = o["name"]?.jsonPrimitive?.content ?: full,
                repo = full,
                description = o["description"]?.jsonPrimitive?.content.orEmpty().take(200),
                license = lic,
                kind = kind,
                stars = o["stargazers_count"]?.jsonPrimitive?.intOrNull ?: 0,
                updated = o["pushed_at"]?.jsonPrimitive?.content.orEmpty().take(10),
            )
        }
    }

    /** Merges several searches: one entry per repository, most stars first. */
    fun merge(lists: List<List<OpenGame>>): List<OpenGame> =
        lists.flatten().groupBy { it.repo.lowercase() }.map { it.value.first() }.sortedByDescending { it.stars }

    /** The installable APKs of a release (the 64-bit one first when it says so). */
    fun parseRelease(text: String): List<ApkAsset> {
        val o = json.parseToJsonElement(text).jsonObject
        val tag = o["tag_name"]?.jsonPrimitive?.content.orEmpty()
        val assets = o["assets"]?.jsonArray ?: return emptyList()
        return assets.mapNotNull { a ->
            val ao = a.jsonObject
            val name = ao["name"]?.jsonPrimitive?.content ?: return@mapNotNull null
            if (!name.endsWith(".apk", true)) return@mapNotNull null
            ApkAsset(name, ao["browser_download_url"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                ao["size"]?.jsonPrimitive?.longOrNull ?: 0L, tag)
        }.sortedByDescending { if (it.name.contains("arm64", true) || it.name.contains("64", true)) 1 else 0 }
    }

    /** itch.io pages listing Cardboard games for Android (licence shown on each game page). */
    val ITCH_PAGES = listOf(
        "Cardboard para Android (itch.io)" to "https://itch.io/games/input-cardboard-vr/platform-android",
        "Cardboard gratuitos (itch.io)" to "https://itch.io/games/free/input-cardboard-vr/lang-en",
    )
}
