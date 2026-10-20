package com.custom.astrion.cards.impl

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.net.URLEncoder

/** One poster on a Plex shelf. */
@Serializable
internal data class PlexItem(
    val ratingKey: String,
    val type: String,
    val title: String,
    val subtitle: String,
    val thumb: String?,
    val resume: Boolean,
)

@Serializable
internal data class PlexShelf(val title: String, val items: List<PlexItem>)

/** [host] + [path], with the token appended when there is one. */
internal fun plexUrl(host: String, token: String, path: String): String {
    val sep = if ('?' in path) "&" else "?"
    return host + path + if (token.isBlank()) "" else "${sep}X-Plex-Token=$token"
}

/**
 * A poster scaled by the server to the tile's own pixel size, so the remote
 * downloads and decodes no more than it draws. Plex versions [thumb] in its
 * path (`/library/metadata/17458/thumb/1792049769`), so the URL, and with it
 * the art cache key, only changes when the artwork does.
 */
internal fun plexThumbUrl(host: String, token: String, thumb: String, widthPx: Int, heightPx: Int): String {
    val inner = URLEncoder.encode(thumb, "UTF-8")
    return plexUrl(host, token, "/photo/:/transcode?width=$widthPx&height=$heightPx&minSize=1&upscale=1&url=$inner")
}

/** A shelf's items from a Plex response, or null if it has no `Metadata`. */
internal fun parsePlexItems(response: JsonObject): List<PlexItem>? {
    val meta = (response["MediaContainer"] as? JsonObject)?.get("Metadata") as? JsonArray ?: return null
    return meta.mapNotNull { el ->
        val o = el as? JsonObject ?: return@mapNotNull null
        val key = o.str("ratingKey") ?: return@mapNotNull null
        val kind = o.str("type") ?: "video"
        val resume = (o.str("viewOffset")?.toLongOrNull() ?: 0L) > 0L
        if (kind == "episode") {
            val s = o.str("parentIndex")
            val ep = o.str("index")
            PlexItem(
                ratingKey = key, type = kind,
                title = o.str("grandparentTitle") ?: o.str("title") ?: "?",
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
 * What to say when no shelf loaded: the actual reason, instead of a bare
 * "nothing to show" that looks the same for a typo, a dead server and an
 * empty library. [error] is the first failed request's reason, if any.
 */
internal fun plexEmptyMessage(host: String, error: String?): String = when {
    "YOUR_" in host || host.isBlank() -> "Plex: set plex.host in dashboard.json"
    error != null -> "Plex: can't load from ${host.substringAfter("://")} ($error)"
    else -> "Plex: nothing to show yet"
}

/**
 * The Plex Android TV app's VIEW intent: opens Plex over whatever is on
 * screen, jumps to the item on this server and starts it at its resume point.
 *
 * Prefixed with a wake and CEC One Touch Play, so the TV also switches to the
 * Streamer's input. Without it Plex happily started on the Streamer while the
 * TV stayed on another input (the PC), which looked like nothing happened.
 * One Touch Play times out while the Streamer is asleep, hence the wake and
 * the pause before it (same as the remote's Home button).
 */
internal fun plexDeepLink(machineId: String, ratingKey: String): String =
    "input keyevent KEYCODE_WAKEUP; sleep 1; cmd hdmi_control onetouchplay; " +
        "am start -a android.intent.action.VIEW " +
        "-d 'plex://server://$machineId/com.plexapp.plugins.library/library/metadata/$ratingKey'"

private fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.content
