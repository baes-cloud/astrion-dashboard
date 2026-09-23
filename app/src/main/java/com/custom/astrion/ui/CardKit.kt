package com.custom.astrion.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Interaction modifiers and the one card container.
 *
 * Every tap in the app goes through [tap] / [tapAndHold] / [holdOnly], which
 * add a haptic tick (where the hardware has a motor) on top of the visible
 * pressed state the components draw themselves. A disabled control is drawn
 * with [liveOrDim] so "this does nothing right now" is visible, not just
 * silent.
 */

/** Alpha for a control that cannot act right now (dead socket, unavailable entity). */
const val DISABLED_ALPHA = 0.4f

/** Clickable with haptic confirmation. `enabled = false` stops the call. */
@Composable
fun Modifier.tap(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    onClick: () -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    return this.clickable(enabled = enabled, onClickLabel = onClickLabel, role = Role.Button) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onClick()
    }
}

/** Tap plus long-press, both haptic. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.tapAndHold(
    enabled: Boolean = true,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    return this.combinedClickable(
        enabled = enabled,
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
        onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onLongClick()
        },
    )
}

/**
 * Long-press only: a tap does nothing and gives no feedback, so the control
 * doesn't promise an action it won't perform.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun Modifier.holdOnly(
    enabled: Boolean = true,
    onLongClick: () -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    return this.combinedClickable(
        enabled = enabled,
        onClick = {},
        onLongClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onLongClick()
        },
    )
}

/** Draw a control at [DISABLED_ALPHA] when it can't act. */
fun Modifier.liveOrDim(live: Boolean): Modifier =
    if (live) this else this.alpha(DISABLED_ALPHA)

/**
 * Legacy name. It used to dim the WHOLE card, which also dimmed the
 * "Unavailable" label down to 1.7:1. Now it only dims — apply it to the
 * controls, never to the row that carries the label.
 */
fun Modifier.dimIfUnavailable(unavailable: Boolean): Modifier = liveOrDim(!unavailable)

/**
 * The one card container.
 *
 * `flush` drops the card's own shape and fill (used inside `stack`, which
 * draws a single shape for all its children). Unavailable state is NOT
 * handled by dimming the card any more: pass it to [StateLine] /
 * [UnavailableBadge] and disable the controls.
 */
@Composable
fun AstrionCard(
    modifier: Modifier = Modifier,
    flush: Boolean = false,
    background: Color = AstrionTheme.cardBg,
    corner: Dp = Radius.card,
    padding: PaddingValues = PaddingValues(Space.card),
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    var m = modifier.fillMaxWidth()
    if (!flush) {
        m = m.clip(RoundedCornerShape(corner)).background(background)
    }
    m = when {
        onClick != null && onLongClick != null ->
            m.tapAndHold(enabled = enabled, onClick = onClick, onLongClick = onLongClick)
        onClick != null -> m.tap(enabled = enabled, onClick = onClick)
        else -> m
    }
    Column(modifier = m.padding(padding), content = content)
}

/**
 * Wraps a deliberately small visual control in a full-size touch target.
 * Keeps the look, fixes the aim.
 */
@Composable
fun TouchTarget(
    size: Dp = Touch.compact,
    enabled: Boolean = true,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .tap(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { content() }
}

/** Card padding shorthand used by rows that are tighter than [Space.card]. */
val RowCardPadding = PaddingValues(horizontal = Space.card, vertical = 10.dp)
