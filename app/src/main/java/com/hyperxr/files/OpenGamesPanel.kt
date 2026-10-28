package com.hyperxr.files

import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import com.hyperxr.xrapps.ApkAsset
import com.hyperxr.xrapps.OpenGame
import com.hyperxr.xrapps.OpenGames
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Open-source VR games: a hand-checked list plus a live GitHub search (OpenXR and Cardboard),
 * only projects under an open-source licence. A game whose latest release has an APK is
 * downloaded and opened in the patch/install panel; otherwise its source page opens.
 */
class OpenGamesPanel(private val host: MediaHost, private val openUrl: (String) -> Unit) :
    XrPanel(1.45f, 1.25f, title = "Jogos VR") {
    private enum class Tab(val label: String) { PARTNERS("Parceiros"), CURATED("Selecionados"), OPENXR("GitHub: OpenXR"), CARDBOARD("GitHub: Cardboard"), ITCH("itch.io") }
    private var tab = Tab.PARTNERS
    @Volatile private var results: List<OpenGame>? = null
    @Volatile private var status: String? = null
    private var page = 0
    @Volatile private var busy: String? = null

    override fun draw(c: PanelCanvas) {
        var x = c.pad
        for (t in Tab.values()) {
            c.button("tab:${t.name}", t.label, x, c.y, 236f, 64f, accent = t == tab) { select(t) }
            x += 246f
        }
        c.y += 80f
        status?.let { c.paragraph(it, 24f, XrTheme.accent2) }
        when (tab) {
            Tab.PARTNERS -> {
                c.paragraph("Jogos que os autores deixaram o HyperMagic oferecer, sem modificação: o APK original vem da página do autor e é conferido byte a byte antes de instalar. Eles rodam como foram feitos (não passam pelo Corrigir APK).", 22f)
                val ctx = com.hyperxr.HyperXRApp.instance
                for (g in com.hyperxr.xrapps.PartnerGames.ALL) {
                    val inst = com.hyperxr.compat.PartnerGameInstaller.installed(ctx, g)
                    val detail = "${g.author} • ${g.description} • ${g.permission} • ${g.itchPage}"
                    c.row("partner:${g.packageName}", if (inst) "Jogar ${g.name}" else "Baixar e instalar ${g.name}", detail = detail, enabled = busy == null) {
                        if (inst) { if (!com.hyperxr.compat.PartnerGameInstaller.launch(ctx, g)) { status = "Não abriu: reinstale"; dirty = true } }
                        else {
                            busy = g.packageName
                            com.hyperxr.compat.PartnerGameInstaller.downloadAndInstall(ctx, g, { p -> status = p; dirty = true }) { _, msg -> busy = null; status = msg; dirty = true }
                        }
                    }
                    c.row("partnerpage:${g.packageName}", "Página do autor", detail = g.itchPage) { openUrl(g.itchPage) }
                }
                return
            }
            Tab.ITCH -> {
                c.paragraph("O itch.io não tem busca por licença aberta para apps: abra a lista e veja a licença na página de cada jogo. " +
                    "APKs baixados vão para Corrigir APK.", 24f)
                for ((label, url) in OpenGames.ITCH_PAGES) c.row("itch:$url", label, detail = url) { openUrl(url) }
                return
            }
            else -> Unit
        }
        val list = if (tab == Tab.CURATED) OpenGames.CURATED else results
        if (list == null) { c.paragraph("Buscando no GitHub…", 26f); return }
        if (list.isEmpty()) { c.paragraph("Nada encontrado com licença open source.", 26f); return }
        if (tab == Tab.CURATED) c.paragraph("Licença conferida em cada repositório. Ainda não testados no HyperMagic: jogos feitos para o Quest passam pelo Corrigir APK.", 22f)
        val per = 5
        val pages = (list.size + per - 1) / per
        page = page.coerceIn(0, pages - 1)
        for (g in list.drop(page * per).take(per)) {
            val kind = if (g.kind == OpenGame.Kind.OPENXR) "OpenXR" else "Cardboard"
            val detail = buildString {
                append(kind); append(" • "); append(g.license)
                if (g.stars > 0) append(" • ${g.stars} estrelas")
                if (g.updated.isNotEmpty()) append(" • ${g.updated}")
                if (g.needs.isNotEmpty()) append(" • precisa de ${g.needs}")
                if (g.description.isNotEmpty()) append(" — ${g.description}")
            }
            c.row("g:${g.repo}", (if (busy == g.repo) "Baixando… " else "") + g.name, detail = detail) { get(g) }
        }
        if (pages > 1) {
            c.button("prev", "Anterior", c.pad, c.h - 96f, 200f, 70f, enabled = page > 0) { page-- }
            c.label("${page + 1}/$pages", c.w / 2 - 30f, c.h - 76f, 26f, XrTheme.textDim)
            c.button("next", "Próxima", c.w - c.pad - 200f, c.h - 96f, 200f, 70f, enabled = page < pages - 1) { page++ }
        }
    }

    private fun select(t: Tab) {
        tab = t; page = 0; status = null; dirty = true
        val kind = when (t) { Tab.OPENXR -> OpenGame.Kind.OPENXR; Tab.CARDBOARD -> OpenGame.Kind.CARDBOARD; else -> return }
        results = null
        mediaIo.execute {
            val lists = OpenGames.queries(kind).mapNotNull { q ->
                runCatching { OpenGames.parseSearch(get(OpenGames.searchUrl(q)), kind) }
                    .onFailure { status = "GitHub: ${it.message}" }.getOrNull()
            }
            results = OpenGames.merge(lists)
            dirty = true
        }
    }

    /** Latest release with an APK -> download and patch/install; otherwise the source page. */
    private fun get(g: OpenGame) {
        if (busy != null) return
        busy = g.repo; dirty = true
        downloadOpenGame(host, g, openUrl, { status = it; dirty = true }) { busy = null; dirty = true }
    }

    private fun get(url: String): String = githubGet(url)
}

/**
 * Downloads an open-source game's latest release APK and opens it in the check/patch/install
 * panel (the Store and the Jogos VR panel both use it); without an APK, its source page opens.
 */
fun downloadOpenGame(host: MediaHost, g: OpenGame, openUrl: (String) -> Unit, status: (String) -> Unit, done: () -> Unit) {
    status("Procurando APK em ${g.repo}…")
    mediaIo.execute {
        try {
            val apk: ApkAsset? = runCatching { OpenGames.parseRelease(githubGet(OpenGames.latestReleaseUrl(g.repo))).firstOrNull() }.getOrNull()
            if (apk == null) {
                status("${g.name}: sem APK pronto na última versão; abrindo o código-fonte")
                host.onGl { openUrl(g.url) }
                return@execute
            }
            status("Baixando ${apk.name} (${FileKinds.formatSize(apk.bytes)})…")
            val dir = ApkImports.dir(host.activity)
            dir.listFiles()?.forEach { it.delete() }
            val out = File(dir, apk.name.replace('/', '_'))
            downloadFile(apk.url, out)
            Diagnostics.log("Open-source game downloaded: ${g.repo} ${apk.tag} ${apk.name} (${g.license})")
            status("${g.name} ${apk.tag} baixado: confira e instale")
            host.onGl { host.openPanel(ApkPanel(host, out), 1.25f) }
        } catch (t: Throwable) {
            status("Falha: ${t.message}")
        } finally {
            done()
        }
    }
}

internal fun githubGet(url: String): String = (URL(url).openConnection() as HttpURLConnection).run {
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("User-Agent", "HyperMagic")
        connectTimeout = 15000; readTimeout = 30000
        if (responseCode == 403) error("limite de buscas do GitHub atingido; tente em alguns minutos")
        require(responseCode == 200) { "HTTP $responseCode" }
        inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

internal fun downloadFile(url: String, out: File) {
        (URL(url).openConnection() as HttpURLConnection).run {
            setRequestProperty("User-Agent", "HyperMagic")
            instanceFollowRedirects = true
            connectTimeout = 15000; readTimeout = 60000
            require(responseCode == 200) { "HTTP $responseCode" }
            inputStream.use { i -> out.outputStream().use { i.copyTo(it, 1 shl 16) } }
        }
    }
