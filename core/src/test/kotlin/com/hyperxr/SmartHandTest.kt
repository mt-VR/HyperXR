package com.hyperxr

import com.hyperxr.input.GamepadProfiles
import com.hyperxr.input.SmartHand
import com.hyperxr.settings.HyperSettings
import com.hyperxr.settings.SmartAction
import com.hyperxr.settings.SmartHandSettings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SmartHandTest {
    private fun on(bindings: Map<String, SmartAction> = SmartHand.DEFAULT, auto: Boolean = true) =
        SmartHand(SmartHandSettings(enabled = true, bindings = bindings, autoJoyCon = auto))

    @Test fun offPassesEverythingOn() {
        val s = SmartHand()
        assertNull(s.onKey(1, "btn:ZR", GamepadProfiles.Side.RIGHT, "Joy-Con (R)", down = true, repeat = false))
        assertFalse(s.click[1])
    }

    @Test fun joyConSideChoosesTheHand() {
        val s = on()
        s.onKey(1, "btn:ZL", GamepadProfiles.Side.LEFT, "Joy-Con (L)", true, false, joyCon = true)
        assertTrue(s.click[0]); assertFalse(s.click[1])
        s.onKey(2, "btn:ZR", GamepadProfiles.Side.RIGHT, "Joy-Con (R)", true, false, joyCon = true)
        assertTrue(s.click[1])
        s.onKey(1, "btn:ZL", GamepadProfiles.Side.LEFT, "Joy-Con (L)", false, false, joyCon = true)
        assertFalse(s.click[0]); assertTrue(s.click[1])
    }

    @Test fun otherControllersUseTheDominantHand() {
        val s = on()
        assertEquals(1, s.handOf(GamepadProfiles.Side.PRO, "Xbox Wireless Controller"))
        s.settings = s.settings.copy(rightHanded = false)
        assertEquals(0, s.handOf(null, "VR BOX"))
        assertEquals(1, s.handOf(null, "Gamepad (R)"))
    }

    @Test fun twoButtonsHoldUntilTheLastIsReleased() {
        val s = on()
        s.onKey(1, "btn:A", null, "pad", true, false)
        s.onKey(1, "btn:ZR", null, "pad", true, false)
        s.onKey(1, "btn:A", null, "pad", false, false)
        assertTrue(s.click[1])
        s.onKey(1, "btn:ZR", null, "pad", false, false)
        assertFalse(s.click[1])
    }

    @Test fun repeatsAndDuplicatesDoNotStack() {
        val s = on()
        s.onKey(1, "btn:A", null, "pad", true, false)
        s.onKey(1, "btn:A", null, "pad", true, true)
        s.onKey(1, "btn:A", null, "pad", true, false)
        s.onKey(1, "btn:A", null, "pad", false, false)
        assertFalse(s.click[1])
    }

    @Test fun oneShotActionsAreEvents() {
        val s = on()
        val ev = s.onKey(3, "btn:B", null, "pad", true, false)!!
        assertEquals(listOf(SmartHand.Event(SmartAction.BACK, true, 1)), ev)
        assertEquals(listOf(SmartHand.Event(SmartAction.BACK, false, 1)), s.onKey(3, "btn:B", null, "pad", false, false))
    }

    @Test fun unboundKeysGoOn() {
        val s = on()
        assertNull(s.onKey(1, "key:999", null, "kbd", true, false))
        assertNull(s.onKey(1, "key:999", null, "kbd", false, false))
    }

    @Test fun joyConAutoCalibrationFillsMissingButtons() {
        val s = on(bindings = mapOf("btn:A" to SmartAction.BACK))
        assertTrue(s.onKey(1, "btn:ZR", GamepadProfiles.Side.RIGHT, "Joy-Con (R)", true, false, joyCon = true)!!.isEmpty())
        assertTrue(s.click[1])
        // the user's own choice wins over the preset
        assertEquals(SmartAction.BACK, s.onKey(1, "btn:A", GamepadProfiles.Side.RIGHT, "Joy-Con (R)", true, false, joyCon = true)!!.single().action)
        // other pads do not get the preset
        assertNull(s.onKey(2, "btn:ZR", null, "pad", true, false))
        val off = on(bindings = emptyMap(), auto = false)
        assertNull(off.onKey(1, "btn:ZR", GamepadProfiles.Side.RIGHT, "Joy-Con (R)", true, false, joyCon = true))
    }

    @Test fun captureTakesTheNextPress() {
        val s = on()
        var got: String? = null
        s.capture = { got = it }
        assertEquals(emptyList<SmartHand.Event>(), s.onKey(1, "btn:X", null, "pad", true, false))
        assertEquals("btn:X", got)
        assertNull(s.capture)
        assertFalse(s.click[1])
    }

    @Test fun releaseAllLetsGo() {
        val s = on()
        s.onKey(1, "btn:R", null, "pad", true, false)
        s.onKey(1, "btn:PLUS", null, "pad", true, false)
        assertTrue(s.grab[1])
        val ev = s.releaseAll()
        assertFalse(s.grab[1])
        assertEquals(listOf(SmartHand.Event(SmartAction.MENU, false, 1)), ev)
    }

    @Test fun settingsRoundTrip() {
        val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }
        val s = HyperSettings().let { it.copy(controllers = it.controllers.copy(smartHand = SmartHandSettings(enabled = true, bindings = mapOf("key:66" to SmartAction.GRAB)))) }
        val back = json.decodeFromString(HyperSettings.serializer(), json.encodeToString(s))
        assertEquals(s.controllers.smartHand, back.controllers.smartHand)
        assertEquals("ZR", SmartHand.label("btn:ZR"))
        assertEquals("Tecla Enter", SmartHand.label("key:66"))
    }
}
