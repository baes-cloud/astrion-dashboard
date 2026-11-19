package com.custom.astrion.cards.impl

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardContext
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.parseHexColor
import com.custom.astrion.ui.screensaverIsNight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Lit rooms (Step 5a + Step 5b GPU-optimized):
 *  - Step 5a (always on, zero visual risk):
 *      * Caches ALL Path, Stroke, and unit-alpha radial Brush objects inside
 *        `drawWithCache` (using draw-time `alpha` + `scale` so `GradientCache`
 *        never thrashes during 400 ms light fades).
 *      * Replaces per-frame `HashMap<Int, RoomLit>` allocations with fixed arrays.
 *      * Uses `derivedStateOf` for the shade-layer visibility gate.
 *  - Step 5b (feature-flagged via `"baked_glow_masks": true` in `lit_rooms`):
 *      * Pre-bakes cove-strip washes and outline halos off-thread into 1/4-res
 *        `ALPHA_8` bitmaps (~10 KB each), replacing 32 stroked `drawPath` calls
 *        per pass (96 paths/fixture) with 1 single alpha-tinted `drawImage` blit.
 */
private class Glow(
    val key: String,
    val left: Float,
    val top: Float,
    val reach: Float,
    val room: Int,
    val color: Color,
    val level: State<Float>,
    val customShape: List<Pair<Float, Float>>? = null,
    val ambient: Boolean = true,
    val strength: Float = 1f,
    val line: List<Pair<Float, Float>>? = null,
    val outline: List<Pair<Float, Float>>? = null,
)

private class CachedGlowDraw(
    val glow: Glow,
    val clipPath: Path?,
    val linePath: Path?,
    val outlinePath: Path?,
    val poolCenter: Offset,
    val poolRadius: Float,
    val poolBlackBrush: Brush?,
    val poolColorBrush: Brush?,
    val washStrokes: List<Stroke>,
    val haloInnerStrokes: List<Stroke>,
    val haloOuterStrokes: List<Stroke>,
)

private const val POOL_CUT = 0.6f
private const val TINT = 0.8f
private const val SHINE = 0.15f
private const val NIGHT_SHINE = 0.3f
private const val ROOM_TINT = 0.35f
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
    val bakedEnabled = opts["baked_glow_masks"] as? Boolean ?: false
    val recolor = remember(opts) { parseRecolor(opts) }
    val night = { if (screensaverIsNight(ctx.entities, System.currentTimeMillis())) 1f else 0f }
    val shadeMode = opts["shade"] as? String ?: "sun"
    val dusk = duskFraction(ctx.entities["sun.sun"]) ?: night()
    val shadeTarget = maxShade * when (shadeMode) {
        "off" -> 0f
        "always" -> 1f
        "night" -> night()
        else -> dusk
    }
    val shade = animateFloatAsState(shadeTarget, tween(SHADE_FADE_MS), label = "shade")
    val darkness = when (shadeMode) {
        "always" -> 1f
        "night" -> night()
        else -> dusk
    }
    val glowScale = animateFloatAsState(
        dayGlow + (1f - dayGlow) * darkness, tween(SHADE_FADE_MS), label = "glowScale",
    )
    val shine = animateFloatAsState(
        SHINE + (NIGHT_SHINE - SHINE) * darkness, tween(SHADE_FADE_MS), label = "shine",
    )

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
                val color = parseHexColor(el["glow_color"] as? String) ?: e?.let { lightColor(it, recolor) } ?: WARM_WHITE
                val customShape = remember(el) {
                    (el["glow_shape"] as? List<*>).orEmpty().mapNotNull { p ->
                        val xy = (p as? List<*>)?.filterIsInstance<Number>() ?: return@mapNotNull null
                        if (xy.size == 2) xy[0].toFloat() / 100f to xy[1].toFloat() / 100f else null
                    }.takeIf { it.size >= 3 }
                }
                val linePaths = remember(el) {
                    (el["glow_line"] as? List<*>).orEmpty().let { raw ->
                        val multi = ((raw.firstOrNull() as? List<*>)?.firstOrNull() is List<*>)
                        (if (multi) raw else listOf(raw)).mapNotNull { path ->
                            (path as? List<*>).orEmpty().mapNotNull { p ->
                                val xy = (p as? List<*>)?.filterIsInstance<Number>() ?: return@mapNotNull null
                                if (xy.size == 2) xy[0].toFloat() / 100f to xy[1].toFloat() / 100f else null
                            }.takeIf { it.size >= 2 }
                        }
                    }
                }
                val outline = remember(el) {
                    (el["glow_outline"] as? List<*>).orEmpty().mapNotNull { p ->
                        val xy = (p as? List<*>)?.filterIsInstance<Number>() ?: return@mapNotNull null
                        if (xy.size == 2) xy[0].toFloat() / 100f to xy[1].toFloat() / 100f else null
                    }.takeIf { it.size >= 3 }
                }
                val glowReach = (el["glow_reach"] as? Number)?.toFloat() ?: 1f
                val strength = (el["glow_strength"] as? Number)?.toFloat() ?: 1f
                linePaths.forEachIndexed { idx, path ->
                    glows += Glow(
                        key = "line:$roomIndex:$id:$idx",
                        left = path[0].first,
                        top = path[0].second,
                        reach = WASH_DEPTH * glowReach,
                        room = roomIndex,
                        color = color,
                        level = level,
                        ambient = el["glow_ambient"] as? Boolean ?: true,
                        strength = strength,
                        line = path,
                    )
                }
                if (outline != null) {
                    glows += Glow(
                        key = "outline:$roomIndex:$id",
                        left = outline[0].first,
                        top = outline[0].second,
                        reach = OUTLINE_DEPTH * glowReach,
                        room = roomIndex,
                        color = color,
                        level = level,
                        ambient = el["glow_ambient"] as? Boolean ?: false,
                        strength = strength,
                        outline = outline,
                    )
                }
                val spots = remember(el, linePaths, outline) {
                    (el["glow_spots"] as? List<*>).orEmpty().mapNotNull { s ->
                        (s as? List<*>)?.filterIsInstance<Number>()?.takeIf { it.size == 2 }
                    }.ifEmpty {
                        if (linePaths.isNotEmpty() || outline != null) emptyList()
                        else listOf(listOf(el["left"] as? Number ?: 50, el["top"] as? Number ?: 50))
                    }
                }
                spots.forEachIndexed { idx, (l, t) ->
                    glows += Glow(
                        key = "spot:$roomIndex:$id:$idx",
                        left = l.toFloat() / 100f,
                        top = t.toFloat() / 100f,
                        reach = reach * glowReach,
                        room = roomIndex,
                        color = color,
                        level = level,
                        customShape = customShape,
                        ambient = el["glow_ambient"] as? Boolean ?: (customShape == null),
                        strength = strength,
                    )
                }
            }
        }
    }

    // Step 5b: off-thread 1/4-res ALPHA_8 StackBlur masks for line washes & outline halos.
    var layerSize by remember { mutableStateOf(IntSize.Zero) }
    var bakedMasks by remember { mutableStateOf<Map<String, ImageBitmap>>(emptyMap()) }
    val geomKey = remember(glows.size, shapes, bakedEnabled) {
        glows.map { Triple(it.key, it.line, it.outline) }
    }
    LaunchedEffect(bakedEnabled, layerSize, geomKey, shapes) {
        if (!bakedEnabled || layerSize.width < 16 || layerSize.height < 16) {
            bakedMasks = emptyMap()
            return@LaunchedEffect
        }
        val snapshot = glows.filter { it.line != null || it.outline != null }
        bakedMasks = withContext(Dispatchers.Default) {
            buildMap {
                snapshot.forEach { g ->
                    val roomShape = shapes.getOrNull(g.room)
                    val bmp = when {
                        g.line != null -> BakedGlowMasks.bakeLineWash(layerSize.width, layerSize.height, g.line, g.reach, roomShape)
                        g.outline != null -> BakedGlowMasks.bakeOutlineHalo(layerSize.width, layerSize.height, g.outline, g.reach, roomShape)
                        else -> null
                    }
                    if (bmp != null) put(g.key, bmp)
                }
            }
        }
    }

    // Reusable per-room scratch arrays (avoids HashMap allocation per draw frame).
    val roomCount = shapes.size
    val shadeRoomLevels = remember(roomCount) { FloatArray(roomCount) }
    val colorRoomLevels = remember(roomCount) { FloatArray(roomCount) }
    val colorRoomTint = remember(roomCount) { arrayOfNulls<Color>(roomCount) }

    // derivedStateOf: only recomposes when shade crosses the visibility threshold.
    val showShade by remember(shadeTarget) { derivedStateOf { shadeTarget > 0f || shade.value > 0.002f } }
    val shadeInk = SHADE

    Box(Modifier.fillMaxSize().onSizeChanged { layerSize = it }) {
        if (showShade) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithCache {
                        val paths = shapes.map { it.toPath(size.width, size.height) }
                        val cached = buildCachedGlows(glows, paths, size.width, size.height)
                        val dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
                        val blackFilter = ColorFilter.tint(Color.Black)
                        onDrawBehind {
                            drawRect(shadeInk.copy(alpha = shade.value))
                            shadeRoomLevels.fill(0f)
                            for (i in cached.indices) {
                                val g = cached[i].glow
                                val k = g.level.value
                                if (g.ambient && k >= 0.01f && g.room in 0 until roomCount && k > shadeRoomLevels[g.room]) {
                                    shadeRoomLevels[g.room] = k
                                }
                            }
                            for (r in 0 until roomCount) {
                                val lv = shadeRoomLevels[r]
                                if (lv >= 0.01f) {
                                    paths.getOrNull(r)?.let { path ->
                                        drawPath(path, Color.Black.copy(alpha = lv * roomGlow), blendMode = BlendMode.DstOut)
                                    }
                                }
                            }
                            for (i in cached.indices) {
                                val c = cached[i]
                                val g = c.glow
                                val cut = (g.level.value * POOL_CUT * g.strength).coerceAtMost(1f)
                                if (cut <= 0f) continue
                                val mask = if (bakedEnabled) bakedMasks[g.key] else null
                                when {
                                    mask != null -> {
                                        val mAlpha = if (g.outline != null) cut * HALO_CUT else cut
                                        drawImage(
                                            image = mask,
                                            dstSize = dstSize,
                                            alpha = mAlpha.coerceIn(0f, 1f),
                                            colorFilter = blackFilter,
                                            blendMode = BlendMode.DstOut,
                                        )
                                    }
                                    g.line != null -> drawCachedWash(c, paths, Color.Black, cut, BlendMode.DstOut)
                                    g.outline != null -> drawCachedHalo(c, paths, Color.Black, cut * HALO_CUT, BlendMode.DstOut)
                                    else -> drawCachedPool(c, c.poolBlackBrush, cut, BlendMode.DstOut)
                                }
                            }
                        }
                    },
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .drawWithCache {
                    val paths = shapes.map { it.toPath(size.width, size.height) }
                    val cached = buildCachedGlows(glows, paths, size.width, size.height)
                    val dstSize = IntSize(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
                    val railBloomStroke = Stroke(width = RAIL_BLOOM.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    val railCoreStroke = Stroke(width = RAIL.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
                    onDrawBehind {
                        colorRoomLevels.fill(0f)
                        colorRoomTint.fill(null)
                        for (i in cached.indices) {
                            val g = cached[i].glow
                            val k = g.level.value
                            if (g.ambient && k >= 0.01f && g.room in 0 until roomCount && k > colorRoomLevels[g.room]) {
                                colorRoomLevels[g.room] = k
                                colorRoomTint[g.room] = g.color
                            }
                        }
                        val gs = glowScale.value
                        val sh = shine.value
                        for (r in 0 until roomCount) {
                            val lv = colorRoomLevels[r]
                            val tintColor = colorRoomTint[r]
                            if (lv >= 0.01f && tintColor != null) {
                                paths.getOrNull(r)?.let { path ->
                                    drawPath(path, tintColor.copy(alpha = lv * roomGlow * ROOM_TINT * gs), blendMode = BlendMode.Multiply)
                                }
                            }
                        }
                        for (i in cached.indices) {
                            val c = cached[i]
                            val g = c.glow
                            val a = (g.level.value * glowStrength * gs * g.strength).coerceAtMost(1f)
                            val mask = if (bakedEnabled) bakedMasks[g.key] else null
                            if (g.line != null) {
                                if (a > 0f) {
                                    if (mask != null) {
                                        val tint = ColorFilter.tint(g.color)
                                        drawImage(mask, dstSize = dstSize, alpha = (a * TINT).coerceIn(0f, 1f), colorFilter = tint, blendMode = BlendMode.Multiply)
                                        drawImage(mask, dstSize = dstSize, alpha = (a * WASH_SHINE).coerceIn(0f, 1f), colorFilter = tint, blendMode = BlendMode.Screen)
                                    } else {
                                        drawCachedWash(c, paths, g.color, a * TINT, BlendMode.Multiply)
                                        drawCachedWash(c, paths, g.color, a * WASH_SHINE, BlendMode.Screen)
                                    }
                                }
                                drawCachedRail(c, railBloomStroke, railCoreStroke)
                                continue
                            }
                            if (g.outline != null) {
                                if (a > 0f) {
                                    if (mask != null) {
                                        val tint = ColorFilter.tint(g.color)
                                        drawImage(mask, dstSize = dstSize, alpha = (a * TINT).coerceIn(0f, 1f), colorFilter = tint, blendMode = BlendMode.Multiply)
                                        drawImage(mask, dstSize = dstSize, alpha = (a * WASH_SHINE).coerceIn(0f, 1f), colorFilter = tint, blendMode = BlendMode.Screen)
                                    } else {
                                        drawCachedHalo(c, paths, g.color, a * TINT, BlendMode.Multiply)
                                        drawCachedHalo(c, paths, g.color, a * WASH_SHINE, BlendMode.Screen)
                                    }
                                }
                                continue
                            }
                            if (a > 0f) {
                                drawCachedPool(c, c.poolColorBrush, a * TINT, BlendMode.Multiply)
                                drawCachedPool(c, c.poolColorBrush, a * sh, BlendMode.Screen)
                            }
                        }
                    }
                },
        )
    }
}

private fun buildCachedGlows(
    glows: List<Glow>,
    roomPaths: List<Path?>,
    w: Float,
    h: Float,
): List<CachedGlowDraw> {
    val minDim = minOf(w, h)
    return glows.map { g ->
        val clipPath = g.customShape?.toPath(w, h) ?: roomPaths.getOrNull(g.room)
        val linePath = g.line?.toOpenPath(w, h)
        val outlinePath = g.outline?.toPath(w, h)
        val center = Offset(g.left * w, g.top * h)
        val fullRadius = (g.reach * w).coerceAtLeast(1f)
        val isPool = g.line == null && g.outline == null
        val blackBrush = if (isPool) unitPoolBrush(Color.Black, center, fullRadius) else null
        val colorBrush = if (isPool) unitPoolBrush(g.color, center, fullRadius) else null

        val washStrokes = if (g.line != null) {
            val depth = g.reach * minDim
            WASH_RINGS.map { f -> Stroke(width = 2f * depth * f, cap = StrokeCap.Round, join = StrokeJoin.Round) }
        } else emptyList()

        val (haloInner, haloOuter) = if (g.outline != null) {
            val depth = g.reach * minDim
            val inner = HALO_RINGS.filter { it <= HALO_FEATHER }.map { f -> Stroke(width = 2f * depth * f, join = StrokeJoin.Round) }
            val outer = HALO_RINGS.filter { it > HALO_FEATHER }.map { f -> Stroke(width = 2f * depth * f, join = StrokeJoin.Round) }
            inner to outer
        } else emptyList<Stroke>() to emptyList()

        CachedGlowDraw(
            glow = g,
            clipPath = clipPath,
            linePath = linePath,
            outlinePath = outlinePath,
            poolCenter = center,
            poolRadius = fullRadius,
            poolBlackBrush = blackBrush,
            poolColorBrush = colorBrush,
            washStrokes = washStrokes,
            haloInnerStrokes = haloInner,
            haloOuterStrokes = haloOuter,
        )
    }
}

private fun unitPoolBrush(color: Color, center: Offset, radius: Float): Brush =
    Brush.radialGradient(
        0f to color.copy(alpha = 1f),
        0.18f to color.copy(alpha = 0.72f),
        0.42f to color.copy(alpha = 0.38f),
        0.7f to color.copy(alpha = 0.13f),
        1f to Color.Transparent,
        center = center,
        radius = radius,
    )

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

/** Draw pool reusing static unit-alpha gradient via draw-time scale + alpha (zero GradientCache churn). */
private fun DrawScope.drawCachedPool(c: CachedGlowDraw, brush: Brush?, alpha: Float, mode: BlendMode) {
    val k = c.glow.level.value
    if (k < 0.01f || alpha <= 0f || brush == null) return
    val path = c.clipPath ?: return
    val radiusScale = 0.6f + 0.4f * k
    clipPath(path) {
        scale(scale = radiusScale, pivot = c.poolCenter) {
            drawCircle(
                brush = brush,
                radius = c.poolRadius,
                center = c.poolCenter,
                alpha = alpha.coerceIn(0f, 1f),
                blendMode = mode,
            )
        }
    }
}

private fun DrawScope.drawCachedHalo(c: CachedGlowDraw, paths: List<Path?>, color: Color, alpha: Float, mode: BlendMode) {
    val shape = c.outlinePath ?: return
    if (c.glow.level.value < 0.01f || alpha <= 0f) return
    val ringColor = color.copy(alpha = (alpha * HALO_RING_ALPHA).coerceIn(0f, 1f))
    val draw: DrawScope.() -> Unit = {
        for (s in c.haloInnerStrokes) drawPath(shape, ringColor, style = s, blendMode = mode)
        clipPath(shape, ClipOp.Difference) {
            for (s in c.haloOuterStrokes) drawPath(shape, ringColor, style = s, blendMode = mode)
        }
    }
    val room = paths.getOrNull(c.glow.room)
    if (room == null) draw() else clipPath(room) { draw() }
}

private const val OUTLINE_DEPTH = 0.06f
private val HALO_RINGS = FloatArray(GLOW_STEPS) { i -> ((i + 1) / GLOW_STEPS.toFloat()).let { it * it } }
private const val HALO_RING_ALPHA = 0.04f
private const val HALO_CUT = 0.55f
private const val HALO_FEATHER = 0.3f

private fun DrawScope.drawCachedWash(c: CachedGlowDraw, paths: List<Path?>, color: Color, alpha: Float, mode: BlendMode) {
    val line = c.linePath ?: return
    if (c.glow.level.value < 0.01f || alpha <= 0f) return
    val ringColor = color.copy(alpha = (alpha * WASH_RING_ALPHA).coerceIn(0f, 1f))
    val draw: DrawScope.() -> Unit = {
        for (s in c.washStrokes) drawPath(line, ringColor, style = s, blendMode = mode)
    }
    val room = paths.getOrNull(c.glow.room)
    if (room == null) draw() else clipPath(room) { draw() }
}

private fun DrawScope.drawCachedRail(c: CachedGlowDraw, bloomStroke: Stroke, coreStroke: Stroke) {
    val k = c.glow.level.value
    val line = c.linePath ?: return
    if (k < 0.01f) return
    val fade = (k / LEVEL_FLOOR).coerceAtMost(1f)
    val p = ((k - LEVEL_FLOOR) / (1f - LEVEL_FLOOR)).coerceIn(0f, 1f)
    drawPath(line, c.glow.color.copy(alpha = fade * (0.075f + 0.15f * p)), style = bloomStroke)
    drawPath(line, lerp(c.glow.color, Color.White, 0.3f * p).copy(alpha = fade * (0.225f + 0.275f * p)), style = coreStroke)
}

private fun List<Pair<Float, Float>>.toOpenPath(w: Float, h: Float): Path? {
    if (size < 2) return null
    return Path().apply {
        moveTo(this@toOpenPath[0].first * w, this@toOpenPath[0].second * h)
        for (i in 1 until this@toOpenPath.size) lineTo(this@toOpenPath[i].first * w, this@toOpenPath[i].second * h)
    }
}

private const val WASH_DEPTH = 0.27f
private const val GLOW_STEPS = 32
private val WASH_RINGS = FloatArray(GLOW_STEPS) { i -> ((i + 1) / GLOW_STEPS.toFloat()).pow(2.2f) }
private const val WASH_RING_ALPHA = 0.042f
private const val WASH_SHINE = 0.28f
private val RAIL = 2.5.dp
private val RAIL_BLOOM = 7.dp

private fun brightnessOf(e: EntityState): Float =
    e.attrInt("brightness")?.let { (it / 255f).coerceIn(0f, 1f) } ?: 1f

internal fun lightColor(e: EntityState, recolor: List<Pair<Color, Color>> = emptyList()): Color? {
    val c = rawLightColor(e) ?: return null
    return recolor.firstOrNull { (from, _) -> near(c, from) }?.second ?: c
}

private fun rawLightColor(e: EntityState): Color? {
    (e.attr("rgb_color") as? JsonArray)?.takeIf { it.size >= 3 }?.let { rgb ->
        fun ch(i: Int) = (rgb[i] as? JsonPrimitive)?.content?.toDoubleOrNull()?.toInt()?.coerceIn(0, 255)
        val r = ch(0); val g = ch(1); val b = ch(2)
        if (r != null && g != null && b != null) return Color(r, g, b)
    }
    e.attrInt("color_temp_kelvin")?.let { return kelvinToColor(it) }
    return null
}

internal fun parseRecolor(opts: Map<String, Any?>?): List<Pair<Color, Color>> =
    (opts?.get("recolor") as? List<*>).orEmpty().mapNotNull { r ->
        val m = r as? Map<*, *> ?: return@mapNotNull null
        val from = parseHexColor(m["from"] as? String) ?: return@mapNotNull null
        val to = parseHexColor(m["to"] as? String) ?: return@mapNotNull null
        from to to
    }

private const val RECOLOR_NEAR = 40f / 255f

private fun near(a: Color, b: Color): Boolean {
    val dr = a.red - b.red; val dg = a.green - b.green; val db = a.blue - b.blue
    return dr * dr + dg * dg + db * db <= RECOLOR_NEAR * RECOLOR_NEAR
}

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
