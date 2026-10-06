package com.custom.astrion.voice

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import com.custom.astrion.ui.rememberSampledBitmap
import com.custom.astrion.ui.tap

/**
 * Voice assistant modal, shown while a [VoiceSession] is running.
 *
 * Pikachu: drop PNGs into `/sdcard/astrion/voice/` named for the phase —
 * `listening.png`, `processing.png`, `speaking.png` (plus optional
 * `done.png` / `error.png`). Whichever exist are used; anything missing
 * falls back to `idle.png`, then to a plain animated mic orb, so this works
 * with no images at all.
 */
@Composable
fun VoiceOverlay(
    state: VoiceState,
    imageDir: String = "/sdcard/astrion/voice",
    onDismiss: () -> Unit,
) {
    if (state.phase == VoicePhase.IDLE) return

    // Decoded off the main thread, downsampled to the panel and cached, so
    // the overlay opening (at the wake word) and each phase change don't
    // stall on a full-size PNG decode.
    val artPath = remember(imageDir, state.phase) { phaseImagePath(imageDir, state.phase) }
    val art: ImageBitmap? = rememberSampledBitmap(artPath, targetPx = 256).value

    // In-content overlay rather than a Dialog, so MainActivity keeps input
    // focus and the VOICE key can still be intercepted while this is open
    // (press it again to stop listening).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xCC0B1015))
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {},
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(Color(0xFF182129))
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            // Pulsing avatar: scales with live mic level while listening, and
            // breathes gently in the other phases.
            val pulse = rememberPulse(state)
            Box(
                modifier = Modifier.size(140.dp),
                contentAlignment = Alignment.Center,
            ) {
                Box(
                    modifier = Modifier
                        .size(140.dp)
                        .scale(pulse)
                        .clip(CircleShape)
                        .background(haloColor(state.phase)),
                )
                if (art != null) {
                    Image(
                        bitmap = art,
                        contentDescription = null,
                        modifier = Modifier.size(112.dp).scale(pulse),
                        contentScale = ContentScale.Fit,
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(84.dp)
                            .scale(pulse)
                            .clip(CircleShape)
                            .background(accentColor(state.phase)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Filled.Mic, contentDescription = null,
                            tint = Color(0xFF161F28), modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }

            Text(
                phaseLabel(state.phase),
                color = accentColor(state.phase),
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp,
            )

            if (state.transcript.isNotBlank()) {
                Text(
                    "“${state.transcript}”",
                    color = Color(0xFFF2F5F3), fontSize = 16.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.reply.isNotBlank()) {
                Text(
                    state.reply,
                    color = Color(0xFFAEBFBB), fontSize = 14.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            state.error?.let {
                Text(
                    it,
                    color = Color(0xFFE06767), fontSize = 13.sp,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(44.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF3A4F57))
                    .tap(onClick = onDismiss),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    if (state.phase == VoicePhase.LISTENING) "Stop" else "Close",
                    color = Color(0xFFEEF2EF), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/** Mic level drives the scale while listening; a slow breathe otherwise. */
@Composable
private fun rememberPulse(state: VoiceState): Float {
    val t = rememberInfiniteTransition(label = "voicepulse")
    val breathe by t.animateFloat(
        initialValue = 0.94f,
        targetValue = 1.06f,
        animationSpec = infiniteRepeatable(
            animation = tween(900), repeatMode = RepeatMode.Reverse,
        ),
        label = "breathe",
    )
    return if (state.phase == VoicePhase.LISTENING) {
        (0.95f + state.level * 0.35f).coerceIn(0.95f, 1.30f)
    } else {
        breathe
    }
}

private fun phaseLabel(p: VoicePhase) = when (p) {
    VoicePhase.LISTENING -> "LISTENING"
    VoicePhase.PROCESSING -> "THINKING"
    VoicePhase.SPEAKING -> "SPEAKING"
    VoicePhase.DONE -> "DONE"
    VoicePhase.ERROR -> "ERROR"
    VoicePhase.IDLE -> ""
}

private fun accentColor(p: VoicePhase) = when (p) {
    VoicePhase.LISTENING -> Color(0xFFFFC24B)
    VoicePhase.PROCESSING -> Color(0xFF8CBDB5)
    VoicePhase.SPEAKING -> Color(0xFF5FD3A0)
    VoicePhase.ERROR -> Color(0xFFE06767)
    else -> Color(0xFFAEBFBB)
}

private fun haloColor(p: VoicePhase) = accentColor(p).copy(alpha = 0.14f)

/** `<dir>/<phase>.png`, falling back to `<dir>/idle.png`, else null. */
private fun phaseImagePath(dir: String, phase: VoicePhase): String? {
    val names = when (phase) {
        VoicePhase.LISTENING -> listOf("listening", "idle")
        VoicePhase.PROCESSING -> listOf("processing", "thinking", "idle")
        VoicePhase.SPEAKING -> listOf("speaking", "idle")
        VoicePhase.DONE -> listOf("done", "idle")
        VoicePhase.ERROR -> listOf("error", "idle")
        VoicePhase.IDLE -> listOf("idle")
    }
    return names.map { File(dir, "$it.png") }.firstOrNull { it.isFile }?.absolutePath
}
