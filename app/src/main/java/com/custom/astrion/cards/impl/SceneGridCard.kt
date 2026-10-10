package com.custom.astrion.cards.impl

import androidx.compose.animation.core.animateDpAsState
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.parseHexColor
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.drawBehind
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
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
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
 * `"style": "pill"` draws a scrolling row of pills instead: a glowing colour
 * dot + name, with the most recently activated scene (the scene entity's
 * state is its last-activated time; within 12 h) lit up.
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

        if (config.string("style") == "pill") {
            PillRow(scenes, ctx)
            return
        }

        fun activate(entityId: String) {
            // scene.* → scene.turn_on, script.* → script.turn_on, etc.
            val domain = entityId.substringBefore('.')
            ctx.client.callService(ServiceCall(domain = domain, service = "turn_on", entityId = entityId))
        }
        fun nameOf(scene: Map<String, Any?>, entityId: String) =
            scene["name"] as? String ?: ctx.entities[entityId]?.friendlyName ?: entityId
        fun colorOf(scene: Map<String, Any?>): Color =
            parseHexColor(scene["color"] as? String) ?: Color(0xFF34454F)
        fun iconOf(scene: Map<String, Any?>): ImageVector? = sceneIcon(scene["icon"] as? String)

        if (title != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    color = Color(0xFFA9BAB6),
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
    private fun PillRow(scenes: List<Map<String, Any?>>, ctx: CardContext) {
        fun activatedAt(id: String): Long? = ctx.entities[id]?.state?.let { iso ->
            runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
        }
        val ids = scenes.mapNotNull { it["entity_id"] as? String }
        val latest = ids.mapNotNull { id -> activatedAt(id)?.let { id to it } }.maxByOrNull { it.second }
        // Optimistic: light the tapped pill at once, until HA reports a newer one.
        var tapped by remember { androidx.compose.runtime.mutableStateOf<Pair<String, Long>?>(null) }
        val active = tapped?.takeIf { t -> latest == null || latest.second < t.second }?.first
            ?: latest?.takeIf { System.currentTimeMillis() - it.second < 12 * 3_600_000L }?.first

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            scenes.forEach { scene ->
                val entityId = scene["entity_id"] as? String ?: return@forEach
                val color = parseHexColor(scene["color"] as? String) ?: Color(0xFFA9D2CB)
                val name = scene["name"] as? String ?: ctx.entities[entityId]?.friendlyName ?: entityId
                val isActive = entityId == active
                val haptics = LocalHapticFeedback.current
                Row(
                    modifier = Modifier
                        .height(34.dp)
                        .clip(RoundedCornerShape(13.dp))
                        // Inactive sits on the card fill so it still reads
                        // as a pill against the page; active is sunken, tinted
                        // with the scene colour below.
                        .background(if (isActive) AstrionTheme.controlSunken else AstrionTheme.cardBg)
                        .then(if (isActive) Modifier.background(color.copy(alpha = 0.10f)) else Modifier)
                        .then(
                            if (isActive) Modifier.border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(13.dp))
                            else Modifier
                        )
                        .clickable {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            tapped = entityId to System.currentTimeMillis()
                            ctx.client.callService(
                                ServiceCall(domain = entityId.substringBefore('.'), service = "turn_on", entityId = entityId)
                            )
                        }
                        .padding(horizontal = 13.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Glowing dot. No coloured shadows on Android 8.1, so the
                    // glow is a few fading rings drawn behind it.
                    Box(
                        modifier = Modifier
                            .size(if (isActive) 11.dp else 10.dp)
                            .drawBehind {
                                if (isActive) for (i in 4 downTo 1) {
                                    drawCircle(color.copy(alpha = 0.10f), radius = size.minDimension / 2 + i * 2.dp.toPx())
                                }
                            }
                            .clip(CircleShape)
                            .background(if (isActive) color else color.copy(alpha = 0.8f)),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        name,
                        color = if (isActive) AstrionTheme.textPrimary else AstrionTheme.textSecondary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 0.3.sp,
                        maxLines = 1,
                        softWrap = false,
                    )
                    // Colour is never the only signal: the active pill says so.
                    if (isActive) {
                        Text(
                            "ON",
                            modifier = Modifier.padding(start = 2.dp),
                            color = AstrionTheme.accent,
                            fontSize = 8.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 0.16.em,
                            maxLines = 1,
                            softWrap = false,
                        )
                    }
                }
            }
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
        val fg = if (luminance(color) > 0.75f) Color(0xFF141414) else Color(0xFFEEF2EF)
        // Semi-transparent face (darker/faded over the band) plus a darker,
        // faded "lip" the raised face sits on — its thickness. Pressing sinks
        // the face onto the lip (a 3D press-in).
        val face = color.copy(alpha = 0.55f)
        val base = Color(color.red * 0.5f, color.green * 0.5f, color.blue * 0.5f, 0.62f)
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val haptics = LocalHapticFeedback.current
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
                    .clickable(interactionSource = interaction, indication = null) {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    }
                    .padding(horizontal = 6.dp, vertical = 7.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = if (icon != null) Arrangement.SpaceBetween else Arrangement.Center,
            ) {
                if (icon != null) {
                    Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
                }
                // One line, stepping down for longer names — narrow row tiles
                // were breaking "Santorini" mid-word.
                Text(
                    name,
                    color = fg,
                    fontSize = when {
                        name.length > 10 -> 12.sp
                        name.length > 7 -> 13.sp
                        else -> 15.sp
                    },
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                )
            }
        }
    }
}
