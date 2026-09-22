package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.tap
import kotlinx.coroutines.launch

/**
 * Generic swipeable container: stacks child cards on top of each other and
 * swipes between them, with a title and pagination dots.
 *
 * The sibling of [RowCard] — that one puts children side by side, this one
 * puts them one behind the other. Used on Media to keep the big player and the
 * Sonos shelves in the same slot instead of making the page twice as long.
 *
 * The dots are tappable, not decoration. Children like `media_shelves` are
 * built from LazyRows, which eat horizontal drags for their own scrolling, so
 * on those pages the only reliable swipe surface is the gaps between rows —
 * the dots give a way through that always works.
 *
 * Config shape:
 *   { "type": "swipe_stack", "options": {
 *       "titles": ["Player", "Media"],
 *       "height": 330,
 *       "cards": [
 *         { "type": "media_player",  "options": { ... } },
 *         { "type": "media_shelves", "options": { ... } }
 *       ]
 *   } }
 *
 * `height` is in dp and fixes the swipe area. Left at 0 the pager sizes to its
 * content, which makes the whole page jump as you drag between children of
 * different heights.
 */
class SwipeStackCard : CardRenderer {
    override val type = "swipe_stack"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val children = (config.options["cards"] as? List<Map<String, Any?>>) ?: emptyList()
        if (children.isEmpty()) return
        val titles = config.stringList("titles")
        val height = config.int("height", 0)

        val pagerState = rememberPagerState(pageCount = { children.size })
        val scope = rememberCoroutineScope()

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    titles.getOrNull(pagerState.currentPage).orEmpty(),
                    color = AstrionTheme.textSecondary,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 1.sp,
                )
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    children.indices.forEach { i ->
                        val current = i == pagerState.currentPage
                        Box(
                            modifier = Modifier
                                // The dot is 7dp but the touch target is 22dp:
                                // a 7dp target is a third of the 24dp minimum
                                // and this is the fallback control for a page
                                // you may not be able to swipe to.
                                .size(22.dp)
                                .tap { scope.launch { pagerState.animateScrollToPage(i) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(if (current) 8.dp else 7.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (current) AstrionTheme.accent else AstrionTheme.controlBg
                                    ),
                            )
                        }
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = if (height > 0) Modifier.height(height.dp) else Modifier,
            ) { page ->
                val child = children[page]
                val childType = child["type"] as? String
                val childOptions = (child["options"] as? Map<String, Any?>) ?: emptyMap()
                val renderer = childType?.let { CardRegistry.get(it) }
                Box(Modifier.fillMaxWidth()) {
                    renderer?.Render(CardConfig(childType, childOptions), ctx)
                }
            }
        }
    }
}
