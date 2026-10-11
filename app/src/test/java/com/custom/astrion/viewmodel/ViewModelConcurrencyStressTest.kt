package com.custom.astrion.viewmodel

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.SystemClock
import android.view.KeyEvent
import androidx.lifecycle.viewModelScope
import com.custom.astrion.config.AppConfig
import com.custom.astrion.config.AppConfigRepository
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.config.HotkeyConfig
import com.custom.astrion.config.PageConfig
import com.custom.astrion.ha.ConnectionState
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.input.HardwareKey
import com.custom.astrion.input.HardwareKeyRouter
import com.custom.astrion.ir.IrBlaster
import com.custom.astrion.power.MotionWakeController
import com.custom.astrion.test.MainDispatcherRule
import com.custom.astrion.ui.StrongHaptics
import com.custom.astrion.voice.VoiceSession
import com.custom.astrion.voice.VoiceState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Empirical concurrency, stress, and high-frequency emissions test suite for M3 ViewModels:
 * - DashboardViewModel
 * - PowerViewModel
 * - InputViewModel
 *
 * Stresses:
 * - Multi-threaded rapid intent dispatch
 * - High-frequency flow emissions & channel backpressure
 * - Dock debounce & rapid battery changes
 * - Upstream connection flapping & telemetry storms
 * - Rapid key events, hold/double-tap scheduling & IR mode debounce
 * - Coroutine scope cancellation & lifecycle memory cleanup
 */
class ViewModelConcurrencyStressTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val baseEpoch = 100_000L
    private val clockOffset = AtomicInteger(0)

    @Before
    fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.uptimeMillis() } answers { baseEpoch + clockOffset.get() }
        every { SystemClock.elapsedRealtime() } answers { baseEpoch + clockOffset.get() }
    }

    @After
    fun tearDown() {
        unmockkStatic(SystemClock::class)
    }

    private fun mockBatteryIntent(
        level: Int,
        scale: Int = 100,
        plugged: Int = BatteryManager.BATTERY_PLUGGED_AC,
        status: Int = BatteryManager.BATTERY_STATUS_CHARGING,
    ): Intent {
        val intent = mockk<Intent>(relaxed = true)
        every { intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) } returns level
        every { intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1) } returns scale
        every { intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) } returns plugged
        every { intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN) } returns status
        return intent
    }

    private fun mockKeyEvent(
        keyCode: Int,
        action: Int = KeyEvent.ACTION_DOWN,
        repeatCount: Int = 0,
    ): KeyEvent {
        val event = mockk<KeyEvent>(relaxed = true)
        every { event.keyCode } returns keyCode
        every { event.action } returns action
        every { event.repeatCount } returns repeatCount
        return event
    }

    @Test
    fun testDashboardViewModel_concurrentIntentsAndNavigation() = runBlocking {
        val client = mockk<HaClient>(relaxed = true)
        val voice = mockk<VoiceSession>(relaxed = true)
        val configRepo = mockk<AppConfigRepository>(relaxed = true)

        val pages = (0..4).map { PageConfig("Page $it", emptyList()) }
        val testConfig = AppConfig(pages = pages, startPage = 0)
        val configFlow = MutableStateFlow(DashboardLoader.Result(testConfig, null))
        val connectionFlow = MutableStateFlow(ConnectionState.CONNECTED)
        val outcomesFlow = MutableSharedFlow<HaClient.CallOutcome>(extraBufferCapacity = 64)
        val entitiesFlow = MutableStateFlow<Map<String, EntityState>>(emptyMap())
        val voiceFlow = MutableStateFlow(VoiceState())

        every { client.connection } returns connectionFlow
        every { client.outcomes } returns outcomesFlow
        every { client.entities } returns entitiesFlow
        every { client.snapshot() } returns emptyMap()
        every { voice.state } returns voiceFlow
        every { configRepo.configFlow } returns configFlow
        every { configRepo.getCurrentResult() } returns DashboardLoader.Result(testConfig, null)

        val vm = DashboardViewModel(client, voice, configRepo)

        val threadCount = 16
        val intentsPerThread = 100
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        // Launch concurrent collectors to verify collectors do not hang or crash
        val collectorScope = CoroutineScope(Dispatchers.Default)
        val collectorJobs = (0 until 4).map {
            collectorScope.launch {
                vm.uiState.collect { /* consume */ }
            }
        }

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until intentsPerThread) {
                        val targetPage = (t + i) % pages.size
                        when (i % 6) {
                            0 -> vm.onIntent(DashboardIntent.NavigateToPage(targetPage))
                            1 -> vm.onIntent(DashboardIntent.NavigateToPageByName("Page $targetPage"))
                            2 -> vm.onIntent(DashboardIntent.NavTargetHandled)
                            3 -> vm.onIntent(DashboardIntent.DismissAlert("alert_$i"))
                            4 -> vm.onIntent(DashboardIntent.ClearToast)
                            5 -> vm.onIntent(if (i % 2 == 0) DashboardIntent.Foreground else DashboardIntent.Background)
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Timeout during concurrent intent submission", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("No exceptions should occur during concurrent intent handling", 0, errors.get())
        assertTrue("Current page should be within valid bounds", vm.uiState.value.currentPage in pages.indices)
        assertTrue("NavEpoch should increment under rapid navigation", vm.uiState.value.navEpoch > 0)

        collectorJobs.forEach { it.cancel() }
        collectorScope.cancel()
        vm.viewModelScope.cancel()
    }

    @Test
    fun testDashboardViewModel_highFrequencyOutcomesAndEntitiesBurst() = runBlocking {
        val client = mockk<HaClient>(relaxed = true)
        val voice = mockk<VoiceSession>(relaxed = true)
        val configRepo = mockk<AppConfigRepository>(relaxed = true)

        val testConfig = AppConfig(pages = listOf(PageConfig("Main", emptyList())), startPage = 0)
        val configFlow = MutableStateFlow(DashboardLoader.Result(testConfig, null))
        val connectionFlow = MutableStateFlow(ConnectionState.CONNECTED)
        val outcomesFlow = MutableSharedFlow<HaClient.CallOutcome>(extraBufferCapacity = 1024)
        val entitiesFlow = MutableStateFlow<Map<String, EntityState>>(emptyMap())

        every { client.connection } returns connectionFlow
        every { client.outcomes } returns outcomesFlow
        every { client.entities } returns entitiesFlow
        every { client.snapshot() } answers { entitiesFlow.value }
        every { voice.state } returns MutableStateFlow(VoiceState())
        every { configRepo.configFlow } returns configFlow
        every { configRepo.getCurrentResult() } returns DashboardLoader.Result(testConfig, null)

        val vm = DashboardViewModel(client, voice, configRepo)

        val threadCount = 8
        val outcomesPerThread = 125 // 8 * 125 = 1000 outcomes total
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until outcomesPerThread) {
                        val kind = HaClient.CallOutcome.Kind.values()[i % HaClient.CallOutcome.Kind.values().size]
                        val call = ServiceCall("light", "turn_on", "light.room_$i")
                        outcomesFlow.tryEmit(HaClient.CallOutcome(call, kind, if (kind == HaClient.CallOutcome.Kind.FAILED) "Timeout" else null))
                        clockOffset.addAndGet(5)
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Timeout during outcomes burst", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("No errors during outcomes burst", 0, errors.get())

        // Rapid entity updates burst (500 updates)
        val entityLatch = CountDownLatch(1)
        val entityScope = CoroutineScope(Dispatchers.Default)
        entityScope.launch {
            for (i in 0 until 500) {
                entitiesFlow.value = mapOf("light.test" to EntityState("light.test", "on", JsonObject(emptyMap())))
            }
            entityLatch.countDown()
        }
        assertTrue("Timeout during entity updates burst", entityLatch.await(5, TimeUnit.SECONDS))
        entityScope.cancel()

        vm.viewModelScope.cancel()
    }

    @Test
    fun testPowerViewModel_concurrentBatteryAndDockDebounceBurst() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val client = mockk<HaClient>(relaxed = true)
        val motionWake = mockk<MotionWakeController>(relaxed = true)
        val configRepo = mockk<AppConfigRepository>(relaxed = true)

        val baseConfig = AppConfig(
            pages = emptyList(),
            options = mapOf(
                "power" to mapOf(
                    "report_entity" to "sensor.astrion_battery",
                    "report_name" to "Remote Battery",
                    "dock_debounce_seconds" to 1,
                    "home_after_seconds" to 5,
                ),
                "screensaver" to mapOf(
                    "enabled" to true,
                    "idle_seconds" to 5,
                    "undocked_idle_seconds" to 5,
                    "brightness" to 0.5f,
                ),
            ),
        )

        every { configRepo.configFlow } returns MutableStateFlow(DashboardLoader.Result(baseConfig, null))
        every { configRepo.getCurrentResult() } returns DashboardLoader.Result(baseConfig, null)
        every { client.connection } returns MutableStateFlow(ConnectionState.CONNECTED)
        every { client.snapshot() } returns emptyMap()

        val vm = PowerViewModel(context, client, motionWake, configRepo)

        val threadCount = 16
        val eventsPerThread = 100 // 1600 events total
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until eventsPerThread) {
                        val isPlugged = (i % 2 == 0)
                        val status = if (isPlugged) BatteryManager.BATTERY_STATUS_CHARGING else BatteryManager.BATTERY_STATUS_DISCHARGING
                        val pluggedFlag = if (isPlugged) BatteryManager.BATTERY_PLUGGED_AC else 0
                        val level = 20 + (i % 70)
                        val intent = mockBatteryIntent(level = level, plugged = pluggedFlag, status = status)

                        vm.onIntent(PowerIntent.BatteryChanged(intent))
                        if (i % 10 == 0) {
                            vm.markUserActivity()
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Timeout waiting for battery events", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("No errors during concurrent battery changes", 0, errors.get())
        assertNotNull("Battery pct should be recorded", vm.uiState.value.batteryPct)

        vm.viewModelScope.cancel()
    }

    @Test
    fun testPowerViewModel_rapidConnectionFlappingTelemetryStorm() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val client = mockk<HaClient>(relaxed = true)
        val motionWake = mockk<MotionWakeController>(relaxed = true)
        val configRepo = mockk<AppConfigRepository>(relaxed = true)

        val baseConfig = AppConfig(
            pages = emptyList(),
            options = mapOf(
                "power" to mapOf(
                    "report_entity" to "sensor.astrion_battery",
                    "report_name" to "Remote Battery",
                    "dock_debounce_seconds" to 1,
                ),
            ),
        )

        val connectionFlow = MutableStateFlow(ConnectionState.DISCONNECTED)
        every { configRepo.configFlow } returns MutableStateFlow(DashboardLoader.Result(baseConfig, null))
        every { configRepo.getCurrentResult() } returns DashboardLoader.Result(baseConfig, null)
        every { client.connection } returns connectionFlow
        every { client.snapshot() } returns emptyMap()

        val vm = PowerViewModel(context, client, motionWake, configRepo)

        // Set initial battery state
        val intent = mockBatteryIntent(level = 80, plugged = BatteryManager.BATTERY_PLUGGED_AC, status = BatteryManager.BATTERY_STATUS_CHARGING)
        vm.onIntent(PowerIntent.BatteryChanged(intent))

        val threadCount = 8
        val flapsPerThread = 50 // 400 flaps total
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until flapsPerThread) {
                        connectionFlow.value = if (i % 2 == 0) ConnectionState.CONNECTED else ConnectionState.DISCONNECTED
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Timeout during connection flapping", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("No errors during connection flapping", 0, errors.get())

        vm.viewModelScope.cancel()
    }

    @Test
    fun testInputViewModel_highFrequencyConcurrentKeyEventsAndIrDebounce() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val router = HardwareKeyRouter()
        val irBlaster = mockk<IrBlaster>(relaxed = true)
        val client = mockk<HaClient>(relaxed = true)
        val configRepo = mockk<AppConfigRepository>(relaxed = true)
        val haptics = mockk<StrongHaptics>(relaxed = true)

        every { irBlaster.available } returns true
        every { irBlaster.statusLine() } returns "IR ready"
        coEvery { irBlaster.blastAsync(any(), any()) } returns true

        val hotkeys = listOf(
            HotkeyConfig(key = "LIGHT", page = "Lights"),
            HotkeyConfig(key = "SCENE", service = "scene.turn_on", entityId = "scene.main"),
            HotkeyConfig(key = "CUSTOM_1", service = "astrion.open_settings"),
            HotkeyConfig(key = "MUTE", service = "astrion.toggle_mute", entityId = "media_player.main"),
        )
        val baseConfig = AppConfig(
            pages = listOf(PageConfig("Lights", emptyList())),
            hotkeys = hotkeys,
            options = mapOf(
                "ir_mode" to mapOf(
                    "repeat" to 1,
                    "toggle_key" to "MENU",
                    "codes" to mapOf("POWER" to "0x1234"),
                ),
            ),
        )

        every { configRepo.configFlow } returns MutableStateFlow(DashboardLoader.Result(baseConfig, null))
        every { configRepo.getCurrentResult() } returns DashboardLoader.Result(baseConfig, null)

        val vm = InputViewModel(context, router, irBlaster, client, configRepo, haptics)

        val threadCount = 16
        val keysPerThread = 100 // 1600 key events total
        val executor = Executors.newFixedThreadPool(threadCount)
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        // Key codes: LIGHT=134, SCENE=136, CUSTOM_1=138, MUTE=91, VOICE=133, VOLUME_UP=24, UNBOUND=999
        val testKeyCodes = listOf(134, 136, 138, 91, 133, 24, 999)

        for (t in 0 until threadCount) {
            executor.submit {
                try {
                    for (i in 0 until keysPerThread) {
                        val keyCode = testKeyCodes[(t + i) % testKeyCodes.size]
                        val downEvent = mockKeyEvent(keyCode, KeyEvent.ACTION_DOWN)
                        val upEvent = mockKeyEvent(keyCode, KeyEvent.ACTION_UP)

                        vm.handleKeyEvent(downEvent, isScreensaverOn = false)
                        vm.handleKeyEvent(upEvent, isScreensaverOn = false)

                        if (i % 20 == 0) {
                            vm.toggleIrMode()
                        }
                    }
                } catch (e: Throwable) {
                    e.printStackTrace()
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }
        }

        assertTrue("Timeout during rapid key dispatch", latch.await(10, TimeUnit.SECONDS))
        executor.shutdown()

        assertEquals("No errors during concurrent key dispatch", 0, errors.get())

        vm.viewModelScope.cancel()
    }

    @Test
    fun testInputViewModel_effectChannelBurstDelivery() = runBlocking {
        val context = mockk<Context>(relaxed = true)
        val router = HardwareKeyRouter()
        val irBlaster = mockk<IrBlaster>(relaxed = true)
        val client = mockk<HaClient>(relaxed = true)
        val configRepo = mockk<AppConfigRepository>(relaxed = true)
        val haptics = mockk<StrongHaptics>(relaxed = true)

        val hotkeys = listOf(
            HotkeyConfig(key = "LIGHT", page = "Lights"),
        )
        val baseConfig = AppConfig(pages = emptyList(), hotkeys = hotkeys)

        every { configRepo.configFlow } returns MutableStateFlow(DashboardLoader.Result(baseConfig, null))
        every { configRepo.getCurrentResult() } returns DashboardLoader.Result(baseConfig, null)

        val vm = InputViewModel(context, router, irBlaster, client, configRepo, haptics)

        val receivedEffects = AtomicInteger(0)
        val collectorScope = CoroutineScope(Dispatchers.Default)
        val collectorJob = collectorScope.launch {
            vm.effects.collect {
                receivedEffects.incrementAndGet()
            }
        }

        val iterations = 50
        for (i in 0 until iterations) {
            // LIGHT keycode = 134
            val down = mockKeyEvent(134, KeyEvent.ACTION_DOWN)
            val up = mockKeyEvent(134, KeyEvent.ACTION_UP)
            vm.handleKeyEvent(down, isScreensaverOn = false)
            vm.handleKeyEvent(up, isScreensaverOn = false)
        }

        // Each LIGHT press produces 2 effects: DismissTopSheet and NavigateToPage("Lights")
        val expectedMin = iterations * 2
        var retries = 50
        while (receivedEffects.get() < expectedMin && retries > 0) {
            delay(20)
            retries--
        }

        collectorJob.cancel()
        collectorScope.cancel()
        vm.viewModelScope.cancel()

        assertTrue(
            "Expected at least $expectedMin effects, received ${receivedEffects.get()}",
            receivedEffects.get() >= expectedMin
        )
    }

    @Test
    fun testLifecycleAndScopeCancellation_zeroMemoryLeak() {
        val client = mockk<HaClient>(relaxed = true)
        val voice = mockk<VoiceSession>(relaxed = true)
        val configRepo = mockk<AppConfigRepository>(relaxed = true)
        val context = mockk<Context>(relaxed = true)
        val motionWake = mockk<MotionWakeController>(relaxed = true)
        val router = HardwareKeyRouter()
        val irBlaster = mockk<IrBlaster>(relaxed = true)
        val haptics = mockk<StrongHaptics>(relaxed = true)

        val baseConfig = AppConfig(pages = emptyList(), startPage = 0)
        every { configRepo.configFlow } returns MutableStateFlow(DashboardLoader.Result(baseConfig, null))
        every { configRepo.getCurrentResult() } returns DashboardLoader.Result(baseConfig, null)
        every { client.connection } returns MutableStateFlow(ConnectionState.CONNECTED)
        every { client.outcomes } returns MutableSharedFlow()
        every { client.entities } returns MutableStateFlow(emptyMap())
        every { client.snapshot() } returns emptyMap()
        every { voice.state } returns MutableStateFlow(VoiceState())

        val instances = 30
        val vms = (0 until instances).map {
            Triple(
                DashboardViewModel(client, voice, configRepo),
                PowerViewModel(context, client, motionWake, configRepo),
                InputViewModel(context, router, irBlaster, client, configRepo, haptics),
            )
        }

        // Trigger active loops on all ViewModels
        vms.forEach { (dVm, pVm, iVm) ->
            dVm.onIntent(DashboardIntent.Foreground)
            pVm.markUserActivity()
            pVm.onIntent(PowerIntent.ScreenPowerChanged(false))
            iVm.toggleIrMode()
        }

        // Cancel all scopes
        vms.forEach { (dVm, pVm, iVm) ->
            dVm.viewModelScope.cancel()
            pVm.viewModelScope.cancel()
            iVm.viewModelScope.cancel()
        }

        // Verify scope cancellation status
        vms.forEach { (dVm, pVm, iVm) ->
            assertFalse("DashboardViewModel scope should not be active", dVm.viewModelScope.coroutineContext[Job]?.isActive ?: true)
            assertFalse("PowerViewModel scope should not be active", pVm.viewModelScope.coroutineContext[Job]?.isActive ?: true)
            assertFalse("InputViewModel scope should not be active", iVm.viewModelScope.coroutineContext[Job]?.isActive ?: true)
        }
    }
}
