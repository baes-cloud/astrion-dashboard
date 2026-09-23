package com.custom.astrion.cards.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ui.AstrionCard
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.UnavailableBadge

/**
 * Sensor / monitor card: a titled list of read-only entity values with their
 * unit (temperature, humidity, power…). Current value only — no history
 * graph, kept light for the MT6580. An unavailable sensor says so, in lilac
 * with an icon, instead of an anonymous dash.
 *
 * Config: { "type": "monitor", "options": { "title": "Sensors",
 *     "entities": [ { "entity_id": "sensor.lounge_temperature", "name": "Lounge" } ] } }
 */
class MonitorCard : CardRenderer {
    override val type = "monitor"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val title = config.string("title")
        val entities = (config.options["entities"] as? List<Map<String, Any?>>) ?: emptyList()

        AstrionCard {
            Column(verticalArrangement = Arrangement.spacedBy(Space.gutter)) {
                if (!title.isNullOrBlank()) {
                    Text(title, style = AstrionType.title, color = AstrionTheme.textPrimary)
                }
                entities.forEach { row ->
                    val entityId = row["entity_id"] as? String ?: return@forEach
                    MonitorRow(ctx, entityId, row["name"] as? String)
                }
            }
        }
    }

    /** One value; reads only its own entity. */
    @Composable
    private fun MonitorRow(ctx: CardContext, entityId: String, configName: String?) {
        val e = ctx.entity(entityId)
        val name = configName ?: e?.friendlyName ?: entityId
        val unit = e?.attrString("unit_of_measurement").orEmpty()
        val unavailable = e == null || e.isUnavailable
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, style = AstrionType.body, color = AstrionTheme.textSecondary, modifier = Modifier.weight(1f))
            if (unavailable) {
                UnavailableBadge()
            } else {
                val v = e!!.state
                Text(
                    if (unit.isBlank()) v else "$v $unit",
                    style = AstrionType.bodyStrong,
                    color = AstrionTheme.textPrimary,
                )
            }
        }
    }
}
