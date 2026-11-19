package com.custom.astrion.cards.impl

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
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
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.BottomSheet
import com.custom.astrion.ui.Glyphs
import com.custom.astrion.ui.LocalOpenIrMode
import com.custom.astrion.ui.components.BaeoSectionHeader
import com.custom.astrion.ui.parseHexColor
import com.custom.astrion.ui.rememberSampledBitmap
import com.custom.astrion.ui.tap
import com.custom.astrion.ui.tightTextStyle

private val GREYSCALE = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(0f) })

@Composable
private fun MembersSheet(ctx: CardContext, groupId: String, title: String?, onClose: () -> Unit) {
    val group = ctx.entities[groupId]
    val members = group?.attrStringList("entity_id").orEmpty()
    val anyOn = members.any { ctx.entities[it]?.isOn == true }
    BottomSheet(
        title = title?.takeIf { it.isNotBlank() } ?: group?.friendlyName ?: groupId,
        subtitle = if (anyOn) "On" else "Off",
        onDismissRequest = onClose,
    ) {
        members.forEach { id ->
            val e = ctx.entities[id]
            val on = e?.isOn == true
            val unavailable = e == null || e.isUnavailable
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(if (on) AstrionTheme.ochreSurface else AstrionTheme.controlBg)
                    .tap(enabled = !unavailable && ctx.connected) {
                        ctx.client.callService(ServiceCall(id.substringBefore('.'), "toggle", id))
                    }
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    e?.friendlyName ?: id,
                    color = AstrionTheme.textPrimary,
                    fontSize = AstrionTheme.size.body,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when {
                        unavailable -> "Unavailable"
                        on -> "On"
                        else -> "Off"
                    },
                    color = if (on) AstrionTheme.ochreInk else AstrionTheme.textSecondary,
                    fontSize = AstrionTheme.size.body,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        if (members.size > 1) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(AstrionTheme.accentStrong)
                    .tap(enabled = ctx.connected) {
                        ctx.client.callService(ServiceCall(groupId.substringBefore('.'), if (anyOn) "turn_off" else "turn_on", groupId))
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${if (members.size == 2) "Both" else "All"} ${if (anyOn) "off" else "on"}",
                    color = AstrionTheme.colors.onPrimary,
                    fontSize = AstrionTheme.size.body,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
    }
}

/**
 * Borderless Matte Button Grid (Bæo v3.0):
 *  - Lightweight static Column/Row layout (no LazyVerticalGrid subcomposition
 *    overhead for small action bars, mixes, TV launchers, and cinema macros).
 *  - Supports monochrome `glyph` names alongside PNG `icon` paths.
 *  - Supports `key_dot` ("red"|"green"|"blue"|"yellow") passive hardware remote
 *    colour indicators, `active_tone` ("ochre"|"accent"|"blush"), and internal
 *    `astrion.open_ir` action.
 */
class ButtonGridCard : CardRenderer {
    override val type = "button_grid"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val columns = config.int("columns", 3).coerceAtLeast(1)
        val buttons = (config.options["buttons"] as? List<Map<String, Any?>>) ?: emptyList()
        val title = config.string("title")
        val configuredTileHeight = config.int("tile_height", 0).takeIf { it > 0 }?.dp
        val iconSize = config.int("icon_size", 0).takeIf { it > 0 }?.dp
        val labelLines = config.int("label_lines", 1).coerceIn(1, 2)
        val spacing = config.int("spacing", 8).coerceIn(2, 24).dp
        val openIr = LocalOpenIrMode.current

        if (buttons.isEmpty()) return

        val hasAnyIcon = buttons.any { it["icon"] != null || it["glyph"] != null }
        val effectiveTileHeight = configuredTileHeight ?: if (hasAnyIcon) 60.dp else 48.dp

        Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
            if (title != null) BaeoSectionHeader(title)
            buttons.chunked(columns).forEach { rowButtons ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(spacing),
                ) {
                    rowButtons.forEach { b ->
                        val entityId = b["entity_id"] as? String
                        val stateEntity = (b["state_entity"] as? String) ?: entityId
                        val showState = b["show_state"] == true && stateEntity != null
                        val active = isButtonActive(ctx, b, stateEntity, showState)
                        val off = showState && !active
                        var membersOpen by remember(b) { mutableStateOf(false) }

                        GridButton(
                            b = b,
                            modifier = Modifier.weight(1f),
                            tileHeight = effectiveTileHeight,
                            iconSize = iconSize,
                            labelLines = labelLines,
                            active = active,
                            off = off,
                            enabled = ctx.connected || b["service"] == "astrion.open_ir",
                            onLongClick = if (b["hold"] == "members" && entityId != null) {
                                { membersOpen = true }
                            } else null,
                        ) {
                            if (b["service"] == "astrion.open_ir") {
                                openIr?.invoke()
                            } else {
                                fire(ctx, b)
                            }
                        }
                        if (membersOpen && entityId != null) {
                            MembersSheet(ctx, entityId, b["name"] as? String) { membersOpen = false }
                        }
                    }
                    repeat(columns - rowButtons.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }

    private fun isButtonActive(
        ctx: CardContext,
        b: Map<String, Any?>,
        stateEntity: String?,
        showState: Boolean,
    ): Boolean {
        val litWhen = b["lit_when"]?.toString()
        if (stateEntity != null && litWhen != null) {
            val e = ctx.entities[stateEntity] ?: return false
            val attr = b["lit_attribute"] as? String
            val actual = attr?.let { e.attrString(it) } ?: e.state
            return actual.equals(litWhen, ignoreCase = true)
        }
        if (showState && stateEntity != null) {
            return ctx.entities[stateEntity]?.isOn == true
        }
        return false
    }

    @Suppress("UNCHECKED_CAST")
    private fun fire(ctx: CardContext, b: Map<String, Any?>) {
        val service = b["service"] as? String ?: return
        val data = (b["data"] as? Map<String, Any?>).orEmpty()
        val confirm = b["confirm"] as? Boolean ?: false
        ctx.client.callService(ServiceCall.fromConfig(service, b["entity_id"] as? String, data), confirm = confirm)
    }

    @OptIn(ExperimentalFoundationApi::class)
    @Composable
    private fun GridButton(
        b: Map<String, Any?>,
        modifier: Modifier,
        tileHeight: Dp,
        iconSize: Dp?,
        labelLines: Int,
        active: Boolean,
        off: Boolean,
        enabled: Boolean,
        onLongClick: (() -> Unit)? = null,
        onClick: () -> Unit,
    ) {
        val name = b["name"] as? String
        val iconPath = b["icon"] as? String
        val glyphVec = glyphForName(b["glyph"] as? String)
        val bitmap by rememberSampledBitmap(iconPath, targetPx = 96)
        val hasVisual = bitmap != null || glyphVec != null
        val customTileColor = parseHexColor(b["color"] as? String)
        val activeTone = b["active_tone"] as? String
        val keyDotColor = keyDotColorFor(b["key_dot"] as? String)

        val interaction = remember { MutableInteractionSource() }
        val pressed by interaction.collectIsPressedAsState()
        val haptics = LocalHapticFeedback.current

        val baseFace = when {
            customTileColor != null -> customTileColor
            active && activeTone == "ochre" -> AstrionTheme.ochreSurface
            active && activeTone == "blush" -> AstrionTheme.blushContainer
            active -> AstrionTheme.accentContainer
            else -> AstrionTheme.cardBg
        }
        val face = if (pressed && customTileColor == null) AstrionTheme.surfaceElevated else baseFace

        val iconTint = when {
            active && activeTone == "ochre" -> AstrionTheme.ochreInk
            active && activeTone == "blush" -> AstrionTheme.blush
            active -> AstrionTheme.accent
            else -> AstrionTheme.textSecondary
        }
        val inkColor = parseHexColor(b["text_color"] as? String)
            ?: customTileColor?.let {
                if (0.2126f * it.red + 0.7152f * it.green + 0.0722f * it.blue > 0.6f) AstrionTheme.colors.surfacePrimary
                else AstrionTheme.colors.onPrimary
            }
            ?: when {
                active && activeTone == "ochre" -> AstrionTheme.ochreInk
                active && activeTone == "blush" -> AstrionTheme.blush
                active -> AstrionTheme.textPrimary
                else -> AstrionTheme.textOnControl
            }

        val glyphDp = iconSize ?: 20.dp

        Box(
            modifier = modifier
                .height(tileHeight)
                .clip(RoundedCornerShape(14.dp))
                .background(face)
                .combinedClickable(
                    enabled = enabled,
                    interactionSource = interaction,
                    indication = null,
                    onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onClick()
                    },
                    onLongClick = onLongClick?.let { hold ->
                        {
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                            hold()
                        }
                    },
                ),
        ) {
            if (keyDotColor != null) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 7.dp, end = 7.dp)
                        .size(6.dp)
                        .clip(CircleShape)
                        .background(keyDotColor),
                )
            }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.Center)
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                when {
                    glyphVec != null -> {
                        Icon(
                            imageVector = glyphVec,
                            contentDescription = name,
                            tint = iconTint,
                            modifier = Modifier.size(glyphDp),
                        )
                        if (!name.isNullOrBlank()) Spacer(Modifier.height(if (labelLines > 1) 3.dp else 4.dp))
                    }
                    bitmap != null -> {
                        Image(
                            bitmap = bitmap!!,
                            contentDescription = name,
                            modifier = Modifier.size(glyphDp),
                            alpha = if (off) 0.50f else 1f,
                            colorFilter = if (off) GREYSCALE else null,
                        )
                        if (!name.isNullOrBlank()) Spacer(Modifier.height(if (labelLines > 1) 2.dp else 3.dp))
                    }
                }
                if (!name.isNullOrBlank()) {
                    Text(
                        text = name,
                        style = if (labelLines > 1) tightTextStyle(13.sp) else LocalTextStyle.current,
                        color = inkColor,
                        fontSize = if (hasVisual || name.length > 8) AstrionTheme.size.label else AstrionTheme.size.body,
                        fontWeight = if (active || customTileColor != null) FontWeight.SemiBold else FontWeight.Medium,
                        maxLines = labelLines,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }

    private fun glyphForName(name: String?): ImageVector? = when (name) {
        "radio" -> Glyphs.radio
        "disc" -> Glyphs.disc
        "headphones" -> Glyphs.headphones
        "note", "music" -> Glyphs.note
        "tv" -> Glyphs.tv
        "home" -> Glyphs.home
        "eye" -> Glyphs.eye
        "remote" -> Glyphs.remote
        "bulb", "light" -> Glyphs.bulb
        "roller_blind", "blinds" -> Glyphs.rollerBlind
        "mirrorball" -> Glyphs.mirrorball
        "speaker" -> Glyphs.speaker
        "sun" -> Glyphs.sun
        "sheer" -> Glyphs.sheer
        "moon" -> Glyphs.moon
        "play" -> Glyphs.play
        "search" -> Glyphs.search
        else -> null
    }

    @Composable
    private fun keyDotColorFor(name: String?): Color? = when (name?.lowercase()) {
        "red" -> AstrionTheme.colors.keyRed
        "green" -> AstrionTheme.colors.keyGreen
        "blue" -> AstrionTheme.colors.keyBlue
        "yellow" -> AstrionTheme.colors.keyYellow
        else -> null
    }
}
