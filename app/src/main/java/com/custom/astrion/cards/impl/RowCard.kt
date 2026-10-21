package com.custom.astrion.cards.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.cards.CardRenderer

/**
 * Generic horizontal container: lays out child cards side by side with equal
 * weight. Lets the file config build rows (e.g. two cover buttons) without a
 * dedicated card type per combination.
 *
 * Config shape:
 *   { "type": "row", "options": { "cards": [
 *       { "type": "cover", "options": { "entity_id": "cover.blinds", "name": "Sofa" } },
 *       { "type": "cover", "options": { "entity_id": "cover.blinds", "name": "Bed" } }
 *   ] } }
 *
 * A child's own `"width"` (dp) fixes its width instead, e.g. the door tile
 * beside Auto lights on Main; the rest share what's left.
 */
class RowCard : CardRenderer {
    override val type = "row"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val children = (config.options["cards"] as? List<Map<String, Any?>>) ?: emptyList()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            children.forEach { child ->
                val childType = child["type"] as? String ?: return@forEach
                val childOptions = (child["options"] as? Map<String, Any?>) ?: emptyMap()
                val renderer = CardRegistry.get(childType) ?: return@forEach
                val width = (childOptions["width"] as? Number)?.toFloat()
                Box(modifier = if (width != null) Modifier.width(width.dp) else Modifier.weight(1f)) {
                    renderer.Render(CardConfig(childType, childOptions), ctx)
                }
            }
        }
    }
}
