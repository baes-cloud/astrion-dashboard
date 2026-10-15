package com.custom.astrion.viewmodel

import app.cash.turbine.test
import com.custom.astrion.config.AppConfig
import com.custom.astrion.config.AppConfigRepository
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.config.PageConfig
import com.custom.astrion.ha.ConnectionState
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.test.MainDispatcherRule
import com.custom.astrion.ui.AlertAction
import com.custom.astrion.voice.VoicePhase
import com.custom.astrion.voice.VoiceSession
import com.custom.astrion.voice.VoiceState
import androidx.lifecycle.viewModelScope
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {

    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    private val client = mockk<HaClient>(relaxed = true)
    private val voice = mockk<VoiceSession>(relaxed = true)
    private val configRepo = mockk<AppConfigRepository>(relaxed = true)

    private val connectionFlow = MutableStateFlow(ConnectionState.DISCONNECTED)
    private val outcomesFlow = MutableSharedFlow<HaClient.CallOutcome>(extraBufferCapacity = 16)
    private val entitiesFlow = MutableStateFlow<Map<String, EntityState>>(emptyMap())
    private val voiceStateFlow = MutableStateFlow(VoiceState())
    private lateinit var configFlow: MutableStateFlow<DashboardLoader.Result>

    private val initialPages = listOf(
        PageConfig("Main", emptyList()),
        PageConfig("Lights", emptyList()),
        PageConfig("Media", emptyList()),
    )
    private val baseConfig = AppConfig(
        pages = initialPages,
        startPage = 0,
        options = mapOf(
            "alarm" to mapOf(
                "ringing_entity" to "input_boolean.alarm",
                "snooze" to mapOf("service" to "alarm.snooze", "entity_id" to "alarm.master"),
                "stop" to mapOf("service" to "alarm.dismiss", "entity_id" to "alarm.master"),
            ),
            "alerts" to listOf(
                mapOf("entity" to "binary_sensor.leak", "severity" to "alarm"),
            ),
        ),
    )

    private lateinit var viewModel: DashboardViewModel

    @Before
    fun setUp() {
        configFlow = MutableStateFlow(DashboardLoader.Result(baseConfig, null))

        every { client.connection } returns connectionFlow
        every { client.outcomes } returns outcomesFlow
        every { client.entities } returns entitiesFlow
        every { client.snapshot() } answers { entitiesFlow.value }
        every { client.live } answers { entitiesFlow.value }

        every { voice.state } returns voiceStateFlow

        every { configRepo.configFlow } returns configFlow
        every { configRepo.getCurrentResult() } answers { configFlow.value }

        viewModel = DashboardViewModel(client, voice, configRepo)
    }

    private fun runVmTest(block: suspend TestScope.() -> Unit) = runTest {
        try {
            block()
        } finally {
            viewModel.viewModelScope.cancel()
        }
    }

    @Test
    fun initialStateLoadsFromConfigRepository() {
        val state = viewModel.uiState.value
        assertEquals(baseConfig, state.config)
        assertNull(state.configNotice)
        assertEquals(0, state.currentPage)
        assertNull(state.navTarget)
        assertEquals(ConnectionState.DISCONNECTED, state.connectionState)
        assertNull(state.activeAlarm)
        assertTrue(state.activeAlerts.isEmpty())
        assertNull(state.toastMessage)
    }

    @Test
    fun configUpdatesFlowUpdatesUiStateAndNotice() = runVmTest {
        val updatedConfig = baseConfig.copy(startPage = 1)
        val noticeText = "Config reloaded from disk"

        configFlow.value = DashboardLoader.Result(updatedConfig, noticeText)
        runCurrent()

        val state = viewModel.uiState.value
        assertEquals(updatedConfig, state.config)
        assertEquals(noticeText, state.configNotice)
    }

    @Test
    fun reloadConfigIntentCallsRepository() = runVmTest {
        coEvery { configRepo.loadConfig(any()) } returns DashboardLoader.Result(baseConfig, null)

        viewModel.onIntent(DashboardIntent.ReloadConfig(force = true))
        runCurrent()

        coVerify { configRepo.loadConfig(force = true) }
    }

    @Test
    fun navigateToValidPageIndexUpdatesStateAndIncrementsEpoch() {
        viewModel.onIntent(DashboardIntent.NavigateToPage(1))

        val state = viewModel.uiState.value
        assertEquals(1, state.currentPage)
        assertEquals(1, state.navTarget)
        assertEquals(1, state.navEpoch)

        viewModel.onIntent(DashboardIntent.NavigateToPage(2))
        val nextState = viewModel.uiState.value
        assertEquals(2, nextState.currentPage)
        assertEquals(2, nextState.navTarget)
        assertEquals(2, nextState.navEpoch)
    }

    @Test
    fun navigateToInvalidPageIndexIgnored() {
        viewModel.onIntent(DashboardIntent.NavigateToPage(99))

        val state = viewModel.uiState.value
        assertEquals(0, state.currentPage)
        assertNull(state.navTarget)
        assertEquals(0, state.navEpoch)
    }

    @Test
    fun navigateToPageByNameCaseInsensitive() {
        viewModel.onIntent(DashboardIntent.NavigateToPageByName("lights"))

        val state = viewModel.uiState.value
        assertEquals(1, state.currentPage)
        assertEquals(1, state.navTarget)
        assertEquals(1, state.navEpoch)
    }

    @Test
    fun navigateToUnknownPageNameIgnored() {
        viewModel.onIntent(DashboardIntent.NavigateToPageByName("NonExistent"))

        val state = viewModel.uiState.value
        assertEquals(0, state.currentPage)
        assertNull(state.navTarget)
    }

    @Test
    fun navTargetHandledClearsTarget() {
        viewModel.onIntent(DashboardIntent.NavigateToPage(1))
        assertEquals(1, viewModel.uiState.value.navTarget)

        viewModel.onIntent(DashboardIntent.NavTargetHandled)
        assertNull(viewModel.uiState.value.navTarget)
        assertEquals(1, viewModel.uiState.value.currentPage)
    }

    @Test
    fun connectionStateUpdatesFromHaClient() = runVmTest {
        connectionFlow.value = ConnectionState.CONNECTED
        runCurrent()
        assertEquals(ConnectionState.CONNECTED, viewModel.uiState.value.connectionState)

        connectionFlow.value = ConnectionState.ERROR
        runCurrent()
        assertEquals(ConnectionState.ERROR, viewModel.uiState.value.connectionState)
    }

    @Test
    fun voiceStateUpdatesFromVoiceSession() = runVmTest {
        val testVoiceState = VoiceState(phase = VoicePhase.LISTENING, transcript = "hello")
        voiceStateFlow.value = testVoiceState
        runCurrent()

        assertEquals(testVoiceState, viewModel.uiState.value.voiceState)
    }

    @Test
    fun toggleVoiceWhenIdleStartsVoiceSession() {
        voiceStateFlow.value = VoiceState(phase = VoicePhase.IDLE)
        viewModel.onIntent(DashboardIntent.ToggleVoice)
        verify { voice.start(any()) }
    }

    @Test
    fun toggleVoiceWhenListeningStopsListening() {
        voiceStateFlow.value = VoiceState(phase = VoicePhase.LISTENING)
        viewModel.onIntent(DashboardIntent.ToggleVoice)
        verify { voice.stopListening() }
    }

    @Test
    fun cancelVoiceAndStopListeningIntents() {
        viewModel.onIntent(DashboardIntent.CancelVoice)
        verify { voice.cancel() }

        viewModel.onIntent(DashboardIntent.StopVoiceListening)
        verify { voice.stopListening() }
    }

    @Test
    fun callOutcomeDoneEmitsSuccessToast() = runVmTest {
        val call = ServiceCall("light", "turn_on", "light.kitchen")
        outcomesFlow.emit(HaClient.CallOutcome(call, HaClient.CallOutcome.Kind.DONE))
        runCurrent()

        val toast = viewModel.uiState.value.toastMessage
        assertNotNull(toast)
        assertEquals("✓  light.kitchen", toast?.text)
        assertFalse(toast?.bad == true)
    }

    @Test
    fun callOutcomeFailedEmitsErrorToast() = runVmTest {
        val call = ServiceCall("light", "turn_on", "light.kitchen")
        outcomesFlow.emit(HaClient.CallOutcome(call, HaClient.CallOutcome.Kind.FAILED, error = "Connection refused"))
        runCurrent()

        val toast = viewModel.uiState.value.toastMessage
        assertNotNull(toast)
        assertEquals("Didn't work: light.kitchen", toast?.text)
        assertEquals("Connection refused", toast?.detail)
        assertTrue(toast?.bad == true)
    }

    @Test
    fun callOutcomeQueuedEmitsOfflineToast() = runVmTest {
        val call = ServiceCall("switch", "turn_off")
        outcomesFlow.emit(HaClient.CallOutcome(call, HaClient.CallOutcome.Kind.QUEUED))
        runCurrent()

        val toast = viewModel.uiState.value.toastMessage
        assertNotNull(toast)
        assertEquals("Offline", toast?.text)
        assertTrue(toast?.bad == true)
    }

    @Test
    fun callOutcomeDroppedEmitsDroppedToast() = runVmTest {
        val call = ServiceCall("switch", "turn_off", "switch.fan")
        outcomesFlow.emit(HaClient.CallOutcome(call, HaClient.CallOutcome.Kind.DROPPED))
        runCurrent()

        val toast = viewModel.uiState.value.toastMessage
        assertNotNull(toast)
        assertEquals("Not sent: switch.fan", toast?.text)
        assertTrue(toast?.bad == true)
    }

    @Test
    fun clearToastClearsToastMessage() = runVmTest {
        val call = ServiceCall("light", "turn_on", "light.living_room")
        outcomesFlow.emit(HaClient.CallOutcome(call, HaClient.CallOutcome.Kind.DONE))
        runCurrent()
        assertNotNull(viewModel.uiState.value.toastMessage)

        viewModel.onIntent(DashboardIntent.ClearToast)
        runCurrent()
        assertNull(viewModel.uiState.value.toastMessage)
    }

    @Test
    fun alarmStateUpdatesAndEmitsWakeScreenEffect() = runVmTest {
        viewModel.effects.test {
            entitiesFlow.value = mapOf(
                "input_boolean.alarm" to EntityState("input_boolean.alarm", "on", JsonObject(emptyMap())),
            )
            // Advance time for alarm watcher loop
            advanceTimeBy(1100L)
            runCurrent()

            val state = viewModel.uiState.value
            assertNotNull(state.activeAlarm)
            assertTrue(state.activeAlarm?.ringing == true)

            val effect1 = awaitItem()
            assertTrue(effect1 is DashboardEffect.WakeScreenForAlarm && effect1.isRinging)
            val effect2 = awaitItem()
            assertTrue(effect2 is DashboardEffect.BringActivityToFront)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun snoozeAndDismissAlarmCallHaService() {
        viewModel.onIntent(DashboardIntent.SnoozeAlarm)
        verify { client.callService(match { it.domain == "alarm" && it.service == "snooze" && it.entityId == "alarm.master" }) }

        viewModel.onIntent(DashboardIntent.DismissAlarm)
        verify { client.callService(match { it.domain == "alarm" && it.service == "dismiss" && it.entityId == "alarm.master" }) }
    }

    @Test
    fun hideAlarmUpdatesState() {
        assertFalse(viewModel.uiState.value.isAlarmHidden)
        viewModel.onIntent(DashboardIntent.HideAlarm)
        assertTrue(viewModel.uiState.value.isAlarmHidden)
    }

    @Test
    fun alertObservationDismissAndActionTrigger() = runVmTest {
        val now = System.currentTimeMillis()
        val leakEntity = EntityState("binary_sensor.leak", "on", JsonObject(emptyMap()), now, now)
        entitiesFlow.value = mapOf("binary_sensor.leak" to leakEntity)

        // Advance watcher
        advanceTimeBy(1100L)
        runCurrent()

        val alerts = viewModel.uiState.value.activeAlerts
        assertEquals(1, alerts.size)
        val alertToken = alerts.first().token

        // Trigger alert action
        val testAction = AlertAction("Resolve", "light.turn_off", "light.kitchen", emptyMap())
        viewModel.onIntent(DashboardIntent.TriggerAlertAction(testAction))
        verify { client.callService(match { it.domain == "light" && it.service == "turn_off" && it.entityId == "light.kitchen" }) }

        // Dismiss alert
        viewModel.onIntent(DashboardIntent.DismissAlert(alertToken))
        runCurrent()

        assertTrue(viewModel.uiState.value.hiddenAlertTokens.contains(alertToken))
        assertTrue(viewModel.uiState.value.activeAlerts.isEmpty())
    }

    @Test
    fun initConnectsClientWhenDisconnected() {
        verify { client.connect() }
    }

    @Test
    fun retryConnectionIntentConnectsClient() {
        viewModel.onIntent(DashboardIntent.RetryConnection)
        verify(atLeast = 2) { client.connect() }
    }

    @Test
    fun foregroundIntentReconnectsClientWhenDisconnected() {
        viewModel.onIntent(DashboardIntent.Foreground)
        verify(atLeast = 2) { client.connect() }
    }
}
