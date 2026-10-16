package com.custom.astrion.ui.ambient

import com.custom.astrion.ha.EntityState
import com.custom.astrion.input.HardwareKey
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AmbientKeysTest {
    private val club = "media_player.club"
    private fun playing(muted: Boolean = false) = mapOf(
        club to EntityState(
            club, "playing",
            JsonObject(mapOf("media_title" to JsonPrimitive("Song"), "is_volume_muted" to JsonPrimitive(muted))),
        ),
    )

    private fun call(key: HardwareKey, entities: Map<String, EntityState> = playing()) =
        ambientTransportCall(key, entities, listOf(club), now = 0L)

    @Test
    fun transportKeysActOnTheShownPlayer() {
        assertEquals("volume_up", call(HardwareKey.VOLUME_UP)?.service)
        assertEquals("volume_down", call(HardwareKey.VOLUME_DOWN)?.service)
        assertEquals("media_previous_track", call(HardwareKey.LEFT)?.service)
        assertEquals("media_next_track", call(HardwareKey.RIGHT)?.service)
        assertEquals(club, call(HardwareKey.RIGHT)?.entityId)
    }

    @Test
    fun muteToggles() {
        assertEquals(JsonPrimitive(true), call(HardwareKey.MUTE)?.data?.get("is_volume_muted"))
        assertEquals(JsonPrimitive(false), call(HardwareKey.MUTE, playing(muted = true))?.data?.get("is_volume_muted"))
    }

    @Test
    fun otherKeysOrNothingPlayingDismissInstead() {
        assertNull(call(HardwareKey.CENTER))
        assertNull(call(HardwareKey.UP))
        assertNull(call(HardwareKey.VOLUME_UP, emptyMap()))
    }
}
