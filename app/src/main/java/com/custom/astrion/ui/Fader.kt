package com.custom.astrion.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Borderless Matte Horizontal Fader (Bæo v3.0):
 *  - Smooth rounded tonal fill (`accentStrong` / `accentFillQuiet` / blind fill)
 *    with ZERO harsh border and ZERO bright vertical cut-line slicing labels.
 *  - Reads drag fraction inside [drawBehind] so dragging never forces
 *    recomposition of the surrounding container; only the tiny numeric readout
 *    leaf updates.
 *  - Relative horizontal drag so accidental taps/scrolls never blast volume.
 */
@Composable
fun Fader(
    value: Float?,
    onCommit: (Float) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    fill: Color = AstrionTheme.accentStrong,
    track: Color = AstrionTheme.trackBg,
    radius: Dp = 14.dp,
    @Suppress("UNUSED_PARAMETER") cap: Boolean = false,
    @Suppress("UNUSED_PARAMETER") fillBlendMode: BlendMode? = null,
    @Suppress("UNUSED_PARAMETER") borderColor: Color? = null,
    muted: Boolean = false,
    label: @Composable RowScope.() -> Unit = {},
) {
    val dragState = remember { mutableStateOf<Float?>(null) }
    val commit by rememberUpdatedState(onCommit)
    val start by rememberUpdatedState(value ?: 0f)
    val shape = RoundedCornerShape(radius)

    Box(
        modifier = modifier
            .clip(shape)
            .background(track)
            .semantics {
                val cur = (dragState.value ?: value ?: 0f).coerceIn(0f, 1f)
                progressBarRangeInfo = ProgressBarRangeInfo(cur, 0f..1f)
                if (enabled) {
                    setProgress { target ->
                        val clamped = target.coerceIn(0f, 1f)
                        commit(clamped)
                        true
                    }
                }
            }
            .drawBehind {
                val frac = if (muted) 0f else (dragState.value ?: value ?: 0f).coerceIn(0f, 1f)
                if (frac > 0.005f) {
                    val fillW = (size.width * frac).coerceAtLeast(radius.toPx().coerceAtMost(size.width))
                    val r = CornerRadius(radius.toPx(), radius.toPx())
                    drawRoundRect(
                        color = fill,
                        size = Size(fillW, size.height),
                        cornerRadius = r,
                    )
                }
            }
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                    var last = 0L
                    detectHorizontalDragGestures(
                        onDragStart = { dragState.value = start },
                        onDragEnd = { dragState.value?.let(commit); dragState.value = null },
                        onDragCancel = { dragState.value?.let(commit); dragState.value = null },
                    ) { change, dx ->
                        change.consume()
                        val next = ((dragState.value ?: start) + dx / size.width).coerceIn(0f, 1f)
                        dragState.value = next
                        val now = SystemClock.uptimeMillis()
                        if (now - last > COMMIT_EVERY_MS) {
                            last = now
                            commit(next)
                        }
                    }
                },
            ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            label()
            Box(Modifier.weight(1f))
            FaderReadout(value = value, dragValue = { dragState.value }, muted = muted)
        }
    }
}

@Composable
private fun FaderReadout(
    value: Float?,
    dragValue: () -> Float?,
    muted: Boolean,
) {
    val drag = dragValue()
    val shown = (drag ?: value ?: 0f).coerceIn(0f, 1f)
    val text = when {
        muted && drag == null -> "MUTED"
        value == null && drag == null -> "—"
        else -> "${(shown * 100).roundToInt()}%"
    }
    Text(
        text = text,
        color = if (muted && drag == null) AstrionTheme.blush else AstrionTheme.textPrimary,
        fontFamily = AstrionTheme.bodyFont,
        fontWeight = FontWeight.SemiBold,
        fontSize = if (muted && drag == null) AstrionTheme.size.label else AstrionTheme.size.body,
        style = TextStyle(fontFeatureSettings = "tnum"),
        maxLines = 1,
    )
}

private const val COMMIT_EVERY_MS = 350L
