package com.custom.astrion.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.BatteryStd
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Timer
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.impl.nextAlarmMs
import com.custom.astrion.cards.impl.weatherEmoji
import com.custom.astrion.ha.EntityMap
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * The screensaver: a black, dim, night-friendly face that takes over once the
 * remote has sat untouched for a while: `idle_seconds` in its dock, or
 * `undocked_idle_seconds` (dimmer, until the screen times out) off it.
 *
 * A big, thin, half-faded clock is the whole point of it; everything else only
 * appears when it is worth a glance from across the room:
 *  - what's playing (art, title, artist, a progress hairline), from whichever
 *    player is actually playing — preferred `media_entities` first, then any;
 *  - a running kitchen timer counting down;
 *  - the next alarm, if it goes off within `alarm_within_hours`;
 *  - the next diary entry, if it starts within `event_within_hours`;
 *  - `alerts` — "this entity is in this state" facts, e.g. the door unlocked;
 *  - HA unreachable, and the remote's own charge.
 *
 * At night (sun below the horizon, else outside 07:00–21:00) the palette goes
 * to a dimmed Baby Doll rose on a near-black face and the backlight drops to
 * `night_brightness`; MainActivity owns the backlight and the idle timer, this
 * only draws.
 *
 * BÆOREMOTE sits centred with its baseline at 92% of the height. It fades in
 * last (1.2 s ease-out), drops to half after five minutes, and drifts at most
 * 1% of the height every ten — the only motion besides the face's own drift.
 *
 * The whole block drifts a few dp every minute. The HA100 is an LCD so burn-in
 * isn't the worry it is on OLED, but a static clock on a panel that stays lit
 * all night still leaves image retention on cheap LCDs, and the drift is free.
 *
 * Config (`screensaver` in dashboard.json; every key optional):
 *   { "enabled": true, "trigger": "docked" | "always", "idle_seconds": 45,
 *     "undocked_idle_seconds": 90, "undocked_brightness": 0.08,
 *     "brightness": 0.2, "night_brightness": 0.03, "keep_screen_on": true,
 *     "keys_pass_through": true | false | ["VOLUME_UP", "VOLUME_DOWN", ...],
 *     "time_format": 12, "weather_entity": "weather.home",
 *     "media_entities": ["media_player.club"], "media_any": true,
 *     "calendar_entity": "calendar.work", "title_separator": " - ",
 *     "event_within_hours": 12,
 *     "alarm_entities": [...], "always_entities": [...],
 *     "enabled_entity": "...", "off_today_entity": "...", "alarm_within_hours": 12,
 *     "timers": true,
 *     "alerts": [ { "entity_id": "lock.front", "state": "unlocked",
 *                   "text": "Front door unlocked", "icon": "lock_open" } ] }
 */
@Composable
fun Screensaver(
    options: Map<String, Any?>,
    entities: () -> EntityMap,
    client: HaClient,
    connected: Boolean,
    batteryPct: Int?,
    charging: Boolean,
) {
    // The face redraws on the minute, not every second. It shows h:mm, and a
    // whole-face pass costs ~36ms of UI thread on this SoC: once a second
    // that was 86,400 redraws a night on the dock. Entities are sampled every
    // SAMPLE_MS rather than observed (several HA updates a second would
    // otherwise redraw it constantly), so a new track still shows promptly.
    // The only things that need seconds — a timer counting down, the track
    // position — tick in their own small scopes (Countdown, MediaProgress).
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var sampled by remember { mutableStateOf(entities()) }
    val currentOptions by rememberUpdatedState(options)
    val currentConnected by rememberUpdatedState(connected)
    LaunchedEffect(Unit) {
        // What the face last showed. HaClient hands out a new map on every
        // change anywhere in HA, so comparing maps by identity redrew the
        // face every sample; this compares only what the face draws.
        var shown = faceKey(sampled, currentOptions, now, currentConnected)
        while (true) {
            val t = System.currentTimeMillis()
            val latest = entities()
            if (t / 60_000 != now / 60_000) {
                // Redrawn for the clock anyway, so take the latest too.
                now = t
                sampled = latest
                shown = faceKey(latest, currentOptions, t, currentConnected)
            } else if (latest !== sampled) {
                val next = faceKey(latest, currentOptions, now, currentConnected)
                if (next != shown) {
                    shown = next
                    sampled = latest
                }
            }
            // Next sample, but never past the minute boundary.
            delay(minOf(SAMPLE_MS - t % SAMPLE_MS, 60_000 - t % 60_000) + 50)
        }
    }
    val entities = sampled

    val night = screensaverIsNight(entities, now)
    val clockInk = if (night) NightClock else DayClock
    val ink = if (night) NightInk else DayInk
    val faint = if (night) NightFaint else DayFaint
    val accent = if (night) NightAccent else DayAccent

    val is24 = (options["time_format"] as? Number)?.toInt() == 24
    val timeFmt = remember(is24) { SimpleDateFormat(if (is24) "HH:mm" else "h:mm", Locale.getDefault()) }
    val amPmFmt = remember { SimpleDateFormat("a", Locale.getDefault()) }
    val dateFmt = remember { SimpleDateFormat("EEEE d MMMM", Locale.getDefault()) }

    // Drift: a new spot each minute, from a small fixed set so it stays calm.
    val minute = now / 60_000
    val (dx, dy) = DRIFT[(minute % DRIFT.size).toInt()]

    val weather = pickWeather(entities, options)
    val media = pickPlayingMedia(entities, options)
    val facts = buildFacts(options, entities, now, connected)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(if (night) NightFaceBrush else SolidColor(Color.Black)),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset(x = dx.dp, y = dy.dp)
                // The bottom inset keeps the name's clear field below.
                .padding(start = 20.dp, end = 20.dp, top = 32.dp, bottom = 92.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.8f))

            // ---- clock -------------------------------------------------------
            Row {
                Text(
                    timeFmt.format(Date(now)),
                    // Faded through the colour, not Modifier.alpha: no extra
                    // layer. The rose night ink is already dim at full alpha.
                    color = if (night) clockInk else clockInk.copy(alpha = 0.62f),
                    fontFamily = AstrionTheme.bodyFont, fontSize = 81.sp,
                    fontWeight = FontWeight.Medium,
                    letterSpacing = (-1.5).sp,
                    lineHeight = 81.sp,
                    fontFeatureSettings = "tnum",
                    maxLines = 1,
                    modifier = Modifier.alignByBaseline(),
                )
                if (!is24) {
                    Text(
                        amPmFmt.format(Date(now)).lowercase(Locale.getDefault()),
                        color = faint,
                        fontFamily = AstrionTheme.bodyFont, fontSize = 19.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 6.dp).alignByBaseline(),
                    )
                }
            }
            Spacer(Modifier.height(13.dp))
            // Date · weather · the remote's own charge. The battery lives here,
            // not at the bottom, to keep the name's clear field empty.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dateFmt.format(Date(now)), color = ink, fontSize = 15.sp, maxLines = 1)
                if (weather != null && !weather.isUnavailable) {
                    val temp = weather.attrDouble("temperature")
                    Text("  ·  ", color = faint, fontSize = 15.sp)
                    Text(
                        weatherEmoji(weather.state),
                        fontSize = 15.sp,
                        // Emoji ignore the text colour; fade them instead so a
                        // bright sun doesn't glare out of a dark room.
                        modifier = Modifier.alpha(if (night) 0.45f else 0.7f),
                    )
                    if (temp != null) {
                        Spacer(Modifier.width(5.dp))
                        Text("${Math.round(temp)}°", color = ink, fontSize = 15.sp)
                    }
                }
                if (batteryPct != null) {
                    Text("  ·  ", color = faint, fontSize = 15.sp)
                    Icon(
                        if (charging) Icons.Filled.BatteryChargingFull else Icons.Filled.BatteryStd,
                        contentDescription = null,
                        tint = ink,
                        modifier = Modifier.size(14.dp),
                    )
                    Spacer(Modifier.width(2.dp))
                    Text("$batteryPct%", color = ink, fontSize = 15.sp, maxLines = 1)
                }
            }

            // ---- glanceable facts ---------------------------------------------
            if (facts.isNotEmpty()) {
                Spacer(Modifier.height(26.dp))
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                ) {
                    facts.forEach { f -> FactRow(f, if (f.warn) accent else ink, faint) }
                }
            }

            Spacer(Modifier.weight(1f))

            // ---- now playing --------------------------------------------------
            if (media != null) {
                NowPlaying(
                    media, client, night,
                    titleInk = if (night) clockInk else ink,
                    ink = ink, faint = faint, accent = accent,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }

        ScreensaverName(now, night)
    }
}

/**
 * BÆOREMOTE, centred, baseline at 92% of the height. Fades in last, after the
 * face (1.2 s ease-out, no slide), drops to half after [NAME_DIM_AFTER_MS],
 * and steps ≤1% of the height every ten minutes against burn-in. A single
 * animateFloatAsState: nothing runs between those steps.
 */
@Composable
private fun ScreensaverName(now: Long, night: Boolean) {
    val shownAt = remember { System.currentTimeMillis() }
    var arrived by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { arrived = true }
    val dimmed = now - shownAt >= NAME_DIM_AFTER_MS
    val full = if (night) 1f else 0.7f
    val nameAlpha by animateFloatAsState(
        targetValue = when {
            !arrived -> 0f
            dimmed -> full * 0.5f
            else -> full
        },
        animationSpec = tween(NAME_FADE_MS, easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)),
        label = "nameAlpha",
    )
    val dy = NAME_DRIFT[((now / 600_000) % NAME_DRIFT.size).toInt()]
    Layout(
        content = {
            Text(
                "BÆOREMOTE",
                // Tracking trails the last letter too; one step of start
                // padding (0.318em of 13sp) centres it optically.
                modifier = Modifier
                    .padding(start = 4.dp)
                    .graphicsLayer { alpha = nameAlpha },
                color = if (night) AstrionTheme.nightInk2 else DayName,
                fontFamily = AstrionTheme.headingFont,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                letterSpacing = 0.318.em,
                maxLines = 1,
            )
        },
        modifier = Modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val p = measurables.first().measure(constraints.copy(minWidth = 0, minHeight = 0))
        val baseline = p[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height
        layout(constraints.maxWidth, constraints.maxHeight) {
            p.place(
                (constraints.maxWidth - p.width) / 2,
                (constraints.maxHeight * 0.92f).roundToInt() - baseline + dy.dp.roundToPx(),
            )
        }
    }
}

// ---- palette -----------------------------------------------------------------
// Day: cool greys off the app's own palette. Night: a dimmed Baby Doll rose —
// blue-ish light is the one that keeps people awake, and warm reads softer in
// the dark. Clock and title in nightInk; date, artist, meta and progress in
// nightInk2.
private val DayClock = Color(0xFFDDE7E3)
private val DayInk = Color(0xFFA9BAB6)
private val DayFaint = Color(0xFF718583)
private val DayAccent = Color(0xFFE8B25A)
/** `device-ink` at 70% is applied by [ScreensaverName]'s alpha. */
private val DayName = Color(0xFFE9EDF2)
private val NightClock = AstrionTheme.nightInk
private val NightInk = AstrionTheme.nightInk2
private val NightFaint = AstrionTheme.nightInk2
private val NightAccent = AstrionTheme.nightInk
/** Night face: [AstrionTheme.nightFace] at the centre, darkening outwards. */
private val NightFaceBrush = Brush.radialGradient(listOf(AstrionTheme.nightFace, Color(0xFF050607)))
/** Night progress track. */
private val NightTrack = Color(0xFF1A1D21)

private const val NAME_FADE_MS = 1200
private const val NAME_DIM_AFTER_MS = 5 * 60_000L
/** Name offsets in dp, one per ten minutes: within 1% of the 582dp height. */
private val NAME_DRIFT = listOf(0, -4, 3, -2, 5, -5, 2, -3)

/** How often the face looks at HA (it redraws only if something changed). */
private const val SAMPLE_MS = 10_000L

/** Recomposes only itself, once a second, for as long as it's shown. */
@Composable
private fun rememberSecondTick(): Long {
    var t by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000 - System.currentTimeMillis() % 1000)
            t = System.currentTimeMillis()
        }
    }
    return t
}

/** Everything the face draws from HA, for deciding whether a sample changes it. */
private data class FaceKey(
    val night: Boolean,
    val weatherState: String?,
    val weatherTemp: Double?,
    val mediaId: String?,
    val mediaState: String?,
    val mediaAttributes: JsonObject?,
    val facts: List<Fact>,
)

private fun faceKey(entities: EntityMap, options: Map<String, Any?>, now: Long, connected: Boolean): FaceKey {
    val weather = pickWeather(entities, options)
    val media = pickPlayingMedia(entities, options)
    return FaceKey(
        night = screensaverIsNight(entities, now),
        weatherState = weather?.state,
        weatherTemp = weather?.attrDouble("temperature"),
        mediaId = media?.entityId,
        mediaState = media?.state,
        mediaAttributes = media?.attributes,
        facts = buildFacts(options, entities, now, connected),
    )
}

private fun pickWeather(entities: EntityMap, options: Map<String, Any?>): EntityState? =
    (options["weather_entity"] as? String)?.let { entities[it] }
        ?: entities.values.firstOrNull { it.domain == "weather" && !it.isUnavailable }

/** Per-minute (x, y) offsets in dp, cycled. */
private val DRIFT = listOf(
    0 to 0, 6 to -10, -5 to 8, 8 to 12, -8 to -6, 3 to 16, -4 to -14, 7 to 4,
)

/**
 * Night = the sun is below the horizon, when HA has `sun.sun`; otherwise a
 * plain 21:00–07:00 window. Also used by MainActivity to pick the backlight.
 */
fun screensaverIsNight(entities: EntityMap, nowMs: Long): Boolean {
    entities["sun.sun"]?.state?.let { s ->
        if (s == "below_horizon") return true
        if (s == "above_horizon") return false
    }
    val hour = Calendar.getInstance().apply { timeInMillis = nowMs }.get(Calendar.HOUR_OF_DAY)
    return hour >= 21 || hour < 7
}

// ---- facts ---------------------------------------------------------------------

/**
 * One line on the face. [countdownTo] (epoch ms) makes [detail] a live
 * countdown, ticking in its own scope so the rest of the face stays still.
 */
private data class Fact(
    val icon: ImageVector,
    val text: String,
    val detail: String? = null,
    val warn: Boolean = false,
    val countdownTo: Long? = null,
)

@Composable
private fun FactRow(f: Fact, tint: Color, faint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(f.icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            f.text, color = tint, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (f.countdownTo != null) {
            Spacer(Modifier.width(7.dp))
            val now = rememberSecondTick()
            Text(formatCountdown((f.countdownTo - now).coerceAtLeast(0)), color = faint, fontSize = 15.sp, maxLines = 1)
        } else if (f.detail != null) {
            Spacer(Modifier.width(7.dp))
            Text(f.detail, color = faint, fontSize = 15.sp, maxLines = 1)
        }
    }
}

private fun buildFacts(options: Map<String, Any?>, entities: EntityMap, now: Long, connected: Boolean): List<Fact> {
    val out = mutableListOf<Fact>()
    if (!connected) out += Fact(Icons.Filled.CloudOff, "Home Assistant offline", warn = true)

    // Alerts first: they are the things that might need doing something about.
    (options["alerts"] as? List<*>)?.forEach { raw ->
        val a = raw as? Map<*, *> ?: return@forEach
        val id = a["entity_id"] as? String ?: return@forEach
        val e = entities[id] ?: return@forEach
        val states = when (val s = a["state"]) {
            is String -> listOf(s)
            is List<*> -> s.filterIsInstance<String>()
            else -> listOf("on")
        }
        if (e.state !in states) return@forEach
        val text = ((a["text"] as? String) ?: "{name} {state}")
            .replace("{name}", e.friendlyName)
            .replace("{state}", e.state.humanise().lowercase(Locale.getDefault()))
        out += Fact(alertIcon(a["icon"] as? String), text, warn = a["warn"] as? Boolean ?: true)
    }

    // Timers: anything running or paused, counting down.
    if (options["timers"] as? Boolean != false) {
        entities.values
            .filter { it.domain == "timer" && (it.state == "active" || it.state == "paused") }
            .sortedBy { it.entityId }
            .forEach { t ->
                if (t.state == "active") {
                    val finishesAt = t.attrString("finishes_at")?.let { parseIsoMs(it) } ?: return@forEach
                    if (finishesAt < now) return@forEach
                    out += Fact(Icons.Filled.Timer, t.friendlyName, countdownTo = finishesAt)
                } else {
                    val remainingMs = t.attrString("remaining")?.let { parseHms(it) } ?: return@forEach
                    out += Fact(Icons.Filled.Pause, t.friendlyName, formatCountdown(remainingMs) + " paused")
                }
            }
    }

    // Next alarm, if it's soon enough to matter tonight.
    val alarmIds = (options["alarm_entities"] as? List<*>)?.filterIsInstance<String>().orEmpty()
    val alarmsOn = (options["enabled_entity"] as? String)?.let { entities[it]?.state != "off" } ?: true
    if (alarmIds.isNotEmpty() && alarmsOn) {
        val within = ((options["alarm_within_hours"] as? Number)?.toDouble() ?: 12.0) * 3_600_000
        nextAlarmMs(
            entities, now, alarmIds,
            offTodayEntity = options["off_today_entity"] as? String,
            alwaysEntities = (options["always_entities"] as? List<*>)?.filterIsInstance<String>().orEmpty().toSet(),
        )?.takeIf { it - now <= within }?.let { t ->
            out += Fact(Icons.Filled.Alarm, "Alarm " + clockLabel(t, now), "in " + formatUntil(t - now))
        }
    }

    // Next diary entry, if it's today-ish (or on now).
    (options["calendar_entity"] as? String)?.let { entities[it] }?.let { cal ->
        calendarFact(cal, options, now)?.let { out += it }
    }
    return out
}

private fun calendarFact(cal: EntityState, options: Map<String, Any?>, now: Long): Fact? {
    val sep = options["title_separator"] as? String
    val title = cal.attrString("message")
        ?.let { m -> sep?.let { m.substringBefore(it) } ?: m }
        ?.trim()?.takeIf { it.isNotEmpty() }
        ?: return null
    val place = cal.attrString("location")?.trim()?.takeIf { it.isNotEmpty() }
    val start = cal.attrString("start_time")?.let { Time.parseHaLocal(it) } ?: return null
    val end = cal.attrString("end_time")?.let { Time.parseHaLocal(it) }
    val allDay = (cal.attr("all_day") as? JsonPrimitive)?.booleanOrNull ?: false
    val within = ((options["event_within_hours"] as? Number)?.toDouble() ?: 12.0) * 3_600_000

    val label = listOfNotNull(title, place).joinToString(" · ")
    return when {
        end != null && end <= now -> null
        start <= now -> if (allDay) Fact(Icons.Filled.Event, label, "today") else Fact(Icons.Filled.Event, label, "now")
        start - now > within -> null
        allDay -> Fact(Icons.Filled.Event, label, dayLabel(start, now))
        else -> Fact(Icons.Filled.Event, label, clockLabel(start, now))
    }
}

private fun alertIcon(name: String?): ImageVector = when (name) {
    "lock_open", "lock", "door" -> Icons.Filled.LockOpen
    "vacuum" -> Icons.Filled.CleaningServices
    "timer" -> Icons.Filled.Timer
    "info" -> Icons.Filled.Info
    "music" -> Icons.Filled.MusicNote
    else -> Icons.Filled.Warning
}

// ---- now playing ---------------------------------------------------------------

/** "TV" / "TV Audio" is a Sonos saying it is carrying the TV, not a title. */
private val PLACEHOLDER_TITLES = setOf("TV", "TV Audio")

private fun EntityState.playingTitle(): String? =
    attrString("media_title")?.takeIf { it.isNotBlank() && it !in PLACEHOLDER_TITLES }

/**
 * The player worth showing: first playing entry of `media_entities`, then (if
 * `media_any` isn't false) any other media_player that's playing something
 * with a real title. A speaker carrying the TV has no title of its own, so it
 * is skipped and the TV/Plex entity that does have one wins instead.
 */
private fun pickPlayingMedia(entities: EntityMap, options: Map<String, Any?>): EntityState? {
    val preferred = (options["media_entities"] as? List<*>)?.filterIsInstance<String>().orEmpty()
    val any = options["media_any"] as? Boolean ?: true
    val pool = preferred.mapNotNull { entities[it] } +
        if (any) entities.values.filter { it.domain == "media_player" }.sortedBy { it.entityId } else emptyList()
    return pool.firstOrNull { it.state == "playing" && it.playingTitle() != null }
}

@Composable
private fun NowPlaying(
    e: EntityState,
    client: HaClient,
    night: Boolean,
    titleInk: Color,
    ink: Color,
    faint: Color,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    val title = e.playingTitle() ?: return
    val subtitle = (e.attrString("media_artist") ?: e.attrString("media_series_title") ?: e.attrString("app_name"))
        ?.takeIf { it.isNotBlank() }
    val album = e.attrString("media_album_name")?.takeIf { it.isNotBlank() && it != title }

    val artPath = e.attrString("entity_picture")
    // Shared with the media player card via ArtCache, so it's usually
    // already decoded; 76dp needs ~128px.
    var art by remember(artPath) { mutableStateOf(artPath?.let { ArtCache.peek(it, 128) }) }
    LaunchedEffect(artPath) {
        art = artPath?.let { p -> ArtCache.load(p, 128) { client.fetchBytes(p) } }
    }

    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val img = art
            if (img != null) {
                Image(
                    bitmap = img,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    // Art is the brightest thing on the screen by far, so it
                    // is faded hardest.
                    alpha = 0.55f,
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(7.dp)),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(7.dp))
                        .background(Color(0xFF12181E)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = faint, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = titleInk,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Normal,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Text(subtitle, color = faint, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(
                    listOfNotNull(album, e.friendlyName).joinToString(" · "),
                    color = faint.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }

        // Progress hairline, extrapolated from HA's last position report.
        val duration = e.attrDouble("media_duration")
        val position = e.attrDouble("media_position")
        if (duration != null && duration > 0 && position != null) {
            val reportedAt = e.attrString("media_position_updated_at")?.let { parseIsoMs(it) }
            MediaProgress(
                position, reportedAt, duration, faint,
                fill = if (night) faint else accent.copy(alpha = 0.7f),
                track = if (night) NightTrack else faint.copy(alpha = 0.35f),
            )
        }
    }
}

/** Position, hairline and length: the one part of the face that ticks each second. */
@Composable
private fun MediaProgress(position: Double, reportedAt: Long?, duration: Double, faint: Color, fill: Color, track: Color) {
    val now = rememberSecondTick()
    val pos = position + (reportedAt?.let { (now - it) / 1000.0 } ?: 0.0)
    val frac = (pos / duration).toFloat().coerceIn(0f, 1f)
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatMediaTime(pos), color = faint, fontSize = 11.sp, textAlign = TextAlign.End, modifier = Modifier.width(40.dp))
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 8.dp)
                .height(2.dp)
                .clip(CircleShape)
                .background(track),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(frac)
                    .height(2.dp)
                    .clip(CircleShape)
                    .background(fill),
            )
        }
        Text(formatMediaTime(duration), color = faint, fontSize = 11.sp, modifier = Modifier.width(40.dp))
    }
}

// ---- time helpers ----------------------------------------------------------------

internal fun parseIsoMs(iso: String): Long? =
    runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()

/** "0:05:00" → ms. */
private fun parseHms(s: String): Long? =
    s.split(":").mapNotNull { it.toLongOrNull() }.takeIf { it.size == 3 }
        ?.let { (h, m, sec) -> (h * 3600 + m * 60 + sec) * 1000 }

/** 1:04:09 or 4:09. */
private fun formatCountdown(ms: Long): String {
    val total = (ms + 999) / 1000
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
    else String.format(Locale.US, "%d:%02d", m, s)
}

internal fun formatMediaTime(seconds: Double): String {
    val t = seconds.toLong().coerceAtLeast(0)
    return if (t >= 3600) String.format(Locale.US, "%d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60)
    else String.format(Locale.US, "%d:%02d", t / 60, t % 60)
}

/** "7h 20m", "45m". */
private fun formatUntil(ms: Long): String {
    val mins = (ms / 60_000).coerceAtLeast(0)
    return if (mins >= 60) "${mins / 60}h ${mins % 60}m" else "${mins}m"
}

/** "7:05 am" today, "7:05 am tomorrow", else "7:05 am Tue". */
private fun clockLabel(t: Long, now: Long): String {
    val time = Time.format(t, "h:mm a").lowercase(Locale.getDefault())
    return when (val d = dayLabel(t, now)) {
        "today" -> time
        else -> "$time $d"
    }
}

private fun dayLabel(t: Long, now: Long): String {
    val today = Time.day(now)
    return when (Time.day(t)) {
        today -> "today"
        today.plusDays(1) -> "tomorrow"
        else -> Time.format(t, "EEE")
    }
}
