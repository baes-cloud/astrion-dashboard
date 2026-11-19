package com.custom.astrion.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Flat matte Porter's-tinted weather glyphs replacing Android 8.1 cartoon
 * emojis across Main and the Screensaver. Draws directly on Canvas with zero
 * bitmap/emoji allocations and matches the calm Pixel 10a matte aesthetic.
 */
@Composable
fun WeatherGlyph(
    condition: String,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color? = null,
) {
    val cloudColor = tint ?: AstrionTheme.colors.textSoft
    val rainColor = tint ?: AstrionTheme.accent
    val sunColor = tint ?: AstrionTheme.on
    val moonColor = tint ?: AstrionTheme.blush
    val boltColor = tint ?: AstrionTheme.ochreInk
    val label = weatherLabel(condition)

    Canvas(
        modifier = modifier
            .size(size)
            .semantics { contentDescription = label },
    ) {
        val w = this.size.width
        val h = this.size.height
        val stroke = (w * 0.085f).coerceAtLeast(1.4f)

        when (condition) {
            "sunny", "clear" -> drawSun(Offset(w * 0.5f, h * 0.5f), w * 0.22f, w * 0.36f, sunColor, stroke)
            "clear-night" -> drawMoon(w, h, moonColor)
            "partlycloudy" -> {
                drawSun(Offset(w * 0.66f, h * 0.34f), w * 0.16f, w * 0.25f, sunColor, stroke * 0.85f)
                drawCloud(w, h, yShift = h * 0.12f, scale = 0.88f, color = cloudColor)
            }
            "cloudy" -> drawCloud(w, h, yShift = h * 0.06f, scale = 0.96f, color = cloudColor)
            "fog" -> {
                drawCloud(w, h, yShift = -h * 0.05f, scale = 0.86f, color = cloudColor.copy(alpha = 0.85f))
                for (row in 0..1) {
                    val y = h * (0.74f + row * 0.14f)
                    drawLine(
                        color = cloudColor.copy(alpha = 0.7f),
                        start = Offset(w * (0.18f + row * 0.08f), y),
                        end = Offset(w * (0.82f - row * 0.06f), y),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
            "rainy", "pouring" -> {
                drawCloud(w, h, yShift = -h * 0.08f, scale = 0.90f, color = cloudColor)
                val drops = if (condition == "pouring") 4 else 3
                val startX = if (drops == 4) 0.24f else 0.30f
                val step = if (drops == 4) 0.17f else 0.20f
                for (i in 0 until drops) {
                    val x = w * (startX + i * step)
                    val topY = h * (if (i % 2 == 0) 0.68f else 0.72f)
                    drawLine(
                        color = rainColor,
                        start = Offset(x, topY),
                        end = Offset(x - w * 0.05f, topY + h * 0.17f),
                        strokeWidth = stroke,
                        cap = StrokeCap.Round,
                    )
                }
            }
            "lightning", "lightning-rainy" -> {
                drawCloud(w, h, yShift = -h * 0.10f, scale = 0.90f, color = cloudColor)
                val bolt = Path().apply {
                    moveTo(w * 0.54f, h * 0.56f)
                    lineTo(w * 0.42f, h * 0.75f)
                    lineTo(w * 0.53f, h * 0.75f)
                    lineTo(w * 0.45f, h * 0.94f)
                    lineTo(w * 0.63f, h * 0.70f)
                    lineTo(w * 0.52f, h * 0.70f)
                    close()
                }
                drawPath(bolt, color = boltColor)
            }
            "snowy", "snowy-rainy", "hail" -> {
                drawCloud(w, h, yShift = -h * 0.08f, scale = 0.90f, color = cloudColor)
                val r = (w * 0.055f).coerceAtLeast(1.4f)
                listOf(0.32f to 0.76f, 0.50f to 0.84f, 0.68f to 0.76f).forEach { (fx, fy) ->
                    drawCircle(rainColor, radius = r, center = Offset(w * fx, h * fy))
                }
            }
            "windy", "windy-variant" -> {
                val p1 = Path().apply {
                    moveTo(w * 0.14f, h * 0.42f)
                    lineTo(w * 0.70f, h * 0.42f)
                    cubicTo(w * 0.84f, h * 0.42f, w * 0.84f, h * 0.24f, w * 0.70f, h * 0.24f)
                }
                val p2 = Path().apply {
                    moveTo(w * 0.20f, h * 0.60f)
                    lineTo(w * 0.78f, h * 0.60f)
                    cubicTo(w * 0.90f, h * 0.60f, w * 0.90f, h * 0.78f, w * 0.74f, h * 0.78f)
                }
                val style = Stroke(width = stroke, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawPath(p1, color = cloudColor, style = style)
                drawPath(p2, color = rainColor, style = style)
            }
            else -> drawCloud(w, h, yShift = h * 0.06f, scale = 0.92f, color = cloudColor)
        }
    }
}

private fun DrawScope.drawSun(center: Offset, coreR: Float, rayEndR: Float, color: Color, stroke: Float) {
    drawCircle(color = color, radius = coreR, center = center)
    val rayStart = coreR + stroke * 1.15f
    if (rayEndR <= rayStart) return
    for (i in 0 until 8) {
        val a = (i * PI / 4.0)
        val c = cos(a).toFloat()
        val s = sin(a).toFloat()
        drawLine(
            color = color,
            start = Offset(center.x + c * rayStart, center.y + s * rayStart),
            end = Offset(center.x + c * rayEndR, center.y + s * rayEndR),
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
    }
}

private fun DrawScope.drawMoon(w: Float, h: Float, color: Color) {
    val path = Path().apply {
        moveTo(w * 0.60f, h * 0.18f)
        cubicTo(w * 0.36f, h * 0.22f, w * 0.24f, h * 0.48f, w * 0.34f, h * 0.70f)
        cubicTo(w * 0.44f, h * 0.88f, w * 0.72f, h * 0.88f, w * 0.82f, h * 0.68f)
        cubicTo(w * 0.64f, h * 0.70f, w * 0.48f, h * 0.56f, w * 0.50f, h * 0.38f)
        cubicTo(w * 0.51f, h * 0.29f, w * 0.55f, h * 0.23f, w * 0.60f, h * 0.18f)
        close()
    }
    drawPath(path, color = color)
}

private fun DrawScope.drawCloud(w: Float, h: Float, yShift: Float, scale: Float, color: Color) {
    val cx = w * 0.5f
    val cy = h * 0.54f + yShift
    val bw = w * 0.68f * scale
    val bh = h * 0.28f * scale
    // Flat matte solid cloud silhouette (base capsule + two overlapping matte domes).
    drawRoundRect(
        color = color,
        topLeft = Offset(cx - bw / 2f, cy - bh * 0.25f),
        size = Size(bw, bh),
        cornerRadius = CornerRadius(bh / 2f, bh / 2f),
    )
    drawCircle(color = color, radius = bw * 0.27f, center = Offset(cx - bw * 0.12f, cy - bh * 0.28f))
    drawCircle(color = color, radius = bw * 0.21f, center = Offset(cx + bw * 0.16f, cy - bh * 0.12f))
}
