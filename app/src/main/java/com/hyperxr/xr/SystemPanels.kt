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
    fun windowCount(): Int
    fun windowsMinimized(): Boolean
    /** Dock Home button: hide all windows (they keep running) or bring them back. */
    fun toggleWindows()
    val trackingDescription: String
    val modeDescription: String
    val handStatus: String
    /** HyperConnect (controller phone) state for the settings. */
    val hyperConnectStatus: String
    /** Background tabs of the library: id ("camera", "dim" or an environment id) to label. */
    fun backgroundOptions(): List<Pair<String, String>>
    fun backgroundChoice(): String
    fun setBackgroundChoice(id: String)
    /** VR mode: the chosen environment around you instead of the camera (the dock's VR button). */
    fun vrOn(): Boolean
    fun setVr(on: Boolean)
    /** False without a usable camera: VR is then always on. */
    fun vrSwitchable(): Boolean
    /** Opens a system panel by its catalog target ("files", "settings", "environments"...). */
    fun openTarget(target: String)
    fun appIcon(app: AppEntry, size: Int): Bitmap
    fun openKeyboard(target: com.hyperxr.windows.KeyTarget)
    fun closeKeyboard()
    fun pinnedApps(): List<AppEntry>
    fun libraryOpen(): Boolean
    fun toggleLibrary()
    fun isRunning(app: AppEntry): Boolean
    /** Taskbar "Voz": listen for one spoken command. */
    fun startVoice()
    /** Last voice state for the taskbar ("Ouvindo…", "Entendi: …"), empty when idle. */
    fun voiceStatus(): String
}

private val background = Executors.newSingleThreadExecutor()

class QuickSettingsPanel(private val a: LauncherActions, private val open: (String) -> Unit) : XrPanel(1.0f, 1.1f, title = "Ajustes rápidos") {
    private var t = 0f
    override fun update(dt: Float) { t += dt; if (t > 1f) { t = 0f; dirty = true } } // live hand status
    override fun draw(c: PanelCanvas) {
        val s = HyperXRApp.instance.settings
        c.paragraph("${a.modeDescription} • ${a.trackingDescription}", 26f)
        c.paragraph(a.handStatus, 22f)
        c.toggle("mode", "Launcher no modo New", a.mode() == LauncherMode.NEW) { a.setMode(if (it) LauncherMode.NEW else LauncherMode.DOCK); dirty = true }
        val hv = s.current.handTracking.handVisual
        c.row("handvis", "Mãos: " + if (hv == com.hyperxr.settings.HandVisual.NONE) "ocultas" else "contorno") {
            val next = if (hv == com.hyperxr.settings.HandVisual.NONE) com.hyperxr.settings.HandVisual.AURA else com.hyperxr.settings.HandVisual.NONE
            s.update { it.copy(handTracking = it.handTracking.copy(handVisual = next)) }; dirty = true
        }
        c.toggle("dt", "Direct Touch", s.current.handTracking.directTouch) { v -> s.update { it.copy(handTracking = it.handTracking.copy(directTouch = v)) }; dirty = true }
        c.toggle("pp", "Clicar apontando e empurrando", s.current.handTracking.clickGesture == com.hyperxr.settings.ClickGesture.POINT_PUSH) { v ->
            s.update { it.copy(handTracking = it.handTracking.copy(clickGesture = if (v) com.hyperxr.settings.ClickGesture.POINT_PUSH else com.hyperxr.settings.ClickGesture.PINCH)) }; dirty = true
        }
        c.row("env", "Ambientes…") { open("environments") }
        c.row("rc", "Recentralizar visão") { a.recenter() }
        c.row("exit", "Sair para as configurações 2D", accent = true) { a.exitToSettings() }
    }
}

class EnvironmentsPanel(private val a: LauncherActions) : XrPanel(1.6f, 1.15f, title = "Ambientes") {
    private val repo = HyperXRApp.instance.environments
    @Volatile private var catalog: List<CatalogItem>? = null
    @Volatile private var status = ""
    private var showCatalog = false
    private var page = 0
    /** Previews, loaded in the background (shipped thumbnails, or made once from the panorama). */
    private val previews = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()
    private val loading = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())
    private val imgPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG or android.graphics.Paint.FILTER_BITMAP_FLAG)
    private val boxPaint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)

    private fun preview(key: String, load: () -> Bitmap?): Bitmap? {
        previews[key]?.let { return it }
        if (loading.add(key)) background.execute { load()?.let { previews[key] = it; dirty = true } }
        return null
    }

    override fun draw(c: PanelCanvas) {
        c.button("tabInst", "Instalados", c.pad, c.y, 260f, 70f, accent = !showCatalog) { showCatalog = false; page = 0; dirty = true }
        c.button("tabCat", "Catálogo (Poly Haven, CC0)", c.pad + 280f, c.y, 460f, 70f, accent = showCatalog) {
            showCatalog = true; page = 0; dirty = true
            if (catalog == null) loadCatalog()
        }
        c.y += 90f
        if (status.isNotEmpty()) c.paragraph(status, 26f, XrTheme.accent2)
        // grid of pictures: what each environment looks like
        val cols = 3; val rows = 2; val per = cols * rows
        val gap = 24f
        val tw = (c.w - 2 * c.pad - (cols - 1) * gap) / cols
        val ih = tw / 2f
        val th = ih + 96f
        val top = c.y
        if (!showCatalog) {
            val list = repo.installed()
            val pages = maxOf(1, (list.size + per - 1) / per); page = page.coerceIn(0, pages - 1)
            list.drop(page * per).take(per).forEachIndexed { i, e ->
                val x = c.pad + (i % cols) * (tw + gap); val y = top + (i / cols) * (th + gap)
                val kind = when (e.kind) { EnvironmentKind.SCENE_3D -> "Cena 3D"; EnvironmentKind.PANORAMA_360 -> "360°"; else -> "Procedural" }
                tile(c, "env:${e.id}", x, y, tw, ih, preview(e.id) { repo.preview(e) }, e.name, "$kind • ${e.license} • ${e.author}", e.id == a.currentEnvironmentId()) {
                    a.selectEnvironment(e); dirty = true
                }
            }
            pager(c, pages)
        } else {
            val list = catalog
            if (list == null) { c.paragraph("Carregando catálogo…"); return }
            val pages = maxOf(1, (list.size + per - 1) / per); page = page.coerceIn(0, pages - 1)
            list.drop(page * per).take(per).forEachIndexed { i, item ->
                val x = c.pad + (i % cols) * (tw + gap); val y = top + (i / cols) * (th + gap)
                val kind = if (item.kind == EnvironmentKind.SCENE_3D) "Cena 3D" else "360°"
                tile(c, "dl:${item.id}", x, y, tw, ih, preview("cat:${item.id}") { repo.preview(item) }, "Baixar: ${item.name}", "$kind • CC0 • ${item.author}", false) { download(item) }
            }
            pager(c, pages)
        }
    }

    private fun tile(c: PanelCanvas, id: String, x: Float, y: Float, w: Float, ih: Float, img: Bitmap?, title: String, detail: String, current: Boolean, action: () -> Unit) {
        val r = android.graphics.RectF(x, y, x + w, y + ih)
        boxPaint.color = XrTheme.button
        c.canvas.drawRoundRect(r, 22f, 22f, boxPaint)
        if (img != null) {
            c.canvas.save()
            val path = android.graphics.Path().apply { addRoundRect(r, 22f, 22f, android.graphics.Path.Direction.CW) }
            c.canvas.clipPath(path); c.canvas.drawBitmap(img, null, r, imgPaint); c.canvas.restore()
        } else c.label("Prévia…", x + 24f, y + ih / 2 - 16f, 24f, XrTheme.textDim)
        if (current || isHovered(id)) {
            boxPaint.style = android.graphics.Paint.Style.STROKE; boxPaint.strokeWidth = if (current) 8f else 4f
            boxPaint.color = if (current) XrTheme.accent else android.graphics.Color.WHITE
            c.canvas.drawRoundRect(r, 22f, 22f, boxPaint); boxPaint.style = android.graphics.Paint.Style.FILL
        }
        c.label(title, x + 4f, y + ih + 12f, 28f, XrTheme.text, bold = true, maxWidth = w - 8f, maxLines = 1)
        c.label(detail, x + 4f, y + ih + 52f, 22f, XrTheme.textDim, maxWidth = w - 8f, maxLines = 1)
        addRegion(Region(android.graphics.RectF(x, y, x + w, y + ih + 90f), id, action))
    }

    private fun pager(c: PanelCanvas, pages: Int) {
        val by = c.h - 96f
        c.button("prev", "Anterior", c.pad, by, 220f, 76f, enabled = page > 0) { page--; dirty = true }
        c.label("${page + 1} / $pages", c.w / 2 - 40f, by + 20f, 28f, XrTheme.textDim)
        c.button("next", "Próxima", c.w - c.pad - 220f, by, 220f, 76f, enabled = page < pages - 1) { page++; dirty = true }
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
        c.paragraph("O HyperXR sempre começa em realidade mista: a câmera mostra o seu ambiente. O botão Modo VR na dock troca a câmera por um ambiente virtual, escolhido no app Ambientes. A imagem da câmera é mono (não estéreo).")
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

class XrAppsPanel(private val a: LauncherActions, private val host: com.hyperxr.files.MediaHost) : XrPanel(1.3f, 1.0f, title = "Apps XR (OpenXR, Quest, Daydream, Cardboard)") {
    private val apps by lazy { HyperXRApp.instance.xrApps.discover() }
    override fun draw(c: PanelCanvas) {
        // importing comes first: patching is how OpenXR / Quest / Daydream APKs get here
        c.row("import", "Importar APK para corrigir (OpenXR, Quest, Daydream)", accent = true,
            detail = "Escolha um APK do celular: o HyperXR diz se roda e oferece Corrigir e instalar") {
            host.openPanel(com.hyperxr.files.ApkPickerPanel(host), 1.25f)
        }
        if (apps.isEmpty()) {
            c.paragraph("Nenhum app Cardboard/OpenXR instalado ainda. Apps são detectados pelas categorias de intent que declaram (CARDBOARD, DAYDREAM, IMMERSIVE_HMD).")
            return
        }
        for (x in apps.take(6)) {
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
    /** Lays the keyboard on the real table instead (null: not offered). */
    private val onTable: (() -> Unit)? = null,
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
        val keys = if (onTable != null) 8 else 7
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
        if (onTable != null) k("table", "Mesa", w7 * 0.9f) { onTable.invoke() }
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
