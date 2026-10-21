package com.custom.astrion.cards.impl

import com.custom.astrion.ui.AstrionTheme
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.ui.BottomSheet
import com.custom.astrion.ui.Glyphs
import androidx.compose.foundation.border
import kotlin.math.abs
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.roundToInt
import com.custom.astrion.ui.tap
import com.custom.astrion.ui.tapAndHold

/**
 * Long-press detail popup for a light (bubble-card style): a big vertical
 * brightness pill, a power toggle, and — only when the light's
 * `supported_color_modes` allow — colour swatches and colour-temperature
 * presets. Everything fires standard light.turn_on/turn_off service data
 * (brightness_pct / rgb_color / color_temp_kelvin).
 */
@Composable
fun LightDetailDialog(
    entityId: String,
    e: EntityState?,
    client: HaClient,
    onClose: () -> Unit,
) {
    val on = e?.isOn == true
    val name = e?.friendlyName ?: entityId
    val brightness = e?.attrInt("brightness")
    val level: Float = when {
        !on -> 0f
        brightness != null -> (brightness / 255f).coerceIn(0f, 1f)
        else -> 1f
    }
    var dragLevel by remember(level) { mutableStateOf(level) }
    /** A group member opened from this popup, stacked on top of it. */
    var memberDetail by remember(entityId) { mutableStateOf<String?>(null) }

    val colorModes = e?.attrStringList("supported_color_modes") ?: emptyList()
    val hasColor = colorModes.any { it in listOf("hs", "rgb", "rgbw", "rgbww", "xy") }
    val hasTemp = colorModes.contains("color_temp") || hasColor

    // Fill colour follows the light's live rgb_color where available.
    val rgb = e?.attr("rgb_color") as? JsonArray
    val fillColor = if (rgb != null && rgb.size >= 3) {
        fun ch(i: Int) = (rgb[i] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(0, 255) ?: 255
        Color(ch(0), ch(1), ch(2))
    } else AstrionTheme.colors.lightWarmWhite

    fun commit(fraction: Float) {
        val pct = (fraction.coerceIn(0f, 1f) * 100).roundToInt()
        if (pct <= 0) {
            client.callService(ServiceCall("light", "turn_off", entityId))
        } else {
            client.callService(ServiceCall.of("light", "turn_on", entityId, "brightness_pct" to pct))
        }
    }

    fun setRgb(r: Int, g: Int, b: Int) {
        client.callService(
            ServiceCall(
                "light", "turn_on", entityId,
                mapOf("rgb_color" to JsonArray(listOf(JsonPrimitive(r), JsonPrimitive(g), JsonPrimitive(b)))),
            )
        )
    }

    fun setKelvin(k: Int) {
        client.callService(ServiceCall.of("light", "turn_on", entityId, "color_temp_kelvin" to k))
    }

    val members = e?.attrStringList("entity_id").orEmpty().filter { it.startsWith("light.") }
    val kelvin = e?.attrInt("color_temp_kelvin")
    val shown = (dragLevel * 100).roundToInt()

    BottomSheet(
        title = name,
        subtitle = if (members.isNotEmpty()) "${members.size} lights" else null,
        onDismissRequest = onClose,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            // Vertical brightness fader: drag or tap to set; the fill rises
            // from the bottom in the light's own colour.
            Box(
                modifier = Modifier
                    .width(FADER_WIDTH)
                    .height(FADER_HEIGHT)
                    .clip(RoundedCornerShape(20.dp))
                    .background(AstrionTheme.colors.surfaceSunken)
                    .pointerInput(entityId) {
                        detectVerticalDragGestures(
                            onDragEnd = { commit(dragLevel) },
                        ) { change, _ ->
                            dragLevel = (1f - change.position.y / size.height).coerceIn(0f, 1f)
                        }
                    }
                    .pointerInput(entityId) {
                        detectTapGestures { offset ->
                            val f = (1f - offset.y / size.height).coerceIn(0f, 1f)
                            dragLevel = f
                            commit(f)
                        }
                    },
            ) {
                if (dragLevel > 0f) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .fillMaxHeight(dragLevel.coerceIn(0.02f, 1f))
                            .background(fillColor.copy(alpha = if (on) 1f else 0.4f)),
                    ) {
                        // The fader's cap: a short line riding the top of the fill.
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 8.dp, start = 14.dp, end = 14.dp)
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(CircleShape)
                                .background(AstrionTheme.onBg.copy(alpha = 0.5f)),
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.Bottom) {
                    Text(
                        "$shown",
                        color = AstrionTheme.textPrimary,
                        fontFamily = AstrionTheme.readoutFont,
                        fontWeight = FontWeight.ExtraLight,
                        fontSize = AstrionTheme.size.readoutLarge,
                        maxLines = 1,
                    )
                    Text(
                        "%",
                        color = AstrionTheme.textSecondary,
                        fontFamily = AstrionTheme.readoutFont,
                        fontWeight = FontWeight.Light,
                        fontSize = AstrionTheme.size.displayXs,
                        modifier = Modifier.padding(start = 3.dp, bottom = 12.dp),
                    )
                }

                // Power: says what it will do.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) AstrionTheme.on else AstrionTheme.raised)
                        .tap { client.toggle(entityId) },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val ink = if (on) AstrionTheme.onBg else AstrionTheme.textPrimary
                    Icon(Glyphs.power, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp))
                    Text(
                        if (on) "Turn off" else "Turn on",
                        color = ink,
                        fontSize = AstrionTheme.size.body,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                if (hasTemp) {
                    Caption("Warmth")
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        WARMTH.forEach { w ->
                            val picked = on && kelvin != null && abs(kelvin - w.kelvin) < 150 &&
                                e?.attrString("color_mode") == "color_temp"
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(40.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(AstrionTheme.raised)
                                    .then(
                                        if (picked) Modifier.border(1.5.dp, AstrionTheme.textPrimary, RoundedCornerShape(12.dp))
                                        else Modifier,
                                    )
                                    .tap { setKelvin(w.kelvin) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                            ) {
                                Box(
                                    Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(w.r, w.g, w.b)),
                                )
                                Text(
                                    w.label,
                                    color = AstrionTheme.textPrimary,
                                    fontSize = AstrionTheme.size.label,
                                    fontWeight = FontWeight.Bold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }

                if (hasColor) {
                    Caption("Colour")
                    SWATCHES.chunked(6).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            row.forEach { (r, g, b) ->
                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .aspectRatio(1f)
                                        .clip(CircleShape)
                                        .background(Color(r, g, b))
                                        .tap { setRgb(r, g, b) },
                                )
                            }
                        }
                    }
                }
            }
        }

        // A group's own lights, one tap each. Picking out one light in a
        // group was a reason to reach for the phone.
        if (members.isNotEmpty()) {
            GroupMembers(members, client) { memberDetail = it }
        }
    }

    memberDetail?.let { id ->
        LightDetailDialog(
            entityId = id,
            e = client.live[id],
            client = client,
            onClose = { memberDetail = null },
        )
    }
}

/**
 * The lights inside a group, as chips: tap toggles that one light, hold opens
 * its own popup (brightness, colour) on top of this one; BACK returns.
 */
@Composable
private fun GroupMembers(members: List<String>, client: HaClient, onOpen: (String) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        members.forEach { id ->
            val m = client.live[id]
            val on = m?.isOn == true
            val unavailable = m == null || m.isUnavailable
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) AstrionTheme.on.copy(alpha = 0.85f) else AstrionTheme.controlSunken)
                    .tapAndHold(
                        enabled = !unavailable,
                        onClick = { client.toggle(id) },
                        onLongClick = { onOpen(id) },
                    )
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Icon(
                    Glyphs.bulb,
                    contentDescription = null,
                    tint = when {
                        unavailable -> AstrionTheme.unavailable
                        on -> AstrionTheme.onBg
                        else -> AstrionTheme.textOnControl
                    },
                    modifier = Modifier.size(15.dp),
                )
                Text(
                    m?.friendlyName ?: id.substringAfter('.'),
                    color = when {
                        unavailable -> AstrionTheme.unavailable
                        on -> AstrionTheme.onBg
                        else -> AstrionTheme.textOnControl
                    },
                    fontSize = AstrionTheme.label,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 120.dp),
                )
            }
        }
    }
}

@Composable
private fun Caption(text: String) {
    Text(text, color = AstrionTheme.textMuted, fontSize = AstrionTheme.size.label, fontWeight = FontWeight.SemiBold)
}

private class Warmth(val label: String, val kelvin: Int, val r: Int, val g: Int, val b: Int)

/** Warm to cool. Each dot is roughly the white it sets. */
private val WARMTH = listOf(
    Warmth("Candle", 2200, 255, 179, 71),
    Warmth("Warm", 2700, 255, 194, 75),
    Warmth("Soft", 3500, 255, 217, 160),
    Warmth("Day", 5500, 255, 255, 255),
)

private val SWATCHES = listOf(
    Triple(243, 155, 184), Triple(244, 63, 94), Triple(201, 160, 240),
    Triple(110, 155, 232), Triple(110, 200, 184), Triple(249, 166, 90),
    Triple(255, 235, 59), Triple(76, 175, 80), Triple(0, 188, 212),
    Triple(33, 150, 243), Triple(156, 39, 176), Triple(255, 255, 255),
)

private val FADER_WIDTH = 96.dp
private val FADER_HEIGHT = 248.dp
