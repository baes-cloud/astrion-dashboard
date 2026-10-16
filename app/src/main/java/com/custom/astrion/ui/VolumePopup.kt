package com.custom.astrion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeMute
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * TV-style floating volume OSD (On-Screen Display).
 *
 * Appears dynamically near the top of the screen when:
 * 1. Physical volume buttons (VOLUME_UP, VOLUME_DOWN, MUTE) are pressed.
 * 2. Home Assistant updates [targetEntityId] (defaults to "media_player.club") volume or mute state.
 *
 * Displays a tactile capsule with speaker name, percentage/mute badge, and an
 * animated slider bar. Automatically dismisses after 2.2 seconds of inactivity.
 * Users can also tap or drag the slider on screen to quickly fine-tune volume.
 */
@Composable
fun VolumePopup(
    client: HaClient,
    targetEntityId: String = "media_player.club",
    externalTriggerEpoch: Long = 0L,
    modifier: Modifier = Modifier,
) {
    val entity = client.live[targetEntityId]
    val liveVolume = entity?.attrDouble("volume_level")?.toFloat() ?: 0f
    val liveMuted = (entity?.attr("is_volume_muted") as? JsonPrimitive)?.booleanOrNull == true
    val friendlyName = entity?.friendlyName ?: "Club"

    var isVisible by remember { mutableStateOf(false) }
    var hideEpoch by remember { mutableLongStateOf(0L) }

    // User drag override to give instantaneous touch responsiveness
    var dragVolume by remember { mutableStateOf<Float?>(null) }
    val effectiveVolume = dragVolume ?: liveVolume

    // Animated volume fraction for buttery smooth bar gliding
    val animatedVol by animateFloatAsState(
        targetValue = effectiveVolume.coerceIn(0f, 1f),
        animationSpec = tween(120),
        label = "volume_bar",
    )

    // Track state changes to trigger popup
    var prevVolume by remember { mutableFloatStateOf(liveVolume) }
    var prevMuted by remember { mutableStateOf(liveMuted) }
    var prevTriggerEpoch by remember { mutableLongStateOf(externalTriggerEpoch) }
    var initialized by remember { mutableStateOf(false) }

    // Detect volume changes or external button presses
    LaunchedEffect(liveVolume, liveMuted, externalTriggerEpoch) {
        if (!initialized) {
            initialized = true
            prevVolume = liveVolume
            prevMuted = liveMuted
            prevTriggerEpoch = externalTriggerEpoch
            return@LaunchedEffect
        }

        val volChanged = abs(liveVolume - prevVolume) > 0.005f
        val muteChanged = liveMuted != prevMuted
        val triggerFired = externalTriggerEpoch != prevTriggerEpoch && externalTriggerEpoch > 0L

        prevVolume = liveVolume
        prevMuted = liveMuted
        prevTriggerEpoch = externalTriggerEpoch

        if (volChanged || muteChanged || triggerFired) {
            isVisible = true
            hideEpoch = System.currentTimeMillis() + 2200L
        }
    }

    // Auto-dismiss countdown
    LaunchedEffect(hideEpoch, isVisible) {
        if (isVisible && hideEpoch > 0L) {
            val remaining = hideEpoch - System.currentTimeMillis()
            if (remaining > 0) {
                delay(remaining)
            }
            if (dragVolume == null) {
                isVisible = false
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 68.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(180)) + slideInVertically(
                initialOffsetY = { -it },
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            ),
            exit = fadeOut(tween(220)) + slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = tween(220),
            ),
        ) {
            Row(
                modifier = Modifier
                    .width(280.dp)
                    .height(54.dp)
                    .shadow(12.dp, RoundedCornerShape(20.dp))
                    .clip(RoundedCornerShape(20.dp))
                    .background(AstrionTheme.colors.surfaceCard.copy(alpha = 0.95f)) // High-opacity Yacht Race slate ground
                    .border(1.dp, AstrionTheme.colors.hairlineStrong, RoundedCornerShape(20.dp))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Mute/speaker icon button
                HitTarget(
                    visual = 36.dp,
                    haptic = false,
                    face = Modifier
                        .clip(CircleShape)
                        .background(if (liveMuted) AstrionTheme.colors.blush.copy(alpha = 0.2f) else AstrionTheme.colors.hairline),
                    onClick = {
                        hideEpoch = System.currentTimeMillis() + 2200L
                        client.callService(
                            ServiceCall.of(
                                "media_player",
                                "volume_mute",
                                targetEntityId,
                                "is_volume_muted" to !liveMuted,
                            )
                        )
                    },
                ) {
                    val icon = when {
                        liveMuted -> Icons.Filled.VolumeOff
                        effectiveVolume >= 0.5f -> Icons.Filled.VolumeUp
                        effectiveVolume > 0f -> Icons.Filled.VolumeDown
                        else -> Icons.Filled.VolumeMute
                    }
                    val iconTint = if (liveMuted) AstrionTheme.colors.blush else AstrionTheme.accent
                    Icon(
                        imageVector = icon,
                        contentDescription = if (liveMuted) "Muted" else "Volume",
                        tint = iconTint,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(Modifier.width(12.dp))

                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.Center,
                ) {
                    // Title & Volume % readout
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = friendlyName,
                            color = AstrionTheme.textPrimary,
                            fontFamily = AstrionTheme.headingFont,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = AstrionTheme.size.small,
                            maxLines = 1,
                        )
                        Text(
                            text = if (liveMuted) "MUTED" else "${(animatedVol * 100).roundToInt()}%",
                            color = if (liveMuted) AstrionTheme.colors.blush else AstrionTheme.textSecondary,
                            fontFamily = AstrionTheme.bodyFont,
                            fontWeight = FontWeight.Bold,
                            fontSize = AstrionTheme.size.label,
                            letterSpacing = if (liveMuted) 0.5.sp else 0.sp,
                        )
                    }

                    Spacer(Modifier.height(5.dp))

                    // Tactile Volume Slider Bar
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(AstrionTheme.colors.hairlineStrong)
                            .pointerInput(targetEntityId) {
                                detectTapGestures { offset ->
                                    val pct = (offset.x / size.width).coerceIn(0f, 1f)
                                    hideEpoch = System.currentTimeMillis() + 2200L
                                    client.callService(
                                        ServiceCall.of(
                                            "media_player",
                                            "set_volume_level",
                                            targetEntityId,
                                            "volume_level" to pct,
                                        )
                                    )
                                }
                            }
                            .pointerInput(targetEntityId) {
                                detectHorizontalDragGestures(
                                    onDragStart = { offset ->
                                        hideEpoch = System.currentTimeMillis() + 2200L
                                        val pct = (offset.x / size.width).coerceIn(0f, 1f)
                                        dragVolume = pct
                                    },
                                    onDragEnd = {
                                        dragVolume?.let { pct ->
                                            client.callService(
                                                ServiceCall.of(
                                                    "media_player",
                                                    "set_volume_level",
                                                    targetEntityId,
                                                    "volume_level" to pct,
                                                )
                                            )
                                        }
                                        dragVolume = null
                                        hideEpoch = System.currentTimeMillis() + 2200L
                                    },
                                    onDragCancel = {
                                        dragVolume = null
                                        hideEpoch = System.currentTimeMillis() + 2200L
                                    },
                                    onHorizontalDrag = { change, _ ->
                                        change.consume()
                                        hideEpoch = System.currentTimeMillis() + 2200L
                                        val pct = (change.position.x / size.width).coerceIn(0f, 1f)
                                        dragVolume = pct
                                    },
                                )
                            },
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .fillMaxWidth(if (liveMuted) 0f else animatedVol.coerceIn(0f, 1f))
                                .clip(RoundedCornerShape(3.dp))
                                .background(if (liveMuted) AstrionTheme.colors.blush.copy(alpha = 0.4f) else AstrionTheme.accent),
                        )
                    }
                }
            }
        }
    }
}
