package com.hyperxr.input

/**
 * Which controller this is and how its keys map onto HyperXR's buttons, for every kind Android
 * can see: Nintendo Joy-Con and Pro, PlayStation (DualShock 4, DualSense), Xbox, 8BitDo, any
 * other Bluetooth or USB gamepad, and the little VR Box style remotes (which often pretend to be
 * a keyboard and a mouse rather than a gamepad, so many games ignore them).
 *
 * Motion sensors of controllers are never used: they drift and jitter. The controller's pose
 * comes from the tracked hand holding it (ControllerPoseFusion).
 *
 * Plain Kotlin (Android key codes as numbers) so it is tested on the JVM.
 */
object GamepadProfiles {
    enum class Family(val label: String) {
        NINTENDO("Nintendo"), PLAYSTATION("PlayStation"), XBOX("Xbox"), EIGHTBITDO("8BitDo"),
        GENERIC("Gamepad"), REMOTE("Controle remoto (VR Box)"),
    }

    /** Where the controller sits: one hand (LEFT/RIGHT), or both hands with two sticks (PRO). */
    enum class Side { LEFT, RIGHT, PRO, COMBINED }

    class Profile(val family: Family, val side: Side) {
        val label get() = family.label + when (side) { Side.LEFT -> " (esquerdo)"; Side.RIGHT -> if (family == Family.NINTENDO) " (direito)" else ""; else -> "" }
    }

    /** What Android tells about the device (InputDevice), as plain values. */
    class Device(
        val name: String, val vendorId: Int, val productId: Int,
        val gamepad: Boolean, val joystick: Boolean, val keyboard: Boolean, val alphabeticKeyboard: Boolean,
        val mouse: Boolean, val external: Boolean, val virtual: Boolean,
    )

    const val NINTENDO_VENDOR = 0x057E
    const val SONY_VENDOR = 0x054C
    const val MICROSOFT_VENDOR = 0x045E
    const val EIGHTBITDO_VENDOR = 0x2DC8

    /** Names the small Bluetooth remotes and cheap pads use (lower case). */
    private val REMOTE_NAMES = Regex("vr ?-?box|vrbox|vr ?park|vr ?remote|bt ?gamepad|bluetooth ?(remote|gamepad|controller|hid)|shutter|selfie|magicsee|mocute|terios|ipega|gamesir|gen ?game|btgame|game ?pad|remote|joystick|controller")

    fun classify(d: Device): Profile? {
        if (d.virtual) return null
        val name = d.name.lowercase()
        val pad = d.gamepad || d.joystick
        // Nintendo first: Joy-Cons come one per hand
        if (pad && (d.vendorId == NINTENDO_VENDOR || "joy-con" in name || "joycon" in name || "pro controller" in name)) {
            val side = when {
                d.productId == 0x2006 || "(l)" in name || "left" in name -> Side.LEFT
                d.productId == 0x2007 || "(r)" in name || "right" in name -> Side.RIGHT
                d.productId == 0x2009 || "pro" in name -> Side.PRO
                "combined" in name -> Side.COMBINED
                else -> Side.RIGHT
            }
            return Profile(Family.NINTENDO, side)
        }
        if (pad) {
            val family = when {
                // the maker's id first; names only when it is unknown ("Wireless Controller" is
                // Sony's name for the DualShock 4, but also part of Xbox's)
                d.vendorId == SONY_VENDOR -> Family.PLAYSTATION
                d.vendorId == MICROSOFT_VENDOR -> Family.XBOX
                d.vendorId == EIGHTBITDO_VENDOR -> Family.EIGHTBITDO
                "xbox" in name -> Family.XBOX
                "8bitdo" in name -> Family.EIGHTBITDO
                "wireless controller" in name || "dualshock" in name || "dualsense" in name -> Family.PLAYSTATION
                // a remote in "game mode" is a gamepad with one stick
                REMOTE_NAMES.containsMatchIn(name) && ("vr" in name || "remote" in name || "shutter" in name) -> Family.REMOTE
                else -> Family.GENERIC
            }
            return Profile(family, if (family == Family.REMOTE) Side.RIGHT else Side.PRO)
        }
        // keyboard / mouse remotes: only external, never a real (alphabetic) keyboard used for typing
        if (!d.external) return null
        if (d.keyboard && !d.alphabeticKeyboard && (REMOTE_NAMES.containsMatchIn(name) || d.mouse)) return Profile(Family.REMOTE, Side.RIGHT)
        if (d.mouse && !d.keyboard && REMOTE_NAMES.containsMatchIn(name)) return Profile(Family.REMOTE, Side.RIGHT)
        return null
    }

    // Android key codes (android.view.KeyEvent)
    const val DPAD_UP = 19; const val DPAD_DOWN = 20; const val DPAD_LEFT = 21; const val DPAD_RIGHT = 22; const val DPAD_CENTER = 23
    const val VOLUME_UP = 24; const val VOLUME_DOWN = 25; const val CAMERA = 27; const val ENTER = 66; const val DEL = 67
    const val SPACE = 62; const val MENU = 82; const val BACK = 4; const val HOME = 3; const val ESCAPE = 111
    const val PAGE_UP = 92; const val PAGE_DOWN = 93
    const val MEDIA_PLAY_PAUSE = 85; const val MEDIA_NEXT = 87; const val MEDIA_PREVIOUS = 88; const val MEDIA_PLAY = 126; const val MEDIA_PAUSE = 127
    const val BUTTON_A = 96; const val BUTTON_B = 97; const val BUTTON_C = 98; const val BUTTON_X = 99; const val BUTTON_Y = 100; const val BUTTON_Z = 101
    const val BUTTON_L1 = 102; const val BUTTON_R1 = 103; const val BUTTON_L2 = 104; const val BUTTON_R2 = 105
    const val BUTTON_THUMBL = 106; const val BUTTON_THUMBR = 107; const val BUTTON_START = 108; const val BUTTON_SELECT = 109; const val BUTTON_MODE = 110
    const val BUTTON_1 = 188

    /** The d-pad keys: not buttons but directions (they steer like a stick when the stick is idle). */
    fun dpad(code: Int): IntArray? = when (code) {
        DPAD_UP -> intArrayOf(0, -1); DPAD_DOWN -> intArrayOf(0, 1); DPAD_LEFT -> intArrayOf(-1, 0); DPAD_RIGHT -> intArrayOf(1, 0)
        else -> null
    }

    /**
     * A key to a HyperXR button. A is "select" everywhere (Cross on PlayStation, A on Xbox), B is
     * "back" (Circle / B). Null: not a button (or a d-pad direction, see [dpad]).
     */
    fun mapKey(code: Int, p: Profile): ControllerButton? {
        if (p.family == Family.NINTENDO) return mapNintendo(code, p.side)
        if (dpad(code) != null) return null
        // the standard gamepad keys (what Android's key layouts give for PlayStation, Xbox, 8BitDo...)
        when (code) {
            BUTTON_A -> return ControllerButton.A
            BUTTON_B -> return ControllerButton.B
            BUTTON_X -> return ControllerButton.X
            BUTTON_Y -> return ControllerButton.Y
            BUTTON_L1 -> return ControllerButton.L
            BUTTON_R1 -> return ControllerButton.R
            BUTTON_L2 -> return ControllerButton.ZL
            BUTTON_R2 -> return ControllerButton.ZR
            BUTTON_THUMBL -> return ControllerButton.STICK_L
            BUTTON_THUMBR -> return ControllerButton.STICK_R
            BUTTON_START, MENU -> return ControllerButton.PLUS
            BUTTON_SELECT -> return ControllerButton.MINUS
            BUTTON_MODE -> return ControllerButton.HOME
            BUTTON_C -> return ControllerButton.X
            BUTTON_Z -> return ControllerButton.Y
        }
        // cheap pads without a key layout: numbered buttons
        if (code in BUTTON_1 until BUTTON_1 + 16) return when (code - BUTTON_1) {
            0 -> ControllerButton.A; 1 -> ControllerButton.B; 2 -> ControllerButton.X; 3 -> ControllerButton.Y
            4 -> ControllerButton.L; 5 -> ControllerButton.R; 6 -> ControllerButton.ZL; 7 -> ControllerButton.ZR
            8 -> ControllerButton.MINUS; 9 -> ControllerButton.PLUS; 10 -> ControllerButton.STICK_L; 11 -> ControllerButton.STICK_R
            else -> ControllerButton.HOME
        }
        // remotes in keyboard / media mode
        if (p.family == Family.REMOTE) return when (code) {
            ENTER, DPAD_CENTER, SPACE, MEDIA_PLAY_PAUSE, MEDIA_PLAY, MEDIA_PAUSE, VOLUME_UP, CAMERA -> ControllerButton.A
            BACK, ESCAPE, DEL, VOLUME_DOWN -> ControllerButton.B
            MEDIA_NEXT, PAGE_DOWN -> ControllerButton.R
            MEDIA_PREVIOUS, PAGE_UP -> ControllerButton.L
            else -> null
        }
        return null
    }

    /** Keycodes from the hid-nintendo / vendor drivers for single and paired Joy-Cons. */
    private fun mapNintendo(code: Int, side: Side): ControllerButton? = when (code) {
        BUTTON_R1 -> ControllerButton.R
        BUTTON_L1 -> ControllerButton.L
        BUTTON_R2 -> ControllerButton.ZR
        BUTTON_L2 -> ControllerButton.ZL
        BUTTON_START -> ControllerButton.PLUS
        BUTTON_SELECT -> ControllerButton.MINUS
        BUTTON_MODE, HOME -> ControllerButton.HOME
        BUTTON_A -> ControllerButton.A
        BUTTON_B -> ControllerButton.B
        BUTTON_X -> ControllerButton.X
        BUTTON_Y -> ControllerButton.Y
        BUTTON_THUMBR -> ControllerButton.STICK_R
        BUTTON_THUMBL -> ControllerButton.STICK_L
        BUTTON_C, BUTTON_Z -> if (side == Side.LEFT) ControllerButton.SL else ControllerButton.SR
        // left Joy-Con arrows, by position in the Nintendo layout (right = A, down = B, up = X, left = Y)
        DPAD_RIGHT -> ControllerButton.A
        DPAD_DOWN -> ControllerButton.B
        DPAD_UP -> ControllerButton.X
        DPAD_LEFT -> ControllerButton.Y
        else -> null
    }

    /**
     * Older PlayStation drivers report L2/R2 on the RX/RY axes (rest = -1) and the right stick on
     * Z/RZ; newer ones use the trigger axes. True when the triggers must be read from RX/RY.
     */
    fun triggersOnRxRy(p: Profile, hasTriggerAxes: Boolean) = p.family == Family.PLAYSTATION && !hasTriggerAxes

    /** A mouse movement (remote in mouse mode) to a stick deflection: px per event, saturating. */
    fun mouseToStick(dx: Float, dy: Float, out: FloatArray) {
        val k = 1f / 18f
        out[0] = (dx * k).coerceIn(-1f, 1f); out[1] = (dy * k).coerceIn(-1f, 1f)
    }
}
