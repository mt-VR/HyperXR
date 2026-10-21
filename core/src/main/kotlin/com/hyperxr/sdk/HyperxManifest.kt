package com.hyperxr.sdk

import com.hyperxr.permissions.HyperPermission
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class WindowSpec(val width: Float = 0.9f, val height: Float = 0.6f, val resizable: Boolean = true)

@Serializable
data class NetworkSpec(val allowedHosts: List<String> = emptyList())

@Serializable
data class IntegritySpec(val algorithm: String = "sha256", val files: Map<String, String> = emptyMap())

/** `manifest.json` at the root of every `.hyperx` package. See docs/SDK.md. */
@Serializable
data class HyperxManifest(
    val format: Int = 1,
    val id: String,
    val name: String,
    val version: String,
    /** HyperXR SDK API level the package targets. */
    val sdk: Int = 1,
    /** "scene" or "window". */
    val mode: String,
    val entry: String = "main.lua",
    val permissions: List<String> = emptyList(),
    val author: String = "",
    val license: String = "",
    val description: String = "",
    val icon: String? = null,
    val window: WindowSpec? = null,
    val network: NetworkSpec? = null,
    val integrity: IntegritySpec = IntegritySpec(),
) {
    val declaredPermissions: Set<HyperPermission>
        get() = permissions.mapNotNull { HyperPermission.fromKey(it) }.toSet()

    companion object {
        const val FILE_NAME = "manifest.json"
        const val SUPPORTED_FORMAT = 1
        const val SDK_LEVEL = 1
        val json = Json { ignoreUnknownKeys = false; encodeDefaults = false; prettyPrint = true }

        fun parse(text: String): HyperxManifest = json.decodeFromString(serializer(), text)
        fun encode(m: HyperxManifest): String = json.encodeToString(serializer(), m)
    }
}
