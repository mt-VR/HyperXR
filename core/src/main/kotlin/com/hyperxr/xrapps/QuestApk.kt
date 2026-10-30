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
        // Meta (Quest)
        "XR_FB_display_refresh_rate", "XR_FB_color_space", "XR_FB_swapchain_update_state", "XR_FB_swapchain_update_state_opengl_es",
        "XR_FB_foveation", "XR_FB_foveation_configuration", "XR_FB_composition_layer_settings", "XR_FB_composition_layer_image_layout",
        "XR_FB_touch_controller_pro", "XR_META_touch_controller_plus", "XR_EXT_performance_settings", "XR_KHR_android_thread_settings",
        // Pico
        "XR_BD_controller_interaction",
        // hands (tracked by HyperMagic with the camera while the game runs)
        "XR_EXT_hand_tracking", "XR_FB_hand_tracking_aim", "XR_FB_hand_tracking_mesh", "XR_FB_hand_tracking_capsules",
        // curved panels and 180/360 images
        "XR_KHR_composition_layer_cylinder", "XR_KHR_composition_layer_equirect2",
        // mixed reality: the phone's camera behind the game
        "XR_FB_passthrough",
        // anchors and the room (Meta scene model)
        "XR_FB_spatial_entity", "XR_FB_spatial_entity_query", "XR_FB_spatial_entity_storage", "XR_FB_spatial_entity_container",
        "XR_FB_scene", "XR_FB_scene_capture",
        // controller models, per-layer blending, local dimming, headset id
        "XR_FB_render_model", "XR_FB_composition_layer_alpha_blend", "XR_META_local_dimming", "XR_META_headset_id",
        // hands as controllers, Touch proximity, haptics, and the smaller Meta extensions
        "XR_EXT_hand_interaction", "XR_META_simultaneous_hands_and_controllers", "XR_FB_touch_controller_proximity",
        "XR_FB_haptic_amplitude_envelope", "XR_FB_haptic_pcm", "XR_META_performance_metrics", "XR_META_recommended_layer_resolution",
        "XR_FB_composition_layer_secure_content", "XR_META_automatic_layer_filter", "XR_FB_keyboard_tracking",
        "XR_META_passthrough_preferences", "XR_META_passthrough_layer_resumed_event", "XR_FB_spatial_entity_storage_batch",
        "XR_OCULUS_android_session_state_enable",
        // Pico spatial sensing: anchors, the room, its planes and mesh (and the futures they use)
        "XR_EXT_future", "XR_BD_future_progress", "XR_BD_spatial_sensing", "XR_BD_spatial_anchor", "XR_BD_spatial_scene",
        "XR_BD_spatial_mesh", "XR_BD_spatial_plane",
        // bodies inferred from the head and the hands (Meta upper / full body, Pico)
        "XR_FB_body_tracking", "XR_META_body_tracking_full_body", "XR_BD_body_tracking",
        // the room as a mesh, passthrough colour cubes, keyboard-hands passthrough layers
        "XR_META_spatial_entity_mesh", "XR_META_passthrough_color_lut", "XR_FB_passthrough_keyboard_hands",
        // room depth for occlusion, gaze from the head, thumb microgestures
        "XR_META_environment_depth", "XR_FB_eye_tracking_social", "XR_META_hand_tracking_microgestures",
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
        if (abi != null) for (name in libs.filter { it.startsWith("lib/$abi/") }) zip.getInputStream(zip.getEntry(name)).use { scan.feed(it) }
        // Google VR / Cardboard apps: which Java APIs they use (controller, Daydream checks)
        val gvr = libs.any { it.endsWith("/libgvr.so") || it.endsWith("/libgvrunity.so") || it.endsWith("/libcardboard_api.so") || it.endsWith("/libGfxPluginCardboard.so") }
        val vrManifest = manifest.descendants("category").any { it.attr("name") == ApkPatcher.DAYDREAM || it.attr("name") == ApkPatcher.CARDBOARD } ||
            manifest.descendants("activity").any { it.attr("enableVrMode") != null } ||
            manifest.all("uses-feature").any { (it.attr("name") ?: "").startsWith("android.software.vr") || (it.attr("name") ?: "").startsWith("android.hardware.vr") } ||
            manifest.descendants("meta-data").any { (it.attr("name") ?: "").startsWith("com.google.android.vr") }
        if (gvr || vrManifest) {
            zip.entries().asSequence().filter { it.name.matches(Regex("classes\\d*\\.dex")) }.forEach { e -> zip.getInputStream(e).use { scan.feed(it) } }
        }
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
            LoaderKind.META_FORWARD -> wont("Loader OpenXR da Meta que só procura o runtime do Quest (\"Corrigir e instalar\" troca pelo loader da Khronos)")
            LoaderKind.UNKNOWN -> reasons += "Loader OpenXR de origem desconhecida: \"Corrigir e instalar\" troca pelo loader da Khronos"
            LoaderKind.NONE -> reasons += "Sem loader OpenXR embutido (espera o do sistema)"
        }
        if (graphics == setOf("Vulkan")) wont("Renderiza só com Vulkan; o runtime HyperMagic aceita OpenGL ES")
        else if ("Vulkan" in graphics) reasons += "Tem Vulkan e OpenGL ES: precisa escolher OpenGL ES (o runtime HyperMagic só oferece esse)"
        if (platformSdk) reasons += "Usa o Meta Platform SDK: a verificação de licença da loja Meta pode fechar o jogo"
        val meta2 = exts.filter { (it.startsWith("XR_FB_") || it.startsWith("XR_META_") || it.startsWith("XR_OCULUS_")) && it !in RUNTIME_EXTENSIONS }.sorted()
        val metaOk = exts.count { (it.startsWith("XR_FB_") || it.startsWith("XR_META_")) && it in RUNTIME_EXTENSIONS }
        if (metaOk > 0) reasons += "$metaOk extensões Meta que o jogo usa o runtime HyperMagic atende (taxa de quadros, cores, foveação, controles Touch)"
        if (meta2.isNotEmpty()) reasons += "Cita ${meta2.size} extensões Meta que o HyperMagic ainda não tem (${meta2.take(4).joinToString()}${if (meta2.size > 4) "..." else ""}); o jogo funciona só se forem opcionais"
        if (picoApp) {
            reasons += "App da Pico: os controles Pico 4 / Neo 3 / G3 são atendidos (pelos Joy-Con ou pelas mãos)"
            val picoExt = exts.filter { (it.startsWith("XR_PICO_") || it.startsWith("XR_BD_")) && it !in RUNTIME_EXTENSIONS }.sorted()
            if (picoExt.isNotEmpty()) reasons += "Cita ${picoExt.size} extensões Pico que o HyperMagic não tem (${picoExt.take(4).joinToString()}${if (picoExt.size > 4) "..." else ""}); o jogo funciona só se forem opcionais"
            if (picoLibs.isNotEmpty()) reasons += "Traz o SDK nativo da Pico (${picoLibs.joinToString()}): o que ele pede aos serviços do óculos Pico (loja, limites de área) não existe num celular"
        }
        val ddController = scan.has("Lcom/google/vr/sdk/controller/ControllerManager;")
        if (daydream) reasons += "Detectado como Daydream por: ${daydreamSignals.joinToString()}"
        if (ddController) reasons += "Usa o controle Daydream: instale o HyperMagic Daydream (Ajustes > Mãos e controles) e o Joy-Con vira o controle; sem ele, as telas que exigem o controle não avançam"
        if (questApp && xrApi == XrApi.OPENXR) reasons += "Jogos de Quest esperam controles Touch: aqui a entrada vem das mãos/Joy-Con e pode ficar limitada"
        if ("XR_FB_scene" in exts || "XR_FB_spatial_entity" in exts) reasons += "Usa âncoras / modelo da sala da Meta: a sala vem da área desenhada no HyperGuardian (ou uma de 4 x 4 m); âncoras ficam paradas onde foram criadas"
        if ("XR_FB_passthrough" in exts) reasons += "Usa passthrough (realidade mista): a câmera do celular aparece atrás do jogo, em baixa resolução e sem profundidade"
        if ("XR_EXT_hand_tracking" in exts) reasons += "Usa hand tracking: o HyperMagic rastreia suas mãos pela câmera enquanto o jogo roda (juntas, pinça e mira da Meta)"
        if (verdict == Verdict.MAY_RUN && xrApi == XrApi.OPENXR && loader == LoaderKind.KHRONOS_BROKER && meta2.isEmpty() && !platformSdk && picoLibs.isEmpty() && "Vulkan" !in graphics) verdict = Verdict.LIKELY_RUNS

        return ApkReport(
            packageName = pkg, versionName = manifest.attr("versionName") ?: "", versionCode = manifest.attr("versionCode")?.toLongOrNull() ?: 0,
            minSdk = sdk?.attr("minSdkVersion")?.toIntOrNull() ?: 1, targetSdk = sdk?.attr("targetSdkVersion")?.toIntOrNull() ?: 0,
            abis = abis, nativeLibs = libNames.sorted(), engine = engine, xrApi = xrApi, loader = loader, questApp = questApp,
            supportedDevices = meta["com.oculus.supportedDevices"], platformSdk = platformSdk, graphics = graphics, extensions = exts,
            launchActivity = launch, daydreamController = ddController, verdict = verdict, reasons = reasons, daydream = daydream, picoApp = picoApp,
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
