package com.custom.astrion.cards.impl

import com.custom.astrion.ui.AstrionTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ui.tap

/**
 * Example custom light card.
 *
 * Config shape (in your dashboard config):
 *   CardConfig("light", mapOf(
 *       "entity_id" to "light.kitchen",
 *       "name" to "Kitchen",
 *   ))
 *
 * This is a full native Compose card — you control the whole look. Tap toggles;
 * the tile tints itself based on live on/off state pulled from the entity map.
 * Rewrite this however you like; nothing here is dictated by Sanytron.
 */
class LightCard : CardRenderer {
    override val type = "light"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val entity = ctx.entities[entityId]
        val isOn = entity?.state == "on"
        val label = config.string("name") ?: entity?.friendlyName ?: entityId

        val bg = if (isOn) AstrionTheme.colors.lightOn else AstrionTheme.colors.surfaceVariant
        val fg = if (isOn) AstrionTheme.colors.onLightOn else AstrionTheme.colors.textSoft

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(84.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(bg)
                .tap { ctx.client.toggle(entityId) }
                .padding(horizontal = 18.dp),
            contentAlignment = Alignment.CenterStart,
        ) {
            Column {
                Text(label, color = fg, fontSize = AstrionTheme.size.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    if (isOn) "On" else "Off",
                    color = fg.copy(alpha = 0.7f),
                    fontSize = AstrionTheme.size.small,
                )
            }
        }
    }
}
