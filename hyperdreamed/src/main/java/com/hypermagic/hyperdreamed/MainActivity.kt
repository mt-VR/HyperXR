package com.hypermagic.hyperdreamed

import android.Manifest
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperxr.daydream.DaydreamBle
import com.hyperxr.daydream.VrServicesPatcher
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private val io = Executors.newSingleThreadExecutor()
    val items = mutableStateListOf<Imported>()
    var busy by mutableStateOf("")
    var refresh by mutableIntStateOf(0)
    lateinit var emulator: ControllerEmulator

    private val pick = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@registerForActivityResult
        busy = "Lendo ${uris.size} arquivo(s)…"
        io.execute {
            for (u in uris) {
                val r = runCatching { Patching.import(this, u) }
                runOnUiThread {
                    r.onSuccess { new -> items.removeAll { it.report.packageName == new.report.packageName }; items += new; items.sortBy { VrServicesPatcher.installOrder(it.report.role) } }
                        .onFailure { busy = "Não deu para ler: ${it.message}" }
                }
            }
            runOnUiThread { if (busy.startsWith("Lendo")) busy = "" }
        }
    }

    private val btPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh++ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        emulator = ControllerEmulator(this).also { it.onChange = { runOnUiThread { refresh++ } } }
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Color(0xFF7CC4FF), secondary = Color(0xFFB59CFF), background = Color(0xFF0E1320), surface = Color(0xFF182033))) {
                Box(Modifier.fillMaxSize().background(Color(0xFF0E1320))) { Screen(this@MainActivity) }
            }
        }
    }

    override fun onResume() { super.onResume(); refresh++ }
    override fun onDestroy() { emulator.stop(); ShizukuLog.unbind(); io.shutdown(); super.onDestroy() }

    fun pickFiles() = pick.launch(arrayOf("*/*"))

    fun patchAndInstall(item: Imported) {
        busy = "Corrigindo ${item.name}…"
        io.execute {
            try {
                Patching.patchAndSign(this, item)
                runOnUiThread { busy = "Instalando ${item.report.packageName}…" }
                Patching.installPreferShizuku(this, item.patched!!) { ok, msg -> runOnUiThread { item.status = msg; busy = ""; refresh++ } }
            } catch (t: Throwable) {
                runOnUiThread { item.status = "Falhou: ${t.message}"; busy = ""; refresh++ }
            }
        }
    }

    fun uninstallOriginal(pkg: String) {
        Patching.uninstallPreferShizuku(this, pkg) { _, msg -> runOnUiThread { busy = msg; refresh++ } }
    }

    // ------------------------------------------------------------ Shizuku log
    var log by mutableStateOf("")

    fun shizukuState() = ShizukuLog.state(this)

    fun askShizuku() { ShizukuLog.requestPermission { runOnUiThread { refresh++ } } }

    /** Clears the log, opens one of the VR apps, waits and captures the crash + filtered lines. */
    fun captureLog(pkgToOpen: String?) {
        if (shizukuState() != ShizukuState.READY) { log = "Shizuku não está pronto: instale e permita o HyperDreamed nele, depois tente de novo."; return }
        busy = "Capturando log…"; log = ""
        ShizukuLog.withService(this) { svc ->
            if (svc == null) { runOnUiThread { busy = ""; log = "Não deu para falar com o Shizuku." }; return@withService }
            io.execute {
                runCatching {
                    svc.clearLog()
                    if (pkgToOpen != null) { svc.launch(pkgToOpen); Thread.sleep(6500) } else Thread.sleep(500)
                    val vrPkgs = "com.google.vr.vrcore,com.google.android.vr.home,com.google.android.vr.inputmethod"
                    svc.dump(vrPkgs, 12000)
                }.onSuccess { out -> runOnUiThread { log = out.ifBlank { "Nada no log. Abra o Daydream até ele fechar e capture de novo." }; busy = "" } }
                    .onFailure { runOnUiThread { log = "Erro: ${it.message}"; busy = "" } }
            }
        }
    }

    fun copyLog() {
        val cm = getSystemService(android.content.ClipboardManager::class.java)
        cm.setPrimaryClip(android.content.ClipData.newPlainText("HyperDreamed log", log))
    }

    fun shareLog() {
        startActivity(android.content.Intent.createChooser(
            android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, log), "Compartilhar log"))
    }

    fun hasBtPermissions(): Boolean = Build.VERSION.SDK_INT < 31 ||
        listOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT).all { checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

    fun askBt() { if (Build.VERSION.SDK_INT >= 31) btPermissions.launch(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT)) }

    fun keepScreenOn(on: Boolean) {
        if (on) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /** While emulating, the phone's volume keys are the controller's. */
    @android.annotation.SuppressLint("RestrictedApi")  // lint-only: the framework override every Activity has
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (emulator.running) {
            val bit = when (event.keyCode) { KeyEvent.KEYCODE_VOLUME_UP -> DaydreamBle.VOL_UP; KeyEvent.KEYCODE_VOLUME_DOWN -> DaydreamBle.VOL_DOWN; else -> 0 }
            if (bit != 0) { emulator.button(bit, event.action == KeyEvent.ACTION_DOWN); return true }
        }
        return super.dispatchKeyEvent(event)
    }
}

private val Dim = Color(0xFFA9B4C8)

@Composable
private fun Screen(a: MainActivity) {
    var tab by remember { mutableIntStateOf(0) }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 28.dp, bottom = 8.dp)) {
            Text("HyperDreamed", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("Daydream em celulares que nunca foram Daydream, do Android 11 ao 16", fontSize = 14.sp, color = Dim)
        }
        TabRow(selectedTabIndex = tab, containerColor = Color.Transparent) {
            listOf("Patcher", "Controle", "Log", "Celular").forEachIndexed { i, t -> Tab(selected = tab == i, onClick = { tab = i }, text = { Text(t) }) }
        }
        when (tab) {
            0 -> PatcherTab(a)
            1 -> ControllerTab(a)
            2 -> LogTab(a)
            else -> PhoneTab(a)
        }
    }
}

@Composable
private fun Section(title: String, body: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(bottom = 12.dp), colors = CardDefaults.cardColors(containerColor = Color(0xFF182033)), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            body()
        }
    }
}

@Composable
private fun Line(text: String, color: Color = Dim) = Text(text, fontSize = 14.sp, color = color)

@Composable
private fun PatcherTab(a: MainActivity) {
    @Suppress("UNUSED_VARIABLE") val r = a.refresh
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Section("Como funciona") {
            Line("1. Traga os SEUS arquivos (APK, APKM, XAPK ou APKS) do Daydream e dos Serviços do Google para RV. O HyperDreamed não baixa nem distribui apps do Google.")
            Line("2. Se a versão original estiver instalada, desinstale antes: o app corrigido tem outra assinatura.")
            Line("3. Corrija e instale os Serviços para RV primeiro, depois o Daydream. Corrija também os jogos Daydream que você tiver, para eles aceitarem os Serviços corrigidos.")
            Line("4. Sem controle Daydream? Use outro celular com o HyperDreamed na aba Controle.")
        }
        Button(onClick = { a.pickFiles() }, modifier = Modifier.fillMaxWidth()) { Text("Escolher arquivos") }
        if (a.busy.isNotEmpty()) Line(a.busy, MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        for (item in a.items) {
            val rep = item.report
            val installed = Patching.signer(a, rep.packageName)
            Section(rep.role.label) {
                Line("${item.name} · ${rep.packageName} ${rep.versionName}")
                when (installed) {
                    Patching.Signer.OTHER -> {
                        Line("A versão original está instalada: desinstale antes de instalar a corrigida.", Color(0xFFFFC46B))
                        OutlinedButton(onClick = { a.uninstallOriginal(rep.packageName) }) { Text("Desinstalar a original") }
                    }
                    Patching.Signer.THIS_PHONE -> Line("A versão corrigida já está instalada.", Color(0xFF7BE0A0))
                    Patching.Signer.NOT_INSTALLED -> {}
                }
                if (rep.changes.isEmpty()) Line("Nada a corrigir encontrado.")
                rep.changes.forEach { Line("• $it", Color(0xFFDDE6F5)) }
                rep.warnings.forEach { Line("! $it", Color(0xFFFFC46B)) }
                rep.blockers.forEach { Line("× $it", Color(0xFFFF7B7B)) }
                if (item.status.isNotEmpty()) Line(item.status, if (item.status.startsWith("Instalado")) Color(0xFF7BE0A0) else Color(0xFFFF9B7B))
                Button(onClick = { a.patchAndInstall(item) }, enabled = a.busy.isEmpty() && rep.blockers.isEmpty() && installed != Patching.Signer.OTHER) {
                    Text(if (installed == Patching.Signer.THIS_PHONE) "Corrigir e reinstalar" else "Corrigir e instalar")
                }
            }
        }
    }
}

@Composable
private fun ControllerTab(a: MainActivity) {
    @Suppress("UNUSED_VARIABLE") val r = a.refresh
    val e = a.emulator
    var rename by remember { mutableStateOf(true) }
    var tapClicks by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        if (!e.running) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Section("Este celular vira um controle Daydream") {
                    Line("Use no celular que NÃO está no óculos. Segure-o como o controle: em pé, tela para cima, a parte de cima apontando para a frente.")
                    Line("No celular do óculos, abra o Daydream e pareie um controle: ele aparece como \"Daydream controller\".")
                    Line("Tela: touchpad em cima, botões embaixo. Volume + e - do celular são os do controle. Segure Home para recentralizar.")
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Line("Usar o nome \"Daydream controller\" no Bluetooth", Color.White); Line("Volta ao nome original ao parar") }
                        Switch(checked = rename, onCheckedChange = { rename = it })
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Line("Tocar no touchpad também clica", Color.White) }
                        Switch(checked = tapClicks, onCheckedChange = { tapClicks = it })
                    }
                    e.unsupported()?.let { Line(it, Color(0xFFFF9B7B)) }
                    if (!a.hasBtPermissions()) Button(onClick = { a.askBt() }) { Text("Permitir Bluetooth") }
                    else Button(onClick = { e.start(rename)?.let { msg -> a.busy = msg } ?: a.keepScreenOn(true); a.refresh++ }) { Text("Começar a emular") }
                    if (a.busy.isNotEmpty()) Line(a.busy, Color(0xFFFF9B7B))
                }
            }
        } else {
            Line(e.status, MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            // touchpad
            Box(Modifier.fillMaxWidth().aspectRatio(1f).clip(CircleShape).background(Color(0xFF243049)).border(2.dp, Color(0xFF3B4A6B), CircleShape)
                .pointerInput(tapClicks) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val w = size.width.toFloat(); val h = size.height.toFloat()
                        e.touch(down.position.x / w, down.position.y / h)
                        if (tapClicks) e.button(DaydreamBle.CLICK, true)
                        while (true) {
                            val ev = awaitPointerEvent()
                            val p = ev.changes.firstOrNull() ?: break
                            if (!p.pressed) break
                            e.touch(p.position.x / w, p.position.y / h)
                        }
                        e.release()
                        if (tapClicks) e.button(DaydreamBle.CLICK, false)
                    }
                }, contentAlignment = Alignment.Center) { Text("Touchpad", color = Dim) }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                HoldButton("Clique", Modifier.weight(1f)) { e.button(DaydreamBle.CLICK, it) }
                HoldButton("App", Modifier.weight(1f)) { e.button(DaydreamBle.APP, it) }
                HoldButton("Home", Modifier.weight(1f)) { e.button(DaydreamBle.HOME, it) }
            }
            Spacer(Modifier.height(14.dp))
            OutlinedButton(onClick = { e.stop(); a.keepScreenOn(false); a.refresh++ }, modifier = Modifier.fillMaxWidth()) { Text("Parar") }
        }
    }
}

/** A button that reports press and release (the controller's buttons are held, not tapped). */
@Composable
private fun HoldButton(label: String, modifier: Modifier, onState: (Boolean) -> Unit) {
    var pressed by remember { mutableStateOf(false) }
    Box(modifier.height(84.dp).clip(RoundedCornerShape(22.dp)).background(if (pressed) Color(0xFF7CC4FF) else Color(0xFF2B3856))
        .pointerInput(Unit) {
            awaitEachGesture {
                awaitFirstDown(); pressed = true; onState(true)
                while (true) { val ev = awaitPointerEvent(); if (ev.changes.none { it.pressed }) break }
                pressed = false; onState(false)
            }
        }, contentAlignment = Alignment.Center) {
        Text(label, color = if (pressed) Color(0xFF0E1320) else Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun LogTab(a: MainActivity) {
    @Suppress("UNUSED_VARIABLE") val r = a.refresh
    val st = a.shizukuState()
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Section("Log automático (Shizuku)") {
            Line("O HyperDreamed não consegue ler o erro de outro app sozinho. Com o Shizuku (que roda como shell) ele lê o log do sistema, captura a queda do Daydream e você me manda o texto. O Shizuku também passa a instalar e desinstalar sem telas de confirmação.")
            when (st) {
                ShizukuState.NOT_INSTALLED -> Line("Shizuku não instalado. Instale o app Shizuku (Play Store) e inicie-o (por ADB sem fio ou root).", Color(0xFFFFC46B))
                ShizukuState.NOT_RUNNING -> Line("Shizuku instalado mas parado. Abra o Shizuku e inicie o serviço.", Color(0xFFFFC46B))
                ShizukuState.NO_PERMISSION -> Button(onClick = { a.askShizuku() }) { Text("Permitir o HyperDreamed no Shizuku") }
                ShizukuState.READY -> Line("Shizuku pronto.", Color(0xFF7BE0A0))
            }
        }
        if (st == ShizukuState.READY) {
            Section("Capturar a queda") {
                Line("Toque no botão: ele limpa o log, abre o Daydream, espera alguns segundos (encaixe no óculos ou deixe abrir) e traz o que apareceu, inclusive o erro fatal.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { a.captureLog("com.google.android.vr.home") }, enabled = a.busy.isEmpty()) { Text("Abrir Daydream e capturar") }
                    OutlinedButton(onClick = { a.captureLog(VrServicesPatcher.VR_SERVICES) }, enabled = a.busy.isEmpty()) { Text("Serviço para RV") }
                }
                OutlinedButton(onClick = { a.captureLog(null) }, enabled = a.busy.isEmpty()) { Text("Só capturar o log atual (sem abrir nada)") }
                if (a.busy.isNotEmpty()) Line(a.busy, MaterialTheme.colorScheme.primary)
            }
        }
        if (a.log.isNotEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { a.copyLog() }, modifier = Modifier.weight(1f)) { Text("Copiar") }
                Button(onClick = { a.shareLog() }, modifier = Modifier.weight(1f)) { Text("Compartilhar") }
            }
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(14.dp)).background(Color(0xFF0A0E18)).padding(10.dp).verticalScroll(rememberScrollState())) {
                Text(a.log, fontSize = 11.sp, color = Color(0xFFCBD5E8), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun PhoneTab(a: MainActivity) {
    @Suppress("UNUSED_VARIABLE") val r = a.refresh
    val pm = a.packageManager
    val sm = a.getSystemService(SensorManager::class.java)
    val sdk = Build.VERSION.SDK_INT
    val androidName = mapOf(30 to "11", 31 to "12", 32 to "12L", 33 to "13", 34 to "14", 35 to "15", 36 to "16")[sdk] ?: "API $sdk"
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Section("Android") {
            Line("Android $androidName (${Build.MANUFACTURER} ${Build.MODEL})", Color.White)
            Line(if (sdk in 30..36) "Versão coberta pelo HyperDreamed." else if (sdk < 30) "Anterior ao Android 11: o Daydream original costuma funcionar sem correção onde era suportado." else "Versão mais nova que o Android 16: pode funcionar, não foi considerada.")
            val vrMode = VrServicesPatcher.VR_FEATURES.filter { pm.hasSystemFeature(it) }
            Line(if (vrMode.isEmpty()) "Sem modo VR do Android (normal fora dos celulares Daydream): o patch faz o Daydream seguir sem ele." else "Modo VR do Android presente: ${vrMode.joinToString()}")
            Line(if (sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE) != null) "Giroscópio: sim" else "Giroscópio: não — o rastreamento da cabeça no Daydream fica ruim ou não funciona", Color(0xFFDDE6F5))
            Line(if (Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() && Build.SUPPORTED_32_BIT_ABIS.isEmpty()) "Só executa 64 bits: apps Daydream só 32 bits não rodam aqui" else "Executa apps 32 e 64 bits", Color(0xFFDDE6F5))
        }
        Section("Apps do Google para RV") {
            for ((pkg, label) in listOf(VrServicesPatcher.VR_SERVICES to "Serviços do Google para RV", VrServicesPatcher.DAYDREAM_HOME to "Daydream", VrServicesPatcher.VR_KEYBOARD to "Teclado de RV")) {
                val v = Patching.installedVersion(a, pkg)
                val who = when (Patching.signer(a, pkg)) {
                    Patching.Signer.NOT_INSTALLED -> "não instalado"
                    Patching.Signer.THIS_PHONE -> "corrigido pelo HyperDreamed ($v)"
                    Patching.Signer.OTHER -> if (v?.endsWith("-hyperxr") == true) "HyperMagic Daydream ($v) — substitui os Serviços para RV" else "original ($v)"
                }
                Line("$label: $who", Color(0xFFDDE6F5))
            }
        }
        Section("Controle por outro celular") {
            val e = a.emulator
            Line(e.unsupported() ?: "Este celular pode virar um controle Daydream (Bluetooth LE periférico).", Color(0xFFDDE6F5))
        }
    }
}
