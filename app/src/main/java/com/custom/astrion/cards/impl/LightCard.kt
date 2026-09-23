package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lightbulb
import androidx.compose.material.icons.outlined.Lightbulb
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.IconWell
import com.custom.astrion.ui.PendingSpinner
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.StateKind
import com.custom.astrion.ui.StateLine
import com.custom.astrion.ui.WellState
import com.custom.astrion.ui.rememberAction
import com.custom.astrion.ui.rememberOptimistic
import com.custom.astrion.ui.tap

/**
 * Simple light tile: tap anywhere to toggle. On is the amber well plus a
 * filled bulb (not colour alone); optimistic, with pending and failure.
 *
 * Config: { "type": "light", "options": { "entity_id": "light.kitchen", "name": "Kitchen" } }
 */
class LightCard : CardRenderer {
    override val type = "light"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val e = ctx.entity(entityId)
        val label = config.string("name") ?: e?.friendlyName ?: entityId
        val unavailable = e == null || e.isUnavailable
        val live = !unavailable && ctx.connected
        val actualOn = e?.isOn == true
        val opt = rememberOptimistic(actualOn)
        val on = opt.show(actualOn)
        val action = rememberAction(ctx)
        val shape = RoundedCornerShape(Radius.card)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 72.dp)
                .clip(shape)
                .background(if (on) AstrionTheme.onWell else AstrionTheme.cardBg)
                .then(if (action.failed) Modifier.border(2.dp, AstrionTheme.danger, shape) else Modifier)
                .tap(enabled = live, onClickLabel = if (on) "Turn $label off" else "Turn $label on") {
                    opt.set(!on)
                    action.run(ServiceCall(entityId.substringBefore('.'), "toggle", entityId), onFail = { opt.clear() })
                }
                .padding(horizontal = Space.l),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconWell(
                if (on) Icons.Filled.Lightbulb else Icons.Outlined.Lightbulb,
                when {
                    unavailable -> WellState.Unavailable
                    on -> WellState.On
                    else -> WellState.Off
                },
            )
            Spacer(Modifier.width(Space.m))
            Column(Modifier.weight(1f)) {
                Text(label, style = AstrionType.title, color = AstrionTheme.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    unavailable -> StateLine("", StateKind.Unavailable)
                    on -> StateLine("On", StateKind.On)
                    else -> StateLine("Off")
                }
            }
            if (action.busy) PendingSpinner()
        }
    }
}
