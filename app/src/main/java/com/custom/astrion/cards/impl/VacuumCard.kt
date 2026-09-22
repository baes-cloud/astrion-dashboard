package com.custom.astrion.cards.impl

import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import com.custom.astrion.ui.tap

/**
 * Robot vacuum card: the map (from a Roborock/Xiaomi map image entity, which
 * already renders the robot moving), start/pause/dock/locate controls, a fan-
 * speed (cleaning-mode) dropdown, and room buttons that start a segment clean.
 *
 * Room buttons fire `vacuum.send_command app_segment_clean` with the segment
 * id (the Roborock room id from your map). The map image is fetched from the
 * entity's `entity_picture` — no blocked-zone editing, just a live-ish view.
 *
 * Config shape:
 *   { "type": "vacuum", "options": {
 *       "entity_id": "vacuum.roborock",
 *       "map_image": "image.roborock_map",
 *       "map_rotation": 90,
 *       "map_height": 200,
 *       "rooms": [ { "name": "Kitchen", "id": 18 }, ... ]
 *   } }
 *
 * The same options map can be reused as the "vacuum" overlay block on a
 * `picture_elements` floorplan card (see [PictureElementsCard]) — tapping the
 * robot icon there opens this exact content in a popup via [VacuumPanelContent].
 */
class VacuumCard : CardRenderer {
    override val type = "vacuum"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1B343D))
                .padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            VacuumPanelContent(config.options, ctx)
        }
    }
}

/**
 * The vacuum panel's actual content (map, controls, cleaning mode, room
 * buttons) with no outer container — call this inside whatever container you
 * want (a plain card, or a Dialog for the floorplan popup).
 */
@Suppress("UNCHECKED_CAST")
@Composable
fun VacuumPanelContent(options: Map<String, Any?>, ctx: CardContext) {
    val entityId = options["entity_id"] as? String ?: return
    val e = ctx.entities[entityId]
    val state = e?.state ?: "unknown"
    val name = options["name"] as? String ?: e?.friendlyName ?: "Vacuum"
    val fanSpeed = e?.attrString("fan_speed")
    val fanList = e?.attrStringList("fan_speed_list") ?: emptyList()
    val mapEntity = options["map_image"] as? String
    val mapHeight = (options["map_height"] as? Number)?.toInt() ?: 200
    val rooms = (options["rooms"] as? List<Map<String, Any?>>) ?: emptyList()

    // Degrees clockwise to rotate the map so it matches the floorplan card's
    // orientation above it (the vacuum map's native orientation rarely
    // matches an arbitrary hand-drawn/rendered floorplan).
    val rotation = (options["map_rotation"] as? Number)?.toInt() ?: 0

    val mapPic = mapEntity?.let { ctx.entities[it]?.attrString("entity_picture") }
    var mapBmp by remember(mapPic, rotation) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(mapPic, rotation) {
        val fetched = mapPic?.let { ctx.client.fetchBitmap(it) } ?: return@LaunchedEffect
        mapBmp = if (rotation == 0) fetched else rotateVacuumBitmap(fetched, rotation)
    }

    var fanExpanded by remember { mutableStateOf(false) }

    fun vac(service: String) = ctx.client.callService(ServiceCall("vacuum", service, entityId))
    fun cleanSegment(id: Int) {
        ctx.client.callService(
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
        Text(name, color = Color(0xFFE6F0F1), fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f))
        Text(prettyVacuumLabel(state), color = Color(0xFF93AFB6), fontSize = 13.sp)
    }

    // Map (includes the robot, rooms, path — rendered by the integration).
    // Fixed height (configurable) so the whole card clears the pager dots.
    mapBmp?.let { bmp ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(mapHeight.dp)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF0E2229)),
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

    // Controls.
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    ) {
        VacuumCtrlBtn(Icons.Filled.PlayArrow, accent = true) { vac("start") }
        VacuumCtrlBtn(Icons.Filled.Pause) { vac("pause") }
        VacuumCtrlBtn(Icons.Filled.Home) { vac("return_to_base") }
        VacuumCtrlBtn(Icons.Filled.MyLocation) { vac("locate") }
    }

    // Fan speed / cleaning mode dropdown.
    if (fanList.isNotEmpty()) {
        Box {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E3841))
                    .tap { fanExpanded = true }
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Cleaning mode", color = Color(0xFF93AFB6), fontSize = 11.sp)
                    Text(fanSpeed?.let(::prettyVacuumLabel) ?: "—", color = Color(0xFFE6F0F1), fontSize = 15.sp)
                }
                Icon(Icons.Filled.ArrowDropDown, contentDescription = null, tint = Color(0xFFCBDCE0))
            }
            DropdownMenu(
                expanded = fanExpanded,
                onDismissRequest = { fanExpanded = false },
                modifier = Modifier.background(Color(0xFF1E3841)),
            ) {
                fanList.forEach { f ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                prettyVacuumLabel(f),
                                color = if (f == fanSpeed) Color(0xFF6EA8FE) else Color(0xFFE6F0F1),
                                fontSize = 14.sp,
                            )
                        },
                        onClick = {
                            fanExpanded = false
                            ctx.client.callService(
                                ServiceCall.of("vacuum", "set_fan_speed", entityId, "fan_speed" to f)
                            )
                        },
                    )
                }
            }
        }
    }

    // Room selection → segment clean.
    if (rooms.isNotEmpty()) {
        rooms.chunked(3).forEach { chunk ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                chunk.forEach { room ->
                    val label = room["name"] as? String ?: "?"
                    val id = (room["id"] as? Number)?.toInt()
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(44.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(Color(0xFF2A4954))
                            .tap(enabled = id != null) { id?.let { cleanSegment(it) } },
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(label, color = Color(0xFFE6F0F1), fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
                repeat(3 - chunk.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun VacuumCtrlBtn(icon: ImageVector, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(if (accent) Color(0xFF4C6EF5) else Color(0xFF2C4C58))
            .tap(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = Color.White)
    }
}

private fun prettyVacuumLabel(s: String): String =
    s.split('_').joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }

/** Rotate a bitmap by whole-degree steps (clockwise), swapping width/height as needed. */
private fun rotateVacuumBitmap(src: ImageBitmap, degrees: Int): ImageBitmap {
    val android = src.asAndroidBitmap()
    val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
    return Bitmap.createBitmap(android, 0, 0, android.width, android.height, matrix, true).asImageBitmap()
}
