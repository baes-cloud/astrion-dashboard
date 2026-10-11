package com.custom.astrion.ui.ambient

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.material3.Text
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.ambient.scene.AmbientScene

/** The now-playing view: cover, backdrop, and the typeset text block. */
@Composable
fun NowPlayingAmbient(
    scene: AmbientScene,
    title: String,
    artist: String?,
    album: String?,
    statusLine: String,
    durationSec: Double,
    positionSec: Double,
    reportedAtMs: Long?,
    playing: Boolean,
    paused: Boolean,
    modifier: Modifier = Modifier,
) {
    // Pause dims cover, backdrop and text to ~60% over 600 ms.
    val dim by animateFloatAsState(if (paused) 0.6f else 1f, tween(600), label = "ambientDim")

    Box(modifier.fillMaxSize().background(Color(0xFF070809))) {
        Image(
            bitmap = scene.backdrop,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer { alpha = dim },
        )

        Column(Modifier.fillMaxSize().padding(horizontal = 40.dp)) {
            Spacer(Modifier.height(72.dp))
            // Cover: 400 px square, 8 px corners, nothing framing it.
            Image(
                bitmap = scene.cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(8.dp))
                    .graphicsLayer { alpha = dim },
            )

            Spacer(Modifier.height(22.dp))

            // Text, left-aligned to the cover's left edge.
            Column(Modifier.fillMaxWidth().graphicsLayer { alpha = dim }) {
                Text(
                    statusLine,
                    color = scene.inkFaint,
                    fontFamily = AstrionTheme.bodyFont,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    title,
                    color = scene.ink,
                    fontFamily = AstrionTheme.headingFont,
                    fontWeight = FontWeight.Normal,
                    fontSize = if (title.length > 36) 26.sp else 32.sp,
                    lineHeight = if (title.length > 36) 30.sp else 36.sp,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (!artist.isNullOrBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        artist,
                        color = scene.inkDim,
                        fontFamily = AstrionTheme.bodyFont,
                        fontWeight = FontWeight.Medium,
                        fontSize = 17.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!album.isNullOrBlank()) {
                    Spacer(Modifier.height(3.dp))
                    Text(
                        album,
                        color = scene.inkFaint,
                        fontFamily = AstrionTheme.bodyFont,
                        fontSize = 13.sp,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = TextStyle(fontFeatureSettings = "tnum"),
                    )
                }
                if (durationSec > 0) {
                    Spacer(Modifier.height(20.dp))
                    AmbientProgress(
                        positionSec = positionSec,
                        reportedAtMs = reportedAtMs,
                        durationSec = durationSec,
                        playing = playing,
                        fill = scene.ink,
                        track = scene.inkFaint.copy(alpha = 0.3f),
                        ink = scene.inkFaint,
                    )
                }
            }
        }
    }
}
