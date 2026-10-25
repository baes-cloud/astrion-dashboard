package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
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
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.Glyphs
import com.custom.astrion.ui.LocalMinuteClock
import com.custom.astrion.ui.dimIfUnavailable
import com.custom.astrion.ui.tapAndHold

/**
 * The status strip on Main: no cards, just a row of notifications where only
 * the round icon changes with state, the name and a short state line beside
 * it. Items spread edge to edge with equal gaps between them.
 *
 * Config shape:
 *   { "type": "status_strip", "options": { "items": [
 *       { "kind": "switch", "entity_id": "input_boolean.light_automations",
 *         "name": "Automations", "icon": "automations",
 *         "on_label": "Enabled", "off_label": "Disabled" },
 *       { "kind": "lock", "entity_id": "lock.front_door",
 *         "hold_entity": "input_boolean.front_door_keep_unlocked" }
 *   ] } }
 *
 * A `switch` item toggles on tap and turns its icon hollow while off. A `lock`
 * item is named by its state (Locked, Unlocked, or Lock Override while the
 * hold entity is on), says when it last changed, locks or unlocks on tap and
 * toggles the hold entity on a long press; unlocked turns its icon Baby Doll.
 */
class StatusStripCard : CardRenderer {
    override val type = "status_strip"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val items = (config.options["items"] as? List<Map<String, Any?>>).orEmpty()
        if (items.isEmpty()) return
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            items.forEach { item ->
                when (item["kind"] as? String) {
                    "lock" -> LockItem(item, ctx)
                    else -> SwitchItem(item, ctx)
                }
            }
        }
    }

    @Composable
    private fun SwitchItem(item: Map<String, Any?>, ctx: CardContext) {
        val entityId = item["entity_id"] as? String ?: return
        val e = ctx.entities[entityId]
        val unavailable = e == null || e.isUnavailable
        val on = e?.isOn == true
        Item(
            icon = if (item["icon"] == "automations") Glyphs.automations else Glyphs.motion,
            name = item["name"] as? String ?: e?.friendlyName ?: entityId,
            state = when {
                unavailable -> "Unavailable"
                on -> item["on_label"] as? String ?: "On"
                else -> item["off_label"] as? String ?: "Off"
            },
            look = if (on) Look.ON else Look.OFF,
            unavailable = unavailable,
            live = !unavailable && ctx.connected,
            onTap = { ctx.client.toggle(entityId) },
            onHold = null,
        )
    }

    @Composable
    private fun LockItem(item: Map<String, Any?>, ctx: CardContext) {
        val entityId = item["entity_id"] as? String ?: return
        val e = ctx.entities[entityId]
        val unavailable = e == null || e.isUnavailable
        val state = e?.state.orEmpty()
        val locked = state == "locked"
        val moving = state == "locking" || state == "unlocking"
        val holdEntity = item["hold_entity"] as? String
        val override = holdEntity?.let { ctx.entity(it)?.isOn } == true
        val now = LocalMinuteClock.current
        Item(
            icon = if (locked) Glyphs.doorLocked else Glyphs.doorUnlocked,
            name = when {
                override -> "Lock Override"
                state == "locking" -> "Locking…"
                state == "unlocking" -> "Unlocking…"
                locked -> "Locked"
                unavailable -> "Door"
                else -> "Unlocked"
            },
            state = if (unavailable) "Unavailable" else ago(e?.lastChangedMs, now) ?: "",
            look = when {
                moving -> Look.OFF
                locked -> Look.ON
                else -> Look.ATTENTION
            },
            unavailable = unavailable,
            live = !unavailable && !moving && ctx.connected,
            onTap = {
                ctx.client.callService(ServiceCall(domain = "lock", service = if (locked) "unlock" else "lock", entityId = entityId))
            },
            onHold = holdEntity?.let { h ->
                { ctx.client.callService(ServiceCall(domain = "input_boolean", service = "toggle", entityId = h)) }
            },
        )
    }

    private enum class Look { ON, OFF, ATTENTION }

    @Composable
    private fun Item(
        icon: ImageVector,
        name: String,
        state: String,
        look: Look,
        unavailable: Boolean,
        live: Boolean,
        onTap: () -> Unit,
        onHold: (() -> Unit)?,
    ) {
        val (well, ink, ring) = when {
            unavailable -> Triple(Color.Transparent, AstrionTheme.unavailable, AstrionTheme.colors.hairlineStrong)
            look == Look.ATTENTION -> Triple(AstrionTheme.blush, AstrionTheme.colors.onBlush, Color.Transparent)
            look == Look.ON -> Triple(AstrionTheme.colors.accentContainer, AstrionTheme.accent, AstrionTheme.accent.copy(alpha = 0.25f))
            else -> Triple(Color.Transparent, AstrionTheme.textMuted, AstrionTheme.colors.hairlineStrong)
        }
        Row(
            modifier = Modifier
                .heightIn(min = 52.dp)
                .dimIfUnavailable(unavailable)
                .tapAndHold(enabled = live, onClick = onTap, onLongClick = { onHold?.invoke() }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(well)
                    .border(1.dp, ring, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.width(8.dp))
            Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                Text(
                    name, color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.body,
                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state,
                    color = if (look == Look.ATTENTION && !unavailable) AstrionTheme.blush else AstrionTheme.textMuted,
                    fontSize = AstrionTheme.size.label, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }

    /** "2 hrs ago" from the entity's last change (epoch millis). */
    private fun ago(lastChangedMs: Long?, nowMs: Long): String? {
        val then = lastChangedMs ?: return null
        val secs = (nowMs - then) / 1000
        if (secs < 0) return null
        return when {
            secs < 60 -> "just now"
            secs < 3600 -> "${secs / 60} min ago"
            secs < 7200 -> "1 hr ago"
            secs < 86_400 -> "${secs / 3600} hrs ago"
            else -> "${secs / 86_400} d ago"
        }
    }
}
