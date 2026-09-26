package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.filled.Alarm
import com.custom.astrion.ui.tap
import com.custom.astrion.ha.ServiceCall
import androidx.compose.foundation.layout.Box
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Pause
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.MusicNote
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ui.AstrionTheme
import kotlinx.coroutines.delay

/**
 * The two one-line cards on Main: the next diary entry, and what's playing.
 *
 * Both are deliberately text only. They replace things that used to be bigger
 * — the diary line was buried inside the weather card, and now-playing was a
 * 54dp transport row — on a page whose whole job is to give the floorplan as
 * much of the screen as it can get. Play/pause is still on the hardware OK
 * button (held), so dropping the on-screen transport loses no function.
 */

/** One slim row: glyph, then a single line of text that ellipsises. */
@Composable
private fun InfoLine(
    icon: ImageVector,
    text: String,
    tint: Color,
    description: String,
    flush: Boolean = false,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (flush) Modifier
                else Modifier.clip(RoundedCornerShape(14.dp)).background(AstrionTheme.cardBgAlt)
            )
            // Tighter when joined into a stack: it is the card's footer there,
            // and those 4dp go to the weather card's larger forecast text.
            .padding(horizontal = 12.dp, vertical = if (flush) 5.dp else 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(14.dp))
        Text(
            text,
            color = tint,
            fontSize = AstrionTheme.label,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * The next (or current) diary entry as its own card.
 *
 * Config shape:
 *   { "type": "calendar_line", "options": {
 *       "entity_id": "calendar.work",
 *       "title_separator": " - "     // trims a venue off the event title
 *   } }
 */
class CalendarLineCard : CardRenderer {
    override val type = "calendar_line"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return

        // The label is relative ("Tomorrow 6:15 PM"), so it has to re-evaluate
        // as the day turns, not just when the entity changes.
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(60_000)
                now = System.currentTimeMillis()
            }
        }

        val line = nextCalendarEvent(ctx.entity(entityId), now, config.string("title_separator"))
            ?: return
        InfoLine(Icons.Filled.Event, line, AstrionTheme.accent, "Next diary entry", config.bool("flush"))
    }
}

/**
 * What's playing, on one line.
 *
 * `tv_entities` exists for the same reason it does on the media card: when the
 * speakers are carrying the TV feed their own metadata is literally "TV", so
 * the title is borrowed from whichever TV entity actually has one.
 *
 * Config shape:
 *   { "type": "now_playing", "options": {
 *       "entity_id": "media_player.club",
 *       "tv_entities": ["media_player.plex_...", "media_player.the_club_tv"],
 *       "prefix": "Now playing"
 *   } }
 */
class NowPlayingLineCard : CardRenderer {
    override val type = "now_playing"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val prefix = config.string("prefix") ?: "Now playing"
        val e = ctx.entity(entityId)

        val onTvSource = e?.attrString("source") == "TV" ||
            e?.attrString("media_title") in listOf("TV", "TV Audio")
        val tv = if (!onTvSource) null else config.stringList("tv_entities")
            .mapNotNull { ctx.entity(it) }
            .filterNot { it.isUnavailable }
            .let { candidates ->
                candidates.firstOrNull { !it.attrString("media_title").isNullOrBlank() }
                    ?: candidates.firstOrNull { it.state == "playing" }
            }

        val title = (tv ?: e)?.attrString("media_title")?.takeIf { it.isNotBlank() }
        val artist = if (tv != null) {
            tv.attrString("media_series_title") ?: tv.attrString("app_name")
        } else {
            e?.attrString("media_artist") ?: e?.attrString("media_series_title")
        }?.takeIf { it.isNotBlank() }

        // "TV" / "TV Audio" is the speaker's placeholder for "I am carrying
        // the television's audio", not a thing that is playing. If no TV
        // entity could supply a real title, say nothing is on rather than
        // reporting the placeholder as the track.
        val placeholder = tv == null && title in listOf("TV", "TV Audio")
        val idle = e == null || e.isUnavailable || title == null || placeholder
        val text = if (idle) {
            "$prefix: nothing"
        } else {
            "$prefix: " + listOfNotNull(title, artist).joinToString(" · ")
        }
        val tint = if (idle) AstrionTheme.textMuted else AstrionTheme.textSecondary
        if (!config.bool("controls")) {
            InfoLine(Icons.Filled.MusicNote, text, tint, "Now playing", config.bool("flush"))
            return
        }

        // Thin mini-player: the whole strip is ONE target — tap anywhere on it
        // to mute or unmute. The speaker icon on the right shows the state
        // rather than being a separate, smaller button to aim for.
        //
        // Sonos mute is per speaker, so muting only the Club would leave
        // anything grouped with it still playing. The tap sets the Club and
        // every current group member to the SAME state — the opposite of the
        // Club's — so they can't end up half muted.
        val live = e != null && !e.isUnavailable && ctx.connected
        val muted = e?.attrString("is_volume_muted") == "true"
        val targets = (listOf(entityId) + (e?.attrStringList("group_members") ?: emptyList())).distinct()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (config.bool("flush")) Modifier
                    else Modifier.clip(RoundedCornerShape(14.dp)).background(AstrionTheme.cardBgAlt)
                )
                .tap(enabled = live) {
                    targets.forEach { id ->
                        ctx.client.callService(
                            ServiceCall.of("media_player", "volume_mute", id, "is_volume_muted" to !muted)
                        )
                    }
                }
                .padding(start = 12.dp, end = 5.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Text(
                text,
                color = tint,
                fontSize = AstrionTheme.label,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MuteBadge(muted)
        }
    }

    /** 28dp round state badge: speaker on, or struck through in red when muted. */
    @Composable
    private fun MuteBadge(muted: Boolean) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(if (muted) AstrionTheme.dangerBg else AstrionTheme.controlBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                contentDescription = if (muted) "Muted — tap to unmute" else "Tap to mute",
                tint = if (muted) AstrionTheme.danger else AstrionTheme.textOnControl,
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

/**
 * One row, two facts: the next diary entry on the left, the next alarm on the
 * right.
 *
 *   📅 Next event: Tue 8:15 HQ          ⏰ Next alarm: 7:05 Tue
 *
 * The alarm is the earliest one still to come across `alarm_entities`
 * (timestamp sensors). It honours the same switches Home Assistant does:
 * everything is "off" while `enabled_entity` is off, and while
 * `off_today_entity` is on, today's alarms are skipped — except those listed
 * in `always_entities` (the WFH alarm, which "stop for today" never cancels).
 *
 * Config shape:
 *   { "type": "next_up", "options": {
 *       "calendar_entity": "calendar.work", "title_separator": " - ",
 *       "alarm_entities": ["sensor.work_alarm_1", "sensor.work_alarm_2", "sensor.work_alarm_wfh"],
 *       "always_entities": ["sensor.work_alarm_wfh"],
 *       "enabled_entity": "input_boolean.work_alarms_enabled",
 *       "off_today_entity": "input_boolean.work_alarms_off_today"
 *   } }
 */
class NextUpCard : CardRenderer {
    override val type = "next_up"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(30_000)
                now = System.currentTimeMillis()
            }
        }

        val event = config.string("calendar_entity")?.let {
            nextCalendarEvent(ctx.entity(it), now, config.string("title_separator"))
        }
        val alarm = nextAlarm(config, ctx, now)
        if (event == null && alarm == null) return

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The event owns everything left of the alarm and ellipsises if it
            // must; the alarm hugs the right edge, a fixed gap between them.
            Box(Modifier.weight(1f)) {
                if (event != null) {
                    Fact(Icons.Filled.Event, "Next event:", event, AstrionTheme.accent, Modifier)
                }
            }
            if (alarm != null) {
                Spacer(Modifier.width(12.dp))
                Fact(Icons.Filled.Alarm, "Next alarm:", alarm, AstrionTheme.on, Modifier)
            }
        }
    }

    @Composable
    private fun Fact(icon: ImageVector, label: String, value: String, tint: Color, modifier: Modifier) {
        Row(modifier, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = AstrionTheme.textSecondary)) { append("$label ") }
                    withStyle(SpanStyle(color = tint, fontWeight = FontWeight.Medium)) { append(value) }
                },
                fontSize = 12.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    /** "7:05 Tue", "off", or null when no alarm is configured or due. */
    private fun nextAlarm(config: CardConfig, ctx: CardContext, nowMs: Long): String? {
        val ids = config.stringList("alarm_entities")
        if (ids.isEmpty()) return null
        config.string("enabled_entity")?.let { if (ctx.entity(it)?.state == "off") return "off" }
        val next = nextAlarmMs(
            ctx.entities, nowMs, ids,
            offTodayEntity = config.string("off_today_entity"),
            alwaysEntities = config.stringList("always_entities").toSet(),
        ) ?: return null

        val d = java.util.Date(next)
        return java.text.SimpleDateFormat("h:mm", java.util.Locale.getDefault()).format(d) + " " +
            java.text.SimpleDateFormat("EEE", java.util.Locale.getDefault()).format(d)
    }
}

/**
 * Epoch ms of the earliest alarm still to come across [ids] (timestamp
 * sensors), or null. While [offTodayEntity] is on, today's alarms are skipped
 * except those in [alwaysEntities]. Shared by `next_up` and the screensaver;
 * the `enabled_entity` switch is the caller's to check, since `next_up` says
 * "off" where the screensaver just says nothing.
 */
internal fun nextAlarmMs(
    entities: com.custom.astrion.ha.EntityMap,
    nowMs: Long,
    ids: List<String>,
    offTodayEntity: String?,
    alwaysEntities: Set<String>,
): Long? {
    val offToday = offTodayEntity?.let { entities[it]?.state == "on" } == true
    val dayKey = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    val today = dayKey.format(java.util.Date(nowMs))
    return ids.mapNotNull { id ->
        val iso = entities[id]?.state ?: return@mapNotNull null
        val t = runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
            ?: return@mapNotNull null
        if (t <= nowMs) return@mapNotNull null
        if (offToday && id !in alwaysEntities && dayKey.format(java.util.Date(t)) == today) return@mapNotNull null
        t
    }.minOrNull()
}
