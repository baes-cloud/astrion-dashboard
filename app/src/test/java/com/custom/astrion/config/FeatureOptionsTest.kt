package com.custom.astrion.config

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FeatureOptionsTest {
    private val defaults = mapOf("screensaver" to mapOf("enabled" to true, "idle_seconds" to 45, "brightness" to 0.3))

    @Test
    fun screensaverKeysOverrideDefaults() {
        val f = FeatureOptions(mapOf("screensaver" to mapOf("idle_seconds" to 10)), defaults)
        assertEquals(10_000L, f.screensaver.idleMs)
        assertEquals(0.3f, f.screensaver.brightness, 0f)
        assertTrue(f.screensaver.enabled)
    }

    @Test
    fun negativeUndockedIdleDisables() {
        val f = FeatureOptions(mapOf("screensaver" to mapOf("undocked_idle_seconds" to -1)), defaults)
        assertEquals(-1L, f.screensaver.undockedIdleMs)
    }

    @Test
    fun passThroughListMatchesKeyNamesIgnoringCase() {
        val f = FeatureOptions(mapOf("screensaver" to mapOf("keys_pass_through" to listOf("volume_up"))), defaults)
        assertTrue(f.screensaver.passesThrough("VOLUME_UP"))
        assertFalse(f.screensaver.passesThrough("CENTER"))
        assertTrue(FeatureOptions(emptyMap(), defaults).screensaver.passesThrough("CENTER"))
    }

    @Test
    fun missingBlocksFallBackToDefaults() {
        val f = FeatureOptions(emptyMap(), emptyMap())
        assertEquals("docked", f.voice.wakeWord)
        assertEquals(10 * 60_000L, f.voice.wakeWordUndockedMs)
        assertEquals(120, f.power.screenTimeoutSeconds)
        assertEquals(1, f.ir.repeat)
        assertNull(f.alarm.snooze)
        assertTrue(f.alarm.entities.isEmpty())
    }

    @Test
    fun alarmActionsAndEntities() {
        val f = FeatureOptions(
            mapOf(
                "alarm" to mapOf(
                    "ringing_entity" to "input_boolean.alarm_ringing",
                    "snooze" to mapOf("service" to "script.snooze", "entity_id" to "timer.snooze"),
                ),
                "ir_mode" to mapOf("repeat" to 9),
            ),
            emptyMap(),
        )
        assertEquals(AlarmAction("script.snooze", "timer.snooze"), f.alarm.snooze)
        assertEquals(listOf("input_boolean.alarm_ringing"), f.alarm.entities)
        assertEquals(5, f.ir.repeat)
    }
}
