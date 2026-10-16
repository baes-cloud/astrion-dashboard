package com.custom.astrion.ui.ambient

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.custom.astrion.ui.AstrionTheme
import java.util.Locale
import kotlinx.coroutines.delay
import kotlin.math.abs

/**
 * The progress hairline: 4 dp tall, rounded ends, no knob; elapsed on the left
 * and total on the right. It extrapolates from HA's last position report and
 * advances on a half-second tick rather than every frame, because the HA100
 * is weak. A jump of more than 1.5 s (a seek, or a fresh HA report that disagrees)
 * glides over 350 ms rather than snapping.
 */
@Composable
fun AmbientProgress(
    positionSec: Double,
    reportedAtMs: Long?,
    durationSec: Double,
    playing: Boolean,
    fill: Color,
    track: Color,
    ink: Color,
) {
    if (durationSec <= 0) return
    val frac = remember { Animatable(0f) }

    LaunchedEffect(positionSec, reportedAtMs, durationSec, playing) {
        while (true) {
            val nowMs = System.currentTimeMillis()
            val extrapolated = if (playing && reportedAtMs != null)
                positionSec + (nowMs - reportedAtMs) / 1000.0 else positionSec
            val target = (extrapolated / durationSec).toFloat().coerceIn(0f, 1f)
            val deltaSec = abs(target - frac.value) * durationSec
            if (deltaSec > 1.5) frac.animateTo(target, tween(350)) else frac.snapTo(target)
            if (!playing) break
            // Half a second moves a 3-minute track about a pixel on this
            // panel, so there's nothing to gain from waking every frame.
            delay(TICK_MS)
        }
    }

    val shownSec = (frac.value * durationSec)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(time(shownSec), color = ink, fontSize = AstrionTheme.size.caption, fontFamily = AstrionTheme.bodyFont, maxLines = 1)
        Box(
            Modifier
                .weight(1f)
                .padding(horizontal = 10.dp)
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(track),
        ) {
            Box(Modifier.fillMaxWidth(frac.value).height(4.dp).clip(RoundedCornerShape(2.dp)).background(fill))
        }
        Text(time(durationSec), color = ink, fontSize = AstrionTheme.size.caption, fontFamily = AstrionTheme.bodyFont, maxLines = 1, textAlign = TextAlign.End)
    }
}

private fun time(seconds: Double): String {
    val t = seconds.toLong().coerceAtLeast(0)
    return if (t >= 3600) String.format(Locale.US, "%d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
    else String.format(Locale.US, "%d:%02d", t / 60, t % 60)
}

private const val TICK_MS = 500L
