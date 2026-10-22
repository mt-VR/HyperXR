package com.hyperxr.appworld

import com.hyperxr.launcher.AppKind
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import com.hyperxr.xr.LauncherActions
import com.hyperxr.xr.UrlEditor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** The notification letters opened: each one with the app's own buttons, quick reply and dismiss. */
class NotificationPanel(private val world: AppWorld) : XrPanel(1.2f, 1.0f, title = "Notificações") {
    private var seen = -1
    private var page = 0
    private var replying: Pair<WorldNotification, WorldNotification.Action>? = null
    private val editor = UrlEditor({ text -> sendReply(text) }, { dirty = true })
    private val fmt = SimpleDateFormat("HH:mm", Locale.getDefault())

    override fun update(dt: Float) { if (NotificationHub.version != seen) { seen = NotificationHub.version; dirty = true } }

    private fun sendReply(text: String) {
        val (_, a) = replying ?: return
        NotificationHub.run(world.activity(), a, text)
        replying = null
        world.closeKeyboard()
        dirty = true
    }

    override fun draw(c: PanelCanvas) {
        val r = replying
        if (r != null) {
            c.paragraph("Responder a ${r.first.appLabel}: ${r.first.title}", 28f, XrTheme.text, bold = true)
            c.paragraph(editor.text + "|", 32f, XrTheme.text)
            c.button("send", "Enviar", c.pad, c.h - 110f, 240f, 80f, accent = true) { sendReply(editor.text) }
            c.button("cancel", "Cancelar", c.pad + 260f, c.h - 110f, 240f, 80f) { replying = null; editor.cancel(); world.closeKeyboard() }
            return
        }
        val list = NotificationHub.list()
        if (list.isEmpty()) { c.paragraph("Nenhuma notificação.", 30f); return }
        val per = 4
        val pages = (list.size + per - 1) / per
        page = page.coerceIn(0, pages - 1)
        for (n in list.drop(page * per).take(per)) {
            val top = c.y
            c.label("${n.appLabel} · ${fmt.format(Date(n.time))}", c.pad, top, 22f, XrTheme.textDim)
            c.label(n.title, c.pad, top + 30f, 28f, XrTheme.text, bold = true)
            c.label(n.text.take(140), c.pad, top + 66f, 24f, XrTheme.textDim, maxWidth = c.w - 2 * c.pad)
            var x = c.pad
            val by = top + 130f
            n.contentIntent?.let { pi -> c.button("open:${n.key}", "Abrir", x, by, 150f, 56f) { try { pi.send() } catch (_: Throwable) {} }; x += 160f }
            for ((i, a) in n.actions.take(3).withIndex()) {
                val w = 200f
                c.button("act:${n.key}:$i", a.title.ifEmpty { "Ação" }, x, by, w, 56f, accent = a.remoteInputs != null) {
                    if (a.remoteInputs != null) { replying = n to a; editor.begin(""); world.openKeyboardFor(editor) }
                    else NotificationHub.run(world.activity(), a, null)
                }
                x += w + 10f
            }
            c.button("dismiss:${n.key}", "Dispensar", c.w - c.pad - 180f, by, 180f, 56f) { NotificationHub.dismiss(n.key) }
            c.y = by + 76f
        }
        if (pages > 1) {
            c.button("prev", "Anterior", c.pad, c.h - 96f, 200f, 70f, enabled = page > 0) { page-- }
            c.button("next", "Próxima", c.w - c.pad - 200f, c.h - 96f, 200f, 70f, enabled = page < pages - 1) { page++ }
        }
    }
}

/** App-to-World settings: access, which objects are in the world, compatible apps and portals. */
class AppWorldPanel(private val world: AppWorld, private val a: LauncherActions) : XrPanel(1.3f, 1.1f, title = "App-to-World") {
    private var page = 0
    private var tab = 0

    override fun draw(c: PanelCanvas) {
        val act = world.activity()
        val access = HxNotificationListener.enabled(act)
        c.paragraph("Apps viram objetos no espaço: a música vira uma caixa de som, notificações viram cartas, arquivos viram livros numa estante e qualquer app vira um portal que abre o app e recebe arquivos soltos nele.", 24f)
        c.button("access", if (access) "Acesso às notificações: ativo" else "Permitir acesso às notificações", c.pad, c.y, 560f, 64f, accent = !access) {
            act.runOnUiThread { HxNotificationListener.openSettings(act) }
        }
        c.button("organize", if (world.organize) "Organizar: ligado (segure e arraste)" else "Organizar objetos", c.pad + 580f, c.y, c.w - 2 * c.pad - 580f, 64f, accent = world.organize) { world.organize = !world.organize }
        c.y += 84f
        var x = c.pad
        for ((i, t) in listOf("Objetos do sistema", "Apps compatíveis", "Portais de apps").withIndex()) {
            c.button("tab$i", t, x, c.y, 360f, 60f, accent = tab == i) { tab = i; page = 0 }
            x += 370f
        }
        c.y += 80f
        when (tab) {
            0 -> for ((key, label) in listOf("music" to "Caixa de som (app de música tocando)", "notifications" to "Cartas (notificações)", "shelf" to "Estante (arquivos recentes)")) {
                c.toggle("sys:$key", label, world.has(key)) { world.toggle(key); dirty = true }
            }
            1 -> {
                val manifests = SpatialRegistry.also { it.invalidate() }.discover(act)
                if (manifests.isEmpty()) c.paragraph("Nenhum app instalado declara objetos para o HyperXR ainda. Desenvolvedores: docs/APP_TO_WORLD.md.", 24f)
                for (m in manifests) for (o in m.objects) {
                    val key = "app:${m.packageName}:${o.id}"
                    c.toggle(key, "${o.label} (${m.packageName})", world.has(key)) { world.toggle(key); dirty = true }
                }
            }
            2 -> {
                val apps = a.allApps().filter { it.kind != AppKind.UNAVAILABLE }
                val per = 6
                val pages = (apps.size + per - 1) / per
                page = page.coerceIn(0, (pages - 1).coerceAtLeast(0))
                for (e in apps.drop(page * per).take(per)) {
                    val key = "portal:${e.id}"
                    c.row(key, (if (world.has(key)) "Remover portal: " else "Colocar no mundo: ") + e.title, accent = world.has(key), icon = a.appIcon(e, 96)) { world.toggle(key); dirty = true }
                }
                c.button("pp", "Anterior", c.pad, c.h - 96f, 200f, 70f, enabled = page > 0) { page-- }
                c.button("pn", "Próxima", c.w - c.pad - 200f, c.h - 96f, 200f, 70f, enabled = page < pages - 1) { page++ }
            }
        }
    }
}

