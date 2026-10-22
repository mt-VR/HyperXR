package com.hyperxr.xr

import com.hyperxr.math.Vec3
import com.hyperxr.rendering.FrameScene
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A window onto the real room while in VR: the camera image (the one hand tracking already uses)
 * inside a movable panel, so the desk, the phone or a person who walked in can be seen without
 * leaving the environment. Drag it by the title bar like any window.
 */
class RealityWindow(private val ctx: XrContext) : XrPanel(0.8f, 0.56f, title = "Mundo real") {
    private val area = android.graphics.RectF()

    override fun draw(c: PanelCanvas) {
        area.set(c.pad, c.y, c.w - c.pad, c.h - c.pad)
        val cam = ctx.camera
        if (cam == null || !cam.hasFrame) {
            c.paragraph("A câmera não está ligada neste modo. Ligue o hand tracking (Ajustes rápidos) ou use realidade mista.", 26f)
        }
    }

    override fun submit(scene: FrameScene) {
        super.submit(scene)
        val cam = ctx.camera ?: return
        if (!visible || !cam.hasFrame || area.width() <= 0f) return
        // fit the camera image (its own aspect) into the content area, just in front of the panel
        val ppm = pixelsPerMeter
        val aw = area.width() / ppm; val ah = area.height() / ppm
        val (w, h) = if (aw / ah > cam.imageAspect) ah * cam.imageAspect to ah else aw to aw / cam.imageAspect
        val cx = (area.centerX() - pxW / 2f) / ppm
        val cy = (pxH / 2f - area.centerY()) / ppm
        val p = scene.addPanel()
        p.texture = cam.textureId; p.oes = true; p.texMatrix = cam.texMatrix
        p.pose.set(pose)
        p.pose.position.add(pose.orientation.rotate(Vec3(cx, cy, 0.004f)))
        p.width = w; p.height = h
        p.radius = 0.02f
    }
}

/**
 * Workspaces: the set of open windows (browser tabs, Android apps, web apps) saved under a name
 * and reopened together, e.g. "trabalho" or "cinema".
 */
class Workspaces(dir: File) {
    private val root = File(dir, "workspaces").apply { mkdirs() }
    private val bad = Regex("[^\\p{L}\\p{N} _-]")

    fun list(): List<String> = root.listFiles().orEmpty().filter { it.extension == "json" }.sortedByDescending { it.lastModified() }.map { it.nameWithoutExtension }
    fun file(name: String) = File(root, bad.replace(name.trim(), "").take(40).ifEmpty { "espaço" } + ".json")
    fun save(name: String, json: String) = file(name).writeText(json)
    fun load(name: String): String? = file(name).takeIf { it.isFile }?.readText()
    fun delete(name: String) = file(name).delete()
    fun savedAt(name: String): Long = file(name).lastModified()
}

/** Saved workspaces: save the current windows under a name, reopen or delete. */
class WorkspacesPanel(private val ws: Workspaces, private val host: WorkspaceHost) : XrPanel(1.1f, 0.95f, title = "Espaços de trabalho") {
    private val editor = UrlEditor({ name -> finishSave(name) }, { dirty = true })
    private var status = ""
    private val fmt = SimpleDateFormat("dd/MM HH:mm", Locale.getDefault())

    private fun finishSave(name: String) {
        host.closeKeyboard()
        if (name.isBlank()) return
        status = if (host.saveWorkspace(name)) "Salvo: $name" else "Abra janelas antes de salvar um espaço"
        dirty = true
    }

    override fun draw(c: PanelCanvas) {
        c.paragraph("Um espaço guarda as janelas abertas (abas do navegador, apps Android) e reabre todas juntas. Também por voz: \"salvar espaço cinema\", \"abrir espaço cinema\".", 24f)
        if (editor.active) {
            c.paragraph("Nome: " + editor.text + "|", 30f, XrTheme.text, bold = true)
            c.button("ok", "Salvar", c.pad, c.y, 220f, 70f, accent = true) { finishSave(editor.text) }
            c.button("cancel", "Cancelar", c.pad + 240f, c.y, 220f, 70f) { editor.cancel(); host.closeKeyboard() }
            c.y += 90f
        } else c.row("new", "Salvar as janelas atuais como espaço", accent = true) { editor.begin(""); host.openKeyboard(editor) }
        if (status.isNotEmpty()) c.paragraph(status, 24f, XrTheme.accent2)
        val list = ws.list()
        if (list.isEmpty()) c.paragraph("Nenhum espaço salvo ainda.", 26f)
        for (name in list.take(6)) {
            val y = c.y
            c.button("open:$name", name, c.pad, y, c.w - 2 * c.pad - 200f, 80f) { host.openWorkspace(name) }
            c.label(fmt.format(Date(ws.savedAt(name))), c.w - c.pad - 380f, y + 26f, 22f, XrTheme.textDim)
            c.button("del:$name", "Apagar", c.w - c.pad - 190f, y, 190f, 80f) { ws.delete(name); dirty = true }
            c.y += 94f
        }
    }
}

interface WorkspaceHost {
    fun saveWorkspace(name: String): Boolean
    fun openWorkspace(name: String)
    fun openKeyboard(target: com.hyperxr.windows.KeyTarget)
    fun closeKeyboard()
}
