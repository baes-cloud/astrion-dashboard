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
 * Context handed to every card render. Gives the card read access to live
 * entity states and the ability to fire service calls back to HA.
 *
 * Built once per client (see Dashboard) so it never itself triggers
 * recomposition. Entity reads are per entity: `ctx.entities[id]` and
 * [entity] read [HaClient.entityState], so a composable recomposes only when
 * an entity it actually read changes. The Main page's floorplan has 18 radar
 * position sensors updating continuously as people move through the room;
 * when every read went through the whole-map State, each of those updates
 * recomposed every card that read any entity, several times a second, on a
 * 1GB MT6580.
 *
 * Only whole-map operations (iterating, `keys`, `size`…) subscribe to every
 * entity, so reach for those only when a card genuinely needs them.
 */
@Stable
class CardContext(
    private val entitiesState: State<EntityMap>,
    val client: HaClient,
    private val connectionState: State<ConnectionState>,
) {
    /**
     * Live entity map. `entities[id]` subscribes to that one entity; anything
     * that walks the whole map subscribes to all of them.
     */
    val entities: EntityMap = object : AbstractMap<String, EntityState>() {
        override fun get(key: String): EntityState? = client.entityState(key).value
        override fun containsKey(key: String): Boolean = get(key) != null
        override val entries: Set<Map.Entry<String, EntityState>>
            get() = entitiesState.value.entries
    }

    /** Single-entity read; same as `entities[id]`. */
    fun entity(id: String): EntityState? = client.entityState(id).value

    /**
     * False when the websocket is down. A dead socket is just "everything is
     * unavailable at once", so cards gate their taps on this with the same
     * treatment they use for a single unavailable entity, rather than looking
     * fully live and firing calls into the void.
     */
    val connected: Boolean get() = connectionState.value == ConnectionState.CONNECTED
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
