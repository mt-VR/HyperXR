package com.hyperxr.assistant

import android.content.Context
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.voice.VoiceCommand
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors

/** What the assistant needs from the launcher. Called on the GL thread. */
interface AssistantHost {
    fun snapshot(): RuntimeSnapshot
    fun run(cmd: VoiceCommand)
}

/**
 * The HyperXR assistant: a small language model running on the phone (MediaPipe LLM Inference,
 * offline once downloaded) that sees the runtime state, answers questions and runs launcher
 * actions. Speech comes from the Android/Google recognizer as text, typed text works the same.
 * Without a model it still runs the plain commands the keyword parser understands.
 */
class Assistant(private val context: Context, private val host: AssistantHost) {
    class Message(val fromUser: Boolean, val text: String)

    private val prefs = context.getSharedPreferences("assistant", Context.MODE_PRIVATE)
    private val dir = File(context.filesDir, "models").apply { mkdirs() }
    private val worker = Executors.newSingleThreadExecutor()
    private val downloader = Executors.newSingleThreadExecutor()
    private var llm: LlmInference? = null
    private var loadedId: String? = null
    private var tts: android.speech.tts.TextToSpeech? = null

    val messages = ArrayList<Message>()
    @Volatile var status = ""; private set
    @Volatile var busy = false; private set
    @Volatile var downloading: AssistantModel? = null; private set
    @Volatile var progress = 0f; private set
    /** Bumped on every change so panels know when to redraw. */
    @Volatile var version = 0; private set
    private val history = ArrayList<Pair<String, String>>()

    var modelId: String
        get() = prefs.getString("model", "") ?: ""
        set(v) { prefs.edit().putString("model", v).apply(); unload(); changed() }
    var useGpu: Boolean
        get() = prefs.getBoolean("gpu", false)
        set(v) { prefs.edit().putBoolean("gpu", v).apply(); unload(); changed() }
    var speak: Boolean
        get() = prefs.getBoolean("speak", false)
        set(v) { prefs.edit().putBoolean("speak", v).apply(); changed() }

    fun file(m: AssistantModel) = File(dir, m.file)
    fun installed(m: AssistantModel) = file(m).length() == m.bytes
    /** The chosen model if downloaded, else any downloaded one. */
    fun activeModel(): AssistantModel? = AssistantBrain.MODELS.firstOrNull { it.id == modelId && installed(it) } ?: AssistantBrain.MODELS.firstOrNull { installed(it) }

    /** Phones with less than ~6 GB of RAM get the light model suggested. */
    fun suggested(): AssistantModel {
        val mi = android.app.ActivityManager.MemoryInfo()
        (context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager).getMemoryInfo(mi)
        return if (mi.totalMem >= 5_500_000_000L) AssistantBrain.MODELS[0] else AssistantBrain.MODELS[1]
    }

    private fun changed() { version++ }

    private fun add(fromUser: Boolean, text: String) {
        synchronized(messages) { messages += Message(fromUser, text); while (messages.size > 30) messages.removeAt(0) }
        changed()
    }

    /** Handles a request (spoken or typed). Call on the GL thread: the snapshot reads the windows. */
    fun ask(text: String) {
        val request = text.trim()
        if (request.isEmpty()) return
        add(true, request)
        val snap = host.snapshot()
        AssistantBrain.fastPath(request, snap)?.let { cmd -> host.run(cmd); reply(AssistantBrain.describe(cmd, snap)); return }
        val model = activeModel()
        if (model == null) {
            val rule = AssistantBrain.rules(request, snap)
            if (rule != null) { host.run(rule); reply(AssistantBrain.describe(rule, snap)) }
            else reply("Para entender perguntas e pedidos livres, baixe um modelo de IA no painel Assistente (uma vez; depois funciona sem internet).")
            return
        }
        if (busy) { reply("Ainda estou pensando na pergunta anterior."); return }
        busy = true; status = "Pensando…"; changed()
        worker.execute {
            val out = try {
                val engine = engine(model)
                var prompt = AssistantBrain.prompt(snap, request, history, model.contextTokens)
                // keep room for the answer inside the model's context window
                if (engine.sizeInTokens(prompt) > model.contextTokens - 220) prompt = AssistantBrain.prompt(snap, request, emptyList(), 1000)
                val session = LlmInferenceSession.createFromOptions(engine,
                    LlmInferenceSession.LlmInferenceSessionOptions.builder().setTemperature(0.2f).setTopK(20).setTopP(0.9f).build())
                try { session.addQueryChunk(prompt); session.generateResponse() } finally { session.close() }
            } catch (t: Throwable) {
                Diagnostics.log("Assistant model failed: $t")
                null
            }
            busy = false; status = ""
            if (out == null) { reply("O modelo não rodou neste celular (falta memória?). Tente o modelo leve ou desligue a GPU."); return@execute }
            val r = AssistantBrain.parse(out, request, snap)
            synchronized(history) { history += request to r.say; while (history.size > 4) history.removeAt(0) }
            r.actions.forEach { host.run(it) }
            reply(r.say)
        }
    }

    /** First message of an empty chat, so the panel does not reopen on the model list. */
    fun hello() = add(false, if (activeModel() != null) "Oi! Pergunte ou peça algo." else "Sem modelo baixado entendo só comandos simples, como \"abre o YouTube\".")

    private fun reply(text: String) {
        add(false, text)
        if (speak) say(text)
    }

    private fun engine(m: AssistantModel): LlmInference {
        llm?.let { if (loadedId == m.id) return it }
        unload()
        status = "Carregando o modelo…"; changed()
        val opts = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(file(m).absolutePath)
            .setMaxTokens(m.contextTokens)
            .setPreferredBackend(if (useGpu) LlmInference.Backend.GPU else LlmInference.Backend.CPU)
            .build()
        return LlmInference.createFromOptions(context, opts).also { llm = it; loadedId = m.id; status = "Pensando…"; changed() }
    }

    private fun unload() {
        val l = llm ?: return
        llm = null; loadedId = null
        worker.execute { runCatching { l.close() } }
    }

    // ---------------------------------------------------------------- model download
    /** Downloads [m] from Hugging Face (litert-community, Apache-2.0), resuming a partial file. */
    fun download(m: AssistantModel) {
        if (downloading != null) return
        val free = dir.usableSpace
        val part = File(dir, m.file + ".part")
        if (free < m.bytes - part.length() + 200_000_000L) { add(false, "Falta espaço: o modelo precisa de ${m.bytes / 1_000_000} MB livres."); return }
        downloading = m; progress = part.length().toFloat() / m.bytes; changed()
        downloader.execute {
            try {
                var url = URL(m.url)
                var conn: HttpURLConnection
                var redirects = 0
                while (true) {
                    conn = url.openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = false
                    conn.connectTimeout = 20_000; conn.readTimeout = 30_000
                    if (part.length() > 0) conn.setRequestProperty("Range", "bytes=${part.length()}-")
                    val code = conn.responseCode
                    if (code in 300..399 && redirects++ < 5) { url = URL(url, conn.getHeaderField("Location")); conn.disconnect(); continue }
                    if (code != 200 && code != 206) throw IllegalStateException("HTTP $code")
                    if (code == 200) part.delete()  // server ignored the range: start over
                    break
                }
                conn.inputStream.use { input ->
                    java.io.FileOutputStream(part, true).use { out ->
                        val buf = ByteArray(256 * 1024)
                        var done = part.length()
                        var last = 0L
                        while (true) {
                            val n = input.read(buf); if (n < 0) break
                            out.write(buf, 0, n); done += n
                            if (done - last > 4_000_000) { last = done; progress = done.toFloat() / m.bytes; changed() }
                        }
                    }
                }
                if (part.length() != m.bytes) throw IllegalStateException("arquivo incompleto (${part.length()} de ${m.bytes} bytes)")
                part.renameTo(file(m))
                prefs.edit().putString("model", m.id).apply()
                add(false, "Modelo ${m.label} pronto. Pode perguntar ou pedir.")
            } catch (t: Throwable) {
                Diagnostics.log("Model download failed: $t")
                add(false, "O download parou (${t.message}). Toque em baixar de novo para continuar de onde parou.")
            } finally {
                downloading = null; changed()
            }
        }
    }

    fun delete(m: AssistantModel) {
        if (loadedId == m.id) unload()
        file(m).delete(); File(dir, m.file + ".part").delete(); changed()
    }

    // ---------------------------------------------------------------- spoken replies
    private fun say(text: String) {
        val t = tts
        if (t == null) {
            tts = android.speech.tts.TextToSpeech(context) { ok ->
                if (ok == android.speech.tts.TextToSpeech.SUCCESS) { tts?.language = Locale("pt", "BR"); tts?.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "hx") }
            }
        } else t.speak(text, android.speech.tts.TextToSpeech.QUEUE_FLUSH, null, "hx")
    }

    fun release() {
        unload()
        tts?.shutdown(); tts = null
        worker.shutdown()
    }
}
