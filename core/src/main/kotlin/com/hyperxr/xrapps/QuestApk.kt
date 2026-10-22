package com.hyperxr.xrapps

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

enum class XrApi { OPENXR, VRAPI, CARDBOARD, NONE }

/** Which OpenXR loader the game ships (it decides whether HyperXR's runtime can be found). */
enum class LoaderKind {
    /** Khronos Android loader: asks the runtime broker, so the HyperXR runtime is found. */
    KHRONOS_BROKER,
    /** Meta's loader that forwards to the Quest system runtime only. */
    META_FORWARD,
    UNKNOWN,
    NONE,
}

enum class Verdict { LIKELY_RUNS, MAY_RUN, WONT_RUN }

data class ApkReport(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val minSdk: Int,
    val targetSdk: Int,
    val abis: Set<String>,
    val nativeLibs: List<String>,
    val engine: String,
    val xrApi: XrApi,
    val loader: LoaderKind,
    val questApp: Boolean,
    val supportedDevices: String?,
    val platformSdk: Boolean,
    val graphics: Set<String>,
    val extensions: Set<String>,
    val launchActivity: String?,
    val verdict: Verdict,
    val reasons: List<String>,
)

/**
 * Inspects a Quest (or any XR) APK without installing it: manifest (package, SDK levels, VR
 * categories, Meta device list), bundled native libraries (engine, OpenXR loader, VrApi, Meta
 * Platform SDK) and the OpenXR extension names compiled into them, then states plainly whether
 * HyperXR's OpenXR runtime can run it.
 */
object QuestApk {
    /** Extensions the HyperXR runtime implements (native/openxr-runtime). */
    val RUNTIME_EXTENSIONS = setOf(
        "XR_KHR_android_create_instance", "XR_KHR_opengl_es_enable", "XR_KHR_convert_timespec_time",
        "XR_EXT_local_floor", "XR_MND_headless",
    )

    private val QUEST_CATEGORY = "com.oculus.intent.category.VR"
    private val OPENXR_CATEGORY = "org.khronos.openxr.intent.category.IMMERSIVE_HMD"

    fun inspect(apk: File, deviceAbis: List<String> = listOf("arm64-v8a", "armeabi-v7a")): ApkReport = ZipFile(apk).use { zip ->
        val manifestEntry = zip.getEntry("AndroidManifest.xml") ?: error("APK sem AndroidManifest.xml")
        val manifest = BinaryXml.parse(zip.getInputStream(manifestEntry).use { it.readBytes() })
        val libs = zip.entries().asSequence().map { it.name }.filter { it.startsWith("lib/") && it.endsWith(".so") }.toList()
        val abis = libs.map { it.split('/')[1] }.toSet()
        // scan the libraries of the ABI this phone will load
        val abi = deviceAbis.firstOrNull { it in abis }
        val scan = StringScan()
        if (abi != null) for (name in libs.filter { it.startsWith("lib/$abi/") }) zip.getInputStream(zip.getEntry(name)).use { scan.feed(it) }
        analyze(manifest, libs, abis, abi, scan)
    }

    /** Separated from [inspect] for tests. */
    fun analyze(manifest: XmlElement, libs: List<String>, abis: Set<String>, abi: String?, scan: StringScan): ApkReport {
        val pkg = manifest.attr("package") ?: ""
        val sdk = manifest.all("uses-sdk").firstOrNull()
        val app = manifest.all("application").firstOrNull()
        val meta = app?.all("meta-data").orEmpty().associate { (it.attr("name") ?: "") to (it.attr("value") ?: "") }
        val features = manifest.all("uses-feature").mapNotNull { it.attr("name") }
        val activities = app?.descendants("activity").orEmpty() + app?.descendants("activity-alias").orEmpty()
        var launch: String? = null
        val categories = HashSet<String>()
        for (a in activities) for (f in a.all("intent-filter")) {
            val cats = f.all("category").mapNotNull { it.attr("name") }
            categories += cats
            if (launch == null && f.all("action").any { it.attr("name") == "android.intent.action.MAIN" } &&
                cats.any { it == "android.intent.category.LAUNCHER" || it == QUEST_CATEGORY || it == OPENXR_CATEGORY || it == "android.intent.category.INFO" }) {
                launch = a.attr("name")?.let { n -> if (n.startsWith(".")) pkg + n else if ('.' !in n) "$pkg.$n" else n }
            }
        }
        val libNames = libs.map { it.substringAfterLast('/') }.toSet()
        val engine = when {
            "libunity.so" in libNames || "libil2cpp.so" in libNames -> "Unity"
            "libUE4.so" in libNames || "libUnreal.so" in libNames -> "Unreal Engine"
            "libgodot_android.so" in libNames -> "Godot"
            libNames.isEmpty() -> "Java/Kotlin"
            else -> "Nativo"
        }
        val hasLoader = "libopenxr_loader.so" in libNames
        val xrApi = when {
            hasLoader || OPENXR_CATEGORY in categories -> XrApi.OPENXR
            "libvrapi.so" in libNames || scan.has("vrapi_Initialize") -> XrApi.VRAPI
            "com.google.intent.category.CARDBOARD" in categories -> XrApi.CARDBOARD
            else -> XrApi.NONE
        }
        val loader = when {
            !hasLoader -> LoaderKind.NONE
            scan.has("org.khronos.openxr.runtime_broker") -> LoaderKind.KHRONOS_BROKER
            scan.has("libopenxr_forwardloader") -> LoaderKind.META_FORWARD
            else -> LoaderKind.UNKNOWN
        }
        val questApp = QUEST_CATEGORY in categories || "com.oculus.supportedDevices" in meta ||
            features.contains("android.hardware.vr.headtracking") && engine != "Java/Kotlin"
        val platformSdk = "libovrplatformloader.so" in libNames || "libovrplatform.so" in libNames
        val graphics = buildSet {
            if (scan.has("XR_KHR_vulkan_enable") || scan.has("XR_KHR_vulkan_enable2")) add("Vulkan")
            if (scan.has("XR_KHR_opengl_es_enable")) add("OpenGL ES")
        }
        val exts = scan.extensions()

        val reasons = ArrayList<String>()
        var verdict = Verdict.MAY_RUN
        fun wont(r: String) { verdict = Verdict.WONT_RUN; reasons += r }
        if (abi == null && abis.isNotEmpty()) wont("Bibliotecas só para ${abis.joinToString()}; este celular não as carrega")
        when (xrApi) {
            XrApi.VRAPI -> wont("Usa a VrApi da Meta (SDK Mobile antigo), que só existe no sistema do Quest")
            XrApi.NONE -> reasons += "Não usa OpenXR: abre como app Android comum (tela plana)"
            XrApi.CARDBOARD -> reasons += "App Cardboard: roda com o próprio renderizador estéreo"
            XrApi.OPENXR -> Unit
        }
        if (xrApi == XrApi.OPENXR) when (loader) {
            LoaderKind.KHRONOS_BROKER -> reasons += "Loader OpenXR padrão Khronos: encontra o runtime HyperXR pelo broker"
            LoaderKind.META_FORWARD -> wont("Loader OpenXR da Meta que só procura o runtime do Quest")
            LoaderKind.UNKNOWN -> reasons += "Loader OpenXR de origem desconhecida: pode não procurar runtimes fora do Quest"
            LoaderKind.NONE -> reasons += "Sem loader OpenXR embutido (espera o do sistema)"
        }
        if (graphics == setOf("Vulkan")) wont("Renderiza só com Vulkan; o runtime HyperXR aceita OpenGL ES")
        else if ("Vulkan" in graphics) reasons += "Tem Vulkan e OpenGL ES: precisa escolher OpenGL ES (o runtime HyperXR só oferece esse)"
        if (platformSdk) reasons += "Usa o Meta Platform SDK: a verificação de licença da loja Meta pode fechar o jogo"
        val meta2 = exts.filter { (it.startsWith("XR_FB_") || it.startsWith("XR_META_") || it.startsWith("XR_OCULUS_")) }.sorted()
        if (meta2.isNotEmpty()) reasons += "Cita ${meta2.size} extensões Meta (${meta2.take(4).joinToString()}${if (meta2.size > 4) "..." else ""}); o HyperXR não as implementa, o jogo funciona só se forem opcionais"
        if (questApp && xrApi == XrApi.OPENXR) reasons += "Jogos de Quest esperam controles Touch: aqui a entrada vem das mãos/Joy-Con e pode ficar limitada"
        if (verdict == Verdict.MAY_RUN && xrApi == XrApi.OPENXR && loader == LoaderKind.KHRONOS_BROKER && meta2.isEmpty() && !platformSdk && "Vulkan" !in graphics) verdict = Verdict.LIKELY_RUNS

        return ApkReport(
            packageName = pkg, versionName = manifest.attr("versionName") ?: "", versionCode = manifest.attr("versionCode")?.toLongOrNull() ?: 0,
            minSdk = sdk?.attr("minSdkVersion")?.toIntOrNull() ?: 1, targetSdk = sdk?.attr("targetSdkVersion")?.toIntOrNull() ?: 0,
            abis = abis, nativeLibs = libNames.sorted(), engine = engine, xrApi = xrApi, loader = loader, questApp = questApp,
            supportedDevices = meta["com.oculus.supportedDevices"], platformSdk = platformSdk, graphics = graphics, extensions = exts,
            launchActivity = launch, verdict = verdict, reasons = reasons,
        )
    }

    /** OBB files that belong to [pkg] next to the APK (same folder, or a folder named after the package). */
    fun findObbs(apk: File, pkg: String): List<File> {
        val dir = apk.parentFile ?: return emptyList()
        val candidates = (dir.listFiles().orEmpty().toList() + File(dir, pkg).listFiles().orEmpty().toList())
        return candidates.filter { it.isFile && it.name.endsWith(".obb", true) && (it.name.contains(pkg) || it.parentFile?.name == pkg) }.distinct()
    }

    private val OBB_NAME = Regex("^(main|patch)\\.\\d+\\.[A-Za-z0-9_.]+\\.obb$")
    fun isValidObbName(name: String) = OBB_NAME.matches(name)
}

/** Collects marker strings and `XR_*` names from binaries, streaming (libraries can be 200 MB). */
class StringScan {
    private val markers = listOf("org.khronos.openxr.runtime_broker", "libopenxr_forwardloader", "vrapi_Initialize",
        "XR_KHR_vulkan_enable", "XR_KHR_vulkan_enable2", "XR_KHR_opengl_es_enable")
    private val found = HashSet<String>()
    private val ext = HashSet<String>()
    private val run = StringBuilder()

    fun has(s: String) = s in found || s in ext
    fun extensions(): Set<String> = ext

    fun feed(input: InputStream) {
        val buf = ByteArray(1 shl 16)
        while (true) {
            val n = input.read(buf)
            if (n < 0) break
            for (i in 0 until n) {
                val c = buf[i].toInt() and 0xff
                if (c in 0x20..0x7e) { if (run.length < 256) run.append(c.toChar()) } else flush()
            }
        }
        flush()
    }

    fun feed(bytes: ByteArray) = feed(bytes.inputStream())

    private fun flush() {
        if (run.length >= 8) {
            val s = run.toString()
            for (m in markers) if (s.contains(m)) found += m
            var i = s.indexOf("XR_")
            while (i >= 0) {
                var j = i + 3
                while (j < s.length && (s[j].isLetterOrDigit() || s[j] == '_')) j++
                val name = s.substring(i, j)
                // extension names look like XR_<VENDOR>_<name>, vendor in capitals, name in lower case
                if (EXT.matches(name)) ext += name
                i = s.indexOf("XR_", j)
            }
        }
        run.setLength(0)
    }

    private companion object { val EXT = Regex("^XR_[A-Z]{2,8}_[a-z][a-z0-9_]{2,60}$") }
}
