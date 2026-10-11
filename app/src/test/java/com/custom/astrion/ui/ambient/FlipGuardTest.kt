package com.custom.astrion.ui.ambient

import org.junit.Assert.assertEquals
import org.junit.Test

class FlipGuardTest {

    @Test fun firstItemShowsImmediately() {
        val g = FlipGuard()
        assertEquals("A", g.update("A", 0))
    }

    @Test fun switchToBrandNewItemShowsImmediately() {
        val g = FlipGuard()
        g.update("A", 0)
        assertEquals("B", g.update("B", 100))
    }

    @Test fun returnToJustLeftItemIsHeldThenShown() {
        val g = FlipGuard(returnWindowMs = 5_000, holdMs = 2_000)
        g.update("A", 0)
        g.update("B", 1_000) // leaves A at t=1000
        // Back to A at t=1500, inside the 5s window: held, still showing B.
        assertEquals("B", g.update("A", 1_500))
        // Still held before 2s elapse.
        assertEquals("B", g.update("A", 3_000))
        // Hold elapsed and still A: now it shows.
        assertEquals("A", g.update("A", 3_600))
    }

    @Test fun movingOnDuringHoldAbandonsIt() {
        val g = FlipGuard(returnWindowMs = 5_000, holdMs = 2_000)
        g.update("A", 0)
        g.update("B", 1_000)
        assertEquals("B", g.update("A", 1_500)) // start holding A
        // Player jumps to a different new item: shown at once, hold dropped.
        assertEquals("C", g.update("C", 1_800))
    }

    @Test fun returnAfterWindowShowsImmediately() {
        val g = FlipGuard(returnWindowMs = 5_000, holdMs = 2_000)
        g.update("A", 0)
        g.update("B", 1_000) // leaves A at t=1000
        // Back to A well after the window: no hold.
        assertEquals("A", g.update("A", 7_000))
    }

    @Test fun stoppingClearsToNull() {
        val g = FlipGuard()
        g.update("A", 0)
        assertEquals(null, g.update(null, 500))
    }
}
