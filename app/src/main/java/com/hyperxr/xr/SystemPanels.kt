package com.hyperxr.xr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.hyperxr.HyperXRApp
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.environments.CatalogItem
import com.hyperxr.environments.EnvironmentEntry
import com.hyperxr.environments.EnvironmentKind
import com.hyperxr.launcher.AppCategory
import com.hyperxr.launcher.AppEntry
import com.hyperxr.permissions.GrantPolicy
import com.hyperxr.permissions.HyperPermission
import com.hyperxr.rendering.IconFactory
import com.hyperxr.settings.LauncherMode
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Actions the launcher exposes to its panels. */
interface LauncherActions {
    fun launch(app: AppEntry)
    fun togglePin(app: AppEntry)
    fun isPinned(app: AppEntry): Boolean
    fun setMode(mode: LauncherMode)
    fun mode(): LauncherMode
    fun recenter()
    fun exitToSettings()
    fun selectEnvironment(entry: EnvironmentEntry)
    fun currentEnvironmentId(): String
    fun capture()
    fun allApps(): List<AppEntry>
    fun icons(): IconFactory
    fun resetLayout()
    fun windowCount(): Int
    fun windowsMinimized(): Boolean
    /** Dock Home button: hide all windows (they keep running) or bring them back. */
    fun toggleWindows()
    val trackingDescription: String
    val modeDescription: String
    val handStatus: String
}

private val background = Executors.newSingleThreadExecutor()

/** Status + clock + recents (Dock mode home). */
class HomePanel(private val a: LauncherActions) : XrPanel(1.15f, 0.42f, title = "") {
    init { closable = false; priority = 0 }
    private var lastMinute = -1
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())
    private val dateFmt = SimpleDateFormat("EEEE, d 'de' MMMM", Locale("pt", "BR"))

    override fun update(dt: Float) {
        val m = (System.currentTimeMillis() / 60000).toInt()
        if (m != lastMinute) { lastMinute = m; dirty = true }
    }

    override fun draw(c: PanelCanvas) {
        c.label(fmt.format(Date()), c.pad, 30f, 96f, XrTheme.text, bold = true)
        c.label(dateFmt.format(Date()), c.pad + 4f, 150f, 30f, XrTheme.textDim)
        var x = c.pad
        x += c.chip(a.modeDescription, x, 200f, XrTheme.accent) + 10f
        c.chip(a.trackingDescription, x, 200f, XrTheme.button)
        c.label("Recentes", 560f, 36f, 30f, XrTheme.textDim, bold = true)
        val recents = a.allApps().let { all -> HyperXRApp.instance.settings.current.launcher.recents.mapNotNull { id -> all.firstOrNull { it.id == id } } }.take(4)
        if (recents.isEmpty()) c.label("Nenhum app aberto ainda", 560f, 90f, 26f, XrTheme.textDim)
        recents.forEachIndexed { i, app ->
            c.button("recent:${app.id}", app.title, 560f + (i % 2) * 230f, 84f + (i / 2) * 100f, 218f, 86f, icon = a.icons().icon(app.iconKey, 96)) { a.launch(app) }
        }
    }
}

/** Dock controls strip: library, quick settings, mode switch, recenter. */
class DockControls(private val a: LauncherActions, private val open: (String) -> Unit) : XrPanel(1.36f, 0.13f, title = "") {
    init { closable = false; priority = 0 }
    private var lastMinute = -1L
    private var lastWindows = -1
    private var battery = ""

    override fun update(dt: Float) {
        val minute = System.currentTimeMillis() / 60_000
        val n = a.windowCount() * 2 + if (a.windowsMinimized()) 1 else 0
        if (minute != lastMinute || n != lastWindows) {
            lastMinute = minute; lastWindows = n
            battery = batteryText()
            dirty = true
        }
    }

    private fun batteryText(): String {
        val i = com.hyperxr.HyperXRApp.instance.registerReceiver(null, android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED)) ?: return ""
        val level = i.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
        val plugged = i.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0) != 0
        if (level < 0) return ""
        return "${level * 100 / scale.coerceAtLeast(1)}%" + if (plugged) " carregando" else ""
    }

    override fun draw(c: PanelCanvas) {
        val n = a.windowCount()
        val count = if (n > 0) 5 else 4
        val statusW = 230f
        val bw = (c.w - c.pad * 2 - statusW - count * 14f) / count
        val y = 16f
        var x = c.pad
        fun b(id: String, label: String, accent: Boolean = false, act: () -> Unit) { c.button(id, label, x, y, bw, 86f, accent = accent) { act() }; x += bw + 14f }
        if (n > 0) b("win", if (a.windowsMinimized()) "Janelas ($n)" else "Início", accent = a.windowsMinimized()) { a.toggleWindows() }
        b("lib", "Biblioteca") { open("library") }
        b("qs", "Ajustes rápidos") { open("settings") }
        b("mode", if (a.mode() == LauncherMode.DOCK) "Modo: Dock" else "Modo: New", accent = true) {
            a.setMode(if (a.mode() == LauncherMode.DOCK) LauncherMode.NEW else LauncherMode.DOCK)
        }
        b("rc", "Recentralizar") { a.recenter() }
        val time = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date())
        c.label(time, x + 10f, 14f, 40f, XrTheme.text, bold = true)
        if (battery.isNotEmpty()) c.label(battery, x + 10f, 64f, 26f, XrTheme.textDim)
    }
}

class LibraryPanel(private val a: LauncherActions) : XrPanel(1.3f, 0.95f, title = "Biblioteca") {
    private var category: AppCategory? = null
    private var page = 0
    override fun draw(c: PanelCanvas) {
        val cats = listOf(null to "Todos", AppCategory.HYPERXR_GAME to "Jogos", AppCategory.WEB to "Web",
            AppCategory.CARDBOARD to "Cardboard", AppCategory.OPENXR to "OpenXR", AppCategory.SYSTEM to "Sistema")
        val bw = (c.w - 2 * c.pad - 5 * 10f) / 6
        cats.forEachIndexed { i, (cat, label) ->
            c.button("cat$i", label, c.pad + i * (bw + 10f), c.y, bw, 70f, accent = category == cat) { category = cat; page = 0; dirty = true }
        }
        c.y += 90f
        val apps = a.allApps().filter { category == null || it.category == category }
        val perPage = 8
        val pages = maxOf(1, (apps.size + perPage - 1) / perPage)
        page = page.coerceIn(0, pages - 1)
        val colW = (c.w - 2 * c.pad - 20f) / 2
        apps.drop(page * perPage).take(perPage).forEachIndexed { i, app ->
            val x = c.pad + (i % 2) * (colW + 20f)
            val y = c.y + (i / 2) * 120f
            c.button("open:${app.id}", app.title, x, y, colW - 110f, 104f, icon = a.icons().icon(app.iconKey, 128)) { a.launch(app) }
            c.button("pin:${app.id}", if (a.isPinned(app)) "Soltar" else "Fixar", x + colW - 100f, y, 100f, 104f, accent = a.isPinned(app)) { a.togglePin(app); dirty = true }
        }
        val by = c.h - 110f
        c.button("prev", "‹ Anterior", c.pad, by, 220f, 80f, enabled = page > 0) { page--; dirty = true }
        c.label("Página ${page + 1} de $pages  •  ${apps.size} apps", c.w / 2 - 150f, by + 22f, 28f, XrTheme.textDim)
        c.button("next", "Próxima ›", c.w - c.pad - 220f, by, 220f, 80f, enabled = page < pages - 1) { page++; dirty = true }
    }
}

class QuickSettingsPanel(private val a: LauncherActions, private val open: (String) -> Unit) : XrPanel(1.0f, 1.1f, title = "Ajustes rápidos") {
    private var t = 0f
    override fun update(dt: Float) { t += dt; if (t > 1f) { t = 0f; dirty = true } } // live hand status
    override fun draw(c: PanelCanvas) {
        val s = HyperXRApp.instance.settings
        c.paragraph("${a.modeDescription} • ${a.trackingDescription}", 26f)
        c.paragraph(a.handStatus, 22f)
        c.toggle("mode", "Launcher no modo New", a.mode() == LauncherMode.NEW) { a.setMode(if (it) LauncherMode.NEW else LauncherMode.DOCK); dirty = true }
        val hv = s.current.handTracking.handVisual
        c.row("handvis", "Mãos: " + when (hv) { com.hyperxr.settings.HandVisual.SKELETON -> "esqueleto"; com.hyperxr.settings.HandVisual.AURA -> "aura"; com.hyperxr.settings.HandVisual.BOTH -> "esqueleto + aura"; else -> "ocultas" }) {
            val next = com.hyperxr.settings.HandVisual.values()[(hv.ordinal + 1) % com.hyperxr.settings.HandVisual.values().size]
            s.update { it.copy(handTracking = it.handTracking.copy(handVisual = next)) }; dirty = true
        }
        c.toggle("dt", "Direct Touch", s.current.handTracking.directTouch) { v -> s.update { it.copy(handTracking = it.handTracking.copy(directTouch = v)) }; dirty = true }
        c.row("env", "Ambientes…") { open("environments") }
        c.row("reset", "Reorganizar ícones (modo New)") { a.resetLayout() }
        c.row("rc", "Recentralizar visão") { a.recenter() }
        c.row("exit", "Sair para as configurações 2D", accent = true) { a.exitToSettings() }
    }
}

class EnvironmentsPanel(private val a: LauncherActions) : XrPanel(1.25f, 1.0f, title = "HyperXR Environments") {
    private val repo = HyperXRApp.instance.environments
    @Volatile private var catalog: List<CatalogItem>? = null
    @Volatile private var status = ""
    private var showCatalog = false
    override fun draw(c: PanelCanvas) {
        c.button("tabInst", "Instalados", c.pad, c.y, 260f, 70f, accent = !showCatalog) { showCatalog = false; dirty = true }
        c.button("tabCat", "Catálogo (Poly Haven, CC0)", c.pad + 280f, c.y, 460f, 70f, accent = showCatalog) {
            showCatalog = true; dirty = true
            if (catalog == null) loadCatalog()
        }
        c.y += 90f
        if (status.isNotEmpty()) c.paragraph(status, 26f, XrTheme.accent2)
        if (!showCatalog) {
            for (e in repo.installed()) {
                val kind = when (e.kind) { EnvironmentKind.SCENE_3D -> "Cena 3D"; EnvironmentKind.PANORAMA_360 -> "Panorama 360° (sem geometria)"; else -> "Procedural" }
                c.row("env:${e.id}", e.name, accent = e.id == a.currentEnvironmentId(), detail = "$kind • ${e.license} • ${e.author}") { a.selectEnvironment(e); dirty = true }
            }
        } else {
            val list = catalog
            if (list == null) { c.paragraph("Carregando catálogo…"); return }
            for (item in list.take(6)) {
                val kind = if (item.kind == EnvironmentKind.SCENE_3D) "Cena 3D" else "Panorama 360°"
                c.row("dl:${item.id}", "Baixar: ${item.name}", detail = "$kind • CC0 • ${item.author}") { download(item) }
            }
            c.paragraph("Catálogo completo, prévias e tamanhos: app 2D > Ambientes.", 24f)
        }
    }

    private fun loadCatalog() {
        status = "Buscando catálogo…"
        background.execute {
            status = try { catalog = repo.fetchCatalog(); "" } catch (t: Throwable) { "Sem conexão com a Poly Haven: ${t.message}" }
            dirty = true
        }
    }

    private fun download(item: CatalogItem) {
        status = "Preparando ${item.name}…"; dirty = true
        background.execute {
            val entry = try { repo.resolve(item) } catch (t: Throwable) { null }
            if (entry == null) { status = "Falha ao obter arquivos de ${item.name}"; dirty = true; return@execute }
            val wifiOnly = HyperXRApp.instance.settings.current.network.downloadsOnWifiOnly
            repo.download(entry, wifiOnly) { done, total -> status = "Baixando ${item.name}: ${done * 100 / maxOf(1, total)}%"; dirty = true }
                .onSuccess { status = "${it.name} instalado (${it.license}, ${it.author})" }
                .onFailure { status = "Falhou: ${it.message}" }
            dirty = true
        }
    }
}

class MessagesPanel : XrPanel(1.3f, 0.95f, title = "Mensagens do sistema") {
    private var count = -1
    override fun update(dt: Float) {
        val n = Diagnostics.recentEvents().size
        if (n != count) { count = n; dirty = true }
    }
    override fun draw(c: PanelCanvas) {
        val crashDir = File(HyperXRApp.instance.filesDir, "crash")
        crashDir.listFiles()?.forEach { f -> c.paragraph("⚠ ${f.nameWithoutExtension}: ${f.readText().take(200)}", 26f, XrTheme.danger) }
        val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
        for (e in Diagnostics.recentEvents().takeLast(14).reversed()) {
            val ts = e.substringBefore(' ').toLongOrNull()
            c.paragraph("${ts?.let { fmt.format(Date(it)) } ?: ""}  ${e.substringAfter(' ')}", 24f)
            if (c.y > c.h - 60f) break
        }
    }
}

class HelpPanel : XrPanel(1.3f, 1.0f, title = "Central de ajuda") {
    override fun draw(c: PanelCanvas) {
        c.heading("Interação")
        c.paragraph("Olhar + toque na tela (ou botão do VR Box): seleciona o que está no centro da visão.")
        c.paragraph("Mãos: toque diretamente nos botões com o indicador (Direct Touch). Pinça para selecionar à distância nos jogos.")
        c.paragraph("Joy-Con: aponte e pressione R para selecionar, L para voltar. Segure em um ícone (modo New) para movê-lo. HOME recentraliza.")
        c.heading("Modos")
        c.paragraph("Realidade mista usa a câmera quando o aparelho permite; em VR SBS um ambiente 3D substitui a câmera. A imagem da câmera é mono (não estéreo).")
        c.heading("Privacidade")
        c.paragraph("Imagens da câmera são processadas no aparelho e nunca são enviadas para a rede pelo HyperXR.")
    }
}

class TvPanel(private val a: LauncherActions) : XrPanel(1.0f, 0.7f, title = "TV") {
    override fun draw(c: PanelCanvas) {
        c.paragraph("Vídeo na web dentro do HyperXR:")
        for (id in listOf("web:youtube", "web:twitch", "web:tiktok")) {
            val app = a.allApps().firstOrNull { it.id == id } ?: continue
            c.row("tv:$id", app.title, icon = a.icons().icon(app.iconKey, 96), detail = app.target) { a.launch(app) }
        }
    }
}

class XrAppsPanel(private val a: LauncherActions) : XrPanel(1.3f, 1.0f, title = "Apps XR (Cardboard e OpenXR)") {
    private val apps by lazy { HyperXRApp.instance.xrApps.discover() }
    override fun draw(c: PanelCanvas) {
        if (apps.isEmpty()) {
            c.paragraph("Nenhum app Cardboard/OpenXR instalado foi encontrado. Apps são detectados pelas categorias de intent que declaram (CARDBOARD, DAYDREAM, IMMERSIVE_HMD).")
            return
        }
        for (x in apps.take(7)) {
            val icon = x.icon?.let { d -> Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { b -> d.setBounds(0, 0, 96, 96); d.draw(android.graphics.Canvas(b)) } }
            c.row("xr:${x.packageName}", x.entry.title, icon = icon, detail = "${x.entry.runtime} • ${x.entry.compatibility} • ${x.entry.limitations}") { a.launch(x.entry) }
        }
    }
}

class InfoPanel(title: String, private val text: String) : XrPanel(1.0f, 0.5f, title = title) {
    override fun draw(c: PanelCanvas) { c.paragraph(text) }
}

/** Asks for prompted permissions before a package starts. `unsafe` is only grantable in the 2D app. */
class PermissionPromptPanel(private val app: AppEntry, private val packageId: String, private val onDone: (Boolean) -> Unit) :
    XrPanel(1.1f, 0.85f, title = "Permissões de ${app.title}") {
    override fun draw(c: PanelCanvas) {
        val repo = HyperXRApp.instance.packages
        val pkg = repo.load(packageId) ?: run { c.paragraph("Pacote não instalado"); return }
        val pending = pkg.pendingPrompts
        if (pending.isEmpty()) { onDone(true); visible = false; return }
        c.paragraph("Este pacote pede acesso a:", 30f, XrTheme.text)
        for (p in pending) {
            if (p.policy == GrantPolicy.EXPLICIT_DANGEROUS) {
                c.paragraph("• unsafe — só pode ser concedida no app 2D, com confirmação explícita. Sem ela o pacote roda no sandbox.", 26f, XrTheme.danger)
                continue
            }
            c.paragraph("• ${p.key}: ${p.description}", 26f)
            val y = c.y
            c.button("allow:${p.key}", "Permitir", c.pad, y, 240f, 76f, accent = true) { repo.decide(packageId, p, true); dirty = true }
            c.button("deny:${p.key}", "Negar", c.pad + 260f, y, 200f, 76f) { repo.decide(packageId, p, false); dirty = true }
            c.y += 92f
        }
        if (pending.all { it == HyperPermission.UNSAFE }) c.row("go", "Abrir sem 'unsafe'", accent = true) { onDone(true); visible = false }
        c.row("cancel", "Cancelar") { onDone(false); visible = false }
    }
}

/** Spatial keyboard typing into a window (web field or Android app). */
class KeyboardPanel(
    val target: com.hyperxr.windows.KeyTarget,
    /** Starts voice dictation; the recognized text is passed to the callback (null when unavailable). */
    private val dictate: ((onText: (String?) -> Unit) -> Unit)? = null,
) : XrPanel(0.82f, 0.38f, ppm = 1500f, title = "Teclado") {
    init { priority = 2 } // the keyboard is always reachable
    private var shift = false
    private var layer = 0 // 0 letters, 1 symbols, 2 accents
    @Volatile private var listening = false
    @Volatile private var note: String? = null

    override fun draw(c: PanelCanvas) {
        val rows = when (layer) {
            1 -> listOf("1234567890", "@#$%&*-+()", "!?/:;'\",._", "")
            2 -> listOf("áàâãéêíóôõ", "úüçñ€£¿¡ºª", "\u00ab\u00bb[]{}<>=~", "")
            else -> listOf("qwertyuiop", "asdfghjkl", "zxcvbnm", "")
        }
        val keyW = (c.w - 2 * c.pad - 9 * 8f) / 10
        rows.forEachIndexed { r, row ->
            val offset = c.pad + (10 - row.length) * (keyW + 8f) / 2
            row.forEachIndexed { i, ch ->
                val label = if (shift) ch.uppercaseChar().toString() else ch.toString()
                c.button("k$r$i", label, offset + i * (keyW + 8f), c.y + r * 84f, keyW, 74f) {
                    target.typeText(label); if (shift) { shift = false; dirty = true }
                }
            }
        }
        val y = c.y + 3 * 84f
        val keys = 7
        val w7 = (c.w - 2 * c.pad - (keys - 1) * 8f) / keys
        var x = c.pad
        fun k(id: String, label: String, width: Float = w7, accent: Boolean = false, act: () -> Unit) { c.button(id, label, x, y, width, 74f, accent = accent) { act() }; x += width + 8f }
        k("shift", "Shift", accent = shift) { shift = !shift; dirty = true }
        k("sym", if (layer == 1) "abc" else "?123") { layer = if (layer == 1) 0 else 1; dirty = true }
        k("acc", if (layer == 2) "abc" else "áç") { layer = if (layer == 2) 0 else 2; dirty = true }
        k("space", "Espaço", w7 * 1.6f) { target.typeText(" ") }
        k("bksp", "Apagar", w7 * 0.9f) { target.sendKey(android.view.KeyEvent.KEYCODE_DEL) }
        if (dictate != null) k("mic", if (listening) "Ouvindo…" else "Ditado", w7 * 0.9f, accent = listening) {
            if (!listening) {
                listening = true; note = null; dirty = true
                dictate.invoke { text ->
                    listening = false
                    if (text.isNullOrBlank()) note = "Ditado indisponível ou nada reconhecido" else target.typeText(text)
                    dirty = true
                }
            }
        }
        k("enter", "Enter", c.w - c.pad - x, accent = true) { target.sendKey(android.view.KeyEvent.KEYCODE_ENTER) }
        note?.let { c.label(it, c.pad, y + 80f, 24f, XrTheme.accent2) }
    }
}

/** Installed launchable apps that can open in an XR window through Shizuku. */
class AndroidAppsPanel(
    private val activity: android.app.Activity,
    private val open: (pkg: String, component: String, title: String) -> Unit,
) : XrPanel(1.3f, 1.0f, title = "Apps Android") {
    private data class Launchable(val pkg: String, val component: String, val label: String, val icon: Bitmap?)
    private val apps: List<Launchable> by lazy {
        val pm = activity.packageManager
        val intent = android.content.Intent(android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        pm.queryIntentActivities(intent, 0).filter { it.activityInfo.packageName != activity.packageName }.map { ri ->
            val d = ri.loadIcon(pm)
            val b = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888).also { bmp -> d.setBounds(0, 0, 96, 96); d.draw(android.graphics.Canvas(bmp)) }
            Launchable(ri.activityInfo.packageName, "${ri.activityInfo.packageName}/${ri.activityInfo.name}", ri.loadLabel(pm).toString(), b)
        }.sortedBy { it.label.lowercase() }
    }
    private var page = 0
    @Volatile private var message: String? = null

    override fun draw(c: PanelCanvas) {
        when (com.hyperxr.shell.ShellBridge.state(activity)) {
            com.hyperxr.shell.ShizukuState.NOT_INSTALLED -> {
                c.paragraph("Para abrir apps Android em janelas no XR, instale o Shizuku e inicie-o (depuração sem fio ou root).", 28f, XrTheme.text)
                c.paragraph("O Shizuku permite ao HyperXR criar uma tela virtual e abrir o app nela, como o scrcpy faz.")
                return
            }
            com.hyperxr.shell.ShizukuState.NOT_RUNNING -> { c.paragraph("O Shizuku está instalado mas não está rodando. Abra o app Shizuku e inicie o serviço.", 28f, XrTheme.text); return }
            com.hyperxr.shell.ShizukuState.NO_PERMISSION -> {
                c.paragraph("Conceda ao HyperXR a permissão do Shizuku.", 28f, XrTheme.text)
                c.row("grant", "Conceder permissão do Shizuku", accent = true) {
                    activity.runOnUiThread { com.hyperxr.shell.ShellBridge.requestPermission { ok -> message = if (ok) null else "Permissão negada"; dirty = true } }
                }
                message?.let { c.paragraph(it, 26f, XrTheme.danger) }
                return
            }
            com.hyperxr.shell.ShizukuState.READY -> Unit
        }
        val perPage = 10
        val pages = maxOf(1, (apps.size + perPage - 1) / perPage)
        page = page.coerceIn(0, pages - 1)
        val colW = (c.w - 2 * c.pad - 20f) / 2
        apps.drop(page * perPage).take(perPage).forEachIndexed { i, a ->
            c.button("app:${a.component}", a.label, c.pad + (i % 2) * (colW + 20f), c.y + (i / 2) * 104f, colW, 92f, icon = a.icon) {
                open(a.pkg, a.component, a.label); visible = false; onClose?.invoke()
            }
        }
        val by = c.h - 110f
        c.button("prev", "‹ Anterior", c.pad, by, 220f, 80f, enabled = page > 0) { page--; dirty = true }
        c.label("Página ${page + 1} de $pages", c.w / 2 - 100f, by + 22f, 28f, XrTheme.textDim)
        c.button("next", "Próxima ›", c.w - c.pad - 220f, by, 220f, 80f, enabled = page < pages - 1) { page++; dirty = true }
    }
}
