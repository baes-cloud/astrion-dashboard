package com.custom.astrion.cards.impl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.MotionPhotosAuto
import androidx.compose.material.icons.filled.MotionPhotosPaused
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Whatshot
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.Fader
import com.custom.astrion.ui.HitTarget
import com.custom.astrion.ui.PowerPill
import com.custom.astrion.ui.UnavailableLabel
import com.custom.astrion.ui.dimIfUnavailable
import com.custom.astrion.ui.humanise
import com.custom.astrion.ui.tap
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Cover / blind card (Bæo v3.0 Matte Tonal):
 *  - Miniature architectural window curtain visualizer (`32×56dp`) showing live
 *    fabric drop (`blindSolidFill` + blush hem for blackout, vertically pleated
 *    `blindSheerFill` when `"style": "sheer"`).
 *  - Smooth borderless relative-drag [Fader] sending `cover.set_cover_position`
 *    (honoring `invert_position`) plus a compact borderless matte rocker
 *    (`▲ | ■ | ▼`, highlighting `■` in `blushContainer` while moving).
 */
class CoverCard : CardRenderer {
    override val type = "cover"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        val invertPosition = config.bool("invert_position", false)
        val invertButtons = config.bool("invert_buttons", false)
        val sheer = config.string("style") == "sheer" ||
            name.contains("sheer", ignoreCase = true) ||
            entityId.contains("sheer", ignoreCase = true)

        val rawPosition = e?.attrInt("current_position")
        val position = rawPosition?.let { if (invertPosition) 100 - it else it }
        val rawState = e?.state.orEmpty()
        val moving = rawState == "opening" || rawState == "closing"
        val open = when {
            position != null -> position > 0
            else -> (rawState == "open") != invertPosition
        }
        val stateLabel = when {
            rawState == "opening" -> if (invertPosition) "Closing…" else "Opening…"
            rawState == "closing" -> if (invertPosition) "Opening…" else "Closing…"
            position != null -> when (position) {
                0 -> "Closed"
                100 -> "Open"
                else -> "$position% open"
            }
            else -> if (open) "Open" else "Closed"
        }

        fun call(service: String) {
            ctx.client.callService(ServiceCall(domain = "cover", service = service, entityId = entityId))
        }
        fun setOpenFraction(openFrac: Float) {
            val openPct = (openFrac.coerceIn(0f, 1f) * 100f).roundToInt()
            val haPos = if (invertPosition) 100 - openPct else openPct
            ctx.client.callService(
                ServiceCall.of("cover", "set_cover_position", entityId, "position" to haPos)
            )
        }
        val openService = if (invertButtons) "close_cover" else "open_cover"
        val closeService = if (invertButtons) "open_cover" else "close_cover"

        val fill = config.options["pin"] == "fill"
        val openFraction = position?.let { it.coerceIn(0, 100) / 100f } ?: if (open) 1f else 0f
        val closedFraction = (1f - openFraction).coerceIn(0f, 1f)
        val fabricFill = if (sheer) AstrionTheme.blindSheerFill else AstrionTheme.blindSolidFill

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fill) Modifier.fillMaxHeight() else Modifier)
                .clip(RoundedCornerShape(AstrionTheme.surfaces.cardCornerRadius))
                .background(AstrionTheme.cardBg)
                .padding(horizontal = 11.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Miniature architectural window visualizer (shows dropped blind curtain).
            BlindWindowVisualizer(
                closedFraction = closedFraction,
                sheer = sheer,
                unavailable = unavailable,
                modifier = Modifier
                    .width(30.dp)
                    .height(if (fill) 52.dp else 50.dp),
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = name,
                            color = AstrionTheme.textPrimary,
                            fontSize = AstrionTheme.size.body,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (unavailable) {
                            UnavailableLabel(AstrionTheme.size.micro)
                        } else {
                            Text(
                                text = stateLabel,
                                color = if (moving) AstrionTheme.blush else AstrionTheme.textSecondary,
                                fontSize = AstrionTheme.size.micro,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }

                    Row(
                        modifier = Modifier.dimIfUnavailable(unavailable),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        CoverBtn(Icons.Filled.KeyboardArrowUp, "Open $name", live, active = false) { call(openService) }
                        CoverBtn(Icons.Filled.Stop, "Stop $name", live, active = moving) { call("stop_cover") }
                        CoverBtn(Icons.Filled.KeyboardArrowDown, "Close $name", live, active = false) { call(closeService) }
                    }
                }

                // Direct smooth drag fader for exact blind positioning.
                Fader(
                    value = if (unavailable) null else openFraction,
                    onCommit = ::setOpenFraction,
                    enabled = live,
                    fill = fabricFill,
                    track = AstrionTheme.trackBg,
                    radius = 9.dp,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(26.dp)
                        .dimIfUnavailable(unavailable),
                ) {
                    Text(
                        text = if (sheer) "Sheer" else "Position",
                        color = AstrionTheme.textSecondary,
                        fontSize = AstrionTheme.size.micro,
                        fontWeight = FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    @Composable
    private fun BlindWindowVisualizer(
        closedFraction: Float,
        sheer: Boolean,
        unavailable: Boolean,
        modifier: Modifier = Modifier,
    ) {
        val well = AstrionTheme.trackBg
        val fabric = if (sheer) AstrionTheme.blindSheerFill else AstrionTheme.blindSolidFill
        val hem = if (sheer) AstrionTheme.textMuted else AstrionTheme.blush
        val pleat = AstrionTheme.textPrimary.copy(alpha = 0.14f)
        val windowGlow = AstrionTheme.accent.copy(alpha = 0.10f)
        Canvas(
            modifier = modifier
                .dimIfUnavailable(unavailable)
                .clip(RoundedCornerShape(8.dp)),
        ) {
            val r = CornerRadius(8.dp.toPx(), 8.dp.toPx())
            drawRoundRect(color = well, cornerRadius = r)
            // Soft daylight tint in the uncovered pane below the blind.
            drawRoundRect(color = windowGlow, cornerRadius = r)
            val dropH = (size.height * closedFraction.coerceIn(0f, 1f))
                .coerceAtLeast(if (closedFraction > 0.02f) 6.dp.toPx() else 0f)
            if (dropH > 0f) {
                drawRect(color = fabric, topLeft = Offset.Zero, size = Size(size.width, dropH))
                if (sheer) {
                    val pleats = 4
                    val stepX = size.width / (pleats + 1)
                    for (i in 1..pleats) {
                        val x = stepX * i
                        drawLine(
                            color = pleat,
                            start = Offset(x, 0f),
                            end = Offset(x, dropH),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                }
                val hemH = 2.dp.toPx()
                drawRect(
                    color = hem,
                    topLeft = Offset(0f, (dropH - hemH).coerceAtLeast(0f)),
                    size = Size(size.width, hemH),
                )
            }
        }
    }

    @Composable
    private fun CoverBtn(
        icon: ImageVector,
        description: String,
        enabled: Boolean,
        active: Boolean,
        onClick: () -> Unit,
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 30.dp)
                .clip(RoundedCornerShape(9.dp))
                .background(if (active) AstrionTheme.blushContainer else AstrionTheme.controlBg)
                .tap(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = description,
                tint = if (active) AstrionTheme.blush else AstrionTheme.textOnControl,
                modifier = Modifier.size(19.dp),
            )
        }
    }
}

/**
 * Fan row: kept as a registered renderer for config compatibility, though not
 * placed on the Climate page (removed in 4442501).
 */
class FanCard : CardRenderer {
    override val type = "fan"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val on = e?.isOn == true
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        val speeds = fanSpeedCount(e)
        val speed = fanSpeedOf(e, speeds)
        var detail by remember { mutableStateOf(false) }

        var pending by remember(entityId) { mutableStateOf<Int?>(null) }
        LaunchedEffect(pending) {
            val s = pending ?: return@LaunchedEffect
            delay(FAN_SETTLE_MS)
            ctx.client.callService(
                ServiceCall.of("fan", "set_percentage", entityId, "percentage" to fanPercentFor(s, speeds))
            )
            delay(FAN_ECHO_MS)
            pending = null
        }
        LaunchedEffect(speed, on) {
            if (on && pending == speed) pending = null
        }
        fun nudge(delta: Int) {
            val from = pending ?: if (on) speed else 0
            val next = (from + delta).coerceIn(1, speeds)
            pending = if (on && next == speed) null else next
        }
        val shownSpeed = pending ?: if (on) speed else null
        val left = config.string("timer_left_entity")
            ?.let { ctx.entity(it)?.state?.toDoubleOrNull() }?.takeIf { it > 0 }
        val stateLine = if (shownSpeed == null) "Off" else listOfNotNull(
            "Speed $shownSpeed/$speeds",
            e?.attrString("preset_mode")?.takeUnless { it.equals("normal", ignoreCase = true) }?.humanise(),
            left?.let { formatMinutesLeft(it) },
        ).joinToString(" · ")

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AstrionTheme.surfaces.cardCornerRadius))
                .background(AstrionTheme.cardBg)
                .padding(horizontal = 12.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(10.dp))
                    .tap(enabled = e != null) { detail = true },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(AstrionTheme.raised),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Air,
                        contentDescription = "$name, ${if (on) "on" else "off"}, more controls",
                        tint = when {
                            unavailable -> AstrionTheme.unavailable
                            on -> AstrionTheme.accent
                            else -> AstrionTheme.colors.textSoft
                        },
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name, color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.body, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        Icon(
                            Icons.Filled.ChevronRight, contentDescription = null,
                            tint = AstrionTheme.textSecondary, modifier = Modifier.size(16.dp),
                        )
                    }
                    if (unavailable) {
                        UnavailableLabel(AstrionTheme.size.label)
                    } else {
                        Text(
                            stateLine, color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.label,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.width(7.dp))
            Row(
                modifier = Modifier.dimIfUnavailable(unavailable),
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                CircleBtn(Icons.Filled.Remove, "$name slower", live && shownSpeed != null, size = 38.dp) { nudge(-1) }
                CircleBtn(Icons.Filled.Add, "$name faster", live, size = 38.dp) { nudge(1) }
                PowerPill(on = on, name = name, enabled = live, height = 38.dp) { ctx.client.toggle(entityId) }
            }
        }

        if (detail) FanDetailDialog(entityId, name, config, ctx) { detail = false }
    }
}

private const val FAN_SETTLE_MS = 700L
private const val FAN_ECHO_MS = 5_000L

/**
 * Switch tile (Bæo v3.0 Matte Tonal): borderless matte action pills.
 */
class SwitchCard : CardRenderer {
    override val type = "switch"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val on = e?.isOn == true
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        val icon = switchIcon(config.string("icon"))
        val onColor = parseColor(config.options["on_color"]) ?: AstrionTheme.colors.accentContainer
        val compact = config.bool("compact", false)

        if (config.string("style") == "row") {
            RowStyle(config, ctx, entityId, name, on, unavailable, live, icon)
            return
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AstrionTheme.surfaces.cardCornerRadius))
                .background(if (on && !unavailable) onColor else AstrionTheme.cardBg)
                .tap(enabled = live) { ctx.client.toggle(entityId) }
                .padding(if (compact) PaddingValues(horizontal = 10.dp, vertical = 6.dp) else PaddingValues(14.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(if (compact) 28.dp else 42.dp)
                    .dimIfUnavailable(unavailable)
                    .clip(RoundedCornerShape(if (compact) 8.dp else 12.dp))
                    .background(AstrionTheme.raised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = if (on) "$name, on" else "$name, off",
                    tint = when {
                        unavailable -> AstrionTheme.unavailable
                        on -> AstrionTheme.colors.blush
                        else -> AstrionTheme.colors.textSoft
                    },
                    modifier = Modifier.size(if (compact) 17.dp else 24.dp),
                )
            }
            Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name, color = AstrionTheme.textPrimary, fontSize = if (compact) AstrionTheme.size.label else AstrionTheme.size.emphasis,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (compact) {
                    // State is carried by the tile colour and the icon tint.
                } else if (unavailable) {
                    UnavailableLabel(AstrionTheme.size.label)
                } else {
                    Text(if (on) "On" else "Off", color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.label)
                }
            }
        }
    }
}

@Composable
private fun RowStyle(
    config: CardConfig,
    ctx: CardContext,
    entityId: String,
    name: String,
    on: Boolean,
    unavailable: Boolean,
    live: Boolean,
    icon: ImageVector,
) {
    val offAttention = config.bool("off_attention", false)
    val shownIcon = if (on) icon else config.string("icon_off")?.let { switchIcon(it) } ?: icon
    val stateWord = if (on) config.string("on_label") ?: "On" else config.string("off_label") ?: "Off"
    val pausedWarning = !unavailable && !on && offAttention
    val tint = when {
        unavailable -> AstrionTheme.unavailable
        pausedWarning -> AstrionTheme.blush
        on -> if (offAttention) AstrionTheme.accent else AstrionTheme.blush
        else -> AstrionTheme.colors.textSoft
    }
    val holdOnly = config.bool("hold", false)
    var hint by remember(entityId) { mutableStateOf(false) }
    LaunchedEffect(hint) { if (hint) { delay(HOLD_HINT_MS); hint = false } }
    val haptics = LocalHapticFeedback.current
    val holdActionPhrase = when {
        on && config.string("off_label").equals("Paused", ignoreCase = true) -> "Hold to pause"
        !on && config.string("off_label").equals("Paused", ignoreCase = true) -> "Hold to resume"
        on -> "Hold to turn off"
        else -> "Hold to turn on"
    }
    val toggleOn = if (holdOnly) {
        Modifier.combinedClickable(
            enabled = live,
            onClick = { hint = true },
            onLongClick = {
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                hint = false
                ctx.client.toggle(entityId)
            },
        )
    } else Modifier.tap(enabled = live) { ctx.client.toggle(entityId) }

    val pillBg = when {
        pausedWarning -> AstrionTheme.blushContainer
        else -> AstrionTheme.cardBg
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(pillBg)
            .then(toggleOn)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            shownIcon,
            contentDescription = if (holdOnly) "$name, $stateWord. $holdActionPhrase" else "$name, $stateWord",
            tint = tint,
            modifier = Modifier.size(17.dp),
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            val stateFirst = config.bool("state_first", false)
            val title: @Composable () -> Unit = {
                Text(
                    name,
                    color = AstrionTheme.textPrimary,
                    fontSize = AstrionTheme.size.label,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            val state: @Composable () -> Unit = {
                if (unavailable) {
                    UnavailableLabel(AstrionTheme.size.micro)
                } else {
                    Text(
                        if (hint) holdActionPhrase else stateWord,
                        color = when {
                            hint -> AstrionTheme.textPrimary
                            pausedWarning -> AstrionTheme.blush
                            else -> AstrionTheme.textSecondary
                        },
                        fontSize = AstrionTheme.size.micro,
                        fontWeight = if (pausedWarning || hint) FontWeight.SemiBold else FontWeight.Normal,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (stateFirst) { state(); title() } else { title(); state() }
        }
    }
}

private const val HOLD_HINT_MS = 2_500L

private fun switchIcon(name: String?): ImageVector = when (name) {
    "motion", "auto" -> Icons.Filled.MotionPhotosAuto
    "motion_sensor", "walk" -> Icons.Filled.DirectionsWalk
    "paused", "motion_off" -> Icons.Filled.MotionPhotosPaused
    "heater", "heat" -> Icons.Filled.Whatshot
    "fan" -> Icons.Filled.Air
    "bulb", "light" -> Icons.Filled.Lightbulb
    "music" -> Icons.Filled.MusicNote
    "night" -> Icons.Filled.Bedtime
    else -> Icons.Filled.PowerSettingsNew
}

private fun parseColor(v: Any?): Color? = when (v) {
    is Number -> Color(v.toLong())
    is String -> v.removePrefix("#").toLongOrNull(16)?.let { Color(it) }
    else -> null
}

@Composable
private fun CircleBtn(
    icon: ImageVector,
    description: String? = null,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    onClick: () -> Unit,
) {
    HitTarget(
        visual = size,
        onClick = onClick,
        enabled = enabled,
        face = Modifier.clip(CircleShape).background(AstrionTheme.controlBg),
    ) {
        Icon(icon, contentDescription = description, tint = AstrionTheme.textOnControl)
    }
}
