package com.custom.astrion.cards.impl

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Remove
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.Glyphs
import com.custom.astrion.ui.MIN_TOUCH
import com.custom.astrion.ui.PowerPill
import com.custom.astrion.ui.UnavailableLabel
import com.custom.astrion.ui.components.BaeoSectionHeader
import com.custom.astrion.ui.components.BaeoSegmentItem
import com.custom.astrion.ui.components.BaeoSegmentedBar
import com.custom.astrion.ui.dimIfUnavailable
import com.custom.astrion.ui.humanise
import com.custom.astrion.ui.tap
import kotlinx.coroutines.delay
import kotlin.math.max
import kotlin.math.min

/**
 * Climate / thermostat card (Bæo v3.0 Matte Tonal).
 *
 * Borderless mode-tinted matte card (`accentContainer` Cool, `warmSurface` Heat,
 * `cardBg` Off), Manrope 400 tabular setpoint readout with 48dp steppers,
 * outdoor temperature context, zero-GPU thermal delta scale, and recessed
 * segmented bars for HVAC mode and fan speed.
 *
 * Keeps `climate.aircon` fan_mode controls inline; does not introduce a separate
 * fan card.
 */
private const val SETPOINT_SETTLE_MS = 900L
private const val SETPOINT_ECHO_MS = 6_000L

class ClimateCard : CardRenderer {
    override val type = "climate"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        val step = e?.attrDouble("target_temp_step")
            ?: (config.options["step"] as? Number)?.toDouble()
            ?: 1.0
        val minT = e?.attrDouble("min_temp") ?: 16.0
        val maxT = e?.attrDouble("max_temp") ?: 30.0

        val target = e?.attrDouble("temperature")
        val current = e?.attrDouble("current_temperature")
        val mode = e?.state ?: "off"
        val modes = e?.attrStringList("hvac_modes") ?: emptyList()
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val fanMode = e?.attrString("fan_mode")
        val fanModes = config.stringList("fan_modes").ifEmpty { listOf("low", "medium", "high", "auto") }

        val weatherId = config.string("weather_entity") ?: "weather.forecast_home"
        val outdoor = ctx.entities[weatherId]?.attrDouble("temperature")

        fun setTemp(t: Double) {
            val clamped = t.coerceIn(minT, maxT)
            ctx.client.callService(
                ServiceCall.of("climate", "set_temperature", entityId, "temperature" to clamped)
            )
        }

        // Setpoint taps settle locally to avoid Fujitsu cloud 429 rate-limits.
        var pendingTarget by remember(entityId) { mutableStateOf<Double?>(null) }
        LaunchedEffect(pendingTarget) {
            val t = pendingTarget ?: return@LaunchedEffect
            delay(SETPOINT_SETTLE_MS)
            setTemp(t)
            delay(SETPOINT_ECHO_MS)
            pendingTarget = null
        }
        LaunchedEffect(target) {
            if (pendingTarget != null && target == pendingTarget) pendingTarget = null
        }
        fun nudge(delta: Double) {
            val from = pendingTarget ?: target ?: return
            val next = (from + delta).coerceIn(minT, maxT)
            pendingTarget = if (next == target) null else next
        }
        val shownTarget = pendingTarget ?: target

        fun setMode(m: String) {
            ctx.client.callService(
                ServiceCall.of("climate", "set_hvac_mode", entityId, "hvac_mode" to m)
            )
        }
        fun setFan(f: String) {
            ctx.client.callService(
                ServiceCall.of("climate", "set_fan_mode", entityId, "fan_mode" to f)
            )
        }
        val isOff = mode == "off"
        val isHeat = mode == "heat"
        fun togglePower() {
            ctx.client.callService(ServiceCall("climate", if (isOff) "turn_on" else "turn_off", entityId))
        }
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected

        val targetBg = when {
            unavailable || isOff -> AstrionTheme.cardBg
            isHeat -> AstrionTheme.warmSurface
            mode == "cool" || mode == "dry" || mode == "heat_cool" || mode == "auto" -> AstrionTheme.accentContainer
            else -> AstrionTheme.cardBg
        }
        val cardBg by animateColorAsState(targetBg, tween(240), label = "climateBg")
        val modeInk = when {
            isOff -> AstrionTheme.textSecondary
            isHeat -> AstrionTheme.warmHeat
            else -> AstrionTheme.accent
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AstrionTheme.surfaces.cardCornerRadius))
                .background(cardBg)
                .padding(horizontal = 13.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            // Header: title, live status (or pending setpoint feedback), power pill.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = name,
                        color = AstrionTheme.textPrimary,
                        fontSize = AstrionTheme.size.emphasis,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (unavailable) {
                        UnavailableLabel(AstrionTheme.size.label)
                    } else {
                        val subtitle = when {
                            pendingTarget != null -> "Setting ${trim(pendingTarget!!)}°…"
                            isOff -> "Off"
                            else -> listOfNotNull(
                                modeLabel(mode),
                                fanMode?.let { "${it.humanise()} fan" },
                            ).joinToString(" · ")
                        }
                        Text(
                            text = subtitle,
                            color = if (pendingTarget != null) AstrionTheme.blush else modeInk,
                            fontSize = AstrionTheme.size.label,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                PowerPill(on = !isOff, name = name, enabled = live, height = 34.dp) { togglePower() }
            }

            // Setpoint readout + 48dp matte circular steppers.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .dimIfUnavailable(unavailable),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Stepper(Icons.Filled.Remove, "Lower target temperature", live && shownTarget != null) {
                    nudge(-step)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = shownTarget?.let { "${trim(it)}°" } ?: "—",
                        color = if (unavailable) AstrionTheme.unavailable else AstrionTheme.textPrimary,
                        fontFamily = AstrionTheme.readoutFont,
                        fontSize = AstrionTheme.size.displayXl,
                        fontWeight = FontWeight.Normal,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                        maxLines = 1,
                    )
                    val meta = buildList {
                        current?.let { add("Now ${trim(it)}°") }
                        outdoor?.let { add("Out ${trim(it)}°") }
                    }.joinToString(" · ")
                    if (meta.isNotEmpty() && !unavailable) {
                        Text(
                            text = meta,
                            color = AstrionTheme.textSecondary,
                            fontSize = AstrionTheme.size.label,
                            style = TextStyle(fontFeatureSettings = "tnum"),
                            maxLines = 1,
                        )
                    }
                }

                Stepper(Icons.Filled.Add, "Raise target temperature", live && shownTarget != null) {
                    nudge(step)
                }
            }

            // Zero-GPU thermal delta scale (current vs target across minT..maxT).
            if (!unavailable && (current != null || shownTarget != null)) {
                ThermalDeltaScale(
                    minTemp = minT,
                    maxTemp = maxT,
                    current = current,
                    target = shownTarget,
                    deltaColor = if (isHeat) AstrionTheme.warmHeat else AstrionTheme.accent,
                    active = !isOff,
                )
            }

            // HVAC Mode segmented bar (1-tap wakes unit directly into the chosen mode).
            val shownModes = modes.filter { it != "off" }.take(4)
            if (shownModes.isNotEmpty()) {
                BaeoSectionHeader("Mode")
                BaeoSegmentedBar(modifier = Modifier.dimIfUnavailable(unavailable)) {
                    shownModes.forEach { m ->
                        val isSelected = m == mode
                        val selBg = if (m == "heat") AstrionTheme.blushContainer else AstrionTheme.accentStrong
                        val selInk = if (m == "heat") AstrionTheme.warmHeat else AstrionTheme.colors.onPrimary
                        BaeoSegmentItem(
                            label = modeLabel(m),
                            selected = isSelected,
                            icon = modeIcon(m),
                            enabled = live,
                            height = 36.dp,
                            selectedBg = selBg,
                            selectedInk = selInk,
                            onClick = { setMode(m) },
                        )
                    }
                }
            }

            // Climate fan_mode segmented bar (kept inline on climate.aircon).
            if (fanModes.isNotEmpty()) {
                BaeoSectionHeader("Fan speed")
                BaeoSegmentedBar(modifier = Modifier.dimIfUnavailable(unavailable)) {
                    fanModes.forEach { f ->
                        val isSelected = !isOff && fanMode?.equals(f, ignoreCase = true) == true
                        BaeoSegmentItem(
                            label = f.humanise(),
                            selected = isSelected,
                            enabled = live,
                            height = 36.dp,
                            onClick = { setFan(f) },
                        )
                    }
                }
            }
        }
    }

    private fun trim(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else String.format("%.1f", d)

    private fun modeLabel(mode: String): String = when (mode) {
        "fan_only" -> "Fan"
        "heat_cool" -> "Auto"
        else -> mode.humanise()
    }

    private fun modeIcon(mode: String): ImageVector? = when (mode) {
        "cool" -> Glyphs.cool
        "heat" -> Glyphs.heat
        "fan_only" -> Glyphs.fan
        "dry" -> Glyphs.drop
        else -> null
    }

    @Composable
    private fun Stepper(
        icon: ImageVector,
        description: String,
        enabled: Boolean,
        onClick: () -> Unit,
    ) {
        Box(
            modifier = Modifier
                .size(MIN_TOUCH)
                .clip(CircleShape)
                .background(AstrionTheme.controlBg)
                .tap(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = AstrionTheme.textOnControl)
        }
    }

    @Composable
    private fun ThermalDeltaScale(
        minTemp: Double,
        maxTemp: Double,
        current: Double?,
        target: Double?,
        deltaColor: Color,
        active: Boolean,
    ) {
        val track = AstrionTheme.trackBg
        val marker = AstrionTheme.textSecondary
        val dot = if (active) deltaColor else AstrionTheme.textMuted
        val span = (maxTemp - minTemp).coerceAtLeast(1.0)
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(10.dp)
                .padding(horizontal = 2.dp),
        ) {
            val barH = 4.dp.toPx()
            val y = (size.height - barH) / 2f
            val r = CornerRadius(barH / 2f, barH / 2f)
            drawRoundRect(color = track, topLeft = Offset(0f, y), size = Size(size.width, barH), cornerRadius = r)

            val curX = current?.let { (((it - minTemp) / span).coerceIn(0.0, 1.0) * size.width).toFloat() }
            val tgtX = target?.let { (((it - minTemp) / span).coerceIn(0.0, 1.0) * size.width).toFloat() }

            if (active && curX != null && tgtX != null) {
                val left = min(curX, tgtX)
                val width = max(barH, max(curX, tgtX) - left)
                drawRoundRect(
                    color = deltaColor.copy(alpha = 0.50f),
                    topLeft = Offset(left, y),
                    size = Size(width, barH),
                    cornerRadius = r,
                )
            }
            if (curX != null) {
                drawRoundRect(
                    color = marker,
                    topLeft = Offset((curX - 1.dp.toPx()).coerceIn(0f, size.width - 2.dp.toPx()), 1.dp.toPx()),
                    size = Size(2.dp.toPx(), size.height - 2.dp.toPx()),
                    cornerRadius = CornerRadius(1.dp.toPx(), 1.dp.toPx()),
                )
            }
            if (tgtX != null) {
                drawCircle(
                    color = dot,
                    radius = 4.dp.toPx(),
                    center = Offset(tgtX.coerceIn(4.dp.toPx(), size.width - 4.dp.toPx()), size.height / 2f),
                )
            }
        }
    }
}
