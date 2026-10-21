package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.custom.astrion.ui.HitTarget
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.Glyphs
import com.custom.astrion.ui.UnavailableLabel
import com.custom.astrion.ui.dimIfUnavailable
import com.custom.astrion.ui.humanise
import com.custom.astrion.ui.tap
import kotlinx.coroutines.delay

/**
 * Climate / thermostat card.
 *
 * Big setpoint readout with +/- steppers, current temperature, and a row of
 * HVAC mode chips read live from the entity's `hvac_modes` attribute.
 *
 * Uses:
 *   climate.set_temperature { entity_id, temperature }
 *   climate.set_hvac_mode   { entity_id, hvac_mode }
 *
 * Config shape:
 *   CardConfig("climate", mapOf(
 *       "entity_id" to "climate.lounge",
 *       "step" to 0.5,          // optional, default 0.5
 *   ))
 */
/** Quiet time after the last setpoint tap before it's sent. */
private const val SETPOINT_SETTLE_MS = 900L
/** How long the local setpoint overrides HA's while waiting for the echo. */
private const val SETPOINT_ECHO_MS = 6_000L

class ClimateCard : CardRenderer {
    override val type = "climate"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        // Prefer the entity's real step; a 1° aircon ignores 0.5° changes and
        // the down button looks broken (25.5 rounds back to 26).
        val step = e?.attrDouble("target_temp_step")
            ?: (config.options["step"] as? Number)?.toDouble()
            ?: 1.0
        val minT = e?.attrDouble("min_temp")
        val maxT = e?.attrDouble("max_temp")

        val target = e?.attrDouble("temperature")
        val current = e?.attrDouble("current_temperature")
        val mode = e?.state ?: "off"
        val modes = e?.attrStringList("hvac_modes") ?: emptyList()
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val fanMode = e?.attrString("fan_mode")
        val fanModes = config.stringList("fan_modes").ifEmpty { listOf("low", "medium", "high", "auto") }

        fun setTemp(t: Double) {
            val clamped = t.coerceIn(minT ?: t, maxT ?: t)
            ctx.client.callService(
                ServiceCall.of("climate", "set_temperature", entityId, "temperature" to clamped)
            )
        }
        // Setpoint taps settle locally and go to HA once they stop. Each tap
        // used to send its own set_temperature, computed from HA's last echo:
        // three quick taps sent the same +1 three times, and the bursts
        // tripped the Fujitsu cloud's rate limit (16 "429"s in 10 days).
        var pendingTarget by remember(entityId) { mutableStateOf<Double?>(null) }
        LaunchedEffect(pendingTarget) {
            val t = pendingTarget ?: return@LaunchedEffect
            delay(SETPOINT_SETTLE_MS)
            setTemp(t)
            // If HA never echoes it (rejected, or rounded), stop overriding.
            delay(SETPOINT_ECHO_MS)
            pendingTarget = null
        }
        // HA has caught up (or the taps came back to where it was): show HA's value.
        LaunchedEffect(target) {
            if (pendingTarget != null && target == pendingTarget) pendingTarget = null
        }
        fun nudge(delta: Double) {
            val from = pendingTarget ?: target ?: return
            val next = (from + delta).let { t -> t.coerceIn(minT ?: t, maxT ?: t) }
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
        fun turnOff() {
            ctx.client.callService(ServiceCall("climate", "turn_off", entityId))
        }

        val isOff = mode == "off"
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .dimIfUnavailable(unavailable)
                .clip(RoundedCornerShape(20.dp))
                .background(AstrionTheme.cardBg)
                // Tightened so the Climate page (this card plus three blinds)
                // fits one screen without scrolling.
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // Header: name and what it's doing, then power.
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.title, fontWeight = FontWeight.Bold)
                    Text(
                        when {
                            unavailable -> "Unavailable"
                            isOff -> "Off"
                            else -> listOfNotNull(
                                (e?.attrString("hvac_action") ?: mode).humanise(),
                                shownTarget?.let { "to ${trim(it)}°" },
                            ).joinToString(" ")
                        },
                        color = AstrionTheme.textSecondary,
                        fontSize = AstrionTheme.size.small,
                    )
                }
                HitTarget(
                    visual = 38.dp,
                    onClick = {
                        if (isOff) ctx.client.callService(ServiceCall("climate", "turn_on", entityId)) else turnOff()
                    },
                    enabled = live,
                    face = Modifier.clip(CircleShape).background(if (isOff) AstrionTheme.raised else AstrionTheme.accentStrong),
                ) {
                    Icon(
                        Glyphs.power,
                        contentDescription = if (isOff) "Turn on" else "Turn off",
                        tint = if (isOff) AstrionTheme.textSecondary else AstrionTheme.textPrimary,
                        modifier = Modifier.size(19.dp),
                    )
                }
            }

            // Setpoint, big in Antonio, with steppers either side.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Stepper(Glyphs.minus, "Lower target temperature", live) {
                    nudge(-step)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Row {
                        Text(
                            shownTarget?.let { trim(it) } ?: "—",
                            color = if (unavailable) AstrionTheme.unavailable else AstrionTheme.textPrimary,
                            fontFamily = AstrionTheme.readoutFont,
                            fontWeight = FontWeight.ExtraLight,
                            fontSize = AstrionTheme.size.readoutLarge,
                            maxLines = 1,
                        )
                        if (shownTarget != null) Text(
                            "°",
                            color = AstrionTheme.textPrimary,
                            fontFamily = AstrionTheme.readoutFont,
                            fontWeight = FontWeight.Light,
                            fontSize = AstrionTheme.size.displayLg,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    // The steppers silently no-op when there is no target, so
                    // an unreachable aircon used to look like a working one
                    // showing a dash. Say so instead.
                    if (unavailable) {
                        UnavailableLabel(AstrionTheme.size.small)
                    } else {
                        Text(
                            current?.let { "Now ${trim(it)}° inside" } ?: "",
                            color = AstrionTheme.textSecondary,
                            fontSize = AstrionTheme.size.small,
                        )
                    }
                }

                Stepper(Glyphs.plus, "Raise target temperature", live) {
                    nudge(step)
                }
            }

            // HVAC modes (off is the power button's job), icon over word.
            val shownModes = modes.filter { it != "off" }.take(4)
            if (shownModes.isNotEmpty()) {
                Segmented {
                    shownModes.forEach { m ->
                        ModeChip(
                            label = m.humanise().replace(" only", ""),
                            icon = modeGlyph(m),
                            selected = m == mode,
                            modifier = Modifier.weight(1f),
                            enabled = live,
                        ) { setMode(m) }
                    }
                }
            }

            // Fan modes
            if (fanModes.isNotEmpty()) {
                Segmented {
                    fanModes.forEach { f ->
                        ModeChip(
                            label = f.humanise(),
                            icon = null,
                            selected = fanMode?.equals(f, ignoreCase = true) == true,
                            modifier = Modifier.weight(1f),
                            enabled = live,
                        ) { setFan(f) }
                    }
                }
            }
        }
    }

    private fun modeGlyph(mode: String) = when (mode) {
        "cool" -> Glyphs.snow
        "heat" -> Glyphs.flame
        "fan_only" -> Glyphs.wind
        "dry" -> Glyphs.drop
        else -> Glyphs.thermo
    }

    /** A recessed track holding a row of choices. */
    @Composable
    private fun Segmented(content: @Composable RowScope.() -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(13.dp))
                .background(AstrionTheme.colors.segmentTrack)
                .border(1.dp, AstrionTheme.colors.hairline, RoundedCornerShape(13.dp))
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            content = content,
        )
    }

    private fun trim(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()

    @Composable
    private fun Stepper(
        icon: androidx.compose.ui.graphics.vector.ImageVector,
        description: String? = null,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(AstrionTheme.raised)
                .tap(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = AstrionTheme.textPrimary, modifier = Modifier.size(20.dp))
        }
    }

    @Composable
    private fun ModeChip(
        label: String,
        icon: androidx.compose.ui.graphics.vector.ImageVector?,
        selected: Boolean,
        modifier: Modifier,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        Column(
            modifier = modifier
                .height(if (icon != null) 44.dp else 36.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(if (selected) AstrionTheme.accentStrong else Color.Transparent)
                .tap(enabled = enabled, onClick = onClick),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(2.dp, Alignment.CenterVertically),
        ) {
            val ink = if (selected) AstrionTheme.textPrimary else AstrionTheme.textSecondary
            if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(15.dp))
            Text(
                label,
                color = ink,
                fontSize = if (icon != null) AstrionTheme.size.label else AstrionTheme.size.small,
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}
