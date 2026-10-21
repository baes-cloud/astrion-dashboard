package com.custom.astrion.cards.impl

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.Glyphs
import com.custom.astrion.ui.HitTarget
import com.custom.astrion.ui.InWindowDialog
import com.custom.astrion.ui.tap
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.net.URLEncoder

/**
 * A row of Plex buttons under the TV card: Search plus one per library. Each
 * opens a full-page browser over the dashboard. Films (and music videos)
 * start on the TV with one tap; a show opens to its seasons and episodes.
 * Playback is PlexTarget's wake-TV + deep-link route, so it works with the
 * TV off. Same connection options as the `plex` card.
 *
 * Config:
 *   { "type": "plex_browse", "options": {
 *       "host": "http://10.0.0.10:32400", "adb_entity": …, "tv_entity": …,
 *       "buttons": [
 *         { "name": "Search", "kind": "search" },
 *         { "name": "Movies", "section": "1", "icon": "movie" },
 *         { "name": "TV",     "section": "2", "icon": "tv" },
 *         { "name": "MTV",    "section": "4", "icon": "music_video" }
 *       ]
 *   } }
 */
class PlexBrowseCard : CardRenderer {
    override val type = "plex_browse"

    @Suppress("UNCHECKED_CAST")
    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val target = remember(config) { PlexTarget.from(config) } ?: return
        val buttons = (config.options["buttons"] as? List<Map<String, Any?>>).orEmpty()
        val machineId by produceState<String?>(config.string("machine_id"), target.host) {
            if (value == null) value = target.machineId()
        }
        var open by remember { mutableStateOf<Map<String, Any?>?>(null) }
        // Inside the TV card (`flush`): smaller, glassy tiles over its artwork wash.
        val flush = config.bool("flush")

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(if (flush) 6.dp else 8.dp)) {
            buttons.forEach { b ->
                Column(
                    Modifier
                        .weight(1f)
                        .height(if (flush) 50.dp else 58.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (flush) AstrionTheme.colors.void.copy(alpha = 0.35f) else AstrionTheme.cardBgAlt)
                        .tap { open = b },
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Icon(buttonIcon(b), contentDescription = null, tint = AstrionTheme.accent, modifier = Modifier.size(if (flush) 19.dp else 22.dp))
                    Spacer(Modifier.height(3.dp))
                    Text(
                        b["name"] as? String ?: "",
                        color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.small,
                        fontWeight = FontWeight.SemiBold, maxLines = 1,
                    )
                }
            }
        }

        open?.let { b ->
            InWindowDialog(onDismissRequest = { open = null }) {
                PlexBrowser(ctx, target, machineId, b) { open = null }
            }
        }
    }
}

private fun buttonIcon(b: Map<String, Any?>): ImageVector = when {
    b["kind"] == "search" -> Glyphs.search
    b["icon"] == "tv" -> Glyphs.tv
    b["icon"] == "music_video" || b["icon"] == "mtv" -> Glyphs.mtv
    else -> Glyphs.film
}

/** A thing in a list: a film, show, season, episode or music video. */
private data class Entry(
    val key: String,
    val type: String,
    val title: String,
    val subtitle: String,
    val thumb: String?,
    val watched: Boolean,
    val resume: Boolean,
) {
    val playable get() = type == "movie" || type == "episode" || type == "clip"
    fun asItem() = PlexItem(key, type, title, subtitle, thumb, resume)
}

private fun JsonObject.s(k: String): String? = (this[k] as? JsonPrimitive)?.contentOrNull

private fun parseEntries(o: JsonObject?, metadataKey: String = "Metadata"): List<Entry> {
    val meta = (o?.get("MediaContainer") as? JsonObject)?.get(metadataKey) as? JsonArray ?: return emptyList()
    return meta.mapNotNull { (it as? JsonObject)?.let(::entry) }
}

private fun entry(m: JsonObject): Entry? {
    val key = m.s("ratingKey") ?: return null
    val type = m.s("type") ?: "video"
    val leaves = m.s("leafCount")?.toIntOrNull()
    val viewedLeaves = m.s("viewedLeafCount")?.toIntOrNull()
    val watched = when {
        leaves != null && viewedLeaves != null -> leaves > 0 && viewedLeaves >= leaves
        else -> (m.s("viewCount")?.toIntOrNull() ?: 0) > 0
    }
    val subtitle = when (type) {
        "show" -> listOfNotNull(m.s("year"), leaves?.let { "$it episode" + if (it == 1) "" else "s" }).joinToString(" · ")
        "season" -> leaves?.let { "$it episode" + if (it == 1) "" else "s" }.orEmpty()
        "episode" -> listOfNotNull(
            m.s("grandparentTitle"),
            listOfNotNull(m.s("parentIndex")?.let { "S$it" }, m.s("index")?.let { "E$it" }).joinToString("").ifBlank { null },
        ).joinToString(" · ")
        else -> listOfNotNull(m.s("year"), m.s("librarySectionTitle")?.takeIf { it != "Movies" }).joinToString(" · ")
    }
    return Entry(
        key = key, type = type,
        title = m.s("title") ?: "?",
        subtitle = subtitle,
        thumb = if (type == "episode") m.s("thumb") ?: m.s("grandparentThumb") else m.s("thumb"),
        watched = watched,
        resume = (m.s("viewOffset")?.toLongOrNull() ?: 0L) > 0L,
    )
}

private sealed interface Screen {
    data class Library(val section: String, val title: String) : Screen
    data class Show(val key: String, val title: String) : Screen
    data object Search : Screen
}

@Composable
private fun PlexBrowser(
    ctx: CardContext,
    target: PlexTarget,
    machineId: String?,
    button: Map<String, Any?>,
    onClose: () -> Unit,
) {
    val first: Screen = if (button["kind"] == "search") Screen.Search
    else Screen.Library(button["section"]?.toString() ?: "1", button["name"] as? String ?: "Library")
    // A small back stack: library → show, search → show.
    val stack = remember { mutableStateListOf(first) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    fun play(e: Entry) {
        scope.launch {
            val ok = target.play(ctx, machineId, e.asItem()) { status = it }
            delay(if (ok) 1200 else 4000)
            status = null
            if (ok) onClose()
        }
    }
    fun open(e: Entry) {
        when {
            e.playable -> play(e)
            e.type == "show" -> stack += Screen.Show(e.key, e.title)
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(22.dp))
            .background(AstrionTheme.pageBg)
            .padding(start = 14.dp, end = 12.dp, top = 12.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val screen = stack.last()
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (stack.size > 1) {
                RoundButton(Icons.AutoMirrored.Filled.ArrowBack, "Back") { stack.removeAt(stack.lastIndex) }
                Spacer(Modifier.width(10.dp))
            }
            if (screen !is Screen.Search) {
                Text(
                    when (screen) {
                        is Screen.Library -> screen.title
                        is Screen.Show -> screen.title
                        else -> ""
                    },
                    color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.displayXs,
                    fontFamily = AstrionTheme.headingFont, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Text(
                    "Search Plex", color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.displayXs,
                    fontFamily = AstrionTheme.headingFont, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                )
            }
            RoundButton(Icons.Filled.Close, "Close", onClose)
        }
        status?.let {
            Text(
                it, color = AstrionTheme.colors.onPrimary, fontSize = AstrionTheme.size.small, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(AstrionTheme.accentStrong)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        Box(Modifier.weight(1f)) {
            when (screen) {
                is Screen.Library -> LibraryGrid(target, screen.section, ::open)
                is Screen.Show -> ShowView(target, screen.key, ::open)
                Screen.Search -> SearchView(target, ::open)
            }
        }
    }
}

@Composable
private fun RoundButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    HitTarget(visual = 40.dp, onClick = onClick, face = Modifier.clip(CircleShape).background(AstrionTheme.controlBg)) {
        Icon(icon, contentDescription = description, tint = AstrionTheme.textOnControl, modifier = Modifier.size(20.dp))
    }
}

private enum class Sort(val label: String, val query: String) {
    TITLE("A–Z", "sort=titleSort"),
    ADDED("Recently added", "sort=addedAt:desc"),
    UNWATCHED("Unwatched", "unwatched=1&sort=titleSort"),
}

/** A whole library as a poster grid, fetched a page at a time as you scroll. */
@Composable
private fun LibraryGrid(target: PlexTarget, section: String, onOpen: (Entry) -> Unit) {
    var sort by remember { mutableStateOf(Sort.TITLE) }
    val entries = remember(sort) { mutableStateListOf<Entry>() }
    var total by remember(sort) { mutableStateOf<Int?>(null) }
    var error by remember(sort) { mutableStateOf<String?>(null) }
    var loading by remember(sort) { mutableStateOf(false) }
    val grid = rememberLazyGridState()
    val scope = rememberCoroutineScope()

    fun loadMore() {
        if (loading || (total != null && entries.size >= total!!)) return
        loading = true
        scope.launch {
            val got = PlexNet.get(target.url("/library/sections/$section/all?${sort.query}&X-Plex-Container-Start=${entries.size}&X-Plex-Container-Size=$PAGE"))
            val mc = got.json?.get("MediaContainer") as? JsonObject
            total = mc?.s("totalSize")?.toIntOrNull() ?: total ?: 0
            entries += parseEntries(got.json)
            error = got.error
            loading = false
        }
    }
    // Load first. Scrolling a grid that isn't on screen (the "Loading…" note
    // stands in for it until the first page arrives) waits forever, which
    // stalled every library on "Loading…".
    LaunchedEffect(sort) {
        loadMore()
        if (grid.layoutInfo.totalItemsCount > 0) grid.scrollToItem(0)
    }
    // Near the end of what's loaded: fetch the next page.
    val nearEnd by remember { derivedStateOf { (grid.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) > entries.size - 12 } }
    LaunchedEffect(nearEnd, entries.size) { if (nearEnd && entries.isNotEmpty()) loadMore() }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Chips(Sort.entries.map { it.label }, Sort.entries.indexOf(sort)) { sort = Sort.entries[it] }
        total?.takeIf { it > 0 }?.let {
            Text(
                if (it == 1) "1 TITLE" else "$it TITLES",
                color = AstrionTheme.colors.onSurfaceVariant, fontSize = AstrionTheme.size.label,
                fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                modifier = Modifier.padding(start = 2.dp, top = 2.dp),
            )
        }
        when {
            error != null && entries.isEmpty() -> Note("Can't reach Plex ($error).")
            total == 0 -> Note("Nothing here.")
            entries.isEmpty() -> Note("Loading…")
            else -> LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                state = grid,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                itemsIndexed(entries, key = { _, e -> e.key }) { _, e -> PosterCell(target, e) { onOpen(e) } }
            }
        }
    }
}

private const val PAGE = 60
private val SEARCH_THUMB_W = 72.dp

@Composable
private fun PosterCell(target: PlexTarget, e: Entry, onClick: () -> Unit) {
    Column(Modifier.tap(onClick = onClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f)) {
            Poster(target, e.thumb, Modifier.fillMaxSize(), 2f / 3f)
            if (e.watched) WatchedMark(Modifier.align(Alignment.TopEnd).padding(4.dp))
        }
        Spacer(Modifier.height(5.dp))
        Text(e.title, color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.label, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (e.subtitle.isNotBlank()) {
            Text(e.subtitle, color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun WatchedMark(modifier: Modifier) {
    Box(modifier.size(20.dp).clip(CircleShape).background(AstrionTheme.colors.void.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
        Icon(Icons.Filled.CheckCircle, contentDescription = "Watched", tint = AstrionTheme.accent, modifier = Modifier.size(16.dp))
    }
}

/** A poster or still, scaled by the server to what it's drawn at. */
@Composable
private fun Poster(target: PlexTarget, thumb: String?, modifier: Modifier, aspect: Float, widthDp: Dp = 110.dp) {
    val density = LocalDensity.current
    val w = with(density) { widthDp.roundToPx() }
    val url = thumb?.let { target.thumb(it, w, (w / aspect).toInt()) }
    var bmp by remember(url) { mutableStateOf<ImageBitmap?>(url?.let { com.custom.astrion.ui.ArtCache.peek(it) }) }
    LaunchedEffect(url) { if (bmp == null && url != null) PlexNet.poster(url)?.let { bmp = it } }
    val shaped = modifier.clip(RoundedCornerShape(8.dp))
    val b = bmp
    if (b != null) Image(b, contentDescription = null, modifier = shaped, contentScale = ContentScale.Crop)
    else Box(shaped.background(AstrionTheme.raised))
}

/** A show: its seasons as chips, the chosen season's episodes as rows. */
@Composable
private fun ShowView(target: PlexTarget, showKey: String, onOpen: (Entry) -> Unit) {
    val seasons by produceState<List<Entry>?>(null, showKey) {
        value = parseEntries(PlexNet.get(target.url("/library/metadata/$showKey/children")).json)
    }
    // Start on the first season with something unwatched.
    var picked by remember(seasons) {
        mutableIntStateOf(seasons?.indexOfFirst { !it.watched }?.takeIf { it >= 0 } ?: 0)
    }
    val season = seasons?.getOrNull(picked)
    val episodes by produceState<List<Entry>?>(null, season?.key) {
        value = null
        val key = season?.key ?: return@produceState
        value = parseEntries(PlexNet.get(target.url("/library/metadata/$key/children")).json)
    }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        when {
            seasons == null -> Note("Loading…")
            seasons!!.isEmpty() -> Note("No episodes.")
            else -> {
                if (seasons!!.size > 1) Chips(seasons!!.map { it.title }, picked) { picked = it }
                val eps = episodes
                if (eps == null) Note("Loading…") else LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(eps, key = { it.key }) { e -> EpisodeRow(target, e) { onOpen(e) } }
                }
            }
        }
    }
}

@Composable
private fun EpisodeRow(target: PlexTarget, e: Entry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(AstrionTheme.cardBgAlt).tap(onClick = onClick).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(112.dp).aspectRatio(16f / 9f)) {
            Poster(target, e.thumb, Modifier.fillMaxSize(), 16f / 9f, widthDp = 112.dp)
            if (e.watched) WatchedMark(Modifier.align(Alignment.TopEnd).padding(3.dp))
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                e.subtitle.substringAfterLast(" · "), color = AstrionTheme.colors.onSurfaceVariant,
                fontSize = AstrionTheme.size.label, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp,
            )
            Text(e.title, color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.body, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (e.resume) Text("Resume", color = AstrionTheme.accent, fontSize = AstrionTheme.size.label, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** Plex search: films, shows, episodes and music videos, as you type. */
@Composable
private fun SearchView(target: PlexTarget, onOpen: (Entry) -> Unit) {
    var query by remember { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Pair<String, List<Entry>>>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    LaunchedEffect(Unit) { focus.requestFocus() }
    LaunchedEffect(query) {
        val q = query.trim()
        if (q.length < 2) { results = null; return@LaunchedEffect }
        delay(450)
        val got = PlexNet.get(target.url("/hubs/search?query=${URLEncoder.encode(q, "UTF-8")}&limit=12"))
        error = got.error
        val hubs = (got.json?.get("MediaContainer") as? JsonObject)?.get("Hub") as? JsonArray
        // Film, show and episode hubs only (the music library has its own search).
        val groups = linkedMapOf<String, MutableList<Entry>>()
        hubs.orEmpty().forEach { h ->
            val hub = h as? JsonObject ?: return@forEach
            if (hub.s("type") !in setOf("movie", "show", "episode", "clip")) return@forEach
            (hub["Metadata"] as? JsonArray).orEmpty().forEach { m ->
                val o = m as? JsonObject ?: return@forEach
                val e = entry(o) ?: return@forEach
                // Music videos live in a movie-type library: group by library.
                val group = when (e.type) {
                    "show" -> "TV shows"
                    "episode" -> "Episodes"
                    else -> o.s("librarySectionTitle") ?: "Movies"
                }
                groups.getOrPut(group) { mutableListOf() } += e
            }
        }
        results = groups.toList()
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            Modifier.fillMaxWidth().height(46.dp).clip(RoundedCornerShape(23.dp)).background(AstrionTheme.colors.surfacePrimary).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Search, contentDescription = null, tint = AstrionTheme.accent, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Box(Modifier.weight(1f)) {
                if (query.isEmpty()) Text("Films, shows, episodes…", color = AstrionTheme.textMuted, fontSize = AstrionTheme.size.bodyLarge)
                BasicTextField(
                    value = query, onValueChange = { query = it }, singleLine = true,
                    textStyle = TextStyle(color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.bodyLarge),
                    cursorBrush = SolidColor(AstrionTheme.accent),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { keyboard?.hide() }),
                    modifier = Modifier.fillMaxWidth().focusRequester(focus),
                )
            }
            if (query.isNotEmpty()) {
                HitTarget(visual = 26.dp, onClick = { query = "" }) {
                    Icon(Icons.Filled.Close, contentDescription = "Clear", tint = AstrionTheme.textSecondary, modifier = Modifier.size(18.dp))
                }
            }
        }
        val r = results
        when {
            query.trim().length < 2 -> Note("Type a film, show or episode name.")
            r == null && error != null -> Note("Can't reach Plex ($error).")
            r == null -> Note("Searching…")
            r.isEmpty() -> Note("Nothing found for “${query.trim()}”.")
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                r.forEach { (group, list) ->
                    item(key = "h-$group") {
                        Text(
                            group.uppercase(), color = AstrionTheme.colors.onSurfaceVariant, fontSize = AstrionTheme.size.label,
                            fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp,
                            modifier = Modifier.padding(start = 4.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(list, key = { "$group-${it.key}" }) { e ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).tap { keyboard?.hide(); onOpen(e) }.padding(4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // One slot width for posters and stills, so every title starts in line.
                            val aspect = if (e.type == "episode") 16f / 9f else 2f / 3f
                            Box(Modifier.width(SEARCH_THUMB_W), contentAlignment = Alignment.Center) {
                                Box(Modifier.width(if (e.type == "episode") SEARCH_THUMB_W else 40.dp).aspectRatio(aspect)) {
                                    Poster(target, e.thumb, Modifier.fillMaxSize(), aspect, widthDp = SEARCH_THUMB_W)
                                }
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                Text(e.title, color = AstrionTheme.textPrimary, fontSize = AstrionTheme.size.body, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                if (e.subtitle.isNotBlank()) Text(e.subtitle, color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (e.watched) WatchedMark(Modifier)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Chips(labels: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        labels.forEachIndexed { i, label ->
            val on = i == selected
            Text(
                label,
                color = if (on) AstrionTheme.colors.onPrimary else AstrionTheme.textSecondary,
                fontSize = AstrionTheme.size.small, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(18.dp)).background(if (on) AstrionTheme.accentStrong else AstrionTheme.raised)
                    .tap { onSelect(i) }.padding(horizontal = 14.dp, vertical = 9.dp),
            )
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, color = AstrionTheme.textSecondary, fontSize = AstrionTheme.size.small, modifier = Modifier.padding(vertical = 14.dp, horizontal = 4.dp))
}
