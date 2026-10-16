package com.custom.astrion.viewmodel

import com.custom.astrion.ui.ActiveAlert
import com.custom.astrion.ui.AlarmUiState
import com.custom.astrion.ui.AlertSpec
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which alarms and alerts hold the screen on (the power state machine's Hold input). */
class HoldsScreenTest {
    private fun alarm(ringing: Boolean) = AlarmUiState(ringing, null, 0, null, null, null)
    private fun alert(severity: String) = ActiveAlert(
        AlertSpec("a", "lock.front", listOf("unlocked"), 0, null, null, severity, "t", null, null, emptyList()),
        null,
        "tok-$severity",
    )

    @Test
    fun ringingAlarmHolds() = assertTrue(DashboardUiState(activeAlarm = alarm(ringing = true)).holdsScreen)

    @Test
    fun hiddenOrSnoozedAlarmDoesNot() {
        assertFalse(DashboardUiState(activeAlarm = alarm(ringing = true), isAlarmHidden = true).holdsScreen)
        assertFalse(DashboardUiState(activeAlarm = alarm(ringing = false)).holdsScreen)
    }

    @Test
    fun alarmSeverityAlertHolds() = assertTrue(DashboardUiState(activeAlerts = listOf(alert("alarm"))).holdsScreen)

    @Test
    fun warningAlertOnlyWakesItDoesNotHold() {
        assertFalse(DashboardUiState(activeAlerts = listOf(alert("warning"))).holdsScreen)
        assertFalse(DashboardUiState(activeAlerts = listOf(alert("info"))).holdsScreen)
    }

    @Test
    fun nothingShowingDoesNotHold() = assertFalse(DashboardUiState().holdsScreen)
}
