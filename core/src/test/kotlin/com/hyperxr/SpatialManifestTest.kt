package com.hyperxr

import com.hyperxr.appworld.Gesture
import com.hyperxr.appworld.SpatialManifestParser
import com.hyperxr.appworld.TargetType
import com.hyperxr.appworld.WorldModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpatialManifestTest {
    private val ok = """
        {"version": 1, "objects": [
          {"id": "lamp", "label": "Luz da sala", "model": "lamp", "color": "#FFAA00", "size": 0.3,
           "state": "content://com.example.home/state",
           "actions": [
             {"id": "toggle", "label": "Ligar/desligar", "gesture": "pinch", "intent": {"action": "com.example.home.TOGGLE", "extras": {"room": "sala"}}},
             {"id": "photo", "label": "Mostrar foto", "gesture": "drop", "accepts": "image/*", "intent": {"type": "activity", "action": "android.intent.action.SEND", "component": "com.example.home/.ShowActivity"}}
           ]},
          {"id": "speaker", "model": "speaker", "actions": []}
        ]}
    """.trimIndent()

    @Test fun parsesObjectsAndActions() {
        val m = SpatialManifestParser.parse("com.example.home", ok).getOrThrow()
        assertEquals(2, m.objects.size)
        val lamp = m.objects[0]
        assertEquals(WorldModel.LAMP, lamp.model)
        assertEquals(0xFFFFAA00.toInt(), lamp.color)
        assertEquals("Luz da sala", lamp.label)
        assertEquals(2, lamp.actions.size)
        assertEquals(Gesture.PINCH, lamp.actions[0].gesture)
        assertEquals(TargetType.BROADCAST, lamp.actions[0].target.type)
        assertEquals("sala", lamp.actions[0].target.extras["room"])
        assertEquals(Gesture.DROP, lamp.actions[1].gesture)
        assertEquals("speaker", m.objects[1].label)
    }

    @Test fun rejectsUnsafeOrBrokenManifests() {
        fun bad(json: String) = assertTrue(json, SpatialManifestParser.parse("com.example.home", json).isFailure)
        bad("""{"objects": []}""")
        bad("""{"version": 2, "objects": [{"id": "a"}]}""")
        bad("""{"objects": [{"id": "Bad Id"}]}""")
        bad("""{"objects": [{"id": "a"}, {"id": "a"}]}""")
        bad("""{"objects": [{"id": "a", "model": "dragon"}]}""")
        bad("""{"objects": [{"id": "a", "state": "file:///sdcard/x"}]}""")
        // actions may only target the declaring package
        bad("""{"objects": [{"id": "a", "actions": [{"id": "x", "intent": {"type": "activity", "action": "android.intent.action.VIEW", "component": "com.other.app/.Main"}}]}]}""")
        bad("""{"objects": [{"id": "a", "actions": [{"id": "x", "gesture": "drop", "intent": {"action": "a.b.C"}}]}]}""")
    }

    @Test fun mimePatterns() {
        assertTrue(SpatialManifestParser.mimeMatches("image/*", "image/png"))
        assertTrue(SpatialManifestParser.mimeMatches("*/*", "video/mp4"))
        assertFalse(SpatialManifestParser.mimeMatches("image/*", "video/mp4"))
        assertTrue(SpatialManifestParser.mimeMatches("application/pdf", "application/pdf"))
        assertEquals(0x80112233.toInt(), SpatialManifestParser.parseColor("#11223380"))
    }
}
