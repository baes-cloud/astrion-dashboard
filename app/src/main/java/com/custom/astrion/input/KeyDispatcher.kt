package com.custom.astrion.input

import android.view.KeyEvent

/** Delayed callbacks, so the timing below runs on a Handler on device and a fake clock in tests. */
interface KeyTimers {
    fun postDelayed(r: Runnable, delayMs: Long)
    fun remove(r: Runnable)
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
    private val longPressMs: Long = LONG_PRESS_MS,
    private val doubleTapMs: Long = DOUBLE_TAP_MS,
) {
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
                    doubleH.invoke()
                    return true
                }
                // Page jumps don't wait for the release; see pageJumpKeys.
                tapFiredOnDown = -1
                if (repeatCount == 0 && (longH != null || doubleH != null) && key in pageJumpKeys) {
                    shortH?.invoke()
                    tapFiredOnDown = code
                }
                if (longH != null) {
                    // Long-capable: start the hold timer on first press, ignore repeats.
                    if (repeatCount == 0) {
                        cancelPendingLong()
                        longFired = false
                        activeLongKey = code
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
                    shortH?.invoke()
                }
                return true
            }
            KeyEvent.ACTION_UP -> {
                if ((longH != null || doubleH != null) && code == activeLongKey) {
                    cancelPendingLong()
                    activeLongKey = -1
                    // Already ran on the DOWN (a page jump): don't run it twice.
                    val tap = if (tapFiredOnDown == code) null else shortH
                    tapFiredOnDown = -1
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
                                tap?.invoke()
                            }
                            pendingSingle = r
                            pendingSingleKey = code
                            timers.postDelayed(r, doubleTapMs)
                        } else {
                            tap?.invoke()
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
        /** Hold this long for a button's long-press action to fire. */
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
    }
}
