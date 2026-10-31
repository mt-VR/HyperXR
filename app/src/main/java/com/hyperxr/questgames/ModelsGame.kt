package com.hyperxr.questgames

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import com.hyperxr.gltf.GltfLoader
import com.hyperxr.gltf.GltfModel
import com.hyperxr.gltf.ModelPiece
import com.hyperxr.gltf.ModelPieces
import com.hyperxr.input.GrabDrag
import com.hyperxr.math.Mat4
import com.hyperxr.math.Pose
import com.hyperxr.math.Quat
import com.hyperxr.math.Vec3
import com.hyperxr.rendering.GpuModel
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors

/**
 * Modelos 3D: the glTF / GLB files of a folder chosen on the phone, opened in the space around
 * you. A closed hand near a model grabs all of it (6DoF: it moves and turns with the hand); a
 * pinch on one of its pieces (each node of the file) pulls that piece off on its own. The menu
 * browses the folder and its subfolders, spreads a model into its pieces and puts it back
 * together, and scales or closes it.
 */
class ModelsGame(host: GameHost) : QuestGame(host) {
    override val title = "Modelos 3D"
    override val shadowRadius = 0f
    override val menu = ModelsMenu(this)

    class Entry(val name: String, val docId: String, val folder: Boolean, val size: Long)

    /** A model open in the room. Each piece has its own offset (model space, around the model's centre). */
    inner class Obj(val name: String, val model: GltfModel, val gpu: GpuModel, val pieces: List<ModelPiece>) {
        val pose = Pose()
        var scale = ModelPieces.fitScale(model, 0.45f)
        val offsets = Array(pieces.size) { Pose() }
        val center = Vec3((model.boundsMin.x + model.boundsMax.x) / 2, (model.boundsMin.y + model.boundsMax.y) / 2, (model.boundsMin.z + model.boundsMax.z) / 2)
        val radius = 0.5f * model.boundsMin.distanceTo(model.boundsMax)
        /** Per drawable part: centre-shift times the node's own transform. */
        val partLocal: Array<FloatArray> = Array(gpu.parts.size) { k ->
            val t = Mat4.identity(FloatArray(16)); t[12] = -center.x; t[13] = -center.y; t[14] = -center.z
            Mat4.multiply(t, gpu.parts[k].world, FloatArray(16))
        }
        val pieceOf = IntArray(gpu.parts.size).also { a -> pieces.forEachIndexed { pi, p -> p.parts.forEach { if (it < a.size) a[it] = pi } } }

        /** Piece [k]'s rigid pose in game space. */
        fun piecePose(k: Int, out: Pose): Pose {
            val o = offsets[k]
            tmpV.set(o.position).scale(scale)
            pose.transformPoint(tmpV, out.position)
            pose.orientation.mul(o.orientation, out.orientation)
            return out
        }

        /** Sets piece [k]'s offset from a game-space pose. */
        fun setPiecePose(k: Int, g: Pose) {
            val inv = pose.orientation.conjugate(tmpQ)
            tmpV.set(g.position).sub(pose.position)
            inv.rotate(tmpV, offsets[k].position).scale(1f / scale)
            inv.mul(g.orientation, offsets[k].orientation)
        }

        /** Distance (metres) from game point [p] to piece [k]. */
        fun distanceToPiece(k: Int, p: Vec3): Float {
            piecePose(k, tmpPose).inverse(tmpInv)
            tmpInv.transformPoint(p, tmpV).scale(1f / scale).add(center)
            return pieces[k].distanceTo(tmpV) * scale
        }

        fun release() = gpu.release()
    }

    private val tmpV = Vec3(); private val tmpQ = Quat(); private val tmpPose = Pose(); private val tmpInv = Pose()
    private val prefs = host.ctx.activity.getSharedPreferences("models3d", Context.MODE_PRIVATE)
    private val io = Executors.newSingleThreadExecutor()
    private val loaded = ConcurrentLinkedQueue<Pair<String, GltfModel>>()

    val open = ArrayList<Obj>()
    var selected: Obj? = null
    var tree: Uri? = prefs.getString("tree", null)?.let(Uri::parse)
    /** The folders entered from the chosen one: (document id, name). */
    val path = ArrayList<Pair<String, String>>()
    @Volatile var entries: List<Entry> = emptyList()
    @Volatile var progress = ""

    // grabbing: per hand, the model or (model, piece) it holds
    private val drags = arrayOf(GrabDrag(), GrabDrag())
    private val held = arrayOfNulls<Obj>(2)
    private val heldPiece = IntArray(2) { -1 }
    private val hand = Pose(); private val p = Vec3(); private val piece = Pose()
    private val hover = arrayOfNulls<Obj>(2); private val hoverPiece = IntArray(2) { -1 }
    private val m = FloatArray(16); private val col = FloatArray(4)

    override fun start() {
        status = if (tree == null) "Escolha uma pasta com arquivos .glb / .gltf no menu" else "Abra um modelo no menu. Mão fechada pega o modelo inteiro; pinça numa parte puxa só ela"
        tree?.let { list() }
    }

    fun chooseFolder() {
        val act = host.ctx.activity
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        com.hyperxr.xr.ActivityResults.launch(act, i) { data ->
            val uri = data?.data ?: return@launch
            runCatching { act.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            prefs.edit().putString("tree", uri.toString()).apply()
            tree = uri; path.clear(); list()
        }
    }

    private fun currentDoc(t: Uri) = path.lastOrNull()?.first ?: DocumentsContract.getTreeDocumentId(t)

    fun list() {
        val t = tree ?: return
        progress = "Lendo a pasta…"; menu.dirty = true
        io.execute {
            try {
                entries = children(t, currentDoc(t)).filter { it.folder || isModel(it.name) }
                    .sortedWith(compareByDescending<Entry> { it.folder }.thenBy { it.name.lowercase() })
                progress = if (entries.isEmpty()) "Nenhum .glb ou .gltf aqui" else ""
            } catch (e: Throwable) {
                entries = emptyList(); progress = "Sem acesso à pasta (${e.message}); escolha de novo"
            }
            menu.dirty = true
        }
    }

    private fun children(t: Uri, doc: String): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(t, doc)
        val cols = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE, DocumentsContract.Document.COLUMN_SIZE)
        val out = ArrayList<Entry>()
        host.ctx.activity.contentResolver.query(uri, cols, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                out += Entry(c.getString(1) ?: "?", c.getString(0), c.getString(2) == DocumentsContract.Document.MIME_TYPE_DIR, if (c.isNull(3)) 0L else c.getLong(3))
            }
        }
        return out
    }

    fun enter(e: Entry) { path += e.docId to e.name; list() }
    fun up() { if (path.isNotEmpty()) { path.removeAt(path.size - 1); list() } }

    fun openModel(e: Entry) {
        val t = tree ?: return
        if (e.size > MAX_BYTES) { progress = "${e.name} é grande demais (${e.size / 1_000_000} MB)"; menu.dirty = true; return }
        progress = "Abrindo ${e.name}…"; menu.dirty = true
        val folder = currentDoc(t)
        io.execute {
            try {
                val cr = host.ctx.activity.contentResolver
                fun read(docId: String) = cr.openInputStream(DocumentsContract.buildDocumentUriUsingTree(t, docId))!!.use { it.readBytes() }
                val bytes = read(e.docId)
                // a .gltf points at its .bin and textures next to it in the same folder
                val siblings by lazy { children(t, folder).associateBy { it.name } }
                val model = GltfLoader.load(bytes) { name ->
                    val clean = Uri.decode(name).substringAfterLast('/')
                    read(siblings[clean]?.docId ?: throw com.hyperxr.gltf.GltfException("$clean não está na pasta"))
                }
                loaded += e.name.substringBeforeLast('.') to model
                progress = ""
            } catch (t2: Throwable) { progress = "Não deu para abrir ${e.name}: ${t2.message}" }
            menu.dirty = true
        }
    }

    private fun place(o: Obj) {
        // in front of the eyes, a little below, turned to face them
        val f = host.dirToGame(host.ctx.input.head.orientation.rotate(Vec3(0f, 0f, -1f)), Vec3())
        f.y = 0f; if (f.length() < 1e-3f) f.set(0f, 0f, -1f); f.normalize()
        val n = open.size
        o.pose.position.set(host.head).addScaled(f, 0.65f); o.pose.position.y -= 0.25f
        o.pose.position.addScaled(Vec3(-f.z, 0f, f.x), (n % 3 - 1) * 0.35f)
        Quat.fromAxisAngle(0f, 1f, 0f, kotlin.math.atan2(-f.x, -f.z), o.pose.orientation)
    }

    fun explode(o: Obj) { for (k in o.pieces.indices) { ModelPieces.explodeOffset(o.pieces[k], o.model, 0.9f, o.offsets[k].position); o.offsets[k].orientation.identity() } }
    fun assemble(o: Obj) { for (k in o.pieces.indices) { o.offsets[k].position.set(0f, 0f, 0f); o.offsets[k].orientation.identity() } }
    fun resize(o: Obj, f: Float) { o.scale = (o.scale * f).coerceIn(1e-4f, 1e4f) }
    fun close(o: Obj) { for (i in 0..1) if (held[i] === o) { held[i] = null; drags[i].end() }; open.remove(o); o.release(); if (selected === o) selected = open.lastOrNull(); menu.dirty = true }

    override fun update(dt: Float) {
        while (true) {
            val (name, model) = loaded.poll() ?: break
            try {
                val o = Obj(name, model, GpuModel(model), ModelPieces.of(model))
                place(o); open += o; selected = o
                status = "$name: ${o.pieces.size} partes. Mão fechada pega o modelo; pinça numa parte puxa só ela"
            } catch (t: Throwable) { progress = "Não deu para mostrar $name: ${t.message}" }
            menu.dirty = true
        }
        for (i in 0..1) hand(i)
    }

    private fun hand(i: Int) {
        val g = host.hands[i]
        hover[i] = null; hoverPiece[i] = -1
        val o = held[i]
        if (o != null) {
            val keep = if (heldPiece[i] >= 0) g.pinch else g.fist
            if (!keep || !g.usable || !host.handPose(i, hand)) { held[i] = null; heldPiece[i] = -1; drags[i].end(); return }
            if (heldPiece[i] >= 0) { drags[i].update(hand, piece); o.setPiecePose(heldPiece[i], piece) }
            else drags[i].update(hand, o.pose)
            return
        }
        if (!g.usable || host.uiBusy[i] || !host.pinchPoint(i, p)) return
        // the nearest piece under the pinch
        var best: Obj? = null; var bestK = -1; var bestD = PIECE_REACH
        for (ob in open) for (k in ob.pieces.indices) {
            val d = ob.distanceToPiece(k, p)
            if (d < bestD) { bestD = d; best = ob; bestK = k }
        }
        hover[i] = best; hoverPiece[i] = bestK
        if (g.pinch && best != null && host.handPose(i, hand) && held[1 - i]?.let { it === best && heldPiece[1 - i] == bestK } != true) {
            best.piecePose(bestK, piece)
            drags[i].begin(hand, piece); held[i] = best; heldPiece[i] = bestK; selected = best; menu.dirty = true
            host.play(host.sTick, p.x, p.y, p.z, 0.4f)
            return
        }
        if (g.fist) {
            // the whole model: a closed hand near it
            val near = open.minByOrNull { it.pose.position.distanceTo(g.palm) - it.radius * it.scale }
            if (near != null && near.pose.position.distanceTo(g.palm) < near.radius * near.scale + GRAB_REACH && held[1 - i] !== near && host.handPose(i, hand)) {
                drags[i].begin(hand, near.pose); held[i] = near; heldPiece[i] = -1; selected = near; menu.dirty = true
            }
        }
    }

    override fun draw() {
        for (o in open) {
            for (k in o.gpu.parts.indices) {
                val part = o.gpu.parts[k]
                val pk = o.pieceOf[k]
                o.piecePose(pk, tmpPose)
                val lit = (hover[0] === o && hoverPiece[0] == pk) || (hover[1] === o && hoverPiece[1] == pk) ||
                    (held[0] === o && heldPiece[0] == pk) || (held[1] === o && heldPiece[1] == pk)
                part.color.copyInto(col)
                if (lit) { col[0] = minOf(1f, col[0] * 1.25f + 0.08f); col[1] = minOf(1f, col[1] * 1.25f + 0.08f); col[2] = minOf(1f, col[2] * 1.25f + 0.12f) }
                val d = host.drawMesh(part.mesh, tmpPose.position.x, tmpPose.position.y, tmpPose.position.z, o.scale, o.scale, o.scale, col,
                    orientation = tmpPose.orientation, gloss = 0.3f, shadow = false)
                d.texture = part.texture
                d.doubleSided = part.doubleSided
                d.transparent = part.blend || col[3] < 0.999f
                Mat4.multiply(d.matrix, o.partLocal[k], m)
                m.copyInto(d.matrix)
            }
            if (o === selected && open.size > 1) host.drawSphere(o.pose.position.x, o.pose.position.y + o.radius * o.scale + 0.04f, o.pose.position.z, 0.008f, XrThemeAccent, unlit = true, shadow = false)
        }
    }

    override fun release() { open.forEach { it.release() }; open.clear(); io.shutdownNow() }

    companion object {
        const val MAX_BYTES = 150_000_000L
        const val PIECE_REACH = 0.025f
        const val GRAB_REACH = 0.08f
        private val XrThemeAccent = floatArrayOf(0.3f, 0.6f, 1f, 1f)
        fun isModel(name: String) = name.lowercase().let { it.endsWith(".glb") || it.endsWith(".gltf") || it.endsWith(".vrm") }
    }
}

/** The menu: the chosen folder (and its subfolders) and the models open in the room. */
class ModelsMenu(private val game: ModelsGame) : XrPanel(0.95f, 1.05f, title = "Modelos 3D") {
    private var tab = 0
    private var page = 0
    private var tick = 0f
    init { closable = false }

    override fun update(dt: Float) { tick += dt; if (tick > 0.5f) { tick = 0f; dirty = true } }

    override fun draw(c: PanelCanvas) {
        val tw = (c.w - 2 * c.pad - 12f) / 2f
        c.button("t0", "Pasta", c.pad, c.pad, tw, 70f, accent = tab == 0) { tab = 0; dirty = true }
        c.button("t1", "Abertos (${game.open.size})", c.pad + tw + 12f, c.pad, tw, 70f, accent = tab == 1) { tab = 1; dirty = true }
        c.y = c.pad + 96f
        if (tab == 0) folder(c) else opened(c)
    }

    private fun folder(c: PanelCanvas) {
        if (game.tree == null) {
            c.paragraph("Escolha uma pasta do celular com modelos .glb, .gltf ou .vrm. Ela fica guardada para a próxima vez.", 28f)
            c.button("choose", "Escolher pasta", c.pad, c.y, c.w - 2 * c.pad, 80f, accent = true) { game.chooseFolder() }
            return
        }
        val where = (listOf("Pasta") + game.path.map { it.second }).joinToString(" / ")
        c.label(where, c.pad, c.y, 26f, XrTheme.textDim, maxWidth = c.w - 2 * c.pad, maxLines = 1); c.y += 46f
        val per = 7
        val all = game.entries
        val pages = maxOf(1, (all.size + per - 1) / per)
        page = page.coerceIn(0, pages - 1)
        for (e in all.drop(page * per).take(per)) {
            if (e.folder) c.row("d:${e.docId}", e.name + " /", detail = "pasta") { page = 0; game.enter(e) }
            else c.row("f:${e.docId}", e.name, detail = if (e.size > 0) "%.1f MB".format(e.size / 1e6) else null) { game.openModel(e) }
        }
        val y = c.h - 100f
        val bw = 110f
        c.button("up", "Voltar", c.pad, y, 170f, 76f, enabled = game.path.isNotEmpty()) { page = 0; game.up() }
        c.button("prev", "<", c.pad + 182f, y, bw, 76f, enabled = page > 0) { page--; dirty = true }
        c.button("next", ">", c.pad + 182f + bw + 12f, y, bw, 76f, enabled = page < pages - 1) { page++; dirty = true }
        c.button("choose", "Outra pasta", c.pad + 182f + 2 * (bw + 12f), y, c.w - 2 * c.pad - 182f - 2 * (bw + 12f), 76f) { game.chooseFolder() }
        c.label(game.progress, c.pad, y - 46f, 24f, XrTheme.textDim, maxWidth = c.w - 2 * c.pad, maxLines = 1)
    }

    private fun opened(c: PanelCanvas) {
        if (game.open.isEmpty()) { c.paragraph("Nenhum modelo aberto. Abra um na aba Pasta.", 28f); return }
        for (o in game.open.takeLast(5)) c.row("o:${System.identityHashCode(o)}", o.name, accent = o === game.selected, detail = "${o.pieces.size} partes") { game.selected = o; dirty = true }
        val o = game.selected ?: return
        c.y += 10f
        val half = (c.w - 2 * c.pad - 12f) / 2f
        c.button("explode", "Separar partes", c.pad, c.y, half, 76f) { game.explode(o) }
        c.button("assemble", "Montar de novo", c.pad + half + 12f, c.y, half, 76f) { game.assemble(o) }
        c.y += 88f
        c.button("bigger", "Maior", c.pad, c.y, half, 76f) { game.resize(o, 1.25f) }
        c.button("smaller", "Menor", c.pad + half + 12f, c.y, half, 76f) { game.resize(o, 0.8f) }
        c.y += 88f
        c.button("close", "Fechar este modelo", c.pad, c.y, c.w - 2 * c.pad, 76f) { game.close(o) }
        c.y += 96f
        c.paragraph("Mão fechada perto do modelo: pega e gira ele inteiro. Pinça numa parte: puxa só aquela parte.", 24f)
    }
}
