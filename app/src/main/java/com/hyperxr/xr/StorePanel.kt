package com.hyperxr.xr

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import com.hyperxr.HyperXRApp
import com.hyperxr.files.MediaHost
import com.hyperxr.launcher.AppCategory
import com.hyperxr.launcher.AppEntry
import com.hyperxr.launcher.AppKind
import com.hyperxr.rendering.CoverArt
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import com.hyperxr.xrapps.OpenGame
import com.hyperxr.xrapps.OpenGames
import com.hyperxr.xrapps.PartnerGame
import com.hyperxr.xrapps.PartnerGames

/**
 * Loja: one place for everything that can be played or opened, laid out like a headset store:
 * shelves down the left (Início, Tudo, the HyperMagic games by kind, Minecraft, OpenXR and
 * Cardboard, apps), search on top, a banner and pages of large covers, and a page per item with
 * its details and one main button. Every game and app is here, downloaded or not.
 * HyperMagic's own games are part of the app; OpenXR games are downloaded from their authors'
 * releases and pass through the APK check/patch panel; partner games are installed unchanged.
 */
class StorePanel(
    private val a: LauncherActions,
    private val host: MediaHost,
    /** Every app and game, downloaded or not. */
    private val catalog: () -> List<AppEntry>,
    private val openUrl: (String) -> Unit,
) : XrPanel(1.76f, 1.16f, title = "Loja") {
    @Volatile private var installing: String? = null

    /** One thing in the store. */
    private class Item(
        val id: String, val title: String, val subtitle: String, val description: String, val iconKey: String?,
        val entry: AppEntry? = null, val open: OpenGame? = null, val partner: PartnerGame? = null,
        val facts: List<Pair<String, String>> = emptyList(),
    )

    /** A shelf of the sidebar: its name and what is on it. */
    private class Shelf(val key: String, val title: String, val glyph: String, val items: List<Item>)

    private val search = UrlEditor({ a.closeKeyboard(); dirty = true }, { page = 0; dirty = true })
    private var shelf = "home"
    private var page = 0
    private var detail: Item? = null
    @Volatile private var status: String? = null
    @Volatile private var busy = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var shelves: List<Shelf> = emptyList()
    private var builtAt = 0L
    private var heroIndex = 0
    private var heroT = 0f

    init { Thread { com.hyperxr.progress.ProgressStore.record(com.hyperxr.progress.Activity.OpenedStore) }.start() }

    private fun entryItem(e: AppEntry, sub: String) = Item(
        "app:${e.id}", e.title, sub, listOf(e.limitations, e.compatibility).filter { it.isNotEmpty() }.joinToString("\n\n"), e.iconKey, entry = e,
        facts = listOf("Roda em" to e.runtime, "Funciona com" to e.compatibility).filter { it.second.isNotEmpty() },
    )

    private fun build(): List<Shelf> {
        val all = catalog()
        val byId = all.associateBy { it.id }
        val recents = HyperXRApp.instance.settings.current.launcher.recents.mapNotNull { byId[it] }
            .filter { it.kind != AppKind.UNAVAILABLE && com.hyperxr.launcher.GameInstalls.visible(it) }.take(12)
        val games = all.filter { it.category == AppCategory.HYPERXR_GAME && it.target != "opengames" && it.target != "emulators" }
        val minecraft = games.filter { it.id.startsWith("game:questcraft") || it.id.startsWith("game:gorillacraft") }
        val hyper = games - minecraft.toSet()
        fun genre(e: AppEntry): String = GENRES[e.id.removePrefix("game:")] ?: "adventure"
        val installedXr = all.filter { it.kind == AppKind.ANDROID_PACKAGE && (it.category == AppCategory.OPENXR || it.category == AppCategory.CARDBOARD) }
        val apps = all.filter { it.category != AppCategory.HYPERXR_GAME && it.kind != AppKind.UNAVAILABLE && it !in installedXr }
        val partners = PartnerGames.ALL.map { g ->
            Item("partner:${g.packageName}", g.name, "Grátis • ${g.author}", "${g.description}\n\n${g.permission}", null, partner = g,
                facts = listOf("Autor" to g.author, "Tamanho" to "${g.bytes / 1_000_000} MB", "Origem" to g.itchPage))
        }
        val open = OpenGames.CURATED.map { g ->
            Item("open:${g.repo}", g.name, "Grátis • ${if (g.kind == OpenGame.Kind.OPENXR) "OpenXR" else "Cardboard"} • ${g.license}",
                g.description + (if (g.needs.isNotEmpty()) "\n\nPrecisa de: ${g.needs}" else ""), null, open = g,
                facts = listOf("Licença" to g.license, "Código" to (if (g.ready) "${g.url}/tree/main/ports" else g.url),
                    "Tipo" to when { g.ready -> "OpenXR (feito para o HyperMagic)"; g.kind == OpenGame.Kind.OPENXR -> "OpenXR (passa pelo Corrigir APK)"; else -> "Cardboard" }))
        }
        val hyperItems = hyper.map { entryItem(it, gameSub(it)) }
        val xrItems = partners + open + installedXr.map { entryItem(it, "Instalado") } +
            listOfNotNull(byId["hx:opengames"]?.let { Item("more:opengames", "Buscar mais jogos VR", "GitHub e itch.io", "Busca ao vivo por jogos OpenXR e Cardboard com licença open source.", "opengames", entry = it) })
        val appItems = apps.map { entryItem(it, if (it.kind == AppKind.WEB) "Grátis • Web" else "Grátis • Já instalado") }
        val everything = hyperItems + minecraft.map { entryItem(it, gameSub(it) + " · conta Minecraft") } + xrItems + appItems
        val mine = everything.filter { it.entry?.let { e -> !com.hyperxr.launcher.GameInstalls.isGame(e) || com.hyperxr.launcher.GameInstalls.installed(e.id) } ?: false }
        return listOf(
            Shelf("home", "Início", "explore", recents.map { entryItem(it, "Abrir") }),
            Shelf("all", "Tudo", "apps", everything),
            Shelf("hyper", "HyperMagic", "sports_esports", hyperItems),
            Shelf("action", "Ação", "rocket_launch", hyper.filter { genre(it) == "action" }.map { entryItem(it, gameSub(it)) }),
            Shelf("sports", "Esportes", "sports_kabaddi", hyper.filter { genre(it) == "sports" }.map { entryItem(it, gameSub(it)) }),
            Shelf("rhythm", "Ritmo", "music_note", hyper.filter { genre(it) == "rhythm" }.map { entryItem(it, gameSub(it)) }),
            Shelf("puzzle", "Quebra-cabeça", "extension", hyper.filter { genre(it) == "puzzle" }.map { entryItem(it, gameSub(it)) }),
            Shelf("adventure", "Aventura", "landscape", hyper.filter { genre(it) == "adventure" }.map { entryItem(it, gameSub(it)) }),
            Shelf("minecraft", "Minecraft", "view_in_ar", minecraft.map { entryItem(it, gameSub(it) + " · conta Minecraft") }),
            Shelf("xr", "OpenXR", "vrpano", xrItems),
            Shelf("apps", "Apps e vídeo", "live_tv", appItems),
            Shelf("mine", "Instalados", "done", mine),
        )
    }

    override fun update(dt: Float) {
        val now = System.currentTimeMillis()
        if (now - builtAt > 4000) { builtAt = now; shelves = build(); dirty = true }
        // the banner on Início changes every few seconds
        heroT += dt
        if (heroT > 6f && detail == null && shelf == "home") { heroT = 0f; heroIndex++; dirty = true }
    }

    override fun draw(c: PanelCanvas) {
        if (shelves.isEmpty()) shelves = build()
        val d = detail
        if (d != null) { drawDetail(c, d); return }
        // sidebar: the shelves with how many things each has
        val sideW = 400f
        c.label("Loja", c.pad, c.y, 46f, XrTheme.text, bold = true, maxLines = 1)
        var y = c.y + 74f
        for (sh in shelves) {
            val label = if (sh.key == "home") sh.title else "${sh.title}  ${sh.items.size}"
            c.button("shelf:${sh.key}", label, c.pad - 10f, y, sideW - c.pad, 58f, accent = shelf == sh.key, icon = Glyphs.get(sh.glyph)) {
                shelf = sh.key; page = 0; search.cancel(); dirty = true
            }
            y += 64f
        }
        val x0 = sideW + 20f
        val mw = c.w - x0 - c.pad
        // search on top
        val q = search.text
        c.button("search", if (search.active) "$q|" else if (q.isNotEmpty()) q else "Pesquise jogos, apps e muito mais", x0, c.y, mw - (if (q.isNotEmpty()) 150f else 0f), 64f,
            accent = search.active, icon = Glyphs.get("search")) { if (!search.active) { search.begin(search.text); a.openKeyboard(search) } }
        if (q.isNotEmpty()) c.button("clear", "Limpar", x0 + mw - 140f, c.y, 140f, 64f) { search.begin(""); search.cancel(); dirty = true }
        var top = c.y + 84f
        status?.let { c.label(it, x0, top - 12f, 22f, XrTheme.accent2, maxLines = 1, maxWidth = mw); top += 20f }
        val current = shelves.firstOrNull { it.key == shelf } ?: shelves.first()
        val list: List<Item> = when {
            q.isNotBlank() -> shelves.first { it.key == "all" }.items.filter { it.title.contains(q.trim(), true) || it.description.contains(q.trim(), true) }
            current.key == "home" -> emptyList()
            else -> current.items
        }
        if (q.isBlank() && current.key == "home") { drawHome(c, x0, top, mw); return }
        c.label(if (q.isNotBlank()) "Resultados para \"${q.trim()}\"  ·  ${list.size}" else "${current.title}  ·  ${list.size}", x0, top, 30f, XrTheme.text, bold = true, maxLines = 1, maxWidth = mw)
        top += 52f
        grid(c, list, x0, top, mw, c.h - top - 70f)
    }

    /** A page of covers, 4 across, with the page arrows under it. */
    private fun grid(c: PanelCanvas, list: List<Item>, x0: Float, top: Float, mw: Float, mh: Float) {
        val cols = 5; val gap = 18f
        val cw = (mw - gap * (cols - 1)) / cols
        val chh = cw * 9f / 16f + 64f
        val rows = ((mh + gap) / (chh + gap)).toInt().coerceAtLeast(1)
        val per = cols * rows
        val pages = maxOf(1, (list.size + per - 1) / per)
        page = page.coerceIn(0, pages - 1)
        if (list.isEmpty()) c.label("Nada aqui ainda.", x0, top + 20f, 28f, XrTheme.textDim)
        list.drop(page * per).take(per).forEachIndexed { i, it -> card(c, it, x0 + (i % cols) * (cw + gap), top + (i / cols) * (chh + gap), cw) }
        if (pages > 1) {
            val by = c.h - 66f
            c.button("prevpage", "‹  Anterior", x0, by, 200f, 56f, enabled = page > 0) { page--; dirty = true }
            c.label("Página ${page + 1} de $pages", x0 + mw / 2 - 90f, by + 14f, 24f, XrTheme.textDim, maxLines = 1, maxWidth = 200f)
            c.button("nextpage", "Próxima  ›", x0 + mw - 200f, by, 200f, 56f, enabled = page < pages - 1) { page++; dirty = true }
        }
    }

    /** Início: a big banner of one game, what you played last, and new games. */
    private fun drawHome(c: PanelCanvas, x0: Float, top: Float, mw: Float) {
        val hyper = shelves.first { it.key == "hyper" }.items
        if (hyper.isNotEmpty()) {
            val hero = hyper[heroIndex.mod(hyper.size)]
            val hh = 270f
            val r = RectF(x0, top, x0 + mw, top + hh)
            c.canvas.save()
            c.canvas.clipPath(Path().apply { addRoundRect(r, 26f, 26f, Path.Direction.CW) })
            c.canvas.drawBitmap(CoverArt.cover(host.activity, hero.iconKey, "", 1280, 720), null, r, paint)
            paint.shader = android.graphics.LinearGradient(r.left, 0f, r.left + mw * 0.6f, 0f, Color.argb(220, 10, 20, 26), Color.argb(0, 10, 20, 26), android.graphics.Shader.TileMode.CLAMP)
            c.canvas.drawRect(r, paint); paint.shader = null
            c.canvas.restore()
            c.label("Destaque", x0 + 36f, top + 34f, 22f, XrTheme.accent2, bold = true, maxLines = 1)
            c.label(hero.title, x0 + 36f, top + 66f, 50f, Color.WHITE, bold = true, maxLines = 1, maxWidth = mw * 0.55f)
            c.label(hero.entry?.limitations?.substringBefore(". Inspirado")?.ifEmpty { hero.subtitle } ?: hero.subtitle, x0 + 36f, top + 128f, 22f, Color.argb(230, 255, 255, 255), maxLines = 2, maxWidth = mw * 0.5f)
            val (label, act) = mainAction(hero)
            c.button("heroMain", label, x0 + 36f, top + hh - 88f, 230f, 62f, accent = true, enabled = !busy && installing == null) { act() }
            c.button("heroInfo", "Detalhes", x0 + 282f, top + hh - 88f, 190f, 62f) { detail = hero; status = null; dirty = true }
            c.button("heroNext", "›", x0 + mw - 86f, top + hh / 2 - 36f, 64f, 72f) { heroIndex++; heroT = 0f; dirty = true }
        }
        var y = top + 290f
        val recent = shelves.first { it.key == "home" }.items
        val cols = 5; val gap = 18f
        val cw = (mw - gap * (cols - 1)) / cols
        val rowsOf = listOf(
            if (recent.isNotEmpty()) "Continue jogando" to recent else "Comece por aqui" to hyper.drop(3).take(cols),
            "Novidades" to hyper.takeLast(cols).reversed(),
        )
        for ((title, items) in rowsOf) {
            c.label(title, x0, y, 28f, XrTheme.text, bold = true, maxLines = 1)
            y += 44f
            items.take(cols).forEachIndexed { i, it -> card(c, it, x0 + i * (cw + gap), y, cw) }
            y += cw * 9f / 16f + 64f + 14f
        }
        c.button("seeAll", "Ver tudo na Loja (${shelves.first { it.key == "all" }.items.size})", x0, c.h - 66f, 420f, 56f, icon = Glyphs.get("apps")) { shelf = "all"; page = 0; dirty = true }
    }

    private fun cover(it: Item): Bitmap = CoverArt.cover(host.activity, it.iconKey, it.title)

    private fun card(c: PanelCanvas, it: Item, x: Float, top: Float, w: Float) {
        val h = w * 9f / 16f
        val id = "card:${it.id}"
        val hover = isHovered(id); val press = isPressed(id)
        val grow = if (press) -6f else if (hover) 6f else 0f
        val r = RectF(x - grow, top - grow, x + w + grow, top + h + grow)
        c.canvas.save()
        c.canvas.clipPath(Path().apply { addRoundRect(r, 22f, 22f, Path.Direction.CW) })
        c.canvas.drawBitmap(cover(it), null, r, paint)
        c.canvas.restore()
        if (hover) {
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 4f; paint.color = Color.WHITE
            c.canvas.drawRoundRect(r, 22f, 22f, paint); paint.style = Paint.Style.FILL
        }
        c.label(it.title, x + 4f, top + h + 10f, 25f, XrTheme.text, bold = true, maxWidth = w - 8f, maxLines = 1)
        c.label(it.subtitle, x + 4f, top + h + 42f, 20f, XrTheme.textDim, maxWidth = w - 8f, maxLines = 1)
        addRegion(Region(RectF(x, top, x + w, top + h + 64f), id, { detail = it; status = null; dirty = true }))
    }

    private fun drawDetail(c: PanelCanvas, it: Item) {
        c.button("back", "‹ Loja", c.pad, c.y, 170f, 64f) { detail = null }
        val top = c.y + 84f
        val cw = 760f; val ch = cw * 9f / 16f
        val r = RectF(c.pad, top, c.pad + cw, top + ch)
        c.canvas.save()
        c.canvas.clipPath(Path().apply { addRoundRect(r, 28f, 28f, Path.Direction.CW) })
        c.canvas.drawBitmap(CoverArt.cover(host.activity, it.iconKey, it.title, 1280, 720), null, r, paint)
        c.canvas.restore()
        val rx = c.pad + cw + 40f
        val rw = c.w - rx - c.pad
        var y = top
        y += c.label(it.title, rx, y, 50f, XrTheme.text, bold = true, maxWidth = rw, maxLines = 2) + 10f
        y += c.label(it.subtitle, rx, y, 26f, XrTheme.textDim, maxWidth = rw) + 30f
        val (label, act) = mainAction(it)
        c.button("main", if (busy) "Aguarde…" else label, rx, y, rw, 92f, accent = true, enabled = !busy && installing == null) { act() }
        y += 120f
        val e = it.entry
        if (e != null && com.hyperxr.launcher.GameInstalls.isGame(e) && com.hyperxr.launcher.GameInstalls.installed(e.id)) {
            c.button("remove", "Desinstalar", rx, y - 16f, rw, 64f) { com.hyperxr.launcher.GameInstalls.uninstall(e.id); status = "${e.title} removido (o progresso fica salvo)"; builtAt = 0L }
            y += 70f
        }
        for ((k, v) in it.facts) {
            c.label(k, rx, y, 22f, XrTheme.textDim, maxWidth = rw)
            y += 30f
            y += c.label(v, rx, y, 24f, XrTheme.text, maxWidth = rw, maxLines = 2) + 16f
        }
        status?.let { c.label(it, rx, c.h - 70f, 24f, XrTheme.accent2, maxWidth = rw, maxLines = 2) }
        c.label(it.description.ifEmpty { "Sem descrição." }, c.pad, top + ch + 30f, 28f, XrTheme.textDim, maxWidth = cw, maxLines = 8)
    }

    companion object {
        /** Which shelf each HyperMagic game is on. */
        private val GENRES: Map<String, String> = buildMap {
            for (g in listOf("slowtime", "drones", "zombieyard", "saber", "spells", "skeet", "paintball", "zerog", "boxing", "macacada")) put(g, "action")
            for (g in listOf("golf", "pingpong", "archery", "slingshot", "basketball", "bowling", "darts", "dodgeball", "fishing", "kayak", "wingsuit", "tableracing")) put(g, "sports")
            for (g in listOf("rhythm", "fruit", "neonrail", "orbs", "shapewall", "drumboat", "fittargets", "domeracket")) put(g, "rhythm")
            for (g in listOf("bomb", "spydesk", "pieces", "cubes", "fallingblocks", "tinytown", "dungeon")) put(g, "puzzle")
        }
    }

    private fun gameSub(e: AppEntry) = if (com.hyperxr.launcher.GameInstalls.installed(e.id)) "Instalado" else "Grátis • Baixar"

    private fun mainAction(it: Item): Pair<String, () -> Unit> {
        it.entry?.let { e ->
            if (com.hyperxr.launcher.GameInstalls.isGame(e) && !com.hyperxr.launcher.GameInstalls.installed(e.id)) {
                return (if (installing == e.id) "Instalando…" else "Baixar") to {
                    // the game is inside HyperMagic already: installing puts it in the library
                    installing = e.id; dirty = true
                    Thread {
                        Thread.sleep(900)
                        com.hyperxr.launcher.GameInstalls.install(e.id)
                        installing = null; status = "${e.title} instalado: está na Biblioteca"; builtAt = 0L; dirty = true
                    }.start()
                }
            }
            return (if (e.category == AppCategory.HYPERXR_GAME) "Jogar" else "Abrir") to { a.launch(e) }
        }
        it.partner?.let { g ->
            val ctx = host.activity
            return if (com.hyperxr.compat.PartnerGameInstaller.installed(ctx, g)) "Jogar" to {
                if (!com.hyperxr.compat.PartnerGameInstaller.launch(ctx, g)) { status = "Não abriu: reinstale"; dirty = true }
            } else "Baixar (${g.bytes / 1_000_000} MB)" to {
                busy = true; dirty = true
                com.hyperxr.compat.PartnerGameInstaller.downloadAndInstall(ctx, g, { p -> status = p; dirty = true }) { _, msg -> busy = false; status = msg; dirty = true }
            }
        }
        it.open?.let { g ->
            return "Baixar" to {
                busy = true; dirty = true
                com.hyperxr.files.downloadOpenGame(host, g, openUrl, { s -> status = s; dirty = true }) { busy = false; dirty = true }
            }
        }
        return "Abrir" to {}
    }
}
