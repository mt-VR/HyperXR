package com.hyperxr.xrapps

import java.io.File
import java.io.InputStream
import java.util.zip.ZipFile

enum class XrApi { OPENXR, VRAPI, CARDBOARD, DAYDREAM, NONE }

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
    /** Made for Pico headsets (Pico SDK in the manifest or the libraries). */
    val picoApp: Boolean = false,
    /** Uses the Daydream controller API (served by Google VR Services, or by HyperXR Daydream). */
    val daydreamController: Boolean = false,
    /** Daydream app (also when it supports Cardboard too). */
    val daydream: Boolean = false,
    /** Native libraries the game's libraries need that neither the APK nor Android has. */
    val missingLibs: List<String> = emptyList(),
    /** Godot 4 games: the renderer they start with on Android ("gl_compatibility", "mobile"...). */
    val godotRenderer: String? = null,
    val verdict: Verdict,
    val reasons: List<String>,
)

/**
 * Inspects an XR APK without installing it: manifest (package, SDK levels, VR categories, headset
 * markers), bundled native libraries (engine, OpenXR loader, VrApi, headset SDKs) and the OpenXR
 * extension names compiled into them, then states plainly whether HyperXR's OpenXR runtime can
 * run it. Games made for a Quest or a Pico are recognised and refused: HyperXR does not adapt them.
 */
object QuestApk {
    /** Extensions the HyperXR runtime implements (native/openxr-runtime). */
    val RUNTIME_EXTENSIONS = setOf(
        "XR_KHR_android_create_instance", "XR_KHR_opengl_es_enable", "XR_KHR_convert_timespec_time",
        "XR_EXT_local_floor", "XR_MND_headless", "XR_EXT_performance_settings", "XR_KHR_android_thread_settings",
        // hands (tracked by HyperMagic with the camera while the game runs), also as controllers
        "XR_EXT_hand_tracking", "XR_EXT_hand_interaction",
        // curved panels and 180/360 images
        "XR_KHR_composition_layer_cylinder", "XR_KHR_composition_layer_equirect2",
    )

    /** Pico's own native SDK and platform (store) libraries. */
    private val PICO_LIBS = setOf("libpxr_api.so", "libPxrPlatform.so", "libpxrplatformloader.so", "libPxrPlatformLoader.so")

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
        val needed = HashMap<String, List<String>>()
        if (abi != null) for (name in libs.filter { it.startsWith("lib/$abi/") }) {
            val e = zip.getEntry(name)
            zip.getInputStream(e).use { scan.feed(it) }
            needed[name] = ElfNeeded.read { zip.getInputStream(e) }
        }
        // Google VR / Cardboard apps: which Java APIs they use (controller, Daydream checks)
        val gvr = libs.any { it.endsWith("/libgvr.so") || it.endsWith("/libgvrunity.so") || it.endsWith("/libcardboard_api.so") || it.endsWith("/libGfxPluginCardboard.so") }
        val vrManifest = manifest.descendants("category").any { it.attr("name") == ApkPatcher.DAYDREAM || it.attr("name") == ApkPatcher.CARDBOARD } ||
            manifest.descendants("activity").any { it.attr("enableVrMode") != null } ||
            manifest.all("uses-feature").any { (it.attr("name") ?: "").startsWith("android.software.vr") || (it.attr("name") ?: "").startsWith("android.hardware.vr") } ||
            manifest.descendants("meta-data").any { (it.attr("name") ?: "").startsWith("com.google.android.vr") }
        if (gvr || vrManifest) {
            zip.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) }.forEach { e -> zip.getInputStream(e).use { scan.feed(it) } }
        }
        val godot = zip.getEntry(GodotProject.PATH)?.let { e -> GodotProject.parse(zip.getInputStream(e).use { it.readBytes() }) }?.let(GodotProject::renderer)
        analyze(manifest, libs, abis, abi, scan, ElfNeeded.missing(needed), godot)
    }

    /** Separated from [inspect] for tests. */
    fun analyze(manifest: XmlElement, libs: List<String>, abis: Set<String>, abi: String?, scan: StringScan, missingLibs: List<String> = emptyList(), godotRenderer: String? = null): ApkReport {
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
                cats.any { it == "android.intent.category.LAUNCHER" || it == QUEST_CATEGORY || it == OPENXR_CATEGORY || it == "android.intent.category.INFO" ||
                    it == ApkPatcher.DAYDREAM || it == ApkPatcher.CARDBOARD }) {
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
        // Daydream shows in many ways besides the launcher category (Unity and SDK templates differ)
        val gvrLib = "libgvr.so" in libNames || "libgvrunity.so" in libNames || "libgvr_audio.so" in libNames
        val daydreamSignals = buildList {
            if (ApkPatcher.DAYDREAM in categories) add("categoria DAYDREAM")
            if (activities.any { it.attr("enableVrMode") != null }) add("modo VR do Android (enableVrMode)")
            if (meta.keys.any { it.startsWith("com.google.android.vr.icon") }) add("ícone do Daydream Home")
            if (features.any { it == "android.software.vr.mode" || it == "android.hardware.vr.high_performance" }) add("recursos de VR do Android")
            if (scan.has("Lcom/google/vr/sdk/controller/ControllerManager;") || scan.has("gvr_controller_create_and_init")) add("controle Daydream")
            if (scan.has("Lcom/google/vr/ndk/base/DaydreamApi;") && (scan.has("isDaydreamReadyPlatform") || scan.has("launchInVr"))) add("API Daydream")
        }
        val daydream = daydreamSignals.isNotEmpty() && (gvrLib || ApkPatcher.DAYDREAM in categories || scan.has("Lcom/google/vr/"))
        val xrApi = when {
            hasLoader || OPENXR_CATEGORY in categories -> XrApi.OPENXR
            "libvrapi.so" in libNames || scan.has("vrapi_Initialize") -> XrApi.VRAPI
            daydream && ApkPatcher.CARDBOARD !in categories -> XrApi.DAYDREAM
            ApkPatcher.CARDBOARD in categories || "libgvr.so" in libNames || "libcardboard_api.so" in libNames -> XrApi.CARDBOARD
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
        val picoLibs = PICO_LIBS.intersect(libNames)
        val picoApp = picoLibs.isNotEmpty() || meta.keys.any { it.startsWith("pvr.") || it.startsWith("PICO") || it.startsWith("pico.") } ||
            features.any { it.startsWith("picovr") || it.startsWith("com.pico") }
        val graphics = buildSet {
            if (scan.has("XR_KHR_vulkan_enable") || scan.has("XR_KHR_vulkan_enable2")) add("Vulkan")
            if (scan.has("XR_KHR_opengl_es_enable")) add("OpenGL ES")
        }
        val exts = scan.extensions()

        val reasons = ArrayList<String>()
        var verdict = Verdict.MAY_RUN
        fun wont(r: String) { verdict = Verdict.WONT_RUN; reasons += r }
        val questOnly = missingLibs.filter { it in ElfNeeded.QUEST_ONLY }
        if (questOnly.isNotEmpty()) wont("Precisa de ${questOnly.joinToString()}, que só existe dentro do sistema do Quest: o jogo fecha assim que abre")
        val otherMissing = missingLibs - questOnly.toSet()
        if (otherMissing.isNotEmpty()) reasons += "Pede bibliotecas que não vêm no APK nem são públicas no Android (${otherMissing.joinToString()}): se o celular não as tiver, o jogo fecha ao abrir"
        if (questApp || picoApp || platformSdk) wont("Feito para " + (if (picoApp && !questApp) "Pico" else "Quest") + ": o HyperMagic não roda nem corrige jogos desses óculos")
        if (abi == null && abis.isNotEmpty()) wont("Bibliotecas só para ${abis.joinToString()}; este celular não as carrega")
        when (xrApi) {
            XrApi.VRAPI -> wont("Usa a VrApi da Meta (Gear VR / Quest antigo): o HyperMagic roda só OpenXR")
            XrApi.DAYDREAM -> wont("App só Daydream: sem o Google VR Services e um celular Daydream-ready ele recusa abrir. \"Corrigir e instalar\" o marca como compatível com Cardboard")
            XrApi.NONE -> reasons += "Não usa OpenXR: abre como app Android comum (tela plana)"
            XrApi.CARDBOARD -> reasons += (if (ApkPatcher.DAYDREAM in categories) "Daydream e Cardboard: " else "App Cardboard: ") +
                "roda com o próprio renderizador estéreo, usando a calibração do VR Box que o HyperMagic grava antes de abrir"
            XrApi.OPENXR -> Unit
        }
        if (xrApi == XrApi.OPENXR) when (loader) {
            LoaderKind.KHRONOS_BROKER -> reasons += "Loader OpenXR padrão Khronos: encontra o runtime HyperMagic pelo broker"
            LoaderKind.META_FORWARD -> wont("Loader OpenXR da Meta, que só procura o runtime do Quest")
            LoaderKind.UNKNOWN -> wont("Loader OpenXR de fabricante (não é o da Khronos): ele não procura o runtime HyperMagic")
            LoaderKind.NONE -> reasons += "Sem loader OpenXR embutido (espera o do sistema)"
        }
        val godotVulkan = godotRenderer != null && godotRenderer != GodotProject.COMPATIBILITY
        if (godotVulkan) reasons += "Godot com o renderizador \"$godotRenderer\" (Vulkan): o runtime HyperMagic só oferece OpenGL ES. \"Corrigir e instalar\" troca para o renderizador Compatibilidade (OpenGL ES); alguns efeitos podem mudar"
        else if (godotRenderer != null) reasons += "Godot com o renderizador Compatibilidade (OpenGL ES): o que o runtime HyperMagic oferece"
        if (graphics == setOf("Vulkan")) wont("Renderiza só com Vulkan; o runtime HyperMagic aceita OpenGL ES")
        else if ("Vulkan" in graphics && godotRenderer == null) reasons += "Tem Vulkan e OpenGL ES: precisa escolher OpenGL ES (o runtime HyperMagic só oferece esse)"
        // Frida gadget: a library plus its "<name>.config.so", put in by whoever modified the APK
        val gadget = libNames.filter { it.endsWith(".config.so") && it.removeSuffix(".config.so") + ".so" in libNames } +
            libNames.filter { it.contains("frida", true) || it.contains("gadget", true) }
        if (gadget.isNotEmpty()) reasons += "APK modificado: traz o Frida gadget (${gadget.distinct().joinToString()}), que não faz parte do jogo. " +
            "Em Android recente ele costuma derrubar o jogo antes de abrir (SIGSEGV em memória anônima); use o APK sem modificação"
        val vendorExt = exts.filter { e -> listOf("XR_FB_", "XR_META_", "XR_OCULUS_", "XR_BD_", "XR_PICO_").any { e.startsWith(it) } }.sorted()
        if (vendorExt.isNotEmpty()) reasons += "Cita ${vendorExt.size} extensões de fabricante (${vendorExt.take(4).joinToString()}${if (vendorExt.size > 4) "..." else ""}) que o HyperMagic não tem; o jogo funciona só se forem opcionais"
        val ddController = scan.has("Lcom/google/vr/sdk/controller/ControllerManager;")
        if (daydream) reasons += "Detectado como Daydream por: ${daydreamSignals.joinToString()}"
        if (ddController) reasons += "Usa o controle Daydream: instale o HyperMagic Daydream (Ajustes > Mãos e controles) e o Joy-Con vira o controle; sem ele, as telas que exigem o controle não avançam"
        if ("XR_EXT_hand_tracking" in exts) reasons += "Usa hand tracking: o HyperMagic rastreia suas mãos pela câmera enquanto o jogo roda"
        if (verdict == Verdict.MAY_RUN && xrApi == XrApi.OPENXR && loader == LoaderKind.KHRONOS_BROKER && vendorExt.isEmpty() && ("Vulkan" !in graphics || godotRenderer == GodotProject.COMPATIBILITY)) verdict = Verdict.LIKELY_RUNS

        return ApkReport(
            packageName = pkg, versionName = manifest.attr("versionName") ?: "", versionCode = manifest.attr("versionCode")?.toLongOrNull() ?: 0,
            minSdk = sdk?.attr("minSdkVersion")?.toIntOrNull() ?: 1, targetSdk = sdk?.attr("targetSdkVersion")?.toIntOrNull() ?: 0,
            abis = abis, nativeLibs = libNames.sorted(), engine = engine, xrApi = xrApi, loader = loader, questApp = questApp,
            supportedDevices = meta["com.oculus.supportedDevices"], platformSdk = platformSdk, graphics = graphics, extensions = exts,
            launchActivity = launch, daydreamController = ddController, missingLibs = missingLibs, godotRenderer = godotRenderer, verdict = verdict, reasons = reasons, daydream = daydream, picoApp = picoApp,
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
        "Lcom/google/vr/sdk/controller/ControllerManager;",
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
