package com.hyperxr.xr

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import com.hyperxr.HyperXRApp
import com.hyperxr.files.MediaHost
import com.hyperxr.social.Calls
import com.hyperxr.social.Friend
import com.hyperxr.social.Profile
import com.hyperxr.social.Social
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme

/** White Material Symbols glyphs (res/drawable-nodpi/glyph_*.png, from tools/make_glyphs.py). */
object Glyphs {
    private val cache = HashMap<String, Bitmap>()
    fun get(name: String): Bitmap = synchronized(cache) {
        cache.getOrPut(name) {
            val ctx = HyperXRApp.instance
            val id = ctx.resources.getIdentifier("glyph_$name", "drawable", ctx.packageName)
            if (id != 0) BitmapFactory.decodeResource(ctx.resources, id, BitmapFactory.Options().apply { inScaled = false })
            else Bitmap.createBitmap(4, 4, Bitmap.Config.ARGB_8888)
        }
    }
}

private val avatarPaint = Paint(Paint.ANTI_ALIAS_FLAG)

/** Round avatar: the profile's colour with its initial (no photos are uploaded). */
internal fun PanelCanvas.avatar(p: Profile?, x: Float, top: Float, size: Float, online: Boolean = false) {
    val hue = (p?.color ?: 210).toFloat()
    avatarPaint.color = Color.HSVToColor(floatArrayOf(hue, 0.55f, 0.75f))
    canvas.drawCircle(x + size / 2, top + size / 2, size / 2, avatarPaint)
    val initial = (p?.name?.firstOrNull() ?: '?').uppercaseChar().toString()
    avatarPaint.color = Color.WHITE; avatarPaint.textSize = size * 0.48f; avatarPaint.typeface = XrTheme.medium
    canvas.drawText(initial, x + size / 2 - avatarPaint.measureText(initial) / 2, top + size * 0.66f, avatarPaint)
    if (online) {
        avatarPaint.color = Color.rgb(36, 37, 40); canvas.drawCircle(x + size * 0.86f, top + size * 0.86f, size * 0.17f, avatarPaint)
        avatarPaint.color = XrTheme.ok; canvas.drawCircle(x + size * 0.86f, top + size * 0.86f, size * 0.12f, avatarPaint)
    }
}

/** Opens the 2D settings on a section (account set-up is easier with the phone keyboard). */
internal fun openSettingsSection(host: MediaHost, section: String) {
    host.activity.runOnUiThread {
        host.activity.startActivity(Intent(host.activity, com.hyperxr.ui2d.MainActivity::class.java)
            .putExtra(com.hyperxr.ui2d.MainActivity.EXTRA_SECTION, section).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

/**
 * Pessoas: your profile, new messages, friends with what they are doing, call and message
 * buttons, friend requests, and adding friends by user name. Live through Supabase.
 */
class PeoplePanel(private val a: LauncherActions, private val host: MediaHost) : XrPanel(0.74f, 1.12f, title = "") {
    private val add = UrlEditor({ name -> a.closeKeyboard(); if (name.isNotBlank()) { Social.addFriend(name); note = "Pedido enviado para @${name.trim().removePrefix("@")}" }; dirty = true }, { dirty = true })
    private var page = 0
    private var note: String? = null
    private val listener: () -> Unit = { dirty = true }

    init { Social.addListener(listener); Calls.addListener(listener); Social.start() }

    override fun release() { Social.removeListener(listener); Calls.removeListener(listener); super.release() }

    override fun draw(c: PanelCanvas) {
        val me = Social.me
        if (!Social.sb.signedIn || me == null) {
            c.label("Pessoas", c.pad, c.y, 46f, XrTheme.text, bold = true)
            c.y += 80f
            c.paragraph(if (!Social.sb.signedIn) "Entre numa conta HyperMagic para conversar, ligar e mostrar a tela para os seus amigos."
                else "Escolha o seu nome de usuário para os amigos te acharem.", 28f)
            c.paragraph("Faça isso nas configurações 2D (com o teclado do celular): Pessoas.", 24f)
            c.row("setup", "Abrir as configurações de Pessoas", accent = true) { openSettingsSection(host, "PEOPLE") }
            Social.lastError?.let { c.paragraph(it, 22f, XrTheme.danger) }
            return
        }
        // header: avatar, name, online
        c.avatar(me, c.pad, c.y, 96f, online = true)
        c.label(me.name, c.pad + 120f, c.y + 6f, 38f, XrTheme.text, bold = true, maxLines = 1, maxWidth = c.w - c.pad * 2 - 260f)
        c.label("Online agora · nível ${me.level}", c.pad + 120f, c.y + 54f, 24f, XrTheme.ok, maxLines = 1)
        c.chip("@${me.username}", c.w - c.pad - 200f, c.y + 26f, XrTheme.button)
        c.y += 130f
        // new messages
        val unread = Social.unreadTotal
        val firstUnread = Social.unread.keys.firstOrNull()
        c.button("newmsg", "Nova mensagem", c.pad, c.y, c.w - 2 * c.pad, 76f) {
            val f = Social.friends.firstOrNull { it.profile.id == firstUnread } ?: Social.friends.firstOrNull { it.state == Friend.State.FRIEND }
            if (f != null) host.openPanel(ChatPanel(a, host, f.profile), 1.2f) else note = "Adicione um amigo primeiro"
        }
        if (unread > 0) { avatarPaint.color = XrTheme.accent; c.canvas.drawCircle(c.w - c.pad - 40f, c.y + 38f, 22f, avatarPaint); c.label("$unread", c.w - c.pad - 40f - (if (unread > 9) 15f else 8f), c.y + 22f, 26f, Color.WHITE, bold = true) }
        c.y += 96f
        // friends
        val list = Social.friends
        val rowH = 108f
        val per = ((c.h - c.y - 150f) / rowH).toInt().coerceAtLeast(1)
        val pages = maxOf(1, (list.size + per - 1) / per)
        page = page.coerceIn(0, pages - 1)
        if (list.isEmpty()) c.paragraph("Nenhum amigo ainda. Toque em Adicionar amigo e digite o usuário (@nome) de alguém.", 24f)
        for (f in list.drop(page * per).take(per)) friendRow(c, f, rowH)
        if (pages > 1) {
            c.button("pprev", "▲", c.w - c.pad - 130f, c.h - 236f, 60f, 56f, enabled = page > 0) { page-- }
            c.button("pnext", "▼", c.w - c.pad - 60f, c.h - 236f, 60f, 56f, enabled = page < pages - 1) { page++ }
        }
        // bottom: add friend + status line
        (note ?: Social.lastError ?: Calls.info.takeIf { it.isNotEmpty() })?.let { c.label(it, c.pad, c.h - 170f, 22f, XrTheme.accent2, maxLines = 2) }
        val by = c.h - 110f
        c.button("add", if (add.active) "@" + add.text + "|" else "Adicionar amigo", c.pad, by, c.w - 2 * c.pad - 100f, 80f, accent = add.active, icon = Glyphs.get("person_add")) {
            if (!add.active) { add.begin(""); a.openKeyboard(add) }
        }
        c.roundIconButton("search", Glyphs.get("search"), c.w - c.pad - 84f, by, 80f) { if (!add.active) { add.begin(""); a.openKeyboard(add) } }
    }

    private fun friendRow(c: PanelCanvas, f: Friend, rowH: Float) {
        val p = f.profile
        val top = c.y
        val id = "f:${p.id}"
        if (isHovered(id)) { avatarPaint.color = XrTheme.buttonHover; c.canvas.drawRoundRect(RectF(c.pad - 10f, top - 6f, c.w - c.pad + 10f, top + rowH - 10f), 20f, 20f, avatarPaint) }
        addRegion(Region(RectF(c.pad, top, c.w - c.pad - 200f, top + rowH - 12f), id, { if (f.state == Friend.State.FRIEND) host.openPanel(ChatPanel(a, host, p), 1.2f) }))
        c.avatar(p, c.pad, top + 6f, 78f, online = p.online && f.state == Friend.State.FRIEND)
        c.label(p.name, c.pad + 100f, top + 8f, 30f, XrTheme.text, bold = true, maxLines = 1, maxWidth = c.w - c.pad * 2 - 300f)
        val sub = when (f.state) {
            Friend.State.FRIEND -> p.activity()
            Friend.State.REQUEST_IN -> "Quer ser seu amigo"
            Friend.State.REQUEST_OUT -> "Pedido enviado"
        }
        c.label(sub, c.pad + 100f, top + 50f, 22f, if (p.online && f.state == Friend.State.FRIEND) XrTheme.ok else XrTheme.textDim, maxLines = 1, maxWidth = c.w - c.pad * 2 - 300f)
        val bx = c.w - c.pad - 76f
        when (f.state) {
            Friend.State.FRIEND -> {
                c.roundIconButton("call:${p.id}", Glyphs.get("call"), bx, top + 10f, 70f, enabled = Calls.state == Calls.State.IDLE) { Calls.call(p) }
                c.roundIconButton("chat:${p.id}", Glyphs.get("chat"), bx - 84f, top + 10f, 70f) { host.openPanel(ChatPanel(a, host, p), 1.2f) }
            }
            Friend.State.REQUEST_IN -> {
                c.roundIconButton("ok:${p.id}", Glyphs.get("check"), bx, top + 10f, 70f, accent = true) { Social.acceptFriend(p.id) }
                c.roundIconButton("no:${p.id}", Glyphs.get("close"), bx - 84f, top + 10f, 70f) { Social.removeFriend(p.id) }
            }
            Friend.State.REQUEST_OUT -> c.roundIconButton("cancel:${p.id}", Glyphs.get("close"), bx, top + 10f, 70f) { Social.removeFriend(p.id) }
        }
        c.y += rowH
    }
}

/** A conversation with one friend: messages as bubbles, typing with the XR keyboard, call. */
class ChatPanel(private val a: LauncherActions, private val host: MediaHost, private val friend: Profile) : XrPanel(0.95f, 1.1f, title = "") {
    private val input: UrlEditor = UrlEditor({ text -> if (text.isNotBlank()) Social.send(friend.id, text); reopen() }, { dirty = true })

    private fun reopen() { input.begin(""); dirty = true }
    private val listener: () -> Unit = { dirty = true }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG)

    init { Social.addListener(listener); Social.openChat = friend.id; Social.loadConversation(friend.id) }
    override fun release() { Social.removeListener(listener); if (Social.openChat == friend.id) Social.openChat = null; super.release() }

    override fun draw(c: PanelCanvas) {
        val p = Social.profile(friend.id) ?: friend
        c.avatar(p, c.pad, c.y, 84f, online = p.online)
        c.label(p.name, c.pad + 106f, c.y + 4f, 34f, XrTheme.text, bold = true, maxLines = 1, maxWidth = c.w - 400f)
        c.label(p.activity(), c.pad + 106f, c.y + 48f, 22f, if (p.online) XrTheme.ok else XrTheme.textDim, maxLines = 1)
        c.roundIconButton("call", Glyphs.get("call"), c.w - c.pad - 160f, c.y + 4f, 74f, enabled = Calls.state == Calls.State.IDLE) { Calls.call(p) }
        c.y += 110f
        c.divider()
        // messages from the bottom up
        val msgs = Social.conversation(friend.id)
        val bottom = c.h - 140f
        var y = bottom
        val maxW = c.w * 0.68f
        for (m in msgs.asReversed()) {
            val mine = m.sender != friend.id
            val tp = android.text.TextPaint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 28f; color = Color.WHITE; typeface = XrTheme.regular }
            val layout = android.text.StaticLayout.Builder.obtain(m.body, 0, m.body.length, tp, (maxW - 40f).toInt()).build()
            var lw = 0f; for (i in 0 until layout.lineCount) lw = maxOf(lw, layout.getLineWidth(i))
            val bh = layout.height + 30f
            y -= bh + 14f
            if (y < c.y) break
            val bx = if (mine) c.w - c.pad - lw - 40f else c.pad
            bubble.color = if (mine) XrTheme.accent else XrTheme.button
            c.canvas.drawRoundRect(RectF(bx, y, bx + lw + 40f, y + bh), 24f, 24f, bubble)
            c.canvas.save(); c.canvas.translate(bx + 20f, y + 15f); layout.draw(c.canvas); c.canvas.restore()
        }
        if (msgs.isEmpty()) c.label("Diga oi para ${p.name}!", c.pad, bottom - 60f, 26f, XrTheme.textDim)
        Social.lastError?.let { c.label(it, c.pad, bottom - 4f, 20f, XrTheme.danger, maxLines = 1) }
        // input
        val by = c.h - 110f
        c.button("type", if (input.active) input.text + "|" else "Escreva uma mensagem", c.pad, by, c.w - 2 * c.pad - 100f, 80f, accent = input.active) {
            if (!input.active) { input.begin(""); a.openKeyboard(input) }
        }
        c.roundIconButton("send", Glyphs.get("send"), c.w - c.pad - 84f, by, 80f, accent = true, enabled = input.text.isNotBlank()) {
            Social.send(friend.id, input.text); input.begin("")
        }
    }
}

/**
 * The call window: incoming call (atender / recusar), calling, and in the call the timer, mute,
 * screen sharing and hang up. The other person's shared screen shows above the buttons.
 */
class CallPanel : XrPanel(1.2f, 0.92f, title = "") {
    private val listener: () -> Unit = { dirty = true }
    private var seq = -1
    private var second = -1L

    init { closable = false; priority = 3; Calls.addListener(listener) }
    override fun release() { Calls.removeListener(listener); super.release() }

    override fun update(dt: Float) {
        val s = System.currentTimeMillis() / 1000
        if (s != second && Calls.state == Calls.State.ACTIVE) { second = s; dirty = true }
        if (Calls.remoteScreenSeq != seq) { seq = Calls.remoteScreenSeq; dirty = true }
    }

    override fun draw(c: PanelCanvas) {
        val peer = Calls.peerId?.let { Social.profile(it) }
        val screen = Calls.remoteScreen
        if (screen != null && Calls.state == Calls.State.ACTIVE) {
            val sw = c.w - 2 * c.pad; val sh = minOf(sw * 9f / 16f, c.h - 230f)
            c.image(screen, c.pad, c.pad, sh * 16f / 9f, sh)
            c.label("Tela de ${Calls.peerName}", c.pad, c.pad + sh + 8f, 22f, XrTheme.textDim)
        } else {
            c.avatar(peer, c.w / 2 - 90f, c.pad + 20f, 180f)
            c.label(Calls.peerName.ifEmpty { "Chamada" }, c.pad, c.pad + 220f, 44f, XrTheme.text, bold = true, maxLines = 1)
            val line = when (Calls.state) {
                Calls.State.INCOMING -> "está te ligando"
                Calls.State.OUTGOING -> "chamando…"
                Calls.State.ACTIVE -> elapsed()
                Calls.State.IDLE -> Calls.info
            }
            c.label(line, c.pad, c.pad + 280f, 28f, XrTheme.textDim, maxLines = 2)
            if (Calls.state == Calls.State.ACTIVE && Calls.info.isNotEmpty()) c.label(Calls.info, c.pad, c.pad + 330f, 22f, XrTheme.accent2, maxLines = 2)
        }
        val by = c.h - 130f
        val size = 100f
        when (Calls.state) {
            Calls.State.INCOMING -> {
                bigButton(c, "decline", "call_end", c.w / 2 - 180f, by, size, XrTheme.danger) { Calls.decline() }
                bigButton(c, "accept", "call", c.w / 2 + 80f, by, size, XrTheme.ok) { Calls.accept() }
            }
            Calls.State.OUTGOING -> bigButton(c, "cancel", "call_end", c.w / 2 - size / 2, by, size, XrTheme.danger) { Calls.hangUp() }
            Calls.State.ACTIVE -> {
                c.roundIconButton("mute", Glyphs.get(if (Calls.muted) "mic_off" else "mic"), c.w / 2 - 230f, by, size, accent = Calls.muted) { Calls.toggleMute() }
                c.roundIconButton("share", Glyphs.get(if (Calls.sharing) "stop_screen_share" else "screen_share"), c.w / 2 - 50f, by, size, accent = Calls.sharing) { Calls.toggleShare() }
                bigButton(c, "hang", "call_end", c.w / 2 + 130f, by, size, XrTheme.danger) { Calls.hangUp() }
            }
            Calls.State.IDLE -> c.button("close", "Fechar", c.w / 2 - 120f, by, 240f, 80f) { visible = false; onClose?.invoke() }
        }
    }

    private fun elapsed(): String {
        val s = (System.currentTimeMillis() - Calls.startedAtMs) / 1000
        return "%d:%02d".format(s / 60, s % 60) + if (Calls.sharing) " · mostrando a sua tela" else ""
    }

    private fun bigButton(c: PanelCanvas, id: String, glyph: String, x: Float, top: Float, size: Float, color: Int, action: () -> Unit) {
        val hover = isHovered(id)
        avatarPaint.color = if (hover) Color.argb(255, minOf(255, Color.red(color) + 30), minOf(255, Color.green(color) + 30), minOf(255, Color.blue(color) + 30)) else color
        c.canvas.drawCircle(x + size / 2, top + size / 2, size / 2, avatarPaint)
        c.image(Glyphs.get(glyph), x + size * 0.25f, top + size * 0.25f, size * 0.5f, size * 0.5f)
        addRegion(Region(RectF(x, top, x + size, top + size), id, action))
    }
}

/** A short notice in front of the user (new message), with a button to answer. */
class ToastPanel(text: String, private val action: String, private val onAction: () -> Unit) : XrPanel(0.8f, 0.2f, title = "") {
    private val message = text
    var ttl = 7f
    init { closable = false; priority = 3 }
    override fun update(dt: Float) { ttl -= dt; if (ttl <= 0f) { visible = false; onClose?.invoke() } }
    override fun draw(c: PanelCanvas) {
        c.label(message, c.pad, 34f, 28f, XrTheme.text, maxLines = 2, maxWidth = c.w - 2 * c.pad - 220f)
        c.button("act", action, c.w - c.pad - 200f, (c.h - 80f) / 2, 200f, 80f, accent = true) { onAction(); visible = false; onClose?.invoke() }
    }
}
