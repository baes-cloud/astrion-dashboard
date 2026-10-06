package com.custom.astrion.cards

import com.custom.astrion.cards.impl.nextAlarmMs
import com.custom.astrion.ha.EntityState
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.util.TimeZone

class NextAlarmTest {
    @Before fun utc() { TimeZone.setDefault(TimeZone.getTimeZone("UTC")) }

    private fun e(id: String, state: String) = EntityState(id, state, JsonObject(emptyMap()))
    private fun ms(iso: String) = java.time.Instant.parse(iso).toEpochMilli()

    private val now = ms("2026-10-06T06:00:00Z")
    private val ents = mapOf(
        "sensor.alarm_today" to e("sensor.alarm_today", "2026-10-06T07:00:00+00:00"),
        "sensor.alarm_tomorrow" to e("sensor.alarm_tomorrow", "2026-10-07T06:30:00+00:00"),
        "sensor.alarm_past" to e("sensor.alarm_past", "2026-10-06T05:00:00+00:00"),
        "input_boolean.off_today" to e("input_boolean.off_today", "on"),
    )
    private val ids = listOf("sensor.alarm_today", "sensor.alarm_tomorrow", "sensor.alarm_past")

    @Test fun earliestFutureAlarm() {
        assertEquals(ms("2026-10-06T07:00:00Z"), nextAlarmMs(ents, now, ids, null, emptySet()))
    }

    @Test fun offTodaySkipsTodaysAlarms() {
        assertEquals(ms("2026-10-07T06:30:00Z"), nextAlarmMs(ents, now, ids, "input_boolean.off_today", emptySet()))
    }

    @Test fun alwaysEntitiesIgnoreOffToday() {
        assertEquals(
            ms("2026-10-06T07:00:00Z"),
            nextAlarmMs(ents, now, ids, "input_boolean.off_today", setOf("sensor.alarm_today")),
        )
    }

    @Test fun nothingAhead() {
        assertNull(nextAlarmMs(ents, now, listOf("sensor.alarm_past", "sensor.missing"), null, emptySet()))
    }
}
