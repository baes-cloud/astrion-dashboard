package com.custom.astrion.ui

import com.custom.astrion.config.AlarmOptions
import com.custom.astrion.ha.EntityState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AlarmUiStateTest {
    private val opts = AlarmOptions(
        mapOf(
            "ringing_entity" to "input_boolean.alarm",
            "snooze_timer" to "timer.snooze",
            "info_entity" to "sensor.first_event",
        ),
    )

    private fun e(id: String, state: String, vararg attrs: Pair<String, String>) =
        id to EntityState(id, state, JsonObject(attrs.associate { it.first to JsonPrimitive(it.second) }))

    @Test
    fun offWhenFlagIsOff() {
        assertNull(alarmUiState(opts, mapOf(e("input_boolean.alarm", "off"))))
    }

    @Test
    fun ringingWithEventDetails() {
        val s = alarmUiState(
            opts,
            mapOf(
                e("input_boolean.alarm", "on"),
                e("sensor.first_event", "2026-10-06T09:00:00+00:00", "summary" to "Standup", "location" to " "),
            ),
        )!!
        assertTrue(s.ringing)
        assertEquals("Standup", s.title)
        assertNull(s.place)
        assertEquals(300_000L, s.snoozeTotalMs)
    }

    @Test
    fun snoozedUsesTheTimersOwnDuration() {
        val s = alarmUiState(
            opts,
            mapOf(
                e("input_boolean.alarm", "on"),
                e("timer.snooze", "active", "finishes_at" to "2026-10-06T07:09:00+00:00", "duration" to "0:09:00"),
            ),
        )!!
        assertFalse(s.ringing)
        assertEquals(1791270540000L, s.snoozeEndsMs)
        assertEquals(540_000L, s.snoozeTotalMs)
    }
}
