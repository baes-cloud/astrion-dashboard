package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import com.custom.astrion.ui.LocalMinuteClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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

/**
 * Door-lock card: name, how long the bolt has been where it is, and one
 * button that shows the current state (Locked, green / Unlocked, amber) and
 * toggles it. (It used to be a two-chip Lock | Unlock segmented control;
 * The owner preferred the single state button, 2026-09-27.)
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
        val now = LocalMinuteClock.current
        val age = if (showAge) ago(e?.lastChangedMs, now) else null

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
                // 66dp -> 50dp -> 40dp. This card sits above the `pin: fill`
                // floorplan, so its padding is floorplan: one line of text,
                // and the button is the only thing here you touch.
                .padding(horizontal = 12.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .clip(RoundedCornerShape(9.dp))
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
                    modifier = Modifier.size(18.dp),
                    tint = when {
                        unavailable -> AstrionTheme.unavailable
                        // Once a hold_entity exists the padlock reports THAT:
                        // green while the door is allowed to lock, red while
                        // it is being held open.
                        holdEntity != null -> if (keepUnlocked) AstrionTheme.danger else AstrionTheme.good
                        locked -> AstrionTheme.good
                        else -> AstrionTheme.blush
                    },
                )
            }
            Spacer(Modifier.width(10.dp))
            // Name and age on one line.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.Bottom) {
                Text(
                    name,
                    color = AstrionTheme.textPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                Spacer(Modifier.width(8.dp))
                if (unavailable) {
                    UnavailableLabel(13.sp)
                } else {
                    Text(
                        // When the door is being held unlocked that outranks
                        // how long ago the bolt last moved, so it takes the
                        // age's place rather than overflowing the line.
                        listOfNotNull(
                            if (keepUnlocked || age == null) state.humanise() else null,
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
            // One button that shows the state and flips it (owner's call:
            // the two-chip segmented control was more than the door needs).
            // Inert while the bolt is moving or the lock is unreachable.
            Spacer(Modifier.width(8.dp))
            StateButton(
                state = state,
                locked = locked,
                moving = moving,
                live = live && !moving,
            ) { call(if (locked) "unlock" else "lock") }
        }
    }

    @Composable
    private fun StateButton(state: String, locked: Boolean, moving: Boolean, live: Boolean, onClick: () -> Unit) {
        val tint = when {
            moving -> AstrionTheme.textSecondary
            locked -> AstrionTheme.good
            else -> AstrionTheme.blush
        }
        Row(
            modifier = Modifier
                .height(30.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(tint.copy(alpha = 0.16f))
                .tap(enabled = live, onClick = onClick)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(15.dp),
            )
            Spacer(Modifier.width(6.dp))
            Text(
                when {
                    moving -> state.humanise() + "…"
                    locked -> "Locked"
                    else -> "Unlocked"
                },
                color = tint,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

    /** "3 min ago" from the entity's last change (epoch millis). */
    private fun ago(lastChangedMs: Long?, nowMs: Long): String? {
        val then = lastChangedMs ?: return null
        val secs = (nowMs - then) / 1000
        if (secs < 0) return null
        return when {
            secs < 60 -> "just now"
            secs < 3600 -> "${secs / 60} min ago"
            secs < 86_400 -> "${secs / 3600} hr ago"
            else -> "${secs / 86_400} d ago"
        }
    }
}
