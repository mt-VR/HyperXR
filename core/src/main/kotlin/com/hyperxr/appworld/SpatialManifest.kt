package com.hyperxr.appworld

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Shape HyperXR builds for a spatial object (all made of its own primitives, no app models). */
enum class WorldModel { BOX, SPHERE, CYLINDER, SPEAKER, SHELF, LAMP, PLANT, SCREEN, PEDESTAL }

/** How an action is triggered on the object. */
enum class Gesture { PINCH, LONG_PINCH, DROP }

/** What HyperXR starts when the action fires. Always sent to the declaring package only. */
enum class TargetType { BROADCAST, ACTIVITY }

data class ActionTarget(val type: TargetType, val action: String, val component: String?, val extras: Map<String, String>)

data class WorldAction(
    val id: String,
    val label: String,
    val gesture: Gesture,
    val target: ActionTarget,
    /** For DROP: MIME pattern of files the object accepts ("image/\*", "*\/\*"...). */
    val accepts: String?,
)

data class WorldObjectSpec(
    val id: String,
    val label: String,
    val model: WorldModel,
    /** ARGB. */
    val color: Int,
    /** Largest dimension in metres. */
    val size: Float,
    val actions: List<WorldAction>,
    /** content:// URI of the app's own provider returning rows (key, value): "on", "text", "progress"... */
    val stateUri: String?,
)

data class SpatialManifest(val packageName: String, val version: Int, val objects: List<WorldObjectSpec>)

/**
 * App-to-World API: an Android app declares, in a JSON resource referenced from its manifest,
 * objects it wants in the HyperXR world and the actions each one exposes. Nothing of the app runs
 * inside HyperXR: actions are intents to that same package, state is read from its provider.
 *
 * ```xml
 * <receiver android:name=".SpatialActions" android:exported="true">
 *     <intent-filter><action android:name="com.hyperxr.action.SPATIAL" /></intent-filter>
 *     <meta-data android:name="com.hyperxr.spatial" android:resource="@raw/hyperxr_spatial" />
 * </receiver>
 * ```
 * See docs/APP_TO_WORLD.md for the JSON format.
 */
object SpatialManifestParser {
    const val ACTION_DISCOVER = "com.hyperxr.action.SPATIAL"
    const val META_DATA = "com.hyperxr.spatial"
    const val MAX_OBJECTS = 8
    const val MAX_ACTIONS = 6
    private val ID = Regex("^[a-z][a-z0-9_]{0,31}$")
    private val ACTION_NAME = Regex("^[A-Za-z][A-Za-z0-9_.]{2,127}$")
    private val COMPONENT = Regex("^([A-Za-z0-9_.]+/)?[A-Za-z0-9_.$]+$")
    private val json = Json { ignoreUnknownKeys = true }

    fun parse(packageName: String, text: String): Result<SpatialManifest> = runCatching {
        val root = json.parseToJsonElement(text).jsonObject
        val version = root["version"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: 1
        require(version == 1) { "versão $version não suportada" }
        val arr = root["objects"] as? JsonArray ?: error("sem \"objects\"")
        require(arr.size in 1..MAX_OBJECTS) { "de 1 a $MAX_OBJECTS objetos" }
        val ids = HashSet<String>()
        val objects = arr.map { el ->
            val o = el.jsonObject
            val id = o.str("id") ?: error("objeto sem id")
            require(ID.matches(id)) { "id inválido: $id" }
            require(ids.add(id)) { "id repetido: $id" }
            val model = o.str("model")?.let { m -> WorldModel.entries.firstOrNull { it.name.equals(m, true) } ?: error("modelo desconhecido: $m") } ?: WorldModel.BOX
            val actions = (o["actions"] as? JsonArray).orEmpty().map { parseAction(it.jsonObject, packageName) }
            require(actions.size <= MAX_ACTIONS) { "$id: no máximo $MAX_ACTIONS ações" }
            require(actions.map { it.id }.toSet().size == actions.size) { "$id: ações repetidas" }
            val state = o.str("state")
            if (state != null) require(state.startsWith("content://")) { "$id: state deve ser content://" }
            WorldObjectSpec(
                id = id,
                label = (o.str("label") ?: id).take(40),
                model = model,
                color = o.str("color")?.let(::parseColor) ?: 0xFFB0B4BC.toInt(),
                size = (o["size"]?.jsonPrimitive?.floatOrNull ?: 0.25f).coerceIn(0.05f, 1.0f),
                actions = actions,
                stateUri = state,
            )
        }
        SpatialManifest(packageName, version, objects)
    }

    private fun parseAction(o: JsonObject, pkg: String): WorldAction {
        val id = o.str("id") ?: error("ação sem id")
        require(ID.matches(id)) { "id de ação inválido: $id" }
        val gesture = o.str("gesture")?.let { g -> Gesture.entries.firstOrNull { it.name.equals(g, true) } ?: error("gesto desconhecido: $g") } ?: Gesture.PINCH
        val intent = o["intent"]?.jsonObject ?: error("$id: sem intent")
        val type = intent.str("type")?.let { t -> TargetType.entries.firstOrNull { it.name.equals(t, true) } ?: error("tipo desconhecido: $t") } ?: TargetType.BROADCAST
        val action = intent.str("action") ?: error("$id: intent sem action")
        require(ACTION_NAME.matches(action)) { "$id: action inválida" }
        val component = intent.str("component")
        if (component != null) {
            require(COMPONENT.matches(component)) { "$id: componente inválido" }
            // a component of another package would let one app drive a second one through HyperXR
            require(!component.contains('/') || component.substringBefore('/') == pkg) { "$id: componente de outro pacote" }
        }
        val extras = (intent["extras"] as? JsonObject)?.mapValues { it.value.jsonPrimitive.content.take(200) }.orEmpty()
        require(extras.size <= 8) { "$id: extras demais" }
        val accepts = o.str("accepts")
        if (gesture == Gesture.DROP) require(accepts != null) { "$id: DROP precisa de \"accepts\"" }
        return WorldAction(id, (o.str("label") ?: id).take(30), gesture, ActionTarget(type, action, component, extras), accepts)
    }

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull

    fun parseColor(s: String): Int {
        val h = s.removePrefix("#")
        require(h.matches(Regex("^[0-9a-fA-F]{6}([0-9a-fA-F]{2})?$"))) { "cor inválida: $s" }
        val v = h.toLong(16)
        return if (h.length == 6) (0xFF000000L or v).toInt() else ((v and 0xFF) shl 24 or (v ushr 8)).toInt()
    }

    /** "image/\*" matches "image/png"; "*\/\*" matches everything. */
    fun mimeMatches(pattern: String, mime: String): Boolean {
        if (pattern == "*/*") return true
        val (pt, ps) = pattern.lowercase().split('/').let { it[0] to it.getOrElse(1) { "*" } }
        val (mt, ms) = mime.lowercase().split('/').let { it[0] to it.getOrElse(1) { "" } }
        return pt == mt && (ps == "*" || ps == ms)
    }
}
