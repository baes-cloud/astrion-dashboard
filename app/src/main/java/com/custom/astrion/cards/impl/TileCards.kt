package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.Blinds
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.UnavailableLabel
import com.custom.astrion.ui.dimIfUnavailable
import com.custom.astrion.ui.humanise
import com.custom.astrion.ui.tap
import kotlinx.coroutines.delay

/**
 * Cover / curtain card: open / stop / close buttons plus a position readout.
 *
 * Uses cover.open_cover / cover.close_cover / cover.stop_cover.
 *
 * Config: CardConfig("cover", mapOf("entity_id" to "cover.living_room", "name" to "Curtains"))
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
        // Some blinds are wired backwards: they report 100 when shut, and/or
        // their open/close motors run the other way. Fixed per-entity in
        // config, because it varies by blind — not a house-wide convention.
        val invertPosition = config.bool("invert_position", false)
        val invertButtons = config.bool("invert_buttons", false)

        val rawPosition = e?.attrInt("current_position") // as HA reports it
        val position = rawPosition?.let { if (invertPosition) 100 - it else it }
        // This card had no on/off encoding at all — open and closed looked
        // identical. Open is now carried by both the glyph and its tint.
        val open = when {
            position != null -> position > 0
            else -> (e?.state == "open") != invertPosition
        }
        val stateLabel = position?.let { "$it% open" } ?: (e?.state?.humanise() ?: "—")

        fun call(service: String) {
            ctx.client.callService(ServiceCall(domain = "cover", service = service, entityId = entityId))
        }
        val openService = if (invertButtons) "close_cover" else "open_cover"
        val closeService = if (invertButtons) "open_cover" else "close_cover"

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .dimIfUnavailable(unavailable)
                .clip(RoundedCornerShape(18.dp))
                .background(AstrionTheme.cardBgAlt)
                // 55dp tall: three blinds, the fan and the aircon fill the
                // Climate page exactly, without scrolling.
                .padding(horizontal = 12.dp, vertical = 8.5.dp),
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
                    if (open) Icons.Filled.Blinds else Icons.Outlined.Blinds,
                    contentDescription = if (open) "$name, open" else "$name, closed",
                    tint = when {
                        unavailable -> AstrionTheme.unavailable
                        open -> AstrionTheme.blush
                        else -> Color(0xFFC3D0CD)
                    },
                )
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = AstrionTheme.textPrimary,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    // Was maxLines=1 with no overflow, so a long name simply clipped.
                    overflow = TextOverflow.Ellipsis,
                )
                if (unavailable) {
                    UnavailableLabel(12.sp)
                } else {
                    Text(stateLabel, color = AstrionTheme.textSecondary, fontSize = 12.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                CircleBtn(Icons.Filled.KeyboardArrowUp, "Open $name", live, size = 38.dp) { call(openService) }
                CircleBtn(Icons.Filled.Stop, "Stop $name", live, size = 38.dp) { call("stop_cover") }
                CircleBtn(Icons.Filled.KeyboardArrowDown, "Close $name", live, size = 38.dp) { call(closeService) }
            }
        }
    }
}

/**
 * Fan row: one line, the height of a blind row. Speed down / up and power sit
 * on the right; tapping the name opens everything else (speed bars, modes,
 * swing, timer) in [FanDetailDialog].
 *
 * Speed taps settle locally and go to HA once they stop, as the aircon's
 * setpoint does: each tap used to work from HA's last echo, so a quick
 * double tap sent the same speed twice. "+" on a fan that's off starts it at
 * speed 1; "−" stops at speed 1 rather than turning it off.
 *
 * Config:
 *   { "type": "fan", "options": { "entity_id": "fan.fann", "name": "Fan",
 *       "swing_entity": "switch.fann_vertical_oscillation",   // second swing axis, optional
 *       "timer_entity": "select.fann_timer",                  // sleep timer select, optional
 *       "timer_left_entity": "sensor.fann_time_remaining",    // minutes left, optional
 *       "temperature_entity": "sensor.fann_temperature",      // optional
 *       "problem_entity": "binary_sensor.fann_problem" } }    // optional
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
            // If HA never echoes it, stop overriding.
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
                .dimIfUnavailable(unavailable)
                .clip(RoundedCornerShape(18.dp))
                .background(AstrionTheme.cardBgAlt)
                // Same 52dp as the blind rows it sits above.
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
                            else -> Color(0xFFC3D0CD)
                        },
                    )
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            name, color = AstrionTheme.textPrimary, fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                        )
                        // Says the name opens more.
                        Icon(
                            Icons.Filled.ChevronRight, contentDescription = null,
                            tint = AstrionTheme.textSecondary, modifier = Modifier.size(16.dp),
                        )
                    }
                    if (unavailable) {
                        UnavailableLabel(12.sp)
                    } else {
                        Text(
                            stateLine, color = AstrionTheme.textSecondary, fontSize = 12.sp,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Spacer(Modifier.width(7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                CircleBtn(Icons.Filled.Remove, "$name slower", live && shownSpeed != null, size = 38.dp) { nudge(-1) }
                CircleBtn(Icons.Filled.Add, "$name faster", live, size = 38.dp) { nudge(1) }
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(if (on) AstrionTheme.accentStrong else AstrionTheme.controlBg)
                        .tap(enabled = live) { ctx.client.toggle(entityId) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PowerSettingsNew,
                        contentDescription = if (on) "Turn $name off" else "Turn $name on",
                        tint = if (on) Color.White else AstrionTheme.textOnControl,
                    )
                }
            }
        }

        if (detail) FanDetailDialog(entityId, name, config, ctx) { detail = false }
    }
}

/** Quiet time after the last speed tap before it's sent. */
private const val FAN_SETTLE_MS = 700L
/** How long the local speed overrides HA's while waiting for the echo. */
private const val FAN_ECHO_MS = 5_000L

/**
 * Switch tile: simple toggle. Works for switch.* (and anything toggleable).
 *
 * Config: CardConfig("switch", mapOf("entity_id" to "switch.porch", "name" to "Porch"))
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
        // On-state background (e.g. a semi-transparent dark red for a heater).
        val onColor = parseColor(config.options["on_color"]) ?: Color(0xFF2E5A46)
        // `compact`: one line (name, state as a dot of colour), for a row of
        // toggles that shouldn't take a card's worth of height each.
        val compact = config.bool("compact", false)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .dimIfUnavailable(unavailable)
                .clip(RoundedCornerShape(18.dp))
                .background(if (on) onColor else AstrionTheme.cardBgAlt)
                .tap(enabled = live) { ctx.client.toggle(entityId) }
                .padding(if (compact) PaddingValues(horizontal = 10.dp, vertical = 6.dp) else PaddingValues(14.dp)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading icon box, matching the cover tiles below it.
            Box(
                modifier = Modifier
                    .size(if (compact) 28.dp else 42.dp)
                    .clip(RoundedCornerShape(if (compact) 8.dp else 12.dp))
                    .background(AstrionTheme.raised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = if (on) "$name, on" else "$name, off",
                    tint = when {
                        unavailable -> AstrionTheme.unavailable
                        on -> Color(0xFFE79A9A)
                        else -> Color(0xFFC3D0CD)
                    },
                    modifier = Modifier.size(if (compact) 17.dp else 24.dp),
                )
            }
            Spacer(Modifier.width(if (compact) 8.dp else 12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name, color = AstrionTheme.textPrimary, fontSize = if (compact) 13.sp else 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (compact) {
                    // State is carried by the tile colour and the icon tint.
                } else if (unavailable) {
                    UnavailableLabel(13.sp)
                } else {
                    Text(if (on) "On" else "Off", color = AstrionTheme.textSecondary, fontSize = 13.sp)
                }
            }
        }
    }
}

/** Map a switch card's `icon` option name to a Material icon. */
private fun switchIcon(name: String?): ImageVector = when (name) {
    "heater", "heat" -> Icons.Filled.Whatshot
    "fan" -> Icons.Filled.Air
    "bulb", "light" -> Icons.Filled.Lightbulb
    "music" -> Icons.Filled.MusicNote
    "night" -> Icons.Filled.Bedtime
    else -> Icons.Filled.PowerSettingsNew
}

/** Parse an `on_color` option: a hex string ("#AARRGGBB") or an ARGB number. */
private fun parseColor(v: Any?): Color? = when (v) {
    is Number -> Color(v.toLong())
    is String -> v.removePrefix("#").toLongOrNull(16)?.let { Color(it) }
    else -> null
}

/** Shared small circular icon button used by the tile cards above. */
@Composable
private fun CircleBtn(
    icon: ImageVector,
    description: String? = null,
    enabled: Boolean = true,
    size: Dp = 44.dp,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(AstrionTheme.controlBg)
            .tap(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = AstrionTheme.textOnControl)
    }
}
