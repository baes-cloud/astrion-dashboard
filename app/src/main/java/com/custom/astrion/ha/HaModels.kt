package com.custom.astrion.ha

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull

/**
 * A single Home Assistant entity's live state.
 *
 * Mirrors the object HA returns from `get_states` and inside `state_changed`
 * events. We keep attributes as a raw JsonObject so individual cards can pull
 * out whatever domain-specific fields they need (brightness, hvac_mode,
 * current_temperature, media_title, etc.) without us modelling every domain.
 */
data class EntityState(
    val entityId: String,
    val state: String,
    val attributes: JsonObject,
    /** Epoch millis, as HA sends them (it sends seconds); null if unknown. */
    val lastChangedMs: Long? = null,
    val lastUpdatedMs: Long? = null,
) {
    val domain: String get() = entityId.substringBefore('.')

    /** Convenience: friendly_name attribute, falling back to the entity id. */
    val friendlyName: String
        get() = (attributes["friendly_name"] as? kotlinx.serialization.json.JsonPrimitive)
            ?.content ?: entityId

    fun attr(key: String): JsonElement? = attributes[key]

    /** Attribute as String, or null if absent/not a primitive. */
    fun attrString(key: String): String? =
        (attributes[key] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull

    /** Attribute as Double, or null. Handles ints and numeric strings too. */
    fun attrDouble(key: String): Double? =
        (attributes[key] as? kotlinx.serialization.json.JsonPrimitive)?.let {
            it.doubleOrNull ?: it.contentOrNull?.toDoubleOrNull()
        }

    /** Attribute as Int, or null. */
    fun attrInt(key: String): Int? = attrDouble(key)?.toInt()

    /** Attribute as a list of strings (e.g. media_player source lists, hvac_modes). */
    fun attrStringList(key: String): List<String> =
        (attributes[key] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull }
            ?: emptyList()

    val isOn: Boolean get() = state == "on"
    val isUnavailable: Boolean get() = state == "unavailable" || state == "unknown"
}

/** Snapshot of the whole entity registry, keyed by entity_id. */
typealias EntityMap = Map<String, EntityState>

/**
 * Connection lifecycle as surfaced to the UI. Kept intentionally small.
 */
enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    AUTHENTICATING,
    CONNECTED,
    AUTH_FAILED,
    ERROR,
}

/**
 * A service call request. Matches HA's `call_service` websocket command shape:
 * { type: call_service, domain, service, service_data, target: { entity_id } }
 */
data class ServiceCall(
    val domain: String,
    val service: String,
    val entityId: String? = null,
    val data: Map<String, JsonElement> = emptyMap(),
    /** What to call this in a toast ("Jellyfin", "Long lights"); null = the entity's name. */
    val label: String? = null,
) {
    /**
     * Safe to send late, after a Wi-Fi blip: it sets a state rather than
     * stepping or flipping one. Ten queued volume-ups or D-pad presses, or a
     * toggle that someone already undid by hand, would do the wrong thing when
     * they all land at once on reconnect. Unlocking is never sent late either.
     */
    val replaysSafely: Boolean
        get() = domain !in NEVER_REPLAY_DOMAINS && service in REPLAY_SERVICES

    companion object {
        private val NEVER_REPLAY_DOMAINS = setOf("script", "remote", "androidtv", "button", "input_button")
        private val REPLAY_SERVICES = setOf(
            "turn_on", "turn_off", "lock", "select_source", "select_option",
            "set_temperature", "set_hvac_mode", "set_fan_mode", "set_value",
            "open_cover", "close_cover", "set_cover_position", "volume_set",
        )

        /**
         * Build a ServiceCall from plain values (String / Number / Boolean),
         * wrapping them as JSON primitives. Keeps card code readable:
         *   ServiceCall.of("light","turn_on","light.kitchen", "brightness_pct" to 60)
         */
        fun of(
            domain: String,
            service: String,
            entityId: String? = null,
            vararg data: Pair<String, Any?>,
        ): ServiceCall {
            val mapped: Map<String, JsonElement> = data.mapNotNull { (k, v) ->
                val el: JsonElement? = when (v) {
                    null -> null
                    is JsonElement -> v
                    is Boolean -> kotlinx.serialization.json.JsonPrimitive(v)
                    is Number -> kotlinx.serialization.json.JsonPrimitive(v)
                    is String -> kotlinx.serialization.json.JsonPrimitive(v)
                    else -> kotlinx.serialization.json.JsonPrimitive(v.toString())
                }
                el?.let { k to it }
            }.toMap()
            return ServiceCall(domain, service, entityId, mapped)
        }

        /**
         * From config's `"service": "light.turn_on"` plus `entity_id` and a
         * `data` map, the shape every button, hotkey and alert action uses.
         */
        fun fromConfig(service: String, entityId: String?, data: Map<String, Any?> = emptyMap()): ServiceCall =
            of(
                service.substringBefore('.'),
                service.substringAfter('.'),
                entityId,
                *data.entries.map { it.key to it.value }.toTypedArray(),
            )
    }
}
