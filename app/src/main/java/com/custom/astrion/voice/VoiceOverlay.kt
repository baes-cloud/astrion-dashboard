package com.custom.astrion.voice

import android.graphics.BitmapFactory
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import com.custom.astrion.ui.AstrionButton
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.decodeSampled
import androidx.compose.ui.graphics.lerp

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

    // Decode once per (dir, phase) — these are small and the SoC is modest.
    val art: ImageBitmap? = remember(imageDir, state.phase) {
        loadPhaseImage(imageDir, state.phase)
    }

    // In-content overlay rather than a Dialog, so MainActivity keeps input
    // focus and the VOICE key can still be intercepted while this is open
    // (press it again to stop listening).
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AstrionTheme.scrim)
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
                .padding(horizontal = Space.xl)
                .clip(RoundedCornerShape(Radius.sheet))
                .background(AstrionTheme.cardBg)
                .padding(Space.xl),
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
                            tint = AstrionTheme.pinnedTopBg, modifier = Modifier.size(40.dp),
                        )
                    }
                }
            }

            Text(
                phaseLabel(state.phase),
                color = accentColor(state.phase),
                style = AstrionType.shout,
            )

            if (state.transcript.isNotBlank()) {
                Text(
                    "“${state.transcript}”",
                    color = AstrionTheme.textPrimary, style = AstrionType.title,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            if (state.reply.isNotBlank()) {
                Text(
                    state.reply,
                    color = AstrionTheme.textSecondary, style = AstrionType.body,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }
            state.error?.let {
                Text(
                    it,
                    color = AstrionTheme.danger, style = AstrionType.bodyStrong,
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                )
            }

            AstrionButton(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                label = if (state.phase == VoicePhase.LISTENING) "Stop (or press Mic)" else "Close",
            )
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
    VoicePhase.LISTENING -> AstrionTheme.on
    VoicePhase.PROCESSING -> AstrionTheme.accent
    VoicePhase.SPEAKING -> AstrionTheme.good
    VoicePhase.ERROR -> AstrionTheme.danger
    else -> AstrionTheme.textSecondary
}

/** Opaque blend toward the card — no alpha layer under the pulsing avatar. */
private fun haloColor(p: VoicePhase) = lerp(AstrionTheme.cardBg, accentColor(p), 0.18f)

/** `<dir>/<phase>.png`, falling back to `<dir>/idle.png`, else null. */
private fun loadPhaseImage(dir: String, phase: VoicePhase): ImageBitmap? {
    val names = when (phase) {
        VoicePhase.LISTENING -> listOf("listening", "idle")
        VoicePhase.PROCESSING -> listOf("processing", "thinking", "idle")
        VoicePhase.SPEAKING -> listOf("speaking", "idle")
        VoicePhase.DONE -> listOf("done", "idle")
        VoicePhase.ERROR -> listOf("error", "idle")
        VoicePhase.IDLE -> listOf("idle")
    }
    for (n in names) {
        val f = File(dir, "$n.png")
        if (f.exists()) {
            // Downsampled to the 112dp it's drawn at, and cached.
            decodeSampled(f.absolutePath, 160)?.let { return it }
        }
    }
    return null
}
