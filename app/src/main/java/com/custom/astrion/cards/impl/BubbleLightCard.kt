package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.TouchTarget
import com.custom.astrion.ui.UnavailableLabel
import com.custom.astrion.ui.dimIfUnavailable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt

/**
 * Light control card: icon + name + state on top, a dedicated brightness
 * slider (coloured fill, white thumb) below, and — when the light supports
 * colour — three quick colour-preset dots at the top right.
 *
 * Long-press the icon row opens the full colour/brightness detail popup
 * (swatches + colour-temp presets).
 *
 * Uses:
 *   light.toggle
 *   light.turn_on { brightness_pct }
 *   light.turn_on { rgb_color }   (quick presets)
 *
 * Config shape:
 *   CardConfig("bubble_light", mapOf(
 *       "entity_id" to "light.kitchen",
 *       "name" to "Kitchen",
 *   ))
 */
class BubbleLightCard : CardRenderer {
    override val type = "bubble_light"

    // Fixed quick-preset colours shown on any colour-capable light.
    private val presets = listOf(
        Color(0xFF9B59B6), // purple
        Color(0xFF4A90D9), // blue
        Color(0xFFFFCBA4), // peach
    )

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entities[entityId]
        val on = e?.isOn == true
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val haptics = LocalHapticFeedback.current

        // brightness attribute is 0..255; convert to 0..1 fraction.
        val brightness = e?.attrInt("brightness")
        val level: Float = when {
            !on -> 0f
            brightness != null -> (brightness / 255f).coerceIn(0f, 1f)
            else -> 1f
        }

        // Local drag state so the fill responds instantly, then commits to HA.
        var dragLevel by remember(level) { mutableStateOf(level) }

        // Long-press opens the colour/brightness detail popup.
        var showDetail by remember { mutableStateOf(false) }

        fun commit(fraction: Float) {
            var pct = (fraction.coerceIn(0f, 1f) * 100).roundToInt()
            // Floor a near-zero result rather than treating it as "off". The
            // bar is the widest target on the page and a tap anywhere on it
            // committed instantly, so a mis-tap at the left edge — or a scroll
            // that started slightly sideways — used to kill the light outright,
            // with no undo. Dimming to 5% is recoverable; off is a surprise.
            // The bulb icon remains the deliberate off switch.
            if (pct in 1..4) pct = 5
            if (pct <= 0) {
                ctx.client.callService(ServiceCall("light", "turn_off", entityId))
            } else {
                ctx.client.callService(
                    ServiceCall.of("light", "turn_on", entityId, "brightness_pct" to pct)
                )
            }
        }

        fun setColor(c: Color) {
            ctx.client.callService(
                ServiceCall.of(
                    "light", "turn_on", entityId,
                    "rgb_color" to JsonArray(
                        listOf(
                            JsonPrimitive((c.red * 255).roundToInt()),
                            JsonPrimitive((c.green * 255).roundToInt()),
                            JsonPrimitive((c.blue * 255).roundToInt()),
                        )
                    ),
                )
            )
        }

        // "dimmable": false → on/off only (no slider, no colour, no detail popup).
        val dimmable = config.bool("dimmable", true)
        val colorModes = e?.attrStringList("supported_color_modes") ?: emptyList()
        val hasColor = dimmable && colorModes.any { it in listOf("hs", "rgb", "rgbw", "rgbww", "xy") }

        // Reflect the light's real colour when it reports one (rgb_color);
        // otherwise a neutral blue-grey.
        val rgb = e?.attr("rgb_color") as? JsonArray
        val lightColor: Color? = if (on && rgb != null && rgb.size >= 3) {
            fun ch(i: Int) = (rgb[i] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(0, 255)
            val r = ch(0); val g = ch(1); val b = ch(2)
            if (r != null && g != null && b != null) Color(r, g, b) else null
        } else null

        val fillColor = lightColor ?: Color(0xFF6E9BD9) // neutral blue fallback
        val iconBg = when {
            unavailable -> Color(0xFF2C3E4E)
            on -> AstrionTheme.on
            else -> Color(0xFF33525E)
        }
        val iconTint = when {
            unavailable -> AstrionTheme.unavailable
            on -> AstrionTheme.onBg
            else -> Color(0xFFB6C9CE)
        }

        // The pill IS the slider: brightness fills it left-to-right and you
        // drag anywhere along it, rather than aiming at a separate track.
        // Trade-off: a horizontal drag on a pill sets brightness, so page
        // swipes come from the dots, the page edges, or the shortcut keys.
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .dimIfUnavailable(unavailable)
                .height(64.dp)
                .clip(RoundedCornerShape(32.dp))
                .background(AstrionTheme.cardBg)
                .then(
                    when {
                        !live -> Modifier
                        dimmable -> Modifier
                            .pointerInput(entityId) {
                                detectHorizontalDragGestures(
                                    onDragEnd = {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        commit(dragLevel)
                                    },
                                ) { change, _ ->
                                    dragLevel = (change.position.x / size.width).coerceIn(0f, 1f)
                                }
                            }
                            .pointerInput(entityId) {
                                detectTapGestures { offset ->
                                    val frac = (offset.x / size.width).coerceIn(0f, 1f)
                                    dragLevel = frac
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    commit(frac)
                                }
                            }
                        // On/off pill: no slider, so a tap anywhere toggles.
                        else -> Modifier.pointerInput(entityId) {
                            detectTapGestures {
                                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                ctx.client.toggle(entityId)
                            }
                        }
                    }
                ),
        ) {
            // Brightness fill — the pill's own background, tinted by the light.
            if (on && dimmable) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(dragLevel.coerceIn(0.02f, 1f))
                        .background(fillColor.copy(alpha = 0.45f)),
                )
            }

            Row(
                modifier = Modifier
                    .matchParentSize()
                    .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(iconBg)
                        .pointerInput(entityId, dimmable, live) {
                            if (!live) return@pointerInput
                            detectTapGestures(
                                onTap = {
                                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                    ctx.client.toggle(entityId)
                                },
                                onLongPress = if (dimmable) {
                                    {
                                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                        showDetail = true
                                    }
                                } else null,
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    // Filled when on, outlined when off — so on/off survives
                    // without relying on colour alone.
                    Icon(
                        if (on) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                        contentDescription = if (on) "$name, on" else "$name, off",
                        tint = iconTint,
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        color = AstrionTheme.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (unavailable) {
                        UnavailableLabel()
                    } else {
                        Text(
                            if (on) (if (dimmable) "${(dragLevel * 100).roundToInt()}%" else "On") else "Off",
                            color = AstrionTheme.textSecondary,
                            fontSize = AstrionTheme.label,
                        )
                    }
                }
                if (on && hasColor && live) {
                    // The dots stay 22dp visually but each sits in a 44dp
                    // target. They live in the top-right corner that a right
                    // thumb passes through on its way up the card, three of
                    // them within 90dp, and each one recolours a light
                    // instantly with no undo — at 220dpi, 22dp is under 5mm
                    // against a ~10mm thumb contact patch.
                    Row {
                        presets.forEach { c ->
                            TouchTarget(size = 44.dp, onClick = { setColor(c) }) {
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(c),
                                )
                            }
                        }
                    }
                }
            }

        }

        if (showDetail) {
            val overlay = com.custom.astrion.ui.LocalOverlay.current
            androidx.compose.runtime.LaunchedEffect(Unit) {
                overlay.show { LightDetailSheet(entityId, ctx, onClose = { overlay.dismiss() }) }
                showDetail = false
            }
        }
    }
}
