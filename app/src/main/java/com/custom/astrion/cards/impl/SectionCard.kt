package com.custom.astrion.cards.impl

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ui.SectionLabel
import com.custom.astrion.ui.Space

/**
 * A bare section heading — the same [SectionLabel] every card uses above its
 * contents, so a page can group cards without a card type needing a title.
 *
 * Config: { "type": "section", "options": { "title": "Blinds" } }
 */
class SectionCard : CardRenderer {
    override val type = "section"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val title = config.string("title") ?: return
        SectionLabel(title, modifier = Modifier.padding(top = Space.xs))
    }
}
