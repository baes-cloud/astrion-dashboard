package com.custom.astrion.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

/**
 * Popups drawn inside the main window rather than as Android Dialogs.
 *
 * A Dialog is a separate window: while one was up it took key focus, so the
 * hardware buttons (volume, page keys, colour keys) did nothing, and its
 * touches never reached MainActivity, so the screensaver's idle timer ran out
 * behind it. The IR, voice and alarm overlays were already in-window for the
 * same reasons; this gives the light, vacuum and media-browser popups the
 * same treatment through one host.
 */
class SheetHost {
    internal class Entry(val dismiss: () -> Unit, val bottom: Boolean, val content: @Composable () -> Unit)

    internal val entries = mutableStateListOf<Entry>()

    val isShowing: Boolean get() = entries.isNotEmpty()

    /** Dismiss every popup (a page key took you elsewhere). */
    fun dismissAll() {
        entries.toList().asReversed().forEach { it.dismiss() }
    }

    /** Dismiss the top popup (BACK). True if there was one. */
    fun dismissTop(): Boolean {
        val top = entries.lastOrNull() ?: return false
        top.dismiss()
        return true
    }

    /** Draws the popups. Place it above the dashboard, below the screensaver. */
    @Composable
    fun Host() {
        val top = entries.lastOrNull() ?: return
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(SCRIM)
                .clickable(remember { MutableInteractionSource() }, indication = null) { top.dismiss() },
            contentAlignment = if (top.bottom) Alignment.BottomCenter else Alignment.Center,
        ) {
            // Eats taps that land on the popup's padding, so only the scrim dismisses.
            val eat = Modifier.clickable(remember { MutableInteractionSource() }, indication = null) {}
            if (top.bottom) {
                val rise = remember(top) { Animatable(1f) }
                LaunchedEffect(top) { rise.animateTo(0f, tween(SHEET_RISE_MS)) }
                Box(
                    Modifier
                        .fillMaxWidth()
                        .graphicsLayer { translationY = rise.value * size.height }
                        .then(eat),
                ) {
                    top.content()
                }
            } else {
                Box(Modifier.padding(16.dp).then(eat)) {
                    top.content()
                }
            }
        }
    }

    private companion object {
        /** Same scrim as the IR and alert overlays. */
        val SCRIM: Color @Composable @ReadOnlyComposable get() = AstrionTheme.colors.scrim
        const val SHEET_RISE_MS = 240
    }
}

val LocalSheetHost = staticCompositionLocalOf<SheetHost?> { null }

/**
 * Drop-in for [Dialog]: shows [content] in the window's [SheetHost] for as
 * long as this is composed, or falls back to a real Dialog where there's no
 * host. [bottom] docks it to the bottom edge as a sheet.
 */
@Composable
fun InWindowDialog(onDismissRequest: () -> Unit, bottom: Boolean = false, content: @Composable () -> Unit) {
    val host = LocalSheetHost.current
    if (host == null) {
        Dialog(onDismissRequest = onDismissRequest, content = content)
        return
    }
    val latestContent = rememberUpdatedState(content)
    val latestDismiss = rememberUpdatedState(onDismissRequest)
    DisposableEffect(host) {
        val entry = SheetHost.Entry({ latestDismiss.value() }, bottom) { latestContent.value() }
        host.entries += entry
        onDispose { host.entries -= entry }
    }
}

/**
 * A bottom sheet: grab handle, a title (and a line under it) beside a close
 * button, then [content]. Shown through [InWindowDialog], so BACK and the
 * scrim close it as before.
 */
@Composable
fun BottomSheet(
    title: String,
    onDismissRequest: () -> Unit,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    InWindowDialog(onDismissRequest = onDismissRequest, bottom = true) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp))
                .background(AstrionTheme.cardBg)
                .padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .size(width = 32.dp, height = 3.dp)
                    .clip(CircleShape)
                    .background(AstrionTheme.colors.hairlineStrong),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        color = AstrionTheme.textPrimary,
                        fontSize = AstrionTheme.size.title,
                        fontWeight = FontWeight.ExtraBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Text(
                            subtitle,
                            color = AstrionTheme.textSecondary,
                            fontSize = AstrionTheme.size.small,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(AstrionTheme.raised)
                        .tap(onClick = onDismissRequest),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Glyphs.close, contentDescription = "Close", tint = AstrionTheme.textSecondary, modifier = Modifier.size(17.dp))
                }
            }
            content()
        }
    }
}
