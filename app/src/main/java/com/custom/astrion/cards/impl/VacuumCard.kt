package com.custom.astrion.cards.impl

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionCard
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.HoldButton
import com.custom.astrion.ui.IconAction
import com.custom.astrion.ui.ImageCache
import com.custom.astrion.ui.LocalFeedback
import com.custom.astrion.ui.OptionList
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.SectionLabel
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.StateKind
import com.custom.astrion.ui.StateLine
import com.custom.astrion.ui.Tone
import com.custom.astrion.ui.Touch
import com.custom.astrion.ui.rememberAction
import com.custom.astrion.ui.rememberRemoteBitmap
import com.custom.astrion.ui.tap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Robot vacuum: live map, start / pause / dock / locate, cleaning mode, and
 * per-room cleaning.
 *
 * Room buttons are press-and-HOLD: a mis-tap used to send the robot off
 * across the flat at night. The cleaning-mode picker is an inline list (it
 * was a Material dropdown — its own popup window, which took the hardware
 * keys away while open).
 *
 * Config: { "type": "vacuum", "options": {
 *     "entity_id": "vacuum.roborock", "name": "Vacuum",
 *     "map_image": "image.roborock_map", "map_rotation": 90, "map_height": 200,
 *     "rooms": [ { "name": "Kitchen", "id": 18 }, … ] } }
 *
 * The same options map is the floorplan's `vacuum` overlay block; tapping the
 * robot there opens [VacuumPanelContent] in a sheet.
 */
class VacuumCard : CardRenderer {
    override val type = "vacuum"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        AstrionCard {
            Column(verticalArrangement = Arrangement.spacedBy(Space.m)) {
                VacuumPanelContent(config.options, ctx)
            }
        }
    }
}

/** The vacuum panel's content, no outer container. */
@Suppress("UNCHECKED_CAST")
@Composable
fun VacuumPanelContent(options: Map<String, Any?>, ctx: CardContext) {
    val entityId = options["entity_id"] as? String ?: return
    val e = ctx.entity(entityId)
    val state = e?.state ?: "unknown"
    val name = options["name"] as? String ?: e?.friendlyName ?: "Vacuum"
    val fanSpeed = e?.attrString("fan_speed")
    val fanList = e?.attrStringList("fan_speed_list") ?: emptyList()
    val mapEntity = options["map_image"] as? String
    val mapHeight = (options["map_height"] as? Number)?.toInt() ?: 200
    val rooms = (options["rooms"] as? List<Map<String, Any?>>) ?: emptyList()
    val rotation = (options["map_rotation"] as? Number)?.toInt() ?: 0
    val unavailable = e == null || e.isUnavailable
    val live = !unavailable && ctx.connected
    val feedback = LocalFeedback.current

    val mapPic = mapEntity?.let { ctx.entity(it)?.attrString("entity_picture") }
    // Rotated once per (map url, rotation) and cached — it used to be
    // re-fetched and re-rotated every time the popup opened.
    val mapKey = mapPic?.let { "vacmap:${ctx.client.authedUrl(it)}@$rotation" }
    val mapBmp by rememberRemoteBitmap(mapKey) {
        val pic = mapPic ?: return@rememberRemoteBitmap null
        val key = mapKey ?: return@rememberRemoteBitmap null
        val fetched = ctx.client.fetchBitmap(pic, targetPx = 720) ?: return@rememberRemoteBitmap null
        val out = if (rotation == 0) fetched else withContext(Dispatchers.Default) {
            rotateVacuumBitmap(fetched, rotation)
        }
        ImageCache.put(key, out)
        out
    }

    val action = rememberAction(ctx)
    var showModes by remember { mutableStateOf(false) }

    fun vac(service: String) = action.run(ServiceCall("vacuum", service, entityId))
    fun cleanSegment(id: Int) {
        action.run(
            ServiceCall(
                "vacuum", "send_command", entityId,
                mapOf(
                    "command" to JsonPrimitive("app_segment_clean"),
                    "params" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive(id))))),
                ),
            )
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, style = AstrionType.headline, color = AstrionTheme.textPrimary, modifier = Modifier.weight(1f))
        StateLine(
            prettyVacuumLabel(state),
            when {
                unavailable -> StateKind.Unavailable
                state == "cleaning" || state == "returning" -> StateKind.On
                state == "error" -> StateKind.Danger
                else -> StateKind.Normal
            },
        )
    }

    mapBmp?.let { bmp ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(mapHeight.dp)
                .clip(RoundedCornerShape(Radius.control))
                .background(AstrionTheme.planBg),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                bitmap = bmp,
                contentDescription = "Vacuum map",
                modifier = Modifier.fillMaxSize().graphicsLayer(scaleX = 1.9f, scaleY = 1.9f),
                contentScale = ContentScale.Fit,
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        IconAction(Icons.Filled.PlayArrow, "Start cleaning", { vac("start") }, size = 56.dp, tone = Tone.Accent, enabled = live)
        IconAction(Icons.Filled.Pause, "Pause", { vac("pause") }, size = 56.dp, enabled = live)
        IconAction(Icons.Filled.Home, "Return to dock", { vac("return_to_base") }, size = 56.dp, enabled = live)
        IconAction(Icons.Filled.MyLocation, "Locate (beep)", { vac("locate") }, size = 56.dp, enabled = live)
    }
    if (action.busy || action.failed) {
        StateLine(
            if (action.failed) "Vacuum didn't accept that" else "Sending…",
            if (action.failed) StateKind.Danger else StateKind.Pending,
        )
    }

    if (fanList.isNotEmpty()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Touch.min)
                .clip(RoundedCornerShape(Radius.control))
                .background(AstrionTheme.raised)
                .tap(enabled = live) { showModes = !showModes }
                .padding(horizontal = Space.card, vertical = Space.s),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Cleaning mode", style = AstrionType.label, color = AstrionTheme.textSecondary)
                Text(
                    fanSpeed?.let(::prettyVacuumLabel) ?: "—",
                    style = AstrionType.bodyStrong, color = AstrionTheme.textPrimary,
                )
            }
            Icon(
                if (showModes) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                contentDescription = null, tint = AstrionTheme.textOnControl,
            )
        }
        if (showModes) {
            OptionList(options = fanList, selected = fanSpeed, label = ::prettyVacuumLabel) { f ->
                showModes = false
                action.run(ServiceCall.of("vacuum", "set_fan_speed", entityId, "fan_speed" to f))
            }
        }
    }

    if (rooms.isNotEmpty()) {
        SectionLabel("Clean a room · hold")
        rooms.chunked(3).forEach { chunk ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                chunk.forEach { room ->
                    val label = room["name"] as? String ?: "?"
                    val id = (room["id"] as? Number)?.toInt()
                    HoldButton(
                        label = label,
                        onHold = {
                            if (id != null) {
                                cleanSegment(id)
                                feedback.show("Cleaning $label")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = live && id != null,
                        fill = AstrionTheme.accentStrong,
                        holdMs = 600,
                        description = "Clean $label, press and hold",
                        onQuickTap = { feedback.show("Hold a room to start cleaning it") },
                    )
                }
                repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private fun prettyVacuumLabel(s: String): String =
    s.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

/** Rotate a bitmap by whole-degree steps (clockwise). */
private fun rotateVacuumBitmap(src: ImageBitmap, degrees: Int): ImageBitmap {
    val android = src.asAndroidBitmap()
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(android, 0, 0, android.width, android.height, matrix, true).asImageBitmap()
}
