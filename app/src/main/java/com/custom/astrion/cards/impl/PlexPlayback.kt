package com.custom.astrion.cards.impl

import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ArtCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * The Plex server and TV a card talks to, read from the same options on the
 * `plex` shelves and the `plex_browse` buttons, plus the one way of starting
 * something on the TV that works from cold (see PlexCard's notes): wake the
 * TV over androidtv_remote, wait for ADB, fire Plex's deep link.
 */
internal class PlexTarget(
    val host: String,
    val token: String,
    private val playEntity: String?,
    private val adbEntity: String?,
    private val tvEntity: String?,
    private val wakeTimeoutMs: Long,
) {
    companion object {
        fun from(config: CardConfig): PlexTarget? {
            val host = config.string("host")?.trimEnd('/') ?: return null
            return PlexTarget(
                host = host,
                token = config.string("token").orEmpty(),
                playEntity = config.string("play_entity"),
                adbEntity = config.string("adb_entity"),
                tvEntity = config.string("tv_entity"),
                wakeTimeoutMs = config.int("wake_timeout", 25).coerceIn(5, 60) * 1000L,
            )
        }

        const val PLEX_APP = "com.plexapp.android"
        /** How long to wait for the TV to show Plex after the intent. */
        const val OPEN_TIMEOUT_MS = 10_000L
    }

    fun url(path: String) = plexUrl(host, token, path)
    fun thumb(thumb: String, w: Int, h: Int) = plexThumbUrl(host, token, thumb, w, h)

    suspend fun machineId(): String? =
        PlexNet.get(url("/identity")).json?.let { (it["MediaContainer"] as? JsonObject)?.str("machineIdentifier") }

    /**
     * Start [item] on the TV, reporting progress through [status] (null
     * clears it). Returns true once Plex is in front on the TV.
     */
    suspend fun play(ctx: CardContext, machineId: String?, item: PlexItem, status: (String?) -> Unit): Boolean {
        val mid = machineId ?: run {
            status("Plex server unreachable")
            return false
        }
        if (adbEntity == null) {
            // No ADB bridge configured: the old session-only path.
            val entity = playEntity ?: return false
            val id = "plex://$mid/${item.ratingKey}" + if (item.resume) "?resume=1" else ""
            ctx.client.callService(
                ServiceCall.of("media_player", "play_media", entity, "media_content_type" to item.type, "media_content_id" to id)
            )
            return true
        }
        status("Starting ${item.title}…")
        // ADB can't reach a sleeping TV, so wake it over the androidtv_remote
        // protocol first and wait for the bridge.
        if (isAsleep(ctx.entity(adbEntity)?.state)) {
            if (tvEntity != null) {
                ctx.client.callService(ServiceCall(domain = "media_player", service = "turn_on", entityId = tvEntity))
                status("Waking the TV…")
            }
            val awake = withTimeoutOrNull(wakeTimeoutMs) {
                while (isAsleep(ctx.entity(adbEntity)?.state)) delay(500)
                true
            }
            if (awake == null) {
                status("TV didn't wake. Try again")
                return false
            }
        }
        status("Opening Plex…")
        // confirm: a failed ADB call shows as a toast, not silence.
        ctx.client.callService(
            ServiceCall.of("androidtv", "adb_command", adbEntity, "command" to plexDeepLink(mid, item.ratingKey)),
            confirm = true,
        )
        // The ADB entity reports the foreground app: wait for Plex.
        val opened = withTimeoutOrNull(OPEN_TIMEOUT_MS) {
            while (ctx.entity(adbEntity)?.attrString("app_id") != PLEX_APP) delay(500)
            true
        }
        val app = ctx.entity(adbEntity)?.attrString("app_id")
        status(
            when {
                opened == true -> "Playing on the TV"
                app != null -> "Plex didn't open on the TV (showing $app). Try again"
                else -> null
            }
        )
        return opened == true
    }

    /** ADB entity states that mean "no shell to talk to". */
    private fun isAsleep(state: String?): Boolean =
        state == null || state == "off" || state == "unavailable" || state == "unknown"
}

/** Plex HTTP: one client, JSON replies, posters through ArtCache. */
internal object PlexNet {
    val http: OkHttpClient = com.custom.astrion.ha.Http.base.newBuilder()
        .callTimeout(12, TimeUnit.SECONDS)
        .build()
    val json = Json { ignoreUnknownKeys = true }

    /** A Plex response, or why there isn't one ("HTTP 401", "unreachable", "not JSON"). */
    class Got(val json: JsonObject?, val error: String?)

    suspend fun get(u: String): Got = withContext(Dispatchers.IO) {
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

    /** Already server-scaled by [plexThumbUrl], so no downsampling. */
    suspend fun poster(url: String) = ArtCache.load(url) {
        runCatching {
            http.newCall(Request.Builder().url(url).build()).execute().use { r ->
                if (r.isSuccessful) r.body?.bytes() else null
            }
        }.getOrNull()
    }
}

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.content
