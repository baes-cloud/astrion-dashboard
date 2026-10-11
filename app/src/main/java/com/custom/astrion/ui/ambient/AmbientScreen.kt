package com.custom.astrion.ui.ambient

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.custom.astrion.config.AmbientOptions
import com.custom.astrion.ha.EntityMap
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ui.Screensaver
import com.custom.astrion.ui.ambient.scene.AmbientScene
import com.custom.astrion.ui.ambient.scene.SceneBuilder
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** How often the ambient screen samples HA (matches the screensaver's cadence). */
private const val SAMPLE_MS = 1_000L

private val PLACEHOLDER_TITLES = setOf("TV", "TV Audio")

private fun EntityState.trackKey(): String =
    listOf(entityId, attrString("media_content_id") ?: attrString("media_title") ?: "", attrString("media_artist") ?: "")
        .joinToString("|")

/**
 * The ambient idle face.
 *
 * The idea comes from Klangbild (https://github.com/hazymorning/Klangbild) by
 * hazymorning, a Music Assistant now-playing screensaver for Android TV. This
 * is an independent reimplementation of its ideas for the HA100 — no Klangbild
 * code was copied.
 *
 * Reuses MainActivity's idle timer, backlight, key
 * routing and touch-to-dismiss (it is hosted exactly like the screensaver); its
 * own job is only to decide and draw now-playing vs the rest clock, and to bake
 * each track's scene off the main thread.
 *
 * When `restClock` is off and nothing is playing it stands aside by rendering
 * the app's existing rich [Screensaver] face, so idle is never blank.
 */
@Composable
fun AmbientScreen(
    cfg: AmbientOptions,
    screensaverOptions: Map<String, Any?>,
    entities: () -> EntityMap,
    client: HaClient,
    connected: Boolean,
    batteryPct: Int?,
    charging: Boolean,
    is24: Boolean,
) {
    val machine = remember(cfg) { AmbientMachine(cfg.mediaPlayers, cfg.restClock) }
    val flip = remember(cfg) { FlipGuard() }

    var ui by remember { mutableStateOf<AmbientUi>(AmbientUi.Dormant) }
    var player by remember { mutableStateOf<EntityState?>(null) }
    var displayedKey by remember { mutableStateOf<String?>(null) }

    // Status flash: a volume/mute change shows for 3.5 s in the status line.
    var flashText by remember { mutableStateOf<String?>(null) }
    var flashUntil by remember { mutableLongStateOf(0L) }
    val currentEntities by rememberUpdatedState(entities)

    LaunchedEffect(cfg) {
        var lastVol: Double? = null
        var lastMuted: Boolean? = null
        while (true) {
            val now = System.currentTimeMillis()
            val ents = currentEntities()
            val next = machine.tick(ents, now)
            ui = next
            val p = (next as? AmbientUi.NowPlaying)?.let { ents[it.playerId] }
            player = p

            // Volume / mute flash.
            if (p != null) {
                val vol = p.attrDouble("volume_level")
                val muted = p.attrString("is_volume_muted")?.toBooleanStrictOrNull()
                if (lastVol != null && vol != null && vol != lastVol) {
                    flashText = "Volume ${Math.round(vol * 100)} %"; flashUntil = now + 3_500
                } else if (lastMuted != null && muted != null && muted != lastMuted) {
                    flashText = if (muted) "Muted" else "Unmuted"; flashUntil = now + 3_500
                }
                lastVol = vol; lastMuted = muted
            }

            val rawKey = if (next is AmbientUi.NowPlaying) p?.trackKey() else null
            displayedKey = flip.update(rawKey, now)
            delay(SAMPLE_MS - now % SAMPLE_MS)
        }
    }

    // Bake a scene whenever the displayed track changes. Previous scene is held
    // by Crossfade during the swap; nothing palette/blur runs per frame.
    var scene by remember { mutableStateOf<AmbientScene?>(null) }
    val currentPlayer by rememberUpdatedState(player)
    LaunchedEffect(displayedKey) {
        val key = displayedKey
        val p = currentPlayer
        if (key == null || p == null || p.trackKey() != key) return@LaunchedEffect
        val art = p.attrString("entity_picture")?.let { client.fetchBytes(it) }
        scene = SceneBuilder.build(key, art)
    }

    Box(Modifier.fillMaxSize().background(Color(0xFF070809))) {
        // One crossfade target: the now-playing track key, "rest", or "off".
        val target: String = when (ui) {
            is AmbientUi.NowPlaying -> displayedKey ?: "np"
            AmbientUi.Rest -> "rest"
            AmbientUi.Dormant -> "off"
        }
        Crossfade(targetState = target, animationSpec = tween(800), label = "ambient") { t ->
            when {
                t == "rest" -> RestClock(is24)
                t == "off" -> Screensaver(
                    options = screensaverOptions,
                    entities = entities,
                    client = client,
                    connected = connected,
                    batteryPct = batteryPct,
                    charging = charging,
                )
                else -> {
                    val s = scene
                    val p = player
                    if (s != null && p != null && p.trackKey() == t) {
                        val paused = (ui as? AmbientUi.NowPlaying)?.paused == true
                        val album = p.attrString("media_album_name")
                            ?.takeIf { cfg.showAlbum && it.isNotBlank() && it != p.attrString("media_title") }
                        NowPlayingAmbient(
                            scene = s,
                            title = p.attrString("media_title")
                                ?.takeIf { it !in PLACEHOLDER_TITLES } ?: p.friendlyName,
                            artist = p.attrString("media_artist") ?: p.attrString("app_name"),
                            album = album,
                            statusLine = statusLine(p, flashText, flashUntil, paused),
                            durationSec = p.attrDouble("media_duration") ?: 0.0,
                            positionSec = p.attrDouble("media_position") ?: 0.0,
                            reportedAtMs = p.attrString("media_position_updated_at")
                                ?.let { com.custom.astrion.ui.parseIsoMs(it) },
                            playing = !paused,
                            paused = paused,
                        )
                    } else {
                        // Scene still baking: hold the dark ground, no flash of content.
                        Box(Modifier.fillMaxSize().background(Color(0xFF070809)))
                    }
                }
            }
        }
    }
}

private fun statusLine(p: EntityState, flashText: String?, flashUntil: Long, paused: Boolean): String {
    if (flashText != null && System.currentTimeMillis() < flashUntil) return flashText
    val muted = p.attrString("is_volume_muted")?.toBooleanStrictOrNull() == true
    val time = SimpleDateFormat("h:mm", Locale.getDefault()).format(Date())
    return when {
        paused -> "$time · Paused"
        muted -> "$time · Muted"
        else -> "$time · ${p.friendlyName}"
    }
}
