package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.PhoneBluetoothSpeaker
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Speaker as SpeakerOutlined
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.input.VolumeFocus
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.Fader
import com.custom.astrion.ui.Glyphs
import com.custom.astrion.ui.WordmarkTracking
import com.custom.astrion.ui.baeoWordmark
import com.custom.astrion.ui.components.BaeoSectionHeader
import com.custom.astrion.ui.components.BaeoSwitch
import com.custom.astrion.ui.dimIfUnavailable
import com.custom.astrion.ui.tap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.roundToInt

/**
 * Sonos-style speaker group + volume controller (`BÆOZONE`, v3.0 Matte Tonal):
 *  - Zero harsh borders; velvety matte `accentContainer` wash for `BÆOLINK` and
 *    linked zones, calm `cardBg` for unlinked zones.
 *  - Full-row tappable `BÆOLINK` header with accessible 48dp [BaeoSwitch].
 *  - Live Club master volume `%` pill inside [LinkCard].
 *  - Smooth matte [Fader] with `accentStrong` (linked) / `accentFillQuiet` (unlinked)
 *    and `muted = isMuted`; non-double-dimmed `#90A1AB` offline ink.
 *  - Matte `VOL KEYS` focus badge (replaces harsh 2dp border).
 *  - Responsive bounded-height fill with automatic scroll fallback if > 4 rooms.
 */
class SpeakerGroupCard : CardRenderer {
    override val type = "speaker_group"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val master = config.string("master") ?: return
        val speakers = (config.options["speakers"] as? List<Map<String, Any?>>) ?: emptyList()
        val compact = config.bool("compact", false)
        if (config.string("layout") == "list") {
            LinkList(config, ctx, master, speakers)
            return
        }

        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
            val title = config.string("title") ?: "Speakers"
            if (title.isNotEmpty()) BaeoSectionHeader(title)
            if (compact) {
                val height = config.int("height", 0)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (height > 0) Modifier.height(height.dp) else Modifier)
                        .clip(RoundedCornerShape(AstrionTheme.surfaces.cardCornerRadius))
                        .background(AstrionTheme.cardBg)
                        .padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SpeakerRow(ctx, master, config.string("name"), config.string("icon"), isMaster = true, master = master, compact = true, panel = null)
                    speakers.forEach { sp ->
                        val id = sp["entity_id"] as? String ?: return@forEach
                        SpeakerRow(
                            ctx, id, sp["name"] as? String, sp["icon"] as? String,
                            isMaster = false, master = master, compact = true,
                            panel = AstrionTheme.trackBg,
                            modifier = if (height > 0) Modifier.weight(1f) else Modifier,
                        )
                    }
                }
            } else {
                SpeakerRow(ctx, master, config.string("name"), config.string("icon"), isMaster = true, master = master)
                speakers.forEach { sp ->
                    val id = sp["entity_id"] as? String ?: return@forEach
                    SpeakerRow(ctx, id, sp["name"] as? String, sp["icon"] as? String, isMaster = false, master = master)
                }
            }
        }
    }

    @Composable
    private fun LinkList(config: CardConfig, ctx: CardContext, master: String, speakers: List<Map<String, Any?>>) {
        val explicitHeight = config.int("height", 0)
        val masterName = config.string("name") ?: ctx.entities[master]?.friendlyName ?: master
        @Suppress("UNCHECKED_CAST")
        val toggles = (config.options["toggles"] as? List<Map<String, Any?>>) ?: emptyList()
        val joined = speakers.mapNotNull { sp ->
            val id = sp["entity_id"] as? String ?: return@mapNotNull null
            val members = ctx.entities[id]?.attrStringList("group_members").orEmpty()
            if (members.contains(master) && members.size > 1) {
                id to (sp["name"] as? String ?: ctx.entities[id]?.friendlyName ?: id)
            } else null
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (explicitHeight > 0) Modifier.height(explicitHeight.dp) else Modifier.fillMaxSize()),
        ) {
            val fillEvenly = constraints.hasBoundedHeight && speakers.size <= 4
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        when {
                            fillEvenly -> Modifier.fillMaxHeight()
                            constraints.hasBoundedHeight -> Modifier.verticalScroll(rememberScrollState())
                            else -> Modifier
                        }
                    ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(
                    Modifier.pointerInput(Unit) {
                        awaitEachGesture {
                            awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                            VolumeFocus.clear()
                        }
                    },
                ) {
                    LinkCard(ctx, master, masterName, toggles.firstOrNull(), joined)
                }

                val linkedIds = joined.map { it.first }.toSet()
                val (linked, unlinked) = speakers.filter { it["entity_id"] is String }
                    .partition { (it["entity_id"] as String) in linkedIds }

                listOf("Linked" to linked, "Not linked" to unlinked).forEach { (caption, group) ->
                    if (group.isEmpty()) return@forEach
                    BaeoSectionHeader(
                        text = caption,
                        color = if (group === linked) AstrionTheme.accent else AstrionTheme.textMuted,
                    )
                    group.forEach { sp ->
                        val id = sp["entity_id"] as String
                        key(id) {
                            LinkRow(
                                ctx = ctx,
                                entityId = id,
                                name = sp["name"] as? String,
                                master = master,
                                modifier = if (fillEvenly) Modifier.weight(1f) else Modifier.height(66.dp),
                            )
                        }
                    }
                }
            }
        }
    }

    /** Borderless matte BÆOLINK hero card with full-row toggle and live Club % pill. */
    @Composable
    private fun LinkCard(
        ctx: CardContext,
        master: String,
        masterName: String,
        toggle: Map<String, Any?>?,
        joined: List<Pair<String, String>>,
    ) {
        val toggleId = toggle?.get("entity_id") as? String
        val t = toggleId?.let { ctx.entities[it] }
        val on = t?.isOn == true
        val toggleUnavailable = t == null || t.isUnavailable
        val toggleEnabled = toggleId != null && !toggleUnavailable && ctx.connected
        val m = ctx.entities[master]
        val masterVol = m?.attrDouble("volume_level")?.let { (it * 100).roundToInt() }
        val masterMuted = (m?.attr("is_volume_muted") as? JsonPrimitive)?.booleanOrNull == true
        val shape = RoundedCornerShape(AstrionTheme.surfaces.cardCornerRadius)
        val art = rememberArt(ctx, m?.attrString("entity_picture"), m?.artKey())
        val title = m?.attrString("media_title")?.takeIf { it.isNotBlank() && m.state in listOf("playing", "paused") }

        fun toggleFollow() {
            val id = toggleId ?: return
            ctx.client.callService(ServiceCall(id.substringBefore('.'), "toggle", id))
        }

        Column(
            modifier = Modifier
                // A little air under the tab bar, paid for by the tighter card below.
                .padding(top = 10.dp)
                .fillMaxWidth()
                .clip(shape)
                // Between v2 and v3 (Bae): v3's layout on a plain card, with v2's
                // thin accent outline marking it on instead of a full green wash.
                .background(AstrionTheme.cardBg)
                .then(if (on) Modifier.border(1.dp, AstrionTheme.accent.copy(alpha = 0.45f), shape) else Modifier)
                .padding(horizontal = 14.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            // Full-row tappable BÆOLINK toggle header.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .tap(enabled = toggleEnabled, onClick = ::toggleFollow),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    baeoWordmark(toggle?.get("name") as? String ?: "BÆOLINK"),
                    color = AstrionTheme.textPrimary,
                    fontFamily = AstrionTheme.headingFont,
                    fontSize = AstrionTheme.size.emphasis,
                    letterSpacing = WordmarkTracking,
                    maxLines = 1,
                )
                Text(
                    if (on) "On · follows you" else "Off · stays put",
                    color = if (on) AstrionTheme.accent else AstrionTheme.textSecondary,
                    fontSize = AstrionTheme.size.label,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                )
                if (toggleId != null) {
                    BaeoSwitch(
                        checked = on,
                        enabled = toggleEnabled,
                        onCheckedChange = { toggleFollow() },
                    )
                }
            }

            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AstrionTheme.trackBg),
                    contentAlignment = Alignment.Center,
                ) {
                    if (art != null) {
                        Image(art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                    } else {
                        Icon(Glyphs.note, contentDescription = null, tint = AstrionTheme.accent, modifier = Modifier.size(18.dp))
                    }
                }
                Column(Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            if (title != null) "NOW PLAYING" else "NOTHING PLAYING",
                            color = AstrionTheme.accent,
                            fontSize = AstrionTheme.size.micro,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.12.em,
                            maxLines = 1,
                        )
                        if (masterVol != null) {
                            Text(
                                text = if (masterMuted) "$masterName · Muted" else "$masterName $masterVol%",
                                color = if (masterMuted) AstrionTheme.blush else AstrionTheme.textSecondary,
                                fontSize = AstrionTheme.size.micro,
                                fontWeight = FontWeight.SemiBold,
                                style = TextStyle(fontFeatureSettings = "tnum"),
                                maxLines = 1,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(AstrionTheme.surfaceElevated)
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            )
                        }
                    }
                    if (title != null) {
                        Text(
                            title,
                            color = AstrionTheme.textPrimary,
                            fontSize = AstrionTheme.size.body,
                            fontWeight = FontWeight.Bold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    val names = listOf(masterName) + joined.map { it.second }
                    Text(
                        if (names.size == 1) "Just $masterName"
                        else names.dropLast(1).joinToString(", ") + " and " + names.last(),
                        color = AstrionTheme.textSecondary,
                        fontSize = AstrionTheme.size.label,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (joined.isNotEmpty()) {
                    Row(
                        modifier = Modifier
                            .height(42.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(AstrionTheme.controlBg)
                            .tap(enabled = ctx.connected) {
                                joined.forEach { (id, _) -> ctx.client.callService(ServiceCall("media_player", "unjoin", id)) }
                            }
                            .padding(horizontal = 11.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Icon(Glyphs.link, contentDescription = null, tint = AstrionTheme.textOnControl, modifier = Modifier.size(16.dp))
                        Text("Unjoin", color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    }
                }
            }
        }
    }

    /** Borderless matte speaker zone row with smooth fader and calm focus badge. */
    @Composable
    private fun LinkRow(
        ctx: CardContext,
        entityId: String,
        name: String?,
        master: String,
        modifier: Modifier,
    ) {
        val e = ctx.entities[entityId]
        val label = name ?: e?.friendlyName ?: entityId
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        val vol = e?.attrDouble("volume_level")?.toFloat()
        val muted = (e?.attr("is_volume_muted") as? JsonPrimitive)?.booleanOrNull == true
        val members = e?.attrStringList("group_members") ?: emptyList()
        val grouped = members.contains(master) && members.size > 1
        val focused = VolumeFocus.selected.collectAsState().value == entityId
        val shape = RoundedCornerShape(AstrionTheme.surfaces.cardCornerRadius)

        fun toggleGroup() {
            if (grouped) {
                ctx.client.callService(ServiceCall("media_player", "unjoin", entityId))
            } else {
                ctx.client.callService(
                    ServiceCall(
                        "media_player", "join", master,
                        mapOf("group_members" to JsonArray(listOf(JsonPrimitive(entityId)))),
                    )
                )
            }
        }

        val rowBg = when {
            focused -> AstrionTheme.surfaceElevated
            grouped -> AstrionTheme.accentContainer
            else -> AstrionTheme.cardBg
        }

        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(rowBg)
                .pointerInput(entityId) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                        if (!unavailable) VolumeFocus.select(entityId)
                    }
                }
                .padding(7.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Fader(
                value = if (unavailable) null else vol,
                onCommit = { v ->
                    ctx.client.callService(ServiceCall.of("media_player", "volume_set", entityId, "volume_level" to v.toDouble()))
                },
                modifier = Modifier.weight(1f).fillMaxHeight(),
                enabled = live,
                // Bae: keep the pink volume in Zones.
                fill = if (grouped) AstrionTheme.blush.copy(alpha = 0.55f) else AstrionTheme.blush.copy(alpha = 0.38f),
                track = AstrionTheme.trackBg,
                radius = 12.dp,
                muted = muted,
            ) {
                Icon(
                    Glyphs.speaker,
                    contentDescription = null,
                    tint = when {
                        unavailable -> AstrionTheme.unavailable
                        grouped -> AstrionTheme.textPrimary
                        else -> AstrionTheme.textSecondary
                    },
                    modifier = Modifier.size(18.dp),
                )
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        text = if (unavailable) "$label · Offline" else label,
                        color = if (unavailable) AstrionTheme.unavailable else AstrionTheme.textPrimary,
                        fontSize = AstrionTheme.size.body,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (focused && !unavailable) {
                        Text(
                            text = "VOL KEYS",
                            color = AstrionTheme.blush,
                            fontSize = AstrionTheme.size.micro,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.08.em,
                            maxLines = 1,
                        )
                    }
                }
            }

            Row(
                modifier = Modifier
                    .width(84.dp)
                    .fillMaxHeight()
                    .dimIfUnavailable(unavailable)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (grouped) AstrionTheme.accentStrong else AstrionTheme.controlBg)
                    .tap(enabled = live, onClick = ::toggleGroup),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterHorizontally),
            ) {
                Icon(
                    Glyphs.link,
                    contentDescription = if (grouped) "Remove $label from group" else "Add $label to group",
                    tint = if (grouped) AstrionTheme.colors.onPrimary else AstrionTheme.textSecondary,
                    modifier = Modifier.size(16.dp),
                )
                Text(
                    if (grouped) "Joined" else "Join",
                    color = if (grouped) AstrionTheme.colors.onPrimary else AstrionTheme.textSecondary,
                    fontSize = AstrionTheme.size.label,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                )
            }
        }
    }

    private fun speakerIcon(key: String?): ImageVector = when (key) {
        "sub" -> Icons.Filled.GraphicEq
        "play3" -> Icons.Filled.Speaker
        "play1" -> Icons.Outlined.SpeakerOutlined
        "move" -> Icons.Filled.PhoneBluetoothSpeaker
        "lamp" -> Icons.Filled.Lightbulb
        else -> Icons.Filled.Speaker
    }

    @Composable
    private fun SpeakerRow(
        ctx: CardContext,
        entityId: String,
        name: String?,
        icon: String?,
        isMaster: Boolean,
        master: String,
        compact: Boolean = false,
        panel: Color? = AstrionTheme.cardBg,
        modifier: Modifier = Modifier,
    ) {
        val e = ctx.entities[entityId]
        val label = name ?: e?.friendlyName ?: entityId
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        val vol = e?.attrDouble("volume_level")
        val muted = (e?.attr("is_volume_muted") as? JsonPrimitive)?.booleanOrNull == true
        val members = e?.attrStringList("group_members") ?: emptyList()
        val grouped = !isMaster && members.contains(master) && members.size > 1

        fun toggleGroup() {
            if (grouped) {
                ctx.client.callService(ServiceCall("media_player", "unjoin", entityId))
            } else {
                ctx.client.callService(
                    ServiceCall(
                        "media_player", "join", master,
                        mapOf("group_members" to JsonArray(listOf(JsonPrimitive(entityId)))),
                    )
                )
            }
        }

        Column(
            modifier = modifier
                .fillMaxWidth()
                .then(
                    if (panel == null) Modifier
                    else Modifier
                        .clip(RoundedCornerShape(if (compact) 14.dp else AstrionTheme.surfaces.cardCornerRadius))
                        .background(panel)
                        .padding(if (compact) 9.dp else 12.dp)
                ),
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp, Alignment.CenterVertically),
        ) {
            val btnHeight = when {
                compact && isMaster -> 40.dp
                compact -> 38.dp
                else -> 44.dp
            }
            @Composable
            fun Buttons(fill: Boolean) {
                Row(
                    modifier = (if (fill) Modifier.fillMaxWidth() else Modifier).dimIfUnavailable(unavailable),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val each = if (fill) Modifier.weight(1f) else Modifier.width(52.dp)
                    WideBtn(
                        Icons.Filled.VolumeOff,
                        description = if (muted) "Unmute $label" else "Mute $label",
                        active = muted,
                        enabled = live,
                        height = btnHeight,
                        modifier = each,
                    ) {
                        ctx.client.callService(
                            ServiceCall.of("media_player", "volume_mute", entityId, "is_volume_muted" to !muted)
                        )
                    }
                    WideBtn(
                        Icons.Filled.VolumeDown,
                        description = "$label volume down",
                        enabled = live,
                        height = btnHeight,
                        modifier = each,
                    ) {
                        ctx.client.callService(ServiceCall("media_player", "volume_down", entityId))
                    }
                    WideBtn(
                        Icons.Filled.VolumeUp,
                        description = "$label volume up",
                        enabled = live,
                        height = btnHeight,
                        modifier = each,
                    ) {
                        ctx.client.callService(ServiceCall("media_player", "volume_up", entityId))
                    }
                }
            }
            val singleRow = compact && isMaster
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    speakerIcon(icon),
                    contentDescription = null,
                    tint = if (unavailable) AstrionTheme.unavailable else AstrionTheme.accent,
                    modifier = Modifier.size(22.dp),
                )
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        label,
                        color = AstrionTheme.textPrimary,
                        fontSize = AstrionTheme.size.body,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Text(
                        when {
                            unavailable -> "Unavailable"
                            muted -> "Muted"
                            vol != null -> "${(vol * 100).roundToInt()}%"
                            else -> "—"
                        },
                        color = when {
                            unavailable -> AstrionTheme.unavailable
                            muted -> AstrionTheme.colors.blush
                            else -> AstrionTheme.textSecondary
                        },
                        fontSize = AstrionTheme.label,
                        fontWeight = if (unavailable) FontWeight.Medium else FontWeight.Normal,
                        maxLines = 1,
                    )
                }
                if (singleRow) {
                    Buttons(fill = false)
                } else if (isMaster) {
                    MasterChip()
                } else {
                    JoinToggle(grouped, enabled = live, label = label, compact = compact, onClick = ::toggleGroup)
                }
            }

            LevelBar(level = (vol ?: 0.0).toFloat(), muted = muted, unavailable = unavailable)
            if (!singleRow) Buttons(fill = true)
        }
    }

    @Composable
    private fun JoinToggle(
        grouped: Boolean,
        enabled: Boolean,
        label: String,
        compact: Boolean = false,
        onClick: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .height(if (compact) 32.dp else 40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (grouped) AstrionTheme.accentStrong else AstrionTheme.controlBg)
                .tap(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (grouped) Icons.Filled.Link else Icons.Filled.LinkOff,
                contentDescription = if (grouped) "Remove $label from group" else "Add $label to group",
                tint = if (grouped) AstrionTheme.colors.onPrimary else AstrionTheme.textMuted,
                modifier = Modifier.size(18.dp),
            )
            Text(
                if (grouped) "Leave" else "Join",
                color = if (grouped) AstrionTheme.colors.onPrimary else AstrionTheme.textMuted,
                fontSize = AstrionTheme.size.label,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    @Composable
    private fun MasterChip() {
        Row(
            modifier = Modifier
                .height(40.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(AstrionTheme.cardBgAlt)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Filled.GraphicEq, contentDescription = "Group master", tint = AstrionTheme.textMuted, modifier = Modifier.size(18.dp))
            Text("Master", color = AstrionTheme.textMuted, fontSize = AstrionTheme.size.label, fontWeight = FontWeight.SemiBold)
        }
    }

    @Composable
    private fun LevelBar(
        level: Float,
        muted: Boolean,
        unavailable: Boolean,
        track: Color = AstrionTheme.trackBg,
        thickness: Dp = 5.dp,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(thickness)
                .clip(RoundedCornerShape(3.dp))
                .background(track),
        ) {
            if (!unavailable) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(level.coerceIn(0f, 1f).coerceAtLeast(0.02f))
                        .fillMaxHeight()
                        .background(if (muted) AstrionTheme.colors.textMuted else AstrionTheme.accent),
                )
            }
        }
    }

    @Composable
    private fun WideBtn(
        icon: ImageVector,
        description: String,
        modifier: Modifier = Modifier,
        active: Boolean = false,
        enabled: Boolean = true,
        height: Dp = 44.dp,
        onClick: () -> Unit,
    ) {
        Box(
            modifier = modifier
                .height(height)
                .clip(RoundedCornerShape(12.dp))
                .background(if (active) AstrionTheme.blushContainer else AstrionTheme.controlBg)
                .tap(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (active) AstrionTheme.blush else AstrionTheme.textOnControl,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
