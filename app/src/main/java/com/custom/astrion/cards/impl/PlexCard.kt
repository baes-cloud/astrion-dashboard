package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.ImageCache
import com.custom.astrion.ui.LocalFeedback
import com.custom.astrion.ui.PendingSpinner
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.SectionLabel
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.StateKind
import com.custom.astrion.ui.StateLine
import com.custom.astrion.ui.decodeSampledBytes
import com.custom.astrion.ui.rememberRemoteBitmap
import com.custom.astrion.ui.tap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
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

    private data class PlexItem(
        val ratingKey: String,
        val type: String,
        val title: String,
        val subtitle: String,
        val thumb: String?,
        val resume: Boolean,
    )

    private data class Shelf(val title: String, val items: List<PlexItem>)

    /** Result of loading the rows: `reachable` false = the server never answered. */
    private data class ShelfLoad(val shelves: List<Shelf>, val reachable: Boolean)

    private companion object {
        val http: OkHttpClient = OkHttpClient.Builder()
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
        val json = Json { ignoreUnknownKeys = true }

        /** App-scope row cache: key → (load, fetched-at). */
        val shelfCache = ConcurrentHashMap<String, Pair<ShelfLoad, Long>>()
        val machineIds = ConcurrentHashMap<String, String>()
        const val TTL_MS = 5 * 60 * 1000L

        // ~3.5 tiles across a 480px/220dpi panel.
        val TILE_W = 92.dp
        val POSTER_H = 132.dp
        /** Posters are drawn at ~127×182 px; ask the server for about that. */
        const val POSTER_PX = 184
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
        val feedback = LocalFeedback.current

        // machineIdentifier is needed to build the plex:// play id. Read it
        // from the server rather than pinning it in config.
        val machineId by produceState<String?>(
            initialValue = config.string("machine_id") ?: machineIds[host], host, token,
        ) {
            if (value == null) {
                value = get(url(host, token, "/identity"))?.mc()?.str("machineIdentifier")
                value?.let { machineIds[host] = it }
            }
        }

        // Rows are cached app-wide for a few minutes, so coming back to the
        // TV page shows posters on the first frame instead of "loading…".
        val key = "$host|$token|$limit|$rowSpecs"
        var load by remember(key) { mutableStateOf(shelfCache[key]?.first) }
        LaunchedEffect(key) {
            val cached = shelfCache[key]
            if (cached != null && System.currentTimeMillis() - cached.second < TTL_MS) return@LaunchedEffect
            var anyReached = false
            val shelves = rowSpecs.mapNotNull { spec ->
                val title = spec["title"] as? String ?: return@mapNotNull null
                val path = spec["path"] as? String ?: return@mapNotNull null
                val items = fetchItems(host, token, path, limit)
                if (items != null) anyReached = true
                if (items.isNullOrEmpty()) null else Shelf(title, items)
            }
            val result = ShelfLoad(shelves, reachable = anyReached || rowSpecs.isEmpty())
            if (result.reachable) shelfCache[key] = result to System.currentTimeMillis()
            load = result
        }

        // Which poster is starting (null = none). Starting cold takes several
        // seconds; a second tap meanwhile used to launch a second sequence.
        var startingKey by remember { mutableStateOf<String?>(null) }
        val scope = rememberCoroutineScope()

        fun play(item: PlexItem) {
            if (startingKey != null) {
                feedback.show("Already starting — give it a moment")
                return
            }
            val mid = machineId ?: run {
                feedback.error("Plex server unreachable")
                return
            }
            if (adbEntity == null) {
                // No ADB bridge configured: the session-only path.
                val id = "plex://$mid/${item.ratingKey}" + if (item.resume) "?resume=1" else ""
                ctx.client.callService(
                    ServiceCall.of(
                        "media_player", "play_media", playEntity,
                        "media_content_type" to item.type,
                        "media_content_id" to id,
                    )
                )
                feedback.show("Playing ${item.title}")
                return
            }
            startingKey = item.ratingKey
            scope.launch {
                try {
                    feedback.show("Starting ${item.title}…")
                    // ADB can't reach a sleeping TV: wake it over the
                    // androidtv_remote protocol first and wait for the bridge.
                    if (isAsleep(ctx.client.peek(adbEntity)?.state)) {
                        if (tvEntity != null) {
                            ctx.client.callService(
                                ServiceCall(domain = "media_player", service = "turn_on", entityId = tvEntity)
                            )
                            feedback.show("Waking the TV…")
                        }
                        val awake = withTimeoutOrNull(wakeTimeoutMs) {
                            while (isAsleep(ctx.client.peek(adbEntity)?.state)) delay(500)
                            true
                        }
                        if (awake == null) {
                            feedback.error("TV didn't wake — try again")
                            return@launch
                        }
                    }
                    feedback.show("Opening Plex…")
                    ctx.client.callService(
                        ServiceCall.of(
                            "androidtv", "adb_command", adbEntity,
                            "command" to deepLink(mid, item.ratingKey),
                        )
                    )
                    delay(4000)
                } finally {
                    startingKey = null
                }
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
            val current = load
            when {
                current == null -> {
                    SectionLabel("Plex")
                    Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                        repeat(4) {
                            Box(
                                Modifier
                                    .width(TILE_W)
                                    .height(POSTER_H)
                                    .clip(RoundedCornerShape(Radius.small))
                                    .background(AstrionTheme.raised),
                            )
                        }
                    }
                }
                !current.reachable -> StateLine("Plex server unreachable", StateKind.Danger)
                current.shelves.isEmpty() -> StateLine("Plex — nothing to show")
                else -> current.shelves.forEach { shelf ->
                    Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                        SectionLabel(shelf.title)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            items(shelf.items) { item ->
                                PosterTile(
                                    host, token, item,
                                    starting = startingKey == item.ratingKey,
                                ) { play(item) }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun PosterTile(host: String, token: String, item: PlexItem, starting: Boolean, onClick: () -> Unit) {
        val posterUrl = item.thumb?.let { thumbUrl(host, token, it) }
        val cacheKey = posterUrl?.let { ImageCache.remoteKey(it, POSTER_PX) }
        val bmp by rememberRemoteBitmap(cacheKey) {
            val u = posterUrl ?: return@rememberRemoteBitmap null
            withContext(Dispatchers.IO) {
                runCatching {
                    http.newCall(Request.Builder().url(u).build()).execute().use { r ->
                        r.body?.bytes()?.let { decodeSampledBytes(it, POSTER_PX) }
                    }
                }.getOrNull()
            }?.also { img -> cacheKey?.let { ImageCache.put(it, img) } }
        }
        val shape = RoundedCornerShape(Radius.small)

        Column(
            modifier = Modifier
                .width(TILE_W)
                .clip(RoundedCornerShape(Radius.small))
                .tap(onClickLabel = "Play ${item.title}", onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(Space.xs),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(POSTER_H)
                    .clip(shape)
                    .background(AstrionTheme.raised)
                    .then(if (starting) Modifier.border(2.dp, AstrionTheme.accent, shape) else Modifier),
            ) {
                bmp?.let {
                    Image(it, contentDescription = item.title, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                // Episode / year badge on the poster itself (was a 10sp line).
                if (item.subtitle.isNotBlank()) {
                    Text(
                        item.subtitle,
                        style = AstrionType.label,
                        color = AstrionTheme.textPrimary,
                        maxLines = 1,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(Space.xs)
                            .clip(RoundedCornerShape(Radius.small))
                            .background(AstrionTheme.pinnedTopBg)
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
                if (starting) {
                    Box(
                        Modifier
                            .align(Alignment.Center)
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(AstrionTheme.pinnedTopBg),
                        contentAlignment = Alignment.Center,
                    ) { PendingSpinner(size = 24.dp) }
                }
            }
            Text(
                item.title,
                style = AstrionType.label,
                color = AstrionTheme.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    // ---- Plex HTTP ----------------------------------------------------------

    private fun url(host: String, token: String, path: String): String {
        val sep = if ('?' in path) "&" else "?"
        return host + path + if (token.isBlank()) "" else "${sep}X-Plex-Token=$token"
    }

    /** Server-side scaled poster — keeps the decode tiny on a 1GB device. */
    private fun thumbUrl(host: String, token: String, thumb: String): String {
        val inner = URLEncoder.encode(thumb, "UTF-8")
        val q = "/photo/:/transcode?width=128&height=184&minSize=1&upscale=1&url=$inner"
        return url(host, token, q)
    }

    private suspend fun get(u: String): JsonObject? = withContext(Dispatchers.IO) {
        runCatching {
            http.newCall(Request.Builder().url(u).header("Accept", "application/json").build())
                .execute().use { r ->
                    if (!r.isSuccessful) null
                    else json.parseToJsonElement(r.body?.string() ?: return@use null) as? JsonObject
                }
        }.getOrNull()
    }

    private suspend fun fetchItems(host: String, token: String, path: String, limit: Int): List<PlexItem>? {
        val sep = if ('?' in path) "&" else "?"
        val p = "$path${sep}X-Plex-Container-Start=0&X-Plex-Container-Size=$limit"
        val meta = get(url(host, token, p))?.mc()?.get("Metadata") as? JsonArray ?: return null
        return meta.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            val key = o.str("ratingKey") ?: return@mapNotNull null
            val kind = o.str("type") ?: "video"
            val resume = (o.str("viewOffset")?.toLongOrNull() ?: 0L) > 0L
            if (kind == "episode") {
                val show = o.str("grandparentTitle") ?: o.str("title") ?: "?"
                val s = o.str("parentIndex")
                val ep = o.str("index")
                PlexItem(
                    ratingKey = key, type = kind, title = show,
                    subtitle = listOfNotNull(s?.let { "S$it" }, ep?.let { "E$it" })
                        .joinToString("")
                        .ifBlank { o.str("title").orEmpty() },
                    thumb = o.str("grandparentThumb") ?: o.str("thumb"),
                    resume = resume,
                )
            } else {
                PlexItem(
                    ratingKey = key, type = kind,
                    title = o.str("title") ?: "?",
                    subtitle = o.str("year") ?: kind.replaceFirstChar { it.uppercase() },
                    thumb = o.str("thumb"),
                    resume = resume,
                )
            }
        }
    }

    /**
     * The Plex Android/Android-TV app's VIEW intent. `server://` (two slashes
     * after the scheme, then the machine identifier) is the app's own
     * addressing for "this item, on this server" — it opens Plex, jumps
     * straight to the item and starts it at its stored resume point.
     */
    private fun deepLink(machineId: String, ratingKey: String): String =
        "am start -a android.intent.action.VIEW " +
            "-d 'plex://server://$machineId/com.plexapp.plugins.library/library/metadata/$ratingKey'"

    /** ADB entity states that mean "no shell to talk to". */
    private fun isAsleep(state: String?): Boolean =
        state == null || state == "off" || state == "unavailable" || state == "unknown"

    private fun JsonObject.mc(): JsonObject? = this["MediaContainer"] as? JsonObject
    private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.content
}
