package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionButton
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.HoldButton
import com.custom.astrion.ui.IconWell
import com.custom.astrion.ui.LocalFeedback
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.StateKind
import com.custom.astrion.ui.StateLine
import com.custom.astrion.ui.Tone
import com.custom.astrion.ui.Touch
import com.custom.astrion.ui.WellState
import com.custom.astrion.ui.holdOnly
import com.custom.astrion.ui.humanise
import com.custom.astrion.ui.rememberAction
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/**
 * Door lock: state and how long it's been that way, and a segmented
 * Lock / Unlock control whose live side is inert (a stray tap on the lit side
 * can never re-fire the bolt).
 *
 * Unlocking the front door is the one control here with a real-world cost,
 * on a remote anyone can pick up: Unlock is press-and-HOLD (a fill sweeps
 * across; a quick tap says "Hold to unlock"). Lock stays a tap — locking is
 * always safe. Both are 48dp tall.
 *
 * `locking` / `unlocking` show as their own state; a call HA refuses
 * outlines the control red and says why in the feedback strip.
 *
 * `hold_entity`: hold the padlock to toggle a helper such as "keep unlocked";
 * the padlock turns red and the state line says "held open".
 *
 * Config: { "type": "lock", "options": {
 *     "entity_id": "lock.front_door", "name": "Front Door", "show_age": true,
 *     "hold_entity": "input_boolean.front_door_keep_unlocked", "flush": false } }
 */
class LockCard : CardRenderer {
    override val type = "lock"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entity(entityId)
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        val showAge = config.bool("show_age", true)
        val feedback = LocalFeedback.current

        val state = e?.state.orEmpty()
        val locked = state == "locked"
        val moving = state == "locking" || state == "unlocking"

        val holdEntity = config.string("hold_entity")
        val keepUnlocked = holdEntity?.let { ctx.entity(it)?.isOn } == true
        val holdLive = holdEntity != null && ctx.connected

        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(30_000)
                now = System.currentTimeMillis()
            }
        }
        val age = if (showAge) ago(e?.lastChanged, now) else null

        val lockAction = rememberAction(ctx)
        val holdAction = rememberAction(ctx)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (config.bool("flush")) Modifier
                    else Modifier.clip(RoundedCornerShape(Radius.card)).background(AstrionTheme.cardBg)
                )
                .padding(horizontal = Space.m, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconWell(
                icon = if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                state = when {
                    unavailable -> WellState.Unavailable
                    keepUnlocked -> WellState.Danger
                    locked -> WellState.Good
                    moving -> WellState.Neutral
                    else -> WellState.On
                },
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.control))
                    .then(if (holdAction.failed) Modifier.border(2.dp, AstrionTheme.danger, RoundedCornerShape(Radius.control)) else Modifier)
                    // Hold only: a tap here must not move the bolt by accident.
                    .holdOnly(enabled = holdLive) {
                        val target = holdEntity ?: return@holdOnly
                        holdAction.run(ServiceCall("input_boolean", "toggle", target))
                    }
                    .semantics {
                        contentDescription = "$name, ${state.humanise()}" + if (keepUnlocked) ", held open" else ""
                    },
                size = 44.dp,
            )
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(
                    name, style = AstrionType.title, color = AstrionTheme.textPrimary,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                when {
                    unavailable -> StateLine("", StateKind.Unavailable)
                    moving -> StateLine(state.humanise() + "…", StateKind.Pending)
                    keepUnlocked -> StateLine("${state.humanise()} · held open", StateKind.Danger)
                    else -> StateLine(
                        listOfNotNull(state.humanise(), age).joinToString(" · "),
                        if (locked) StateKind.Good else StateKind.On,
                    )
                }
            }
            Spacer(Modifier.width(Space.s))
            // Segmented control: a sunken track, the live state raised in it.
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.control))
                    .background(AstrionTheme.controlSunken)
                    .then(if (lockAction.failed) Modifier.border(2.dp, AstrionTheme.danger, RoundedCornerShape(Radius.control)) else Modifier)
                    .padding(2.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                if (locked) {
                    Segment("Lock", active = true)
                } else {
                    AstrionButton(
                        onClick = { lockAction.run(ServiceCall("lock", "lock", entityId)) },
                        modifier = Modifier.width(64.dp),
                        label = "Lock",
                        tone = Tone.Ghost,
                        enabled = live,
                        pending = lockAction.busy,
                        height = Touch.compact,
                        textStyle = AstrionType.label,
                        description = "Lock $name",
                    )
                }
                if (!locked && !unavailable && !moving) {
                    // Already unlocked: the lit, inert side.
                    Segment("Unlock", active = true)
                } else {
                    HoldButton(
                        label = "Unlock",
                        onHold = { lockAction.run(ServiceCall("lock", "unlock", entityId)) },
                        modifier = Modifier.width(64.dp),
                        height = Touch.compact,
                        container = AstrionTheme.controlSunken,
                        fill = AstrionTheme.on,
                        ink = AstrionTheme.textSecondary,
                        textStyle = AstrionType.label,
                        enabled = live && locked,
                        pending = lockAction.busy && locked,
                        holdMs = 600,
                        description = "Unlock $name, press and hold",
                        onQuickTap = { feedback.show("Hold Unlock to open the door") },
                    )
                }
            }
        }
    }

    /** The current side of the segmented control: raised, lit, not a button. */
    @Composable
    private fun Segment(label: String, active: Boolean) {
        Box(
            modifier = Modifier
                .width(64.dp)
                .heightIn(min = Touch.compact)
                .clip(RoundedCornerShape(Radius.small))
                .background(if (active) AstrionTheme.raised else AstrionTheme.controlSunken)
                .semantics { contentDescription = "$label, current" },
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = AstrionType.label, color = AstrionTheme.textPrimary)
        }
    }

    /** "3 min ago" from HA's ISO-8601 `last_changed`. */
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
