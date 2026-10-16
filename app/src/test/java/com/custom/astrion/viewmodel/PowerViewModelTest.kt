package com.custom.astrion.viewmodel

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.custom.astrion.config.AppConfig
import com.custom.astrion.config.AppConfigRepository
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.ha.ConnectionState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.power.MotionWakeController
import com.custom.astrion.test.MainDispatcherRule
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PowerViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context = mockk<Context>(relaxed = true)
    private val client = mockk<HaClient>(relaxed = true)
    private val motionWake = mockk<MotionWakeController>(relaxed = true)
    private val configRepo = mockk<AppConfigRepository>(relaxed = true)

    private val connectionFlow = MutableStateFlow(ConnectionState.DISCONNECTED)
    private lateinit var configFlow: MutableStateFlow<DashboardLoader.Result>

    /** The VM's clock is the test scheduler's virtual time. */
    private fun now() = 10_000L + mainDispatcherRule.testDispatcher.scheduler.currentTime

    private val baseConfig = AppConfig(
        pages = emptyList(),
        options = mapOf(
            "power" to mapOf(
                "report_entity" to "sensor.astrion_battery",
                "report_name" to "Remote Battery",
                "dock_debounce_seconds" to 2,
                "home_after_seconds" to 5,
            ),
            "screensaver" to mapOf(
                "enabled" to true,
                "idle_seconds" to 10,
                "undocked_idle_seconds" to 10,
                "brightness" to 0.5f,
            ),
        ),
    )

    private lateinit var viewModel: PowerViewModel

    @Before
    fun setUp() {
        configFlow = MutableStateFlow(DashboardLoader.Result(baseConfig, null))
        every { configRepo.configFlow } returns configFlow
        every { configRepo.getCurrentResult() } answers { configFlow.value }

        every { client.connection } returns connectionFlow
        every { client.snapshot() } returns emptyMap()

        viewModel = PowerViewModel(context, client, motionWake, configRepo, clock = ::now)
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    private fun mockBatteryIntent(
        level: Int,
        scale: Int = 100,
        plugged: Int = BatteryManager.BATTERY_PLUGGED_AC,
        status: Int = BatteryManager.BATTERY_STATUS_CHARGING,
    ): Intent {
        val intent = mockk<Intent>()
        every { intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns level
        every { intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns scale
        every { intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) } returns plugged
        every { intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) } returns status
        return intent
    }

    @Test
    fun initialPowerState() {
        val state = viewModel.uiState.value
        assertNull(state.batteryPct)
        assertFalse(state.isPlugged)
        assertFalse(state.isCharging)
        assertFalse(state.isDocked)
        assertFalse(state.isScreensaverOn)
        assertTrue(state.isScreenOn)
        assertEquals(-1, state.swallowKey)
        assertFalse(state.swallowTouch)
    }

    @Test
    fun batteryChangedUpdatesUiStateAndPostsTelemetry() = runVmTest {
        val intent = mockBatteryIntent(level = 85, scale = 100, plugged = BatteryManager.BATTERY_PLUGGED_AC, status = BatteryManager.BATTERY_STATUS_CHARGING)

        viewModel.onIntent(PowerIntent.BatteryChanged(intent))
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(85, state.batteryPct)
        assertTrue(state.isPlugged)
        assertTrue(state.isCharging)
        assertEquals(BatteryManager.BATTERY_STATUS_CHARGING, state.batteryStatus)

        coVerify(timeout = 2000L) {
            client.postState(
                "sensor.astrion_battery",
                "85",
                match {
                    it["plugged"] == true &&
                    it["charging"] == true &&
                    it["friendly_name"] == "Remote Battery" &&
                    it["unit_of_measurement"] == "%"
                }
            )
        }
    }

    @Test
    fun reconnectPostsBatteryTelemetry() = runVmTest {
        val intent = mockBatteryIntent(level = 90)
        viewModel.onIntent(PowerIntent.BatteryChanged(intent))
        runCurrent()

        // Transition to CONNECTED
        connectionFlow.value = ConnectionState.CONNECTED
        runCurrent()

        coVerify(timeout = 2000L, atLeast = 2) {
            client.postState("sensor.astrion_battery", "90", any())
        }
    }

    @Test
    fun takingChargeTransitionsToDockedAfterDebounce() = runVmTest {
        val chargingIntent = mockBatteryIntent(level = 80, plugged = BatteryManager.BATTERY_PLUGGED_AC, status = BatteryManager.BATTERY_STATUS_CHARGING)

        viewModel.onIntent(PowerIntent.BatteryChanged(chargingIntent))
        runCurrent()

        // Debounce is 2 seconds: immediately not docked
        assertFalse(viewModel.uiState.value.isDocked)

        // Advance 2.5 seconds
        advanceTimeBy(2500L)
        runCurrent()

        assertTrue(viewModel.uiState.value.isDocked)
    }

    @Test
    fun unpluggingImmediatelyUndocksWithoutDelay() = runVmTest {
        val chargingIntent = mockBatteryIntent(level = 80, plugged = BatteryManager.BATTERY_PLUGGED_AC, status = BatteryManager.BATTERY_STATUS_CHARGING)
        viewModel.onIntent(PowerIntent.BatteryChanged(chargingIntent))
        advanceTimeBy(2500L)
        runCurrent()
        assertTrue(viewModel.uiState.value.isDocked)

        // Unplug
        val unpluggedIntent = mockBatteryIntent(level = 80, plugged = 0, status = BatteryManager.BATTERY_STATUS_DISCHARGING)
        viewModel.onIntent(PowerIntent.BatteryChanged(unpluggedIntent))
        runCurrent()

        // Undocked immediately
        assertFalse(viewModel.uiState.value.isDocked)
    }

    @Test
    fun dockFaultDetectedWhenDrainingOnDock() = runVmTest {
        // Dock at 68%
        val chargingIntent = mockBatteryIntent(level = 68, plugged = BatteryManager.BATTERY_PLUGGED_AC, status = BatteryManager.BATTERY_STATUS_CHARGING)
        viewModel.onIntent(PowerIntent.BatteryChanged(chargingIntent))
        advanceTimeBy(2500L)
        runCurrent()
        assertTrue(viewModel.uiState.value.isDocked)
        assertFalse(viewModel.uiState.value.isDockFault)

        // Drop to 65% while plugged
        val drainIntent = mockBatteryIntent(level = 65, plugged = BatteryManager.BATTERY_PLUGGED_AC, status = BatteryManager.BATTERY_STATUS_CHARGING)
        viewModel.onIntent(PowerIntent.BatteryChanged(drainIntent))
        runCurrent()

        assertTrue(viewModel.uiState.value.isDockFault)
        assertTrue(viewModel.uiState.value.isDockDraining)
    }

    @Test
    fun userActivityResetsScreensaverAndTriggersReturnHome() = runVmTest {
        viewModel.events.test {
            // Dismiss screensaver on user activity
            viewModel.onIntent(PowerIntent.UserActivity)
            assertFalse(viewModel.uiState.value.isScreensaverOn)

            // home_after_seconds is 5s
            advanceTimeBy(5100L)
            runCurrent()

            val event = awaitItem()
            assertEquals(PowerEvent.ReturnHome, event)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun screensaverTimeoutActivatesScreensaver() = runVmTest {
        assertFalse(viewModel.uiState.value.isScreensaverOn)

        // idle_seconds = 10s
        advanceTimeBy(10_001L)
        runCurrent()

        assertTrue(viewModel.uiState.value.isScreensaverOn)

        // Dismiss screensaver intent
        viewModel.onIntent(PowerIntent.DismissScreensaver)
        assertFalse(viewModel.uiState.value.isScreensaverOn)
    }

    @Test
    fun screenOffArmsMotionWakeAndScreenOnDisarms() = runVmTest {
        // Turn screen off
        viewModel.onIntent(PowerIntent.ScreenPowerChanged(isScreenOn = false))
        runCurrent()

        assertFalse(viewModel.uiState.value.isScreenOn)
        verify { motionWake.start(any()) }

        // Turn screen on
        viewModel.onIntent(PowerIntent.ScreenPowerChanged(isScreenOn = true))
        runCurrent()

        assertTrue(viewModel.uiState.value.isScreenOn)
        verify { motionWake.stop() }
    }

    @Test
    fun screenOffNarrowsAfterTheFilterDelayAndScreenOnRestores() = runVmTest {
        viewModel.onIntent(PowerIntent.ScreenPowerChanged(isScreenOn = false))
        advanceTimeBy(29_000L)
        runCurrent()
        verify(exactly = 0) { client.setEntityFilter(any()) }

        advanceTimeBy(1_100L)
        runCurrent()
        verify(exactly = 1) { client.setEntityFilter(match { it != null }) }

        viewModel.onIntent(PowerIntent.ScreenPowerChanged(isScreenOn = true))
        runCurrent()
        verify(exactly = 1) { client.setEntityFilter(null) }
    }

    @Test
    fun holdScreenKeepsItOnOffTheDockUntilReleased() = runVmTest {
        assertFalse(viewModel.uiState.value.keepScreenOn)
        viewModel.onIntent(PowerIntent.HoldScreen(true))
        assertTrue(viewModel.uiState.value.keepScreenOn)
        advanceTimeBy(600_000L)
        runCurrent()
        assertTrue("an alarm holds the screen with no re-wake loop", viewModel.uiState.value.keepScreenOn)
        viewModel.onIntent(PowerIntent.HoldScreen(false))
        assertFalse(viewModel.uiState.value.keepScreenOn)
    }

    @Test
    fun motionWakeCallbackEmitsWakeScreenEvent() = runVmTest {
        val callbackSlot = slot<() -> Unit>()
        every { motionWake.start(capture(callbackSlot)) } answers { }

        viewModel.events.test {
            viewModel.onIntent(PowerIntent.ScreenPowerChanged(isScreenOn = false))
            runCurrent()

            assertTrue(callbackSlot.isCaptured)
            // Trigger motion wake
            callbackSlot.captured.invoke()
            runCurrent()

            val event = awaitItem()
            assertEquals(PowerEvent.WakeScreen, event)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun swallowKeyAndTouchIntents() {
        viewModel.onIntent(PowerIntent.SetSwallowKey(136))
        assertEquals(136, viewModel.uiState.value.swallowKey)

        viewModel.onIntent(PowerIntent.ClearSwallowKey)
        assertEquals(-1, viewModel.uiState.value.swallowKey)

        viewModel.onIntent(PowerIntent.SetSwallowTouch(true))
        assertTrue(viewModel.uiState.value.swallowTouch)

        viewModel.onIntent(PowerIntent.SetSwallowTouch(false))
        assertFalse(viewModel.uiState.value.swallowTouch)
    }

    @Test
    fun undockDebounceAbsorbsBriefDockPinFlicker() = runVmTest {
        val configWithUndockDebounce = baseConfig.copy(
            options = baseConfig.options + (
                "power" to mapOf(
                    "report_entity" to "sensor.astrion_battery",
                    "dock_debounce_seconds" to 2,
                    "undock_debounce_seconds" to 15,
                    "home_after_seconds" to 5,
                )
            )
        )
        configFlow.value = DashboardLoader.Result(configWithUndockDebounce, null)
        runCurrent()

        val chargingIntent = mockBatteryIntent(level = 80, plugged = BatteryManager.BATTERY_PLUGGED_AC, status = BatteryManager.BATTERY_STATUS_CHARGING)
        viewModel.onIntent(PowerIntent.BatteryChanged(chargingIntent))
        advanceTimeBy(2500L)
        runCurrent()
        assertTrue(viewModel.uiState.value.isDocked)

        // Brief 5-second dock pin drop
        val unpluggedIntent = mockBatteryIntent(level = 80, plugged = 0, status = BatteryManager.BATTERY_STATUS_DISCHARGING)
        viewModel.onIntent(PowerIntent.BatteryChanged(unpluggedIntent))
        advanceTimeBy(5000L)
        runCurrent()
        assertTrue("Should remain docked during 5s pin bounce", viewModel.uiState.value.isDocked)

        // Pin reconnects before 15s debounce expires
        viewModel.onIntent(PowerIntent.BatteryChanged(chargingIntent))
        advanceTimeBy(12_000L)
        runCurrent()
        assertTrue("Should still be docked after pin reconnects", viewModel.uiState.value.isDocked)

        // Now unplug for > 15s
        viewModel.onIntent(PowerIntent.BatteryChanged(unpluggedIntent))
        advanceTimeBy(16_000L)
        runCurrent()
        assertFalse("Should undock after full 15s debounce", viewModel.uiState.value.isDocked)
    }

    @Test
    fun screenPowerOnPreservesActiveScreensaver() = runVmTest {
        advanceTimeBy(10_001L)
        runCurrent()
        assertTrue(viewModel.uiState.value.isScreensaverOn)

        // Simulate OS waking screen on AC plug-in bounce
        viewModel.onIntent(PowerIntent.ScreenPowerChanged(isScreenOn = true))
        runCurrent()
        assertTrue("Active screensaver must not be dismissed by ACTION_SCREEN_ON alone", viewModel.uiState.value.isScreensaverOn)
    }

    @Test
    fun badDockStillCountsAsDockedAndReportsFault() = runVmTest {
        // Plugged in, but the pins barely touch: NOT_CHARGING at 60%.
        val badDock = mockBatteryIntent(level = 60, status = BatteryManager.BATTERY_STATUS_NOT_CHARGING)
        viewModel.onIntent(PowerIntent.BatteryChanged(badDock))
        advanceTimeBy(2500L)
        runCurrent()

        val state = viewModel.uiState.value
        assertTrue("plugged in is docked, charging or not", state.isDocked)
        assertTrue(state.isDockFault)
        assertTrue("a docked remote keeps its screen on", state.keepScreenOn)
        coVerify(timeout = 2000L) {
            client.postState("sensor.astrion_battery", "60", match { it["docked"] == true && it["dock_fault"] == true })
        }
    }

    @Test
    fun dockedScreensaverNeverNarrowsTheSubscription() = runVmTest {
        val charging = mockBatteryIntent(level = 80)
        viewModel.onIntent(PowerIntent.BatteryChanged(charging))
        advanceTimeBy(10_001L)
        runCurrent()
        assertTrue(viewModel.uiState.value.isScreensaverOn)

        // Well past screen_off_filter_seconds, screen still on.
        advanceTimeBy(120_000L)
        runCurrent()
        verify(exactly = 0) { client.setEntityFilter(match { it != null }) }
    }
}
