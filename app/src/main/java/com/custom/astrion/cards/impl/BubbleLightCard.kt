package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.LocalOverlay
import com.custom.astrion.ui.PendingSpinner
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.StateKind
import com.custom.astrion.ui.StateLine
import com.custom.astrion.ui.TouchTarget
import com.custom.astrion.ui.liveOrDim
import com.custom.astrion.ui.rememberAction
import com.custom.astrion.ui.rememberOptimistic
import com.custom.astrion.ui.tapAndHold
import kotlin.math.roundToInt

/**
 * Light pill: the pill IS the brightness slider (fill tinted by the light's
 * own colour), the bulb toggles it, long-press the bulb for the colour /
 * brightness sheet, and — for colour lights that are on — three quick colour
 * presets in 44dp targets.
 *
 * Slider: drag to preview, release to commit; a tap sets the level where you
 * tapped. Near-zero floors to 5 % — turning the light OFF is the bulb's job,
 * never a mis-tap's or a sideways scroll's.
 *
 * Everything is optimistic, spins if HA is slow, outlines red if refused.
 *
 * Config: { "type": "bubble_light", "options": {
 *     "entity_id": "light.kitchen", "name": "Kitchen", "dimmable": true } }
 * `dimmable: false` → an on/off pill: tap anywhere toggles.
 */
class BubbleLightCard : CardRenderer {
    override val type = "bubble_light"

    private val presets = listOf(AstrionTheme.presetPurple, AstrionTheme.presetBlue, AstrionTheme.presetPeach)

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entity(entityId)
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val haptics = LocalHapticFeedback.current
        val overlay = LocalOverlay.current

        val actualOn = e?.isOn == true
        val onOpt = rememberOptimistic(actualOn)
        val on = onOpt.show(actualOn)
        val level = lightLevel(e)
        var dragLevel by remember(level) { mutableStateOf(level) }
        val action = rememberAction(ctx)

        fun commit(fraction: Float) {
            onOpt.set(true)
            action.run(
                ServiceCall.of("light", "turn_on", entityId, "brightness_pct" to brightnessPct(fraction)),
                onFail = { onOpt.clear() },
            )
        }
        fun toggle() {
            onOpt.set(!on)
            action.run(ServiceCall("light", "toggle", entityId), onFail = { onOpt.clear() })
        }
        fun openDetail() {
            overlay.show { LightDetailSheet(entityId, ctx, onClose = { overlay.dismiss() }) }
        }

        val dimmable = config.bool("dimmable", true)
        val colorModes = e?.attrStringList("supported_color_modes") ?: emptyList()
        val hasColor = dimmable && colorModes.any { it in COLOR_MODES }
        // Opaque blend of the light's colour into the pill — no alpha layer.
        val fill = lerp(AstrionTheme.cardBg, lightColor(e) ?: AstrionTheme.lightNeutralFill, 0.45f)

        val commitNow by rememberUpdatedState(::commit)
        val toggleNow by rememberUpdatedState(::toggle)
        val shape = RoundedCornerShape(32.dp)

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .clip(shape)
                .background(AstrionTheme.cardBg)
                .then(if (action.failed) Modifier.border(2.dp, AstrionTheme.danger, shape) else Modifier)
                .then(
                    when {
                        !live -> Modifier
                        dimmable -> Modifier
                            .pointerInput(entityId) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        commitNow(dragLevel)
                                    },
                                ) { change, _ ->
                                    dragLevel = (change.position.x / size.width).coerceIn(0f, 1f)
                                }
                            }
                            .pointerInput(entityId) {
                                detectTapGestures { offset ->
                                    val frac = (offset.x / size.width).coerceIn(0f, 1f)
                                    dragLevel = brightnessPct(frac) / 100f
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    commitNow(frac)
                                }
                            }
                        else -> Modifier.pointerInput(entityId) {
                            detectTapGestures {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                toggleNow()
                            }
                        }
                    }
                ),
        ) {
            if (on && dimmable) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(dragLevel.coerceIn(0.02f, 1f))
                        .background(fill),
                )
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .padding(horizontal = Space.gutter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.gutter),
            ) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .liveOrDim(live)
                        .clip(CircleShape)
                        .background(
                            when {
                                unavailable -> AstrionTheme.unavailableWell
                                on -> AstrionTheme.on
                                else -> AstrionTheme.controlBg
                            }
                        )
                        .tapAndHold(
                            enabled = live,
                            onClick = { toggle() },
                            onLongClick = { if (dimmable) openDetail() },
                        )
                        .semantics {
                            contentDescription = when {
                                unavailable -> "$name, unavailable"
                                on -> "$name, on. Tap to turn off, hold for colour"
                                else -> "$name, off. Tap to turn on, hold for colour"
                            }
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    if (action.busy) {
                        PendingSpinner(size = 20.dp, color = if (on) AstrionTheme.onBg else AstrionTheme.accent)
                    } else {
                        Icon(
                            when {
                                unavailable -> Icons.Filled.CloudOff
                                on -> Icons.Filled.Lightbulb
                                else -> Icons.Outlined.Lightbulb
                            },
                            contentDescription = null,
                            tint = when {
                                unavailable -> AstrionTheme.unavailable
                                on -> AstrionTheme.onBg
                                else -> AstrionTheme.textOnControl
                            },
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        name, style = AstrionType.title, color = AstrionTheme.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    when {
                        unavailable -> StateLine("", StateKind.Unavailable)
                        on -> StateLine(if (dimmable) "${(dragLevel * 100).roundToInt()}%" else "On", StateKind.On)
                        else -> StateLine("Off")
                    }
                }
                if (on && hasColor && live) {
                    Row {
                        presets.forEach { c ->
                            TouchTarget(size = 44.dp, onClick = {
                                action.run(rgbCall(entityId, c.channel(0), c.channel(1), c.channel(2)))
                            }) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(c)
                                        .semantics { contentDescription = "Colour preset" },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

/** 0..255 channel of a Color: 0 = red, 1 = green, 2 = blue. */
private fun Color.channel(i: Int): Int = ((when (i) {
    0 -> red
    1 -> green
    else -> blue
}) * 255).roundToInt()
