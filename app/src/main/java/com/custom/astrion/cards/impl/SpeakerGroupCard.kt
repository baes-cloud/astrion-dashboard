package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PhoneBluetoothSpeaker
import androidx.compose.material.icons.filled.Speaker
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.outlined.Speaker as SpeakerOutlined
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.roundToInt

/**
 * Sonos-style speaker group + volume controller.
 *
 * Per speaker: a type icon, the name, live volume % and a compact volume bar
 * (name row); below that, the group-membership tick (left) and mute/vol-/vol+
 * (right) on their own row. The volume bar is deliberately narrow and pushed
 * to the right — a vertical scroll gesture landing near the middle of the
 * row can't drag it, and worst case only nudges a value near its left (low)
 * end rather than jumping to max.
 *
 * Ticking a speaker immediately fires `media_player.join` against the master
 * (no apply step); unticking fires `media_player.unjoin` on that speaker.
 *
 * Group state is read from the SPEAKER's own `group_members` attribute
 * (checked = it contains the master's entity id). This is deliberate: some
 * Sonos setups expose alias entity ids inside `group_members`, so checking
 * "is the speaker in the master's list" can never match — the reverse
 * containment is robust, and it also means speakers only ever show as grouped
 * while the master is actually part of a group right now.
 *
 * Config shape:
 *   { "type": "speaker_group", "options": {
 *       "master": "media_player.living_room",
 *       "name": "Living Room",
 *       "icon": "sub",              // optional type icon for the master row
 *       "speakers": [
 *         { "entity_id": "media_player.kitchen", "name": "Kitchen", "icon": "play1" },
 *         { "entity_id": "media_player.bedroom", "name": "Bedroom", "icon": "lamp" }
 *       ]
 *   } }
 *
 * Recognised `icon` keys: "sub", "play3", "play1", "move", "lamp" — anything
 * else (or omitted) falls back to a generic speaker glyph.
 */
class SpeakerGroupCard : CardRenderer {
    override val type = "speaker_group"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val master = config.string("master") ?: return
        val speakers = (config.options["speakers"] as? List<Map<String, Any?>>) ?: emptyList()

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1B343D))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text(
                config.string("title") ?: "Speakers",
                color = Color(0xFF93AFB6),
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
        val vol = e?.attrDouble("volume_level")
        val muted = (e?.attr("is_volume_muted") as? JsonPrimitive)?.booleanOrNull == true
        val members = e?.attrStringList("group_members") ?: emptyList()
        // Grouped = this speaker's own group contains the master (and it isn't
        // just a group of itself).
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

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // Name row: type icon, name, volume % + a narrow volume bar
            // pinned to the right edge.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    speakerIcon(icon),
                    contentDescription = null,
                    tint = Color(0xFF6EA8FE),
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    label,
                    color = Color(0xFFE6F0F1),
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    when {
                        muted -> "Muted"
                        vol != null -> "${(vol * 100).roundToInt()}%"
                        else -> "—"
                    },
                    color = if (muted) Color(0xFFE79A9A) else Color(0xFF93AFB6),
                    fontSize = 12.sp,
                )
                VolumeBar(entityId, vol, muted, ctx, modifier = Modifier.width(96.dp))
            }
            // Controls row: a wide Join/Leave button filling the left space,
            // mute/vol-/vol+ on the right.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (!isMaster) {
                    JoinButton(grouped, Modifier.weight(1f), ::toggleGroup)
                } else {
                    Spacer(Modifier.weight(1f))
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SmallBtn(Icons.Filled.VolumeOff, active = muted) {
                        ctx.client.callService(
                            ServiceCall.of("media_player", "volume_mute", entityId, "is_volume_muted" to !muted)
                        )
                    }
                    SmallBtn(Icons.Filled.VolumeDown) {
                        ctx.client.callService(ServiceCall("media_player", "volume_down", entityId))
                    }
                    SmallBtn(Icons.Filled.VolumeUp) {
                        ctx.client.callService(ServiceCall("media_player", "volume_up", entityId))
                    }
                }
            }
        }
    }

    /** Tap/drag volume bar. Local state responds instantly; commits to HA. */
    @Composable
    private fun VolumeBar(
        entityId: String,
        vol: Double?,
        muted: Boolean,
        ctx: CardContext,
        modifier: Modifier = Modifier.fillMaxWidth(),
    ) {
        val level = (vol ?: 0.0).toFloat().coerceIn(0f, 1f)
        // Re-syncs to the live level whenever HA reports a new one.
        var dragLevel by remember(level) { mutableStateOf(level) }

        fun commit(fraction: Float) {
            ctx.client.callService(
                ServiceCall.of(
                    "media_player", "volume_set", entityId,
                    "volume_level" to fraction.coerceIn(0f, 1f).toDouble(),
                )
            )
        }

        BoxWithConstraints(
            modifier = modifier
                .height(24.dp) // taller touch target than the visible bar
                .pointerInput(entityId) {
                    detectTapGestures { offset ->
                        val f = (offset.x / size.width).coerceIn(0f, 1f)
                        dragLevel = f
                        commit(f)
                    }
                }
                .pointerInput(entityId) {
                    detectHorizontalDragGestures(
                        onDragEnd = { commit(dragLevel) },
                    ) { change, _ ->
                        dragLevel = (change.position.x / size.width).coerceIn(0f, 1f)
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF152B33)),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(dragLevel.coerceAtLeast(0.02f))
                        .fillMaxHeight()
                        .background(if (muted) Color(0xFF5A7783) else Color(0xFF57C4A3)),
                )
            }
            // Thumb — matches the white-circle slider style used elsewhere (lights).
            val thumbSize = 14.dp
            val thumbX = (maxWidth * dragLevel - thumbSize / 2).coerceIn(0.dp, maxWidth - thumbSize)
            Box(
                modifier = Modifier
                    .padding(start = thumbX)
                    .size(thumbSize)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
    }

    /** Wide Join/Leave button — fills the row's left space next to the mute/vol buttons. */
    @Composable
    private fun JoinButton(grouped: Boolean, modifier: Modifier, onClick: () -> Unit) {
        Box(
            modifier = modifier
                .height(34.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (grouped) Color(0xFF2C4C58) else Color(0xFF1E3841))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (grouped) "Leave group" else "Join group",
                color = if (grouped) Color(0xFF6EA8FE) else Color(0xFF93AFB6),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    @Composable
    private fun SmallBtn(icon: ImageVector, active: Boolean = false, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .size(34.dp)
                .clip(CircleShape)
                .background(if (active) Color(0xFF3A2E2E) else Color(0xFF2C4C58))
                .clickable(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = null,
                tint = if (active) Color(0xFFE06767) else Color(0xFFCBDCE0),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}
