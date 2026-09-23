package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.ChoiceChip
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.TouchTarget
import kotlinx.coroutines.launch

/**
 * Children stacked one behind another, swiped between — e.g. the full player
 * and the music shelves in one slot on Media.
 *
 * With `titles`, the switcher is a segmented tab bar ("Player | Media",
 * 44dp tall, the current tab filled) instead of the old 7dp dots: children
 * built from LazyRows eat horizontal drags, so the tabs are the way through
 * that always works, and they say what's behind each page. Without titles,
 * dots in 44dp targets.
 *
 * Children are top-aligned (a shorter child used to be vertically centred in
 * the fixed height, leaving blank bands above and below it).
 *
 * Config: { "type": "swipe_stack", "options": {
 *     "titles": ["Player", "Media"], "height": 420,
 *     "cards": [ { "type": …, "options": { … } }, … ] } }
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

        Column(verticalArrangement = Arrangement.spacedBy(Space.s)) {
            if (titles.size >= children.size) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(Radius.control))
                        .background(AstrionTheme.controlSunken)
                        .padding(2.dp),
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    children.indices.forEach { i ->
                        ChoiceChip(
                            label = titles[i],
                            selected = i == pagerState.currentPage,
                            onClick = { scope.launch { pagerState.animateScrollToPage(i) } },
                            modifier = Modifier.weight(1f),
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    children.indices.forEach { i ->
                        val current = i == pagerState.currentPage
                        TouchTarget(onClick = { scope.launch { pagerState.animateScrollToPage(i) } }) {
                            Box(
                                Modifier
                                    .size(if (current) 10.dp else 8.dp)
                                    .clip(CircleShape)
                                    .background(if (current) AstrionTheme.accent else AstrionTheme.controlBg),
                            )
                        }
                    }
                }
            }

            HorizontalPager(
                state = pagerState,
                modifier = if (height > 0) Modifier.height(height.dp) else Modifier,
                verticalAlignment = Alignment.Top,
            ) { page ->
                val child = children[page]
                val childType = child["type"] as? String
                val childOptions = (child["options"] as? Map<String, Any?>) ?: emptyMap()
                val renderer = childType?.let { CardRegistry.get(it) }
                Box(Modifier.fillMaxWidth()) {
                    if (childType != null && renderer != null) {
                        renderer.Render(CardConfig(childType, childOptions), ctx)
                    }
                }
            }
        }
    }
}
