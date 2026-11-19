package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ArtCache
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.BottomSheet
import com.custom.astrion.ui.Glyphs
import com.custom.astrion.ui.HitTarget
import com.custom.astrion.ui.components.BaeoSectionHeader
import com.custom.astrion.ui.rememberPageVisits
import com.custom.astrion.ui.tap
import com.custom.astrion.ui.tapAndHold
import com.custom.astrion.ui.tightTextStyle
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Swipeable media shelves (Bæo v3.0 Matte Tonal):
 *  - Stable keys & contentTypes on `LazyRow`.
 *  - Honest offline copy when disconnected with no cached shelves.
 *  - Tap feedback highlight + status toast, and long-press Quick Action sheet
 *    (`Play now`, `Play next`, `Add to queue`).
 *  - Optional docked 46dp Mini Now-Playing bar (`mini_player: true`) that jumps
 *    back to the Player tab on tap.
 */
class MediaShelvesCard : CardRenderer {
    override val type = "media_shelves"

    private data class Item(
        val title: String,
        val contentId: String,
        val contentType: String,
        val thumb: String?,
        val maPlayer: String? = null,
    )

    private data class Shelf(val title: String, val items: List<Item>)

    private companion object {
        val TILE = 84.dp
        val MIN_TILE = 60.dp
        val LABEL_LINE = 18.sp
        val CAPTION_LINE = 13.sp
        val LABEL_GAP = 5.dp
        val CAPTION_GAP = 4.dp
        val MINI_BAR_HEIGHT = 46.dp
        const val ART_PX = 128
        const val SHELF_TTL_MS = 15 * 60_000L
        val shelfCache = java.util.concurrent.ConcurrentHashMap<String, List<Shelf>>()
        val shelfFetchedAt = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val limit = config.int("limit", 10).coerceIn(1, 30)
        val rowSpecs = (config.options["rows"] as? List<Map<String, Any?>>) ?: emptyList()
        val showMiniPlayer = config.bool("mini_player", true)

        val connected = ctx.connected
        val cacheKey = entityId + "|" + rowSpecs.hashCode()
        val visits = rememberPageVisits()
        val shelves by produceState(initialValue = shelfCache[cacheKey], entityId, limit, connected, visits) {
            if (!connected) return@produceState
            val now = android.os.SystemClock.elapsedRealtime()
            val lastFetch = shelfFetchedAt[cacheKey] ?: 0L
            if (!value.isNullOrEmpty() && lastFetch > 0L && now - lastFetch < SHELF_TTL_MS) return@produceState
            val results = arrayOfNulls<Shelf>(rowSpecs.size)
            value?.forEach { cached -> rowSpecs.indexOfFirst { it["title"] == cached.title }.takeIf { it >= 0 }?.let { results[it] = cached } }
            coroutineScope {
                rowSpecs.forEachIndexed { i, spec ->
                    launch {
                        val shelf = loadShelf(ctx, entityId, spec, limit)
                        if (shelf != null) results[i] = shelf
                        val ordered = results.filterNotNull()
                        if (ordered.isNotEmpty()) shelfFetchedAt[cacheKey] = now
                        shelfCache[cacheKey] = ordered
                        value = ordered
                    }
                }
            }
            if (value == null) value = emptyList()
        }

        var tappedId by remember { mutableStateOf<String?>(null) }
        var actionItem by remember { mutableStateOf<Item?>(null) }
        LaunchedEffect(tappedId) {
            if (tappedId != null) {
                delay(900L)
                tappedId = null
            }
        }

        fun playItem(item: Item, enqueue: String = "replace") {
            tappedId = item.contentId
            if (item.maPlayer != null) {
                ctx.client.callService(
                    ServiceCall.of(
                        "music_assistant", "play_media", item.maPlayer,
                        "media_id" to item.contentId,
                        "media_type" to item.contentType,
                        "enqueue" to enqueue,
                    ),
                    confirm = true,
                )
            } else {
                ctx.client.callService(
                    ServiceCall.of(
                        "media_player", "play_media", entityId,
                        "media_content_id" to item.contentId,
                        "media_content_type" to item.contentType,
                        "enqueue" to enqueue,
                    ),
                    confirm = true,
                )
            }
        }

        val playerEntity = ctx.entities[entityId]
        val hasMini = showMiniPlayer && playerEntity != null &&
            !playerEntity.attrString("media_title").isNullOrBlank() &&
            playerEntity.state in listOf("playing", "paused")

        Column(Modifier.fillMaxSize()) {
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val topPad = config.int("top_padding", 0).dp
                val rowGap = config.int("row_spacing", 10).dp
                val rowCount = shelves?.size ?: 0
                val fitTile: Dp? = if (constraints.hasBoundedHeight && rowCount > 0) {
                    val chrome = with(LocalDensity.current) { LABEL_LINE.toDp() + CAPTION_LINE.toDp() * 2 } + LABEL_GAP + CAPTION_GAP
                    (maxHeight - topPad - rowGap * (rowCount - 1)) / rowCount - chrome
                } else null
                val fits = fitTile != null && fitTile >= MIN_TILE
                val tile = if (fits) minOf(TILE, fitTile!!) else TILE
                Column(
                    modifier = (if (constraints.hasBoundedHeight && !fits) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                        .fillMaxWidth()
                        .padding(top = topPad),
                    verticalArrangement = Arrangement.spacedBy(rowGap),
                ) {
                    when {
                        shelves == null && !connected -> StatusNote("Offline — reconnect to browse music")
                        shelves == null -> StatusNote("Loading shelves…")
                        shelves!!.isEmpty() -> StatusNote("Nothing to show right now")
                        else -> shelves!!.forEach { shelf ->
                            Column(verticalArrangement = Arrangement.spacedBy(LABEL_GAP)) {
                                BaeoSectionHeader(shelf.title)
                                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    items(
                                        items = shelf.items,
                                        key = { "${shelf.title}-${it.contentId}" },
                                        contentType = { "media_tile" },
                                    ) { item ->
                                        Tile(
                                            ctx = ctx,
                                            item = item,
                                            tile = tile,
                                            highlighted = tappedId == item.contentId,
                                            onClick = { playItem(item, "replace") },
                                            onLongClick = { actionItem = item },
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            if (hasMini) {
                Spacer(Modifier.height(6.dp))
                MiniNowPlayingBar(ctx, entityId)
            }
        }

        actionItem?.let { item ->
            BottomSheet(
                title = item.title,
                subtitle = "Choose playback action",
                onDismissRequest = { actionItem = null },
            ) {
                QuickActionBtn("Play now", primary = true) {
                    playItem(item, "replace")
                    actionItem = null
                }
                QuickActionBtn("Play next", primary = false) {
                    playItem(item, "next")
                    actionItem = null
                }
                QuickActionBtn("Add to queue", primary = false) {
                    playItem(item, "add")
                    actionItem = null
                }
            }
        }
    }

    @Composable
    private fun QuickActionBtn(label: String, primary: Boolean, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(if (primary) AstrionTheme.accentStrong else AstrionTheme.controlBg)
                .tap(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = if (primary) AstrionTheme.colors.onPrimary else AstrionTheme.textPrimary,
                fontSize = AstrionTheme.size.body,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    @Composable
    private fun MiniNowPlayingBar(ctx: CardContext, entityId: String) {
        val e = ctx.entities[entityId] ?: return
        val title = e.attrString("media_title")?.takeIf { it.isNotBlank() } ?: return
        val artist = e.attrString("media_artist") ?: e.attrString("app_name") ?: ""
        val playing = e.state == "playing"
        val artKey = e.artKey()
        val artPath = e.attrString("entity_picture")
        var art by remember(artKey) { mutableStateOf(artKey?.let { ArtCache.peek(it, 96) }) }
        LaunchedEffect(artKey) {
            if (art == null && artKey != null && artPath != null) {
                art = ArtCache.load(artKey, 96) { ctx.client.fetchBytes(artPath) }
            }
        }
        val switchTab = LocalTabSwitcher.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(MINI_BAR_HEIGHT)
                .clip(RoundedCornerShape(14.dp))
                .background(AstrionTheme.surfaceElevated)
                .tap { switchTab?.invoke(0) }
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(AstrionTheme.raised),
                contentAlignment = Alignment.Center,
            ) {
                if (art != null) {
                    Image(art!!, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Icon(Glyphs.note, contentDescription = null, tint = AstrionTheme.accent, modifier = Modifier.size(16.dp))
                }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = AstrionTheme.textPrimary,
                    fontSize = AstrionTheme.size.label,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (artist.isNotBlank()) {
                    Text(
                        artist,
                        color = AstrionTheme.textSecondary,
                        fontSize = AstrionTheme.size.micro,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            HitTarget(
                visual = 34.dp,
                enabled = ctx.connected,
                onClick = { ctx.client.callService(ServiceCall("media_player", "media_play_pause", entityId)) },
                face = Modifier.clip(CircleShape).background(AstrionTheme.accent),
            ) {
                Icon(
                    if (playing) Glyphs.pause else Glyphs.play,
                    contentDescription = if (playing) "Pause" else "Play",
                    tint = AstrionTheme.onAccent,
                    modifier = Modifier.size(15.dp),
                )
            }
        }
    }

    @Composable
    private fun StatusNote(text: String) {
        Text(
            text = text,
            color = AstrionTheme.textSecondary,
            fontSize = AstrionTheme.size.label,
            modifier = Modifier.padding(vertical = 8.dp, horizontal = 2.dp),
        )
    }

    @Composable
    private fun Tile(
        ctx: CardContext,
        item: Item,
        tile: Dp,
        highlighted: Boolean,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ) {
        var art by remember(item.thumb) { mutableStateOf(item.thumb?.let { ArtCache.peek(it, ART_PX) }) }
        LaunchedEffect(item.thumb) {
            val thumb = item.thumb ?: return@LaunchedEffect
            if (art == null) {
                ArtCache.load(thumb, ART_PX) { ctx.client.fetchBytes(thumb) }?.let { art = it }
            }
        }
        Column(
            modifier = Modifier
                .width(tile)
                .clip(RoundedCornerShape(10.dp))
                .background(if (highlighted) AstrionTheme.accentContainer else AstrionTheme.pageBg.copy(alpha = 0f))
                .tapAndHold(onClick = onClick, onLongClick = onLongClick),
            verticalArrangement = Arrangement.spacedBy(CAPTION_GAP),
        ) {
            val mod = Modifier.size(tile).clip(RoundedCornerShape(10.dp))
            Box(mod) {
                if (art != null) {
                    Image(art!!, contentDescription = null, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Box(Modifier.fillMaxSize().background(AstrionTheme.cardBg), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.MusicNote,
                            contentDescription = null,
                            tint = AstrionTheme.colors.textMuted,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                }
                if (highlighted) {
                    Box(
                        Modifier
                            .fillMaxSize()
                            .background(AstrionTheme.accent.copy(alpha = 0.30f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(Glyphs.play, contentDescription = null, tint = AstrionTheme.textPrimary, modifier = Modifier.size(24.dp))
                    }
                }
            }
            Text(
                item.title,
                color = if (highlighted) AstrionTheme.accent else AstrionTheme.textSecondary,
                fontSize = AstrionTheme.size.label,
                fontWeight = if (highlighted) FontWeight.SemiBold else FontWeight.Normal,
                style = tightTextStyle(CAPTION_LINE),
                minLines = 2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    private suspend fun loadShelf(ctx: CardContext, entityId: String, spec: Map<String, Any?>, limit: Int): Shelf? {
        val title = spec["title"] as? String ?: return null
        if (spec["source"] == "music_assistant") {
            val items = musicAssistantItems(ctx, spec, limit)
            return if (items.isEmpty()) null else Shelf(title, items)
        }
        val cid = spec["content_id"] as? String ?: return null
        val ctype = spec["content_type"] as? String ?: return null
        val rowLimit = (spec["limit"] as? Number)?.toInt()?.let { if (it <= 0) Int.MAX_VALUE else it } ?: limit
        val result = ctx.client.browseMedia(entityId, cid, ctype) ?: return null
        val items = (result["children"] as? JsonArray)
            ?.mapNotNull { parseItem(it as? JsonObject) }
            ?.take(rowLimit)
            .orEmpty()
        return if (items.isEmpty()) null else Shelf(title, items)
    }

    private suspend fun musicAssistantItems(
        ctx: CardContext,
        spec: Map<String, Any?>,
        defaultLimit: Int,
    ): List<Item> {
        val entry = spec["config_entry_id"] as? String ?: return emptyList()
        val player = spec["player"] as? String ?: return emptyList()
        val mediaType = spec["media_type"] as? String ?: "album"
        val limit = (spec["limit"] as? Number)?.toInt()?.takeIf { it > 0 } ?: defaultLimit
        val noSpotify = spec["exclude_spotify"] as? Boolean ?: false
        val data = buildJsonObject {
            put("config_entry_id", entry)
            put("media_type", mediaType)
            put("order_by", spec["order_by"] as? String ?: "random")
            put("limit", if (noSpotify) limit * 4 else limit)
            (spec["favorite"] as? Boolean)?.let { put("favorite", it) }
        }
        val response = ctx.client.callServiceForResponse("music_assistant", "get_library", data)
            ?: return emptyList()
        return (response["items"] as? JsonArray).orEmpty().mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            val uri = str("uri") ?: return@mapNotNull null
            if (noSpotify && str("image")?.contains("scdn.co") == true) return@mapNotNull null
            Item(
                title = str("name") ?: uri,
                contentId = uri,
                contentType = str("media_type") ?: mediaType,
                thumb = str("image")?.replace("size=0", "size=256"),
                maPlayer = player,
            )
        }.take(limit)
    }

    private fun parseItem(o: JsonObject?): Item? {
        o ?: return null
        fun str(k: String) = (o[k] as? JsonPrimitive)?.content
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
