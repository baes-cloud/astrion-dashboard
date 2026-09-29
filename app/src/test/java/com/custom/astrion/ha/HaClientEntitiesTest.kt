package com.custom.astrion.ha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** subscribe_entities' compressed events, as HA sends them. */
class HaClientEntitiesTest {
    private fun event(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    private fun client() = HaClient("http://ha.local:8123", "token").apply {
        onEntitiesEvent(event("""
            {"a": {
              "light.kitchen": {"s": "off", "a": {"friendly_name": "Kitchen", "brightness": null},
                                "c": "01ABC", "lc": 1727600000.5},
              "sensor.temp": {"s": "21.5", "a": {"unit_of_measurement": "°C"},
                              "c": "01DEF", "lc": 1727600000.0, "lu": 1727600060.0}
            }}
        """))
    }

    @Test fun seedGivesFullStates() {
        val c = client()
        val light = c.entityState("light.kitchen").value!!
        assertEquals("off", light.state)
        assertEquals("Kitchen", light.friendlyName)
        assertEquals("2024-09-29T08:53:20.500Z", light.lastChanged)
        // lu omitted means it equals lc.
        assertEquals(light.lastChanged, light.lastUpdated)
        assertEquals("2024-09-29T08:54:20Z", c.entityState("sensor.temp").value!!.lastUpdated)
    }

    @Test fun diffMergesStateAndAttributes() {
        val c = client()
        c.onEntitiesEvent(event("""
            {"c": {"light.kitchen": {
              "+": {"s": "on", "a": {"brightness": 200}, "c": "01XYZ", "lc": 1727600100.0},
              "-": {"a": ["friendly_name"]}
            }}}
        """))
        val light = c.entityState("light.kitchen").value!!
        assertEquals("on", light.state)
        assertEquals(200.0, light.attrDouble("brightness")!!, 0.0)
        assertNull(light.attr("friendly_name"))
        assertEquals("2024-09-29T08:55:00Z", light.lastChanged)
        assertEquals(light.lastChanged, light.lastUpdated)
    }

    @Test fun attributeOnlyDiffKeepsLastChanged() {
        val c = client()
        c.onEntitiesEvent(event("""{"c": {"sensor.temp": {"+": {"a": {"x": 1}, "lu": 1727600200.0}}}}"""))
        val t = c.entityState("sensor.temp").value!!
        assertEquals("21.5", t.state)
        assertEquals("°C", t.attrString("unit_of_measurement"))
        assertEquals("2024-09-29T08:53:20Z", t.lastChanged)
        assertEquals("2024-09-29T08:56:40Z", t.lastUpdated)
    }

    @Test fun removalDropsEntity() {
        val c = client()
        c.onEntitiesEvent(event("""{"r": ["sensor.temp"]}"""))
        assertNull(c.entityState("sensor.temp").value)
    }
}
