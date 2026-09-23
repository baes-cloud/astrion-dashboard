package com.custom.astrion.cards.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionCard
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.ChoiceChip
import com.custom.astrion.ui.IconAction
import com.custom.astrion.ui.PendingSpinner
import com.custom.astrion.ui.SectionLabel
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.StateKind
import com.custom.astrion.ui.StateLine
import com.custom.astrion.ui.Tone
import com.custom.astrion.ui.humanise
import com.custom.astrion.ui.rememberAction
import com.custom.astrion.ui.rememberOptimistic
import kotlinx.coroutines.delay

/**
 * Climate / aircon.
 *
 * Fixes from the audit:
 * - Setpoint taps ADD UP. Each tap used to send HA's last-echoed target ±
 *   step, so three quick taps sent the same +0.5 three times. Now the card
 *   keeps a local target, shows it immediately (in accent, with a spinner),
 *   and sends ONE set_temperature 600 ms after the last tap.
 * - "Off" reads as off: the setpoint drops to a muted colour, the line under
 *   it says "Off", no mode or fan chip is lit, and the power button is the
 *   one lit thing (tap it to turn the unit on again).
 * - Every HVAC mode shows (in rows of three, `off` left to the power
 *   button) — `take(4)` used to drop the fifth and sixth silently.
 *
 * Config: { "type": "climate", "options": {
 *     "entity_id": "climate.lounge", "name": "Aircon", "step": 0.5,
 *     "fan_modes": ["low", "medium", "high", "auto"] } }
 * Step: the entity's `target_temp_step`, else `step`, else 1.0.
 */
class ClimateCard : CardRenderer {
    override val type = "climate"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entity(entityId)
        val step = e?.attrDouble("target_temp_step")
            ?: (config.options["step"] as? Number)?.toDouble()
            ?: 1.0
        val minT = e?.attrDouble("min_temp")
        val maxT = e?.attrDouble("max_temp")
        val target = e?.attrDouble("temperature")
        val current = e?.attrDouble("current_temperature")
        val actualMode = e?.state ?: "off"
        val modes = (e?.attrStringList("hvac_modes") ?: emptyList()).filter { it != "off" }
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val actualFan = e?.attrString("fan_mode")
        val fanModes = config.stringList("fan_modes").ifEmpty { listOf("low", "medium", "high", "auto") }
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected

        val modeOpt = rememberOptimistic(actualMode)
        val mode = modeOpt.show(actualMode)
        val fanOpt = rememberOptimistic(actualFan)
        val fan = fanOpt.show(actualFan)
        val isOff = mode == "off"
        val powerAction = rememberAction(ctx)
        val modeAction = rememberAction(ctx)
        val fanAction = rememberAction(ctx)
        val tempAction = rememberAction(ctx)

        // Local setpoint while tapping; one call after the taps stop.
        var pendingTarget by remember { mutableStateOf<Double?>(null) }
        var lastTap by remember { mutableLongStateOf(0L) }
        LaunchedEffect(lastTap) {
            val want = pendingTarget ?: return@LaunchedEffect
            delay(600)
            tempAction.run(
                ServiceCall.of("climate", "set_temperature", entityId, "temperature" to want),
                onFail = { pendingTarget = null },
            )
        }
        // HA caught up (or moved elsewhere): drop the local value.
        LaunchedEffect(target) {
            if (pendingTarget != null && target == pendingTarget) pendingTarget = null
        }
        val shownTarget = pendingTarget ?: target

        fun nudge(delta: Double) {
            val base = shownTarget ?: return
            var next = base + delta
            if (minT != null) next = next.coerceAtLeast(minT)
            if (maxT != null) next = next.coerceAtMost(maxT)
            pendingTarget = next
            lastTap = System.currentTimeMillis()
        }

        AstrionCard(padding = androidx.compose.foundation.layout.PaddingValues(Space.l)) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.card)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(name, style = AstrionType.headline, color = AstrionTheme.textPrimary)
                        when {
                            unavailable -> StateLine("", StateKind.Unavailable)
                            isOff -> StateLine("Off")
                            else -> StateLine(
                                (e?.attrString("hvac_action") ?: mode).humanise(),
                                StateKind.On,
                            )
                        }
                    }
                    IconAction(
                        Icons.Filled.PowerSettingsNew,
                        if (isOff) "Turn $name on" else "Turn $name off",
                        {
                            val turnOn = isOff
                            modeOpt.set(if (turnOn) (e?.attrStringList("hvac_modes")?.firstOrNull { it != "off" } ?: "on") else "off")
                            powerAction.run(
                                ServiceCall("climate", if (turnOn) "turn_on" else "turn_off", entityId),
                                onFail = { modeOpt.clear() },
                            )
                        },
                        tone = if (isOff) Tone.Danger else Tone.On,
                        enabled = live,
                        pending = powerAction.busy,
                        failed = powerAction.failed,
                    )
                }

                // Setpoint with steppers
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconAction(
                        Icons.Filled.Remove, "Lower target temperature", { nudge(-step) },
                        size = 60.dp, enabled = live && shownTarget != null, failed = tempAction.failed,
                    )
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                shownTarget?.let { "${trim(it)}°" } ?: "—",
                                style = AstrionType.hero,
                                color = when {
                                    unavailable -> AstrionTheme.unavailable
                                    pendingTarget != null -> AstrionTheme.accent
                                    isOff -> AstrionTheme.textMuted
                                    else -> AstrionTheme.textPrimary
                                },
                            )
                            if (tempAction.busy) {
                                Spacer(Modifier.size(Space.xs))
                                PendingSpinner(size = 16.dp)
                            }
                        }
                        if (!unavailable) {
                            Text(
                                listOfNotNull(
                                    if (isOff) "Off" else null,
                                    current?.let { "now ${trim(it)}°" },
                                ).joinToString(" · ").replaceFirstChar { it.uppercase() },
                                style = AstrionType.body,
                                color = AstrionTheme.textSecondary,
                            )
                        }
                    }
                    IconAction(
                        Icons.Filled.Add, "Raise target temperature", { nudge(step) },
                        size = 60.dp, enabled = live && shownTarget != null, failed = tempAction.failed,
                    )
                }

                if (modes.isNotEmpty()) {
                    SectionLabel("Mode")
                    ChipRows(modes, selected = if (isOff) null else mode, enabled = live, busy = modeAction.busy) { m ->
                        modeOpt.set(m)
                        modeAction.run(
                            ServiceCall.of("climate", "set_hvac_mode", entityId, "hvac_mode" to m),
                            onFail = { modeOpt.clear() },
                        )
                    }
                }
                if (fanModes.isNotEmpty()) {
                    SectionLabel("Fan")
                    // While off, no fan chip is lit — the brightest thing on
                    // the page used to be the fan speed of a unit that was off.
                    ChipRows(
                        fanModes,
                        selected = if (isOff) null else fanModes.firstOrNull { it.equals(fan, ignoreCase = true) },
                        enabled = live,
                        busy = fanAction.busy,
                    ) { f ->
                        fanOpt.set(f)
                        fanAction.run(
                            ServiceCall.of("climate", "set_fan_mode", entityId, "fan_mode" to f),
                            onFail = { fanOpt.clear() },
                        )
                    }
                }
            }
        }
    }

    /** Chips in rows of up to four (three when there are 5–6, so none is dropped). */
    @Composable
    private fun ChipRows(
        options: List<String>,
        selected: String?,
        enabled: Boolean,
        busy: Boolean,
        onPick: (String) -> Unit,
    ) {
        val perRow = if (options.size <= 4) options.size else 3
        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            options.chunked(perRow.coerceAtLeast(1)).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    row.forEach { opt ->
                        ChoiceChip(
                            label = opt.humanise(),
                            selected = opt == selected,
                            onClick = { onPick(opt) },
                            modifier = Modifier.weight(1f),
                            enabled = enabled,
                            pending = busy && opt == selected,
                        )
                    }
                    repeat(perRow - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    private fun trim(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
}
