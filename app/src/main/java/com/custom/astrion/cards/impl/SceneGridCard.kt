package com.custom.astrion.cards.impl

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Nightlight
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.WbSunny
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall

/**
 * Scene grid — packs N scene buttons into a configurable grid, each tile a
 * solid colour with an icon above the label (matches the app-wide "flat
 * colour tile" theme also used for speaker/vacuum glyphs).
 *
 * Config shape:
 *   CardConfig("scene_grid", mapOf(
 *       "title" to "Scenes",     // optional section header above the grid
 *       "columns" to 3,
 *       "scenes" to listOf(
 *           mapOf("entity_id" to "scene.movie", "name" to "Movie", "icon" to "night"),
 *           ...
 *       ),
 *   ))
 *
 * Recognised `icon` keys: "mood", "night", "white", "day", "club", "off" —
 * anything else (or omitted) shows no icon, just the label.
 */
class SceneGridCard : CardRenderer {
    override val type = "scene_grid"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val columns = config.int("columns", 2).coerceAtLeast(1)
        val scenes = (config.options["scenes"] as? List<Map<String, Any?>>) ?: emptyList()
        val row = config.string("layout") == "row"
        val title = config.string("title")

        fun activate(entityId: String) {
            // scene.* → scene.turn_on, script.* → script.turn_on, etc.
            val domain = entityId.substringBefore('.')
            ctx.client.callService(ServiceCall(domain = domain, service = "turn_on", entityId = entityId))
        }
        fun nameOf(scene: Map<String, Any?>, entityId: String) =
            scene["name"] as? String ?: ctx.entities[entityId]?.friendlyName ?: entityId
        fun colorOf(scene: Map<String, Any?>): Color =
            (scene["color"] as? String)?.let(::parseHexColor) ?: Color(0xFF2A4954)
        fun iconOf(scene: Map<String, Any?>): ImageVector? = sceneIcon(scene["icon"] as? String)

        if (title != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    color = Color(0xFF93AFB6),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                )
                SceneGridBody(row, columns, scenes, ::nameOf, ::colorOf, ::iconOf, ::activate)
            }
        } else {
            SceneGridBody(row, columns, scenes, ::nameOf, ::colorOf, ::iconOf, ::activate)
        }
    }

    @Composable
    private fun SceneGridBody(
        row: Boolean,
        columns: Int,
        scenes: List<Map<String, Any?>>,
        nameOf: (Map<String, Any?>, String) -> String,
        colorOf: (Map<String, Any?>) -> Color,
        iconOf: (Map<String, Any?>) -> ImageVector?,
        activate: (String) -> Unit,
    ) {
        if (row) {
            // Horizontally scrollable row; tiles sized so exactly 4 fit the width.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val gap = 8.dp
                val tileW = (maxWidth - gap * 3) / 4
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    scenes.forEach { scene ->
                        val entityId = scene["entity_id"] as? String ?: return@forEach
                        SceneButton(
                            name = nameOf(scene, entityId),
                            color = colorOf(scene),
                            icon = iconOf(scene),
                            modifier = Modifier.width(tileW),
                        ) { activate(entityId) }
                    }
                }
            }
        } else {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                scenes.chunked(columns).forEach { chunk ->
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        chunk.forEach { scene ->
                            val entityId = scene["entity_id"] as? String ?: return@forEach
                            SceneButton(
                                name = nameOf(scene, entityId),
                                color = colorOf(scene),
                                icon = iconOf(scene),
                                modifier = Modifier.weight(1f),
                            ) { activate(entityId) }
                        }
                        // Pad the final short row so tiles keep equal width.
                        repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }

    /** Parse "#RRGGBB" (treated opaque) or "#AARRGGBB" to a Color. */
    private fun parseHexColor(s: String): Color? {
        val h = s.removePrefix("#")
        val v = h.toLongOrNull(16) ?: return null
        return if (h.length <= 6) Color(0xFF000000L or v) else Color(v)
    }

    /** Perceived luminance of the base RGB (0..1) — used to pick a readable text colour. */
    private fun luminance(c: Color): Float =
        0.2126f * c.red + 0.7152f * c.green + 0.0722f * c.blue

    private fun sceneIcon(key: String?): ImageVector? = when (key) {
        "mood" -> Icons.Filled.Palette
        "night" -> Icons.Filled.Nightlight
        "white" -> Icons.Filled.LightMode
        "day" -> Icons.Filled.WbSunny
        "club" -> Icons.Filled.MusicNote
        "off" -> Icons.Filled.PowerSettingsNew
        else -> null
    }

    @Composable
    private fun SceneButton(name: String, color: Color, icon: ImageVector?, modifier: Modifier, onClick: () -> Unit) {
        // Dark text/icon on light tiles (e.g. the white scene), light otherwise.
        val fg = if (luminance(color) > 0.75f) Color(0xFF141414) else Color(0xFFF0F2F6)
        // Semi-transparent face (darker/faded over the band) plus a darker,
        // faded "lip" the raised face sits on — its thickness. Pressing sinks
        // the face onto the lip (a 3D press-in).
        val face = color.copy(alpha = 0.55f)
        val base = Color(color.red * 0.5f, color.green * 0.5f, color.blue * 0.5f, 0.62f)
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val lip = 5.dp
        val faceH = if (icon != null) 60.dp else 48.dp
        val sink by animateDpAsState(if (pressed) lip else 0.dp, label = "sink")

        Box(
            modifier = modifier
                .height(faceH + lip)
                .clip(RoundedCornerShape(14.dp))
                .background(base),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(faceH)
                    .align(Alignment.TopCenter)
                    .offset(y = sink)
                    .clip(RoundedCornerShape(14.dp))
                    .background(face)
                    .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                    .padding(horizontal = 6.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (icon != null) Arrangement.SpaceBetween else Arrangement.Center,
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                }
                Text(
                    name,
                    color = fg,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}
