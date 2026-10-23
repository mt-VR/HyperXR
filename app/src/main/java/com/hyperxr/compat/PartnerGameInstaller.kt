package com.hyperxr.compat

import android.content.Context
import android.content.Intent
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.xrapps.PartnerGame
import com.hyperxr.xrapps.PartnerGames
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gets a partner game from its author's itch.io page (the same steps as the page's Download
 * button), checks it is byte for byte the published APK, and installs it unchanged.
 */
object PartnerGameInstaller {
    private const val UA = "Mozilla/5.0 (Linux; Android) HyperXR"

    fun installed(ctx: Context, g: PartnerGame) = CompatLayer.installed(ctx, g.packageName)

    fun launch(ctx: Context, g: PartnerGame): Boolean = ctx.packageManager.getLaunchIntentForPackage(g.packageName)
        ?.let { ctx.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); true } ?: false

    /** Background thread: download, verify, install. [progress] gets status lines. */
    fun downloadAndInstall(ctx: Context, g: PartnerGame, progress: (String) -> Unit, done: (Boolean, String) -> Unit) {
        Thread({
            try {
                progress("Abrindo a página do autor no itch.io...")
                val cookies = ArrayList<String>()
                val page = request(g.itchPage, cookies = cookies)
                val csrf = PartnerGames.csrfToken(page.second) ?: error("a página do itch.io mudou (sem token)")
                val answer = request(PartnerGames.fileEndpoint(g), post = "csrf_token=" + java.net.URLEncoder.encode(csrf, "UTF-8"), cookies = cookies)
                val url = PartnerGames.downloadUrl(answer.second) ?: error("o itch.io não deu o link do arquivo")
                progress("Baixando ${g.fileName} (${g.bytes / 1_000_000} MB)...")
                val apk = File(ctx.cacheDir, "partner_${g.packageName}.apk")
                download(url, apk)
                val hash = apk.inputStream().use { PartnerGames.sha256(it) }
                if (hash != g.sha256) {
                    apk.delete()
                    done(false, "O arquivo do itch.io não é a versão conhecida (o autor pode ter atualizado). Nada foi instalado.")
                    return@Thread
                }
                progress("Conferido: é o APK original. Instalando...")
                CompatLayer.installApkFile(ctx, apk) { ok, msg -> done(ok, if (ok) "${g.name} instalado" else msg) }
            } catch (t: Throwable) {
                Diagnostics.log("Partner game ${g.packageName}: ${t.message}")
                done(false, "Não deu para baixar: ${t.message}")
            }
        }, "partner-game").start()
    }

    private fun request(url: String, post: String? = null, cookies: MutableList<String>): Pair<Int, String> {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15000; c.readTimeout = 30000
        c.setRequestProperty("User-Agent", UA)
        if (cookies.isNotEmpty()) c.setRequestProperty("Cookie", cookies.joinToString("; "))
        if (post != null) {
            c.requestMethod = "POST"; c.doOutput = true
            c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            c.setRequestProperty("X-Requested-With", "XMLHttpRequest")
            c.outputStream.use { it.write(post.toByteArray()) }
        }
        c.headerFields["Set-Cookie"]?.forEach { v -> val kv = v.substringBefore(';'); cookies.removeAll { it.substringBefore('=') == kv.substringBefore('=') }; cookies += kv }
        val code = c.responseCode
        val body = (if (code < 400) c.inputStream else c.errorStream)?.bufferedReader()?.use { it.readText() } ?: ""
        if (code >= 400) error("itch.io respondeu $code")
        return code to body
    }

    private fun download(url: String, out: File) {
        var u = url
        repeat(5) {
            val c = URL(u).openConnection() as HttpURLConnection
            c.instanceFollowRedirects = false
            c.connectTimeout = 15000; c.readTimeout = 60000
            c.setRequestProperty("User-Agent", UA)
            val code = c.responseCode
            if (code in 300..399) { u = URL(URL(u), c.getHeaderField("Location")).toString(); return@repeat }
            if (code >= 400) error("download respondeu $code")
            c.inputStream.use { i -> out.outputStream().use { i.copyTo(it, 256 * 1024) } }
            return
        }
        error("redirecionamentos demais")
    }
}
