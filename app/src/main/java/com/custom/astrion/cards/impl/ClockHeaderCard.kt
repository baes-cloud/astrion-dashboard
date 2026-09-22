package com.custom.astrion.cards.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ui.AstrionTheme
import kotlinx.coroutines.delay
import kotlin.math.roundToInt
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * A slim page header: the page's name (or the date) on the left, with the time
 * trailing on the right,
 * at the section-label size used elsewhere ("Recently Played", "Playlists").
 * Pages other than Main have no clock otherwise, and the status bar is hidden
 * in kiosk mode.
 *
 * Reads the device clock, so it renders instantly and never waits on HA.
 *
 * `date_format` replaces the page name with the live date, for a page whose
 * name is already obvious from what's on it — Main uses it so the date has a
 * home up here instead of costing a line inside the weather card.
 *
 * `calendar_entity` fills the middle with the next diary entry, in the same
 * accent blue and glyph the standalone `calendar_line` card uses. Set it only
 * on the page that wants it; the slot collapses to nothing when it is absent
 * or the calendar has no upcoming event.
 *
 * Config: { "type": "clock_header",
 *           "options": { "title": "Plex", "time_format": 12,
 *                        "date_format": "EEE, d MMM",
 *                        "calendar_entity": "calendar.work",
 *                        "title_separator": " - ",
 *                        "weather_entity": "weather.home" } }
 */
class ClockHeaderCard : CardRenderer {
    override val type = "clock_header"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val is24 = config.int("time_format", 12) == 24
        val dateFormat = config.string("date_format")

        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                // Re-tick just after the minute rolls over rather than on a
                // fixed interval, so the displayed minute is never stale.
                delay(60_000 - (System.currentTimeMillis() % 60_000) + 250)
            }
        }
        val fmt = remember(is24) {
            SimpleDateFormat(if (is24) "HH:mm" else "h:mm a", Locale.getDefault())
        }
        val dateFmt = remember(dateFormat) {
            dateFormat?.let { runCatching { SimpleDateFormat(it, Locale.getDefault()) }.getOrNull() }
        }
        val heading = dateFmt?.format(Date(now)) ?: config.string("title").orEmpty()
        val event = config.string("calendar_entity")?.let {
            nextCalendarLine(ctx.entity(it), now, config.string("title_separator"))
        }

        // Three slots, with EQUAL weights on the two ends. That is what puts
        // the diary entry on the true centre line of the screen rather than
        // the midpoint of whatever the date and time leave over — the date is
        // the wider of the two, so a SpaceBetween row would push the middle
        // right by half the difference. Equal end weights also stop a long
        // event title from ever colliding with either end: it ellipsises
        // inside its own slot instead.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                HeaderText(heading)
            }
            Box(Modifier.weight(1.5f), contentAlignment = Alignment.Center) {
                if (event != null) {
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
                            event.plain(),
                            color = AstrionTheme.accent,
                            fontSize = AstrionTheme.label,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                HeaderText(fmt.format(Date(now)))
            }
        }
    }

    @Composable
    private fun HeaderText(text: String) {
        Text(
            text,
            color = AstrionTheme.textPrimary,
            fontSize = 13.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
