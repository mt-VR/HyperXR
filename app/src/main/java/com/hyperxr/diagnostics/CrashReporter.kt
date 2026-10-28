package com.hyperxr.diagnostics

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Keeps a report of every time HyperXR closed by itself, so the cause can be sent and fixed:
 * Kotlin/Java exceptions (caught as they happen, with the stack and the last log lines) and,
 * on Android 11+, native crashes and freezes (ANR) read back from the system on the next start.
 */
object CrashReporter {
    private const val MAX_REPORTS = 10
    /** Bumped when the report format improves: the system's older exits are read again. */
    private const val LAST_KEY = "lastExit2"

    fun dir(context: Context) = File(context.filesDir, "crashlogs").apply { mkdirs() }

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val sw = StringWriter()
                error.printStackTrace(PrintWriter(sw))
                write(app, "java", buildString {
                    appendLine("Tipo: erro Kotlin/Java")
                    appendLine("Thread: ${thread.name}")
                    appendLine()
                    appendLine(sw.toString().take(12000))
                    appendLine("Últimos eventos:")
                    Diagnostics.recentEvents().takeLast(40).forEach { appendLine(it) }
                })
            }
            previous?.uncaughtException(thread, error)
        }
        runCatching { collectExitReasons(app) }
    }

    /** Native crashes and ANRs are not exceptions: the system remembers them, read them on start. */
    private fun collectExitReasons(app: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val prefs = app.getSharedPreferences("crash", Context.MODE_PRIVATE)
        val last = prefs.getLong(LAST_KEY, 0L)
        val am = app.getSystemService(ActivityManager::class.java) ?: return
        var newest = last
        for (e in am.getHistoricalProcessExitReasons(app.packageName, 0, 16)) {
            if (e.timestamp <= last) continue
            newest = maxOf(newest, e.timestamp)
            val kind = when (e.reason) {
                ApplicationExitInfo.REASON_CRASH_NATIVE -> "crash nativo (C/C++)"
                ApplicationExitInfo.REASON_ANR -> "travou (ANR)"
                ApplicationExitInfo.REASON_LOW_MEMORY -> "sem memória"
                // Java crashes are already written by the handler above
                else -> null
            } ?: continue
            val trace = runCatching { e.traceInputStream?.use { it.readBytes() } }.getOrNull()
            write(app, "exit", buildString {
                appendLine("Tipo: $kind")
                appendLine("Processo: ${e.processName}")
                appendLine("Quando: ${fmt(e.timestamp)}")
                appendLine("Memória (PSS): ${e.pss / 1024} MB")
                e.description?.let { appendLine("Descrição: $it") }
                if (trace != null) {
                    appendLine()
                    appendLine(if (e.reason == ApplicationExitInfo.REASON_ANR) String(trace).take(12000) else nativeFrames(trace))
                }
            }, e.timestamp)
        }
        if (newest != last) prefs.edit().putLong(LAST_KEY, newest).apply()
    }

    // ---------------------------------------------------------------- tombstone (protobuf) reader
    private class Field(val num: Int, val type: Int, val varint: Long, val bytes: ByteArray?)

    private fun parse(b: ByteArray): List<Field> {
        val out = ArrayList<Field>()
        var i = 0
        fun varint(): Long { var r = 0L; var sh = 0; while (i < b.size) { val c = b[i++].toInt() and 0xFF; r = r or ((c and 0x7F).toLong() shl sh); if (c < 0x80) break; sh += 7 }; return r }
        while (i < b.size) {
            val key = varint(); val num = (key ushr 3).toInt(); val type = (key and 7).toInt()
            when (type) {
                0 -> out += Field(num, 0, varint(), null)
                1 -> { if (i + 8 > b.size) return out; var v = 0L; for (k in 0 until 8) v = v or ((b[i + k].toLong() and 0xFF) shl (8 * k)); i += 8; out += Field(num, 1, v, null) }
                2 -> { val n = varint().toInt(); if (n < 0 || i + n > b.size) return out; out += Field(num, 2, 0, b.copyOfRange(i, i + n)); i += n }
                5 -> { if (i + 4 > b.size) return out; i += 4 }
                else -> return out
            }
        }
        return out
    }

    private fun List<Field>.str(n: Int) = firstOrNull { it.num == n && it.bytes != null }?.let { String(it.bytes!!) }
    private fun List<Field>.num(n: Int) = firstOrNull { it.num == n && it.bytes == null }?.varint

    /**
     * Android's tombstone (tombstone.proto): the signal and fault address, the abort message, and
     * the crashing thread's backtrace (library, function, offset), then the other threads' tops.
     */
    private fun nativeFrames(bytes: ByteArray): String = runCatching {
        val t = parse(bytes)
        val sb = StringBuilder()
        val tid = t.num(6) ?: -1L
        t.firstOrNull { it.num == 10 && it.bytes != null }?.let { sigF ->
            val sig = parse(sigF.bytes!!)
            sb.appendLine("Sinal: ${sig.str(2) ?: sig.num(1)} (${sig.str(4) ?: sig.num(3)})" + (sig.num(9)?.let { "  endereço: 0x" + java.lang.Long.toHexString(it) } ?: ""))
        }
        t.str(14)?.takeIf { it.isNotBlank() }?.let { sb.appendLine("Mensagem: $it") }
        for (c in t.filter { it.num == 15 && it.bytes != null }) parse(c.bytes!!).str(1)?.let { sb.appendLine("Causa: $it") }
        // threads: map<uint32, Thread> (entries: key = 1, value = 2)
        val threads = t.filter { it.num == 16 && it.bytes != null }.mapNotNull { e -> parse(e.bytes!!).firstOrNull { it.num == 2 && it.bytes != null }?.let { parse(it.bytes!!) } }
        fun frames(th: List<Field>, max: Int): String = th.filter { it.num == 4 && it.bytes != null }.take(max).mapIndexed { k, f ->
            val fr = parse(f.bytes!!)
            val file = fr.str(6)?.substringAfterLast('/') ?: "?"
            val fn = fr.str(4)?.takeIf { it.isNotEmpty() }?.let { " $it+${fr.num(5) ?: 0}" } ?: ""
            "  #%02d %s pc 0x%x%s".format(k, file, fr.num(1) ?: 0L, fn)
        }.joinToString("\n")
        val crashing = threads.firstOrNull { it.num(1) == tid }
        if (crashing != null) {
            sb.appendLine("Thread que caiu: ${crashing.str(2) ?: "?"} (tid $tid)")
            sb.appendLine(frames(crashing, 40))
        }
        sb.appendLine()
        sb.appendLine("Outras threads (topo da pilha):")
        for (th in threads) if (th !== crashing) {
            val name = th.str(2) ?: "?"
            val top = frames(th, 3)
            if (top.contains("libhyperxr") || top.contains("libonnx") || top.contains("libGLES") || top.contains("libopenxr") || top.contains("libyuv") || top.contains("libretro") || top.contains("libluajit"))
                sb.appendLine("$name\n$top")
        }
        sb.toString()
    }.getOrElse { "Não consegui ler a pilha nativa: ${it.message}" }

    /** An error that was caught (a game that failed and was closed), kept like a crash so it can be sent. */
    fun record(context: Context, what: String, error: Throwable) = runCatching {
        val sw = StringWriter()
        error.printStackTrace(PrintWriter(sw))
        write(context.applicationContext, "caught", "Tipo: erro capturado (o app continuou)\nOnde: $what\n\n" + sw.toString().take(12000) +
            "\nÚltimos eventos:\n" + Diagnostics.recentEvents().takeLast(30).joinToString("\n"))
    }

    private fun write(app: Context, tag: String, body: String, time: Long = System.currentTimeMillis()) {
        val d = dir(app)
        val head = "HyperMagic ${versionName(app)} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Data: ${fmt(time)}\n"
        File(d, "$time-$tag.txt").writeText(head + body)
        d.listFiles()?.sortedByDescending { it.name }?.drop(MAX_REPORTS)?.forEach { it.delete() }
    }

    private fun versionName(app: Context) = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun fmt(t: Long) = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date(t))

    fun reports(context: Context): List<File> = dir(context).listFiles()?.sortedByDescending { it.name } ?: emptyList()

    fun clear(context: Context) { dir(context).listFiles()?.forEach { it.delete() } }
}
