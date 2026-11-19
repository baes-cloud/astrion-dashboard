package com.custom.astrion.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/** A slip in dashboard.json costs one card or one edit, not the whole layout. */
class ConfigResilienceTest {
    @Before fun reset() = DashboardLoader.forgetLastGood()

    private val good = """
        { "pages": [ { "name": "Mine", "cards": [ { "type": "light", "options": { "entity_id": "light.a" } } ] } ],
          "hotkeys": [ { "key": "LIGHT", "page": "Mine" } ] }
    """

    @Test fun oneUnreadableCardDoesNotTakeThePageWithIt() {
        val config = DashboardLoader.parse(
            """{ "pages": [ { "name": "Mine", "cards": [ { "options": {} }, "oops", { "type": "light" } ] } ] }"""
        )
        val cards = config.pages.single().cards
        assertEquals(listOf(DashboardLoader.INVALID_CARD, DashboardLoader.INVALID_CARD, "light"), cards.map { it.type })
        val warnings = DashboardLoader.warnings(config, knownTypes = setOf("light"))
        assertEquals(2, warnings.size)
        assertTrue(warnings[0], warnings[0].startsWith("Mine card 1:"))
    }

    @Test fun aBrokenEditKeepsTheLastLayoutThatWorked() {
        assertNull(DashboardLoader.loadLayoutText({ good }).notice)
        val broken = DashboardLoader.loadLayoutText({ """{ "pages": [ """ })
        assertEquals("Mine", broken.config.pages.single().name)
        assertTrue(broken.notice!!, "keeping the last layout" in broken.notice!!)
    }

    @Test fun withNothingToKeepItFallsBackToTheBuiltInLayout() {
        val broken = DashboardLoader.loadLayoutText({ "nope" })
        assertEquals(DashboardLoader.default, broken.config)
        assertTrue("built-in defaults" in broken.notice!!)
    }

    @Test fun unknownCardTypesAndKeysAreNamed() {
        val config = DashboardLoader.parse(
            """
            { "pages": [ { "name": "Mine", "cards": [ { "type": "ligth" } ] } ],
              "hotkeys": [ { "key": "LIGHT", "page": "Mine" }, { "key": "LIHGT", "page": "Mine" } ],
              "longHotkeys": [ { "key": "menu", "service": "astrion.open_guide" } ] }
            """
        )
        assertEquals(
            listOf("Mine card 1: unknown type \"ligth\"", "hotkeys: no key called \"LIHGT\""),
            DashboardLoader.warnings(config, knownTypes = setOf("light")),
        )
        // Nothing registered yet (app start, unit tests): types can't be judged.
        assertEquals(listOf("hotkeys: no key called \"LIHGT\""), DashboardLoader.warnings(config, knownTypes = emptySet()))
    }

    @Test fun theBundledLayoutHasNoWarnings() {
        val text = java.io.File("../device/config/dashboard.json").readText()
        assertEquals(emptyList<String>(), DashboardLoader.warnings(DashboardLoader.parse(text), knownTypes = emptySet()))
    }
}
