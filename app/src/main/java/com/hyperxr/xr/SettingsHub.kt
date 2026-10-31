package com.hyperxr.xr

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.view.KeyEvent
import com.hyperxr.HyperXRApp
import com.hyperxr.compat.CompatLayer
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.settings.ClickGesture
import com.hyperxr.settings.HandVisual
import com.hyperxr.settings.HyperSettings
import com.hyperxr.settings.LauncherMode
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import com.hyperxr.windows.KeyTarget

/**
 * Settings laid out like a headset's settings app: a search field on top, a grid of category
 * tiles (icon, name and what is inside), each opening its page of switches and actions, and a
 * bottom strip with close, minimise and the app's name. Every switch is a real HyperXR setting.
 */

/** Last result of installing / opening HyperXR Daydream, shown in its status row. */
@Volatile private var daydreamMessage = ""
@Volatile private var hyperConnectMessage = ""

class SettingsHubPanel(private val a: LauncherActions, private val open: (String) -> Unit) :
    XrPanel(1.6f, 1.0f, title = ""), KeyTarget {
    init { closable = false }

    private sealed class Item(val label: String) {
        class Toggle(label: String, val detail: String, val get: (HyperSettings) -> Boolean, val set: (HyperSettings, Boolean) -> HyperSettings) : Item(label)
        class Action(label: String, val detail: String, val run: () -> Unit) : Item(label)
        class Info(label: String, val value: () -> String) : Item(label)
    }
    private class Category(val id: String, val icon: String, val title: String, val subtitle: String, val items: List<Item>)

    private val settings get() = HyperXRApp.instance.settings
    private val ctx = HyperXRApp.instance

    private val categories: List<Category> = listOf(
        Category("system", "tune", "Sistema", "Modo, recentralizar, tela 2D", listOf(
            Item.Info("Modo") { "${a.modeDescription} • ${a.trackingDescription}" },
            Item.Action("Idioma", "Toque para trocar: Português, English, Español, Русский") {
                val langs = com.hyperxr.i18n.I18n.LANGUAGES.map { it.first }
                settings.update { st -> st.copy(language = langs[(langs.indexOf(com.hyperxr.i18n.Translations.resolve(st.language)) + 1) % langs.size]) }
            },
            Item.Info("Idioma atual") { com.hyperxr.i18n.I18n.LANGUAGES.first { it.first == com.hyperxr.i18n.I18n.language }.second },
            Item.Toggle("HyperBot", "O robozinho na sua mesa", { it.launcher.hyperBot }) { s, v -> s.copy(launcher = s.launcher.copy(hyperBot = v)) },
            Item.Toggle("Interface Meta", "Desligado: interface Hyper (a do HyperMagic)", { it.launcher.mode == LauncherMode.META }) { s, v ->
                a.setMode(if (v) LauncherMode.META else LauncherMode.HYPER); s.copy(launcher = s.launcher.copy(mode = if (v) LauncherMode.META else LauncherMode.HYPER)) },
            Item.Action("Recentralizar visão", "Traz tudo para a frente de você") { a.recenter() },
            Item.Toggle("Animação de inicialização", "O logo e a viagem pelas estrelas ao abrir", { it.graphics.bootAnimation }) { s, v -> s.copy(graphics = s.graphics.copy(bootAnimation = v)) },
            Item.Toggle("Timewarp", "Reajusta a imagem com o giroscópio antes de mostrar", { it.graphics.timewarp }) { s, v -> s.copy(graphics = s.graphics.copy(timewarp = v)) },
            Item.Toggle("Correção da lente", "Distorção para as lentes do VR Box", { it.graphics.lensDistortion }) { s, v -> s.copy(graphics = s.graphics.copy(lensDistortion = v)) },
            Item.Action("Configurações 2D completas", "Sai do XR para a tela do celular") { a.exitToSettings() },
        )),
        Category("wifi", "wifi", "Wi-Fi", "Rede do celular", listOf(
            Item.Action("Abrir o Wi-Fi do Android", "Escolher a rede") {
                runCatching { ctx.startActivity(android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)) }
            },
        )),
        Category("guardian", "fence", "Limite", "Aviso de limite, passthrough", listOf(
            Item.Toggle("Aviso de limite", "Mostra a câmera perto de obstáculos (ARCore Depth)", { it.graphics.vrBoundary }) { s, v -> s.copy(graphics = s.graphics.copy(vrBoundary = v)) },
            Item.Toggle("Câmera preenchendo a visão", "Passthrough sem bordas", { it.graphics.passthroughFill }) { s, v -> s.copy(graphics = s.graphics.copy(passthroughFill = v)) },
            Item.Toggle("3D na realidade mista", "Distância entre os olhos sobre a câmera", { it.graphics.stereoInMixedReality }) { s, v -> s.copy(graphics = s.graphics.copy(stereoInMixedReality = v)) },
        )),
        Category("personalization", "edit", "Personalização", "Ambiente virtual", listOf(
            Item.Action("Ambientes", "Escolher o ambiente ao seu redor") { open("environments") },
            Item.Action("Capturar sua sala em 360°", "Gire no lugar e sua sala vira um ambiente") { open("roomcapture") },
            Item.Action("Desenho no ar", "Faça a pinça e desenhe linhas de luz no espaço") { open("airdraw") },
            Item.Toggle("Ambiente pela hora do dia", "Troca sozinho de manhã, à tarde e à noite", { it.launcher.autoEnvironmentByTime }) { s, v -> s.copy(launcher = s.launcher.copy(autoEnvironmentByTime = v)) },
            Item.Toggle("Reabrir janelas", "As janelas da última vez voltam ao abrir", { it.launcher.restoreWindows }) { s, v -> s.copy(launcher = s.launcher.copy(restoreWindows = v)) },
        )),
        Category("storage", "storage", "Armazenamento", storageText(), listOf(
            Item.Info("Livre") { storageText() },
            Item.Action("Arquivos", "Abrir o gerenciador de arquivos") { open("files") },
        )),
        Category("apps", "apps", "Apps", "Apps Android, Corrigir APK", listOf(
            Item.Action("Apps Android", "Abrir apps instalados em janelas") { open("androidapps") },
            Item.Action("Corrigir APK", "Fazer jogos de Quest e Daydream rodarem") { open("importapk") },
            Item.Action("Apps XR", "Apps OpenXR instalados") { open("xrapps") },
        )),
        Category("notifications", "notifications", "Notificações", "Mensagens do sistema", listOf(
            Item.Action("Mensagens do sistema", "Avisos do HyperMagic") { open("messages") },
        )),
        Category("hands", "back_hand", "Mãos e controles", "Hand tracking, opções do controle", listOf(
            Item.Info("Mãos") { a.handStatus },
            Item.Toggle("Mostrar as mãos", "Contorno das mãos rastreadas", { it.handTracking.handVisual != HandVisual.NONE }) { s, v ->
                s.copy(handTracking = s.handTracking.copy(handVisual = if (v) HandVisual.AURA else HandVisual.NONE)) },
            Item.Action("Tom de pele", "Mãos e braços do modo VR: toque para trocar") {
                settings.update { it.copy(handTracking = it.handTracking.copy(skinTone = (it.handTracking.skinTone + 1) % com.hyperxr.settings.SKIN_TONES.size)) }
            },
            Item.Toggle("Direct Touch", "Tocar botões com o dedo indicador", { it.handTracking.directTouch }) { s, v -> s.copy(handTracking = s.handTracking.copy(directTouch = v)) },
            Item.Toggle("Raio curto + pinça", "Clique das mãos: um raio curto mira, a pinça clica", { it.handTracking.clickGesture == ClickGesture.SHORT_RAY }) { s, v ->
                s.copy(handTracking = s.handTracking.copy(clickGesture = if (v) ClickGesture.SHORT_RAY else ClickGesture.PINCH)) },
            Item.Toggle("Clicar apontando e empurrando", "Em vez da pinça", { it.handTracking.clickGesture == ClickGesture.POINT_PUSH }) { s, v ->
                s.copy(handTracking = s.handTracking.copy(clickGesture = if (v) ClickGesture.POINT_PUSH else ClickGesture.SHORT_RAY)) },
            Item.Info("HyperConnect") { hyperConnectMessage.ifEmpty { a.hyperConnectStatus } },
            Item.Toggle("HyperConnect", "Outro celular como controle, por Bluetooth", { it.controllers.hyperConnect }) { s, v -> s.copy(controllers = s.controllers.copy(hyperConnect = v)) },
            Item.Toggle("Vibração no HyperConnect", "O celular controle vibra ao apontar um botão", { it.controllers.hyperConnectHaptics }) { s, v -> s.copy(controllers = s.controllers.copy(hyperConnectHaptics = v)) },
            Item.Toggle("Controle por cor", "Controle com adesivos coloridos, achado pela câmera (calibre nas configurações 2D)", { it.controllers.colorController }) { s, v -> s.copy(controllers = s.controllers.copy(colorController = v)) },
            Item.Action("Enviar o app HyperConnect", "Instale no outro celular, pareie os dois e conecte") {
                hyperConnectMessage = CompatLayer.shareHyperConnect(HyperXRApp.instance)
            },
            Item.Info("Controle Daydream") { daydreamMessage.ifEmpty { CompatLayer.daydreamStatus(HyperXRApp.instance) } },
            Item.Action("Instalar HyperMagic Daydream", "Joy-Con como controle Daydream nos apps GVR") {
                daydreamMessage = "Instalando..."
                CompatLayer.installDaydream(HyperXRApp.instance) { msg -> daydreamMessage = msg; Diagnostics.log("HyperMagic Daydream: $msg") }
            },
            Item.Action("Configurar controle Daydream", "Permissão do Joy-Con, eixos e teste ao vivo") {
                daydreamMessage = if (CompatLayer.openDaydream(HyperXRApp.instance)) "Aberto na tela do celular" else "Instale o HyperMagic Daydream primeiro"
            },
            Item.Toggle("Joy-Con", "Usar Joy-Cons como controles", { it.controllers.joyConEnabled }) { s, v -> s.copy(controllers = s.controllers.copy(joyConEnabled = v)) },
        )),
        Category("accessibility", "accessibility_new", "Acessibilidade", "Clique por olhar, toque na tela", listOf(
            Item.Toggle("Clique automático ao apontar", "Olhar parado num botão clica", { it.controllers.dwellClick }) { s, v -> s.copy(controllers = s.controllers.copy(dwellClick = v)) },
            Item.Toggle("Toque na tela seleciona", "Botão do VR Box", { it.controllers.screenTapSelects }) { s, v -> s.copy(controllers = s.controllers.copy(screenTapSelects = v)) },
            Item.Toggle("Modo sentado", "Interface mais baixa", { it.accessibility.seatedMode }) { s, v -> s.copy(accessibility = s.accessibility.copy(seatedMode = v)) },
            Item.Toggle("Alto contraste", "", { it.accessibility.highContrast }) { s, v -> s.copy(accessibility = s.accessibility.copy(highContrast = v)) },
        )),
        Category("privacy", "shield", "Privacidade", "Câmera, downloads", listOf(
            Item.Toggle("Baixar ambientes", "Permite baixar ambientes da internet", { it.network.allowEnvironmentDownloads }) { s, v -> s.copy(network = s.network.copy(allowEnvironmentDownloads = v)) },
            Item.Toggle("Só pelo Wi-Fi", "Downloads grandes só no Wi-Fi", { it.network.downloadsOnWifiOnly }) { s, v -> s.copy(network = s.network.copy(downloadsOnWifiOnly = v)) },
        )),
        Category("experimental", "science", "Experimental", "Recursos em desenvolvimento", listOf(
            Item.Toggle("Profundidade por IA", "Depth Anything V2 confirma os toques no teclado de mesa", { it.handTracking.aiDepth }) { s, v -> s.copy(handTracking = s.handTracking.copy(aiDepth = v)) },
            Item.Toggle("Câmera a 60 fps acima da Depth API", "Menos latência nas mãos", { it.handTracking.prefer60FpsOverDepth }) { s, v -> s.copy(handTracking = s.handTracking.copy(prefer60FpsOverDepth = v)) },
            Item.Toggle("Depth API do ARCore nas mãos", "Distância medida das mãos", { it.handTracking.useArCoreDepth }) { s, v -> s.copy(handTracking = s.handTracking.copy(useArCoreDepth = v)) },
        )),
    )

    private var page: Category? = null
    private val query = StringBuilder()
    private var searching = false

    private fun storageText(): String {
        val f = HyperXRApp.instance.filesDir
        val free = f.usableSpace / 1e9; val total = f.totalSpace / 1e9
        return "%.2f GB livres de %.0f GB".format(free, total)
    }

    // search typing
    override fun typeText(text: String) { query.append(text); dirty = true }
    override fun sendKey(keyCode: Int) {
        when (keyCode) {
            KeyEvent.KEYCODE_DEL -> if (query.isNotEmpty()) query.setLength(query.length - 1)
            KeyEvent.KEYCODE_ENTER -> { searching = false; a.closeKeyboard() }
        }
        dirty = true
    }

    override fun paintBackground(c: PanelCanvas) {
        fill.color = BG
        c.canvas.drawRoundRect(RectF(0f, 0f, c.w, c.h - STRIP - 20f), 44f, 44f, fill)
        // the window strip below, like a headset's window bar
        fill.color = STRIP_BG
        c.canvas.drawRoundRect(RectF(0f, c.h - STRIP, c.w, c.h), 28f, 28f, fill)
        c.y = 0f
    }

    override fun draw(c: PanelCanvas) {
        val bodyH = c.h - STRIP - 20f
        // ---- bottom strip: close, minimise, name
        stripIcon(c, "close", "close", 60f, c.h - STRIP / 2) { visible = false; onClose?.invoke() }
        stripIcon(c, "min", "remove", 150f, c.h - STRIP / 2) { visible = false; onClose?.invoke() }
        text.textSize = 30f; text.color = TEXT; text.typeface = XrTheme.medium; text.textAlign = Paint.Align.CENTER
        c.canvas.drawText(com.hyperxr.i18n.I18n.t(page?.title ?: "Configurações"), c.w / 2, c.h - STRIP / 2 + 10f, text)
        text.textAlign = Paint.Align.LEFT

        val p = page
        if (p != null) { drawPage(c, p, bodyH); return }

        // ---- search field
        val sb = RectF(c.w / 2 - 340f, 36f, c.w / 2 + 340f, 106f)
        fill.color = if (isHovered("search")) SEARCH_HOVER else SEARCH_BG
        c.canvas.drawRoundRect(sb, 35f, 35f, fill)
        icon("search")?.let { c.canvas.drawBitmap(it, null, RectF(sb.left + 26f, sb.top + 15f, sb.left + 66f, sb.top + 55f), bmp) }
        text.textSize = 28f; text.typeface = XrTheme.regular
        text.color = if (query.isEmpty()) TEXT2 else TEXT
        c.canvas.drawText(if (query.isEmpty()) com.hyperxr.i18n.I18n.t("Pesquisar nas configurações") else query.toString() + if (searching) "|" else "", sb.left + 86f, sb.top + 45f, text)
        addRegion(Region(sb, "search", { searching = true; a.openKeyboard(this); dirty = true }))

        val q = query.toString().trim().lowercase()
        if (q.isNotEmpty()) { drawSearch(c, q, bodyH); return }

        // ---- the grid of tiles
        val cols = 4
        val gap = 22f
        val left = 44f
        val top = 140f
        val tw = (c.w - 2 * left - (cols - 1) * gap) / cols
        val th = 170f
        categories.forEachIndexed { i, cat ->
            val x = left + (i % cols) * (tw + gap)
            val y = top + (i / cols) * (th + gap)
            if (y + th > bodyH - 20f) return@forEachIndexed
            val r = RectF(x, y, x + tw, y + th)
            fill.color = if (isHovered("cat:${cat.id}")) TILE_HOVER else TILE
            c.canvas.drawRoundRect(r, 26f, 26f, fill)
            icon(cat.icon)?.let { c.canvas.drawBitmap(it, null, RectF(x + 26f, y + 24f, x + 70f, y + 68f), bmp) }
            c.label(cat.title, x + 26f, y + 88f, 30f, TEXT, bold = true, maxWidth = tw - 40f, maxLines = 1)
            c.label(cat.subtitle, x + 26f, y + 128f, 22f, TEXT2, maxWidth = tw - 40f, maxLines = 1)
            addRegion(Region(r, "cat:${cat.id}", { page = cat; dirty = true }))
        }
    }

    /** A category's page: back arrow, then its switches and actions as rows. */
    private fun drawPage(c: PanelCanvas, p: Category, bodyH: Float) {
        val back = RectF(36f, 30f, 116f, 110f)
        if (isHovered("back")) { fill.color = TILE_HOVER; c.canvas.drawRoundRect(back, 40f, 40f, fill) }
        icon("arrow_back")?.let { c.canvas.drawBitmap(it, null, RectF(back.left + 18f, back.top + 18f, back.right - 18f, back.bottom - 18f), bmp) }
        addRegion(Region(back, "back", { page = null; dirty = true }))
        icon(p.icon)?.let { c.canvas.drawBitmap(it, null, RectF(140f, 44f, 192f, 96f), bmp) }
        c.label(p.title, 212f, 42f, 40f, TEXT, bold = true)
        rows(c, p.items, 140f, bodyH)
    }

    private fun drawSearch(c: PanelCanvas, q: String, bodyH: Float) {
        val hits = categories.flatMap { cat -> cat.items.filter { it.label.lowercase().contains(q) || cat.title.lowercase().contains(q) } }
        if (hits.isEmpty()) { c.label("Nada encontrado para \"$q\"", 60f, 150f, 28f, TEXT2); return }
        rows(c, hits, 140f, bodyH)
    }

    private fun rows(c: PanelCanvas, items: List<Item>, top: Float, bodyH: Float) {
        var y = top
        val left = 60f; val right = c.w - 60f
        val s = settings.current
        for ((i, it) in items.withIndex()) {
            if (y + 104f > bodyH - 10f) break
            val r = RectF(left, y, right, y + 104f)
            val id = "row$i"
            fill.color = if (isHovered(id) && it !is Item.Info) TILE_HOVER else TILE
            c.canvas.drawRoundRect(r, 22f, 22f, fill)
            c.label(it.label, left + 30f, y + 18f, 28f, TEXT, bold = true, maxWidth = r.width() - 260f, maxLines = 1)
            when (it) {
                is Item.Toggle -> {
                    if (it.detail.isNotEmpty()) c.label(it.detail, left + 30f, y + 60f, 21f, TEXT2, maxWidth = r.width() - 260f, maxLines = 1)
                    val on = it.get(s)
                    switch(c, right - 130f, y + 30f, on)
                    addRegion(Region(r, id, { settings.update { cur -> it.set(cur, !it.get(cur)) }; dirty = true }))
                }
                is Item.Action -> {
                    if (it.detail.isNotEmpty()) c.label(it.detail, left + 30f, y + 60f, 21f, TEXT2, maxWidth = r.width() - 200f, maxLines = 1)
                    icon("arrow_back")?.let { b ->
                        c.canvas.save(); c.canvas.rotate(180f, right - 60f, y + 52f)
                        c.canvas.drawBitmap(b, null, RectF(right - 80f, y + 32f, right - 40f, y + 72f), bmp); c.canvas.restore()
                    }
                    addRegion(Region(r, id, { it.run(); dirty = true }))
                }
                is Item.Info -> c.label(it.value(), left + 30f, y + 60f, 21f, TEXT2, maxWidth = r.width() - 60f, maxLines = 1)
            }
            y += 118f
        }
    }

    /** A pill switch: blue with the knob right when on, grey with the knob left when off. */
    private fun switch(c: PanelCanvas, x: Float, y: Float, on: Boolean) {
        val r = RectF(x, y, x + 96f, y + 48f)
        fill.color = if (on) ON else OFF
        c.canvas.drawRoundRect(r, 24f, 24f, fill)
        fill.color = Color.WHITE
        c.canvas.drawCircle(if (on) r.right - 24f else r.left + 24f, r.centerY(), 19f, fill)
    }

    private fun stripIcon(c: PanelCanvas, id: String, name: String, cx: Float, cy: Float, action: () -> Unit) {
        if (isHovered(id)) { fill.color = TILE_HOVER; c.canvas.drawCircle(cx, cy, 32f, fill) }
        icon(name)?.let { c.canvas.drawBitmap(it, null, RectF(cx - 22f, cy - 22f, cx + 22f, cy + 22f), bmp) }
        addRegion(Region(RectF(cx - 36f, cy - 36f, cx + 36f, cy + 36f), id, action))
    }

    override fun update(dt: Float) {
        // live values (hands, storage) on the open page
        tick += dt
        if (tick > 1f) { tick = 0f; if (page != null) dirty = true }
    }
    private var tick = 0f

    override fun release() { if (searching) a.closeKeyboard(); super.release() }

    private fun icon(name: String): Bitmap? = ICONS.getOrPut(name) {
        val id = ctx.resources.getIdentifier("st_$name", "drawable", ctx.packageName)
        if (id == 0) null else BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inScaled = false })
    }

    private companion object {
        val ICONS = HashMap<String, Bitmap?>()
        val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        val bmp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        const val STRIP = 96f
        // the same teal-slate look as every other panel (XrTheme)
        val BG = XrTheme.panelBottom
        val STRIP_BG = XrTheme.bar
        val TILE = XrTheme.tile
        val TILE_HOVER = XrTheme.buttonHover
        val SEARCH_BG = XrTheme.button
        val SEARCH_HOVER = XrTheme.buttonHover
        val TEXT = XrTheme.text
        val TEXT2 = XrTheme.textDim
        val ON = XrTheme.accent
        val OFF = XrTheme.buttonDisabled
    }
}
