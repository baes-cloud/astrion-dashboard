package com.custom.astrion.cards.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionSwitch
import com.custom.astrion.ui.SectionLabel
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.rememberAction
import com.custom.astrion.ui.rememberOptimistic

/**
 * Lights grouped into titled zones. Each light is a full-width bubble_light
 * pill; each zone title carries a master switch (52×30 visual in a 48dp
 * target — it turns a whole room off, so it is the biggest control in the
 * header row) that turns every light in the zone on or off, optimistically.
 *
 * Config: { "type": "light_zones", "options": { "zones": [
 *     { "title": "Club", "lights": [
 *         { "entity_id": "light.downlights", "name": "Downlights" },
 *         { "entity_id": "light.bar", "name": "Bar", "dimmable": false } ] } ] } }
 */
class LightZonesCard : CardRenderer {
    override val type = "light_zones"

    // Reuse the exact bubble_light pill (incl. its long-press colour sheet).
    private val bubble = BubbleLightCard()

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val zones = (config.options["zones"] as? List<Map<String, Any?>>) ?: emptyList()

        Column(verticalArrangement = Arrangement.spacedBy(Space.l)) {
            zones.forEach { zone ->
                val title = zone["title"] as? String
                val lights = (zone["lights"] as? List<Map<String, Any?>>) ?: emptyList()
                Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                    ZoneHeader(ctx, title.orEmpty(), lights.mapNotNull { it["entity_id"] as? String })
                    lights.forEach { light ->
                        bubble.Render(CardConfig("bubble_light", light), ctx)
                    }
                }
            }
        }
    }

    @Composable
    private fun ZoneHeader(ctx: CardContext, title: String, ids: List<String>) {
        val actualAnyOn = ids.any { ctx.entity(it)?.isOn == true }
        val opt = rememberOptimistic(actualAnyOn)
        val anyOn = opt.show(actualAnyOn)
        val action = rememberAction(ctx)
        SectionLabel(title) {
            AstrionSwitch(
                on = anyOn,
                onClick = {
                    val want = !anyOn
                    opt.set(want)
                    val svc = if (want) "turn_on" else "turn_off"
                    action.run(
                        *ids.map { ServiceCall("light", svc, entityId = it) }.toTypedArray(),
                        onFail = { opt.clear() },
                    )
                },
                description = if (anyOn) "Turn all $title lights off" else "Turn all $title lights on",
                enabled = ctx.connected && ids.isNotEmpty(),
                pending = action.busy,
            )
        }
    }
}
