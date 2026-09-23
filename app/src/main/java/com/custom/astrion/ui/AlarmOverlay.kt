package com.custom.astrion.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Snooze
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** What the alarm popup shows. Built by MainActivity from live HA state. */
data class AlarmUiState(
    /** Ringing, as opposed to snoozed. */
    val ringing: Boolean,
    /** Epoch ms the snooze ends, when snoozed. */
    val snoozeEndsMs: Long?,
    /** The snooze timer's full length, so its ring drains over the real time. */
    val snoozeTotalMs: Long,
    /** What the alarm is for, e.g. "Head Office". */
    val title: String?,
    /** Where, e.g. "HQ". */
    val place: String?,
    /** When it starts, e.g. "8:15 AM". */
    val startsAt: String?,
    /** Snooze length for the button label, from the timer's duration. */
    val snoozeMinutes: Int = 5,
)

// This screen's own palette (tokens in AstrionTheme.Alarm): deep night navy,
// one warm dawn light, and the two buttons as the only saturated things on it.
private val NavyTop = AstrionTheme.Alarm.navyTop
private val NavyMid = AstrionTheme.Alarm.navyMid
private val NavyBottom = AstrionTheme.Alarm.navyBottom
private val Dawn = AstrionTheme.Alarm.dawn
private val Dusk = AstrionTheme.Alarm.dusk
private val Ink = AstrionTheme.Alarm.ink
private val InkSoft = AstrionTheme.Alarm.inkSoft
private val InkFaint = AstrionTheme.Alarm.inkFaint
private val GoldHi = AstrionTheme.Alarm.goldHi
private val GoldLo = AstrionTheme.Alarm.goldLo
private val GoldInk = AstrionTheme.Alarm.goldInk
private val Wine = AstrionTheme.dangerStrong
private val WineHi = AstrionTheme.dangerStrongHi
private val WineInk = AstrionTheme.dangerInk

/**
 * The work-alarm popup: almost, but not quite, full screen.
 *
 * Mirrors Home Assistant rather than keeping its own state. It is up exactly
 * while `input_boolean.work_alarm_ringing` is on, and shows "snoozed" while
 * `timer.work_alarm_snooze` runs — so snoozing or stopping from the phone
 * updates both remotes too.
 *
 * Ringing: a warm dawn light behind a huge thin clock, a ripple pulsing out of
 * the alarm glyph, the shift on a small card, and two full-width buttons at
 * the bottom where a thumb lands. Snoozed: the light cools, the ripple stops,
 * and a ring drains as the snooze runs out.
 *
 * Snooze is one tap. Stop is press-and-HOLD, because it is "stop for today" —
 * it also cancels the second alarm — and an accidental tap at 7am means
 * sleeping through a shift. It fills as it's held; a quick tap just explains
 * itself underneath.
 *
 * Like the IR and voice overlays this draws in the Activity's own window, not
 * a Dialog, so hardware buttons keep working underneath it — and while it
 * rings, OK or any shortcut button snoozes (MainActivity), which the popup
 * says in words under the buttons.
 *
 * Cheap to draw: no glows (the concentric alpha rectangles are gone — a 1dp
 * edge in the button's colour instead), and the ripple only animates while
 * actually ringing.
 */
@Composable
fun AlarmOverlay(
    state: AlarmUiState,
    onSnooze: () -> Unit,
    onDismiss: () -> Unit,
    onHide: () -> Unit,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            delay(1000)
        }
    }
    val timeFmt = remember { SimpleDateFormat("h:mm", Locale.getDefault()) }
    val ampmFmt = remember { SimpleDateFormat("a", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("EEEE, d MMMM", Locale.getDefault()) }
    val light = if (state.ringing) Dawn else Dusk

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AstrionTheme.scrim)
            // Swallow taps on the scrim so nothing underneath is hit by mistake.
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            )
            .padding(10.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(Radius.sheet))
                .background(Brush.verticalGradient(listOf(NavyTop, NavyMid, NavyBottom)))
                // The dawn: a soft pool of warm light behind the clock.
                .drawBehind {
                    drawCircle(
                        brush = Brush.radialGradient(
                            listOf(light.copy(alpha = 0.20f), Color.Transparent),
                            center = Offset(size.width / 2, size.height * 0.22f),
                            radius = size.width * 0.95f,
                        ),
                        radius = size.width * 0.95f,
                        center = Offset(size.width / 2, size.height * 0.22f),
                    )
                }
                .padding(horizontal = 18.dp, vertical = 22.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Ripple(ringing = state.ringing, tint = light)
            Spacer(Modifier.height(10.dp))
            Text(
                if (state.ringing) "WAKE UP" else "SNOOZING",
                color = light,
                style = AstrionType.shout,
            )

            Spacer(Modifier.weight(0.5f))

            Row {
                Text(
                    timeFmt.format(Date(now)),
                    color = Ink,
                    style = AstrionType.alarmClock,
                    modifier = Modifier.alignByBaseline(),
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    ampmFmt.format(Date(now)),
                    color = InkSoft,
                    style = AstrionType.alarmAmPm,
                    modifier = Modifier.alignByBaseline(),
                )
            }
            Text(dateFmt.format(Date(now)), color = InkSoft, style = AstrionType.body)

            Spacer(Modifier.weight(0.5f))

            if (!state.ringing && state.snoozeEndsMs != null) {
                SnoozeRing(state.snoozeEndsMs, state.snoozeTotalMs, now)
                Spacer(Modifier.weight(0.4f))
            }

            if (state.title != null || state.startsAt != null) {
                ShiftCard(state)
            }

            Spacer(Modifier.weight(1f))

            if (state.ringing) {
                PillButton(
                    icon = Icons.Filled.Snooze,
                    label = "Snooze · ${state.snoozeMinutes} min",
                    ink = GoldInk,
                    brush = Brush.horizontalGradient(listOf(GoldHi, GoldLo)),
                    glowColor = GoldLo,
                    onClick = onSnooze,
                )
                Spacer(Modifier.height(14.dp))
            }

            HoldToStop(onDismiss)

            if (state.ringing) {
                // The physical-button path, said out loud.
                Text(
                    "OK or any shortcut button snoozes",
                    color = InkFaint,
                    style = AstrionType.label,
                )
            } else {
                Text(
                    "Hide until it rings again",
                    color = InkSoft,
                    style = AstrionType.body,
                    modifier = Modifier
                        .heightIn(min = Touch.min)
                        .clip(RoundedCornerShape(Radius.control))
                        .tap(onClick = onHide)
                        .padding(horizontal = Space.card, vertical = 14.dp),
                )
            }
        }
    }
}

/** The alarm glyph, with rings pulsing out of it while it rings. */
@Composable
private fun Ripple(ringing: Boolean, tint: Color) {
    if (!ringing) {
        // Snoozed: a still glyph. The infinite transition used to keep
        // redrawing every frame for the whole snooze.
        RippleGlyph(ringing = false, tint = tint, modifier = Modifier.size(64.dp))
        return
    }
    val t = rememberInfiniteTransition(label = "ripple")
    val p by t.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800, easing = LinearEasing), RepeatMode.Restart),
        label = "ripple-phase",
    )
    Box(
        modifier = Modifier
            .size(64.dp)
            .drawBehind {
                if (!ringing) return@drawBehind
                val base = size.minDimension / 2 * 0.62f
                for (k in 0..1) {
                    val q = (p + k * 0.5f) % 1f
                    drawCircle(
                        color = tint.copy(alpha = (1f - q) * 0.45f),
                        radius = base * (1f + q * 0.95f),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        RippleGlyph(ringing = true, tint = tint)
    }
}

@Composable
private fun RippleGlyph(ringing: Boolean, tint: Color, modifier: Modifier = Modifier) {
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(AstrionTheme.Alarm.glyphWell),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (ringing) Icons.Filled.Alarm else Icons.Filled.Snooze,
                contentDescription = null,
                tint = tint,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** What the alarm is for, as a small frosted card. */
@Composable
private fun ShiftCard(state: AlarmUiState) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(AstrionTheme.Alarm.card)
            .border(1.dp, AstrionTheme.Alarm.cardEdge, RoundedCornerShape(Radius.card))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(Radius.control))
                .background(AstrionTheme.Alarm.glyphWell),
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Filled.Event, contentDescription = null, tint = Dusk, modifier = Modifier.size(20.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                state.title ?: "Work",
                color = Ink,
                style = AstrionType.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                listOfNotNull(state.place, state.startsAt?.let { "starts $it" }).joinToString("  ·  "),
                color = InkSoft,
                style = AstrionType.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Snoozed: a ring that drains as the snooze runs out, time left inside it. */
@Composable
private fun SnoozeRing(endsMs: Long, totalMs: Long, nowMs: Long) {
    val leftMs = (endsMs - nowMs).coerceAtLeast(0)
    val frac = (leftMs.toFloat() / totalMs.coerceAtLeast(1)).coerceIn(0f, 1f)
    val secs = leftMs / 1000
    Box(Modifier.size(112.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val w = 5.dp.toPx()
            val inset = w / 2
            val arcSize = Size(size.width - w, size.height - w)
            drawArc(
                color = AstrionTheme.Alarm.ringTrack, startAngle = 0f, sweepAngle = 360f, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(width = w),
            )
            drawArc(
                color = GoldHi, startAngle = -90f, sweepAngle = 360f * frac, useCenter = false,
                topLeft = Offset(inset, inset), size = arcSize, style = Stroke(width = w, cap = StrokeCap.Round),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("%d:%02d".format(secs / 60, secs % 60), color = Ink, style = AstrionType.countdown)
            Text("BACK IN", color = InkFaint, style = AstrionType.section)
        }
    }
}


@Composable
private fun PillButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    ink: Color,
    brush: Brush,
    glowColor: Color,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(64.dp)
            .clip(RoundedCornerShape(percent = 50))
            .background(brush)
            .border(1.dp, glowColor, RoundedCornerShape(percent = 50))
            .tap(onClick = onClick),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(10.dp))
        Text(label, color = ink, style = AstrionType.button)
    }
}

/**
 * Press and hold for [HOLD_MS] to fire. Brighter red sweeps across the pill
 * while held; releasing early cancels and springs back, and a quick tap
 * explains itself with a hint underneath.
 */
@Composable
private fun HoldToStop(onDismiss: () -> Unit) {
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var hintAt by remember { mutableLongStateOf(0L) }
    var hint by remember { mutableStateOf(false) }
    LaunchedEffect(hintAt) {
        if (hintAt > 0) {
            hint = true
            delay(2200)
            hint = false
        }
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(Wine)
                .border(1.dp, WineHi, RoundedCornerShape(percent = 50))
                .pointerInput(Unit) {
                    detectTapGestures(onPress = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val started = System.currentTimeMillis()
                        var fill: Job? = null
                        fill = scope.launch {
                            progress.animateTo(1f, tween(HOLD_MS, easing = LinearEasing))
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            onDismiss()
                            progress.snapTo(0f)
                        }
                        tryAwaitRelease()
                        if (progress.value < 1f) {
                            fill.cancel()
                            scope.launch { progress.animateTo(0f, tween(180)) }
                            if (System.currentTimeMillis() - started < 350) hintAt = System.currentTimeMillis()
                        }
                    })
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(progress.value)
                    .background(Brush.horizontalGradient(listOf(Wine, WineHi))),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Filled.AlarmOff, contentDescription = null, tint = WineInk, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
                Text("Hold to stop", color = WineInk, style = AstrionType.button)
            }
        }
        // Fixed-height slot so the hint appearing never shifts the buttons.
        Box(Modifier.height(24.dp), contentAlignment = Alignment.Center) {
            if (hint) Text("Keep holding — stops alarms for today", color = WineInk, style = AstrionType.label)
        }
    }
}

private const val HOLD_MS = 900
