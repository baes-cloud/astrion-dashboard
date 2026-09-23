package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Blinds
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Whatshot
import androidx.compose.material.icons.outlined.Blinds
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.IconAction
import com.custom.astrion.ui.IconWell
import com.custom.astrion.ui.PendingSpinner
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.StateKind
import com.custom.astrion.ui.StateLine
import com.custom.astrion.ui.Touch
import com.custom.astrion.ui.WellState
import com.custom.astrion.ui.humanise
import com.custom.astrion.ui.parseHexColor
import com.custom.astrion.ui.rememberAction
import com.custom.astrion.ui.rememberOptimistic
import com.custom.astrion.ui.tap

/** The shared row shape of the tile cards: icon well, title + state, controls. */
@Composable
private fun TileRow(
    background: Color = AstrionTheme.cardBg,
    failed: Boolean = false,
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    val shape = RoundedCornerShape(Radius.card)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 68.dp)
            .clip(shape)
            .background(background)
            .then(if (failed) Modifier.border(2.dp, AstrionTheme.danger, shape) else Modifier)
            .then(modifier)
            .padding(horizontal = Space.card, vertical = Space.s),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@Composable
private fun TileTitle(name: String, state: String, kind: StateKind, modifier: Modifier) {
    Column(modifier) {
        Text(name, style = AstrionType.title, color = AstrionTheme.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        StateLine(state, kind)
    }
}

/**
 * Cover / blind: open / stop / close plus position. Open is carried by the
 * glyph (filled vs outlined) and the amber well, not colour alone.
 * `invert_position` / `invert_buttons` fix blinds wired backwards.
 *
 * Config: { "type": "cover", "options": { "entity_id": "cover.x", "name": "Sofa",
 *     "invert_position": false, "invert_buttons": false } }
 */
class CoverCard : CardRenderer {
    override val type = "cover"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entity(entityId)
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        val invertPosition = config.bool("invert_position", false)
        val invertButtons = config.bool("invert_buttons", false)

        val rawPosition = e?.attrInt("current_position")
        val position = rawPosition?.let { if (invertPosition) 100 - it else it }
        val open = when {
            position != null -> position > 0
            else -> (e?.state == "open") != invertPosition
        }
        val moving = e?.state == "opening" || e?.state == "closing"
        val stateLabel = when {
            moving -> e?.state?.humanise() + "…"
            position != null -> "$position% open"
            else -> e?.state?.humanise() ?: "—"
        }
        val action = rememberAction(ctx)

        fun call(service: String) = action.run(ServiceCall("cover", service, entityId))
        val openService = if (invertButtons) "close_cover" else "open_cover"
        val closeService = if (invertButtons) "open_cover" else "close_cover"

        TileRow(failed = action.failed) {
            IconWell(
                if (open) Icons.Filled.Blinds else Icons.Outlined.Blinds,
                when {
                    unavailable -> WellState.Unavailable
                    open -> WellState.On
                    else -> WellState.Off
                },
                modifier = Modifier.semantics { contentDescription = if (open) "$name, open" else "$name, closed" },
            )
            Spacer(Modifier.width(Space.m))
            TileTitle(
                name,
                stateLabel,
                when {
                    unavailable -> StateKind.Unavailable
                    moving || action.busy -> StateKind.Pending
                    open -> StateKind.On
                    else -> StateKind.Normal
                },
                Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                IconAction(Icons.Filled.KeyboardArrowUp, "Open $name", { call(openService) }, size = Touch.compact, enabled = live)
                IconAction(Icons.Filled.Stop, "Stop $name", { call("stop_cover") }, size = Touch.compact, enabled = live)
                IconAction(Icons.Filled.KeyboardArrowDown, "Close $name", { call(closeService) }, size = Touch.compact, enabled = live)
            }
        }
    }
}

/**
 * Fan: tap the name side to toggle; % readout; slower / faster by `step`
 * (default 20). Speed changes show immediately.
 *
 * Config: { "type": "fan", "options": { "entity_id": "fan.bedroom", "name": …, "step": 20 } }
 */
class FanCard : CardRenderer {
    override val type = "fan"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entity(entityId)
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val actualOn = e?.isOn == true
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        val actualPct = e?.attrInt("percentage") ?: 0
        val step = config.int("step", 20).coerceAtLeast(1)

        val onOpt = rememberOptimistic(actualOn)
        val on = onOpt.show(actualOn)
        val pctOpt = rememberOptimistic(actualPct)
        val pct = pctOpt.show(actualPct)
        val action = rememberAction(ctx)

        fun setPct(p: Int) {
            val v = p.coerceIn(0, 100)
            pctOpt.set(v)
            onOpt.set(v > 0)
            action.run(
                ServiceCall.of("fan", "set_percentage", entityId, "percentage" to v),
                onFail = { pctOpt.clear(); onOpt.clear() },
            )
        }

        TileRow(background = if (on) AstrionTheme.fanOnBg else AstrionTheme.cardBg, failed = action.failed) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(Radius.control))
                    .tap(enabled = live, onClickLabel = if (on) "Turn $name off" else "Turn $name on") {
                        onOpt.set(!on)
                        action.run(ServiceCall("fan", "toggle", entityId), onFail = { onOpt.clear() })
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconWell(
                    Icons.Filled.Air,
                    when {
                        unavailable -> WellState.Unavailable
                        on -> WellState.On
                        else -> WellState.Off
                    },
                )
                Spacer(Modifier.width(Space.m))
                TileTitle(
                    name,
                    if (on) "$pct%" else "Off",
                    when {
                        unavailable -> StateKind.Unavailable
                        action.busy -> StateKind.Pending
                        on -> StateKind.On
                        else -> StateKind.Normal
                    },
                    Modifier.weight(1f),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                IconAction(Icons.Filled.KeyboardArrowDown, "$name slower", { setPct(pct - step) }, size = Touch.compact, enabled = live)
                IconAction(Icons.Filled.KeyboardArrowUp, "$name faster", { setPct(pct + step) }, size = Touch.compact, enabled = live)
            }
        }
    }
}

/**
 * Switch tile: the whole row toggles. `icon` (heater / fan / bulb) and
 * `on_color` (the row's fill while on; "#RRGGBB" or "#AARRGGBB").
 *
 * Config: { "type": "switch", "options": { "entity_id": "switch.porch", "name": "Porch",
 *     "icon": "heater", "on_color": "#2E5A46" } }
 */
class SwitchCard : CardRenderer {
    override val type = "switch"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entity(entityId)
        val name = config.string("name") ?: e?.friendlyName ?: entityId
        val actualOn = e?.isOn == true
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        val icon = switchIcon(config.string("icon"))
        val onColor = parseHexColor(config.options["on_color"]) ?: AstrionTheme.switchOnDefault
        val opt = rememberOptimistic(actualOn)
        val on = opt.show(actualOn)
        val action = rememberAction(ctx)

        TileRow(
            background = if (on) onColor else AstrionTheme.cardBg,
            failed = action.failed,
            modifier = Modifier.tap(enabled = live, onClickLabel = if (on) "Turn $name off" else "Turn $name on") {
                opt.set(!on)
                action.run(ServiceCall(entityId.substringBefore('.'), "toggle", entityId), onFail = { opt.clear() })
            },
        ) {
            IconWell(
                icon,
                when {
                    unavailable -> WellState.Unavailable
                    on -> WellState.On
                    else -> WellState.Off
                },
                modifier = Modifier.semantics { contentDescription = if (on) "$name, on" else "$name, off" },
            )
            Spacer(Modifier.width(Space.m))
            TileTitle(
                name,
                if (on) "On" else "Off",
                when {
                    unavailable -> StateKind.Unavailable
                    on -> StateKind.On
                    else -> StateKind.Normal
                },
                Modifier.weight(1f),
            )
            if (action.busy) PendingSpinner()
        }
    }
}

/** Map a switch card's `icon` option name to a Material icon. */
private fun switchIcon(name: String?): ImageVector = when (name) {
    "heater", "heat" -> Icons.Filled.Whatshot
    "fan" -> Icons.Filled.Air
    "bulb", "light" -> Icons.Filled.Lightbulb
    else -> Icons.Filled.PowerSettingsNew
}
