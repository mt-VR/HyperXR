package com.hyperxr.sdk

import com.hyperxr.permissions.HyperPermission
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class InspectionResult(
    val manifest: HyperxManifest?,
    val errors: List<String>,
    val warnings: List<String>,
    val files: List<String>,
    val uncompressedBytes: Long,
) {
    val valid: Boolean get() = manifest != null && errors.isEmpty()
}

/**
 * Reads and validates `.hyperx` packages (ZIP containers). Inspection never executes package
 * content: it only parses the JSON manifest, walks the entry table and hashes bytes.
 */
object HyperxPackage {
    const val EXTENSION = ".hyperx"
    const val MAX_ENTRIES = 4096
    const val MAX_FILE_BYTES = 256L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 768L * 1024 * 1024
    const val MAX_COMPRESSION_RATIO = 200
    const val MAX_MANIFEST_BYTES = 256 * 1024

    private val ID_REGEX = Regex("^[a-z][a-z0-9_]*(\\.[a-z0-9_]+)+$")
    private val VERSION_REGEX = Regex("^\\d+\\.\\d+\\.\\d+([-+][0-9A-Za-z.-]+)?$")

    /** Rejects absolute paths, traversal, backslashes, drive letters and control characters. */
    fun isSafeEntryName(name: String): Boolean {
        if (name.isEmpty() || name.length > 512) return false
        if (name.startsWith("/") || name.contains('\\') || name.contains(':')) return false
        if (name.any { it.code < 0x20 || it.code == 0x7f }) return false
        return name.split('/').none { it == ".." || it == "." } && !name.contains("//")
    }

    fun inspect(file: File): InspectionResult = try {
        ZipFile(file).use { inspect(it) }
    } catch (e: Exception) {
        InspectionResult(null, listOf("Not a readable ZIP container: ${e.message}"), emptyList(), emptyList(), 0)
    }

    fun inspect(zip: ZipFile): InspectionResult {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()
        val names = mutableListOf<String>()
        val seen = HashSet<String>()
        var total = 0L
        var count = 0

        for (entry in zip.entries()) {
            if (++count > MAX_ENTRIES) { errors += "Too many entries (> $MAX_ENTRIES)"; break }
            val name = entry.name
            if (!isSafeEntryName(name)) { errors += "Unsafe entry path: '$name'"; continue }
            if (!seen.add(name)) { errors += "Duplicate entry: '$name'"; continue }
            if (entry.isDirectory) continue
            val size = entry.size
            if (size < 0 || size > MAX_FILE_BYTES) errors += "Entry too large or unknown size: '$name'"
            if (entry.compressedSize > 0 && size / entry.compressedSize > MAX_COMPRESSION_RATIO) {
                errors += "Suspicious compression ratio for '$name'"
            }
            total += maxOf(size, 0)
            names += name
        }
        if (total > MAX_TOTAL_BYTES) errors += "Package expands to more than $MAX_TOTAL_BYTES bytes"

        val manifestEntry = zip.getEntry(HyperxManifest.FILE_NAME)
        if (manifestEntry == null) {
            errors += "Missing ${HyperxManifest.FILE_NAME}"
            return InspectionResult(null, errors, warnings, names, total)
        }
        val manifestText = try {
            readBounded(zip.getInputStream(manifestEntry), MAX_MANIFEST_BYTES).toString(Charsets.UTF_8)
        } catch (e: Exception) {
            errors += "Unreadable manifest: ${e.message}"
            return InspectionResult(null, errors, warnings, names, total)
        }
        val manifest = try {
            HyperxManifest.parse(manifestText)
        } catch (e: Exception) {
            errors += "Invalid manifest JSON: ${e.message?.lineSequence()?.firstOrNull()}"
            return InspectionResult(null, errors, warnings, names, total)
        }

        validateManifest(manifest, names.toSet(), errors, warnings)
        if (errors.isEmpty()) verifyIntegrity(zip, manifest, names, errors)
        return InspectionResult(manifest, errors, warnings, names, total)
    }

    fun validateManifest(m: HyperxManifest, files: Set<String>, errors: MutableList<String>, warnings: MutableList<String>) {
        if (m.format != HyperxManifest.SUPPORTED_FORMAT) errors += "Unsupported package format ${m.format}"
        if (!ID_REGEX.matches(m.id)) errors += "Invalid id '${m.id}' (expected reverse-domain, lowercase)"
        if (m.name.isBlank() || m.name.length > 64) errors += "Name must be 1..64 characters"
        if (!VERSION_REGEX.matches(m.version)) errors += "Version '${m.version}' is not semantic (x.y.z)"
        if (m.sdk > HyperxManifest.SDK_LEVEL) errors += "Requires SDK level ${m.sdk}, runtime supports ${HyperxManifest.SDK_LEVEL}"
        if (m.mode != "scene" && m.mode != "window") errors += "mode must be 'scene' or 'window'"
        if (!m.entry.endsWith(".lua") || !isSafeEntryName(m.entry)) errors += "Entry '${m.entry}' must be a .lua path"
        else if (m.entry !in files) errors += "Entry script '${m.entry}' not found in package"
        m.icon?.let { if (it !in files) warnings += "Icon '$it' not found" }
        for (p in m.permissions) if (HyperPermission.fromKey(p) == null) errors += "Unknown permission '$p'"
        if (m.permissions.size != m.permissions.toSet().size) warnings += "Duplicate permissions declared"
        if ("network" in m.permissions && m.network?.allowedHosts.isNullOrEmpty()) {
            errors += "Permission 'network' requires network.allowedHosts"
        }
        m.network?.allowedHosts?.forEach { h ->
            if (!Regex("^[a-z0-9.-]+$").matches(h) || h.startsWith(".") || !h.contains('.')) errors += "Invalid allowed host '$h'"
        }
        if ("unsafe" in m.permissions) warnings += "Package requests 'unsafe': it will run without sandbox protections if granted"
        if (m.integrity.algorithm != "sha256") errors += "Unsupported integrity algorithm '${m.integrity.algorithm}'"
    }

    private fun verifyIntegrity(zip: ZipFile, m: HyperxManifest, names: List<String>, errors: MutableList<String>) {
        val listed = m.integrity.files
        val content = names.filter { it != HyperxManifest.FILE_NAME }
        for (name in content) if (name !in listed) errors += "File '$name' is not covered by integrity.files"
        for ((name, expected) in listed) {
            val entry = zip.getEntry(name)
            if (entry == null) { errors += "Integrity lists missing file '$name'"; continue }
            val actual = sha256(zip.getInputStream(entry), MAX_FILE_BYTES)
            if (!actual.equals(expected, ignoreCase = true)) errors += "Hash mismatch for '$name'"
        }
    }

    fun sha256(input: InputStream, limit: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(64 * 1024)
        var read = 0L
        input.use {
            while (true) {
                val n = it.read(buf)
                if (n < 0) break
                read += n
                require(read <= limit) { "stream exceeds $limit bytes" }
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun readBounded(input: InputStream, limit: Int): ByteArray = input.use {
        val bytes = it.readNBytes(limit + 1)
        require(bytes.size <= limit) { "file exceeds $limit bytes" }
        bytes
    }

    /**
     * Builds a package from a source directory containing `manifest.json` (without integrity).
     * Integrity hashes are computed for every file. Used by the `hyperx pack` tool and the build.
     */
    fun pack(sourceDir: File, output: File): HyperxManifest {
        val manifestFile = File(sourceDir, HyperxManifest.FILE_NAME)
        require(manifestFile.isFile) { "No manifest.json in $sourceDir" }
        val base = HyperxManifest.parse(manifestFile.readText())
        val files = sourceDir.walkTopDown()
            .filter { it.isFile && it.name != HyperxManifest.FILE_NAME && !it.name.startsWith(".") }
            .map { it.relativeTo(sourceDir).invariantSeparatorsPath to it }
            .sortedBy { it.first }
            .toList()
        val hashes = files.associate { (rel, f) -> rel to sha256(f.inputStream(), MAX_FILE_BYTES) }
        val manifest = base.copy(integrity = IntegritySpec("sha256", hashes))
        output.parentFile?.mkdirs()
        ZipOutputStream(output.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry(HyperxManifest.FILE_NAME).apply { time = 0 })
            zip.write(HyperxManifest.encode(manifest).toByteArray())
            zip.closeEntry()
            for ((rel, f) in files) {
                zip.putNextEntry(ZipEntry(rel).apply { time = 0 })
                f.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
        return manifest
    }

    /** Extracts a validated package. Refuses to run on an invalid inspection result. */
    fun extract(zipFile: File, inspection: InspectionResult, dest: File) {
        require(inspection.valid) { "Refusing to extract invalid package" }
        val root = dest.canonicalFile
        ZipFile(zipFile).use { zip ->
            for (name in inspection.files) {
                val out = File(root, name).canonicalFile
                require(out.path.startsWith(root.path + File.separator)) { "Path escapes destination: $name" }
                out.parentFile.mkdirs()
                zip.getInputStream(zip.getEntry(name)).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
            File(root, HyperxManifest.FILE_NAME).writeText(
                zip.getInputStream(zip.getEntry(HyperxManifest.FILE_NAME)).use { it.readBytes().toString(Charsets.UTF_8) },
            )
        }
    }
}
