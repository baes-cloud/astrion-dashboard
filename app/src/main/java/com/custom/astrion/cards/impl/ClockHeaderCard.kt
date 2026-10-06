package com.custom.astrion.cards.impl

import android.content.BroadcastReceiver
import com.custom.astrion.ui.LocalMinuteClock
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.foundation.Canvas
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
 * A slim page header laid out like a phone's status bar: the page's name (or
 * the date) on the left, the time centred, and the battery level on the right,
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
 * `calendar_entity` shows the next diary entry in place of the name or date,
 * in the same accent blue and glyph the standalone `calendar_line` card uses.
 * Set it only on the page that wants it; the name or date comes back when it
 * is absent or the calendar has no upcoming event.
 *
 * `battery` (default true) shows the remote's own charge: an outline filled
 * to the level, a bolt while charging, and the percentage.
 *
 * Config: { "type": "clock_header",
 *           "options": { "title": "Plex", "time_format": 12,
 *                        "date_format": "EEE, d MMM", "battery": true,
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

        // Ticks just after the minute rolls over, so the shown minute is never stale.
        val now = LocalMinuteClock.current
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

        // Three slots, with EQUAL weights on the two ends, like a phone's
        // status bar: that is what puts the clock on the true centre line of
        // the screen rather than the midpoint of whatever the date and the
        // battery leave over. Equal end weights also stop a long date or
        // diary entry from colliding with the clock: it ellipsises inside its
        // own slot instead.
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
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
                } else {
                    HeaderText(heading)
                }
            }
            Box(contentAlignment = Alignment.Center) {
                HeaderText(fmt.format(Date(now)))
            }
            Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                if (config.bool("battery", true)) BatteryIndicator()
            }
        }
    }

    /**
     * Phone-style battery: an outline filled to the charge level, a bolt while
     * charging, and the percentage beside it. Fed by the sticky
     * ACTION_BATTERY_CHANGED broadcast, which only fires when the level or
     * plug state changes, so it costs nothing while idle.
     */
    @Composable
    private fun BatteryIndicator() {
        val context = LocalContext.current
        var pct by remember { mutableIntStateOf(-1) }
        var charging by remember { mutableStateOf(false) }
        DisposableEffect(context) {
            fun read(intent: Intent?) {
                intent ?: return
                val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
                if (level >= 0 && scale > 0) pct = (level * 100f / scale).roundToInt()
                val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            }
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(c: Context, intent: Intent) = read(intent)
            }
            read(context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED)))
            onDispose { runCatching { context.unregisterReceiver(receiver) } }
        }
        if (pct < 0) return

        val fill = when {
            charging -> AstrionTheme.good
            pct <= 15 -> AstrionTheme.danger
            else -> AstrionTheme.textPrimary
        }
        val outline = AstrionTheme.textSecondary
        val bolt = AstrionTheme.pinnedTopBg
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(5.dp),
        ) {
            HeaderText("$pct%")
            Canvas(
                Modifier
                    .size(width = 22.dp, height = 11.dp)
                    .semantics { contentDescription = if (charging) "Battery $pct%, charging" else "Battery $pct%" },
            ) {
                val stroke = 1.2.dp.toPx()
                val nubW = 2.dp.toPx()
                val bodyW = size.width - nubW
                val r = CornerRadius(2.5.dp.toPx())
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(stroke / 2, stroke / 2),
                    size = Size(bodyW - stroke, size.height - stroke),
                    cornerRadius = r,
                    style = Stroke(stroke),
                )
                drawRoundRect(
                    color = outline,
                    topLeft = Offset(bodyW, size.height * 0.3f),
                    size = Size(nubW, size.height * 0.4f),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
                val inset = stroke + 1.dp.toPx()
                val innerW = bodyW - inset * 2
                drawRoundRect(
                    color = fill,
                    topLeft = Offset(inset, inset),
                    size = Size(innerW * pct.coerceIn(0, 100) / 100f, size.height - inset * 2),
                    cornerRadius = CornerRadius(1.dp.toPx()),
                )
                if (charging) {
                    val cx = bodyW / 2
                    val h = size.height
                    val path = Path().apply {
                        moveTo(cx + h * 0.10f, h * 0.08f)
                        lineTo(cx - h * 0.30f, h * 0.56f)
                        lineTo(cx - h * 0.02f, h * 0.56f)
                        lineTo(cx - h * 0.10f, h * 0.92f)
                        lineTo(cx + h * 0.30f, h * 0.44f)
                        lineTo(cx + h * 0.02f, h * 0.44f)
                        close()
                    }
                    drawPath(path, color = bolt)
                }
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
