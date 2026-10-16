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
import com.custom.astrion.power.PowerEffect
import com.custom.astrion.power.PowerInput
import com.custom.astrion.power.PowerMachine
import com.custom.astrion.power.PowerOutputs
import com.custom.astrion.power.PowerSettings
import com.custom.astrion.power.PowerState
import com.custom.astrion.ui.AlertSpec
import com.custom.astrion.ui.screensaverIsNight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
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
    /** An alarm or alert that must keep the screen on is showing (or no longer). */
    data class HoldScreen(val hold: Boolean) : PowerIntent
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

/**
 * Runs [PowerMachine] against the real world: feeds it the broadcasts and
 * the clock, applies its outputs (motion-wake, HA foreground and entity
 * filter here; keep-on and backlight through [uiState] for MainActivity),
 * and carries out its effects. All the deciding is in the machine.
 */
class PowerViewModel(
    private val context: Context,
    private val client: HaClient,
    private val motionWake: MotionWakeController,
    private val configRepo: AppConfigRepository,
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
) : ViewModel() {

    private val _uiState = MutableStateFlow(PowerUiState())
    val uiState: StateFlow<PowerUiState> = _uiState.asStateFlow()

    private val _events = Channel<PowerEvent>(Channel.BUFFERED)
    val events: Flow<PowerEvent> = _events.receiveAsFlow()

    private val lock = Any()
    private var machine = PowerState(lastActivityMs = clock())
    /** What has been applied to motion-wake and HA; null re-applies everything. */
    private var applied: PowerOutputs? = null
    private var tickJob: Job? = null

    private fun currentConfig(): AppConfig = configRepo.getCurrentResult().config

    init {
        enforceScreenTimeout()

        viewModelScope.launch {
            configRepo.configFlow.collect {
                enforceScreenTimeout()
                dispatch(PowerInput.Tick(clock()))
            }
        }

        viewModelScope.launch {
            client.connection.collect { conn ->
                // The clock is synced by the timesync daemon on the remote
                // (device/timesync.rc), not from here: an app can't set the
                // debug.* property that would trigger it.
                if (conn == ConnectionState.CONNECTED) postBatteryTelemetry()
            }
        }

        dispatch(PowerInput.Tick(clock()))
    }

    fun markUserActivity() = dispatch(PowerInput.Activity(clock()))

    /** Folds [input] into the machine, then applies what changed. Safe from any thread. */
    private fun dispatch(input: PowerInput) {
        val effects = synchronized(lock) {
            val cfg = PowerSettings.from(currentConfig())
            val step = PowerMachine.reduce(machine, input, cfg)
            val s = step.state
            machine = s
            val night = s.screensaver && screensaverIsNight(client.snapshot(), System.currentTimeMillis())
            val out = PowerMachine.outputs(s, cfg, night)
            _uiState.update {
                it.copy(
                    batteryPct = s.pct,
                    isPlugged = s.plugged,
                    isCharging = s.charging,
                    batteryStatus = s.status,
                    isDocked = s.docked,
                    isDockFault = s.dockFault,
                    isDockDraining = s.dockDraining,
                    isScreensaverOn = s.screensaver,
                    screenBrightness = out.brightness,
                    keepScreenOn = out.keepScreenOn,
                    isScreenOn = s.screenOn,
                )
            }
            apply(out)
            scheduleTick(s, cfg, input.nowMs)
            step.effects
        }
        effects.forEach { effect ->
            when (effect) {
                PowerEffect.WakeScreen -> _events.trySend(PowerEvent.WakeScreen)
                PowerEffect.ReturnHome -> _events.trySend(PowerEvent.ReturnHome)
                PowerEffect.ReportBattery -> postBatteryTelemetry()
            }
        }
    }

    private fun apply(out: PowerOutputs) {
        val prev = applied
        if (prev?.motionWake != out.motionWake) {
            if (out.motionWake) {
                motionWake.setTimeoutMinutes(currentConfig().features.power.motionWakeMinutes)
                motionWake.start { dispatch(PowerInput.Motion(clock())) }
            } else {
                motionWake.stop()
            }
        }
        if (prev?.foreground != out.foreground) client.setForeground(out.foreground)
        if (prev != null && prev.narrowed != out.narrowed) {
            client.setEntityFilter(if (out.narrowed) screenOffEntities() else null)
        }
        applied = out
    }

    /** One timer, for whenever the machine next changes by itself. */
    private fun scheduleTick(s: PowerState, cfg: PowerSettings, now: Long) {
        tickJob?.cancel()
        val due = PowerMachine.nextDeadline(s, cfg, now) ?: return
        tickJob = viewModelScope.launch {
            delay((due - clock()).coerceAtLeast(0L))
            dispatch(PowerInput.Tick(maxOf(clock(), due)))
        }
    }

    private fun postBatteryTelemetry() {
        val power = currentConfig().features.power
        val entity = power.reportEntity
        val s = synchronized(lock) { machine }
        val pct = s.pct
        if (entity.isNullOrBlank() || pct == null) return
        viewModelScope.launch(Dispatchers.IO) {
            val attrs = mutableMapOf<String, Any?>(
                "unit_of_measurement" to "%",
                "device_class" to "battery",
                "state_class" to "measurement",
                "plugged" to s.plugged,
                "charging" to s.charging,
                "status" to Battery.statusName(s.status),
                "docked" to s.docked,
                "dock_fault" to s.dockFault,
            )
            power.reportName?.let { attrs["friendly_name"] = it }
            client.postState(entity, pct.toString(), attrs)
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
                val b = intent.intent
                val level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                dispatch(
                    PowerInput.Battery(
                        pct = if (level >= 0 && scale > 0) (level * 100) / scale else null,
                        plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0,
                        status = b.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN),
                        nowMs = clock(),
                    )
                )
            }
            is PowerIntent.ScreenPowerChanged -> dispatch(PowerInput.Screen(intent.isScreenOn, clock()))
            PowerIntent.UserActivity, PowerIntent.DismissScreensaver -> markUserActivity()
            is PowerIntent.HoldScreen -> dispatch(PowerInput.Hold(intent.hold, clock()))
            is PowerIntent.SetSwallowKey -> _uiState.update { it.copy(swallowKey = intent.keyCode) }
            PowerIntent.ClearSwallowKey -> _uiState.update { it.copy(swallowKey = -1) }
            is PowerIntent.SetSwallowTouch -> _uiState.update { it.copy(swallowTouch = intent.swallow) }
            PowerIntent.Foreground -> {
                enforceScreenTimeout()
                // Re-apply motion-wake and HA foreground (marking them stale), keeping the filter.
                synchronized(lock) { applied = applied?.let { it.copy(motionWake = !it.motionWake, foreground = !it.foreground) } }
                dispatch(PowerInput.Tick(clock()))
            }
            PowerIntent.Background -> Unit
        }
    }
}
