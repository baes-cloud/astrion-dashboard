package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.custom.astrion.ha.HaClient
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonPrimitive
import com.custom.astrion.ui.tap

/** One row in the media browser. */
private data class MediaItem(
    val title: String,
    val contentId: String,
    val contentType: String,
    val canExpand: Boolean,
    val canPlay: Boolean,
)

/**
 * Modal media browser over `media_player/browse_media`. Drill into expandable
 * folders (with a back button), tap a playable item to play it and close.
 * Kept to a plain list — no thumbnails — to stay light on the MT6580.
 */
@Composable
fun MediaBrowser(entityId: String, client: HaClient, onClose: () -> Unit) {
    // Navigation stack of (contentId, contentType); root is (null, null).
    val stack = remember { mutableStateListOf<Pair<String?, String?>>(null to null) }
    var title by remember { mutableStateOf("Media") }
    var items by remember { mutableStateOf<List<MediaItem>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    // Reload whenever the depth changes (push/pop).
    androidx.compose.runtime.LaunchedEffect(stack.size) {
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

    Dialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1B343D))
                .padding(12.dp),
        ) {
            // Header: back (when nested), title, close.
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (stack.size > 1) {
                    IconBtn(Icons.Filled.ArrowBack) { if (stack.size > 1) stack.removeAt(stack.size - 1) }
                    Spacer(Modifier.width(6.dp))
                }
                Text(
                    title,
                    color = Color(0xFFE6F0F1),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                IconBtn(Icons.Filled.Close, onClick = onClose)
            }
            Spacer(Modifier.height(8.dp))

            when {
                items == null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = Color(0xFF6EA8FE))
                }
                error != null -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(error!!, color = Color(0xFFE0A0A0), fontSize = 14.sp)
                }
                items!!.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Nothing here", color = Color(0xFF93AFB6), fontSize = 14.sp)
                }
                else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    items(items!!) { item ->
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
}

@Composable
private fun MediaRow(item: MediaItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .tap(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            item.title,
            color = Color(0xFFE6F0F1),
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (item.canExpand) {
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Color(0xFF93AFB6))
        } else if (item.canPlay) {
            Icon(Icons.Filled.PlayArrow, contentDescription = null, tint = Color(0xFF6EA8FE))
        }
    }
}

@Composable
private fun IconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color(0xFFCBDCE0))
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
