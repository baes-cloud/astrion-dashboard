package com.custom.astrion.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The layout the APK bundles must load: a broken one would only show up on the remote. */
class BundledDashboardTest {
    private val config = DashboardLoader.parse(File("../device/config/dashboard.json").readText())

    @Test
    fun parsesWithPagesAndHotkeys() {
        assertTrue(config.pages.isNotEmpty())
        assertTrue(config.startPage in config.pages.indices)
        assertTrue(config.hotkeys.isNotEmpty())
    }

    @Test
    fun screensaverMergesOverCompiledDefaults() {
        val ss = config.features.screensaver
        assertEquals(0.12f, ss.nightBrightness, 0f)
        assertEquals("weather.forecast_home", ss.raw["weather_entity"])
    }

    @Test
    fun hotkeysNameRealKeys() {
        val keys = com.custom.astrion.input.HardwareKey.entries.map { it.name }.toSet()
        (config.hotkeys + config.longHotkeys + config.doubleHotkeys).forEach {
            assertTrue("unknown key ${it.key}", it.key.uppercase() in keys)
        }
    }
}
