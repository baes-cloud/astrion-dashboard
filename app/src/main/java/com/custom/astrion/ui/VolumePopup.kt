package com.custom.astrion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.abs

/**
 * Floating borderless matte volume OSD (Bæo v3.0):
 *  - Opaque Tier 4 matte capsule (`surfaceElevated`), zero shadow, zero border.
 *  - Smooth borderless [Fader] calling `media_player.volume_set` (`Double`).
 *  - Suppresses passive HA echoes while the screensaver is up.
 */
@Composable
fun VolumePopup(
    client: HaClient,
    targetEntityId: String = "media_player.club",
    externalTriggerEpoch: Long = 0L,
    suppressPassive: Boolean = false,
    modifier: Modifier = Modifier,
) {
    val entity = client.live[targetEntityId]
    val liveVolume = entity?.attrDouble("volume_level")?.toFloat() ?: 0f
    val liveMuted = (entity?.attr("is_volume_muted") as? JsonPrimitive)?.booleanOrNull == true
    val friendlyName = entity?.friendlyName ?: "Club"

    var isVisible by remember { mutableStateOf(false) }
    var hideEpoch by remember { mutableLongStateOf(0L) }

    var prevVolume by remember { mutableFloatStateOf(liveVolume) }
    var prevMuted by remember { mutableStateOf(liveMuted) }
    var prevTriggerEpoch by remember { mutableLongStateOf(externalTriggerEpoch) }
    var initialized by remember { mutableStateOf(false) }

    LaunchedEffect(liveVolume, liveMuted, externalTriggerEpoch, suppressPassive) {
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

        if (triggerFired || (!suppressPassive && (volChanged || muteChanged))) {
            isVisible = true
            hideEpoch = System.currentTimeMillis() + 2400L
        }
    }

    LaunchedEffect(hideEpoch, isVisible) {
        if (isVisible && hideEpoch > 0L) {
            val remaining = hideEpoch - System.currentTimeMillis()
            if (remaining > 0) delay(remaining)
            isVisible = false
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .padding(top = 56.dp),
        contentAlignment = Alignment.TopCenter,
    ) {
        AnimatedVisibility(
            visible = isVisible,
            enter = fadeIn(tween(160)) + slideInVertically(
                initialOffsetY = { -it },
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
            ),
            exit = fadeOut(tween(200)) + slideOutVertically(
                targetOffsetY = { -it },
                animationSpec = tween(200),
            ),
        ) {
            Row(
                modifier = Modifier
                    .width(296.dp)
                    .height(58.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(AstrionTheme.surfaceElevated)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                HitTarget(
                    visual = 40.dp,
                    haptic = false,
                    face = Modifier
                        .clip(CircleShape)
                        .background(if (liveMuted) AstrionTheme.blushContainer else AstrionTheme.controlBg),
                    onClick = {
                        hideEpoch = System.currentTimeMillis() + 2400L
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
                        liveVolume >= 0.5f -> Icons.Filled.VolumeUp
                        liveVolume > 0f -> Icons.Filled.VolumeDown
                        else -> Icons.Filled.VolumeMute
                    }
                    Icon(
                        imageVector = icon,
                        contentDescription = if (liveMuted) "Unmute $friendlyName" else "Mute $friendlyName",
                        tint = if (liveMuted) AstrionTheme.blush else AstrionTheme.accent,
                        modifier = Modifier.size(20.dp),
                    )
                }

                Spacer(Modifier.width(6.dp))

                Fader(
                    value = liveVolume,
                    onCommit = { pct ->
                        hideEpoch = System.currentTimeMillis() + 2400L
                        client.callService(
                            ServiceCall.of(
                                "media_player",
                                "volume_set",
                                targetEntityId,
                                "volume_level" to pct.toDouble(),
                            )
                        )
                    },
                    muted = liveMuted,
                    fill = AstrionTheme.accentStrong,
                    track = AstrionTheme.trackBg,
                    radius = 12.dp,
                    modifier = Modifier
                        .weight(1f)
                        .height(42.dp),
                ) {
                    Text(
                        text = friendlyName,
                        color = AstrionTheme.textPrimary,
                        fontFamily = AstrionTheme.bodyFont,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = AstrionTheme.size.body,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
