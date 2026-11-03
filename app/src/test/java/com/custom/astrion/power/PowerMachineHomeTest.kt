package com.custom.astrion.power

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The return-home timer when ticks go missing. PowerMachineTest's simulator
 * always ticks on time; the real timer stops while the CPU sleeps, so these
 * feed inputs with no tick in between.
 */
class PowerMachineHomeTest {

    private val cfg = PowerMachineTest.CFG

    private fun homes(step: Step) = step.effects.count { it == PowerEffect.ReturnHome }

    @Test
    fun `a wake-up touch after the deadline still goes home, once`() {
        val touched = PowerMachine.reduce(PowerState(lastActivityMs = 0), PowerInput.Activity(0), cfg).state
        // Slept through the deadline (300 s); picked up at 20 min.
        val woke = PowerMachine.reduce(touched, PowerInput.Activity(1_200_000), cfg)
        assertEquals(1, homes(woke))
        // The touch restarts the timer for next time.
        assertEquals(1_500_000L, woke.state.homeDueMs)
        assertEquals(0, homes(PowerMachine.reduce(woke.state, PowerInput.Activity(1_201_000), cfg)))
    }

    @Test
    fun `the screen coming back on after the deadline goes home`() {
        var s = PowerMachine.reduce(PowerState(lastActivityMs = 0), PowerInput.Activity(0), cfg).state
        s = PowerMachine.reduce(s, PowerInput.Screen(on = false, nowMs = 120_000), cfg).state
        val on = PowerMachine.reduce(s, PowerInput.Screen(on = true, nowMs = 900_000), cfg)
        assertEquals(1, homes(on))
    }

    @Test
    fun `a touch before the deadline doesn't go home`() {
        val touched = PowerMachine.reduce(PowerState(lastActivityMs = 0), PowerInput.Activity(0), cfg).state
        val again = PowerMachine.reduce(touched, PowerInput.Activity(299_999), cfg)
        assertEquals(0, homes(again))
    }

    @Test
    fun `home_after_seconds 0 never goes home late either`() {
        val off = cfg.copy(homeAfterMs = 0)
        val touched = PowerMachine.reduce(PowerState(lastActivityMs = 0), PowerInput.Activity(0), off).state
        assertNull(touched.homeDueMs)
        assertEquals(0, homes(PowerMachine.reduce(touched, PowerInput.Activity(3_600_000), off)))
    }
}
