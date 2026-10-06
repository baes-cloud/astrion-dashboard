package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.InWindowDialog
import com.custom.astrion.ui.humanise
import com.custom.astrion.ui.tap
import kotlin.math.ceil
import kotlin.math.roundToInt

/** HA fan feature bit for oscillation. */
private const val FAN_OSCILLATE = 2

/**
 * Speeds the fan has, from its `percentage_step` (12.5 → 8). A fan that only
 * takes percentages (step 1) gets ten 10% steps rather than a hundred.
 */
internal fun fanSpeedCount(e: EntityState?): Int {
    val step = e?.attrDouble("percentage_step")?.takeIf { it > 0 } ?: 10.0
    return (100.0 / step).roundToInt().coerceIn(1, 100).let { if (it > 10) 10 else it }
}

/** Speed 1..[count] from the fan's percentage, which it keeps while off. */
internal fun fanSpeedOf(e: EntityState?, count: Int): Int {
    val pct = e?.attrDouble("percentage") ?: return 1
    return ceil(pct * count / 100.0 - 1e-6).toInt().coerceIn(1, count)
}

/**
 * Percentage for speed [speed] of [count], rounded down as HA's own
 * ranged_value_to_percentage does. Rounding up lands on the next speed: 38%
 * of an 8-speed fan is speed 4, 37% is speed 3.
 */
internal fun fanPercentFor(speed: Int, count: Int): Int = speed * 100 / count

/** "1h 20m left", "45 min left". */
internal fun formatMinutesLeft(minutes: Double): String {
    val m = minutes.roundToInt()
    return if (m >= 60) "${m / 60}h ${m % 60}m left" else "$m min left"
}

/**
 * Everything the fan does, from the fan row: speed as a row of bars, power,
 * the fan's preset modes, oscillation (plus a second swing axis if the device
 * has one as a switch), and its sleep timer. Optional extras come from the
 * fan card's config: `swing_entity`, `timer_entity`, `timer_left_entity`,
 * `temperature_entity`, `problem_entity`.
 */
@Composable
fun FanDetailDialog(entityId: String, name: String, config: CardConfig, ctx: CardContext, onClose: () -> Unit) {
    val e = ctx.entity(entityId)
    val on = e?.isOn == true
    val live = e != null && !e.isUnavailable && ctx.connected
    val speeds = fanSpeedCount(e)
    val speed = fanSpeedOf(e, speeds)
    // The bar you tapped shows at once; HA's echo replaces it.
    var picked by remember(speed, on) { mutableStateOf<Int?>(null) }
    val level = picked ?: if (on) speed else 0

    val presets = e?.attrStringList("preset_modes").orEmpty()
    val preset = e?.attrString("preset_mode")
    val canOscillate = ((e?.attrInt("supported_features") ?: 0) and FAN_OSCILLATE) != 0
    val oscillating = e?.attrString("oscillating") == "true"
    val swingId = config.string("swing_entity")
    val swing = swingId?.let { ctx.entity(it) }
    val timerId = config.string("timer_entity")
    val timer = timerId?.let { ctx.entity(it) }
    val left = config.string("timer_left_entity")?.let { ctx.entity(it)?.state?.toDoubleOrNull() }?.takeIf { it > 0 }
    val temperature = config.string("temperature_entity")?.let { ctx.entity(it)?.state?.toDoubleOrNull() }
    val fault = config.string("problem_entity")?.let { ctx.entity(it)?.isOn } == true

    fun call(domain: String, service: String, target: String, vararg data: Pair<String, Any?>) {
        ctx.client.callService(ServiceCall.of(domain, service, target, *data))
    }

    InWindowDialog(onDismissRequest = onClose) {
        Column(
            modifier = Modifier
                .width(300.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF243140))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        name, color = AstrionTheme.textPrimary, fontFamily = AstrionTheme.headingFont,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        listOfNotNull(
                            if (level > 0) "Speed $level of $speeds" else "Off",
                            temperature?.let { "${it.roundToInt()}° here" },
                        ).joinToString(" · "),
                        color = AstrionTheme.textSecondary, fontSize = 13.sp,
                    )
                    if (fault) Text("The fan reports a fault", color = AstrionTheme.danger, fontSize = 12.sp)
                }
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(if (on) AstrionTheme.accent else AstrionTheme.controlBg)
                        .tap(enabled = live) { ctx.client.toggle(entityId) },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.PowerSettingsNew,
                        contentDescription = if (on) "Turn $name off" else "Turn $name on",
                        tint = if (on) Color(0xFF10201D) else AstrionTheme.textOnControl,
                    )
                }
            }

            // Off, the bars show faintly the speed it will come back on at.
            SpeedBars(speeds, level, resume = if (on) 0 else speed, live) { s ->
                picked = s
                call("fan", "set_percentage", entityId, "percentage" to fanPercentFor(s, speeds))
            }

            if (presets.isNotEmpty()) {
                Section("Mode") {
                    presets.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            row.forEach { p ->
                                Chip(p.humanise(), p == preset, live, Modifier.weight(1f)) {
                                    call("fan", "set_preset_mode", entityId, "preset_mode" to p)
                                }
                            }
                            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                        }
                    }
                }
            }

            if (canOscillate || swing != null) {
                Section("Swing") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (canOscillate) {
                            Chip("Side to side", oscillating, live, Modifier.weight(1f), Icons.Filled.SwapHoriz) {
                                call("fan", "oscillate", entityId, "oscillating" to !oscillating)
                            }
                        }
                        if (swing != null && swingId != null) {
                            Chip(
                                "Up and down", swing.isOn, ctx.connected && !swing.isUnavailable,
                                Modifier.weight(1f), Icons.Filled.SwapVert,
                            ) { ctx.client.toggle(swingId) }
                        }
                    }
                }
            }

            if (timer != null && timerId != null) {
                Section("Timer", trailing = left?.let { formatMinutesLeft(it) }) {
                    Row(
                        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        timer.attrStringList("options").forEach { opt ->
                            Chip(
                                if (opt == "cancel") "Off" else opt, timer.state == opt,
                                ctx.connected && !timer.isUnavailable, Modifier,
                            ) { call("select", "select_option", timerId, "option" to opt) }
                        }
                    }
                }
            }
        }
    }
}

/** Speed as rising bars; tap one to set that speed (and turn the fan on). */
@Composable
private fun SpeedBars(count: Int, level: Int, resume: Int, enabled: Boolean, onPick: (Int) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().height(64.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        for (s in 1..count) {
            // The whole column is the target; the bar inside rises with speed.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .tap(enabled = enabled) { onPick(s) },
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(0.3f + 0.7f * s / count)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            when {
                                s <= level -> AstrionTheme.accent
                                s <= resume -> AstrionTheme.accent.copy(alpha = 0.3f)
                                else -> AstrionTheme.controlSunken
                            }
                        ),
                )
            }
        }
    }
}

@Composable
private fun Section(title: String, trailing: String? = null, content: @Composable ColumnScope.() -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                title.uppercase(), color = AstrionTheme.textSecondary, fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
            )
            if (trailing != null) Text(trailing, color = AstrionTheme.accent, fontSize = 11.sp)
        }
        content()
    }
}

@Composable
private fun Chip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    modifier: Modifier,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .height(36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(if (selected) AstrionTheme.accentStrong else AstrionTheme.controlSunken)
            .tap(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val ink = if (selected) Color.White else AstrionTheme.textSecondary
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(label, color = ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}
