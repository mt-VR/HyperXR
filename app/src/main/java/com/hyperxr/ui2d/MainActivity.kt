package com.hyperxr.ui2d

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hyperxr.HyperXRApp
import com.hyperxr.settings.HyperSettings
import com.hyperxr.xr.XrActivity
import java.util.concurrent.Executors

/**
 * First screen: a conventional 2D landscape management UI. It never renders VR/MR elements;
 * the immersive experience starts only from "Iniciar experiência XR".
 */
class MainActivity : ComponentActivity() {
    val app: HyperXRApp get() = HyperXRApp.instance
    val io = Executors.newFixedThreadPool(2)
    var settingsState by mutableStateOf(HyperSettings())
    /** Bumped when something outside settings changed (permissions, installs) to recompose. */
    var refresh by mutableIntStateOf(0)

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        io.execute { app.capabilities.probe(app.runtimeBroker); runOnUiThread { refresh++ } }
    }
    val apkImport = ApkImportState(this)
    val closeCheck = GameCloseCheck(this)
    val pickApk = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { apkImport.import(it) } }
    val pickPackage = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { onPackagePicked(it) } }
    var pendingInstall by mutableStateOf<Pair<java.io.File, com.hyperxr.sdk.InspectionResult>?>(null)
    var message by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settingsState = app.settings.current
        io.execute {
            app.packages.installBundled()
            app.capabilities.probe(app.runtimeBroker)
            runOnUiThread { refresh++ }
        }
        setContent {
            MaterialTheme(colorScheme = HxColorScheme, shapes = HxShapes) {
                DisposableEffect(Unit) {
                    val l: (HyperSettings) -> Unit = { s -> runOnUiThread { settingsState = s } }
                    app.settings.addListener(l)
                    onDispose { app.settings.removeListener(l) }
                }
                var booting by remember { mutableStateOf(!bootSplashPlayed && app.settings.current.graphics.bootAnimation) }
                androidx.compose.foundation.layout.Box {
                    // a new language redraws every text
                    androidx.compose.runtime.key(settingsState.language) {
                        if (settingsState.onboarding.wizardDone) Root(this@MainActivity) else SetupWizard(this@MainActivity)
                    }
                    if (booting) BootSplash { bootSplashPlayed = true; booting = false }
                }
            }
        }
    }

    fun requestRuntimePermissions() {
        val perms = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 31) perms += Manifest.permission.BLUETOOTH_CONNECT
        permissionLauncher.launch(perms.toTypedArray())
    }

    fun hasPermission(p: String) = checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED

    fun startXr() {
        startActivity(Intent(this, XrActivity::class.java))
    }

    private fun onPackagePicked(uri: android.net.Uri) {
        io.execute {
            val r = try { app.packages.inspectUri(uri) } catch (t: Throwable) { null }
            runOnUiThread { if (r == null) message = "Não foi possível ler o arquivo" else pendingInstall = r }
        }
    }

    fun confirmInstall() {
        val (file, result) = pendingInstall ?: return
        pendingInstall = null
        io.execute {
            val msg = try { val p = app.packages.commitInstall(file, result); "Instalado: ${p.manifest.name} ${p.manifest.version}" } catch (t: Throwable) { "Falha: ${t.message}" }
            runOnUiThread { message = msg; refresh++ }
        }
    }

    override fun onResume() { super.onResume(); refresh++; closeCheck.onResume() }

    companion object {
        /** Section name (Section.name) to open, e.g. from the XR library. */
        const val EXTRA_SECTION = "section"
    }
    override fun onDestroy() { io.shutdown(); super.onDestroy() }
}

enum class Section(val title: String) {
    HOME("Início"), IMPORTS("Importar e jogar"), LAUNCHER("Launcher"), GRAPHICS("Gráficos"), PERFORMANCE("Desempenho"),
    REALITY_LENSES("RealityLenses"), HEAD("Head tracking"), HANDS("Hand tracking"), CONTROLS("Controles"), HYPERCONTROLLER("HyperController"), COLOR_CONTROLLER("Controle por cor"), PERMISSIONS("Permissões e pacotes"),
    RUNTIME("OpenXR, Monado e Broker"), ENVIRONMENTS("Ambientes 3D"), APPS("Apps XR e Web"), PEOPLE("Pessoas"), MINECRAFT("Minecraft"), AUDIO("Áudio"),
    DEVICE("Dispositivo e diagnóstico"), ACCESSIBILITY("Acessibilidade"), NETWORK("Rede"),
}

@Composable
private fun Root(a: MainActivity) {
    var section by remember { mutableStateOf(a.intent?.getStringExtra(MainActivity.EXTRA_SECTION)?.let { n -> Section.entries.firstOrNull { it.name == n } } ?: Section.HOME) }
    Column(Modifier.fillMaxSize().background(HxColors.bg)) {
    FreeAppBanner()
    CrashBanner(a)
    Row(Modifier.fillMaxSize()) {
        Column(
            Modifier.width(250.dp).fillMaxHeight().background(Color(0xFF101020)).verticalScroll(rememberScrollState()).padding(12.dp),
        ) {
            Text("HyperMagic", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = HxColors.text, modifier = Modifier.padding(8.dp))
            Text("Configurações e gerenciamento", fontSize = 12.sp, color = HxColors.dim, modifier = Modifier.padding(start = 8.dp, bottom = 12.dp))
            Section.entries.forEach { s ->
                val sel = s == section
                Text(
                    s.title,
                    modifier = Modifier.padding(vertical = 2.dp).clip(RoundedCornerShape(12.dp))
                        .background(if (sel) HxColors.accent.copy(alpha = 0.25f) else Color.Transparent)
                        .clickableNoRipple { section = s }.padding(horizontal = 14.dp, vertical = 11.dp).width(210.dp),
                    color = if (sel) HxColors.text else HxColors.dim, fontWeight = if (sel) FontWeight.SemiBold else FontWeight.Normal,
                )
            }
        }
        Box(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(24.dp)) {
                Text(section.title, fontSize = 28.sp, fontWeight = FontWeight.Bold, color = HxColors.text, modifier = Modifier.padding(bottom = 16.dp))
                @Suppress("UNUSED_EXPRESSION") a.refresh // recompose on refresh
                when (section) {
                    Section.HOME -> HomeSection(a)
                    Section.IMPORTS -> ImportsSection(a)
                    Section.LAUNCHER -> LauncherSection(a)
                    Section.GRAPHICS -> GraphicsSection(a)
                    Section.PERFORMANCE -> PerformanceSection(a)
                    Section.REALITY_LENSES -> RealityLensesSection(a)
                    Section.HEAD -> HeadSection(a)
                    Section.HANDS -> HandsSection(a)
                    Section.CONTROLS -> ControlsSection(a)
                    Section.HYPERCONTROLLER -> HyperControllerSection(a)
                    Section.COLOR_CONTROLLER -> ColorControllerSection(a)
                    Section.PERMISSIONS -> PermissionsSection(a)
                    Section.RUNTIME -> RuntimeSection(a)
                    Section.ENVIRONMENTS -> EnvironmentsSection(a)
                    Section.APPS -> AppsSection(a)
                    Section.MINECRAFT -> MinecraftSection(a)
                    Section.PEOPLE -> PeopleSection(a)
                    Section.AUDIO -> AudioSection(a)
                    Section.DEVICE -> DeviceSection(a)
                    Section.ACCESSIBILITY -> AccessibilitySection(a)
                    Section.NETWORK -> NetworkSection(a)
                }
            }
            Dialogs(a)
        }
    }
    }
}

/** When HyperXR closed by itself: what happened, to copy or send. */
@Composable
private fun CrashBanner(a: MainActivity) {
    @Suppress("UNUSED_EXPRESSION") a.refresh
    val reports = remember(a.refresh) { com.hyperxr.diagnostics.CrashReporter.reports(a) }
    if (reports.isEmpty()) return
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(Color(0xFF3A2A10)).padding(horizontal = 16.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("O HyperMagic fechou sozinho ${reports.size} vez(es). O relatório mostra o motivo.", color = Color(0xFFFFD27A), fontSize = 14.sp, modifier = Modifier.weight(1f))
        Text("Ver relatório", color = Color.White, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickableNoRipple { open = true }.padding(8.dp))
    }
    if (open) {
        val text = reports.joinToString("\n\n==========\n\n") { runCatching { it.readText() }.getOrDefault("") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { open = false },
            title = { Text("Relatórios de erro") },
            text = { Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) { Text(text, fontSize = 11.sp, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace) } },
            confirmButton = {
                Row {
                    androidx.compose.material3.TextButton(onClick = {
                        val cm = a.getSystemService(android.content.ClipboardManager::class.java)
                        cm?.setPrimaryClip(android.content.ClipData.newPlainText("HyperMagic crash", text))
                    }) { Text("Copiar") }
                    androidx.compose.material3.TextButton(onClick = {
                        val send = android.content.Intent(android.content.Intent.ACTION_SEND).setType("text/plain").putExtra(android.content.Intent.EXTRA_TEXT, text)
                        a.startActivity(android.content.Intent.createChooser(send, "Enviar relatório"))
                    }) { Text("Compartilhar") }
                }
            },
            dismissButton = {
                androidx.compose.material3.TextButton(onClick = { com.hyperxr.diagnostics.CrashReporter.clear(a); open = false; a.refresh++ }) { Text("Apagar") }
            },
        )
    }
}

/** The warning against people selling HyperXR: at the top of every settings page. */
@Composable
private fun FreeAppBanner() {
    Text(
        FREE_APP_NOTICE,
        modifier = Modifier.fillMaxWidth().background(Color(0xFFC62828)).padding(horizontal = 16.dp, vertical = 12.dp),
        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}

const val FREE_APP_NOTICE = "SE VOCÊ PAGOU POR ESSE APP, VOCÊ FOI ROUBADO. ELE É GRATUITO."

