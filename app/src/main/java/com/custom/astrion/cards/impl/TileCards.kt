package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.Blinds
import androidx.compose.material3.Icon
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AstrionTheme.raised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (open) Icons.Filled.Blinds else Icons.Outlined.Blinds,
                    contentDescription = if (open) "$name, open" else "$name, closed",
                    tint = when {
                        unavailable -> AstrionTheme.unavailable
                        open -> AstrionTheme.on
                        else -> Color(0xFFB6C9CE)
                    },
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name,
                    color = AstrionTheme.textPrimary,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    // Was maxLines=1 with no overflow, so a long name simply clipped.
                    overflow = TextOverflow.Ellipsis,
                )
                if (unavailable) {
                    UnavailableLabel(13.sp)
                } else {
                    Text(stateLabel, color = AstrionTheme.textSecondary, fontSize = 13.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircleBtn(Icons.Filled.KeyboardArrowUp, "Open $name", live) { call(openService) }
                CircleBtn(Icons.Filled.Stop, "Stop $name", live) { call("stop_cover") }
                CircleBtn(Icons.Filled.KeyboardArrowDown, "Close $name", live) { call(closeService) }
            }
        }
    }
}

/**
 * Fan card: toggle tile with a percentage readout and up/down speed steppers.
 *
 * Uses fan.toggle and fan.set_percentage.
 *
 * Config: CardConfig("fan", mapOf("entity_id" to "fan.bedroom", "step" to 20))
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
        val pct = e?.attrInt("percentage") ?: 0
        val step = config.int("step", 20).coerceAtLeast(1)

        fun setPct(p: Int) {
            ctx.client.callService(
                ServiceCall.of("fan", "set_percentage", entityId, "percentage" to p.coerceIn(0, 100))
            )
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .dimIfUnavailable(unavailable)
                .clip(RoundedCornerShape(18.dp))
                .background(if (on) Color(0xFF2B3A67) else AstrionTheme.cardBgAlt)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .tap(enabled = live) { ctx.client.toggle(entityId) }
            ) {
                Text(
                    name, color = AstrionTheme.textPrimary, fontSize = 17.sp,
                    fontWeight = FontWeight.SemiBold,
                    // Previously had neither maxLines nor overflow, so a long
                    // name wrapped and reflowed the whole row.
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (unavailable) {
                    UnavailableLabel(13.sp)
                } else {
                    Text(if (on) "$pct%" else "Off", color = AstrionTheme.textSecondary, fontSize = 13.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircleBtn(Icons.Filled.KeyboardArrowDown, "$name slower", live) { setPct(pct - step) }
                CircleBtn(Icons.Filled.KeyboardArrowUp, "$name faster", live) { setPct(pct + step) }
            }
        }
    }
}

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

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .dimIfUnavailable(unavailable)
                .clip(RoundedCornerShape(18.dp))
                .background(if (on) onColor else AstrionTheme.cardBgAlt)
                .tap(enabled = live) { ctx.client.toggle(entityId) }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Leading icon box, matching the cover tiles below it.
            Box(
                modifier = Modifier
                    .size(42.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(AstrionTheme.raised),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = if (on) "$name, on" else "$name, off",
                    tint = when {
                        unavailable -> AstrionTheme.unavailable
                        on -> Color(0xFFE79A9A)
                        else -> Color(0xFFB6C9CE)
                    },
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    name, color = AstrionTheme.textPrimary, fontSize = 16.sp,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                if (unavailable) {
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
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(CircleShape)
            .background(AstrionTheme.controlBg)
            .tap(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = AstrionTheme.textOnControl)
    }
}
