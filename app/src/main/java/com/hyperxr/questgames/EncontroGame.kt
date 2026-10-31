package com.hyperxr.questgames

import android.content.Intent
import com.hyperxr.diagnostics.Diagnostics
import com.hyperxr.gltf.GltfLoader
import com.hyperxr.gltf.GltfModel
import com.hyperxr.rendering.GpuModel
import com.hyperxr.social.Social
import com.hyperxr.socialvr.AvatarStyle
import com.hyperxr.socialvr.MapLayout
import com.hyperxr.socialvr.PieceBounds
import com.hyperxr.socialvr.Placement
import com.hyperxr.socialvr.SocialAvatar
import com.hyperxr.socialvr.SocialCatalog
import com.hyperxr.socialvr.SocialMap
import com.hyperxr.socialvr.VrmPose
import com.hyperxr.spatialui.PanelCanvas
import com.hyperxr.spatialui.XrPanel
import com.hyperxr.spatialui.XrTheme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executors
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private fun rgba(r: Float, g: Float, b: Float, a: Float = 1f) = floatArrayOf(r, g, b, a)

/**
 * Encontro: a social space for HyperMagic players. Pick a room (20 maps HyperMagic puts together
 * from public-domain asset packs, or a glTF/GLB file of your own), pick an avatar (30 public-domain
 * VRM avatars), and meet whoever else is in that room online. Walk with the stick or pinch a spot
 * on the floor to jump there.
 *
 * Everything is downloaded the first time it is used and kept on the phone. Avatars are still
 * meshes (arms lowered once, [VrmPose]); hands of other players show as spheres.
 */
class EncontroGame(host: GameHost) : QuestGame(host) {
    override val title = "Encontro"
    override val look = GameSky.Look(
        zenith = rgba(0.32f, 0.5f, 0.85f), horizon = rgba(0.78f, 0.84f, 0.92f), ground = rgba(0.42f, 0.45f, 0.48f),
        sunDir = floatArrayOf(0.35f, 0.8f, 0.4f), sunColor = rgba(1f, 0.96f, 0.86f), sunSize = 0.03f, clouds = 0.35f,
        floor = GameSky.FLOOR_PLAIN, floorColor = rgba(0.5f, 0.52f, 0.55f), fog = 0.006f, objectFog = 0.004f,
        light = rgba(1.05f, 1f, 0.95f), ambient = rgba(0.55f, 0.56f, 0.6f),
    )
    override val shadowRadius = 6f
    override val menu = EncontroMenu(this)

    private val prefs = host.ctx.activity.getSharedPreferences("encontro", 0)
    private val dir = File(host.ctx.activity.filesDir, "encontro").apply { mkdirs() }
    private val io = Executors.newFixedThreadPool(3) { r -> Thread(r, "hx-encontro").apply { isDaemon = true } }

    // what is being shown
    var room: Room? = null; private set
    var avatar: SocialAvatar = SocialCatalog.AVATARS.firstOrNull { it.id == prefs.getString("avatar", "") } ?: SocialCatalog.AVATARS[0]; private set
    @Volatile var progress = ""; private set
    private var placements: List<Placement> = emptyList()
    private val pieces = HashMap<String, GpuModel>()

    /** A room: one of the catalogue's maps, or an imported glTF file. */
    class Room(val id: String, val title: String, val map: SocialMap?, val file: File?)

    // models: loaded on worker threads, put on the GPU on the render thread
    private val loaded = ConcurrentHashMap<String, GltfModel>()
    private val failed = ConcurrentHashMap.newKeySet<String>()
    private val loading = ConcurrentHashMap.newKeySet<String>()
    private val gpu = HashMap<String, GpuModel>()
    private val bounds = ConcurrentHashMap<String, PieceBounds>()

    // the player in the room: where the play space's origin is and how it is turned
    private var px = 0f; private var pz = 0f; private var turn = 0f
    private var teleportArmed = BooleanArray(2)
    private val target = FloatArray(3)
    private var targetValid = false

    // online
    private var net: com.hyperxr.social.Supabase.Realtime? = null
    private var topic = ""
    private val netQueue = ConcurrentLinkedQueue<Pair<String, JSONObject>>()
    private val myId = Social.me?.id ?: ("anon-" + (System.nanoTime() % 1_000_000))
    private val myName = Social.me?.name ?: "Visitante"
    class Peer(val id: String) {
        var name = ""; var avatar = ""; var lastSeen = 0f
        val head = FloatArray(3); val net = FloatArray(3); var yaw = 0f
        val hands = Array(2) { FloatArray(3) }; val handOn = BooleanArray(2)
    }
    val peers = LinkedHashMap<String, Peer>()
    private var sendTimer = 0f

    override fun start() {
        status = "Escolha uma sala e um avatar no painel à direita"
        val last = prefs.getString("room", "") ?: ""
        val map = SocialCatalog.MAPS.firstOrNull { it.id == last } ?: SocialCatalog.MAPS[0]
        enter(Room(map.id, map.title, map, null))
    }

    // ------------------------------------------------------------------ rooms and avatars
    fun enter(r: Room) {
        leaveNet()
        room = r; placements = emptyList(); pieces.clear(); peers.clear()
        px = 0f; pz = 0f; turn = 0f
        prefs.edit().putString("room", r.id).apply()
        val names = r.map?.pieces ?: listOf("__file__")
        progress = "Baixando ${r.title}…"
        for (n in names) request(pieceKey(r, n)) { fetchPiece(r, n) }
        joinNet(r)
        menu.dirty = true
    }

    fun chooseAvatar(a: SocialAvatar) {
        avatar = a
        prefs.edit().putString("avatar", a.id).apply()
        request("av:${a.id}") { fetchAvatar(a) }
        menu.dirty = true
    }

    /** Imports a glTF / GLB file picked with Android's file picker as a room of its own. */
    fun importRoom() {
        val act = host.ctx.activity
        com.hyperxr.xr.ActivityResults.launch(act, Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")) { data ->
            val uri = data?.data ?: return@launch
            io.execute {
                try {
                    val bytes = act.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
                    GltfLoader.load(bytes)  // refuse what is not a usable glTF before keeping it
                    val hash = MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }.take(16)
                    val f = File(File(dir, "imported").apply { mkdirs() }, "$hash.glb")
                    f.writeBytes(bytes)
                    val name = runCatching { act.contentResolver.query(uri, null, null, null, null)?.use { c -> if (c.moveToFirst()) c.getString(c.getColumnIndexOrThrow("_display_name")) else null } }.getOrNull()
                        ?.substringBeforeLast('.') ?: "Sala importada"
                    prefs.edit().putString("imp:$hash", name).apply()
                    progress = "\"$name\" importada: está na lista de salas"
                } catch (t: Throwable) { progress = "Não deu para importar: ${t.message}" }
                menu.dirty = true
            }
        }
    }

    /** Rooms imported on this phone (glTF files): the room id is the file's hash, so the same file meets the same people. */
    fun importedRooms(): List<Room> = prefs.all.keys.filter { it.startsWith("imp:") }.mapNotNull { k ->
        val hash = k.removePrefix("imp:")
        val f = File(File(dir, "imported"), "$hash.glb")
        if (f.isFile) Room("imp-$hash", prefs.getString(k, "Sala importada") ?: "Sala importada", null, f) else null
    }

    private fun pieceKey(r: Room, n: String) = if (r.map != null) "map:${r.map.pack}/$n" else "file:${r.id}"

    private fun request(key: String, work: () -> GltfModel) {
        if (loaded.containsKey(key) || failed.contains(key) || !loading.add(key)) return
        io.execute {
            try { loaded[key] = work() } catch (t: Throwable) { failed += key; Diagnostics.log("Encontro: $key ${t.message}") }
            finally { loading.remove(key); menu.dirty = true }
        }
    }

    private fun download(url: String, out: File) {
        if (out.isFile && out.length() > 0) return
        val tmp = File(out.path + ".part")
        val c = URL(url).openConnection() as HttpURLConnection
        c.instanceFollowRedirects = true; c.connectTimeout = 15000; c.readTimeout = 60000
        c.setRequestProperty("User-Agent", "HyperMagic")
        if (c.responseCode != 200) error("HTTP ${c.responseCode}")
        c.inputStream.use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 16) } }
        tmp.renameTo(out)
    }

    /** A map piece: its file, without the collider meshes the packs carry, and its bounds for the layout. */
    private fun fetchPiece(r: Room, n: String): GltfModel {
        val f = r.file ?: File(File(dir, "maps/${r.map!!.pack}").apply { mkdirs() }, "$n.glb").also { download(r.map.url(n), it) }
        val m = GltfLoader.load(f.readBytes())
        val kept = m.instances.filter { !it.nodeName.contains("collider", ignoreCase = true) }
        val model = GltfModel(m.meshes, m.materials, m.images, kept, m.boundsMin, m.boundsMax, m.warnings)
        bounds[pieceKey(r, n)] = boundsOf(model, n, r.map?.ground?.contains(n) == true)
        return model
    }

    private fun fetchAvatar(a: SocialAvatar): GltfModel {
        val f = File(File(dir, "avatars").apply { mkdirs() }, "${a.id}.vrm")
        download(a.url, f)
        return GltfLoader.load(VrmPose.relaxArms(f.readBytes()))
    }

    private fun boundsOf(m: GltfModel, name: String, ground: Boolean): PieceBounds {
        val mn = floatArrayOf(Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE); val mx = floatArrayOf(-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE)
        for (inst in m.instances) for (p in m.meshes[inst.mesh].primitives) {
            val w = inst.world; val v = p.positions
            for (k in 0 until v.size / 3) {
                val x = v[k * 3]; val y = v[k * 3 + 1]; val z = v[k * 3 + 2]
                for (c in 0..2) {
                    val t = w[c] * x + w[4 + c] * y + w[8 + c] * z + w[12 + c]
                    if (t < mn[c]) mn[c] = t; if (t > mx[c]) mx[c] = t
                }
            }
        }
        if (mn[0] > mx[0]) for (c in 0..2) { mn[c] = 0f; mx[c] = 0f }
        return PieceBounds(name, ground, mn[0], mn[1], mn[2], mx[0], mx[1], mx[2])
    }

    private fun gpuOf(key: String): GpuModel? = gpu[key] ?: loaded[key]?.let { m -> runCatching { GpuModel(m) }.getOrNull()?.also { gpu[key] = it } }

    /** Lays the map out once every piece is in (or failed): the same layout on every phone. */
    private fun layoutIfReady() {
        val r = room ?: return
        if (placements.isNotEmpty()) return
        val names = r.map?.pieces ?: listOf("__file__")
        if (names.any { loading.contains(pieceKey(r, it)) || (!loaded.containsKey(pieceKey(r, it)) && !failed.contains(pieceKey(r, it))) }) {
            val done = names.count { loaded.containsKey(pieceKey(r, it)) || failed.contains(pieceKey(r, it)) }
            progress = "Baixando ${r.title}: $done de ${names.size}"
            return
        }
        placements = if (r.map == null) listOf(Placement("__file__", 0f, 0f, 0f, 0f))
            else MapLayout.arrange(names.mapNotNull { bounds[pieceKey(r, it)] })
        val missing = names.count { failed.contains(pieceKey(r, it)) }
        progress = if (missing > 0) "${r.title}: $missing peça(s) não baixaram" else r.title
        menu.dirty = true
    }

    // ------------------------------------------------------------------ online
    private fun joinNet(r: Room) {
        if (Social.me == null) return
        topic = "encontro:${r.id}"
        runCatching { net = Social.sb.realtime().also { it.subscribe(topic) { e, p -> netQueue.add(e to p) } } }
    }

    private fun leaveNet() { if (topic.isNotEmpty()) net?.unsubscribe(topic); topic = ""; net = null }

    private fun onNet(p: JSONObject) {
        val id = p.optString("id"); if (id.isEmpty() || id == myId) return
        val peer = peers.getOrPut(id) { Peer(id) }
        peer.name = p.optString("n"); peer.avatar = p.optString("a"); peer.lastSeen = host.time
        p.optJSONArray("h")?.let { for (k in 0..2) peer.net[k] = it.optDouble(k).toFloat() }
        peer.yaw = p.optDouble("y").toFloat()
        for (i in 0..1) {
            val q = p.optJSONArray("p$i"); peer.handOn[i] = q != null
            if (q != null) for (k in 0..2) peer.hands[i][k] = q.optDouble(k).toFloat()
        }
        SocialCatalog.AVATARS.firstOrNull { it.id == peer.avatar }?.let { a -> request("av:${a.id}") { fetchAvatar(a) } }
        menu.dirty = true
    }

    // ------------------------------------------------------------------ space: room <-> play space
    /** Room point -> game (play space) point. */
    private fun toGame(x: Float, z: Float, out: FloatArray) {
        val dx = x - px; val dz = z - pz; val c = cos(-turn); val s = sin(-turn)
        out[0] = dx * c + dz * s; out[2] = -dx * s + dz * c
    }

    /** Game point -> room point. */
    private fun toRoom(x: Float, z: Float, out: FloatArray) {
        val c = cos(turn); val s = sin(turn)
        out[0] = px + x * c + z * s; out[2] = pz - x * s + z * c
    }

    private val g = FloatArray(3)
    private val fwd = com.hyperxr.math.Vec3()
    private val fwdGame = com.hyperxr.math.Vec3()
    /** Where the head looks, as a yaw in the play space. */
    private val headYaw: Float get() {
        host.ctx.input.head.orientation.rotate(fwd.set(0f, 0f, -1f), fwdGame)
        host.dirToGame(fwdGame, fwd)
        return atan2(-fwd.x, -fwd.z)
    }

    override fun update(dt: Float) {
        while (true) { val (e, p) = netQueue.poll() ?: break; if (e == "s") onNet(p) else if (e == "bye") peers.remove(p.optString("id")) }
        layoutIfReady()
        request("av:${avatar.id}") { fetchAvatar(avatar) }

        // walking with the stick (where the head looks), turning in steps with the second button
        val pad = host.pad
        if (pad.connected) {
            val sx = pad.stickX; val sy = pad.stickY
            if (sx * sx + sy * sy > 0.04f) {
                val yaw = headYaw + turn
                val speed = 2.2f * dt
                px += (-sin(yaw) * sy + cos(yaw) * sx) * speed
                pz += (-cos(yaw) * sy - sin(yaw) * sx) * speed
            }
            if (pad.altDown) turn += (PI / 4).toFloat()
        }
        // hands: point down at the floor and pinch to aim, let go to jump there
        targetValid = false
        for (i in 0..1) {
            val h = host.hands[i]
            if (!h.usable) { teleportArmed[i] = false; continue }
            val aiming = h.pinch && h.dir.y < -0.15f
            if (aiming) {
                val t = -h.palm.y / h.dir.y
                if (t in 0.3f..14f) {
                    target[0] = h.palm.x + h.dir.x * t; target[2] = h.palm.z + h.dir.z * t
                    targetValid = true; teleportArmed[i] = true
                }
            } else if (teleportArmed[i] && !h.pinch) {
                teleportArmed[i] = false
                // the head ends up over the spot
                toRoom(target[0] - host.head.x, target[2] - host.head.z, g)
                px = g[0]; pz = g[2]
                host.play(host.sTick, host.head.x, host.head.y, host.head.z, 0.4f)
            }
        }

        // peers: smoothing, and dropping the ones that left
        val it = peers.values.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (host.time - p.lastSeen > 8f) { it.remove(); menu.dirty = true; continue }
            for (k in 0..2) p.head[k] += (p.net[k] - p.head[k]) * (1f - kotlin.math.exp(-12f * dt))
        }
        sendTimer -= dt
        if (sendTimer <= 0f && net != null) {
            sendTimer = 0.12f
            toRoom(host.head.x, host.head.z, g)
            val o = JSONObject().put("id", myId).put("n", myName).put("a", avatar.id)
                .put("h", JSONArray().put(g[0].toDouble()).put(host.head.y.toDouble()).put(g[2].toDouble()))
                .put("y", (headYaw + turn).toDouble())
            for (i in 0..1) {
                val h = host.hands[i]
                if (h.usable) { toRoom(h.palm.x, h.palm.z, g); o.put("p$i", JSONArray().put(g[0].toDouble()).put(h.palm.y.toDouble()).put(g[2].toDouble())) }
            }
            net?.broadcast(topic, "s", o)
        }
        status = when {
            Social.me == null -> "Sozinho: entre na sua conta (Pessoas) para ver outras pessoas · $progress"
            peers.isEmpty() -> "Ninguém mais nesta sala agora · $progress"
            else -> "${peers.size} pessoa(s) aqui: ${peers.values.joinToString { it.name }} · $progress"
        }
    }

    private val peerColor = rgba(0.55f, 0.6f, 0.9f)
    private val handColor = rgba(0.95f, 0.85f, 0.75f)
    private val ringColor = rgba(0.4f, 0.8f, 1f, 0.9f)

    override fun draw() {
        val r = room ?: return
        for (pl in placements) {
            val key = if (r.map == null) "file:${r.id}" else "map:${r.map.pack}/${pl.name}"
            val m = gpuOf(key) ?: continue
            toGame(pl.x, pl.z, g)
            host.drawGpuModel(m, g[0], pl.y, g[2], pl.yaw - turn, shadow = false)
        }
        for (p in peers.values) {
            toGame(p.head[0], p.head[2], g)
            val m = gpuOf("av:${p.avatar}")
            if (m != null) host.drawGpuModel(m, g[0], 0f, g[2], p.yaw - turn)
            else {
                host.drawCylinder(g[0], 0.75f, g[2], 0.22f, 1.5f, peerColor)
                host.drawSphere(g[0], p.head[1], g[2], 0.13f, peerColor)
            }
            for (i in 0..1) if (p.handOn[i]) { toGame(p.hands[i][0], p.hands[i][2], g); host.drawSphere(g[0], p.hands[i][1], g[2], 0.05f, handColor) }
        }
        if (targetValid) host.drawTorus(target[0], 0.02f, target[2], 0.35f, ringColor, unlit = true, glow = true)
    }

    override fun release() {
        net?.let { n -> if (topic.isNotEmpty()) n.broadcast(topic, "bye", JSONObject().put("id", myId)) }
        leaveNet()
        gpu.values.forEach { it.release() }; gpu.clear()
        io.shutdownNow()
    }

    fun loadingAvatar(a: SocialAvatar) = loading.contains("av:${a.id}")
    fun hasAvatar(a: SocialAvatar) = loaded.containsKey("av:${a.id}")
}

/** The game's panel: rooms (catalogue and imported), avatars by style, who is here. */
class EncontroMenu(private val game: EncontroGame) : XrPanel(0.95f, 1.0f, title = "Encontro") {
    private enum class Tab(val label: String) { ROOMS("Salas"), AVATARS("Avatar"), PEOPLE("Pessoas") }
    private var tab = Tab.ROOMS
    private var page = 0
    private var style = AvatarStyle.FEMININE
    private var tick = 0f

    init { closable = false }

    override fun update(dt: Float) { tick += dt; if (tick > 0.5f) { tick = 0f; dirty = true } }

    override fun draw(c: PanelCanvas) {
        var x = c.pad
        for (t in Tab.entries) { c.button("tab:${t.name}", t.label, x, c.pad, 250f, 70f, accent = t == tab) { tab = t; page = 0; dirty = true }; x += 262f }
        c.y = c.pad + 96f
        when (tab) {
            Tab.ROOMS -> rooms(c)
            Tab.AVATARS -> avatars(c)
            Tab.PEOPLE -> people(c)
        }
    }

    private fun rooms(c: PanelCanvas) {
        val all = SocialCatalog.MAPS.map { EncontroGame.Room(it.id, it.title, it, null) } + game.importedRooms()
        val per = 7
        val pages = (all.size + per) / per
        page = page.coerceIn(0, pages - 1)
        for (r in all.drop(page * per).take(per)) {
            val here = game.room?.id == r.id
            c.row("room:${r.id}", r.title, accent = here, detail = if (r.map != null) "${r.map.pieces.size} peças · CC0" else "importada (glTF)") { if (!here) game.enter(r) }
        }
        val y = c.h - 100f
        c.button("prev", "<", c.pad, y, 110f, 76f, enabled = page > 0) { page--; dirty = true }
        c.button("next", ">", c.pad + 122f, y, 110f, 76f, enabled = page < pages - 1) { page++; dirty = true }
        c.button("import", "Importar sala (glTF/GLB)", c.pad + 250f, y, c.w - 2 * c.pad - 250f, 76f) { game.importRoom() }
        c.label(game.progress, c.pad, y - 46f, 24f, XrTheme.textDim, maxWidth = c.w - 2 * c.pad, maxLines = 1)
    }

    private fun avatars(c: PanelCanvas) {
        var x = c.pad
        for (s in AvatarStyle.entries) { c.button("style:${s.name}", s.label, x, c.y, 260f, 64f, accent = s == style) { style = s; dirty = true }; x += 272f }
        c.y += 84f
        for (a in SocialCatalog.AVATARS.filter { it.style == style }) {
            val mine = game.avatar.id == a.id
            val detail = when { game.loadingAvatar(a) -> "baixando…"; game.hasAvatar(a) -> "no celular"; else -> "100Avatars nº ${a.number} · CC0" }
            c.row("av:${a.id}", a.name, accent = mine, detail = detail) { game.chooseAvatar(a) }
        }
    }

    private fun people(c: PanelCanvas) {
        if (game.peers.isEmpty()) c.paragraph("Ninguém mais nesta sala agora. Quem entrar na mesma sala (ou importar o mesmo arquivo) aparece aqui.", 28f)
        for (p in game.peers.values) c.row("p:${p.id}", p.name.ifEmpty { "Visitante" }, detail = SocialCatalog.AVATARS.firstOrNull { it.id == p.avatar }?.name ?: "") {}
        c.paragraph("Andar: analógico (ou aponte para o chão, faça a pinça e solte). Girar 45°: segundo botão do controle.", 24f)
        c.paragraph("Avatares e mapas: Polygonal Mind, ${SocialCatalog.LICENSE}.", 22f)
    }
}
