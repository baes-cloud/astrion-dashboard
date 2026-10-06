package com.custom.astrion.cards.impl

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.LocalTextStyle
import com.custom.astrion.ui.tightTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
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
import com.custom.astrion.ui.rememberSampledBitmap
import com.custom.astrion.ui.tap
import java.io.File

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
        val labelLines = config.int("label_lines", 1).coerceIn(1, 2)
        val spacing = config.int("spacing", 10).coerceIn(2, 24).dp

        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            if (title != null) {
                Text(title, color = Color(0xFFAEBFBB), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            }
            buttons.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    row.forEach { b ->
                        GridButton(b, Modifier.weight(1f), tileHeight, iconSize, labelLines) { fire(ctx, b) }
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fire(ctx: CardContext, b: Map<String, Any?>) {
        val service = b["service"] as? String ?: return
        val domain = service.substringBefore('.')
        val svc = service.substringAfter('.')
        val entityId = b["entity_id"] as? String
        val data = (b["data"] as? Map<String, Any?>).orEmpty()
        ctx.client.callService(
            ServiceCall.of(domain, svc, entityId, *data.entries.map { it.key to it.value }.toTypedArray())
        )
    }

    @Composable
    private fun GridButton(
        b: Map<String, Any?>,
        modifier: Modifier,
        tileHeight: Dp?,
        iconSize: Dp?,
        labelLines: Int,
        onClick: () -> Unit,
    ) {
        val name = b["name"] as? String
        val iconPath = b["icon"] as? String
        // Off-thread and downsampled — these are drawn into a 32dp box but
        // were being decoded at native resolution (up to 78 KB apiece) on the
        // composition thread, six at a time, when the Media page first drew.
        val bitmap by rememberSampledBitmap(iconPath, targetPx = 96)
        val hasIcon = bitmap != null
        val tileColor = (b["color"] as? String)?.let(::parseHexColor)
        val inkColor = (b["text_color"] as? String)?.let(::parseHexColor)
            ?: tileColor?.let { if (0.2126f * it.red + 0.7152f * it.green + 0.0722f * it.blue > 0.6f) Color(0xFF151B21) else Color.White }
            ?: Color(0xFFEEF2EF)

        val height = tileHeight ?: if (hasIcon) 68.dp else 48.dp
        val glyph = iconSize ?: 32.dp

        Column(
            modifier = modifier
                .height(height)
                .clip(RoundedCornerShape(14.dp))
                .background(tileColor ?: AstrionTheme.raised)
                .tap(onClick = onClick)
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
                    fontSize = if (hasIcon) (if (labelLines > 1) 11.sp else 12.sp) else if (name.length > 8) 13.sp else 15.sp,
                    fontWeight = if (tileColor != null && !hasIcon) FontWeight.Bold else FontWeight.Medium,
                    maxLines = labelLines,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }

    /** "#RRGGBB" (opaque) or "#AARRGGBB". */
    private fun parseHexColor(s: String): Color? {
        val h = s.removePrefix("#")
        val v = h.toLongOrNull(16) ?: return null
        return if (h.length <= 6) Color(0xFF000000L or v) else Color(v)
    }
}
