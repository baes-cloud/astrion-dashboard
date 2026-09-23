package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardContext
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionButton
import com.custom.astrion.ui.AstrionSheet
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.ChoiceChip
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.Tone
import com.custom.astrion.ui.TouchTarget
import com.custom.astrion.ui.UnavailableBadge
import com.custom.astrion.ui.liveOrDim
import com.custom.astrion.ui.rememberAction
import com.custom.astrion.ui.rememberOptimistic
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/** Colour-capable modes in HA's `supported_color_modes`. */
internal val COLOR_MODES = setOf("hs", "rgb", "rgbw", "rgbww", "xy")

/** 10 swatches (colour lights) — unchanged from the original popup. */
private val SWATCHES = listOf(
    Triple(244, 67, 54), Triple(255, 152, 0), Triple(255, 235, 59),
    Triple(76, 175, 80), Triple(0, 188, 212),
    Triple(33, 150, 243), Triple(156, 39, 176), Triple(233, 30, 99),
    Triple(255, 182, 193), Triple(255, 255, 255),
)

/** Colour-temperature presets, warm → cool. */
private val TEMPS = listOf("Candle" to 2200, "Warm" to 2700, "Neutral" to 4000, "Cool" to 5500)

/** 0..1 brightness of a light (0 when off). */
internal fun lightLevel(e: EntityState?): Float {
    if (e?.isOn != true) return 0f
    val b = e.attrInt("brightness") ?: return 1f
    return (b / 255f).coerceIn(0f, 1f)
}

/** The light's live colour, if it reports one. */
internal fun lightColor(e: EntityState?): Color? {
    val rgb = e?.attr("rgb_color") as? JsonArray ?: return null
    if (rgb.size < 3) return null
    fun ch(i: Int) = (rgb[i] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(0, 255)
    val r = ch(0) ?: return null
    val g = ch(1) ?: return null
    val b = ch(2) ?: return null
    return Color(r, g, b)
}

/** brightness_pct for a slider fraction; near-zero floors to 5 % (off is the power button's job). */
internal fun brightnessPct(fraction: Float): Int {
    val pct = (fraction.coerceIn(0f, 1f) * 100).roundToInt()
    return if (pct < 5) 5 else pct
}

internal fun rgbCall(entityId: String, r: Int, g: Int, b: Int) = ServiceCall(
    "light", "turn_on", entityId,
    mapOf("rgb_color" to JsonArray(listOf(JsonPrimitive(r), JsonPrimitive(g), JsonPrimitive(b)))),
)

/**
 * Light detail sheet (long-press a light anywhere): big vertical brightness
 * pill, a power toggle, colour-temperature presets and — for colour lights —
 * ten swatches.
 *
 * An in-window sheet, not a Dialog: the hardware buttons keep working while
 * it's open, and BACK closes it. Reads the entity live, so it tracks changes
 * made elsewhere while open. Near-bottom taps on the pill floor at 5 % —
 * turning the light off is the power button's job, never a mis-tap's.
 */
@Composable
fun LightDetailSheet(entityId: String, ctx: CardContext, onClose: () -> Unit) {
    val e = ctx.entity(entityId)
    val name = e?.friendlyName ?: entityId
    val unavailable = e == null || e.isUnavailable
    val live = !unavailable && ctx.connected

    val actualOn = e?.isOn == true
    val onOpt = rememberOptimistic(actualOn)
    val on = onOpt.show(actualOn)
    val level = lightLevel(e)
    var dragLevel by remember(level) { mutableStateOf(level) }
    val action = rememberAction(ctx)

    val colorModes = e?.attrStringList("supported_color_modes") ?: emptyList()
    val hasColor = colorModes.any { it in COLOR_MODES }
    val hasTemp = colorModes.contains("color_temp") || hasColor
    val fillColor = lightColor(e) ?: AstrionTheme.lightNeutralFill

    fun commit(fraction: Float) {
        onOpt.set(true)
        action.run(
            ServiceCall.of("light", "turn_on", entityId, "brightness_pct" to brightnessPct(fraction)),
            onFail = { onOpt.clear() },
        )
    }

    AstrionSheet(
        onDismiss = onClose,
        title = name,
        subtitle = when {
            unavailable -> null
            !on -> "Off"
            else -> "${(dragLevel * 100).roundToInt()}%"
        },
    ) {
        if (unavailable) UnavailableBadge()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(Space.l),
        ) {
            BrightnessPill(
                level = if (on) dragLevel else 0f,
                fill = fillColor,
                enabled = live,
                failed = action.failed,
                onPreview = { dragLevel = it },
                onCommit = { commit(it) },
            )
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                AstrionButton(
                    onClick = {
                        val want = !on
                        onOpt.set(want)
                        action.run(
                            ServiceCall("light", if (want) "turn_on" else "turn_off", entityId),
                            onFail = { onOpt.clear() },
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                    label = if (on) "On" else "Off",
                    icon = Icons.Filled.PowerSettingsNew,
                    tone = if (on) Tone.On else Tone.Neutral,
                    enabled = live,
                    pending = action.busy,
                    description = if (on) "$name is on, turn off" else "$name is off, turn on",
                )
                if (hasTemp) {
                    TEMPS.chunked(2).forEach { pair ->
                        Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                            pair.forEach { (label, k) ->
                                ChoiceChip(
                                    label = label,
                                    selected = false,
                                    onClick = {
                                        onOpt.set(true)
                                        action.run(
                                            ServiceCall.of("light", "turn_on", entityId, "color_temp_kelvin" to k),
                                            onFail = { onOpt.clear() },
                                        )
                                    },
                                    modifier = Modifier.weight(1f),
                                    enabled = live,
                                )
                            }
                        }
                    }
                }
            }
        }
        if (hasColor) {
            SWATCHES.chunked(5).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    row.forEach { (r, g, b) ->
                        TouchTarget(size = 52.dp, enabled = live, onClick = {
                            onOpt.set(true)
                            action.run(rgbCall(entityId, r, g, b), onFail = { onOpt.clear() })
                        }) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .liveOrDim(live)
                                    .clip(CircleShape)
                                    .background(Color(r, g, b))
                                    .semantics { contentDescription = "Colour $r $g $b" },
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Vertical brightness pill: drag to preview, release to commit; tap to set.
 * Fill rises from the bottom in the light's own colour.
 */
@Composable
private fun BrightnessPill(
    level: Float,
    fill: Color,
    enabled: Boolean,
    failed: Boolean,
    onPreview: (Float) -> Unit,
    onCommit: (Float) -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val preview by rememberUpdatedState(onPreview)
    val commit by rememberUpdatedState(onCommit)
    var last by remember { mutableStateOf(level) }
    val shape = RoundedCornerShape(Radius.sheet)
    Box(
        modifier = Modifier
            .width(112.dp)
            .height(210.dp)
            .liveOrDim(enabled)
            .clip(shape)
            .background(AstrionTheme.trackBg)
            .then(if (failed) Modifier.border(2.dp, AstrionTheme.danger, shape) else Modifier)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(
                    onDragEnd = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        commit(last)
                    },
                ) { change, _ ->
                    last = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                    preview(last)
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    val f = (1f - offset.y / size.height).coerceIn(0f, 1f)
                    last = f
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    preview(f)
                    commit(f)
                }
            }
            .semantics { contentDescription = "Brightness ${(level * 100).roundToInt()} percent" },
    ) {
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .fillMaxHeight(level.coerceIn(0.02f, 1f))
                .background(fill),
        )
    }
}
