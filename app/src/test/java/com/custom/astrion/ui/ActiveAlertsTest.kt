package com.custom.astrion.ui

import com.custom.astrion.ha.EntityState
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ActiveAlertsTest {
    private val changed = java.time.Instant.parse("2026-10-06T00:00:00Z").toEpochMilli()

    private fun e(id: String, state: String) =
        EntityState(id, state, JsonObject(emptyMap()), changed, changed)

    private val now = java.time.Instant.parse("2026-10-06T00:10:00Z").toEpochMilli()

    private fun specs(vararg m: Map<String, Any?>) = AlertSpec.parse(m.toList())

    @Test fun matchingStateIsActive() {
        val s = specs(mapOf("entity" to "binary_sensor.leak", "severity" to "alarm"))
        val out = activeAlerts(s, mapOf("binary_sensor.leak" to e("binary_sensor.leak", "on")), now)
        assertEquals(1, out.size)
        assertEquals("binary_sensor.leak@$changed", out[0].token)
    }

    @Test fun otherStateIsNotActive() {
        val s = specs(mapOf("entity" to "lock.front", "state" to listOf("unlocked", "open")))
        assertTrue(activeAlerts(s, mapOf("lock.front" to e("lock.front", "locked")), now).isEmpty())
    }

    @Test fun forSecondsWaitsUntilDue() {
        val s = specs(mapOf("entity" to "lock.front", "state" to "unlocked", "for_seconds" to 900))
        val ents = mapOf("lock.front" to e("lock.front", "unlocked"))
        // Unlocked 10 min ago, needs 15.
        assertTrue(activeAlerts(s, ents, now).isEmpty())
        assertEquals(1, activeAlerts(s, ents, now + 5 * 60_000).size)
    }

    @Test fun unlessEntitySuppresses() {
        val s = specs(mapOf(
            "entity" to "binary_sensor.door", "unless" to mapOf("entity" to "input_boolean.home"),
        ))
        val ents = mapOf(
            "binary_sensor.door" to e("binary_sensor.door", "on"),
            "input_boolean.home" to e("input_boolean.home", "on"),
        )
        assertTrue(activeAlerts(s, ents, now).isEmpty())
        assertEquals(1, activeAlerts(s, ents + ("input_boolean.home" to e("input_boolean.home", "off")), now).size)
    }

    @Test fun missingEntityIsIgnored() {
        val s = specs(mapOf("entity" to "binary_sensor.gone"))
        assertTrue(activeAlerts(s, emptyMap(), now).isEmpty())
    }

    @Test fun nextDueIsWhenForSecondsRunsOut() {
        val s = specs(
            mapOf("entity" to "lock.front", "state" to "unlocked", "for_seconds" to 900),
            mapOf("entity" to "binary_sensor.leak"),
        )
        val ents = mapOf(
            "lock.front" to e("lock.front", "unlocked"),
            "binary_sensor.leak" to e("binary_sensor.leak", "on"),
        )
        assertEquals(changed + 900_000, nextAlertDueMs(s, ents, now))
        // Already due, or not in an alerting state: nothing pending.
        assertEquals(null, nextAlertDueMs(s, ents, changed + 900_000))
        assertEquals(null, nextAlertDueMs(s, mapOf("lock.front" to e("lock.front", "locked")), now))
    }
}
