package com.hyperxr.input

import com.hyperxr.settings.SmartAction
import com.hyperxr.settings.SmartHandSettings

/**
 * SmartHand: the tracked hand aims (the pinch ball, as always) and the buttons of whatever
 * controller is in that hand do the clicking. Any controller Android sees works: a button is
 * bound by its name when [GamepadProfiles] knows the pad ("btn:ZR"), else by its keycode
 * ("key:66"), so keyboards and VR Box remotes can be calibrated too.
 *
 * The hand a button acts for comes from the controller: a left Joy-Con drives the left hand, a
 * right one the right hand, anything else the dominant hand. Holding several buttons bound to the
 * same action keeps it held until the last one is let go.
 */
class SmartHand(var settings: SmartHandSettings = SmartHandSettings()) {
    /** Held right now, per hand (0 left, 1 right). */
    val click = BooleanArray(2)
    val grab = BooleanArray(2)
    private val clickCount = IntArray(2)
    private val grabCount = IntArray(2)
    /** What each pressed (device, binding) started, so its release ends exactly that. */
    private val held = HashMap<Pair<Int, String>, Pair<Int, SmartAction>>()

    /** A press or release of a one-shot action (back, menu, recenter) for [hand]. */
    data class Event(val action: SmartAction, val pressed: Boolean, val hand: Int)

    /** Calibration: while set, the next button pressed is reported here instead of acting. */
    @Volatile var capture: ((String) -> Unit)? = null

    val enabled get() = settings.enabled

    /** Which hand a controller belongs to. */
    fun handOf(side: GamepadProfiles.Side?, deviceName: String): Int = when {
        side == GamepadProfiles.Side.LEFT -> 0
        side == GamepadProfiles.Side.RIGHT -> 1
        deviceName.contains("(L)") || deviceName.contains(" Left", ignoreCase = true) -> 0
        deviceName.contains("(R)") || deviceName.contains(" Right", ignoreCase = true) -> 1
        else -> if (settings.rightHanded) 1 else 0
    }

    /**
     * One key event. Returns null when SmartHand is off or the key is not bound (the event then
     * goes on to the rest of the input), else the events it produced (possibly none).
     */
    fun onKey(deviceId: Int, binding: String, side: GamepadProfiles.Side?, deviceName: String, down: Boolean, repeat: Boolean, joyCon: Boolean = false): List<Event>? {
        capture?.let { cb -> if (down && !repeat) { capture = null; cb(binding) }; return emptyList() }
        if (!settings.enabled) return null
        val key = deviceId to binding
        if (down) {
            if (repeat || key in held) return emptyList()
            val action = bindingsFor(joyCon)[binding] ?: return null
            if (action == SmartAction.NONE) return null
            val hand = handOf(side, deviceName)
            held[key] = hand to action
            return press(hand, action, true)
        }
        val (hand, action) = held.remove(key) ?: return if (bindingsFor(joyCon).containsKey(binding)) emptyList() else null
        return press(hand, action, false)
    }

    /** The bindings in force: on a Joy-Con with auto calibration, the preset fills what the user did not set. */
    fun bindingsFor(joyCon: Boolean): Map<String, SmartAction> =
        if (joyCon && settings.autoJoyCon) JOYCON_PRESET + settings.bindings else settings.bindings

    private fun press(hand: Int, action: SmartAction, down: Boolean): List<Event> {
        val d = if (down) 1 else -1
        return when (action) {
            SmartAction.CLICK -> { clickCount[hand] = (clickCount[hand] + d).coerceAtLeast(0); click[hand] = clickCount[hand] > 0; emptyList() }
            SmartAction.GRAB -> { grabCount[hand] = (grabCount[hand] + d).coerceAtLeast(0); grab[hand] = grabCount[hand] > 0; emptyList() }
            else -> listOf(Event(action, down, hand))
        }
    }

    /** Everything let go (a controller disconnected, the app paused). */
    fun releaseAll(): List<Event> {
        val out = ArrayList<Event>()
        for ((_, v) in held) if (v.second != SmartAction.CLICK && v.second != SmartAction.GRAB) out += Event(v.second, false, v.first)
        held.clear()
        clickCount.fill(0); grabCount.fill(0); click.fill(false); grab.fill(false)
        return out
    }

    companion object {
        fun buttonBinding(b: ControllerButton) = "btn:${b.name}"
        fun keyBinding(code: Int) = "key:$code"

        /** Readable name of a binding, for the calibration screen. */
        fun label(binding: String): String = when {
            binding.startsWith("btn:") -> when (val n = binding.removePrefix("btn:")) {
                "PLUS" -> "+"; "MINUS" -> "-"; "STICK_R" -> "Analógico R"; "STICK_L" -> "Analógico L"; "CAPTURE" -> "Captura"; "HOME" -> "Home"
                else -> n
            }
            binding.startsWith("key:") -> "Tecla " + (binding.removePrefix("key:").toIntOrNull()?.let { KEY_NAMES[it] ?: "#$it" } ?: binding)
            else -> binding
        }

        private val KEY_NAMES = mapOf(
            66 to "Enter", 23 to "OK", 24 to "Volume +", 25 to "Volume -", 62 to "Espaço", 4 to "Voltar", 67 to "Apagar",
            19 to "Cima", 20 to "Baixo", 21 to "Esquerda", 22 to "Direita", 111 to "Esc",
        )

        /**
         * Joy-Con auto calibration (same buttons by position on the left and right ones): the
         * triggers and the face button under the thumb click, the shoulders grab, B goes back,
         * + / - open the menu, Home recenters. Plain gamepads use the same names.
         */
        val JOYCON_PRESET: Map<String, SmartAction> = linkedMapOf(
            "btn:ZR" to SmartAction.CLICK, "btn:ZL" to SmartAction.CLICK, "btn:A" to SmartAction.CLICK,
            "btn:R" to SmartAction.GRAB, "btn:L" to SmartAction.GRAB,
            "btn:B" to SmartAction.BACK,
            "btn:PLUS" to SmartAction.MENU, "btn:MINUS" to SmartAction.MENU,
            "btn:HOME" to SmartAction.RECENTER, "btn:CAPTURE" to SmartAction.RECENTER,
        )

        /** Default: the Joy-Con preset plus Enter / OK / Volume + of keyboards and VR Box remotes. */
        val DEFAULT: Map<String, SmartAction> = JOYCON_PRESET + mapOf(
            "key:66" to SmartAction.CLICK, "key:23" to SmartAction.CLICK, "key:24" to SmartAction.CLICK,
        )
    }
}
