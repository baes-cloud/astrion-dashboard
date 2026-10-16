package com.custom.astrion.cards.impl

import com.custom.astrion.ui.AstrionTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.tap

/**
 * Source picker for a media_player: a compact row showing the current source,
 * tapping it opens a dropdown of the entity's live `source_list`; picking one
 * fires `media_player.select_source` immediately.
 *
 * Handles players whose source_list vanishes while off (e.g. Android TV) by
 * showing a hint instead of an empty menu.
 *
 * Config shape:
 *   { "type": "source_select", "options": {
 *       "entity_id": "media_player.living_room_tv",
 *       "name": "TV source"
 *   } }
 */
class SourceSelectCard : CardRenderer {
    override val type = "source_select"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val sources = e?.attrStringList("source_list") ?: emptyList()
        val current = e?.attrString("source")

        var expanded by remember { mutableStateOf(false) }

        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(AstrionTheme.colors.surfaceVariant)
                    .tap(enabled = sources.isNotEmpty()) { expanded = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, color = AstrionTheme.colors.onSurfaceMuted, fontSize = AstrionTheme.size.label)
                    Text(
                        current ?: if (sources.isEmpty()) "No sources (device off?)" else "Select source…",
                        color = AstrionTheme.colors.onSurface,
                        fontSize = AstrionTheme.size.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = if (sources.isEmpty()) AstrionTheme.colors.textMuted else AstrionTheme.colors.textOnControl,
                )
            }

            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier
                    .background(AstrionTheme.colors.surfaceVariant)
                    .widthIn(max = 400.dp),
            ) {
                sources.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                s,
                                color = if (s == current) AstrionTheme.colors.accent else AstrionTheme.colors.onSurface,
                                fontSize = AstrionTheme.size.body,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            ctx.client.callService(
                                ServiceCall.of("media_player", "select_source", entityId, "source" to s)
                            )
                        },
                    )
                }
            }
        }
    }
}
