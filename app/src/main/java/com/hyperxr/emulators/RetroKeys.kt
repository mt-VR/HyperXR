package com.hyperxr.emulators

import android.view.KeyEvent

/** Android key codes and characters to libretro RETROK_* codes (for PC cores). */
object RetroKeys {
    const val RETURN = 13; const val BACKSPACE = 8; const val TAB = 9; const val ESCAPE = 27; const val SPACE = 32
    const val DELETE = 127; const val UP = 273; const val DOWN = 274; const val RIGHT = 275; const val LEFT = 276
    const val INSERT = 277; const val HOME = 278; const val END = 279; const val PAGEUP = 280; const val PAGEDOWN = 281
    const val F1 = 282; const val CAPSLOCK = 301; const val RSHIFT = 303; const val LSHIFT = 304
    const val RCTRL = 305; const val LCTRL = 306; const val RALT = 307; const val LALT = 308
    const val MOD_SHIFT = 0x01; const val MOD_CTRL = 0x02; const val MOD_ALT = 0x04

    fun fromKeyCode(code: Int): Int? = when (code) {
        in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z -> 'a'.code + (code - KeyEvent.KEYCODE_A)
        in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9 -> '0'.code + (code - KeyEvent.KEYCODE_0)
        in KeyEvent.KEYCODE_F1..KeyEvent.KEYCODE_F12 -> F1 + (code - KeyEvent.KEYCODE_F1)
        KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> RETURN
        KeyEvent.KEYCODE_DEL -> BACKSPACE
        KeyEvent.KEYCODE_FORWARD_DEL -> DELETE
        KeyEvent.KEYCODE_TAB -> TAB
        KeyEvent.KEYCODE_ESCAPE -> ESCAPE
        KeyEvent.KEYCODE_SPACE -> SPACE
        KeyEvent.KEYCODE_DPAD_UP -> UP
        KeyEvent.KEYCODE_DPAD_DOWN -> DOWN
        KeyEvent.KEYCODE_DPAD_LEFT -> LEFT
        KeyEvent.KEYCODE_DPAD_RIGHT -> RIGHT
        KeyEvent.KEYCODE_INSERT -> INSERT
        KeyEvent.KEYCODE_MOVE_HOME -> HOME
        KeyEvent.KEYCODE_MOVE_END -> END
        KeyEvent.KEYCODE_PAGE_UP -> PAGEUP
        KeyEvent.KEYCODE_PAGE_DOWN -> PAGEDOWN
        KeyEvent.KEYCODE_SHIFT_LEFT -> LSHIFT
        KeyEvent.KEYCODE_SHIFT_RIGHT -> RSHIFT
        KeyEvent.KEYCODE_CTRL_LEFT -> LCTRL
        KeyEvent.KEYCODE_CTRL_RIGHT -> RCTRL
        KeyEvent.KEYCODE_ALT_LEFT -> LALT
        KeyEvent.KEYCODE_ALT_RIGHT -> RALT
        KeyEvent.KEYCODE_CAPS_LOCK -> CAPSLOCK
        KeyEvent.KEYCODE_MINUS -> '-'.code
        KeyEvent.KEYCODE_EQUALS -> '='.code
        KeyEvent.KEYCODE_LEFT_BRACKET -> '['.code
        KeyEvent.KEYCODE_RIGHT_BRACKET -> ']'.code
        KeyEvent.KEYCODE_BACKSLASH -> '\\'.code
        KeyEvent.KEYCODE_SEMICOLON -> ';'.code
        KeyEvent.KEYCODE_APOSTROPHE -> '\''.code
        KeyEvent.KEYCODE_GRAVE -> '`'.code
        KeyEvent.KEYCODE_COMMA -> ','.code
        KeyEvent.KEYCODE_PERIOD -> '.'.code
        KeyEvent.KEYCODE_SLASH -> '/'.code
        else -> null
    }

    /** US-layout key and whether Shift is needed for a typed character (XR keyboard text). */
    fun fromChar(c: Char): Pair<Int, Boolean>? {
        if (c in 'a'..'z' || c in '0'..'9') return c.code to false
        if (c in 'A'..'Z') return c.lowercaseChar().code to true
        val plain = " -=[]\\;',./`"
        if (c in plain) return c.code to false
        val shifted = mapOf('!' to '1', '@' to '2', '#' to '3', '$' to '4', '%' to '5', '^' to '6', '&' to '7', '*' to '8',
            '(' to '9', ')' to '0', '_' to '-', '+' to '=', '{' to '[', '}' to ']', '|' to '\\', ':' to ';', '"' to '\'',
            '<' to ',', '>' to '.', '?' to '/', '~' to '`')
        shifted[c]?.let { return it.code to true }
        if (c == '\n') return RETURN to false
        // accented letters: their base letter (DOS has no Unicode)
        val base = java.text.Normalizer.normalize(c.toString(), java.text.Normalizer.Form.NFD).firstOrNull()
        if (base != null && base != c && (base in 'a'..'z' || base in 'A'..'Z')) return fromChar(base)
        return null
    }
}
