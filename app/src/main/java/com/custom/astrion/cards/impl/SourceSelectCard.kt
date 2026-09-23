package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.LocalOverlay
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.Touch
import com.custom.astrion.ui.UnavailableBadge
import com.custom.astrion.ui.liveOrDim
import com.custom.astrion.ui.tap

/**
 * Source picker for a media_player: a row showing the current source; tap
 * for a sheet listing the live `source_list` (a Material dropdown before —
 * its own popup window, which stole the hardware keys while open).
 *
 * Handles players whose source_list vanishes while off (e.g. Android TV)
 * with a hint instead of an empty list.
 *
 * Config: { "type": "source_select", "options": {
 *     "entity_id": "media_player.living_room_tv", "name": "TV source" } }
 */
class SourceSelectCard : CardRenderer {
    override val type = "source_select"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entity(entityId)
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val sources = e?.attrStringList("source_list") ?: emptyList()
        val current = e?.attrString("source")
        val unavailable = e == null || e.isUnavailable
        val live = sources.isNotEmpty() && ctx.connected
        val overlay = LocalOverlay.current

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Touch.min)
                .clip(RoundedCornerShape(Radius.control))
                .background(AstrionTheme.cardBg)
                .tap(enabled = live) {
                    overlay.show { SourceSheet(ctx, entityId, title = name, onClose = { overlay.dismiss() }) }
                }
                .padding(horizontal = Space.card, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = AstrionType.label, color = AstrionTheme.textSecondary)
                if (unavailable) {
                    UnavailableBadge()
                } else {
                    Text(
                        current ?: if (sources.isEmpty()) "No sources (device off?)" else "Select source…",
                        style = AstrionType.bodyStrong,
                        color = AstrionTheme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                Icons.Filled.ExpandMore, contentDescription = null,
                tint = AstrionTheme.textOnControl, modifier = Modifier.liveOrDim(live),
            )
        }
    }
}
