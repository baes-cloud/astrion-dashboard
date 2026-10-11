package com.custom.astrion.viewmodel

import android.content.Context
import android.os.SystemClock
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.lifecycle.viewModelScope
import app.cash.turbine.test
import com.custom.astrion.config.AppConfig
import com.custom.astrion.config.AppConfigRepository
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.config.HotkeyConfig
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.input.HardwareKey
import com.custom.astrion.input.HardwareKeyRouter
import com.custom.astrion.ir.IrBlaster
import com.custom.astrion.test.MainDispatcherRule
import com.custom.astrion.ui.StrongHaptics
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InputViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val context = mockk<Context>(relaxed = true)
    private val router = HardwareKeyRouter()
    private val irBlaster = mockk<IrBlaster>(relaxed = true)
    private val client = mockk<HaClient>(relaxed = true)
    private val configRepo = mockk<AppConfigRepository>(relaxed = true)
    private val haptics = mockk<StrongHaptics>(relaxed = true)

    private lateinit var configFlow: MutableStateFlow<DashboardLoader.Result>
    private var simulatedUptime = 5000L

    private val testHotkeys = listOf(
        HotkeyConfig(key = "LIGHT", page = "Lights"),
        HotkeyConfig(key = "SCENE", service = "scene.turn_on", entityId = "scene.relax"),
        HotkeyConfig(key = "CUSTOM_1", service = "astrion.open_settings"),
        HotkeyConfig(key = "CUSTOM_2", service = "astrion.launch", data = mapOf("package" to "com.test.app")),
        HotkeyConfig(key = "MUTE", service = "astrion.toggle_mute", entityId = "media_player.main"),
    )

    private val baseConfig = AppConfig(
        pages = emptyList(),
        hotkeys = testHotkeys,
        options = mapOf(
            "ir_mode" to mapOf(
                "repeat" to 2,
                "toggle_key" to "MENU",
                "codes" to mapOf("POWER" to "0xE0E040BF"),
            ),
        ),
    )

    private lateinit var viewModel: InputViewModel

    @Before
    fun setUp() {
        mockkStatic(SystemClock::class)
        every { SystemClock.uptimeMillis() } answers { simulatedUptime }

        every { irBlaster.available } returns true
        every { irBlaster.statusLine() } returns "IR emitter ready · 38 kHz"
        coEvery { irBlaster.blastAsync(any(), any()) } returns true

        configFlow = MutableStateFlow(DashboardLoader.Result(baseConfig, null))
        every { configRepo.configFlow } returns configFlow
        every { configRepo.getCurrentResult() } answers { configFlow.value }

        viewModel = InputViewModel(context, router, irBlaster, client, configRepo, haptics)
    }

    @After
    fun tearDown() {
        unmockkStatic(SystemClock::class)
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    private fun mockKeyEvent(
        keyCode: Int,
        action: Int = KeyEvent.ACTION_DOWN,
        repeatCount: Int = 0,
    ): KeyEvent {
        val event = mockk<KeyEvent>()
        every { event.keyCode } returns keyCode
        every { event.action } returns action
        every { event.repeatCount } returns repeatCount
        return event
    }

    @Test
    fun initialInputState() {
        val state = viewModel.uiState.value
        assertFalse(state.isIrMode)
        assertNull(state.irLastKey)
        assertTrue(state.isIrAvailable)
        assertEquals("IR emitter ready · 38 kHz", state.irStatusLine)
        assertEquals(2, state.irRepeat)
    }

    @Test
    fun pageJumpHotkeyEmitsNavigationEffect() = runVmTest {
        viewModel.effects.test {
            // Key LIGHT = keycode 134
            val event = mockKeyEvent(134, KeyEvent.ACTION_DOWN)
            val handled = viewModel.handleKeyEvent(event, isScreensaverOn = false)
            assertTrue(handled)

            val effect1 = awaitItem()
            assertEquals(InputEffect.DismissTopSheet, effect1)
            val effect2 = awaitItem()
            assertEquals(InputEffect.NavigateToPage("Lights"), effect2)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun standardServiceHotkeyCallsHaClient() = runVmTest {
        // Key SCENE = keycode 136
        val down = mockKeyEvent(136, KeyEvent.ACTION_DOWN)
        val up = mockKeyEvent(136, KeyEvent.ACTION_UP)

        viewModel.handleKeyEvent(down, isScreensaverOn = false)
        viewModel.handleKeyEvent(up, isScreensaverOn = false)
        runCurrent()

        verify {
            client.callService(
                match { it.domain == "scene" && it.service == "turn_on" && it.entityId == "scene.relax" },
                confirm = true
            )
        }
    }

    @Test
    fun openSettingsServiceHotkeyEmitsOpenSettingsEffect() = runVmTest {
        viewModel.effects.test {
            // Key CUSTOM_1 = keycode 138
            val down = mockKeyEvent(138, KeyEvent.ACTION_DOWN)
            val up = mockKeyEvent(138, KeyEvent.ACTION_UP)

            viewModel.handleKeyEvent(down, isScreensaverOn = false)
            viewModel.handleKeyEvent(up, isScreensaverOn = false)
            runCurrent()

            val effect = awaitItem()
            assertEquals(InputEffect.OpenSystemSettings, effect)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun launchPackageServiceHotkeyEmitsLaunchEffect() = runVmTest {
        viewModel.effects.test {
            // Key CUSTOM_2 = keycode 139
            val down = mockKeyEvent(139, KeyEvent.ACTION_DOWN)
            val up = mockKeyEvent(139, KeyEvent.ACTION_UP)

            viewModel.handleKeyEvent(down, isScreensaverOn = false)
            viewModel.handleKeyEvent(up, isScreensaverOn = false)
            runCurrent()

            val effect = awaitItem()
            assertEquals(InputEffect.LaunchPackage("com.test.app"), effect)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun toggleMuteServiceCallsHaClient() = runVmTest {
        val playerEntity = EntityState(
            "media_player.main",
            "playing",
            JsonObject(mapOf("is_volume_muted" to JsonPrimitive(false)))
        )
        every { client.live } returns mapOf("media_player.main" to playerEntity)

        // Key MUTE = keycode 91
        val down = mockKeyEvent(91, KeyEvent.ACTION_DOWN)
        val up = mockKeyEvent(91, KeyEvent.ACTION_UP)

        viewModel.handleKeyEvent(down, isScreensaverOn = false)
        viewModel.handleKeyEvent(up, isScreensaverOn = false)
        runCurrent()

        verify {
            client.callService(
                match {
                    it.domain == "media_player" &&
                    it.service == "volume_mute" &&
                    it.entityId == "media_player.main"
                }
            )
        }
    }

    @Test
    fun voiceKeyEmitsTriggerVoiceKeyEffect() = runVmTest {
        viewModel.effects.test {
            // Key VOICE = keycode 133
            val event = mockKeyEvent(133, KeyEvent.ACTION_DOWN, repeatCount = 0)
            val handled = viewModel.handleKeyEvent(event, isScreensaverOn = false)
            assertTrue(handled)

            val effect = awaitItem()
            assertEquals(InputEffect.TriggerVoiceKey, effect)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun unboundKeyReturnsFalse() {
        val event = mockKeyEvent(999, KeyEvent.ACTION_DOWN)
        val handled = viewModel.handleKeyEvent(event, isScreensaverOn = false)
        assertFalse(handled)
    }

    @Test
    fun screensaverActiveSwallowsKeyAndTouchEvent() {
        val keyEvent = mockKeyEvent(134, KeyEvent.ACTION_DOWN)
        assertTrue(viewModel.handleKeyEvent(keyEvent, isScreensaverOn = true))

        val motionEvent = mockk<MotionEvent>(relaxed = true)
        assertTrue(viewModel.handleTouchEvent(motionEvent, isScreensaverOn = true))
        assertFalse(viewModel.handleTouchEvent(motionEvent, isScreensaverOn = false))
    }

    @Test
    fun toggleIrModeTogglesStateWithDebounce() = runVmTest {
        assertFalse(viewModel.uiState.value.isIrMode)

        // Advance simulated time past debounce threshold
        simulatedUptime += 1000L
        viewModel.onIntent(InputIntent.ToggleIrMode)
        assertTrue(viewModel.uiState.value.isIrMode)

        // Close IR Mode
        viewModel.onIntent(InputIntent.CloseIrMode)
        assertFalse(viewModel.uiState.value.isIrMode)
    }

    @Test
    fun toggleIrModeShowsToastWhenUnavailable() = runVmTest {
        every { irBlaster.available } returns false
        viewModel.effects.test {
            simulatedUptime += 1000L
            viewModel.onIntent(InputIntent.ToggleIrMode)

            val effect = awaitItem()
            assertTrue(effect is InputEffect.ShowToast && effect.message.contains("No IR emitter"))

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun irModeInterceptsDpadKeysAndTransmitsSamsungCode() = runVmTest {
        simulatedUptime += 1000L
        viewModel.onIntent(InputIntent.ToggleIrMode)
        assertTrue(viewModel.uiState.value.isIrMode)

        // Key UP = keycode 19 (Samsung code 0xE0E006F9)
        val event = mockKeyEvent(19, KeyEvent.ACTION_DOWN)
        val handled = viewModel.handleKeyEvent(event, isScreensaverOn = false)
        assertTrue(handled)
        runCurrent()

        coVerify { irBlaster.blastAsync(0xE0E006F9L, 2) }
        val state = viewModel.uiState.value
        assertNotNull(state.irLastKey)
        assertTrue(state.irLastKey!!.contains("UP → 0xE0E006F9"))
    }

    @Test
    fun irModeReportsFailedTransmission() = runVmTest {
        coEvery { irBlaster.blastAsync(any(), any()) } returns false

        simulatedUptime += 1000L
        viewModel.onIntent(InputIntent.ToggleIrMode)

        val event = mockKeyEvent(20, KeyEvent.ACTION_DOWN) // Key DOWN
        viewModel.handleKeyEvent(event, isScreensaverOn = false)
        runCurrent()

        assertTrue(viewModel.uiState.value.irLastKey!!.contains("DOWN — transmit FAILED"))
    }

    @Test
    fun blastIrIntentTransmitsDirectly() = runVmTest {
        viewModel.onIntent(InputIntent.BlastIr(HardwareKey.POWER))
        runCurrent()

        coVerify { irBlaster.blastAsync(0xE0E040BFL, 2) }
        assertTrue(viewModel.uiState.value.irLastKey!!.contains("POWER → 0xE0E040BF"))
    }
}
