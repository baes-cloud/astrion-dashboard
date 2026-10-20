package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ArtCache
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.HitTarget
import com.custom.astrion.ui.InWindowDialog
import com.custom.astrion.ui.tap
import android.util.Log
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Music search: a "Search music" bar that opens a panel with a text field,
 * type filters and results from Music Assistant (`music_assistant.search`),
 * so it covers every provider MA has: the local library now, Spotify etc.
 * once they're added in MA. Tap a result to play it now on [player]; tap its
 * + to add it to the queue instead.
 *
 * Config:
 *   { "type": "music_search", "options": {
 *       "config_entry_id": "01KVF3KES1KSZ0JYSXJ405AKVA",   // the MA integration
 *       "player": "media_player.club_2",                    // an MA player
 *       "placeholder": "Search music",
 *       "limit": 8                                          // per type
 *   } }
 */
class MusicSearchCard : CardRenderer {
    override val type = "music_search"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entry = config.string("config_entry_id") ?: return
        val player = config.string("player") ?: return
        val placeholder = config.string("placeholder") ?: "Search music"
        val limit = config.int("limit", 8)
        var open by remember { mutableStateOf(false) }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(40.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(AstrionTheme.cardBgAlt)
                .tap(enabled = ctx.connected) { open = true }
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = AstrionTheme.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(placeholder, color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.body)
        }

        if (open) {
            InWindowDialog(onDismissRequest = { open = false }) {
                SearchPanel(ctx, entry, player, placeholder, limit) { open = false }
            }
        }
    }
}

/** One result, already flattened for a row. */
private data class Hit(
    val name: String,
    val subtitle: String,
    val uri: String,
    val mediaType: String,
    val image: String?,
)

private enum class Kind(val label: String, val key: String, val maType: String?) {
    ALL("All", "", null),
    ARTISTS("Artists", "artists", "artist"),
    ALBUMS("Albums", "albums", "album"),
    TRACKS("Tracks", "tracks", "track"),
    PLAYLISTS("Playlists", "playlists", "playlist"),
    RADIO("Radio", "radio", "radio"),
}

@Composable
private fun SearchPanel(
    ctx: CardContext,
    entry: String,
    player: String,
    placeholder: String,
    limit: Int,
    onClose: () -> Unit,
) {
    var query by remember { mutableStateOf("") }
    var kind by remember { mutableStateOf(Kind.ALL) }
    var results by remember { mutableStateOf<Map<Kind, List<Hit>>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var queued by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current

    LaunchedEffect(Unit) { focus.requestFocus() }

    // Search as you type, after a short pause, so each keystroke isn't a call.
    LaunchedEffect(query, kind) {
        val q = query.trim()
        if (q.length < 2) {
            results = null
            return@LaunchedEffect
        }
        delay(450)
        busy = true
        failed = false
        val found = runCatching { search(ctx, entry, q, kind, limit) }
            .onFailure { Log.w("MusicSearch", "search failed", it) }
            .getOrNull()
        Log.d("MusicSearch", "\"$q\" ${kind.label}: ${found?.values?.sumOf { it.size } ?: "no answer"}")
        failed = found == null
        results = found
        busy = false
    }

    fun play(hit: Hit, enqueue: String) {
        ctx.client.callService(
            ServiceCall.of(
                "music_assistant", "play_media", player,
                "media_id" to hit.uri,
                "media_type" to hit.mediaType,
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(23.dp))
                    .background(AstrionTheme.colors.surfacePrimary)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = AstrionTheme.accent, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(10.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(placeholder, color = AstrionTheme.textMuted, fontSize = AstrionTheme.size.bodyLarge)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.bodyLarge),
                        cursorBrush = SolidColor(AstrionTheme.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) {
                    HitTarget(visual = 26.dp, onClick = { query = "" }) {
                        Icon(Icons.Filled.Close, contentDescription = "Clear", tint = AstrionTheme.textSecondary, modifier = Modifier.size(18.dp))
                    }
                }
            }
            Spacer(Modifier.width(8.dp))
            HitTarget(
                visual = 40.dp,
                onClick = onClose,
                face = Modifier.clip(CircleShape).background(AstrionTheme.controlBg),
            ) {
                Icon(Icons.Filled.Close, contentDescription = "Close search", tint = AstrionTheme.textOnControl, modifier = Modifier.size(20.dp))
            }
        }

        // Type filters, scrollable sideways if they don't fit.
        Row(
            Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Kind.entries.forEach { k ->
                val on = k == kind
                Text(
                    k.label,
                    color = if (on) AstrionTheme.colors.onPrimary else AstrionTheme.textSecondary,
                    fontSize = AstrionTheme.size.small,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(16.dp))
                        .background(if (on) AstrionTheme.accentStrong else AstrionTheme.raised)
                        .tap { kind = k }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }

        queued?.let {
            Text(it, color = AstrionTheme.accent, fontSize = AstrionTheme.size.small)
            LaunchedEffect(it) { delay(2500); queued = null }
        }

        val shown = results
        when {
            query.trim().length < 2 -> Hint("Type an artist, album, song, playlist or station.")
            shown == null && failed && !busy -> Hint("Music Assistant didn't answer. Try again.")
            shown == null -> Hint("Searching…")
            shown.values.all { it.isEmpty() } -> Hint("Nothing found for “${query.trim()}”.")
            else -> LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                shown.forEach { (k, hits) ->
                    if (hits.isEmpty()) return@forEach
                    if (kind == Kind.ALL) item(key = "h-${k.key}") {
                        Text(
                            k.label.uppercase(),
                            color = AstrionTheme.colors.onSurfaceVariant, fontSize = AstrionTheme.size.label,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 8.dp, bottom = 2.dp, start = 4.dp),
                        )
                    }
                    items(hits, key = { "${k.key}-${it.uri}" }) { hit ->
                        HitRow(
                            ctx, hit,
                            onPlay = { keyboard?.hide(); play(hit, "replace"); onClose() },
                            onQueue = { play(hit, "add"); queued = "Added “${hit.name}” to the queue" },
                        )
                    }
                }
            }
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
private fun HitRow(ctx: CardContext, hit: Hit, onPlay: () -> Unit, onQueue: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .tap(onClick = onPlay)
            .padding(horizontal = 4.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Art(ctx, hit)
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                hit.name, color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.body,
                fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (hit.subtitle.isNotBlank()) {
                Text(
                    hit.subtitle, color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
        HitTarget(
            visual = 34.dp,
            onClick = onQueue,
            face = Modifier.clip(CircleShape).background(AstrionTheme.controlBg),
        ) {
            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add ${hit.name} to the queue", tint = AstrionTheme.textOnControl, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun Art(ctx: CardContext, hit: Hit) {
    val url = hit.image
    var art by remember(url) { mutableStateOf<ImageBitmap?>(url?.let { ArtCache.peek(it, 96) }) }
    LaunchedEffect(url) {
        val u = url ?: return@LaunchedEffect
        if (art == null) ArtCache.load(u, 96) { ctx.client.fetchBytes(u) }?.let { art = it }
    }
    val shape = if (hit.mediaType == "artist") CircleShape else RoundedCornerShape(8.dp)
    Box(
        Modifier.size(46.dp).clip(shape).background(AstrionTheme.raised),
        contentAlignment = Alignment.Center,
    ) {
        val a = art
        if (a != null) {
            Image(a, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        } else {
            Icon(typeIcon(hit.mediaType), contentDescription = null, tint = AstrionTheme.textSecondary, modifier = Modifier.size(22.dp))
        }
    }
}

private fun typeIcon(t: String): ImageVector = when (t) {
    "artist" -> Icons.Filled.Person
    "album" -> Icons.Filled.Album
    "playlist" -> Icons.Filled.QueueMusic
    "radio" -> Icons.Filled.Radio
    else -> Icons.Filled.MusicNote
}

/** `music_assistant.search`, flattened per type. Null if HA didn't answer. */
private suspend fun search(ctx: CardContext, entry: String, q: String, kind: Kind, limit: Int): Map<Kind, List<Hit>>? {
    val data = buildJsonObject {
        put("config_entry_id", entry)
        put("name", q)
        put("limit", limit)
        kind.maType?.let { t -> put("media_type", buildJsonArray { add(JsonPrimitive(t)) }) }
    }
    val response = ctx.client.callServiceForResponse("music_assistant", "search", data) ?: return null
    val kinds = if (kind == Kind.ALL) Kind.entries.drop(1) else listOf(kind)
    return kinds.associateWith { k ->
        (response[k.key] as? JsonArray).orEmpty().mapNotNull { el -> parseHit(el as? JsonObject, k.maType ?: "track") }
    }
}

private fun parseHit(o: JsonObject?, fallbackType: String): Hit? {
    o ?: return null
    fun str(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
    val uri = str("uri") ?: return null
    val type = str("media_type") ?: fallbackType
    val artists = (o["artists"] as? JsonArray).orEmpty()
        .mapNotNull { ((it as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull }
    val album = ((o["album"] as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
    // Where it comes from, so a Spotify result reads differently from the library.
    // MA names provider instances like "spotify--x8r8ytof"; the part before "--" is enough.
    val provider = uri.substringBefore("://").substringBefore("--")
    val source = when (provider) {
        "library" -> null
        "spotify" -> "Spotify"
        else -> provider.replace('_', ' ').replaceFirstChar(Char::uppercase)
    }
    val subtitle = listOfNotNull(
        artists.takeIf { it.isNotEmpty() }?.joinToString(", "),
        album?.takeIf { type == "track" },
        source,
    ).joinToString(" · ")
    return Hit(
        name = str("name") ?: uri,
        subtitle = subtitle,
        uri = uri,
        mediaType = type,
        // MA's imageproxy serves full size at size=0; a row needs ~96 px.
        image = str("image")?.takeIf { it.isNotBlank() }?.replace("size=0", "size=128"),
    )
}
