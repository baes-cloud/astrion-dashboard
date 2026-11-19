package com.custom.astrion.cards.impl

import android.util.Log
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.PlaylistAdd
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Radio
import androidx.compose.material.icons.filled.Search
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
import com.custom.astrion.ui.components.BaeoSectionHeader
import com.custom.astrion.ui.tap
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * Music search bar (Bæo v3.0 Matte Tonal):
 *  - Recessed borderless matte search pill + library browser button.
 *  - Fixes double-X confusion by using a text "Clear" control inside the input
 *    and a circular close button outside.
 *  - Wires [MediaBrowserDialog] folder stack to physical BACK.
 */
class MusicSearchCard : CardRenderer {
    override val type = "music_search"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entry = config.string("config_entry_id") ?: return
        val player = config.string("player") ?: return
        val placeholder = config.string("placeholder") ?: "Search music"
        val limit = config.int("limit", 8)
        val browsePlayers = config.stringList("browse_players")
        var open by remember { mutableStateOf(false) }
        var browsing by remember { mutableStateOf(false) }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .height(42.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AstrionTheme.cardBg)
                    .tap(enabled = ctx.connected) { open = true }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = AstrionTheme.accent, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(10.dp))
                Text(placeholder, color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.body)
            }
            if (browsePlayers.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                HitTarget(
                    visual = 42.dp,
                    enabled = ctx.connected,
                    onClick = { browsing = true },
                    face = Modifier.clip(RoundedCornerShape(14.dp)).background(AstrionTheme.cardBg),
                ) {
                    Icon(Icons.Filled.LibraryMusic, contentDescription = "Browse music", tint = AstrionTheme.accent, modifier = Modifier.size(20.dp))
                }
            }
        }

        if (open) {
            InWindowDialog(onDismissRequest = { open = false }) {
                MusicSearchPanel(ctx, entry, player, placeholder, limit) { open = false }
            }
        }

        if (browsing) {
            val rooms = remember(browsePlayers) {
                browsePlayers.map { BrowseRoom(it, ctx.entity(it)?.friendlyName ?: it.substringAfter('.')) }
            }
            MediaBrowserDialog(ctx.client, rooms) { browsing = false }
        }
    }
}

private data class Hit(
    val name: String,
    val subtitle: String,
    val provider: String?,
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
internal fun MusicSearchPanel(
    ctx: CardContext,
    entry: String,
    player: String,
    placeholder: String = "Search music",
    limit: Int = 8,
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
            .clip(RoundedCornerShape(AstrionTheme.surfaces.dialogCornerRadius))
            .background(AstrionTheme.cardBg)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                Modifier
                    .weight(1f)
                    .height(46.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AstrionTheme.colors.surfacePrimary)
                    .padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.Search, contentDescription = null, tint = AstrionTheme.accent, modifier = Modifier.size(19.dp))
                Spacer(Modifier.width(8.dp))
                Box(Modifier.weight(1f)) {
                    if (query.isEmpty()) {
                        Text(placeholder, color = AstrionTheme.textMuted, fontSize = AstrionTheme.size.body)
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        textStyle = TextStyle(color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.body),
                        cursorBrush = SolidColor(AstrionTheme.accent),
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                        keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                        modifier = Modifier.fillMaxWidth().focusRequester(focus),
                    )
                }
                if (query.isNotEmpty()) {
                    Text(
                        text = "Clear",
                        color = AstrionTheme.accent,
                        fontSize = AstrionTheme.size.label,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .tap { query = "" }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                    )
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

        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Kind.entries.forEach { k ->
                val on = k == kind
                Text(
                    k.label,
                    color = if (on) AstrionTheme.colors.onPrimary else AstrionTheme.textSecondary,
                    fontSize = AstrionTheme.size.label,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) AstrionTheme.accentStrong else AstrionTheme.raised)
                        .tap { kind = k }
                        .padding(horizontal = 12.dp, vertical = 7.dp),
                )
            }
        }

        queued?.let {
            Text(
                it,
                color = AstrionTheme.accent,
                fontSize = AstrionTheme.size.label,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(AstrionTheme.accentContainer)
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            )
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
                    if (kind == Kind.ALL) item(key = "h-${k.key}", contentType = "header") {
                        BaeoSectionHeader(k.label, modifier = Modifier.padding(top = 8.dp, bottom = 2.dp))
                    }
                    items(hits, key = { "${k.key}-${it.uri}" }, contentType = { "hit" }) { hit ->
                        HitRow(
                            ctx, hit,
                            onPlay = { keyboard?.hide(); play(hit, "replace"); onClose() },
                            onQueue = { play(hit, "add"); queued = "Added “${hit.name}” to queue" },
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
        text, color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.label,
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
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    hit.name,
                    color = AstrionTheme.textPrimary,
                    fontSize = AstrionTheme.size.body,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                hit.provider?.let { prov ->
                    Text(
                        text = prov,
                        color = AstrionTheme.textSecondary,
                        fontSize = AstrionTheme.size.micro,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(AstrionTheme.raised)
                            .padding(horizontal = 5.dp, vertical = 1.dp),
                    )
                }
            }
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
            Icon(Icons.AutoMirrored.Filled.PlaylistAdd, contentDescription = "Add ${hit.name} to queue", tint = AstrionTheme.textOnControl, modifier = Modifier.size(18.dp))
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
    val rawProvider = uri.substringBefore("://").substringBefore("--")
    val source = when (rawProvider) {
        "library" -> null
        "spotify" -> "Spotify"
        "filesystem_smb", "filesystem_local" -> "Local"
        else -> rawProvider.replace('_', ' ').replaceFirstChar(Char::uppercase)
    }
    val subtitle = listOfNotNull(
        artists.takeIf { it.isNotEmpty() }?.joinToString(", "),
        album?.takeIf { type == "track" },
    ).joinToString(" · ")
    return Hit(
        name = str("name") ?: uri,
        subtitle = subtitle,
        provider = source,
        uri = uri,
        mediaType = type,
        image = str("image")?.takeIf { it.isNotBlank() }?.replace("size=0", "size=128"),
    )
}
