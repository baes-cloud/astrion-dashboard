package com.custom.astrion

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.Toast
import kotlin.math.abs
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
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
import com.custom.astrion.ha.ConnectionState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.input.HardwareKey
import com.custom.astrion.input.HardwareKeyRouter
import com.custom.astrion.ir.IrBlaster
import com.custom.astrion.ir.IrModeOverlay
import com.custom.astrion.ui.AlarmOverlay
import com.custom.astrion.ui.AlarmUiState
import com.custom.astrion.ui.Dashboard
import com.custom.astrion.ui.Screensaver
import com.custom.astrion.ui.screensaverIsNight
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
            // MUTE and the CH rocker have Samsung codes in the table but were
            // missing here, so those three buttons kept firing their normal
            // Sonos/blinds bindings while the popup claimed the remote was in
            // IR mode. The toggle key (MENU) is deliberately NOT in this set:
            // it has to survive to close the popup.
            HardwareKey.MUTE, HardwareKey.PAGE_UP, HardwareKey.PAGE_DOWN,
            HardwareKey.POWER, HardwareKey.HOME, HardwareKey.BACK,
        )

        /**
         * Ignore a second IR-Mode toggle within this window.
         *
         * The toggle is a SHORT press, and short-only keys deliberately fire on
         * every ACTION_DOWN so volume can auto-repeat while held — which would
         * otherwise make holding ☰ flap the popup open and shut at the key
         * repeat rate.
         */
        const val IR_TOGGLE_DEBOUNCE_MS = 500L

        // Logcat for any button not yet mapped, so unknown keycodes can be
        // identified. The on-screen toast that went with it is now gated on a
        // debug build: POWER is unbound in the live config, so on a release
        // build every press of it popped "Unmapped key: 132" over the
        // dashboard in a dark room.
        val DEBUG_KEYS = BuildConfig.DEBUG
        const val KEY_TAG = "AstrionKeys"

        // Hold this long for a button's long-press action to fire.
        const val LONG_PRESS_MS = 1500L

        /**
         * How long a key with a double-tap binding waits after a release to
         * see whether a second tap is coming.
         *
         * This is a real cost, not a tuning knob: for those keys the single
         * tap cannot fire until the window closes, because until then we do
         * not know which action was meant. Kept short enough that the page
         * jump still feels like a button press.
         */
        const val DOUBLE_TAP_MS = 280L

        /** How often the wake word watcher re-checks dock/config/connection. */
        const val WAKE_WORD_POLL_MS = 2_000L

        /** `voice.wake_word_undocked_minutes` default: keep listening this long off the dock. */
        const val WAKE_WORD_UNDOCKED_MIN = 10

        /** Pause before re-arming after a wake word run failed. */
        const val WAKE_WORD_BACKOFF_MS = 15_000L

        /**
         * Treat a resume after this long as a "cold arrival" and reset to the
         * configured start page.
         *
         * With the screen off the Activity isn't resumed, so the keypress that
         * wakes the device is consumed by the system as the wake and never
         * reaches dispatchKeyEvent — meaning every page button needed two
         * presses from cold, the first doing nothing but turning the screen on
         * and leaving you on whatever page you abandoned. The key that caused
         * the wake is not recoverable from an Activity, so it can't be
         * replayed; landing on a known page instead at least makes the cold
         * arrival predictable rather than random.
         */
        const val COLD_ARRIVAL_MS = 30_000L

        // Motion-wake tuning: accel magnitude delta (m/s²) that counts as
        // "moved", and a cooldown so a single lift fires one wake.
        const val MOTION_THRESHOLD = 0.9f
        const val WAKE_COOLDOWN_MS = 2000L

        /** How often the idle timer checks whether the screensaver is due. */
        const val SCREENSAVER_TICK_MS = 5_000L
    }

    // Long-press timing state.
    private val keyHandler = Handler(Looper.getMainLooper())
    private var pendingLong: Runnable? = null
    private var activeLongKey = -1
    private var longFired = false

    // Double-tap timing state: the deferred single-tap action, and which key
    // it belongs to, so a second tap of a DIFFERENT key doesn't consume it.
    private var pendingSingle: Runnable? = null
    private var pendingSingleKey = -1

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

    /** When the Activity last paused — drives the cold-arrival page reset. */
    private var pausedAtMs = 0L

    // ---- IR Mode ------------------------------------------------------------
    private lateinit var irBlaster: IrBlaster
    /** When true the hardware buttons blast IR instead of driving the TV over HA. */
    private var irMode by mutableStateOf(false)
    /** Last button blasted, echoed in the popup so you can see it working. */
    private var irLastKey by mutableStateOf<String?>(null)
    /** Active code table: defaults overlaid with dashboard.json overrides. */
    private var irCodes: Map<HardwareKey, Long> = IrBlaster.DEFAULT_CODES
    /** Last toggle, for [IR_TOGGLE_DEBOUNCE_MS]. */
    private var lastIrToggleMs = 0L
    /** How many times each frame is repeated; `ir_mode.repeat` in config. */
    private var irRepeat = 1

    // ---- Work alarm ---------------------------------------------------------
    /**
     * Watches HA for the alarm even while the screen is off. Compose stops
     * drawing when the display sleeps, so the popup alone could never wake
     * the remote — this collector runs regardless and does the waking.
     */
    private val alarmScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    /** "Hide until it rings again", set from the snoozed popup. */
    private var alarmHidden by mutableStateOf(false)
    /** Whether this Activity is in front, so a ring only reorders when needed. */
    private var inFront = false

    // ---- Voice --------------------------------------------------------------
    private lateinit var voice: VoiceSession

    // ---- Docked screensaver -------------------------------------------------
    /** On external power, i.e. sitting in the dock. From ACTION_BATTERY_CHANGED. */
    private var docked by mutableStateOf(false)
    private var batteryPct by mutableStateOf<Int?>(null)
    private var charging by mutableStateOf(false)
    private var screensaverOn by mutableStateOf(false)
    /** Last touch or key press, for the idle timeout. */
    private var lastActivityMs = System.currentTimeMillis()
    /**
     * The touch gesture / key press that dismissed the screensaver is eaten
     * whole, so waking it never also taps whatever was underneath.
     */
    private var swallowTouch = false
    private var swallowKey = -1

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onBatteryChanged(intent)
    }

    private val screensaverTick = object : Runnable {
        override fun run() {
            checkScreensaver()
            keyHandler.postDelayed(this, SCREENSAVER_TICK_MS)
        }
    }

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
        voice.onWakeWord = { runOnUiThread { onWakeWordHeard() } }
        bindHotkeys(
            dashboard.config.hotkeys,
            dashboard.config.longHotkeys,
            dashboard.config.doubleHotkeys,
        )
        client.connect()
        watchAlarm()
        watchWakeWord()
        // Sticky broadcast: registering hands back the current state at once.
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let { onBatteryChanged(it) }

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

                // Docked screensaver: above the dashboard, below everything
                // that must interrupt it (alarm, voice) — and those also
                // dismiss it outright, see hideScreensaver's callers.
                if (screensaverOn) {
                    Screensaver(
                        options = screensaverOptions(),
                        entities = entities.value,
                        client = client,
                        connected = connection.value == com.custom.astrion.ha.ConnectionState.CONNECTED,
                        batteryPct = batteryPct,
                        charging = charging,
                    )
                }

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

                // Work alarm: above the dashboard and IR Mode, since it's the
                // one overlay that must not be missed.
                val alarm = alarmUiState(entities.value)
                if (alarm != null && !alarmHidden) {
                    AlarmOverlay(
                        state = alarm,
                        onSnooze = { fireAlarmAction("snooze") },
                        onDismiss = { fireAlarmAction("stop") },
                        onHide = { alarmHidden = true },
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
        inFront = true
        // Re-read the config file on every foreground, so edits (adb push, file
        // manager) apply without a rebuild or restart.
        reloadDashboard()
        // Cold arrival (screen slept, or we were away a while): land on the
        // configured start page so the first thing you see is predictable.
        // See COLD_ARRIVAL_MS for why the waking keypress can't just be replayed.
        val away = System.currentTimeMillis() - pausedAtMs
        if (pausedAtMs > 0L && away >= COLD_ARRIVAL_MS) {
            navTarget = dashboard.config.startPage
        }
        markActivity()
        applyDockKeepAwake()
        keyHandler.removeCallbacks(screensaverTick)
        keyHandler.postDelayed(screensaverTick, SCREENSAVER_TICK_MS)
    }

    override fun onPause() {
        inFront = false
        keyHandler.removeCallbacks(screensaverTick)
        hideScreensaver()
        pausedAtMs = System.currentTimeMillis()
        super.onPause()
    }

    /** Load config from disk and (re)bind hotkeys. Synchronous — the file is tiny. */
    private fun reloadDashboard() {
        val result = DashboardLoader.load()
        dashboard = result
        bindHotkeys(result.config.hotkeys, result.config.longHotkeys, result.config.doubleHotkeys)
    }

    // ---- hotkeys ------------------------------------------------------------

    /** Rebind the physical buttons to the config's short, long and double hotkeys. */
    private fun bindHotkeys(
        short: List<HotkeyConfig>,
        long: List<HotkeyConfig>,
        double: List<HotkeyConfig> = emptyList(),
    ) {
        keyRouter.clear()
        cancelPendingSingle()
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
        double.forEach { hk ->
            val key = runCatching { HardwareKey.valueOf(hk.key.uppercase()) }.getOrNull()
                ?: return@forEach
            keyRouter.onDouble(key) { runHotkey(hk) }
        }

        // Refresh the IR code table from config, then claim the toggle key.
        irCodes = IrBlaster.fromConfig(irOptions()["codes"] as? Map<String, Any?>)
        irRepeat = (irOptions()["repeat"] as? Number)?.toInt()?.coerceIn(1, 5) ?: 1
        bindIrToggle()
    }

    /**
     * Bind the IR-Mode toggle — a SHORT press of ☰ MENU by default.
     *
     * One key, one gesture, both ways: the same tap that opens the popup
     * closes it again. That works because MENU is excluded from
     * [IR_INTERCEPTED], so while the popup is up its press still reaches the
     * router instead of being swallowed and blasted at the TV.
     *
     * Override with `ir_mode.toggle_key` / `ir_mode.toggle_long` in config —
     * no rebuild needed.
     */
    private fun bindIrToggle() {
        val opts = irOptions()
        val keyName = (opts["toggle_key"] as? String) ?: "MENU"
        val useLong = (opts["toggle_long"] as? Boolean) ?: false
        val key = runCatching { HardwareKey.valueOf(keyName.uppercase()) }.getOrNull() ?: return
        val toggle = { toggleIrMode(); true }
        if (useLong) keyRouter.onLong(key, toggle) else keyRouter.on(key, toggle)
    }

    private fun toggleIrMode() {
        val now = android.os.SystemClock.uptimeMillis()
        if (now - lastIrToggleMs < IR_TOGGLE_DEBOUNCE_MS) return
        lastIrToggleMs = now
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

    /** `alarm` block from dashboard.json (empty map when absent). */
    @Suppress("UNCHECKED_CAST")
    private fun alarmOptions(): Map<String, Any?> =
        (dashboard.config.options["alarm"] as? Map<String, Any?>) ?: emptyMap()

    /**
     * Null when no alarm is on; otherwise what the popup should show. Pure
     * mirror of HA: ringing = the `ringing_entity` flag, snoozed = the snooze
     * timer running while that flag is still on.
     */
    private fun alarmUiState(entities: com.custom.astrion.ha.EntityMap): AlarmUiState? {
        val opts = alarmOptions()
        val ringingId = opts["ringing_entity"] as? String ?: return null
        if (entities[ringingId]?.state != "on") return null

        val timer = (opts["snooze_timer"] as? String)?.let { entities[it] }
        val snoozeEnds = timer?.takeIf { it.state == "active" }?.attrString("finishes_at")?.let { iso ->
            runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        }

        // The timer's own duration ("0:05:00"), so the snooze ring drains
        // over the real length rather than an assumed five minutes.
        val snoozeTotalMs = timer?.attrString("duration")?.split(":")?.mapNotNull { it.toLongOrNull() }
            ?.takeIf { it.size == 3 }?.let { (h, m, sec) -> (h * 3600 + m * 60 + sec) * 1000 }
            ?: 300_000L

        val info = (opts["info_entity"] as? String)?.let { entities[it] }
        val startsAt = info?.state?.let { iso ->
            runCatching {
                val t = java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
                java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault()).format(java.util.Date(t))
            }.getOrNull()
        }
        return AlarmUiState(
            ringing = snoozeEnds == null,
            snoozeEndsMs = snoozeEnds,
            snoozeTotalMs = snoozeTotalMs,
            title = info?.attrString("summary")?.takeIf { it.isNotBlank() },
            place = info?.attrString("location")?.takeIf { it.isNotBlank() },
            startsAt = startsAt,
        )
    }

    /** 0 = no alarm, 1 = ringing, 2 = snoozed. */
    private fun alarmPhase(entities: com.custom.astrion.ha.EntityMap): Int =
        alarmUiState(entities)?.let { if (it.ringing) 1 else 2 } ?: 0

    /**
     * React to the alarm starting and stopping, screen on or off. Starting
     * (or a snooze ending) wakes the display, keeps it on and brings this app
     * forward; stopping lets the screen time out normally again.
     */
    private fun watchAlarm() {
        // While it is actually ringing, the screen stays on — even if someone
        // presses Power to shut it up. Re-asserted every 20 s until it stops
        // or is snoozed.
        var keepAwake: kotlinx.coroutines.Job? = null
        alarmScope.launch {
            client.entities
                .map { alarmPhase(it) }
                .distinctUntilChanged()
                .collect { phase ->
                    keepAwake?.cancel()
                    keepAwake = null
                    when (phase) {
                        1 -> {
                            alarmHidden = false
                            hideScreensaver()
                            wakeForAlarm()
                            keepAwake = alarmScope.launch {
                                while (true) {
                                    kotlinx.coroutines.delay(20_000)
                                    wakeForAlarm()
                                }
                            }
                        }
                        2 -> {
                            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            applyDockKeepAwake()
                        }
                        else -> {
                            alarmHidden = false
                            @Suppress("DEPRECATION")
                            window.clearFlags(
                                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                            )
                            applyDockKeepAwake()
                        }
                    }
                }
        }
    }

    private fun wakeForAlarm() {
        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
        )
        // Same wake the motion sensor uses, minus its cooldown: an alarm must
        // never be swallowed because someone picked the remote up a second ago.
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null && !pm.isInteractive) {
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                    or PowerManager.ACQUIRE_CAUSES_WAKEUP
                    or PowerManager.ON_AFTER_RELEASE,
                "astrion:alarm",
            )
            wl.acquire(10_000)
        }
        // If another app is in front (Key Mapper launched something, say),
        // come forward — API 27 still allows a background activity start.
        if (!inFront) {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                )
            )
        }
    }

    /** Fire the configured `snooze` / `stop` action: `{ service, entity_id }`. */
    @Suppress("UNCHECKED_CAST")
    private fun fireAlarmAction(which: String) {
        val action = alarmOptions()[which] as? Map<String, Any?> ?: return
        val service = action["service"] as? String ?: return
        client.callService(
            ServiceCall(
                domain = service.substringBefore('.'),
                service = service.substringAfter('.'),
                entityId = action["entity_id"] as? String,
            )
        )
    }

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
        val ran = runAction(hk)
        hk.then.forEach { runAction(it) }
        return ran || hk.then.isNotEmpty()
    }

    /**
     * A single hotkey action. Two built-in pseudo-services need to read live
     * entity state, so they can't be expressed as plain config service calls:
     *   astrion.toggle_mute   — mute or unmute depending on what it is now
     *   astrion.unjoin_others — drop every speaker currently grouped to this one
     */
    private fun runAction(hk: HotkeyConfig): Boolean {
        val service = hk.service ?: return false
        val entityId = hk.entityId
        when (service) {
            "astrion.toggle_mute" -> {
                if (entityId == null) return false
                val muted = client.entities.value[entityId]?.attrString("is_volume_muted") == "true"
                client.callService(
                    ServiceCall.of("media_player", "volume_mute", entityId, "is_volume_muted" to !muted)
                )
                return true
            }
            "astrion.unjoin_others" -> {
                if (entityId == null) return false
                // group_members[0] is the group leader; everyone else is joined
                // to it and gets dropped.
                client.entities.value[entityId]?.attrStringList("group_members")
                    ?.filter { it != entityId }
                    ?.forEach { client.callService(ServiceCall("media_player", "unjoin", entityId = it)) }
                return true
            }
        }
        val domain = service.substringBefore('.')
        val svc = service.substringAfter('.')
        val data = hk.data.mapValues { JsonPlain.toJson(it.value) }
        client.callService(ServiceCall(domain, svc, entityId, data))
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

        // ---- Screensaver ----------------------------------------------------
        // Any button wakes it, and by default that press also does its normal
        // job, so the first press is never lost. `keys_pass_through: false`
        // makes it only the wake — the same as a touch.
        markActivity()
        if (screensaverOn) {
            hideScreensaver()
            if (event.action == KeyEvent.ACTION_DOWN &&
                screensaverOptions()["keys_pass_through"] as? Boolean == false
            ) {
                swallowKey = code
                return true
            }
        }
        if (code == swallowKey) {
            if (event.action == KeyEvent.ACTION_UP) swallowKey = -1
            return true
        }

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
                    val ok = irBlaster.blast(codeHex, irRepeat)
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
        val doubleH = keyRouter.doubleHandler(code)

        // Unmapped: log/toast for diagnosis, then let the OS handle it.
        if (shortH == null && longH == null && doubleH == null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                Log.i(KEY_TAG, "keyCode=$code (${KeyEvent.keyCodeToString(code)})")
                if (DEBUG_KEYS) Toast.makeText(this, "Unmapped key: $code", Toast.LENGTH_SHORT).show()
            }
            return super.dispatchKeyEvent(event)
        }

        when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                // A press arriving while this key's single-tap is still held
                // back IS the second tap: cancel the deferred single and fire
                // the double instead. Checked before the long-press timer so a
                // double tap never also arms a hold.
                if (doubleH != null && event.repeatCount == 0 &&
                    pendingSingle != null && pendingSingleKey == code
                ) {
                    cancelPendingSingle()
                    cancelPendingLong()
                    longFired = true // suppress the short action on this release
                    activeLongKey = -1
                    doubleH.invoke()
                    return true
                }
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
                } else if (doubleH != null) {
                    // Short-only but double-capable: nothing can fire until the
                    // window closes on release. Arming here rather than on UP
                    // would make a held key auto-repeat into a double tap.
                    longFired = false
                    activeLongKey = code
                } else {
                    // Short-only: fire on every down (preserves hold-to-repeat).
                    shortH?.invoke()
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if ((longH != null || doubleH != null) && code == activeLongKey) {
                    cancelPendingLong()
                    activeLongKey = -1
                    // Released before the hold threshold → it was a tap.
                    if (!longFired) {
                        if (doubleH != null) {
                            // Hold the single back until the window closes.
                            cancelPendingSingle()
                            val r = Runnable {
                                pendingSingle = null
                                pendingSingleKey = -1
                                shortH?.invoke()
                            }
                            pendingSingle = r
                            pendingSingleKey = code
                            keyHandler.postDelayed(r, DOUBLE_TAP_MS)
                        } else {
                            shortH?.invoke()
                        }
                    }
                }
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    /**
     * Touches reset the idle timer. The one that dismisses the screensaver is
     * consumed down to its UP, so it can't land on a button underneath.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        markActivity()
        if (ev.actionMasked == MotionEvent.ACTION_DOWN && screensaverOn) {
            hideScreensaver()
            swallowTouch = true
        }
        if (swallowTouch) {
            if (ev.actionMasked == MotionEvent.ACTION_UP || ev.actionMasked == MotionEvent.ACTION_CANCEL) {
                swallowTouch = false
            }
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    // ---- docked screensaver -------------------------------------------------

    /**
     * `screensaver` block from dashboard.json. A file written before the
     * screensaver existed has no such block, so fall back to the compiled-in
     * one rather than leaving the feature switched off until someone edits it.
     */
    @Suppress("UNCHECKED_CAST")
    private fun screensaverOptions(): Map<String, Any?> =
        (dashboard.config.options["screensaver"] as? Map<String, Any?>)
            ?: (DashboardConfig.default.options["screensaver"] as? Map<String, Any?>)
            ?: emptyMap()

    private fun screensaverEnabled(): Boolean = screensaverOptions()["enabled"] as? Boolean ?: true

    /** "docked" (default): only on external power. "always": whenever idle. */
    private fun screensaverArmed(): Boolean =
        screensaverEnabled() && (docked || screensaverOptions()["trigger"] == "always")

    private fun markActivity() {
        lastActivityMs = System.currentTimeMillis()
    }

    private fun onBatteryChanged(intent: Intent) {
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else null
        charging = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_CHARGING
        if (plugged != docked) {
            docked = plugged
            // Dropping it in the dock starts the idle countdown from now;
            // lifting it out takes the screensaver down at once.
            markActivity()
            if (!screensaverArmed()) hideScreensaver()
            applyDockKeepAwake()
        }
    }

    /**
     * Docked, the screen stays on so the screensaver is actually seen rather
     * than the system timeout blanking the panel first (`keep_screen_on`).
     * The alarm manages the same flag, so it calls back in here after clearing.
     */
    private fun applyDockKeepAwake() {
        val keep = docked && screensaverEnabled() && screensaverOptions()["keep_screen_on"] as? Boolean != false
        if (keep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else if (alarmPhase(client.entities.value) != 1) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /** Idle tick: show the screensaver when due, and keep its backlight right. */
    private fun checkScreensaver() {
        if (screensaverOn) {
            // Re-evaluated each tick so the backlight follows sunset/sunrise.
            applyScreensaverBrightness()
            return
        }
        if (!inFront || !screensaverArmed() || irMode) return
        if (alarmUiState(client.entities.value) != null && !alarmHidden) return
        val phase = voice.state.value.phase
        if (phase != VoicePhase.IDLE && phase != VoicePhase.DONE) return
        val idleMs = ((screensaverOptions()["idle_seconds"] as? Number)?.toLong() ?: 45L) * 1000
        if (System.currentTimeMillis() - lastActivityMs < idleMs) return
        screensaverOn = true
        applyScreensaverBrightness()
    }

    private fun applyScreensaverBrightness() {
        val opts = screensaverOptions()
        val night = screensaverIsNight(client.entities.value, System.currentTimeMillis())
        val level = if (night) {
            (opts["night_brightness"] as? Number)?.toFloat() ?: 0.05f
        } else {
            (opts["brightness"] as? Number)?.toFloat() ?: 0.22f
        }
        setWindowBrightness(level.coerceIn(0.01f, 1f))
    }

    private fun hideScreensaver() {
        if (!screensaverOn) return
        screensaverOn = false
        markActivity()
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    private fun setWindowBrightness(value: Float) {
        val lp = window.attributes
        if (lp.screenBrightness == value) return
        lp.screenBrightness = value
        window.attributes = lp
    }

    private fun cancelPendingLong() {
        pendingLong?.let { keyHandler.removeCallbacks(it) }
        pendingLong = null
    }

    private fun cancelPendingSingle() {
        pendingSingle?.let { keyHandler.removeCallbacks(it) }
        pendingSingle = null
        pendingSingleKey = -1
    }

    // ---- wake word while docked ---------------------------------------------

    /** Partial wake lock held while armed, so the CPU keeps feeding the mic. */
    private var wakeWordLock: PowerManager.WakeLock? = null

    /** Created on first wake word, so installs that never use it never open one. */
    private var chime: ToneGenerator? = null

    /**
     * `voice.wake_word` in dashboard.json: "docked" (default) listens for the
     * pipeline's wake word while charging and for `wake_word_undocked_minutes`
     * (default 10) after it leaves the dock, "always" listens on battery
     * too, "off" disables it. Polled, so docking, config edits and reconnects
     * all take effect without a restart.
     */
    private fun watchWakeWord() {
        alarmScope.launch {
            var lastDockedMs = 0L
            while (true) {
                delay(WAKE_WORD_POLL_MS)
                val now = System.currentTimeMillis()
                if (isDocked()) lastDockedMs = now
                val graceMs = ((voiceOptions()["wake_word_undocked_minutes"] as? Number)?.toLong()
                    ?: WAKE_WORD_UNDOCKED_MIN.toLong()) * 60_000
                val mode = (voiceOptions()["wake_word"] as? String) ?: "docked"
                val want = when (mode) {
                    "always" -> true
                    "docked" -> lastDockedMs > 0 && now - lastDockedMs < graceMs
                    else -> false
                } && voice.hasPermission && client.connection.value == ConnectionState.CONNECTED

                val s = voice.state.value
                if (want && !s.armed && s.phase == VoicePhase.IDLE &&
                    System.currentTimeMillis() - voice.lastWakeFailureAt > WAKE_WORD_BACKOFF_MS
                ) {
                    voice.armWakeWord(voicePipelineId())
                } else if (!want && s.armed) {
                    voice.disarm()
                }
                holdWakeWordLock(voice.state.value.armed)
            }
        }
    }

    private fun isDocked(): Boolean {
        val battery = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return false
        return battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
    }

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

    /** Wake word heard: light the screen, come to the front, short chime. */
    private fun onWakeWordHeard() {
        lastWakeMs = 0L // a spoken wake must never be eaten by the motion cooldown
        wakeScreen()
        hideScreensaver() // restore full backlight for the voice overlay
        if (!inFront) {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                )
            )
        }
        if (chime == null) chime = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 60) }.getOrNull()
        chime?.startTone(ToneGenerator.TONE_PROP_BEEP, 120)
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
        alarmScope.cancel()
        voice.cancel()
        holdWakeWordLock(false)
        chime?.release()
        keyHandler.removeCallbacks(screensaverTick)
        runCatching { unregisterReceiver(batteryReceiver) }
        sensorManager?.unregisterListener(motionListener)
        client.disconnect()
        super.onDestroy()
    }
}
