package com.hyperxr.voice

import java.text.Normalizer

/** What a spoken sentence asks HyperXR to do. */
sealed class VoiceCommand {
    data class OpenApp(val appId: String, val title: String) : VoiceCommand()
    data class Search(val query: String) : VoiceCommand()
    data class Background(val choice: String) : VoiceCommand()  // "camera", "dim" or an environment id
    data class SaveWorkspace(val name: String) : VoiceCommand()
    data class OpenWorkspace(val name: String) : VoiceCommand()
    /** [dock] true: the Hyper interface; false: the Meta one. */
    data class SetMode(val dock: Boolean) : VoiceCommand()
    object Recenter : VoiceCommand()
    object Capture : VoiceCommand()
    object CloseWindows : VoiceCommand()
    object HideWindows : VoiceCommand()
    object ShowWindows : VoiceCommand()
    object Library : VoiceCommand()
    object RealityWindow : VoiceCommand()
    /** A setting change asked of the assistant ("fps" to "60"); keys in AssistantBrain.SETTINGS. */
    data class Setting(val key: String, val value: String) : VoiceCommand()
    /** Start or stop the 16:9 screen recording. */
    data class Record(val start: Boolean) : VoiceCommand()
    data class Unknown(val heard: String) : VoiceCommand()
}

/**
 * Turns Portuguese speech (as the recognizer wrote it) into a launcher command. Works on the
 * normalised text (lower case, no accents), tolerates fillers ("por favor", "o", "a") and finds
 * apps and environments by the closest name, so "abre o youtubi" still opens YouTube.
 */
object VoiceCommands {
    class Named(val id: String, val name: String)

    fun normalize(s: String): String = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "").replace(Regex("[^a-z0-9 ]"), " ").replace(Regex("\\s+"), " ").trim()

    private val FILLERS = setOf("o", "a", "os", "as", "um", "uma", "por", "favor", "pra", "para", "me", "de", "do", "da", "no", "na", "hyperxr", "hyper", "xr")

    private fun strip(words: List<String>) = words.filter { it !in FILLERS }.joinToString(" ")

    fun parse(heard: String, apps: List<Named>, environments: List<Named>, workspaces: List<String>): VoiceCommand {
        val t = normalize(heard)
        val w = t.split(' ').filter { it.isNotEmpty() }
        if (w.isEmpty()) return VoiceCommand.Unknown(heard)
        fun has(vararg k: String) = k.any { key -> if (' ' in key) t.contains(key) else key in w }
        fun after(vararg verbs: String): String? {
            for (v in verbs) { val i = w.indexOf(v); if (i >= 0) return strip(w.drop(i + 1)).ifEmpty { null } }
            return null
        }
        // workspaces first: "salvar espaco trabalho", "abrir espaco cinema"
        if (has("espaco", "workspace")) {
            val keywords = setOf("salvar", "salva", "guardar", "abrir", "abre", "abra", "carregar", "espaco", "workspace")
            val name = strip(w.filter { it !in keywords })
            if (has("salvar", "salva", "guardar")) return VoiceCommand.SaveWorkspace(name.ifEmpty { "espaço" })
            val match = closest(name, workspaces.map { Named(it, it) })
            return VoiceCommand.OpenWorkspace(match?.id ?: name)
        }
        if (has("mundo real", "janela real", "ver a sala", "ver o quarto", "portal")) return VoiceCommand.RealityWindow
        if (has("recentralizar", "centralizar", "recentrar")) return VoiceCommand.Recenter
        if (has("gravar", "gravacao", "grava", "gravando")) return VoiceCommand.Record(!has("parar", "para", "pare", "terminar", "encerrar", "salvar"))
        if (has("capturar", "captura", "print", "screenshot") || has("tirar foto")) return VoiceCommand.Capture
        if (has("fechar", "fecha", "feche") && has("janela", "janelas", "tudo")) return VoiceCommand.CloseWindows
        if (has("esconder", "esconde", "minimizar", "ocultar") && has("janela", "janelas")) return VoiceCommand.HideWindows
        if (has("mostrar", "mostra") && has("janela", "janelas")) return VoiceCommand.ShowWindows
        if (has("modo", "interface")) { if (has("hyper", "haiper", "dock", "doca")) return VoiceCommand.SetMode(true); if (has("meta", "new", "novo")) return VoiceCommand.SetMode(false) }
        if (has("biblioteca")) return VoiceCommand.Library
        if (has("escurecer", "escuro", "escurece")) return VoiceCommand.Background("dim")
        if (has("camera", "realidade mista", "passthrough")) return VoiceCommand.Background("camera")
        if (has("ambiente", "cenario", "fundo")) {
            val name = after("ambiente", "cenario", "fundo") ?: return VoiceCommand.Unknown(heard)
            closest(name, environments)?.let { return VoiceCommand.Background(it.id) }
        }
        // a search keeps every word after the verb ("receita de bolo"), only leading fillers go
        for (v in listOf("pesquisar", "pesquisa", "pesquise", "procurar", "procura", "buscar", "busca", "google")) {
            val i = w.indexOf(v)
            if (i >= 0) w.drop(i + 1).dropWhile { it in FILLERS }.joinToString(" ").takeIf { it.isNotEmpty() }?.let { return VoiceCommand.Search(it) }
        }
        val target = after("abrir", "abre", "abra", "iniciar", "inicia", "jogar", "joga", "tocar", "toca") ?: if (w.size <= 3) strip(w) else null
        if (target != null) closest(target, apps)?.let { return VoiceCommand.OpenApp(it.id, it.name) }
        return VoiceCommand.Unknown(heard)
    }

    /** Best name match: exact/contains first, then edit distance relative to length (≤ 40%). */
    fun closest(query: String, options: List<Named>): Named? {
        val q = normalize(query)
        if (q.isEmpty()) return null
        options.firstOrNull { normalize(it.name) == q }?.let { return it }
        options.filter { normalize(it.name).let { n -> n.startsWith(q) || q.startsWith(n) || n.contains(q) } }.minByOrNull { it.name.length }?.let { return it }
        val scored = options.map { it to distance(q, normalize(it.name)).toFloat() / maxOf(q.length, normalize(it.name).length) }
        return scored.filter { it.second <= 0.4f }.minByOrNull { it.second }?.first
    }

    fun distance(a: String, b: String): Int {
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val cur = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = cur
            }
        }
        return d[b.length]
    }
}
