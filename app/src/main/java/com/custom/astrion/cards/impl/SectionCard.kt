package com.custom.astrion.cards.impl

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
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
            color = Color(0xFF9FBAC0),
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 1.sp,
        )
    }
}
