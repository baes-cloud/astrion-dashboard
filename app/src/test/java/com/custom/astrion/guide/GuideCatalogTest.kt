package com.custom.astrion.guide

import com.custom.astrion.config.AppConfig
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.config.HotkeyConfig
import com.custom.astrion.input.HardwareKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GuideCatalogTest {

    private fun catalog(
        hotkeys: List<HotkeyConfig> = emptyList(),
        long: List<HotkeyConfig> = emptyList(),
        double: List<HotkeyConfig> = emptyList(),
        options: Map<String, Any?> = emptyMap(),
        names: Map<String, String> = emptyMap(),
    ) = GuideCatalog.from(AppConfig(emptyList(), hotkeys = hotkeys, longHotkeys = long, doubleHotkeys = double, options = options)) { names[it] }

    @Test
    fun labelWinsOverTheDescribedAction() {
        val c = catalog(long = listOf(HotkeyConfig("LIGHT", service = "script.long_lights", label = "All lights off")))
        assertEquals("All lights off", c.forKey(HardwareKey.LIGHT)?.hold)
    }

    @Test
    fun describesPagesScriptsAndApps() {
        val c = catalog(
            hotkeys = listOf(
                HotkeyConfig("SCENE", page = "Media"),
                HotkeyConfig("CUSTOM_1", service = "media_player.select_source", entityId = "media_player.tv", data = mapOf("source" to "com.netflix.ninja")),
            ),
            long = listOf(HotkeyConfig("AC", service = "script.long_aircon")),
            double = listOf(HotkeyConfig("CUSTOM_2", service = "light.toggle", entityId = "light.tv_art_lights")),
            names = mapOf("script.long_aircon" to "Long Aircon", "light.tv_art_lights" to "TV art lights"),
        )
        assertEquals("Opens the Media page", c.forKey(HardwareKey.SCENE)?.tap)
        assertEquals("Switches to Netflix", c.forKey(HardwareKey.CUSTOM_1)?.tap)
        assertEquals("Runs “Long Aircon”", c.forKey(HardwareKey.AC)?.hold)
        assertEquals("Turns TV art lights on or off", c.forKey(HardwareKey.CUSTOM_2)?.double)
    }

    @Test
    fun arrowsCollapseIntoOneEntry() {
        val c = catalog(
            hotkeys = listOf("UP", "DOWN", "LEFT", "RIGHT").map {
                HotkeyConfig(it, service = "remote.send_command", entityId = "remote.tv", data = mapOf("command" to "DPAD_$it"))
            },
        )
        val arrows = c.forKey(HardwareKey.LEFT)
        assertNotNull(arrows)
        assertEquals(4, arrows!!.keys.size)
        assertEquals("Move around the TV's menus", arrows.tap)
        assertEquals(1, c.keys.count { HardwareKey.UP in it.keys })
    }

    @Test
    fun builtInsAreListedWithoutHotkeys() {
        val c = catalog(long = listOf(HotkeyConfig("MENU", service = "astrion.open_guide")))
        val menu = c.forKey(HardwareKey.MENU)!!
        assertTrue(menu.tap!!.startsWith("IR mode"))
        assertEquals("Opens this guide", menu.hold)
        assertNull("only the tap toggles IR mode", menu.double)
        assertNotNull(c.forKey(HardwareKey.VOICE)?.tap)
    }

    @Test
    fun unboundKeysAreLeftOut() {
        assertNull(catalog().forKey(HardwareKey.CUSTOM_3))
    }

    @Test
    fun guideBlockRenamesButtonsAndSuppliesTips() {
        val c = catalog(
            hotkeys = listOf(HotkeyConfig("SCENE", page = "Media")),
            options = mapOf("guide" to mapOf(
                "names" to mapOf("SCENE" to "Media"),
                "tips" to listOf(mapOf("title" to "Play music", "icon" to "music", "steps" to listOf("Press Media."))),
            )),
        )
        assertEquals("Media", c.forKey(HardwareKey.SCENE)?.name)
        assertEquals(listOf(GuideTip("Play music", "music", listOf("Press Media."))), c.tips)
        assertEquals(GuideCatalog.DEFAULT_TIPS, catalog().tips)
    }

    /** The shipped config: every key it binds shows up, and hold ☰ opens the guide. */
    @Test
    fun bundledConfigCoversEveryBoundKey() {
        val config = DashboardLoader.parse(File("../device/config/dashboard.json").readText())
        val c = GuideCatalog.from(config)
        val hidden = ((config.options["guide"] as? Map<*, *>)?.get("hide") as? List<*>).orEmpty()
        (config.hotkeys + config.longHotkeys + config.doubleHotkeys).forEach { hk ->
            if (hk.key.uppercase() in hidden) return@forEach
            val key = HardwareKey.valueOf(hk.key.uppercase())
            assertNotNull("${hk.key} missing from the guide", c.forKey(key))
        }
        assertEquals("Opens this guide", c.forKey(HardwareKey.MENU)?.hold)
        assertTrue(c.tips.size >= 5)
        assertNotNull("the bundled Main page has a floorplan", c.plan)
        assertTrue(c.plan!!.lights.isNotEmpty())
        assertEquals("Hey Pikachu", c.wakeWord)
    }

    @Test
    fun hiddenKeysAreLeftOutAndPlanIsFoundInsideStacks() {
        val c = GuideCatalog.from(
            AppConfig(
                pages = listOf(com.custom.astrion.config.PageConfig("Main", listOf(
                    com.custom.astrion.cards.CardConfig("stack", mapOf("cards" to listOf(
                        mapOf("type" to "picture_elements", "options" to mapOf(
                            "image" to "/x.png",
                            "elements" to listOf(mapOf("entity_id" to "light.a", "left" to 10, "top" to 20), mapOf("entity_id" to "switch.b", "left" to 1, "top" to 1)),
                            "vacuum" to mapOf("dock_position" to listOf(6, 63)),
                        )),
                    ))),
                ))),
                hotkeys = listOf(HotkeyConfig("MUTE", service = "astrion.toggle_mute", entityId = "media_player.x")),
                options = mapOf("guide" to mapOf("hide" to listOf("MUTE"))),
            ),
        )
        assertNull(c.forKey(HardwareKey.MUTE))
        assertEquals(PlanExample("/x.png", listOf(10f to 20f), 6f to 63f), c.plan)
    }
}
