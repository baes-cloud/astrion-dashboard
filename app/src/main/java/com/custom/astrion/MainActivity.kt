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
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.database.ContentObserver
import android.provider.Settings
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
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlin.math.sqrt
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.custom.astrion.ui.AstrionTheme
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import com.custom.astrion.config.DashboardConfig
import com.custom.astrion.config.DashboardLoader
import com.custom.astrion.config.HotkeyConfig
import com.custom.astrion.config.JsonPlain
import com.custom.astrion.config.AppConfig
import com.custom.astrion.ha.ConnectionState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.input.HardwareKey
import com.custom.astrion.input.HardwareKeyRouter
import com.custom.astrion.input.KeyDispatcher
import com.custom.astrion.input.KeyTimers
import com.custom.astrion.power.MotionWake
import com.custom.astrion.ir.IrBlaster
import com.custom.astrion.ir.IrModeOverlay
import com.custom.astrion.ui.AlarmOverlay
import com.custom.astrion.ui.AlarmUiState
import com.custom.astrion.ui.AlertAction
import com.custom.astrion.ui.AlertOverlay
import com.custom.astrion.ui.AlertSpec
import com.custom.astrion.ui.activeAlerts
import com.custom.astrion.ui.nextAlertDueMs
import com.custom.astrion.ui.Dashboard
import com.custom.astrion.ui.ActionToast
import com.custom.astrion.ui.AstrionMaterialTheme
import com.custom.astrion.ui.ToastMessage
import com.custom.astrion.ui.humanise
import com.custom.astrion.ui.LocalDashboardShowing
import com.custom.astrion.ui.MinuteClock
import com.custom.astrion.ui.LocalSheetHost
import com.custom.astrion.ui.SheetHost
import com.custom.astrion.ui.StrongHaptics
import com.custom.astrion.ui.Screensaver
import com.custom.astrion.ui.unplacedWhen
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

        /**
         * Wanted but blocked on something with no event of its own (the mic
         * permission granted from Settings): look again this often.
         */
        const val WAKE_WORD_RECHECK_MS = 60_000L

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

        /** Motion-wake cooldown, so a single lift fires one wake. */
        const val WAKE_COOLDOWN_MS = 2000L

        /**
         * How often the idle timer looks again while something holds the
         * screensaver off (IR mode, the alarm, voice). When nothing does, it
         * waits exactly as long as the idle time has left.
         */
        const val SCREENSAVER_TICK_MS = 5_000L

        /** While the screensaver is up: re-check the backlight for sunset/sunrise. */
        const val SCREENSAVER_SHOWN_TICK_MS = 60_000L

        /**
         * Some MediaTek chargers report NOT_CHARGING rather than FULL once the
         * battery tops out on the dock; at or above this level that still
         * counts as docked.
         */
        const val TOPPED_UP_PCT = 95

        /** On the dock but this many points below its peak: the dock isn't keeping up. */
        const val DOCK_DRAIN_PCT = 3
    }

    private val keyHandler = Handler(Looper.getMainLooper())

    /** Wakes the screen when the remote is picked up; see [MotionWake]. */
    private lateinit var motionWake: MotionWake
    private var lastWakeMs = 0L

    private lateinit var client: HaClient
    private val keyRouter = HardwareKeyRouter()

    /** Tap / hold / double-tap timing for the bound keys. */
    private val keys = KeyDispatcher(
        keyRouter,
        object : KeyTimers {
            override fun postDelayed(r: Runnable, delayMs: Long) { keyHandler.postDelayed(r, delayMs) }
            override fun remove(r: Runnable) = keyHandler.removeCallbacks(r)
        },
        onHoldFired = { holdHaptics.performHapticFeedback(HapticFeedbackType.LongPress) },
    )

    /** Confirmation / failure pill; see ui/ActionToast.kt and [watchOutcomes]. */
    private var toast by mutableStateOf<ToastMessage?>(null)

    /** In-window popups (light, vacuum, media browser); see ui/Sheet.kt. */
    private val sheetHost = SheetHost()

    /** Felt confirmation that a 1.5s hardware hold registered. */
    private val holdHaptics by lazy { StrongHaptics(this) }

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
    // ---- Alerts (leak / intruder / washer / door…) ---------------------------
    /** Tokens ("id@last_changed") hidden from this remote until they re-fire. */
    private var hiddenAlerts by mutableStateOf(setOf<String>())
    /** Clock for `for_seconds` alerts; ticked by [watchAlerts]. */
    private var alertNow by mutableStateOf(System.currentTimeMillis())

    /** Whether this Activity is in front, so a ring only reorders when needed. */
    private var inFront = false
        set(value) { field = value; inFrontState = value }

    /** Compose-visible copies of [inFront] and the screen state, for [LocalDashboardShowing]. */
    private var inFrontState by mutableStateOf(false)
    private var screenOnState by mutableStateOf(true)

    // ---- Voice --------------------------------------------------------------
    private lateinit var voice: VoiceSession

    // ---- Docked screensaver -------------------------------------------------
    /**
     * Sitting in the dock AND actually taking charge, steadily for
     * `power.dock_debounce_seconds`. From ACTION_BATTERY_CHANGED.
     *
     * Plugged-in alone used to be enough. A remote seated badly on its dock
     * can read as plugged while drawing too little current, or flick in and
     * out of contact — and was then treated as on mains: screen held on,
     * wake word streaming, every flicker restarting the undocked wake word
     * grace. It drained faster than the dock could fill it.
     */
    private var docked by mutableStateOf(false)
    private var batteryPct by mutableStateOf<Int?>(null)
    private var charging by mutableStateOf(false)
    /** Raw EXTRA_PLUGGED, for the battery report. */
    private var plugged = false
    /** EXTRA_STATUS, for the battery report. */
    private var batteryStatus = BatteryManager.BATTERY_STATUS_UNKNOWN
    /** Confirms [docked] once charging has held for the debounce. */
    private val dockConfirm = Runnable { updateDocked(true) }
    private var dockConfirmPending = false
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
            keyHandler.postDelayed(this, checkScreensaver())
        }
    }

    private val storagePermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { reloadDashboard(force = true) }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) voice.start(features.voice.pipeline) }

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

        motionWake = MotionWake(this, keyHandler, ::wakeScreen)

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
        startForegroundService(Intent(this, KeepAliveService::class.java))
        requestBatteryExemptionOnce()
        watchNetwork()
        watchAlarm()
        watchAlerts()
        watchWakeWord()
        watchScreen()
        watchScreenTimeout()
        watchBatteryReport()
        watchOutcomes()
        // Sticky broadcast: registering hands back the current state at once.
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?.let { onBatteryChanged(it) }

        setContent {
            // Manrope everywhere by default (headings opt into Syne), a light
            // ripple, themed Material pieces and real haptics: see AstrionMaterialTheme.
            AstrionMaterialTheme {
              CompositionLocalProvider(LocalSheetHost provides sheetHost) {
                val connection = client.connection.collectAsState()
                // Derived, so this root scope recomposes only when the alarm or
                // the set of alerts actually changes. Both read client.live by
                // id, so they re-run only when one of their own entities
                // changes, not on every HA update.
                val alarm by remember { derivedStateOf { alarmUiState(client.live) } }
                val alerts by remember {
                    derivedStateOf {
                        activeAlerts(alertSpecs(), client.live, alertNow)
                            .filter { it.token !in hiddenAlerts }
                    }
                }
                // Overlays are stacked in the SAME window as the dashboard (not
                // Dialogs) so this Activity keeps key focus and dispatchKeyEvent
                // continues to fire while they're on screen.
                Box(modifier = Modifier.fillMaxSize()) {
                    // Still composed under the screensaver, so waking it is
                    // instant and keeps the page — but not placed, so it isn't
                    // drawn: its radar dots and vacuum animation otherwise kept
                    // repainting behind the black face all night.
                    Box(modifier = Modifier.fillMaxSize().unplacedWhen(screensaverOn)) {
                    CompositionLocalProvider(
                        LocalDashboardShowing provides (screenOnState && inFrontState && !screensaverOn),
                    ) {
                    MinuteClock {
                    Dashboard(
                        client = client,
                        connectionState = connection,
                        config = dashboard.config,
                        configNotice = dashboard.notice,
                        navTarget = navTarget,
                        onNavHandled = { navTarget = null },
                    )
                    // Light / vacuum / media-browser popups, in this window so
                    // the hardware keys and the idle timer keep working.
                    sheetHost.Host()
                    }
                    }
                    }

                    // Docked screensaver: above the dashboard, below everything
                    // that must interrupt it (alarm, voice) — and those also
                    // dismiss it outright, see hideScreensaver's callers.
                    if (screensaverOn) ScreensaverHost(connection)

                    // IR Mode modal sits above the dashboard while active.
                    if (irMode) {
                        IrModeOverlay(
                            options = features.ir.raw,
                            client = client,
                            blaster = irBlaster,
                            lastKeyLabel = irLastKey,
                            onClose = { irMode = false; irLastKey = null },
                        )
                    }

                    // Configured alerts: above IR Mode, below the work alarm.
                    if (alerts.isNotEmpty() && (alarm == null || alarmHidden)) {
                        val top = alerts.first()
                        AlertOverlay(
                            alert = top,
                            moreCount = alerts.size - 1,
                            nowMs = alertNow,
                            onAction = { fireAlertAction(it) },
                            onHide = { hiddenAlerts = hiddenAlerts + top.token },
                        )
                    }

                    // Work alarm: above the dashboard and IR Mode, since it's the
                    // one overlay that must not be missed.
                    val ringing = alarm
                    if (ringing != null && !alarmHidden) {
                        AlarmOverlay(
                            state = ringing,
                            onSnooze = { fireAlarmAction(features.alarm.snooze) },
                            onDismiss = { fireAlarmAction(features.alarm.stop) },
                            onHide = { alarmHidden = true },
                        )
                    }

                    // What a button did (or that it didn't): below the alarm and
                    // voice, above everything else.
                    ActionToast(toast, onGone = { toast = null })

                    // Voice modal, driven by the session's own state machine.
                    VoiceHost()
                }
              }
            }
        }
    }

    /**
     * Collects the voice state in its own scope: while listening the mic
     * level updates it several times a second, which collected at the root
     * re-ran the whole root content each time.
     */
    @Composable
    private fun VoiceHost() {
        val voiceState = voice.state.collectAsState().value
        VoiceOverlay(
            state = voiceState,
            imageDir = features.voice.imageDir,
            onDismiss = {
                if (voiceState.phase == VoicePhase.LISTENING) voice.stopListening()
                else voice.cancel()
            },
        )
    }

    /**
     * The screensaver reads the whole entity map; hosting it in its own scope
     * keeps those reads from recomposing everything else at the root.
     */
    @Composable
    private fun ScreensaverHost(connection: State<ConnectionState>) {
        Screensaver(
            options = features.screensaver.raw,
            entities = { client.snapshot() },
            client = client,
            connected = connection.value == ConnectionState.CONNECTED,
            batteryPct = batteryPct,
            charging = charging,
        )
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

    /** Modified time + size of the config file at the last load. */
    private var loadedConfigStamp: Pair<Long, Long>? = null

    /**
     * Load config from disk and (re)bind hotkeys. Runs on every foreground, so
     * an unchanged file is skipped: re-parsing it on the main thread delayed
     * every screen wake, and swapping in an equal-but-new config rebuilt
     * every card on every page.
     */
    private fun reloadDashboard(force: Boolean = false) {
        val file = DashboardLoader.configFile
        // lastModified() is 0 when the file is missing or unreadable, so the
        // fallback paths (write defaults / no permission) always re-run.
        val stamp = file.lastModified() to file.length()
        if (!force && stamp.first != 0L && stamp == loadedConfigStamp) return
        val result = DashboardLoader.load()
        loadedConfigStamp = file.lastModified() to file.length()
        dashboard = result
        bindHotkeys(result.config.hotkeys, result.config.longHotkeys, result.config.doubleHotkeys)
        enforceScreenTimeout()
    }

    // ---- hotkeys ------------------------------------------------------------

    /** Rebind the physical buttons to the config's short, long and double hotkeys. */
    private fun bindHotkeys(
        short: List<HotkeyConfig>,
        long: List<HotkeyConfig>,
        double: List<HotkeyConfig> = emptyList(),
    ) {
        keyRouter.clear()
        keys.pageJumpKeys.clear()
        keys.cancelPendingSingle()
        short.forEach { hk ->
            val key = runCatching { HardwareKey.valueOf(hk.key.uppercase()) }.getOrNull()
                ?: return@forEach
            keyRouter.on(key) { runHotkey(hk) }
            if (hk.page != null && hk.then.isEmpty()) keys.pageJumpKeys += key
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
        irCodes = IrBlaster.fromConfig(features.ir.codes)
        irRepeat = features.ir.repeat
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
        val opts = features.ir
        val useLong = opts.toggleLong
        val key = runCatching { HardwareKey.valueOf(opts.toggleKey.uppercase()) }.getOrNull() ?: return
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

    /** dashboard.json's feature blocks, typed once per loaded config. */
    private val features get() = dashboard.config.features

    /**
     * Null when no alarm is on; otherwise what the popup should show. Pure
     * mirror of HA: ringing = the `ringing_entity` flag, snoozed = the snooze
     * timer running while that flag is still on.
     */
    private fun alarmUiState(entities: com.custom.astrion.ha.EntityMap): AlarmUiState? {
        val opts = features.alarm
        val ringingId = opts.ringingEntity ?: return null
        if (entities[ringingId]?.state != "on") return null

        val timer = opts.snoozeTimer?.let { entities[it] }
        val snoozeEnds = timer?.takeIf { it.state == "active" }?.attrString("finishes_at")?.let { iso ->
            runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        }

        // The timer's own duration ("0:05:00"), so the snooze ring drains
        // over the real length rather than an assumed five minutes.
        val snoozeTotalMs = timer?.attrString("duration")?.split(":")?.mapNotNull { it.toLongOrNull() }
            ?.takeIf { it.size == 3 }?.let { (h, m, sec) -> (h * 3600 + m * 60 + sec) * 1000 }
            ?: 300_000L

        val info = opts.infoEntity?.let { entities[it] }
        val startsAt = info?.state?.let { iso ->
            runCatching {
                val t = java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli()
                alarmTimeFormat.format(java.util.Date(t))
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

    /** For the alarm popup's start time; only ever used on the main thread. */
    private val alarmTimeFormat = java.text.SimpleDateFormat("h:mm a", java.util.Locale.getDefault())

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
            // Reads only the alarm's entities (client.live by id), so this
            // re-runs when they change rather than on every HA update.
            snapshotFlow { alarmPhase(client.live) }
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

    /** `alerts` list from dashboard.json. */
    private fun alertSpecs(): List<AlertSpec> {
        // Parsed once per loaded config rather than on every check.
        val config = dashboard.config
        if (config !== alertSpecsFor) {
            alertSpecsCached = AlertSpec.parse(config.options["alerts"])
            alertSpecsFor = config
        }
        return alertSpecsCached
    }
    private var alertSpecsFor: AppConfig? = null
    private var alertSpecsCached: List<AlertSpec> = emptyList()

    /**
     * Like [watchAlarm] for the configured alerts. A new "alarm" wakes the
     * screen and keeps it on until cleared or hidden; a "warning" wakes it
     * once; an "info" just waits to be seen.
     */
    private fun watchAlerts() {
        var lastToken: String? = null
        var keepAwake: kotlinx.coroutines.Job? = null
        alarmScope.launch {
            // Re-evaluated when an alert's entity, the config or a hide
            // changes (client.live reads subscribe per entity), and when
            // [alertNow] ticks. The clock only ticks when something needs
            // it: a `for_seconds` alert coming due, or one on screen whose
            // "for N min" must move. This used to scan every 3 s for the
            // life of the app, alerts or not.
            snapshotFlow {
                val specs = alertSpecs()
                val now = alertNow
                val top = activeAlerts(specs, client.live, now).firstOrNull { it.token !in hiddenAlerts }
                Triple(top, nextAlertDueMs(specs, client.live, now), top != null)
            }.collectLatest { (top, dueMs, shown) ->
                if (top?.token != lastToken) {
                    keepAwake?.cancel()
                    keepAwake = null
                    when (top?.spec?.severity) {
                        "alarm" -> {
                            hideScreensaver()
                            wakeForAlarm()
                            keepAwake = alarmScope.launch {
                                while (true) {
                                    delay(20_000)
                                    wakeForAlarm()
                                }
                            }
                        }
                        "warning" -> {
                            hideScreensaver()
                            wakeForAlarm()
                        }
                    }
                    // Nothing urgent left: let the screen time out again,
                    // unless the work alarm is the one holding it on.
                    if (top?.spec?.severity != "alarm" && top?.spec?.severity != "warning" &&
                        alarmPhase(client.live) != 1
                    ) {
                        @Suppress("DEPRECATION")
                        window.clearFlags(
                            WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON or
                                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON
                        )
                        applyDockKeepAwake()
                    }
                    lastToken = top?.token
                }
                val now = System.currentTimeMillis()
                val wait = listOfNotNull(
                    dueMs?.let { it - now },
                    if (shown) 60_000 - now % 60_000 else null,
                ).minOrNull() ?: return@collectLatest
                delay(wait.coerceAtLeast(250))
                alertNow = System.currentTimeMillis()
            }
        }
    }

    private fun fireAlertAction(action: AlertAction) {
        client.callService(ServiceCall.fromConfig(action.service, action.entityId, action.data))
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

    /** Fire the configured `snooze` / `stop` action. */
    private fun fireAlarmAction(action: com.custom.astrion.config.AlarmAction?) {
        action ?: return
        client.callService(ServiceCall.fromConfig(action.service, action.entityId))
    }

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
                    voice.start(features.voice.pipeline)
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
            // A popup belongs to the page it was opened on.
            sheetHost.dismissAll()
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
     * and two act on the remote itself, standing in for Key Mapper:
     *   astrion.open_settings — Android Settings
     *   astrion.launch        — the app in `data.package` (e.g. "com.aiks.HaRemote")
     */
    private fun runAction(hk: HotkeyConfig): Boolean {
        val service = hk.service ?: return false
        val entityId = hk.entityId
        when (service) {
            "astrion.toggle_mute" -> {
                if (entityId == null) return false
                val muted = client.live[entityId]?.attrString("is_volume_muted") == "true"
                client.callService(
                    ServiceCall.of("media_player", "volume_mute", entityId, "is_volume_muted" to !muted)
                )
                return true
            }
            "astrion.unjoin_others" -> {
                if (entityId == null) return false
                // group_members[0] is the group leader; everyone else is joined
                // to it and gets dropped.
                client.live[entityId]?.attrStringList("group_members")
                    ?.filter { it != entityId }
                    ?.forEach { client.callService(ServiceCall("media_player", "unjoin", entityId = it)) }
                return true
            }
            "astrion.open_settings" -> {
                return runCatching {
                    startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }.isSuccess
            }
            "astrion.launch" -> {
                val pkg = hk.data["package"] as? String ?: return false
                val intent = packageManager.getLaunchIntentForPackage(pkg)
                if (intent == null) {
                    Toast.makeText(this, "Not installed: $pkg", Toast.LENGTH_SHORT).show()
                    return false
                }
                return runCatching { startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
            }
        }
        val domain = service.substringBefore('.')
        val svc = service.substringAfter('.')
        val data = hk.data.mapValues { JsonPlain.toJson(it.value) }
        // Keys that repeat or whose effect you see/hear at once (D-pad, TV
        // commands, volume) don't get a "done" pill; failures always do.
        val quiet = domain == "remote" || domain == "androidtv" || svc.startsWith("volume_")
        client.callService(ServiceCall(domain, svc, entityId, data), confirm = !quiet)
        return true
    }

    // ---- action feedback ------------------------------------------------------

    private fun watchOutcomes() {
        alarmScope.launch {
            client.outcomes.collect { o ->
                val label = callLabel(o.call)
                toast = when (o.kind) {
                    HaClient.CallOutcome.Kind.DONE -> ToastMessage(System.nanoTime(), "✓  $label")
                    HaClient.CallOutcome.Kind.FAILED ->
                        ToastMessage(System.nanoTime(), "Didn't work: $label", o.error, bad = true)
                    HaClient.CallOutcome.Kind.QUEUED ->
                        ToastMessage(System.nanoTime(), "Offline", "Will send when Home Assistant is back", bad = true)
                    HaClient.CallOutcome.Kind.DROPPED ->
                        ToastMessage(System.nanoTime(), "Not sent: $label", "Home Assistant was offline too long", bad = true)
                }
            }
        }
    }

    /** "Club lights off", "Hue play · toggle", or "light.toggle" when nothing better is known. */
    private fun callLabel(call: ServiceCall): String {
        val name = call.entityId?.let { client.live[it]?.friendlyName }
            ?: return "${call.domain}.${call.service}"
        return if (call.domain in setOf("script", "scene", "automation")) name
        else "$name · ${call.service.humanise().lowercase()}"
    }

    /**
     * Physical buttons arrive as standard KeyEvents. The screensaver, popups,
     * IR mode and the voice key get first look; bound keys then go through
     * [KeyDispatcher]'s tap / hold / double-tap timing.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val code = event.keyCode
        val key = HardwareKey.fromKeyCode(code)

        // ---- Screensaver ----------------------------------------------------
        // Any button wakes it, and by default that press also does its normal
        // job, so the first press is never lost. `keys_pass_through: false`
        // makes it only the wake — the same as a touch. A list of key names
        // passes just those through, e.g. volume and the page keys, while the
        // D-pad / OK / app keys only wake: pressing OK to wake a docked remote
        // was also switching the TV on (21 of 68 Google TV sessions played
        // nothing).
        markActivity()
        if (screensaverOn) {
            hideScreensaver()
            val passThrough = features.screensaver.passesThrough(key.name)
            if (event.action == KeyEvent.ACTION_DOWN && !passThrough) {
                swallowKey = code
                return true
            }
        }
        if (code == swallowKey) {
            if (event.action == KeyEvent.ACTION_UP) swallowKey = -1
            return true
        }

        // BACK closes an open popup before it does anything else.
        if (key == HardwareKey.BACK && sheetHost.isShowing) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) sheetHost.dismissTop()
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

        // Unmapped: log/toast for diagnosis, then let the OS handle it.
        if (!keys.isBound(code)) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
                Log.i(KEY_TAG, "keyCode=$code (${KeyEvent.keyCodeToString(code)})")
                if (DEBUG_KEYS) Toast.makeText(this, "Unmapped key: $code", Toast.LENGTH_SHORT).show()
            }
            return super.dispatchKeyEvent(event)
        }

        if (keys.handle(code, event.action, event.repeatCount)) return true
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
            // An alert popup is drawn above the screensaver and is what the
            // finger is aimed at — let that tap land ("Emptied", "Lock it").
            swallowTouch = activeAlerts(alertSpecs(), client.live, alertNow)
                .none { it.token !in hiddenAlerts }
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

    private fun screensaverEnabled(): Boolean = features.screensaver.enabled

    /**
     * Docked: after `idle_seconds`. Off the dock: after `undocked_idle_seconds`
     * (dimmer, and the system timeout then turns the screen off), unless that
     * is -1. `trigger: "always"` keeps the old meaning: the docked idle time
     * off the dock as well.
     */
    private fun screensaverArmed(): Boolean =
        screensaverEnabled() && (docked || features.screensaver.alwaysTrigger || features.screensaver.undockedIdleMs >= 0)

    private fun markActivity() {
        lastActivityMs = System.currentTimeMillis()
    }

    /**
     * Highest level seen since it was last put on the dock; null off the
     * dock. A remote whose dock pins barely touch can report "charging" while
     * drawing less than it uses: 141 sat "plugged" from 68% down to 42% on
     * 4 Oct. Falling [DOCK_DRAIN_PCT] below this counts as a dock fault too.
     */
    private var pluggedPeakPct: Int? = null

    private fun onBatteryChanged(intent: Intent) {
        plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        batteryPct = if (level >= 0 && scale > 0) level * 100 / scale else null
        pluggedPeakPct = if (!plugged) null else maxOf(pluggedPeakPct ?: 0, batteryPct ?: 0)
        batteryStatus = intent.getIntExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_UNKNOWN)
        charging = batteryStatus == BatteryManager.BATTERY_STATUS_CHARGING
        // Leaving the dock (or losing charge on it) counts at once; arriving
        // only once charging has held for the debounce.
        if (!takingCharge()) {
            keyHandler.removeCallbacks(dockConfirm)
            dockConfirmPending = false
            updateDocked(false)
        } else if (!docked && !dockConfirmPending) {
            dockConfirmPending = true
            keyHandler.postDelayed(dockConfirm, features.power.dockDebounceSeconds * 1000L)
        }
        reportBattery()
    }

    /** Plugged in and the battery actually charging (or already full). */
    private fun takingCharge(): Boolean = plugged && when (batteryStatus) {
        BatteryManager.BATTERY_STATUS_CHARGING, BatteryManager.BATTERY_STATUS_FULL -> true
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> (batteryPct ?: 0) >= TOPPED_UP_PCT
        else -> false
    }

    private fun updateDocked(value: Boolean) {
        dockConfirmPending = false
        if (value == docked) return
        docked = value
        if (!value) undockedAtMs = System.currentTimeMillis()
        // Docked and undocked idle times differ.
        rescheduleScreensaverTick()
        // Dropping it in the dock starts the idle countdown from now;
        // lifting it out takes the screensaver down at once (it comes back,
        // dimmer, once it's been put down off the dock).
        markActivity()
        if (!docked || !screensaverArmed()) hideScreensaver()
        applyDockKeepAwake()
        if (docked) listenForMotion(false)
        reportBattery()
    }

    // ---- battery report to HA -----------------------------------------------

    /** Last report sent, so an unchanged state isn't re-posted. */
    private var lastBatteryReport: List<Any?>? = null

    /**
     * Mirror this remote's battery into HA as `power.report_entity` (e.g.
     * `sensor.lounge_remote_battery`), so HA can tell you when a remote is
     * on its dock but not charging (`dock_fault`), or running flat. Off
     * unless configured. States set this way don't survive an HA restart,
     * so it's re-sent on every (re)connect.
     */
    private fun reportBattery(force: Boolean = false) {
        val entityId = features.power.reportEntity ?: return
        val pct = batteryPct ?: return
        if (client.connection.value != ConnectionState.CONNECTED) return
        val draining = (pluggedPeakPct ?: pct) - pct >= DOCK_DRAIN_PCT
        val dockFault = plugged && (!takingCharge() || draining)
        val report = listOf(entityId, pct, plugged, batteryStatus, docked, dockFault)
        if (!force && report == lastBatteryReport) return
        lastBatteryReport = report
        val status = when (batteryStatus) {
            BatteryManager.BATTERY_STATUS_CHARGING -> "charging"
            BatteryManager.BATTERY_STATUS_FULL -> "full"
            BatteryManager.BATTERY_STATUS_DISCHARGING -> "discharging"
            BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "not_charging"
            else -> "unknown"
        }
        val attrs = mutableMapOf<String, Any?>(
            "unit_of_measurement" to "%",
            "device_class" to "battery",
            "state_class" to "measurement",
            "plugged" to plugged,
            "charging" to charging,
            "status" to status,
            "docked" to docked,
            "dock_fault" to dockFault,
            "dock_draining" to draining,
        )
        features.power.reportName?.let { attrs["friendly_name"] = it }
        client.postState(entityId, pct.toString(), attrs)
    }

    /** Re-send the battery report whenever the connection comes (back) up. */
    private fun watchBatteryReport() {
        alarmScope.launch {
            client.connection.collect { if (it == ConnectionState.CONNECTED) reportBattery(force = true) }
        }
    }

    /**
     * Docked, the screen stays on so the screensaver is actually seen rather
     * than the system timeout blanking the panel first (`keep_screen_on`).
     * The alarm manages the same flag, so it calls back in here after clearing.
     */
    private fun applyDockKeepAwake() {
        val keep = docked && screensaverEnabled() && features.screensaver.keepScreenOn
        if (keep) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else if (alarmPhase(client.live) != 1) {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * Idle tick: show the screensaver when due, and keep its backlight right.
     * Returns when to look again: at the moment the idle time would run out
     * rather than every few seconds, and once a minute while it is up.
     */
    private fun checkScreensaver(): Long {
        if (screensaverOn) {
            // Re-evaluated each tick so the backlight follows sunset/sunrise.
            applyScreensaverBrightness()
            return SCREENSAVER_SHOWN_TICK_MS
        }
        if (!inFront || !screensaverArmed() || irMode) return SCREENSAVER_TICK_MS
        if (alarmUiState(client.live) != null && !alarmHidden) return SCREENSAVER_TICK_MS
        val phase = voice.state.value.phase
        if (phase != VoicePhase.IDLE && phase != VoicePhase.DONE) return SCREENSAVER_TICK_MS
        val dockedIdleMs = features.screensaver.idleMs
        val idleMs = if (docked) dockedIdleMs else features.screensaver.undockedIdleMs.takeIf { it >= 0 } ?: dockedIdleMs
        val left = idleMs - (System.currentTimeMillis() - lastActivityMs)
        // Activity since only pushes the deadline later, so waking at the old
        // one and measuring again is always early enough.
        if (left > 0) return left + 50
        screensaverOn = true
        // Waking it lands on the start page, not wherever it was left.
        navTarget = dashboard.config.startPage
        applyScreensaverBrightness()
        return SCREENSAVER_SHOWN_TICK_MS
    }

    private fun applyScreensaverBrightness() {
        val opts = features.screensaver
        val night = screensaverIsNight(client.live, System.currentTimeMillis())
        val level = when {
            !docked -> minOf(opts.undockedBrightness, if (night) opts.nightBrightness else 1f)
            night -> opts.nightBrightness
            else -> opts.brightness
        }
        setWindowBrightness(level.coerceIn(0.01f, 1f))
    }

    /** Look again soon: something changed what the idle deadline is. */
    private fun rescheduleScreensaverTick() {
        keyHandler.removeCallbacks(screensaverTick)
        if (inFront) keyHandler.postDelayed(screensaverTick, SCREENSAVER_TICK_MS)
    }

    private fun hideScreensaver() {
        if (!screensaverOn) return
        screensaverOn = false
        rescheduleScreensaverTick()
        markActivity()
        setWindowBrightness(WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE)
    }

    private fun setWindowBrightness(value: Float) {
        val lp = window.attributes
        if (lp.screenBrightness == value) return
        lp.screenBrightness = value
        window.attributes = lp
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
            // Re-checked when something it depends on changes (dock, config,
            // connection, the session's own state) and at the one moment a
            // clock matters (the undocked grace or a failure backoff running
            // out), instead of every 2 s for the life of the app.
            combine(
                snapshotFlow { docked to features.voice },
                client.connection,
                voice.state.map { it.armed to it.phase }.distinctUntilChanged(),
            ) { _, _, _ -> }.collectLatest {
                while (true) {
                    val wait = updateWakeWord() ?: break
                    delay(wait)
                }
            }
        }
    }

    /** When the remote last left the dock; 0 = not since launch. */
    private var undockedAtMs = 0L

    /**
     * Arm or disarm the wake word to match the dock, config and connection.
     * Returns how long until it needs looking at again with nothing else
     * changing, or null if only an event can change the answer.
     */
    private fun updateWakeWord(): Long? {
        val now = System.currentTimeMillis()
        val graceMs = features.voice.wakeWordUndockedMs
        val mode = features.voice.wakeWord
        val graceLeft = if (!docked && undockedAtMs > 0) undockedAtMs + graceMs - now else 0L
        val wantByMode = when (mode) {
            "always" -> true
            "docked" -> docked || graceLeft > 0
            else -> false
        }
        // Permission can be granted from Settings without any event here.
        val want = wantByMode && voice.hasPermission && client.connection.value == ConnectionState.CONNECTED

        val s = voice.state.value
        val backoffLeft = voice.lastWakeFailureAt + WAKE_WORD_BACKOFF_MS - now
        if (want && !s.armed && s.phase == VoicePhase.IDLE && backoffLeft <= 0) {
            voice.armWakeWord(features.voice.pipeline)
        } else if (!want && s.armed) {
            voice.disarm()
        }
        holdWakeWordLock(voice.state.value.armed)

        return listOfNotNull(
            graceLeft.takeIf { mode == "docked" && it > 0 },
            backoffLeft.takeIf { want && it > 0 },
            WAKE_WORD_RECHECK_MS.takeIf { wantByMode && !want },
        ).minOrNull()?.coerceAtLeast(250)
    }

    /**
     * Reconnect the moment the network returns (Wi-Fi drop, router reboot)
     * instead of waiting out the client's reconnect backoff.
     */
    private fun watchNetwork() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = client.onNetworkAvailable()
        }
        cm.registerDefaultNetworkCallback(callback)
        networkCallback = callback
    }
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    /**
     * Doze (screen off, on battery, lying still) cuts network access for
     * apps that aren't exempt from battery optimisation — the HA connection
     * then goes quiet and the alarm can't arrive. Ask for the exemption once;
     * if it's declined, it can still be granted in Settings > Battery.
     */
    private fun requestBatteryExemptionOnce() {
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        if (pm.isIgnoringBatteryOptimizations(packageName)) return
        val prefs = getSharedPreferences("astrion", Context.MODE_PRIVATE)
        if (prefs.getBoolean("asked_battery_exemption", false)) return
        prefs.edit().putBoolean("asked_battery_exemption", true).apply()
        runCatching {
            @Suppress("BatteryLife")
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        }
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

    /**
     * Only listen while the screen is off — with it on, wakeScreen() has
     * nothing to do — and then only for `power.motion_wake_minutes`
     * (default 5; 0 = never, -1 = for as long as it's off), and not on the
     * dock.
     */
    private fun listenForMotion(on: Boolean) {
        if (on && !docked) motionWake.start(features.power.motionWakeMinutes) else motionWake.stop()
    }

    // ---- screen timeout -------------------------------------------------------

    private val timeoutObserver by lazy {
        object : ContentObserver(keyHandler) {
            override fun onChange(selfChange: Boolean) = enforceScreenTimeout()
        }
    }

    /**
     * Keep the system screen timeout at `power.screen_timeout_seconds`.
     * Docked, the window's keep-screen-on flag overrides it anyway; off the
     * dock it is what finally turns the screen off. Needs WRITE_SETTINGS,
     * granted once over adb (`appops set com.custom.astrion WRITE_SETTINGS
     * allow`, see docs/POWER.md); without it this does nothing.
     */
    private fun enforceScreenTimeout() {
        val secs = features.power.screenTimeoutSeconds
        if (secs <= 0 || !Settings.System.canWrite(this)) return
        val want = secs * 1000
        val current = Settings.System.getInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, -1)
        if (current == want) return
        runCatching { Settings.System.putInt(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT, want) }
            .onFailure { Log.w("Astrion", "Couldn't set screen timeout", it) }
    }

    private fun watchScreenTimeout() {
        contentResolver.registerContentObserver(
            Settings.System.getUriFor(Settings.System.SCREEN_OFF_TIMEOUT), false, timeoutObserver,
        )
        enforceScreenTimeout()
    }

    // ---- screen on / off ------------------------------------------------------

    /** Pending switch to the screen-off entity filter. */
    private var filterJob: kotlinx.coroutines.Job? = null

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) =
            onScreen(intent.action == Intent.ACTION_SCREEN_ON)
    }

    private fun watchScreen() {
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        })
        val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm?.isInteractive == false) onScreen(false)
    }

    /**
     * Screen off: start the motion-wake window, and after
     * `power.screen_off_filter_seconds` (default 30; -1 = never) narrow the
     * HA subscription to what the alarm and alerts watch — see
     * [HaClient.setEntityFilter]. The delay keeps a quick off/on from
     * costing a full re-snapshot. Screen on: everything again.
     */
    private fun onScreen(on: Boolean) {
        screenOnState = on
        client.setForeground(on)
        listenForMotion(!on)
        filterJob?.cancel()
        filterJob = null
        if (on) {
            client.setEntityFilter(null)
            return
        }
        val delaySec = features.power.screenOffFilterSeconds
        if (delaySec < 0) return
        filterJob = alarmScope.launch {
            delay(delaySec * 1000L)
            client.setEntityFilter(screenOffEntities())
        }
    }

    /** What must keep arriving with the screen off: the alarm's and alerts' entities. */
    private fun screenOffEntities(): Set<String> {
        val ids = features.alarm.entities.toMutableSet()
        alertSpecs().forEach { spec ->
            ids += spec.entity
            spec.unlessEntity?.let { ids += it }
        }
        ids += features.power.screenOffEntities
        return ids
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
        keyHandler.removeCallbacks(dockConfirm)
        motionWake.stop()
        runCatching { unregisterReceiver(screenReceiver) }
        runCatching { contentResolver.unregisterContentObserver(timeoutObserver) }
        networkCallback?.let { cb ->
            runCatching { (getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager).unregisterNetworkCallback(cb) }
        }
        keys.cancelPending()
        client.close()
        stopService(Intent(this, KeepAliveService::class.java))
        super.onDestroy()
    }
}
