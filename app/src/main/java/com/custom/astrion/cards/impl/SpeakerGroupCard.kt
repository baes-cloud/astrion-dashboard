package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.NightsStay
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.WordmarkTracking
import com.custom.astrion.ui.baeoWordmark
import com.custom.astrion.ui.dimIfUnavailable
import com.custom.astrion.ui.tap
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.roundToInt

/**
 * Sonos-style speaker group + volume controller.
 *
 * Each speaker gets its own card. Inside it: the type icon, the name, and the
 * current level immediately beside the name, with the join/leave control on the
 * right; a read-only level bar under that; then mute / vol- / vol+ spread
 * evenly across the full width.
 *
 * The interactive volume slider is deliberately gone. It was a 24dp-tall
 * drag/tap target that committed instantly on any touch, sitting inside a
 * vertically-scrolling page — so a scroll that started slightly sideways
 * changed the volume, and there was no undo. The level is still shown, as a
 * percentage and as a non-interactive bar; changing it goes through the
 * step buttons, which are now 44dp tall and a third of the card wide each.
 *
 * Unavailable speakers are dimmed, labelled, and have their controls disabled.
 * That matters here specifically: `media_player.bathroom_sonos` in this install
 * is frequently `unavailable`, and it used to render identically to a working
 * speaker — so its volume buttons appeared to do nothing, with no explanation.
 *
 * Ticking a speaker fires `media_player.join` against the master (no apply
 * step); unticking fires `media_player.unjoin` on that speaker.
 *
 * Group state is read from the SPEAKER's own `group_members` attribute
 * (grouped = it contains the master's entity id). This is deliberate: some
 * Sonos setups expose alias entity ids inside `group_members`, so checking
 * "is the speaker in the master's list" can never match — the reverse
 * containment is robust.
 *
 * Config shape:
 *   { "type": "speaker_group", "options": {
 *       "master": "media_player.living_room",
 *       "name": "Living Room",
 *       "icon": "sub",
 *       "speakers": [ { "entity_id": …, "name": …, "icon": … }, … ]
 *   } }
 *
 * Recognised `icon` keys: "sub", "play3", "play1", "move", "lamp".
 *
 * `"compact": true` fits the group on one screen: the master becomes a single
 * row (name and level on the left, mute / vol- / vol+ on the right, level bar
 * underneath) at about half the height, and every card is a little tighter.
 * In compact mode the master's card holds the others: its controls run
 * across the top and each speaker is a darker panel inside it. `"height"`
 * (dp) fixes the whole card's height and the panels share what's left, so the
 * group fills the screen exactly (the page scrolls, so it can't be measured).
 *
 * `"layout": "list"` is the Link tab: the master is a title only (name and
 * level, no controls) with optional `"toggles"` pills under it
 * (`[{ "entity_id", "name", "icon": "music" | "night" }]`, each toggled with
 * `<domain>.toggle`). Each speaker is one row: name over a level bar, then
 * vol- / vol+ / link. No mute. A linked speaker's row is outlined in the
 * accent, so the group reads at a glance. `"height"` works as in compact.
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

        // Each speaker is its own card rather than a row inside one big one:
        // at 349dp wide a stacked group ran long enough that speakers blurred
        // together, and the per-speaker controls read as belonging to whichever
        // name happened to be nearest.
        Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 10.dp)) {
            // "title": "" hides the heading (e.g. under a tab that already names it).
            val title = config.string("title") ?: "Speakers"
            if (title.isNotEmpty()) {
                Text(
                    title,
                    color = AstrionTheme.textSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                )
            }
            if (compact) {
                val height = config.int("height", 0)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (height > 0) Modifier.height(height.dp) else Modifier)
                        .clip(RoundedCornerShape(18.dp))
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
        val height = config.int("height", 0)
        val m = ctx.entities[master]
        val mVol = m?.attrDouble("volume_level")
        val mUnavailable = m == null || m.isUnavailable
        @Suppress("UNCHECKED_CAST")
        val toggles = (config.options["toggles"] as? List<Map<String, Any?>>) ?: emptyList()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (height > 0) Modifier.height(height.dp) else Modifier),
            // No card of its own: the header and the speaker cards sit
            // straight on the page.
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // One line: the master's name and level, then the toggles on the
            // right. The level is set like the speakers' own, not bold.
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 4.dp, top = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                // Same as the Aircon card's title.
                Text(
                    config.string("name") ?: m?.friendlyName ?: master,
                    color = AstrionTheme.textPrimary,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 17.sp,
                )
                Text(
                    when {
                        mUnavailable -> "Unavailable"
                        mVol != null -> "${(mVol * 100).roundToInt()}%"
                        else -> "—"
                    },
                    color = if (mUnavailable) AstrionTheme.unavailable else AstrionTheme.textSecondary,
                    fontSize = 14.sp,
                    modifier = Modifier.weight(1f),
                )
                toggles.forEach { t ->
                    val id = t["entity_id"] as? String ?: return@forEach
                    TogglePill(ctx, id, t["name"] as? String, t["icon"] as? String, Modifier)
                }
            }
            speakers.forEach { sp ->
                val id = sp["entity_id"] as? String ?: return@forEach
                LinkRow(
                    ctx, id, sp["name"] as? String, sp["icon"] as? String, master,
                    if (height > 0) Modifier.weight(1f) else Modifier,
                )
            }
        }
    }

    /** A toggle such as Follow me: icon + word (or a wordmark), filled Gunmetal when on. */
    @Composable
    private fun TogglePill(ctx: CardContext, entityId: String, name: String?, icon: String?, modifier: Modifier) {
        val e = ctx.entities[entityId]
        val on = e?.isOn == true
        val unavailable = e == null || e.isUnavailable
        Row(
            modifier = modifier
                .height(if (icon == "wordmark") 34.dp else 38.dp)
                .dimIfUnavailable(unavailable)
                .clip(RoundedCornerShape(12.dp))
                .background(if (on) AstrionTheme.accentStrong else AstrionTheme.controlSunken)
                .tap(enabled = !unavailable && ctx.connected) {
                    ctx.client.callService(ServiceCall(entityId.substringBefore('.'), "toggle", entityId))
                }
                .padding(horizontal = if (icon == "wordmark") 10.dp else 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally),
        ) {
            val label = name ?: e?.friendlyName ?: entityId
            // "icon": "wordmark" sets the name as a Bæo wordmark (BÆOLINK),
            // with no glyph, in the footer's Syne and tracking.
            if (icon == "wordmark") {
                Text(
                    baeoWordmark(label),
                    // One step of start padding centres the trailing tracking.
                    modifier = Modifier.padding(start = 3.dp),
                    color = if (on) AstrionTheme.textPrimary else AstrionTheme.textSecondary,
                    fontFamily = AstrionTheme.headingFont,
                    fontWeight = FontWeight.Normal,
                    fontSize = 12.sp,
                    letterSpacing = WordmarkTracking,
                    maxLines = 1,
                )
                return@Row
            }
            Icon(
                if (icon == "night") Icons.Filled.NightsStay else Icons.Filled.MusicNote,
                contentDescription = null,
                tint = if (on) AstrionTheme.blush else AstrionTheme.textMuted,
                modifier = Modifier.size(18.dp),
            )
            Text(
                label,
                color = if (on) AstrionTheme.textPrimary else AstrionTheme.textSecondary,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
            )
        }
    }

    /** One speaker on the Link tab: name over its level, then - / + / link. */
    @Composable
    private fun LinkRow(
        ctx: CardContext,
        entityId: String,
        name: String?,
        icon: String?,
        master: String,
        modifier: Modifier,
    ) {
        val e = ctx.entities[entityId]
        val label = name ?: e?.friendlyName ?: entityId
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        val vol = e?.attrDouble("volume_level")
        val members = e?.attrStringList("group_members") ?: emptyList()
        val grouped = members.contains(master) && members.size > 1
        val shape = RoundedCornerShape(18.dp)
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
        // Two lines: name, level and link on top; vol- / level bar / vol+
        // under it, so the buttons sit either side of what they change.
        Column(
            modifier = modifier
                .fillMaxWidth()
                .dimIfUnavailable(unavailable)
                .clip(shape)
                .background(AstrionTheme.cardBg)
                .then(if (grouped) Modifier.border(1.5.dp, AstrionTheme.accentStrong, shape) else Modifier)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    speakerIcon(icon),
                    contentDescription = null,
                    tint = when {
                        unavailable -> AstrionTheme.unavailable
                        grouped -> AstrionTheme.accent
                        else -> AstrionTheme.textMuted
                    },
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    label,
                    color = AstrionTheme.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when {
                        unavailable -> "Unavailable"
                        vol != null -> "${(vol * 100).roundToInt()}%"
                        else -> "—"
                    },
                    color = if (unavailable) AstrionTheme.unavailable else AstrionTheme.textSecondary,
                    fontSize = 14.sp,
                    maxLines = 1,
                )
                Row(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (grouped) AstrionTheme.accentStrong else AstrionTheme.controlSunken)
                        .tap(enabled = live, onClick = ::toggleGroup)
                        .padding(horizontal = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        if (grouped) Icons.Filled.Link else Icons.Filled.LinkOff,
                        contentDescription = if (grouped) "Remove $label from group" else "Add $label to group",
                        tint = if (grouped) AstrionTheme.textPrimary else AstrionTheme.textMuted,
                        modifier = Modifier.size(17.dp),
                    )
                    Text(
                        if (grouped) "Linked" else "Join",
                        color = if (grouped) AstrionTheme.textPrimary else AstrionTheme.textMuted,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                WideBtn(Icons.Filled.VolumeDown, "$label volume down", Modifier.width(56.dp), enabled = live, height = 38.dp) {
                    ctx.client.callService(ServiceCall("media_player", "volume_down", entityId))
                }
                Box(Modifier.weight(1f)) {
                    LevelBar(
                        level = (vol ?: 0.0).toFloat(), muted = false, unavailable = unavailable,
                        track = AstrionTheme.controlSunken, thickness = 6.dp,
                    )
                }
                WideBtn(Icons.Filled.VolumeUp, "$label volume up", Modifier.width(56.dp), enabled = live, height = 38.dp) {
                    ctx.client.callService(ServiceCall("media_player", "volume_up", entityId))
                }
            }
        }
    }

    /** Maps a config "icon" key to a recognisable speaker-type glyph. */
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
        /** Card fill; null draws no card of its own (the master inside its container). */
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
                .dimIfUnavailable(unavailable)
                .then(
                    if (panel == null) Modifier
                    else Modifier
                        .clip(RoundedCornerShape(if (compact) 14.dp else 18.dp))
                        .background(panel)
                        .padding(if (compact) 9.dp else 12.dp)
                ),
            // Centred, so a panel given extra height keeps its controls together.
            verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp, Alignment.CenterVertically),
        ) {
            // Compact panels share a fixed-height card; 38dp is what still
            // fits three speakers without the buttons being squeezed flat.
            val btnHeight = when {
                compact && isMaster -> 40.dp
                compact -> 38.dp
                else -> 44.dp
            }
            @Composable
            fun Buttons(fill: Boolean) {
                Row(
                    modifier = if (fill) Modifier.fillMaxWidth() else Modifier,
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
            // Name row: type icon, name, level right beside it, and the
            // join/leave control on the right where the level used to sit.
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
                // Name + level share one flexible slot so the level sits
                // directly against the name. They must be nested rather than
                // being two weighted siblings of the spacer — two weights in
                // the same row split the space evenly, which truncated names
                // that had plenty of room ("Living Ro…").
                Row(
                    modifier = Modifier.weight(1f),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        label,
                        color = AstrionTheme.textPrimary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        // Shrinks only when it genuinely runs out of room.
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
                            muted -> Color(0xFFE79A9A)
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

            // Read-only level bar. Shows where the volume is without offering a
            // drag target that a page scroll can catch.
            LevelBar(level = (vol ?: 0.0).toFloat(), muted = muted, unavailable = unavailable)

            // Controls: three equal buttons across the full width.
            if (!singleRow) Buttons(fill = true)
        }
    }

    /**
     * Join/leave toggle. Carries its state in the glyph (linked vs broken
     * link) and in a word, not colour alone.
     */
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
                .background(if (grouped) AstrionTheme.controlBg else AstrionTheme.cardBgAlt)
                .tap(enabled = enabled, onClick = onClick)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (grouped) Icons.Filled.Link else Icons.Filled.LinkOff,
                contentDescription = if (grouped) "Remove $label from group" else "Add $label to group",
                tint = if (grouped) AstrionTheme.accent else AstrionTheme.textMuted,
                modifier = Modifier.size(18.dp),
            )
            Text(
                if (grouped) "Leave" else "Join",
                color = if (grouped) AstrionTheme.accent else AstrionTheme.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    /** Non-interactive marker for the speaker the group is anchored on. */
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
            Icon(
                Icons.Filled.GraphicEq,
                contentDescription = "Group master",
                tint = AstrionTheme.textMuted,
                modifier = Modifier.size(18.dp),
            )
            Text(
                "Master",
                color = AstrionTheme.textMuted,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    /** Non-interactive volume level indicator. */
    @Composable
    private fun LevelBar(
        level: Float,
        muted: Boolean,
        unavailable: Boolean,
        track: Color = AstrionTheme.trackBg,
        thickness: androidx.compose.ui.unit.Dp = 5.dp,
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
                        .background(if (muted) Color(0xFF718583) else AstrionTheme.good),
                )
            }
        }
    }

    /** Full-height control button that shares the row width equally. */
    @Composable
    private fun WideBtn(
        icon: ImageVector,
        description: String,
        modifier: Modifier = Modifier,
        active: Boolean = false,
        enabled: Boolean = true,
        height: androidx.compose.ui.unit.Dp = 44.dp,
        onClick: () -> Unit,
    ) {
        Box(
            modifier = modifier
                .height(height)
                .clip(RoundedCornerShape(12.dp))
                .background(if (active) AstrionTheme.dangerBg else AstrionTheme.controlBg)
                .tap(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (active) AstrionTheme.danger else AstrionTheme.textOnControl,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}
