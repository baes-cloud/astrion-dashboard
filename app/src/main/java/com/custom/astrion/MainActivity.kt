package com.custom.astrion

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.custom.astrion.input.HardwareKey
import com.custom.astrion.ui.AstrionMaterialTheme
import com.custom.astrion.ui.DashboardScreen
import com.custom.astrion.ui.SheetHost
import com.custom.astrion.ui.ambient.ambientTransportCall
import com.custom.astrion.viewmodel.*
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.koin.androidx.viewmodel.ext.android.viewModel

/**
 * Modernized thin host for Astrion HA100 dashboard.
 * Serves solely as window manager, permission router, and Compose host (< 250 lines).
 * All business logic and concurrency is handled by ViewModels with Coroutines and StateFlow.
 */
class MainActivity : ComponentActivity() {

    private val dashboardVm: DashboardViewModel by viewModel()
    private val powerVm: PowerViewModel by viewModel()
    private val inputVm: InputViewModel by viewModel()
    private val sheetHost = SheetHost()

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { dashboardVm.onIntent(DashboardIntent.ReloadConfig(force = true)) }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) dashboardVm.onIntent(DashboardIntent.ToggleVoice) }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            powerVm.onIntent(PowerIntent.BatteryChanged(intent))
        }
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            powerVm.onIntent(PowerIntent.ScreenPowerChanged(intent.action == Intent.ACTION_SCREEN_ON))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureKioskWindow()
        checkPermissions()
        runCatching { startService(Intent(this, KeepAliveService::class.java)) }
            .onFailure { runCatching { startForegroundService(Intent(this, KeepAliveService::class.java)) } }
        requestBatteryExemptionOnce()

        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })

        observeViewModelEffects()

        setContent {
            AstrionMaterialTheme {
                DashboardScreen(
                    dashboardVm = dashboardVm,
                    powerVm = powerVm,
                    inputVm = inputVm,
                    sheetHost = sheetHost,
                    onRequestMicPermission = { micPermission.launch(Manifest.permission.RECORD_AUDIO) },
                )
            }
        }
    }

    private fun configureKioskWindow() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        )
    }

    private fun checkPermissions() {
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            storagePermission.launch(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE))
        }
    }

    private fun observeViewModelEffects() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    powerVm.uiState.collectLatest { state ->
                        if (state.keepScreenOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        val lp = window.attributes
                        if (lp.screenBrightness != state.screenBrightness) {
                            lp.screenBrightness = state.screenBrightness
                            window.attributes = lp
                        }
                    }
                }
                launch {
                    // A ringing alarm holds the screen on through the power state machine.
                    dashboardVm.uiState.map { it.holdsScreen }.distinctUntilChanged().collect { hold ->
                        powerVm.onIntent(PowerIntent.HoldScreen(hold))
                    }
                }
                launch {
                    // The wake word follows the dock (power state machine) ...
                    powerVm.uiState.map { it.wakeWordWanted }.distinctUntilChanged().collect { wanted ->
                        dashboardVm.onIntent(DashboardIntent.SetWakeWordWanted(wanted))
                    }
                }
                launch {
                    // ... and while armed, the CPU must keep feeding the mic.
                    dashboardVm.uiState.map { it.voiceState.armed }.distinctUntilChanged().collect(::holdWakeWordLock)
                }
                launch {
                    powerVm.events.collectLatest { event ->
                        when (event) {
                            PowerEvent.ReturnHome -> {
                                if (!inputVm.uiState.value.isIrMode) {
                                    sheetHost.dismissAll()
                                    dashboardVm.onIntent(DashboardIntent.NavigateToPage(dashboardVm.uiState.value.config.startPage))
                                }
                            }
                            PowerEvent.WakeScreen -> wakeScreen()
                        }
                    }
                }
                launch {
                    dashboardVm.effects.collectLatest { effect ->
                        when (effect) {
                            is DashboardEffect.WakeScreenForAlarm -> wakeScreenForAlarm()
                            is DashboardEffect.BringActivityToFront -> bringToFront()
                            is DashboardEffect.RequestRecordAudioPermission -> micPermission.launch(Manifest.permission.RECORD_AUDIO)
                            is DashboardEffect.WakeWordHeard -> onWakeWordHeard()
                        }
                    }
                }
                launch {
                    inputVm.effects.collectLatest { effect ->
                        when (effect) {
                            is InputEffect.NavigateToPage -> dashboardVm.onIntent(DashboardIntent.NavigateToPageByName(effect.pageName))
                            is InputEffect.DismissTopSheet -> sheetHost.dismissAll()
                            is InputEffect.ShowToast -> android.widget.Toast.makeText(this@MainActivity, effect.message, android.widget.Toast.LENGTH_SHORT).show()
                            is InputEffect.LaunchPackage -> launchExternalPackage(effect.packageName)
                            is InputEffect.OpenSystemSettings -> startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                            is InputEffect.TriggerVoiceKey -> dashboardVm.onIntent(DashboardIntent.ToggleVoice)
                        }
                    }
                }
            }
        }
    }

    private fun wakeScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (!pm.isInteractive) {
            @Suppress("DEPRECATION")
            pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "astrion:motion"
            ).acquire(3500)
        }
    }

    /** Turns the screen on once; whether it then stays on is [PowerViewModel]'s keepScreenOn. */
    private fun wakeScreenForAlarm() {
        @Suppress("DEPRECATION")
        window.addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON)
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) {
            @Suppress("DEPRECATION")
            pm.newWakeLock(PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE, "astrion:alarm").acquire(10_000)
        }
        bringToFront()
    }

    /** Partial wake lock held while the wake word is armed. */
    private var wakeWordLock: PowerManager.WakeLock? = null

    /** Created on the first wake word, so installs that never use it never open one. */
    private var chime: ToneGenerator? = null

    private fun holdWakeWordLock(on: Boolean) {
        if (on && wakeWordLock?.isHeld != true) {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
            wakeWordLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "astrion:wakeword").apply {
                setReferenceCounted(false)
                acquire()
            }
        } else if (!on) {
            wakeWordLock?.let { if (it.isHeld) it.release() }
            wakeWordLock = null
        }
    }

    /** Wake word heard: light the screen at full backlight, come to the front, short chime. */
    private fun onWakeWordHeard() {
        wakeScreen()
        powerVm.onIntent(PowerIntent.UserActivity)
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) bringToFront()
        if (chime == null) chime = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 60) }.getOrNull()
        chime?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
    }

    private fun bringToFront() {
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun launchExternalPackage(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    private fun requestBatteryExemptionOnce() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences("astrion", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_battery_exemption", false)) return
        prefs.edit().putBoolean("asked_battery_exemption", true).apply()
        runCatching {
            @Suppress("BatteryLife")
            startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
        }
    }

    override fun onResume() {
        super.onResume()
        dashboardVm.onIntent(DashboardIntent.Foreground)
        powerVm.onIntent(PowerIntent.Foreground)
    }

    override fun onPause() {
        dashboardVm.onIntent(DashboardIntent.Background)
        powerVm.onIntent(PowerIntent.Background)
        super.onPause()
    }

    /** The ambient transport key whose UP must be eaten too, so it never leaks. */
    private var ambientSwallowKey = -1

    /**
     * On the ambient now-playing screen, volume, mute and left/right act on
     * the player shown and the screen stays up; true if [event] was one.
     */
    private fun handleAmbientKey(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_UP && event.keyCode == ambientSwallowKey) {
            ambientSwallowKey = -1
            return true
        }
        val ambient = dashboardVm.uiState.value.config.features.ambient
        if (!ambient.enabled || !powerVm.uiState.value.isScreensaverOn || event.action != KeyEvent.ACTION_DOWN) return false
        val call = ambientTransportCall(
            HardwareKey.fromKeyCode(event.keyCode), dashboardVm.client.snapshot(), ambient.mediaPlayers, System.currentTimeMillis(),
        ) ?: return false
        dashboardVm.client.callService(call)
        ambientSwallowKey = event.keyCode
        return true
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (handleAmbientKey(event)) return true
        val pState = powerVm.uiState.value
        val wasScreensaver = pState.isScreensaverOn
        powerVm.onIntent(PowerIntent.UserActivity)
        if (pState.swallowKey == event.keyCode) {
            if (event.action == KeyEvent.ACTION_UP) powerVm.onIntent(PowerIntent.ClearSwallowKey)
            return true
        }
        if (wasScreensaver) {
            val key = HardwareKey.fromKeyCode(event.keyCode)
            if (!dashboardVm.uiState.value.config.features.screensaver.passesThrough(key.name)) {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    powerVm.onIntent(PowerIntent.SetSwallowKey(event.keyCode))
                }
                return true
            }
        }
        if (event.keyCode == KeyEvent.KEYCODE_BACK && !inputVm.uiState.value.isIrMode && sheetHost.isShowing) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                sheetHost.dismissTop()
            }
            return true
        }
        if (inputVm.handleKeyEvent(event, wasScreensaver)) return true
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val pState = powerVm.uiState.value
        val wasScreensaver = pState.isScreensaverOn
        powerVm.onIntent(PowerIntent.UserActivity)
        val dState = dashboardVm.uiState.value
        val alertOrAlarmShowing = dState.activeAlerts.isNotEmpty() || (dState.activeAlarm != null && !dState.isAlarmHidden)
        if (wasScreensaver && ev.actionMasked == MotionEvent.ACTION_DOWN && !alertOrAlarmShowing) {
            powerVm.onIntent(PowerIntent.SetSwallowTouch(true))
            return true
        }
        if (pState.swallowTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                powerVm.onIntent(PowerIntent.SetSwallowTouch(false))
            }
            return true
        }
        if (inputVm.handleTouchEvent(ev, wasScreensaver)) return true
        return super.dispatchTouchEvent(ev)
    }

    override fun onDestroy() {
        holdWakeWordLock(false)
        chime?.release()
        runCatching { unregisterReceiver(batteryReceiver) }
        runCatching { unregisterReceiver(screenReceiver) }
        if (isFinishing) stopService(Intent(this, KeepAliveService::class.java))
        super.onDestroy()
    }
}
