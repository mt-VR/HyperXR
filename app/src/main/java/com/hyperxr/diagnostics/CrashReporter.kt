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
        val last = prefs.getLong("lastExit", 0L)
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
        if (newest != last) prefs.edit().putLong("lastExit", newest).apply()
    }

    /** The tombstone is a protobuf: its readable strings (signal, libraries, functions) are enough to locate the crash. */
    private fun nativeFrames(bytes: ByteArray): String {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        for (b in bytes) {
            val c = b.toInt() and 0xFF
            if (c in 32..126) sb.append(c.toChar())
            else {
                if (sb.length >= 6) out += sb.toString()
                sb.setLength(0)
            }
        }
        val useful = out.filter { s -> s.contains(".so") || s.contains("SIG") || s.contains("Abort") || s.contains("hyperxr") || s.contains("Java_") || s.startsWith("_Z") }
        return "Pilha nativa (resumo):\n" + useful.distinct().take(80).joinToString("\n")
    }

    private fun write(app: Context, tag: String, body: String, time: Long = System.currentTimeMillis()) {
        val d = dir(app)
        val head = "HyperXR ${versionName(app)} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}\n" +
            "Data: ${fmt(time)}\n"
        File(d, "$time-$tag.txt").writeText(head + body)
        d.listFiles()?.sortedByDescending { it.name }?.drop(MAX_REPORTS)?.forEach { it.delete() }
    }

    private fun versionName(app: Context) = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull() ?: "?"

    private fun fmt(t: Long) = SimpleDateFormat("dd/MM/yyyy HH:mm:ss", Locale.US).format(Date(t))

    fun reports(context: Context): List<File> = dir(context).listFiles()?.sortedByDescending { it.name } ?: emptyList()

    fun clear(context: Context) { dir(context).listFiles()?.forEach { it.delete() } }
}
