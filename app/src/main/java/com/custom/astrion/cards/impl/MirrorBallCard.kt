package com.custom.astrion.cards.impl

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.LocalDashboardShowing
import com.custom.astrion.ui.LocalPageVisible
import com.custom.astrion.ui.dimIfUnavailable
import com.custom.astrion.ui.tap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Borderless Matte Mirror Ball pill (Bæo v3.0):
 *  - 48dp matte action pill: warm matte ochre (`ochreSurface` + `ochreInk`)
 *    when spinning, calm matte `cardBg` when still.
 */
class MirrorBallCard : CardRenderer {
    override val type = "mirror_ball"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val pillHeight = config.int("height", 48).coerceAtLeast(44).dp
        val glyphSize = config.int("size", 28).coerceAtMost(36).dp
        val e = ctx.entities[entityId]
        val unavailable = e == null || e.isUnavailable
        val on = e?.isOn == true
        val bg = if (on && !unavailable) AstrionTheme.ochreSurface else AstrionTheme.cardBg
        val ink = if (on && !unavailable) AstrionTheme.ochreInk else AstrionTheme.textSecondary
        val clipPath = remember { Path() }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(pillHeight)
                .dimIfUnavailable(unavailable)
                .clip(RoundedCornerShape(14.dp))
                .background(bg)
                .tap(enabled = !unavailable && ctx.connected) { ctx.client.toggle(entityId) }
                .semantics { contentDescription = "Mirror ball, ${if (on) "on" else "off"}" },
            contentAlignment = Alignment.Center,
        ) {
            if (on) {
                val clock = rememberBallClock(LocalPageVisible.current && LocalDashboardShowing.current)
                Canvas(Modifier.size(glyphSize)) {
                    val ms = clock.value
                    val turn = (ms % SPIN_MS) / SPIN_MS.toFloat() * PI.toFloat()
                    val leg = (ms % (2 * TWINKLE_MS)) / TWINKLE_MS.toFloat()
                    val twinkle = FastOutSlowInEasing.transform(if (leg <= 1f) leg else 2f - leg)
                    ball(ink, turn, twinkle, clipPath)
                }
            } else {
                Canvas(Modifier.size(glyphSize)) { ball(ink, STILL_TURN, null, clipPath) }
            }
        }
    }
}

private fun DrawScope.ball(ink: Color, turn: Float, twinkle: Float?, outline: Path) {
    val u = size.minDimension / 24f
    val outerStroke = Stroke(width = 1.6f * u, cap = StrokeCap.Round)
    val facetStroke = Stroke(width = 1.2f * u)
    val c = Offset(12f * u, 13.5f * u)
    val r = 7f * u
    drawLine(ink, Offset(c.x, 2.5f * u), Offset(c.x, c.y - r), 1.6f * u, StrokeCap.Round)
    drawCircle(ink, r, c, style = outerStroke)
    outline.reset()
    outline.addOval(Rect(c, r))
    clipPath(outline) {
        for (dy in listOf(-0.42f, 0.42f)) {
            val y = c.y + dy * r
            val half = sqrt(r * r - (dy * r) * (dy * r))
            drawLine(ink, Offset(c.x - half, y), Offset(c.x + half, y), 1.2f * u)
        }
        for (i in 0 until 3) {
            val w = r * abs(cos(turn + i * PI.toFloat() / 3f))
            if (w < 0.6f * u) continue
            drawOval(
                ink,
                topLeft = Offset(c.x - w, c.y - r),
                size = Size(2f * w, 2f * r),
                style = facetStroke,
            )
        }
    }
    if (twinkle != null) {
        sparkle(ink, Offset(20.5f * u, 6f * u), 2.4f * u * (0.4f + 0.6f * twinkle))
        sparkle(ink, Offset(3.5f * u, 9f * u), 1.8f * u * (1f - 0.6f * twinkle))
        sparkle(ink, Offset(20f * u, 20.5f * u), 1.6f * u * (0.5f + 0.5f * sin(twinkle * PI.toFloat())))
    }
}

private fun DrawScope.sparkle(ink: Color, at: Offset, arm: Float) {
    if (arm <= 0.1f) return
    val w = arm * 0.45f
    drawLine(ink, Offset(at.x - arm, at.y), Offset(at.x + arm, at.y), w, StrokeCap.Round)
    drawLine(ink, Offset(at.x, at.y - arm), Offset(at.x, at.y + arm), w, StrokeCap.Round)
}

private const val SPIN_MS = 2400
private const val TWINKLE_MS = 700
private const val STILL_TURN = 0.35f
