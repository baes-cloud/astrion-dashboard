package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.custom.astrion.cards.CardContext
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.BottomSheet
import com.custom.astrion.ui.Glyphs
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The player's queue, as a bottom sheet. Music Assistant's
 * `music_assistant.get_queue` only hands HA the current and the next item
 * (plus where in the queue you are), so that's what it lists; the full list
 * lives in Music Assistant itself.
 *
 * [speaker] is the Sonos the card shows (Club), [player] Music Assistant's
 * player for it (Club 2). When the Sonos is playing something Music
 * Assistant didn't start (a Sonos favourite, the radio, the TV), MA's queue
 * still holds its last track, so the sheet shows the speaker's own now
 * playing instead.
 */
@Composable
fun QueueSheet(ctx: CardContext, player: String, speaker: String? = null, onClose: () -> Unit) {
    var queue by remember(player) { mutableStateOf<QueueInfo?>(null) }
    var loading by remember(player) { mutableStateOf(true) }
    val sonos = speaker?.let { ctx.entities[it] }
    val sonosTitle = sonos?.attrString("media_title")?.takeIf { it.isNotBlank() && sonos.state in ACTIVE }
    // Refetched when the track changes under the open sheet.
    val nowTitle = ctx.entities[player]?.attrString("media_title")
    LaunchedEffect(player, nowTitle, sonosTitle) {
        // One retry: the first ask can land while the socket is still busy
        // reconnecting after the screen wakes. A failed refetch keeps the
        // list already showing.
        repeat(2) {
            val response = ctx.client.callServiceForResponse("music_assistant", "get_queue", entityId = player)
            (response?.get(player) as? JsonObject)?.let(::parseQueue)?.let {
                queue = it
                loading = false
                return@LaunchedEffect
            }
        }
        loading = false
    }

    val q = queue?.takeIf { info ->
        // Only MA's queue while MA is what the speaker is playing.
        sonosTitle == null || info.active && info.current?.let { sameTrack(it.title, sonosTitle) } == true
    }
    val direct = q == null && sonosTitle != null && !(loading && queue == null)
    BottomSheet(
        title = if (direct) "Now playing" else "Up next",
        subtitle = if (direct) {
            listOfNotNull(sonos?.attrString("source")?.takeIf { it.isNotBlank() }, "On the Sonos").joinToString(" · ")
        } else q?.let { info ->
            val pos = info.index?.let { "Track ${it + 1}" }
            listOfNotNull(pos?.let { p -> info.total?.let { "$p of $it" } ?: p }, info.name).joinToString(" · ")
        },
        onDismissRequest = onClose,
    ) {
        when {
            q == null && loading -> Note("Asking Music Assistant…")
            q == null -> {
                // Nothing from MA, or MA isn't what's playing: show the
                // speaker's own track (or MA's player's, without a speaker).
                val p = sonos?.takeIf { sonosTitle != null } ?: ctx.entities[player]
                val title = p?.attrString("media_title")?.takeIf { it.isNotBlank() && p.state in ACTIVE }
                if (title != null) {
                    Caption("NOW PLAYING")
                    QueueRow(QueueItem(title, p.attrString("media_artist") ?: p.attrString("media_album_name"), null), now = true)
                    Note(
                        if (direct && queue != null) "Playing on the Sonos directly, not from Music Assistant, so there's no queue to show."
                        else "Music Assistant didn't send the queue. Close and reopen to try again."
                    )
                } else {
                    Note("No queue for this player right now.")
                }
            }
            else -> {
                q.current?.let {
                    Caption("NOW PLAYING")
                    QueueRow(it, now = true)
                }
                q.next?.let {
                    Caption("UP NEXT")
                    QueueRow(it, now = false)
                }
                if (q.current == null && q.next == null) Note("The queue is empty.")
                Note("Music Assistant shares only the playing and next track with Home Assistant.")
            }
        }
    }
}

private val ACTIVE = listOf("playing", "paused")

/**
 * Club and Club 2 name a track differently ("Bouncer" against "Bouncer
 * (Original Mix)", or MA's "Artist - Title" queue name), so one only has to
 * contain the other.
 */
internal fun sameTrack(a: String, b: String): Boolean {
    val x = a.trim().lowercase()
    val y = b.trim().lowercase()
    return x.isNotEmpty() && y.isNotEmpty() && (x.contains(y) || y.contains(x))
}

@Composable
private fun QueueRow(item: QueueItem, now: Boolean) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (now) AstrionTheme.colors.accentContainer else AstrionTheme.raised)
            .then(if (now) Modifier.border(1.dp, AstrionTheme.accentStrong, shape) else Modifier)
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(AstrionTheme.trackBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Glyphs.note, contentDescription = null, tint = AstrionTheme.accent, modifier = Modifier.size(18.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                color = if (now) AstrionTheme.colors.accentBright else AstrionTheme.textPrimary,
                fontSize = AstrionTheme.size.body,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            item.artist?.let {
                Text(
                    it,
                    color = AstrionTheme.textSecondary,
                    fontSize = AstrionTheme.size.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        item.seconds?.let { s ->
            Text(
                "${s / 60}:${(s % 60).toString().padStart(2, '0')}",
                color = AstrionTheme.textMuted,
                fontSize = AstrionTheme.size.small,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(
        text,
        color = AstrionTheme.textMuted,
        fontSize = AstrionTheme.size.label,
        fontWeight = FontWeight.Bold,
        letterSpacing = 0.14.em,
        modifier = Modifier.padding(start = 4.dp, top = 2.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(text, color = AstrionTheme.textMuted, fontSize = AstrionTheme.size.small, modifier = Modifier.padding(4.dp))
}

private class QueueItem(val title: String, val artist: String?, val seconds: Int?)

private class QueueInfo(
    val name: String?,
    val active: Boolean,
    val index: Int?,
    val total: Int?,
    val current: QueueItem?,
    val next: QueueItem?,
)

private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

private fun parseItem(o: JsonObject?): QueueItem? {
    o ?: return null
    val media = o["media_item"] as? JsonObject
    val title = media?.str("name") ?: o.str("name") ?: return null
    val artist = (media?.get("artists") as? JsonArray)
        ?.mapNotNull { (it as? JsonObject)?.str("name") }
        ?.takeIf { it.isNotEmpty() }
        ?.joinToString(", ")
    val seconds = (o["duration"] as? JsonPrimitive)?.doubleOrNull?.toInt()?.takeIf { it > 0 }
    return QueueItem(title, artist, seconds)
}

private fun parseQueue(o: JsonObject) = QueueInfo(
    name = o.str("name"),
    active = (o["active"] as? JsonPrimitive)?.booleanOrNull ?: true,
    index = (o["current_index"] as? JsonPrimitive)?.intOrNull,
    total = (o["items"] as? JsonPrimitive)?.intOrNull,
    current = parseItem(o["current_item"] as? JsonObject),
    next = parseItem(o["next_item"] as? JsonObject),
)
