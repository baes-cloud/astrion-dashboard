package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.tap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

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
 *
 * A row can instead come from Music Assistant's library (e.g. a random pick
 * of albums, like MA's own "Random albums" shelf). A fresh set is fetched each
 * time the card is shown, and a tap plays it on the MA player:
 *   { "title": "Random albums", "source": "music_assistant",
 *     "config_entry_id": "01KVF3KES1KSZ0JYSXJ405AKVA",
 *     "player": "media_player.club_2",
 *     "media_type": "album", "order_by": "random", "limit": 12 }
 *
 * Inside a fixed-height parent (swipe_stack `height`) the shelves scroll
 * themselves; otherwise they take their full height and the page scrolls.
 */
class MediaShelvesCard : CardRenderer {
    override val type = "media_shelves"

    private data class Item(
        val title: String,
        val contentId: String,
        val contentType: String,
        val thumb: String?,
        /** Music Assistant player to play on; null = the card's media_player. */
        val maPlayer: String? = null,
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
                if (spec["source"] == "music_assistant") {
                    val items = musicAssistantItems(ctx, spec, limit)
                    return@mapNotNull if (items.isEmpty()) null else Shelf(title, items)
                }
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

        // Scroll inside a fixed-height parent (swipe_stack "height"); with no
        // fixed height the page itself scrolls, and nesting would crash.
        BoxWithConstraints {
            Column(
                modifier = if (constraints.hasBoundedHeight) Modifier.verticalScroll(rememberScrollState()) else Modifier,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when {
                    shelves == null -> ShelfLabel("Loading…")
                    shelves!!.isEmpty() -> ShelfLabel("Nothing to show")
                    else -> shelves!!.forEach { shelf ->
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ShelfLabel(shelf.title)
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                items(shelf.items) { item ->
                                    Tile(ctx, item) {
                                        if (item.maPlayer != null) {
                                            ctx.client.callService(
                                                ServiceCall.of(
                                                    "music_assistant", "play_media", item.maPlayer,
                                                    "media_id" to item.contentId,
                                                    "media_type" to item.contentType,
                                                )
                                            )
                                        } else {
                                            ctx.client.playMedia(entityId, item.contentId, item.contentType)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * Shelf heading: a small, darker uppercase label followed by a hairline
     * running the rest of the width, so shelves read as sections without the
     * heading competing with the art.
     */
    @Composable
    private fun ShelfLabel(text: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text.uppercase(),
                color = Color(0xFF7F9AA2),
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.2.sp,
            )
            Spacer(Modifier.width(10.dp))
            Box(
                Modifier
                    .weight(1f)
                    .height(1.dp)
                    .background(AstrionTheme.controlSunken),
            )
        }
    }

    @Composable
    private fun Tile(ctx: CardContext, item: Item, onClick: () -> Unit) {
        var art by remember(item.thumb) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(item.thumb) {
            art = item.thumb?.let { ctx.client.fetchBitmap(it) }
        }
        // Art with a caption underneath: covers alone don't say which playlist
        // is which.
        Column(
            modifier = Modifier.width(TILE).tap(onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val mod = Modifier.size(TILE).clip(RoundedCornerShape(8.dp))
            if (art != null) {
                Image(art!!, contentDescription = null, modifier = mod, contentScale = ContentScale.Crop)
            } else {
                // Much of this art lives on internet CDNs (Spotify/YouTube/
                // SoundCloud). If the panel can't reach them the tile would
                // otherwise be an empty box that looks broken, so show a glyph.
                Box(mod.background(AstrionTheme.raised), contentAlignment = Alignment.Center) {
                    Icon(
                        Icons.Filled.MusicNote,
                        contentDescription = null,
                        tint = Color(0xFF5C7783),
                        modifier = Modifier.size(26.dp),
                    )
                }
            }
            // Up to two lines in a muted tone so the art leads. minLines keeps
            // every caption two lines tall, so tiles in a row stay aligned.
            Text(
                item.title,
                color = AstrionTheme.textSecondary,
                fontSize = 11.sp,
                lineHeight = 13.sp,
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    /** One Music Assistant row via `music_assistant.get_library`. */
    private suspend fun musicAssistantItems(
        ctx: CardContext,
        spec: Map<String, Any?>,
        defaultLimit: Int,
    ): List<Item> {
        val entry = spec["config_entry_id"] as? String ?: return emptyList()
        val player = spec["player"] as? String ?: return emptyList()
        val mediaType = spec["media_type"] as? String ?: "album"
        val limit = (spec["limit"] as? Number)?.toInt() ?: defaultLimit
        val data = buildJsonObject {
            put("config_entry_id", entry)
            put("media_type", mediaType)
            put("order_by", spec["order_by"] as? String ?: "random")
            put("limit", limit)
            (spec["favorite"] as? Boolean)?.let { put("favorite", it) }
        }
        val response = ctx.client.callServiceForResponse("music_assistant", "get_library", data)
            ?: return emptyList()
        return (response["items"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val uri = str("uri") ?: return@mapNotNull null
            Item(
                title = str("name") ?: uri,
                contentId = uri,
                contentType = str("media_type") ?: mediaType,
                // MA's imageproxy serves full size at size=0; tiles need ~256px.
                thumb = str("image")?.replace("size=0", "size=256"),
                maPlayer = player,
            )
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
