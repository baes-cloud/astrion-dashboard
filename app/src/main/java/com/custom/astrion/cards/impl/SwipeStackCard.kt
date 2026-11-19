package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
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
import com.custom.astrion.input.VolumeFocus
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.LocalNavEpoch
import com.custom.astrion.ui.LocalPageNameOverride
import com.custom.astrion.ui.components.BaeoSegmentItem
import com.custom.astrion.ui.components.BaeoSegmentedBar
import com.custom.astrion.ui.tap
import kotlinx.coroutines.launch

/**
 * Lets a child inside [SwipeStackCard] jump to a sibling tab (e.g., Player's
 * Active-Zone pill -> Zones tab 2, Media's Mini Now-Playing bar -> Player tab 0).
 */
val LocalTabSwitcher = compositionLocalOf<((Int) -> Unit)?> { null }

/**
 * Borderless matte swipeable container (Bæo v3.0):
 *  - Recessed `BaeoSegmentedBar` (`#141B22` trough + `#28333F` elevated matte
 *    pill — zero neon glow, zero drop shadows, zero borders).
 *  - `beyondViewportPageCount = 2` keeps all 3 Media tabs warm and composed.
 *  - `fillMaxSize()` inside `HorizontalPager` retires the hardcoded `height: 454`
 *    hack and supports `"pin": "fill"` children inside `"column"` tabs.
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
        val fill = config.options["pin"] == "fill"

        val pagerState = rememberPagerState(pageCount = { children.size })
        val scope = rememberCoroutineScope()
        val navEpoch = LocalNavEpoch.current
        LaunchedEffect(navEpoch) {
            if (navEpoch > 0 && pagerState.currentPage != 0) pagerState.scrollToPage(0)
        }
        LaunchedEffect(pagerState.currentPage) { VolumeFocus.clear() }

        val deviceNames = config.stringList("device_names")
        val nameOverride = LocalPageNameOverride.current
        if (deviceNames.isNotEmpty() && nameOverride != null) {
            val tabName = deviceNames.getOrNull(pagerState.currentPage)
            SideEffect { nameOverride.value = tabName }
        }

        // Lets the footer arrows step through these tabs (see Dashboard's step()).
        val tabsSlot = com.custom.astrion.ui.LocalPageTabsSlot.current
        androidx.compose.runtime.DisposableEffect(tabsSlot, children.size) {
            tabsSlot?.value = com.custom.astrion.ui.PageTabs(children.size, { pagerState.currentPage }) { idx, animate ->
                scope.launch { if (animate) pagerState.animateScrollToPage(idx) else pagerState.scrollToPage(idx) }
            }
            onDispose { tabsSlot?.value = null }
        }

        val switchToTab: (Int) -> Unit = { idx ->
            if (idx in children.indices) scope.launch { pagerState.animateScrollToPage(idx) }
        }

        Column(
            modifier = if (fill) Modifier.fillMaxSize() else Modifier,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (titles.size >= children.size) {
                BaeoSegmentedBar(trackColor = AstrionTheme.colors.surfacePrimary) {
                    children.indices.forEach { i ->
                        val current = i == pagerState.currentPage
                        BaeoSegmentItem(
                            label = titles[i],
                            selected = current,
                            height = 40.dp,
                            selectedBg = AstrionTheme.surfaceElevated,
                            selectedInk = AstrionTheme.textPrimary,
                            onClick = { switchToTab(i) },
                        )
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        titles.getOrNull(pagerState.currentPage).orEmpty(),
                        color = AstrionTheme.textSecondary,
                        fontSize = AstrionTheme.size.label,
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
                                    .size(22.dp)
                                    .tap { switchToTab(i) },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(if (current) 8.dp else 7.dp)
                                        .clip(CircleShape)
                                        .background(if (current) AstrionTheme.accent else AstrionTheme.controlBg),
                                )
                            }
                        }
                    }
                }
            }

            CompositionLocalProvider(LocalTabSwitcher provides switchToTab) {
                HorizontalPager(
                    state = pagerState,
                    beyondViewportPageCount = 2,
                    modifier = when {
                        fill -> Modifier.weight(1f).fillMaxWidth()
                        height > 0 -> Modifier.height(height.dp).fillMaxWidth()
                        else -> Modifier.fillMaxWidth()
                    },
                    verticalAlignment = Alignment.Top,
                    userScrollEnabled = titles.size < children.size,
                ) { page ->
                    val child = children[page]
                    val childType = child["type"] as? String
                    val childOptions = (child["options"] as? Map<String, Any?>) ?: emptyMap()
                    Box(if (fill) Modifier.fillMaxSize() else Modifier.fillMaxWidth()) {
                        if (childType == "column") {
                            val cards = (childOptions["cards"] as? List<Map<String, Any?>>) ?: emptyList()
                            val gap = (childOptions["spacing"] as? Number)?.toInt() ?: 10
                            val hasFillChild = fill && cards.any {
                                ((it["options"] as? Map<*, *>)?.get("pin") == "fill") || it["pin"] == "fill"
                            }
                            Column(
                                modifier = if (hasFillChild) Modifier.fillMaxSize() else Modifier.fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(gap.dp),
                            ) {
                                cards.forEach { c ->
                                    val t = c["type"] as? String ?: return@forEach
                                    val o = (c["options"] as? Map<String, Any?>) ?: emptyMap()
                                    val fillChild = hasFillChild && (o["pin"] == "fill" || c["pin"] == "fill")
                                    if (fillChild) {
                                        Box(Modifier.weight(1f).fillMaxWidth()) {
                                            CardRegistry.get(t)?.Render(CardConfig(t, o), ctx)
                                        }
                                    } else {
                                        CardRegistry.get(t)?.Render(CardConfig(t, o), ctx)
                                    }
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
}
