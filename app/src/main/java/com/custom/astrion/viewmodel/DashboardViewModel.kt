package com.custom.astrion.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.custom.astrion.config.AppConfig
import com.custom.astrion.config.AppConfigRepository
import com.custom.astrion.config.DashboardConfig
import com.custom.astrion.ha.ConnectionState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.*
import com.custom.astrion.voice.VoicePhase
import com.custom.astrion.voice.VoiceSession
import com.custom.astrion.voice.VoiceState
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

data class DashboardUiState(
    val config: AppConfig = DashboardConfig.fallback,
    val configNotice: String? = null,
    val currentPage: Int = 0,
    val navTarget: Int? = null,
    val navEpoch: Int = 0,
    val connectionState: ConnectionState = ConnectionState.DISCONNECTED,
    val activeAlarm: AlarmUiState? = null,
    val isAlarmHidden: Boolean = false,
    val activeAlerts: List<ActiveAlert> = emptyList(),
    val hiddenAlertTokens: Set<String> = emptySet(),
    val toastMessage: ToastMessage? = null,
    val voiceState: VoiceState = VoiceState(),
) {
    /**
     * A ringing alarm, or an `alarm`-severity alert, is on screen: the power
     * state machine keeps the screen on while this holds. A `warning` alert
     * only wakes the screen; the normal timeout then applies.
     */
    val holdsScreen: Boolean
        get() = (activeAlarm?.ringing == true && !isAlarmHidden) ||
            activeAlerts.firstOrNull()?.spec?.severity == "alarm"
}

sealed interface DashboardIntent {
    data class NavigateToPage(val pageIndex: Int) : DashboardIntent
    data class NavigateToPageByName(val pageName: String) : DashboardIntent
    object NavTargetHandled : DashboardIntent
    data class ReloadConfig(val force: Boolean = false) : DashboardIntent
    object ToggleVoice : DashboardIntent
    object CancelVoice : DashboardIntent
    object StopVoiceListening : DashboardIntent
    object SnoozeAlarm : DashboardIntent
    object DismissAlarm : DashboardIntent
    object HideAlarm : DashboardIntent
    data class TriggerAlertAction(val action: AlertAction) : DashboardIntent
    data class DismissAlert(val token: String) : DashboardIntent
    object ClearToast : DashboardIntent
    object Foreground : DashboardIntent
    object Background : DashboardIntent
    object RetryConnection : DashboardIntent
}

sealed interface DashboardEffect {
    object RequestRecordAudioPermission : DashboardEffect
    data class WakeScreenForAlarm(val isRinging: Boolean) : DashboardEffect
    object BringActivityToFront : DashboardEffect
}

class DashboardViewModel(
    val client: HaClient,
    val voice: VoiceSession,
    val configRepo: AppConfigRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        DashboardUiState(
            config = configRepo.getCurrentResult().config,
            configNotice = configRepo.getCurrentResult().notice,
            currentPage = configRepo.getCurrentResult().config.startPage,
        )
    )
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _effects = Channel<DashboardEffect>(Channel.BUFFERED)
    val effects: Flow<DashboardEffect> = _effects.receiveAsFlow()

    private var toastJob: Job? = null
    private var alertsJob: Job? = null
    @Volatile private var cachedAlertSpecs: List<AlertSpec> = emptyList()
    private var lastAlarmPhase: Int = 0
    private var lastAlertToken: String? = null

    init {
        // Observe config updates
        viewModelScope.launch {
            configRepo.configFlow.collect { res ->
                val specs = AlertSpec.parse(res.config.options["alerts"])
                cachedAlertSpecs = specs
                val watched = res.config.features.alarm.entities.toMutableSet()
                specs.forEach { s ->
                    watched += s.entity
                    s.unlessEntity?.let { watched += it }
                }
                client.alertWatchIds = watched
                client.trustedTlsHosts = (res.config.options["trusted_tls_hosts"] as? List<*>)
                    ?.filterIsInstance<String>()?.toSet().orEmpty()
                _uiState.update { current ->
                    current.copy(
                        config = res.config,
                        configNotice = res.notice,
                    )
                }
                updateAlertsAndAlarms()
            }
        }

        // Observe connection
        viewModelScope.launch {
            client.connection.collect { conn ->
                _uiState.update { it.copy(connectionState = conn) }
            }
        }

        // Ensure HA connection is active
        if (client.connection.value == ConnectionState.DISCONNECTED || client.connection.value == ConnectionState.ERROR) {
            client.connect()
        }

        // Observe voice
        viewModelScope.launch {
            voice.state.collect { vState ->
                _uiState.update { it.copy(voiceState = vState) }
            }
        }

        // Observe outcomes for toast
        viewModelScope.launch {
            client.outcomes.collect { outcome ->
                val label = callLabel(outcome.call)
                val msg = when (outcome.kind) {
                    HaClient.CallOutcome.Kind.DONE -> ToastMessage(System.nanoTime(), "✓  $label")
                    HaClient.CallOutcome.Kind.FAILED -> ToastMessage(System.nanoTime(), "Didn't work: $label", outcome.error, bad = true)
                    HaClient.CallOutcome.Kind.QUEUED -> ToastMessage(System.nanoTime(), "Offline", "Will send when Home Assistant is back", bad = true)
                    HaClient.CallOutcome.Kind.DROPPED -> ToastMessage(System.nanoTime(), "Not sent: $label", "Home Assistant was offline too long", bad = true)
                }
                showToast(msg)
            }
        }

        // Observe HA entities for alarms and alerts
        viewModelScope.launch {
            client.entities.collect {
                updateAlertsAndAlarms()
            }
        }
    }

    private fun callLabel(call: ServiceCall): String {
        return call.entityId ?: "${call.domain}.${call.service}"
    }

    private fun showToast(msg: ToastMessage) {
        toastJob?.cancel()
        _uiState.update { it.copy(toastMessage = msg) }
        toastJob = viewModelScope.launch {
            delay(4000L)
            _uiState.update { it.copy(toastMessage = null) }
        }
    }

    private fun startWatchers() {
        updateAlertsAndAlarms()
    }

    private fun updateAlertsAndAlarms() {
        val now = System.currentTimeMillis()
        val live = client.snapshot()
        val alarmState = alarmUiState(uiState.value.config.features.alarm, live)
        val phase = alarmState?.let { if (it.ringing) 1 else 2 } ?: 0

        val specs = cachedAlertSpecs
        val active = activeAlerts(specs, live, now)
        val hidden = uiState.value.hiddenAlertTokens
        val filtered = active.filter { it.token !in hidden }

        val unhideAlarm = phase != lastAlarmPhase && phase == 1
        _uiState.update {
            it.copy(
                activeAlarm = alarmState,
                isAlarmHidden = if (unhideAlarm) false else it.isAlarmHidden,
                activeAlerts = filtered,
            )
        }

        if (phase != lastAlarmPhase) {
            lastAlarmPhase = phase
            if (phase == 1) {
                _effects.trySend(DashboardEffect.WakeScreenForAlarm(isRinging = true))
                _effects.trySend(DashboardEffect.BringActivityToFront)
            }
        }

        val top = filtered.firstOrNull()
        if (top?.token != lastAlertToken) {
            lastAlertToken = top?.token
            if (top?.spec?.severity == "alarm" || top?.spec?.severity == "warning") {
                _effects.trySend(DashboardEffect.WakeScreenForAlarm(isRinging = top.spec.severity == "alarm"))
                if (top.spec.severity == "alarm") {
                    _effects.trySend(DashboardEffect.BringActivityToFront)
                }
            }
        }

        // Schedule a one-shot timer only if a `for_seconds` alert is currently pending.
        alertsJob?.cancel()
        val dueMs = nextAlertDueMs(specs, live, now)
        alertsJob = if (dueMs != null) {
            viewModelScope.launch {
                delay((dueMs - System.currentTimeMillis()).coerceAtLeast(50L))
                updateAlertsAndAlarms()
            }
        } else {
            null
        }
    }

    fun onIntent(intent: DashboardIntent) {
        when (intent) {
            is DashboardIntent.NavigateToPage -> {
                val pages = uiState.value.config.pages
                if (intent.pageIndex in pages.indices) {
                    _uiState.update {
                        it.copy(
                            currentPage = intent.pageIndex,
                            navTarget = intent.pageIndex,
                            navEpoch = it.navEpoch + 1,
                        )
                    }
                }
            }
            is DashboardIntent.NavigateToPageByName -> {
                val idx = uiState.value.config.pages.indexOfFirst { it.name.equals(intent.pageName, ignoreCase = true) }
                if (idx >= 0) {
                    onIntent(DashboardIntent.NavigateToPage(idx))
                }
            }
            DashboardIntent.NavTargetHandled -> {
                _uiState.update { it.copy(navTarget = null) }
            }
            is DashboardIntent.ReloadConfig -> {
                viewModelScope.launch {
                    configRepo.loadConfig(intent.force)
                }
            }
            DashboardIntent.ToggleVoice -> {
                val phase = voice.state.value.phase
                if (phase == VoicePhase.LISTENING) {
                    voice.stopListening()
                } else if (phase != VoicePhase.IDLE && phase != VoicePhase.DONE && phase != VoicePhase.ERROR) {
                    voice.cancel()
                } else {
                    voice.start(uiState.value.config.features.voice.pipeline)
                }
            }
            DashboardIntent.CancelVoice -> {
                voice.cancel()
            }
            DashboardIntent.StopVoiceListening -> {
                voice.stopListening()
            }
            DashboardIntent.SnoozeAlarm -> {
                uiState.value.config.features.alarm.snooze?.let {
                    client.callService(ServiceCall.fromConfig(it.service, it.entityId))
                }
            }
            DashboardIntent.DismissAlarm -> {
                uiState.value.config.features.alarm.stop?.let {
                    client.callService(ServiceCall.fromConfig(it.service, it.entityId))
                }
            }
            DashboardIntent.HideAlarm -> {
                _uiState.update { it.copy(isAlarmHidden = true) }
            }
            is DashboardIntent.TriggerAlertAction -> {
                client.callService(ServiceCall.fromConfig(intent.action.service, intent.action.entityId, intent.action.data))
            }
            is DashboardIntent.DismissAlert -> {
                _uiState.update { it.copy(hiddenAlertTokens = it.hiddenAlertTokens + intent.token) }
                updateAlertsAndAlarms()
            }
            DashboardIntent.ClearToast -> {
                toastJob?.cancel()
                _uiState.update { it.copy(toastMessage = null) }
            }
            DashboardIntent.Foreground -> {
                viewModelScope.launch {
                    configRepo.loadConfig(force = false)
                }
                startWatchers()
                if (client.connection.value == ConnectionState.DISCONNECTED || client.connection.value == ConnectionState.ERROR) {
                    client.connect()
                }
            }
            DashboardIntent.Background -> {
                // Background operations if any
            }
            DashboardIntent.RetryConnection -> {
                client.connect()
            }
        }
    }
}
