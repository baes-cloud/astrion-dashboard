package com.custom.astrion.viewmodel

import android.content.Context
import android.content.Intent
import android.os.BatteryManager
import android.os.SystemClock
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.custom.astrion.config.AppConfig
import com.custom.astrion.config.AppConfigRepository
import com.custom.astrion.ha.ConnectionState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.power.Battery
import com.custom.astrion.power.MotionWakeController
import com.custom.astrion.ui.AlertSpec
import com.custom.astrion.ui.screensaverIsNight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PowerUiState(
    val batteryPct: Int? = null,
    val isPlugged: Boolean = false,
    val isCharging: Boolean = false,
    val batteryStatus: Int = BatteryManager.BATTERY_STATUS_UNKNOWN,
    val isDocked: Boolean = false,
    val isDockFault: Boolean = false,
    val isDockDraining: Boolean = false,
    val isScreensaverOn: Boolean = false,
    val screenBrightness: Float = -1.0f,
    val keepScreenOn: Boolean = false,
    val isScreenOn: Boolean = true,
    val swallowTouch: Boolean = false,
    val swallowKey: Int = -1,
)

sealed interface PowerIntent {
    data class BatteryChanged(val intent: Intent) : PowerIntent
    data class ScreenPowerChanged(val isScreenOn: Boolean) : PowerIntent
    object UserActivity : PowerIntent
    object DismissScreensaver : PowerIntent
    data class SetSwallowKey(val keyCode: Int) : PowerIntent
    object ClearSwallowKey : PowerIntent
    data class SetSwallowTouch(val swallow: Boolean) : PowerIntent
    object Foreground : PowerIntent
    object Background : PowerIntent
}

sealed interface PowerEvent {
    object ReturnHome : PowerEvent
    object WakeScreen : PowerEvent
}

class PowerViewModel(
    private val context: Context,
    private val client: HaClient,
    private val motionWake: MotionWakeController,
    private val configRepo: AppConfigRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PowerUiState())
    val uiState: StateFlow<PowerUiState> = _uiState.asStateFlow()

    private val _events = Channel<PowerEvent>(Channel.BUFFERED)
    val events: Flow<PowerEvent> = _events.receiveAsFlow()

    private var lastActivityMs = SystemClock.elapsedRealtime()
    private var peakPct: Int? = null
    private var dockConfirmJob: Job? = null
    private var undockDebounceJob: Job? = null
    private var screensaverJob: Job? = null
    private var homeTimeoutJob: Job? = null
    private var filterJob: Job? = null

    private fun currentConfig(): AppConfig = configRepo.getCurrentResult().config

    init {
        // Enforce screen timeout on launch
        enforceScreenTimeout()

        // Start screensaver loop
        startScreensaverLoop()

        // Observe config changes to keep screen timeout & brightness in sync
        viewModelScope.launch {
            configRepo.configFlow.collect {
                enforceScreenTimeout()
                updateBrightness()
            }
        }

        // Observe connection state to re-post battery telemetry and trigger time sync on reconnect
        viewModelScope.launch {
            client.connection.collect { conn ->
                if (conn == ConnectionState.CONNECTED) {
                    postBatteryTelemetry()
                    triggerTimeSync()
                }
            }
        }

        // Arm motion wake initially if screen is off
        updateMotionWake()
        updateForegroundState()
    }

    private fun updateForegroundState() {
        val state = _uiState.value
        client.setForeground(state.isScreenOn && !state.isScreensaverOn)
    }

    fun markUserActivity() {
        lastActivityMs = SystemClock.elapsedRealtime()
        // If undock was being debounced and the user physically interacted with the remote,
        // complete undock immediately if still unplugged.
        if (undockDebounceJob?.isActive == true && !_uiState.value.isPlugged) {
            undockDebounceJob?.cancel()
            undockDebounceJob = null
            updateDocked(false)
        }
        val wasScreensaverOn = _uiState.value.isScreensaverOn
        if (wasScreensaverOn) {
            _uiState.update { it.copy(isScreensaverOn = false) }
            filterJob?.cancel()
            if (_uiState.value.isScreenOn) {
                client.setEntityFilter(null)
            }
            updateForegroundState()
            startScreensaverLoop()
        }
        val homeSec = currentConfig().features.power.homeAfterSeconds
        homeTimeoutJob?.cancel()
        if (homeSec > 0) {
            homeTimeoutJob = viewModelScope.launch {
                delay(homeSec * 1000L)
                _events.send(PowerEvent.ReturnHome)
            }
        }
        updateBrightness()
    }

    private fun startScreensaverLoop() {
        screensaverJob?.cancel()
        screensaverJob = viewModelScope.launch {
            while (isActive) {
                val screensaver = currentConfig().features.screensaver
                val idleMs = if (_uiState.value.isDocked) screensaver.idleMs else screensaver.undockedIdleMs
                val elapsedMs = SystemClock.elapsedRealtime() - lastActivityMs
                if (screensaver.enabled && idleMs > 0 && elapsedMs >= idleMs && !_uiState.value.isScreensaverOn) {
                    _uiState.update { it.copy(isScreensaverOn = true) }
                    updateForegroundState()
                    updateBrightness()
                    scheduleScreensaverFilter()
                } else if (_uiState.value.isScreensaverOn) {
                    updateBrightness()
                }
                delay(if (_uiState.value.isScreensaverOn) 30_000L else 1000L)
            }
        }
    }

    private fun scheduleScreensaverFilter() {
        filterJob?.cancel()
        val delaySec = currentConfig().features.power.screenOffFilterSeconds
        if (delaySec <= 0) return
        filterJob = viewModelScope.launch {
            delay(delaySec * 1000L)
            if (_uiState.value.isScreenOn && _uiState.value.isScreensaverOn) {
                val allowed = screensaverEntities()
                if (allowed.isNotEmpty()) {
                    client.setEntityFilter(allowed)
                }
            }
        }
    }

    private fun updateDocked(docked: Boolean) {
        val changed = _uiState.value.isDocked != docked
        _uiState.update { it.copy(isDocked = docked) }
        if (docked) {
            if (peakPct == null) peakPct = _uiState.value.batteryPct
        } else {
            peakPct = null
        }
        if (changed) {
            if (docked) {
                val screensaver = currentConfig().features.screensaver
                val elapsedMs = SystemClock.elapsedRealtime() - lastActivityMs
                if (screensaver.enabled && screensaver.idleMs > 0 && elapsedMs >= screensaver.idleMs && !_uiState.value.isScreensaverOn) {
                    _uiState.update { it.copy(isScreensaverOn = true) }
                    scheduleScreensaverFilter()
                }
                if (!_uiState.value.isScreenOn && screensaver.enabled && screensaver.keepScreenOn) {
                    _events.trySend(PowerEvent.WakeScreen)
                }
            }
            updateMotionWake()
            updateForegroundState()
            updateBrightness()
            updateFaults()
        }
    }

    private fun updateFaults() {
        val pct = _uiState.value.batteryPct ?: 0
        val plugged = _uiState.value.isPlugged
        val status = _uiState.value.batteryStatus
        val fault = Battery.dockFault(plugged, status, pct, peakPct)
        val draining = Battery.draining(peakPct, pct)
        _uiState.update { it.copy(isDockFault = fault, isDockDraining = draining) }
    }

    private fun updateBrightness() {
        val screensaver = currentConfig().features.screensaver
        val screensaverActive = _uiState.value.isScreensaverOn
        val docked = _uiState.value.isDocked
        val isNight = screensaverIsNight(client.snapshot(), System.currentTimeMillis())
        val nightLevel = screensaver.nightBrightness
        val b = when {
            !screensaverActive -> -1.0f
            isNight -> nightLevel
            !docked -> screensaver.undockedBrightness
            else -> screensaver.brightness
        }
        val clamped = if (b < 0f) -1.0f else b.coerceIn(0.01f, 1.0f)
        val keepAwake = docked && screensaver.enabled && screensaver.keepScreenOn
        _uiState.update { it.copy(screenBrightness = clamped, keepScreenOn = keepAwake) }
    }

    private fun postBatteryTelemetry() {
        val entity = currentConfig().features.power.reportEntity
        val pct = _uiState.value.batteryPct
        if (!entity.isNullOrBlank() && pct != null) {
            viewModelScope.launch(Dispatchers.IO) {
                val status = Battery.statusName(_uiState.value.batteryStatus)
                val attrs = mutableMapOf<String, Any?>(
                    "unit_of_measurement" to "%",
                    "device_class" to "battery",
                    "state_class" to "measurement",
                    "plugged" to _uiState.value.isPlugged,
                    "charging" to _uiState.value.isCharging,
                    "status" to status,
                    "docked" to _uiState.value.isDocked,
                    "dock_fault" to _uiState.value.isDockFault,
                )
                currentConfig().features.power.reportName?.let { attrs["friendly_name"] = it }
                client.postState(entity, pct.toString(), attrs)
            }
        }
    }

    private fun updateMotionWake() {
        if (!_uiState.value.isScreenOn && !_uiState.value.isDocked) {
            motionWake.setTimeoutMinutes(currentConfig().features.power.motionWakeMinutes)
            motionWake.start {
                viewModelScope.launch {
                    _events.send(PowerEvent.WakeScreen)
                }
            }
        } else {
            motionWake.stop()
        }
    }

    private fun onScreenPowerChanged(isOn: Boolean) {
        _uiState.update { it.copy(isScreenOn = isOn) }
        updateMotionWake()
        updateForegroundState()
        filterJob?.cancel()
        if (!isOn) {
            screensaverJob?.cancel()
            if (!_uiState.value.isDocked && !_uiState.value.isPlugged && _uiState.value.isScreensaverOn) {
                _uiState.update { it.copy(isScreensaverOn = false) }
                updateBrightness()
            }
            val delaySec = currentConfig().features.power.screenOffFilterSeconds.coerceAtLeast(1)
            filterJob = viewModelScope.launch {
                delay(delaySec * 1000L)
                client.setEntityFilter(screenOffEntities())
            }
        } else {
            val screensaver = currentConfig().features.screensaver
            val elapsedMs = SystemClock.elapsedRealtime() - lastActivityMs
            if (_uiState.value.isDocked && screensaver.enabled && screensaver.idleMs > 0 && elapsedMs >= screensaver.idleMs) {
                _uiState.update { it.copy(isScreensaverOn = true) }
                updateForegroundState()
                updateBrightness()
            } else if (!_uiState.value.isScreensaverOn) {
                markUserActivity()
            } else {
                updateBrightness()
            }
            startScreensaverLoop()
            client.setEntityFilter(null)
            if (_uiState.value.isScreensaverOn) {
                scheduleScreensaverFilter()
            }
        }
    }

    private fun triggerTimeSync() {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                Runtime.getRuntime().exec(arrayOf("setprop", "debug.astrion.timesync", "1")).waitFor()
            }
        }
    }

    private fun screenOffEntities(): Set<String> {
        val config = currentConfig()
        val ids = config.features.alarm.entities.toMutableSet()
        val specs = AlertSpec.parse(config.options["alerts"])
        specs.forEach { spec ->
            ids += spec.entity
            spec.unlessEntity?.let { ids += it }
        }
        ids += config.features.power.screenOffEntities
        return ids
    }

    /**
     * Entities to keep subscribed while the screensaver is active: everything
     * needed by the screensaver, alarms, and alerts (weather, media players,
     * timers, calendars, sun, alarms, and alert triggers).
     */
    private fun screensaverEntities(): Set<String> {
        val ids = screenOffEntities().toMutableSet()
        ids += "sun.sun"
        val raw = currentConfig().features.screensaver.raw
        (raw["weather_entity"] as? String)?.let { ids += it }
        (raw["calendar_entity"] as? String)?.let { ids += it }
        (raw["enabled_entity"] as? String)?.let { ids += it }
        (raw["off_today_entity"] as? String)?.let { ids += it }
        (raw["alarm_entities"] as? List<*>)?.filterIsInstance<String>()?.let { ids += it }
        (raw["always_entities"] as? List<*>)?.filterIsInstance<String>()?.let { ids += it }
        (raw["media_entities"] as? List<*>)?.filterIsInstance<String>()?.let { ids += it }
        (raw["alerts"] as? List<*>)?.forEach { item ->
            ((item as? Map<*, *>)?.get("entity_id") as? String)?.let { ids += it }
        }
        client.snapshot().keys.forEach { entityId ->
            val domain = entityId.substringBefore('.')
            if (domain == "weather" || domain == "media_player" || domain == "timer" || domain == "calendar") {
                ids += entityId
            }
        }
        return ids
    }

    private fun enforceScreenTimeout() {
        val seconds = currentConfig().features.power.screenTimeoutSeconds
        if (seconds <= 0) return
        val current = runCatching {
            Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT)
        }.getOrNull() ?: return
        val target = seconds * 1000
        if (current != target && Settings.System.canWrite(context)) {
            runCatching {
                Settings.System.putInt(context.contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, target)
            }
        }
    }

    fun onIntent(intent: PowerIntent) {
        when (intent) {
            is PowerIntent.BatteryChanged -> {
                val bIntent = intent.intent
                val level = bIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = bIntent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                val pct = if (level >= 0 && scale > 0) (level * 100) / scale else null
                val plugged = bIntent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                val status = bIntent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
                val charging = status == BatteryManager.BATTERY_STATUS_CHARGING

                if (pct != null && _uiState.value.isDocked) {
                    val currentPeak = peakPct
                    if (currentPeak == null || pct > currentPeak) {
                        peakPct = pct
                    }
                }

                val prevPct = _uiState.value.batteryPct
                val prevPlugged = _uiState.value.isPlugged

                _uiState.update {
                    it.copy(
                        batteryPct = pct,
                        isPlugged = plugged,
                        isCharging = charging,
                        batteryStatus = status,
                    )
                }

                // Dock / undock debounce state machine
                val takingCharge = Battery.takingCharge(plugged, status, pct)
                val powerOpts = currentConfig().features.power
                val dockDebounceSec = powerOpts.dockDebounceSeconds
                val undockDebounceSec = powerOpts.undockDebounceSeconds
                if (!takingCharge) {
                    dockConfirmJob?.cancel()
                    dockConfirmJob = null
                    if (_uiState.value.isDocked) {
                        if (undockDebounceSec <= 0) {
                            undockDebounceJob?.cancel()
                            undockDebounceJob = null
                            updateDocked(false)
                        } else if (undockDebounceJob?.isActive != true) {
                            undockDebounceJob = viewModelScope.launch {
                                delay(undockDebounceSec * 1000L)
                                updateDocked(false)
                            }
                        }
                    }
                } else {
                    undockDebounceJob?.cancel()
                    undockDebounceJob = null
                    if (!_uiState.value.isDocked) {
                        dockConfirmJob?.cancel()
                        dockConfirmJob = viewModelScope.launch {
                            delay(dockDebounceSec * 1000L)
                            updateDocked(true)
                        }
                    }
                }

                updateFaults()

                if (pct != prevPct || plugged != prevPlugged) {
                    postBatteryTelemetry()
                }
            }
            is PowerIntent.ScreenPowerChanged -> {
                onScreenPowerChanged(intent.isScreenOn)
            }
            PowerIntent.UserActivity -> {
                markUserActivity()
            }
            PowerIntent.DismissScreensaver -> {
                markUserActivity()
            }
            is PowerIntent.SetSwallowKey -> {
                _uiState.update { it.copy(swallowKey = intent.keyCode) }
            }
            PowerIntent.ClearSwallowKey -> {
                _uiState.update { it.copy(swallowKey = -1) }
            }
            is PowerIntent.SetSwallowTouch -> {
                _uiState.update { it.copy(swallowTouch = intent.swallow) }
            }
            PowerIntent.Foreground -> {
                enforceScreenTimeout()
                updateMotionWake()
                updateForegroundState()
            }
            PowerIntent.Background -> {
                // Background housekeeping
            }
        }
    }
}
