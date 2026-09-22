package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import com.custom.astrion.ui.AstrionTheme

/**
 * Sensor / monitor card — the native equivalent of the stock
 * `custom:aiks-switch-monitor-card` (StatisticsCardParser): a labelled list of
 * read-only entity values (temperature, humidity, power, etc.) with their unit.
 *
 * Reads each entity's live state plus its `unit_of_measurement` attribute; no
 * history graph (kept light for the MT6580), just the current value.
 *
 * Config shape:
 *   { "type": "monitor", "options": {
 *       "title": "Sensors",
 *       "entities": [
 *         { "entity_id": "sensor.lounge_temperature", "name": "Lounge" },
 *         { "entity_id": "sensor.power_now", "name": "Power" }
 *       ]
 *   } }
 */
class MonitorCard : CardRenderer {
    override val type = "monitor"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val title = config.string("title")
        val entities = (config.options["entities"] as? List<Map<String, Any?>>) ?: emptyList()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1B343D))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            if (!title.isNullOrBlank()) {
                Text(
                    title,
                    color = Color(0xFFE6F0F1),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            entities.forEach { row ->
                val entityId = row["entity_id"] as? String ?: return@forEach
                val e = ctx.entities[entityId]
                val name = row["name"] as? String ?: e?.friendlyName ?: entityId
                val unit = e?.attrString("unit_of_measurement").orEmpty()
                // Uses the shared helper rather than its own inline string
                // comparison, and says "Unavailable" instead of an anonymous
                // dash that reads the same as a sensor reporting nothing.
                val rowUnavailable = e == null || e.isUnavailable
                val value = if (rowUnavailable) "Unavailable" else e!!.state

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, color = Color(0xFF93AFB6), fontSize = 14.sp, modifier = Modifier.weight(1f))
                    Text(
                        if (rowUnavailable || unit.isBlank()) value else "$value $unit",
                        color = if (rowUnavailable) AstrionTheme.unavailable else AstrionTheme.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
