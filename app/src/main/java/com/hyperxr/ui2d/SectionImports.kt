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
import com.hyperxr.compat.CompatLayer
import com.hyperxr.files.ApkImports
import com.hyperxr.files.FileKinds
import com.hyperxr.files.ImportedGames
import com.hyperxr.files.StorageAccess
import com.hyperxr.files.downloadFile
import com.hyperxr.files.githubGet
import com.hyperxr.xrapps.OpenGames
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
    SectionCard("Importar um APK", "Jogos OpenXR para Android (loader da Khronos), apps Daydream e Cardboard, e pacotes divididos (.xapk, .apks, .apkm). O HyperMagic diz se roda, corrige o que dá, assina e instala. Jogos feitos para Quest ou Pico não são suportados.") {
        Button(onClick = { a.pickApk.launch(APK_TYPES) }, colors = ButtonDefaults.buttonColors(containerColor = HxColors.accent)) { Text("Escolher APK") }
        Note("Com o Shizuku ativo a instalação é direta; sem ele, o instalador do Android pede confirmação.")
    }
    RuntimeReadyCard(a)
    ImportedGamesCard(a)
    OpenSourceGamesCard(a)
    PhoneApksCard(a)
}

/** OpenXR games find the runtime only through a broker: install the one inside HyperMagic. */
@Composable
private fun RuntimeReadyCard(a: MainActivity) {
    val broker = a.app.runtimeBroker
    var active by remember { mutableStateOf<Boolean?>(null) }
    var owners by remember { mutableStateOf<Map<String, String?>>(emptyMap()) }
    var activeName by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(a.refresh) {
        a.io.execute {
            val r = broker.activeRuntime(); val o = broker.brokerOwners()
            a.runOnUiThread { active = r?.packageName == a.packageName; activeName = r?.label; owners = o }
        }
    }
    when (active) {
        null -> return
        true -> SectionCard("Runtime OpenXR pronto", "Os jogos OpenXR encontram o runtime HyperMagic.") {}
        false -> SectionCard("Falta o broker OpenXR", "Sem ele, jogos OpenXR mostram \"OpenXR failed to start\" ou abrem fora do VR: o loader do jogo não acha o runtime HyperMagic.") {
            val other = owners["org.khronos.openxr.runtime_broker"]?.takeIf { it != CompatLayer.BROKER_PACKAGE }
            if (other != null) {
                Note("O broker $other já está instalado e tem prioridade: escolha \"HyperMagic Runtime\" nele, ou desinstale-o e instale o do HyperMagic.", HxColors.warn)
            } else {
                if (activeName != null) Note("Hoje os jogos recebem o runtime \"$activeName\".", HxColors.warn)
                Button(onClick = {
                    status = "Instalando…"
                    CompatLayer.installBroker(a) { msg -> a.runOnUiThread { status = msg; a.refresh++ } }
                }, colors = ButtonDefaults.buttonColors(containerColor = HxColors.accent)) { Text("Instalar o broker do HyperMagic") }
            }
            status?.let { Note(it) }
        }
    }
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
                    OutlinedButton(onClick = { a.closeCheck.show(g.packageName, "Log de ${g.label}") }) { Text("Log") }
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

/**
 * Open-source VR games: the APK of each one's release on GitHub is downloaded and goes through the
 * same analysis / fix / install as any imported APK.
 */
@Composable
private fun OpenSourceGamesCard(a: MainActivity) {
    var busy by remember { mutableStateOf<String?>(null) }
    var status by remember { mutableStateOf<String?>(null) }
    SectionCard("Jogos open source", "Código aberto, com a licença conferida. Os marcados \"pronto\" foram compilados pelo HyperMagic para o runtime dele; os outros passam pelo Corrigir e instalar.") {
        OpenGames.CURATED.forEach { g ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(g.name + if (g.ready) "  • pronto" else "", color = HxColors.text, fontWeight = FontWeight.SemiBold)
                    Note("${g.description} (${g.license}, ${g.kind.name.lowercase()})")
                    if (g.needs.isNotEmpty()) Note("Precisa de: ${g.needs}", HxColors.warn)
                }
                OutlinedButton(enabled = busy == null && !a.apkImport.busy, onClick = {
                    busy = g.repo; status = "Procurando o APK de ${g.name}…"
                    a.io.execute {
                        val msg = try {
                            val apk = OpenGames.parseRelease(githubGet(OpenGames.releaseUrl(g))).firstOrNull()
                            if (apk == null) {
                                a.runOnUiThread { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(g.url))) }
                                "${g.name}: a última versão não tem APK; abri o código-fonte"
                            } else {
                                a.runOnUiThread { status = "Baixando ${apk.name} (${FileKinds.formatSize(apk.bytes)})…" }
                                val dir = File(a.cacheDir, "opengames").apply { deleteRecursively(); mkdirs() }
                                val out = File(dir, apk.name.replace('/', '_'))
                                downloadFile(apk.url, out)
                                a.runOnUiThread { a.apkImport.importFile(out) }
                                null
                            }
                        } catch (t: Throwable) { "Falha ao baixar ${g.name}: ${t.message}" }
                        a.runOnUiThread { busy = null; status = msg }
                    }
                }) { Text(if (busy == g.repo) "Baixando…" else "Baixar") }
            }
        }
        status?.let { Note(it) }
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
