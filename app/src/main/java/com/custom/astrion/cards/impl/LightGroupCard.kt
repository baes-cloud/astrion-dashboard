package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionSwitch
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.LocalOverlay
import com.custom.astrion.ui.PendingSpinner
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.SectionLabel
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.StateKind
import com.custom.astrion.ui.StateLine
import com.custom.astrion.ui.liveOrDim
import com.custom.astrion.ui.rememberAction
import com.custom.astrion.ui.rememberOptimistic
import com.custom.astrion.ui.tapAndHold
import kotlin.math.roundToInt

/**
 * A page-section of lights, auto-split by each light's own live
 * `supported_color_modes`:
 *   - DIMMABLE: 2-column tiles with icon, name, state and a brightness bar
 *     (drag / tap; 5 % floor). Long-press the icon for the colour sheet.
 *   - OTHER LIGHTS: 2-column on/off rows with a real switch.
 * Anything reporting nothing yet is treated as dimmable, so nothing
 * flickers to a switch on first paint.
 *
 * Config: { "type": "light_group", "options": { "lights": [
 *     { "entity_id": "light.downlights", "name": "Downlights" } ] } }
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
            val modes = ctx.entity(id)?.attrStringList("supported_color_modes")
            val isSimple = modes != null && modes.isNotEmpty() && modes.all { it == "onoff" }
            if (isSimple) simple.add(id to name) else dimmable.add(id to name)
        }

        Column(verticalArrangement = Arrangement.spacedBy(Space.xl)) {
            if (dimmable.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.gutter)) {
                    SectionLabel("Dimmable")
                    GridRows(dimmable) { id, name -> DimmableTile(ctx, id, name) }
                }
            }
            if (simple.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(Space.gutter)) {
                    SectionLabel("Other lights")
                    GridRows(simple) { id, name -> ToggleRow(ctx, id, name) }
                }
            }
        }
    }

    /** Two per row, padding the final odd row so widths stay equal. */
    @Composable
    private fun GridRows(
        items: List<Pair<String, String?>>,
        content: @Composable (String, String?) -> Unit,
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(Space.gutter)) {
            items.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(Space.gutter)) {
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
        val e = ctx.entity(entityId)
        val name = configName ?: e?.friendlyName ?: entityId
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        val overlay = LocalOverlay.current
        val actualOn = e?.isOn == true
        val onOpt = rememberOptimistic(actualOn)
        val on = onOpt.show(actualOn)
        val level = lightLevel(e)
        var dragLevel by remember(level) { mutableStateOf(level) }
        val action = rememberAction(ctx)

        fun commit(fraction: Float) {
            onOpt.set(true)
            action.run(
                ServiceCall.of("light", "turn_on", entityId, "brightness_pct" to brightnessPct(fraction)),
                onFail = { onOpt.clear() },
            )
        }
        val commitNow by rememberUpdatedState(::commit)
        val shape = RoundedCornerShape(Radius.card)

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(AstrionTheme.cardBg)
                .then(if (action.failed) Modifier.border(2.dp, AstrionTheme.danger, shape) else Modifier)
                .padding(Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Space.s)) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .liveOrDim(live)
                        .clip(CircleShape)
                        .background(
                            when {
                                unavailable -> AstrionTheme.unavailableWell
                                on -> AstrionTheme.on
                                else -> AstrionTheme.controlBg
                            }
                        )
                        .tapAndHold(
                            enabled = live,
                            onClick = {
                                onOpt.set(!on)
                                action.run(ServiceCall("light", "toggle", entityId), onFail = { onOpt.clear() })
                            },
                            onLongClick = {
                                overlay.show { LightDetailSheet(entityId, ctx, onClose = { overlay.dismiss() }) }
                            },
                        )
                        .semantics { contentDescription = "$name, ${if (on) "on" else "off"}. Hold for colour" },
                    contentAlignment = Alignment.Center,
                ) {
                    if (action.busy) {
                        PendingSpinner(size = 18.dp, color = if (on) AstrionTheme.onBg else AstrionTheme.accent)
                    } else {
                        Icon(
                            when {
                                unavailable -> Icons.Filled.CloudOff
                                on -> Icons.Filled.Lightbulb
                                else -> Icons.Outlined.Lightbulb
                            },
                            contentDescription = null,
                            tint = when {
                                unavailable -> AstrionTheme.unavailable
                                on -> AstrionTheme.onBg
                                else -> AstrionTheme.textOnControl
                            },
                            modifier = Modifier.size(20.dp),
                        )
                    }
                }
                Column(Modifier.weight(1f)) {
                    Text(name, style = AstrionType.bodyStrong, color = AstrionTheme.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    when {
                        unavailable -> StateLine("", StateKind.Unavailable)
                        on -> StateLine("${(dragLevel * 100).roundToInt()}%", StateKind.On)
                        else -> StateLine("Off")
                    }
                }
            }
            // Brightness bar: 36dp touch height around a 12dp track.
            BoxWithConstraints(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(36.dp)
                    .liveOrDim(live)
                    .pointerInput(entityId, live) {
                        if (!live) return@pointerInput
                        detectHorizontalDragGestures(onDragEnd = { commitNow(dragLevel) }) { change, _ ->
                            dragLevel = (change.position.x / size.width).coerceIn(0f, 1f)
                        }
                    }
                    .pointerInput(entityId, live) {
                        if (!live) return@pointerInput
                        detectTapGestures { offset ->
                            val frac = (offset.x / size.width).coerceIn(0f, 1f)
                            dragLevel = brightnessPct(frac) / 100f
                            commitNow(frac)
                        }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(12.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(AstrionTheme.trackBg),
                ) {
                    if (on) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(dragLevel.coerceAtLeast(0.02f))
                                .fillMaxHeight()
                                .background(AstrionTheme.on),
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun ToggleRow(ctx: CardContext, entityId: String, configName: String?) {
        val e = ctx.entity(entityId)
        val name = configName ?: e?.friendlyName ?: entityId
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        val actualOn = e?.isOn == true
        val opt = rememberOptimistic(actualOn)
        val on = opt.show(actualOn)
        val action = rememberAction(ctx)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clip(RoundedCornerShape(Radius.card))
                .background(AstrionTheme.cardBg)
                .padding(start = Space.m, end = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(name, style = AstrionType.bodyStrong, color = AstrionTheme.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    unavailable -> StateLine("", StateKind.Unavailable)
                    on -> StateLine("On", StateKind.On)
                    else -> StateLine("Off")
                }
            }
            AstrionSwitch(
                on = on,
                onClick = {
                    opt.set(!on)
                    action.run(ServiceCall("light", "toggle", entityId), onFail = { opt.clear() })
                },
                description = if (on) "Turn $name off" else "Turn $name on",
                enabled = live,
                pending = action.busy,
            )
        }
    }
}
