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
        val http: OkHttpClient = com.custom.astrion.ha.Http.base.newBuilder()
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
        val json = Json { ignoreUnknownKeys = true }

        // Last shelves per host: the page is recomposed from scratch every
        // time you navigate to it, and a cold load of five rows took ~10 s on
        // this SoC — so show the previous result at once and refresh behind it.
        // Kept on disk too (DiskJson), so a cold start shows them at once.
        val shelfCache = java.util.concurrent.ConcurrentHashMap<String, List<PlexShelf>>()
        val shelvesSerializer = ListSerializer(PlexShelf.serializer())

        // ~3.5 tiles across a 480px/220dpi panel.
        val TILE_W = 84.dp
        val POSTER_H = 120.dp

        const val PLEX_APP = "com.plexapp.android"
        /** How long to wait for the TV to show Plex after the intent. */
        const val OPEN_TIMEOUT_MS = 10_000L

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
        val host = config.string("host")?.trimEnd('/') ?: return
        val token = config.string("token").orEmpty()
        val playEntity = config.string("play_entity") ?: return
        val adbEntity = config.string("adb_entity")
        val tvEntity = config.string("tv_entity")
        val wakeTimeoutMs = config.int("wake_timeout", 25).coerceIn(5, 60) * 1000L
        val limit = config.int("limit", 10).coerceIn(1, 30)
        val rowSpecs = (config.options["rows"] as? List<Map<String, Any?>>) ?: emptyList()

        // machineIdentifier is needed to build the plex:// play id. Read it
        // from the server rather than pinning it in config.
        val machineId by produceState<String?>(initialValue = config.string("machine_id"), host, token) {
            if (value == null) {
                value = get(plexUrl(host, token, "/identity")).json?.mc()?.str("machineIdentifier")
            }
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
                        val got = get(plexUrl(host, token, "$path${sep}X-Plex-Container-Start=0&X-Plex-Container-Size=$limit"))
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
                    item.thumb?.let { t -> launch { loadPoster(plexThumbUrl(host, token, t, posterW, posterH)) } }
                }
            }
        }

        // Starting cold takes several seconds (wake the TV, wait for ADB, fire
        // the intent, wait for Plex to buffer). Without a line saying so, a tap
        // looks exactly like the old do-nothing bug.
        var status by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        fun play(item: PlexItem) {
            val mid = machineId ?: run {
                status = "Plex server unreachable"
                return
            }
            if (adbEntity == null) {
                // No ADB bridge configured: the old session-only path.
                val id = "plex://$mid/${item.ratingKey}" + if (item.resume) "?resume=1" else ""
                ctx.client.callService(
                    ServiceCall.of(
                        "media_player", "play_media", playEntity,
                        "media_content_type" to item.type,
                        "media_content_id" to id,
                    )
                )
                return
            }
            scope.launch {
                status = "Starting ${item.title}…"
                // ADB can't reach a sleeping TV, so wake it over the
                // androidtv_remote protocol first and wait for the bridge.
                if (isAsleep(ctx.entity(adbEntity)?.state)) {
                    if (tvEntity != null) {
                        ctx.client.callService(
                            ServiceCall(domain = "media_player", service = "turn_on", entityId = tvEntity)
                        )
                        status = "Waking the TV…"
                    }
                    val awake = withTimeoutOrNull(wakeTimeoutMs) {
                        while (isAsleep(ctx.entity(adbEntity)?.state)) delay(500)
                        true
                    }
                    if (awake == null) {
                        status = "TV didn't wake — try again"
                        delay(4000); status = null
                        return@launch
                    }
                }
                status = "Opening Plex…"
                // confirm: a failed ADB call shows as a toast, not silence.
                ctx.client.callService(
                    ServiceCall.of(
                        "androidtv", "adb_command", adbEntity,
                        "command" to plexDeepLink(mid, item.ratingKey),
                    ),
                    confirm = true,
                )
                // The ADB entity reports the foreground app: wait for Plex.
                val opened = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
                    while (ctx.entity(adbEntity)?.attrString("app_id") != PLEX_APP) delay(500)
                    true
                }
                val app = ctx.entity(adbEntity)?.attrString("app_id")
                status = when {
                    opened == true -> "Playing on the TV"
                    app != null -> "Plex didn't open on the TV (showing $app) — try again"
                    else -> null
                }
                delay(4000)
                status = null
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            status?.let { ShelfLabel(it) }
            when {
                shelves == null -> ShelfLabel("Plex — loading…")
                shelves!!.isEmpty() -> ShelfLabel(plexEmptyMessage(host, loadError))
                else -> shelves!!.forEach { shelf ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
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

    @Composable
    private fun ShelfLabel(text: String) {
        Text(
            text,
            color = AstrionTheme.colors.onSurfaceVariant,
            fontSize = AstrionTheme.size.label,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
    }

    @Composable
    private fun PosterTile(posterUrl: String?, item: PlexItem, onClick: () -> Unit) {
        // Posters come from ArtCache (memory, then disk), so swiping back
        // and forth, or restarting, doesn't refetch them from the server.
        var bmp by remember(posterUrl) { mutableStateOf(posterUrl?.let { ArtCache.peek(it) }) }
        LaunchedEffect(posterUrl) {
            if (bmp == null && posterUrl != null) loadPoster(posterUrl)?.let { bmp = it }
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

    // ---- Plex HTTP ----------------------------------------------------------

    /** A Plex response, or why there isn't one ("HTTP 401", "unreachable", "not JSON"). */
    private class Got(val json: JsonObject?, val error: String?)

    /** One configured row's result: its items, or why it has none. */
    private class RowResult(val title: String, val items: List<PlexItem>?, val error: String?)

    private suspend fun get(u: String): Got = withContext(Dispatchers.IO) {
        try {
            http.newCall(Request.Builder().url(u).header("Accept", "application/json").build())
                .execute().use { r ->
                    if (!r.isSuccessful) return@use Got(null, "HTTP ${r.code}")
                    val body = r.body?.string() ?: return@use Got(null, "empty reply")
                    val obj = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
                    if (obj == null) Got(null, "not JSON") else Got(obj, null)
                }
        } catch (e: java.io.IOException) {
            Got(null, "unreachable")
        }
    }

    /** Already server-scaled to the tile by [plexThumbUrl], so no downsampling. */
    private suspend fun loadPoster(url: String) = ArtCache.load(url) {
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes() else null
            }
        }.getOrNull()
    }

    /** ADB entity states that mean "no shell to talk to". */
    private fun isAsleep(state: String?): Boolean =
        state == null || state == "off" || state == "unavailable" || state == "unknown"

    private fun JsonObject.mc(): JsonObject? = this["MediaContainer"] as? JsonObject
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.content
}
