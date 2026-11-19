package com.custom.astrion.cards.impl

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SurroundSound
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.ArtCache
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.Fader
import com.custom.astrion.ui.Glyphs
import com.custom.astrion.ui.HitTarget
import com.custom.astrion.ui.InWindowDialog
import com.custom.astrion.ui.LocalDashboardShowing
import com.custom.astrion.ui.LocalPageVisible
import com.custom.astrion.ui.formatMediaTime
import com.custom.astrion.ui.parseHexColor
import com.custom.astrion.ui.parseIsoMs
import com.custom.astrion.ui.rememberSampledBitmap
import com.custom.astrion.ui.tap
import com.custom.astrion.ui.tightTextStyle
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Borderless Matte Media Player & TV Console Hero (Bæo v3.0):
 *  - `"full"` (Media Tab 0): velvety ARGB_8888 artwork wash with >= 0.64 dark
 *    scrim, top utility row (bare Search + Active-Zone Bridge Pill + bare Queue),
 *    borderless album art capped <= 144dp, scrubbable seek bar (+ -30s/+30s skip),
 *    bare Prev/Next flanking a solid matte Hailstorm Play/Pause circle, and a
 *    smooth borderless Fader.
 *  - `"tv"` (TV Cinema Console): borderless artwork hero with `also_on` PC HDMI 2
 *    support, matte TV Power button, optional embedded Club audio Fader +
 *    explicit "TV Audio" matte pill, and real HA attributes only.
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
        val artFrom = if (tv?.attrString("entity_picture") != null) tv else e
        val art = rememberArt(ctx, artFrom?.attrString("entity_picture"), artFrom?.artKey())

        fun mp(service: String, vararg data: Pair<String, Any?>) {
            ctx.client.callService(ServiceCall.of("media_player", service, entityId, *data))
        }

        val queuePlayer = config.string("queue_player") ?: "media_player.club_2"
        val speakerName = config.string("name")
            ?: e?.friendlyName?.takeIf { it.isNotBlank() }
            ?: "Club"
        val searchEntryId = config.string("search_entry_id")
        val linkToggleEntity = config.string("link_toggle_entity") ?: "input_boolean.music_follows_me"
        var queueOpen by remember { mutableStateOf(false) }
        var searchOpen by remember { mutableStateOf(false) }
        val switchTab = LocalTabSwitcher.current

        val showControls = config.bool("show_controls", true)
        ArtBackdrop(art, bottomShade = full) {
            if (full) {
                FullContent(
                    ctx = ctx,
                    e = e,
                    entityId = entityId,
                    speakerName = speakerName,
                    linkToggleEntity = linkToggleEntity,
                    title = title,
                    artist = artist,
                    playing = playing,
                    art = art,
                    mp = ::mp,
                    topButtons = topButtons,
                    sourceEntity = sourceEntity,
                    enabled = live,
                    showControls = showControls,
                    onSearch = {
                        if (searchEntryId != null) searchOpen = true
                        else switchTab?.invoke(1)
                    },
                    onZones = switchTab?.let { { it(2) } },
                    onQueue = { queueOpen = true },
                )
            } else {
                CompactContent(title, artist, playing, art, ::mp, live)
            }
        }
        if (full && queueOpen) {
            QueueSheet(ctx, queuePlayer, speaker = entityId) { queueOpen = false }
        }
        if (full && searchOpen && searchEntryId != null) {
            InWindowDialog(onDismissRequest = { searchOpen = false }) {
                MusicSearchPanel(
                    ctx = ctx,
                    entry = searchEntryId,
                    player = queuePlayer,
                    onClose = { searchOpen = false },
                )
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun fireService(ctx: CardContext, b: Map<String, Any?>) {
        val service = b["service"] as? String ?: return
        val data = (b["data"] as? Map<String, Any?>).orEmpty()
        ctx.client.callService(ServiceCall.fromConfig(service, b["entity_id"] as? String, data))
    }

    // ---- tv (TV Cinema Console): hero + power + embedded audio strip --------

    @Suppress("UNCHECKED_CAST")
    @Composable
    private fun TvCard(config: CardConfig, ctx: CardContext, entityId: String) {
        val apps = remember(config) { (config.options["apps"] as? List<Map<String, Any?>>) ?: emptyList() }
        val players = remember(config) { config.stringList("art_entities") }
        val alsoOnEntries = remember(config) {
            when (val raw = config.options["also_on"]) {
                is Map<*, *> -> listOf(raw)
                is List<*> -> raw.filterIsInstance<Map<*, *>>()
                else -> emptyList()
            }
        }
        val session by remember(config, ctx) {
            derivedStateOf {
                val activeAlsoOn = alsoOnEntries.firstOrNull { alsoOn(it, ctx) }
                resolveTvSession(
                    ctx.entity(entityId),
                    players.mapNotNull { ctx.entity(it) }.filterNot { it.isUnavailable },
                    apps,
                    activeAlsoOn,
                )
            }
        }
        val art = rememberArt(ctx, session.artPath)
        val footer = remember(config) { config.options["footer"] as? Map<String, Any?> }
        val corner = remember(config) { config.options["corner_button"] as? Map<String, Any?> }
        val audioEntity = config.string("audio_entity")
        val audioName = config.string("audio_name") ?: "Club"
        val tvPowerRemote = config.string("tv_power_entity")
        val tvAudioBtn = remember(config) { config.options["tv_audio_button"] as? Map<String, Any?> }

        ArtBackdrop(art, washPx = 6) {
            Box(Modifier.fillMaxWidth()) {
                Column(
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SessionArt(art, session, config.string("placeholder"))
                        Spacer(Modifier.width(12.dp))
                        val endReserve = if (tvPowerRemote != null || (corner != null && audioEntity == null)) 46.dp else 0.dp
                        SessionInfo(ctx, session, Modifier.weight(1f).padding(end = endReserve))
                    }

                    // Embedded Club volume fader + explicit matte "TV Audio" pill.
                    if (audioEntity != null) {
                        TvAudioStrip(ctx, audioEntity, audioName, tvAudioBtn ?: corner)
                    }

                    footer?.let { f ->
                        val type = f["type"] as? String
                        val renderer = type?.let { CardRegistry.get(it) }
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
                                val inFront = session.frontApp != null && appSource(a) == session.frontApp
                                AppTile(a, Modifier.weight(1f), ctx.connected, inFront = inFront) {
                                    val tvAdb = a["entity_id"] as? String
                                    if (inFront && tvAdb != null) {
                                        ctx.client.callService(
                                            ServiceCall.of(
                                                "androidtv", "adb_command", tvAdb,
                                                "command" to "input keyevent KEYCODE_WAKEUP; sleep 1; cmd hdmi_control onetouchplay",
                                            )
                                        )
                                    } else {
                                        fireService(ctx, a)
                                    }
                                }
                            }
                        }
                    }
                }

                if (tvPowerRemote != null) {
                    val tvIsOn = session.state != TvState.OFF
                    HitTarget(
                        visual = 38.dp,
                        enabled = ctx.connected,
                        onClick = {
                            ctx.client.callService(
                                ServiceCall.of("remote", "send_command", tvPowerRemote, "command" to "POWER"),
                                confirm = true,
                            )
                        },
                        modifier = Modifier.align(Alignment.TopEnd).padding(6.dp),
                        face = Modifier
                            .clip(CircleShape)
                            .background(if (tvIsOn) AstrionTheme.accentContainer else AstrionTheme.controlBg),
                    ) {
                        Icon(
                            Glyphs.power,
                            contentDescription = if (tvIsOn) "Turn TV off" else "Turn TV on",
                            tint = if (tvIsOn) AstrionTheme.accent else AstrionTheme.textOnControl,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                } else if (corner != null && audioEntity == null) {
                    CornerButton(ctx, corner, Modifier.align(Alignment.TopEnd).padding(8.dp))
                }
            }
        }
    }

    @Composable
    private fun TvAudioStrip(
        ctx: CardContext,
        audioEntity: String,
        audioName: String,
        tvAudioBtn: Map<String, Any?>?,
    ) {
        val spk = ctx.entities[audioEntity]
        val muted = spk?.attrString("is_volume_muted") == "true"
        val enabled = spk != null && !spk.isUnavailable && ctx.connected
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Fader(
                value = spk?.attrDouble("volume_level")?.toFloat(),
                onCommit = { v ->
                    ctx.client.callService(
                        ServiceCall.of("media_player", "volume_set", audioEntity, "volume_level" to v.toDouble())
                    )
                },
                enabled = enabled,
                muted = muted,
                fill = AstrionTheme.accentStrong,
                track = AstrionTheme.colors.surfacePrimary.copy(alpha = 0.78f),
                radius = 12.dp,
                modifier = Modifier.weight(1f).height(42.dp),
            ) {
                Icon(
                    if (muted) Glyphs.mute else Glyphs.vol,
                    contentDescription = null,
                    tint = if (muted) AstrionTheme.blush else AstrionTheme.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    audioName,
                    color = AstrionTheme.textPrimary,
                    fontSize = AstrionTheme.size.body,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }

            if (tvAudioBtn != null) {
                val litEntity = tvAudioBtn["lit_entity"] as? String ?: audioEntity
                val lit = run {
                    val e = ctx.entity(litEntity)
                    val v = (tvAudioBtn["lit_attribute"] as? String)?.let { e?.attrString(it) } ?: e?.state
                    v != null && v == tvAudioBtn["lit_when"]?.toString()
                }
                val label = tvAudioBtn["short_label"] as? String ?: "TV Audio"
                Row(
                    modifier = Modifier
                        .height(42.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (lit) AstrionTheme.accentStrong else AstrionTheme.controlBg)
                        .tap(enabled = ctx.connected) {
                            (tvAudioBtn["service"] as? String)?.let { svc ->
                                ctx.client.callService(
                                    ServiceCall.fromConfig(svc, tvAudioBtn["entity_id"] as? String),
                                    confirm = true,
                                )
                            }
                            (tvAudioBtn["unjoin_others"] as? String)?.let { master ->
                                ctx.client.live[master]?.attrStringList("group_members")
                                    ?.filter { it != master }
                                    ?.forEach { ctx.client.callService(ServiceCall("media_player", "unjoin", entityId = it)) }
                            }
                        }
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        Glyphs.speaker,
                        contentDescription = null,
                        tint = if (lit) AstrionTheme.colors.onPrimary else AstrionTheme.textOnControl,
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        label,
                        color = if (lit) AstrionTheme.colors.onPrimary else AstrionTheme.textOnControl,
                        fontSize = AstrionTheme.size.label,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    @Composable
    private fun CornerButton(ctx: CardContext, b: Map<String, Any?>, modifier: Modifier) {
        val litEntity = b["lit_entity"] as? String
        val lit = litEntity?.let { id ->
            val e = ctx.entity(id)
            val v = (b["lit_attribute"] as? String)?.let { e?.attrString(it) } ?: e?.state
            v != null && v == b["lit_when"]?.toString()
        } == true
        val label = b["label"] as? String ?: "Sound"
        HitTarget(
            visual = 34.dp,
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
                .background(if (lit) AstrionTheme.accentStrong else AstrionTheme.controlBg),
        ) {
            Icon(
                Icons.Filled.SurroundSound,
                contentDescription = if (lit) "$label, on" else label,
                tint = if (lit) AstrionTheme.colors.onPrimary else AstrionTheme.textOnControl,
                modifier = Modifier.size(18.dp),
            )
        }
    }

    @Composable
    private fun SessionArt(art: ImageBitmap?, s: TvSession, placeholder: String?) {
        val shape = RoundedCornerShape(12.dp)
        val logo by rememberSampledBitmap(if (art == null) s.logo else null, targetPx = 128)
        val tvPicture by rememberSampledBitmap(
            if (art == null && s.state == TvState.OFF) placeholder else null, targetPx = 280,
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
            l != null -> Box(
                modifier = Modifier
                    .size(SQUARE_ART)
                    .clip(shape)
                    .background(AstrionTheme.raised)
                    .background(parseHexColor(s.logoColor)?.copy(alpha = 0.18f) ?: Color.Transparent),
                contentAlignment = Alignment.Center,
            ) {
                Image(l, contentDescription = null, modifier = Modifier.size(48.dp))
            }
            p != null -> Image(
                p, null, alpha = 0.65f, contentScale = ContentScale.Crop,
                modifier = Modifier.width(WIDE_ART_W).aspectRatio(p.width.toFloat() / p.height.coerceAtLeast(1)).clip(shape),
            )
            else -> Box(
                modifier = Modifier.size(SQUARE_ART).clip(shape).background(AstrionTheme.raised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (s.state == TvState.OFF) Glyphs.tv else Icons.Filled.Movie,
                    contentDescription = null,
                    tint = AstrionTheme.colors.accentMuted,
                    modifier = Modifier.size(34.dp),
                )
            }
        }
    }

    @Composable
    private fun SessionInfo(ctx: CardContext, s: TvSession, modifier: Modifier) {
        val status = when (s.state) {
            TvState.PLAYING -> "Playing"
            TvState.PAUSED -> "Paused"
            TvState.ON -> "Active"
            TvState.OFF -> "Standby"
        }.let { word -> listOfNotNull(word, s.appName?.takeIf { it != s.title }).joinToString(" · ") }
        Column(modifier) {
            Text(
                s.title, color = AstrionTheme.textPrimary, fontFamily = AstrionTheme.headingFont,
                fontSize = AstrionTheme.size.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = tightTextStyle(21.sp),
            )
            s.subtitle?.let {
                Spacer(Modifier.height(3.dp))
                Text(
                    it, color = AstrionTheme.colors.textSoft, fontSize = AstrionTheme.size.body,
                    maxLines = 2, overflow = TextOverflow.Ellipsis, style = tightTextStyle(17.sp),
                )
            }
            s.meta?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(7.dp).clip(CircleShape).background(
                        when (s.state) {
                            TvState.PLAYING -> AstrionTheme.good
                            TvState.PAUSED -> AstrionTheme.blush
                            TvState.ON -> AstrionTheme.accent
                            TvState.OFF -> AstrionTheme.textMuted
                        }
                    )
                )
                Spacer(Modifier.width(6.dp))
                Text(status, color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            s.progressEntity?.let { SessionProgress(ctx, it, interactive = false) }
        }
    }

    /**
     * Progress bar with live extrapolation and optional scrub/skip controls
     * (`media_player.media_seek`) for the full Media player.
     */
    @Composable
    private fun SessionProgress(
        ctx: CardContext,
        entityId: String,
        topGap: Dp = 6.dp,
        interactive: Boolean = false,
        enabled: Boolean = true,
    ) {
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
        val livePos = (if (ticking && reportedAt != null) position + (now - reportedAt) / 1000.0 else position)
            .coerceIn(0.0, duration)
        var scrubFrac by remember(entityId) { mutableStateOf<Float?>(null) }
        val shownPos = scrubFrac?.let { it * duration } ?: livePos
        val shownFrac = (shownPos / duration).toFloat().coerceIn(0f, 1f)

        fun seekTo(sec: Double) {
            val target = sec.coerceIn(0.0, duration)
            ctx.client.callService(
                ServiceCall.of("media_player", "media_seek", entityId, "seek_position" to target)
            )
        }

        Spacer(Modifier.height(topGap))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(if (interactive) 20.dp else 6.dp)
                .then(
                    if (!interactive || !enabled) Modifier else Modifier
                        .pointerInput(entityId, duration) {
                            detectTapGestures { offset ->
                                val f = (offset.x / size.width).coerceIn(0f, 1f)
                                seekTo(f * duration)
                            }
                        }
                        .pointerInput(entityId, duration) {
                            detectHorizontalDragGestures(
                                onDragStart = { offset -> scrubFrac = (offset.x / size.width).coerceIn(0f, 1f) },
                                onDragEnd = {
                                    scrubFrac?.let { seekTo(it * duration) }
                                    scrubFrac = null
                                },
                                onDragCancel = { scrubFrac = null },
                            ) { change, _ ->
                                change.consume()
                                scrubFrac = (change.position.x / size.width).coerceIn(0f, 1f)
                            }
                        }
                ),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(if (interactive) 4.dp else 3.dp)
                    .clip(CircleShape)
                    .background(AstrionTheme.colors.surfacePrimary.copy(alpha = 0.78f)),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(shownFrac)
                        .fillMaxHeight()
                        .clip(CircleShape)
                        .background(AstrionTheme.accent),
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                formatMediaTime(shownPos),
                color = AstrionTheme.textSecondary,
                fontSize = AstrionTheme.size.micro,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
            if (interactive && enabled && duration >= 180.0) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SkipPill("−30s") { seekTo(livePos - 30.0) }
                    SkipPill("+30s") { seekTo(livePos + 30.0) }
                }
            }
            Text(
                formatMediaTime(duration),
                color = AstrionTheme.textSecondary,
                fontSize = AstrionTheme.size.micro,
                style = TextStyle(fontFeatureSettings = "tnum"),
            )
        }
    }

    @Composable
    private fun SkipPill(label: String, onClick: () -> Unit) {
        Text(
            text = label,
            color = AstrionTheme.textOnControl,
            fontSize = AstrionTheme.size.micro,
            fontWeight = FontWeight.SemiBold,
            style = TextStyle(fontFeatureSettings = "tnum"),
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .background(AstrionTheme.colors.surfacePrimary.copy(alpha = 0.65f))
                .tap(onClick = onClick)
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }

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
                .height(if (hasIcon) 44.dp else 72.dp)
                .clip(shape)
                .background(if (inFront) AstrionTheme.accentContainer else AstrionTheme.controlSunken)
                .tap(enabled = enabled, onClick = onClick)
                .padding(if (hasIcon) 3.dp else 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val alpha = if (dim) 0.5f else 1f
            val logo by rememberSampledBitmap(a["icon"] as? String, targetPx = 128)
            if (logo != null) {
                Image(logo!!, contentDescription = name, alpha = alpha, modifier = Modifier.size(28.dp))
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
                Text(name, color = color.copy(alpha = alpha), fontSize = AstrionTheme.size.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1)
            }
        }
    }

    // ---- compact: single-row transport + volume -----------------------------
    @Composable
    private fun CompactContent(
        title: String,
        artist: String,
        playing: Boolean,
        art: ImageBitmap?,
        mp: (String, Array<out Pair<String, Any?>>) -> Unit,
        enabled: Boolean,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 10.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artMod = Modifier.size(40.dp).clip(CircleShape)
            if (art != null) {
                Image(art, null, modifier = artMod, contentScale = ContentScale.Crop)
            } else {
                Box(artMod.background(AstrionTheme.colors.surfaceVariant))
            }
            Column(Modifier.weight(1f)) {
                Text(title, color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.body, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(artist, color = AstrionTheme.textSecondary, fontSize = AstrionTheme.label,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            CircleControl(Icons.Filled.VolumeDown, 40.dp, "Volume down", enabled = enabled) { mp("volume_down", emptyArray()) }
            CircleControl(
                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, 42.dp,
                description = if (playing) "Pause" else "Play", accent = true, enabled = enabled,
            ) { mp("media_play_pause", emptyArray()) }
            CircleControl(Icons.Filled.VolumeUp, 40.dp, "Volume up", enabled = enabled) { mp("volume_up", emptyArray()) }
        }
    }

    // ---- full (Media Tab 0): borderless matte player ------------------------
    @Composable
    private fun FullContent(
        ctx: CardContext,
        e: EntityState?,
        entityId: String,
        speakerName: String,
        linkToggleEntity: String,
        title: String,
        artist: String,
        playing: Boolean,
        art: ImageBitmap?,
        mp: (String, Array<out Pair<String, Any?>>) -> Unit,
        topButtons: List<Map<String, Any?>>,
        sourceEntity: String?,
        enabled: Boolean,
        showControls: Boolean,
        onSearch: () -> Unit,
        onZones: (() -> Unit)?,
        onQueue: () -> Unit,
    ) {
        val members = e?.attrStringList("group_members").orEmpty()
        val extraCount = (members.size - 1).coerceAtLeast(0)
        val baeoLinkOn = ctx.entities[linkToggleEntity]?.isOn == true
        val zoneSummary = buildList {
            add(if (extraCount > 0) "$speakerName +$extraCount" else speakerName)
            if (baeoLinkOn) add("BÆOLINK")
        }.joinToString(" · ")

        val hasProgress = e != null && (e.attrDouble("media_duration") ?: 0.0) > 0 &&
            e.attrDouble("media_position") != null

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Top utility bar: bare Search (left), Active-Zone bridge pill (centre), bare Queue (right).
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                HitTarget(visual = 34.dp, onClick = onSearch, enabled = ctx.connected) {
                    Icon(Glyphs.search, contentDescription = "Search music", tint = AstrionTheme.textOnControl, modifier = Modifier.size(19.dp))
                }
                Row(
                    modifier = Modifier
                        .clip(CircleShape)
                        .background(if (baeoLinkOn) AstrionTheme.accentContainer else AstrionTheme.colors.surfacePrimary.copy(alpha = 0.62f))
                        .tap(enabled = onZones != null) { onZones?.invoke() }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        Glyphs.speaker,
                        contentDescription = null,
                        tint = if (baeoLinkOn) AstrionTheme.accent else AstrionTheme.textSecondary,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        text = zoneSummary,
                        color = if (baeoLinkOn) AstrionTheme.accent else AstrionTheme.textOnControl,
                        fontSize = AstrionTheme.size.micro,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (onZones != null) {
                        Icon(Glyphs.fwd, contentDescription = null, tint = AstrionTheme.textMuted, modifier = Modifier.size(12.dp))
                    }
                }
                HitTarget(visual = 34.dp, onClick = onQueue, enabled = enabled) {
                    Icon(Glyphs.list, contentDescription = "Queue", tint = AstrionTheme.textOnControl, modifier = Modifier.size(19.dp))
                }
            }

            if (sourceEntity != null) CompactSourceSelect(ctx, sourceEntity)
            if (topButtons.isNotEmpty()) {
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    topButtons.forEach { b ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(36.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AstrionTheme.controlBg.copy(alpha = 0.65f))
                                .tap(enabled = enabled) { fireService(ctx, b) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                b["name"] as? String ?: "",
                                color = AstrionTheme.textPrimary,
                                fontSize = AstrionTheme.size.label,
                                fontWeight = FontWeight.Medium,
                            )
                        }
                    }
                }
            }

            // Borderless album cover capped <= 144dp so Tab 0 always fits 581.8dp with slack.
            FocusedAlbumArt(art = art, maxHeight = if (hasProgress) 122.dp else 144.dp, maxWidth = 280.dp)

            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                key(title) {
                    Text(
                        text = title,
                        color = AstrionTheme.textPrimary,
                        fontFamily = AstrionTheme.bodyFont,
                        fontSize = AstrionTheme.size.emphasis,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .basicMarquee(iterations = 1, initialDelayMillis = 1_500),
                    )
                }
                Text(
                    text = artist,
                    color = AstrionTheme.textSecondary,
                    fontSize = AstrionTheme.size.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (hasProgress) {
                    SessionProgress(ctx, entityId, topGap = 5.dp, interactive = true, enabled = enabled)
                }
            }

            if (showControls) {
                // Bare Prev / Next flanking a solid matte Hailstorm Play/Pause circle.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(26.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    HitTarget(visual = 42.dp, enabled = enabled, onClick = { mp("media_previous_track", emptyArray()) }) {
                        Icon(Glyphs.prev, contentDescription = "Previous track", tint = AstrionTheme.textPrimary, modifier = Modifier.size(22.dp))
                    }
                    CircleControl(
                        icon = if (playing) Glyphs.pause else Glyphs.play,
                        size = 48.dp,
                        description = if (playing) "Pause" else "Play",
                        accent = true,
                        enabled = enabled,
                    ) { mp("media_play_pause", emptyArray()) }
                    HitTarget(visual = 42.dp, enabled = enabled, onClick = { mp("media_next_track", emptyArray()) }) {
                        Icon(Glyphs.next, contentDescription = "Next track", tint = AstrionTheme.textPrimary, modifier = Modifier.size(22.dp))
                    }
                }
                VolumeRow(ctx, entityId, speakerName, enabled)
            }
        }
    }

    @Composable
    private fun FocusedAlbumArt(
        art: ImageBitmap?,
        maxHeight: Dp = 144.dp,
        maxWidth: Dp = 280.dp,
    ) {
        val frontShape = RoundedCornerShape(14.dp)
        val imgAspect = if (art != null && art.height > 0) {
            (art.width.toFloat() / art.height.toFloat()).coerceIn(0.5f, 2.5f)
        } else 1f

        val targetW: Dp
        val targetH: Dp
        if (maxWidth.value / maxHeight.value > imgAspect) {
            targetH = maxHeight
            targetW = maxHeight * imgAspect
        } else {
            targetW = maxWidth
            targetH = maxWidth / imgAspect
        }

        Box(
            modifier = Modifier.fillMaxWidth().height(targetH),
            contentAlignment = Alignment.Center,
        ) {
            if (art != null) {
                Image(
                    art,
                    contentDescription = null,
                    modifier = Modifier.size(width = targetW, height = targetH).clip(frontShape),
                    contentScale = ContentScale.Crop,
                )
            } else {
                Box(
                    Modifier
                        .size(width = targetW, height = targetH)
                        .clip(frontShape)
                        .background(AstrionTheme.raised),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Glyphs.note, contentDescription = "Nothing playing", tint = AstrionTheme.colors.accentMuted, modifier = Modifier.size(40.dp))
                }
            }
        }
    }

    @Composable
    private fun VolumeRow(ctx: CardContext, entityId: String, name: String, enabled: Boolean) {
        val e = ctx.entities[entityId]
        val muted = e?.attrString("is_volume_muted") == "true"
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            HitTarget(
                visual = 40.dp,
                enabled = enabled,
                onClick = {
                    ctx.client.callService(ServiceCall.of("media_player", "volume_mute", entityId, "is_volume_muted" to !muted))
                },
                face = Modifier
                    .clip(CircleShape)
                    .background(if (muted) AstrionTheme.blushContainer else AstrionTheme.colors.surfacePrimary.copy(alpha = 0.72f)),
            ) {
                Icon(
                    if (muted) Glyphs.mute else Glyphs.vol,
                    contentDescription = if (muted) "Unmute" else "Mute",
                    tint = if (muted) AstrionTheme.blush else AstrionTheme.textPrimary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Fader(
                value = e?.attrDouble("volume_level")?.toFloat(),
                onCommit = { v ->
                    ctx.client.callService(ServiceCall.of("media_player", "volume_set", entityId, "volume_level" to v.toDouble()))
                },
                modifier = Modifier.weight(1f).height(40.dp),
                enabled = enabled,
                muted = muted,
                fill = AstrionTheme.accentStrong,
                track = AstrionTheme.colors.surfacePrimary.copy(alpha = 0.72f),
                radius = 12.dp,
            ) {
                Text(name, color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.body, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }

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
                    .background(AstrionTheme.colors.controlBg.copy(alpha = 0.5f))
                    .tap(enabled = sources.isNotEmpty()) { expanded = true }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    current ?: if (sources.isEmpty()) "No sources" else "Select source…",
                    color = AstrionTheme.textPrimary,
                    fontSize = AstrionTheme.size.label,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    Icons.Filled.ArrowDropDown,
                    contentDescription = null,
                    tint = if (sources.isEmpty()) AstrionTheme.textMuted else AstrionTheme.textOnControl,
                    modifier = Modifier.size(18.dp),
                )
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
                modifier = Modifier.background(AstrionTheme.surfaceElevated).widthIn(max = 400.dp),
            ) {
                sources.forEach { s ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                s,
                                color = if (s == current) AstrionTheme.accent else AstrionTheme.textPrimary,
                                fontSize = AstrionTheme.size.body,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                            )
                        },
                        onClick = {
                            expanded = false
                            ctx.client.callService(ServiceCall.of("media_player", "select_source", entityId, "source" to s))
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun CircleControl(
        icon: ImageVector,
        size: Dp,
        description: String? = null,
        accent: Boolean = false,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (accent) AstrionTheme.accent else AstrionTheme.controlBg)
                .tap(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (accent) AstrionTheme.onAccent else AstrionTheme.textPrimary,
                modifier = Modifier.size(size * 0.44f),
            )
        }
    }
}

private enum class TvState { PLAYING, PAUSED, ON, OFF }

private data class TvSession(
    val state: TvState,
    val title: String,
    val subtitle: String? = null,
    val meta: String? = null,
    val appName: String? = null,
    val artPath: String? = null,
    val logo: String? = null,
    val logoColor: String? = null,
    val progressEntity: String? = null,
    val frontApp: String? = null,
)

private const val PLAYER_OFF_STATES = "off standby unavailable unknown"

private val POSTER_W = 86.dp
private val SQUARE_ART = 88.dp
private val WIDE_ART_W = 126.dp

private fun appSource(a: Map<String, Any?>): String? = (a["data"] as? Map<*, *>)?.get("source") as? String

private fun resolveTvSession(
    tv: EntityState?,
    players: List<EntityState>,
    apps: List<Map<String, Any?>>,
    alsoOnMatch: Map<*, *>? = null,
): TvSession {
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
    if (alsoOnMatch != null) {
        val src = alsoOnMatch["is"]?.toString()?.uppercase() ?: "HDMI"
        val title = (alsoOnMatch["title"] as? String) ?: (alsoOnMatch["name"] as? String) ?: "PC · $src"
        val subtitle = (alsoOnMatch["subtitle"] as? String) ?: "External input"
        return TvSession(
            state = TvState.ON,
            title = title,
            subtitle = subtitle,
            appName = alsoOnMatch["app_name"] as? String,
            frontApp = alsoOnMatch["is"]?.toString(),
        )
    }
    if (!tvOn) return TvSession(TvState.OFF, "TV off")
    val playing = tv?.state == "playing"
    return TvSession(
        state = if (playing) TvState.PLAYING else TvState.ON,
        title = (front?.get("name") as? String) ?: "TV",
        subtitle = if (playing) null else "Ready",
        logo = front?.get("icon") as? String,
        logoColor = front?.get("color") as? String,
        frontApp = frontPkg,
    )
}

@Composable
internal fun rememberArt(ctx: CardContext, path: String?, cacheKey: String? = path): ImageBitmap? {
    var art by remember { mutableStateOf(cacheKey?.let { ArtCache.peek(it, 480) }) }
    LaunchedEffect(cacheKey) {
        art = if (path == null || cacheKey == null) null
        else ArtCache.load(cacheKey, 480) { ctx.client.fetchBytes(path) }
    }
    return art
}

internal fun EntityState.artKey(): String? = attrString("entity_picture")?.let { path ->
    val track = attrString("media_album_name")?.takeIf { it.isNotBlank() } ?: attrString("media_title").orEmpty()
    "$path#$track"
}

/**
 * Velvety borderless ARGB_8888 artwork wash with >= 0.64 matte dark scrim so
 * bright/white album covers always hold >= 4.5:1 text contrast.
 */
@Composable
private fun ArtBackdrop(
    art: ImageBitmap?,
    washPx: Int = 32,
    bottomShade: Boolean = false,
    content: @Composable () -> Unit,
) {
    val blurred by produceState<ImageBitmap?>(initialValue = null, art, washPx) {
        value = art?.let { img ->
            withContext(Dispatchers.Default) {
                val src = img.asAndroidBitmap()
                if (src.width <= 0) return@withContext null
                val w = washPx
                val h = (w * src.height / src.width).coerceAtLeast(1)
                val scaled = Bitmap.createScaledBitmap(src, w, h, true)
                val argb = if (scaled.config == Bitmap.Config.ARGB_8888) scaled else {
                    scaled.copy(Bitmap.Config.ARGB_8888, false).also { if (scaled !== src) scaled.recycle() }
                }
                argb.asImageBitmap()
            }
        }
    }
    val shade = AstrionTheme.colors.void
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(AstrionTheme.surfaces.cardCornerRadius))
            .background(AstrionTheme.cardBg),
    ) {
        blurred?.let { bg ->
            Image(bitmap = bg, contentDescription = null, modifier = Modifier.matchParentSize(), contentScale = ContentScale.Crop)
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .background(AstrionTheme.colors.background.copy(alpha = if (bottomShade) 0.64f else 0.72f)),
            )
            if (bottomShade) {
                Box(
                    modifier = Modifier
                        .matchParentSize()
                        .background(
                            Brush.verticalGradient(
                                0.30f to shade.copy(alpha = 0.0f),
                                0.68f to shade.copy(alpha = 0.48f),
                                1.00f to shade.copy(alpha = 0.84f),
                            )
                        ),
                )
            }
        }
        content()
    }
}
