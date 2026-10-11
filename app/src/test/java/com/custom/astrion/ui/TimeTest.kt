package com.custom.astrion.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale

class TimeTest {

    @Test
    fun test24HourTimeFormatting() {
        val dt = ZonedDateTime.of(2026, 10, 10, 9, 5, 0, 0, ZoneId.systemDefault())
        val ms = dt.toInstant().toEpochMilli()
        val formatted = Time.format(ms, "HH:mm")
        assertEquals("09:05", formatted)
    }

    @Test
    fun test12HourTimeFormatting() {
        val dt = ZonedDateTime.of(2026, 10, 10, 14, 30, 0, 0, ZoneId.systemDefault())
        val ms = dt.toInstant().toEpochMilli()
        val formatted = Time.format(ms, "h:mm a", Locale.US)
        assertEquals("2:30 PM", formatted)
    }

    @Test
    fun testDateFormatting() {
        val dt = ZonedDateTime.of(2026, 10, 10, 12, 0, 0, 0, ZoneId.systemDefault())
        val ms = dt.toInstant().toEpochMilli()
        val formatted = Time.format(ms, "yyyy-MM-dd", Locale.US)
        assertEquals("2026-10-10", formatted)
    }

    @Test
    fun testLeapYearFormatting() {
        val dt = ZonedDateTime.of(2028, 2, 29, 12, 0, 0, 0, ZoneId.systemDefault())
        val ms = dt.toInstant().toEpochMilli()
        val formatted = Time.format(ms, "yyyy-MM-dd", Locale.US)
        assertEquals("2028-02-29", formatted)
    }

    @Test
    fun testParseHaLocalValid() {
        val parsed = Time.parseHaLocal("2026-10-10 14:30:00")
        assertNotNull(parsed)
        val zdt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(parsed!!), ZoneId.systemDefault())
        assertEquals(2026, zdt.year)
        assertEquals(10, zdt.monthValue)
        assertEquals(10, zdt.dayOfMonth)
        assertEquals(14, zdt.hour)
        assertEquals(30, zdt.minute)
    }

    @Test
    fun testParseHaLocalInvalidReturnsNull() {
        assertNull(Time.parseHaLocal("invalid-date"))
        assertNull(Time.parseHaLocal(""))
    }

    @Test
    fun testParseIsoDateTimeWithOffset() {
        val iso = "2026-10-10T12:00:00+00:00"
        val parsed = Time.parseIsoDateTime(iso)
        assertNotNull(parsed)
        assertEquals(1791633600000L, parsed)
    }

    @Test
    fun testParseIsoDateTimeWithoutOffset() {
        val iso = "2026-10-10T14:30:00"
        val parsed = Time.parseIsoDateTime(iso)
        assertNotNull(parsed)
        val zdt = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(parsed!!), ZoneId.systemDefault())
        assertEquals(2026, zdt.year)
        assertEquals(10, zdt.monthValue)
        assertEquals(14, zdt.hour)
        assertEquals(30, zdt.minute)
    }

    @Test
    fun testParseIsoDateTimeShort() {
        val iso = "2026-10-10T14:30"
        val parsed = Time.parseIsoDateTime(iso)
        assertNotNull(parsed)
    }

    @Test
    fun testParseIsoDateTimeMalformedReturnsNull() {
        assertNull(Time.parseIsoDateTime("not-a-date"))
        assertNull(Time.parseIsoDateTime(""))
    }

    @Test
    fun testDayConversion() {
        val dt = ZonedDateTime.of(2026, 10, 10, 23, 59, 0, 0, ZoneId.systemDefault())
        val ms = dt.toInstant().toEpochMilli()
        val localDate = Time.day(ms)
        assertEquals(LocalDate.of(2026, 10, 10), localDate)
    }

    @Test
    fun testBoundedPatternCacheDoesNotExceedLimit() {
        Time.clearCache()
        val dt = ZonedDateTime.of(2026, 10, 10, 12, 0, 0, 0, ZoneId.systemDefault())
        val ms = dt.toInstant().toEpochMilli()
        // Generate 60 distinct patterns to test cache eviction
        for (i in 0 until 60) {
            val pat = "yyyy-MM-dd 'T$i'"
            val res = Time.format(ms, pat, Locale.US)
            assertTrue(res.contains("2026-10-10"))
        }
        assertEquals("Cache size must not exceed limit of 50", 50, Time.cacheSize())
    }

    @Test
    fun testStrictCalendarDateParsingHaLocal() {
        // Invalid calendar date Feb 30 must return null
        assertNull(Time.parseHaLocal("2026-02-30 12:00:00"))
        // Non-leap year Feb 29 must return null
        assertNull(Time.parseHaLocal("2023-02-29 12:00:00"))
        // Valid leap year Feb 29 must succeed
        assertNotNull(Time.parseHaLocal("2024-02-29 12:00:00"))
    }

    @Test
    fun testStrictCalendarDateParsingIso() {
        // Invalid date Feb 30 with offset must return null
        assertNull(Time.parseIsoDateTime("2026-02-30T12:00:00Z"))
        // Invalid date Feb 30 without offset must return null
        assertNull(Time.parseIsoDateTime("2026-02-30T12:00:00"))
        assertNull(Time.parseIsoDateTime("2026-02-30T12:00"))
        // Malformed offset must return null
        assertNull(Time.parseIsoDateTime("2026-10-10T12:00:00+99:99"))
        // Valid leap year Feb 29 must succeed
        assertNotNull(Time.parseIsoDateTime("2024-02-29T12:00:00Z"))
    }

    @Test
    fun testLruEvictionRetainsMru() {
        Time.clearCache()
        val dt = ZonedDateTime.of(2026, 10, 10, 12, 0, 0, 0, ZoneId.systemDefault())
        val ms = dt.toInstant().toEpochMilli()

        for (i in 0 until 50) {
            Time.format(ms, "yyyy-MM-dd 'P$i'", Locale.US)
        }
        assertEquals(50, Time.cacheSize())

        // Access P0 so it becomes MRU
        Time.format(ms, "yyyy-MM-dd 'P0'", Locale.US)

        // Add 51st item
        Time.format(ms, "yyyy-MM-dd 'P50'", Locale.US)
        assertEquals(50, Time.cacheSize())

        // P0 (MRU) should still be in cache, P1 (eldest) should be evicted
        assertTrue(Time.hasPattern("yyyy-MM-dd 'P0'", Locale.US))
        assertFalse(Time.hasPattern("yyyy-MM-dd 'P1'", Locale.US))
    }
}
