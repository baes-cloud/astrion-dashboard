package com.custom.astrion.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/** One short message; [id] makes a repeat of the same text show again. */
data class ToastMessage(val id: Long, val text: String, val detail: String? = null, val bad: Boolean = false)

/**
 * A small pill near the bottom of the screen confirming what a button did,
 * or saying it didn't work. Hardware-key actions had no visible result at all
 * (28% of music actions were repeated within a minute), and failed or offline
 * calls vanished silently.
 */
@Composable
fun ActionToast(message: ToastMessage?, onGone: () -> Unit) {
    LaunchedEffect(message?.id) {
        if (message != null) {
            delay(if (message.bad) 3_500 else 1_600)
            onGone()
        }
    }
    Box(Modifier.fillMaxSize().padding(bottom = 28.dp), contentAlignment = Alignment.BottomCenter) {
        AnimatedVisibility(visible = message != null, enter = fadeIn(), exit = fadeOut()) {
            val m = message ?: return@AnimatedVisibility
            Column(
                modifier = Modifier
                    .widthIn(max = 320.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(if (m.bad) AstrionTheme.dangerBg else AstrionTheme.raised)
                    .padding(horizontal = 16.dp, vertical = 9.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    m.text,
                    color = if (m.bad) AstrionTheme.danger else AstrionTheme.textPrimary,
                    fontSize = AstrionTheme.body,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (m.detail != null) {
                    Text(
                        m.detail,
                        color = AstrionTheme.textSecondary,
                        fontSize = AstrionTheme.label,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
