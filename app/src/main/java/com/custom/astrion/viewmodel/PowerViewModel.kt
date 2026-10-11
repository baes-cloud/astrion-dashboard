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
    private var screensaverJob: Job? = null
    private var homeTimeoutJob: Job? = null
    private var filterJob: Job? = null

    private fun currentConfig(): AppConfig = configRepo.getCurrentResult().config

    init {
        // Start screensaver loop
        startScreensaverLoop()

        // Observe connection state to re-post battery telemetry on reconnect
        viewModelScope.launch {
            client.connection.collect { conn ->
                if (conn == ConnectionState.CONNECTED) {
                    postBatteryTelemetry()
                }
            }
        }

        // Arm motion wake initially if screen is off
        updateMotionWake()
    }

    fun markUserActivity() {
        lastActivityMs = SystemClock.elapsedRealtime()
        if (_uiState.value.isScreensaverOn) {
            _uiState.update { it.copy(isScreensaverOn = false) }
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
                }
                updateBrightness()
                delay(1000L)
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
            screensaverActive && isNight -> nightLevel
            screensaverActive -> if (!docked) screensaver.undockedBrightness else screensaver.brightness
            !docked -> minOf(screensaver.undockedBrightness, if (isNight) nightLevel else 1f)
            isNight -> nightLevel
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
        if (!_uiState.value.isScreenOn) {
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
        filterJob?.cancel()
        if (!isOn) {
            filterJob = viewModelScope.launch {
                delay(30_000L)
                client.setEntityFilter(screenOffEntities())
            }
        } else {
            client.setEntityFilter(null)
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

                // Dock debounce state machine
                val takingCharge = Battery.takingCharge(plugged, status, pct)
                val debounceSec = currentConfig().features.power.dockDebounceSeconds
                dockConfirmJob?.cancel()
                if (!takingCharge) {
                    updateDocked(false)
                } else if (!_uiState.value.isDocked) {
                    dockConfirmJob = viewModelScope.launch {
                        delay(debounceSec * 1000L)
                        updateDocked(true)
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
                _uiState.update { it.copy(isScreensaverOn = false) }
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
            }
            PowerIntent.Background -> {
                // Background housekeeping
            }
        }
    }
}
