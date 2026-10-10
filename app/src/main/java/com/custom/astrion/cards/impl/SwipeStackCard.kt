package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.LocalNavEpoch
import com.custom.astrion.ui.LocalPageNameOverride
import com.custom.astrion.ui.tap
import kotlinx.coroutines.launch

/**
 * Generic swipeable container: stacks child cards on top of each other and
 * swipes between them. With `titles`, a segmented tab bar picks the page;
 * without, a title and pagination dots.
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
 * `device_names` (optional, one per child) replaces the page's device name in
 * the footer while that child is showing.
 *
 * A tab can hold several cards: { "type": "column", "options": {
 *   "spacing": 10, "cards": [ ... ] } }.
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
        // A button jump (the music key, the 5 min home timer) opens on the
        // first tab rather than wherever it was left.
        val navEpoch = LocalNavEpoch.current
        LaunchedEffect(navEpoch) {
            if (navEpoch > 0 && pagerState.currentPage != 0) pagerState.scrollToPage(0)
        }

        // Per-tab device names for the page footer (Media: BÆOSOUND, …,
        // BÆOLINK); without `device_names` the page's own name stands.
        val deviceNames = config.stringList("device_names")
        val nameOverride = LocalPageNameOverride.current
        if (deviceNames.isNotEmpty() && nameOverride != null) {
            val tabName = deviceNames.getOrNull(pagerState.currentPage)
            SideEffect { nameOverride.value = tabName }
        }

        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (titles.size >= children.size) {
                // Segmented tab bar ("Player | Media"): says what's behind each
                // page and, unlike a swipe, always gets through children built
                // from LazyRows (which eat horizontal drags).
                // Recessed track; the current tab sinks a shade darker and
                // gets a bright Hailstorm neon rule under its name. Gunmetal fill is kept for
                // things that are ON (Linked, BÆOLINK, the active scene), so
                // the tab no longer reads as a switched-on button.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(AstrionTheme.trackBg)
                        .padding(3.dp),
                    horizontalArrangement = Arrangement.spacedBy(3.dp),
                ) {
                    children.indices.forEach { i ->
                        val current = i == pagerState.currentPage
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .height(38.dp)
                                .clip(RoundedCornerShape(9.dp))
                                .background(if (current) TabSelectedBg else AstrionTheme.trackBg)
                                .tap { scope.launch { pagerState.animateScrollToPage(i) } },
                            contentAlignment = Alignment.Center,
                        ) {
                            // Cut-out titles: a dark drop shadow sinks the
                            // names into the track; the current one also
                            // glows faintly in the neon of its rule, which
                            // reads larger without a bigger font.
                            Text(
                                titles[i],
                                color = if (current) AstrionTheme.textPrimary else AstrionTheme.textMuted,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                style = TextStyle(
                                    shadow = if (current) {
                                        Shadow(TabNeon.copy(alpha = 0.55f), Offset(0f, 0f), blurRadius = 10f)
                                    } else {
                                        Shadow(Color.Black.copy(alpha = 0.75f), Offset(0f, 2f), blurRadius = 3f)
                                    },
                                ),
                            )
                            if (current) {
                                // Neon rule: a faint glow rising off a bright core.
                                Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 8.dp)
                                        .size(width = 40.dp, height = 10.dp)
                                        .background(
                                            Brush.radialGradient(
                                                listOf(TabNeon.copy(alpha = 0.18f), Color.Transparent),
                                            )
                                        ),
                                )
                                Box(
                                    Modifier
                                        .align(Alignment.BottomCenter)
                                        .padding(bottom = 5.dp)
                                        .size(width = 26.dp, height = 3.dp)
                                        .clip(RoundedCornerShape(2.dp))
                                        .background(TabNeon),
                                )
                            }
                        }
                    }
                }
            } else Row(
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
                // Shorter tabs sit at the top rather than centred in the pager.
                verticalAlignment = Alignment.Top,
            ) { page ->
                val child = children[page]
                val childType = child["type"] as? String
                val childOptions = (child["options"] as? Map<String, Any?>) ?: emptyMap()
                Box(Modifier.fillMaxWidth()) {
                    if (childType == "column") {
                        // A tab holding several cards, spaced like the page.
                        val cards = (childOptions["cards"] as? List<Map<String, Any?>>) ?: emptyList()
                        val gap = (childOptions["spacing"] as? Number)?.toInt() ?: 10
                        Column(verticalArrangement = Arrangement.spacedBy(gap.dp)) {
                            cards.forEach { c ->
                                val t = c["type"] as? String ?: return@forEach
                                val o = (c["options"] as? Map<String, Any?>) ?: emptyMap()
                                CardRegistry.get(t)?.Render(CardConfig(t, o), ctx)
                            }
                        }
                    } else {
                        childType?.let { CardRegistry.get(it) }
                            ?.Render(CardConfig(childType, childOptions), ctx)
                    }
                }
            }
        }
    }
}

/** The current tab: a shade darker than the track it sits in. */
private val TabSelectedBg = Color(0xFF131A21)

/** Hailstorm pushed brighter for the tab's neon rule. */
private val TabNeon = Color(0xFFA6E3D9)
