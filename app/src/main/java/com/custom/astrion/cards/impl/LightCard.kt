package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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

        val bg = if (isOn) Color(0xFFFFC24B) else Color(0xFF1E3841)
        val fg = if (isOn) Color(0xFF241A00) else Color(0xFFB6C9CE)

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
                Text(label, color = fg, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    if (isOn) "On" else "Off",
                    color = fg.copy(alpha = 0.7f),
                    fontSize = 13.sp,
                )
            }
        }
    }
}
