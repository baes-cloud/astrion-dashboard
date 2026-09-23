package com.custom.astrion.cards.impl

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ActionHandle
import com.custom.astrion.ui.AstrionButton
import com.custom.astrion.ui.AstrionSheet
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.IconAction
import com.custom.astrion.ui.ImageCache
import com.custom.astrion.ui.LocalOverlay
import com.custom.astrion.ui.OptionList
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.StateKind
import com.custom.astrion.ui.StateLine
import com.custom.astrion.ui.Tone
import com.custom.astrion.ui.Touch
import com.custom.astrion.ui.UnavailableBadge
import com.custom.astrion.ui.rememberAction
import com.custom.astrion.ui.rememberOptimistic
import com.custom.astrion.ui.rememberRemoteBitmap
import com.custom.astrion.ui.tap

/**
 * Media player card, two layouts:
 *  - "compact" (default): art (green ring while playing), title / artist,
 *    vol− / play-pause / vol+.
 *  - "full": optional source picker, top buttons, big art, title / artist,
 *    transport row. `show_controls: false` makes it display-only (the TV
 *    page), and a display-only card with nothing on collapses to one line
 *    ("Nothing playing on the TV") so the poster rows start on screen one.
 *
 * Play/pause is optimistic; every control spins if HA is slow and outlines
 * red if refused. Art is fetched at the size it's drawn and cached; the
 * blurred backdrop is a 32px copy darkened with a Multiply tint (no alpha
 * scrim layer — Modifier.blur is a no-op on API 26 anyway).
 *
 * Config: entity_id, variant ("full"), show_controls, source_entity,
 * top_buttons [{name, service, entity_id, data}], tv_entity / tv_entities.
 */
class MediaPlayerCard : CardRenderer {
    override val type = "media_player"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val full = config.string("variant") == "full"
        val showControls = config.bool("show_controls", true)
        val topButtons = (config.options["top_buttons"] as? List<Map<String, Any?>>) ?: emptyList()
        val sourceEntity = config.string("source_entity")
        val e = ctx.entity(entityId)
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        val actualPlaying = e?.state == "playing"

        val tvEntities = config.stringList("tv_entities").ifEmpty { listOfNotNull(config.string("tv_entity")) }
        val tv = borrowTvEntity(e, tvEntities) { ctx.entity(it) }
        val tvTitle = tv?.attrString("media_title")?.takeIf { it.isNotBlank() }
        val mediaTitle = tvTitle ?: e?.attrString("media_title")?.takeIf { it.isNotBlank() }
        val title = mediaTitle ?: e?.friendlyName ?: entityId
        val artist = if (tv != null) {
            tv.attrString("media_series_title") ?: tv.attrString("app_name") ?: e?.attrString("source")
        } else {
            e?.attrString("media_artist") ?: e?.attrString("media_series_title") ?: e?.attrString("app_name")
        }
        val artPath = (if (tv != null) tv.attrString("entity_picture") else null) ?: e?.attrString("entity_picture")

        // Display-only and nothing on: one line, not a 470dp placeholder.
        val idle = unavailable || e?.state in listOf("off", "idle", "standby") || mediaTitle == null
        if (full && !showControls && idle) {
            IdleLine(if (unavailable) null else "Nothing playing on the TV")
            return
        }

        val artPx = if (full) 480 else 128
        val artKey = artPath?.let { ImageCache.remoteKey(ctx.client.authedUrl(it), artPx) }
        val art by rememberRemoteBitmap(artKey) { artPath?.let { ctx.client.fetchBitmap(it, artPx) } }
        val backdrop = remember(art) {
            art?.let { img ->
                val src = img.asAndroidBitmap()
                if (src.width <= 0) return@let null
                val w = 32
                val h = (w * src.height / src.width).coerceAtLeast(1)
                Bitmap.createScaledBitmap(src, w, h, true).asImageBitmap()
            }
        }

        val playOpt = rememberOptimistic(actualPlaying)
        val playing = playOpt.show(actualPlaying)
        val transport = rememberAction(ctx)
        val volume = rememberAction(ctx)

        fun mp(handle: ActionHandle, service: String) {
            handle.run(ServiceCall("media_player", service, entityId))
        }
        fun playPause() {
            playOpt.set(!playing)
            transport.run(ServiceCall("media_player", "media_play_pause", entityId), onFail = { playOpt.clear() })
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(Radius.card))
                .background(AstrionTheme.cardBg),
        ) {
            backdrop?.let { bg ->
                Image(
                    bitmap = bg,
                    contentDescription = null,
                    modifier = Modifier.matchParentSize(),
                    contentScale = ContentScale.Crop,
                    colorFilter = ColorFilter.tint(AstrionTheme.artBackdropTint, BlendMode.Multiply),
                )
            }
            if (full) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(Space.l),
                    verticalArrangement = Arrangement.spacedBy(Space.m),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    if (sourceEntity != null) SourceRow(ctx, sourceEntity)
                    if (topButtons.isNotEmpty()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Space.gutter),
                        ) {
                            topButtons.forEach { b -> TopButton(ctx, b, live, Modifier.weight(1f)) }
                        }
                    }
                    Art(art, Modifier.fillMaxWidth().aspectRatio(1.2f), RoundedCornerShape(Radius.card), tv != null)
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            title, style = AstrionType.headline, color = AstrionTheme.textPrimary,
                            maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        when {
                            unavailable -> UnavailableBadge()
                            artist != null -> Text(
                                artist, style = AstrionType.body, color = AstrionTheme.textSecondary,
                                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            e?.state == "paused" -> StateLine("Paused")
                        }
                    }
                    if (showControls) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            IconAction(
                                Icons.Filled.VolumeDown, "Volume down", { mp(volume, "volume_down") },
                                enabled = live, pending = volume.busy, failed = volume.failed,
                            )
                            IconAction(
                                Icons.Filled.SkipPrevious, "Previous track", { mp(transport, "media_previous_track") },
                                size = 52.dp, enabled = live,
                            )
                            IconAction(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                if (playing) "Pause" else "Play",
                                ::playPause,
                                size = 68.dp, tone = Tone.Accent, enabled = live,
                                pending = transport.busy, failed = transport.failed, iconSize = 32.dp,
                            )
                            IconAction(
                                Icons.Filled.SkipNext, "Next track", { mp(transport, "media_next_track") },
                                size = 52.dp, enabled = live,
                            )
                            IconAction(
                                Icons.Filled.VolumeUp, "Volume up", { mp(volume, "volume_up") },
                                enabled = live, pending = volume.busy, failed = volume.failed,
                            )
                        }
                    }
                }
            } else {
                // Compact: one row. The row itself is NOT a hidden toggle.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Space.gutter, vertical = Space.s),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val ring = if (playing) {
                        Modifier.border(2.dp, AstrionTheme.good, CircleShape)
                    } else {
                        Modifier
                    }
                    Art(art, Modifier.size(44.dp).then(ring), CircleShape, tv != null)
                    Column(Modifier.weight(1f)) {
                        Text(
                            title, style = AstrionType.title, color = AstrionTheme.textPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        when {
                            unavailable -> UnavailableBadge()
                            else -> StateLine(
                                artist ?: if (playing) "Playing" else e?.state?.replaceFirstChar { it.uppercase() } ?: "",
                                if (playing) StateKind.Good else StateKind.Normal,
                            )
                        }
                    }
                    IconAction(
                        Icons.Filled.VolumeDown, "Volume down", { mp(volume, "volume_down") },
                        size = Touch.compact, enabled = live, pending = volume.busy, failed = volume.failed,
                    )
                    IconAction(
                        if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        if (playing) "Pause" else "Play",
                        ::playPause,
                        tone = Tone.Accent, enabled = live, pending = transport.busy, failed = transport.failed,
                    )
                    IconAction(
                        Icons.Filled.VolumeUp, "Volume up", { mp(volume, "volume_up") },
                        size = Touch.compact, enabled = live, pending = volume.busy, failed = volume.failed,
                    )
                }
            }
        }
    }

    /** Album art, or a glyph when there's none (idle reads as idle, not broken). */
    @Composable
    private fun Art(art: ImageBitmap?, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape, isTv: Boolean) {
        if (art != null) {
            Image(art, contentDescription = null, modifier = modifier.clip(shape), contentScale = ContentScale.Crop)
        } else {
            Box(modifier.clip(shape).background(AstrionTheme.artPlaceholder), contentAlignment = Alignment.Center) {
                Icon(
                    if (isTv) Icons.Filled.Movie else Icons.Filled.MusicNote,
                    contentDescription = "No artwork",
                    tint = AstrionTheme.textMuted,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
    }

    /** The display-only card with nothing on: one slim line. */
    @Composable
    private fun IdleLine(text: String?) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Touch.min)
                .clip(RoundedCornerShape(Radius.control))
                .background(AstrionTheme.cardBg)
                .padding(horizontal = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Tv, contentDescription = null, tint = AstrionTheme.textSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Space.s))
            if (text == null) {
                UnavailableBadge(text = "TV unavailable")
            } else {
                Text(text, style = AstrionType.body, color = AstrionTheme.textSecondary)
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Composable
    private fun TopButton(ctx: CardContext, b: Map<String, Any?>, live: Boolean, modifier: Modifier) {
        val action = rememberAction(ctx)
        AstrionButton(
            onClick = {
                val service = b["service"] as? String ?: return@AstrionButton
                val data = (b["data"] as? Map<String, Any?>).orEmpty()
                action.run(
                    ServiceCall.of(
                        service.substringBefore('.'), service.substringAfter('.'),
                        b["entity_id"] as? String,
                        *data.entries.map { it.key to it.value }.toTypedArray(),
                    )
                )
            },
            modifier = modifier,
            label = b["name"] as? String ?: "",
            enabled = live,
            pending = action.busy,
            failed = action.failed,
        )
    }

    /** The current source; tap for a picker sheet of the live `source_list`. */
    @Composable
    private fun SourceRow(ctx: CardContext, entityId: String) {
        val e = ctx.entity(entityId)
        val sources = e?.attrStringList("source_list") ?: emptyList()
        val current = e?.attrString("source")
        val overlay = LocalOverlay.current
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Touch.compact)
                .clip(RoundedCornerShape(Radius.control))
                .background(AstrionTheme.controlBg)
                .tap(enabled = sources.isNotEmpty()) {
                    overlay.show { SourceSheet(ctx, entityId, onClose = { overlay.dismiss() }) }
                }
                .padding(horizontal = Space.m),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                current ?: if (sources.isEmpty()) "No sources" else "Select source…",
                style = AstrionType.body, color = AstrionTheme.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
            )
            Icon(Icons.Filled.ExpandMore, contentDescription = null, tint = AstrionTheme.textOnControl)
        }
    }
}

/**
 * Source picker for a media_player, as a sheet (the Material dropdown it
 * replaces was its own popup window and took the hardware keys away).
 */
@Composable
fun SourceSheet(ctx: CardContext, entityId: String, title: String? = null, onClose: () -> Unit) {
    val e = ctx.entity(entityId)
    val sources = e?.attrStringList("source_list") ?: emptyList()
    val current = e?.attrString("source")
    AstrionSheet(onDismiss = onClose, title = title ?: "Source", subtitle = e?.friendlyName) {
        if (sources.isEmpty()) {
            StateLine("No sources (device off?)")
        } else {
            OptionList(options = sources, selected = current) { s ->
                ctx.client.callService(ServiceCall.of("media_player", "select_source", entityId, "source" to s))
                onClose()
            }
        }
    }
}
