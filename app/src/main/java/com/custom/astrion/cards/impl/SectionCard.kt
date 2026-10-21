package com.custom.astrion.cards.impl

import com.custom.astrion.ui.AstrionTheme
import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.runtime.Composable
import androidx.compose.ui.text.font.FontWeight
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer

/**
 * A bare section heading, matching the labels the button_grid ("Playlists")
 * and light_zones cards draw above their contents. Lets any page group its
 * cards under a divider without that card type needing its own title option.
 *
 * Config: { "type": "section", "options": { "title": "Blinds" } }
 */
class SectionCard : CardRenderer {
    override val type = "section"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val title = config.string("title") ?: return
        Text(
            title,
            color = AstrionTheme.textPrimary,
            fontSize = AstrionTheme.size.small,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 2.dp, top = 2.dp),
        )
    }
}
