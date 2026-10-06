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

    /** Every lit_rooms light must have an icon on the same plan, or it never glows. */
    @Test
    fun litRoomLightsAreOnTheFloorplan() {
        val plans = mutableListOf<Map<*, *>>()
        fun walk(v: Any?) {
            when (v) {
                is Map<*, *> -> {
                    if (v["type"] == "picture_elements") (v["options"] as? Map<*, *>)?.let { if (it["lit_rooms"] != null) plans += it }
                    v.values.forEach(::walk)
                }
                is List<*> -> v.forEach(::walk)
            }
        }
        config.pages.flatMap { it.cards }.forEach { c ->
            if (c.type == "picture_elements" && c.options["lit_rooms"] != null) plans += c.options
            walk(c.options)
        }
        assertTrue(plans.isNotEmpty())
        plans.forEach { plan ->
            val placed = (plan["elements"] as List<*>).mapNotNull { (it as Map<*, *>)["entity_id"] }.toSet()
            ((plan["lit_rooms"] as Map<*, *>)["rooms"] as List<*>).forEach { r ->
                r as Map<*, *>
                assertTrue("${r["name"]}: shape needs 3+ points", (r["shape"] as List<*>).size >= 3)
                (r["lights"] as List<*>).forEach { assertTrue("$it has no floorplan icon", it in placed) }
            }
        }
    }
}
