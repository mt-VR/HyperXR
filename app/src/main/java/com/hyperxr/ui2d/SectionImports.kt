package com.hyperxr.ui2d

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.hyperxr.files.ApkImports
import com.hyperxr.files.FileKinds
import com.hyperxr.files.ImportedGames
import com.hyperxr.files.StorageAccess
import com.hyperxr.xrapps.XrApi
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val APK_TYPES = arrayOf(
    "application/vnd.android.package-archive", "application/octet-stream", "application/zip",
    "application/x-zip-compressed", "application/xapk-package-archive", "application/vnd.apkm",
)

private fun apiName(api: XrApi) = when (api) {
    XrApi.OPENXR -> "OpenXR"; XrApi.VRAPI -> "VrApi (Meta)"; XrApi.CARDBOARD -> "Cardboard"; XrApi.DAYDREAM -> "Daydream"; XrApi.NONE -> "app 2D"
}

/**
 * The import tab: bring in an APK (picker or the ones already on the phone), let HyperMagic
 * analyse, patch and install it, and start the imported games from one list.
 */
@Composable
fun ImportsSection(a: MainActivity) {
    SectionCard("Importar um APK", "Jogos OpenXR, de Quest e de Pico, apps Daydream e Cardboard, e pacotes divididos (.xapk, .apks, .apkm). O HyperMagic diz se roda, corrige para o runtime HyperMagic, assina e instala.") {
        Button(onClick = { a.pickApk.launch(APK_TYPES) }, colors = ButtonDefaults.buttonColors(containerColor = HxColors.accent)) { Text("Escolher APK") }
        Note("Com o Shizuku ativo a instalação é direta; sem ele, o instalador do Android pede confirmação.")
    }
    ImportedGamesCard(a)
    PhoneApksCard(a)
}

@Composable
private fun ImportedGamesCard(a: MainActivity) {
    var tick by remember { mutableIntStateOf(0) }
    val games = remember(a.refresh, tick) { ImportedGames.all(a) }
    var error by remember { mutableStateOf<String?>(null) }
    SectionCard("Seus jogos importados", "Toque em Jogar com o celular já no visor ou logo antes de colocá-lo.") {
        if (games.isEmpty()) Note("Nenhum jogo importado ainda. Escolha um APK acima ou um da lista abaixo.")
        games.forEach { (g, installed) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppIcon(a, g.packageName, installed)
                Column(Modifier.weight(1f)) {
                    Text(if (installed) ImportedGames.installedLabel(a, g.packageName) ?: g.label else g.label, color = HxColors.text, fontWeight = FontWeight.SemiBold)
                    Note(listOfNotNull(apiName(g.api), if (g.patched) "corrigido pelo HyperMagic" else "original", if (installed) null else "não instalado").joinToString(" • "))
                }
                if (installed) {
                    Button(onClick = {
                        error = try { ImportedGames.launch(a, g); null } catch (t: Throwable) { "Não abriu ${g.label}: ${t.message}" }
                    }, colors = ButtonDefaults.buttonColors(containerColor = HxColors.accent)) { Text("Jogar") }
                    OutlinedButton(onClick = {
                        a.startActivity(Intent(Intent.ACTION_DELETE, Uri.parse("package:${g.packageName}")))
                    }) { Text("Desinstalar") }
                } else OutlinedButton(onClick = { ImportedGames.forget(a, g.packageName); tick++ }) { Text("Tirar da lista") }
            }
        }
        error?.let { Note(it, HxColors.bad) }
    }
    OtherXrAppsCard(a, games.map { it.first.packageName }.toSet())
}

/** XR apps installed some other way (Play Store, adb): startable from here too. */
@Composable
private fun OtherXrAppsCard(a: MainActivity, imported: Set<String>) {
    val others = remember(a.refresh, imported) { a.app.xrApps.discover().filter { it.packageName !in imported } }
    if (others.isEmpty()) return
    SectionCard("Outros apps XR instalados", "Instalados sem passar pelo HyperMagic: abrem como estão, sem correção.") {
        others.forEach { x ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppIcon(a, x.packageName, true)
                Column(Modifier.weight(1f)) {
                    Text(x.entry.title, color = HxColors.text, fontWeight = FontWeight.SemiBold)
                    Note(x.entry.runtime)
                }
                OutlinedButton(onClick = { a.app.xrApps.launch(x) }) { Text("Jogar") }
            }
        }
    }
}

/** APK files already on the phone (Download, Documentos, Bluetooth...), importable with one tap. */
@Composable
private fun PhoneApksCard(a: MainActivity) {
    val granted = remember(a.refresh) { StorageAccess.granted(a) }
    var files by remember { mutableStateOf<List<File>?>(null) }
    var scan by remember { mutableIntStateOf(0) }
    var shown by remember { mutableIntStateOf(15) }
    LaunchedEffect(granted, scan) {
        if (!granted) return@LaunchedEffect
        files = null
        a.io.execute { val found = runCatching { ApkImports.scan() }.getOrDefault(emptyList()); a.runOnUiThread { files = found } }
    }
    val fmt = remember { SimpleDateFormat("dd/MM HH:mm", Locale.getDefault()) }
    SectionCard("APKs no celular", "Os APKs que já estão no celular, do mais novo ao mais antigo.") {
        if (!granted) {
            Note("Para listar os APKs, permita o acesso aos arquivos.")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { StorageAccess.request(a) }) { Text("Permitir acesso") }
                OutlinedButton(onClick = { a.refresh++ }) { Text("Já permiti") }
            }
            return@SectionCard
        }
        val list = files
        when {
            list == null -> Note("Procurando APKs…")
            list.isEmpty() -> Note("Nenhum APK encontrado (Download, Documentos, Bluetooth...).")
            else -> list.take(shown).forEach { f ->
                Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(f.name, color = HxColors.text)
                        Note("${f.parentFile?.name ?: ""} • ${FileKinds.formatSize(f.length())} • ${fmt.format(Date(f.lastModified()))}")
                    }
                    OutlinedButton(enabled = !a.apkImport.busy, onClick = { a.apkImport.importFile(f) }) { Text("Importar") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (list != null && list.size > shown) OutlinedButton(onClick = { shown += 15 }) { Text("Mostrar mais") }
            OutlinedButton(onClick = { scan++ }) { Text("Procurar de novo") }
        }
    }
}

@Composable
private fun AppIcon(a: MainActivity, pkg: String, installed: Boolean) {
    val icon: ImageBitmap? = remember(pkg, installed) {
        if (!installed) null else runCatching { a.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap() }.getOrNull()
    }
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(10.dp))) {
        if (icon != null) Image(icon, contentDescription = null, modifier = Modifier.size(44.dp))
    }
}
