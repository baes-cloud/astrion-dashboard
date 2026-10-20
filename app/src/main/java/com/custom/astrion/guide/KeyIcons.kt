package com.custom.astrion.guide

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.input.HardwareKey
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.tap

/**
 * The glyphs printed on the HA100's buttons, redrawn as thin strokes on a
 * 24-unit grid so the guide shows the button you're actually looking at.
 * The TV guide (Bæovision `guide.html`) uses the same path strings; keep
 * them in step.
 */
object KeyGlyphs {
    val paths: Map<HardwareKey, List<String>> = mapOf(
        HardwareKey.BACK to listOf("M15 5 L8 12 L15 19"),
        HardwareKey.HOME to listOf("M4 11.5 L12 4.5 L20 11.5", "M6.5 9.5 V19 H17.5 V9.5"),
        HardwareKey.POWER to listOf("M12 3.5 V11", "M7.2 6.6 A7.2 7.2 0 1 0 16.8 6.6"),
        HardwareKey.VOLUME_UP to listOf("M12 6 V18", "M6 12 H18"),
        HardwareKey.VOLUME_DOWN to listOf("M6 12 H18"),
        HardwareKey.PAGE_UP to listOf("M7 14.5 L12 9.5 L17 14.5"),
        HardwareKey.PAGE_DOWN to listOf("M7 9.5 L12 14.5 L17 9.5"),
        // The ring around OK.
        HardwareKey.UP to listOf("M12 2.5 A9.5 9.5 0 1 1 11.99 2.5 Z", "M12 8 A4 4 0 1 1 11.99 8 Z"),
        HardwareKey.MUTE to listOf("M4 9.5 H7.5 L12 6 V18 L7.5 14.5 H4 Z", "M15.5 10 L19.5 14", "M19.5 10 L15.5 14"),
        HardwareKey.VOICE to listOf(
            "M9.5 6 A2.5 2.5 0 0 1 14.5 6 V11.5 A2.5 2.5 0 0 1 9.5 11.5 Z",
            "M6.5 11 A5.5 5.5 0 0 0 17.5 11", "M12 16.5 V19.5",
        ),
        HardwareKey.MENU to listOf("M5 7 H19", "M5 12 H19", "M5 17 H19"),
        HardwareKey.LIGHT to listOf(
            "M9.2 15.5 C9.2 13.6 7.2 12.6 7.2 10 A4.8 4.8 0 0 1 16.8 10 C16.8 12.6 14.8 13.6 14.8 15.5 Z",
            "M9.6 18 H14.4", "M10.6 20.4 H13.4",
            "M12 1.8 V3.4", "M5.2 4.4 L6.3 5.5", "M18.8 4.4 L17.7 5.5", "M2.8 10 H4.4", "M19.6 10 H21.2",
        ),
        HardwareKey.CURTAIN to listOf("M4 5 H20", "M5.5 5 V19", "M8.5 5 V19", "M15.5 5 V19", "M18.5 5 V19"),
        HardwareKey.SCENE to listOf(
            "M9 17.5 V6.2 L18 4.2 V15.5",
            "M9 17.5 A2.2 2.2 0 1 1 4.6 17.5 A2.2 2.2 0 1 1 9 17.5 Z",
            "M18 15.5 A2.2 2.2 0 1 1 13.6 15.5 A2.2 2.2 0 1 1 18 15.5 Z",
        ),
        HardwareKey.AC to listOf(
            "M3.5 5 H20.5 V12 H3.5 Z", "M6.5 9.5 H17.5",
            "M8 14.5 C7 15.5 9 16.5 8 17.8", "M12 14.5 C11 15.5 13 16.5 12 17.8", "M16 14.5 C15 15.5 17 16.5 16 17.8",
        ),
        // The coloured buttons are just a short bar of colour.
        HardwareKey.CUSTOM_1 to listOf("M7 12 H17"),
        HardwareKey.CUSTOM_2 to listOf("M7 12 H17"),
        HardwareKey.CUSTOM_3 to listOf("M7 12 H17"),
        HardwareKey.CUSTOM_4 to listOf("M7 12 H17"),
    )
}

/** A button's printed glyph; OK is the one with letters instead. */
@Composable
fun KeyIcon(key: HardwareKey, size: Dp, colour: Color = AstrionTheme.textPrimary) {
    val glyphColour = keyColour(key) ?: colour
    if (key == HardwareKey.CENTER) {
        Box(Modifier.size(size), contentAlignment = Alignment.Center) {
            Text("OK", color = glyphColour, fontSize = (size.value * 0.5f).sp, fontWeight = FontWeight.Bold)
        }
        return
    }
    val raw = KeyGlyphs.paths[key] ?: return
    val paths = remember(key) { raw.map { PathParser().parsePathString(it).toPath() } }
    val colourBar = keyColour(key) != null
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension / 24f
        scale(s, pivot = androidx.compose.ui.geometry.Offset.Zero) {
            paths.forEach {
                drawPath(
                    it, glyphColour,
                    style = Stroke(width = if (colourBar) 3f else 1.6f, cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
    }
}

@Composable
internal fun keyColour(key: HardwareKey): Color? = when (key) {
    HardwareKey.CUSTOM_1 -> AstrionTheme.colors.keyRed
    HardwareKey.CUSTOM_2 -> AstrionTheme.colors.keyGreen
    HardwareKey.CUSTOM_3 -> AstrionTheme.colors.keyBlue
    HardwareKey.CUSTOM_4 -> AstrionTheme.colors.keyYellow
    else -> null
}

/**
 * The HA100's button block, laid out as on the remote: Back · Home · Power,
 * the volume and page rockers either side of the ring and OK, Mute · Mic ·
 * Menu, the four page buttons, the four colours. [highlight] pulses (the
 * walkthrough's "this one"); [onKey] makes it a map you can tap.
 */
@Composable
fun RemoteMap(
    highlight: Set<HardwareKey> = emptySet(),
    onKey: ((HardwareKey) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val pulse by rememberInfiniteTransition(label = "pulse").animateFloat(
        initialValue = 0.35f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(700), RepeatMode.Reverse), label = "pulse",
    )
    val gap = 5.dp
    val h = 42.dp

    @Composable
    fun RowScope.Key(key: HardwareKey, weight: Float = 1f, height: Dp = h, shape: RoundedCornerShape = RoundedCornerShape(9.dp)) {
        val lit = key in highlight
        Box(
            Modifier
                .weight(weight)
                .height(height)
                .clip(shape)
                .background(AstrionTheme.colors.void)
                .then(if (lit) Modifier.border(2.dp, AstrionTheme.accent.copy(alpha = pulse), shape) else Modifier)
                .then(if (onKey != null) Modifier.tap { onKey(key) } else Modifier),
            contentAlignment = Alignment.Center,
        ) {
            KeyIcon(key, 22.dp, if (lit) AstrionTheme.accent else AstrionTheme.textOnControl)
        }
    }

    Column(
        modifier
            .clip(RoundedCornerShape(18.dp))
            .background(AstrionTheme.colors.surfaceSunken)
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(gap),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Key(HardwareKey.BACK)
            Key(HardwareKey.HOME, 2f)
            Key(HardwareKey.POWER)
        }
        // Rockers either side of the ring.
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                Row { Key(HardwareKey.VOLUME_UP) }
                Row { Key(HardwareKey.VOLUME_DOWN) }
            }
            Box(Modifier.weight(2f).height(h * 2 + gap), contentAlignment = Alignment.Center) {
                val ring = HardwareKey.UP in highlight || HardwareKey.LEFT in highlight
                val ok = HardwareKey.CENTER in highlight
                Box(
                    Modifier
                        .size(h * 2 + gap)
                        .clip(CircleShape)
                        .background(AstrionTheme.colors.void)
                        .then(if (ring) Modifier.border(2.dp, AstrionTheme.accent.copy(alpha = pulse), CircleShape) else Modifier)
                        .then(if (onKey != null) Modifier.tap { onKey(HardwareKey.UP) } else Modifier),
                )
                Box(
                    Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(AstrionTheme.colors.controlSunken)
                        .then(if (ok) Modifier.border(2.dp, AstrionTheme.accent.copy(alpha = pulse), CircleShape) else Modifier)
                        .then(if (onKey != null) Modifier.tap { onKey(HardwareKey.CENTER) } else Modifier),
                    contentAlignment = Alignment.Center,
                ) {
                    KeyIcon(HardwareKey.CENTER, 26.dp, if (ok) AstrionTheme.accent else AstrionTheme.textOnControl)
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(gap)) {
                Row { Key(HardwareKey.PAGE_UP) }
                Row { Key(HardwareKey.PAGE_DOWN) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Key(HardwareKey.MUTE)
            Key(HardwareKey.VOICE, 2f)
            Key(HardwareKey.MENU)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            listOf(HardwareKey.LIGHT, HardwareKey.CURTAIN, HardwareKey.SCENE, HardwareKey.AC).forEach { Key(it) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            listOf(HardwareKey.CUSTOM_1, HardwareKey.CUSTOM_2, HardwareKey.CUSTOM_3, HardwareKey.CUSTOM_4).forEach { Key(it, height = 30.dp) }
        }
    }
}
