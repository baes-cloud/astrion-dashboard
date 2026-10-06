package com.custom.astrion.power

import android.os.BatteryManager

/** The dock and battery rules, kept free of Android state so they can be tested. */
object Battery {
    /**
     * Some MediaTek chargers report NOT_CHARGING rather than FULL once the
     * battery tops out on the dock; at or above this level that still
     * counts as docked.
     */
    const val TOPPED_UP_PCT = 95

    /** On the dock but this many points below its peak: the dock isn't keeping up. */
    const val DOCK_DRAIN_PCT = 3

    /** Plugged in and the battery actually charging (or already full). */
    fun takingCharge(plugged: Boolean, status: Int, pct: Int?): Boolean = plugged && when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> (pct ?: 0) >= TOPPED_UP_PCT
        else -> false
    }

    /**
     * Fallen [DOCK_DRAIN_PCT] below the highest level seen since it was put
     * on the dock. A remote whose dock pins barely touch can report
     * "charging" while drawing less than it uses: 141 sat "plugged" from 68%
     * down to 42% on 4 Oct.
     */
    fun draining(peakPct: Int?, pct: Int): Boolean = (peakPct ?: pct) - pct >= DOCK_DRAIN_PCT

    /** On the dock but not keeping up: not taking charge, or draining. */
    fun dockFault(plugged: Boolean, status: Int, pct: Int, peakPct: Int?): Boolean =
        plugged && (!takingCharge(plugged, status, pct) || draining(peakPct, pct))

    fun statusName(status: Int): String = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
        BatteryManager.BATTERY_STATUS_FULL -> "full"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
        else -> "unknown"
    }
}
