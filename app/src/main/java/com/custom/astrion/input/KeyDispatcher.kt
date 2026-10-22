package com.custom.astrion.input

import android.view.KeyEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/** Delayed callbacks, so the timing below runs on Coroutines on device and a fake clock in tests. */
interface KeyTimers {
    fun postDelayed(r: Runnable, delayMs: Long)
    fun remove(r: Runnable)
}

/** Coroutine-based timers replacing legacy Android Handlers with structured concurrency. */
class CoroutineKeyTimers(private val scope: CoroutineScope) : KeyTimers {
    private val activeJobs = ConcurrentHashMap<Runnable, Job>()

    override fun postDelayed(r: Runnable, delayMs: Long) {
        activeJobs[r]?.cancel()
        val job = scope.launch {
            delay(delayMs)
            activeJobs.remove(r)
            r.run()
        }
        activeJobs[r] = job
    }

    override fun remove(r: Runnable) {
        activeJobs.remove(r)?.cancel()
    }
}

/**
 * Tap-vs-hold-vs-double logic for the bound hardware keys:
 *  - keys with a long-press binding fire their SHORT action on release (if
 *    released before [longPressMs]) or their LONG action once held past it;
 *  - keys with a double-tap binding hold their SHORT action back for
 *    [doubleTapMs] after release, in case a second tap is coming;
 *  - keys with only a short binding fire on each down (so volume etc. still
 *    repeat while held).
 */
class KeyDispatcher(
    private val router: HardwareKeyRouter,
    private val timers: KeyTimers,
    /** Runs just before a long action, for the hold's haptic tick. */
    private val onHoldFired: () -> Unit = {},
    private val onTapFired: () -> Unit = {},
    private val longPressMs: Long = LONG_PRESS_MS,
    private val doubleTapMs: Long = DOUBLE_TAP_MS,
) {
    /** Secondary constructor accepting a [CoroutineScope] for 100% structured concurrency. */
    constructor(
        router: HardwareKeyRouter,
        scope: CoroutineScope,
        onHoldFired: () -> Unit = {},
        onTapFired: () -> Unit = {},
        longPressMs: Long = LONG_PRESS_MS,
        doubleTapMs: Long = DOUBLE_TAP_MS,
    ) : this(router, CoroutineKeyTimers(scope), onHoldFired, onTapFired, longPressMs, doubleTapMs)
    /**
     * Keys whose tap is a plain page jump. Those jump on key-DOWN, even when
     * the key also has a hold or double-tap binding: a page jump is harmless
     * if the press turns out to be a hold or a double, and waiting for the
     * release (plus the double-tap window) made SCENE take 0.5–0.8s.
     */
    val pageJumpKeys = mutableSetOf<HardwareKey>()

    private var pendingLong: Runnable? = null
    private var activeLongKey = -1
    private var longFired = false

    // The deferred single-tap action, and which key it belongs to, so a
    // second tap of a DIFFERENT key doesn't consume it.
    private var pendingSingle: Runnable? = null
    private var pendingSingleKey = -1

    /** The key whose tap already ran on its DOWN, so its UP doesn't run it again. */
    private var tapFiredOnDown = -1

    /** The held key's press woke the screen, so its release is not a tap. */
    private var tapSuppressed = false

    /** The key whose first DOWN we saw, until its UP. */
    private var downKey = -1

    fun isBound(code: Int): Boolean =
        router.shortHandler(code) != null || router.longHandler(code) != null || router.doubleHandler(code) != null

    /**
     * Handle a DOWN or UP of a bound key. Returns false for any other action,
     * which the caller passes on to the system.
     */
    fun handle(code: Int, action: Int, repeatCount: Int): Boolean {
        val key = HardwareKey.fromKeyCode(code)
        val shortH = router.shortHandler(code)
        val longH = router.longHandler(code)
        val doubleH = router.doubleHandler(code)

        when (action) {
            KeyEvent.ACTION_DOWN -> {
                // Repeats of a key whose first DOWN never reached us: the
                // press began on a dark screen and woke it.
                val wokeOnHold = repeatCount > 0 && downKey != code
                if (repeatCount == 0) downKey = code
                // A press arriving while this key's single-tap is still held
                // back IS the second tap: cancel the deferred single and fire
                // the double instead. Checked before the long-press timer so a
                // double tap never also arms a hold.
                if (doubleH != null && repeatCount == 0 &&
                    pendingSingle != null && pendingSingleKey == code
                ) {
                    cancelPendingSingle()
                    cancelPendingLong()
                    longFired = true // suppress the short action on this release
                    activeLongKey = -1
                    onTapFired()
                    doubleH.invoke()
                    return true
                }
                // Page jumps don't wait for the release; see pageJumpKeys.
                tapFiredOnDown = -1
                if (repeatCount == 0 && (longH != null || doubleH != null) && key in pageJumpKeys) {
                    onTapFired()
                    shortH?.invoke()
                    tapFiredOnDown = code
                }
                if (longH != null) {
                    // Long-capable: start the hold timer on first press, ignore repeats.
                    // A repeat of a key we never saw go down is a hold that
                    // began on a dark screen: the system ate the first DOWN to
                    // wake it, so arm the timer from here instead of losing
                    // the hold. Its release must not run the tap: that press
                    // was the wake-up.
                    if (repeatCount == 0 || wokeOnHold) {
                        downKey = code
                        cancelPendingLong()
                        longFired = false
                        activeLongKey = code
                        tapSuppressed = wokeOnHold
                        val r = Runnable {
                            longFired = true
                            onHoldFired()
                            longH.invoke()
                        }
                        pendingLong = r
                        timers.postDelayed(r, longPressMs)
                    }
                } else if (doubleH != null) {
                    // Short-only but double-capable: nothing can fire until the
                    // window closes on release. Arming here rather than on UP
                    // would make a held key auto-repeat into a double tap.
                    longFired = false
                    activeLongKey = code
                } else {
                    // Short-only: fire on every down (preserves hold-to-repeat).
                    if (repeatCount == 0) onTapFired()
                    shortH?.invoke()
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if (code == downKey) downKey = -1
                if ((longH != null || doubleH != null) && code == activeLongKey) {
                    cancelPendingLong()
                    activeLongKey = -1
                    // Already ran on the DOWN (a page jump), or the press
                    // only woke the screen: don't run it.
                    val tap = if (tapFiredOnDown == code || tapSuppressed) null else shortH
                    tapFiredOnDown = -1
                    tapSuppressed = false
                    // Released before the hold threshold → it was a tap.
                    if (!longFired) {
                        if (doubleH != null) {
                            // Hold the single back until the window closes
                            // (still armed when the tap already ran, so a
                            // second tap is recognised as the double).
                            cancelPendingSingle()
                            val r = Runnable {
                                pendingSingle = null
                                pendingSingleKey = -1
                                if (tap != null) {
                                    onTapFired()
                                    tap.invoke()
                                }
                            }
                            pendingSingle = r
                            pendingSingleKey = code
                            timers.postDelayed(r, doubleTapMs)
                        } else {
                            if (tap != null) {
                                onTapFired()
                                tap.invoke()
                            }
                        }
                    }
                }
                return true
            }
        }
        return false
    }

    /** Drop anything pending, e.g. before rebinding or on destroy. */
    fun cancelPending() {
        cancelPendingLong()
        cancelPendingSingle()
    }

    fun cancelPendingSingle() {
        pendingSingle?.let { timers.remove(it) }
        pendingSingle = null
        pendingSingleKey = -1
    }

    private fun cancelPendingLong() {
        pendingLong?.let { timers.remove(it) }
        pendingLong = null
    }

    companion object {
        /**
         * Hold this long for a button's long-press action to fire. Was 1.5 s:
         * a hold let go a little early ran the tap instead (Page ▲/▼ nudged
         * the lights rather than moving the blinds), so holds often took a
         * few tries. Android's own hold is 0.5 s; 0.8 s keeps a firm tap a tap.
         */
        const val LONG_PRESS_MS = 800L

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
    }
}
