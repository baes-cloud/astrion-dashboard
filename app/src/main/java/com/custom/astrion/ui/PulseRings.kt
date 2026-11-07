package com.custom.astrion.ui

import android.os.SystemClock
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.withFrameMillis
import kotlinx.coroutines.delay

/**
 * Two rings pulsing out of a glyph, for alerts and a ringing alarm. Only
 * composed while it pulses, and ticks at [PULSE_FPS]: an infinite transition
 * asks for a frame 60 times a second for as long as it exists, even when
 * nothing is drawn, and an info alert or a snoozed alarm can sit on the dock
 * for hours. Fills its parent; put it first in the glyph's Box.
 */
@Composable
fun PulseRings(
    tint: Color,
    periodMs: Int,
    baseFraction: Float,
    growth: Float,
    maxAlpha: Float,
) {
    val now = remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) {
        val tick = 1000L / PULSE_FPS
        while (true) {
            delay(tick - SystemClock.uptimeMillis() % tick)
            withFrameMillis { now.longValue = it }
        }
    }
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val phase = (now.longValue % periodMs) / periodMs.toFloat()
                val base = size.minDimension / 2 * baseFraction
                for (k in 0..1) {
                    val q = (phase + k * 0.5f) % 1f
                    drawCircle(
                        color = tint.copy(alpha = (1f - q) * maxAlpha),
                        radius = base * (1f + q * growth),
                        style = Stroke(width = 2.dp.toPx()),
                    )
                }
            },
    )
}

private const val PULSE_FPS = 15L
