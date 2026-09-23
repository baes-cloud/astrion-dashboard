package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Dehaze
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Grain
import androidx.compose.material.icons.filled.NightsStay
import androidx.compose.material.icons.filled.Thermostat
import androidx.compose.material.icons.filled.Umbrella
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.WbCloudy
import androidx.compose.material.icons.filled.WbSunny
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.HaClient
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.weatherLabel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * Clock + weather. Big local time (device clock), date, current condition
 * and temperature from a `weather.*` entity, optional diary line, and a
 * multi-day forecast with min→max range bars.
 *
 * Weather glyphs are Material vector icons tinted from the theme (they were
 * colour emoji — bright 2017 Noto bitmaps in a dark room, and a text-layout
 * cost on every recomposition).
 *
 * The forecast comes from `weather.get_forecasts` and is kept in an
 * app-scope cache, so arriving at Main (every cold wake) shows it on the
 * first frame instead of popping it in and resizing the floorplan below.
 *
 * Config shape:
 *   { "type": "clock_weather", "options": {
 *       "entity_id": "weather.forecast_home", "time_format": 12,
 *       "forecast_rows": 4, "show_time": true, "show_date": true,
 *       "bare": false, "watermark_size": 150, "chip_days": 5,
 *       "calendar_entity": "calendar.work", "title_separator": " - "
 *   } }
 */
class ClockWeatherCard : CardRenderer {
    override val type = "clock_weather"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: "weather.forecast_home"
        val e = ctx.entity(entityId)
        val is24 = config.int("time_format", 12) == 24
        val forecastRows = config.int("forecast_rows", 4)
        val calendarEntity = config.string("calendar_entity")
        val showTime = config.bool("show_time", true)
        // Dropping the clock makes this the Main-page variant: one strip.
        val dense = !showTime
        val showDate = config.bool("show_date", true)
        val bare = config.bool("bare", false)
        val watermark = config.int("watermark_size", 150)

        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                delay(10_000)
            }
        }
        val timeFmt = remember(is24) {
            SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", Locale.getDefault())
        }
        val dateFmt = remember { SimpleDateFormat("EEE, d MMM", Locale.getDefault()) }

        val forecast = rememberForecast(ctx.client, entityId)

        val condition = e?.state ?: "unknown"
        val temp = e?.attrDouble("temperature")
        val todayEvent = calendarEntity?.let {
            nextCalendarEvent(ctx.entity(it), now, config.string("title_separator"))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (bare) Modifier
                    else Modifier.clip(RoundedCornerShape(Radius.card)).background(AstrionTheme.cardBg)
                ),
        ) {
            // Oversized, very faint condition glyph behind the card. Opaque
            // tint one step off the card colour — no alpha layer.
            if (watermark > 0 && !bare) {
                Icon(
                    weatherIcon(condition), contentDescription = null,
                    tint = AstrionTheme.raised,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = (watermark / 4.4f).dp, y = (-6).dp)
                        .size(watermark.dp),
                )
            }

            if (dense) {
                DenseContent(
                    condition, temp, forecast, now, config.int("chip_days", 5).coerceIn(0, 6),
                    padH = if (bare) 0.dp else Space.gutter,
                    padV = if (bare) Space.s else 9.dp,
                    calendarLine = todayEvent,
                )
            } else {
                FullContent(
                    condition = condition, temp = temp, forecast = forecast,
                    time = if (showTime) timeFmt.format(Date(now)) else null,
                    date = if (showDate) dateFmt.format(Date(now)) else null,
                    todayEvent = todayEvent, forecastRows = forecastRows,
                )
            }
        }
    }

    @Composable
    private fun FullContent(
        condition: String,
        temp: Double?,
        forecast: List<Forecast>,
        time: String?,
        date: String?,
        todayEvent: String?,
        forecastRows: Int,
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(Space.m),
            verticalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WeatherGlyph(condition, 34.dp)
                Spacer(Modifier.width(Space.m))
                Column(Modifier.weight(1f)) {
                    if (time != null) {
                        Text(time, style = AstrionType.display, color = AstrionTheme.textPrimary)
                    }
                    if (date != null) {
                        Text(date, style = AstrionType.body, color = AstrionTheme.textSecondary)
                    } else {
                        Text(weatherLabel(condition), style = AstrionType.bodyStrong, color = AstrionTheme.textPrimary)
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        temp?.let { "${trim(it)}°" } ?: "—",
                        style = AstrionType.headline, color = AstrionTheme.textPrimary,
                    )
                    if (date != null) {
                        Text(weatherLabel(condition), style = AstrionType.label, color = AstrionTheme.textSecondary)
                    }
                }
            }

            if (todayEvent != null) DiaryLine(todayEvent)

            val shown = forecast.take(forecastRows)
            if (shown.isNotEmpty()) {
                val temps = shown.flatMap { listOfNotNull(it.low, it.high) }
                val weekMin = temps.minOrNull() ?: 0.0
                val weekMax = temps.maxOrNull() ?: 1.0
                val span = (weekMax - weekMin).coerceAtLeast(1.0)
                Column(verticalArrangement = Arrangement.spacedBy(Space.xs)) {
                    shown.forEach { f -> ForecastRow(f, weekMin, span) }
                }
            }
        }
    }

    @Composable
    private fun DiaryLine(text: String) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                Icons.Filled.Event, contentDescription = "Next diary entry",
                tint = AstrionTheme.accent, modifier = Modifier.size(14.dp),
            )
            Spacer(Modifier.width(Space.xs))
            Text(
                text, style = AstrionType.label, color = AstrionTheme.accent,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }

    /**
     * Main-page layout: one strip, two zones. Left is NOW (glyph, the one
     * big number, the condition, today's range on the week's scale). Right
     * is NEXT (diary line, then the coming days as columns). Whole degrees.
     */
    @Composable
    private fun DenseContent(
        condition: String,
        temp: Double?,
        forecast: List<Forecast>,
        nowMs: Long,
        chipDays: Int,
        padH: Dp,
        padV: Dp,
        calendarLine: String?,
    ) {
        val isoDay = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
        val todayKey = isoDay.format(Date(nowMs))
        val today = forecast.firstOrNull { it.date == todayKey }
        val days = forecast.filter { it.date > todayKey }.take(chipDays)

        val temps = forecast.flatMap { listOfNotNull(it.low, it.high) }
        val weekMin = temps.minOrNull() ?: 0.0
        val span = ((temps.maxOrNull() ?: 1.0) - weekMin).coerceAtLeast(1.0)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(IntrinsicSize.Min)
                .padding(horizontal = padH, vertical = padV),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(
                modifier = Modifier.weight(0.45f),
                verticalArrangement = Arrangement.spacedBy(Space.xs),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    WeatherGlyph(condition, 24.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        temp?.let { "${whole(it)}°" } ?: "—",
                        style = AstrionType.display, color = AstrionTheme.textPrimary, maxLines = 1,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(
                        weatherLabel(condition),
                        style = AstrionType.label.copy(lineHeight = 14.sp),
                        color = AstrionTheme.textSecondary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (today != null) TodayBar(today, weekMin, span)
            }

            Box(
                Modifier
                    .padding(horizontal = Space.gutter)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(AstrionTheme.divider),
            )

            Column(
                modifier = Modifier.weight(0.55f),
                verticalArrangement = Arrangement.spacedBy(Space.s),
            ) {
                if (calendarLine != null) DiaryLine(calendarLine)
                Row(Modifier.fillMaxWidth()) {
                    days.forEach { f ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(Space.xxs),
                        ) {
                            Text(f.day, style = AstrionType.label, color = AstrionTheme.textSecondary, maxLines = 1)
                            WeatherGlyph(f.condition, 18.dp)
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = AstrionTheme.textPrimary, fontWeight = FontWeight.SemiBold)) {
                                        append(whole(f.high))
                                    }
                                    withStyle(SpanStyle(color = AstrionTheme.textSecondary)) {
                                        append("/" + whole(f.low))
                                    }
                                },
                                style = AstrionType.label,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }

    /** "7° ▬▬▬ 16°": today's low and high, placed on the week's scale. */
    @Composable
    private fun TodayBar(f: Forecast, weekMin: Double, span: Double) {
        val lowFrac = (((f.low ?: weekMin) - weekMin) / span).toFloat().coerceIn(0f, 1f)
        val highFrac = (((f.high ?: (weekMin + span)) - weekMin) / span).toFloat().coerceIn(0f, 1f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${whole(f.low)}°", style = AstrionType.label, color = AstrionTheme.textSecondary)
            Spacer(Modifier.width(5.dp))
            RangeBar(lowFrac, highFrac, 5.dp, Modifier.weight(1f))
            Spacer(Modifier.width(5.dp))
            Text("${whole(f.high)}°", style = AstrionType.label, color = AstrionTheme.textPrimary)
        }
    }

    @Composable
    private fun RangeBar(lowFrac: Float, highFrac: Float, height: Dp, modifier: Modifier) {
        Box(
            modifier = modifier
                .height(height)
                .clip(RoundedCornerShape(height / 2))
                .background(AstrionTheme.trackBg),
        ) {
            Row(Modifier.fillMaxWidth().fillMaxHeight()) {
                Spacer(Modifier.weight(lowFrac.coerceAtLeast(0.001f)))
                Box(
                    modifier = Modifier
                        .weight((highFrac - lowFrac).coerceAtLeast(0.04f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(height / 2))
                        .background(Brush.horizontalGradient(listOf(AstrionTheme.rangeLow, AstrionTheme.rangeHigh))),
                )
                Spacer(Modifier.weight((1f - highFrac).coerceAtLeast(0.001f)))
            }
        }
    }

    private fun whole(d: Double?): String = d?.let { Math.round(it).toString() } ?: "–"

    @Composable
    private fun ForecastRow(f: Forecast, weekMin: Double, span: Double) {
        val lowFrac = (((f.low ?: weekMin) - weekMin) / span).toFloat().coerceIn(0f, 1f)
        val highFrac = (((f.high ?: (weekMin + span)) - weekMin) / span).toFloat().coerceIn(0f, 1f)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(f.day, style = AstrionType.label, color = AstrionTheme.textSecondary, modifier = Modifier.width(40.dp))
            Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) { WeatherGlyph(f.condition, 18.dp) }
            Text(
                f.low?.let { "${trim(it)}°" } ?: "",
                style = AstrionType.label, color = AstrionTheme.textSecondary, textAlign = TextAlign.End,
                modifier = Modifier.width(40.dp),
            )
            Spacer(Modifier.width(Space.s))
            RangeBar(lowFrac, highFrac, 8.dp, Modifier.weight(1f))
            Spacer(Modifier.width(Space.s))
            Text(
                f.high?.let { "${trim(it)}°" } ?: "",
                style = AstrionType.label, color = AstrionTheme.textPrimary,
                textAlign = TextAlign.End, modifier = Modifier.width(40.dp),
            )
        }
    }

    internal data class Forecast(
        val day: String,
        val condition: String,
        val low: Double?,
        val high: Double?,
        /** yyyy-MM-dd — how the compact layout finds today and tomorrow. */
        val date: String = "",
    )

    /** App-scope forecast cache: entity id → (forecast, fetched-at ms). */
    private object ForecastCache {
        val entries = ConcurrentHashMap<String, Pair<List<Forecast>, Long>>()
    }

    /** Cached forecast immediately; refetch when older than 30 min. */
    @Composable
    private fun rememberForecast(client: HaClient, entityId: String): List<Forecast> {
        var forecast by remember(entityId) {
            mutableStateOf(ForecastCache.entries[entityId]?.first ?: emptyList())
        }
        LaunchedEffect(entityId) {
            while (true) {
                val cached = ForecastCache.entries[entityId]
                val age = cached?.let { System.currentTimeMillis() - it.second } ?: Long.MAX_VALUE
                if (age < REFRESH_MS) {
                    delay(REFRESH_MS - age)
                    continue
                }
                val parsed = client.getForecast(entityId)?.let { parseForecast(it) }.orEmpty()
                if (parsed.isNotEmpty()) {
                    ForecastCache.entries[entityId] = parsed to System.currentTimeMillis()
                    forecast = parsed
                    delay(REFRESH_MS)
                } else {
                    // Not connected yet / HA busy: try again soon, keep the old one.
                    delay(30_000)
                }
            }
        }
        return forecast
    }

    private fun parseForecast(arr: JsonArray): List<Forecast> {
        val dayFmt = SimpleDateFormat("EEE", Locale.getDefault())
        val parser = SimpleDateFormat("yyyy-MM-dd'T'HH:mm", Locale.getDefault())
        val isoDay = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        return arr.mapNotNull { el ->
            val o = el as? JsonObject ?: return@mapNotNull null
            fun str(k: String) = (o[k] as? JsonPrimitive)?.content
            fun dbl(k: String) = (o[k] as? JsonPrimitive)?.content?.toDoubleOrNull()
            val dt = str("datetime")
            val parsed = dt?.let { runCatching { parser.parse(it.take(16)) }.getOrNull() }
            val day = parsed?.let { dayFmt.format(it) } ?: ""
            val date = parsed?.let { isoDay.format(it) } ?: ""
            Forecast(day, str("condition") ?: "unknown", dbl("templow"), dbl("temperature"), date)
        }
    }

    private fun trim(d: Double): String =
        if (d == d.toLong().toDouble()) d.toLong().toString() else String.format(Locale.US, "%.1f", d)

    private companion object {
        const val REFRESH_MS = 30 * 60 * 1000L
    }
}

/** A weather condition as a tinted vector glyph. */
@Composable
internal fun WeatherGlyph(condition: String, size: Dp) {
    Icon(
        weatherIcon(condition),
        contentDescription = weatherLabel(condition),
        tint = weatherTint(condition),
        modifier = Modifier.size(size),
    )
}

internal fun weatherIcon(condition: String): ImageVector = when (condition) {
    "sunny", "clear" -> Icons.Filled.WbSunny
    "clear-night" -> Icons.Filled.NightsStay
    "partlycloudy" -> Icons.Filled.WbCloudy
    "cloudy" -> Icons.Filled.Cloud
    "fog" -> Icons.Filled.Dehaze
    "rainy", "pouring" -> Icons.Filled.Umbrella
    "lightning", "lightning-rainy" -> Icons.Filled.FlashOn
    "snowy", "snowy-rainy" -> Icons.Filled.AcUnit
    "windy", "windy-variant" -> Icons.Filled.Air
    "hail" -> Icons.Filled.Grain
    "exceptional" -> Icons.Filled.Warning
    else -> Icons.Filled.Thermostat
}

internal fun weatherTint(condition: String): Color = when (condition) {
    "sunny", "clear", "lightning", "lightning-rainy", "exceptional" -> AstrionTheme.on
    "clear-night", "rainy", "pouring", "hail" -> AstrionTheme.accent
    "snowy", "snowy-rainy" -> AstrionTheme.textPrimary
    else -> AstrionTheme.textSecondary
}

/**
 * The next diary entry as "Tue 8:15am HQ": day, start time, location.
 *
 * The event's own `location` is the label (a short site code on this
 * calendar); the title is the fallback, trimmed at `title_separator`. The
 * day is always named; the time carries am/pm so it can't be misread (the
 * header shows "8:25 PM" right beside it). An all-day entry has no time.
 */
internal data class CalendarLine(val day: String, val time: String?, val label: String) {
    fun plain(): String = listOfNotNull(day, time, label).joinToString(" ")
}

internal fun nextCalendarEvent(
    e: com.custom.astrion.ha.EntityState?,
    nowMs: Long,
    titleSeparator: String?,
): String? = nextCalendarLine(e, nowMs, titleSeparator)?.plain()

internal fun nextCalendarLine(
    e: com.custom.astrion.ha.EntityState?,
    @Suppress("UNUSED_PARAMETER") nowMs: Long,
    titleSeparator: String?,
): CalendarLine? {
    e ?: return null
    val title = e.attrString("message")
        ?.let { m -> titleSeparator?.let { m.substringBefore(it) } ?: m }
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
    val label = e.attrString("location")?.trim()?.takeIf { it.isNotEmpty() }
        ?: title
        ?: return null
    val startStr = e.attrString("start_time") ?: return null
    val allDay = (e.attr("all_day") as? JsonPrimitive)?.booleanOrNull ?: false
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val start = runCatching { fmt.parse(startStr) }.getOrNull() ?: return null

    val day = SimpleDateFormat("EEE", Locale.getDefault()).format(start)
    val time = if (allDay) null else shortTime(start)
    return CalendarLine(day, time, label)
}

/** "7:05am" — compact, unambiguous, same form for events and alarms. */
internal fun shortTime(d: Date): String =
    SimpleDateFormat("h:mma", Locale.US).format(d).lowercase(Locale.US)
