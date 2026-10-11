package com.custom.astrion.ui.ambient

import com.custom.astrion.ha.EntityMap
import com.custom.astrion.ha.EntityState

/** What the ambient host should draw this moment. */
sealed interface AmbientUi {
    /** A player is current. [paused] drives the dimmed "between tracks / paused" look. */
    data class NowPlaying(val playerId: String, val paused: Boolean) : AmbientUi
    /** Nothing playing: the rest clock. */
    data object Rest : AmbientUi
    /** Ambient should not take the screen (disabled, or restClock off and nothing playing). */
    data object Dormant : AmbientUi
}

/** A media_player state that means "has something to show". */
private val ACTIVE_STATES = setOf("playing", "paused")

/** The speaker-carrying-TV placeholder titles the screensaver already skips. */
private val PLACEHOLDER_TITLES = setOf("TV", "TV Audio")

private fun EntityState.hasRealTitle(): Boolean =
    attrString("media_title")?.let { it.isNotBlank() && it !in PLACEHOLDER_TITLES } == true

/**
 * Which configured player to show, independent of the state machine so it can
 * be tested on its own: the first [mediaPlayers] entry that is `playing`;
 * otherwise the most recently `paused` one, but only while it has been paused
 * for less than [pausedGraceMs]; otherwise none. A player with no real title
 * (a speaker merely carrying TV audio) is never chosen.
 */
fun pickAmbientPlayer(
    entities: EntityMap,
    mediaPlayers: List<String>,
    now: Long,
    pausedGraceMs: Long = 4_000L,
): EntityState? {
    val pool = mediaPlayers.mapNotNull { entities[it] }.filter { it.state in ACTIVE_STATES && it.hasRealTitle() }
    pool.firstOrNull { it.state == "playing" }?.let { return it }
    // Paused: the one that paused most recently, and only within the grace.
    return pool.filter { it.state == "paused" }
        .maxByOrNull { it.lastChangedMs ?: Long.MIN_VALUE }
        ?.takeIf { e ->
            val since = e.lastChangedMs
            since == null || now - since <= pausedGraceMs
        }
}

/**
 * The idle-screen state machine. [tick] is called with a fresh entity snapshot
 * on each sample while the ambient host is up; it returns what to draw and
 * remembers just enough between calls to hold the screen through the gap
 * between songs.
 *
 * Clock-injected and free of any Android type, so it unit-tests without a
 * device. The host (idle timing, backlight, key routing) lives in MainActivity;
 * this only decides now-playing vs rest clock vs stand-aside.
 */
class AmbientMachine(
    private val mediaPlayers: List<String>,
    private val restClock: Boolean,
    /** Hold now-playing this long after playback stops: the gap between songs. */
    private val gapHoldMs: Long = 4_000L,
    private val pausedGraceMs: Long = 4_000L,
) {
    private var lastPlayingPlayer: String? = null
    private var lastPlayingAt = 0L

    fun tick(entities: EntityMap, now: Long): AmbientUi {
        if (mediaPlayers.isEmpty()) return idle()

        val active = pickAmbientPlayer(entities, mediaPlayers, now, pausedGraceMs)
        if (active != null) {
            if (active.state == "playing") {
                lastPlayingPlayer = active.entityId
                lastPlayingAt = now
            }
            return AmbientUi.NowPlaying(active.entityId, paused = active.state != "playing")
        }

        // Nothing selectable right now: hold the gap from the last time something
        // actually played, so a track change doesn't flash the clock.
        val held = lastPlayingPlayer
        if (held != null && now - lastPlayingAt <= gapHoldMs) {
            return AmbientUi.NowPlaying(held, paused = true)
        }
        lastPlayingPlayer = null
        return idle()
    }

    private fun idle(): AmbientUi = if (restClock) AmbientUi.Rest else AmbientUi.Dormant
}
