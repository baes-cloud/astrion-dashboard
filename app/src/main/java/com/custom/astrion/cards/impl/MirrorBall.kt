package com.custom.astrion.cards.impl

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardContext
import com.custom.astrion.ui.LocalDashboardShowing
import com.custom.astrion.ui.LocalPageVisible
import com.custom.astrion.ui.parseHexColor
import kotlinx.coroutines.delay
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.sin
import kotlin.random.Random

/**
 * Mirror ball specks drifting round the room while spinning.
 *
 * Step 5a GPU optimization: `derivedStateOf` avoids recomposing during fades,
 * and [drawWithCache] caches room clip [Path] and max corner reach once per
 * layout size instead of reallocating every 8 fps tick.
 */
@Composable
internal fun MirrorBallLayer(opts: Map<*, *>, ctx: CardContext) {
    val id = opts["entity_id"] as? String ?: return
    val on = ctx.entities[id]?.isOn == true
    val level = animateFloatAsState(if (on) 1f else 0f, tween(FADE_MS), label = "mirrorBall")
    val visible by remember(on) { derivedStateOf { on || level.value >= 0.01f } }
    if (!visible) return

    val center = remember(opts) {
        (opts["center"] as? List<*>)?.filterIsInstance<Number>()?.takeIf { it.size == 2 }
            ?.let { it[0].toFloat() / 100f to it[1].toFloat() / 100f } ?: (0.5f to 0.5f)
    }
    val shape = remember(opts) {
        (opts["shape"] as? List<*>).orEmpty().mapNotNull { p ->
            (p as? List<*>)?.filterIsInstance<Number>()?.takeIf { it.size == 2 }
                ?.let { it[0].toFloat() / 100f to it[1].toFloat() / 100f }
        }.takeIf { it.size >= 3 }
    }
    val color = parseHexColor(opts["color"] as? String) ?: SPECK_WARM
    val minX = shape?.minOf { it.first } ?: 0f
    val maxX = shape?.maxOf { it.first } ?: 1f
    val minY = shape?.minOf { it.second } ?: 0f
    val maxY = shape?.maxOf { it.second } ?: 1f
    val isAxisAlignedRect = shape != null && shape.size == 4 && shape.all { (x, y) ->
        (x == minX || x == maxX) && (y == minY || y == maxY)
    }
    val specks = remember { specks() }
    val clock = rememberBallClock(LocalPageVisible.current && LocalDashboardShowing.current)

    Box(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val c = Offset(center.first * size.width, center.second * size.height)
                val reach = (shape ?: listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f))
                    .maxOf { (x, y) -> hypot(x * size.width - c.x, y * size.height - c.y) }
                val core = SPECK_CORE.toPx()
                val clipPolygon = if (!isAxisAlignedRect && shape != null) {
                    Path().apply {
                        moveTo(shape[0].first * size.width, shape[0].second * size.height)
                        for (i in 1 until shape.size) lineTo(shape[i].first * size.width, shape[i].second * size.height)
                        close()
                    }
                } else null
                val rectL = minX * size.width
                val rectT = minY * size.height
                val rectR = maxX * size.width
                val rectB = maxY * size.height

                onDrawBehind {
                    val k = level.value
                    if (k < 0.01f) return@onDrawBehind
                    val t = (clock.value % TURN_MS) / TURN_MS.toFloat()
                    val twoPiT = t * 2f * PI.toFloat()
                    val draw = {
                        specks.forEach { s ->
                            val a = s.angle + twoPiT
                            val twinkle = 0.65f + 0.35f * sin(s.phase + twoPiT * TWINKLES)
                            val alpha = k * s.bright * twinkle * (1f - 0.55f * s.r)
                            if (alpha >= 0.02f) {
                                val dist = s.r * reach
                                val p = Offset(c.x + cos(a) * dist, c.y + sin(a) * dist)
                                val radius = core * s.size
                                drawCircle(color.copy(alpha = alpha * 0.3f), radius * 2.5f, p)
                                drawCircle(color.copy(alpha = alpha), radius, p)
                            }
                        }
                    }
                    when {
                        isAxisAlignedRect -> clipRect(rectL, rectT, rectR, rectB) { draw() }
                        clipPolygon != null -> clipPath(clipPolygon) { draw() }
                        else -> draw()
                    }
                }
            },
    )
}

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

private val SPECK_WARM = Color(255, 236, 190)
private val SPECK_CORE = 1.6.dp
private const val SPECKS = 48
private const val TURN_MS = 36_000
private const val FPS = 8
private const val TWINKLES = 9f
private const val FADE_MS = 600
