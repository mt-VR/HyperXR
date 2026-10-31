package com.hyperxr.ui2d

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperxr.compat.CompatLayer
import com.hyperxr.files.ApkImports
import com.hyperxr.files.ImportedGame
import com.hyperxr.files.ImportedGames
import com.hyperxr.shell.ShellBridge
import com.hyperxr.shell.ShizukuState
import com.hyperxr.xrapps.ApkReport
import com.hyperxr.xrapps.PatchReport
import com.hyperxr.xrapps.QuestApk
import com.hyperxr.xrapps.Verdict
import com.hyperxr.xrapps.XrApi
import java.io.File

/**
 * Import an APK in the 2D app (OpenXR, Daydream, Cardboard): analyse, patch for the
 * HyperXR runtime, sign and install, all on the phone screen where the installer asks anyway.
 */
class ApkImportState(private val a: MainActivity) {
    var file by mutableStateOf<File?>(null)
    var report by mutableStateOf<ApkReport?>(null)
    var plan by mutableStateOf<PatchReport?>(null)
    var status by mutableStateOf<String?>(null)
    var busy by mutableStateOf(false)
    var open by mutableStateOf(false)
    private var label = ""

    fun import(uri: Uri) = analyse("Copiando e analisando o APK…") { ApkImports.copy(a, uri) }

    /** An APK already on the phone (found by the import tab): read where it is, nothing copied. */
    fun importFile(f: File) = analyse("Analisando o APK…") {
        ApkImports.dir(a).listFiles()?.forEach { it.delete() }
        f
    }

    private fun analyse(first: String, source: () -> File) {
        open = true; busy = true; report = null; plan = null; file = null; status = first
        a.io.execute {
            try {
                val picked = source()
                // split bundles (.xapk/.apks): base + this phone's native libraries in one APK
                val f = if (com.hyperxr.xrapps.ApkBundle.isBundle(picked)) {
                    a.runOnUiThread { status = "Juntando o pacote dividido num APK…" }
                    com.hyperxr.xrapps.ApkBundle.merge(picked, File(ApkImports.dir(a), picked.nameWithoutExtension + "-merged.apk"), Build.SUPPORTED_ABIS.toList()).apk
                } else picked
                val r = QuestApk.inspect(f, Build.SUPPORTED_ABIS.toList())
                val p = if (r.xrApi != XrApi.NONE || r.questApp) runCatching { CompatLayer.plan(a, f) }.getOrNull() else null
                a.runOnUiThread { file = f; label = picked.nameWithoutExtension; report = r; plan = p; status = null; busy = false }
            } catch (t: Throwable) {
                a.runOnUiThread { status = "Não foi possível ler o APK: ${t.message}"; busy = false }
            }
        }
    }

    /** Everything the analysis found, as text to paste in a message when a game does not work. */
    fun reportText(): String {
        val r = report ?: return status ?: ""
        return buildString {
            appendLine("${r.packageName} ${r.versionName} (${r.versionCode}) • ${r.verdict} • ${r.xrApi} • ${r.engine} • loader ${r.loader}")
            appendLine("Android ${r.minSdk}+ (alvo ${r.targetSdk}) • ABIs ${r.abis.joinToString()} • gráficos ${r.graphics.joinToString()}")
            appendLine("Quest ${r.questApp} • Pico ${r.picoApp} • Platform SDK ${r.platformSdk} • atividade ${r.launchActivity}")
            r.reasons.forEach { appendLine("• $it") }
            plan?.let { p ->
                p.changes.forEach { appendLine("Patch: $it") }
                p.warnings.forEach { appendLine("Aviso: $it") }
                p.blockers.forEach { appendLine("Impede: $it") }
            }
            status?.let { appendLine("Estado: $it") }
            appendLine("Bibliotecas: ${r.nativeLibs.joinToString()}")
            appendLine("Extensões: ${r.extensions.sorted().joinToString()}")
            append("Celular: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}, ${Build.SUPPORTED_ABIS.joinToString()}")
        }
    }

    /** Whether the analysed app is installed now (the installer may have finished meanwhile). */
    fun installedNow(): Boolean = report?.let { CompatLayer.installed(a, it.packageName) } ?: false

    fun play() {
        val r = report ?: return
        try { ImportedGames.launch(a, ImportedGames.all(a).firstOrNull { it.first.packageName == r.packageName }?.first ?: gameOf(r, false)) }
        catch (t: Throwable) { status = "Não abriu: ${t.message}" }
    }

    private fun gameOf(r: ApkReport, patched: Boolean) =
        ImportedGame(r.packageName, ImportedGames.installedLabel(a, r.packageName) ?: label, r.launchActivity, r.xrApi, patched, System.currentTimeMillis())

    fun patchAndInstall() {
        val f = file ?: return
        busy = true
        a.io.execute {
            try {
                val (rep, out) = CompatLayer.patchAndSign(a, f) { s -> a.runOnUiThread { status = s } }
                if (out == null) a.runOnUiThread { status = rep.blockers.joinToString(" • "); busy = false }
                else install(out, patched = true)
            } catch (t: Throwable) {
                a.runOnUiThread { status = "Falha no patch: ${t.message}"; busy = false }
            }
        }
    }

    fun installOriginal() { file?.let { busy = true; a.io.execute { install(it, patched = false) } } }

    /** Shizuku installs directly; otherwise the Android installer asks on screen. */
    private fun install(apk: File, patched: Boolean) {
        report?.let { ImportedGames.remember(a, it, label, patched) }  // listed in the import tab once installed
        if (ShellBridge.state(a) == ShizukuState.READY) {
            ShellBridge.withService(a) { svc ->
                a.io.execute {
                    val err = svc?.let { s -> ParcelFileDescriptor.open(apk, ParcelFileDescriptor.MODE_READ_ONLY).use { s.installApk(it, apk.length()) } } ?: "Shizuku não respondeu"
                    a.runOnUiThread {
                        status = when {
                            err.isEmpty() -> "Instalado. Toque em Jogar, ou abra pela aba Importar e jogar."
                            err.contains("UPDATE_INCOMPATIBLE") || err.contains("signatures do not match", true) ->
                                "Já existe uma versão com outra assinatura: desinstale-a e tente de novo (os dados do jogo serão apagados)."
                            else -> "Falha: $err"
                        }
                        busy = false
                    }
                }
            }
            return
        }
        a.runOnUiThread {
            if (!a.packageManager.canRequestPackageInstalls()) {
                a.startActivity(Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${a.packageName}")))
                status = "Permita \"instalar apps desconhecidos\" para o HyperMagic e toque em instalar de novo."
                busy = false
                return@runOnUiThread
            }
            try {
                val uri = androidx.core.content.FileProvider.getUriForFile(a, "${a.packageName}.files", apk)
                a.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
                status = "Confirme a instalação. Se falhar por assinatura, desinstale a versão antiga primeiro."
            } catch (t: Throwable) { status = "Instalador indisponível: ${t.message}" }
            busy = false
        }
    }
}

@Composable
fun ApkImportCard(a: MainActivity) {
    SectionCard("Corrigir APK: OpenXR e Daydream", "Escolha o APK de um jogo OpenXR ou de um app Daydream/Cardboard. O HyperMagic diz se ele pode rodar, corrige o que dá (Daydream vira Cardboard), assina e instala. Jogos de Quest e Pico não são suportados.") {
        Button(onClick = { a.pickApk.launch(arrayOf("application/vnd.android.package-archive", "application/octet-stream", "application/zip", "application/x-zip-compressed", "application/xapk-package-archive", "application/vnd.apkm")) },
            colors = ButtonDefaults.buttonColors(containerColor = HxColors.accent)) { Text("Escolher APK") }
        Note("Tudo sobre importar e jogar fica na aba Importar e jogar. No XR: Apps XR > Importar APK para corrigir.")
    }
}

@Composable
fun ApkImportDialog(a: MainActivity) {
    val s = a.apkImport
    if (!s.open) return
    val r = s.report
    AlertDialog(
        onDismissRequest = { if (!s.busy) s.open = false },
        title = { Text(r?.packageName ?: "Importar APK") },
        text = {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (r != null) {
                    val verdict = when (r.verdict) { Verdict.LIKELY_RUNS -> "Deve funcionar"; Verdict.MAY_RUN -> "Pode funcionar"; Verdict.WONT_RUN -> "Não vai funcionar" }
                    val api = when (r.xrApi) { XrApi.OPENXR -> "OpenXR"; XrApi.VRAPI -> "VrApi (Meta)"; XrApi.CARDBOARD -> "Cardboard"; XrApi.DAYDREAM -> "Daydream"; XrApi.NONE -> "sem XR" }
                    Text("$verdict • $api • ${r.engine}" + if (r.questApp) " • app de Quest" else "", color = HxColors.text)
                    Text("Versão ${r.versionName} • Android ${r.minSdk}+", fontSize = 13.sp, color = HxColors.dim)
                    r.reasons.take(6).forEach { Text("• $it", fontSize = 13.sp, color = HxColors.text) }
                    s.plan?.let { p ->
                        if (p.changes.isNotEmpty()) Text("Patch: " + p.changes.joinToString("; "), fontSize = 13.sp, color = HxColors.accent)
                        p.warnings.forEach { Text("Aviso: $it", fontSize = 13.sp, color = HxColors.warn) }
                        p.blockers.forEach { Text("Impede: $it", fontSize = 13.sp, color = HxColors.bad) }
                    }
                }
                s.status?.let { Text(it, color = HxColors.accent) }
            }
        },
        confirmButton = {
            Row {
                val p = s.plan
                if (r != null && p != null && p.blockers.isEmpty() && p.changes.isNotEmpty())
                    TextButton(enabled = !s.busy, onClick = { s.patchAndInstall() }) { Text("Corrigir e instalar") }
                if (r != null) TextButton(enabled = !s.busy, onClick = { s.installOriginal() }) { Text("Instalar original") }
                @Suppress("UNUSED_EXPRESSION") a.refresh  // re-checked when the installer returns
                if (r != null && !s.busy && s.installedNow()) TextButton(onClick = { s.play() }) { Text("Jogar") }
            }
        },
        dismissButton = {
            Row {
                if (r != null || s.status != null) TextButton(onClick = {
                    a.getSystemService(android.content.ClipboardManager::class.java)
                        ?.setPrimaryClip(android.content.ClipData.newPlainText("HyperMagic APK", s.reportText()))
                    android.widget.Toast.makeText(a, "Relatório copiado", android.widget.Toast.LENGTH_SHORT).show()
                }) { Text("Copiar relatório") }
                TextButton(enabled = !s.busy, onClick = { s.open = false }) { Text("Fechar") }
            }
        },
    )
}
