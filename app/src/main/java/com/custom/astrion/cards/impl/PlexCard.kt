package com.custom.astrion.cards.impl

import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
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

    private companion object {
        val http: OkHttpClient = OkHttpClient.Builder()
            .callTimeout(12, TimeUnit.SECONDS)
            .build()
        val json = Json { ignoreUnknownKeys = true }

        // Small LRU so swiping back and forth doesn't refetch posters.
        val posterCache = object : LinkedHashMap<String, ImageBitmap>(0, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ImageBitmap>?) = size > 48
        }

        @Synchronized fun cacheGet(k: String): ImageBitmap? = posterCache[k]
        @Synchronized fun cachePut(k: String, v: ImageBitmap) { posterCache[k] = v }

        // ~3.5 tiles across a 480px/220dpi panel.
        val TILE_W = 92.dp
        val POSTER_H = 132.dp
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
                value = get(url(host, token, "/identity"))?.mc()?.str("machineIdentifier")
            }
        }

        val shelves by produceState<List<Shelf>?>(initialValue = null, host, token, limit) {
            value = rowSpecs.mapNotNull { spec ->
                val title = spec["title"] as? String ?: return@mapNotNull null
                val path = spec["path"] as? String ?: return@mapNotNull null
                val items = fetchItems(host, token, path, limit)
                if (items.isNullOrEmpty()) null else Shelf(title, items)
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
                ctx.client.callService(
                    ServiceCall.of(
                        "androidtv", "adb_command", adbEntity,
                        "command" to deepLink(mid, item.ratingKey),
                    )
                )
                delay(4000)
                status = null
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            status?.let { ShelfLabel(it) }
            when {
                shelves == null -> ShelfLabel("Plex — loading…")
                shelves!!.isEmpty() -> ShelfLabel("Plex — nothing to show")
                else -> shelves!!.forEach { shelf ->
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        ShelfLabel(shelf.title)
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(shelf.items) { item ->
                                PosterTile(host, token, item) { play(item) }
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
            color = Color(0xFF9FBAC0),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
    }

    @Composable
    private fun PosterTile(host: String, token: String, item: PlexItem, onClick: () -> Unit) {
        val posterUrl = item.thumb?.let { thumbUrl(host, token, it) }
        var bmp by remember(posterUrl) { mutableStateOf(posterUrl?.let { cacheGet(it) }) }
        LaunchedEffect(posterUrl) {
            if (bmp == null && posterUrl != null) {
                val loaded = withContext(Dispatchers.IO) {
                    runCatching {
                        http.newCall(Request.Builder().url(posterUrl).build()).execute().use { r ->
                            r.body?.bytes()?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                    }.getOrNull()?.asImageBitmap()
                }
                if (loaded != null) { cachePut(posterUrl, loaded); bmp = loaded }
            }
        }

        Column(
            modifier = Modifier
                .width(TILE_W)
                .tap(onClick = onClick),
            verticalArrangement = Arrangement.spacedBy(3.dp),
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
            Text(
                item.title,
                color = AstrionTheme.textPrimary,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (item.subtitle.isNotBlank()) {
                Text(
                    item.subtitle,
                    color = AstrionTheme.textSecondary,
                    fontSize = 10.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
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
        val q = "/photo/:/transcode?width=200&height=300&minSize=1&upscale=1&url=$inner"
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
