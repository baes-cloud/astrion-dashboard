package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.PendingSpinner
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.SectionLabel
import com.custom.astrion.ui.Touch
import com.custom.astrion.ui.liveOrDim
import com.custom.astrion.ui.rememberAction
import com.custom.astrion.ui.rememberSampledBitmap

/**
 * Grid of action buttons, each firing a HA service call; a button can carry
 * a PNG icon from a file path, a label, or both (playlist buttons, TV apps,
 * Group / Ungroup).
 *
 * Tiles use a MINIMUM height (`tile_height`), not a fixed one, so larger
 * system text grows the tile instead of clipping the label. Each tile shows
 * a spinner while its call is in flight and a red outline if refused.
 *
 * Config: { "type": "button_grid", "options": {
 *     "title": "Playlists", "columns": 3, "tile_height": 56, "icon_size": 26,
 *     "spacing": 8,
 *     "buttons": [ { "name": "Disco", "icon": "/sdcard/astrion/icons/disco.png",
 *                    "service": "script.play_disco", "entity_id": …, "data": {…} } ] } }
 */
class ButtonGridCard : CardRenderer {
    override val type = "button_grid"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val columns = config.int("columns", 3).coerceAtLeast(1)
        val buttons = (config.options["buttons"] as? List<Map<String, Any?>>) ?: emptyList()
        val title = config.string("title")
        val tileHeight = config.int("tile_height", 0).takeIf { it > 0 }?.dp
        val iconSize = config.int("icon_size", 0).takeIf { it > 0 }?.dp
        val spacing = config.int("spacing", 10).coerceIn(2, 24).dp

        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            if (title != null) SectionLabel(title)
            buttons.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    row.forEach { b ->
                        GridButton(ctx, b, Modifier.weight(1f), tileHeight, iconSize)
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Composable
    private fun GridButton(
        ctx: CardContext,
        b: Map<String, Any?>,
        modifier: Modifier,
        tileHeight: Dp?,
        iconSize: Dp?,
    ) {
        val name = b["name"] as? String
        val iconPath = b["icon"] as? String
        // Off-thread, downsampled, cached.
        val bitmap by rememberSampledBitmap(iconPath, targetPx = 96)
        val hasIcon = bitmap != null
        val height = tileHeight ?: if (hasIcon) 68.dp else Touch.min
        val glyph = iconSize ?: 32.dp
        val action = rememberAction(ctx)
        val live = ctx.connected
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val haptics = LocalHapticFeedback.current
        val shape = RoundedCornerShape(Radius.control)

        Box(
            modifier = modifier
                .heightIn(min = height.coerceAtLeast(Touch.min))
                .liveOrDim(live)
                .clip(shape)
                .background(if (pressed) AstrionTheme.controlPressed else AstrionTheme.raised)
                .then(if (action.failed) Modifier.border(2.dp, AstrionTheme.danger, shape) else Modifier)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    enabled = live,
                    role = Role.Button,
                ) {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val service = b["service"] as? String ?: return@clickable
                    val data = (b["data"] as? Map<String, Any?>).orEmpty()
                    action.run(
                        ServiceCall.of(
                            service.substringBefore('.'), service.substringAfter('.'),
                            b["entity_id"] as? String,
                            *data.entries.map { it.key to it.value }.toTypedArray(),
                        )
                    )
                }
                .padding(6.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                val bmp = bitmap
                if (action.busy) {
                    PendingSpinner(size = glyph.coerceAtMost(24.dp), color = AstrionTheme.accent)
                } else if (bmp != null) {
                    Image(bitmap = bmp, contentDescription = null, modifier = Modifier.size(glyph))
                }
                if (!name.isNullOrBlank()) {
                    if (hasIcon || action.busy) Spacer(Modifier.height(3.dp))
                    Text(
                        name,
                        style = if (hasIcon) AstrionType.label else AstrionType.bodyStrong,
                        color = AstrionTheme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
