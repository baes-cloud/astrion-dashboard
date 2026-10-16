package com.custom.astrion.ui.ambient

import com.custom.astrion.ha.EntityMap
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.input.HardwareKey

/**
 * What a key does on the ambient now-playing screen: volume, mute and the
 * D-pad left/right act on the player being shown, and the screen stays up.
 * Null for any other key (or nothing playing), which then dismisses it as
 * usual. The HA100 has no play/pause or skip buttons of its own.
 */
fun ambientTransportCall(key: HardwareKey, entities: EntityMap, mediaPlayers: List<String>, now: Long): ServiceCall? {
    if (key !in TRANSPORT) return null
    val player = pickAmbientPlayer(entities, mediaPlayers, now) ?: return null
    val id = player.entityId
    return when (key) {
        HardwareKey.VOLUME_UP -> ServiceCall.of("media_player", "volume_up", id)
        HardwareKey.VOLUME_DOWN -> ServiceCall.of("media_player", "volume_down", id)
        HardwareKey.MUTE -> {
            val muted = player.attrString("is_volume_muted")?.toBooleanStrictOrNull() == true
            ServiceCall.of("media_player", "volume_mute", id, "is_volume_muted" to !muted)
        }
        HardwareKey.LEFT -> ServiceCall.of("media_player", "media_previous_track", id)
        HardwareKey.RIGHT -> ServiceCall.of("media_player", "media_next_track", id)
        else -> null
    }
}

private val TRANSPORT = setOf(
    HardwareKey.VOLUME_UP, HardwareKey.VOLUME_DOWN, HardwareKey.MUTE, HardwareKey.LEFT, HardwareKey.RIGHT,
)
