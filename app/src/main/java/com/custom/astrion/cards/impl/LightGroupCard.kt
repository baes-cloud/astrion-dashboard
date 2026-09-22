package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.tap
import kotlin.math.roundToInt

/**
 * A whole page-section of lights, auto-split into two groups by each light's
 * OWN live `supported_color_modes` — no need to say which of your lights are
 * dimmable, the entity tells us:
 *   - "DIMMABLE": a 2-column grid of uniform cards (icon, name/state, a
 *     drag/tap brightness slider with a white thumb — same accent colour
 *     for every card, no per-light colour picking here).
 *   - "OTHER LIGHTS": a 2-column grid of compact toggle-only rows, for
 *     anything reporting only "onoff" (or nothing yet — treated as dimmable
 *     until state loads, so nothing flickers to a switch on first paint).
 *
 * Long-press a dimmable card's icon for the full colour/brightness popup.
 *
 * Config shape:
 *   CardConfig("light_group", mapOf(
 *       "lights" to listOf(
 *           mapOf("entity_id" to "light.downlights", "name" to "Downlights"),
 *           ...
 *       ),
 *   ))
 */
class LightGroupCard : CardRenderer {
    override val type = "light_group"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val lights = (config.options["lights"] as? List<Map<String, Any?>>) ?: emptyList()

        val dimmable = mutableListOf<Pair<String, String?>>()
        val simple = mutableListOf<Pair<String, String?>>()
        lights.forEach { l ->
            val id = l["entity_id"] as? String ?: return@forEach
            val name = l["name"] as? String
            val modes = ctx.entities[id]?.attrStringList("supported_color_modes")
            val isSimple = modes != null && modes.isNotEmpty() && modes.all { it == "onoff" }
            if (isSimple) simple.add(id to name) else dimmable.add(id to name)
        }

        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            if (dimmable.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("DIMMABLE")
                    GridRows(dimmable) { id, name -> DimmableTile(ctx, id, name) }
                }
            }
            if (simple.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SectionLabel("OTHER LIGHTS")
                    GridRows(simple) { id, name -> ToggleRow(ctx, id, name) }
                }
            }
        }
    }

    @Composable
    private fun SectionLabel(text: String) {
        Text(text, color = Color(0xFF9FBAC0), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp)
    }

    /** Packs entries two-per-row, padding the final odd row so widths stay equal. */
    @Composable
    private fun GridRows(
        items: List<Pair<String, String?>>,
        content: @Composable (String, String?) -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (id, name) ->
                        Box(Modifier.weight(1f)) { content(id, name) }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }

    @Composable
    private fun DimmableTile(ctx: CardContext, entityId: String, configName: String?) {
        val e = ctx.entities[entityId]
        val on = e?.isOn == true
        val name = configName ?: e?.friendlyName ?: entityId

        val brightness = e?.attrInt("brightness")
        val level: Float = when {
            !on -> 0f
            brightness != null -> (brightness / 255f).coerceIn(0f, 1f)
            else -> 1f
        }
        var dragLevel by remember(level) { mutableStateOf(level) }
        var showDetail by remember { mutableStateOf(false) }

        fun commit(fraction: Float) {
            val pct = (fraction.coerceIn(0f, 1f) * 100).roundToInt()
            if (pct <= 0) {
                ctx.client.callService(ServiceCall("light", "turn_off", entityId))
            } else {
                ctx.client.callService(ServiceCall.of("light", "turn_on", entityId, "brightness_pct" to pct))
            }
        }

        // Uniform accent for every dimmable card — no per-light colour picking.
        val fillColor = Color(0xFFFFC24B)
        val iconBg = if (on) Color(0xFFFFC24B) else Color(0xFF2C4D59)
        val iconTint = if (on) Color(0xFF241A00) else Color(0xFF9FBAC0)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Color(0xFF1C3740))
                .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(iconBg)
                        .pointerInput(entityId) {
                            detectTapGestures(
                                onTap = { ctx.client.toggle(entityId) },
                                onLongPress = { showDetail = true },
                            )
                        },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Lightbulb, contentDescription = null, tint = iconTint, modifier = Modifier.size(17.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        name, color = Color(0xFFF3F8F9), fontSize = 14.sp, fontWeight = FontWeight.SemiBold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    Text(if (on) "${(dragLevel * 100).roundToInt()}%" else "Off", color = Color(0xFF9FBAC0), fontSize = 12.sp)
                }
            }
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(34.dp)
                    .pointerInput(entityId) {
                        detectHorizontalDragGestures(onDragEnd = { commit(dragLevel) }) { change, _ ->
                            dragLevel = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    }
                    .pointerInput(entityId) {
                        detectTapGestures { offset ->
                            val frac = (offset.x / size.width).coerceIn(0f, 1f)
                            dragLevel = frac
                            commit(frac)
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(17.dp))
                        .background(Color(0xFF2C4D59)),
                ) {
                    if (on) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(dragLevel.coerceAtLeast(0.02f))
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(17.dp))
                                .background(fillColor.copy(alpha = 0.5f)),
                        )
                    }
                }
                val thumbSize = 18.dp
                val thumbX = (maxWidth * dragLevel - thumbSize / 2).coerceIn(0.dp, maxWidth - thumbSize)
                Box(
                    modifier = Modifier
                        .padding(start = thumbX)
                        .size(thumbSize)
                        .clip(CircleShape)
                        .background(Color(0xFFF2F7F8)),
                )
            }
        }

        if (showDetail) {
            LightDetailDialog(entityId = entityId, e = e, client = ctx.client, onClose = { showDetail = false })
        }
    }

    @Composable
    private fun ToggleRow(ctx: CardContext, entityId: String, configName: String?) {
        val e = ctx.entities[entityId]
        val on = e?.isOn == true
        val name = configName ?: e?.friendlyName ?: entityId

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1C3740))
                .padding(horizontal = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(CircleShape)
                    .background(if (on) Color(0xFFFFC24B) else Color(0xFF2C4D59))
                    .tap { ctx.client.toggle(entityId) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.Lightbulb, contentDescription = null,
                    tint = if (on) Color(0xFF241A00) else Color(0xFF9FBAC0),
                    modifier = Modifier.size(15.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                Text(name, color = Color(0xFFF3F8F9), fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(if (on) "On" else "Off", color = Color(0xFF9FBAC0), fontSize = 11.sp)
            }
            // Switch: matches the app's flat-toggle style used elsewhere.
            // Raised from 40×24dp — under 6mm tall at this density.
            Box(
                modifier = Modifier
                    .size(width = 50.dp, height = 30.dp)
                    .clip(RoundedCornerShape(15.dp))
                    .background(if (on) AstrionTheme.accent else AstrionTheme.controlBg)
                    .tap { ctx.client.toggle(entityId) },
            ) {
                Box(
                    modifier = Modifier
                        .padding(start = if (on) 23.dp else 3.dp, top = 3.dp)
                        .size(24.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFF2F7F8)),
                )
            }
        }
    }
}
