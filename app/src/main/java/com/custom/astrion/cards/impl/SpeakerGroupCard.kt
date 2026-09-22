package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
 */
class SpeakerGroupCard : CardRenderer {
    override val type = "speaker_group"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val master = config.string("master") ?: return
        val speakers = (config.options["speakers"] as? List<Map<String, Any?>>) ?: emptyList()

        // Each speaker is its own card rather than a row inside one big one:
        // at 349dp wide a stacked group ran long enough that speakers blurred
        // together, and the per-speaker controls read as belonging to whichever
        // name happened to be nearest.
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                config.string("title") ?: "Speakers",
                color = AstrionTheme.textSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 1.sp,
            )
            SpeakerRow(ctx, master, config.string("name"), config.string("icon"), isMaster = true, master = master)
            speakers.forEach { sp ->
                val id = sp["entity_id"] as? String ?: return@forEach
                SpeakerRow(ctx, id, sp["name"] as? String, sp["icon"] as? String, isMaster = false, master = master)
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
            modifier = Modifier
                .fillMaxWidth()
                .dimIfUnavailable(unavailable)
                .clip(RoundedCornerShape(18.dp))
                .background(AstrionTheme.cardBg)
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
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
                if (isMaster) {
                    MasterChip()
                } else {
                    JoinToggle(grouped, enabled = live, label = label, onClick = ::toggleGroup)
                }
            }

            // Read-only level bar. Shows where the volume is without offering a
            // drag target that a page scroll can catch.
            LevelBar(level = (vol ?: 0.0).toFloat(), muted = muted, unavailable = unavailable)

            // Controls: three equal buttons across the full width.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                WideBtn(
                    Icons.Filled.VolumeOff,
                    description = if (muted) "Unmute $label" else "Mute $label",
                    active = muted,
                    enabled = live,
                    modifier = Modifier.weight(1f),
                ) {
                    ctx.client.callService(
                        ServiceCall.of("media_player", "volume_mute", entityId, "is_volume_muted" to !muted)
                    )
                }
                WideBtn(
                    Icons.Filled.VolumeDown,
                    description = "$label volume down",
                    enabled = live,
                    modifier = Modifier.weight(1f),
                ) {
                    ctx.client.callService(ServiceCall("media_player", "volume_down", entityId))
                }
                WideBtn(
                    Icons.Filled.VolumeUp,
                    description = "$label volume up",
                    enabled = live,
                    modifier = Modifier.weight(1f),
                ) {
                    ctx.client.callService(ServiceCall("media_player", "volume_up", entityId))
                }
            }
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
        onClick: () -> Unit,
    ) {
        Row(
            modifier = Modifier
                .height(40.dp)
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
    private fun LevelBar(level: Float, muted: Boolean, unavailable: Boolean) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(AstrionTheme.trackBg),
        ) {
            if (!unavailable) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(level.coerceIn(0f, 1f).coerceAtLeast(0.02f))
                        .fillMaxHeight()
                        .background(if (muted) Color(0xFF5A7783) else AstrionTheme.good),
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
        onClick: () -> Unit,
    ) {
        Box(
            modifier = modifier
                .height(44.dp)
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
