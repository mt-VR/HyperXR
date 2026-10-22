package com.hyperxr.sdkhost

import android.content.Context
import android.net.Uri
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.permissions.HyperPermission
import com.hyperxr.permissions.PermissionGrants
import com.hyperxr.permissions.PermissionPolicy
import com.hyperxr.sdk.HyperxManifest
import com.hyperxr.sdk.HyperxPackage
import com.hyperxr.sdk.InspectionResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class StoredGrants(
    val granted: List<String> = emptyList(),
    val denied: List<String> = emptyList(),
    /** Run with the JIT off so the instruction budget is always enforced (default for third-party packages). */
    val strictInterpreter: Boolean = true,
    val bundled: Boolean = false,
)

data class InstalledPackage(
    val manifest: HyperxManifest,
    val dir: File,
    val dataDir: File,
    val grants: PermissionGrants,
    val strictInterpreter: Boolean,
    val bundled: Boolean,
) {
    val pendingPrompts: List<HyperPermission>
        get() = manifest.declaredPermissions.filter { it !in grants.granted && it !in grants.denied }
}

/**
 * Installs and tracks `.hyperx` packages. Every package is inspected (manifest, integrity,
 * paths) before extraction; nothing from a package is executed during installation.
 */
class PackageRepository(private val context: Context) {
    private val root = File(context.filesDir, "packages")
    private val dataRoot = File(context.filesDir, "pkgdata")
    private val grantsRoot = File(context.filesDir, "grants")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /** Extracts packages shipped in assets/packages when missing or when their version changed. */
    @Synchronized
    fun installBundled() {
        // games removed from HyperXR are uninstalled once
        for (id in com.hyperxr.launcher.BuiltinApps.RETIRED_PACKAGES) if (load(id) != null) runCatching { uninstall(id) }
        val assets = context.assets.list("packages").orEmpty().filter { it.endsWith(HyperxPackage.EXTENSION) }
        for (name in assets) {
            val tmp = File(context.cacheDir, name)
            context.assets.open("packages/$name").use { input -> tmp.outputStream().use { input.copyTo(it) } }
            val result = HyperxPackage.inspect(tmp)
            val m = result.manifest
            if (!result.valid || m == null) {
                Diagnostics.log("Bundled package $name invalid: ${result.errors}")
                continue
            }
            val existing = load(m.id)
            if (existing == null || existing.manifest.version != m.version || existing.manifest.integrity != m.integrity) {
                extract(tmp, result, bundled = true)
            }
            tmp.delete()
        }
    }

    /** Copies a user-picked file and inspects it. The caller shows the result before calling [commitInstall]. */
    fun inspectUri(uri: Uri): Pair<File, InspectionResult> {
        val tmp = File(context.cacheDir, "incoming-${System.nanoTime()}.hyperx")
        context.contentResolver.openInputStream(uri)!!.use { input ->
            tmp.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    require(total <= HyperxPackage.MAX_TOTAL_BYTES) { "File too large" }
                    out.write(buf, 0, n)
                }
            }
        }
        return tmp to HyperxPackage.inspect(tmp)
    }

    @Synchronized
    fun commitInstall(file: File, result: InspectionResult): InstalledPackage {
        val p = extract(file, result, bundled = false)
        file.delete()
        return p
    }

    private fun extract(file: File, result: InspectionResult, bundled: Boolean): InstalledPackage {
        val m = result.manifest!!
        val dir = File(root, m.id)
        val staging = File(root, "${m.id}.staging")
        staging.deleteRecursively()
        HyperxPackage.extract(file, result, staging)
        dir.deleteRecursively()
        check(staging.renameTo(dir)) { "Could not install ${m.id}" }
        val previous = readGrants(m.id)
        val initial = PermissionPolicy.initialGrants(m.id, m.declaredPermissions)
        // Keep earlier user decisions for permissions the new version still declares.
        val keptGranted = previous?.granted.orEmpty().mapNotNull { HyperPermission.fromKey(it) }.filter { it in m.declaredPermissions }
        val stored = StoredGrants(
            granted = (initial.granted + keptGranted).map { it.key }.distinct(),
            denied = previous?.denied.orEmpty().filter { HyperPermission.fromKey(it) in m.declaredPermissions },
            strictInterpreter = previous?.strictInterpreter ?: !bundled,
            bundled = bundled,
        )
        writeGrants(m.id, stored)
        Diagnostics.log("Installed ${m.id} ${m.version}${if (bundled) " (bundled)" else ""}")
        return load(m.id)!!
    }

    fun list(): List<InstalledPackage> =
        root.listFiles().orEmpty().filter { it.isDirectory && !it.name.endsWith(".staging") }.mapNotNull { load(it.name) }.sortedBy { it.manifest.name }

    fun load(id: String): InstalledPackage? {
        val dir = File(root, id)
        val mf = File(dir, HyperxManifest.FILE_NAME)
        if (!mf.isFile) return null
        val m = try { HyperxManifest.parse(mf.readText()) } catch (e: Exception) { return null }
        val stored = readGrants(id) ?: StoredGrants()
        val grants = PermissionGrants(
            id,
            stored.granted.mapNotNull { HyperPermission.fromKey(it) }.filter { it in m.declaredPermissions }.toSet(),
            stored.denied.mapNotNull { HyperPermission.fromKey(it) }.toSet(),
        )
        return InstalledPackage(m, dir, File(dataRoot, id).apply { mkdirs() }, grants, stored.strictInterpreter, stored.bundled)
    }

    /** Applies a user decision through [PermissionPolicy] (which refuses undeclared and unconfirmed unsafe grants). */
    @Synchronized
    fun decide(id: String, permission: HyperPermission, approve: Boolean, explicitConfirmation: Boolean = false): InstalledPackage {
        val p = load(id) ?: error("Package $id not installed")
        val next = PermissionPolicy.grant(p.grants, p.manifest.declaredPermissions, permission, approve, explicitConfirmation, integrityVerified = true)
        val stored = (readGrants(id) ?: StoredGrants()).copy(granted = next.granted.map { it.key }, denied = next.denied.map { it.key })
        writeGrants(id, stored)
        Diagnostics.log("${if (approve) "Granted" else "Denied"} ${permission.key} for $id")
        return load(id)!!
    }

    @Synchronized
    fun setStrict(id: String, strict: Boolean) {
        writeGrants(id, (readGrants(id) ?: StoredGrants()).copy(strictInterpreter = strict))
    }

    @Synchronized
    fun uninstall(id: String) {
        File(root, id).deleteRecursively()
        File(dataRoot, id).deleteRecursively()
        File(grantsRoot, "$id.json").delete()
    }

    private fun readGrants(id: String): StoredGrants? {
        val f = File(grantsRoot, "$id.json")
        return if (f.isFile) try { json.decodeFromString(StoredGrants.serializer(), f.readText()) } catch (e: Exception) { null } else null
    }

    private fun writeGrants(id: String, g: StoredGrants) {
        grantsRoot.mkdirs()
        File(grantsRoot, "$id.json").writeText(json.encodeToString(StoredGrants.serializer(), g))
    }
}
