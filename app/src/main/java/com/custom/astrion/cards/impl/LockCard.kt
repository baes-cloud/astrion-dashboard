package com.custom.astrion.cards.impl

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.LocalMinuteClock
import com.custom.astrion.ui.UnavailableLabel
import com.custom.astrion.ui.holdOnly
import com.custom.astrion.ui.humanise
import com.custom.astrion.ui.tap
import kotlinx.coroutines.delay

/**
 * Door-lock card (Bæo v3.0 Matte Tonal):
 *  - Compact mode renders as a 48dp borderless matte action pill matching
 *    Automations / Neon / Mirror Ball (`blushContainer` when unlocked or held
 *    open, `cardBg` when locked), with zero bezel overhang.
 *  - Tap locks; hold unlocks (tap while locked briefly shows "Hold to unlock").
 *  - Holding the name/state toggles `hold_entity` ("Held open").
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
        val compact = config.bool("compact", false)
        val showAge = config.bool("show_age", true) && !compact

        val state = e?.state.orEmpty()
        val locked = state == "locked"
        val moving = state == "locking" || state == "unlocking"

        val holdEntity = config.string("hold_entity")
        val keepUnlocked = holdEntity?.let { ctx.entity(it)?.isOn } == true
        val holdLive = holdEntity != null && ctx.connected

        val now = LocalMinuteClock.current
        val age = if (showAge) ago(e?.lastChangedMs, now) else null

        fun call(service: String) {
            ctx.client.callService(ServiceCall(domain = "lock", service = service, entityId = entityId))
        }

        var hintAt by remember { mutableLongStateOf(0L) }
        val hint = hintAt != 0L
        LaunchedEffect(hintAt) {
            if (hintAt != 0L) {
                delay(2_000)
                hintAt = 0L
            }
        }

        val attention = !unavailable && (keepUnlocked || !locked)
        val pillBg = when {
            config.bool("flush") -> AstrionTheme.pageBg.copy(alpha = 0f)
            compact && attention -> AstrionTheme.blushContainer
            else -> AstrionTheme.cardBg
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (compact) Modifier.height(48.dp) else Modifier)
                .clip(RoundedCornerShape(if (compact) 14.dp else AstrionTheme.surfaces.cardCornerRadius))
                .background(pillBg)
                .padding(
                    horizontal = if (compact) 10.dp else 12.dp,
                    vertical = if (compact) 0.dp else 8.dp,
                ),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!compact) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(if (locked && !keepUnlocked) AstrionTheme.controlSunken else AstrionTheme.blushContainer)
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
                        contentDescription = if (keepUnlocked) "$name, ${state.humanise()}, held open" else "$name, ${state.humanise()}",
                        modifier = Modifier.size(18.dp),
                        tint = when {
                            unavailable -> AstrionTheme.unavailable
                            keepUnlocked -> AstrionTheme.blush
                            locked -> AstrionTheme.accent
                            else -> AstrionTheme.blush
                        },
                    )
                }
                Spacer(Modifier.width(10.dp))
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .then(
                        if (compact && holdEntity != null) Modifier.holdOnly(enabled = holdLive) {
                            ctx.client.callService(
                                ServiceCall(domain = "input_boolean", service = "toggle", entityId = holdEntity)
                            )
                        } else Modifier
                    ),
                horizontalAlignment = if (compact) Alignment.End else Alignment.Start,
            ) {
                Text(
                    name,
                    color = AstrionTheme.textPrimary,
                    fontSize = AstrionTheme.size.body,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = if (compact) TextAlign.End else TextAlign.Start,
                )
                if (unavailable) {
                    UnavailableLabel(AstrionTheme.size.label)
                } else {
                    Text(
                        if (compact) {
                            when {
                                hint -> "Hold unlock"
                                keepUnlocked -> "Held open"
                                else -> state.humanise()
                            }
                        } else listOfNotNull(
                            if (keepUnlocked || age == null) state.humanise() else null,
                            if (keepUnlocked) "held open" else age,
                        ).joinToString(" · "),
                        color = when {
                            hint -> AstrionTheme.textPrimary
                            attention -> AstrionTheme.blush
                            else -> AstrionTheme.textSecondary
                        },
                        fontSize = AstrionTheme.size.label,
                        fontWeight = if (attention || hint) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = if (compact) TextAlign.End else TextAlign.Start,
                    )
                }
            }

            Spacer(Modifier.width(6.dp))
            StateButton(
                state = state,
                locked = locked,
                moving = moving,
                attention = attention,
                live = live && !moving,
                iconOnly = compact,
                onHint = { hintAt = System.currentTimeMillis() },
            ) { call(if (locked) "unlock" else "lock") }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun StateButton(
        state: String,
        locked: Boolean,
        moving: Boolean,
        attention: Boolean,
        live: Boolean,
        iconOnly: Boolean = false,
        onHint: () -> Unit = {},
        onClick: () -> Unit,
    ) {
        val tint = when {
            moving -> AstrionTheme.textSecondary
            attention -> AstrionTheme.blush
            locked -> AstrionTheme.accent
            else -> AstrionTheme.blush
        }
        if (iconOnly) {
            val haptics = LocalHapticFeedback.current
            Box(
                modifier = Modifier
                    .size(width = 34.dp, height = 44.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .combinedClickable(
                        enabled = live,
                        onClick = {
                            if (locked) onHint()
                            else {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                onClick()
                            }
                        },
                        onLongClick = {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onClick()
                        },
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (locked) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = if (locked) "Hold to unlock" else "Lock",
                    tint = tint,
                    modifier = Modifier.size(19.dp),
                )
            }
            return
        }
        Row(
            modifier = Modifier
                .height(36.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(if (attention) AstrionTheme.blushContainer else AstrionTheme.accentContainer)
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
                fontSize = AstrionTheme.size.label,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }

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
