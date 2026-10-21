package com.hyperxr.environments

import android.content.Context
import android.graphics.BitmapFactory
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.gltf.GltfLoader
import com.hyperxr.math.Mat4
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.rendering.Gl
import com.hyperxr.rendering.GpuModel
import com.hyperxr.rendering.SceneRenderer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/** Lightweight catalog row before the per-asset file list is fetched. */
data class CatalogItem(val id: String, val name: String, val kind: EnvironmentKind, val author: String, val previewUrl: String, val asset: JsonObject, val popularity: Long)

/**
 * HyperXR Environments: browse, download and load licensed environments.
 * Source: Poly Haven public API (every asset CC0, see https://polyhaven.com/license). Each entry
 * still passes [LicensePolicy] before download, and its licence/author/source are stored with it.
 */
class EnvironmentRepository(private val context: Context) {
    private val root = File(context.filesDir, "environments").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

    fun installed(): List<EnvironmentEntry> = listOf(PolyHavenCatalog.STUDIO) + root.listFiles().orEmpty().mapNotNull { dir ->
        val f = File(dir, "entry.json")
        if (!f.isFile || !File(dir, "complete").exists()) null else try { json.decodeFromString(EnvironmentEntry.serializer(), f.readText()) } catch (e: Exception) { null }
    }

    fun dirOf(id: String) = File(root, id.replace(Regex("[^A-Za-z0-9_.-]"), "_"))

    /** Fetches the catalog of environment-scale models and 360° panoramas (network thread). */
    fun fetchCatalog(maxPanoramas: Int = 30): List<CatalogItem> {
        val items = mutableListOf<CatalogItem>()
        for (cat in PolyHavenCatalog.ENVIRONMENT_CATEGORIES) {
            val text = get("${PolyHavenCatalog.API}/assets?t=models&c=$cat")
            for ((id, asset) in PolyHavenCatalog.parseAssetList(text, EnvironmentKind.SCENE_3D)) {
                if (items.any { it.id == id }) continue
                items += CatalogItem(id, asset["name"]?.jsonPrimitive?.content ?: id, EnvironmentKind.SCENE_3D, PolyHavenCatalog.authorOf(asset),
                    "https://cdn.polyhaven.com/asset_img/thumbs/$id.png?width=256&height=256", asset,
                    asset["download_count"]?.jsonPrimitive?.longOrNull ?: 0)
            }
        }
        val hdris = PolyHavenCatalog.parseAssetList(get("${PolyHavenCatalog.API}/assets?t=hdris"), EnvironmentKind.PANORAMA_360)
            .sortedByDescending { it.second["download_count"]?.jsonPrimitive?.longOrNull ?: 0 }.take(maxPanoramas)
        for ((id, asset) in hdris) {
            items += CatalogItem(id, asset["name"]?.jsonPrimitive?.content ?: id, EnvironmentKind.PANORAMA_360, PolyHavenCatalog.authorOf(asset),
                "https://cdn.polyhaven.com/asset_img/thumbs/$id.png?width=256&height=128", asset,
                asset["download_count"]?.jsonPrimitive?.longOrNull ?: 0)
        }
        return items.sortedByDescending { it.popularity }
    }

    /** Resolves full metadata (files, sizes, licence) for one catalog item. */
    fun resolve(item: CatalogItem): EnvironmentEntry? {
        val files = get("${PolyHavenCatalog.API}/files/${item.id}")
        return if (item.kind == EnvironmentKind.PANORAMA_360) PolyHavenCatalog.panoramaEntry(item.id, item.asset, files)
        else PolyHavenCatalog.modelEntry(item.id, item.asset, files)
    }

    fun isUnmeteredNetwork(): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** Downloads every file of [entry], verifying MD5 when the source publishes it. */
    fun download(entry: EnvironmentEntry, wifiOnly: Boolean, progress: (Long, Long) -> Unit): Result<EnvironmentEntry> = runCatching {
        val problems = LicensePolicy.validate(entry)
        require(problems.isEmpty()) { "Rejected by licence policy: ${problems.joinToString()}" }
        require(!wifiOnly || isUnmeteredNetwork()) { "Downloads are limited to Wi-Fi/unmetered networks (Settings > Network)" }
        val dir = dirOf(entry.id)
        dir.deleteRecursively(); dir.mkdirs()
        var done = 0L
        for (f in entry.files) {
            require(com.hyperxr.sdk.HyperxPackage.isSafeEntryName(f.path)) { "Unsafe file path ${f.path}" }
            val out = File(dir, f.path).apply { parentFile?.mkdirs() }
            val md5 = MessageDigest.getInstance("MD5")
            (URL(f.url).openConnection() as HttpURLConnection).run {
                setRequestProperty("User-Agent", USER_AGENT)
                connectTimeout = 15000; readTimeout = 30000
                require(responseCode == 200) { "HTTP $responseCode for ${f.url}" }
                inputStream.use { input ->
                    out.outputStream().use { o ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            o.write(buf, 0, n); md5.update(buf, 0, n)
                            done += n
                            progress(done, entry.downloadBytes)
                        }
                    }
                }
            }
            f.md5?.let { expected ->
                val actual = md5.digest().joinToString("") { "%02x".format(it) }
                require(actual.equals(expected, true)) { "Checksum mismatch for ${f.path}" }
            }
        }
        File(dir, "entry.json").writeText(json.encodeToString(EnvironmentEntry.serializer(), entry))
        File(dir, "ATTRIBUTION.txt").writeText("${entry.name}\nAuthor: ${entry.author}\nSource: ${entry.sourceUrl}\nLicense: ${entry.license} (${entry.licenseUrl})\n${entry.attribution ?: ""}")
        File(dir, "complete").writeText("ok")
        Diagnostics.log("Environment downloaded: ${entry.name} (${entry.license})")
        entry
    }

    fun delete(entry: EnvironmentEntry) { if (entry.kind != EnvironmentKind.PROCEDURAL) dirOf(entry.id).deleteRecursively() }

    /** Loads an installed environment for the renderer (GL thread). */
    fun loadVisual(entry: EnvironmentEntry): SceneRenderer.EnvironmentVisual {
        if (entry.kind == EnvironmentKind.PROCEDURAL) return SceneRenderer.EnvironmentVisual.Studio
        val dir = dirOf(entry.id)
        return try {
            when (entry.kind) {
                EnvironmentKind.PANORAMA_360 -> {
                    val opts = BitmapFactory.Options().apply { inSampleSize = 2 } // 8k JPEG -> 4k keeps memory reasonable
                    val bmp = BitmapFactory.decodeFile(File(dir, entry.mainFile).absolutePath, opts) ?: error("Cannot decode panorama")
                    val tex = Gl.uploadBitmap(bmp, mipmap = true)
                    bmp.recycle()
                    SceneRenderer.EnvironmentVisual.Panorama(tex)
                }
                else -> {
                    val main = File(dir, entry.mainFile)
                    val model = GltfLoader.load(main.readBytes()) { rel ->
                        val f = File(main.parentFile, rel).canonicalFile
                        require(f.path.startsWith(dir.canonicalPath)) { "Resource outside environment: $rel" }
                        f.readBytes()
                    }
                    model.warnings.forEach { Diagnostics.log("Environment ${entry.name}: $it") }
                    val gpu = GpuModel(model)
                    // centre on the origin, sit on the floor, place in front of the spawn point
                    val m = FloatArray(16)
                    val s = entry.importScale
                    val cx = (model.boundsMin.x + model.boundsMax.x) / 2 * s
                    val cz = (model.boundsMin.z + model.boundsMax.z) / 2 * s
                    Mat4.compose(Vec3(-cx, -model.boundsMin.y * s, -cz - 2.5f), Quat(), s, s, s, m)
                    SceneRenderer.EnvironmentVisual.Model(gpu, m)
                }
            }
        } catch (t: Throwable) {
            Diagnostics.log("Environment ${entry.name} failed to load: ${t.message}")
            SceneRenderer.EnvironmentVisual.Studio
        }
    }

    private fun get(url: String): String = (URL(url).openConnection() as HttpURLConnection).run {
        setRequestProperty("User-Agent", USER_AGENT)
        connectTimeout = 15000; readTimeout = 30000
        require(responseCode == 200) { "HTTP $responseCode for $url" }
        inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    companion object {
        const val USER_AGENT = "HyperXR/0.1 (Android; +https://github.com/mt-VR/HyperXR)"
    }
}
