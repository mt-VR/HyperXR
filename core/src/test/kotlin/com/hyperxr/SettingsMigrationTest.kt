package com.hyperxr

import com.hyperxr.settings.ControllerSettings
import com.hyperxr.settings.HandVisual
import com.hyperxr.settings.HyperSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsMigrationTest {
    @Test fun olderSettingsLoseTheAutomaticClickAndTheSkeleton() {
        val old = HyperSettings(version = 2, controllers = ControllerSettings(dwellClick = true))
        val m = HyperSettings.migrate(old)
        assertFalse(m.controllers.dwellClick)
        assertEquals(HyperSettings.CURRENT_VERSION, m.version)
        val v1 = HyperSettings.migrate(HyperSettings(version = 1).let { it.copy(handTracking = it.handTracking.copy(handVisual = HandVisual.SKELETON)) })
        assertEquals(HandVisual.AURA, v1.handTracking.handVisual)
    }

    @Test fun aChoiceMadeOnTheCurrentVersionIsKept() {
        val now = HyperSettings(controllers = ControllerSettings(dwellClick = true))
        assertTrue(HyperSettings.migrate(now).controllers.dwellClick)
        assertFalse(HyperSettings().controllers.dwellClick)
    }
}
