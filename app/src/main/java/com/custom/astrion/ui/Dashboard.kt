package com.custom.astrion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.config.AppConfig
import com.custom.astrion.config.PageConfig
import com.custom.astrion.ha.ConnectionState
import com.custom.astrion.ha.EntityMap
import com.custom.astrion.ha.HaClient

/**
 * The page shell: one page per config page, reached by the physical shortcut
 * buttons or by tapping the page header (page picker).
 *
 * Sized for the HA100 panel — 480x800 at density 220, i.e. 349 x 582 dp
 * logical. That width is the binding constraint on every layout decision: a
 * 48dp touch target is 14% of the screen.
 */
@Composable
fun Dashboard(
    client: HaClient,
    entitiesState: State<EntityMap>,
    connectionState: State<ConnectionState>,
    config: AppConfig,
    configNotice: String? = null,
    /** Page index requested by a hardware button; consumed via onNavHandled. */
    navTarget: Int? = null,
    onNavHandled: () -> Unit = {},
) {
    // Built once per client, NOT per recomposition.
    val ctx = remember(client) { CardContext(entitiesState, client, connectionState) }

    val pageCount = config.pages.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(
        initialPage = config.startPage.coerceIn(0, pageCount - 1),
        pageCount = { pageCount },
    )
    val scope = rememberCoroutineScope()
    val overlay = LocalOverlay.current
    val nav = remember(config, pagerState, overlay) { DashboardNav(config, pagerState, scope, overlay) }

    // Hardware-button navigation: jump to the requested page, then clear it.
    LaunchedEffect(navTarget) {
        val t = navTarget ?: return@LaunchedEffect
        if (t in 0 until pageCount) pagerState.scrollToPage(t)
        onNavHandled()
    }

    CompositionLocalProvider(LocalNav provides nav) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(AstrionTheme.pageBg),
        ) {
            HorizontalPager(
                state = pagerState,
                // Swipe-to-change-page stays off: horizontal drags belong to
                // sliders and poster shelves. Pages are reached with the four
                // shortcut buttons, or by tapping the page header.
                userScrollEnabled = false,
                modifier = Modifier.fillMaxSize(),
            ) { pageIndex ->
                PageContent(config.pages[pageIndex], ctx)
            }

            // Banners overlay the page rather than pushing it down.
            Column(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth(),
            ) {
                ConnectionBanner(connectionState)
                if (configNotice != null) ConfigNoticeBanner(configNotice)
            }
        }
    }
}

@Composable
private fun PageContent(page: PageConfig, ctx: CardContext) {
    // `pin: "top"` → fixed header band, `pin: "bottom"` → fixed footer band,
    // `pin: "fill"` → a middle card that absorbs the leftover height (the
    // page then lays out to exactly the screen instead of scrolling).
    val pinnedTop = page.cards.filter { it.options["pin"] == "top" }
    val pinnedBottom = page.cards.filter { it.options["pin"] == "bottom" }
    val middle = page.cards.filter { it.options["pin"] != "top" && it.options["pin"] != "bottom" }
    val hasFill = middle.any { it.options["pin"] == "fill" }
    // Every page gets touch navigation: pages without a clock_header get a
    // plain tappable title so the picker is always one tap away.
    val hasHeader = page.cards.any { it.type == "clock_header" }

    Column(modifier = Modifier.fillMaxSize()) {
        if (pinnedTop.isNotEmpty() || !hasHeader) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AstrionTheme.pinnedTopBg)
                    .padding(horizontal = Space.gutter, vertical = Space.xs),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                if (!hasHeader) NavHeader(page.name)
                pinnedTop.forEach { RenderCard(it, ctx) }
            }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .then(
                    // A weighted child can't live inside a scrollable column
                    // (infinite height), so it's one or the other per page.
                    if (hasFill) Modifier else Modifier.verticalScroll(rememberScrollState())
                )
                .padding(horizontal = Space.gutter, vertical = Space.s),
            verticalArrangement = Arrangement.spacedBy(Space.gutter),
        ) {
            middle.forEach { card ->
                if (card.options["pin"] == "fill") {
                    Box(Modifier.weight(1f).fillMaxWidth()) { RenderCard(card, ctx) }
                } else {
                    RenderCard(card, ctx)
                }
            }
        }
        if (pinnedBottom.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AstrionTheme.pinnedBottomBg)
                    .padding(horizontal = Space.gutter, vertical = Space.s),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                pinnedBottom.forEach { RenderCard(it, ctx) }
            }
        }
    }
}

@Composable
private fun RenderCard(cardConfig: CardConfig, ctx: CardContext) {
    val renderer = CardRegistry.get(cardConfig.type)
    if (renderer != null) {
        renderer.Render(cardConfig, ctx)
    } else {
        UnknownCard(cardConfig.type)
    }
}

/**
 * Connection state. `CONNECTING` is transient and harmless: a small pill
 * that reserves nothing. A real failure is loud. Cards separately dim and
 * gate their controls on `ctx.connected`.
 */
@Composable
private fun ConnectionBanner(connectionState: State<ConnectionState>) {
    val connection by connectionState
    if (connection == ConnectionState.CONNECTED) return

    if (connection == ConnectionState.CONNECTING || connection == ConnectionState.AUTHENTICATING) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = Space.xs),
            horizontalArrangement = Arrangement.Center,
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(Radius.control))
                    .background(AstrionTheme.raised)
                    .padding(horizontal = Space.m, vertical = Space.xs),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PendingSpinner(size = 12.dp, color = AstrionTheme.accent)
                Spacer(Modifier.width(Space.s))
                Text("Connecting…", style = AstrionType.label, color = AstrionTheme.textPrimary)
            }
        }
        return
    }

    val label = when (connection) {
        ConnectionState.AUTH_FAILED -> "Auth failed — check token"
        ConnectionState.ERROR -> "Connection error — retrying"
        else -> "Disconnected — controls inactive"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AstrionTheme.dangerStrong)
            .padding(horizontal = Space.m, vertical = Space.gutter),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.ErrorOutline, contentDescription = null,
            tint = AstrionTheme.dangerInk, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Space.s))
        Text(label, style = AstrionType.bodyStrong, color = AstrionTheme.dangerInk)
    }
}

/**
 * Bad-config notice. Dismissible: a developer-facing diagnostic you fix in
 * thirty seconds. The config reloads on every resume, so if the file is
 * still broken it simply comes back.
 */
@Composable
private fun ConfigNoticeBanner(text: String) {
    var shown by remember(text) { mutableStateOf(true) }
    if (!shown) return
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AstrionTheme.noticeBg)
            .tap { shown = false }
            .padding(Space.gutter),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.Info, contentDescription = null,
            tint = AstrionTheme.noticeInk, modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(Space.s))
        Text("$text  (tap to dismiss)", style = AstrionType.label, color = AstrionTheme.noticeInk)
    }
}

@Composable
private fun UnknownCard(type: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.card))
            .background(AstrionTheme.dangerBg)
            .padding(Space.card),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Filled.ErrorOutline, contentDescription = null,
            tint = AstrionTheme.danger, modifier = Modifier.size(18.dp),
        )
        Spacer(Modifier.width(Space.s))
        Text("Unknown card type: $type", style = AstrionType.label, color = AstrionTheme.danger)
    }
}
