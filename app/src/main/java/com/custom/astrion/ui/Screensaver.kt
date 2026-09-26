package com.custom.astrion.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.BatteryChargingFull
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.impl.nextAlarmMs
import com.custom.astrion.cards.impl.weatherEmoji
import com.custom.astrion.ha.EntityMap
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.HaClient
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * The docked screensaver: a black, dim, night-friendly face that takes over
 * once the remote has sat in its dock untouched for a while.
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
 * warm and dimmer and the backlight drops to `night_brightness`; MainActivity
 * owns the backlight and the idle timer, this only draws.
 *
 * The whole block drifts a few dp every minute. The HA100 is an LCD so burn-in
 * isn't the worry it is on OLED, but a static clock on a panel that stays lit
 * all night still leaves image retention on cheap LCDs, and the drift is free.
 *
 * Config (`screensaver` in dashboard.json; every key optional):
 *   { "enabled": true, "trigger": "docked" | "always", "idle_seconds": 45,
 *     "brightness": 0.2, "night_brightness": 0.03, "keep_screen_on": true,
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
    entities: EntityMap,
    client: HaClient,
    connected: Boolean,
    batteryPct: Int?,
    charging: Boolean,
) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis()
            // Land on the next whole second so the timer and clock tick together.
            delay(1000 - now % 1000)
        }
    }

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

    val weather = (options["weather_entity"] as? String)?.let { entities[it] }
        ?: entities.values.firstOrNull { it.domain == "weather" && !it.isUnavailable }
    val media = pickPlayingMedia(entities, options)
    val facts = buildFacts(options, entities, now, connected)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .offset(x = dx.dp, y = dy.dp)
                .padding(horizontal = 20.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.weight(0.8f))

            // ---- clock -------------------------------------------------------
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    timeFmt.format(Date(now)),
                    color = clockInk,
                    // ~260dp wide for "12:45" on a ~349dp-wide panel.
                    fontSize = 96.sp,
                    fontWeight = FontWeight.Thin,
                    lineHeight = 96.sp,
                    maxLines = 1,
                    modifier = Modifier.alpha(if (night) 0.55f else 0.62f),
                )
                if (!is24) {
                    Text(
                        amPmFmt.format(Date(now)).lowercase(Locale.getDefault()),
                        color = faint,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Light,
                        modifier = Modifier.padding(start = 4.dp, bottom = 18.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dateFmt.format(Date(now)), color = ink, fontSize = 16.sp, fontWeight = FontWeight.Light, maxLines = 1)
                if (weather != null && !weather.isUnavailable) {
                    val temp = weather.attrDouble("temperature")
                    Text("  ·  ", color = faint, fontSize = 16.sp)
                    Text(
                        weatherEmoji(weather.state),
                        fontSize = 16.sp,
                        // Emoji ignore the text colour; fade them instead so a
                        // bright sun doesn't glare out of a dark room.
                        modifier = Modifier.alpha(if (night) 0.45f else 0.7f),
                    )
                    if (temp != null) {
                        Spacer(Modifier.width(5.dp))
                        Text("${Math.round(temp)}°", color = ink, fontSize = 16.sp, fontWeight = FontWeight.Light)
                    }
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
                NowPlaying(media, client, now, night, ink, faint, accent)
                Spacer(Modifier.height(18.dp))
            }

            // ---- footer: the remote's own charge -----------------------------
            if (batteryPct != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.BatteryChargingFull,
                        contentDescription = null,
                        tint = faint,
                        modifier = Modifier.size(13.dp),
                    )
                    Spacer(Modifier.width(3.dp))
                    Text(
                        when {
                            batteryPct >= 100 -> "Charged"
                            charging -> "Charging $batteryPct%"
                            else -> "$batteryPct%"
                        },
                        color = faint,
                        fontSize = 12.sp,
                    )
                }
            }
        }
    }
}

// ---- palette -----------------------------------------------------------------
// Day: cool greys off the app's own palette. Night: warm, dim amber — blue-ish
// light is the one that keeps people awake, and it reads softer in the dark.
private val DayClock = Color(0xFFDCE8EC)
private val DayInk = Color(0xFF9DB2BA)
private val DayFaint = Color(0xFF5E7680)
private val DayAccent = Color(0xFFE8B25A)
private val NightClock = Color(0xFFD9A06A)
private val NightInk = Color(0xFF9C7556)
private val NightFaint = Color(0xFF5E4634)
private val NightAccent = Color(0xFFD9824A)

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

private data class Fact(val icon: ImageVector, val text: String, val detail: String? = null, val warn: Boolean = false)

@Composable
private fun FactRow(f: Fact, tint: Color, faint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(f.icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        Spacer(Modifier.width(7.dp))
        Text(
            f.text, color = tint, fontSize = 16.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = false),
        )
        if (f.detail != null) {
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
                val remainingMs = if (t.state == "active") {
                    t.attrString("finishes_at")?.let { parseIsoMs(it) }?.let { it - now }
                } else {
                    t.attrString("remaining")?.let { parseHms(it) }
                } ?: return@forEach
                if (remainingMs < 0) return@forEach
                out += Fact(
                    if (t.state == "paused") Icons.Filled.Pause else Icons.Filled.Timer,
                    t.friendlyName,
                    formatCountdown(remainingMs) + if (t.state == "paused") " paused" else "",
                )
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
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val start = cal.attrString("start_time")?.let { runCatching { fmt.parse(it) }.getOrNull() }?.time ?: return null
    val end = cal.attrString("end_time")?.let { runCatching { fmt.parse(it) }.getOrNull() }?.time
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
    now: Long,
    night: Boolean,
    ink: Color,
    faint: Color,
    accent: Color,
) {
    val title = e.playingTitle() ?: return
    val subtitle = (e.attrString("media_artist") ?: e.attrString("media_series_title") ?: e.attrString("app_name"))
        ?.takeIf { it.isNotBlank() }
    val album = e.attrString("media_album_name")?.takeIf { it.isNotBlank() && it != title }

    val artPath = e.attrString("entity_picture")
    var art by remember(artPath) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(artPath) { art = artPath?.let { client.fetchBitmap(it) } }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
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
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(10.dp))
                        // Art is the brightest thing on the screen by far, so it
                        // is faded hardest — more so at night.
                        .alpha(if (night) 0.35f else 0.6f),
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(76.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(Color(0xFF0E1417)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.MusicNote, contentDescription = null, tint = faint, modifier = Modifier.size(28.dp))
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    color = ink,
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
                    color = faint,
                    fontSize = 12.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(0.8f),
                )
            }
        }

        // Progress hairline, extrapolated from HA's last position report.
        val duration = e.attrDouble("media_duration")
        val position = e.attrDouble("media_position")
        if (duration != null && duration > 0 && position != null) {
            val reportedAt = e.attrString("media_position_updated_at")?.let { parseIsoMs(it) }
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
                        .background(faint.copy(alpha = 0.35f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(frac)
                            .height(2.dp)
                            .background(accent.copy(alpha = 0.7f)),
                    )
                }
                Text(formatMediaTime(duration), color = faint, fontSize = 11.sp, modifier = Modifier.width(40.dp))
            }
        }
    }
}

// ---- time helpers ----------------------------------------------------------------

private fun parseIsoMs(iso: String): Long? =
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

private fun formatMediaTime(seconds: Double): String {
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
    val time = SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(t)).lowercase(Locale.getDefault())
    return when (val d = dayLabel(t, now)) {
        "today" -> time
        else -> "$time $d"
    }
}

private fun dayLabel(t: Long, now: Long): String {
    val key = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    val tomorrow = Calendar.getInstance().apply { timeInMillis = now; add(Calendar.DAY_OF_YEAR, 1) }.timeInMillis
    return when (key.format(Date(t))) {
        key.format(Date(now)) -> "today"
        key.format(Date(tomorrow)) -> "tomorrow"
        else -> SimpleDateFormat("EEE", Locale.getDefault()).format(Date(t))
    }
}
