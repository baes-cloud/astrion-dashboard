package com.custom.astrion.cards.impl

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.components.BaeoSectionHeader
import com.custom.astrion.ui.decodeSampled
import com.custom.astrion.ui.tap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Robot vacuum card & sheet panel (Bæo v3.0 Matte Tonal):
 *  - Labeled Clean/Pause/Dock/Locate buttons.
 *  - Inline borderless matte fan-speed pills (eliminates key-stealing DropdownMenu).
 *  - Room segment buttons with immediate in-sheet confirmation toast.
 */
class VacuumCard : CardRenderer {
    override val type = "vacuum"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(AstrionTheme.surfaces.cardCornerRadius))
                .background(AstrionTheme.cardBg)
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VacuumPanelContent(config.options, ctx, showHeader = true)
        }
    }
}

@Suppress("UNCHECKED_CAST")
@Composable
fun VacuumPanelContent(
    options: Map<String, Any?>,
    ctx: CardContext,
    showHeader: Boolean = false,
) {
    val entityId = options["entity_id"] as? String ?: return
    val e = ctx.entities[entityId]
    val state = e?.state ?: "unknown"
    val name = options["name"] as? String ?: e?.friendlyName ?: "Vacuum"
    val fanSpeed = e?.attrString("fan_speed")
    val fanList = e?.attrStringList("fan_speed_list") ?: emptyList()
    val mapEntity = options["map_image"] as? String
    val mapHeight = (options["map_height"] as? Number)?.toInt() ?: 180
    val rooms = (options["rooms"] as? List<Map<String, Any?>>) ?: emptyList()
    val rotation = (options["map_rotation"] as? Number)?.toInt() ?: 0

    val mapPic = mapEntity?.let { ctx.entities[it]?.attrString("entity_picture") }
    var mapBmp by remember(mapPic, rotation) { mutableStateOf<ImageBitmap?>(null) }
    val mapPx = with(LocalDensity.current) { (mapHeight * 2).dp.roundToPx() }
    LaunchedEffect(mapPic, rotation) {
        val bytes = mapPic?.let { ctx.client.fetchBytes(it) } ?: return@LaunchedEffect
        val rendered = withContext(Dispatchers.Default) {
            val fetched = decodeSampled(bytes, mapPx) ?: return@withContext null
            if (rotation == 0) fetched else rotateVacuumBitmap(fetched, rotation)
        } ?: return@LaunchedEffect
        mapBmp = rendered
    }

    var feedback by remember(entityId) { mutableStateOf<String?>(null) }
    LaunchedEffect(feedback) {
        if (feedback != null) {
            delay(2_200L)
            feedback = null
        }
    }

    fun vac(service: String, label: String) {
        ctx.client.callService(ServiceCall("vacuum", service, entityId))
        feedback = label
    }
    fun cleanSegment(id: Int, roomName: String) {
        ctx.client.callService(
            ServiceCall(
                "vacuum", "send_command", entityId,
                mapOf(
                    "command" to JsonPrimitive("app_segment_clean"),
                    "params" to JsonArray(listOf(JsonArray(listOf(JsonPrimitive(id))))),
                ),
            )
        )
        feedback = "Cleaning $roomName…"
    }

    if (showHeader) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                name,
                color = AstrionTheme.textPrimary,
                fontSize = AstrionTheme.size.emphasis,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            Text(
                feedback ?: prettyVacuumLabel(state),
                color = if (feedback != null) AstrionTheme.accent else AstrionTheme.textSecondary,
                fontSize = AstrionTheme.size.label,
                fontWeight = if (feedback != null) FontWeight.SemiBold else FontWeight.Normal,
            )
        }
    } else if (feedback != null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .background(AstrionTheme.accentContainer)
                .padding(horizontal = 12.dp, vertical = 7.dp),
        ) {
            Text(
                text = feedback.orEmpty(),
                color = AstrionTheme.accent,
                fontSize = AstrionTheme.size.label,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    mapBmp?.let { bmp ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(mapHeight.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(AstrionTheme.colors.surfacePrimary),
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

    // Labeled circular controls (Clean / Pause / Dock / Locate).
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        VacuumCtrlBtn(Icons.Filled.PlayArrow, "Clean", accent = true) { vac("start", "Starting clean…") }
        VacuumCtrlBtn(Icons.Filled.Pause, "Pause") { vac("pause", "Paused") }
        VacuumCtrlBtn(Icons.Filled.Home, "Dock") { vac("return_to_base", "Returning to dock…") }
        VacuumCtrlBtn(Icons.Filled.MyLocation, "Locate") { vac("locate", "Locating vacuum…") }
    }

    // Inline matte cleaning-mode chips (never steals key focus like DropdownMenu).
    if (fanList.isNotEmpty()) {
        BaeoSectionHeader("Suction")
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            fanList.forEach { f ->
                val selected = f == fanSpeed
                Box(
                    modifier = Modifier
                        .height(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(if (selected) AstrionTheme.accentStrong else AstrionTheme.controlBg)
                        .tap {
                            ctx.client.callService(
                                ServiceCall.of("vacuum", "set_fan_speed", entityId, "fan_speed" to f)
                            )
                            feedback = "Mode: ${prettyVacuumLabel(f)}"
                        }
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = prettyVacuumLabel(f),
                        color = if (selected) AstrionTheme.colors.onPrimary else AstrionTheme.textOnControl,
                        fontSize = AstrionTheme.size.label,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                        maxLines = 1,
                    )
                }
            }
        }
    }

    // Room selection → segment clean.
    if (rooms.isNotEmpty()) {
        BaeoSectionHeader("Clean room")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            rooms.chunked(3).forEach { chunk ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    chunk.forEach { room ->
                        val label = room["name"] as? String ?: "?"
                        val id = (room["id"] as? Number)?.toInt()
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(44.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(AstrionTheme.raised)
                                .tap(enabled = id != null) { id?.let { cleanSegment(it, label) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = label,
                                color = AstrionTheme.textPrimary,
                                fontSize = AstrionTheme.size.label,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun VacuumCtrlBtn(
    icon: ImageVector,
    label: String,
    accent: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(CircleShape)
                .background(if (accent) AstrionTheme.accent else AstrionTheme.controlBg)
                .tap(onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                icon,
                contentDescription = label,
                tint = if (accent) AstrionTheme.onAccent else AstrionTheme.textOnControl,
            )
        }
        Text(
            text = label,
            color = AstrionTheme.textSecondary,
            fontSize = AstrionTheme.size.micro,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
        )
    }
}

internal fun prettyVacuumLabel(s: String): String =
    s.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

private fun rotateVacuumBitmap(src: ImageBitmap, degrees: Int): ImageBitmap {
    val android = src.asAndroidBitmap()
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(android, 0, 0, android.width, android.height, matrix, true).asImageBitmap()
}
