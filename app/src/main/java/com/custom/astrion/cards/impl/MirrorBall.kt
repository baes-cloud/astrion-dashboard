package com.custom.astrion.cards.impl

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardContext
import com.custom.astrion.ui.LocalDashboardShowing
import com.custom.astrion.ui.LocalPageVisible
import com.custom.astrion.ui.parseHexColor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.delay

/**
 * The mirror ball's specks drifting round the room while it spins: a field
 * of small soft spots turning slowly about the ball, thick near it and
 * thinning out, each twinkling a little, clipped to "shape" (percent of the
 * plan, like icons). Ticks at FPS, and only while the ball is on and seen.
 *   "mirror_ball": { "entity_id": "fan.mirror_ball", "center": [92, 44],
 *                    "shape": [[1,1],[99,1],[99,50],[1,50]], "color": "#FFECBE" }
 */
@Composable
internal fun MirrorBallLayer(opts: Map<*, *>, ctx: CardContext) {
    val id = opts["entity_id"] as? String ?: return
    val on = ctx.entities[id]?.isOn == true
    val level = animateFloatAsState(if (on) 1f else 0f, tween(FADE_MS), label = "mirrorBall")
    if (!on && level.value < 0.01f) return
    val center = (opts["center"] as? List<*>)?.filterIsInstance<Number>()?.takeIf { it.size == 2 }
        ?.let { it[0].toFloat() / 100f to it[1].toFloat() / 100f } ?: (0.5f to 0.5f)
    val shape = (opts["shape"] as? List<*>).orEmpty().mapNotNull { p ->
        (p as? List<*>)?.filterIsInstance<Number>()?.takeIf { it.size == 2 }
            ?.let { it[0].toFloat() / 100f to it[1].toFloat() / 100f }
    }.takeIf { it.size >= 3 }
    val color = parseHexColor(opts["color"] as? String) ?: SPECK_WARM
    val specks = remember { specks() }
    val clock = rememberBallClock(LocalPageVisible.current && LocalDashboardShowing.current)
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                val k = level.value
                val c = Offset(center.first * size.width, center.second * size.height)
                // Far enough to reach every corner of the room from the ball.
                val reach = (shape ?: listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f))
                    .maxOf { (x, y) -> hypot(x * size.width - c.x, y * size.height - c.y) }
                val t = (clock.value % TURN_MS) / TURN_MS.toFloat()
                val core = SPECK_CORE.toPx()
                val draw = {
                    specks.forEach { s ->
                        val a = s.angle + t * 2f * PI.toFloat()
                        val p = c + Offset(cos(a), sin(a)) * (s.r * reach)
                        val twinkle = 0.65f + 0.35f * sin(s.phase + t * 2f * PI.toFloat() * TWINKLES)
                        val alpha = k * s.bright * twinkle * (1f - 0.55f * s.r)
                        val radius = core * s.size
                        drawCircle(color.copy(alpha = alpha * 0.28f), radius * 2.6f, p, blendMode = BlendMode.Screen)
                        drawCircle(color.copy(alpha = alpha), radius, p)
                    }
                }
                val path = shape?.let { pts ->
                    Path().apply {
                        moveTo(pts[0].first * size.width, pts[0].second * size.height)
                        for (i in 1 until pts.size) lineTo(pts[i].first * size.width, pts[i].second * size.height)
                        close()
                    }
                }
                if (path == null) draw() else clipPath(path) { draw() }
            },
    )
}

/**
 * The frame clock in ms, for the ball and its specks: it ticks [FPS] times a
 * second while [live] and stands still otherwise (another page, the
 * screensaver, screen off), where an infinite transition would keep asking
 * for frames all night. A slow turn needs no more, and every frame redraws the
 * whole screen on the remotes. Each caller wakes on the same grid of the
 * clock, so the ball and its specks share one frame rather than taking two.
 */
@Composable
internal fun rememberBallClock(live: Boolean): State<Long> {
    val now = remember { mutableLongStateOf(0L) }
    LaunchedEffect(live) {
        if (!live) return@LaunchedEffect
        val tick = 1000L / FPS
        while (true) {
            delay(tick - SystemClock.uptimeMillis() % tick)
            withFrameMillis { now.longValue = it }
        }
    }
    return now
}

private class Speck(val angle: Float, val r: Float, val size: Float, val bright: Float, val phase: Float)

/** A fixed scatter, denser near the ball the way a real ball's spots are. */
private fun specks(): List<Speck> {
    val rnd = Random(7)
    return List(SPECKS) {
        Speck(
            angle = rnd.nextFloat() * 2f * PI.toFloat(),
            r = 0.04f + 0.96f * rnd.nextFloat().pow(0.75f),
            size = 0.7f + rnd.nextFloat() * 0.9f,
            bright = 0.55f + rnd.nextFloat() * 0.45f,
            phase = rnd.nextFloat() * 2f * PI.toFloat(),
        )
    }
}

/** The spotlit ball's colour on the walls: what it throws, not a theme colour. */
private val SPECK_WARM = Color(255, 236, 190)
private val SPECK_CORE = 1.6.dp
private const val SPECKS = 150
/** One full turn of the ball. */
private const val TURN_MS = 36_000
/** Ticks a second while it turns, for the ball card too. */
private const val FPS = 20
/** Twinkles per turn. */
private const val TWINKLES = 9f
private const val FADE_MS = 600
