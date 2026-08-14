package com.custom.astrion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.sqrt
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.custom.astrion.config.DashboardConfig
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.config.HotkeyConfig
import com.custom.astrion.config.JsonPlain
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.input.HardwareKey
import com.custom.astrion.input.HardwareKeyRouter
import com.custom.astrion.ir.IrBlaster
import com.custom.astrion.ir.IrModeOverlay
import com.custom.astrion.ui.Dashboard
import com.custom.astrion.voice.VoiceOverlay
import com.custom.astrion.voice.VoicePhase
import com.custom.astrion.voice.VoiceSession

/**
 * Single-activity host. Owns the HA client, wires physical buttons to the
 * config's hotkeys, and renders the swipeable dashboard.
 *
 * IMPORTANT — configure your connection in `secrets.properties` (see
 * secrets.properties.example) before building — HA_URL / HA_TOKEN are
 * injected as BuildConfig fields so a real token never lands in source.
 */
class MainActivity : ComponentActivity() {

    private companion object {
        /**
         * Buttons swallowed by IR Mode. Everything here is diverted to the IR
         * emitter and must not fall through to its normal Android-TV binding
         * or to Android's own navigation (Back/Home would otherwise leave the
         * app entirely).
         */
        val IR_INTERCEPTED = setOf(
            HardwareKey.UP, HardwareKey.DOWN, HardwareKey.LEFT, HardwareKey.RIGHT,
            HardwareKey.CENTER,
            HardwareKey.VOLUME_UP, HardwareKey.VOLUME_DOWN,
            HardwareKey.POWER, HardwareKey.HOME, HardwareKey.BACK,
        )

        // Temporary: on-screen toast + logcat for any button not yet mapped,
        // so unknown keycodes (e.g. power/menu on this unit) can be identified.
        const val DEBUG_KEYS = true
        const val KEY_TAG = "AstrionKeys"

        // Hold this long for a button's long-press action to fire.
        const val LONG_PRESS_MS = 1500L

        // Motion-wake tuning: accel magnitude delta (m/s²) that counts as
        // "moved", and a cooldown so a single lift fires one wake.
        const val MOTION_THRESHOLD = 0.9f
        const val WAKE_COOLDOWN_MS = 2000L
    }

    // Long-press timing state.
    private val keyHandler = Handler(Looper.getMainLooper())
    private var pendingLong: Runnable? = null
    private var activeLongKey = -1
    private var longFired = false

    // Motion-wake: a wake-up accelerometer wakes the screen when the remote is
    // lifted/moved. Only wakes the CPU on actual motion, so it's cheap at rest.
    private var sensorManager: SensorManager? = null
    private var motionSensor: Sensor? = null
    private var lastMagnitude = 0f
    private var lastWakeMs = 0L
    private val motionListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val (x, y, z) = event.values
            val mag = sqrt(x * x + y * y + z * z)
            if (lastMagnitude != 0f && abs(mag - lastMagnitude) > MOTION_THRESHOLD) {
                wakeScreen()
            }
            lastMagnitude = mag
        }
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
    }

    private lateinit var client: HaClient
    private val keyRouter = HardwareKeyRouter()

    /** Current layout: starts as the compiled-in defaults, replaced from disk. */
    private var dashboard by mutableStateOf(DashboardLoader.Result(DashboardConfig.default, null))

    /** Page index requested by a hardware button; consumed by the Dashboard. */
    private var navTarget by mutableStateOf<Int?>(null)

    // ---- IR Mode ------------------------------------------------------------
    private lateinit var irBlaster: IrBlaster
    /** When true the hardware buttons blast IR instead of driving the TV over HA. */
    private var irMode by mutableStateOf(false)
    /** Last button blasted, echoed in the popup so you can see it working. */
    private var irLastKey by mutableStateOf<String?>(null)
    /** Active code table: defaults overlaid with dashboard.json overrides. */
    private var irCodes: Map<HardwareKey, Long> = IrBlaster.DEFAULT_CODES

    // ---- Voice --------------------------------------------------------------
    private lateinit var voice: VoiceSession

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { reloadDashboard() }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) voice.start(voicePipelineId()) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Kiosk-style fullscreen: reclaim the status bar's height for the
        // dashboard (physical buttons make the nav bar redundant too).
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
                or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )

        // /sdcard/astrion/dashboard.json lives on shared storage, so classic
        // runtime storage permissions are needed (Android 8.1 on the HA100).
        if (checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            storagePermission.launch(
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE,
                )
            )
        }

        setupMotionWake()

        client = HaClient(baseUrl = BuildConfig.HA_URL, token = BuildConfig.HA_TOKEN)
        irBlaster = IrBlaster(this)
        voice = VoiceSession(this, client)
        bindHotkeys(dashboard.config.hotkeys, dashboard.config.longHotkeys)
        client.connect()

        setContent {
            val entities = client.entities.collectAsState()
            val connection = client.connection.collectAsState()
            // Overlays are stacked in the SAME window as the dashboard (not
            // Dialogs) so this Activity keeps key focus and dispatchKeyEvent
            // continues to fire while they're on screen.
            Box(modifier = Modifier.fillMaxSize()) {
                Dashboard(
                    client = client,
                    entitiesState = entities,
                    connectionState = connection,
                    config = dashboard.config,
                    configNotice = dashboard.notice,
                    navTarget = navTarget,
                    onNavHandled = { navTarget = null },
                )

                // IR Mode modal sits above the dashboard while active.
                if (irMode) {
                    IrModeOverlay(
                        options = irOptions(),
                        client = client,
                        blaster = irBlaster,
                        lastKeyLabel = irLastKey,
                        onClose = { irMode = false; irLastKey = null },
                    )
                }

                // Voice modal, driven by the session's own state machine.
                val voiceState = voice.state.collectAsState().value
                VoiceOverlay(
                    state = voiceState,
                    imageDir = voiceImageDir(),
                    onDismiss = {
                        if (voiceState.phase == VoicePhase.LISTENING) voice.stopListening()
                        else voice.cancel()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-read the config file on every foreground, so edits (adb push, file
        // manager) apply without a rebuild or restart.
        reloadDashboard()
    }

    /** Load config from disk and (re)bind hotkeys. Synchronous — the file is tiny. */
    private fun reloadDashboard() {
        val result = DashboardLoader.load()
        dashboard = result
        bindHotkeys(result.config.hotkeys, result.config.longHotkeys)
    }

    // ---- hotkeys ------------------------------------------------------------

    /** Rebind the physical buttons to the config's short- and long-press hotkeys. */
    private fun bindHotkeys(short: List<HotkeyConfig>, long: List<HotkeyConfig>) {
        keyRouter.clear()
        short.forEach { hk ->
            val key = runCatching { HardwareKey.valueOf(hk.key.uppercase()) }.getOrNull()
                ?: return@forEach
            keyRouter.on(key) { runHotkey(hk) }
        }
        long.forEach { hk ->
            val key = runCatching { HardwareKey.valueOf(hk.key.uppercase()) }.getOrNull()
                ?: return@forEach
            keyRouter.onLong(key) { runHotkey(hk) }
        }

        // Refresh the IR code table from config, then claim the toggle key.
        irCodes = IrBlaster.fromConfig(irOptions()["codes"] as? Map<String, Any?>)
        bindIrToggle()
    }

    /**
     * Bind the IR-Mode toggle — long-press of 🔇 MUTE by default.
     *
     * NOT keycode 82 (☰): Key Mapper claims that key system-wide as a
     * launcher shortcut (accessibility-service key filtering, scoped to "any
     * input device"), so it never reaches dispatchKeyEvent at all. Override
     * with `ir_mode.toggle_key` / `ir_mode.toggle_long` in config if a given
     * unit's Key Mapper setup differs — no rebuild needed.
     */
    private fun bindIrToggle() {
        val opts = irOptions()
        val keyName = (opts["toggle_key"] as? String) ?: "MUTE"
        val useLong = (opts["toggle_long"] as? Boolean) ?: true
        val key = runCatching { HardwareKey.valueOf(keyName.uppercase()) }.getOrNull() ?: return
        val toggle = { toggleIrMode(); true }
        if (useLong) keyRouter.onLong(key, toggle) else keyRouter.on(key, toggle)
    }

    private fun toggleIrMode() {
        irMode = !irMode
        irLastKey = null
        if (irMode && !irBlaster.available) {
            Toast.makeText(this, "No IR emitter available on this device", Toast.LENGTH_LONG).show()
        }
    }

    /** `ir_mode` block from dashboard.json (empty map when absent). */
    @Suppress("UNCHECKED_CAST")
    private fun irOptions(): Map<String, Any?> =
        (dashboard.config.options["ir_mode"] as? Map<String, Any?>) ?: emptyMap()

    /** `voice` block from dashboard.json. */
    @Suppress("UNCHECKED_CAST")
    private fun voiceOptions(): Map<String, Any?> =
        (dashboard.config.options["voice"] as? Map<String, Any?>) ?: emptyMap()

    private fun voicePipelineId(): String? = voiceOptions()["pipeline"] as? String

    private fun voiceImageDir(): String =
        (voiceOptions()["image_dir"] as? String) ?: "/sdcard/astrion/voice"

    /**
     * Mic button: press once to start listening, again to stop early (HA's VAD
     * will normally end the turn on its own). Requests RECORD_AUDIO on first use.
     */
    private fun onVoiceKey() {
        when (voice.state.value.phase) {
            VoicePhase.LISTENING -> voice.stopListening()
            VoicePhase.PROCESSING, VoicePhase.SPEAKING -> voice.cancel()
            else -> {
                if (voice.hasPermission) {
                    voice.start(voicePipelineId())
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }

    /** Execute one hotkey: page navigation, or a HA service call. */
    private fun runHotkey(hk: HotkeyConfig): Boolean {
        hk.page?.let { pageName ->
            val idx = dashboard.config.pages.indexOfFirst { it.name.equals(pageName, ignoreCase = true) }
            if (idx < 0) return false
            navTarget = idx
            return true
        }
        val service = hk.service ?: return false
        val domain = service.substringBefore('.')
        val svc = service.substringAfter('.')
        val data = hk.data.mapValues { JsonPlain.toJson(it.value) }
        client.callService(ServiceCall(domain, svc, hk.entityId, data))
        return true
    }

    /**
     * Physical buttons arrive as standard KeyEvents. We intercept here to run
     * tap-vs-hold logic:
     *  - keys with a long-press binding fire their SHORT action on release (if
     *    released before LONG_PRESS_MS) or their LONG action once held past it;
     *  - keys without a long binding fire immediately on each down (so volume
     *    etc. still repeat while held).
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val key = HardwareKey.fromKeyCode(code)

        // ---- IR Mode intercept ---------------------------------------------
        // Highest priority: while the popup is up these buttons must NOT reach
        // their normal Android-TV bindings, so we consume them outright (both
        // DOWN and UP) and blast the Samsung code instead.
        if (irMode && key in IR_INTERCEPTED) {
            if (event.action == KeyEvent.ACTION_DOWN) {
                val codeHex = irCodes[key]
                if (codeHex == null) {
                    irLastKey = "$key — no IR code mapped"
                } else {
                    val ok = irBlaster.blast(codeHex)
                    irLastKey = if (ok) {
                        "$key → 0x${codeHex.toString(16).uppercase()}"
                    } else {
                        "$key — transmit FAILED"
                    }
                }
            }
            return true // swallow: no TV service call, no Android navigation
        }

        // Voice button: start (or stop) an Assist session.
        if (key == HardwareKey.VOICE && event.action == KeyEvent.ACTION_DOWN &&
            event.repeatCount == 0
        ) {
            onVoiceKey()
            return true
        }

        val shortH = keyRouter.shortHandler(code)
        val longH = keyRouter.longHandler(code)

        // Unmapped: log/toast for diagnosis, then let the OS handle it.
        if (shortH == null && longH == null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                Log.i(KEY_TAG, "keyCode=$code (${KeyEvent.keyCodeToString(code)})")
                if (DEBUG_KEYS) Toast.makeText(this, "Unmapped key: $code", Toast.LENGTH_SHORT).show()
            }
            return super.dispatchKeyEvent(event)
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (longH != null) {
                    // Long-capable: start the hold timer on first press, ignore repeats.
                    if (event.repeatCount == 0) {
                        cancelPendingLong()
                        longFired = false
                        activeLongKey = code
                        val r = Runnable {
                            longFired = true
                            longH.invoke()
                        }
                        pendingLong = r
                        keyHandler.postDelayed(r, LONG_PRESS_MS)
                    }
                } else {
                    // Short-only: fire on every down (preserves hold-to-repeat).
                    shortH?.invoke()
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (longH != null && code == activeLongKey) {
                    cancelPendingLong()
                    activeLongKey = -1
                    // Released before the hold threshold → it was a tap.
                    if (!longFired) shortH?.invoke()
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun cancelPendingLong() {
        pendingLong?.let { keyHandler.removeCallbacks(it) }
        pendingLong = null
    }

    // ---- motion wake --------------------------------------------------------

    private fun setupMotionWake() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        // Prefer a wake-up accelerometer so events still arrive with the screen
        // off; fall back to the normal one (which only helps while awake).
        motionSensor = sensorManager?.getSensorList(Sensor.TYPE_ACCELEROMETER)
            ?.firstOrNull { it.isWakeUpSensor }
            ?: sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        motionSensor?.let {
            sensorManager?.registerListener(motionListener, it, SensorManager.SENSOR_DELAY_NORMAL)
        }
    }

    private fun wakeScreen() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isInteractive) return // already on — nothing to do
        val now = System.currentTimeMillis()
        if (now - lastWakeMs < WAKE_COOLDOWN_MS) return
        lastWakeMs = now
        @Suppress("DEPRECATION")
        val wl = pm.newWakeLock(
            PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                or PowerManager.ACQUIRE_CAUSES_WAKEUP
                or PowerManager.ON_AFTER_RELEASE,
            "astrion:motionwake",
        )
        wl.acquire(4000)
        keyHandler.postDelayed({ if (wl.isHeld) wl.release() }, 3500)
    }

    override fun onDestroy() {
        sensorManager?.unregisterListener(motionListener)
        client.disconnect()
        super.onDestroy()
    }
}
