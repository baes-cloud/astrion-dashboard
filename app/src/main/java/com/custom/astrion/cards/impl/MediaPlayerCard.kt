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
 */
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
        val artPath = (if (tv != null) tv.attrString("entity_picture") else null)
            ?: e?.attrString("entity_picture")

        var art by remember(artPath) { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(artPath) { art = artPath?.let { ctx.client.fetchBitmap(it) } }

        val blurredBg = remember(art) {
            art?.let { img ->
                val src = img.asAndroidBitmap()
                if (src.width <= 0) return@let null
                val w = 32
                val h = (w * src.height / src.width).coerceAtLeast(1)
                Bitmap.createScaledBitmap(src, w, h, true).asImageBitmap()
            }
        }

        fun mp(service: String, vararg data: Pair<String, Any?>) {
            ctx.client.callService(ServiceCall.of("media_player", service, entityId, *data))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1B343D)),
        ) {
            // Blurred album art background + scrim for legibility.
            blurredBg?.let { bg ->
                Image(
                    bitmap = bg,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                )
                Box(modifier = Modifier.matchParentSize().background(Color(0xB30D1E24)))
            }

            if (full) {
                FullContent(
                    ctx, title, artist, playing, art, ::mp, topButtons, sourceEntity, live,
                    showControls = config.bool("show_controls", true),
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
                Text(title, color = Color(0xFFF1F4FA), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(artist, color = Color(0xFFB6BECC), fontSize = AstrionTheme.label,
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
                                .background(Color(0x662C4C58)) // semi-transparent
                                .tap(enabled = enabled) { fireService(ctx, b) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                b["name"] as? String ?: "",
                                color = Color(0xFFE6F0F1),
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
            val artMod = Modifier.fillMaxWidth().aspectRatio(1.2f).clip(RoundedCornerShape(16.dp))
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
                        tint = Color(0xFF44606C),
                        modifier = Modifier.size(56.dp),
                    )
                }
            }
            // Centered now-playing text.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, color = Color(0xFFF1F4FA), fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                Text(artist, color = Color(0xFFB6BECC), fontSize = 14.sp,
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
                    .background(Color(0x662C4C58))
                    .tap(enabled = sources.isNotEmpty()) { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    current ?: if (sources.isEmpty()) "No sources" else "Select source…",
                    color = Color(0xFFE6F0F1),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = if (sources.isEmpty()) Color(0xFF5A7783) else Color(0xFFCBDCE0),
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(Color(0xFF1E3841)).widthIn(max = 400.dp),
            ) {
                sources.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                s,
                                color = if (s == current) Color(0xFF6EA8FE) else Color(0xFFE6F0F1),
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
                .background(if (accent) AstrionTheme.accentStrong else Color(0x552C4C58))
                .tap(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = Color.White)
        }
    }
}
