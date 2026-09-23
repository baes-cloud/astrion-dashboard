package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.CallOutcome
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.ImageCache
import com.custom.astrion.ui.LocalFeedback
import com.custom.astrion.ui.PendingSpinner
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.SectionLabel
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.StateLine
import com.custom.astrion.ui.liveOrDim
import com.custom.astrion.ui.rememberRemoteBitmap
import com.custom.astrion.ui.tap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.util.concurrent.ConcurrentHashMap

/**
 * Shelves of media from HA's `media_player/browse_media`; one tap plays the
 * item on the target player. Any provider the player exposes works.
 *
 * - Covers are fetched at tile size (117px, not 640px) and cached.
 * - The shelf listing is cached app-wide for 10 minutes, so swiping back to
 *   it doesn't re-browse three folders over the socket every time.
 * - Tapping a cover shows a spinner on THAT cover until HA accepts the play
 *   (and names what's starting in the feedback strip) — the player is on the
 *   other side of the swipe, so this is the only confirmation you'd see.
 *
 * Config: { "type": "media_shelves", "options": {
 *     "entity_id": "media_player.club", "limit": 10,
 *     "rows": [ { "title": "…", "content_id": "…", "content_type": "…" } ] } }
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
        val TILE = 85.dp
        const val TILE_PX = 128
        const val TTL_MS = 10 * 60 * 1000L
        /** key → (shelves, fetched-at). */
        val cache = ConcurrentHashMap<String, Pair<List<Shelf>, Long>>()
    }

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val limit = config.int("limit", 10).coerceIn(1, 30)
        val rowSpecs = (config.options["rows"] as? List<Map<String, Any?>>) ?: emptyList()
        val key = "$entityId|$limit|$rowSpecs"
        val live = ctx.connected
        val feedback = LocalFeedback.current
        val scope = rememberCoroutineScope()

        var shelves by remember(key) { mutableStateOf(cache[key]?.first) }
        LaunchedEffect(key) {
            val cached = cache[key]
            if (cached != null && System.currentTimeMillis() - cached.second < TTL_MS) return@LaunchedEffect
            val loaded = rowSpecs.mapNotNull { spec ->
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
            // An empty result while disconnected is "not yet", not "nothing".
            if (loaded.isNotEmpty()) cache[key] = loaded to System.currentTimeMillis()
            shelves = loaded
        }

        var pendingId by remember { mutableStateOf<String?>(null) }

        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            val list = shelves
            when {
                list == null -> {
                    StateLine("Loading…")
                    SkeletonRow()
                }
                list.isEmpty() -> StateLine(if (live) "Nothing to show" else "Not connected")
                else -> list.forEach { shelf ->
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        SectionLabel(shelf.title)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            items(shelf.items) { item ->
                                Tile(ctx, item, pending = pendingId == item.contentId, enabled = live) {
                                    pendingId = item.contentId
                                    scope.launch {
                                        val outcome = ctx.client.callAwait(
                                            ServiceCall.of(
                                                "media_player", "play_media", entityId,
                                                "media_content_id" to item.contentId,
                                                "media_content_type" to item.contentType,
                                            )
                                        )
                                        if (outcome == CallOutcome.OK || outcome == CallOutcome.NO_REPLY) {
                                            feedback.show("Playing ${item.title}")
                                        }
                                        delay(600)
                                        if (pendingId == item.contentId) pendingId = null
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun SkeletonRow() {
        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
            repeat(4) {
                Box(
                    Modifier
                        .size(TILE)
                        .clip(RoundedCornerShape(Radius.small))
                        .background(AstrionTheme.raised),
                )
            }
        }
    }

    @Composable
    private fun Tile(ctx: CardContext, item: Item, pending: Boolean, enabled: Boolean, onClick: () -> Unit) {
        val thumb = item.thumb
        val artKey = thumb?.let { ImageCache.remoteKey(ctx.client.authedUrl(it), TILE_PX) }
        val art by rememberRemoteBitmap(artKey) { thumb?.let { ctx.client.fetchBitmap(it, TILE_PX) } }
        val shape = RoundedCornerShape(Radius.small)
        Box(
            modifier = Modifier
                .size(TILE)
                .liveOrDim(enabled)
                .clip(shape)
                .background(AstrionTheme.raised)
                .then(if (pending) Modifier.border(2.dp, AstrionTheme.accent, shape) else Modifier)
                .tap(enabled = enabled, onClickLabel = "Play ${item.title}", onClick = onClick)
                .semantics { contentDescription = item.title },
            contentAlignment = Alignment.Center,
        ) {
            val bmp = art
            if (bmp != null) {
                Image(bmp, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                // CDN art the panel can't reach: a glyph, not a broken box.
                Icon(Icons.Filled.MusicNote, contentDescription = null, tint = AstrionTheme.textMuted, modifier = Modifier.size(26.dp))
            }
            if (pending) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(20.dp))
                        .background(AstrionTheme.pinnedTopBg),
                    contentAlignment = Alignment.Center,
                ) {
                    PendingSpinner(size = 22.dp, color = AstrionTheme.accent)
                }
            } else if (bmp == null) {
                Icon(
                    Icons.Filled.PlayArrow, contentDescription = null, tint = AstrionTheme.textMuted,
                    modifier = Modifier.align(Alignment.BottomEnd).size(18.dp),
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
        return Item(title = str("title") ?: cid, contentId = cid, contentType = ctype, thumb = str("thumbnail"))
    }
}
