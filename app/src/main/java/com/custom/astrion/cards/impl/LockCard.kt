package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.UnavailableLabel
import com.custom.astrion.ui.dimIfUnavailable
import com.custom.astrion.ui.holdOnly
import com.custom.astrion.ui.humanise
import com.custom.astrion.ui.tap
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Door-lock card: current state, how long it has been that way, and a two-chip
 * segmented control that locks or unlocks it.
 *
 * A single toggle button was the obvious shape, but a lock is the one control
 * in the house where firing the wrong way costs something real — so the two
 * states are always both on screen with the live one lit, and a tap names the
 * state you want rather than "the other one".
 *
 * `locking` / `unlocking` are transient states the lock reports while the bolt
 * is actually moving; they get their own label so a slow motor doesn't look
 * like a failed tap.
 *
 * `hold_entity` hangs a second control off the padlock tile: hold it to toggle
 * a "keep unlocked" flag, and the padlock turns red while that flag is on. The
 * flag is not the lock's state, so it deliberately does not move the bolt —
 * only the colour, the wording and the held gesture change.
 *
 * Config shape:
 *   { "type": "lock", "options": {
 *       "entity_id": "lock.front_door",
 *       "name": "Front Door",
 *       "show_age": true,
 *       "hold_entity": "input_boolean.front_door_keep_unlocked"
 *   } }
 */
class LockCard : CardRenderer {
    override val type = "lock"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        val showAge = config.bool("show_age", true)

        val state = e?.state.orEmpty()
        val locked = state == "locked"
        val moving = state == "locking" || state == "unlocking"

        // "Keep unlocked" is a separate helper, not a lock state: holding the
        // padlock toggles it, and it tints the padlock red while on.
        val holdEntity = config.string("hold_entity")
        val keepUnlocked = holdEntity?.let { ctx.entity(it)?.isOn } == true
        val holdLive = holdEntity != null && ctx.connected

        // Re-tick so "12 min ago" doesn't sit frozen at whatever it said when
        // the page was first composed.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(30_000)
                now = System.currentTimeMillis()
            }
        }
        val age = if (showAge) ago(e?.lastChanged, now) else null

        fun call(service: String) {
            ctx.client.callService(ServiceCall(domain = "lock", service = service, entityId = entityId))
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .dimIfUnavailable(unavailable)
                .then(
                    if (config.bool("flush")) Modifier
                    else Modifier.clip(RoundedCornerShape(18.dp)).background(AstrionTheme.cardBgAlt)
                )
                // 66dp -> 50dp. This card sits above the `pin: fill`
                // floorplan, so its padding is floorplan. The chips stay the
                // full 32dp — they are the only thing here you touch.
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(if (locked) AstrionTheme.controlSunken else AstrionTheme.raised)
                    // Hold only: a tap here must not move the bolt by accident.
                    // Always applied, gated by `enabled`, so the modifier chain
                    // keeps the same shape whether or not a hold_entity is set.
                    .holdOnly(enabled = holdLive) {
                        val target = holdEntity ?: return@holdOnly
                        ctx.client.callService(
                            ServiceCall(domain = "input_boolean", service = "toggle", entityId = target)
                        )
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    // Colour is never the only carrier: the state line below
                    // says "keep unlocked" in words whenever this is red.
                    contentDescription = if (keepUnlocked) {
                        "$name, ${state.humanise()}, keep unlocked on"
                    } else {
                        "$name, ${state.humanise()}"
                    },
                    modifier = Modifier.size(20.dp),
                    tint = when {
                        unavailable -> AstrionTheme.unavailable
                        // Once a hold_entity exists the padlock reports THAT:
                        // green while the door is allowed to lock, red while
                        // it is being held open.
                        holdEntity != null -> if (keepUnlocked) AstrionTheme.danger else AstrionTheme.good
                        locked -> AstrionTheme.good
                        else -> AstrionTheme.on
                    },
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = AstrionTheme.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (unavailable) {
                    UnavailableLabel(13.sp)
                } else {
                    Text(
                        // When the door is being held unlocked that outranks
                        // how long ago the bolt last moved, so it takes the
                        // age's place rather than overflowing the line.
                        listOfNotNull(
                            state.humanise(),
                            // "keep unlocked" spelled out pushes the line past
                            // the ~23 characters this column fits once the
                            // state word is "Unlocked", and it ellipsised.
                            if (keepUnlocked) "held open" else age,
                        ).joinToString(" · "),
                        color = AstrionTheme.textSecondary,
                        fontSize = 12.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            // One recessed track with the live state raised inside it — a
            // segmented toggle. It used to be two separate chips with the
            // active one in solid accent blue, which made a rarely-used
            // control the loudest thing on the home screen.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(AstrionTheme.controlSunken)
                    .padding(2.dp),
            ) {
                StateChip("Lock", active = locked, live = live && !locked) { call("lock") }
                StateChip("Unlock", active = !locked && !moving && !unavailable, live = live && locked) {
                    call("unlock")
                }
            }
        }
    }

    /**
     * One half of the segmented control. `active` lights the chip that matches
     * the lock's current state; `live` is whether tapping it would do anything
     * — the chip you are already on is deliberately inert, so a stray tap on
     * the lit side can never re-fire the bolt.
     */
    @Composable
    private fun StateChip(label: String, active: Boolean, live: Boolean, onClick: () -> Unit) {
        Box(
            modifier = Modifier
                .width(58.dp)
                .height(30.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(if (active) AstrionTheme.raised else Color.Transparent)
                .tap(enabled = live, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (active) AstrionTheme.textPrimary else AstrionTheme.textSecondary,
                fontSize = 12.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Medium,
            )
        }
    }

    /**
     * "3 min ago" from HA's `last_changed`, which arrives as ISO-8601 with a
     * six-digit fraction and an offset (2026-09-04T20:53:45.944550+00:00).
     * API 26 has java.time, so no desugaring is needed for this.
     */
    private fun ago(lastChanged: String?, nowMs: Long): String? {
        val iso = lastChanged ?: return null
        val then = runCatching {
            OffsetDateTime.parse(iso, DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant()
        }.getOrElse {
            runCatching { Instant.parse(iso) }.getOrNull()
        } ?: return null
        val secs = (nowMs - then.toEpochMilli()) / 1000
        if (secs < 0) return null
        return when {
            secs < 60 -> "just now"
            secs < 3600 -> "${secs / 60} min ago"
            secs < 86_400 -> "${secs / 3600} hr ago"
            else -> "${secs / 86_400} d ago"
        }
    }
}
