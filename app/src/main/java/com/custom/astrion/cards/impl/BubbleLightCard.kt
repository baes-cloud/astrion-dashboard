package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
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
        val name = config.string("name") ?: e?.friendlyName ?: entityId

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
            val pct = (fraction.coerceIn(0f, 1f) * 100).roundToInt()
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
        val iconBg = if (on) Color(0xFFFFC24B) else Color(0xFF33525E)
        val iconTint = if (on) Color(0xFF241A00) else Color(0xFFB6C9CE)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1B343D))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Top row: icon toggle, name/state, quick colour presets.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(iconBg)
                        .pointerInput(entityId, dimmable) {
                            detectTapGestures(
                                onTap = { ctx.client.toggle(entityId) },
                                onLongPress = if (dimmable) { { showDetail = true } } else null,
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = iconTint)
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        name,
                        color = Color(0xFFE6F0F1),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        if (on) (if (dimmable) "${(dragLevel * 100).roundToInt()}%" else "On") else "Off",
                        color = Color(0xFF93AFB6),
                        fontSize = 12.sp,
                    )
                }
                if (on && hasColor) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        presets.forEach { c ->
                            Box(
                                modifier = Modifier
                                    .size(22.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .clickable { setColor(c) },
                            )
                        }
                    }
                }
            }

            // Dedicated brightness slider — coloured fill + white thumb.
            if (dimmable) BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    // Leave a dead-zone gutter on the right so a vertical
                    // scroll gesture starting near the edge of the screen
                    // can't be mistaken for a horizontal brightness drag.
                    .padding(end = 28.dp)
                    .height(28.dp)
                    .pointerInput(entityId) {
                        detectHorizontalDragGestures(
                            onDragEnd = { commit(dragLevel) },
                        ) { change, _ ->
                            dragLevel = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    }
                    .pointerInput(entityId) {
                        detectTapGestures { offset ->
                            val frac = (offset.x / size.width).coerceIn(0f, 1f)
                            dragLevel = frac
                            commit(frac)
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(10.dp)
                        .clip(RoundedCornerShape(5.dp))
                        .background(Color(0xFF152B33)),
                ) {
                    if (on) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(dragLevel.coerceAtLeast(0.02f))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(5.dp))
                                .background(fillColor),
                        )
                    }
                }
                // Thumb — a white circle riding the fill boundary.
                val thumbSize = 20.dp
                val thumbX = (maxWidth * dragLevel - thumbSize / 2).coerceIn(0.dp, maxWidth - thumbSize)
                Box(
                    modifier = Modifier
                        .padding(start = thumbX)
                        .size(thumbSize)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            }
        }

        if (showDetail) {
            LightDetailDialog(
                entityId = entityId,
                e = e,
                client = ctx.client,
                onClose = { showDetail = false },
            )
        }
    }
}
