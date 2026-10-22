package com.hyperxr.files

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import com.hyperxr.HyperXRApp
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import com.hyperxr.xr.UrlEditor
import java.io.File

/** Can HyperXR read (and write) shared storage? */
object StorageAccess {
    fun granted(ctx: android.content.Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Opens the system screen/dialog (it shows on the phone display; take the phone out to answer). */
    fun request(activity: android.app.Activity) = activity.runOnUiThread {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:${activity.packageName}")))
            } catch (_: Throwable) {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
            }
        } else activity.requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE), 41)
    }
}

/**
 * File manager: places, folders, sort, open with the built-in viewers (text, photo, PDF,
 * video/audio), install APK / .hyperx, and copy, cut, paste, rename, new folder and delete.
 */
class FilesPanel(private val host: MediaHost) : XrPanel(1.6f, 1.1f, title = "Arquivos") {
    private val app = HyperXRApp.instance
    private val external = Environment.getExternalStorageDirectory()
    private val places: List<Pair<String, File>> = buildList {
        add("Armazenamento" to external)
        add("Downloads" to File(external, Environment.DIRECTORY_DOWNLOADS))
        add("Câmera (DCIM)" to File(external, Environment.DIRECTORY_DCIM))
        add("Imagens" to File(external, Environment.DIRECTORY_PICTURES))
        add("Vídeos" to File(external, Environment.DIRECTORY_MOVIES))
        add("Músicas" to File(external, Environment.DIRECTORY_MUSIC))
        add("Documentos" to File(external, Environment.DIRECTORY_DOCUMENTS))
        // removable cards: the app-specific dir is <root>/Android/data/<pkg>/files
        app.getExternalFilesDirs(null).drop(1).filterNotNull().forEachIndexed { i, d ->
            val root = d.path.substringBefore("/Android/")
            add((if (i == 0) "Cartão SD" else "Cartão SD ${i + 1}") to File(root))
        }
        add("HyperXR (interno)" to app.filesDir)
    }

    private var cwd: File = external
    @Volatile private var entries: List<File> = emptyList()
    @Volatile private var listing = false
    @Volatile private var status: String? = null
    private var sort = FileSort.NAME
    private var descending = false
    private var page = 0
    private var selectMode = false
    private val selected = LinkedHashSet<String>()
    private var clipboard: List<File> = emptyList()
    private var cut = false
    private var confirmDelete = false
    private var renaming: File? = null
    private var creatingFolder = false
    private val editor = UrlEditor({ name -> finishRename(name) }, { dirty = true })
    @Volatile private var busy = false

    init {
        if (!StorageAccess.granted(app)) cwd = app.filesDir
        refresh()
    }

    private fun refresh() {
        listing = true
        val dir = cwd
        mediaIo.execute {
            val list = dir.listFiles()?.toList()
            entries = if (list == null) emptyList() else FileOps.sorted(list.filter { !it.name.startsWith(".") }, sort, descending)
            if (list == null && dir.exists()) status = if (StorageAccess.granted(app)) "Sem permissão para ler esta pasta" else "Permita o acesso aos arquivos"
            listing = false; dirty = true
        }
    }

    private fun navigate(dir: File) {
        cwd = dir; page = 0; selected.clear(); selectMode = false; confirmDelete = false; status = null
        refresh(); dirty = true
    }

    private fun open(f: File) {
        if (f.isDirectory) { navigate(f); return }
        val kind = FileKinds.kindOf(f.name)
        when (kind) {
            FileKind.APK -> host.openPanel(ApkPanel(host, f))
            FileKind.HYPERX -> installHyperx(f)
            FileKind.IMAGE, FileKind.VIDEO, FileKind.AUDIO, FileKind.TEXT, FileKind.PDF -> {
                val items = entries.filter { it.isFile }.map { MediaItem.of(it) }
                val idx = items.indexOfFirst { it.file == f }
                openViewer(host, items, idx.coerceAtLeast(0))?.let { host.openPanel(it) }
            }
            else -> mediaIo.execute {
                val head = ByteArray(4096)
                val n = try { f.inputStream().use { it.read(head) } } catch (_: Throwable) { -1 }
                if (n >= 0 && FileKinds.looksLikeText(head, n)) host.onGl { host.openPanel(TextViewerPanel(host, f)) }
                else { status = "Sem visualizador para ${FileKinds.label(kind).lowercase()} (${f.name})"; dirty = true }
            }
        }
    }

    private fun installHyperx(f: File) {
        status = "Verificando pacote..."
        mediaIo.execute {
            status = try {
                val (tmp, result) = app.packages.inspectUri(Uri.fromFile(f))
                if (!result.valid) { tmp.delete(); "Pacote inválido: ${result.errors.joinToString()}" }
                else { val p = app.packages.commitInstall(tmp, result); "Instalado: ${p.manifest.name} ${p.manifest.version} (veja na Biblioteca)" }
            } catch (t: Throwable) { "Falha: ${t.message}" }
            dirty = true
        }
    }

    private fun selectedFiles() = entries.filter { it.path in selected }

    private fun runOp(label: String, op: () -> String) {
        if (busy) return
        busy = true; status = "$label..."; dirty = true
        mediaIo.execute {
            status = try { op() } catch (t: Throwable) { "Falha: ${t.message}" }
            busy = false
            host.onGl { refresh() }
        }
    }

    private fun paste() {
        val files = clipboard; val dest = cwd; val move = cut
        runOp(if (move) "Movendo" else "Copiando") {
            var n = 0
            for (f in files) { if (move) FileOps.moveInto(f, dest) else FileOps.copyInto(f, dest); n++ }
            if (move) { clipboard = emptyList(); cut = false }
            "$n ${if (n == 1) "item" else "itens"} ${if (move) "movidos" else "copiados"}"
        }
    }

    private fun delete() {
        val files = selectedFiles()
        selected.clear(); selectMode = false; confirmDelete = false
        runOp("Apagando") {
            var n = 0
            for (f in files) if (f.deleteRecursively()) n++
            if (n == files.size) "$n apagado(s)" else "$n de ${files.size} apagados (alguns protegidos)"
        }
    }

    private fun startRename(f: File) {
        renaming = f
        editor.begin(f.name)
        host.openKeyboardFor(editor)
    }

    private fun finishRename(name: String) {
        val f = renaming ?: return
        renaming = null; creatingFolder = false
        host.closeKeyboard()
        selected.clear(); selectMode = false
        if (name == f.name) { dirty = true; return }
        runOp("Renomeando") { FileOps.rename(f, name.trim()); "Renomeado para ${name.trim()}" }
    }

    private fun newFolder() {
        val d = File(cwd, FileOps.uniqueName(cwd, "Nova pasta"))
        if (!d.mkdirs()) { status = "Não foi possível criar a pasta aqui"; dirty = true; return }
        creatingFolder = true
        entries = FileOps.sorted(entries + d, sort, descending)
        startRename(d)
    }

    override fun draw(c: PanelCanvas) {
        val gap = 10f
        var x = c.pad
        val top = c.y
        fun b(id: String, label: String, w: Float, accent: Boolean = false, enabled: Boolean = true, act: () -> Unit) {
            c.button(id, label, x, top, w, 70f, accent = accent, enabled = enabled) { act(); dirty = true }; x += w + gap
        }
        val parent = cwd.parentFile
        val canUp = parent != null && cwd != external && cwd != app.filesDir && parent.canRead()
        b("up", "Voltar", 140f, enabled = canUp) { parent?.let { navigate(it) } }
        val pathW = c.w - c.pad - x - (230f + 190f + 190f + 3 * gap)
        val ren = renaming
        if (ren != null) {
            fill.color = XrTheme.buttonPressed
            c.canvas.drawRoundRect(android.graphics.RectF(x, top, x + pathW, top + 70f), 22f, 22f, fill)
            c.label("Nome: " + editor.text + "|", x + 20f, top + 18f, 28f, XrTheme.text, maxWidth = pathW - 30f)
            x += pathW + gap
            b("ok", "Confirmar", 230f, accent = true) { finishRename(editor.text) }
            b("cancelren", "Cancelar", 190f) {
                if (creatingFolder) ren.delete()
                renaming = null; creatingFolder = false; editor.cancel(); host.closeKeyboard(); refresh()
            }
        } else {
            val shown = displayPath(cwd)
            c.label(shown, x + 10f, top + 18f, 28f, XrTheme.text, bold = true, maxWidth = pathW - 20f)
            x += pathW + gap
            val sortName = when (sort) { FileSort.NAME -> "Nome"; FileSort.DATE -> "Data"; FileSort.SIZE -> "Tamanho"; FileSort.TYPE -> "Tipo" }
            b("sort", "Ordenar: $sortName${if (descending) " ↓" else ""}", 230f) {
                if (!descending) descending = true else { descending = false; sort = FileSort.entries[(sort.ordinal + 1) % FileSort.entries.size] }
                if (sort == FileSort.DATE && !descending) descending = true
                refresh()
            }
            b("mkdir", "Nova pasta", 190f) { newFolder() }
            b("select", if (selectMode) "Concluir" else "Selecionar", 190f, accent = selectMode) {
                selectMode = !selectMode; if (!selectMode) selected.clear(); confirmDelete = false
            }
        }
        // places
        val listTop = top + 90f
        var py = listTop
        for ((i, pl) in places.withIndex()) {
            val active = cwd.path == pl.second.path || (pl.second != external && cwd.path.startsWith(pl.second.path + "/"))
            c.button("place$i", pl.first, c.pad, py, 290f, 64f, accent = active) { navigate(pl.second) }
            py += 72f
        }
        if (!StorageAccess.granted(app)) {
            c.button("grant", "Permitir acesso", c.pad, py + 10f, 290f, 64f, accent = true) { StorageAccess.request(host.activity); status = "Conceda o acesso na tela do celular e volte" }
        }
        // list
        val lx = c.pad + 310f
        val lw = c.w - c.pad - lx
        val rowH = 80f
        val perPage = ((c.h - 120f - listTop) / (rowH + 8f)).toInt().coerceAtLeast(1)
        val list = entries
        val pages = maxOf(1, (list.size + perPage - 1) / perPage)
        page = page.coerceIn(0, pages - 1)
        when {
            listing && list.isEmpty() -> c.label("Carregando...", lx + 20f, listTop + 20f, 28f, XrTheme.textDim)
            list.isEmpty() -> c.label(status ?: "Pasta vazia", lx + 20f, listTop + 20f, 28f, XrTheme.textDim, maxWidth = lw - 40f)
        }
        list.drop(page * perPage).take(perPage).forEachIndexed { i, f ->
            val y = listTop + i * (rowH + 8f)
            val sel = f.path in selected
            val kind = FileKinds.kindOf(f.name, f.isDirectory)
            c.button("f:${f.path}", "", lx, y, lw, rowH, accent = sel, onLong = {
                selectMode = true; selected += f.path; dirty = true
            }) {
                if (renaming != null) return@button
                if (selectMode) { if (!selected.remove(f.path)) selected += f.path; confirmDelete = false } else open(f)
            }
            val tagW = c.chip(tag(kind), lx + 16f, y + 20f, tagColor(kind))
            c.label(f.name, lx + 34f + tagW, y + 10f, 28f, XrTheme.text, maxWidth = lw - tagW - 60f)
            val detail = if (f.isDirectory) dateText(f.lastModified()) else "${FileKinds.formatSize(f.length())}  •  ${dateText(f.lastModified())}"
            c.label(detail, lx + 34f + tagW, y + 46f, 22f, XrTheme.textDim, maxWidth = lw - tagW - 60f)
        }
        // bottom bar
        x = c.pad
        val by = c.h - 100f
        fun bb(id: String, label: String, w: Float, accent: Boolean = false, enabled: Boolean = true, act: () -> Unit) {
            c.button(id, label, x, by, w, 76f, accent = accent, enabled = enabled && !busy) { act(); dirty = true }; x += w + gap
        }
        val sel = selectedFiles()
        if (sel.isNotEmpty()) {
            bb("copy", "Copiar", 150f) { clipboard = sel; cut = false; selected.clear(); selectMode = false; status = "${sel.size} na área de transferência" }
            bb("cut", "Recortar", 160f) { clipboard = sel; cut = true; selected.clear(); selectMode = false; status = "${sel.size} para mover" }
            bb("rename", "Renomear", 170f, enabled = sel.size == 1) { startRename(sel[0]) }
            bb("del", if (confirmDelete) "Confirmar exclusão" else "Apagar", if (confirmDelete) 260f else 150f, accent = confirmDelete) {
                if (confirmDelete) delete() else confirmDelete = true
            }
            c.label("${sel.size} selecionado(s) • ${FileKinds.formatSize(sel.sumOf { it.length() })}", x + 10f, by + 24f, 24f, XrTheme.textDim)
        } else if (clipboard.isNotEmpty()) {
            bb("paste", "${if (cut) "Mover" else "Colar"} aqui (${clipboard.size})", 280f, accent = true) { paste() }
            bb("clearclip", "Limpar", 140f) { clipboard = emptyList(); cut = false }
            status?.let { c.label(it, x + 10f, by + 24f, 24f, XrTheme.textDim, maxWidth = c.w - x - 380f) }
        } else {
            status?.let { c.label(it, x + 10f, by + 24f, 24f, XrTheme.textDim, maxWidth = c.w - x - 380f) }
                ?: c.label("${list.size} itens", x + 10f, by + 24f, 24f, XrTheme.textDim)
        }
        // paging
        val px = c.w - c.pad - 2 * 160f - gap
        c.button("pgprev", "Anterior", px, by, 160f, 76f, enabled = page > 0) { page--; dirty = true }
        c.button("pgnext", "Próxima", px + 160f + gap, by, 160f, 76f, enabled = page < pages - 1) { page++; dirty = true }
        if (pages > 1) c.label("${page + 1}/$pages", px - 90f, by + 24f, 24f, XrTheme.textDim)
    }

    private fun displayPath(f: File): String {
        val p = f.path
        return when {
            p.startsWith(app.filesDir.path) -> "HyperXR" + p.removePrefix(app.filesDir.path)
            p.startsWith(external.path) -> "Armazenamento" + p.removePrefix(external.path)
            else -> p
        }
    }

    private fun tag(k: FileKind) = when (k) {
        FileKind.FOLDER -> "PASTA"; FileKind.TEXT -> "TXT"; FileKind.IMAGE -> "IMG"; FileKind.VIDEO -> "VÍDEO"; FileKind.AUDIO -> "ÁUDIO"
        FileKind.APK -> "APK"; FileKind.HYPERX -> "HYPERX"; FileKind.OBB -> "OBB"; FileKind.ARCHIVE -> "ZIP"; FileKind.PDF -> "PDF"; FileKind.OTHER -> "ARQ"
    }

    private fun tagColor(k: FileKind) = when (k) {
        FileKind.FOLDER -> Color.rgb(196, 150, 60); FileKind.IMAGE -> Color.rgb(46, 150, 110); FileKind.VIDEO -> Color.rgb(196, 70, 80)
        FileKind.AUDIO -> Color.rgb(150, 90, 200); FileKind.APK, FileKind.OBB -> Color.rgb(70, 160, 80); FileKind.HYPERX -> XrTheme.accent
        FileKind.TEXT, FileKind.PDF -> Color.rgb(90, 120, 170); else -> Color.rgb(96, 98, 104)
    }

    override fun release() {
        if (renaming != null) host.closeKeyboard()
        super.release()
    }

    private companion object { val fill = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG) }
}
