package com.custom.astrion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
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
    internal class Entry(val dismiss: () -> Unit, val content: @Composable () -> Unit)

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
            contentAlignment = Alignment.Center,
        ) {
            // Eats taps that land on the popup's padding, so only the scrim dismisses.
            Box(
                Modifier
                    .padding(16.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {},
            ) {
                top.content()
            }
        }
    }

    private companion object {
        /** Same scrim as the IR and alert overlays. */
        val SCRIM = Color(0xCC0B1015)
    }
}

val LocalSheetHost = staticCompositionLocalOf<SheetHost?> { null }

/**
 * Drop-in for [Dialog]: shows [content] in the window's [SheetHost] for as
 * long as this is composed, or falls back to a real Dialog where there's no
 * host.
 */
@Composable
fun InWindowDialog(onDismissRequest: () -> Unit, content: @Composable () -> Unit) {
    val host = LocalSheetHost.current
    if (host == null) {
        Dialog(onDismissRequest = onDismissRequest, content = content)
        return
    }
    val latestContent = rememberUpdatedState(content)
    val latestDismiss = rememberUpdatedState(onDismissRequest)
    DisposableEffect(host) {
        val entry = SheetHost.Entry({ latestDismiss.value() }) { latestContent.value() }
        host.entries += entry
        onDispose { host.entries -= entry }
    }
}
