package com.custom.astrion.ui

import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaeoThemeNightTest {
    private fun at(y: Int, mo: Int, d: Int, h: Int, mi: Int = 0) =
        LocalDateTime.of(y, mo, d, h, mi).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    @Test fun haSensorWins() {
        assertTrue(baeoThemeIsNight("on", "above_horizon", at(2026, 11, 6, 12)))
        assertFalse(baeoThemeIsNight("off", "below_horizon", at(2026, 11, 6, 2)))
    }

    @Test fun weekdayFallback() {
        // Friday 2026-11-06
        assertFalse(baeoThemeIsNight(null, "above_horizon", at(2026, 11, 6, 7, 30)))
        assertTrue(baeoThemeIsNight(null, "above_horizon", at(2026, 11, 6, 6, 30)))
        assertTrue(baeoThemeIsNight(null, "below_horizon", at(2026, 11, 6, 20, 30)))
    }

    @Test fun weekendMorningsWaitTillNine() {
        // Saturday 2026-11-07: sun is up at 08:00, still Night
        assertTrue(baeoThemeIsNight(null, "above_horizon", at(2026, 11, 7, 8)))
        assertFalse(baeoThemeIsNight(null, "above_horizon", at(2026, 11, 7, 9, 5)))
    }

    @Test fun noSunUsesEightPm() {
        assertFalse(baeoThemeIsNight(null, null, at(2026, 11, 6, 19)))
        assertTrue(baeoThemeIsNight(null, null, at(2026, 11, 6, 20, 1)))
    }
}
