package com.hyperxr

import com.hyperxr.input.ControllerButton
import com.hyperxr.input.GamepadProfiles
import com.hyperxr.input.GamepadProfiles.Family
import com.hyperxr.input.GamepadProfiles.Side
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GamepadProfilesTest {
    private fun dev(name: String, vendor: Int = 0, product: Int = 0, pad: Boolean = true, kb: Boolean = false, alpha: Boolean = false, mouse: Boolean = false, ext: Boolean = true, virtual: Boolean = false) =
        GamepadProfiles.Device(name, vendor, product, pad, pad, kb, alpha, mouse, ext, virtual)

    @Test fun recognisesTheBigFamilies() {
        assertEquals(Family.PLAYSTATION, GamepadProfiles.classify(dev("Wireless Controller", 0x054C, 0x09CC))!!.family)
        assertEquals(Family.PLAYSTATION, GamepadProfiles.classify(dev("DualSense Wireless Controller", 0x054C, 0x0CE6))!!.family)
        assertEquals(Family.XBOX, GamepadProfiles.classify(dev("Xbox Wireless Controller", 0x045E, 0x0B13))!!.family)
        assertEquals(Family.EIGHTBITDO, GamepadProfiles.classify(dev("8BitDo SN30 Pro", 0x2DC8))!!.family)
        assertEquals(Family.GENERIC, GamepadProfiles.classify(dev("USB Gamepad", 0x0079))!!.family)
        val jl = GamepadProfiles.classify(dev("Joy-Con (L)", 0x057E, 0x2006))!!
        assertEquals(Family.NINTENDO, jl.family); assertEquals(Side.LEFT, jl.side)
        // two-handed pads hold the right-hand pose and drive the cursor with the right stick
        assertEquals(Side.PRO, GamepadProfiles.classify(dev("Wireless Controller", 0x054C))!!.side)
    }

    @Test fun vrBoxRemotesInEveryMode() {
        // game mode: a gamepad
        assertEquals(Family.REMOTE, GamepadProfiles.classify(dev("VR BOX"))!!.family)
        // music / mouse mode: a small keyboard with a mouse, or a keyboard alone
        assertEquals(Family.REMOTE, GamepadProfiles.classify(dev("VR-BOX", pad = false, kb = true, mouse = true))!!.family)
        assertEquals(Family.REMOTE, GamepadProfiles.classify(dev("Bluetooth Remote", pad = false, kb = true))!!.family)
        assertEquals(Family.REMOTE, GamepadProfiles.classify(dev("AB Shutter3", pad = false, kb = true))!!.family)
    }

    @Test fun neverTakesKeyboardsOrThePhonesOwnKeys() {
        assertNull(GamepadProfiles.classify(dev("Logitech K380", pad = false, kb = true, alpha = true)))      // a real keyboard
        assertNull(GamepadProfiles.classify(dev("gpio-keys", pad = false, kb = true, ext = false)))           // volume keys
        assertNull(GamepadProfiles.classify(dev("Virtual", virtual = true)))
        assertNull(GamepadProfiles.classify(dev("Logitech M720", pad = false, mouse = true)))                // a plain mouse
    }

    @Test fun selectAndBackAreTheSameEverywhere() {
        val ps = GamepadProfiles.classify(dev("Wireless Controller", 0x054C))!!
        assertEquals(ControllerButton.A, GamepadProfiles.mapKey(GamepadProfiles.BUTTON_A, ps))     // Cross
        assertEquals(ControllerButton.B, GamepadProfiles.mapKey(GamepadProfiles.BUTTON_B, ps))     // Circle
        assertEquals(ControllerButton.ZR, GamepadProfiles.mapKey(GamepadProfiles.BUTTON_R2, ps))
        assertNull(GamepadProfiles.mapKey(GamepadProfiles.DPAD_UP, ps))                            // a direction, not a button
        assertTrue(GamepadProfiles.dpad(GamepadProfiles.DPAD_UP)!!.contentEquals(intArrayOf(0, -1)))
        val generic = GamepadProfiles.classify(dev("USB Gamepad"))!!
        assertEquals(ControllerButton.A, GamepadProfiles.mapKey(GamepadProfiles.BUTTON_1, generic))
        assertEquals(ControllerButton.PLUS, GamepadProfiles.mapKey(GamepadProfiles.BUTTON_1 + 9, generic))
        val remote = GamepadProfiles.classify(dev("VR BOX", pad = false, kb = true, mouse = true))!!
        assertEquals(ControllerButton.A, GamepadProfiles.mapKey(GamepadProfiles.ENTER, remote))
        assertEquals(ControllerButton.A, GamepadProfiles.mapKey(GamepadProfiles.VOLUME_UP, remote))
        assertEquals(ControllerButton.B, GamepadProfiles.mapKey(GamepadProfiles.BACK, remote))
        assertEquals(ControllerButton.R, GamepadProfiles.mapKey(GamepadProfiles.MEDIA_NEXT, remote))
        // a Nintendo left Joy-Con keeps its arrows as face buttons
        val jl = GamepadProfiles.classify(dev("Joy-Con (L)", 0x057E, 0x2006))!!
        assertEquals(ControllerButton.A, GamepadProfiles.mapKey(GamepadProfiles.DPAD_RIGHT, jl))
    }

    @Test fun mouseMovesTheStick() {
        val o = FloatArray(2)
        GamepadProfiles.mouseToStick(9f, -100f, o)
        assertEquals(0.5f, o[0], 1e-4f); assertEquals(-1f, o[1], 0f)
    }
}
