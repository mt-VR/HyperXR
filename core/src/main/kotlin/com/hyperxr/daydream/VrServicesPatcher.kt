package com.hyperxr.daydream

import com.hyperxr.xrapps.BinaryXml
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/**
 * HyperDreamed: prepares the user's own copies of Google's Daydream app, Google VR Services and
 * Daydream games for phones that were never Daydream-ready, on Android 11 to 16.
 *
 *  - Manifest: Android's VR-mode features become optional (ordinary phones lack them and the
 *    installer would refuse), Play's "split required" flag is cleared, and a target SDK older than
 *    Android 7 is raised to 24 (Android 14 and later refuse to install older targets).
 *  - Code ([DexRules]), by framework / SDK name so obfuscation does not matter:
 *    "does this phone have Android's VR mode?" answers yes, the call that switches Android into
 *    VR mode (which throws on phones without it) is removed, the Google VR SDK's
 *    "Daydream-ready phone?" checks answer yes, and Google-signature checks between the apps
 *    answer yes (the patched apps are signed with this phone's own key, not Google's).
 *  - Old signatures removed; the caller signs every patched APK with the same key, so the apps
 *    still recognise each other.
 */
object VrServicesPatcher {
    const val DAYDREAM_HOME = "com.google.android.vr.home"
    const val VR_SERVICES = "com.google.vr.vrcore"
    const val VR_KEYBOARD = "com.google.android.vr.inputmethod"

    enum class Role(val label: String) {
        VR_SERVICES("Serviços do Google para RV"), DAYDREAM("Daydream"), KEYBOARD("Teclado de RV do Google"), GAME("App ou jogo Daydream"), OTHER("Outro app")
    }

    fun roleOf(pkg: String, daydreamCategory: Boolean): Role = when (pkg) {
        VR_SERVICES -> Role.VR_SERVICES
        DAYDREAM_HOME -> Role.DAYDREAM
        VR_KEYBOARD -> Role.KEYBOARD
        else -> if (daydreamCategory) Role.GAME else Role.OTHER
    }

    /** Android features of VR mode (FEATURE_VR_MODE_HIGH_PERFORMANCE and friends). */
    val VR_FEATURES = setOf("android.hardware.vr.high_performance", "android.software.vr.mode", "android.hardware.vr.headtracking")
    /** Below this target SDK, Android 14+ refuses the install (Android 15 raised it to 24). */
    const val MIN_TARGET_SDK = 24

    val RULES: List<DexRules.Rule> = listOf(
        DexRules.Answer("Landroid/content/pm/PackageManager;", "hasSystemFeature", "\"O celular tem modo VR do Android?\" responde sim", 1, VR_FEATURES),
        DexRules.Drop("Landroid/app/Activity;", "setVrModeEnabled", "Troca para o modo VR do Android removida (não existe neste celular)", shorty = "VZL"),
        DexRules.Answer("Lcom/google/vr/ndk/base/DaydreamApi;", "isDaydreamReadyPlatform", "\"Celular Daydream?\" (SDK de VR) responde sim", 1),
        DexRules.Answer("Lcom/google/vr/ndk/base/DaydreamUtils;", "isDaydreamPhone", "\"Celular Daydream?\" (SDK de VR) responde sim", 1),
        DexRules.Answer("Lcom/google/vr/ndk/base/DaydreamUtilsWrapper;", "isDaydreamPhone", "\"Celular Daydream?\" (SDK de VR) responde sim", 1),
        DexRules.Answer("Lcom/google/android/gms/common/GoogleSignatureVerifier;", "isPackageGoogleSigned", "Verificação de assinatura do Google aceita a assinatura nova", 1),
        DexRules.Answer("Lcom/google/android/gms/common/GoogleSignatureVerifier;", "isUidGoogleSigned", "Verificação de assinatura do Google aceita a assinatura nova", 1),
        DexRules.Answer("Lcom/google/android/gms/common/GoogleSignatureVerifier;", "isGooglePublicSignedPackage", "Verificação de assinatura do Google aceita a assinatura nova", 1),
        // the apps verify each other through the system (the Daydream binds the Serviço para RV and
        // each checks the other's signature): both are re-signed with this phone's key, so
        // PackageManager.checkSignatures between them is made to answer "the same" (SIGNATURE_MATCH = 0)
        DexRules.Answer("Landroid/content/pm/PackageManager;", "checkSignatures", "Verificação de que os apps de RV têm a mesma assinatura aceita a nova", 0, shorty = "ILL"),
        DexRules.Answer("Landroid/content/pm/PackageManager;", "checkSignatures", "Verificação de que os apps de RV têm a mesma assinatura aceita a nova", 0, shorty = "III"),
        // The real crash: the Google Play Services reject the re-signed apps ("GoogleCertificatesRslt:
        // not allowed", go/gsrlt) when they sync their feature flags (Phenotype). Make the apps see
        // the Play Services as unavailable (1 = SERVICE_MISSING) so they skip that call instead of
        // crashing on it. Public Play Services API names, which obfuscation keeps.
        DexRules.Answer("Lcom/google/android/gms/common/GoogleApiAvailability;", "isGooglePlayServicesAvailable", "Jogos param de pedir os Serviços do Google Play (Play Services vistos como ausentes)", 1),
        DexRules.Answer("Lcom/google/android/gms/common/GoogleApiAvailabilityLight;", "isGooglePlayServicesAvailable", "Jogos param de pedir os Serviços do Google Play (Play Services vistos como ausentes)", 1),
        DexRules.Answer("Lcom/google/android/gms/common/GooglePlayServicesUtil;", "isGooglePlayServicesAvailable", "Jogos param de pedir os Serviços do Google Play (Play Services vistos como ausentes)", 1),
        DexRules.Answer("Lcom/google/android/gms/common/GooglePlayServicesUtilLight;", "isGooglePlayServicesAvailable", "Jogos param de pedir os Serviços do Google Play (Play Services vistos como ausentes)", 1),
    )

    class Report(
        val packageName: String,
        val versionName: String,
        val role: Role,
        val changes: List<String>,
        val warnings: List<String>,
        val blockers: List<String>,
    )

    /** Edits the binary manifest in place. */
    fun patchManifest(manifest: ByteArray): Pair<ByteArray, List<String>> {
        val out = manifest.copyOf()
        val bb = ByteBuffer.wrap(out).order(ByteOrder.LITTLE_ENDIAN)
        val changes = ArrayList<String>()
        val slots = BinaryXml.slots(manifest)
        for (s in slots) {
            when {
                s.element == "uses-feature" && s.name == "required" && s.dataType == BinaryXml.TYPE_INT_BOOLEAN && s.data != 0 -> {
                    val feature = slots.firstOrNull { it.elementIndex == s.elementIndex && it.name == "name" }?.rawString ?: ""
                    if (feature.startsWith("android.hardware.vr") || feature.startsWith("android.software.vr")) {
                        bb.putInt(s.dataOffset, 0)
                        changes += "$feature deixa de ser obrigatório"
                    }
                }
                s.name == "isSplitRequired" && s.dataType == BinaryXml.TYPE_INT_BOOLEAN && s.data != 0 -> {
                    bb.putInt(s.dataOffset, 0)
                    changes += "APKs adicionais da Play Store deixam de ser obrigatórios"
                }
                s.element == "uses-sdk" && s.name == "targetSdkVersion" && s.rawString == null &&
                    (s.dataType == BinaryXml.TYPE_INT_DEC || s.dataType == BinaryXml.TYPE_INT_HEX) && s.data < MIN_TARGET_SDK -> {
                    bb.putInt(s.dataOffset, MIN_TARGET_SDK)
                    changes += "targetSdk ${s.data} → $MIN_TARGET_SDK (o Android 14 a 16 recusa instalar alvos mais antigos)"
                }
            }
        }
        return out to changes
    }

    /** Patches [input] into [output] (unsigned). With [dryRun] only the report is made. */
    fun patch(input: File, output: File, dryRun: Boolean = false): Report = ZipFile(input).use { zip ->
        val entries = zip.entries().asSequence().toList()
        val names = entries.map { it.name }.toSet()
        val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: error("Não é um APK (falta o AndroidManifest.xml)")
        val manifestBytes = zip.getInputStream(manifestEntry).use { it.readBytes() }
        val manifest = BinaryXml.parse(manifestBytes)
        val pkg = manifest.attr("package") ?: ""
        val version = manifest.attr("versionName") ?: ""
        val categories = manifest.descendants("category").mapNotNull { it.attr("name") }.toSet()
        val role = roleOf(pkg, "com.google.intent.category.DAYDREAM" in categories)
        val (newManifest, manifestChanges) = patchManifest(manifestBytes)
        val changes = ArrayList(manifestChanges)
        val warnings = ArrayList<String>()
        val blockers = ArrayList<String>()
        if (role == Role.OTHER) warnings += "Este app não parece ser do Daydream: as correções só mexem em verificações de VR"

        val dexPatched = HashMap<String, ByteArray>()
        val hits = LinkedHashMap<String, Int>()
        for (name in names.filter { Regex("^classes\\d*\\.dex$").matches(it) }.sorted()) {
            val r = DexRules.patch(zip.getInputStream(zip.getEntry(name)).use { it.readBytes() }, RULES)
            if (r.total > 0) { dexPatched[name] = r.dex; for ((k, v) in r.hits) hits[k] = (hits[k] ?: 0) + v }
        }
        for ((label, n) in hits) changes += "$label ($n ${if (n == 1) "ponto" else "pontos"})"
        if (hits.isEmpty() && role != Role.OTHER) warnings += "Nenhuma verificação de VR encontrada no código: o app pode já funcionar, ou as verificações dele usam nomes que não são do Android"
        if (role == Role.VR_SERVICES || role == Role.DAYDREAM) {
            if (hits.keys.none { it.startsWith("Verificação de assinatura") })
                warnings += "Verificação de assinatura do Google não encontrada pelo nome: se o Daydream disser que os Serviços para RV não estão instalados, mande o relatório"
        }
        if (names.none { it.startsWith("lib/arm64-v8a/") } && names.any { it.startsWith("lib/armeabi") })
            warnings += "Só código 32 bits: não roda em celulares que só executam 64 bits (muitos com Android 14+)"
        if (dryRun || blockers.isNotEmpty()) return Report(pkg, version, role, changes, warnings, blockers)

        ZipOutputStream(output.outputStream().buffered(1 shl 20)).use { out ->
            val written = HashSet<String>()
            for (e in entries) {
                val name = e.name
                if (name.endsWith("/") || !written.add(name)) continue
                val upper = name.uppercase()
                if (upper.startsWith("META-INF/") && (upper == "META-INF/MANIFEST.MF" || upper.substringAfterLast('.') in setOf("RSA", "DSA", "EC", "SF"))) continue
                val stored = e.method == ZipEntry.STORED || name.endsWith(".so") || name == "resources.arsc"
                when {
                    name == "AndroidManifest.xml" -> put(out, name, newManifest, e.method == ZipEntry.STORED)
                    name in dexPatched -> put(out, name, dexPatched.getValue(name), e.method == ZipEntry.STORED)
                    else -> put(out, name, zip.getInputStream(e).use { it.readBytes() }, stored)
                }
            }
        }
        Report(pkg, version, role, changes, warnings, blockers)
    }

    private fun put(out: ZipOutputStream, name: String, data: ByteArray, stored: Boolean) {
        val e = ZipEntry(name)
        if (stored) {
            val crc = CRC32().apply { update(data) }
            e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong(); e.crc = crc.value
        }
        out.putNextEntry(e); out.write(data); out.closeEntry()
    }

    /** Install order: the services first, then Daydream, then everything else. */
    fun installOrder(r: Role) = when (r) { Role.VR_SERVICES -> 0; Role.KEYBOARD -> 1; Role.DAYDREAM -> 2; Role.GAME -> 3; Role.OTHER -> 4 }
}
