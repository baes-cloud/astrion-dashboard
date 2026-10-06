package com.custom.astrion.power

import android.os.BatteryManager.BATTERY_STATUS_CHARGING
import android.os.BatteryManager.BATTERY_STATUS_DISCHARGING
import android.os.BatteryManager.BATTERY_STATUS_FULL
import android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryTest {
    @Test
    fun takingChargeNeedsPowerAndCharge() {
        assertTrue(Battery.takingCharge(true, BATTERY_STATUS_CHARGING, 50))
        assertTrue(Battery.takingCharge(true, BATTERY_STATUS_FULL, 100))
        assertFalse(Battery.takingCharge(false, BATTERY_STATUS_CHARGING, 50))
        assertFalse(Battery.takingCharge(true, BATTERY_STATUS_DISCHARGING, 50))
    }

    @Test
    fun notChargingCountsOnlyWhenToppedUp() {
        assertTrue(Battery.takingCharge(true, BATTERY_STATUS_NOT_CHARGING, 95))
        assertFalse(Battery.takingCharge(true, BATTERY_STATUS_NOT_CHARGING, 94))
    }

    @Test
    fun drainingOnTheDockIsAFault() {
        assertFalse(Battery.dockFault(true, BATTERY_STATUS_CHARGING, pct = 66, peakPct = 68))
        assertTrue(Battery.dockFault(true, BATTERY_STATUS_CHARGING, pct = 65, peakPct = 68))
        assertTrue(Battery.dockFault(true, BATTERY_STATUS_DISCHARGING, pct = 68, peakPct = 68))
        assertFalse(Battery.dockFault(false, BATTERY_STATUS_DISCHARGING, pct = 40, peakPct = null))
    }

    @Test
    fun statusNames() {
        assertEquals("not_charging", Battery.statusName(BATTERY_STATUS_NOT_CHARGING))
        assertEquals("unknown", Battery.statusName(-1))
    }
}
