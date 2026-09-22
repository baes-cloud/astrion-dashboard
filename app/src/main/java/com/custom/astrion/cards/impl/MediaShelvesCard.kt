package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.tap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Swipeable shelves of media pulled straight from Home Assistant's
 * `media_player/browse_media`, where one tap plays the item on the target
 * player — no drilling in, no detail screen. Used for the Sonos/Spotify rows
 * (recently played, favourite songs, favourite playlists); a playlist just
 * starts rather than opening.
 *
 * Because it browses whatever node you point it at, any provider the player
 * exposes works: Sonos favourites folders, a Spotify library node, etc.
 *
 * Config shape:
 *   { "type": "media_shelves", "options": {
 *       "entity_id": "media_player.club",
 *       "limit": 10,
 *       "rows": [
 *         { "title": "Favourite Songs",
 *           "content_id": "object.item.audioItem.musicTrack",
 *           "content_type": "favorites_folder" }
 *       ]
 *   } }
 */
class MediaShelvesCard : CardRenderer {
    override val type = "media_shelves"

    private data class Item(
        val title: String,
        val contentId: String,
        val contentType: String,
        val thumb: String?,
    )

    private data class Shelf(val title: String, val items: List<Item>)

    private companion object {
        // Square album art, ~3.8 across the 480px panel (was 92dp).
        val TILE = 85.dp
    }

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val limit = config.int("limit", 10).coerceIn(1, 30)
        val rowSpecs = (config.options["rows"] as? List<Map<String, Any?>>) ?: emptyList()

        val shelves by produceState<List<Shelf>?>(initialValue = null, entityId, limit) {
            value = rowSpecs.mapNotNull { spec ->
                val title = spec["title"] as? String ?: return@mapNotNull null
                val cid = spec["content_id"] as? String ?: return@mapNotNull null
                val ctype = spec["content_type"] as? String ?: return@mapNotNull null
                val result = ctx.client.browseMedia(entityId, cid, ctype) ?: return@mapNotNull null
                val items = (result["children"] as? JsonArray)
                    ?.mapNotNull { parseItem(it as? JsonObject) }
                    ?.take(limit)
                    .orEmpty()
                if (items.isEmpty()) null else Shelf(title, items)
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            when {
                shelves == null -> ShelfLabel("Loading…")
                shelves!!.isEmpty() -> ShelfLabel("Nothing to show")
                else -> shelves!!.forEach { shelf ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ShelfLabel(shelf.title)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(shelf.items) { item ->
                                Tile(ctx, item) {
                                    ctx.client.playMedia(entityId, item.contentId, item.contentType)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ShelfLabel(text: String) {
        Text(
            text,
            color = Color(0xFF9FBAC0),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
    }

    @Composable
    private fun Tile(ctx: CardContext, item: Item, onClick: () -> Unit) {
        var art by remember(item.thumb) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(item.thumb) {
            art = item.thumb?.let { ctx.client.fetchBitmap(it) }
        }
        // Art only — no caption. The cover is the label, and dropping the text
        // keeps the shelf short. The title still rides on contentDescription
        // for accessibility.
        val mod = Modifier
            .size(TILE)
            .clip(RoundedCornerShape(8.dp))
            .tap(onClick = onClick)
        if (art != null) {
            Image(art!!, contentDescription = item.title, modifier = mod, contentScale = ContentScale.Crop)
        } else {
            // Much of this art lives on internet CDNs (Spotify/YouTube/
            // SoundCloud). If the panel can't reach them the tile would
            // otherwise be an empty box that looks broken, so show a glyph.
            Box(mod.background(AstrionTheme.raised), contentAlignment = Alignment.Center) {
                Icon(
                    Icons.Filled.MusicNote,
                    contentDescription = item.title,
                    tint = Color(0xFF5C7783),
                    modifier = Modifier.size(26.dp),
                )
            }
        }
    }

    private fun parseItem(o: JsonObject?): Item? {
        o ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.content
        // Only offer things that actually play; folders would need drilling in.
        val canPlay = (o["can_play"] as? JsonPrimitive)?.booleanOrNull ?: false
        if (!canPlay) return null
        val cid = str("media_content_id") ?: return null
        val ctype = str("media_content_type") ?: return null
        return Item(
            title = str("title") ?: cid,
            contentId = cid,
            contentType = ctype,
            thumb = str("thumbnail"),
        )
    }
}
