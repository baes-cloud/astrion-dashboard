package com.custom.astrion.cards.impl

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.parseHexColor
import com.custom.astrion.ui.rememberSampledBitmap
import com.custom.astrion.ui.tightTextStyle

/**
 * Generic grid of action buttons, each firing a HA service call. Buttons can
 * carry a PNG icon loaded from a file path (e.g. /sdcard/astrion/icons/mos.png),
 * a text label, or both. Used for the TV-app row, Group/Ungroup, and the
 * playlist buttons.
 *
 * `label_lines: 2` lets a long name wrap onto a second line.
 *
 * `tile_height`, `icon_size` and `spacing` (all dp) shrink the buttons where a
 * grid has to share a page with taller cards — the playlist grid sits under the
 * media stack and would otherwise be cut in half by the bottom of the screen.
 *
 * Config shape:
 *   { "type": "button_grid", "options": {
 *       "columns": 3,
 *       "tile_height": 56,
 *       "icon_size": 26,
 *       "buttons": [
 *         { "name": "Group",   "service": "script.group" },
 *         { "name": "Disco",   "icon": "/sdcard/astrion/icons/disco.png",
 *           "service": "script.playlist_disco" },
 *         { "name": "Netflix", "service": "media_player.play_media",
 *           "entity_id": "media_player.the_club_tvv",
 *           "data": { "media_content_type": "app", "media_content_id": "com.netflix.ninja" } }
 *       ]
 *
 * Optional per button: `color` ("#RRGGBB", tile background — e.g. a brand
 * colour for text-only app tiles) and `text_color` (defaults to white or dark
 * ink, whichever reads on `color`).
 *   } }
 */
/** The darker band under each tile that the face sinks into when pressed. */
private val LIP = 5.dp

class ButtonGridCard : CardRenderer {
    override val type = "button_grid"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val columns = config.int("columns", 3).coerceAtLeast(1)
        val buttons = (config.options["buttons"] as? List<Map<String, Any?>>) ?: emptyList()
        val title = config.string("title")
        val configuredTileHeight = config.int("tile_height", 0).takeIf { it > 0 }?.dp
        val iconSize = config.int("icon_size", 0).takeIf { it > 0 }?.dp
        val labelLines = config.int("label_lines", 1).coerceIn(1, 2)
        val spacing = config.int("spacing", 10).coerceIn(2, 24).dp
        // `"style": "flat"`: plain tiles on the card surface, no raised lip.
        val flat = config.string("style") == "flat"
        val lip = if (flat) 0.dp else LIP

        if (buttons.isEmpty()) return

        val hasAnyIcon = buttons.any { it["icon"] != null }
        val effectiveTileHeight = configuredTileHeight ?: if (hasAnyIcon) 68.dp else 48.dp

        val rowCount = (buttons.size + columns - 1) / columns
        // Each tile is its face plus the lip under it; leaving the lip out
        // here clipped every tile's bottom edge and corners flat.
        val totalGridHeight = ((effectiveTileHeight + lip) * rowCount) + (spacing * (rowCount - 1).coerceAtLeast(0))

        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            if (title != null) {
                Text(
                    title,
                    color = AstrionTheme.colors.onSurfaceVariant,
                    fontSize = AstrionTheme.size.label,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                )
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalGridHeight),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalArrangement = Arrangement.spacedBy(spacing),
                contentPadding = PaddingValues(0.dp),
                userScrollEnabled = false, // Nested scrolling safety with parent verticalScroll
            ) {
                items(
                    count = buttons.size,
                    key = { index ->
                        val b = buttons[index]
                        val name = b["name"] as? String ?: ""
                        val entityId = b["entity_id"] as? String ?: ""
                        val service = b["service"] as? String ?: ""
                        "$name:$entityId:$service:$index"
                    },
                ) { index ->
                    val b = buttons[index]
                    GridButton(
                        b = b,
                        modifier = Modifier.fillMaxWidth(),
                        tileHeight = effectiveTileHeight,
                        iconSize = iconSize,
                        labelLines = labelLines,
                        flat = flat,
                    ) { fire(ctx, b) }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fire(ctx: CardContext, b: Map<String, Any?>) {
        val service = b["service"] as? String ?: return
        val data = (b["data"] as? Map<String, Any?>).orEmpty()
        ctx.client.callService(ServiceCall.fromConfig(service, b["entity_id"] as? String, data))
    }

    @Composable
    private fun GridButton(
        b: Map<String, Any?>,
        modifier: Modifier,
        tileHeight: Dp?,
        iconSize: Dp?,
        labelLines: Int,
        flat: Boolean = false,
        onClick: () -> Unit,
    ) {
        val name = b["name"] as? String
        val iconPath = b["icon"] as? String
        // Off-thread and downsampled — these are drawn into a 32dp box but
        // were being decoded at native resolution (up to 78 KB apiece) on the
        // composition thread, six at a time, when the Media page first drew.
        val bitmap by rememberSampledBitmap(iconPath, targetPx = 96)
        val hasIcon = bitmap != null
        val tileColor = parseHexColor(b["color"] as? String)
        val inkColor = parseHexColor(b["text_color"] as? String)
            ?: tileColor?.let { if (0.2126f * it.red + 0.7152f * it.green + 0.0722f * it.blue > 0.6f) AstrionTheme.colors.surfacePrimary else Color.White }
            ?: AstrionTheme.colors.onSurface

        val height = tileHeight ?: if (hasIcon) 68.dp else 48.dp
        val glyph = iconSize ?: 32.dp

        val lip = if (flat) 0.dp else LIP
        val baseColor = tileColor?.let {
            Color(it.red * 0.5f, it.green * 0.5f, it.blue * 0.5f, 0.62f)
        } ?: AstrionTheme.controlSunken
        val faceColor = tileColor ?: if (flat) AstrionTheme.cardBgAlt else AstrionTheme.raised

        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val haptics = LocalHapticFeedback.current
        val sink by animateDpAsState(if (pressed) lip else 0.dp, label = "button_sink")
        // Flat tiles answer a press by lifting to the raised surface instead of sinking.
        val face = if (flat && pressed && tileColor == null) AstrionTheme.raised else faceColor

        Box(
            modifier = modifier
                .height(height + lip)
                .clip(RoundedCornerShape(14.dp))
                .background(if (flat) Color.Transparent else baseColor),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(height)
                    .align(Alignment.TopCenter)
                    .graphicsLayer { translationY = sink.toPx() }
                    .clip(RoundedCornerShape(14.dp))
                    .background(face)
                    .clickable(interactionSource = interaction, indication = null) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    }
                    .padding(6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                bitmap?.let { bmp ->
                    Image(bitmap = bmp, contentDescription = name, modifier = Modifier.size(glyph))
                    if (!name.isNullOrBlank()) Spacer(Modifier.height(if (labelLines > 1) 2.dp else 3.dp))
                }
                if (!name.isNullOrBlank()) {
                    // `label_lines: 2` wraps a long name ("Purple Disco") onto a
                    // second, tightly spaced line instead of cutting it off.
                    Text(
                        name,
                        style = if (labelLines > 1) tightTextStyle(13.sp) else LocalTextStyle.current,
                        color = inkColor,
                        fontSize = if (hasIcon) (if (labelLines > 1) AstrionTheme.size.label else AstrionTheme.size.label) else if (name.length > 8) AstrionTheme.size.small else AstrionTheme.size.bodyLarge,
                        fontWeight = if (tileColor != null && !hasIcon) FontWeight.Bold else FontWeight.Medium,
                        maxLines = labelLines,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}
