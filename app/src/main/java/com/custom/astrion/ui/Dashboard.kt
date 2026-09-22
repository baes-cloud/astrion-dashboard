package com.custom.astrion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.config.AppConfig
import com.custom.astrion.config.PageConfig
import com.custom.astrion.ha.ConnectionState
import com.custom.astrion.ha.EntityMap
import com.custom.astrion.ha.HaClient

/**
 * Swipeable, paginated dashboard. Each config page is one screen; pages are
 * reached with the physical shortcut buttons (see MainActivity hotkeys).
 *
 * Sized for the HA100 panel — 480x800 at density 220, i.e. 349 x 582 dp
 * logical. That width is the binding constraint on every layout decision here:
 * a 48dp touch target is 14% of the screen.
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
    // Built once per client, NOT per recomposition. See CardContext's docs —
    // rebuilding this was recomposing every card on every page on every HA
    // event, which on the Main page means the whole floorplan every time
    // anyone walks past a radar.
    val ctx = remember(client) { CardContext(entitiesState, client, connectionState) }

    val pageCount = config.pages.size.coerceAtLeast(1)
    val pagerState = rememberPagerState(
        initialPage = config.startPage.coerceIn(0, pageCount - 1),
        pageCount = { pageCount },
    )

    // Hardware-button navigation: jump to the requested page, then clear it.
    LaunchedEffect(navTarget) {
        val t = navTarget ?: return@LaunchedEffect
        if (t in 0 until pageCount) pagerState.scrollToPage(t)
        onNavHandled()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AstrionTheme.pageBg),
    ) {
        HorizontalPager(
            state = pagerState,
            // Swipe-to-change-page is paused: a horizontal-ish touch meant for
            // a slider/drag control inside a card (volume bar, brightness
            // pill) could otherwise get mistaken for a page swipe. Pages are
            // reached with the four physical shortcut buttons.
            userScrollEnabled = false,
            modifier = Modifier.fillMaxSize(),
        ) { pageIndex ->
            PageContent(config.pages[pageIndex], ctx)
        }

        // Banners overlay the dashboard rather than being inserted above it.
        // Inserting them pushed every page down by ~44dp — 7.5% of a 582dp
        // screen — reflowing layouts that already overflow, for the entire
        // time HA was unreachable.
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

@Composable
private fun PageContent(page: PageConfig, ctx: CardContext) {
    // Cards can pin to "top" (fixed header section, e.g. Scenes) or "bottom"
    // (fixed footer section); everything else sits in between.
    //
    // A middle card can also ask for "fill", meaning it absorbs whatever
    // vertical space the other cards leave. When a page has one, the middle
    // section stops scrolling and lays out to exactly the screen height — used
    // on Main so the floorplan grows into the space instead of leaving a band
    // of empty background under the media row.
    val pinnedTop = page.cards.filter { it.options["pin"] == "top" }
    val pinnedBottom = page.cards.filter { it.options["pin"] == "bottom" }
    val middle = page.cards.filter { it.options["pin"] != "top" && it.options["pin"] != "bottom" }
    val hasFill = middle.any { it.options["pin"] == "fill" }

    Column(modifier = Modifier.fillMaxSize()) {
        if (pinnedTop.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AstrionTheme.pinnedTopBg)
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
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
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
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
 * Connection state.
 *
 * `CONNECTING` is the transient, harmless state and used to get a persistent
 * full-width bar in a muted colour that vanished at a glance, while the states
 * that actually need you to do something shared the same weight. Now
 * connecting is a small pill that reserves nothing, and a real failure is
 * loud. Cards separately gate their own taps on `ctx.connected`, so a dead
 * socket also disables the controls rather than letting them look live.
 */
@Composable
private fun ConnectionBanner(connectionState: State<ConnectionState>) {
    val connection by connectionState
    if (connection == ConnectionState.CONNECTED) return

    if (connection == ConnectionState.CONNECTING || connection == ConnectionState.AUTHENTICATING) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xCC1B343D))
                    .padding(horizontal = 10.dp, vertical = 3.dp),
            ) {
                Text("Connecting…", color = AstrionTheme.textSecondary, fontSize = 11.sp)
            }
        }
        return
    }

    val label = when (connection) {
        ConnectionState.AUTH_FAILED -> "Auth failed — check token"
        ConnectionState.ERROR -> "Connection error — retrying"
        else -> "Disconnected — controls inactive"
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(AstrionTheme.danger)
            .padding(10.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = Color(0xFF241012),
            fontSize = AstrionTheme.body,
            fontWeight = FontWeight.Bold,
        )
    }
}

/**
 * Bad-config notice. Dismissible: this is a developer-facing diagnostic that
 * fires on a JSON typo — something you fix thirty seconds later — and it used
 * to hold permanent screen space with no way to clear it. The config reloads
 * on every onResume, so if the file is still broken it simply comes back.
 */
@Composable
private fun ConfigNoticeBanner(text: String) {
    var shown by remember(text) { mutableStateOf(true) }
    if (!shown) return
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF4A3B1E))
            .tap { shown = false }
            .padding(10.dp),
    ) {
        Text("$text  (tap to dismiss)", color = Color(0xFFE8C77B), fontSize = AstrionTheme.label)
    }
}

@Composable
private fun UnknownCard(type: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0xFF2A2030))
            .padding(14.dp),
    ) {
        Text(
            "Unknown card type: $type",
            color = Color(0xFFE0A0A0),
            fontSize = AstrionTheme.label,
        )
    }
}
