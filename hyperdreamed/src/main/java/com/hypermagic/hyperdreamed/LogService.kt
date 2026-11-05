package com.hypermagic.hyperdreamed

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import kotlin.system.exitProcess

/**
 * Shizuku UserService, running in its own process with the shell UID. The shell user is allowed to
 * read every app's system log (READ_LOGS) and to install and remove packages, which an ordinary
 * app cannot. HyperDreamed only talks to it over Binder, and it exists only after the user grants
 * HyperDreamed permission in Shizuku.
 */
class LogService() : ILogService.Stub() {
    @Suppress("UNUSED_PARAMETER")
    constructor(context: android.content.Context) : this()

    override fun destroy() = exitProcess(0)

    override fun info(): String = "uid=${Process.myUid()} sdk=${Build.VERSION.SDK_INT}"

    override fun clearLog() { run(listOf("logcat", "-c")); run(listOf("logcat", "-b", "crash", "-c")) }

    override fun dump(packagesCsv: String, maxLines: Int): String {
        val pkgs = packagesCsv.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val crash = run(listOf("logcat", "-d", "-b", "crash", "-v", "threadtime", "-t", "600"))
        val main = run(listOf("logcat", "-d", "-b", "main,system", "-v", "threadtime", "-t", maxLines.coerceIn(1000, 20000).toString()))
        val kept = main.lineSequence().filter { l -> pkgs.any { l.contains(it) } || TAGS.any { l.contains(it) } }.toList()
        return buildString {
            append("=== ").append(info()).append(" ===\n")
            append("=== crash ===\n")
            append(if (crash.isBlank()) "(buffer de crash vazio)\n" else crash)
            append("\n=== main/system (filtrado) ===\n")
            append(kept.takeLast(500).joinToString("\n"))
        }
    }

    override fun installApk(apk: ParcelFileDescriptor, size: Long): String = try {
        val p = ProcessBuilder("pm", "install", "-r", "-g", "-S", size.toString()).redirectErrorStream(true).start()
        ParcelFileDescriptor.AutoCloseInputStream(apk).use { input -> p.outputStream.use { input.copyTo(it, 256 * 1024) } }
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        if (out.contains("Success")) "" else out.ifEmpty { "pm install falhou (código ${p.exitValue()})" }
    } catch (t: Throwable) { "Erro: ${t.message}" }

    override fun uninstall(pkg: String): String {
        if (!pkg.matches(PKG)) return "pacote inválido"
        val out = run(listOf("pm", "uninstall", pkg)).trim()
        return if (out.contains("Success")) "" else out
    }

    override fun launch(pkg: String): String {
        if (!pkg.matches(PKG)) return "pacote inválido"
        // monkey finds and starts the package's launcher activity without naming the component
        val out = run(listOf("monkey", "-p", pkg, "-c", "android.intent.category.LAUNCHER", "1")).trim()
        return if (out.contains("No activities found")) "Sem tela inicial para abrir em $pkg" else ""
    }

    private fun run(cmd: List<String>): String = try {
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out
    } catch (t: Throwable) { "Erro: ${t.message}" }

    companion object {
        private val PKG = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+$")
        /** Log tags worth keeping even when the line does not name the package. */
        private val TAGS = listOf(
            "AndroidRuntime", "FATAL", "DEBUG", "libc", "VrCore", "vrcore", "VrCtl", "VrSettings",
            "DaydreamApi", "DaydreamManager", "GvrApi", "GvrLayout", "Gvr", "vr.home", "VrHome",
            "GoogleSignatureVerifier", "SignatureCheck", "SecurityException", "crash",
        )
    }
}
