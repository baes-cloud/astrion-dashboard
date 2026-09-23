package com.custom.astrion.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The component family. Every button, chip, switch and label in the app is
 * one of these, so state styling (pressed, selected, on, pending, failed,
 * disabled, unavailable) is defined exactly once.
 */

/** Colour role of a control. */
enum class Tone { Neutral, Accent, On, Good, Danger, Sunken, Ghost }

private data class ToneColors(val container: Color, val pressed: Color, val content: Color)

private fun toneColors(tone: Tone): ToneColors = when (tone) {
    Tone.Neutral -> ToneColors(AstrionTheme.controlBg, AstrionTheme.controlPressed, AstrionTheme.textOnControl)
    Tone.Accent -> ToneColors(AstrionTheme.accentStrong, AstrionTheme.accent, AstrionTheme.onAccent)
    Tone.On -> ToneColors(AstrionTheme.on, AstrionTheme.planIconOn, AstrionTheme.onBg)
    Tone.Good -> ToneColors(AstrionTheme.goodWell, AstrionTheme.controlPressed, AstrionTheme.good)
    Tone.Danger -> ToneColors(AstrionTheme.dangerBg, AstrionTheme.dangerStrong, AstrionTheme.danger)
    Tone.Sunken -> ToneColors(AstrionTheme.controlSunken, AstrionTheme.controlBg, AstrionTheme.textSecondary)
    Tone.Ghost -> ToneColors(Color.Transparent, AstrionTheme.controlBg, AstrionTheme.textSecondary)
}

/**
 * The button. Label, icon, or both.
 *
 * - pressed: container steps one tone lighter while the finger is down (no
 *   ripple animation to pay for);
 * - pending: the icon is replaced by a small spinner (shown by
 *   [ActionHandle] only once a call has taken > 250 ms);
 * - failed: a 2dp danger outline for 2.5 s;
 * - disabled: drawn at [DISABLED_ALPHA] and inert.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AstrionButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    icon: ImageVector? = null,
    description: String? = null,
    tone: Tone = Tone.Neutral,
    enabled: Boolean = true,
    pending: Boolean = false,
    failed: Boolean = false,
    height: Dp = Touch.min,
    shape: Shape = RoundedCornerShape(Radius.control),
    textStyle: TextStyle = AstrionType.bodyStrong,
    iconSize: Dp = 22.dp,
    onLongClick: (() -> Unit)? = null,
) {
    val colors = toneColors(tone)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val haptics = LocalHapticFeedback.current
    var m = modifier
        .heightIn(min = height)
        .liveOrDim(enabled)
        .clip(shape)
        .background(if (pressed) colors.pressed else colors.container)
    if (failed) m = m.border(2.dp, AstrionTheme.danger, shape)
    m = m.combinedClickable(
        interactionSource = interaction,
        indication = null,
        enabled = enabled,
        role = Role.Button,
        onLongClick = onLongClick?.let { lc ->
            {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                lc()
            }
        },
        onClick = {
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            onClick()
        },
    )
    if (description != null) m = m.semantics { contentDescription = description }
    Row(
        modifier = m.padding(horizontal = if (label != null) Space.m else 0.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            pending -> PendingSpinner(size = iconSize.coerceAtMost(20.dp), color = colors.content)
            failed && label == null -> Icon(
                Icons.Filled.ErrorOutline, contentDescription = null,
                tint = AstrionTheme.danger, modifier = Modifier.size(iconSize),
            )
            icon != null -> Icon(icon, contentDescription = null, tint = colors.content, modifier = Modifier.size(iconSize))
        }
        if (label != null) {
            if (icon != null || pending) Spacer(Modifier.width(Space.s))
            Text(
                label,
                style = textStyle,
                color = colors.content,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** Round icon-only button. `size` is both the visual and the touch size. */
@Composable
fun IconAction(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = Touch.min,
    tone: Tone = Tone.Neutral,
    enabled: Boolean = true,
    pending: Boolean = false,
    failed: Boolean = false,
    iconSize: Dp = 22.dp,
    onLongClick: (() -> Unit)? = null,
) {
    AstrionButton(
        onClick = onClick,
        modifier = modifier.size(size),
        icon = icon,
        description = description,
        tone = tone,
        enabled = enabled,
        pending = pending,
        failed = failed,
        height = size,
        shape = CircleShape,
        iconSize = iconSize,
        onLongClick = onLongClick,
    )
}

/** Selectable chip (modes, fan speeds, colour temperatures). */
@Composable
fun ChoiceChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    pending: Boolean = false,
    failed: Boolean = false,
    height: Dp = Touch.compact,
) {
    AstrionButton(
        onClick = onClick,
        modifier = modifier,
        label = label,
        tone = if (selected) Tone.Accent else Tone.Sunken,
        enabled = enabled,
        pending = pending,
        failed = failed,
        height = height,
        textStyle = AstrionType.label,
        description = if (selected) "$label, selected" else label,
    )
}

/** On/off switch: 52×30 visual inside a 48dp-tall touch target. */
@Composable
fun AstrionSwitch(
    on: Boolean,
    onClick: () -> Unit,
    description: String,
    enabled: Boolean = true,
    pending: Boolean = false,
) {
    Box(
        modifier = Modifier
            .size(width = 60.dp, height = Touch.min)
            .liveOrDim(enabled)
            .tap(enabled = enabled, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = 52.dp, height = 30.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(if (on) AstrionTheme.on else AstrionTheme.controlBg),
            contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
        ) {
            Box(
                modifier = Modifier
                    .padding(horizontal = 3.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(if (on) AstrionTheme.onBg else AstrionTheme.textOnControl),
                contentAlignment = Alignment.Center,
            ) {
                if (pending) PendingSpinner(size = 14.dp, color = if (on) AstrionTheme.on else AstrionTheme.controlBg)
            }
        }
    }
}

/** Small indeterminate spinner. Only ever on screen while a call is in flight. */
@Composable
fun PendingSpinner(size: Dp = 18.dp, color: Color = AstrionTheme.accent) {
    CircularProgressIndicator(
        modifier = Modifier.size(size),
        color = color,
        strokeWidth = 2.dp,
    )
}

/** Uppercase section heading, optionally with trailing content (a switch, dots). */
@Composable
fun SectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text.uppercase(), style = AstrionType.section, color = AstrionTheme.textSecondary)
        trailing?.invoke()
    }
}

/** How a one-line state should read. */
enum class StateKind { Normal, On, Good, Danger, Unavailable, Pending }

/**
 * The secondary line under a card title ("35%", "Locked · 3 min ago",
 * "Unavailable"). Unavailable renders as [UnavailableBadge].
 */
@Composable
fun StateLine(text: String, kind: StateKind = StateKind.Normal, modifier: Modifier = Modifier) {
    if (kind == StateKind.Unavailable) {
        UnavailableBadge(modifier)
        return
    }
    Text(
        text,
        modifier = modifier,
        style = AstrionType.label,
        color = when (kind) {
            StateKind.On -> AstrionTheme.on
            StateKind.Good -> AstrionTheme.good
            StateKind.Danger -> AstrionTheme.danger
            StateKind.Pending -> AstrionTheme.accent
            else -> AstrionTheme.textSecondary
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/**
 * "Unavailable", unmistakably: its own hue (lilac, used for nothing else), an
 * icon so it isn't colour alone, and full opacity — the old label was dimmed
 * with its card to 1.7:1.
 */
@Composable
fun UnavailableBadge(modifier: Modifier = Modifier, text: String = "Unavailable") {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Icon(
            Icons.Filled.CloudOff,
            contentDescription = null,
            tint = AstrionTheme.unavailable,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(Space.xs))
        Text(text, style = AstrionType.label, color = AstrionTheme.unavailable, maxLines = 1)
    }
}

/** Legacy entry point; `fontSize` is ignored in favour of the label style. */
@Composable
fun UnavailableLabel(@Suppress("UNUSED_PARAMETER") fontSize: TextUnit = AstrionTheme.label) {
    UnavailableBadge()
}

/** Visual state of an [IconWell]. */
enum class WellState { Off, On, Good, Danger, Unavailable, Neutral }

/**
 * The leading icon square of a card row. The glyph should also change with
 * state (filled/outlined) so colour is never the only carrier.
 */
@Composable
fun IconWell(
    icon: ImageVector,
    state: WellState,
    modifier: Modifier = Modifier,
    size: Dp = 42.dp,
    iconSize: Dp = 22.dp,
    shape: Shape = RoundedCornerShape(Radius.control),
) {
    val (bg, tint) = when (state) {
        WellState.On -> AstrionTheme.on to AstrionTheme.onBg
        WellState.Good -> AstrionTheme.goodWell to AstrionTheme.good
        WellState.Danger -> AstrionTheme.dangerBg to AstrionTheme.danger
        WellState.Unavailable -> AstrionTheme.unavailableWell to AstrionTheme.unavailable
        WellState.Neutral -> AstrionTheme.raised to AstrionTheme.accent
        WellState.Off -> AstrionTheme.raised to AstrionTheme.textSecondary
    }
    Box(
        modifier = modifier.size(size).clip(shape).background(bg),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(iconSize))
    }
}

/** Read-only level bar (volume, brightness, snooze). */
@Composable
fun LevelBar(
    fraction: Float,
    modifier: Modifier = Modifier,
    color: Color = AstrionTheme.good,
    height: Dp = 6.dp,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(height / 2))
            .background(AstrionTheme.trackBg),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                .fillMaxHeight()
                .clip(RoundedCornerShape(height / 2))
                .background(color),
        )
    }
}

/**
 * Press-and-hold to confirm, for the controls where a stray tap costs
 * something real (stopping the day's alarms, unlocking the front door,
 * sending the vacuum off). A fill sweeps across while held; releasing early
 * springs back and calls [onQuickTap] so the caller can explain itself.
 */
@Composable
fun HoldButton(
    label: String,
    onHold: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    holdMs: Int = 700,
    height: Dp = Touch.min,
    container: Color = AstrionTheme.controlBg,
    fill: Color = AstrionTheme.controlPressed,
    ink: Color = AstrionTheme.textOnControl,
    shape: Shape = RoundedCornerShape(Radius.control),
    textStyle: TextStyle = AstrionType.bodyStrong,
    enabled: Boolean = true,
    pending: Boolean = false,
    description: String = "$label, press and hold",
    onQuickTap: (() -> Unit)? = null,
) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    // The gesture block below is keyed only on `enabled`, so read the latest
    // callbacks through state rather than capturing the first ones.
    val currentOnHold by rememberUpdatedState(onHold)
    val currentOnQuickTap by rememberUpdatedState(onQuickTap)
    Box(
        modifier = modifier
            .heightIn(min = height)
            .liveOrDim(enabled)
            .clip(shape)
            .background(container)
            .semantics { contentDescription = description }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onPress = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    val started = System.currentTimeMillis()
                    var done = false
                    val fillJob: Job = scope.launch {
                        progress.animateTo(1f, tween(holdMs, easing = LinearEasing))
                        done = true
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        currentOnHold()
                        progress.snapTo(0f)
                    }
                    tryAwaitRelease()
                    if (!done) {
                        fillJob.cancel()
                        scope.launch { progress.animateTo(0f, tween(150)) }
                        if (System.currentTimeMillis() - started < 350) currentOnQuickTap?.invoke()
                    }
                })
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.value)
                .background(fill),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = Space.m),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (pending) {
                PendingSpinner(size = 18.dp, color = ink)
                Spacer(Modifier.width(Space.s))
            } else if (icon != null) {
                Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(Space.s))
            }
            Text(label, style = textStyle, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
