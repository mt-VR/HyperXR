package com.hyperxr.xrapps

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

data class PatchReport(
    val changes: List<String>,
    val warnings: List<String>,
    /** Problems that make the patched game useless; nothing is written when not empty. */
    val blockers: List<String>,
    val packageName: String,
    /** Daydream app made launchable as a Cardboard app. */
    val daydream: Boolean,
)

/**
 * Prepares an OpenXR (Khronos loader) or Daydream APK for HyperXR, on the phone:
 *  - manifest: targetSdk capped at 29 (the game sees the runtime broker and runtime without
 *    `<queries>`), Android VR-mode `uses-feature` made optional, Play split requirements removed;
 *  - Godot 4 games on Vulkan switched to the Compatibility renderer (OpenGL ES);
 *  - Daydream-only apps: the DAYDREAM launcher category becomes CARDBOARD, so the GVR SDK and
 *    launchers treat them as Cardboard apps (no Google VR Services or Daydream phone needed);
 *  - Daydream checks in the app's own code ("is this a Daydream-ready phone?") answer yes
 *    ([DexPatcher]); the Google VR SDK itself keeps running in Cardboard mode;
 *  - 32-bit only apps: run as they are on phones that execute 32-bit code; on 64-bit-only phones
 *    they are refused with the reason (native ARM code cannot be converted to 64 bits);
 *  - games made for Quest or Pico (their vendor loaders and SDKs) and VrApi games are refused;
 *  - old signatures removed (the caller signs the result).
 */
object ApkPatcher {
    const val MAX_TARGET_SDK = 29
    private val ABI_DIRS = listOf("arm64-v8a", "armeabi-v7a", "armeabi")
    /** Android's own VR-mode features (Daydream apps require them; ordinary phones lack them). */
    private val HEADSET_FEATURES = listOf("android.hardware.vr", "android.software.vr")
    const val DAYDREAM = "com.google.intent.category.DAYDREAM"
    const val CARDBOARD = "com.google.intent.category.CARDBOARD"

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
    /**
     * @param device32Bit whether this phone runs 32-bit ARM code (null = unknown, no check).
     */
    fun patch(input: File, output: File, dryRun: Boolean = false, device32Bit: Boolean? = null): PatchReport {
        ZipFile(input).use { zip ->
            val entries = zip.entries().asSequence().toList()
            val names = entries.map { it.name }.toSet()
            val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: error("APK sem AndroidManifest.xml")
            val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
            val manifest = BinaryXml.parse(manifestBytes)
            val pkg = manifest.attr("package") ?: ""
            var (newManifest, manifestChanges) = patchManifest(manifestBytes)
            val changes = ArrayList(manifestChanges)
            val categories = manifest.descendants("category").mapNotNull { it.attr("name") }.toSet()
            val daydream = DAYDREAM in categories && CARDBOARD !in categories
            if (daydream) {
                newManifest = BinaryXml.replaceStrings(newManifest, mapOf(DAYDREAM to CARDBOARD))
                changes += "App só Daydream marcado como compatível com Cardboard"
            }
            val warnings = ArrayList<String>()
            val blockers = ArrayList<String>()

            val abiDirs = ABI_DIRS.filter { abi -> names.any { it.startsWith("lib/$abi/") } }
            if (abiDirs.isEmpty() && names.any { it.startsWith("lib/") }) blockers += "O APK não tem bibliotecas ARM (arm64-v8a/armeabi-v7a): não roda num celular"
            val only32 = abiDirs.isNotEmpty() && "arm64-v8a" !in abiDirs
            when {
                only32 && device32Bit == false -> blockers += "App só 32 bits (armeabi-v7a) e este celular só executa código 64 bits. " +
                    "Código nativo ARM não pode ser convertido de 32 para 64 bits com segurança: procure a versão 64 bits (arm64-v8a) do app"
                only32 && daydreamOrCardboard(manifestBytes) -> changes += "App 32 bits: roda como está (o SDK de VR do Google vem dentro do app)" +
                    if (device32Bit == true) "; este celular executa código 32 bits" else ""
                only32 -> warnings += "Jogo OpenXR 32 bits: o runtime HyperMagic é 64 bits, então ele só roda com outro runtime OpenXR de 32 bits"
            }
            val libNames = names.filter { it.startsWith("lib/") }.map { it.substringAfterLast('/') }.toSet()
            if (abiDirs.any { "lib/$it/libvrapi.so" in names && "lib/$it/libopenxr_loader.so" !in names })
                blockers += "Jogo VrApi (Gear VR / Quest antigo): o HyperMagic roda só OpenXR"
            // made for a Quest or a Pico: their loaders and SDKs run only on those headsets
            val meta = manifest.all("application").firstOrNull()?.all("meta-data").orEmpty().mapNotNull { it.attr("name") }
            val vendor = QUEST_HINTS.any { it in categories } || "com.oculus.supportedDevices" in meta ||
                meta.any { it.startsWith("pvr.") || it.startsWith("pico.") } || libNames.any { it in VENDOR_LIBS } ||
                abiDirs.any { abi -> "lib/$abi/libopenxr_loader.so" in names && !zip.getInputStream(zip.getEntry("lib/$abi/libopenxr_loader.so")).use { i -> StringScan().also { it.feed(i) } }.has("org.khronos.openxr.runtime_broker") }
            if (vendor) blockers += "Feito para Quest ou Pico: o HyperMagic não corrige jogos desses óculos"

            // Godot 4 games on Vulkan: the Compatibility renderer (OpenGL ES) instead
            var godotProject: ByteArray? = null
            if (GodotProject.PATH in names && names.any { it.endsWith("/libgodot_android.so") }) {
                val settings = GodotProject.parse(zip.getInputStream(zip.getEntry(GodotProject.PATH)).use { it.readBytes() })
                if (settings != null && GodotProject.renderer(settings) != GodotProject.COMPATIBILITY) {
                    godotProject = GodotProject.serialize(GodotProject.withCompatibility(settings))
                    changes += "Godot: renderizador \"${GodotProject.renderer(settings)}\" (Vulkan) trocado por Compatibilidade (OpenGL ES), o que o runtime HyperMagic oferece"
                }
            }
            // Daydream checks in the app's own code
            val dexPatched = HashMap<String, ByteArray>()
            for (name in names.filter { Regex("^classes\\d*\\.dex$").matches(it) }) {
                val bytes = zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }
                val r = DexPatcher.patch(bytes)
                if (r.sites > 0) {
                    dexPatched[name] = r.dex
                    changes += "Verificação \"celular Daydream?\" removida em ${r.sites} ponto(s) de $name (${r.methods.distinct().take(3).joinToString()}${if (r.methods.distinct().size > 3) "…" else ""})"
                }
            }
            val report = PatchReport(changes, warnings, blockers, pkg, daydream)
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
                        name == GodotProject.PATH && godotProject != null -> putBytes(out, name, godotProject, stored = e.method == ZipEntry.STORED)
                        name in dexPatched -> putBytes(out, name, dexPatched.getValue(name), stored = e.method == ZipEntry.STORED)
                        // native libraries and resources.arsc stay uncompressed (mapped in place)
                        else -> putEntry(out, zip, e, stored = e.method == ZipEntry.STORED || name.endsWith(".so") || name == "resources.arsc")
                    }
                }
            }
            return report
        }
    }

    private val QUEST_HINTS = listOf("com.oculus.intent.category.VR")
    /** Native SDKs of Meta's and Pico's headsets (and their stores). */
    private val VENDOR_LIBS = setOf("libOVRPlugin.so", "libovrplatformloader.so", "libOVRPlatformLoader.so", "libovrplatform.so", "libOVRPlatform.so",
        "libpxr_api.so", "libPxrPlatform.so", "libpxrplatformloader.so", "libPxrPlatformLoader.so")

    private fun daydreamOrCardboard(manifest: ByteArray) =
        BinaryXml.parse(manifest).descendants("category").any { it.attr("name") == DAYDREAM || it.attr("name") == CARDBOARD }

    private fun putBytes(out: ZipOutputStream, name: String, data: ByteArray, stored: Boolean) {
        val e = ZipEntry(name)
        if (stored) {
            val crc = CRC32().apply { update(data) }
            e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong(); e.crc = crc.value
        }
        out.putNextEntry(e); out.write(data); out.closeEntry()
    }

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
