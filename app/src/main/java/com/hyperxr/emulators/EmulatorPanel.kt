package com.hyperxr.emulators

import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.OpenableColumns
import com.hyperxr.files.FileKinds
import com.hyperxr.files.MediaHost
import com.hyperxr.files.StorageAccess
import com.hyperxr.files.mediaIo
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import java.io.File
import java.util.zip.ZipInputStream

/** ROMs: the ones imported into HyperXR plus those found on the phone. */
object RomLibrary {
    /** Copies a picked document into the app; a .zip gives its first ROM. Returns null if none. */
    fun import(c: Context, uri: Uri): File? {
        var name = "rom"
        c.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { q ->
            if (q.moveToFirst()) q.getString(0)?.let { name = it.replace('/', '_') }
        }
        val dir = EmulatorFiles.roms(c)
        if (name.endsWith(".zip", true)) {
            c.contentResolver.openInputStream(uri)?.use { raw ->
                ZipInputStream(raw).use { z ->
                    while (true) {
                        val e = z.nextEntry ?: break
                        val base = e.name.substringAfterLast('/')
                        if (e.isDirectory || EmulatorCatalog.forFile(base) == null) continue
                        val out = File(dir, base)
                        out.outputStream().use { z.copyTo(it, 1 shl 16) }
                        return out
                    }
                }
            }
            return null
        }
        if (EmulatorCatalog.forFile(name) == null) return null
        val out = File(dir, name)
        c.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it, 1 shl 16) } } ?: return null
        return out
    }

    fun scan(c: Context): List<File> {
        val out = ArrayList<File>()
        EmulatorFiles.roms(c).listFiles()?.filter { EmulatorCatalog.forFile(it.name) != null }?.let { out += it }
        val root = Environment.getExternalStorageDirectory()
        fun walk(d: File, depth: Int) {
            if (depth > 4 || out.size >= 500) return
            val list = d.listFiles() ?: return
            for (f in list) {
                if (f.isDirectory) {
                    if (f.name.startsWith(".") || (depth == 0 && f.name == "Android")) continue
                    walk(f, depth + 1)
                } else if (EmulatorCatalog.forFile(f.name) != null) out += f
            }
        }
        runCatching { walk(root, 0) }
        return out.distinctBy { it.name.lowercase() to it.length() }.sortedBy { it.name.lowercase() }
    }
}

/**
 * The emulators app: open-source cores embedded in HyperXR (NES, Game Boy / Color, GBA) and the
 * user's own ROMs. Picking a game opens it on a big screen with a hand controller below it.
 */
class EmulatorPanel(private val host: MediaHost, private val play: (EmulatorCore, File) -> Unit) :
    XrPanel(1.4f, 1.2f, title = "Emuladores") {
    @Volatile private var roms: List<File>? = null
    private var page = 0
    private var filter: String? = null

    init { rescan() }

    private fun rescan() {
        roms = null; dirty = true
        mediaIo.execute { roms = runCatching { RomLibrary.scan(host.activity) }.getOrDefault(emptyList()); dirty = true }
    }

    override fun draw(c: PanelCanvas) {
        val act = host.activity
        c.paragraph("Emuladores open source embutidos: ${EmulatorCatalog.cores.joinToString { "${it.system} (${it.coreName})" }}. " +
            "Nenhum jogo vem incluído: use as suas próprias ROMs.", 24f)
        var x = c.pad
        c.button("import", "Importar ROM", x, c.y, 300f, 70f, accent = true) { pickWithSystem() }; x += 316f
        c.button("rescan", "Procurar de novo", x, c.y, 300f, 70f) { rescan() }; x += 316f
        c.button("credits", "Créditos", x, c.y, 220f, 70f) { host.openPanel(EmulatorCreditsPanel(), 1.25f) }
        c.y += 86f
        if (!StorageAccess.granted(act)) {
            c.paragraph("Para achar ROMs no celular (Download, Documentos...), permita o acesso aos arquivos.", 24f, XrTheme.text)
            c.button("grant", "Permitir acesso", c.pad, c.y, 340f, 70f) { StorageAccess.request(act) }
            c.y += 86f
        }
        // system filter
        x = c.pad
        for ((id, label) in listOf<Pair<String?, String>>(null to "Todos") + EmulatorCatalog.cores.map { it.id to it.coreName }) {
            c.button("f:$id", label, x, c.y, 200f, 60f, accent = filter == id) { filter = id; page = 0 }
            x += 212f
        }
        c.y += 76f
        val all = roms
        if (all == null) { c.paragraph("Procurando ROMs…", 26f); return }
        val list = all.filter { f -> filter == null || EmulatorCatalog.forFile(f.name)?.id == filter }
        if (list.isEmpty()) {
            c.paragraph("Nenhuma ROM encontrada. Toque em Importar ROM (aceita .nes, .gb, .gbc, .gba e .zip).", 26f)
            return
        }
        val per = 6
        val pages = (list.size + per - 1) / per
        page = page.coerceIn(0, pages - 1)
        for (f in list.drop(page * per).take(per)) {
            val core = EmulatorCatalog.forFile(f.name) ?: continue
            c.row("rom:${f.absolutePath}", f.nameWithoutExtension, detail = "${core.system} • ${FileKinds.formatSize(f.length())}") { play(core, f) }
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
            .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")) { data ->
            val uri = data?.data ?: return@launch
            mediaIo.execute {
                val f = runCatching { RomLibrary.import(act, uri) }.getOrNull()
                rescan()
                if (f != null) EmulatorCatalog.forFile(f.name)?.let { core -> host.onGl { play(core, f) } }
            }
        }
    }
}

/** Who made the embedded emulators, their licences and where their source code is. */
class EmulatorCreditsPanel : XrPanel(1.3f, 1.25f, title = "Créditos dos emuladores") {
    override fun draw(c: PanelCanvas) {
        for (block in EmulatorCatalog.credits()) {
            c.paragraph(block, 23f, XrTheme.text)
            c.y += 8f
        }
    }
}
