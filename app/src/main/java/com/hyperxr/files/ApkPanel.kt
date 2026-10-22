package com.hyperxr.files

import android.content.Intent
import android.graphics.Color
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.Settings
import com.hyperxr.HyperXRApp
import com.hyperxr.shell.ShellBridge
import com.hyperxr.shell.ShizukuState
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import com.hyperxr.xrapps.ApkReport
import com.hyperxr.xrapps.LoaderKind
import com.hyperxr.xrapps.QuestApk
import com.hyperxr.xrapps.Verdict
import com.hyperxr.xrapps.XrApi
import java.io.File

/**
 * Install an APK (Quest games included): shows what the APK is and whether HyperXR's OpenXR
 * runtime can run it, then installs it with Shizuku (`pm install`, no prompt, OBB copied too) or
 * hands it to the system installer.
 */
class ApkPanel(private val host: MediaHost, private val apk: File) : XrPanel(1.4f, 1.1f, title = "Instalar ${apk.name}") {
    private val app = HyperXRApp.instance
    @Volatile private var report: ApkReport? = null
    @Volatile private var error: String? = null
    @Volatile private var obbs: List<File> = emptyList()
    @Volatile private var status: String? = null
    @Volatile private var busy = false
    @Volatile private var installed = false

    init {
        val ext = FileKinds.extension(apk.name)
        if (ext != "apk") error = "Pacotes divididos (.$ext) ainda não são suportados: use um APK único."
        else mediaIo.execute {
            try {
                val r = QuestApk.inspect(apk, Build.SUPPORTED_ABIS.toList())
                obbs = QuestApk.findObbs(apk, r.packageName)
                installed = isInstalled(r.packageName)
                report = r
            } catch (t: Throwable) { error = "Não foi possível ler o APK: ${t.message}" }
            dirty = true
        }
    }

    private fun isInstalled(pkg: String) = try { app.packageManager.getPackageInfo(pkg, 0); true } catch (_: Throwable) { false }

    override fun draw(c: PanelCanvas) {
        val r = report
        if (error != null) { c.paragraph(error!!, 30f, XrTheme.danger); return }
        if (r == null) { c.paragraph("Analisando o APK (bibliotecas e manifesto)...", 30f); return }
        c.label(r.packageName, c.pad, c.y, 34f, XrTheme.text, bold = true)
        c.y += 50f
        c.paragraph("Versão ${r.versionName} (${r.versionCode}) • Android ${r.minSdk}+ • ${FileKinds.formatSize(apk.length())}" +
            if (installed) " • já instalado" else "", 24f)
        var x = c.pad
        val (vText, vColor) = when (r.verdict) {
            Verdict.LIKELY_RUNS -> "Deve funcionar" to XrTheme.ok
            Verdict.MAY_RUN -> "Pode funcionar" to Color.rgb(214, 160, 40)
            Verdict.WONT_RUN -> "Não vai funcionar" to XrTheme.danger
        }
        x += c.chip(vText, x, c.y, vColor) + 10f
        if (r.questApp) x += c.chip("App de Quest", x, c.y, XrTheme.button) + 10f
        x += c.chip(r.engine, x, c.y, XrTheme.button) + 10f
        x += c.chip(when (r.xrApi) { XrApi.OPENXR -> "OpenXR"; XrApi.VRAPI -> "VrApi (Meta)"; XrApi.CARDBOARD -> "Cardboard"; XrApi.NONE -> "Sem XR" }, x, c.y, XrTheme.button) + 10f
        if (r.graphics.isNotEmpty()) c.chip(r.graphics.joinToString(" + "), x, c.y, XrTheme.button)
        c.y += 58f
        if (r.loader != LoaderKind.NONE) c.paragraph("Loader OpenXR: " + when (r.loader) {
            LoaderKind.KHRONOS_BROKER -> "Khronos (usa o broker)"; LoaderKind.META_FORWARD -> "Meta (só Quest)"; else -> "não identificado"
        }, 24f)
        for (reason in r.reasons.take(6)) c.paragraph("• $reason", 24f, XrTheme.text)
        if (obbs.isNotEmpty()) c.paragraph("OBB encontrado: ${obbs.joinToString { it.name }} (${FileKinds.formatSize(obbs.sumOf { it.length() })})", 24f, XrTheme.textDim)
        else if (r.questApp) c.paragraph("Nenhum OBB ao lado do APK (muitos jogos de Quest não usam).", 24f, XrTheme.textDim)
        status?.let { c.paragraph(it, 26f, XrTheme.accent2) }
        // actions
        val gap = 10f
        val y = c.h - 100f
        val shizuku = ShellBridge.state(app) == ShizukuState.READY
        val label = (if (installed) "Reinstalar" else "Instalar") + (if (obbs.isNotEmpty()) " + OBB" else "") + if (shizuku) " (Shizuku)" else ""
        c.button("install", label, c.pad, y, 420f, 80f, accent = true, enabled = !busy) { install(r, shizuku) }
        if (installed && r.launchActivity != null) c.button("open", "Abrir", c.pad + 420f + gap, y, 200f, 80f, enabled = !busy) { launch(r) }
    }

    private fun launch(r: ApkReport) = host.activity.runOnUiThread {
        try {
            host.activity.startActivity(Intent(Intent.ACTION_MAIN).setClassName(r.packageName, r.launchActivity!!).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (t: Throwable) { status = "Não abriu: ${t.message}"; dirty = true }
    }

    private fun install(r: ApkReport, shizuku: Boolean) {
        busy = true; status = "Instalando..."; dirty = true
        if (shizuku) ShellBridge.withService(app) { svc ->
            mediaIo.execute {
                val msgs = ArrayList<String>()
                if (svc == null) msgs += "Shizuku não respondeu"
                else {
                    val err = ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { svc.installApk(it, apk.length()) }
                    if (err.isEmpty()) {
                        msgs += "Instalado"
                        for (o in obbs) {
                            status = "Copiando ${o.name}..."; dirty = true
                            val name = if (QuestApk.isValidObbName(o.name)) o.name else "main.${r.versionCode}.${r.packageName}.obb"
                            val e2 = ParcelFileDescriptor.open(o, ParcelFileDescriptor.MODE_READ_ONLY).use { svc.copyObb(it, r.packageName, name) }
                            msgs += if (e2.isEmpty()) "OBB copiado" else "OBB falhou: $e2"
                        }
                    } else msgs += "Falha: $err"
                }
                finish(r, msgs.joinToString(" • "))
            }
        } else installWithSystem(r)
    }

    /** No Shizuku: the system installer asks the user (on the phone screen), OBBs copied directly when Android allows. */
    private fun installWithSystem(r: ApkReport) {
        val act = host.activity
        if (Build.VERSION.SDK_INT >= 26 && !act.packageManager.canRequestPackageInstalls()) {
            act.runOnUiThread { act.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${act.packageName}"))) }
            busy = false; status = "Permita \"instalar apps desconhecidos\" para o HyperXR na tela do celular e toque em Instalar de novo"; dirty = true
            return
        }
        mediaIo.execute {
            val msgs = ArrayList<String>()
            for (o in obbs) {
                val name = if (QuestApk.isValidObbName(o.name)) o.name else "main.${r.versionCode}.${r.packageName}.obb"
                msgs += try {
                    val dir = File(android.os.Environment.getExternalStorageDirectory(), "Android/obb/${r.packageName}").apply { mkdirs() }
                    FileOps.copyInto(o, dir).let { copied -> if (copied.name != name) copied.renameTo(File(dir, name)) }
                    "OBB copiado"
                } catch (t: Throwable) { "OBB não copiado (ative o Shizuku para copiar em Android/obb)" }
            }
            act.runOnUiThread {
                try {
                    val uri = androidx.core.content.FileProvider.getUriForFile(act, "${act.packageName}.files", apk)
                    act.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                    msgs.add(0, "Confirme a instalação na tela do celular")
                } catch (t: Throwable) { msgs.add(0, "Instalador do sistema indisponível: ${t.message}") }
                finish(r, msgs.joinToString(" • "))
            }
        }
    }

    private fun finish(r: ApkReport, msg: String) {
        status = msg; busy = false
        installed = isInstalled(r.packageName)
        if (installed) status += " • aparece em Apps XR"
        dirty = true
    }

    private var poll = 0f
    override fun update(dt: Float) {
        // the system installer finishes outside HyperXR: notice when the package appears
        val r = report ?: return
        poll += dt
        if (poll < 2f || installed || busy) return
        poll = 0f
        if (isInstalled(r.packageName)) { installed = true; status = (status ?: "") + " • instalado"; dirty = true }
    }
}
