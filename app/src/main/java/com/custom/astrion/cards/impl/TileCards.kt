package com.custom.astrion.cards.impl

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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.RowScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.HitTarget
import com.custom.astrion.ui.PowerPill
import com.custom.astrion.ui.UnavailableLabel
import com.custom.astrion.ui.dimIfUnavailable
import com.custom.astrion.ui.humanise
import com.custom.astrion.ui.tap
import kotlinx.coroutines.delay

/**
 * Cover / blind card. A window drawing on the left shows how far the blind is
 * down. Beside it: the name with the position in fainter text, then two
 * segmented groups that match the Mode and Fan speed bars above them:
 * 25% and 50% (that much open, honouring `invert_position`), and, after a gap,
 * raise, stop and lower. The arrow for the way it is moving lights while it
 * moves, so the keys double as the status. No slider (Bae).
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
            name.contains("sheer", ignoreCase = true) || entityId.contains("sheer", ignoreCase = true)

        val rawPosition = e?.attrInt("current_position")
        val position = rawPosition?.let { if (invertPosition) 100 - it else it }
        val rawState = e?.state.orEmpty()
        val moving = rawState == "opening" || rawState == "closing"
        // Which way it is going on screen (an inverted motor reports the opposite).
        val goingUp = moving && ((rawState == "opening") != invertPosition)
        val goingDown = moving && !goingUp
        val open = when {
            position != null -> position > 0
            else -> (rawState == "open") != invertPosition
        }
        val stateLabel = when {
            goingUp -> "Opening…"
            goingDown -> "Closing…"
            position != null -> when (position) {
                0 -> "Closed"
                100 -> "Open"
                else -> "$position% open"
            }
            else -> if (open) "Open" else "Closed"
        }
        val closedFrac = (1f - (position?.coerceIn(0, 100) ?: if (open) 100 else 0) / 100f).coerceIn(0f, 1f)

        fun call(service: String) {
            ctx.client.callService(ServiceCall(domain = "cover", service = service, entityId = entityId))
        }
        fun setOpenPct(openPct: Int) {
            val haPos = if (invertPosition) 100 - openPct else openPct
            ctx.client.callService(ServiceCall.of("cover", "set_cover_position", entityId, "position" to haPos))
        }
        val openService = if (invertButtons) "close_cover" else "open_cover"
        val closeService = if (invertButtons) "open_cover" else "close_cover"
        val fill = config.options["pin"] == "fill"

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fill) Modifier.fillMaxHeight() else Modifier)
                .clip(RoundedCornerShape(AstrionTheme.surfaces.cardCornerRadius))
                .background(AstrionTheme.cardBg)
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BlindWindow(
                closedFraction = closedFrac,
                sheer = sheer,
                modifier = Modifier
                    .size(width = 38.dp, height = 58.dp)
                    .dimIfUnavailable(unavailable),
            )
            Spacer(Modifier.width(12.dp))
            Column(
                modifier = Modifier.weight(1f).then(if (fill) Modifier.fillMaxHeight() else Modifier),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        text = name,
                        color = AstrionTheme.textPrimary,
                        fontSize = AstrionTheme.size.body,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.width(8.dp))
                    if (unavailable) {
                        UnavailableLabel(AstrionTheme.size.micro)
                    } else {
                        Text(
                            text = stateLabel,
                            color = if (moving) AstrionTheme.accent else AstrionTheme.textMuted,
                            fontSize = AstrionTheme.size.micro,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            modifier = Modifier.padding(bottom = 1.dp),
                        )
                    }
                }
                // The keys take the rest of the card's height (at least 44dp).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(if (fill) Modifier.weight(1f) else Modifier.height(48.dp))
                        .heightIn(min = 44.dp)
                        .dimIfUnavailable(unavailable),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BlindTrough(Modifier.weight(1.7f).fillMaxHeight()) {
                        BlindKey("$name 25% open", live, selected = !moving && position == 25, text = "25%") { setOpenPct(25) }
                        BlindKey("$name 50% open", live, selected = !moving && position == 50, text = "50%") { setOpenPct(50) }
                    }
                    // The gap that sets the presets apart from the main keys.
                    Spacer(Modifier.width(8.dp))
                    BlindTrough(Modifier.weight(3.3f).fillMaxHeight()) {
                        BlindKey("Open $name", live, selected = goingUp, icon = Icons.Filled.KeyboardArrowUp) { call(openService) }
                        BlindKey("Stop $name", live, selected = false, icon = Icons.Filled.Stop, iconSize = 18.dp) { call("stop_cover") }
                        BlindKey("Close $name", live, selected = goingDown, icon = Icons.Filled.KeyboardArrowDown) { call(closeService) }
                    }
                }
            }
        }
    }

    /** A sunken track holding a few keys, like the Mode and Fan speed bars. */
    @Composable
    private fun BlindTrough(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
        Row(
            modifier = modifier
                .clip(RoundedCornerShape(13.dp))
                .background(AstrionTheme.colors.surfacePrimary)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically,
            content = content,
        )
    }

    @Composable
    private fun RowScope.BlindKey(
        description: String,
        enabled: Boolean,
        selected: Boolean,
        text: String? = null,
        icon: ImageVector? = null,
        iconSize: Dp = 30.dp,
        onClick: () -> Unit,
    ) {
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val bg = when {
            selected -> AstrionTheme.accentStrong
            pressed -> AstrionTheme.controlBg
            else -> Color.Transparent
        }
        val ink = if (selected) AstrionTheme.colors.onPrimary else AstrionTheme.textOnControl
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .clip(RoundedCornerShape(10.dp))
                .background(bg)
                .clickable(interactionSource = interaction, indication = null, enabled = enabled, onClick = onClick)
                .semantics { contentDescription = description },
            contentAlignment = Alignment.Center,
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(iconSize))
            } else if (text != null) {
                Text(text, color = ink, fontSize = AstrionTheme.size.label, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }

    /**
     * The window: a thin frame on a sill, a faint pane, and the blind drawn down
     * from the top as far as it really is in the accent sea-green, with slats
     * for a blackout or pleats for a sheer and a brighter hem where it ends.
     */
    @Composable
    private fun BlindWindow(closedFraction: Float, sheer: Boolean, modifier: Modifier = Modifier) {
        val frame = AstrionTheme.textMuted.copy(alpha = 0.75f)
        val accent = AstrionTheme.accent
        Canvas(modifier) {
            val sw = 1.5.dp.toPx()
            val sillH = 2.5.dp.toPx()
            val frameH = size.height - sillH - 2.dp.toPx()
            val inX = 4.dp.toPx()
            val r = CornerRadius(6.dp.toPx(), 6.dp.toPx())
            // Pane, frame and sill.
            drawRoundRect(color = accent.copy(alpha = 0.06f), topLeft = Offset(inX, 0f),
                size = Size(size.width - inX * 2, frameH), cornerRadius = r)
            drawRoundRect(color = frame, topLeft = Offset(inX + sw / 2, sw / 2),
                size = Size(size.width - inX * 2 - sw, frameH - sw), cornerRadius = r, style = Stroke(sw))
            drawRoundRect(color = frame, topLeft = Offset(0f, size.height - sillH),
                size = Size(size.width, sillH), cornerRadius = CornerRadius(sillH / 2, sillH / 2))
            // The blind.
            val pad = 3.5.dp.toPx()
            val x0 = inX + pad
            val w = size.width - (inX + pad) * 2
            val y0 = pad
            val h = frameH - pad * 2
            val drop = (h * closedFraction).coerceIn(0f, h)
            val hemH = 2.5.dp.toPx()
            if (drop > hemH) {
                drawRect(color = accent.copy(alpha = if (sheer) 0.16f else 0.30f), topLeft = Offset(x0, y0), size = Size(w, drop))
                if (sheer) {
                    for (i in 1..3) {
                        val x = x0 + w * i / 4
                        drawLine(accent.copy(alpha = 0.45f), Offset(x, y0), Offset(x, y0 + drop - hemH), 1.dp.toPx())
                    }
                } else {
                    val step = 5.dp.toPx()
                    var y = y0 + step
                    while (y < y0 + drop - hemH) {
                        drawLine(accent.copy(alpha = 0.45f), Offset(x0, y), Offset(x0 + w, y), 1.dp.toPx())
                        y += step
                    }
                }
            }
            drawRoundRect(color = accent, topLeft = Offset(x0 - 1.dp.toPx(), y0 + (drop - hemH).coerceAtLeast(0f)),
                size = Size(w + 2.dp.toPx(), hemH), cornerRadius = CornerRadius(hemH / 2, hemH / 2))
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
