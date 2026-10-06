package com.custom.astrion.cards.impl

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import com.custom.astrion.cards.CardContext
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ui.screensaverIsNight
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ln
import kotlin.math.pow

/**
 * Lit rooms: each floorplan light throws a soft pool of its own colour and
 * brightness into the room it belongs to, and as the sun goes down rooms with
 * nothing on fall gradually into shade. Config, on the picture_elements card:
 *
 *   "lit_rooms": {
 *     "shade": "sun",            // "sun": deepens with the sun's elevation through
 *                                // dusk (6° to -6°); "night": on/off at sunset;
 *                                // "always" or "off"
 *     "shade_alpha": 0.68,       // how dark an unlit room gets, 0–1
 *     "glow": 0.85,              // colour strength, 0–1
 *     "reach": 26,               // pool radius, % of the plan's width
 *     "rooms": [
 *       { "name": "Bedroom",
 *         "shape": [[0,51],[43,51],[43,100],[0,100]],   // % of the plan, like icons
 *         "lights": ["light.bedlamps", "light.bedroom_lights"] }
 *     ]
 *   }
 *
 * A light glows from its floorplan icon's position, clipped to its room's
 * outline so it stops at the walls. An element may carry "glow_reach" to scale
 * its own pool (1 = the card's reach), and "glow_spots" to glow from several
 * fittings instead of the icon: one light entity that drives six downlights
 * gets six smaller pools.
 *   { "entity_id": "light.downlights", "left": 65, "top": 28,
 *     "glow_spots": [[24,17],[59,12],[84,18]], "glow_reach": 0.6 }
 *
 * Cost: nothing animates at rest. A light changing fades its pool over 400 ms
 * and then the layer is still again. No blur (not cheap on Android 8.1) and no
 * extra bitmaps: one offscreen layer for the shade, gradients for the rest.
 */
private class Glow(
    val left: Float,
    val top: Float,
    val reach: Float,
    val room: Int,
    val color: Color,
    val level: State<Float>,
)

private const val FADE_MS = 400
private const val SHADE_FADE_MS = 1500
private const val DAY_ELEVATION = 6.0
private const val NIGHT_ELEVATION = -6.0
private val SHADE = Color(0xFF060910)
private val WARM_WHITE = Color(255, 196, 120)

@Suppress("UNCHECKED_CAST")
@Composable
internal fun LitRoomsLayer(
    opts: Map<String, Any?>,
    elements: List<Map<String, Any?>>,
    ctx: CardContext,
) {
    val rooms = (opts["rooms"] as? List<Map<String, Any?>>).orEmpty()
    if (rooms.isEmpty()) return
    val shapes = remember(rooms) {
        rooms.map { r ->
            (r["shape"] as? List<*>).orEmpty().mapNotNull { p ->
                val xy = (p as? List<*>)?.filterIsInstance<Number>() ?: return@mapNotNull null
                if (xy.size == 2) xy[0].toFloat() / 100f to xy[1].toFloat() / 100f else null
            }
        }
    }
    val reach = ((opts["reach"] as? Number)?.toFloat() ?: 26f) / 100f
    val glowStrength = ((opts["glow"] as? Number)?.toFloat() ?: 0.85f).coerceIn(0f, 1f)
    val maxShade = ((opts["shade_alpha"] as? Number)?.toFloat() ?: 0.68f).coerceIn(0f, 1f)
    val night = { if (screensaverIsNight(ctx.entities, System.currentTimeMillis())) 1f else 0f }
    val shadeTarget = maxShade * when (opts["shade"] as? String ?: "sun") {
        "off" -> 0f
        "always" -> 1f
        "night" -> night()
        // Reads only sun.sun, whose elevation HA refreshes every few minutes.
        else -> duskFraction(ctx.entities["sun.sun"]) ?: night()
    }
    // Each new elevation eases in, so dusk deepens smoothly rather than in steps.
    val shade = animateFloatAsState(shadeTarget, tween(SHADE_FADE_MS), label = "shade")

    // Only the room lights are read, one entity each, so this recomposes when
    // one of them changes and not for the radar sensors. key() keeps each
    // light's fade attached to that light.
    val glows = mutableListOf<Glow>()
    rooms.forEachIndexed { roomIndex, r ->
        (r["lights"] as? List<*>).orEmpty().filterIsInstance<String>().forEach { id ->
            val el = elements.firstOrNull { it["entity_id"] == id } ?: return@forEach
            key(roomIndex, id) {
                val e = ctx.entities[id]
                val on = e != null && e.isOn
                val level = animateFloatAsState(
                    targetValue = if (on) 0.15f + 0.85f * brightnessOf(e!!) else 0f,
                    animationSpec = tween(FADE_MS),
                    label = "glow",
                )
                val color = e?.let { lightColor(it) } ?: WARM_WHITE
                val spots = (el["glow_spots"] as? List<*>).orEmpty().mapNotNull { s ->
                    (s as? List<*>)?.filterIsInstance<Number>()?.takeIf { it.size == 2 }
                }.ifEmpty { listOf(listOf(el["left"] as? Number ?: 50, el["top"] as? Number ?: 50)) }
                spots.forEach { (l, t) ->
                    glows += Glow(
                        left = l.toFloat() / 100f,
                        top = t.toFloat() / 100f,
                        reach = reach * ((el["glow_reach"] as? Number)?.toFloat() ?: 1f),
                        room = roomIndex,
                        color = color,
                        level = level,
                    )
                }
            }
        }
    }

    // The shade needs its own layer so each pool can cut a hole in it.
    // In daylight there is no shade layer at all.
    if (shadeTarget > 0f || shade.value > 0.002f) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val paths = shapes.map { it.toPath(size.width, size.height) }
                    onDrawBehind {
                        drawRect(SHADE.copy(alpha = shade.value))
                        glows.forEach { g ->
                            pool(g, paths, Color.Black, (g.level.value * 1.45f).coerceAtMost(1f), BlendMode.DstOut)
                        }
                    }
                },
        )
    }

    // The colour, added on top of the plan (and the shade) with Screen.
    Box(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val paths = shapes.map { it.toPath(size.width, size.height) }
                onDrawBehind {
                    glows.forEach { g ->
                        pool(g, paths, g.color, (g.level.value * glowStrength * 1.15f).coerceAtMost(1f), BlendMode.Screen)
                    }
                }
            },
    )
}

/**
 * 0 in daylight, 1 at night, and in between through dusk and dawn: linear in
 * the sun's elevation from [DAY_ELEVATION] down to [NIGHT_ELEVATION] (civil
 * twilight). Null when HA reports no elevation.
 */
private fun duskFraction(sun: EntityState?): Float? {
    val elevation = sun?.attrDouble("elevation") ?: return null
    return ((DAY_ELEVATION - elevation) / (DAY_ELEVATION - NIGHT_ELEVATION)).toFloat().coerceIn(0f, 1f)
}

private fun List<Pair<Float, Float>>.toPath(w: Float, h: Float): Path? {
    if (size < 3) return null
    return Path().apply {
        moveTo(this@toPath[0].first * w, this@toPath[0].second * h)
        for (i in 1 until this@toPath.size) lineTo(this@toPath[i].first * w, this@toPath[i].second * h)
        close()
    }
}

/** One light's pool: a radial gradient from its icon, clipped to its room. */
private fun DrawScope.pool(g: Glow, paths: List<Path?>, color: Color, alpha: Float, mode: BlendMode) {
    val k = g.level.value
    if (k < 0.01f || alpha <= 0f) return
    val path = paths.getOrNull(g.room) ?: return
    val center = Offset(g.left * size.width, g.top * size.height)
    val radius = g.reach * size.width * (0.6f + 0.4f * k)
    val brush = Brush.radialGradient(
        0f to color.copy(alpha = alpha),
        0.45f to color.copy(alpha = alpha * 0.55f),
        1f to color.copy(alpha = 0f),
        center = center,
        radius = radius,
    )
    clipPath(path) { drawCircle(brush, radius, center, blendMode = mode) }
}

/** 0–1. A light that is on but reports no brightness counts as full. */
private fun brightnessOf(e: EntityState): Float =
    e.attrInt("brightness")?.let { (it / 255f).coerceIn(0f, 1f) } ?: 1f

/** The light's real colour: rgb_color, else its colour temperature, else warm white. */
internal fun lightColor(e: EntityState): Color? {
    (e.attr("rgb_color") as? JsonArray)?.takeIf { it.size >= 3 }?.let { rgb ->
        fun ch(i: Int) = (rgb[i] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()?.coerceIn(0, 255)
        val r = ch(0); val g = ch(1); val b = ch(2)
        if (r != null && g != null && b != null) return Color(r, g, b)
    }
    e.attrInt("color_temp_kelvin")?.let { return kelvinToColor(it) }
    return null
}

/** Tanner Helland's blackbody approximation, good enough for a glow. */
private fun kelvinToColor(kelvin: Int): Color {
    val t = kelvin.coerceIn(1000, 12000) / 100.0
    val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
    val g = if (t <= 66) 99.4708025861 * ln(t) - 161.1195681661 else 288.1221695283 * (t - 60).pow(-0.0755148492)
    val b = when {
        t >= 66 -> 255.0
        t <= 19 -> 0.0
        else -> 138.5177312231 * ln(t - 10) - 305.0447927307
    }
    return Color(r.toInt().coerceIn(0, 255), g.toInt().coerceIn(0, 255), b.toInt().coerceIn(0, 255))
}
