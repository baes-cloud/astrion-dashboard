package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.tap

/**
 * Lights grouped into titled zones. Each light renders as the full-width
 * bubble_light pill (dimmable ones show the slider + colour popup; lights
 * flagged "dimmable": false render as a plain on/off pill). Each zone title has
 * a toggle switch on the right that turns every light in that zone on/off.
 *
 * Config shape:
 *   { "type": "light_zones", "options": { "zones": [
 *       { "title": "Club", "lights": [
 *           { "entity_id": "light.downlights", "name": "Downlights" },
 *           { "entity_id": "light.bar", "name": "Bar", "dimmable": false }
 *       ] }
 *   ] } }
 */
class LightZonesCard : CardRenderer {
    override val type = "light_zones"

    // Reuse the exact bubble_light pill (incl. its long-press colour popup).
    private val bubble = BubbleLightCard()

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val zones = (config.options["zones"] as? List<Map<String, Any?>>) ?: emptyList()

        Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
            zones.forEach { zone ->
                val title = zone["title"] as? String
                val lights = (zone["lights"] as? List<Map<String, Any?>>) ?: emptyList()
                val ids = lights.mapNotNull { it["entity_id"] as? String }
                val anyOn = ids.any { ctx.entities[it]?.isOn == true }

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            title?.uppercase() ?: "",
                            color = Color(0xFF9FBAC0),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            letterSpacing = 1.sp,
                        )
                        // Zone master switch: turns every light in the zone on/off.
                        ZoneToggle(anyOn) {
                            val svc = if (anyOn) "turn_off" else "turn_on"
                            ids.forEach { ctx.client.callService(ServiceCall("light", svc, entityId = it)) }
                        }
                    }
                    lights.forEach { light ->
                        bubble.Render(CardConfig("bubble_light", light), ctx)
                    }
                }
            }
        }
    }

    /**
     * Zone master switch — turns a whole room's lights on or off.
     *
     * Was 44×26dp, which made the highest-consequence control on the page the
     * second-smallest thing on it. At 220dpi, 26dp is about 5.8mm against a
     * thumb contact patch of 9–11mm, on a screen used one-handed without
     * aiming. Now 52×32dp.
     */
    @Composable
    private fun ZoneToggle(on: Boolean, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 32.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(if (on) AstrionTheme.accent else AstrionTheme.controlBg)
                .tap(onClick = onClick),
        ) {
            Box(
                modifier = Modifier
                    .padding(start = if (on) 24.dp else 3.dp, top = 3.dp)
                    .size(26.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFF2F7F8)),
            )
        }
    }
}
