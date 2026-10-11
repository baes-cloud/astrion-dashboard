package com.custom.astrion.ui.ambient

/**
 * Smooths out skip-flicker: when someone taps skip twice fast, or a player
 * bounces A → B → A within a breath, the screen would otherwise rebuild the
 * whole scene for each flicker.
 *
 * The rule (from the brief): a brand-new item shows at once; but if the player
 * returns within [returnWindowMs] to the item it just left, hold the current
 * display for [holdMs] and only then show that returned item, and only if it is
 * still the playing one. So A → B → A inside the window waits a beat on B before
 * settling, instead of snapping back.
 *
 * Pure and clock-injected (callers pass `now`), so it unit-tests without a
 * device. [update] returns the track key that should currently be displayed
 * (null = nothing playing), given the key the player reports now.
 */
class FlipGuard(
    private val returnWindowMs: Long = 5_000L,
    private val holdMs: Long = 2_000L,
) {
    /** The track key currently on screen, or null for none. */
    var displayed: String? = null
        private set

    // The item we most recently switched away from, and when.
    private var leftKey: String? = null
    private var leftAt = 0L

    // A pending return we are holding before committing.
    private var holdKey: String? = null
    private var holdUntil = 0L

    /**
     * @param incoming the track key the player reports now (null = not playing).
     * @return the key that should be displayed (may lag [incoming] during a hold).
     */
    fun update(incoming: String?, now: Long): String? {
        // Already showing it: steady state, cancel any stale hold.
        if (incoming == displayed) { holdKey = null; return displayed }

        // Resolve an in-flight hold first.
        if (holdKey != null) {
            when {
                // Player moved on to yet another item: abandon the hold, that
                // new item is shown immediately below.
                incoming != holdKey -> holdKey = null
                // Still the held item and the window has elapsed: commit it.
                now >= holdUntil -> { commit(incoming, now); return displayed }
                // Still holding.
                else -> return displayed
            }
        }

        // Returning to the item we just left, inside the window: hold a beat.
        if (incoming != null && incoming == leftKey && now - leftAt <= returnWindowMs) {
            holdKey = incoming
            holdUntil = now + holdMs
            return displayed
        }

        // A new item (or the first): show it at once.
        commit(incoming, now)
        return displayed
    }

    private fun commit(key: String?, now: Long) {
        if (displayed != null) { leftKey = displayed; leftAt = now }
        displayed = key
        holdKey = null
    }
}
