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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
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

    /**
     * Poly Haven panoramas (CC0) shipped inside the APK, already downloaded: listed in
     * assets/environments/polyhaven.json by tools/fetch_environments.py, each with its preview.
     */
    val bundledPolyHaven: List<EnvironmentEntry> by lazy {
        runCatching {
            val text = context.assets.open("environments/polyhaven.json").use { it.readBytes().toString(Charsets.UTF_8) }
            kotlinx.serialization.json.Json.parseToJsonElement(text).jsonArray.map { e ->
                val o = e.jsonObject
                val id = o["id"]!!.jsonPrimitive.content
                o["ambient"]?.jsonArray?.let { a -> ambientColors["bundled:ph_$id"] = FloatArray(3) { a[it].jsonPrimitive.content.toFloat() } }
                EnvironmentEntry(
                    id = "bundled:ph_$id", name = o["name"]!!.jsonPrimitive.content, kind = EnvironmentKind.PANORAMA_360,
                    author = o["authors"]?.jsonArray?.joinToString(", ") { it.jsonPrimitive.content } ?: "Poly Haven",
                    source = "Poly Haven (incluído no HyperXR)", sourceUrl = o["source"]!!.jsonPrimitive.content,
                    license = "CC0-1.0", licenseUrl = PolyHavenCatalog.LICENSE_URL, format = "Equirectangular JPEG 4096x2048",
                    downloadBytes = 0, compatibility = "360° image only: no geometry, no parallax", mainFile = "environments/ph_$id.jpg",
                    previewUrl = "asset:environments/thumbs/ph_$id.jpg",
                )
            }
        }.getOrDefault(emptyList())
    }

    private val thumbCache = java.util.concurrent.ConcurrentHashMap<String, android.graphics.Bitmap>()

    /**
     * A picture of what the environment looks like (512x256): the shipped preview, or one made
     * once from a downloaded panorama, or the source's preview image; null for the procedural
     * studio. May touch the network or decode a large image: call off the GL thread.
     */
    fun preview(e: EnvironmentEntry): android.graphics.Bitmap? {
        thumbCache[e.id]?.let { return it }
        val bmp: android.graphics.Bitmap? = runCatching {
            when {
                e.previewUrl?.startsWith("asset:") == true -> context.assets.open(e.previewUrl!!.removePrefix("asset:")).use { BitmapFactory.decodeStream(it) }
                isBundled(e) -> runCatching { context.assets.open("environments/thumbs/" + e.mainFile.substringAfterLast('/')).use { BitmapFactory.decodeStream(it) } }.getOrNull()
                e.kind == EnvironmentKind.PANORAMA_360 && e.mainFile.isNotEmpty() -> {
                    val dir = dirOf(e.id); val cached = File(dir, "preview.jpg")
                    if (!cached.isFile) {
                        val src = File(dir, e.mainFile)
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }; BitmapFactory.decodeFile(src.path, o)
                        val big = BitmapFactory.decodeFile(src.path, BitmapFactory.Options().apply { inSampleSize = maxOf(1, o.outWidth / 1024) })
                        android.graphics.Bitmap.createScaledBitmap(big, 512, 256, true).compress(android.graphics.Bitmap.CompressFormat.JPEG, 82, cached.outputStream())
                    }
                    BitmapFactory.decodeFile(cached.path)
                }
                e.previewUrl?.startsWith("http") == true -> {
                    val cached = File(context.cacheDir, "envprev_" + e.id.replace(Regex("[^A-Za-z0-9]"), "_") + ".png")
                    if (!cached.isFile) (URL(e.previewUrl).openConnection() as HttpURLConnection).run {
                        setRequestProperty("User-Agent", USER_AGENT); connectTimeout = 15000; readTimeout = 30000
                        inputStream.use { i -> cached.outputStream().use { i.copyTo(it) } }
                    }
                    BitmapFactory.decodeFile(cached.path)
                }
                else -> null
            }
        }.getOrNull()
        bmp?.let { thumbCache[e.id] = it }
        return bmp
    }

    /** Preview of a catalog item (network). */
    fun preview(item: CatalogItem): android.graphics.Bitmap? = preview(EnvironmentEntry(
        id = "catalog:${item.id}", name = item.name, kind = EnvironmentKind.SCENE_3D, author = item.author, source = "Poly Haven",
        sourceUrl = "", license = "CC0-1.0", licenseUrl = "", format = "", downloadBytes = 0, previewUrl = item.previewUrl, compatibility = ""))

    fun installed(): List<EnvironmentEntry> = listOf(PolyHavenCatalog.STUDIO) + BUNDLED + bundledPolyHaven + root.listFiles().orEmpty().mapNotNull { dir ->
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

    /**
     * Saves the user's own 360 photo as an environment. It never leaves the device, so no licence
     * check applies; the entry records that it is personal content.
     */
    fun importPanorama(name: String, input: java.io.InputStream, source: String = "Galeria do aparelho"): EnvironmentEntry {
        val id = "personal_" + System.currentTimeMillis()
        val dir = dirOf(id)
        dir.mkdirs()
        val out = File(dir, "panorama.jpg")
        out.outputStream().use { input.copyTo(it, 256 * 1024) }
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(out.path, o)
        require(o.outWidth > 0) { "A imagem não pôde ser lida" }
        val entry = EnvironmentEntry(
            id = id, name = name.ifBlank { "Minha foto 360" }, kind = EnvironmentKind.PANORAMA_360, author = "Você",
            source = source, sourceUrl = "", license = "Pessoal", licenseUrl = "", format = "jpg",
            downloadBytes = out.length(), compatibility = "${o.outWidth}x${o.outHeight}", mainFile = out.name,
        )
        File(dir, "entry.json").writeText(json.encodeToString(EnvironmentEntry.serializer(), entry))
        File(dir, "complete").writeText("ok")
        Diagnostics.log("Environment imported ($source): ${entry.name}")
        return entry
    }

    fun delete(entry: EnvironmentEntry) { if (entry.kind != EnvironmentKind.PROCEDURAL && !isBundled(entry)) dirOf(entry.id).deleteRecursively() }

    /** Loads an installed environment for the renderer (GL thread). */
    fun loadVisual(entry: EnvironmentEntry): SceneRenderer.EnvironmentVisual {
        if (entry.kind == EnvironmentKind.PROCEDURAL) return SceneRenderer.EnvironmentVisual.Studio
        val dir = dirOf(entry.id)
        return try {
            when (entry.kind) {
                EnvironmentKind.PANORAMA_360 -> {
                    // at most ~4k wide keeps memory reasonable (an 8k HDRI JPEG is halved)
                    val bmp = if (isBundled(entry)) {
                        context.assets.open(entry.mainFile).use { BitmapFactory.decodeStream(it) } ?: error("Cannot decode panorama")
                    } else {
                        val path = File(dir, entry.mainFile).absolutePath
                        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeFile(path, bounds)
                        var sample = 1
                        while (bounds.outWidth / sample > 4096) sample *= 2
                        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                        BitmapFactory.decodeFile(path, opts) ?: error("Cannot decode panorama")
                    }
                    if (!ambientColors.containsKey(entry.id)) ambientColors[entry.id] = averageColor(bmp)
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

    /** Average colour (0..1 RGB) of each panorama loaded so far, for lighting that matches it. */
    val ambientColors = java.util.concurrent.ConcurrentHashMap<String, FloatArray>(BUNDLED_AMBIENT)

    private fun averageColor(bmp: android.graphics.Bitmap): FloatArray {
        val small = android.graphics.Bitmap.createScaledBitmap(bmp, 32, 16, true)
        var r = 0L; var g = 0L; var b = 0L
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y); r += (c shr 16) and 0xff; g += (c shr 8) and 0xff; b += c and 0xff
        }
        if (small !== bmp) small.recycle()
        val n = 32f * 16f * 255f
        return floatArrayOf(r / n, g / n, b / n)
    }

    private fun get(url: String): String = (URL(url).openConnection() as HttpURLConnection).run {
        setRequestProperty("User-Agent", USER_AGENT)
        connectTimeout = 15000; readTimeout = 30000
        require(responseCode == 200) { "HTTP $responseCode for $url" }
        inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    companion object {
        /** 360 panoramas shipped in the APK (assets/environments), sent by the project owner. */
        val BUNDLED: List<EnvironmentEntry> = listOf(
            bundled("night_street", "Rua roxa à noite", 1774),
            bundled("future_plaza", "Praça futurista", 1774),
            bundled("vr_studio", "Estúdio de criação VR", 1774),
            bundled("penthouse_sunset", "Cobertura ao pôr do sol", 2720),
        )

        /** Average colour of each bundled panorama (0..1 RGB), for ambient lighting. */
        val BUNDLED_AMBIENT: Map<String, FloatArray> = mapOf(
            "bundled:night_street" to floatArrayOf(30 / 255f, 8 / 255f, 62 / 255f),
            "bundled:future_plaza" to floatArrayOf(138 / 255f, 149 / 255f, 164 / 255f),
            "bundled:vr_studio" to floatArrayOf(105 / 255f, 112 / 255f, 143 / 255f),
            "bundled:penthouse_sunset" to floatArrayOf(161 / 255f, 127 / 255f, 102 / 255f),
        )

        fun isBundled(entry: EnvironmentEntry) = entry.id.startsWith("bundled:")

        private fun bundled(file: String, name: String, width: Int) = EnvironmentEntry(
            id = "bundled:$file", name = name, kind = EnvironmentKind.PANORAMA_360, author = "Enviado pelo dono do projeto",
            source = "Incluído no HyperXR", sourceUrl = "builtin", license = "Fornecido pelo usuário", licenseUrl = "",
            format = "jpg", downloadBytes = 0, compatibility = "${width}x${width / 2}", mainFile = "environments/$file.jpg",
        )

        const val USER_AGENT = "HyperXR/0.1 (Android; +https://github.com/mt-VR/HyperXR)"
    }
}
