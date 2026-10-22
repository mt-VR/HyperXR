package com.hyperxr.ui2d

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.unit.sp
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.environments.CatalogItem
import com.hyperxr.environments.EnvironmentEntry
import com.hyperxr.environments.EnvironmentKind
import com.hyperxr.gameruntime.GameActivity
import com.hyperxr.launcher.AppKind
import com.hyperxr.launcher.BuiltinApps
import com.hyperxr.openxr.OpenXrNative
import com.hyperxr.permissions.GrantPolicy
import com.hyperxr.permissions.HyperPermission
import com.hyperxr.runtimebroker.AndroidRuntimeBroker
import com.hyperxr.runtimebroker.RuntimeProbe
import com.hyperxr.runtimebroker.RuntimeSelector
import com.hyperxr.webapps.WebAppActivity
import com.hyperxr.webapps.WebAppConfig
import java.net.URL

@Composable
fun PermissionsSection(a: MainActivity) {
    var unsafeFor by remember { mutableStateOf<String?>(null) }
    SectionCard("Permissões do Android") {
        listOf(Manifest.permission.CAMERA to "Câmera (MR e hand tracking)").plus(
            if (Build.VERSION.SDK_INT >= 31) listOf(Manifest.permission.BLUETOOTH_CONNECT to "Bluetooth (estado dos Joy-Cons)") else emptyList(),
        ).forEach { (p, label) ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), color = HxColors.text)
                Text(if (a.hasPermission(p)) "concedida" else "não concedida", color = if (a.hasPermission(p)) HxColors.ok else HxColors.warn)
            }
        }
        OutlinedButton(onClick = { a.requestRuntimePermissions() }) { Text("Solicitar permissões") }
    }
    SectionCard("Pacotes .hyperx", "Cada pacote declara permissões no manifesto. Instalação inspeciona integridade e caminhos sem executar nada.") {
        Button(onClick = { a.pickPackage.launch(arrayOf("*/*")) }) { Text("Instalar pacote .hyperx…") }
        for (p in a.app.packages.list()) {
            Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).then(Modifier), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("${p.manifest.name} ${p.manifest.version}  •  ${p.manifest.id}${if (p.bundled) "  •  embutido" else ""}", fontWeight = FontWeight.SemiBold, color = HxColors.text)
                Note("${p.manifest.mode} • ${p.manifest.author} • ${p.manifest.license}")
                for (perm in p.manifest.declaredPermissions.sortedBy { it.ordinal }) {
                    val granted = perm in p.grants.granted
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(perm.key, color = if (perm == HyperPermission.UNSAFE) HxColors.bad else HxColors.text)
                            Note(perm.description)
                        }
                        androidx.compose.material3.Switch(checked = granted, onCheckedChange = { v ->
                            if (v && perm == HyperPermission.UNSAFE) unsafeFor = p.manifest.id
                            else { a.app.packages.decide(p.manifest.id, perm, v); a.refresh++ }
                        })
                    }
                }
                ToggleRow("Modo estrito (JIT desligado: limite de execução sempre aplicado)", p.strictInterpreter) { v ->
                    a.app.packages.setStrict(p.manifest.id, v); a.refresh++
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { GameActivity.launch(a, p.manifest.id) }) { Text("Abrir") }
                    if (!p.bundled) OutlinedButton(onClick = { a.app.packages.uninstall(p.manifest.id); a.refresh++ }) { Text("Desinstalar") }
                }
                Gap(10)
            }
        }
    }
    unsafeFor?.let { id ->
        var typed by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { unsafeFor = null },
            title = { Text("Conceder 'unsafe' a $id?") },
            text = {
                Column {
                    Text(HyperPermission.UNSAFE.description)
                    Gap()
                    Text("Para confirmar digite UNSAFE:")
                    OutlinedTextField(value = typed, onValueChange = { typed = it }, singleLine = true)
                }
            },
            confirmButton = {
                TextButton(enabled = typed == "UNSAFE", onClick = {
                    a.app.packages.decide(id, HyperPermission.UNSAFE, approve = true, explicitConfirmation = true)
                    unsafeFor = null; a.refresh++
                }) { Text("Conceder") }
            },
            dismissButton = { TextButton(onClick = { unsafeFor = null }) { Text("Cancelar") } },
        )
    }
}

@Composable
fun RuntimeSection(a: MainActivity) {
    val broker = a.app.runtimeBroker
    val runtimes = remember(a.refresh) { broker.discover() }
    val brokerInstalled = remember(a.refresh) { broker.brokerInstalled() }
    val s = a.settingsState.runtime
    var probe by remember { mutableStateOf<RuntimeProbe?>(broker.lastProbe) }
    var probing by remember { mutableStateOf(false) }
    SectionCard("HyperXR Runtime (OpenXR próprio)", "Runtime OpenXR do próprio HyperXR: apps OpenXR rodam no VR Box com 3DoF, lente corrigida e timewarp.") {
        Note("Para apps OpenXR usarem este runtime, instale o \"OpenXR Runtime Broker\" da Khronos e escolha \"HyperXR Runtime\" nele. O runtime usa o IPD, o FOV e a lente configurados aqui.")
        Note("Suporta: sessões OpenGL ES, camadas de projeção e quad, espaços VIEW/LOCAL/STAGE/LOCAL_FLOOR, ações (sem controles: o runtime roda dentro do app e não recebe os Joy-Cons). Sem Vulkan nem 6DoF.")
        Button(onClick = { a.startActivity(android.content.Intent(a, com.hyperxr.openxr.RuntimeTestActivity::class.java)) }) {
            Text("Testar o runtime HyperXR no visor (15 s)")
        }
    }
    SectionCard("Runtime Broker do HyperXR", "Descobre runtimes OpenXR, mostra capacidades reais e evita sessões concorrentes. Não substitui o loader nem o broker da Khronos.") {
        KeyValue("Broker Khronos", if (brokerInstalled) "instalado" else "não encontrado (o loader usa o runtime do sistema, se houver)")
        if (runtimes.isEmpty()) Note("Nenhum runtime OpenXR instalado. Para usar o Monado instale o APK do runtime e selecione-o no broker.", HxColors.warn)
        runtimes.forEach { r ->
            KeyValue(r.label, "${r.packageName} • ${r.source.name}${if (r.isMonado) " • Monado" else ""}${r.versionName?.let { " • v$it" } ?: ""}")
        }
        val selected = RuntimeSelector.select(runtimes, s.preferredRuntimePackage, s.preferMonado)
        KeyValue("Selecionado", selected?.label ?: "nenhum")
        ToggleRow("Preferir Monado quando instalado", s.preferMonado) { v -> a.app.settings.update { it.copy(runtime = it.runtime.copy(preferMonado = v)) } }
        ToggleRow("Permitir sessão OpenXR (modo Runtime OpenXR)", s.allowOpenXrSession) { v -> a.app.settings.update { it.copy(runtime = it.runtime.copy(allowOpenXrSession = v)) } }
        if (runtimes.size > 1) ChoiceRow("Runtime preferido", runtimes, runtimes.firstOrNull { it.packageName == s.preferredRuntimePackage } ?: runtimes.first(), { it.label }) { r ->
            a.app.settings.update { it.copy(runtime = it.runtime.copy(preferredRuntimePackage = r.packageName)) }
        }
        Button(enabled = !probing, onClick = {
            probing = true
            a.io.execute {
                val r = OpenXrNative.probe(a)
                broker.lastProbe = r
                a.runOnUiThread { probe = r; probing = false }
            }
        }) { Text(if (probing) "Testando…" else "Testar runtime (xrCreateInstance / xrGetSystem)") }
        probe?.let { p ->
            KeyValue("Resultado", if (p.ok) "OK" else "falhou")
            p.error?.let { Note(it, HxColors.bad) }
            if (p.runtimeName.isNotEmpty()) KeyValue("Runtime", "${p.runtimeName} ${p.runtimeVersion}")
            if (p.systemName.isNotEmpty()) KeyValue("Sistema", p.systemName)
            val missing = RuntimeSelector.missingRequired(p)
            if (missing.isNotEmpty()) Note("Extensões obrigatórias ausentes: ${missing.joinToString()}", HxColors.bad)
            RuntimeSelector.WANTED_EXTENSIONS.forEach { (ext, req) ->
                KeyValue(ext, (if (ext in p.extensions) "presente" else "ausente") + if (req) " (obrigatória)" else "")
            }
        }
        Note("Histórico de sessões:")
        a.app.sessionArbiter.history().takeLast(6).forEach { Mono(it) }
    }
    SectionCard("Monado") {
        Note("O Monado é um runtime OpenXR de código aberto (Boost Software License 1.0). O HyperXR o integra como runtime externo: " +
            "embuti-lo exigiria compilar seus drivers nativos para Android e assumir o papel de runtime do sistema, o que não é possível a partir de um app comum.")
        OutlinedButton(onClick = { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(AndroidRuntimeBroker.MONADO_PROJECT_URL))) }) { Text("Projeto Monado") }
    }
}

@Composable
fun EnvironmentsSection(a: MainActivity) {
    val repo = a.app.environments
    val installed = remember(a.refresh) { repo.installed() }
    var catalog by remember { mutableStateOf<List<CatalogItem>?>(null) }
    var status by remember { mutableStateOf("") }
    val progress = remember { mutableStateMapOf<String, Float>() }
    val previews = remember { mutableStateMapOf<String, ImageBitmap>() }
    val details = remember { mutableStateMapOf<String, EnvironmentEntry>() }
    val current = a.settingsState.launcher.environmentId

    SectionCard("Instalados") {
        installed.forEach { e ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(e.name + if (e.id == current) "  (em uso)" else "", color = HxColors.text, fontWeight = FontWeight.SemiBold)
                    Note("${kindLabel(e.kind)} • ${e.license} • ${e.author} • ${e.format}")
                    e.attribution?.let { Note("Atribuição: $it") }
                }
                OutlinedButton(onClick = { a.app.settings.update { it.copy(launcher = it.launcher.copy(environmentId = e.id)) } }) { Text("Usar") }
                if (e.kind != EnvironmentKind.PROCEDURAL) OutlinedButton(onClick = { repo.delete(e); a.refresh++ }) { Text("Excluir") }
            }
        }
    }
    SectionCard("Catálogo HyperXR Environments", "Fonte: Poly Haven (todas as obras CC0, polyhaven.com/license). Cenas 3D e panoramas 360° são listados separadamente.") {
        if (catalog == null) Button(onClick = {
            status = "Carregando…"
            a.io.execute {
                val r = runCatching { repo.fetchCatalog() }
                a.runOnUiThread {
                    r.onSuccess { catalog = it; status = "" }.onFailure { status = "Falha: ${it.message}" }
                }
            }
        }) { Text("Carregar catálogo") }
        if (status.isNotEmpty()) Note(status, HxColors.warn)
        val list = catalog.orEmpty()
        for (kind in listOf(EnvironmentKind.SCENE_3D, EnvironmentKind.PANORAMA_360)) {
            val items = list.filter { it.kind == kind }.take(24)
            if (items.isEmpty()) continue
            Text(kindLabel(kind), fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = HxColors.accent)
            items.forEach { item ->
                LaunchedEffect(item.id) {
                    if (previews[item.id] == null) a.io.execute {
                        runCatching {
                            URL(item.previewUrl).openStream().use { s -> BitmapFactory.decodeStream(s) }?.asImageBitmap()
                        }.getOrNull()?.let { bmp -> a.runOnUiThread { previews[item.id] = bmp } }
                    }
                }
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    previews[item.id]?.let { Image(it, null, Modifier.size(width = 96.dp, height = if (kind == EnvironmentKind.PANORAMA_360) 48.dp else 96.dp).clip(RoundedCornerShape(10.dp))) }
                        ?: Text("", Modifier.width(96.dp))
                    Column(Modifier.weight(1f)) {
                        Text(item.name, color = HxColors.text, fontWeight = FontWeight.SemiBold)
                        val d = details[item.id]
                        Note("Autor: ${item.author} • Licença: CC0-1.0" + (d?.let { " • ${it.format} • ${"%.1f".format(it.downloadBytes / 1_048_576.0)} MB • ${it.compatibility}" } ?: ""))
                        progress[item.id]?.let { LinearProgressIndicator(progress = { it }, modifier = Modifier.fillMaxWidth().height(6.dp)) }
                    }
                    OutlinedButton(enabled = progress[item.id] == null, onClick = {
                        progress[item.id] = 0f
                        a.io.execute {
                            val entry = runCatching { repo.resolve(item) }.getOrNull()
                            if (entry == null) { a.runOnUiThread { progress.remove(item.id); status = "Arquivos indisponíveis para ${item.name}" }; return@execute }
                            a.runOnUiThread { details[item.id] = entry }
                            if (!a.settingsState.network.allowEnvironmentDownloads) { a.runOnUiThread { progress.remove(item.id); status = "Downloads desativados em Rede" }; return@execute }
                            val r = repo.download(entry, a.settingsState.network.downloadsOnWifiOnly) { done, total ->
                                a.runOnUiThread { progress[item.id] = done.toFloat() / maxOf(1L, total) }
                            }
                            a.runOnUiThread {
                                progress.remove(item.id)
                                status = r.fold({ "Instalado: ${it.name}" }, { "Falha: ${it.message}" })
                                a.refresh++
                            }
                        }
                    }) { Text("Baixar") }
                }
            }
        }
    }
}

private fun kindLabel(k: EnvironmentKind) = when (k) {
    EnvironmentKind.SCENE_3D -> "Cena 3D (geometria real)"
    EnvironmentKind.PANORAMA_360 -> "Panorama 360° (imagem, sem paralaxe)"
    EnvironmentKind.PROCEDURAL -> "Procedural (embutido)"
}

@Composable
fun AppsSection(a: MainActivity) {
    val xr = remember(a.refresh) { a.app.xrApps.discover() }
    SectionCard("Aplicativos Cardboard e OpenXR", "Detectados pelas categorias de intent que declaram. O HyperXR inicia o app como ele é; não converte Cardboard em OpenXR.") {
        if (xr.isEmpty()) Note("Nenhum app XR encontrado.")
        xr.forEach { x ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(x.entry.title, color = HxColors.text, fontWeight = FontWeight.SemiBold)
                    Note("${x.entry.category.name} • ${x.entry.runtime} • ${x.entry.compatibility}")
                    Note(x.entry.limitations)
                }
                OutlinedButton(onClick = { a.app.xrApps.launch(x) }) { Text("Iniciar") }
            }
        }
    }
    SectionCard("Jogos e apps HyperXR") {
        BuiltinApps.all.filter { it.kind == AppKind.HYPERX_PACKAGE }.forEach { app ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(app.title, Modifier.weight(1f), color = HxColors.text)
                OutlinedButton(onClick = { GameActivity.launch(a, app.target) }) { Text("Abrir em XR") }
            }
        }
        Note("Permissões pendentes são perguntadas no XR antes do início; podem ser gerenciadas em Permissões e pacotes.")
    }
    SectionCard("Aplicativos web", "Login pelos mecanismos oficiais de cada serviço. Se um serviço não funcionar na WebView, use o navegador externo.") {
        BuiltinApps.all.filter { it.kind == AppKind.WEB }.forEach { app ->
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(app.title, color = HxColors.text)
                    if (app.limitations.isNotEmpty()) Note(app.limitations)
                }
                OutlinedButton(onClick = { WebAppActivity.open(a, app.target, app.title, desktop = app.id == "web:whatsapp" || a.settingsState.network.webAppsDesktopMode) }) { Text("Abrir") }
                OutlinedButton(onClick = { WebAppConfig.openExternal(a, app.target) }) { Text("Navegador externo") }
            }
        }
    }
}

@Composable
fun DeviceSection(a: MainActivity) {
    val caps = a.app.capabilities.last
    SectionCard("Capacidades detectadas", "Valores medidos no aparelho; nada é presumido.") {
        Button(onClick = { a.io.execute { a.app.capabilities.probe(a.app.runtimeBroker); a.runOnUiThread { a.refresh++ } } }) { Text("Detectar novamente") }
        KeyValue("ABIs", caps.abis.joinToString())
        KeyValue("OpenGL ES", caps.glesVersion)
        CapabilityRow("Vulkan", caps.vulkan)
        CapabilityRow("Vetor de rotação", caps.rotationVector)
        CapabilityRow("Giroscópio", caps.gyroscope)
        CapabilityRow("Acelerômetro", caps.accelerometer)
        caps.cameras.forEach { c -> KeyValue("Câmera ${c.id}", "${if (c.facingBack) "traseira" else "frontal/externa"} • ${c.hardwareLevel} • até ${c.maxFps} fps${if (c.depthOutput) " • DEPTH" else ""}${if (c.realtimeTimestamps) " • timestamps em tempo real" else ""}") }
        CapabilityRow("ARCore", caps.arCore)
        CapabilityRow("ARCore Depth", caps.arCoreDepth)
        CapabilityRow("Runtime OpenXR", caps.openXrRuntime)
        CapabilityRow("NNAPI", caps.nnapi)
        caps.accelerators.forEach { KeyValue("  dispositivo NNAPI", "${it.name} (${it.type}, ${it.backend})") }
        CapabilityRow("Delegate GPU", caps.gpuDelegate)
        CapabilityRow("Bluetooth", caps.bluetooth)
        CapabilityRow("IMU de controles", caps.inputDeviceSensors)
        CapabilityRow("Desempenho sustentado", caps.sustainedPerformance)
    }
    DiagnosticsCard()
    SectionCard("Eventos recentes") {
        Diagnostics.recentEvents().takeLast(30).reversed().forEach { Mono(it.substringAfter(' ')) }
    }
}

@Composable
fun DiagnosticsCard() {
    var tick by remember { mutableStateOf(0) }
    SectionCard("Latência por estágio", "Média, percentis e desvio padrão das últimas amostras de cada estágio (ms). Metodologia em docs/PERFORMANCE.md.") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { tick++ }) { Text("Atualizar") }
            OutlinedButton(onClick = { Diagnostics.resetAll(); tick++ }) { Text("Zerar") }
        }
        @Suppress("UNUSED_EXPRESSION") tick
        Diagnostics.all().forEach { Mono(it.format()) }
        Note("Os valores só existem depois de uma sessão XR neste processo (jogos rodam no processo :game).")
    }
}

@Composable
fun Dialogs(a: MainActivity) {
    a.pendingInstall?.let { (_, r) ->
        AlertDialog(
            onDismissRequest = { a.pendingInstall = null },
            title = { Text(r.manifest?.takeIf { r.valid }?.let { "Instalar ${it.name} ${it.version}?" } ?: "Pacote inválido") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    r.manifest?.let { m ->
                        Text("${m.id} • ${m.mode} • ${m.author} • ${m.license}")
                        Text("Arquivos: ${r.files.size} • ${r.uncompressedBytes / 1024} KB • integridade SHA-256 verificada")
                        Text("Permissões declaradas:")
                        m.declaredPermissions.forEach { p ->
                            val how = when (p.policy) { GrantPolicy.INSTALL_TIME -> "concedida na instalação"; GrantPolicy.USER_PROMPT -> "perguntada ao abrir"; GrantPolicy.EXPLICIT_DANGEROUS -> "exige confirmação explícita" }
                            Text("• ${p.key} — $how", fontSize = 13.sp)
                        }
                    }
                    r.errors.forEach { Text("Erro: $it", color = HxColors.bad, fontSize = 13.sp) }
                    r.warnings.forEach { Text("Aviso: $it", color = HxColors.warn, fontSize = 13.sp) }
                }
            },
            confirmButton = { if (r.valid) TextButton(onClick = { a.confirmInstall() }) { Text("Instalar") } },
            dismissButton = { TextButton(onClick = { a.pendingInstall = null }) { Text("Cancelar") } },
        )
    }
    a.message?.let { m ->
        AlertDialog(onDismissRequest = { a.message = null }, text = { Text(m) }, confirmButton = { TextButton(onClick = { a.message = null }) { Text("OK") } })
    }
}
