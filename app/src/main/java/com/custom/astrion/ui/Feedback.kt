package com.custom.astrion.ui

import android.os.SystemClock
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * The app-wide feedback strip: one short line at the bottom of the screen,
 * where the eye already is because the thumb is there.
 *
 *  - Error: a Home Assistant call was rejected, timed out, or couldn't be
 *    sent (socket down). Every failure lands here, including hardware-button
 *    actions, so nothing fails silently.
 *  - Info: a hardware hold / double-tap fired ("Hold LIGHT · Long lights"),
 *    or a control explains itself ("Hold to unlock").
 *
 * Successes stay quiet — the entity changing on screen is the confirmation.
 */
enum class FeedbackKind { Info, Success, Error }

data class FeedbackMessage(val text: String, val kind: FeedbackKind, val id: Long)

@Stable
class FeedbackController {
    var current by mutableStateOf<FeedbackMessage?>(null)
        private set

    fun show(text: String, kind: FeedbackKind = FeedbackKind.Info) {
        current = FeedbackMessage(text, kind, SystemClock.uptimeMillis())
    }

    fun error(text: String) = show(text, FeedbackKind.Error)

    internal fun clear(id: Long) {
        if (current?.id == id) current = null
    }
}

val LocalFeedback = staticCompositionLocalOf { FeedbackController() }

/** Render at the bottom of the root Box. */
@Composable
fun FeedbackStrip(controller: FeedbackController, modifier: Modifier = Modifier) {
    val msg = controller.current ?: return
    LaunchedEffect(msg.id) {
        delay(if (msg.kind == FeedbackKind.Error) 4000L else 2200L)
        controller.clear(msg.id)
    }
    val (bg, fg, icon) = when (msg.kind) {
        FeedbackKind.Error -> Triple(AstrionTheme.dangerStrong, AstrionTheme.dangerInk, Icons.Filled.ErrorOutline)
        FeedbackKind.Success -> Triple(AstrionTheme.goodWell, AstrionTheme.good, Icons.Filled.CheckCircle)
        FeedbackKind.Info -> Triple(AstrionTheme.raised, AstrionTheme.textPrimary, Icons.Filled.Info)
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Space.gutter, vertical = Space.gutter)
            .heightIn(min = 44.dp)
            .clip(RoundedCornerShape(Radius.control))
            .background(bg)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(Space.s))
        Text(msg.text, style = AstrionType.bodyStrong, color = fg, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}
