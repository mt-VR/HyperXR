package com.hyperxr.assistant

import com.hyperxr.voice.VoiceCommand
import com.hyperxr.voice.VoiceCommands
import com.hyperxr.voice.VoiceCommands.Named
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** The live state of the runtime the assistant sees with every question. */
data class RuntimeSnapshot(
    val xrMode: String = "",
    val tracking: String = "",
    val hands: String = "",
    val background: String = "",
    val launcherMode: String = "",
    val battery: String = "",
    val windows: List<String> = emptyList(),
    val windowsHidden: Boolean = false,
    val apps: List<Named> = emptyList(),
    val environments: List<Named> = emptyList(),
    val workspaces: List<String> = emptyList(),
    val joyCons: List<String> = emptyList(),
    /** Current values of the keys in [AssistantBrain.SETTINGS]. */
    val settings: Map<String, String> = emptyMap(),
    val time: String = "",
)

/** A model the assistant can run on the phone (MediaPipe LLM Inference .task bundle). */
data class AssistantModel(val id: String, val label: String, val file: String, val url: String, val bytes: Long, val contextTokens: Int, val note: String)

/** What the assistant answered: text for the user and the actions to run. */
data class AssistantReply(val say: String, val actions: List<VoiceCommand>)

/**
 * Builds the prompt from the runtime state and the question, and turns the model's answer into
 * launcher commands. Small models invent actions, so actions are checked against the request:
 * questions run nothing, and closing or overwriting things needs the user to have asked for it.
 */
object AssistantBrain {
    val MODELS = listOf(
        AssistantModel("qwen1.5b", "Qwen 2.5 1.5B (recomendado)", "Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
            "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_multi-prefill-seq_q8_ekv4096.task",
            1_598_556_720L, 4096, "Entende melhor; precisa de ~6 GB de RAM no celular"),
        AssistantModel("qwen0.5b", "Qwen 2.5 0.5B (leve)", "Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            "https://huggingface.co/litert-community/Qwen2.5-0.5B-Instruct/resolve/main/Qwen2.5-0.5B-Instruct_multi-prefill-seq_q8_ekv1280.task",
            546_660_344L, 1280, "3x mais rápido; erra mais"),
    )

    /** Settings the assistant may change, with their allowed range (spoken names). */
    val SETTINGS = linkedMapOf(
        "hand_tracking" to "sim|nao", "toque_direto" to "sim|nao", "fps" to "30-90", "escala_render" to "0.5-1.0",
        "ipd" to "50-80 mm", "fov" to "70-120 graus", "volume" to "0-100",
    )

    /** Words that must appear in a request before the assistant may change each setting. */
    private val SETTING_WORDS = mapOf(
        "hand_tracking" to arrayOf("mao", "maos", "hand", "tracking", "rastre"),
        "toque_direto" to arrayOf("toque", "touch", "dedo", "encost"),
        "fps" to arrayOf("fps", "fluid", "trav", "lent", "lag", "quent", "esquent", "bateria", "desempenho", "rapid", "suave"),
        "escala_render" to arrayOf("render", "escala", "fluid", "trav", "lent", "lag", "quent", "esquent", "bateria", "desempenho", "qualidade", "nitid", "resolu", "borrad", "rapid", "suave"),
        "ipd" to arrayOf("ipd", "olho", "dupl", "lente", "embac"),
        "fov" to arrayOf("fov", "campo", "visao", "zoom"),
        "volume" to arrayOf("volume", "som", "alto", "baixo", "audio"),
    )

    private val QUESTION_START = setOf("como", "qual", "quais", "quanto", "quanta", "quantos", "quantas", "quando", "onde", "quem", "porque", "oque", "explica", "explique", "explicar")

    /** "como faço…", "o que é…", "qual a bateria?": answered with words, never with actions. */
    fun isQuestion(text: String): Boolean {
        if (text.trim().endsWith("?")) return true
        val w = VoiceCommands.normalize(text).split(' ')
        val first = w.firstOrNull() ?: return false
        val second = w.getOrNull(1)
        return first in QUESTION_START || (first in setOf("o", "por", "pra", "para") && second == "que") || (first == "que" && second != null)
    }

    fun prompt(s: RuntimeSnapshot, question: String, history: List<Pair<String, String>>, contextTokens: Int): String {
        val compact = contextTokens < 2000
        val topics = AssistantKnowledge.relevant(question, if (compact) 2 else 4)
        val apps = pickApps(s.apps, question, if (compact) 12 else 30)
        return buildString {
            appendLine("Você é o assistente do HyperXR, sistema de realidade virtual e mista para celular num óculos VR Box. Fale português do Brasil, curto e direto.")
            appendLine("Responda SÓ com JSON: {\"resposta\":\"texto ao usuário\",\"acoes\":[...]}")
            appendLine("Perguntas e problemas: explique os passos com base em SOBRE O HYPERXR e no ESTADO, sem inventar, e use \"acoes\":[].")
            appendLine("Ações (só quando o pedido mandar fazer algo):")
            appendLine("abrir_app:nome · pesquisar:texto · fundo:camera|escurecer|ambiente · modo:dock|new · recentralizar · capturar · fechar_janelas · esconder_janelas · mostrar_janelas · biblioteca · mundo_real · gravar_tela · parar_gravacao · salvar_espaco:nome · abrir_espaco:nome · ajuste:nome=valor")
            appendLine("Ajustes: " + SETTINGS.entries.joinToString(" ") { "${it.key}(${it.value})" })
            appendLine()
            appendLine("ESTADO AGORA")
            appendLine("Modo: ${s.xrMode} · Rastreamento: ${s.tracking} · Mãos: ${s.hands.ifEmpty { "-" }}")
            appendLine("Fundo: ${s.background} · Launcher: ${s.launcherMode} · Bateria: ${s.battery.ifEmpty { "?" }} · Hora: ${s.time}")
            appendLine("Janelas: " + (if (s.windows.isEmpty()) "nenhuma" else s.windows.joinToString(", ")) + if (s.windowsHidden) " (ocultas)" else "")
            appendLine("Apps: " + apps.joinToString(", ") { it.name })
            appendLine("Ambientes: " + s.environments.take(8).joinToString(", ") { it.name })
            appendLine("Espaços salvos: " + s.workspaces.take(8).joinToString(", ").ifEmpty { "nenhum" })
            appendLine("Joy-Cons: " + s.joyCons.joinToString(", ").ifEmpty { "nenhum" })
            appendLine("Ajustes: " + s.settings.entries.joinToString(" ") { "${it.key}=${it.value}" })
            if (topics.isNotEmpty()) {
                appendLine()
                appendLine("SOBRE O HYPERXR")
                topics.forEach { appendLine("- " + it.text) }
            }
            appendLine()
            appendLine("Exemplo: \"abre o youtube e escurece\" → {\"resposta\":\"Abrindo o YouTube e escurecendo.\",\"acoes\":[\"abrir_app:YouTube\",\"fundo:escurecer\"]}")
            appendLine("Exemplo: \"o que é o modo dock?\" → {\"resposta\":\"No modo dock as janelas ficam lado a lado sobre a barra de tarefas.\",\"acoes\":[]}")
            appendLine("Exemplo: \"a imagem tá dupla\" → {\"resposta\":\"Ajuste o IPD em Configurações 2D > Gráficos até a imagem ficar única.\",\"acoes\":[]}")
            if (!compact) for ((q, a) in history.takeLast(2)) appendLine("Antes: \"$q\" → \"$a\"")
            appendLine()
            append("Pedido: ").append(question)
        }
    }

    /** Apps named in the question first, then the rest up to [max] (the full list can be long). */
    private fun pickApps(apps: List<Named>, question: String, max: Int): List<Named> {
        val q = VoiceCommands.normalize(question)
        val named = apps.filter { a -> VoiceCommands.normalize(a.name).split(' ').any { it.length > 2 && q.contains(it) } }
        return (named + apps).distinct().take(max)
    }

    fun parse(output: String, request: String, s: RuntimeSnapshot): AssistantReply {
        val obj = extractJson(output)
        if (obj == null) {
            val text = output.replace(Regex("```\\w*"), "").trim()
            return AssistantReply(text.ifEmpty { "Não consegui responder." }, emptyList())
        }
        val say = (obj["resposta"] ?: obj["response"] ?: obj["say"])?.let { (it as? JsonPrimitive)?.contentOrNull } ?: ""
        val raw = (obj["acoes"] ?: obj["ações"] ?: obj["actions"]) as? JsonArray ?: JsonArray(emptyList())
        val actions = filter(raw.mapNotNull { e -> actionText(e)?.let { toCommand(it, s) } }, request)
        // the model sometimes forgets the action a plain command asks for: the rule parser fills in
        if (actions.isEmpty() && !isQuestion(request)) {
            val rule = rules(request, s)
            if (rule != null) return AssistantReply(describe(rule, s), listOf(rule))
        }
        // "siga estas etapas:" and nothing else: give the knowledge the answer should have used
        if (isQuestion(request) && (say.trimEnd().endsWith(":") || say.length < 12)) {
            AssistantKnowledge.relevant(request, 1).firstOrNull()?.let { return AssistantReply(it.text, emptyList()) }
        }
        return AssistantReply(say.ifEmpty { if (actions.isEmpty()) "Não entendi." else "Feito." }, actions)
    }

    /** The keyword parser's reading of [request], or null when it found no command. */
    fun rules(request: String, s: RuntimeSnapshot): VoiceCommand? =
        VoiceCommands.parse(request, s.apps, s.environments, s.workspaces).takeIf { it !is VoiceCommand.Unknown }

    /**
     * Short, plain commands ("abre o youtube", "recentralizar") the keyword parser understands run
     * without the model: instant, and the same with or without a model downloaded.
     */
    fun fastPath(request: String, s: RuntimeSnapshot): VoiceCommand? {
        if (isQuestion(request)) return null
        val w = VoiceCommands.normalize(request).split(' ')
        if (w.size > 6 || "e" in w.drop(1)) return null
        return rules(request, s)
    }

    /** What the assistant says when it runs [c] itself. */
    fun describe(c: VoiceCommand, s: RuntimeSnapshot): String = when (c) {
        is VoiceCommand.OpenApp -> "Abrindo ${c.title}."
        is VoiceCommand.Search -> "Pesquisando \"${c.query}\"."
        is VoiceCommand.Background -> when (c.choice) {
            "camera" -> "Mostrando a câmera."
            "dim" -> "Escurecendo a câmera."
            else -> "Ambiente: ${s.environments.firstOrNull { it.id == c.choice }?.name ?: c.choice}."
        }
        is VoiceCommand.SaveWorkspace -> "Salvando o espaço \"${c.name}\"."
        is VoiceCommand.OpenWorkspace -> "Abrindo o espaço \"${c.name}\"."
        is VoiceCommand.SetMode -> if (c.dock) "Modo Dock." else "Modo New."
        is VoiceCommand.Setting -> "Ajuste ${c.key} = ${c.value}."
        VoiceCommand.Recenter -> "Visão recentralizada."
        VoiceCommand.Capture -> "Captura feita."
        VoiceCommand.CloseWindows -> "Fechando as janelas."
        VoiceCommand.HideWindows -> "Janelas ocultas."
        VoiceCommand.ShowWindows -> "Mostrando as janelas."
        VoiceCommand.Library -> "Biblioteca."
        VoiceCommand.RealityWindow -> "Abrindo a janela do mundo real."
        is VoiceCommand.Record -> if (c.start) "Gravando em 16:9." else "Gravação salva em Filmes/HyperXR."
        is VoiceCommand.Unknown -> "Não entendi: ${c.heard}"
    }

    /** Accepts "abrir_app:YouTube" and the object forms models also write. */
    private fun actionText(e: kotlinx.serialization.json.JsonElement): String? = when (e) {
        is JsonPrimitive -> e.contentOrNull
        is JsonObject -> {
            val a = (e["acao"] ?: e["ação"] ?: e["action"])?.jsonPrimitive?.contentOrNull
            val t = (e["alvo"] ?: e["target"])?.jsonPrimitive?.contentOrNull
            val v = (e["valor"] ?: e["value"])?.jsonPrimitive?.contentOrNull
            a?.let { if (t == null) it else if (v == null) "$it:$t" else "$it:$t=$v" }
        }
        else -> null
    }

    private fun extractJson(text: String): JsonObject? {
        val start = text.indexOf('{')
        if (start < 0) return null
        // the first balanced object (models sometimes add text or a second object after it)
        var depth = 0; var inStr = false; var esc = false
        for (i in start until text.length) {
            val c = text[i]
            if (inStr) { if (esc) esc = false else if (c == '\\') esc = true else if (c == '"') inStr = false; continue }
            when (c) {
                '"' -> inStr = true
                '{' -> depth++
                '}' -> if (--depth == 0) return runCatching { Json.parseToJsonElement(text.substring(start, i + 1)) as? JsonObject }.getOrNull()
            }
        }
        return null
    }

    fun toCommand(action: String, s: RuntimeSnapshot): VoiceCommand? {
        val name = VoiceCommands.normalize(action.substringBefore(':')).replace(' ', '_')
        val arg = action.substringAfter(':', "").trim()
        // "hand_tracking:nao" (a setting written as the action) and other spellings models use
        alias(name)?.let { return setting("$it=$arg") }
        return when (name) {
            "abrir_app", "abrir", "app" -> VoiceCommands.closest(arg, s.apps)?.let { VoiceCommand.OpenApp(it.id, it.name) }
            "pesquisar", "buscar" -> arg.takeIf { it.isNotEmpty() }?.let { VoiceCommand.Search(it) }
            "fundo", "ambiente" -> when (VoiceCommands.normalize(arg)) {
                "camera", "passthrough", "realidade mista" -> VoiceCommand.Background("camera")
                "escurecer", "escuro", "dim" -> VoiceCommand.Background("dim")
                else -> VoiceCommands.closest(arg, s.environments)?.let { VoiceCommand.Background(it.id) }
            }
            "modo" -> when (VoiceCommands.normalize(arg)) { "dock" -> VoiceCommand.SetMode(true); "new", "novo" -> VoiceCommand.SetMode(false); else -> null }
            "recentralizar" -> VoiceCommand.Recenter
            "capturar" -> VoiceCommand.Capture
            "fechar_janelas" -> VoiceCommand.CloseWindows
            "esconder_janelas" -> VoiceCommand.HideWindows
            "mostrar_janelas" -> VoiceCommand.ShowWindows
            "biblioteca" -> VoiceCommand.Library
            "mundo_real" -> VoiceCommand.RealityWindow
            "gravar_tela", "gravar" -> VoiceCommand.Record(true)
            "parar_gravacao" -> VoiceCommand.Record(false)
            "salvar_espaco" -> VoiceCommand.SaveWorkspace(arg.ifEmpty { "espaço" })
            "abrir_espaco" -> VoiceCommands.closest(arg, s.workspaces.map { Named(it, it) })?.let { VoiceCommand.OpenWorkspace(it.id) }
            "ajuste", "ajustes", "configurar", "setting" -> setting(arg)
            else -> null
        }
    }

    private fun alias(key: String): String? = when (key) {
        in SETTINGS -> key
        "scale_render", "render_scale", "escala", "resolucao" -> "escala_render"
        "handtracking", "maos", "rastreamento_de_maos" -> "hand_tracking"
        "direct_touch", "toque" -> "toque_direto"
        else -> null
    }

    /** "fps=60", "hand_tracking=nao": key from [SETTINGS], value checked and normalised. */
    fun setting(arg: String): VoiceCommand.Setting? {
        val key = alias(VoiceCommands.normalize(arg.substringBefore('=')).replace(' ', '_')) ?: return null
        val raw = arg.substringAfter('=', "").trim().lowercase().replace(',', '.')
        val value = when (key) {
            "hand_tracking", "toque_direto" -> when (VoiceCommands.normalize(raw)) {
                "sim", "ligar", "ligado", "on", "true", "1" -> "sim"
                "nao", "desligar", "desligado", "off", "false", "0" -> "nao"
                else -> return null
            }
            else -> {
                val n = raw.split(' ').firstOrNull()?.toFloatOrNull() ?: return null
                val (lo, hi) = when (key) { "fps" -> 30f to 90f; "escala_render" -> 0.5f to 1f; "ipd" -> 50f to 80f; "fov" -> 70f to 120f; else -> 0f to 100f }
                val v = n.coerceIn(lo, hi)
                if (key == "escala_render") "%.2f".format(java.util.Locale.ROOT, v) else v.toInt().toString()
            }
        }
        return VoiceCommand.Setting(key, value)
    }

    /** Keeps only actions the request supports (see the class comment). */
    fun filter(actions: List<VoiceCommand>, request: String): List<VoiceCommand> {
        if (isQuestion(request)) return emptyList()
        val w = VoiceCommands.normalize(request).split(' ').toSet()
        fun said(vararg stems: String) = w.any { word -> stems.any { word.startsWith(it) } }
        return actions.filter { a ->
            when (a) {
                VoiceCommand.CloseWindows -> said("fech", "limp")
                VoiceCommand.HideWindows -> said("escond", "minimiz", "ocult")
                is VoiceCommand.OpenWorkspace -> said("espac", "workspace")
                is VoiceCommand.SaveWorkspace -> said("salv", "guard") && said("espac", "workspace")
                is VoiceCommand.SetMode -> said("modo", "dock", "new")
                VoiceCommand.Capture -> said("captur", "print", "foto", "screenshot")
                is VoiceCommand.Record -> said("grav")
                is VoiceCommand.Setting -> said(*SETTING_WORDS.getValue(a.key))
                else -> true
            }
        }.distinct().take(4)
    }
}
