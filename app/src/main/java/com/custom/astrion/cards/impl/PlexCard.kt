package com.custom.astrion.cards.impl

import kotlinx.serialization.builtins.ListSerializer
import com.custom.astrion.ui.DiskJson
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ArtCache
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.rememberPageVisits
import com.custom.astrion.ui.tap
import com.custom.astrion.ui.tightTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Plex poster rows — swipeable shelves of On Deck / Recently Added, where
 * tapping a poster starts that exact episode or film on the TV. No detail
 * screen, no browsing: one tap plays.
 *
 * Metadata and posters come straight from the Plex server's HTTP API (plain
 * http on the LAN — no TLS handshake to pay for on the MT6580, and the server
 * allows unauthenticated local access). Playback goes through Home Assistant's
 * Plex *client* entity:
 *
 *   media_player.play_media
 *     media_content_type: episode | movie | season
 *     media_content_id:   plex://<machineIdentifier>/<ratingKey>[?resume=1]
 *
 * ...but ONLY while a Plex session already exists. That entity is created by
 * HA's Plex integration from the server's *session* list, so with the TV off,
 * or Plex merely open and idle, it is `unavailable` and the call goes nowhere
 * — which is exactly the "tapping a poster does nothing unless I already
 * started something" behaviour. (The Plex Android TV app does not register as
 * a controllable client either: /clients on the server is empty, so there is
 * no client entity to fall back to.)
 *
 * So when `adb_entity` is configured the card takes the other route entirely:
 *
 *   1. `media_player.turn_on` on `tv_entity` (androidtv_remote — that protocol
 *      still answers from standby, where ADB does not), then wait for the ADB
 *      entity to come up. Measured at ~5s on the Google TV Streamer.
 *   2. `androidtv.adb_command` firing a Plex deep link:
 *        am start -a android.intent.action.VIEW \
 *          -d 'plex://server://<machineId>/com.plexapp.plugins.library/library/metadata/<ratingKey>'
 *
 * One intent foregrounds Plex over whatever app was on screen AND starts the
 * item, resuming at its stored offset — no session needed first. Verified
 * end to end from a cold, powered-off TV.
 *
 * Config shape:
 *   { "type": "plex", "options": {
 *       "host": "http://plex:32400",
 *       "token": "<X-Plex-Token>",           // optional on an open LAN
 *       "play_entity": "media_player.plex_...",   // fallback when no adb_entity
 *       "adb_entity": "media_player.club_android_tv_...",
 *       "tv_entity":  "media_player.the_club_tvv",
 *       "wake_timeout": 25,
 *       "limit": 10,
 *       "rows": [
 *         { "title": "On Deck",           "path": "/library/onDeck" },
 *         { "title": "Recently Added TV", "path": "/library/sections/2/recentlyAdded" }
 *       ]
 *   } }
 */
class PlexCard : CardRenderer {
    override val type = "plex"

    private companion object {
        val json get() = PlexNet.json

        // Last shelves per host: the page is recomposed from scratch every
        // time you navigate to it, and a cold load of five rows took ~10 s on
        // this SoC — so show the previous result at once and refresh behind it.
        // Kept on disk too (DiskJson), so a cold start shows them at once.
        val shelfCache = java.util.concurrent.ConcurrentHashMap<String, List<PlexShelf>>()
        val shelvesSerializer = ListSerializer(PlexShelf.serializer())

        // ~3.5 tiles across a 480px/220dpi panel.
        // Four and a bit across, so the fifth peeking in says "swipe for more".
        val TILE_W = 66.dp
        val POSTER_H = 95.dp

        /** Posters per row fetched ahead of being scrolled into view. */
        const val PREFETCH = 6

        fun storedShelves(host: String): List<PlexShelf>? = shelfCache[host]
            ?: DiskJson.read("plex:$host")
                ?.let { runCatching { json.decodeFromString(shelvesSerializer, it) }.getOrNull() }
                ?.also { shelfCache[host] = it }
    }

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val target = remember(config) { PlexTarget.from(config) } ?: return
        val host = target.host
        val token = target.token
        val limit = config.int("limit", 10).coerceIn(1, 30)
        val rowSpecs = (config.options["rows"] as? List<Map<String, Any?>>) ?: emptyList()

        // machineIdentifier is needed to build the plex:// play id. Read it
        // from the server rather than pinning it in config.
        val machineId by produceState<String?>(initialValue = config.string("machine_id"), host, token) {
            if (value == null) value = target.machineId()
        }

        // Refreshed on every visit to the page (it stays composed otherwise).
        val visits = rememberPageVisits()
        var loadError by remember { mutableStateOf<String?>(null) }
        val shelves by produceState(initialValue = storedShelves(host), host, token, limit, visits) {
            // All rows at once rather than one after another.
            val results = kotlinx.coroutines.coroutineScope {
                rowSpecs.map { spec ->
                    async {
                        val title = spec["title"] as? String ?: return@async null
                        val path = spec["path"] as? String ?: return@async null
                        val sep = if ('?' in path) "&" else "?"
                        val got = PlexNet.get(plexUrl(host, token, "$path${sep}X-Plex-Container-Start=0&X-Plex-Container-Size=$limit"))
                        val items = got.json?.let(::parsePlexItems)
                        RowResult(title, items, if (items == null) got.error ?: "no items" else null)
                    }
                }.awaitAll().filterNotNull()
            }
            val fresh = results.mapNotNull { r -> r.items?.takeIf { it.isNotEmpty() }?.let { PlexShelf(r.title, it) } }
            loadError = results.firstNotNullOfOrNull { it.error }
            // A failed refresh keeps what was already on screen.
            if (fresh.isNotEmpty() || value == null) {
                value = fresh
                shelfCache[host] = fresh
                if (fresh.isNotEmpty()) {
                    withContext(Dispatchers.IO) { DiskJson.write("plex:$host", json.encodeToString(shelvesSerializer, fresh)) }
                }
            }
        }

        // Posters at the tile's real pixel size (smaller than the old 200x300).
        val density = LocalDensity.current
        val posterW = with(density) { TILE_W.roundToPx() }
        val posterH = with(density) { POSTER_H.roundToPx() }

        // The first few posters of every row, so the row is filled before
        // it's scrolled to and the first swipe along it doesn't wait.
        LaunchedEffect(shelves, posterW) {
            shelves?.forEach { shelf ->
                shelf.items.take(PREFETCH).forEach { item ->
                    item.thumb?.let { t -> launch { PlexNet.poster(plexThumbUrl(host, token, t, posterW, posterH)) } }
                }
            }
        }

        // Starting cold takes several seconds (wake the TV, wait for ADB, fire
        // the intent, wait for Plex to buffer). Without a line saying so, a tap
        // looks exactly like the old do-nothing bug.
        var status by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        fun play(item: PlexItem) {
            scope.launch {
                target.play(ctx, machineId, item) { status = it }
                delay(4000)
                status = null
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            status?.let { ShelfStatus(it) }
            when {
                shelves == null -> ShelfStatus("Loading Plex…")
                shelves!!.isEmpty() -> ShelfStatus(plexEmptyMessage(host, loadError))
                else -> shelves!!.forEach { shelf ->
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        ShelfLabel(shelf.title)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(shelf.items, key = { it.ratingKey }) { item ->
                                PosterTile(item.thumb?.let { plexThumbUrl(host, token, it, posterW, posterH) }, item) { play(item) }
                            }
                        }
                    }
                }
            }
        }
    }

    /** A shelf's name: the remote's small uppercase section label. */
    @Composable
    private fun ShelfLabel(text: String) {
        Text(
            text.uppercase(),
            color = AstrionTheme.colors.onSurfaceVariant,
            fontSize = AstrionTheme.size.label,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }

    /** A status or problem line (starting on the TV, can't reach Plex): sentence case. */
    @Composable
    private fun ShelfStatus(text: String) {
        Text(
            text,
            color = AstrionTheme.textSecondary,
            fontSize = AstrionTheme.size.small,
            fontWeight = FontWeight.Medium,
        )
    }

    @Composable
    private fun PosterTile(posterUrl: String?, item: PlexItem, onClick: () -> Unit) {
        // Posters come from ArtCache (memory, then disk), so swiping back
        // and forth, or restarting, doesn't refetch them from the server.
        var bmp by remember(posterUrl) { mutableStateOf(posterUrl?.let { ArtCache.peek(it) }) }
        LaunchedEffect(posterUrl) {
            if (bmp == null && posterUrl != null) PlexNet.poster(posterUrl)?.let { bmp = it }
        }

        Column(
            modifier = Modifier
                .width(TILE_W)
                .tap(onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(1.dp),
        ) {
            val posterMod = Modifier
                .fillMaxWidth()
                .height(POSTER_H)
                .clip(RoundedCornerShape(8.dp))
            if (bmp != null) {
                Image(bmp!!, contentDescription = item.title, modifier = posterMod, contentScale = ContentScale.Crop)
            } else {
                Box(posterMod.background(AstrionTheme.raised))
            }
            Spacer(Modifier.height(3.dp))
            Text(
                item.title,
                style = tightTextStyle(13.sp),
                color = AstrionTheme.textPrimary,
                fontSize = AstrionTheme.size.label,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    item.subtitle,
                    style = tightTextStyle(12.sp),
                    color = AstrionTheme.textSecondary,
                    fontSize = AstrionTheme.size.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    /** One configured row's result: its items, or why it has none. */
    private class RowResult(val title: String, val items: List<PlexItem>?, val error: String?)

    private fun JsonObject.mc(): JsonObject? = this["MediaContainer"] as? JsonObject
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.content
}
