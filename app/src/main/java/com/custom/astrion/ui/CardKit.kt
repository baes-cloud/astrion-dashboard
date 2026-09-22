package com.custom.astrion.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Shared building blocks for cards: one card shape, one tap treatment, one way
 * of saying "this entity is unavailable".
 *
 * Two things drove this. First, an unavailable entity used to be pixel-identical
 * to one that is simply off, in every card — `EntityState.isUnavailable` existed
 * but nothing called it, so a light that had dropped off the Zigbee mesh read as
 * "Off" and swallowed taps silently. Second, nothing in the app acknowledged a
 * tap at all: no spinner, no haptic, no optimistic flash. On a handheld used in
 * a dark room the fix for that is a physical tick, not pixels — which is what
 * [tap] adds everywhere it is used.
 */

/** Alpha applied to a card whose entity is unavailable. */
private const val UNAVAILABLE_ALPHA = 0.4f

/**
 * Clickable with haptic confirmation.
 *
 * Use this instead of `Modifier.clickable` on anything that fires a service
 * call. `enabled = false` both greys the interaction out and stops the call —
 * pass `!unavailable` (and, where it matters, `ctx.connected`) so taps into a
 * dead entity or a dead websocket don't silently vanish.
 */
@Composable
fun Modifier.tap(
    enabled: Boolean = true,
    onClick: () -> Unit,
): Modifier {
    val haptics = LocalHapticFeedback.current
    return this.clickable(enabled = enabled) {
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        onClick()
    }
}

/**
 * Tap plus long-press, both haptic. The long-press tick matters more than the
 * tap one: holds are 1.5s and previously gave no signal at all, so there was no
 * way to tell whether you had held long enough.
 */
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
 *
 * `combinedClickable` needs an onClick, and passing a no-op through
 * [tapAndHold] would still fire the haptic tick on every tap — teaching that
 * the tap did something. Here the tick is the signal that the hold registered.
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

/** Dim a card/control whose entity is unavailable. */
fun Modifier.dimIfUnavailable(unavailable: Boolean): Modifier =
    if (unavailable) this.alpha(UNAVAILABLE_ALPHA) else this

/**
 * The word, not just the dimming. Greyed-out is exactly what "off" already
 * looks like on this palette, so the state line has to say it outright.
 */
@Composable
fun UnavailableLabel(fontSize: androidx.compose.ui.unit.TextUnit = AstrionTheme.label) {
    Text(
        "Unavailable",
        color = AstrionTheme.unavailable,
        fontSize = fontSize,
        fontWeight = FontWeight.Medium,
    )
}

/**
 * Standard card container: one shape, one padding, one hairline edge.
 *
 * `unavailable` dims the whole card and disables its click in one place, which
 * is the entire point — this used to be five different behaviours across six
 * cards.
 */
@Composable
fun AstrionCard(
    modifier: Modifier = Modifier,
    background: Color = AstrionTheme.cardBg,
    corner: Dp = 18.dp,
    padding: PaddingValues = PaddingValues(14.dp),
    unavailable: Boolean = false,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(corner)
    var m = modifier
        .fillMaxWidth()
        .dimIfUnavailable(unavailable)
        .clip(shape)
        .background(background)
    m = when {
        onClick != null && onLongClick != null ->
            m.tapAndHold(enabled = !unavailable, onClick = onClick, onLongClick = onLongClick)
        onClick != null -> m.tap(enabled = !unavailable, onClick = onClick)
        else -> m
    }
    Column(modifier = m.padding(padding), content = content)
}

/**
 * Wraps a deliberately small visual control in a full-size touch target.
 *
 * The screen is 349dp wide, so a 48dp target is 14% of it — but several
 * controls were 22–26dp, which is under 6mm against a ~10mm thumb. This keeps
 * the look and fixes the aim.
 */
@Composable
fun TouchTarget(
    size: Dp = 44.dp,
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
