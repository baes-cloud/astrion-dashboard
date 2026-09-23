package com.custom.astrion.cards.impl

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionSheet
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.LocalOverlay
import com.custom.astrion.ui.OverlayController
import com.custom.astrion.ui.PendingSpinner
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.liveOrDim
import com.custom.astrion.ui.parseHexColor
import com.custom.astrion.ui.rememberAction
import com.custom.astrion.ui.rememberOptimistic
import com.custom.astrion.ui.rememberSampledBitmap
import com.custom.astrion.ui.tap
import com.custom.astrion.ui.tapAndHold
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Floorplan — the picture-elements equivalent and the heart of Main: a plan
 * of the flat with every light where it physically is, live mmWave presence
 * dots, and the robot vacuum in its current room.
 *
 * - Each icon is its own composable that reads only its own entity, so a
 *   radar dot moving no longer recomposes the plan and its 15 lights.
 * - Tap a light: it flips optimistically (amber the instant you tap), spins
 *   if HA is slow, outlines red if refused. Long-press a light: the colour /
 *   brightness sheet.
 * - Unavailable lights show a lilac cloud-off glyph and are NOT tappable;
 *   with the socket down every icon is dimmed and inert.
 * - The plan photo is darkened with a Multiply tint (`dim`, default on) so
 *   it isn't the brightest thing in a dark room; icons draw above it at full
 *   strength.
 *
 * Config: image, aspect, max_crop (0.12), fill / pin:"fill", flush,
 * dim (default true), elements [{entity_id,left,top} | {service,targets,
 * icon:"power",left,top}], radars (or legacy radar), vacuum {…}.
 */
class PictureElementsCard : CardRenderer {
    override val type = "picture_elements"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val imagePath = config.string("image") ?: "/sdcard/astrion/floorplan.png"
        val elements = (config.options["elements"] as? List<Map<String, Any?>>) ?: emptyList()
        val overlay = LocalOverlay.current

        // Off-thread, downsampled, cached (no pop-in on return visits).
        val bitmap by rememberSampledBitmap(imagePath, targetPx = 720)

        val aspect = bitmap?.let { it.width.toFloat() / it.height.toFloat() }
            ?: (config.options["aspect"] as? Number)?.toFloat() ?: 1.3f
        val fill = config.bool("fill", false) || config.options["pin"] == "fill"
        val flush = config.bool("flush")
        val dim = config.bool("dim", true)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fill) Modifier.fillMaxHeight() else Modifier.aspectRatio(aspect))
                .then(if (flush) Modifier else Modifier.clip(RoundedCornerShape(Radius.card)))
                .then(if (flush) Modifier else Modifier.background(AstrionTheme.planBg)),
            contentAlignment = Alignment.Center,
        ) {
            // Where the plan is drawn: overlays are placed as percentages of
            // the IMAGE, not the box, so they never drift off their rooms.
            // Cover the box but crop at most `max_crop`; past that, letterbox.
            val boxW = maxWidth
            val boxH = if (maxHeight.value.isFinite()) maxHeight else maxWidth / aspect
            val maxCrop = ((config.options["max_crop"] as? Number)?.toFloat() ?: 0.12f).coerceIn(0f, 0.5f)
            val coverW = if (boxW / boxH > aspect) boxW else boxH * aspect
            val w = minOf(coverW, if (boxW / boxH > aspect) boxH * aspect * (1 + maxCrop) else boxW * (1 + maxCrop))
            val h = w / aspect

            val vacuumOpts = config.options["vacuum"] as? Map<String, Any?>

            Box(Modifier.requiredSize(w, h)) {
                bitmap?.let { bmp ->
                    Image(
                        bitmap = bmp,
                        contentDescription = null,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.FillBounds,
                        colorFilter = if (dim) ColorFilter.tint(AstrionTheme.planDim, BlendMode.Multiply) else null,
                    )
                }

                elements.forEach { el -> PlanIcon(el, ctx, overlay, w, h) }

                val radarList = (config.options["radars"] as? List<Map<String, Any?>>)
                    ?: listOfNotNull(config.options["radar"] as? Map<String, Any?>)
                radarList.forEach { RadarDots(it, ctx, w, h) }

                if (vacuumOpts != null) {
                    VacuumOverlay(vacuumOpts, ctx, w, h) {
                        overlay.show {
                            AstrionSheet(onDismiss = { overlay.dismiss() }, title = null) {
                                VacuumPanelContent(vacuumOpts, ctx)
                            }
                        }
                    }
                }
            }
        }
    }

    /** One element on the plan. Reads its own entity only. */
    @Composable
    private fun PlanIcon(el: Map<String, Any?>, ctx: CardContext, overlay: OverlayController, w: Dp, h: Dp) {
        val leftPct = (el["left"] as? Number)?.toFloat() ?: 50f
        val topPct = (el["top"] as? Number)?.toFloat() ?: 50f
        val entityId = el["entity_id"] as? String
        val service = el["service"] as? String
        val isPower = (el["icon"] as? String) == "power"

        val entity = entityId?.let { ctx.entity(it) }
        val unavailable = entityId != null && (entity == null || entity.isUnavailable)
        val actualOn = entity?.isOn == true
        val opt = rememberOptimistic(actualOn)
        val on = opt.show(actualOn)
        val action = rememberAction(ctx)
        val live = ctx.connected && !unavailable

        val iconBox = 40.dp
        val x = (w * (leftPct / 100f) - iconBox / 2).coerceAtLeast(0.dp)
        val y = (h * (topPct / 100f) - iconBox / 2).coerceAtLeast(0.dp)

        val well = when {
            unavailable -> AstrionTheme.planUnavailableWell
            on -> AstrionTheme.planOnWell
            else -> AstrionTheme.planOffWell
        }
        val tint = when {
            unavailable -> AstrionTheme.unavailable
            on -> AstrionTheme.planIconOn
            else -> AstrionTheme.planIconOff
        }
        val icon = when {
            isPower -> Icons.Filled.PowerSettingsNew
            unavailable -> Icons.Filled.CloudOff
            on -> Icons.Filled.Lightbulb
            else -> Icons.Outlined.Lightbulb
        }
        val name = entity?.friendlyName ?: entityId?.substringAfter('.') ?: service ?: "Button"
        val isLight = entityId?.startsWith("light.") == true

        fun fire() {
            when {
                entityId != null -> {
                    opt.set(!actualOn)
                    val domain = entityId.substringBefore('.')
                    action.run(ServiceCall(domain, "toggle", entityId), onFail = { opt.clear() })
                }
                service != null -> {
                    val domain = service.substringBefore('.')
                    val svc = service.substringAfter('.')
                    val targets = (el["targets"] as? List<*>)?.filterIsInstance<String>().orEmpty()
                    if (targets.isEmpty()) {
                        action.run(ServiceCall(domain, svc))
                    } else {
                        action.run(*targets.map { ServiceCall(domain, svc, entityId = it) }.toTypedArray())
                    }
                }
            }
        }

        val shape = CircleShape
        val base = Modifier
            .offset(x = x, y = y)
            .size(iconBox)
            // Socket down: every icon visibly inert, not just silent.
            .liveOrDim(ctx.connected)
            .clip(shape)
            .background(well)
            .then(if (action.failed) Modifier.border(2.dp, AstrionTheme.danger, shape) else Modifier)
        val interactive = if (isLight) {
            base.tapAndHold(
                enabled = live,
                onClick = ::fire,
                onLongClick = {
                    val id = entityId ?: return@tapAndHold
                    overlay.show { LightDetailSheet(id, ctx, onClose = { overlay.dismiss() }) }
                },
            )
        } else {
            base.tap(enabled = live || (entityId == null && ctx.connected), onClick = ::fire)
        }
        Box(
            modifier = interactive.semantics {
                contentDescription = when {
                    unavailable -> "$name, unavailable"
                    isPower -> name
                    else -> "$name, ${if (on) "on" else "off"}"
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            if (action.busy) {
                PendingSpinner(size = 20.dp, color = tint)
            } else {
                Icon(
                    icon, contentDescription = null, tint = tint,
                    modifier = Modifier.size(24.dp),
                )
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
        val state = ctx.entity(entityId)?.state ?: "unknown"
        val roomEntity = opts["room_entity"] as? String
        val currentRoom = roomEntity?.let { ctx.entity(it)?.state }
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
        // 44dp touch target around the 30dp robot (it was a 30dp target).
        val target = 44.dp
        val x = (w * (leftPct / 100f) - target / 2).coerceAtLeast(0.dp)
        val y = (h * (topPct / 100f) - target / 2).coerceAtLeast(0.dp)

        Box(
            modifier = Modifier
                .offset(x = x, y = y)
                .size(target)
                .clip(CircleShape)
                .tap(onClick = onOpen)
                .semantics { contentDescription = "Robot vacuum, ${state.replace('_', ' ')}. Open controls" },
            contentAlignment = Alignment.Center,
        ) {
            RoboVacIcon(vac = vac, docked = docked, moving = active)
        }
    }

    @Composable
    private fun RoboVacIcon(vac: Dp, docked: Boolean, moving: Boolean) {
        if (docked) {
            Box(modifier = Modifier.size(width = vac, height = vac * 1.35f)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(vac * 0.5f)
                        .clip(RoundedCornerShape(Radius.small))
                        .background(AstrionTheme.vacDock),
                )
                VacBody(vac * 0.92f, Modifier.align(Alignment.BottomCenter))
            }
        } else if (moving) {
            // The only continuous animation on Main, and only while the
            // robot is actually out cleaning.
            val t = rememberInfiniteTransition(label = "vacrock")
            val angle by t.animateFloat(
                initialValue = -10f,
                targetValue = 10f,
                animationSpec = infiniteRepeatable(
                    animation = tween(650, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse,
                ),
                label = "angle",
            )
            VacBody(vac, Modifier.rotate(angle))
        } else {
            VacBody(vac)
        }
    }

    @Composable
    private fun VacBody(d: Dp, modifier: Modifier = Modifier) {
        Box(modifier = modifier.size(d).clip(CircleShape).background(AstrionTheme.vacBody)) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = d * 0.12f)
                    .size(d * 0.24f)
                    .clip(CircleShape)
                    .background(AstrionTheme.vacBump),
            )
        }
    }

    @Composable
    private fun RadarDots(radar: Map<String, Any?>, ctx: CardContext, w: Dp, h: Dp) {
        val prefix = radar["prefix"] as? String ?: return
        val nTargets = (radar["targets"] as? Number)?.toInt() ?: 3
        val spec = RadarSpec(
            originL = (radar["origin_left"] as? Number)?.toFloat() ?: 50f,
            originT = (radar["origin_top"] as? Number)?.toFloat() ?: 10f,
            scaleX = (radar["scale_x"] as? Number)?.toFloat() ?: 8f,
            scaleXRight = (radar["scale_x_right"] as? Number)?.toFloat()
                ?: (radar["scale_x"] as? Number)?.toFloat() ?: 8f,
            scaleY = (radar["scale_y"] as? Number)?.toFloat() ?: 8f,
            topOffsetLeft = (radar["top_offset_left"] as? Number)?.toFloat() ?: 0f,
            rot = ((radar["rotation"] as? Number)?.toFloat() ?: 0f) * (PI.toFloat() / 180f),
            flipX = radar["flip_x"] as? Boolean ?: false,
            flipY = radar["flip_y"] as? Boolean ?: false,
            blend = parseBlend(radar["blend"] as? String),
            // Apollo publishes metres, bare ESPHome LD2450 boards millimetres.
            divisor = (radar["units_per_metre"] as? Number)?.toFloat()
                ?: if ((radar["unit"] as? String)?.lowercase() == "mm") 1000f else 1f,
            fill = parseHexColor(radar["color"] as? String) ?: AstrionTheme.radarFill,
            accent = parseHexColor(radar["accent_color"] as? String) ?: AstrionTheme.radarAccent,
            label = radar["label"] as? String ?: "",
        )
        for (i in 1..nTargets) RadarDot(i, prefix, ctx, w, h, spec)
    }

    /** Everything about one radar block that its dots share. */
    private class RadarSpec(
        val originL: Float,
        val originT: Float,
        val scaleX: Float,
        val scaleXRight: Float,
        val scaleY: Float,
        val topOffsetLeft: Float,
        val rot: Float,
        val flipX: Boolean,
        val flipY: Boolean,
        val blend: BlendMode?,
        val divisor: Float,
        val fill: Color,
        val accent: Color,
        val label: String,
    )

    /** One target: reads only its own two coordinate entities. */
    @Composable
    private fun RadarDot(id: Int, prefix: String, ctx: CardContext, w: Dp, h: Dp, s: RadarSpec) {
        // A target with no lock reports "unknown" — not drawn.
        val rawX = ctx.entity("${prefix}_${id}_x")?.state?.toFloatOrNull() ?: return
        val rawY = ctx.entity("${prefix}_${id}_y")?.state?.toFloatOrNull() ?: return
        val xm = rawX / s.divisor
        val ym = rawY / s.divisor

        val cosR = cos(s.rot)
        val sinR = sin(s.rot)
        var rx = xm * cosR - ym * sinR
        var ry = xm * sinR + ym * cosR
        if (s.flipX) rx = -rx
        if (s.flipY) ry = -ry

        val sx = if (rx >= 0f) s.scaleXRight else s.scaleX
        val extraTop = if (rx < 0f) s.topOffsetLeft else 0f
        val leftPct = (s.originL + rx * sx).coerceIn(0f, 100f)
        val topPct = (s.originT + ry * s.scaleY + extraTop).coerceIn(0f, 100f)

        val dot = 26.dp
        val dx = (w * (leftPct / 100f) - dot / 2).coerceAtLeast(0.dp)
        val dy = (h * (topPct / 100f) - dot / 2).coerceAtLeast(0.dp)
        val fill = s.fill
        val accent = s.accent
        val blend = s.blend

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
            Text("${s.label}$id", style = AstrionType.label, color = AstrionTheme.radarLabel)
        }
    }

    private fun parseBlend(name: String?): BlendMode? = when (name?.lowercase()) {
        "none" -> null
        "multiply" -> BlendMode.Multiply
        "screen" -> BlendMode.Screen
        "softlight" -> BlendMode.Softlight
        "hardlight" -> BlendMode.Hardlight
        "difference" -> BlendMode.Difference
        else -> BlendMode.Overlay
    }
}
