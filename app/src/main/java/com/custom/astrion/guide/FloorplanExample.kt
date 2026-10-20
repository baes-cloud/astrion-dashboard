package com.custom.astrion.guide

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.rememberSampledBitmap

/**
 * A still, labelled copy of the user's floorplan: one lit bulb, one off, the
 * vacuum and a person, each numbered, with what they mean underneath. Drawn
 * from the real plan image and real light positions, so it looks like the
 * Main page, but nothing on it is live or tappable.
 */
@Composable
fun FloorplanExample(plan: PlanExample, modifier: Modifier = Modifier) {
    val bitmap by rememberSampledBitmap(plan.image, 700)
    val aspect = bitmap?.let { it.width.toFloat() / it.height } ?: 1.04f
    val lit = plan.lights.getOrNull(1) ?: plan.lights.firstOrNull() ?: (45f to 12f)
    val off = plan.lights.lastOrNull()?.takeIf { it != lit } ?: (30f to 80f)
    val vac = plan.vacuum ?: (12f to 62f)
    // Somewhere believable between the lights; people move, it's an example.
    val person = (lit.first - 15f).coerceIn(10f, 90f) to (lit.second + 25f).coerceIn(10f, 90f)

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .aspectRatio(aspect)
                .clip(RoundedCornerShape(14.dp))
                .background(AstrionTheme.colors.surfaceSunken),
        ) {
            val w = maxWidth
            val h = maxHeight
            bitmap?.let {
                Image(it, contentDescription = null, contentScale = ContentScale.FillBounds, modifier = Modifier.fillMaxSize())
            }
            // A soft scrim so the markers read over a busy plan.
            Box(Modifier.fillMaxSize().background(AstrionTheme.colors.void.copy(alpha = 0.25f)))

            @Composable
            fun At(p: Pair<Float, Float>, size: Dp, content: @Composable () -> Unit) {
                Box(Modifier.offset(x = w * (p.first / 100f) - size / 2, y = h * (p.second / 100f) - size / 2).size(size)) { content() }
            }

            At(off, 30.dp) { Bulb(on = false) }
            At(lit, 30.dp) { Bulb(on = true) }
            At(vac, 30.dp) {
                Box(
                    Modifier.fillMaxSize().clip(CircleShape).background(AstrionTheme.raised),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.CleaningServices, null, tint = AstrionTheme.textPrimary, modifier = Modifier.size(17.dp))
                }
            }
            At(person, 22.dp) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(CircleShape)
                        .background(AstrionTheme.accent)
                        .border(4.dp, AstrionTheme.accent.copy(alpha = 0.3f), CircleShape),
                )
            }
            // Number badges beside each marker.
            At(lit.first + 7f to lit.second + 7f, 20.dp) { Badge(1) }
            At(vac.first + 7f to vac.second + 7f, 20.dp) { Badge(2) }
            At(person.first + 6f to person.second + 6f, 20.dp) { Badge(3) }
        }
        Legend(1, "A light. Tap to switch it; hold for brightness and colour. On is amber, and the room glows its colour.")
        Legend(2, "The vacuum, in the room it's in. Tap it to start, pause, send it home or clean one room.")
        Legend(3, "A person. Radar sensors (not cameras) feel movement and put a dot where you are.")
    }
}

@Composable
private fun Bulb(on: Boolean) {
    Box(
        Modifier.fillMaxSize().clip(CircleShape).background(if (on) AstrionTheme.on else AstrionTheme.colors.surfacePrimary.copy(alpha = 0.85f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Lightbulb, null, tint = if (on) AstrionTheme.onBg else AstrionTheme.textOnControl, modifier = Modifier.size(17.dp))
    }
}

@Composable
private fun Badge(n: Int) {
    Box(
        Modifier.fillMaxSize().clip(CircleShape).background(AstrionTheme.accentStrong),
        contentAlignment = Alignment.Center,
    ) {
        Text("$n", color = AstrionTheme.colors.onPrimary, fontSize = AstrionTheme.size.label, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun Legend(n: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        Box(Modifier.padding(top = 1.dp).size(20.dp)) { Badge(n) }
        Spacer(Modifier.width(8.dp))
        Text(text, color = AstrionTheme.colors.textSoft, fontSize = AstrionTheme.size.small, modifier = Modifier.weight(1f))
    }
}
