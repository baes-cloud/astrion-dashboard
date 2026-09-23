package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.Radius

/**
 * Vertical container that joins its children into ONE card: a single shape
 * and fill, no gaps between them.
 *
 * The sibling of [RowCard] (side by side) and [SwipeStackCard] (one behind the
 * other). Each child is rendered with `flush: true`, which tells the cards
 * that support it to drop their own rounding and background — otherwise every
 * join would show the child's own rounded corners notched into the parent.
 *
 * A child with `pin: fill` absorbs the remaining height, exactly as it would at
 * page level; give the stack itself `pin: fill` so it has height to share.
 *
 * Config shape:
 *   { "type": "stack", "options": { "pin": "fill", "cards": [
 *       { "type": "lock",             "options": { ... } },
 *       { "type": "picture_elements", "options": { "pin": "fill", ... } },
 *       { "type": "now_playing",      "options": { ... } }
 *   ] } }
 */
class StackCard : CardRenderer {
    override val type = "stack"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val children = (config.options["cards"] as? List<Map<String, Any?>>) ?: emptyList()
        if (children.isEmpty()) return
        val hasFill = children.any { (it["options"] as? Map<*, *>)?.get("pin") == "fill" }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .then(if (hasFill) Modifier.fillMaxHeight() else Modifier)
                .clip(RoundedCornerShape(Radius.card))
                .background(AstrionTheme.cardBg),
        ) {
            children.forEach { child ->
                val childType = child["type"] as? String ?: return@forEach
                val renderer = CardRegistry.get(childType) ?: return@forEach
                val childOptions = ((child["options"] as? Map<String, Any?>) ?: emptyMap()) + ("flush" to true)
                val card = CardConfig(childType, childOptions)
                if (childOptions["pin"] == "fill") {
                    Box(Modifier.weight(1f).fillMaxWidth()) { renderer.Render(card, ctx) }
                } else {
                    renderer.Render(card, ctx)
                }
            }
        }
    }
}
