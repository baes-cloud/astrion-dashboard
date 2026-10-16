package com.custom.astrion.power

import android.os.BatteryManager
import com.custom.astrion.config.AppConfig

/**
 * The dock, screensaver and keep-screen-on rules as one pure state machine.
 *
 * Inputs are what happens to the remote (battery broadcasts, the screen going
 * on or off, a touch or key press, an alarm holding the screen, motion, and
 * the passage of time). [reduce] folds one into a [PowerState] and says what
 * to do once ([PowerEffect]); [outputs] says what must hold while in that
 * state (keep-on, backlight, foreground, motion-wake, the narrowed HA
 * subscription). Time only moves through the inputs' `nowMs`, and
 * [nextDeadline] says when the state would next change on its own, so the
 * caller needs one timer and the whole thing is testable as a table.
 */
object PowerMachine {

    /** How often the screensaver re-checks day/night for its backlight. */
    const val NIGHT_RECHECK_MS = 30_000L

    fun reduce(prev: PowerState, input: PowerInput, cfg: PowerSettings): Step {
        val now = input.nowMs
        val effects = mutableListOf<PowerEffect>()
        var s = when (input) {
            is PowerInput.Battery -> prev.copy(
                pct = input.pct,
                plugged = input.plugged,
                status = input.status,
                plugChangedAtMs = if (input.plugged != prev.plugged) now else prev.plugChangedAtMs,
            )
            is PowerInput.Screen -> when {
                input.on == prev.screenOn -> prev
                !input.on -> prev.copy(
                    screenOn = false,
                    screenOffAtMs = now,
                    // Off the dock the screensaver ends with the screen; on it,
                    // the screen coming back shows it again.
                    screensaver = prev.screensaver && (prev.docked || prev.plugged),
                )
                else -> {
                    val on = prev.copy(screenOn = true, narrowed = false)
                    when {
                        // Woken on the dock (a power bounce, the dock wake) after
                        // the idle time: straight back to the screensaver.
                        on.docked && screensaverDue(on.copy(screensaver = false), cfg, now) -> on.copy(screensaver = true)
                        on.screensaver -> on
                        // Otherwise someone turned it on.
                        else -> activity(on, cfg, now)
                    }
                }
            }
            is PowerInput.Activity -> activity(prev, cfg, now)
            is PowerInput.Hold -> prev.copy(holdScreen = input.hold)
            is PowerInput.Motion -> {
                if (!prev.screenOn && !prev.docked) effects += PowerEffect.WakeScreen
                prev
            }
            is PowerInput.Tick -> prev
        }
        s = settle(s, cfg, now, effects)
        if (s.pct != prev.pct || s.docked != prev.docked || s.dockFault != prev.dockFault) {
            effects += PowerEffect.ReportBattery
        }
        return Step(s, effects)
    }

    /** A touch or key press: the screensaver ends and the home timer restarts. */
    private fun activity(s: PowerState, cfg: PowerSettings, now: Long): PowerState = s.copy(
        lastActivityMs = now,
        screensaver = false,
        homeDueMs = if (cfg.homeAfterMs > 0) now + cfg.homeAfterMs else null,
        // Handled while unplugged: it has been picked up, not bounced on its pins.
        docked = s.docked && s.plugged,
        peakPct = if (s.docked && !s.plugged) null else s.peakPct,
    )

    /** Everything that happens by itself once enough time has passed. */
    private fun settle(start: PowerState, cfg: PowerSettings, now: Long, effects: MutableList<PowerEffect>): PowerState {
        var s = start
        if (dockDue(s, cfg, now)) {
            s = s.copy(docked = true, peakPct = s.pct)
            if (!s.screenOn && cfg.screensaverEnabled && cfg.keepScreenOnDocked) effects += PowerEffect.WakeScreen
        }
        if (undockDue(s, cfg, now)) s = s.copy(docked = false, peakPct = null)
        val pct = s.pct
        if (s.docked && pct != null && pct > (s.peakPct ?: -1)) s = s.copy(peakPct = pct)
        if (screensaverDue(s, cfg, now)) s = s.copy(screensaver = true)
        if (narrowDue(s, cfg, now)) s = s.copy(narrowed = true)
        val home = s.homeDueMs
        if (home != null && now >= home) {
            effects += PowerEffect.ReturnHome
            s = s.copy(homeDueMs = null)
        }
        return s
    }

    private fun dockDue(s: PowerState, cfg: PowerSettings, now: Long) =
        s.plugged && !s.docked && now - s.plugChangedAtMs >= cfg.dockDebounceMs

    private fun undockDue(s: PowerState, cfg: PowerSettings, now: Long) =
        !s.plugged && s.docked && now - s.plugChangedAtMs >= cfg.undockDebounceMs

    private fun screensaverDue(s: PowerState, cfg: PowerSettings, now: Long): Boolean {
        val idle = cfg.idleMs(s.docked)
        return s.screenOn && !s.screensaver && cfg.screensaverEnabled && idle > 0 && now - s.lastActivityMs >= idle
    }

    private fun narrowDue(s: PowerState, cfg: PowerSettings, now: Long) =
        !s.screenOn && !s.narrowed && cfg.screenOffFilterMs >= 0 && now - s.screenOffAtMs >= cfg.screenOffFilterMs

    /** When [reduce] would next change the state with only time passing; null if never. */
    fun nextDeadline(s: PowerState, cfg: PowerSettings, now: Long): Long? = listOfNotNull(
        (s.plugChangedAtMs + cfg.dockDebounceMs).takeIf { s.plugged && !s.docked },
        (s.plugChangedAtMs + cfg.undockDebounceMs).takeIf { !s.plugged && s.docked },
        cfg.idleMs(s.docked).let { idle ->
            (s.lastActivityMs + idle).takeIf { s.screenOn && !s.screensaver && cfg.screensaverEnabled && idle > 0 }
        },
        (s.screenOffAtMs + cfg.screenOffFilterMs).takeIf { !s.screenOn && !s.narrowed && cfg.screenOffFilterMs >= 0 },
        s.homeDueMs,
        (now + NIGHT_RECHECK_MS).takeIf { s.screensaver && s.screenOn },
    ).minOrNull()

    fun outputs(s: PowerState, cfg: PowerSettings, isNight: Boolean): PowerOutputs {
        val b = when {
            !s.screensaver -> -1f
            isNight -> cfg.nightBrightness
            !s.docked -> cfg.undockedBrightness
            else -> cfg.brightness
        }
        return PowerOutputs(
            keepScreenOn = (s.docked && cfg.screensaverEnabled && cfg.keepScreenOnDocked) || s.holdScreen,
            brightness = if (b < 0f) -1f else b.coerceIn(0.01f, 1f),
            foreground = s.screenOn && !s.screensaver,
            motionWake = !s.screenOn && !s.docked,
            narrowed = s.narrowed,
        )
    }
}

data class PowerState(
    val pct: Int? = null,
    val plugged: Boolean = false,
    val status: Int = BatteryManager.BATTERY_STATUS_UNKNOWN,
    /** Plugged in for the dock debounce; stays so through shorter contact drops. */
    val docked: Boolean = false,
    /** When [plugged] last changed, for both debounces. */
    val plugChangedAtMs: Long = 0L,
    /** Highest level since docking, for [Battery.draining]. */
    val peakPct: Int? = null,
    val screenOn: Boolean = true,
    val screenOffAtMs: Long = 0L,
    val lastActivityMs: Long = 0L,
    val screensaver: Boolean = false,
    /** The HA subscription is narrowed to what the screen-off features need. */
    val narrowed: Boolean = false,
    /** An alarm or alert is showing that must keep the screen on. */
    val holdScreen: Boolean = false,
    val homeDueMs: Long? = null,
) {
    val charging: Boolean get() = status == BatteryManager.BATTERY_STATUS_CHARGING
    val dockDraining: Boolean get() = docked && pct != null && Battery.draining(peakPct, pct)
    /** Plugged in but not charging, or charging and still losing ground. */
    val dockFault: Boolean get() = Battery.dockFault(plugged, status, pct ?: 0, peakPct.takeIf { docked })
}

sealed interface PowerInput {
    val nowMs: Long

    data class Battery(val pct: Int?, val plugged: Boolean, val status: Int, override val nowMs: Long) : PowerInput
    data class Screen(val on: Boolean, override val nowMs: Long) : PowerInput
    /** A touch or key press. */
    data class Activity(override val nowMs: Long) : PowerInput
    /** An alarm or alert that holds the screen on started (true) or ended. */
    data class Hold(val hold: Boolean, override val nowMs: Long) : PowerInput
    data class Motion(override val nowMs: Long) : PowerInput
    data class Tick(override val nowMs: Long) : PowerInput
}

sealed interface PowerEffect {
    data object WakeScreen : PowerEffect
    data object ReturnHome : PowerEffect
    /** Level, docked or dock_fault changed: post the battery sensor. */
    data object ReportBattery : PowerEffect
}

data class Step(val state: PowerState, val effects: List<PowerEffect>)

data class PowerOutputs(
    val keepScreenOn: Boolean,
    /** Window backlight override, -1 for the system's. */
    val brightness: Float,
    /** Dashboard visible: HA updates are worth drawing. */
    val foreground: Boolean,
    val motionWake: Boolean,
    val narrowed: Boolean,
)

/** The `power` and `screensaver` settings the machine reads, in milliseconds. */
data class PowerSettings(
    val dockDebounceMs: Long = 5_000,
    val undockDebounceMs: Long = 0,
    val screensaverEnabled: Boolean = true,
    val dockedIdleMs: Long = 45_000,
    /** -1: no screensaver off the dock. */
    val undockedIdleMs: Long = 90_000,
    val keepScreenOnDocked: Boolean = true,
    val brightness: Float = 0.22f,
    val nightBrightness: Float = 0.05f,
    val undockedBrightness: Float = 0.08f,
    /** 0: never return to the start page. */
    val homeAfterMs: Long = 300_000,
    /** -1: never narrow. */
    val screenOffFilterMs: Long = 30_000,
) {
    fun idleMs(docked: Boolean): Long = if (docked) dockedIdleMs else undockedIdleMs

    companion object {
        fun from(config: AppConfig): PowerSettings {
            val power = config.features.power
            val ss = config.features.screensaver
            return PowerSettings(
                dockDebounceMs = power.dockDebounceSeconds.coerceAtLeast(0) * 1000L,
                undockDebounceMs = power.undockDebounceSeconds.coerceAtLeast(0) * 1000L,
                screensaverEnabled = ss.enabled,
                dockedIdleMs = ss.idleMs,
                undockedIdleMs = if (ss.alwaysTrigger) ss.idleMs else ss.undockedIdleMs,
                keepScreenOnDocked = ss.keepScreenOn,
                brightness = ss.brightness,
                nightBrightness = ss.nightBrightness,
                undockedBrightness = ss.undockedBrightness,
                homeAfterMs = power.homeAfterSeconds.coerceAtLeast(0) * 1000L,
                screenOffFilterMs = power.screenOffFilterSeconds.let { if (it < 0) -1L else it * 1000L },
            )
        }
    }
}
