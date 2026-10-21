package com.custom.astrion.cards.impl

import android.graphics.Bitmap
import com.custom.astrion.ui.parseHexColor
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.SurroundSound
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
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
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ArtCache
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.Fader
import com.custom.astrion.ui.Glyphs
import com.custom.astrion.ui.LocalDashboardShowing
import com.custom.astrion.ui.LocalPageVisible
import com.custom.astrion.ui.formatMediaTime
import com.custom.astrion.ui.parseIsoMs
import com.custom.astrion.ui.tap
import com.custom.astrion.ui.tightTextStyle
import kotlinx.coroutines.delay

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
 * "tv" variant (the TV page): what's on the TV, as information only. No
 * transport or volume buttons and no service calls except the app tiles: the
 * hardware keys and the TV's own remote do the controlling. Artwork keeps its
 * own shape (a Plex poster is 2:3), with the title, episode, status and
 * progress beside it, and a row of `apps` launcher tiles underneath:
 *   { "variant": "tv",
 *     "entity_id": "media_player.<the TV's ADB entity>",   // on/off and the app in front
 *     "art_entities": [ "media_player.plex_…", "media_player.<cast entity>" ],
 *     "placeholder": "/sdcard/astrion/serif_tv.png",       // shown while the TV is off
 *     "apps": [ { "name": "Plex", "icon": "/sdcard/astrion/icons/logo_plex.png", "color": "#E5A00D",
 *                 "service": "media_player.select_source", "entity_id": "…",
 *                 "data": { "source": "com.plexapp.android" } }, … ] }
 * An app with an `icon` (PNG path) shows just that logo; otherwise a tinted
 * `badge` letter over its name, or its name as a `wordmark`. The tile of the
 * app in front (matched on `data.source`) is outlined.
 */
class MediaPlayerCard : CardRenderer {
    override val type = "media_player"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        if (config.string("variant") == "tv") {
            TvCard(config, ctx, entityId)
            return
        }
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
        val art = rememberArt(ctx, artPath)

        fun mp(service: String, vararg data: Pair<String, Any?>) {
            ctx.client.callService(ServiceCall.of("media_player", service, entityId, *data))
        }

        if (full) {
            val showControls = config.bool("show_controls", true)
            val queuePlayer = config.string("queue_player")
            var queueOpen by remember { mutableStateOf(false) }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                ArtBackdrop(art) {
                    FullContent(
                        ctx, title, artist, playing, art, ::mp, topButtons, sourceEntity, live,
                        showControls = showControls,
                        // Width / height of the artwork; wider leaves room below
                        // the card for the favourites row on one screen.
                        artAspect = (config.options["art_aspect"] as? Number)?.toFloat() ?: 1.2f,
                        onQueue = queuePlayer?.let { { queueOpen = true } },
                    )
                }
                if (showControls && config.bool("volume_fader", true)) {
                    VolumeRow(ctx, entityId, config.string("name") ?: e?.friendlyName ?: entityId, live)
                }
            }
            if (queueOpen && queuePlayer != null) {
                QueueSheet(ctx, queuePlayer) { queueOpen = false }
            }
        } else ArtBackdrop(art) {
            CompactContent(title, artist, playing, art, ::mp, live)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fireService(ctx: CardContext, b: Map<String, Any?>) {
        val service = b["service"] as? String ?: return
        val data = (b["data"] as? Map<String, Any?>).orEmpty()
        ctx.client.callService(ServiceCall.fromConfig(service, b["entity_id"] as? String, data))
    }

    // ---- tv (TV page): what's on, as information, and the app tiles -------------

    /**
     * The TV card's content. Only what it shows is derived from HA, so a
     * position report or a fresh ADB screen-grab, which change nothing here,
     * doesn't recompose it; the progress line reads its own entity.
     */
    @Suppress("UNCHECKED_CAST")
    @Composable
    private fun TvCard(config: CardConfig, ctx: CardContext, entityId: String) {
        val apps = remember(config) { (config.options["apps"] as? List<Map<String, Any?>>) ?: emptyList() }
        val players = remember(config) { config.stringList("art_entities") }
        val session by remember(config, ctx) {
            derivedStateOf {
                resolveTvSession(
                    ctx.entity(entityId),
                    players.mapNotNull { ctx.entity(it) }.filterNot { it.isUnavailable },
                    apps,
                )
            }
        }
        val art = rememberArt(ctx, session.artPath)
        // `footer`: another card drawn inside this one, under what's on
        // (the Plex buttons), so the two read as one card.
        val footer = remember(config) { config.options["footer"] as? Map<String, Any?> }
        // `corner_button`: a small round button top right, e.g. TV sound.
        val corner = remember(config) { config.options["corner_button"] as? Map<String, Any?> }

        ArtBackdrop(art, washPx = 6) {
          Box(Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.fillMaxWidth().padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SessionArt(art, session, config.string("placeholder"))
                    Spacer(Modifier.width(14.dp))
                    // Keeps the title clear of the corner button.
                    SessionInfo(ctx, session, Modifier.weight(1f).padding(end = if (corner != null) 30.dp else 0.dp))
                }
                footer?.let { f ->
                    val type = f["type"] as? String
                    val renderer = type?.let { com.custom.astrion.cards.CardRegistry.get(it) }
                    if (renderer != null) {
                        val opts = ((f["options"] as? Map<String, Any?>) ?: emptyMap()) + ("flush" to true)
                        renderer.Render(CardConfig(type, opts), ctx)
                    }
                }
                if (apps.isNotEmpty()) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        apps.forEach { a ->
                            AppTile(
                                a, Modifier.weight(1f), ctx.connected,
                                inFront = session.frontApp != null && appSource(a) == session.frontApp,
                            ) { fireService(ctx, a) }
                        }
                    }
                }
            }
            corner?.let { CornerButton(ctx, it, Modifier.align(Alignment.TopEnd).padding(10.dp)) }
          }
        }
    }

    /**
     * The TV card's corner button: lit while [`lit_entity`] has [`lit_when`]
     * (attribute `lit_attribute`, else its state); a tap runs `service` on
     * `entity_id`, then unjoins every other speaker from `unjoin_others`.
     *   "corner_button": { "icon": "speaker", "label": "TV sound",
     *     "service": "script.turn_on", "entity_id": "script.long_music",
     *     "unjoin_others": "media_player.club",
     *     "lit_entity": "media_player.club", "lit_attribute": "source", "lit_when": "TV" }
     */
    @Composable
    private fun CornerButton(ctx: CardContext, b: Map<String, Any?>, modifier: Modifier) {
        val litEntity = b["lit_entity"] as? String
        val lit = litEntity?.let { id ->
            val e = ctx.entity(id)
            val v = (b["lit_attribute"] as? String)?.let { e?.attrString(it) } ?: e?.state
            v != null && v == b["lit_when"]?.toString()
        } == true
        val label = b["label"] as? String ?: "Sound"
        com.custom.astrion.ui.HitTarget(
            visual = 32.dp,
            enabled = ctx.connected,
            onClick = {
                (b["service"] as? String)?.let { svc ->
                    ctx.client.callService(ServiceCall.fromConfig(svc, b["entity_id"] as? String), confirm = true)
                }
                (b["unjoin_others"] as? String)?.let { master ->
                    ctx.client.live[master]?.attrStringList("group_members")
                        ?.filter { it != master }
                        ?.forEach { ctx.client.callService(ServiceCall("media_player", "unjoin", entityId = it)) }
                }
            },
            modifier = modifier,
            face = Modifier
                .clip(CircleShape)
                .background(if (lit) AstrionTheme.accentStrong else AstrionTheme.colors.void.copy(alpha = 0.45f)),
        ) {
            Icon(
                Icons.Filled.SurroundSound,
                contentDescription = if (lit) "$label, on" else label,
                tint = if (lit) AstrionTheme.colors.onPrimary else AstrionTheme.textOnControl,
                modifier = Modifier.size(18.dp),
            )
        }
    }

    /** Artwork at its own proportions: a poster stands, a thumbnail lies flat. */
    @Composable
    private fun SessionArt(art: ImageBitmap?, s: TvSession, placeholder: String?) {
        val shape = RoundedCornerShape(10.dp)
        val logo by com.custom.astrion.ui.rememberSampledBitmap(if (art == null) s.logo else null, targetPx = 128)
        val tvPicture by com.custom.astrion.ui.rememberSampledBitmap(
            if (art == null && s.state == TvState.OFF) placeholder else null, targetPx = 320,
        )
        val l = logo
        val p = tvPicture
        when {
            art != null -> {
                val aspect = art.width.toFloat() / art.height.coerceAtLeast(1)
                val size = when {
                    aspect < 0.85f -> Modifier.width(POSTER_W).aspectRatio(aspect.coerceAtLeast(0.6f))
                    aspect <= 1.2f -> Modifier.size(SQUARE_ART)
                    else -> Modifier.width(WIDE_ART_W).aspectRatio(aspect.coerceAtMost(2f))
                }
                Image(art, null, modifier = size.clip(shape), contentScale = ContentScale.Crop)
            }
            // No artwork: the logo of the app in front, on its own colour.
            l != null -> Box(
                modifier = Modifier
                    .size(SQUARE_ART)
                    .clip(shape)
                    .background(AstrionTheme.raised)
                    .background(parseHexColor(s.logoColor)?.copy(alpha = 0.18f) ?: Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Image(l, contentDescription = null, modifier = Modifier.size(52.dp))
            }
            p != null -> Image(
                p, null, alpha = 0.55f, contentScale = ContentScale.Crop,
                modifier = Modifier.width(WIDE_ART_W).aspectRatio(p.width.toFloat() / p.height.coerceAtLeast(1)).clip(shape),
            )
            else -> Box(
                modifier = Modifier.size(SQUARE_ART).clip(shape).background(AstrionTheme.raised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Filled.Movie, contentDescription = null, tint = AstrionTheme.colors.accentMuted, modifier = Modifier.size(40.dp))
            }
        }
    }

    @Composable
    private fun SessionInfo(ctx: CardContext, s: TvSession, modifier: Modifier) {
        val status = when (s.state) {
            TvState.PLAYING -> "Playing"
            TvState.PAUSED -> "Paused"
            TvState.ON -> "On"
            TvState.OFF -> null
        }?.let { word -> listOfNotNull(word, s.appName?.takeIf { it != s.title }).joinToString(" · ") }
        Column(modifier) {
            Text(
                s.title, color = AstrionTheme.textPrimary, fontFamily = AstrionTheme.headingFont,
                fontSize = AstrionTheme.size.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = tightTextStyle(21.sp),
            )
            s.subtitle?.let {
                Spacer(Modifier.height(4.dp))
                Text(
                    it, color = AstrionTheme.textPrimary.copy(alpha = 0.85f), fontSize = AstrionTheme.size.body,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = tightTextStyle(17.sp),
                )
            }
            s.meta?.let {
                Spacer(Modifier.height(3.dp))
                Text(it, color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (status != null) {
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(7.dp).clip(CircleShape).background(
                            when (s.state) {
                                TvState.PLAYING -> AstrionTheme.good
                                TvState.PAUSED -> AstrionTheme.blush
                                else -> AstrionTheme.good.copy(alpha = 0.5f)
                            }
                        )
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(status, color = AstrionTheme.colors.textSoft, fontSize = AstrionTheme.size.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            s.progressEntity?.let { SessionProgress(ctx, it) }
        }
    }

    /**
     * Elapsed and length under a hairline, extrapolated from HA's last
     * position report. It ticks each second only while playing and on screen.
     */
    @Composable
    private fun SessionProgress(ctx: CardContext, entityId: String) {
        val p = ctx.entity(entityId) ?: return
        val duration = p.attrDouble("media_duration")?.takeIf { it > 0 } ?: return
        val position = p.attrDouble("media_position") ?: return
        val reportedAt = p.attrString("media_position_updated_at")?.let { parseIsoMs(it) }
        val ticking = p.state == "playing" && reportedAt != null &&
            LocalPageVisible.current && LocalDashboardShowing.current
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(ticking) {
            while (ticking) {
                now = System.currentTimeMillis()
                delay(1000 - now % 1000)
            }
        }
        val pos = (if (ticking && reportedAt != null) position + (now - reportedAt) / 1000.0 else position)
            .coerceIn(0.0, duration)
        Spacer(Modifier.height(8.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .height(3.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(AstrionTheme.colors.hairlineStrong),
        ) {
            Box(
                Modifier
                    .fillMaxWidth((pos / duration).toFloat().coerceIn(0f, 1f))
                    .fillMaxHeight()
                    .background(AstrionTheme.accent),
            )
        }
        Spacer(Modifier.height(4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatMediaTime(pos), color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.label)
            Text(formatMediaTime(duration), color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.label)
        }
    }

    /**
     * Launcher tile: the app's logo, or a tinted letter badge over a small-caps
     * name, or a coloured wordmark. Outlined while that app is in front.
     */
    @Composable
    private fun AppTile(a: Map<String, Any?>, modifier: Modifier, enabled: Boolean, inFront: Boolean, onClick: () -> Unit) {
        val name = a["name"] as? String ?: ""
        val color = parseHexColor(a["color"] as? String) ?: AstrionTheme.colors.onSurfaceMuted
        val dim = a["dim"] as? Boolean ?: false
        val badge = a["badge"] as? String
        val hasIcon = a["icon"] is String
        val shape = RoundedCornerShape(12.dp)
        Column(
            modifier = modifier
                .height(if (hasIcon) 44.dp else 76.dp)
                .clip(shape)
                .background(if (inFront) AstrionTheme.colors.surfaceVariant.copy(alpha = 0.5f) else AstrionTheme.colors.pinnedTopBg.copy(alpha = 0.33f))
                .then(if (inFront) Modifier.border(1.5.dp, AstrionTheme.accent.copy(alpha = 0.75f), shape) else Modifier)
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
                    Text(badge, color = color.copy(alpha = alpha), fontSize = AstrionTheme.size.emphasis, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    name.uppercase(), color = AstrionTheme.colors.onSurfaceMuted.copy(alpha = alpha), fontSize = AstrionTheme.size.label,
                    fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, maxLines = 1,
                )
            } else {
                Text(name, color = color.copy(alpha = alpha), fontSize = AstrionTheme.size.heading, fontWeight = FontWeight.Bold, maxLines = 1)
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
                Box(artMod.background(AstrionTheme.colors.surfaceVariant))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = AstrionTheme.colors.onSurface, fontSize = AstrionTheme.size.bodyLarge, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(artist, color = AstrionTheme.colors.textSoft, fontSize = AstrionTheme.label,
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
        onQueue: (() -> Unit)?,
    ) {
        Box {
        Column(
            modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 14.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                                .background(AstrionTheme.colors.controlBg.copy(alpha = 0.4f)) // semi-transparent
                                .tap(enabled = enabled) { fireService(ctx, b) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                b["name"] as? String ?: "",
                                color = AstrionTheme.colors.onSurface,
                                fontSize = AstrionTheme.size.bodyLarge,
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
                // The whole cover, never cropped: it sits centred at its own
                // shape and the card's blurred wash shows either side. (Cropping
                // a square cover into this wide frame cut off half of it.)
                Box(Modifier.fillMaxWidth().aspectRatio(artAspect), contentAlignment = Alignment.Center) {
                    Image(
                        art, null,
                        modifier = Modifier
                            .fillMaxHeight()
                            .aspectRatio(art.width.toFloat() / art.height.coerceAtLeast(1), matchHeightConstraintsFirst = true)
                            .clip(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Fit,
                    )
                }
            } else {
                Box(
                    artMod.background(AstrionTheme.raised),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Glyphs.note,
                        contentDescription = "Nothing playing",
                        tint = AstrionTheme.colors.accentMuted,
                        modifier = Modifier.size(44.dp),
                    )
                }
            }
            // Centred now-playing text.
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(title, color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.title, fontWeight = FontWeight.ExtraBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
                Text(artist, color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.small,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth())
            }
            // Transport: previous, a big play/pause, next. Volume has its own
            // fader under the card. Suppressed on a display-only card.
            if (showControls) Row(
                horizontalArrangement = Arrangement.spacedBy(20.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                CircleControl(Glyphs.prev, 40.dp, "Previous track", enabled = enabled) {
                    mp("media_previous_track", emptyArray())
                }
                CircleControl(
                    if (playing) Glyphs.pause else Glyphs.play, 52.dp,
                    description = if (playing) "Pause" else "Play",
                    accent = true, enabled = enabled,
                ) {
                    mp("media_play_pause", emptyArray())
                }
                CircleControl(Glyphs.next, 40.dp, "Next track", enabled = enabled) {
                    mp("media_next_track", emptyArray())
                }
            }
        }
        // The queue, top right over the art's corner.
        if (onQueue != null) {
            Box(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp)
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
                    .background(AstrionTheme.colors.void.copy(alpha = 0.35f))
                    .tap(onClick = onQueue),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Glyphs.list, contentDescription = "Queue", tint = AstrionTheme.textSecondary, modifier = Modifier.size(16.dp))
            }
        }
        }
    }

    /** Mute, then the player's volume as a fader with its name on it. */
    @Composable
    private fun VolumeRow(ctx: CardContext, entityId: String, name: String, enabled: Boolean) {
        val e = ctx.entities[entityId]
        val muted = e?.attrString("is_volume_muted") == "true"
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(if (muted) AstrionTheme.colors.blushContainer else AstrionTheme.cardBg)
                    .tap(enabled = enabled) {
                        ctx.client.callService(ServiceCall.of("media_player", "volume_mute", entityId, "is_volume_muted" to !muted))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (muted) Glyphs.mute else Glyphs.vol,
                    contentDescription = if (muted) "Unmute" else "Mute",
                    tint = if (muted) AstrionTheme.blush else AstrionTheme.textSecondary,
                    modifier = Modifier.size(19.dp),
                )
            }
            Fader(
                value = e?.attrDouble("volume_level")?.toFloat(),
                onCommit = { v ->
                    ctx.client.callService(ServiceCall.of("media_player", "volume_set", entityId, "volume_level" to v.toDouble()))
                },
                modifier = Modifier.weight(1f).height(40.dp),
                enabled = enabled,
                fill = if (muted) AstrionTheme.raised else AstrionTheme.accentStrong,
            ) {
                Text(name, color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.body, fontWeight = FontWeight.Bold, maxLines = 1)
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
                    .background(AstrionTheme.colors.controlBg.copy(alpha = 0.4f))
                    .tap(enabled = sources.isNotEmpty()) { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    current ?: if (sources.isEmpty()) "No sources" else "Select source…",
                    color = AstrionTheme.colors.onSurface,
                    fontSize = AstrionTheme.size.small,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = if (sources.isEmpty()) AstrionTheme.colors.textMuted else AstrionTheme.colors.textOnControl,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(AstrionTheme.colors.surfaceVariant).widthIn(max = 400.dp),
            ) {
                sources.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                s,
                                color = if (s == current) AstrionTheme.colors.accent else AstrionTheme.colors.onSurface,
                                fontSize = AstrionTheme.size.body,
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
                .background(if (accent) AstrionTheme.accentStrong else AstrionTheme.colors.glass)
                .tap(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = AstrionTheme.textPrimary, modifier = Modifier.size(size * 0.42f))
        }
    }
}


/** What the TV card shows: a session on the TV, or what the TV itself is doing. */
private enum class TvState { PLAYING, PAUSED, ON, OFF }

private data class TvSession(
    val state: TvState,
    val title: String,
    val subtitle: String? = null,
    /** "S16 E13 · TV-14". */
    val meta: String? = null,
    /** The app the session is in, after the state word ("Paused · Plex"). */
    val appName: String? = null,
    /** HA `entity_picture` of the session: a poster or a thumbnail. */
    val artPath: String? = null,
    /** Logo (and its colour) for the art slot when there's no artwork. */
    val logo: String? = null,
    val logoColor: String? = null,
    /** Entity whose media_position / media_duration drive the progress line. */
    val progressEntity: String? = null,
    /** Package of the app in front, to outline its tile. */
    val frontApp: String? = null,
)

private const val PLAYER_OFF_STATES = "off standby unavailable unknown"

private val POSTER_W = 104.dp
private val SQUARE_ART = 108.dp
private val WIDE_ART_W = 148.dp

private fun appSource(a: Map<String, Any?>): String? = (a["data"] as? Map<*, *>)?.get("source") as? String

/**
 * Picks what the TV card shows. A playing session wins. A paused one only
 * counts while the TV is on and its app is the one in front: Plex keeps a
 * paused session open in the background for hours after you've moved on to
 * another app, and that isn't what's on the TV.
 */
private fun resolveTvSession(tv: EntityState?, players: List<EntityState>, apps: List<Map<String, Any?>>): TvSession {
    fun named(text: String?): Map<String, Any?>? = text?.let { t ->
        apps.firstOrNull { a -> (a["name"] as? String)?.let { t.contains(it, ignoreCase = true) } == true }
    }
    fun appOf(p: EntityState) = named(p.attrString("app_name")) ?: named(p.friendlyName)

    val tvOn = tv != null && tv.state !in PLAYER_OFF_STATES.split(' ')
    val frontPkg = if (!tvOn) null else tv?.attrString("app_id") ?: tv?.attrString("source")
    val front = frontPkg?.let { pkg -> apps.firstOrNull { appSource(it) == pkg } }

    val session = players.firstOrNull { it.state == "playing" }
        ?: players.firstOrNull { p ->
            val pkg = appOf(p)?.let(::appSource)
            tvOn && p.state == "paused" && (pkg == null || frontPkg == null || pkg == frontPkg)
        }
    if (session != null) {
        val app = appOf(session) ?: front
        val series = session.attrString("media_series_title")?.takeIf { it.isNotBlank() }
        val title = session.attrString("media_title")?.takeIf { it.isNotBlank() }
        val episode = listOfNotNull(
            session.attrInt("media_season")?.let { "S$it" },
            session.attrInt("media_episode")?.let { "E$it" },
        ).joinToString(" ")
        return TvSession(
            state = if (session.state == "playing") TvState.PLAYING else TvState.PAUSED,
            title = series ?: title ?: (app?.get("name") as? String) ?: session.friendlyName,
            subtitle = if (series != null) title else session.attrString("media_artist"),
            meta = listOfNotNull(episode.ifEmpty { null }, session.attrString("media_content_rating"))
                .joinToString(" · ").ifEmpty { null },
            appName = (app?.get("name") as? String) ?: session.attrString("app_name"),
            artPath = session.attrString("entity_picture"),
            logo = app?.get("icon") as? String,
            logoColor = app?.get("color") as? String,
            progressEntity = session.entityId,
            frontApp = frontPkg,
        )
    }
    if (!tvOn) return TvSession(TvState.OFF, "TV off")
    val playing = tv?.state == "playing"
    return TvSession(
        state = if (playing) TvState.PLAYING else TvState.ON,
        title = (front?.get("name") as? String) ?: "TV",
        subtitle = if (playing) null else "Nothing playing",
        logo = front?.get("icon") as? String,
        logoColor = front?.get("color") as? String,
        frontApp = frontPkg,
    )
}


/**
 * Artwork for [path] through ArtCache: the screensaver shows the same art, and
 * a poster decoded at full size can be ~6 MB. 480px covers the panel's width.
 */
@Composable
internal fun rememberArt(ctx: CardContext, path: String?): ImageBitmap? {
    var art by remember(path) { mutableStateOf(path?.let { ArtCache.peek(it, 480) }) }
    LaunchedEffect(path) {
        art = path?.let { p -> ArtCache.load(p, 480) { ctx.client.fetchBytes(p) } }
    }
    return art
}

/**
 * The player card's shell: rounded, with the artwork blurred behind it under
 * a scrim. Modifier.blur is a no-op on API 26, so "blurred" is a [washPx]-wide
 * copy stretched to fill. The TV card, whose poster sits beside the text
 * rather than filling the card, uses a few pixels: just the poster's colours,
 * where 32px still drew its shapes, blocky, behind the title.
 */
@Composable
private fun ArtBackdrop(art: ImageBitmap?, washPx: Int = 32, content: @Composable () -> Unit) {
    val blurred = remember(art, washPx) {
        art?.let { img ->
            val src = img.asAndroidBitmap()
            if (src.width <= 0) return@let null
            val w = washPx
            val h = (w * src.height / src.width).coerceAtLeast(1)
            Bitmap.createScaledBitmap(src, w, h, true).asImageBitmap()
        }
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(AstrionTheme.colors.surface),
    ) {
        blurred?.let { bg ->
            Image(bitmap = bg, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            Box(modifier = Modifier.matchParentSize().background(AstrionTheme.colors.pinnedTopBg.copy(alpha = 0.7f)))
        }
        content()
    }
}
