package com.custom.astrion.ir

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.tap

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
            .background(Color(0xCC050B0D))
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
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xFF1C3740))
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Header — unmistakable that the remote is in a different mode.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Box(
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE0663A)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.SettingsRemote, contentDescription = null,
                        tint = Color(0xFF20120C), modifier = Modifier.size(22.dp),
                    )
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        "IR MODE",
                        color = Color(0xFFFFC24B), fontSize = 19.sp,
                        fontWeight = FontWeight.Bold, letterSpacing = 2.sp,
                    )
                    Text(
                        "Hardware buttons blast to the TV",
                        color = Color(0xFF9FBAC0), fontSize = 12.sp,
                    )
                }
            }

            // Capability line — makes a dead emitter obvious instead of silent.
            val statusColor = if (blaster.available) Color(0xFF5FD3A0) else Color(0xFFE06767)
            Text(blaster.statusLine(), color = statusColor, fontSize = 11.sp)

            Divider()

            // Live feedback of the last hardware button that was blasted.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF14262D))
                    .padding(vertical = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    toast ?: lastKeyLabel ?: "Press a hardware button…",
                    color = if (toast != null) Color(0xFFFFC24B) else Color(0xFFCBDCE0),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                "NETWORK CONTROLS",
                color = Color(0xFF9FBAC0), fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
            )

            // On-screen buttons → HA over the network.
            buttons.chunked(3).forEach { row ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    row.forEach { b ->
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(Color(0xFF2C4D59))
                                .tap { press(b) },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                b["name"] as? String ?: "?",
                                color = Color(0xFFE6F0F1), fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                maxLines = 1, overflow = TextOverflow.Ellipsis,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }

            Divider()

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF3A2E2E))
                    .tap(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Exit IR Mode",
                    color = Color(0xFFE79A9A), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

@Composable
private fun Divider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(Color(0x332C4D59)),
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
