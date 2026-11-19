package com.custom.astrion.cards.impl

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.BottomSheet
import com.custom.astrion.ui.Glyphs
import com.custom.astrion.ui.components.BaeoSectionHeader
import com.custom.astrion.ui.tap
import com.custom.astrion.ui.tapAndHold
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Long-press light detail sheet (Bæo v3.0 Matte Tonal):
 *  - Balanced top row: 88×166dp smooth vertical matte fader (with 25/50/75%
 *    ticks) beside Manrope 400 tabular % readout, neutral color-dotted power
 *    pill, and borderless Warmth preset pills.
 *  - Full-width 2×6 48dp circular colour swatches (`Role.RadioButton`).
 *  - Borderless matte group-member pills with live color dots.
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
    var memberDetail by remember(entityId) { mutableStateOf<String?>(null) }

    val colorModes = e?.attrStringList("supported_color_modes") ?: emptyList()
    val hasColor = colorModes.any { it in listOf("hs", "rgb", "rgbw", "rgbww", "xy") }
    val hasTemp = colorModes.contains("color_temp") || hasColor

    val fillColor = entityRgbColor(e) ?: AstrionTheme.colors.lightWarmWhite

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
    val tickColor = AstrionTheme.textPrimary.copy(alpha = 0.14f)

    BottomSheet(
        title = name,
        subtitle = if (members.isNotEmpty()) "${members.size} lights" else null,
        onDismissRequest = onClose,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.Top,
        ) {
            // Vertical brightness fader with 25/50/75% matte reference ticks.
            Box(
                modifier = Modifier
                    .width(FADER_WIDTH)
                    .height(FADER_HEIGHT)
                    .clip(RoundedCornerShape(18.dp))
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
                            .fillMaxHeight(dragLevel.coerceIn(0.03f, 1f))
                            .background(fillColor.copy(alpha = if (on) 0.92f else 0.35f)),
                    )
                }
                Canvas(Modifier.fillMaxSize()) {
                    listOf(0.25f, 0.50f, 0.75f).forEach { frac ->
                        val y = size.height * (1f - frac)
                        drawLine(
                            color = tickColor,
                            start = Offset(16.dp.toPx(), y),
                            end = Offset(size.width - 16.dp.toPx(), y),
                            strokeWidth = 1.dp.toPx(),
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
                        text = "$shown",
                        color = AstrionTheme.textPrimary,
                        fontFamily = AstrionTheme.readoutFont,
                        fontWeight = FontWeight.Normal,
                        fontSize = AstrionTheme.size.readoutLarge,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                        maxLines = 1,
                    )
                    Text(
                        text = "%",
                        color = AstrionTheme.textSecondary,
                        fontFamily = AstrionTheme.readoutFont,
                        fontWeight = FontWeight.Normal,
                        fontSize = AstrionTheme.size.displayXs,
                        modifier = Modifier.padding(start = 3.dp, bottom = 10.dp),
                    )
                }

                // Neutral matte power pill with live color dot (no harsh yellow slab).
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(46.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (on) AstrionTheme.surfaceElevated else AstrionTheme.controlBg)
                        .tap { client.toggle(entityId) }
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(CircleShape)
                            .background(if (on) fillColor else AstrionTheme.textMuted),
                    )
                    Text(
                        text = if (on) "Turn off" else "Turn on",
                        color = AstrionTheme.textPrimary,
                        fontSize = AstrionTheme.size.body,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }

                if (hasTemp) {
                    BaeoSectionHeader("Warmth")
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                        WARMTH.forEach { w ->
                            val picked = on && kelvin != null && abs(kelvin - w.kelvin) < 150 &&
                                e?.attrString("color_mode") == "color_temp"
                            Column(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(44.dp)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (picked) AstrionTheme.accentStrong else AstrionTheme.controlBg)
                                    .tap { setKelvin(w.kelvin) },
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(3.dp, Alignment.CenterVertically),
                            ) {
                                Box(
                                    Modifier
                                        .size(9.dp)
                                        .clip(CircleShape)
                                        .background(Color(w.r, w.g, w.b)),
                                )
                                Text(
                                    text = w.label,
                                    color = if (picked) AstrionTheme.colors.onPrimary else AstrionTheme.textOnControl,
                                    fontSize = AstrionTheme.size.micro,
                                    fontWeight = FontWeight.SemiBold,
                                    maxLines = 1,
                                )
                            }
                        }
                    }
                }
            }
        }

        // Full-width 2x6 48dp circular colour swatches below the top row.
        if (hasColor) {
            BaeoSectionHeader("Colour")
            val current = (e?.attr("rgb_color") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content?.toFloatOrNull()?.toInt() }
                ?.takeIf { it.size == 3 && on && e?.attrString("color_mode") != "color_temp" }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SWATCHES.chunked(6).forEachIndexed { rowIdx, row ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        row.forEachIndexed { colIdx, (r, g, b) ->
                            val swatchColor = Color(r, g, b)
                            val picked = current != null &&
                                abs(current[0] - r) + abs(current[1] - g) + abs(current[2] - b) < 60
                            val dotInk = if (swatchColor.luminance() > 0.45f) AstrionTheme.onAccent else AstrionTheme.textPrimary
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1f)
                                    .clip(CircleShape)
                                    .background(swatchColor)
                                    .semantics { contentDescription = "Colour ${rowIdx * 6 + colIdx + 1}" }
                                    .selectable(
                                        selected = picked,
                                        role = Role.RadioButton,
                                        onClick = { setRgb(r, g, b) },
                                    ),
                                contentAlignment = Alignment.Center,
                            ) {
                                if (picked) {
                                    Box(
                                        Modifier
                                            .size(12.dp)
                                            .clip(CircleShape)
                                            .background(dotInk),
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (members.isNotEmpty()) {
            BaeoSectionHeader("Group lights · hold for detail")
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

private fun entityRgbColor(e: EntityState?): Color? {
    val rgb = e?.attr("rgb_color") as? JsonArray ?: return null
    if (rgb.size < 3) return null
    fun ch(i: Int) = (rgb[i] as? JsonPrimitive)?.content?.toIntOrNull()?.coerceIn(0, 255) ?: 255
    return Color(ch(0), ch(1), ch(2))
}

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
            val dotColor = when {
                unavailable -> AstrionTheme.unavailable
                on -> entityRgbColor(m) ?: AstrionTheme.on
                else -> AstrionTheme.textMuted
            }
            Row(
                modifier = Modifier
                    .height(40.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(if (on) AstrionTheme.surfaceElevated else AstrionTheme.controlSunken)
                    .tapAndHold(
                        enabled = !unavailable,
                        onClick = { client.toggle(id) },
                        onLongClick = { onOpen(id) },
                    )
                    .padding(horizontal = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
            ) {
                Box(
                    Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(dotColor),
                )
                Icon(
                    Glyphs.bulb,
                    contentDescription = null,
                    tint = if (unavailable) AstrionTheme.unavailable else if (on) AstrionTheme.textPrimary else AstrionTheme.textMuted,
                    modifier = Modifier.size(14.dp),
                )
                Text(
                    m?.friendlyName ?: id.substringAfter('.'),
                    color = when {
                        unavailable -> AstrionTheme.unavailable
                        on -> AstrionTheme.textPrimary
                        else -> AstrionTheme.textSecondary
                    },
                    fontSize = AstrionTheme.size.label,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 128.dp),
                )
            }
        }
    }
}

private class Warmth(val label: String, val kelvin: Int, val r: Int, val g: Int, val b: Int)

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

private val FADER_WIDTH = 88.dp
private val FADER_HEIGHT = 172.dp
