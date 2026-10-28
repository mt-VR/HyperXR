package com.hyperxr.environments

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Full 3D geometry versus 360° imagery: never mixed up in the UI. */
@Serializable
enum class EnvironmentKind { SCENE_3D, PANORAMA_360, PROCEDURAL }

@Serializable
data class EnvironmentFile(val path: String, val url: String, val size: Long, val md5: String? = null)

@Serializable
data class EnvironmentEntry(
    val id: String,
    val name: String,
    val kind: EnvironmentKind,
    val author: String,
    val source: String,
    val sourceUrl: String,
    /** SPDX-like identifier: CC0-1.0, MIT, CC-BY-4.0, ... */
    val license: String,
    val licenseUrl: String,
    val format: String,
    val downloadBytes: Long,
    val previewUrl: String? = null,
    val attribution: String? = null,
    val compatibility: String = "",
    val files: List<EnvironmentFile> = emptyList(),
    /** Root file to load once downloaded (the .gltf/.glb, or the panorama image). */
    val mainFile: String = "",
    /** Scale to apply on import when the source is not in metres. */
    val importScale: Float = 1f,
    val spawn: List<Float> = listOf(0f, 0f, 2.5f),
)

/**
 * Accepts only licenses that allow redistribution and use inside HyperXR. Unknown or
 * non-commercial licenses are rejected rather than guessed.
 */
object LicensePolicy {
    private val NO_ATTRIBUTION = setOf("CC0-1.0", "PUBLIC-DOMAIN", "MIT-0")
    private val WITH_ATTRIBUTION = setOf("MIT", "CC-BY-4.0", "CC-BY-3.0", "APACHE-2.0")

    fun isAllowed(license: String): Boolean = license.uppercase() in NO_ATTRIBUTION || license.uppercase() in WITH_ATTRIBUTION
    fun requiresAttribution(license: String): Boolean = license.uppercase() in WITH_ATTRIBUTION

    fun validate(e: EnvironmentEntry): List<String> = buildList {
        if (!isAllowed(e.license)) add("License '${e.license}' is not on the allow-list")
        if (requiresAttribution(e.license) && e.attribution.isNullOrBlank()) add("Missing attribution text for ${e.license}")
        if (e.author.isBlank()) add("Missing author")
        if (e.sourceUrl.isBlank()) add("Missing source URL")
        if (e.kind != EnvironmentKind.PROCEDURAL && e.files.isEmpty()) add("No files to download")
    }
}

/**
 * Parser for the public Poly Haven API (https://api.polyhaven.com). Every Poly Haven asset is
 * published under CC0 (https://polyhaven.com/license), which is recorded per entry.
 */
object PolyHavenCatalog {
    const val API = "https://api.polyhaven.com"
    const val LICENSE_URL = "https://polyhaven.com/license"
    private val json = Json { ignoreUnknownKeys = true }

    /** Categories of models that are environment-scale rather than single props. */
    val ENVIRONMENT_CATEGORIES = listOf("structures", "buildings")

    fun parseAssetList(text: String, kind: EnvironmentKind): List<Pair<String, JsonObject>> =
        json.parseToJsonElement(text).jsonObject.entries.map { it.key to it.value.jsonObject }

    fun authorOf(asset: JsonObject): String =
        asset["authors"]?.jsonObject?.keys?.joinToString(", ").orEmpty().ifBlank { "Poly Haven" }

    /** Builds an entry for a 3D model from /files/<id>, choosing the requested resolution (e.g. "1k"). */
    fun modelEntry(id: String, asset: JsonObject, filesJson: String, resolution: String = "1k"): EnvironmentEntry? {
        val files = json.parseToJsonElement(filesJson).jsonObject
        val gltf = files["gltf"]?.jsonObject?.get(resolution)?.jsonObject?.get("gltf")?.jsonObject ?: return null
        val mainUrl = gltf["url"]?.jsonPrimitive?.contentOrNull ?: return null
        val mainName = mainUrl.substringAfterLast('/')
        val list = mutableListOf(EnvironmentFile(mainName, mainUrl, gltf["size"]?.jsonPrimitive?.longOrNull ?: 0, gltf["md5"]?.jsonPrimitive?.contentOrNull))
        gltf["include"]?.jsonObject?.forEach { (path, v) ->
            val o = v.jsonObject
            list += EnvironmentFile(path, o["url"]!!.jsonPrimitive.content, o["size"]?.jsonPrimitive?.longOrNull ?: 0, o["md5"]?.jsonPrimitive?.contentOrNull)
        }
        return EnvironmentEntry(
            id = "polyhaven:$id",
            name = asset["name"]?.jsonPrimitive?.contentOrNull ?: id,
            kind = EnvironmentKind.SCENE_3D,
            author = authorOf(asset),
            source = "Poly Haven",
            sourceUrl = "https://polyhaven.com/a/$id",
            license = "CC0-1.0",
            licenseUrl = LICENSE_URL,
            format = "glTF 2.0 ($resolution textures)",
            downloadBytes = list.sumOf { it.size },
            previewUrl = "https://cdn.polyhaven.com/asset_img/thumbs/$id.png?width=256&height=256",
            compatibility = "Mobile GPU: ${asset["polycount"]?.jsonPrimitive?.longOrNull ?: "?"} triangles",
            files = list,
            mainFile = mainName,
        )
    }

    /** Builds a 360° panorama entry from an HDRI's tonemapped JPEG (8-bit equirectangular). */
    fun panoramaEntry(id: String, asset: JsonObject, filesJson: String): EnvironmentEntry? {
        val files = json.parseToJsonElement(filesJson).jsonObject
        val t = files["tonemapped"]?.jsonObject ?: return null
        val url = t["url"]?.jsonPrimitive?.contentOrNull ?: return null
        val size = t["size"]?.jsonPrimitive?.longOrNull ?: 0
        return EnvironmentEntry(
            id = "polyhaven-hdri:$id",
            name = asset["name"]?.jsonPrimitive?.contentOrNull ?: id,
            kind = EnvironmentKind.PANORAMA_360,
            author = authorOf(asset),
            source = "Poly Haven",
            sourceUrl = "https://polyhaven.com/a/$id",
            license = "CC0-1.0",
            licenseUrl = LICENSE_URL,
            format = "Equirectangular JPEG (tonemapped)",
            downloadBytes = size,
            previewUrl = "https://cdn.polyhaven.com/asset_img/thumbs/$id.png?width=256&height=128",
            compatibility = "360° image only: no geometry, no parallax",
            files = listOf(EnvironmentFile("$id.jpg", url, size, t["md5"]?.jsonPrimitive?.contentOrNull)),
            mainFile = "$id.jpg",
        )
    }

    /** Built-in procedural environment that needs no download (own work). */
    val STUDIO = EnvironmentEntry(
        id = "builtin:studio", name = "HyperMagic Studio", kind = EnvironmentKind.PROCEDURAL,
        author = "HyperMagic", source = "Built-in", sourceUrl = "builtin", license = "MIT", licenseUrl = "LICENSE",
        format = "Procedural", downloadBytes = 0, attribution = "HyperMagic contributors",
        compatibility = "All devices",
    )
}
