package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.weatherLabel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Clock + weather header. Big local time (device clock), date, current
 * condition + temperature from a `weather.*` entity, and — below the current
 * info, full width — a multi-day forecast with a min→max temperature gradient
 * bar per day (styled like the HA clock-weather-card).
 *
 * Forecast data comes from the `weather.get_forecasts` service (modern HA
 * dropped the `forecast` attribute), falling back to the attribute if present.
 *
 * Config shape:
 *   { "type": "clock_weather", "options": {
 *       "entity_id": "weather.forecast_home",
 *       "time_format": 12,
 *       "forecast_rows": 4
 *   } }
 */
class ClockWeatherCard : CardRenderer {
    override val type = "clock_weather"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: "weather.forecast_home"
        val e = ctx.entities[entityId]
        val is24 = config.int("time_format", 12) == 24
        val forecastRows = config.int("forecast_rows", 4)
        val calendarEntity = config.string("calendar_entity")
        // Pages that carry a clock_header of their own don't need this card's
        // big clock repeated ~40dp below it, so the time can be dropped and
        // the card collapses to weather + diary only.
        val showTime = config.bool("show_time", true)
        // Dropping the clock is what makes this the Main-page variant, so the
        // same flag also tightens everything else: the card is competing with
        // the floorplan below it for a 582dp screen, and every dp it gives up
        // is a dp the floorplan grows into (it's the page's `pin: fill` card).
        val dense = !showTime
        // The date can live in the page header instead (see clock_header's
        // date_format), in which case it comes out of here entirely and the
        // condition moves up into the slot it vacated — which also empties the
        // second line of the temperature column, so the whole row loses a line.
        val showDate = config.bool("show_date", true)
        val bare = config.bool("bare", false)
        // Size of the faint condition glyph behind the card; 0 turns it off.
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

        // Fetch the forecast via the service; refresh every 30 min.
        var forecast by remember { mutableStateOf<List<Forecast>>(emptyList()) }
        LaunchedEffect(entityId) {
            while (true) {
                val arr = ctx.client.getForecast(entityId)
                val parsed = arr?.let { parseForecast(it) }.orEmpty()
                if (parsed.isNotEmpty()) forecast = parsed
                delay(30 * 60 * 1000L)
            }
        }

        val condition = e?.state ?: "unknown"
        val temp = e?.attrDouble("temperature")

        // Next diary entry from the calendar entity's own current/next event
        // attributes — no extra HTTP call — kept to one thin line.
        val todayEvent = calendarEntity?.let {
            nextCalendarEvent(ctx.entities[it], now, config.string("title_separator"))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                // `bare` drops the card entirely so the weather reads as part
                // of the page background, like the header above it.
                .then(
                    if (bare) Modifier
                    else Modifier.clip(RoundedCornerShape(18.dp)).background(Color(0xFF1B343D))
                ),
        ) {
            // Oversized, very faint condition glyph as a watermark behind the
            // card. Android 8.1 has no real blur, so softness comes from scale
            // and low alpha rather than a blur pass — same trick as the media
            // card's art background.
            if (watermark > 0) {
                Text(
                    emojiFor(condition),
                    fontSize = watermark.sp,
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .offset(x = (watermark / 4.4f).dp, y = (-6).dp)
                        .alpha(0.13f),
                )
            }

            if (dense) {
                DenseContent(
                    condition, temp, forecast, now, config.int("chip_days", 5).coerceIn(0, 6),
                    // Bare, the text lines up with the header above it rather
                    // than sitting inset inside an edge that isn't there.
                    padH = if (bare) 0.dp else 10.dp,
                    padV = if (bare) 8.dp else 9.dp,
                    calendarLine = todayEvent,
                )
            } else Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(if (dense) 8.dp else 12.dp),
                verticalArrangement = Arrangement.spacedBy(if (dense) 5.dp else 8.dp),
            ) {
            // Current info.
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emojiFor(condition), fontSize = if (dense) 26.sp else 34.sp)
                Spacer(Modifier.width(if (dense) 10.dp else 12.dp))
                Column(Modifier.weight(1f)) {
                    if (showTime) {
                        Text(
                            timeFmt.format(Date(now)),
                            color = Color(0xFFF2F5FA),
                            fontSize = 32.sp,
                            fontWeight = FontWeight.Light,
                        )
                    }
                    // Without the clock above it the date is the headline, so
                    // it steps up to primary weight rather than staying the
                    // caption of something that is no longer there.
                    if (showDate) {
                        Text(
                            dateFmt.format(Date(now)),
                            color = if (dense) Color(0xFFF2F5FA) else Color(0xFF9AB0C4),
                            fontSize = if (dense) 15.sp else 13.sp,
                            fontWeight = if (dense) FontWeight.Medium else FontWeight.Normal,
                        )
                    } else {
                        // Condition takes over as this column's headline.
                        Text(
                            weatherLabel(condition),
                            color = Color(0xFFF2F5FA),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        temp?.let { "${trim(it)}°" } ?: "—",
                        color = Color(0xFFF2F5FA),
                        fontSize = if (dense) 17.sp else 22.sp,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (showDate) {
                        Text(
                            weatherLabel(condition),
                            color = Color(0xFF9AB0C4),
                            // Was 11sp — about 15px tall at 220dpi, on the
                            // landing page, for the one line that says what the
                            // weather is.
                            fontSize = AstrionTheme.label,
                        )
                    }
                }
            }

            // Diary line, hard against the card's left edge rather than
            // indented into the condition column — it is about the day, not
            // about the weather, and at 12sp an extra 36dp of indent was a
            // sixth of the line's width.
            if (todayEvent != null) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                ) {
                    Icon(
                        Icons.Filled.Event,
                        contentDescription = "Next diary entry",
                        tint = AstrionTheme.accent,
                        modifier = Modifier.size(13.dp),
                    )
                    Text(
                        todayEvent,
                        color = AstrionTheme.accent,
                        fontSize = AstrionTheme.label,
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }

            // Full-width forecast rows.
            val shown = forecast.take(forecastRows)
            if (shown.isNotEmpty()) {
                val temps = shown.flatMap { listOfNotNull(it.low, it.high) }
                val weekMin = temps.minOrNull() ?: 0.0
                val weekMax = temps.maxOrNull() ?: 1.0
                val span = (weekMax - weekMin).coerceAtLeast(1.0)

                Column(verticalArrangement = Arrangement.spacedBy(if (dense) 3.dp else 4.dp)) {
                    shown.forEach { f -> ForecastRow(f, weekMin, span, dense) }
                }
            }
            } // content Column
        } // card Box
    }

    /**
     * Main-page layout: one strip, two balanced zones.
     *
     *   🌙 9° Clear      │  📅 Tue 8:15 HQ
     *         night     │  Wed  Thu  Fri  Sat  Sun
     *   7° ▬▬▬▬▬▬ 16°   │   ⛅   ⛅   ⛅   🌤   ☁
     *                   │  19/6 26/9 27/15 18/14 25/12
     *
     * Left is NOW: the one big number on the page, the condition in words,
     * and today's range as a bar on the same scale as the coming days — so
     * where it sits says whether today is a warm or cool one. Right is WHAT'S
     * NEXT: the next diary entry, then the coming days as columns.
     *
     * Temperatures are whole degrees throughout. A daily high/low to 0.1° is
     * false precision, and on a 9° the decimal is a third of the width.
     */
    @Composable
    private fun DenseContent(
        condition: String,
        temp: Double?,
        forecast: List<Forecast>,
        nowMs: Long,
        chipDays: Int,
        padH: androidx.compose.ui.unit.Dp,
        padV: androidx.compose.ui.unit.Dp,
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
                verticalArrangement = Arrangement.spacedBy(5.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(emojiFor(condition), fontSize = 23.sp)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        temp?.let { "${whole(it)}°" } ?: "—",
                        color = AstrionTheme.textPrimary,
                        fontSize = 30.sp,
                        fontWeight = FontWeight.Light,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(7.dp))
                    // Beside the number, not under it; two lines if it needs
                    // them ("Partly / cloudy") rather than truncating.
                    Text(
                        weatherLabel(condition),
                        color = AstrionTheme.textSecondary,
                        fontSize = 12.sp,
                        lineHeight = 14.sp,
                        maxLines = 2,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
                if (today != null) TodayBar(today, weekMin, span)
            }

            Box(
                Modifier
                    .padding(horizontal = 10.dp)
                    .width(1.dp)
                    .fillMaxHeight()
                    .background(AstrionTheme.controlBg),
            )

            Column(
                modifier = Modifier.weight(0.55f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (calendarLine != null) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Filled.Event,
                            contentDescription = "Next diary entry",
                            tint = AstrionTheme.accent,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(5.dp))
                        Text(
                            calendarLine,
                            color = AstrionTheme.accent,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    days.forEach { f ->
                        Column(
                            modifier = Modifier.weight(1f),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(2.dp),
                        ) {
                            Text(f.day, color = AstrionTheme.textSecondary, fontSize = 11.sp, maxLines = 1)
                            Text(emojiFor(f.condition), fontSize = 16.sp)
                            Text(
                                buildAnnotatedString {
                                    withStyle(SpanStyle(color = AstrionTheme.textPrimary, fontWeight = FontWeight.Medium)) {
                                        append(whole(f.high))
                                    }
                                    withStyle(SpanStyle(color = AstrionTheme.textMuted)) {
                                        append("/" + whole(f.low))
                                    }
                                },
                                fontSize = 12.sp,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
    }

    /** "7° ▬▬▬ 16°": today's low and high, the bar placed on the week's scale. */
    @Composable
    private fun TodayBar(f: Forecast, weekMin: Double, span: Double) {
        val lowFrac = (((f.low ?: weekMin) - weekMin) / span).toFloat().coerceIn(0f, 1f)
        val highFrac = (((f.high ?: (weekMin + span)) - weekMin) / span).toFloat().coerceIn(0f, 1f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${whole(f.low)}°", color = AstrionTheme.textSecondary, fontSize = 13.sp)
            Spacer(Modifier.width(5.dp))
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Color(0xFF2C3E4E)),
            ) {
                Row(Modifier.matchParentSize()) {
                    Spacer(Modifier.weight(lowFrac.coerceAtLeast(0.001f)))
                    Box(
                        modifier = Modifier
                            .weight((highFrac - lowFrac).coerceAtLeast(0.05f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(3.dp))
                            .background(
                                Brush.horizontalGradient(listOf(Color(0xFF3DD68C), Color(0xFF9BE7C4)))
                            ),
                    )
                    Spacer(Modifier.weight((1f - highFrac).coerceAtLeast(0.001f)))
                }
            }
            Spacer(Modifier.width(5.dp))
            Text("${whole(f.high)}°", color = AstrionTheme.textPrimary, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        }
    }

    private fun whole(d: Double?): String = d?.let { Math.round(it).toString() } ?: "–"

    @Composable
    private fun ForecastRow(f: Forecast, weekMin: Double, span: Double, dense: Boolean = false) {
        val rowText = if (dense) 12.sp else 13.sp
        val lowFrac = (((f.low ?: weekMin) - weekMin) / span).toFloat().coerceIn(0f, 1f)
        val highFrac = (((f.high ?: (weekMin + span)) - weekMin) / span).toFloat().coerceIn(0f, 1f)

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(f.day, color = Color(0xFF9AB0C4), fontSize = rowText, modifier = Modifier.width(40.dp))
            Text(emojiFor(f.condition), fontSize = if (dense) 16.sp else 18.sp, textAlign = TextAlign.Center,
                modifier = Modifier.width(30.dp))
            Text(
                f.low?.let { "${trim(it)}°" } ?: "",
                color = Color(0xFF9AB0C4), fontSize = rowText, textAlign = TextAlign.End,
                modifier = Modifier.width(40.dp),
            )
            Spacer(Modifier.width(8.dp))
            // Min→max gradient bar, positioned within the week's range via weights.
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(if (dense) 7.dp else 8.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF2C3E4E)),
            ) {
                Row(Modifier.matchParentSize()) {
                    Spacer(Modifier.weight(lowFrac.coerceAtLeast(0.001f)))
                    Box(
                        modifier = Modifier
                            .weight((highFrac - lowFrac).coerceAtLeast(0.03f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(4.dp))
                            .background(
                                Brush.horizontalGradient(
                                    listOf(Color(0xFF3DD68C), Color(0xFF9BE7C4)),
                                )
                            ),
                    )
                    Spacer(Modifier.weight((1f - highFrac).coerceAtLeast(0.001f)))
                }
            }
            Spacer(Modifier.width(8.dp))
            Text(
                f.high?.let { "${trim(it)}°" } ?: "",
                color = Color(0xFFF2F5FA), fontSize = rowText, fontWeight = FontWeight.Medium,
                textAlign = TextAlign.End, modifier = Modifier.width(40.dp),
            )
        }
    }

    private data class Forecast(
        val day: String,
        val condition: String,
        val low: Double?,
        val high: Double?,
        /** yyyy-MM-dd — how the compact layout finds today and tomorrow. */
        val date: String = "",
    )

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

    private fun emojiFor(condition: String): String = weatherEmoji(condition)
}

/**
 * Map HA weather condition strings to a simple emoji — no icon assets needed.
 * Top-level so the slim page header can show the same glyph as this card.
 */
internal fun weatherEmoji(condition: String): String = when (condition) {
    "sunny", "clear" -> "☀️"
    "clear-night" -> "🌙"
    "partlycloudy" -> "⛅"
    "cloudy" -> "☁️"
    "fog" -> "🌫️"
    "rainy", "pouring" -> "🌧️"
    "lightning", "lightning-rainy" -> "⛈️"
    "snowy", "snowy-rainy" -> "❄️"
    "windy", "windy-variant" -> "💨"
    "hail" -> "🌨️"
    else -> "🌡️"
}

/**
 * The calendar entity's `message`/`start_time`/`end_time` attributes
 * describe its next (or current) event — HA doesn't expose a full list
 * over the WebSocket state, only this one. Show it only if that event's
 * date is today, so a distant next event doesn't sit here for days.
 */
/**
 * The calendar entity's current-or-next event, formatted for one line.
 *
 * This used to return null unless the event fell on today's date, which
 * meant the line silently vanished most of the time — if the next thing in
 * the diary is tomorrow, you got nothing. Now it always renders when the
 * entity has an event, and says *when*: bare time for today, "Tomorrow"
 * plus the time, or a short weekday/date beyond that.
 */
/**
 * The next diary entry as "Tue 8:15 HQ": day, start time, location.
 *
 * The event's own `location` is the label, not its title — on this calendar
 * it carries a short site code (HQ, NTH, STH), which is what fits in the
 * header's ~23 characters. The title is the fallback for events that have no
 * location ("Bent Twigs"), trimmed at `title_separator` if one is set.
 *
 * The day is always named, today included: "Tue" reads the same way every
 * time, where "TMRW" only covered one of the seven days an entry can fall on.
 * Time drops AM/PM for the same width reason; an all-day entry has none.
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
    nowMs: Long,
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
    val allDay = (e.attr("all_day") as? kotlinx.serialization.json.JsonPrimitive)?.booleanOrNull ?: false
    val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
    val start = runCatching { fmt.parse(startStr) }.getOrNull() ?: return null

    val day = SimpleDateFormat("EEE", Locale.getDefault()).format(start)
    val time = if (allDay) null else SimpleDateFormat("h:mm", Locale.getDefault()).format(start)
    return CalendarLine(day, time, label)
}
