package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ArtCache
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.HitTarget
import com.custom.astrion.ui.tap
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/** A player the browser can target, e.g. a Sonos room. */
data class BrowseRoom(val entityId: String, val name: String)

/** One row in the media browser. */
private data class MediaItem(
    val title: String,
    val contentId: String,
    val contentType: String,
    val mediaClass: String,
    val thumbnail: String?,
    val canExpand: Boolean,
    val canPlay: Boolean,
)

/** One level of the drill-down; [items] is kept so going back is instant. */
private class Level(val contentId: String?, val contentType: String?, title: String) {
    var title by mutableStateOf(title)
    var items by mutableStateOf<List<MediaItem>?>(null)
    var truncated by mutableStateOf(false)
    var failed by mutableStateOf(false)
}

/** Rows shown per folder. HA sends whole folders; this caps what we keep. */
private const val MAX_ROWS = 300

/** HA media sources that mean nothing on a speaker; hidden from the top level. */
private val HIDDEN_SOURCES = setOf("ai_task", "camera", "image", "image_upload", "nest", "tts")

/**
 * HA's media browser, over `media_player/browse_media`, for the panel that
 * opens from the library button beside "Search music". Room chips pick the
 * player; folders drill in (back steps out); tapping a playable row plays
 * it now on that room and closes, its + adds it to the room's queue.
 *
 * Built for the MT6580: only the open path is held (each level's list,
 * capped at [MAX_ROWS]), and covers decode at row size through [ArtCache].
 */
@Composable
fun MediaBrowser(client: HaClient, rooms: List<BrowseRoom>, onClose: () -> Unit) {
    if (rooms.isEmpty()) return
    var room by remember { mutableStateOf(rooms.first()) }
    val stack = remember { mutableStateListOf(Level(null, null, "Library")) }
    var note by remember { mutableStateOf<String?>(null) }
    var retry by remember { mutableIntStateOf(0) }
    val level = stack.last()

    // Load the open level when it's new, or when the room changes (each
    // player has its own tree: Sonos favourites differ from MA's library).
    LaunchedEffect(level, room, retry) {
        if (level.items != null && !level.failed) return@LaunchedEffect
        level.failed = false
        level.items = null
        val result = client.browseMedia(room.entityId, level.contentId, level.contentType)
        if (result == null) {
            level.failed = true
            level.items = emptyList()
            return@LaunchedEffect
        }
        (result["title"] as? JsonPrimitive)?.contentOrNull?.let { if (stack.size > 1) level.title = it }
        val children = (result["children"] as? JsonArray).orEmpty()
        level.truncated = children.size > MAX_ROWS
        level.items = children.asSequence()
            .take(MAX_ROWS)
            .mapNotNull { parseItem(it as? JsonObject) }
            .filterNot { it.contentId.removePrefix("media-source://") in HIDDEN_SOURCES }
            .toList()
    }

    fun play(item: MediaItem, enqueue: String) {
        client.callService(
            ServiceCall.of(
                "media_player", "play_media", room.entityId,
                "media_content_id" to item.contentId,
                "media_content_type" to item.contentType,
                "enqueue" to enqueue,
            ),
            confirm = true,
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .fillMaxHeight()
            .clip(RoundedCornerShape(22.dp))
            .background(AstrionTheme.colors.surfaceVariant)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        // Header: back (when nested), title, close.
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (stack.size > 1) {
                RoundBtn(Icons.AutoMirrored.Filled.ArrowBack, "Back") { stack.removeAt(stack.size - 1) }
                Spacer(Modifier.width(8.dp))
            }
            Text(
                level.title,
                color = AstrionTheme.textPrimary,
                fontSize = AstrionTheme.size.title,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(8.dp))
            RoundBtn(Icons.Filled.Close, "Close library", onClick = onClose)
        }

        // Room chips. Switching room goes back to its top level, since the
        // trees can differ between players.
        if (rooms.size > 1) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                rooms.forEach { r ->
                    val on = r == room
                    Text(
                        r.name,
                        color = if (on) AstrionTheme.colors.onPrimary else AstrionTheme.textSecondary,
                        fontSize = AstrionTheme.size.small,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(16.dp))
                            .background(if (on) AstrionTheme.accentStrong else AstrionTheme.raised)
                            .tap {
                                if (!on) {
                                    room = r
                                    stack.clear()
                                    stack.add(Level(null, null, "Library"))
                                }
                            }
                            .padding(horizontal = 12.dp, vertical = 7.dp),
                    )
                }
            }
        }

        note?.let {
            Text(it, color = AstrionTheme.accent, fontSize = AstrionTheme.size.small)
            LaunchedEffect(it) { delay(2500); note = null }
        }

        val items = level.items
        when {
            items == null -> Hint("Loading…")
            level.failed -> Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).tap { retry++ }) {
                Hint("${room.name} didn't answer. Tap to try again.")
            }
            items.isEmpty() -> Hint("Nothing in here.")
            else -> LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                itemsIndexed(items, key = { i, it -> "$i-${it.contentId}" }) { _, item ->
                    BrowseRow(
                        client, item,
                        onOpen = {
                            when {
                                item.canExpand -> stack.add(Level(item.contentId, item.contentType, item.title))
                                item.canPlay -> { play(item, "replace"); onClose() }
                            }
                        },
                        onPlay = { play(item, "replace"); onClose() },
                        onQueue = { play(item, "add"); note = "Added “${item.title}” to ${room.name}'s queue" },
                    )
                }
                if (level.truncated) item(key = "more") {
                    Hint("Showing the first $MAX_ROWS. Search finds the rest.")
                }
            }
        }
    }
}

@Composable
private fun BrowseRow(
    client: HaClient,
    item: MediaItem,
    onOpen: () -> Unit,
    onPlay: () -> Unit,
    onQueue: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .tap(onClick = onOpen)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Art(client, item)
        Spacer(Modifier.width(10.dp))
        Text(
            item.title,
            color = AstrionTheme.textPrimary,
            fontSize = AstrionTheme.size.body,
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (item.canPlay) {
            // A folder that also plays (an album, "Tracks") gets its own play
            // button, since tapping the row opens it.
            if (item.canExpand) {
                SmallBtn(Icons.Filled.PlayArrow, "Play ${item.title}", onPlay)
                Spacer(Modifier.width(6.dp))
            }
            SmallBtn(Icons.AutoMirrored.Filled.PlaylistAdd, "Add ${item.title} to the queue", onQueue)
        }
        if (item.canExpand) {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = AstrionTheme.textSecondary, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun Art(client: HaClient, item: MediaItem) {
    val url = item.thumbnail
    var art by remember(url) { mutableStateOf<ImageBitmap?>(url?.let { ArtCache.peek(it, 96) }) }
    LaunchedEffect(url) {
        val u = url ?: return@LaunchedEffect
        if (art == null) ArtCache.load(u, 96) { client.fetchBytes(u) }?.let { art = it }
    }
    val shape = if (item.mediaClass == "artist") CircleShape else RoundedCornerShape(8.dp)
    Box(
        Modifier.size(46.dp).clip(shape).background(AstrionTheme.raised),
        contentAlignment = Alignment.Center,
    ) {
        val a = art
        if (a != null) {
            Image(a, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(classIcon(item.mediaClass), contentDescription = null, tint = AstrionTheme.textSecondary, modifier = Modifier.size(22.dp))
        }
    }
}

@Composable
private fun Hint(text: String) {
    Text(
        text, color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.small,
        modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp, horizontal = 4.dp),
    )
}

@Composable
private fun RoundBtn(icon: ImageVector, label: String, onClick: () -> Unit) {
    HitTarget(visual = 40.dp, onClick = onClick, face = Modifier.clip(CircleShape).background(AstrionTheme.controlBg)) {
        Icon(icon, contentDescription = label, tint = AstrionTheme.textOnControl, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun SmallBtn(icon: ImageVector, label: String, onClick: () -> Unit) {
    HitTarget(visual = 34.dp, onClick = onClick, face = Modifier.clip(CircleShape).background(AstrionTheme.controlBg)) {
        Icon(icon, contentDescription = label, tint = AstrionTheme.textOnControl, modifier = Modifier.size(18.dp))
    }
}

private fun classIcon(c: String): ImageVector = when (c) {
    "artist", "contributing_artist", "composer" -> Icons.Filled.Person
    "album" -> Icons.Filled.Album
    "playlist" -> Icons.Filled.QueueMusic
    "channel", "podcast" -> Icons.Filled.Radio
    "track", "music" -> Icons.Filled.MusicNote
    "genre" -> Icons.Filled.Star
    else -> Icons.Filled.Folder
}

private fun parseItem(o: JsonObject?): MediaItem? {
    o ?: return null
    fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
    fun bool(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false
    // Sonos' top folders have an empty id and are told apart by type alone.
    val contentId = str("media_content_id") ?: return null
    val contentType = str("media_content_type") ?: return null
    return MediaItem(
        title = str("title")?.trim()?.takeIf { it.isNotEmpty() } ?: contentId,
        contentId = contentId,
        contentType = contentType,
        mediaClass = str("media_class") ?: "directory",
        thumbnail = str("thumbnail")?.takeIf { it.isNotBlank() },
        canExpand = bool("can_expand"),
        canPlay = bool("can_play"),
    )
}
