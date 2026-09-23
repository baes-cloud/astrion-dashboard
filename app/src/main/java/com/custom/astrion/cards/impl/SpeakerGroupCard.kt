package com.custom.astrion.cards.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LinkOff
import androidx.compose.material.icons.filled.Radio
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionButton
import com.custom.astrion.ui.AstrionCard
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.LevelBar
import com.custom.astrion.ui.SectionLabel
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.StateKind
import com.custom.astrion.ui.StateLine
import com.custom.astrion.ui.Tone
import com.custom.astrion.ui.Touch
import com.custom.astrion.ui.UnavailableBadge
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.roundToInt
import com.custom.astrion.ui.rememberAction
import com.custom.astrion.ui.rememberOptimistic

/**
 * Sonos group + volume: one card per speaker — type icon, name, level,
 * join/leave on the right; a read-only level bar; then mute / vol− / vol+
 * across the full width (44dp tall, a third of the card each).
 *
 * - Join / Leave is a real button (it used to be drawn in the "muted"
 *   colour at 2.6:1 and read as disabled). Grouping is optimistic.
 * - Mute shows the STATE: speaker glyph when sound is on, struck-through red
 *   when muted — and flips optimistically.
 * - The "Master" marker is plain text, visibly not a button.
 * - Unavailable speakers say so (lilac, with an icon) and their controls are
 *   dimmed and inert.
 *
 * Ticking fires `media_player.join` on the master; leaving fires
 * `media_player.unjoin` on the speaker. Group state is read from the
 * SPEAKER's own `group_members` (robust to alias ids).
 *
 * Config: { "type": "speaker_group", "options": {
 *     "title": "Club Group", "master": "media_player.club", "name": "Club", "icon": "sub",
 *     "speakers": [ { "entity_id": …, "name": …, "icon": "play3|play1|move|lamp|sub" } ] } }
 */
class SpeakerGroupCard : CardRenderer {
    override val type = "speaker_group"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val master = config.string("master") ?: return
        val speakers = (config.options["speakers"] as? List<Map<String, Any?>>) ?: emptyList()

        Column(verticalArrangement = Arrangement.spacedBy(Space.gutter)) {
            SectionLabel(config.string("title") ?: "Speakers")
            SpeakerRow(ctx, master, config.string("name"), config.string("icon"), isMaster = true, master = master)
            speakers.forEach { sp ->
                val id = sp["entity_id"] as? String ?: return@forEach
                SpeakerRow(ctx, id, sp["name"] as? String, sp["icon"] as? String, isMaster = false, master = master)
            }
        }
    }

    /** Speaker-type glyph. None of these is a light bulb (that means "light" here). */
    private fun speakerIcon(key: String?): ImageVector = when (key) {
        "sub" -> Icons.Filled.GraphicEq
        "play3" -> Icons.Filled.Speaker
        "play1" -> Icons.Outlined.SpeakerOutlined
        "move" -> Icons.Filled.Radio
        "lamp" -> Icons.Outlined.SpeakerOutlined
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
        val e = ctx.entity(entityId)
        val label = name ?: e?.friendlyName ?: entityId
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        val vol = e?.attrDouble("volume_level")
        val actualMuted = (e?.attr("is_volume_muted") as? JsonPrimitive)?.booleanOrNull == true
        val members = e?.attrStringList("group_members") ?: emptyList()
        val actualGrouped = !isMaster && members.contains(master) && members.size > 1

        val muteOpt = rememberOptimistic(actualMuted)
        val muted = muteOpt.show(actualMuted)
        val groupOpt = rememberOptimistic(actualGrouped)
        val grouped = groupOpt.show(actualGrouped)
        val muteAction = rememberAction(ctx)
        val volAction = rememberAction(ctx)
        val groupAction = rememberAction(ctx)

        AstrionCard(padding = androidx.compose.foundation.layout.PaddingValues(Space.m)) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        speakerIcon(icon),
                        contentDescription = null,
                        tint = if (unavailable) AstrionTheme.unavailable else AstrionTheme.accent,
                        modifier = Modifier.size(22.dp),
                    )
                    Spacer(Modifier.width(Space.s))
                    Row(
                        modifier = Modifier.weight(1f),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.s),
                    ) {
                        Text(
                            label, style = AstrionType.title, color = AstrionTheme.textPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false),
                        )
                        when {
                            unavailable -> UnavailableBadge()
                            muted -> StateLine("Muted", StateKind.Danger)
                            vol != null -> StateLine("${(vol * 100).roundToInt()}%")
                            else -> StateLine("—")
                        }
                    }
                    if (isMaster) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Filled.GraphicEq, contentDescription = null,
                                tint = AstrionTheme.textMuted, modifier = Modifier.size(16.dp),
                            )
                            Spacer(Modifier.width(Space.xs))
                            Text("Group master", style = AstrionType.label, color = AstrionTheme.textSecondary)
                        }
                    } else {
                        AstrionButton(
                            onClick = {
                                val join = !grouped
                                groupOpt.set(join)
                                val call = if (join) {
                                    ServiceCall(
                                        "media_player", "join", master,
                                        mapOf("group_members" to JsonArray(listOf(JsonPrimitive(entityId)))),
                                    )
                                } else {
                                    ServiceCall("media_player", "unjoin", entityId)
                                }
                                groupAction.run(call, onFail = { groupOpt.clear() })
                            },
                            label = if (grouped) "Leave" else "Join",
                            icon = if (grouped) Icons.Filled.LinkOff else Icons.Filled.Link,
                            tone = if (grouped) Tone.Neutral else Tone.Accent,
                            enabled = live,
                            pending = groupAction.busy,
                            failed = groupAction.failed,
                            height = Touch.compact,
                            textStyle = AstrionType.label,
                            iconSize = 18.dp,
                            description = if (grouped) "$label is grouped. Remove from group" else "Add $label to group",
                        )
                    }
                }

                // Read-only level: no drag target for a page scroll to catch.
                LevelBar(
                    fraction = if (unavailable) 0f else (vol ?: 0.0).toFloat(),
                    color = if (muted) AstrionTheme.textMuted else AstrionTheme.good,
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    AstrionButton(
                        onClick = {
                            val want = !muted
                            muteOpt.set(want)
                            muteAction.run(
                                ServiceCall.of("media_player", "volume_mute", entityId, "is_volume_muted" to want),
                                onFail = { muteOpt.clear() },
                            )
                        },
                        modifier = Modifier.weight(1f),
                        icon = if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                        tone = if (muted) Tone.Danger else Tone.Neutral,
                        enabled = live,
                        pending = muteAction.busy,
                        failed = muteAction.failed,
                        height = Touch.compact,
                        description = if (muted) "$label muted. Unmute" else "Mute $label",
                    )
                    AstrionButton(
                        onClick = { volAction.run(ServiceCall("media_player", "volume_down", entityId)) },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.VolumeDown,
                        enabled = live,
                        pending = volAction.busy,
                        failed = volAction.failed,
                        height = Touch.compact,
                        description = "$label volume down",
                    )
                    AstrionButton(
                        onClick = { volAction.run(ServiceCall("media_player", "volume_up", entityId)) },
                        modifier = Modifier.weight(1f),
                        icon = Icons.Filled.VolumeUp,
                        enabled = live,
                        pending = volAction.busy,
                        failed = volAction.failed,
                        height = Touch.compact,
                        description = "$label volume up",
                    )
                }
            }
        }
    }
}
