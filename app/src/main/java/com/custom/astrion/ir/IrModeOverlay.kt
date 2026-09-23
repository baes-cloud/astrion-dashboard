package com.custom.astrion.ir

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SettingsRemote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionButton
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.SectionLabel
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.StateKind
import com.custom.astrion.ui.StateLine
import com.custom.astrion.ui.Tone
import com.custom.astrion.ui.Touch

/**
 * The "IR Mode" modal.
 *
 * While this is up, MainActivity diverts the D-pad / volume / power / home /
 * back hardware buttons to [IrBlaster] instead of the Android-TV service
 * calls, so the remote behaves like a plain Samsung IR handset.
 *
 * The on-screen buttons are the opposite path: they go over the network via
 * the HA websocket, for the things IR is bad at (launching a specific app,
 * jumping straight to an input).
 *
 * Config (dashboard.json → "ir_mode"):
 * ```json
 * {
 *   "tv_entity":     "media_player.the_serif_qa55ls01dawxxy",
 *   "remote_entity": "remote.the_serif_qa55ls01dawxxy",
 *   "codes":   { "POWER": "0xE0E040BF" },
 *   "buttons": [ { "name": "Netflix", "app": "Netflix" },
 *                { "name": "HDMI",    "source": "HDMI" },
 *                { "name": "Mute",    "ir": "MUTE" } ]
 * }
 * ```
 */
@Composable
fun IrModeOverlay(
    options: Map<String, Any?>,
    client: HaClient,
    blaster: IrBlaster,
    lastKeyLabel: String?,
    onClose: () -> Unit,
) {
    @Suppress("UNCHECKED_CAST")
    val buttons = (options["buttons"] as? List<Map<String, Any?>>) ?: defaultButtons()
    val tvEntity = options["tv_entity"] as? String
    val remoteEntity = options["remote_entity"] as? String
    var toast by remember { mutableStateOf<String?>(null) }

    fun press(b: Map<String, Any?>) {
        val name = b["name"] as? String ?: "?"
        when {
            // Straight IR code by name, e.g. {"name":"Source","ir":"SOURCE"}
            b["ir"] != null -> {
                val code = IrBlaster.NAMED_CODES[(b["ir"] as String).uppercase()]
                    ?: IrBlaster.parseHex(b["ir"])
                toast = if (code != null && blaster.blast(code)) "Sent $name"
                        else "Failed: $name"
            }
            // Launch an app on the TV over the network.
            b["app"] != null && tvEntity != null -> {
                client.callService(
                    ServiceCall.of(
                        "media_player", "play_media", tvEntity,
                        "media_content_type" to "app",
                        "media_content_id" to (b["app"] as String),
                    )
                )
                toast = "Launching $name"
            }
            // Switch input/source over the network.
            b["source"] != null && tvEntity != null -> {
                client.callService(
                    ServiceCall.of(
                        "media_player", "select_source", tvEntity,
                        "source" to (b["source"] as String),
                    )
                )
                toast = "Input → $name"
            }
            // Any remote.send_command payload.
            b["command"] != null && remoteEntity != null -> {
                client.callService(
                    ServiceCall.of(
                        "remote", "send_command", remoteEntity,
                        "command" to (b["command"] as String),
                    )
                )
                toast = "Sent $name"
            }
            // Fallback: an arbitrary service call.
            b["service"] != null -> {
                val svc = b["service"] as String
                client.callService(
                    ServiceCall(
                        domain = svc.substringBefore('.'),
                        service = svc.substringAfter('.'),
                        entityId = b["entity_id"] as? String ?: tvEntity,
                    )
                )
                toast = "Sent $name"
            }
            else -> toast = "$name not configured"
        }
    }

    // NOT a Dialog: a Dialog owns its own window and would take input focus
    // away from MainActivity, so dispatchKeyEvent would stop firing and the
    // hardware buttons could never be intercepted. Rendering in-content keeps
    // key focus on the Activity.
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AstrionTheme.scrim)
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = {}, // swallow taps on the scrim
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Space.l)
                .clip(RoundedCornerShape(Radius.sheet))
                .background(AstrionTheme.cardBg)
                .border(2.dp, AstrionTheme.irBadge, RoundedCornerShape(Radius.sheet))
                .padding(Space.l),
            verticalArrangement = Arrangement.spacedBy(Space.m),
        ) {
            // Header — unmistakable that the remote is in a different mode.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Space.m),
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(AstrionTheme.irBadge),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.SettingsRemote, contentDescription = null,
                        tint = AstrionTheme.irBadgeInk, modifier = Modifier.size(24.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text("IR MODE", color = AstrionTheme.on, style = AstrionType.shout)
                    Text(
                        "Hardware buttons blast to the TV",
                        color = AstrionTheme.textSecondary, style = AstrionType.label,
                    )
                }
            }

            // Capability line — makes a dead emitter obvious instead of silent.
            StateLine(
                blaster.statusLine(),
                if (blaster.available) StateKind.Good else StateKind.Danger,
            )

            Divider()

            // Live feedback of the last hardware button that was blasted.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = Touch.min)
                    .clip(RoundedCornerShape(Radius.control))
                    .background(AstrionTheme.controlSunken)
                    .padding(horizontal = Space.m),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    toast ?: lastKeyLabel ?: "Press a hardware button…",
                    color = if (toast != null) AstrionTheme.on else AstrionTheme.textOnControl,
                    style = AstrionType.bodyStrong,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            SectionLabel("Network controls")

            // On-screen buttons → HA over the network.
            buttons.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Space.s),
                ) {
                    row.forEach { b ->
                        AstrionButton(
                            onClick = { press(b) },
                            modifier = Modifier.weight(1f),
                            label = b["name"] as? String ?: "?",
                            textStyle = AstrionType.label,
                        )
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            Divider()

            AstrionButton(
                onClick = onClose,
                modifier = Modifier.fillMaxWidth(),
                label = "Exit IR Mode",
                tone = Tone.Danger,
                description = "Exit IR Mode (or press Menu again)",
            )
        }
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(AstrionTheme.divider),
    )
}

/** Sensible starting set if `ir_mode.buttons` isn't configured. */
private fun defaultButtons(): List<Map<String, Any?>> = listOf(
    mapOf("name" to "Netflix", "app" to "Netflix"),
    mapOf("name" to "YouTube", "app" to "YouTube"),
    mapOf("name" to "Plex", "app" to "Plex"),
    mapOf("name" to "TV", "source" to "TV"),
    mapOf("name" to "HDMI", "source" to "HDMI"),
    mapOf("name" to "Source", "ir" to "SOURCE"),
    mapOf("name" to "Menu", "ir" to "MENU"),
    mapOf("name" to "Guide", "ir" to "GUIDE"),
    mapOf("name" to "Exit", "ir" to "EXIT"),
)
