package com.custom.astrion.cards

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import com.custom.astrion.ha.ConnectionState
import com.custom.astrion.ha.EntityMap
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient

/**
 * THE EXTENSIBILITY CORE.
 *
 * This is the thing the stock HaRemote app fundamentally does not have: an open
 * card registry. In HaRemote, the set of card types is a hardcoded static list
 * of 11 parsers (CardConfigParserFactory.<clinit>), with no plugin path — any
 * dashboard card whose `type` isn't one of their `custom:aiks-*` strings is
 * silently dropped, and the recognized ones are drawn in a fixed native style
 * you can't change.
 *
 * Here, adding a brand-new native card type is a two-line affair:
 *   1. Implement CardRenderer for your new `type` string.
 *   2. Register it in CardRegistry.
 * ...and it can render literally any Compose UI you want, with any layout.
 *
 * A card in *your* dashboard config is just:
 *   { type: "<your-type>", <arbitrary options...> }
 * and CardConfig.options carries those options straight through to your renderer.
 */

/** One card entry from your dashboard layout config. */
data class CardConfig(
    val type: String,
    /** Free-form per-card options. Your renderer decides how to read these. */
    val options: Map<String, Any?> = emptyMap(),
) {
    fun string(key: String): String? = options[key] as? String
    fun stringList(key: String): List<String> =
        (options[key] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
    fun bool(key: String, default: Boolean = false): Boolean =
        options[key] as? Boolean ?: default
    fun int(key: String, default: Int = 0): Int =
        (options[key] as? Number)?.toInt() ?: default
}

/**
 * Context handed to every card render: live entity state, the connection
 * state, and the client to fire service calls with.
 *
 * Read entities with [entity]. It reads that entity's own observable cell in
 * [HaClient], so the calling composable recomposes when THAT entity changes
 * and never because some other entity in the house did. (The previous
 * version read one whole-map State — on Main that meant six cards
 * recomposing up to ~8×/s whenever any sensor anywhere updated.)
 *
 * [entities] is still the whole map, for the rare reader that genuinely needs
 * it; reading it subscribes to every change, so don't use it in a card.
 */
@Stable
class CardContext(
    private val entitiesState: State<EntityMap>,
    val client: HaClient,
    private val connectionState: State<ConnectionState>,
) {
    /** Whole-map snapshot. Subscribes to EVERY entity change — avoid in cards. */
    val entities: EntityMap get() = entitiesState.value

    /** One entity, subscribing the caller to that entity only. */
    fun entity(id: String): EntityState? = client.cell(id).value

    /**
     * False when the websocket is down. A dead socket is just "everything is
     * unavailable at once", so cards gate (and visibly dim) their controls on
     * this exactly as they do for a single unavailable entity.
     */
    val connected: Boolean get() = connectionState.value == ConnectionState.CONNECTED

    /** True when [id] is missing, `unavailable` or `unknown`. */
    fun isUnavailable(id: String): Boolean = entity(id)?.isUnavailable ?: true
}

/**
 * Implement this to create a new native card type. `type` is the string you'll
 * use in your dashboard config; Render is plain Jetpack Compose, so you have
 * full control over layout, colours, animation, sizing — everything you can't
 * do inside HaRemote's fixed renderers.
 */
interface CardRenderer {
    val type: String

    @Composable
    fun Render(config: CardConfig, ctx: CardContext)
}

/**
 * Global registry. Register once at startup (see AstrionApp). Lookup by type
 * happens when the dashboard is built.
 */
object CardRegistry {
    private val renderers = LinkedHashMap<String, CardRenderer>()

    fun register(renderer: CardRenderer) {
        renderers[renderer.type] = renderer
    }

    fun register(vararg rs: CardRenderer) = rs.forEach { register(it) }

    fun get(type: String): CardRenderer? = renderers[type]

    fun known(): Set<String> = renderers.keys
}
