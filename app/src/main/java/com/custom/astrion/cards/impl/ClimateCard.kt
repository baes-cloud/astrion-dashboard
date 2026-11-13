package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.ui.PowerPill
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
        val isOff = mode == "off"
        // One power button that turns it on as well as off.
        fun togglePower() {
            ctx.client.callService(ServiceCall("climate", if (isOff) "turn_on" else "turn_off", entityId))
        }
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .dimIfUnavailable(unavailable)
                .clip(RoundedCornerShape(20.dp))
                .background(AstrionTheme.cardBg)
                // Tightened so the Climate page (this card plus three blinds)
                // fits one screen without scrolling, under the clock band.
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            // Header: name over what it's doing, and a power toggle. Off is
            // a normal state, not an error, so it's neutral; on is teal.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(name, color = AstrionTheme.colors.onSurface, fontSize = AstrionTheme.size.title, fontWeight = FontWeight.SemiBold)
                    if (!unavailable) {
                        Text(
                            if (isOff) "Off"
                            else listOfNotNull(mode.humanise(), fanMode?.let { "fan ${it.humanise().lowercase()}" }).joinToString(" · "),
                            color = if (isOff) AstrionTheme.textSecondary else AstrionTheme.accent,
                            fontSize = AstrionTheme.size.small,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
                PowerPill(on = !isOff, name = name, enabled = live) { togglePower() }
            }

            // Setpoint with steppers
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Stepper(Icons.Filled.Remove, "Lower target temperature", live) {
                    nudge(-step)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        shownTarget?.let { "${trim(it)}°" } ?: "—",
                        color = if (unavailable) AstrionTheme.unavailable else AstrionTheme.textPrimary,
                        // Syne's bold cut is very wide and odd in numerals;
                        // the lighter cut matches the weather temperature.
                        fontFamily = AstrionTheme.headingFont, fontSize = AstrionTheme.size.readout,
                        fontWeight = FontWeight.Light,
                    )
                    // The steppers silently no-op when there is no target, so
                    // an unreachable aircon used to look like a working one
                    // showing a dash. Say so instead.
                    if (unavailable) {
                        UnavailableLabel(AstrionTheme.size.small)
                    } else {
                        Text(
                            current?.let { "Now ${trim(it)}°" } ?: "",
                            color = AstrionTheme.textSecondary,
                            fontSize = AstrionTheme.size.small,
                        )
                    }
                }

                Stepper(Icons.Filled.Add, "Raise target temperature", live) {
                    nudge(step)
                }
            }

            // Two segmented rows, each captioned: the rows used to be two
            // identical lines of chips, and "Auto" could be a mode or a speed.
            // "off" is the power pill's job, so it isn't a segment.
            val shownModes = modes.filter { it != "off" }.take(4)
            if (shownModes.isNotEmpty()) {
                RowCaption("Mode")
                Segmented {
                    shownModes.forEach { m ->
                        ModeChip(
                            label = modeLabel(m),
                            selected = m == mode,
                            modifier = Modifier.weight(1f),
                            enabled = live,
                        ) { setMode(m) }
                    }
                }
            }

            if (fanModes.isNotEmpty()) {
                RowCaption("Fan speed")
                Segmented {
                    fanModes.forEach { f ->
                        ModeChip(
                            label = f.humanise(),
                            selected = fanMode?.equals(f, ignoreCase = true) == true,
                            modifier = Modifier.weight(1f),
                            enabled = live,
                        ) { setFan(f) }
                    }
                }
            }
        }
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
                .size(46.dp)
                .clip(CircleShape)
                .background(AstrionTheme.controlBg)
                .tap(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = description, tint = AstrionTheme.textOnControl)
        }
    }

    /** Four across a 349 dp panel: the long HA names don't fit a segment. */
    private fun modeLabel(mode: String): String = when (mode) {
        "fan_only" -> "Fan"
        "heat_cool" -> "Auto"
        else -> mode.humanise()
    }

    @Composable
    private fun RowCaption(text: String) {
        Text(
            text.uppercase(),
            color = AstrionTheme.textSecondary,
            fontSize = AstrionTheme.size.label,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(start = 2.dp, top = 1.dp),
        )
    }

    /** One control, not four buttons: the segments share a sunken track. */
    @Composable
    private fun Segmented(content: @Composable RowScope.() -> Unit) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(14.dp))
                .background(AstrionTheme.trackBg)
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            content = content,
        )
    }

    @Composable
    private fun ModeChip(
        label: String,
        selected: Boolean,
        modifier: Modifier,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        Box(
            modifier = modifier
                // 42dp in a 3dp track: a 48dp row to hit.
                .height(42.dp)
                .clip(RoundedCornerShape(11.dp))
                // accentStrong is a darkened 0xFF4C6EF5 — white 13sp on the
                // original measured 4.32:1, the only real contrast failure in
                // the app, and it was on the selected chip.
                .background(if (selected) AstrionTheme.accentStrong else Color.Transparent)
                .tap(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label,
                color = if (selected) Color.White else AstrionTheme.textSecondary,
                fontSize = AstrionTheme.size.small,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
