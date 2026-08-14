package com.custom.astrion.cards.impl

import android.graphics.BitmapFactory
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Floorplan card — the picture-elements equivalent. Draws a background image
 * (loaded safely off-thread) with tappable icons positioned by percentage,
 * each toggling a light and lighting up (amber) when that entity is on.
 * Long-pressing a light icon opens the same colour/brightness detail popup
 * as the bubble_light card on the Lights page.
 */
class PictureElementsCard : CardRenderer {
    override val type = "picture_elements"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val imagePath = config.string("image") ?: "/sdcard/astrion/floorplan.png"
        val elements = (config.options["elements"] as? List<Map<String, Any?>>) ?: emptyList()
        var showVacuumDialog by remember { mutableStateOf(false) }
        var detailEntity by remember { mutableStateOf<String?>(null) }

        // Decode off-thread safely via produceState + Dispatchers.IO
        val bitmap by produceState<ImageBitmap?>(initialValue = null, imagePath) {
            value = withContext(Dispatchers.IO) {
                runCatching {
                    val f = File(imagePath)
                    if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
                }.getOrNull()
            }
        }

        val aspect = bitmap?.let { it.width.toFloat() / it.height.toFloat() }
            ?: (config.options["aspect"] as? Number)?.toFloat() ?: 1.3f

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF0E1116)),
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }

            val w = maxWidth
            val h = maxHeight
            val iconBox = 40.dp

            elements.forEach { el ->
                val leftPct = (el["left"] as? Number)?.toFloat() ?: 50f
                val topPct = (el["top"] as? Number)?.toFloat() ?: 50f
                val entityId = el["entity_id"] as? String
                val service = el["service"] as? String
                val isPower = (el["icon"] as? String) == "power"

                val on = entityId?.let { ctx.entities[it]?.isOn == true } ?: false

                // Position using cheap Modifier.offset instead of expensive layout padding
                val x = w * (leftPct / 100f) - iconBox / 2
                val y = h * (topPct / 100f) - iconBox / 2

                val bg = if (on) Color(0x66FFC24B) else Color(0x33000000)
                val tint = if (on) Color(0xFFFFD37A) else Color(0xFFE8ECF2)
                val icon = when {
                    isPower -> Icons.Filled.PowerSettingsNew
                    on -> Icons.Filled.Lightbulb
                    else -> Icons.Outlined.Lightbulb
                }

                Icon(
                    imageVector = icon,
                    contentDescription = entityId,
                    tint = tint,
                    modifier = Modifier
                        .offset(x = x.coerceAtLeast(0.dp), y = y.coerceAtLeast(0.dp))
                        .size(iconBox)
                        .clip(CircleShape)
                        .background(bg)
                        .pointerInput(entityId, service) {
                            detectTapGestures(
                                // Long-press a light icon → colour/brightness popup
                                // (same dialog as the bubble_light card).
                                onLongPress = if (entityId?.startsWith("light.") == true) {
                                    { _ -> detailEntity = entityId }
                                } else null,
                                onTap = {
                                    when {
                                        entityId != null -> ctx.client.toggle(entityId)
                                        service != null -> {
                                            val domain = service.substringBefore('.')
                                            val svc = service.substringAfter('.')
                                            val targets = (el["targets"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                                            if (targets.isEmpty()) {
                                                ctx.client.callService(ServiceCall(domain, svc))
                                            } else {
                                                targets.forEach { t ->
                                                    ctx.client.callService(ServiceCall(domain, svc, entityId = t))
                                                }
                                            }
                                        }
                                    }
                                },
                            )
                        }
                        .padding(6.dp),
                )
            }

            // Radar overlays: plot mmWave target dots (e.g. LD2450) on the plan.
            // `radars` is a list (one block per sensor); `radar` is the older
            // single-sensor form, still accepted.
            val radarList = (config.options["radars"] as? List<Map<String, Any?>>)
                ?: listOfNotNull(config.options["radar"] as? Map<String, Any?>)
            radarList.forEach { RadarDots(it, ctx, w, h) }

            // Vacuum overlay: a robot-vacuum icon at its current room (or dock).
            val vacuumOpts = config.options["vacuum"] as? Map<String, Any?>
            if (vacuumOpts != null) {
                VacuumOverlay(vacuumOpts, ctx, w, h) { showVacuumDialog = true }
            }

            detailEntity?.let { id ->
                LightDetailDialog(
                    entityId = id,
                    e = ctx.entities[id],
                    client = ctx.client,
                    onClose = { detailEntity = null },
                )
            }

            if (showVacuumDialog && vacuumOpts != null) {
                Dialog(onDismissRequest = { showVacuumDialog = false }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF1B343D))
                            .padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        VacuumPanelContent(vacuumOpts, ctx)
                    }
                }
            }
        }
    }

    @Suppress("UNCHECKED_CAST")
    @Composable
    private fun VacuumOverlay(
        opts: Map<String, Any?>,
        ctx: CardContext,
        w: Dp,
        h: Dp,
        onOpen: () -> Unit,
    ) {
        val entityId = opts["entity_id"] as? String ?: return
        val state = ctx.entities[entityId]?.state ?: "unknown"
        val roomEntity = opts["room_entity"] as? String
        val currentRoom = roomEntity?.let { ctx.entities[it]?.state }
        val roomPositions = opts["room_positions"] as? Map<String, List<Number>>
        val dockPosition = (opts["dock_position"] as? List<*>)?.filterIsInstance<Number>()

        val docked = state == "docked" || state == "charging"
        val active = state == "cleaning" || state == "returning"

        val pos: Pair<Float, Float> = when {
            docked && dockPosition?.size == 2 -> dockPosition[0].toFloat() to dockPosition[1].toFloat()
            currentRoom != null && roomPositions?.get(currentRoom) != null ->
                roomPositions.getValue(currentRoom).let { it[0].toFloat() to it[1].toFloat() }
            dockPosition?.size == 2 -> dockPosition[0].toFloat() to dockPosition[1].toFloat()
            else -> 50f to 50f
        }
        val (leftPct, topPct) = pos
        val vac = 30.dp
        val iconW = vac
        val iconH = if (docked) vac * 1.35f else vac
        val x = (w * (leftPct / 100f) - iconW / 2).coerceAtLeast(0.dp)
        val y = (h * (topPct / 100f) - iconH / 2).coerceAtLeast(0.dp)

        Box(
            modifier = Modifier
                .offset(x = x, y = y)
                .clickable(onClick = onOpen),
        ) {
            RoboVacIcon(vac = vac, docked = docked, moving = active)
        }
    }

    @Composable
    private fun RoboVacIcon(vac: Dp, docked: Boolean, moving: Boolean) {
        val body = Color(0xFF3A4A52)
        val bump = Color(0xFF7B8C96)

        if (docked) {
            Box(modifier = Modifier.size(width = vac, height = vac * 1.35f)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(vac * 0.5f)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFF1E262C)),
                )
                VacBody(vac * 0.92f, body, bump, Modifier.align(Alignment.BottomCenter))
            }
        } else {
            val angle = if (moving) {
                val t = rememberInfiniteTransition(label = "vacrock")
                t.animateFloat(
                    initialValue = -10f,
                    targetValue = 10f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(650, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "angle",
                ).value
            } else {
                0f
            }
            VacBody(vac, body, bump, Modifier.rotate(angle))
        }
    }

    @Composable
    private fun VacBody(d: Dp, body: Color, bump: Color, modifier: Modifier = Modifier) {
        Box(modifier = modifier.size(d).clip(CircleShape).background(body)) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = d * 0.12f)
                    .size(d * 0.24f)
                    .clip(CircleShape)
                    .background(bump),
            )
        }
    }

    @Composable
    private fun RadarDots(
        radar: Map<String, Any?>,
        ctx: CardContext,
        w: Dp,
        h: Dp,
    ) {
        val prefix = radar["prefix"] as? String ?: return
        val nTargets = (radar["targets"] as? Number)?.toInt() ?: 3
        val originL = (radar["origin_left"] as? Number)?.toFloat() ?: 50f
        val originT = (radar["origin_top"] as? Number)?.toFloat() ?: 10f
        val scaleX = (radar["scale_x"] as? Number)?.toFloat() ?: 8f
        val scaleXRight = (radar["scale_x_right"] as? Number)?.toFloat() ?: scaleX
        val scaleY = (radar["scale_y"] as? Number)?.toFloat() ?: 8f
        val topOffsetLeft = (radar["top_offset_left"] as? Number)?.toFloat() ?: 0f
        val rot = ((radar["rotation"] as? Number)?.toFloat() ?: 0f) * (PI.toFloat() / 180f)
        val flipX = radar["flip_x"] as? Boolean ?: false
        val flipY = radar["flip_y"] as? Boolean ?: false
        val blend = parseBlend(radar["blend"] as? String)
        // Sensors differ in reported units: the Apollo publishes metres, the
        // bare ESPHome LD2450 boards publish millimetres. Normalise to metres
        // before the affine transform so one set of scale values means the
        // same thing everywhere. "unit": "mm" | "m", or an explicit divisor.
        val divisor = (radar["units_per_metre"] as? Number)?.toFloat()
            ?: if ((radar["unit"] as? String)?.lowercase() == "mm") 1000f else 1f
        // Per-sensor dot colours so you can tell which radar a dot came from.
        val fill = parseArgb(radar["color"] as? String) ?: Color(0xD9155E6E)
        val accent = parseArgb(radar["accent_color"] as? String) ?: Color(0xFF33CBDA)
        val label = radar["label"] as? String ?: ""

        // Loop handles layout of children, but child states are read ONLY inside child scopes!
        for (i in 1..nTargets) {
            RadarDot(
                id = i,
                prefix = prefix,
                ctx = ctx,
                w = w,
                h = h,
                originL = originL,
                originT = originT,
                scaleX = scaleX,
                scaleXRight = scaleXRight,
                scaleY = scaleY,
                topOffsetLeft = topOffsetLeft,
                rot = rot,
                flipX = flipX,
                flipY = flipY,
                blend = blend,
                divisor = divisor,
                fill = fill,
                accent = accent,
                label = label,
            )
        }
    }

    /** Parse "#AARRGGBB" / "#RRGGBB" to a Color; null if absent or malformed. */
    private fun parseArgb(s: String?): Color? {
        val hex = s?.removePrefix("#") ?: return null
        val v = hex.toLongOrNull(16) ?: return null
        return if (hex.length <= 6) Color(v or 0xFF000000L) else Color(v)
    }

    /**
     * Isolated Radar Dot. Splitting this out prevents coordinate updates of target #1
     * from forcing target #2, #3, or the rest of the floorplan card to recompose.
     */
    @Composable
    private fun RadarDot(
        id: Int,
        prefix: String,
        ctx: CardContext,
        w: Dp,
        h: Dp,
        originL: Float,
        originT: Float,
        scaleX: Float,
        scaleXRight: Float,
        scaleY: Float,
        topOffsetLeft: Float,
        rot: Float,
        flipX: Boolean,
        flipY: Boolean,
        blend: BlendMode?,
        divisor: Float,
        fill: Color,
        accent: Color,
        label: String,
    ) {
        // A target with no lock reports "unknown" — toFloatOrNull drops it, so
        // the dot simply isn't drawn.
        val rawX = ctx.entities["${prefix}_${id}_x"]?.state?.toFloatOrNull() ?: return
        val rawY = ctx.entities["${prefix}_${id}_y"]?.state?.toFloatOrNull() ?: return
        val xm = rawX / divisor
        val ym = rawY / divisor

        val cosR = cos(rot)
        val sinR = sin(rot)
        var rx = xm * cosR - ym * sinR
        var ry = xm * sinR + ym * cosR
        if (flipX) rx = -rx
        if (flipY) ry = -ry

        val sx = if (rx >= 0f) scaleXRight else scaleX
        val extraTop = if (rx < 0f) topOffsetLeft else 0f
        val leftPct = (originL + rx * sx).coerceIn(0f, 100f)
        val topPct = (originT + ry * scaleY + extraTop).coerceIn(0f, 100f)

        val dot = 26.dp
        val dx = (w * (leftPct / 100f) - dot / 2).coerceAtLeast(0.dp)
        val dy = (h * (topPct / 100f) - dot / 2).coerceAtLeast(0.dp)

        Box(
            modifier = Modifier
                .offset(x = dx, y = dy)
                .size(dot)
                .drawBehind {
                    drawCircle(color = fill)
                    blend?.let { drawCircle(color = accent, blendMode = it) }
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("$label$id", color = Color(0xFFDCF1F4), fontSize = 13.sp, fontWeight = FontWeight.Bold)
        }
    }

    private fun parseBlend(name: String?): BlendMode? = when (name?.lowercase()) {
        "none" -> null
        "multiply" -> BlendMode.Multiply
        "screen" -> BlendMode.Screen
        "softlight" -> BlendMode.Softlight
        "hardlight" -> BlendMode.Hardlight
        "difference" -> BlendMode.Difference
        "overlay", null -> BlendMode.Overlay
        else -> BlendMode.Overlay
    }
}
