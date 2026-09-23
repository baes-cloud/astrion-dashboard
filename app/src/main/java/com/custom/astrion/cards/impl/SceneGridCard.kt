package com.custom.astrion.cards.impl

import androidx.compose.animation.core.animateDpAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.PendingSpinner
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.SectionLabel
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.liveOrDim
import com.custom.astrion.ui.parseHexColor
import com.custom.astrion.ui.rememberAction

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

        fun nameOf(scene: Map<String, Any?>, entityId: String) =
            scene["name"] as? String ?: ctx.client.peek(entityId)?.friendlyName ?: entityId
        fun colorOf(scene: Map<String, Any?>): Color =
            parseHexColor(scene["color"] as? String) ?: AstrionTheme.sceneDefault
        fun iconOf(scene: Map<String, Any?>): ImageVector? = sceneIcon(scene["icon"] as? String)

        if (title != null) {
            Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
                SectionLabel(title)
                SceneGridBody(ctx, row, columns, scenes, ::nameOf, ::colorOf, ::iconOf)
            }
        } else {
            SceneGridBody(ctx, row, columns, scenes, ::nameOf, ::colorOf, ::iconOf)
        }
    }

    @Composable
    private fun SceneGridBody(
        ctx: CardContext,
        row: Boolean,
        columns: Int,
        scenes: List<Map<String, Any?>>,
        nameOf: (Map<String, Any?>, String) -> String,
        colorOf: (Map<String, Any?>) -> Color,
        iconOf: (Map<String, Any?>) -> ImageVector?,
    ) {
        if (row) {
            // Horizontally scrollable row.
            //
            // Tiles used to be sized so exactly 4 fit, with no edge fade, peek
            // or arrow — so with five scenes configured (Night/White/Day/Club/
            // Off) the row filled edge to edge and the fifth was invisible.
            // "Off" is the single most likely thing you want from a lighting
            // remote at the end of the night, and it was the one you couldn't
            // see. Fit them all when they're still a comfortable size, and
            // otherwise leave half a tile showing so the row is self-evidently
            // scrollable.
            BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
                val gap = 8.dp
                val count = scenes.size
                val tileW = if (count in 1..5) {
                    (maxWidth - gap * (count - 1)) / count
                } else {
                    (maxWidth - gap * 4) / 4.5f
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(gap),
                ) {
                    scenes.forEach { scene ->
                        val entityId = scene["entity_id"] as? String ?: return@forEach
                        SceneButton(
                            ctx = ctx,
                            entityId = entityId,
                            name = nameOf(scene, entityId),
                            color = colorOf(scene),
                            icon = iconOf(scene),
                            modifier = Modifier.width(tileW),
                        )
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
                                ctx = ctx,
                                entityId = entityId,
                                name = nameOf(scene, entityId),
                                color = colorOf(scene),
                                icon = iconOf(scene),
                                modifier = Modifier.weight(1f),
                            )
                        }
                        // Pad the final short row so tiles keep equal width.
                        repeat(columns - chunk.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
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

    /**
     * A raised face over a darker lip; pressing sinks the face onto the lip
     * (one animated Dp — the app's signature press). The tile then shows a
     * spinner if the scene is slow to confirm, and a red outline if HA
     * refused it, so a scene is never fired twice "to be sure".
     * Colours are opaque blends toward the page, not alpha layers.
     */
    @Composable
    private fun SceneButton(
        ctx: CardContext,
        entityId: String,
        name: String,
        color: Color,
        icon: ImageVector?,
        modifier: Modifier,
    ) {
        val action = rememberAction(ctx)
        val face = lerp(AstrionTheme.pageBg, color, 0.62f)
        val base = lerp(AstrionTheme.pageBg, Color(color.red * 0.5f, color.green * 0.5f, color.blue * 0.5f), 0.7f)
        // Dark ink on light tiles (e.g. the white scene), light otherwise.
        val fg = if (luminance(face) > 0.6f) AstrionTheme.sceneInkDark else AstrionTheme.sceneInkLight
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val haptics = LocalHapticFeedback.current
        val lip = 5.dp
        val faceH = if (icon != null) 60.dp else 48.dp
        val sink by animateDpAsState(if (pressed) lip else 0.dp, label = "sink")
        val live = ctx.connected
        val shape = RoundedCornerShape(Radius.control)

        Box(
            modifier = modifier
                .height(faceH + lip)
                .liveOrDim(live)
                .clip(shape)
                .background(base),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(faceH)
                    .align(Alignment.TopCenter)
                    .offset(y = sink)
                    .clip(shape)
                    .background(face)
                    .then(if (action.failed) Modifier.border(2.dp, AstrionTheme.danger, shape) else Modifier)
                    .clickable(interactionSource = interaction, indication = null, enabled = live) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        // scene.* → scene.turn_on, script.* → script.turn_on, etc.
                        action.run(ServiceCall(entityId.substringBefore('.'), "turn_on", entityId))
                    }
                    .padding(horizontal = 6.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (icon != null) Arrangement.SpaceBetween else Arrangement.Center,
            ) {
                if (action.busy) {
                    PendingSpinner(size = 18.dp, color = fg)
                } else if (icon != null) {
                    Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                }
                Text(name, style = AstrionType.bodyStrong, color = fg, textAlign = TextAlign.Center, maxLines = 1)
            }
        }
    }
}
