package com.custom.astrion.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRegistry
import com.custom.astrion.config.AppConfig
import com.custom.astrion.config.PageConfig
import com.custom.astrion.ha.ConnectionState
import com.custom.astrion.ha.HaClient
import kotlinx.coroutines.delay

/**
 * Whether the page this composable sits on is the one on screen. Hidden pages
 * stay composed (see [Dashboard]); anything that ticks on its own — clocks,
 * "x min ago" labels — can check this to stay still while off screen.
 */
val LocalPageVisible = compositionLocalOf { true }

/**
 * Whether the dashboard is actually on screen: screen on, app in front, no
 * screensaver over it. Live feeds (the RMM dots) stop while it isn't; the
 * dashboard stays composed underneath, so without this they kept streaming
 * with the screen off.
 */
val LocalDashboardShowing = compositionLocalOf { true }

/**
 * Wall-clock time, ticking once a minute just after the minute turns, for
 * every card that shows the time or a relative label ("12 min ago",
 * "Tomorrow 6:15"). One shared ticker instead of one per card, and it stands
 * still while the dashboard isn't showing (screen off, screensaver up):
 * the moment it shows again it jumps to the current time.
 */
val LocalMinuteClock = compositionLocalOf { System.currentTimeMillis() }

/** Provides [LocalMinuteClock] to [content]; see there. */
@Composable
fun MinuteClock(content: @Composable () -> Unit) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    val showing = LocalDashboardShowing.current
    LaunchedEffect(showing) {
        if (!showing) return@LaunchedEffect
        while (true) {
            now = System.currentTimeMillis()
            delay(60_000 - (System.currentTimeMillis() % 60_000) + 250)
        }
    }
    CompositionLocalProvider(LocalMinuteClock provides now, content = content)
}

/**
 * Counts how many times this page has been brought on screen. Pages stay
 * composed, so a card that loads data once (Plex rows, media shelves) would
 * otherwise only ever load at app start; keying the load on this refreshes it
 * on every visit, behind the cached rows.
 */
@Composable
fun rememberPageVisits(): Int {
    val visible = LocalPageVisible.current
    var visits by remember { mutableIntStateOf(0) }
    LaunchedEffect(visible) { if (visible) visits++ }
    return visits
}

/**
 * Measured but not placed when [hidden], so nothing inside is drawn while its
 * composition (and with it page and scroll state) stays alive.
 */
fun Modifier.unplacedWhen(hidden: Boolean): Modifier =
    if (!hidden) this else layout { measurable, constraints ->
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) {}
    }

/**
 * Paginated dashboard. Each config page is one screen; pages are
 * reached with the physical shortcut buttons (see MainActivity hotkeys).
 *
 * Sized for the HA100 panel — 480x800 at density 220, i.e. 349 x 582 dp
 * logical. That width is the binding constraint on every layout decision here:
 * a 48dp touch target is 14% of the screen.
 */
@Composable
fun Dashboard(
    client: HaClient,
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
    val ctx = remember(client) { CardContext(client, connectionState) }

    val pageCount = config.pages.size.coerceAtLeast(1)
    var current by remember { mutableIntStateOf(config.startPage.coerceIn(0, pageCount - 1)) }
    if (current >= pageCount) current = pageCount - 1

    // Hardware-button navigation: jump to the requested page, then clear it.
    LaunchedEffect(navTarget) {
        val t = navTarget ?: return@LaunchedEffect
        if (t in 0 until pageCount) current = t
        onNavHandled()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(AstrionTheme.pageBg),
    ) {
        // Every page stays composed and only the current one is placed (and so
        // drawn). This used to be a HorizontalPager holding just the visible
        // page, so each page key tore one page down and built the next from
        // scratch — Plex rows, shelves, the floorplan — 0.2–0.5s on this SoC.
        // Swiping was already off, so the pager added nothing. Memory is not
        // the constraint (≈70 MB of ≈590 MB free).
        // One name slot per page, for a card that names itself (see
        // LocalPageNameOverride); the footer reads the current page's.
        val nameOverrides = remember(config.pages) { config.pages.map { mutableStateOf<String?>(null) } }
        config.pages.forEachIndexed { i, page ->
            key(i) {
                CompositionLocalProvider(
                    LocalPageVisible provides (i == current),
                    LocalPageNameOverride provides nameOverrides[i],
                ) {
                    Box(Modifier.fillMaxSize().unplacedWhen(i != current)) {
                        PageContent(page, ctx)
                    }
                }
            }
        }

        // The device name sits in the footer slot every page leaves free. It's
        // drawn once, here, rather than per page, so a page change crossfades
        // the name in place instead of swapping it.
        val page = config.pages.getOrNull(current)
        PageName(
            name = nameOverrides.getOrNull(current)?.value ?: page?.deviceName,
            modifier = Modifier.align(Alignment.BottomCenter),
        )

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
        if (pinnedTop.isNotEmpty() && !hasFill) {
            FloatingTopPage(pinnedTop, middle, ctx, Modifier.weight(1f))
        } else {
            if (pinnedTop.isNotEmpty()) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AstrionTheme.pinnedTopBg)
                        .padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalArrangement = Arrangement.spacedBy(5.dp),
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
        // Footer slot for the device name (drawn by Dashboard): the same
        // height on every page, named or not.
        Spacer(Modifier.fillMaxWidth().height(PageNameHeight))
    }
}

/**
 * A scrolling page whose pinned-top cards float over the content instead of
 * sitting in their own band: the content starts just under them and scrolls
 * up behind them, fading out under the card's rounded bottom edge (the TV
 * page's Plex rows under the TV card). Pages with a "fill" card keep the
 * band, since their middle doesn't scroll.
 */
@Composable
private fun FloatingTopPage(
    pinnedTop: List<CardConfig>,
    middle: List<CardConfig>,
    ctx: CardContext,
    modifier: Modifier,
) {
    var pinnedPx by remember { mutableIntStateOf(0) }
    val pinnedDp = with(LocalDensity.current) { pinnedPx.toDp() }
    Box(modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(start = 10.dp, end = 10.dp, top = pinnedDp + 10.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            middle.forEach { RenderCard(it, ctx) }
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .onSizeChanged { pinnedPx = it.height }
                .background(
                    Brush.verticalGradient(
                        0f to AstrionTheme.pageBg,
                        0.82f to AstrionTheme.pageBg,
                        1f to AstrionTheme.pageBg.copy(alpha = 0f),
                    )
                )
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            pinnedTop.forEach { RenderCard(it, ctx) }
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
                    .background(Color(0xCC243140))
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
