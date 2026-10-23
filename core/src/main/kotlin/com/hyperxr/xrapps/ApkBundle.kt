package com.hyperxr.xrapps

import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Split APK bundles (.xapk, .apks, .apkm without encryption): many Daydream and older VR apps are
 * only found like that. HyperXR turns one into a single APK: the base APK plus the native
 * libraries of the split for this phone's ABI (64-bit first). Density and language splits are
 * left out (the base has the default resources); the patcher already makes the splits optional
 * in the manifest. Everything else (detection, patching, signing, installing) then works on it as
 * on any APK.
 */
object ApkBundle {
    /** A zip without a manifest at its root but with .apk files inside. */
    fun isBundle(file: File): Boolean = try {
        ZipFile(file).use { z -> z.getEntry("AndroidManifest.xml") == null && z.entries().asSequence().any { it.name.endsWith(".apk", true) } }
    } catch (_: Throwable) { false }

    class Merged(val apk: File, val base: String, val abiSplit: String?, val skipped: List<String>)

    /** ABI folder name inside lib/ for a split file name like "config.arm64_v8a.apk" or "split_config.armeabi_v7a.apk". */
    fun splitAbi(name: String): String? {
        val n = name.substringAfterLast('/').lowercase()
        return listOf("arm64_v8a" to "arm64-v8a", "armeabi_v7a" to "armeabi-v7a", "x86_64" to "x86_64", "x86" to "x86")
            .firstOrNull { (k, _) -> n.contains(k) && n.endsWith(".apk") }?.second
    }

    fun merge(bundle: File, out: File, deviceAbis: List<String>): Merged = ZipFile(bundle).use { z ->
        val apks = z.entries().asSequence().filter { !it.isDirectory && it.name.endsWith(".apk", true) }.map { it.name }.toList()
        require(apks.isNotEmpty()) { "O pacote não tem APKs" }
        val base = apks.firstOrNull { it.substringAfterLast('/').equals("base.apk", true) }
            ?: apks.firstOrNull { name -> splitAbi(name) == null && !isConfigSplit(name) && !isSplitApk(z, name) }
            ?: apks.first()
        val abiSplit = deviceAbis.firstNotNullOfOrNull { abi -> apks.firstOrNull { splitAbi(it) == abi } }
        val written = HashSet<String>()
        ZipOutputStream(out.outputStream().buffered(1 shl 20)).use { o ->
            copyApk(z, base, o, written) { true }
            if (abiSplit != null) copyApk(z, abiSplit, o, written) { it.startsWith("lib/") }
        }
        Merged(out, base, abiSplit, apks.filter { it != base && it != abiSplit })
    }

    private fun isConfigSplit(name: String) = name.substringAfterLast('/').lowercase().let { it.startsWith("config.") || it.startsWith("split_") }

    /** A split's manifest has a "split" attribute on <manifest>. */
    private fun isSplitApk(z: ZipFile, name: String): Boolean = try {
        ZipInputStream(z.getInputStream(z.getEntry(name))).use { zi ->
            while (true) {
                val e = zi.nextEntry ?: return false
                if (e.name == "AndroidManifest.xml") return BinaryXml.parse(zi.readBytes()).attr("split") != null
            }
            @Suppress("UNREACHABLE_CODE") false
        }
    } catch (_: Throwable) { false }

    private fun copyApk(z: ZipFile, name: String, o: ZipOutputStream, written: MutableSet<String>, take: (String) -> Boolean) {
        ZipInputStream(z.getInputStream(z.getEntry(name)).buffered(1 shl 16)).use { zi ->
            while (true) {
                val e = zi.nextEntry ?: break
                val n = e.name
                if (e.isDirectory || !take(n) || !written.add(n)) continue
                val upper = n.uppercase()
                if (upper.startsWith("META-INF/") && (upper == "META-INF/MANIFEST.MF" || upper.substringAfterLast('.') in setOf("RSA", "DSA", "EC", "SF"))) continue
                val data = zi.readBytes()
                // libraries and resources.arsc stay uncompressed (mapped in place by Android)
                val stored = n.endsWith(".so") || n == "resources.arsc"
                val entry = ZipEntry(n)
                if (stored) {
                    entry.method = ZipEntry.STORED; entry.size = data.size.toLong(); entry.compressedSize = data.size.toLong()
                    entry.crc = java.util.zip.CRC32().apply { update(data) }.value
                }
                o.putNextEntry(entry); o.write(data); o.closeEntry()
            }
        }
    }
}
