package com.custom.astrion.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * A thick horizontal level you drag, for volumes: the fill grows from the
 * left, [label] sits on it at the left and the level at the right.
 *
 * Only a sideways drag moves it, and it moves by how far you drag rather
 * than jumping to where the finger landed, so a tap or a scroll that starts
 * on it can't blast the volume.
 * [onCommit] runs a few times a second while dragging and once on release.
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
    cap: Boolean = true,
    fillBlendMode: BlendMode? = null,
    borderColor: Color? = null,
    label: @Composable RowScope.() -> Unit = {},
) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val shown = (drag ?: value ?: 0f).coerceIn(0f, 1f)
    val commit by rememberUpdatedState(onCommit)
    val start by rememberUpdatedState(value ?: 0f)
    val shape = RoundedCornerShape(radius)
    Box(
        modifier = modifier
            .clip(shape)
            .background(track)
            .then(if (borderColor != null) Modifier.border(1.dp, borderColor, shape) else Modifier)
            .then(
                if (!enabled) Modifier else Modifier.pointerInput(Unit) {
                    var last = 0L
                    detectHorizontalDragGestures(
                        onDragStart = { drag = start },
                        onDragEnd = { drag?.let(commit); drag = null },
                        onDragCancel = { drag?.let(commit); drag = null },
                    ) { change, dx ->
                        change.consume()
                        val next = ((drag ?: start) + dx / size.width).coerceIn(0f, 1f)
                        drag = next
                        val now = SystemClock.uptimeMillis()
                        if (now - last > COMMIT_EVERY_MS) {
                            last = now
                            commit(next)
                        }
                    }
                },
            ),
    ) {
        if (fillBlendMode != null) {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(shown)
                    .drawBehind {
                        // Base tint + blended luminous pass so the blurred album art underneath shines through.
                        drawRect(color = fill.copy(alpha = (fill.alpha * 0.55f).coerceIn(0f, 1f)), size = size)
                        drawRect(
                            brush = Brush.horizontalGradient(
                                listOf(fill.copy(alpha = (fill.alpha * 0.65f).coerceIn(0f, 1f)), fill),
                            ),
                            size = Size(size.width, size.height),
                            blendMode = fillBlendMode,
                        )
                    },
            )
        } else {
            Box(
                Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(shown)
                    .background(fill),
            )
        }
        if (cap && shown > 0.04f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .padding(vertical = 10.dp),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth(shown)
                        .fillMaxHeight(),
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    Box(
                        Modifier
                            .padding(end = 7.dp)
                            .width(2.5.dp)
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(2.dp))
                            .background(AstrionTheme.textPrimary.copy(alpha = 0.9f)),
                    )
                }
            }
        }
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            label()
            Box(Modifier.weight(1f))
            Text(
                if (value == null && drag == null) "—" else "${(shown * 100).roundToInt()}%",
                color = AstrionTheme.textPrimary,
                fontFamily = AstrionTheme.bodyFont,
                fontWeight = FontWeight.SemiBold,
                fontSize = AstrionTheme.size.bodyLarge,
                maxLines = 1,
            )
        }
    }
}

private const val COMMIT_EVERY_MS = 350L
