package com.custom.astrion.cards.impl

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
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
        val spacing = config.int("spacing", 10).coerceIn(2, 24).dp

        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            if (title != null) {
                Text(title, color = Color(0xFF9FBAC0), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
            }
            buttons.chunked(columns).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    row.forEach { b ->
                        GridButton(b, Modifier.weight(1f), tileHeight, iconSize) { fire(ctx, b) }
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
        onClick: () -> Unit,
    ) {
        val name = b["name"] as? String
        val iconPath = b["icon"] as? String
        // Off-thread and downsampled — these are drawn into a 32dp box but
        // were being decoded at native resolution (up to 78 KB apiece) on the
        // composition thread, six at a time, when the Media page first drew.
        val bitmap by rememberSampledBitmap(iconPath, targetPx = 96)
        val hasIcon = bitmap != null

        val height = tileHeight ?: if (hasIcon) 68.dp else 48.dp
        val glyph = iconSize ?: 32.dp

        Column(
            modifier = modifier
                .height(height)
                .clip(RoundedCornerShape(14.dp))
                .background(AstrionTheme.raised)
                .tap(onClick = onClick)
                .padding(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            bitmap?.let { bmp ->
                Image(bitmap = bmp, contentDescription = name, modifier = Modifier.size(glyph))
                if (!name.isNullOrBlank()) Spacer(Modifier.height(3.dp))
            }
            if (!name.isNullOrBlank()) {
                Text(
                    name,
                    color = Color(0xFFE6F0F1),
                    fontSize = if (hasIcon) 12.sp else 15.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
