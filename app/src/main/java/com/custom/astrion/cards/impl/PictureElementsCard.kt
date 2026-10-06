package com.custom.astrion.cards.impl

import com.custom.astrion.ui.parseHexColor
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.key
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.ui.InWindowDialog
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.rememberSampledBitmap
import com.custom.astrion.ui.AstrionTheme
import kotlin.math.roundToInt
import com.custom.astrion.ui.LocalDashboardShowing
import com.custom.astrion.ui.LocalPageVisible
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import com.custom.astrion.ui.tap

/**
 * Floorplan card — the picture-elements equivalent. Draws a background image
 * (loaded safely off-thread) with tappable icons positioned by percentage,
 * each toggling a light and lighting up (amber) when that entity is on.
 * Long-pressing a light icon opens the same colour/brightness detail popup
 * as the bubble_light card on the Lights page.
 */
/** How long a floorplan icon shows its "sent" ring after a tap. */
private const val SENT_RING_MS = 1200L

class PictureElementsCard : CardRenderer {
    override val type = "picture_elements"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val imagePath = config.string("image") ?: "/sdcard/astrion/floorplan.png"
        val elements = (config.options["elements"] as? List<Map<String, Any?>>) ?: emptyList()
        var showVacuumDialog by remember { mutableStateOf(false) }
        var detailEntity by remember { mutableStateOf<String?>(null) }
        val haptics = LocalHapticFeedback.current

        // Decoded off-thread AND downsampled. The floorplan on this device is
        // 1089 x 1047, i.e. 4.56 MB resident as ARGB_8888, held for the life of
        // the card to fill about 460 px of screen.
        val bitmap by rememberSampledBitmap(imagePath, targetPx = 720)

        val aspect = bitmap?.let { it.width.toFloat() / it.height.toFloat() }
            ?: (config.options["aspect"] as? Number)?.toFloat() ?: 1.3f

        // With "pin": "fill" the card is handed a weighted slot and should take
        // all of it, so the plan grows to close out the page rather than
        // sizing itself from its own aspect ratio and leaving a gap. Element
        // and radar positions are percentages of this box, so they follow.
        val fill = config.bool("fill", false) || config.options["pin"] == "fill"

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fill) Modifier.fillMaxHeight() else Modifier.aspectRatio(aspect))
                // Inside a `stack` the container clips the outer corners; a
                // second rounding here would notch the joins.
                .then(if (config.bool("flush")) Modifier else Modifier.clip(RoundedCornerShape(18.dp)))
                // Flush inside a stack, any letterbox margin shows the card's
                // own fill, so it reads as spacing rather than black bars.
                .then(if (config.bool("flush")) Modifier else Modifier.background(Color(0xFF12181E))),
            contentAlignment = Alignment.Center,
        ) {
            // Where the plan is actually drawn — every icon, radar dot and the
            // vacuum below is laid out inside this rectangle, so their
            // percentages are percentages of the IMAGE.
            //
            // This used to draw with ContentScale.Crop into whatever box the
            // page handed it while placing the overlays as percentages of the
            // BOX. Whenever the two shapes differed the picture was zoomed and
            // cropped but the icons weren't, so they drifted off their rooms;
            // and this plan runs edge to edge, so the crop cut off real rooms.
            //
            // Now: scale up to cover the box, but crop at most `max_crop` of the
            // plan's width or height (default 12%, i.e. 6% a side — outer walls
            // and window frames, not rooms). Past that it letterboxes instead.
            val boxW = maxWidth
            // A bottom-pinned overlay bar reserves its strip: the plan is laid
            // out in the space above it and only its overflow runs underneath.
            val bottomReserve = if ((config.options["overlay"] as? Map<*, *>)?.get("anchor") == "bottom") 48.dp else 0.dp
            val boxH = (if (maxHeight.value.isFinite()) maxHeight else maxWidth / aspect) - bottomReserve
            val maxCrop = ((config.options["max_crop"] as? Number)?.toFloat() ?: 0.12f).coerceIn(0f, 0.5f)
            val coverW = if (boxW / boxH > aspect) boxW else boxH * aspect
            val coverWidth = minOf(coverW, if (boxW / boxH > aspect) boxH * aspect * (1 + maxCrop) else boxW * (1 + maxCrop))

            // "stretch": fill the slot exactly instead of cover-cropping — the
            // plan is squashed/stretched a little, nothing is cut off, and the
            // icons (percentages of the image) stay on their rooms. With a
            // bottom-pinned overlay the plan runs down behind the bar only as
            // far as keeps the lowest icon clear of it.
            val stretch = config.bool("stretch", false)
            val fullH = boxH + bottomReserve
            val lowestPct = elements.maxOfOrNull { (it["top"] as? Number)?.toFloat() ?: 0f }?.coerceAtLeast(50f) ?: 90f
            val w = if (stretch) boxW else coverWidth
            val h = when {
                !stretch -> w / aspect
                bottomReserve > 0.dp -> minOf(fullH, (fullH - bottomReserve - 24.dp) / (lowestPct / 100f))
                else -> fullH
            }
            // Where the image box sits vertically (it is centred in the card by
            // default): stretched plans hang from the top edge.
            val imageShift = if (stretch) (h - fullH) / 2 else -bottomReserve / 2

            // Read out here: the vacuum dialog below, outside the image box,
            // needs it too.
            val vacuumOpts = config.options["vacuum"] as? Map<String, Any?>

            // requiredSize, not size: when covering, the rectangle is LARGER
            // than the box and must overflow it (centred, clipped by the card)
            // rather than be squeezed back into it.
            Box(Modifier.offset(y = imageShift).requiredSize(w, h)) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    // The box already has the image's proportions.
                    contentScale = ContentScale.FillBounds,
                )
            }

            // Lit rooms: each light's colour and brightness pooled in its own
            // room, with unlit rooms shaded after sunset. Under everything
            // else, so icons and dots stay crisp on top. See LitRooms.kt.
            val litRooms = config.options["lit_rooms"] as? Map<String, Any?>
            if (litRooms != null && bitmap != null) LitRoomsLayer(litRooms, elements, ctx)

            // Optional embedded card floated over the plan (e.g. the
            // now-playing strip in the empty band across the bedroom /
            // bathroom / office), so the plan can run to the bottom of the
            // page. Drawn before the icons and radar dots, which stay on top.
            //   "overlay": { "top": 77, "left": 4, "right": 4,
            //                "card": { "type": "now_playing", "options": { … } } }
            val overlayOpts = config.options["overlay"] as? Map<String, Any?>
            if (overlayOpts != null && overlayOpts["anchor"] != "bottom") {
                val topPct = (overlayOpts["top"] as? Number)?.toFloat() ?: 77f
                val leftPct = (overlayOpts["left"] as? Number)?.toFloat() ?: 4f
                val rightPct = (overlayOpts["right"] as? Number)?.toFloat() ?: 4f
                OverlayBar(
                    overlayOpts, ctx,
                    Modifier
                        .offset(x = w * (leftPct / 100f), y = h * (topPct / 100f) - 20.dp)
                        .width(w * (1f - (leftPct + rightPct) / 100f)),
                )
            }

            val iconBox = 40.dp

            elements.forEach { el ->
                val leftPct = (el["left"] as? Number)?.toFloat() ?: 50f
                val topPct = (el["top"] as? Number)?.toFloat() ?: 50f
                val entityId = el["entity_id"] as? String
                val service = el["service"] as? String
                val isPower = (el["icon"] as? String) == "power"

                val entity = entityId?.let { ctx.entities[it] }
                val on = entity?.isOn == true
                // A Zigbee light that dropped off the mesh used to render as a
                // dark icon — identical to one deliberately turned off — so the
                // floorplan actively misreported the state of the house.
                val elUnavailable = entityId != null && (entity == null || entity.isUnavailable)

                // Position using cheap Modifier.offset instead of expensive layout padding
                val x = w * (leftPct / 100f) - iconBox / 2
                val y = h * (topPct / 100f) - iconBox / 2

                // With lit rooms the bulb takes the light's own colour, so the
                // icon matches the glow around it.
                val glowColor = if (on && litRooms != null) entity?.let { lightColor(it) } else null
                val bg = when {
                    elUnavailable -> Color(0x44803030)
                    glowColor != null -> glowColor.copy(alpha = 0.42f)
                    on -> Color(0x66FFC24B)
                    else -> Color(0x33000000)
                }
                val tint = when {
                    elUnavailable -> Color(0xFFC98A8A)
                    glowColor != null -> lerp(glowColor, Color.White, 0.65f)
                    on -> Color(0xFFFFD37A)
                    else -> Color(0xFFEEF2EF)
                }
                val icon = when {
                    isPower -> Icons.Filled.PowerSettingsNew
                    elUnavailable -> Icons.Filled.HelpOutline
                    on -> Icons.Filled.Lightbulb
                    else -> Icons.Outlined.Lightbulb
                }

                // The most-tapped targets on Main, and they gave no feedback
                // at all until HA echoed the new state back (often hundreds
                // of ms). Now: a haptic on tap and hold, and a ring for a
                // moment to show the tap was sent.
                var sent by remember(entityId, service) { mutableStateOf(false) }
                LaunchedEffect(sent) {
                    if (sent) {
                        delay(SENT_RING_MS)
                        sent = false
                    }
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
                        .then(
                            if (sent) Modifier.border(2.dp, AstrionTheme.on.copy(alpha = 0.85f), CircleShape)
                            else Modifier
                        )
                        .pointerInput(entityId, service) {
                            detectTapGestures(
                                // Long-press a light icon → colour/brightness popup
                                // (same dialog as the bubble_light card).
                                onLongPress = if (entityId?.startsWith("light.") == true) {
                                    { _ ->
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        detailEntity = entityId
                                    }
                                } else null,
                                onTap = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    sent = true
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

            // Radar Map Manager overlay: RMM's fused targets, i.e. exactly what
            // HA's radar-map-card shows — exclude zones applied, sensors merged,
            // hibernating tracks hidden. Use this instead of `radars`.
            (config.options["rmm"] as? Map<String, Any?>)?.let { RmmTargets(it, ctx, w, h) }

            // Vacuum overlay: a robot-vacuum icon at its current room (or dock).
            if (vacuumOpts != null) {
                VacuumOverlay(vacuumOpts, ctx, w, h) { showVacuumDialog = true }
            }
            } // image rectangle

            // Bottom-anchored overlay: pinned to the card's bottom edge (over
            // the strip of plan the cover-crop trims anyway), not to a plan row.
            val bottomOverlay = config.options["overlay"] as? Map<String, Any?>
            if (bottomOverlay != null && bottomOverlay["anchor"] == "bottom") {
                OverlayBar(
                    bottomOverlay, ctx,
                    Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(8.dp),
                )
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
                InWindowDialog(onDismissRequest = { showVacuumDialog = false }) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(20.dp))
                            .background(Color(0xFF243140))
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
                .tap(onClick = onOpen),
        ) {
            RoboVacIcon(vac = vac, docked = docked, moving = active)
        }
    }

    /**
     * Another card floated over the plan in a frosted strip. Config:
     *   "overlay": { "anchor": "bottom" }            // pinned to the card's bottom edge
     *   "overlay": { "top": 77, "left": 4, "right": 4 } // or at a % row of the plan
     *   … plus "card": { "type": "now_playing", "options": { … } }
     */
    @Suppress("UNCHECKED_CAST")
    @Composable
    private fun OverlayBar(ov: Map<String, Any?>, ctx: CardContext, modifier: Modifier) {
        val card = ov["card"] as? Map<String, Any?> ?: return
        val type = card["type"] as? String ?: return
        val renderer = com.custom.astrion.cards.CardRegistry.get(type) ?: return
        Box(
            modifier = modifier
                .height(40.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(Color(0xD9141C24))
                .border(1.dp, Color(0x22FFFFFF), RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            val opts = ((card["options"] as? Map<String, Any?>) ?: emptyMap()) + ("flush" to true)
            renderer.Render(com.custom.astrion.cards.CardConfig(type, opts), ctx)
        }
    }

    @Composable
    private fun RoboVacIcon(vac: Dp, docked: Boolean, moving: Boolean) {
        val body = Color(0xFF3A4B55)
        val bump = Color(0xFF7B8C96)

        if (docked) {
            Box(modifier = Modifier.size(width = vac, height = vac * 1.35f)) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(vac * 0.5f)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFF1E2830)),
                )
                VacBody(vac * 0.92f, body, bump, Modifier.align(Alignment.BottomCenter))
            }
        } else {
            // Rocks only while it is actually seen. The page stays composed
            // when hidden and under the screensaver, and an infinite
            // transition keeps asking for frames whether or not anything is
            // drawn.
            val live = LocalPageVisible.current && LocalDashboardShowing.current
            if (moving && live) {
                val t = rememberInfiniteTransition(label = "vacrock")
                val angle = t.animateFloat(
                    initialValue = -10f,
                    targetValue = 10f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(650, easing = FastOutSlowInEasing),
                        repeatMode = RepeatMode.Reverse,
                    ),
                    label = "angle",
                )
                // Read in the draw phase, not in composition: each frame
                // re-draws the layer instead of recomposing the icon.
                VacBody(vac, body, bump, Modifier.graphicsLayer { rotationZ = angle.value })
            } else {
                VacBody(vac, body, bump)
            }
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
        val fill = parseHexColor(radar["color"] as? String) ?: Color(0xD94F726D)
        val accent = parseHexColor(radar["accent_color"] as? String) ?: Color(0xFF8CBDB5)
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

    private data class RmmTarget(val id: String, val x: Float, val y: Float)

    /**
     * Fused targets from Radar Map Manager's `rmm/stream` websocket command.
     * RMM's map coordinates are 0–100 percentages of the floorplan image HA's
     * card uses; the remote's floorplan may be a crop of that image, so:
     *   "rmm": { "map_group": "default",
     *            "source_size": [1179, 1179],          // HA card image, px
     *            "crop": [16, 12, 1089, 1047],          // left, top, width, height of
     *                                                   // the remote image within it, px
     *            "show_hibernating": false,
     *            "color": "#D92CAA9C", "accent_color": "#FF8CE0D4" }
     * Without `source_size`/`crop` the two images are taken to be the same.
     */
    @Suppress("UNCHECKED_CAST")
    @Composable
    private fun RmmTargets(opts: Map<String, Any?>, ctx: CardContext, w: Dp, h: Dp) {
        val group = opts["map_group"] as? String ?: "default"
        val showHibernating = opts["show_hibernating"] as? Boolean ?: false
        val src = (opts["source_size"] as? List<*>)?.filterIsInstance<Number>()?.map { it.toFloat() }
        val crop = (opts["crop"] as? List<*>)?.filterIsInstance<Number>()?.map { it.toFloat() }
        val fill = parseHexColor(opts["color"] as? String) ?: Color(0xD92CAA9C)
        val accent = parseHexColor(opts["accent_color"] as? String) ?: Color(0xFF8CE0D4)

        var targets by remember { mutableStateOf<List<RmmTarget>>(emptyList()) }
        // Only while the floorplan is actually on screen. The stream sends two
        // frames a second (~6 KB/s, radar diagnostics included), and with every
        // page kept composed it ran on other pages, under the screensaver and
        // with the screen off — waking the Wi-Fi all night.
        // HaClient re-makes it on every reconnect and stops it with the screen
        // off; this only has to follow the page and the screensaver.
        val live = LocalPageVisible.current && LocalDashboardShowing.current
        val client = ctx.client

        DisposableEffect(live, group, showHibernating) {
            val cancel = if (live) client.startForegroundSubscription(build = { put("type", "rmm/stream") }) { event ->
                val map = event["data"]?.jsonObject?.get("maps")?.jsonObject?.get(group)?.jsonObject
                    ?: return@startForegroundSubscription
                val next = map["targets"]?.jsonArray.orEmpty().mapNotNull { el ->
                    val t = el as? JsonObject ?: return@mapNotNull null
                    val count = t["count"]?.jsonPrimitive?.intOrNull ?: 0
                    if (count <= 0 && !showHibernating) return@mapNotNull null
                    // Rounded to 0.1 %: about two thirds of frames only jitter the
                    // fourth decimal, and an equal list doesn't redraw.
                    RmmTarget(
                        id = t["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null,
                        x = t["x"]?.jsonPrimitive?.floatOrNull?.let { (it * 10).roundToInt() / 10f } ?: return@mapNotNull null,
                        y = t["y"]?.jsonPrimitive?.floatOrNull?.let { (it * 10).roundToInt() / 10f } ?: return@mapNotNull null,
                    )
                }
                if (next != targets) targets = next
            } else null
            if (cancel == null) targets = emptyList()
            onDispose { cancel?.invoke() }
        }

        targets.forEachIndexed { i, t ->
            // RMM percent → source px → remote-image percent.
            val leftPct = if (src != null && crop != null && src.size == 2 && crop.size == 4)
                (t.x / 100f * src[0] - crop[0]) / crop[2] * 100f else t.x
            val topPct = if (src != null && crop != null && src.size == 2 && crop.size == 4)
                (t.y / 100f * src[1] - crop[1]) / crop[3] * 100f else t.y
            // Off the drawn plan (RMM allows points past the edges) — skip.
            if (leftPct !in 0f..100f || topPct !in 0f..100f) return@forEachIndexed
            key(t.id) {
                val dot = 26.dp
                val dx by animateDpAsState(w * (leftPct / 100f) - dot / 2, tween(450), label = "rmmx")
                val dy by animateDpAsState(h * (topPct / 100f) - dot / 2, tween(450), label = "rmmy")
                Box(
                    modifier = Modifier
                        .offset(x = dx.coerceAtLeast(0.dp), y = dy.coerceAtLeast(0.dp))
                        .size(dot)
                        .drawBehind {
                            drawCircle(color = fill)
                            drawCircle(color = accent, blendMode = BlendMode.Overlay)
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Text("${i + 1}", color = Color(0xFFE0EEE8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
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
            Text("$label$id", color = Color(0xFFE0EEE8), fontSize = 13.sp, fontWeight = FontWeight.Bold)
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
