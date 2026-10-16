package com.custom.astrion.power

import android.os.BatteryManager.BATTERY_STATUS_CHARGING
import android.os.BatteryManager.BATTERY_STATUS_DISCHARGING
import android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized

/**
 * The dock / screensaver / keep-on rules as a table. Each row is a script of
 * inputs at given times and the state expected at given times; [simulate]
 * plays it the way PowerViewModel does, firing a tick at every deadline the
 * machine names in between.
 */
@RunWith(Parameterized::class)
class PowerMachineTest(private val case: Case) {

    @Test
    fun runs() {
        val trace = simulate(case.cfg, case.script)
        trace.failures.forEach { throw AssertionError("${case.name}: $it") }
        case.wakes?.let { assertEquals("${case.name}: WakeScreen times", it, trace.times(PowerEffect.WakeScreen)) }
        case.homes?.let { assertEquals("${case.name}: ReturnHome times", it, trace.times(PowerEffect.ReturnHome)) }
    }

    companion object {
        /** dashboard.json as shipped: 2 s dock debounce, 15 s undock debounce. */
        val CFG = PowerSettings(
            dockDebounceMs = 2_000,
            undockDebounceMs = 15_000,
            dockedIdleMs = 45_000,
            undockedIdleMs = 90_000,
            screenOffFilterMs = 30_000,
            homeAfterMs = 300_000,
            brightness = 0.22f,
            undockedBrightness = 0.15f,
        )

        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun cases(): List<Case> = listOf(
            Case("plugged and charging docks after the debounce") {
                battery(0, 80, plugged = true)
                expect(1_999, docked = false, keepOn = false)
                expect(2_000, docked = true, keepOn = true, fault = false)
            },
            Case("bad dock: plugged but not charging still docks, keeps the screen on, reports a fault") {
                battery(0, 60, plugged = true, status = BATTERY_STATUS_NOT_CHARGING)
                expect(2_000, docked = true, keepOn = true, fault = true)
                expect(600_000, docked = true, keepOn = true, fault = true)
            },
            Case("bad dock: charging but losing ground is a fault") {
                battery(0, 68, plugged = true)
                expect(2_000, docked = true, fault = false)
                battery(600_000, 65, plugged = true)
                expect(600_000, docked = true, keepOn = true, fault = true)
            },
            Case("pogo bounce shorter than the undock debounce stays docked", wakes = emptyList()) {
                battery(0, 80, plugged = true)
                battery(10_000, 80, plugged = false, status = BATTERY_STATUS_DISCHARGING)
                expect(20_000, docked = true, keepOn = true)
                battery(22_000, 80, plugged = true)
                expect(60_000, docked = true, keepOn = true)
            },
            Case("contact lost for longer than the undock debounce undocks") {
                battery(0, 80, plugged = true)
                battery(10_000, 80, plugged = false, status = BATTERY_STATUS_DISCHARGING)
                expect(24_999, docked = true)
                expect(25_000, docked = false, keepOn = false, motionWake = false)
            },
            Case("pick-up: a touch while unplugged undocks at once") {
                battery(0, 80, plugged = true)
                battery(10_000, 80, plugged = false, status = BATTERY_STATUS_DISCHARGING)
                touch(11_000)
                expect(11_000, docked = false, keepOn = false, screensaver = false)
            },
            Case("pick-up from the docked screensaver, then the undocked idle applies") {
                battery(0, 80, plugged = true)
                expect(45_000, docked = true, screensaver = true, brightness = 0.22f)
                battery(50_000, 80, plugged = false, status = BATTERY_STATUS_DISCHARGING)
                touch(51_000)
                expect(51_000, docked = false, screensaver = false, brightness = -1f)
                expect(140_999, screensaver = false)
                expect(141_000, screensaver = true, brightness = 0.15f)
            },
            Case("docked screensaver never narrows the HA subscription") {
                battery(0, 80, plugged = true)
                expect(200_000, screensaver = true, narrowed = false)
            },
            Case("screen off narrows after the filter delay; screen on restores") {
                screen(0, on = false)
                expect(29_999, narrowed = false, motionWake = true)
                expect(30_000, narrowed = true)
                screen(40_000, on = true)
                expect(40_000, narrowed = false, motionWake = false)
            },
            Case("screen_off_filter_seconds -1 never narrows", cfg = CFG.copy(screenOffFilterMs = -1)) {
                screen(0, on = false)
                expect(3_600_000, narrowed = false)
            },
            Case("docking with the screen off wakes it", wakes = listOf(3_000L)) {
                screen(0, on = false)
                battery(1_000, 80, plugged = true)
                expect(3_000, docked = true, keepOn = true, motionWake = false)
            },
            Case("motion wakes only off the dock with the screen off", wakes = listOf(1_000L)) {
                screen(0, on = false)
                motion(1_000)
                screen(2_000, on = true)
                motion(3_000)
                battery(4_000, 80, plugged = true)
                screen(10_000, on = false)
                motion(11_000)
            },
            Case("ringing alarm holds the screen on off the dock") {
                hold(10_000, true)
                expect(10_000, docked = false, keepOn = true)
                expect(200_000, keepOn = true)
                hold(210_000, false)
                expect(210_000, keepOn = false)
            },
            Case("hold on the dock: releasing it leaves the dock's keep-on") {
                battery(0, 80, plugged = true)
                hold(10_000, true)
                hold(20_000, false)
                expect(20_000, docked = true, keepOn = true)
            },
            Case("no hold, no dock: nothing keeps the screen on") {
                battery(0, 80, plugged = false, status = BATTERY_STATUS_DISCHARGING)
                expect(600_000, keepOn = false)
            },
            Case("screen back on at the dock after the idle time returns to the screensaver") {
                battery(0, 80, plugged = true)
                expect(45_000, screensaver = true)
                screen(50_000, on = false)
                expect(50_000, screensaver = true)
                screen(51_000, on = true)
                expect(51_000, screensaver = true)
            },
            Case("screen turned on off the dock counts as activity") {
                screen(0, on = false)
                screen(100_000, on = true)
                expect(100_000, screensaver = false)
                expect(189_999, screensaver = false)
                expect(190_000, screensaver = true)
            },
            Case("screen off off the dock ends the screensaver") {
                expect(90_000, screensaver = true)
                screen(100_000, on = false)
                expect(100_000, screensaver = false)
            },
            Case("no undocked screensaver when undocked_idle is -1", cfg = CFG.copy(undockedIdleMs = -1)) {
                expect(3_600_000, screensaver = false)
            },
            Case("screensaver disabled keeps neither screensaver nor dock keep-on", cfg = CFG.copy(screensaverEnabled = false)) {
                battery(0, 80, plugged = true)
                expect(600_000, docked = true, screensaver = false, keepOn = false)
            },
            Case("returns home after home_after_seconds without a touch", homes = listOf(400_000L)) {
                touch(0)
                touch(100_000)
                expect(350_000, screensaver = true)
                expect(500_000)
            },
            Case("home_after_seconds 0 never returns home", cfg = CFG.copy(homeAfterMs = 0), homes = emptyList()) {
                touch(0)
                expect(3_600_000)
            },
        )

        fun simulate(cfg: PowerSettings, script: List<Item>, isNight: Boolean = false): Trace {
            var s = PowerState(lastActivityMs = 0)
            var now = 0L
            val effects = mutableListOf<Pair<Long, PowerEffect>>()
            val failures = mutableListOf<String>()
            fun feed(input: PowerInput) {
                now = input.nowMs
                val step = PowerMachine.reduce(s, input, cfg)
                s = step.state
                step.effects.forEach { effects += input.nowMs to it }
            }
            for (item in script.sortedBy { it.at }) {
                // What PowerViewModel's timer does: a tick at every deadline up to the next input.
                while (true) {
                    val due = PowerMachine.nextDeadline(s, cfg, now) ?: break
                    if (due > item.at) break
                    feed(PowerInput.Tick(due))
                }
                when (item) {
                    is Item.In -> feed(item.input)
                    is Item.Expect -> {
                        feed(PowerInput.Tick(item.at))
                        item.check(s, PowerMachine.outputs(s, cfg, isNight))?.let { failures += "at ${item.at} ms: $it" }
                    }
                }
            }
            return Trace(effects, failures)
        }
    }

    sealed class Item(val at: Long) {
        class In(val input: PowerInput) : Item(input.nowMs)
        class Expect(
            at: Long,
            val docked: Boolean?, val keepOn: Boolean?, val screensaver: Boolean?, val fault: Boolean?,
            val narrowed: Boolean?, val motionWake: Boolean?, val brightness: Float?,
        ) : Item(at) {
            fun check(s: PowerState, o: PowerOutputs): String? = listOfNotNull(
                mismatch("docked", docked, s.docked),
                mismatch("keepScreenOn", keepOn, o.keepScreenOn),
                mismatch("screensaver", screensaver, s.screensaver),
                mismatch("dockFault", fault, s.dockFault),
                mismatch("narrowed", narrowed, o.narrowed),
                mismatch("motionWake", motionWake, o.motionWake),
                mismatch("brightness", brightness, o.brightness),
            ).takeIf { it.isNotEmpty() }?.joinToString()

            private fun mismatch(what: String, want: Any?, got: Any?) =
                if (want != null && want != got) "$what expected $want, was $got" else null
        }
    }

    class Script {
        val items = mutableListOf<Item>()
        fun battery(t: Long, pct: Int, plugged: Boolean, status: Int = BATTERY_STATUS_CHARGING) {
            items += Item.In(PowerInput.Battery(pct, plugged, status, t))
        }
        fun screen(t: Long, on: Boolean) { items += Item.In(PowerInput.Screen(on, t)) }
        fun touch(t: Long) { items += Item.In(PowerInput.Activity(t)) }
        fun hold(t: Long, hold: Boolean) { items += Item.In(PowerInput.Hold(hold, t)) }
        fun motion(t: Long) { items += Item.In(PowerInput.Motion(t)) }
        fun expect(
            t: Long, docked: Boolean? = null, keepOn: Boolean? = null, screensaver: Boolean? = null,
            fault: Boolean? = null, narrowed: Boolean? = null, motionWake: Boolean? = null, brightness: Float? = null,
        ) { items += Item.Expect(t, docked, keepOn, screensaver, fault, narrowed, motionWake, brightness) }
    }

    class Case(
        val name: String,
        val cfg: PowerSettings = CFG,
        val wakes: List<Long>? = null,
        val homes: List<Long>? = null,
        build: Script.() -> Unit,
    ) {
        val script: List<Item> = Script().apply(build).items
        override fun toString() = name
    }

    class Trace(val effects: List<Pair<Long, PowerEffect>>, val failures: List<String>) {
        fun times(effect: PowerEffect) = effects.filter { it.second == effect }.map { it.first }
    }
}

/** Rules outside the table: outputs and deadlines on their own. */
class PowerMachineRulesTest {
    private val cfg = PowerMachineTest.CFG

    @Test
    fun nightBrightnessWinsOnTheScreensaver() {
        val s = PowerState(screensaver = true, docked = true)
        assertEquals(cfg.nightBrightness, PowerMachine.outputs(s, cfg, isNight = true).brightness, 0f)
        assertEquals(cfg.brightness, PowerMachine.outputs(s, cfg, isNight = false).brightness, 0f)
        assertEquals(-1f, PowerMachine.outputs(s.copy(screensaver = false), cfg, isNight = true).brightness, 0f)
    }

    @Test
    fun reportsOnLevelDockAndFaultChangesOnly() {
        var s = PowerState()
        fun step(input: PowerInput): List<PowerEffect> = PowerMachine.reduce(s, input, cfg).also { s = it.state }.effects
        assertTrue(PowerEffect.ReportBattery in step(PowerInput.Battery(80, true, BATTERY_STATUS_CHARGING, 0)))
        assertFalse("same level, not yet docked", PowerEffect.ReportBattery in step(PowerInput.Battery(80, true, BATTERY_STATUS_CHARGING, 1_000)))
        assertTrue("docked", PowerEffect.ReportBattery in step(PowerInput.Tick(2_000)))
        assertFalse("a pin bounce alone", PowerEffect.ReportBattery in step(PowerInput.Battery(80, false, BATTERY_STATUS_DISCHARGING, 3_000)))
        assertTrue("fault", PowerEffect.ReportBattery in step(PowerInput.Battery(80, true, BATTERY_STATUS_NOT_CHARGING, 4_000)))
    }

    @Test
    fun noDeadlineWhenNothingIsPending() {
        val idle = PowerState(screenOn = false, narrowed = true)
        assertEquals(null, PowerMachine.nextDeadline(idle, cfg.copy(homeAfterMs = 0), 0))
    }

    @Test
    fun duplicateScreenBroadcastsChangeNothing() {
        val s = PowerState(lastActivityMs = 0, screensaver = true)
        val step = PowerMachine.reduce(s, PowerInput.Screen(on = true, nowMs = 5_000), cfg)
        assertEquals(s, step.state)
    }
}
