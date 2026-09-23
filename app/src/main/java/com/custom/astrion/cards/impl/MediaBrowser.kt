package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ui.AstrionButton
import com.custom.astrion.ui.AstrionSheet
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.PendingSpinner
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.StateKind
import com.custom.astrion.ui.StateLine
import com.custom.astrion.ui.Tone
import com.custom.astrion.ui.Touch
import com.custom.astrion.ui.tap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/** One row in the media browser. */
private data class MediaItem(
    val title: String,
    val contentId: String,
    val contentType: String,
    val canExpand: Boolean,
    val canPlay: Boolean,
)

/**
 * Drill-down browser over `media_player/browse_media`: expand folders (with
 * a back button), tap a playable item to play it and close. A plain list —
 * no thumbnails — to stay light on the MT6580.
 *
 * Renders as an in-window [AstrionSheet]; show it through LocalOverlay:
 *   overlay.show { MediaBrowser(entityId, ctx.client) { overlay.dismiss() } }
 */
@Composable
fun MediaBrowser(entityId: String, client: HaClient, onClose: () -> Unit) {
    // Navigation stack of (contentId, contentType); root is (null, null).
    val stack = remember { mutableStateListOf<Pair<String?, String?>>(null to null) }
    var title by remember { mutableStateOf("Media") }
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(stack.size) {
        items = null
        error = null
        val (cid, ctype) = stack.last()
        val result = client.browseMedia(entityId, cid, ctype)
        if (result == null) {
            error = "Couldn't load media (timeout)"
            items = emptyList()
        } else {
            title = (result["title"] as? JsonPrimitive)?.content ?: "Media"
            items = (result["children"] as? JsonArray)?.mapNotNull { parseItem(it as? JsonObject) } ?: emptyList()
        }
    }

    AstrionSheet(onDismiss = onClose, title = title) {
        if (stack.size > 1) {
            AstrionButton(
                onClick = { if (stack.size > 1) stack.removeAt(stack.size - 1) },
                label = "Back",
                icon = Icons.Filled.ArrowBack,
                tone = Tone.Ghost,
            )
        }
        val list = items
        when {
            list == null -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                PendingSpinner(size = 28.dp)
            }
            error != null -> StateLine(error ?: "", StateKind.Danger)
            list.isEmpty() -> StateLine("Nothing here")
            else -> Column(verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
                list.forEach { item ->
                    MediaRow(item) {
                        when {
                            item.canExpand -> stack.add(item.contentId to item.contentType)
                            item.canPlay -> {
                                client.playMedia(entityId, item.contentId, item.contentType)
                                onClose()
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MediaRow(item: MediaItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.min)
            .clip(RoundedCornerShape(Radius.control))
            .background(AstrionTheme.raised)
            .tap(onClick = onClick)
            .padding(horizontal = Space.m),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.title, style = AstrionType.body, color = AstrionTheme.textPrimary,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        if (item.canExpand) {
            Icon(Icons.Filled.ChevronRight, contentDescription = "Open", tint = AstrionTheme.textSecondary)
        } else if (item.canPlay) {
            Icon(Icons.Filled.PlayArrow, contentDescription = "Play", tint = AstrionTheme.accent)
        }
    }
}

private fun parseItem(o: JsonObject?): MediaItem? {
    o ?: return null
    fun str(k: String) = (o[k] as? JsonPrimitive)?.content
    fun bool(k: String) = (o[k] as? JsonPrimitive)?.booleanOrNull ?: false
    val contentId = str("media_content_id") ?: return null
    val contentType = str("media_content_type") ?: return null
    val title = str("title") ?: contentId
    return MediaItem(title, contentId, contentType, bool("can_expand"), bool("can_play"))
}
