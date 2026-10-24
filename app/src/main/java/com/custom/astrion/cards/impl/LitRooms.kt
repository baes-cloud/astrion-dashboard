package com.custom.astrion.cards.impl

import com.custom.astrion.ui.AstrionTheme
import androidx.compose.runtime.ReadOnlyComposable
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
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
 *     "shade_alpha": 0.8,        // how dark an unlit room gets, 0–1
 *     "glow": 0.45,              // colour strength at night, 0–1
 *     "room_glow": 0.15,         // a room with any light on lifts this much of
 *                                // its shade everywhere and takes a faint tint
 *     "day_glow": 0.3,           // share of that strength in full daylight, 0–1;
 *                                // rises to 1 through dusk with the shade
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
 * gets six smaller pools. "glow_strength" brightens (or dims) one light's
 * pools against the rest, for a low strip that should still read as lit.
 *   { "entity_id": "light.downlights", "left": 65, "top": 28,
 *     "glow_spots": [[24,17],[59,12],[84,18]], "glow_reach": 0.6 }
 *
 * "glow_line" draws a cove strip between two points instead of pools: the
 * lit rail itself, and a wash of its colour spilling off it the way a strip
 * under the ceiling lights the ceiling and wall. "glow_toward" is a point on
 * the room side; the wash runs deep that way and only a little behind the
 * rail. "glow_reach" scales how deep it runs.
 *   { "entity_id": "light.club_led_group", "glow_line": [[1,47],[98,47]],
 *     "glow_toward": [50,20] }
 *
 * Pools only part-cut the shade, so a lit spot reads as lamplight in a dark
 * room rather than a hole back to the daytime plan.
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
    val customShape: List<Pair<Float, Float>>? = null,
    /**
     * Whether this light also lifts its whole room's shade. "glow_ambient":
     * false keeps an accent light (the LED strip under the sofa) to its own
     * soft pools, so it doesn't read as the room being lit.
     */
    val ambient: Boolean = true,
    /** "glow_strength": how bright this light's pools are, 1 = the card's. */
    val strength: Float = 1f,
    /** "glow_line": the far end of a cove strip from (left, top); null for a pool. */
    val lineEnd: Pair<Float, Float>? = null,
    /** "glow_toward": a point on the side the strip's wash runs deep; null for both sides. */
    val toward: Pair<Float, Float>? = null,
)

/** How much of the shade a full-brightness pool removes at its centre. */
private const val POOL_CUT = 0.6f
/** Pool colour: Multiply tint and Screen highlight, relative to glow. */
private const val TINT = 0.8f
private const val SHINE = 0.15f
/** Tint strength of a lit room's ambient wash, relative to room_glow. */
private const val ROOM_TINT = 0.35f
/** A light on at its lowest brightness still glows this much. */
private const val LEVEL_FLOOR = 0.15f

private const val FADE_MS = 400
private const val SHADE_FADE_MS = 1500
private const val DAY_ELEVATION = 6.0
private const val NIGHT_ELEVATION = -6.0
private val SHADE: Color @Composable @ReadOnlyComposable get() = AstrionTheme.colors.void
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
    val glowStrength = ((opts["glow"] as? Number)?.toFloat() ?: 0.45f).coerceIn(0f, 1f)
    val dayGlow = ((opts["day_glow"] as? Number)?.toFloat() ?: 0.3f).coerceIn(0f, 1f)
    val maxShade = ((opts["shade_alpha"] as? Number)?.toFloat() ?: 0.8f).coerceIn(0f, 1f)
    val roomGlow = ((opts["room_glow"] as? Number)?.toFloat() ?: 0.15f).coerceIn(0f, 1f)
    val night = { if (screensaverIsNight(ctx.entities, System.currentTimeMillis())) 1f else 0f }
    val shadeMode = opts["shade"] as? String ?: "sun"
    // Reads only sun.sun, whose elevation HA refreshes every few minutes.
    val dusk = duskFraction(ctx.entities["sun.sun"]) ?: night()
    val shadeTarget = maxShade * when (shadeMode) {
        "off" -> 0f
        "always" -> 1f
        "night" -> night()
        else -> dusk
    }
    // Each new elevation eases in, so dusk deepens smoothly rather than in steps.
    val shade = animateFloatAsState(shadeTarget, tween(SHADE_FADE_MS), label = "shade")
    // Full-strength pools bleach the bright daytime plan, so the colour is
    // faint by day and comes up with the dark. Follows the shade mode, or the
    // sun when there is no shade.
    val darkness = when (shadeMode) {
        "always" -> 1f
        "night" -> night()
        else -> dusk
    }
    val glowScale = animateFloatAsState(
        dayGlow + (1f - dayGlow) * darkness, tween(SHADE_FADE_MS), label = "glowScale",
    )

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
                    targetValue = if (on) LEVEL_FLOOR + (1f - LEVEL_FLOOR) * brightnessOf(e!!) else 0f,
                    animationSpec = tween(FADE_MS),
                    label = "glow",
                )
                val color = e?.let { lightColor(it) } ?: WARM_WHITE
                val customShape = (el["glow_shape"] as? List<*>).orEmpty().mapNotNull { p ->
                    val xy = (p as? List<*>)?.filterIsInstance<Number>() ?: return@mapNotNull null
                    if (xy.size == 2) xy[0].toFloat() / 100f to xy[1].toFloat() / 100f else null
                }.takeIf { it.size >= 3 }
                val line = (el["glow_line"] as? List<*>).orEmpty().mapNotNull { p ->
                    (p as? List<*>)?.filterIsInstance<Number>()?.takeIf { it.size == 2 }
                }.takeIf { it.size == 2 }
                if (line != null) {
                    glows += Glow(
                        left = line[0][0].toFloat() / 100f,
                        top = line[0][1].toFloat() / 100f,
                        reach = WASH_DEPTH * ((el["glow_reach"] as? Number)?.toFloat() ?: 1f),
                        room = roomIndex,
                        color = color,
                        level = level,
                        ambient = el["glow_ambient"] as? Boolean ?: true,
                        strength = (el["glow_strength"] as? Number)?.toFloat() ?: 1f,
                        lineEnd = line[1][0].toFloat() / 100f to line[1][1].toFloat() / 100f,
                        toward = (el["glow_toward"] as? List<*>)?.filterIsInstance<Number>()
                            ?.takeIf { it.size == 2 }?.let { it[0].toFloat() / 100f to it[1].toFloat() / 100f },
                    )
                } else {
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
                            customShape = customShape,
                            ambient = el["glow_ambient"] as? Boolean ?: (customShape == null),
                            strength = (el["glow_strength"] as? Number)?.toFloat() ?: 1f,
                        )
                    }
                }
            }
        }
    }

    // The shade needs its own layer so each pool can cut a hole in it.
    // In daylight there is no shade layer at all.
    val shadeInk = SHADE
    if (shadeTarget > 0f || shade.value > 0.002f) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithCache {
                    val paths = shapes.map { it.toPath(size.width, size.height) }
                    onDrawBehind {
                        drawRect(shadeInk.copy(alpha = shade.value))
                        // A lit room is never as dark as an empty one.
                        roomLevels(glows).forEach { (room, lit) ->
                            val path = paths.getOrNull(room) ?: return@forEach
                            drawPath(path, Color.Black.copy(alpha = lit.level * roomGlow), blendMode = BlendMode.DstOut)
                        }
                        glows.forEach { g ->
                            val cut = (g.level.value * POOL_CUT * g.strength).coerceAtMost(1f)
                            if (g.lineEnd != null) wash(g, paths, Color.Black, cut, BlendMode.DstOut) else pool(g, paths, Color.Black, cut, BlendMode.DstOut)
                        }
                    }
                },
        )
    }

    // The colour. Multiply tints what the pool reveals the way lamplight
    // colours a surface; a little Screen on top gives the bright core. Screen
    // alone pushed pale lights toward white and the plan looked hazy.
    Box(
        Modifier
            .fillMaxSize()
            .drawWithCache {
                val paths = shapes.map { it.toPath(size.width, size.height) }
                onDrawBehind {
                    roomLevels(glows).forEach { (room, lit) ->
                        val path = paths.getOrNull(room) ?: return@forEach
                        val a = lit.level * roomGlow * ROOM_TINT * glowScale.value
                        drawPath(path, lit.color.copy(alpha = a), blendMode = BlendMode.Multiply)
                    }
                    glows.forEach { g ->
                        val a = (g.level.value * glowStrength * glowScale.value * g.strength).coerceAtMost(1f)
                        if (g.lineEnd != null) {
                            wash(g, paths, g.color, a * TINT, BlendMode.Multiply)
                            wash(g, paths, g.color, a * WASH_SHINE, BlendMode.Screen)
                            rail(g)
                            return@forEach
                        }
                        pool(g, paths, g.color, a * TINT, BlendMode.Multiply)
                        pool(g, paths, g.color, a * SHINE, BlendMode.Screen)
                    }
                }
            },
    )
}

private class RoomLit(val level: Float, val color: Color)

/** Each room with a light on: its brightest light's level and colour. */
private fun roomLevels(glows: List<Glow>): Map<Int, RoomLit> {
    val out = HashMap<Int, RoomLit>()
    glows.forEach { g ->
        val k = g.level.value
        if (g.ambient && k >= 0.01f && k > (out[g.room]?.level ?: 0f)) {
            out[g.room] = RoomLit(k, g.color)
        }
    }
    return out
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

/** One light's pool: a radial gradient from its icon, clipped to its room or custom shape. */
private fun DrawScope.pool(g: Glow, paths: List<Path?>, color: Color, alpha: Float, mode: BlendMode) {
    val k = g.level.value
    if (k < 0.01f || alpha <= 0f) return
    val path = g.customShape?.toPath(size.width, size.height) ?: paths.getOrNull(g.room) ?: return
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

/**
 * A cove strip's wash: a band of light off its line, fading as it goes, deep
 * toward [Glow.toward] and shallow behind the rail, clipped to its room.
 */
private fun DrawScope.wash(g: Glow, paths: List<Path?>, color: Color, alpha: Float, mode: BlendMode) {
    val end = g.lineEnd ?: return
    if (g.level.value < 0.01f || alpha <= 0f) return
    val a = Offset(g.left * size.width, g.top * size.height)
    val b = Offset(end.first * size.width, end.second * size.height)
    val d = b - a
    val len = d.getDistance().takeIf { it > 0f } ?: return
    var n = Offset(-d.y / len, d.x / len)
    val toward = g.toward
    if (toward != null && (toward.first * size.width - a.x) * n.x + (toward.second * size.height - a.y) * n.y < 0f) n = -n
    val deep = g.reach * size.minDimension
    val back = if (toward != null) deep * WASH_BACK else deep
    val path = paths.getOrNull(g.room)
    if (path == null) {
        band(a, b, n * deep, color, alpha, mode)
        band(a, b, -n * back, color, alpha, mode)
    } else clipPath(path) {
        band(a, b, n * deep, color, alpha, mode)
        band(a, b, -n * back, color, alpha, mode)
    }
}

/** One side of a wash: the quad from a–b out by [far], brightest at the line. */
private fun DrawScope.band(a: Offset, b: Offset, far: Offset, color: Color, alpha: Float, mode: BlendMode) {
    val brush = Brush.linearGradient(
        0f to color.copy(alpha = alpha),
        0.12f to color.copy(alpha = alpha * 0.75f),
        0.45f to color.copy(alpha = alpha * 0.3f),
        1f to color.copy(alpha = 0f),
        start = a,
        end = a + far,
    )
    val quad = Path().apply {
        moveTo(a.x, a.y)
        lineTo(b.x, b.y)
        lineTo(b.x + far.x, b.y + far.y)
        lineTo(a.x + far.x, a.y + far.y)
        close()
    }
    drawPath(quad, brush, blendMode = mode)
}

/**
 * The lit rail itself: a saturated bar with a soft bloom, as bright as the
 * light. Seen by day too, like the real strip, so no glowScale.
 */
private fun DrawScope.rail(g: Glow) {
    val k = g.level.value
    val end = g.lineEnd ?: return
    if (k < 0.01f) return
    // level is LEVEL_FLOOR + brightness, so split it back into on/off fade and brightness.
    val fade = (k / LEVEL_FLOOR).coerceAtMost(1f)
    val p = ((k - LEVEL_FLOOR) / (1f - LEVEL_FLOOR)).coerceIn(0f, 1f)
    val a = Offset(g.left * size.width, g.top * size.height)
    val b = Offset(end.first * size.width, end.second * size.height)
    drawLine(g.color.copy(alpha = fade * (0.075f + 0.15f * p)), a, b, RAIL_BLOOM.toPx(), StrokeCap.Round)
    drawLine(lerp(g.color, Color.White, 0.3f * p).copy(alpha = fade * (0.225f + 0.275f * p)), a, b, RAIL.toPx(), StrokeCap.Round)
}

/** How deep a cove strip's wash runs into the room, as a fraction of the plan's short side. */
private const val WASH_DEPTH = 0.3f
/** How far it spills behind the rail, relative to its depth. */
private const val WASH_BACK = 0.25f
/** The wash's Screen highlight: a strip lights its ceiling harder than a lamp's pool. */
private const val WASH_SHINE = 0.35f
private val RAIL = 2.5.dp
private val RAIL_BLOOM = 7.dp

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
