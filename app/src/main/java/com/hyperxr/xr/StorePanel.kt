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
 * search on top, rows of large covers (continue playing, HyperMagic games, Minecraft, open-source
 * OpenXR and Cardboard games, apps), and a page per item with its details and one main button.
 * HyperMagic's own games are part of the app; OpenXR games are downloaded from their authors'
 * releases and pass through the APK check/patch panel; partner games are installed unchanged.
 */
class StorePanel(
    private val a: LauncherActions,
    private val host: MediaHost,
    private val openUrl: (String) -> Unit,
) : XrPanel(1.62f, 1.08f, title = "Loja") {

    /** One thing in the store. */
    private class Item(
        val id: String, val title: String, val subtitle: String, val description: String, val iconKey: String?,
        val entry: AppEntry? = null, val open: OpenGame? = null, val partner: PartnerGame? = null,
        val facts: List<Pair<String, String>> = emptyList(),
    )

    private class Row(val title: String, val items: List<Item>) { var first = 0 }

    private val search = UrlEditor({ a.closeKeyboard(); dirty = true }, { dirty = true })
    private var rowStart = 0
    private var detail: Item? = null
    @Volatile private var status: String? = null
    @Volatile private var busy = false
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private var rows: List<Row> = emptyList()
    private var builtAt = 0L

    init { Thread { com.hyperxr.progress.ProgressStore.record(com.hyperxr.progress.Activity.OpenedStore) }.start() }

    private fun entryItem(e: AppEntry, sub: String) = Item(
        "app:${e.id}", e.title, sub, listOf(e.limitations, e.compatibility).filter { it.isNotEmpty() }.joinToString("\n\n"), e.iconKey, entry = e,
        facts = listOf("Roda em" to e.runtime, "Funciona com" to e.compatibility).filter { it.second.isNotEmpty() },
    )

    private fun build(): List<Row> {
        val all = a.allApps()
        val byId = all.associateBy { it.id }
        val recents = HyperXRApp.instance.settings.current.launcher.recents.mapNotNull { byId[it] }
            .filter { it.kind != AppKind.UNAVAILABLE }.take(10)
        val games = all.filter { it.category == AppCategory.HYPERXR_GAME && it.kind == AppKind.SYSTEM_PANEL && !it.id.startsWith("game:questcraft") && !it.id.startsWith("game:gorillacraft") && it.target != "opengames" && it.target != "emulators" }
        val minecraft = listOfNotNull(byId["game:questcraft"], byId["game:gorillacraft"])
        val installedXr = all.filter { it.kind == AppKind.ANDROID_PACKAGE && (it.category == AppCategory.OPENXR || it.category == AppCategory.CARDBOARD) }
        val media = listOfNotNull(byId["hx:youtube"], byId["hx:browser"], byId["hx:emulators"], byId["hx:tv"], byId["hx:assistant"], byId["game:ocean"], byId["hx:gallery"])
        val partners = PartnerGames.ALL.map { g ->
            Item("partner:${g.packageName}", g.name, "Grátis • ${g.author}", "${g.description}\n\n${g.permission}", null, partner = g,
                facts = listOf("Autor" to g.author, "Tamanho" to "${g.bytes / 1_000_000} MB", "Origem" to g.itchPage))
        }
        val open = OpenGames.CURATED.map { g ->
            Item("open:${g.repo}", g.name, "Grátis • ${if (g.kind == OpenGame.Kind.OPENXR) "OpenXR" else "Cardboard"} • ${g.license}",
                g.description + (if (g.needs.isNotEmpty()) "\n\nPrecisa de: ${g.needs}" else ""), null, open = g,
                facts = listOf("Licença" to g.license, "Código" to g.url, "Tipo" to if (g.kind == OpenGame.Kind.OPENXR) "OpenXR (passa pelo Corrigir APK)" else "Cardboard"))
        }
        return listOfNotNull(
            Row("Continue jogando", recents.map { entryItem(it, "Abrir") }).takeIf { it.items.isNotEmpty() },
            Row("Jogos HyperMagic", games.map { entryItem(it, "Grátis • Já instalado") }),
            Row("Minecraft Java", minecraft.map { entryItem(it, "Grátis com a sua conta Minecraft") }).takeIf { it.items.isNotEmpty() },
            Row("OpenXR e Cardboard: parceiros e open source", partners + open),
            Row("Instalados no celular", installedXr.map { entryItem(it, "Instalado") }).takeIf { it.items.isNotEmpty() },
            Row("Vídeo, web e mais", media.map { entryItem(it, "Grátis • Já instalado") }),
            Row("Mais jogos open source", listOf(Item("more:opengames", "Buscar mais jogos VR", "GitHub e itch.io", "Busca ao vivo por jogos OpenXR e Cardboard com licença open source.", "opengames", entry = byId["hx:opengames"]))),
        )
    }

    override fun update(dt: Float) {
        val now = System.currentTimeMillis()
        if (now - builtAt > 4000) { builtAt = now; rows = build(); dirty = true }
    }

    override fun draw(c: PanelCanvas) {
        if (rows.isEmpty()) rows = build()
        val d = detail
        if (d != null) { drawDetail(c, d); return }
        // search bar like the store's: a wide pill
        val sx = c.pad; val sw = c.w - 2 * c.pad - 110f
        val q = search.text
        c.button("search", if (search.active) "$q|" else if (q.isNotEmpty()) q else "Pesquise jogos, apps e muito mais", sx, c.y, sw, 70f, accent = search.active) {
            if (!search.active) { search.begin(search.text); a.openKeyboard(search) }
        }
        if (q.isNotEmpty()) c.button("clear", "Limpar", sx + sw + 10f, c.y, 100f, 70f) { search.begin(""); search.cancel() }
        c.y += 92f
        status?.let { c.label(it, c.pad, c.y - 14f, 22f, XrTheme.accent2, maxLines = 1); c.y += 18f }
        val shown = if (q.isBlank()) rows else {
            val hits = rows.flatMap { it.items }.distinctBy { it.id }.filter { it.title.contains(q.trim(), true) || it.description.contains(q.trim(), true) }
            listOf(Row("Resultados para \"${q.trim()}\"", hits))
        }
        val rowH = 360f
        val visible = ((c.h - c.y - 10f) / rowH).toInt().coerceAtLeast(1)
        rowStart = rowStart.coerceIn(0, (shown.size - visible).coerceAtLeast(0))
        var y = c.y
        for (r in shown.drop(rowStart).take(visible)) { drawRow(c, r, y); y += rowH }
        // scroll buttons on the right edge, like the store's
        val bx = c.w - 74f
        c.button("top", "⤒", bx, c.h - 270f, 56f, 70f, enabled = rowStart > 0) { rowStart = 0 }
        c.button("up", "▲", bx, c.h - 190f, 56f, 70f, enabled = rowStart > 0) { rowStart-- }
        c.button("down", "▼", bx, c.h - 110f, 56f, 70f, enabled = rowStart + visible < shown.size) { rowStart++ }
    }

    private fun drawRow(c: PanelCanvas, r: Row, top: Float) {
        c.label(r.title, c.pad, top, 34f, XrTheme.text, bold = true, maxLines = 1)
        if (r.items.isEmpty()) { c.label("Nada encontrado.", c.pad, top + 60f, 26f, XrTheme.textDim); return }
        val cardW = 330f; val gap = 22f
        val area = c.w - 2 * c.pad - 90f
        val per = ((area + gap) / (cardW + gap)).toInt().coerceAtLeast(1)
        r.first = r.first.coerceIn(0, (r.items.size - per).coerceAtLeast(0))
        var x = c.pad
        for (it in r.items.drop(r.first).take(per)) { card(c, it, x, top + 52f, cardW); x += cardW + gap }
        if (r.items.size > per) {
            if (r.first > 0) c.button("prev:${r.title}", "‹", c.pad - 6f, top + 120f, 50f, 80f) { r.first -= per }
            if (r.first + per < r.items.size) c.button("next:${r.title}", "›", c.pad + per * (cardW + gap) - gap - 44f, top + 120f, 50f, 80f) { r.first += per }
        }
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
        c.label(it.title, x + 4f, top + h + 12f, 27f, XrTheme.text, bold = true, maxWidth = w - 8f, maxLines = 1)
        c.label(it.subtitle, x + 4f, top + h + 48f, 22f, XrTheme.textDim, maxWidth = w - 8f, maxLines = 1)
        addRegion(Region(RectF(x, top, x + w, top + h + 80f), id, { detail = it; status = null; dirty = true }))
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
        c.button("main", if (busy) "Aguarde…" else label, rx, y, rw, 92f, accent = true, enabled = !busy) { act() }
        y += 120f
        for ((k, v) in it.facts) {
            c.label(k, rx, y, 22f, XrTheme.textDim, maxWidth = rw)
            y += 30f
            y += c.label(v, rx, y, 24f, XrTheme.text, maxWidth = rw, maxLines = 2) + 16f
        }
        status?.let { c.label(it, rx, c.h - 70f, 24f, XrTheme.accent2, maxWidth = rw, maxLines = 2) }
        c.label(it.description.ifEmpty { "Sem descrição." }, c.pad, top + ch + 30f, 28f, XrTheme.textDim, maxWidth = cw, maxLines = 8)
    }

    private fun mainAction(it: Item): Pair<String, () -> Unit> {
        it.entry?.let { e ->
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
