package com.custom.astrion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/**
 * In-window overlays ("sheets").
 *
 * The app used to open three popups as `Dialog`s. A Dialog owns its own
 * window, so while one was open MainActivity.dispatchKeyEvent stopped seeing
 * the hardware buttons: volume changed the Android system volume instead of
 * the Sonos, page buttons did nothing. Everything modal now renders in the
 * Activity's own window through [OverlayController], hosted once at the root
 * by MainActivity, so the physical buttons keep working and BACK closes the
 * sheet.
 */
@Stable
class OverlayController {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
        private set

    val isOpen: Boolean get() = content != null

    /** Replace whatever is open with [sheet]. */
    fun show(sheet: @Composable () -> Unit) {
        content = sheet
    }

    fun dismiss() {
        content = null
    }
}

/** Provided by MainActivity. The default is an inert controller (previews). */
val LocalOverlay = staticCompositionLocalOf { OverlayController() }

/** Draws the open sheet, if any. Place last in the root Box. */
@Composable
fun OverlayHost(controller: OverlayController) {
    controller.content?.invoke()
}

/**
 * A bottom sheet: anchored where the thumb already is, rounded top corners,
 * a title row with a 48dp close button, scrollable body. Tapping the scrim
 * closes it. One scrim layer, no animation.
 */
@Composable
fun AstrionSheet(
    onDismiss: () -> Unit,
    title: String? = null,
    subtitle: String? = null,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val maxH = (LocalConfiguration.current.screenHeightDp * 0.9f).dp
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AstrionTheme.scrim)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.BottomCenter,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = maxH)
                .clip(RoundedCornerShape(topStart = Radius.sheet, topEnd = Radius.sheet))
                .background(AstrionTheme.cardBg)
                // Swallow taps on the panel so they don't reach the scrim.
                .clickable(
                    indication = null,
                    interactionSource = remember { MutableInteractionSource() },
                    onClick = {},
                )
                .padding(start = Space.l, end = Space.s, top = Space.s, bottom = Space.l),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    if (title != null) {
                        Text(
                            title, style = AstrionType.headline, color = AstrionTheme.textPrimary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                    if (subtitle != null) {
                        Text(
                            subtitle, style = AstrionType.label, color = AstrionTheme.textSecondary,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                IconAction(Icons.Filled.Close, "Close", onDismiss, tone = Tone.Ghost)
            }
            Spacer(Modifier.size(Space.xs))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(end = Space.s)
                    .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier),
                verticalArrangement = Arrangement.spacedBy(Space.m),
                content = content,
            )
        }
    }
}

/**
 * A list of options as 48dp rows, the current one ticked. Replaces the
 * Material DropdownMenus (which are popup windows too, and steal key focus).
 */
@Composable
fun OptionList(
    options: List<String>,
    selected: String?,
    label: (String) -> String = { it },
    onPick: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
        options.forEach { opt ->
            val isSel = opt == selected
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Touch.min)
                    .clip(RoundedCornerShape(Radius.control))
                    .background(if (isSel) AstrionTheme.raised else AstrionTheme.cardBg)
                    .tap { onPick(opt) }
                    .padding(horizontal = Space.m),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    label(opt),
                    style = if (isSel) AstrionType.bodyStrong else AstrionType.body,
                    color = if (isSel) AstrionTheme.accent else AstrionTheme.textPrimary,
                    maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (isSel) {
                    Spacer(Modifier.width(Space.s))
                    Icon(Icons.Filled.Check, contentDescription = "Selected", tint = AstrionTheme.accent)
                }
            }
        }
    }
}
