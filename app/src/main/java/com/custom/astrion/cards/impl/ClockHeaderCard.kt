package com.custom.astrion.cards.impl

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.ExpandMore
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.LocalNav
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.Touch
import com.custom.astrion.ui.tap
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The page header: page name (or the live date) on the left, the next diary
 * entry in the middle, the time on the right.
 *
 * The left half is THE touch navigation: tap it (it shows a chevron) for the
 * page picker, which also leads to the map of the physical buttons. It is
 * 44dp tall and spans half the screen, in the band every page starts with.
 *
 * Reads the device clock, so it renders instantly and never waits on HA.
 *
 * Config: { "type": "clock_header",
 *           "options": { "title": "Plex", "time_format": 12,
 *                        "date_format": "EEE, d MMM",
 *                        "calendar_entity": "calendar.work",
 *                        "title_separator": " - " } }
 */
class ClockHeaderCard : CardRenderer {
    override val type = "clock_header"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val is24 = config.int("time_format", 12) == 24
        val dateFormat = config.string("date_format")
        val nav = LocalNav.current

        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                now = System.currentTimeMillis()
                // Re-tick just after the minute rolls over.
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

        Row(
            modifier = Modifier.fillMaxWidth().heightIn(min = Touch.compact),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Equal end weights put the diary entry on the true centre line.
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                Row(
                    modifier = Modifier
                        .heightIn(min = Touch.compact)
                        .clip(RoundedCornerShape(Radius.control))
                        .tap(enabled = nav != null, onClickLabel = "Change page") { nav?.openPicker() },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        heading, style = AstrionType.header, color = AstrionTheme.textPrimary,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                    if (nav != null) {
                        Icon(
                            Icons.Filled.ExpandMore, contentDescription = null,
                            tint = AstrionTheme.textSecondary, modifier = Modifier.size(20.dp),
                        )
                    }
                }
            }
            Box(Modifier.weight(1.5f), contentAlignment = Alignment.Center) {
                if (event != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Space.xs),
                    ) {
                        Icon(
                            Icons.Filled.Event,
                            contentDescription = "Next diary entry",
                            tint = AstrionTheme.accent,
                            modifier = Modifier.size(14.dp),
                        )
                        Text(
                            event.plain(),
                            style = AstrionType.label,
                            color = AstrionTheme.accent,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                Text(
                    fmt.format(Date(now)), style = AstrionType.header, color = AstrionTheme.textPrimary,
                    maxLines = 1,
                )
            }
        }
    }
}
