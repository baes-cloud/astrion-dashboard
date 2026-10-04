package com.custom.astrion.cards.impl

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ArtCache
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.tap

/**
 * Media player card with two layouts:
 *  - "compact" (default): a single row — round album art, title/artist, and
 *    prev / play-pause / next / vol- / vol+ controls. Blurred art background.
 *  - "full": a big square album art on top, title/artist, then transport and
 *    volume rows. For the dedicated Media page.
 *
 * Album art is loaded from the entity's `entity_picture` (auth'd fetch). Since
 * Modifier.blur is a no-op on this API 26 device, the blurred background is a
 * heavily downscaled copy upscaled to fill the card.
 *
 * Config:
 *   { "type": "media_player", "options": { "entity_id": "media_player.club",
 *       "variant": "full" } }   // omit variant for compact
 *
 * "tv" variant (the TV page): wide hero artwork — the poster, or a
 * `placeholder` image file when nothing is showing — with the title and an
 * on/idle status over a gradient, a plain control row (mute / prev / play /
 * next / volume, sent to `volume_entity` for the volume pair), and an `apps`
 * row of launcher tiles inside the same card:
 *   "apps": [ { "name": "Plex", "badge": ">", "color": "#E5A00D",
 *               "service": "media_player.select_source", "entity_id": "…",
 *               "data": { "source": "com.plexapp.android" } },
 *             { "name": "iview", "wordmark": true, "color": "#2BC4B6", … },
 *             { "name": "Netflix", "badge": "N", "color": "#E50914", "dim": true, … } ]
 * An app with an `icon` (PNG path) shows just that logo, no label.
 */
/** HA media_player feature bits a transport button needs (PREVIOUS 16, NEXT 32, PAUSE 1 | PLAY 16384). */
private val TRANSPORT_FEATURES = mapOf(
    "media_previous_track" to 16,
    "media_next_track" to 32,
    "media_play_pause" to (1 or 16384),
)

class MediaPlayerCard : CardRenderer {
    override val type = "media_player"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val full = config.string("variant") == "full"
        val topButtons = (config.options["top_buttons"] as? List<Map<String, Any?>>) ?: emptyList()
        val sourceEntity = config.string("source_entity")
        val e = ctx.entities[entityId]
        val playing = e?.state == "playing"
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        // When the speaker is just carrying the TV feed its own metadata is
        // useless — media_title is literally "TV" / "TV Audio". If a tv_entity
        // is configured, borrow the show/film title and poster from the TV's
        // own player instead.
        // Several entities can describe the same TV session and only some carry
        // the title (the Plex client entity has it; the Android-TV one doesn't),
        // so take a list and prefer whichever actually has a title right now.
        val tvEntities = config.stringList("tv_entities")
            .ifEmpty { listOfNotNull(config.string("tv_entity")) }
        val onTvSource = e?.attrString("source") == "TV" ||
            e?.attrString("media_title") in listOf("TV", "TV Audio")
        val tv = if (!onTvSource) null else tvEntities
            .mapNotNull { ctx.entities[it] }
            .filterNot { it.isUnavailable }
            .let { candidates ->
                candidates.firstOrNull { !it.attrString("media_title").isNullOrBlank() }
                    ?: candidates.firstOrNull { it.state == "playing" }
            }
        val tvTitle = tv?.attrString("media_title")?.takeIf { it.isNotBlank() }

        val title = tvTitle
            ?: e?.attrString("media_title")?.takeIf { it.isNotBlank() }
            ?: e?.friendlyName ?: entityId
        val artist = if (tv != null) {
            // For a show: the series name under the episode title.
            tv.attrString("media_series_title")
                ?: tv.attrString("app_name")
                ?: e?.attrString("source")
                ?: "—"
        } else {
            e?.attrString("media_artist")
                ?: e?.attrString("media_series_title")
                ?: e?.attrString("app_name")
                ?: "—"
        }
        // tv variant: borrow the poster and title from whichever of
        // `art_entities` is actually showing something (the Plex client has
        // the real poster; the cast entity only has the app's icon).
        val watching = if (config.string("variant") != "tv") null else config.stringList("art_entities")
            .mapNotNull { ctx.entities[it] }
            .firstOrNull {
                !it.isUnavailable && it.state in listOf("playing", "paused") &&
                    !it.attrString("entity_picture").isNullOrBlank()
            }
        val artPath = watching?.attrString("entity_picture")
            ?: (if (tv != null) tv.attrString("entity_picture") else null)
            ?: e?.attrString("entity_picture")

        // Through ArtCache: the screensaver shows the same art, and a poster
        // decoded at full size can be ~6 MB. 480px covers the panel's width.
        var art by remember(artPath) { mutableStateOf(artPath?.let { ArtCache.peek(it) }) }
        LaunchedEffect(artPath) {
            art = artPath?.let { p -> ArtCache.load(p, 480) { ctx.client.fetchBytes(p) } }
        }

        val blurredBg = remember(art) {
            art?.let { img ->
                val src = img.asAndroidBitmap()
                if (src.width <= 0) return@let null
                val w = 32
                val h = (w * src.height / src.width).coerceAtLeast(1)
                Bitmap.createScaledBitmap(src, w, h, true).asImageBitmap()
            }
        }

        // tv variant: each transport button goes to a player that can
        // actually do it. The Cast entity rejects next/previous for a native
        // app like Plex ("does not support action media_player.media_next_
        // track"), and HA's Plex client entity for the Google TV supports no
        // transport at all; the ADB entity sends media keys to whatever app is
        // in front. Among `art_entities` (then the card's own entity), a
        // playing/paused player that supports the action wins, then any that
        // is on and supports it.
        fun transportTarget(service: String): String {
            if (config.string("variant") != "tv") return entityId
            val feature = TRANSPORT_FEATURES[service] ?: return entityId
            val able = (config.stringList("art_entities") + entityId).distinct()
                .mapNotNull { ctx.entities[it] }
                .filter { !it.isUnavailable && ((it.attrInt("supported_features") ?: 0) and feature) != 0 }
            return (able.firstOrNull { it.state == "playing" || it.state == "paused" }
                ?: able.firstOrNull { it.state != "off" })?.entityId ?: entityId
        }

        fun mp(service: String, vararg data: Pair<String, Any?>) {
            ctx.client.callService(ServiceCall.of("media_player", service, transportTarget(service), *data))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF243140)),
        ) {
            // Blurred album art background + scrim for legibility.
            blurredBg?.let { bg ->
                Image(
                    bitmap = bg,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(modifier = Modifier.matchParentSize().background(Color(0xB3151D25)))
            }

            if (config.string("variant") == "tv") {
                TvContent(
                    ctx, entityId, e, watching?.attrString("media_title")?.takeIf { it.isNotBlank() } ?: title,
                    playing || watching?.state == "playing", art, ::mp, live,
                    showing = watching?.attrString("media_series_title") ?: watching?.attrString("app_name"),
                    placeholder = config.string("placeholder"),
                    volumeEntity = config.string("volume_entity") ?: entityId,
                    apps = (config.options["apps"] as? List<Map<String, Any?>>) ?: emptyList(),
                )
            } else if (full) {
                FullContent(
                    ctx, title, artist, playing, art, ::mp, topButtons, sourceEntity, live,
                    showControls = config.bool("show_controls", true),
                    // Width / height of the artwork; wider leaves room below
                    // the card for the favourites row on one screen.
                    artAspect = (config.options["art_aspect"] as? Number)?.toFloat() ?: 1.2f,
                )
            } else {
                CompactContent(title, artist, playing, art, ::mp, live)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fireService(ctx: CardContext, b: Map<String, Any?>) {
        val service = b["service"] as? String ?: return
        val domain = service.substringBefore('.')
        val svc = service.substringAfter('.')
        val entityId = b["entity_id"] as? String
        val data = (b["data"] as? Map<String, Any?>).orEmpty()
        ctx.client.callService(
            ServiceCall.of(domain, svc, entityId, *data.entries.map { it.key to it.value }.toTypedArray())
        )
    }

    // ---- tv (TV page): hero art + status, plain controls, app tiles ----------
    @Composable
    private fun TvContent(
        ctx: CardContext,
        entityId: String,
        e: com.custom.astrion.ha.EntityState?,
        title: String,
        playing: Boolean,
        art: ImageBitmap?,
        mp: (String, Array<out Pair<String, Any?>>) -> Unit,
        enabled: Boolean,
        showing: String?,
        placeholder: String?,
        volumeEntity: String,
        apps: List<Map<String, Any?>>,
    ) {
        val placeholderArt by com.custom.astrion.ui.rememberSampledBitmap(placeholder, targetPx = 720)
        val hero = art ?: placeholderArt
        val state = e?.state ?: "unavailable"
        val on = state !in listOf("off", "unavailable", "unknown", "standby")
        val app = showing?.takeIf { it.isNotBlank() } ?: e?.attrString("app_name")?.takeIf { it.isNotBlank() }
        val status = when (state) {
            "playing" -> listOfNotNull("Playing", app).joinToString(" • ")
            "paused" -> listOfNotNull("Paused", app).joinToString(" • ")
            "off", "standby" -> "Off"
            "unavailable", "unknown" -> "Unavailable"
            else -> listOfNotNull("On", app ?: "Idle").joinToString(" • ")
        }
        val muted = ctx.entities[volumeEntity]?.attributes?.get("is_volume_muted")
            ?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content == "true" } ?: false
        fun vol(service: String, vararg data: Pair<String, Any?>) {
            ctx.client.callService(ServiceCall.of("media_player", service, volumeEntity, *data))
        }

        Column(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // App launchers across the top: slim, logo-only.
            if (apps.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    apps.forEach { a ->
                        AppTile(a, Modifier.weight(1f), ctx.connected) { fireService(ctx, a) }
                    }
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1.6f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(AstrionTheme.raised),
            ) {
                if (hero != null) {
                    Image(hero, null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(
                        Icons.Filled.Movie, contentDescription = null, tint = Color(0xFF506763),
                        modifier = Modifier.size(56.dp).align(Alignment.Center),
                    )
                }
                // Legibility scrim for the overlaid title.
                Box(
                    Modifier
                        .matchParentSize()
                        .background(
                            androidx.compose.ui.graphics.Brush.verticalGradient(
                                0.45f to Color.Transparent,
                                1f to Color(0xE6121920),
                            )
                        )
                )
                Column(Modifier.align(Alignment.BottomStart).padding(horizontal = 14.dp, vertical = 12.dp)) {
                    Text(
                        title, color = Color.White, fontFamily = AstrionTheme.headingFont, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier.size(7.dp).clip(CircleShape)
                                .background(if (on) AstrionTheme.good else Color(0xFF748884))
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(status, color = Color(0xFFCFDBD6), fontSize = 13.sp, maxLines = 1)
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PlainControl(if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeMute, "Mute", enabled) {
                    vol("volume_mute", "is_volume_muted" to !muted)
                }
                PlainControl(Icons.Filled.SkipPrevious, "Previous", enabled) {
                    mp("media_previous_track", emptyArray())
                }
                CircleControl(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, 60.dp,
                    description = if (playing) "Pause" else "Play",
                    accent = true, enabled = enabled,
                ) { mp("media_play_pause", emptyArray()) }
                PlainControl(Icons.Filled.SkipNext, "Next", enabled) {
                    mp("media_next_track", emptyArray())
                }
                PlainControl(Icons.Filled.VolumeUp, "Volume up", enabled) { vol("volume_up") }
            }

        }
    }

    @Composable
    private fun PlainControl(icon: ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .tap(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = Color(0xFFB0C2BB), modifier = Modifier.size(26.dp))
        }
    }

    /** Launcher tile: a tinted letter badge over a small-caps name, or a coloured wordmark. */
    @Composable
    private fun AppTile(a: Map<String, Any?>, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
        val name = a["name"] as? String ?: ""
        val color = (a["color"] as? String)?.let { hex ->
            val h = hex.removePrefix("#")
            h.toLongOrNull(16)?.let { v -> if (h.length <= 6) Color(0xFF000000L or v) else Color(v) }
        } ?: Color(0xFF98B5B0)
        val dim = a["dim"] as? Boolean ?: false
        val badge = a["badge"] as? String
        val hasIcon = a["icon"] is String
        Column(
            modifier = modifier
                .height(if (hasIcon) 44.dp else 76.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0x55161F28))
                .tap(enabled = enabled, onClick = onClick)
                .padding(if (hasIcon) 3.dp else 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val alpha = if (dim) 0.5f else 1f
            val logo by com.custom.astrion.ui.rememberSampledBitmap(a["icon"] as? String, targetPx = 128)
            if (logo != null) {
                // Logo only — no label.
                Image(
                    logo!!, contentDescription = name, alpha = alpha,
                    modifier = Modifier.size(28.dp),
                )
            } else if (badge != null && a["wordmark"] != true) {
                Box(
                    modifier = Modifier
                        .size(30.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(color.copy(alpha = 0.22f * alpha)),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(badge, color = color.copy(alpha = alpha), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    name.uppercase(), color = Color(0xFFA6B8B1).copy(alpha = alpha), fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, maxLines = 1,
                )
            } else {
                Text(name, color = color.copy(alpha = alpha), fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }

    // ---- compact (main page): one row, transport + volume ---------------------
    @Composable
    private fun CompactContent(
        title: String,
        artist: String,
        playing: Boolean,
        art: ImageBitmap?,
        mp: (String, Array<out Pair<String, Any?>>) -> Unit,
        enabled: Boolean,
    ) {
        // NOTE: the row itself is deliberately NOT clickable. It used to be a
        // full-width invisible play/pause toggle sitting directly above the
        // page indicator — reaching for navigation and landing slightly high
        // paused the music, and with no play state on screen there was nothing
        // to tell you it had happened.
        // Trimmed from 64dp to 54dp: on Main this row sits under a `pin: fill`
        // floorplan, so its height comes straight out of the plan. The play
        // button only gives up 2dp and the volume pair none at all — they are
        // already at 40dp, and shrinking a touch target on a wall panel to buy
        // whitespace is the wrong trade.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Album art carries play state as a ring, so "is it playing" is
            // answerable without reading anything.
            val artMod = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .then(
                    if (playing) Modifier.border(2.dp, AstrionTheme.good, CircleShape)
                    else Modifier
                )
            if (art != null) {
                Image(art, null, modifier = artMod, contentScale = ContentScale.Crop)
            } else {
                Box(artMod.background(Color(0xFF3A2E5A)))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = Color(0xFFEEF2EF), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(artist, color = Color(0xFFBCC6C1), fontSize = AstrionTheme.label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CircleControl(
                Icons.Filled.VolumeDown, 40.dp,
                description = "Volume down", enabled = enabled,
            ) { mp("volume_down", emptyArray()) }
            CircleControl(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, 42.dp,
                description = if (playing) "Pause" else "Play",
                accent = true, enabled = enabled,
            ) { mp("media_play_pause", emptyArray()) }
            CircleControl(
                Icons.Filled.VolumeUp, 40.dp,
                description = "Volume up", enabled = enabled,
            ) { mp("volume_up", emptyArray()) }
        }
    }

    // ---- full (media page) --------------------------------------------------
    @Composable
    private fun FullContent(
        ctx: CardContext,
        title: String,
        artist: String,
        playing: Boolean,
        art: ImageBitmap?,
        mp: (String, Array<out Pair<String, Any?>>) -> Unit,
        topButtons: List<Map<String, Any?>>,
        sourceEntity: String?,
        enabled: Boolean,
        showControls: Boolean,
        artAspect: Float,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Compact source dropdown, right at the top — kept to one slim
            // row (tight margins) so it doesn't push the shortcut row below
            // this card off the first screen.
            if (sourceEntity != null) {
                CompactSourceSelect(ctx, sourceEntity)
            }
            // Top action buttons (e.g. Group / Ungroup).
            if (topButtons.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    topButtons.forEach { b ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0x663A4F57)) // semi-transparent
                                .tap(enabled = enabled) { fireService(ctx, b) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                b["name"] as? String ?: "",
                                color = Color(0xFFEEF2EF),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }
            // Big album art, or a placeholder glyph when nothing is playing /
            // the poster can't be fetched — otherwise the card is a large
            // empty rectangle that reads as broken rather than idle.
            val artMod = Modifier.fillMaxWidth().aspectRatio(artAspect).clip(RoundedCornerShape(16.dp))
            if (art != null) {
                Image(art, null, modifier = artMod, contentScale = ContentScale.Crop)
            } else {
                Box(
                    artMod.background(AstrionTheme.raised),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Movie,
                        contentDescription = "Nothing playing",
                        tint = Color(0xFF506763),
                        modifier = Modifier.size(56.dp),
                    )
                }
            }
            // Centered now-playing text.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, color = Color(0xFFEEF2EF), fontFamily = AstrionTheme.headingFont, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                Text(artist, color = Color(0xFFBCC6C1), fontSize = 14.sp,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
            }
            // Single control row: vol- prev play/pause next vol+, justified.
            // Suppressed on a display-only card (e.g. the TV page, which just
            // shows what's on screen).
            if (showControls) Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleControl(Icons.Filled.VolumeDown, 48.dp, "Volume down", enabled = enabled) {
                    mp("volume_down", emptyArray())
                }
                CircleControl(Icons.Filled.SkipPrevious, 52.dp, "Previous track", enabled = enabled) {
                    mp("media_previous_track", emptyArray())
                }
                CircleControl(
                    if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, 68.dp,
                    description = if (playing) "Pause" else "Play",
                    accent = true, enabled = enabled,
                ) {
                    mp("media_play_pause", emptyArray())
                }
                CircleControl(Icons.Filled.SkipNext, 52.dp, "Next track", enabled = enabled) {
                    mp("media_next_track", emptyArray())
                }
                CircleControl(Icons.Filled.VolumeUp, 48.dp, "Volume up", enabled = enabled) {
                    mp("volume_up", emptyArray())
                }
            }
        }
    }

    /**
     * A single slim row showing the entity's current source; tap opens a
     * dropdown of its live `source_list`. Deliberately tighter than the
     * standalone `source_select` card (less vertical padding, no stacked
     * label) so embedding it above the rest of the full player doesn't cost
     * much height.
     */
    @Composable
    private fun CompactSourceSelect(ctx: CardContext, entityId: String) {
        val e = ctx.entities[entityId]
        val sources = e?.attrStringList("source_list") ?: emptyList()
        val current = e?.attrString("source")
        var expanded by remember { mutableStateOf(false) }

        Box(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0x663A4F57))
                    .tap(enabled = sources.isNotEmpty()) { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    current ?: if (sources.isEmpty()) "No sources" else "Select source…",
                    color = Color(0xFFEEF2EF),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = if (sources.isEmpty()) Color(0xFF718583) else Color(0xFFD9E3E0),
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Color(0xFF283646)).widthIn(max = 400.dp),
            ) {
                sources.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                s,
                                color = if (s == current) Color(0xFF8CBDB5) else Color(0xFFEEF2EF),
                                fontSize = 14.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            ctx.client.callService(
                                ServiceCall.of("media_player", "select_source", entityId, "source" to s)
                            )
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun CircleControl(
        icon: ImageVector,
        size: androidx.compose.ui.unit.Dp,
        description: String? = null,
        accent: Boolean = false,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (accent) AstrionTheme.accentStrong else Color(0x553A4F57))
                .tap(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = Color.White)
        }
    }
}
