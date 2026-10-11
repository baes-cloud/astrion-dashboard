package com.custom.astrion.ui.ambient

import com.custom.astrion.ha.EntityState
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmbientMachineTest {

    private fun player(
        id: String,
        state: String,
        title: String? = "Song",
        lastChangedMs: Long? = null,
    ): EntityState {
        val attrs = buildMap<String, kotlinx.serialization.json.JsonElement> {
            if (title != null) put("media_title", JsonPrimitive(title))
        }
        return EntityState(id, state, JsonObject(attrs), lastChangedMs = lastChangedMs)
    }

    private fun map(vararg e: EntityState) = e.associateBy { it.entityId }

    private val club = "media_player.club"
    private val bedroom = "media_player.bedroom"
    private val ids = listOf(club, bedroom)

    // ---- player selection ----------------------------------------------------

    @Test fun playingBeatsPaused() {
        val ents = map(player(club, "paused"), player(bedroom, "playing"))
        assertEquals(bedroom, pickAmbientPlayer(ents, ids, now = 0)?.entityId)
    }

    @Test fun firstConfiguredPlayingWins() {
        val ents = map(player(club, "playing"), player(bedroom, "playing"))
        assertEquals(club, pickAmbientPlayer(ents, ids, now = 0)?.entityId)
    }

    @Test fun pausedShownWithinGraceThenDropped() {
        val ents = map(player(club, "paused", lastChangedMs = 1_000))
        assertEquals(club, pickAmbientPlayer(ents, ids, now = 4_000, pausedGraceMs = 4_000)?.entityId)
        assertNull(pickAmbientPlayer(ents, ids, now = 6_000, pausedGraceMs = 4_000))
    }

    @Test fun placeholderTitleNotChosen() {
        val ents = map(player(club, "playing", title = "TV"))
        assertNull(pickAmbientPlayer(ents, ids, now = 0))
    }

    @Test fun unconfiguredPlayerIgnored() {
        val ents = map(player("media_player.other", "playing"))
        assertNull(pickAmbientPlayer(ents, ids, now = 0))
    }

    // ---- state machine -------------------------------------------------------

    @Test fun playingGivesNowPlaying() {
        val m = AmbientMachine(ids, restClock = true)
        assertEquals(AmbientUi.NowPlaying(club, paused = false), m.tick(map(player(club, "playing")), 0))
    }

    @Test fun stopHoldsFourSecondsThenRests() {
        val m = AmbientMachine(ids, restClock = true, gapHoldMs = 4_000)
        m.tick(map(player(club, "playing")), 0)
        // Playback stops (player idle, nothing selectable): held as the gap.
        val stopped = map(player(club, "idle", title = null))
        assertEquals(AmbientUi.NowPlaying(club, paused = true), m.tick(stopped, 2_000))
        // After the gap: rest clock.
        assertEquals(AmbientUi.Rest, m.tick(stopped, 5_000))
    }

    @Test fun restClockOffStandsAside() {
        val m = AmbientMachine(ids, restClock = false, gapHoldMs = 0)
        assertEquals(AmbientUi.Dormant, m.tick(map(player(club, "idle", title = null)), 10_000))
    }

    @Test fun restWhenNothingEverPlayed() {
        val m = AmbientMachine(ids, restClock = true)
        assertEquals(AmbientUi.Rest, m.tick(emptyMap(), 0))
    }

    @Test fun playbackStartsFromRest() {
        val m = AmbientMachine(ids, restClock = true)
        assertEquals(AmbientUi.Rest, m.tick(emptyMap(), 0))
        assertEquals(AmbientUi.NowPlaying(bedroom, paused = false), m.tick(map(player(bedroom, "playing")), 1_000))
    }

    @Test fun noConfiguredPlayersIsDormantWhenClockOff() {
        val m = AmbientMachine(emptyList(), restClock = false)
        assertEquals(AmbientUi.Dormant, m.tick(map(player(club, "playing")), 0))
    }
}
