package com.custom.astrion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.config.AppConfig
import com.custom.astrion.config.HotkeyConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Touch navigation and the button map.
 *
 * Pager swipe stays off (horizontal drags belong to sliders and shelves), and
 * the old page dots were removed — which left the touchscreen with no way to
 * change page at all. Now every page header is a button: tap it for the page
 * picker, which also leads to "What do the buttons do?", a live map of every
 * physical button generated from the current config.
 */
@Stable
class DashboardNav(
    val config: AppConfig,
    private val pager: PagerState,
    private val scope: CoroutineScope,
    private val overlay: OverlayController,
) {
    val pageNames: List<String> get() = config.pages.map { it.name }

    /** Current page index (reads pager state, so it's observable). */
    val current: Int get() = pager.currentPage

    fun goTo(index: Int) {
        if (index !in config.pages.indices) return
        scope.launch { pager.scrollToPage(index) }
    }

    fun openPicker() {
        overlay.show { PagePickerSheet(this, onDismiss = { overlay.dismiss() }) }
    }

    fun openKeyMap() {
        overlay.show { KeyMapSheet(config, onDismiss = { overlay.dismiss() }) }
    }
}

/** Null outside a Dashboard (e.g. a card rendered in isolation). */
val LocalNav = staticCompositionLocalOf<DashboardNav?> { null }

/**
 * Header used on pages that don't carry a `clock_header` card, so touch
 * navigation exists on every page regardless of config.
 */
@Composable
fun NavHeader(title: String) {
    val nav = LocalNav.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = Touch.compact)
            .clip(RoundedCornerShape(Radius.control))
            .tap(enabled = nav != null, onClickLabel = "Change page") { nav?.openPicker() }
            .padding(horizontal = Space.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = AstrionType.header, color = AstrionTheme.textPrimary)
        Icon(
            Icons.Filled.ExpandMore, contentDescription = null,
            tint = AstrionTheme.textSecondary, modifier = Modifier.size(20.dp),
        )
    }
}

// ---- page picker --------------------------------------------------------------

@Composable
fun PagePickerSheet(nav: DashboardNav, onDismiss: () -> Unit) {
    val current = nav.current
    AstrionSheet(onDismiss = onDismiss, title = "Go to page") {
        Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
            nav.pageNames.forEachIndexed { i, name ->
                val keys = nav.config.hotkeys
                    .filter { it.page?.equals(name, ignoreCase = true) == true }
                    .map { keyLabel(it.key) }
                val isCurrent = i == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 56.dp)
                        .clip(RoundedCornerShape(Radius.control))
                        .background(if (isCurrent) AstrionTheme.raised else AstrionTheme.cardBg)
                        .tap {
                            nav.goTo(i)
                            onDismiss()
                        }
                        .padding(horizontal = Space.m),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            name, style = AstrionType.title,
                            color = if (isCurrent) AstrionTheme.accent else AstrionTheme.textPrimary,
                        )
                        if (keys.isNotEmpty()) {
                            Text(
                                keys.joinToString(" · ") { "$it button" },
                                style = AstrionType.label, color = AstrionTheme.textSecondary,
                            )
                        }
                    }
                    if (isCurrent) {
                        Icon(Icons.Filled.Check, contentDescription = "Current page", tint = AstrionTheme.accent)
                    }
                }
            }
        }
        AstrionButton(
            onClick = { nav.openKeyMap() },
            modifier = Modifier.fillMaxWidth(),
            label = "What do the buttons do?",
            icon = Icons.Filled.SettingsRemote,
            tone = Tone.Neutral,
        )
    }
}

// ---- key map ----------------------------------------------------------------------

/** Friendly name for a HardwareKey name as printed / felt on the remote. */
fun keyLabel(key: String): String = when (key.uppercase()) {
    "LIGHT" -> "Light"
    "CURTAIN" -> "Curtain"
    "SCENE" -> "Music"
    "AC" -> "Aircon"
    "CUSTOM_1" -> "Red"
    "CUSTOM_2" -> "Green"
    "CUSTOM_3" -> "Blue"
    "CUSTOM_4" -> "Yellow"
    "UP" -> "Up"
    "DOWN" -> "Down"
    "LEFT" -> "Left"
    "RIGHT" -> "Right"
    "CENTER" -> "OK"
    "PAGE_UP" -> "CH ▲"
    "PAGE_DOWN" -> "CH ▼"
    "VOLUME_UP" -> "Vol +"
    "VOLUME_DOWN" -> "Vol −"
    "MUTE" -> "Mute"
    "MENU" -> "Menu ☰"
    "VOICE" -> "Mic"
    "HOME" -> "Home"
    "BACK" -> "Back"
    "POWER" -> "Power"
    else -> key.humanise()
}

private fun keyDot(key: String): Color? = when (key.uppercase()) {
    "CUSTOM_1" -> AstrionTheme.keyRed
    "CUSTOM_2" -> AstrionTheme.keyGreen
    "CUSTOM_3" -> AstrionTheme.keyBlue
    "CUSTOM_4" -> AstrionTheme.keyYellow
    else -> null
}

private val APP_NAMES = mapOf(
    "com.netflix.ninja" to "Netflix",
    "com.plexapp.android" to "Plex",
    "au.net.abc.iview" to "ABC iView",
    "org.videolan.vlc" to "VLC",
    "com.google.android.youtube.tv" to "YouTube",
)

/** "script.long_lights" → "Long lights"; "light.kitchen" → "Kitchen". */
private fun idLabel(id: String): String = id.substringAfter('.').humanise()

/** One action in words, e.g. "Open Main", "TV · Dpad up", "Long lights". */
fun describeAction(hk: HotkeyConfig): String {
    hk.page?.let { return "Open $it" }
    val service = hk.service ?: return "Nothing"
    val data = hk.data
    val entity = hk.entityId
    return when (service) {
        "astrion.toggle_mute" -> "Mute / unmute speakers"
        "astrion.unjoin_others" -> "Ungroup other speakers"
        "remote.send_command" -> "TV · " + ((data["command"] as? String)?.humanise() ?: "command")
        "media_player.select_source" -> {
            val src = data["source"] as? String ?: "source"
            "Launch " + (APP_NAMES[src] ?: src)
        }
        "media_player.volume_up" -> "Volume up"
        "media_player.volume_down" -> "Volume down"
        "media_player.media_play_pause" -> "Play / pause"
        "media_player.media_next_track" -> "Next track"
        "media_player.media_previous_track" -> "Previous track"
        "script.turn_on", "scene.turn_on" -> entity?.let(::idLabel) ?: "Run script"
        else -> if (service.startsWith("script.")) {
            idLabel(service)
        } else {
            service.substringAfter('.').humanise() + (entity?.let { " · " + idLabel(it) } ?: "")
        }
    }
}

/** An action plus its `then` chain. */
fun describeHotkey(hk: HotkeyConfig): String =
    (listOf(describeAction(hk)) + hk.then.map { describeAction(it) }).joinToString(" + ")

private val KEY_GROUPS = listOf(
    "Shortcut buttons" to listOf("LIGHT", "CURTAIN", "SCENE", "AC"),
    "Coloured buttons" to listOf("CUSTOM_1", "CUSTOM_2", "CUSTOM_3", "CUSTOM_4"),
    "D-pad" to listOf("UP", "DOWN", "LEFT", "RIGHT", "CENTER", "BACK", "HOME"),
    "Rockers" to listOf("VOLUME_UP", "VOLUME_DOWN", "PAGE_UP", "PAGE_DOWN"),
    "Other" to listOf("MUTE", "MENU", "VOICE", "POWER"),
)

/**
 * Every physical button and what tap / hold / double-tap does, generated
 * from the live config — so it's always right after a dashboard.json edit.
 */
@Composable
fun KeyMapSheet(config: AppConfig, onDismiss: () -> Unit) {
    val ir = config.options["ir_mode"] as? Map<*, *>
    val irKey = (ir?.get("toggle_key") as? String)?.uppercase() ?: "MENU"
    val irLong = (ir?.get("toggle_long") as? Boolean) ?: false

    fun linesFor(key: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        config.hotkeys.firstOrNull { it.key.equals(key, true) }?.let { out += "Tap" to describeHotkey(it) }
        config.longHotkeys.firstOrNull { it.key.equals(key, true) }?.let { out += "Hold" to describeHotkey(it) }
        config.doubleHotkeys.firstOrNull { it.key.equals(key, true) }?.let { out += "Double" to describeHotkey(it) }
        if (key == irKey) {
            out += (if (irLong) "Hold" else "Tap") to "IR Mode on / off"
            val holdTaken = irLong || config.longHotkeys.any { it.key.equals(key, true) }
            if (!holdTaken) out += "Hold" to "Show this button map"
        }
        if (key == "VOICE" && out.isEmpty()) out += "Tap" to "Talk to Home Assistant"
        return out
    }

    AstrionSheet(
        onDismiss = onDismiss,
        title = "Remote buttons",
        subtitle = "Hold = press for 1.5 s",
    ) {
        KEY_GROUPS.forEach { (group, keys) ->
            val rows = keys.map { it to linesFor(it) }.filter { it.second.isNotEmpty() }
            if (rows.isEmpty()) return@forEach
            SectionLabel(group)
            Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                rows.forEach { (key, lines) -> KeyRow(key, lines) }
            }
        }
    }
}

@Composable
private fun KeyRow(key: String, lines: List<Pair<String, String>>) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.control))
            .background(AstrionTheme.raised)
            .padding(horizontal = Space.m, vertical = Space.s),
        verticalAlignment = Alignment.Top,
    ) {
        Row(Modifier.width(84.dp), verticalAlignment = Alignment.CenterVertically) {
            keyDot(key)?.let { c ->
                Box(Modifier.size(10.dp).clip(CircleShape).background(c))
                Spacer(Modifier.width(Space.xs))
            }
            Text(keyLabel(key), style = AstrionType.bodyStrong, color = AstrionTheme.textPrimary, maxLines = 1)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(Space.xxs)) {
            lines.forEach { (gesture, what) ->
                Row {
                    Text(
                        gesture, style = AstrionType.label, color = AstrionTheme.accent,
                        modifier = Modifier.width(52.dp),
                    )
                    Text(
                        what, style = AstrionType.label, color = AstrionTheme.textSecondary,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}
