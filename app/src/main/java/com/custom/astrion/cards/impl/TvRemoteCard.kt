package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionButton
import com.custom.astrion.ui.AstrionCard
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.IconAction
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.Tone
import com.custom.astrion.ui.rememberAction

/**
 * TV / Android-TV remote card.
 *
 * Reuses your existing Home Assistant `remote.*` command map — the same
 * mechanism your working aiks-tv-card uses. Each button fires:
 *   remote.send_command  with { entity_id, command }
 * against your Android TV remote entity, e.g. remote.android_tv_10_0_1_248.
 *
 * Unlike the stock card, the layout is fully yours: a D-pad cluster, a transport
 * row, and volume — all native Compose, resized for the 480x800 panel.
 *
 * Config shape:
 *   CardConfig("tv_remote", mapOf(
 *       "name"         to "TVn",
 *       "remote_entity" to "remote.android_tv_10_0_1_248",
 *       // optional: a separate entity for mute if it differs
 *       "mute_entity"   to "remote.the_club_tvv",
 *   ))
 *
 * Command values below match the ones in your existing config (UP/DOWN/LEFT/
 * RIGHT/CENTER/BACK/HOME/MENU/PLAY/PAUSE/VOLUME_UP/VOLUME_DOWN/POWER, etc.).
 */
class TvRemoteCard : CardRenderer {
    override val type = "tv_remote"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val remoteEntity = config.string("remote_entity") ?: return
        val muteEntity = config.string("mute_entity") ?: remoteEntity
        val name = config.string("name") ?: "TV"

        // Per-button command names, overridable from config. Defaults match an
        // Android TV `remote.send_command` entity (DPAD_*, HOME, BACK, ...).
        val commands = (config.options["commands"] as? Map<String, Any?>).orEmpty()
        fun c(key: String, default: String): String = (commands[key] as? String) ?: default

        val action = rememberAction(ctx)
        val live = ctx.connected && ctx.entity(remoteEntity)?.isUnavailable != true

        fun send(command: String, entity: String = remoteEntity) {
            action.run(
                ServiceCall.of(
                    domain = "remote",
                    service = "send_command",
                    entityId = entity,
                    "command" to command,
                )
            )
        }

        // App-launch buttons. Each entry is either { name, app } (launched via
        // media_player.play_media on `media_entity`) or an explicit
        // { name, service: "domain.service", entity_id, data }.
        val mediaEntity = config.string("media_entity") ?: "media_player.android_tv_10_0_1_248"
        val apps = (config.options["apps"] as? List<Map<String, Any?>>) ?: DEFAULT_APPS

        fun launch(app: Map<String, Any?>) {
            val service = app["service"] as? String
            if (service != null) {
                val domain = service.substringBefore('.')
                val svc = service.substringAfter('.')
                val entity = app["entity_id"] as? String
                val data = (app["data"] as? Map<String, Any?>).orEmpty()
                action.run(
                    ServiceCall.of(domain, svc, entity, *data.entries.map { it.key to it.value }.toTypedArray())
                )
            } else {
                val appId = app["app"] as? String ?: return
                action.run(
                    ServiceCall.of(
                        "media_player", "play_media", mediaEntity,
                        "media_content_type" to "app",
                        "media_content_id" to appId,
                    )
                )
            }
        }

        AstrionCard {
            Column(verticalArrangement = Arrangement.spacedBy(Space.card)) {
                // Header row: name + power
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name, style = AstrionType.headline, color = AstrionTheme.textPrimary)
                    IconAction(
                        Icons.Filled.PowerSettingsNew, "TV power", { send(c("power", "POWER")) },
                        tone = Tone.Danger, enabled = live, pending = action.busy, failed = action.failed,
                    )
                }

                DPad(
                    enabled = live,
                    onUp = { send(c("up", "DPAD_UP")) },
                    onDown = { send(c("down", "DPAD_DOWN")) },
                    onLeft = { send(c("left", "DPAD_LEFT")) },
                    onRight = { send(c("right", "DPAD_RIGHT")) },
                    onCenter = { send(c("center", "DPAD_CENTER")) },
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                ) {
                    IconAction(Icons.Filled.ArrowBack, "Back", { send(c("back", "BACK")) }, size = 52.dp, enabled = live)
                    IconAction(Icons.Filled.Home, "Home", { send(c("home", "HOME")) }, size = 52.dp, enabled = live)
                    IconAction(Icons.Filled.Menu, "Menu", { send(c("menu", "MENU")) }, size = 52.dp, enabled = live)
                }

                apps.chunked(2).forEach { pair ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Space.gutter),
                    ) {
                        pair.forEach { app ->
                            AstrionButton(
                                onClick = { launch(app) },
                                modifier = Modifier.weight(1f),
                                label = app["name"] as? String ?: "App",
                                enabled = live,
                            )
                        }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        }
    }

    private companion object {
        val DEFAULT_APPS = listOf(
            mapOf("name" to "Netflix", "app" to "com.netflix.ninja"),
            mapOf("name" to "Plex", "app" to "com.plexapp.android"),
            mapOf("name" to "ABC iView", "app" to "au.net.abc.iview"),
            mapOf("name" to "VLC", "app" to "org.videolan.vlc"),
        )
    }

    @Composable
    private fun DPad(
        enabled: Boolean,
        onUp: () -> Unit,
        onDown: () -> Unit,
        onLeft: () -> Unit,
        onRight: () -> Unit,
        onCenter: () -> Unit,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(188.dp)
                .clip(RoundedCornerShape(percent = 50))
                .background(AstrionTheme.controlSunken),
            contentAlignment = Alignment.Center,
        ) {
            Box(Modifier.align(Alignment.TopCenter).padding(top = Space.m)) {
                IconAction(Icons.Filled.KeyboardArrowUp, "Up", onUp, size = 52.dp, enabled = enabled)
            }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = Space.m)) {
                IconAction(Icons.Filled.KeyboardArrowDown, "Down", onDown, size = 52.dp, enabled = enabled)
            }
            Box(Modifier.align(Alignment.CenterStart).padding(start = Space.m)) {
                IconAction(Icons.Filled.KeyboardArrowLeft, "Left", onLeft, size = 52.dp, enabled = enabled)
            }
            Box(Modifier.align(Alignment.CenterEnd).padding(end = Space.m)) {
                IconAction(Icons.Filled.KeyboardArrowRight, "Right", onRight, size = 52.dp, enabled = enabled)
            }
            AstrionButton(
                onClick = onCenter,
                modifier = Modifier.size(68.dp),
                label = "OK",
                tone = Tone.Accent,
                enabled = enabled,
                height = 68.dp,
                shape = CircleShape,
                description = "OK",
            )
        }
    }
}
