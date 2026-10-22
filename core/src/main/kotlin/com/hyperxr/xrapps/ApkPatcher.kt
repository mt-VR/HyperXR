package com.hyperxr.xrapps

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * Libraries the patcher puts into games, per ABI folder: the Khronos OpenXR loader (asks the
 * runtime broker, so it finds HyperXR's runtime) and the VrApi -> OpenXR adapter for Gear VR /
 * early Quest games (Compatibility-Layer gearvr-shim).
 */
class CompatLibraries(
    val loaders: Map<String, File>,
    val vrapiShims: Map<String, File>,
)

data class PatchReport(
    val changes: List<String>,
    val warnings: List<String>,
    /** Problems that make the patched game useless; nothing is written when not empty. */
    val blockers: List<String>,
    val packageName: String,
    val isVrApiGame: Boolean,
    val questGame: Boolean,
)

/**
 * Prepares an OpenXR / Quest / Gear VR APK for the HyperXR runtime, on the phone (port of
 * Compatibility-Layer's compatibility_layer_port.py, extended):
 *  - manifest: targetSdk capped at 29 (the game sees the runtime broker and runtime without
 *    `<queries>`), headset-only `uses-feature` made optional, Play split requirements removed;
 *  - Meta's OpenXR loader (or an unknown one) replaced by the Khronos loader;
 *  - VrApi games: libvrapi.so replaced by the VrApi -> OpenXR adapter, loader added next to it;
 *  - old signatures removed (the caller signs the result).
 */
object ApkPatcher {
    const val MAX_TARGET_SDK = 29
    private val ABI_DIRS = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    private val ENTITLEMENT = setOf("libovrplatformloader.so", "libOVRPlatformLoader.so", "libovrplatform.so", "libOVRPlatform.so")
    private val HEADSET_FEATURES = listOf("oculus.", "com.oculus.", "android.hardware.vr", "wave.feature", "picovr", "com.samsung.android.vr")

    /** ABI folder in lib/ -> key of [CompatLibraries] maps ("armeabi" games load 32-bit ARM libraries too). */
    fun libKey(abiDir: String) = if (abiDir == "armeabi") "armeabi-v7a" else abiDir

    /** Edits the binary manifest in place; returns the new bytes and what changed. */
    fun patchManifest(manifest: ByteArray): Pair<ByteArray, List<String>> {
        val out = manifest.copyOf()
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val changes = ArrayList<String>()
        val slots = BinaryXml.slots(manifest)
        for (s in slots) {
            when {
                s.element == "uses-sdk" && s.name == "targetSdkVersion" && s.rawString == null &&
                    (s.dataType == BinaryXml.TYPE_INT_DEC || s.dataType == BinaryXml.TYPE_INT_HEX) && s.data > MAX_TARGET_SDK -> {
                    bb.putInt(s.dataOffset, MAX_TARGET_SDK)
                    changes += "targetSdk ${s.data} → $MAX_TARGET_SDK (o jogo enxerga o broker e o runtime)"
                }
                s.name == "isSplitRequired" && s.dataType == BinaryXml.TYPE_INT_BOOLEAN && s.data != 0 -> {
                    bb.putInt(s.dataOffset, 0)
                    changes += "APKs adicionais da Play Store deixam de ser obrigatórios"
                }
                s.element == "uses-feature" && s.name == "required" && s.dataType == BinaryXml.TYPE_INT_BOOLEAN && s.data != 0 -> {
                    val feature = slots.firstOrNull { it.elementIndex == s.elementIndex && it.name == "name" }?.rawString ?: ""
                    if (HEADSET_FEATURES.any { feature.startsWith(it) }) {
                        bb.putInt(s.dataOffset, 0)
                        changes += "$feature deixa de ser obrigatório"
                    }
                }
            }
        }
        return out to changes
    }

    /** Plans and writes the unsigned patched APK to [output]. With [dryRun] only the report is made. */
    fun patch(input: File, output: File, libs: CompatLibraries, dryRun: Boolean = false): PatchReport {
        ZipFile(input).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val names = entries.map { it.name }.toSet()
            val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: error("APK sem AndroidManifest.xml")
            val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
            val manifest = BinaryXml.parse(manifestBytes)
            val pkg = manifest.attr("package") ?: ""
            val (newManifest, manifestChanges) = patchManifest(manifestBytes)
            val changes = ArrayList(manifestChanges)
            val warnings = ArrayList<String>()
            val blockers = ArrayList<String>()

            val abiDirs = ABI_DIRS.filter { abi -> names.any { it.startsWith("lib/$abi/") } }
            if (abiDirs.isEmpty()) blockers += "O APK não tem bibliotecas ARM (arm64-v8a/armeabi-v7a): não roda num celular"
            if (abiDirs.isNotEmpty() && "arm64-v8a" !in abiDirs) warnings += "Jogo 32 bits: o runtime HyperXR é 64 bits, então ele só roda com outro runtime OpenXR de 32 bits"
            val libNames = names.filter { it.startsWith("lib/") }.map { it.substringAfterLast('/') }.toSet()
            ENTITLEMENT.intersect(libNames).takeIf { it.isNotEmpty() }?.let {
                warnings += "Verificação de compra da Meta (${it.joinToString()}): se o jogo exigir, ele fecha ao iniciar"
            }
            val isVrApi = abiDirs.any { "lib/$it/libvrapi.so" in names }

            // what replaces what
            val replace = HashMap<String, File>()
            val add = LinkedHashMap<String, File>()
            for (abi in abiDirs) {
                val key = libKey(abi)
                val loaderPath = "lib/$abi/libopenxr_loader.so"
                val loader = libs.loaders[key]
                if (loaderPath in names && loader != null) {
                    val scan = StringScan()
                    zip.getInputStream(zip.getEntry(loaderPath)).use { scan.feed(it) }
                    if (!scan.has("org.khronos.openxr.runtime_broker")) {
                        replace[loaderPath] = loader
                        changes += "Loader OpenXR da Meta ($abi) trocado pelo loader da Khronos"
                    }
                }
                val vrapiPath = "lib/$abi/libvrapi.so"
                if (vrapiPath in names) {
                    val shim = libs.vrapiShims[key]
                    if (shim == null || loader == null) blockers += "Jogo VrApi ($abi) sem adaptador disponível para essa arquitetura"
                    else {
                        replace[vrapiPath] = shim
                        changes += "libvrapi.so ($abi) trocada pelo adaptador VrApi → OpenXR"
                        if (loaderPath !in names) { add[loaderPath] = loader; changes += "Loader OpenXR da Khronos adicionado ($abi)" }
                    }
                }
            }
            val questGame = QUEST_HINTS.any { hint -> manifest.descendants("category").any { it.attr("name") == hint } } ||
                manifest.all("application").firstOrNull()?.all("meta-data")?.any { it.attr("name") == "com.oculus.supportedDevices" } == true
            val report = PatchReport(changes, warnings, blockers, pkg, isVrApi, questGame)
            if (dryRun || blockers.isNotEmpty()) return report

            ZipOutputStream(output.outputStream().buffered(1 shl 20)).use { out ->
                val written = HashSet<String>()
                for (e in entries) {
                    val name = e.name
                    if (name.endsWith("/") || !written.add(name)) continue
                    val upper = name.uppercase()
                    if (upper.startsWith("META-INF/") && (upper == "META-INF/MANIFEST.MF" || upper.substringAfterLast('.') in setOf("RSA", "DSA", "EC", "SF"))) continue
                    when {
                        name == "AndroidManifest.xml" -> putBytes(out, name, newManifest, stored = e.method == ZipEntry.STORED)
                        name in replace -> putFile(out, name, replace.getValue(name), stored = true)
                        // native libraries and resources.arsc stay uncompressed (mapped in place)
                        else -> putEntry(out, zip, e, stored = e.method == ZipEntry.STORED || name.endsWith(".so") || name == "resources.arsc")
                    }
                }
                for ((name, file) in add) putFile(out, name, file, stored = true)
            }
            return report
        }
    }

    private val QUEST_HINTS = listOf("com.oculus.intent.category.VR")

    private fun putBytes(out: ZipOutputStream, name: String, data: ByteArray, stored: Boolean) {
        val e = ZipEntry(name)
        if (stored) {
            val crc = CRC32().apply { update(data) }
            e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong(); e.crc = crc.value
        }
        out.putNextEntry(e); out.write(data); out.closeEntry()
    }

    private fun putFile(out: ZipOutputStream, name: String, file: File, stored: Boolean) = putStream(out, name, stored, file.length()) { file.inputStream() }

    private fun putEntry(out: ZipOutputStream, zip: ZipFile, e: ZipEntry, stored: Boolean) =
        putStream(out, e.name, stored, e.size) { zip.getInputStream(e) }

    /** Streams an entry; STORED entries need their CRC first, so they are read twice (no big buffers). */
    private fun putStream(out: ZipOutputStream, name: String, stored: Boolean, knownSize: Long, open: () -> java.io.InputStream) {
        val e = ZipEntry(name)
        val buf = ByteArray(1 shl 16)
        if (stored) {
            val crc = CRC32()
            var size = 0L
            open().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; crc.update(buf, 0, n); size += n } }
            e.method = ZipEntry.STORED; e.size = size; e.compressedSize = size; e.crc = crc.value
        } else if (knownSize >= 0) e.size = knownSize
        out.putNextEntry(e)
        open().use { i -> while (true) { val n = i.read(buf); if (n < 0) break; out.write(buf, 0, n) } }
        out.closeEntry()
    }
}
