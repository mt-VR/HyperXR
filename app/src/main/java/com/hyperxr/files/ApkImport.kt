package com.hyperxr.files

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** APKs brought into HyperXR to be checked and patched (OpenXR, Quest, Daydream, Cardboard). */
object ApkImports {
    fun dir(ctx: Context) = File(ctx.filesDir, "imports").apply { mkdirs() }

    /** Copies a picked document (Storage Access Framework) into the app; returns the local file. */
    fun copy(ctx: Context, uri: Uri): File {
        var name = "importado.apk"
        ctx.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0)?.let { name = it.replace('/', '_') }
        }
        if (!name.contains('.')) name += ".apk"
        // keep only the newest import: APKs are big
        dir(ctx).listFiles()?.forEach { it.delete() }
        val out = File(dir(ctx), name)
        ctx.contentResolver.openInputStream(uri)!!.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } }
        return out
    }

    /** APK files in the usual places (Download, Documents, Bluetooth, messaging apps), newest first. */
    fun scan(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        val out = ArrayList<File>()
        fun walk(d: File, depth: Int) {
            if (depth > 4 || out.size >= 300) return
            val list = d.listFiles() ?: return
            for (f in list) {
                if (f.isDirectory) {
                    if (f.name.startsWith(".") || (depth == 0 && f.name == "Android")) continue
                    walk(f, depth + 1)
                } else if (f.name.endsWith(".apk", true) || f.name.endsWith(".apks", true) || f.name.endsWith(".xapk", true)) out += f
            }
        }
        walk(root, 0)
        return out.sortedByDescending { it.lastModified() }
    }
}

/**
 * Pick an APK inside XR: every APK found on the phone, or the Android file picker. The chosen APK
 * opens in the install panel, which analyses it and offers "Corrigir e instalar".
 */
class ApkPickerPanel(private val host: MediaHost) : XrPanel(1.4f, 1.15f, title = "Importar APK para corrigir") {
    @Volatile private var files: List<File>? = null
    private var page = 0
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    init { rescan() }

    private fun rescan() {
        files = null; dirty = true
        mediaIo.execute { files = runCatching { ApkImports.scan() }.getOrDefault(emptyList()); dirty = true }
    }

    override fun draw(c: PanelCanvas) {
        val act = host.activity
        c.paragraph("Jogos OpenXR, de Quest e apps Daydream/Cardboard: escolha o APK e o HyperXR mostra se roda e oferece Corrigir e instalar.", 24f)
        if (!StorageAccess.granted(act)) {
            c.paragraph("Para listar os APKs do celular, permita o acesso aos arquivos (a tela aparece no celular).", 26f, XrTheme.text)
            c.button("grant", "Permitir acesso", c.pad, c.y, 340f, 76f, accent = true) { StorageAccess.request(act) }
            c.button("rescan0", "Já permiti", c.pad + 360f, c.y, 240f, 76f) { rescan() }
            c.y += 96f
        }
        c.button("picker", "Seletor do Android (tire o celular do visor)", c.pad, c.y, 640f, 70f) { pickWithSystem() }
        c.button("rescan", "Procurar de novo", c.w - c.pad - 280f, c.y, 280f, 70f) { rescan() }
        c.y += 90f
        val list = files
        if (list == null) { c.paragraph("Procurando APKs…", 26f); return }
        if (list.isEmpty()) { c.paragraph("Nenhum APK encontrado (Download, Documentos, Bluetooth...).", 26f); return }
        val per = 6
        val pages = (list.size + per - 1) / per
        page = page.coerceIn(0, pages - 1)
        for (f in list.drop(page * per).take(per)) {
            val where = f.parentFile?.name ?: ""
            c.row("apk:${f.absolutePath}", f.name, detail = "$where • ${FileKinds.formatSize(f.length())} • ${fmt.format(Date(f.lastModified()))}") {
                host.openPanel(ApkPanel(host, f), 1.25f)
            }
        }
        if (pages > 1) {
            c.button("prev", "Anterior", c.pad, c.h - 96f, 200f, 70f, enabled = page > 0) { page-- }
            c.label("${page + 1}/$pages", c.w / 2 - 30f, c.h - 76f, 26f, XrTheme.textDim)
            c.button("next", "Próxima", c.w - c.pad - 200f, c.h - 96f, 200f, 70f, enabled = page < pages - 1) { page++ }
        }
    }

    private fun pickWithSystem() {
        val act = host.activity
        com.hyperxr.xr.ActivityResults.launch(act, android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
            .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(android.content.Intent.EXTRA_MIME_TYPES, arrayOf("application/vnd.android.package-archive", "application/octet-stream"))) { data ->
            val uri = data?.data ?: return@launch
            mediaIo.execute {
                val f = runCatching { ApkImports.copy(act, uri) }.getOrNull() ?: return@execute
                host.onGl { host.openPanel(ApkPanel(host, f), 1.25f) }
            }
        }
    }
}
