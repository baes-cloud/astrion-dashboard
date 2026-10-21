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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.layout.layout
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
            // `"label": "Scenes"`: a small centred caption between two short
            // hairlines above the pills, so the row reads as its own thing.
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                config.string("label")?.let { SceneCaption(it) }
                PillRow(scenes, ctx)
            }
            return
        }

        fun activate(entityId: String) {
            // scene.* → scene.turn_on, script.* → script.turn_on, etc.
            val domain = entityId.substringBefore('.')
            ctx.client.callService(ServiceCall(domain = domain, service = "turn_on", entityId = entityId))
        }
        fun nameOf(scene: Map<String, Any?>, entityId: String) =
            scene["name"] as? String ?: ctx.entities[entityId]?.friendlyName ?: entityId
        val defaultFace = AstrionTheme.colors.raised
        fun colorOf(scene: Map<String, Any?>): Color =
            parseHexColor(scene["color"] as? String) ?: defaultFace
        fun iconOf(scene: Map<String, Any?>): ImageVector? = sceneIcon(scene["icon"] as? String)

        if (title != null) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    title,
                    color = AstrionTheme.colors.onSurfaceMuted,
                    fontSize = AstrionTheme.size.label,
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
    private fun SceneCaption(text: String) {
        // Tight on top, with a tiny bit of breathing room below before the pills.
        Row(
            Modifier
                .fillMaxWidth()
                .layout { measurable, constraints ->
                    val p = measurable.measure(constraints)
                    val trimTop = 5.dp.roundToPx()
                    layout(p.width, (p.height - trimTop).coerceAtLeast(0)) { p.place(0, -trimTop) }
                },
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val line = AstrionTheme.textMuted.copy(alpha = 0.3f)
            Box(Modifier.width(32.dp).height(1.dp).background(line))
            Text(
                text.uppercase(),
                color = AstrionTheme.textMuted,
                fontSize = AstrionTheme.size.label,
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.18.em,
                style = com.custom.astrion.ui.tightTextStyle(12.sp),
                modifier = Modifier.padding(horizontal = 8.dp),
            )
            Box(Modifier.width(32.dp).height(1.dp).background(line))
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
                val color = parseHexColor(scene["color"] as? String) ?: AstrionTheme.colors.accentBright
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
                            if (isActive) Modifier.border(1.dp, AstrionTheme.colors.hairline, RoundedCornerShape(13.dp))
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
                        fontSize = AstrionTheme.size.small,
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
                            fontSize = AstrionTheme.size.label,
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
        if (scenes.isEmpty()) return

        val hasAnyIcon = scenes.any { sceneIcon(it["icon"] as? String) != null }
        val faceH = if (hasAnyIcon) 60.dp else 48.dp
        val lip = 5.dp
        val tileHeight = faceH + lip

        if (row) {
            // Horizontal Grid (Replaces manual subcomposed row with LazyHorizontalGrid)
            val gap = 8.dp
            val count = scenes.size
            val screenWidth = LocalConfiguration.current.screenWidthDp.dp
            val availableWidth = (screenWidth - 20.dp).coerceAtLeast(100.dp)
            val tileW = if (count in 1..5) {
                (availableWidth - gap * (count - 1)) / count
            } else {
                (availableWidth - gap * 4) / 4.5f
            }

            LazyHorizontalGrid(
                rows = GridCells.Fixed(1),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(tileHeight),
                horizontalArrangement = Arrangement.spacedBy(gap),
                contentPadding = PaddingValues(0.dp),
                userScrollEnabled = count > 5 || (count * tileW.value > availableWidth.value),
            ) {
                items(
                    count = scenes.size,
                    key = { index -> scenes[index]["entity_id"] as? String ?: index.toString() },
                ) { index ->
                    val scene = scenes[index]
                    val entityId = scene["entity_id"] as? String ?: return@items
                    SceneButton(
                        name = nameOf(scene, entityId),
                        color = colorOf(scene),
                        icon = iconOf(scene),
                        faceH = faceH,
                        lip = lip,
                        modifier = Modifier.width(tileW),
                    ) { activate(entityId) }
                }
            }
        } else {
            // Vertical Grid (Replaces scenes.chunked(columns) + Row + weight(1f) + Spacer)
            val spacing = 6.dp
            val rowCount = (scenes.size + columns - 1) / columns
            val totalGridHeight = (tileHeight * rowCount) + (spacing * (rowCount - 1).coerceAtLeast(0))

            LazyVerticalGrid(
                columns = GridCells.Fixed(columns),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(totalGridHeight),
                horizontalArrangement = Arrangement.spacedBy(spacing),
                verticalArrangement = Arrangement.spacedBy(spacing),
                contentPadding = PaddingValues(0.dp),
                userScrollEnabled = false, // Nested scrolling safety with parent verticalScroll
            ) {
                items(
                    count = scenes.size,
                    key = { index -> scenes[index]["entity_id"] as? String ?: index.toString() },
                ) { index ->
                    val scene = scenes[index]
                    val entityId = scene["entity_id"] as? String ?: return@items
                    SceneButton(
                        name = nameOf(scene, entityId),
                        color = colorOf(scene),
                        icon = iconOf(scene),
                        faceH = faceH,
                        lip = lip,
                        modifier = Modifier.fillMaxWidth(),
                    ) { activate(entityId) }
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
    private fun SceneButton(
        name: String,
        color: Color,
        icon: ImageVector?,
        faceH: Dp,
        lip: Dp,
        modifier: Modifier,
        onClick: () -> Unit,
    ) {
        // Dark text/icon on light tiles (e.g. the white scene), light otherwise.
        val fg = if (luminance(color) > 0.75f) AstrionTheme.colors.textInverse else AstrionTheme.colors.onSurface
        // Semi-transparent face (darker/faded over the band) plus a darker,
        // faded "lip" the raised face sits on — its thickness. Pressing sinks
        // the face onto the lip (a 3D press-in).
        val face = color.copy(alpha = 0.55f)
        val base = Color(color.red * 0.5f, color.green * 0.5f, color.blue * 0.5f, 0.62f)
        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val haptics = LocalHapticFeedback.current
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
                    .graphicsLayer { translationY = sink.toPx() }
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
                        name.length > 10 -> AstrionTheme.size.label
                        name.length > 7 -> AstrionTheme.size.small
                        else -> AstrionTheme.size.bodyLarge
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
