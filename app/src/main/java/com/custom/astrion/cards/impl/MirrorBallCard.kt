package com.custom.astrion.cards.impl

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import com.custom.astrion.ui.dimIfUnavailable
import com.custom.astrion.ui.tap
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A mirror ball that toggles its entity (a fan, a switch: anything with
 * `toggle`). While it's on the ball turns, its facets sliding round, and
 * sparkles twinkle beside it; off, it hangs still and grey.
 *   { "type": "mirror_ball", "options": { "entity_id": "fan.mirror_ball", "size": 44 } }
 * In a `row`, give it a fixed "width" so the cards either side share the rest.
 */
class MirrorBallCard : CardRenderer {
    override val type = "mirror_ball"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val size = config.int("size", 44).dp
        val e = ctx.entities[entityId]
        val unavailable = e == null || e.isUnavailable
        val on = e?.isOn == true
        val ink = if (on) AstrionTheme.on else AstrionTheme.textSecondary
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(size)
                .dimIfUnavailable(unavailable)
                .tap(enabled = !unavailable && ctx.connected) { ctx.client.toggle(entityId) }
                .semantics { contentDescription = "Mirror ball, ${if (on) "on" else "off"}" },
            contentAlignment = Alignment.Center,
        ) {
            if (on) {
                val t = rememberInfiniteTransition(label = "mirrorball")
                val turn by t.animateFloat(0f, PI.toFloat(), infiniteRepeatable(tween(SPIN_MS, easing = LinearEasing)), label = "turn")
                val twinkle by t.animateFloat(0f, 1f, infiniteRepeatable(tween(TWINKLE_MS), RepeatMode.Reverse), label = "twinkle")
                Canvas(Modifier.size(size)) { ball(ink, turn, twinkle) }
            } else {
                Canvas(Modifier.size(size)) { ball(ink, STILL_TURN, null) }
            }
        }
    }
}

/** The ball on a 24-unit grid, scaled to the canvas, as the line icons are. */
private fun DrawScope.ball(ink: Color, turn: Float, twinkle: Float?) {
    val u = size.minDimension / 24f
    val stroke = Stroke(width = 1.6f * u, cap = StrokeCap.Round)
    val c = Offset(12f * u, 13.5f * u)
    val r = 7f * u
    drawLine(ink, Offset(c.x, 2.5f * u), Offset(c.x, c.y - r), 1.6f * u, StrokeCap.Round)
    drawCircle(ink, r, c, style = stroke)
    val outline = Path().apply { addOval(androidx.compose.ui.geometry.Rect(c, r)) }
    clipPath(outline) {
        // Bands of facets across the ball.
        for (dy in listOf(-0.42f, 0.42f)) {
            val y = c.y + dy * r
            val half = sqrt(r * r - (dy * r) * (dy * r))
            drawLine(ink, Offset(c.x - half, y), Offset(c.x + half, y), 1.2f * u)
        }
        // Meridians: ellipses whose width follows the turn, so they slide round.
        for (i in 0 until 3) {
            val w = r * abs(cos(turn + i * PI.toFloat() / 3f))
            if (w < 0.6f * u) continue
            drawOval(
                ink,
                topLeft = Offset(c.x - w, c.y - r),
                size = Size(2f * w, 2f * r),
                style = Stroke(width = 1.2f * u),
            )
        }
    }
    if (twinkle != null) {
        sparkle(ink, Offset(20.5f * u, 6f * u), 2.4f * u * (0.4f + 0.6f * twinkle))
        sparkle(ink, Offset(3.5f * u, 9f * u), 1.8f * u * (1f - 0.6f * twinkle))
        sparkle(ink, Offset(20f * u, 20.5f * u), 1.6f * u * (0.5f + 0.5f * sin(twinkle * PI.toFloat())))
    }
}

/** A four-point glint. */
private fun DrawScope.sparkle(ink: Color, at: Offset, arm: Float) {
    if (arm <= 0.1f) return
    val w = arm * 0.45f
    drawLine(ink, Offset(at.x - arm, at.y), Offset(at.x + arm, at.y), w, StrokeCap.Round)
    drawLine(ink, Offset(at.x, at.y - arm), Offset(at.x, at.y + arm), w, StrokeCap.Round)
}

private const val SPIN_MS = 2400
private const val TWINKLE_MS = 700
/** Where the facets sit while it's off: an even, at-rest look. */
private const val STILL_TURN = 0.35f
