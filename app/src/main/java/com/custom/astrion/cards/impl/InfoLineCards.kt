package com.custom.astrion.cards.impl

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AlarmOff
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.custom.astrion.cards.CardConfig
import com.custom.astrion.cards.CardContext
import com.custom.astrion.cards.CardRenderer
import com.custom.astrion.ha.EntityState
import com.custom.astrion.ha.ServiceCall
import com.custom.astrion.ui.AstrionTheme
import com.custom.astrion.ui.AstrionType
import com.custom.astrion.ui.PendingSpinner
import com.custom.astrion.ui.Radius
import com.custom.astrion.ui.Space
import com.custom.astrion.ui.Touch
import com.custom.astrion.ui.liveOrDim
import com.custom.astrion.ui.rememberAction
import com.custom.astrion.ui.rememberOptimistic
import com.custom.astrion.ui.tap
import kotlinx.coroutines.delay
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The one-line cards on Main: the next diary entry, what's playing, and
 * next-event / next-alarm.
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
                else Modifier.clip(RoundedCornerShape(Radius.control)).background(AstrionTheme.cardBg)
            )
            .padding(horizontal = Space.m, vertical = if (flush) 5.dp else Space.s),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Space.s),
    ) {
        Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(16.dp))
        Text(text, style = AstrionType.label, color = tint, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The next (or current) diary entry as its own card.
 *
 * Config: { "type": "calendar_line", "options": {
 *     "entity_id": "calendar.work", "title_separator": " - ", "flush": false } }
 */
class CalendarLineCard : CardRenderer {
    override val type = "calendar_line"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
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
 * What's playing, on one line — with the play state visible (a paused track
 * used to read "Now playing: …" exactly like a playing one).
 *
 * `tv_entities`: when the speakers carry the TV feed their own metadata is
 * literally "TV", so the title is borrowed from whichever TV entity has one.
 *
 * `controls: true` makes the whole strip ONE 48dp target that mutes or
 * unmutes the speaker AND every speaker grouped with it, to the same state.
 * The mute badge updates optimistically, spins if HA is slow, and outlines
 * red if the call fails.
 *
 * Config: { "type": "now_playing", "options": {
 *     "entity_id": "media_player.club",
 *     "tv_entities": ["media_player.plex_…", "media_player.the_club_tv"],
 *     "prefix": "Now playing", "controls": true, "flush": false } }
 */
class NowPlayingLineCard : CardRenderer {
    override val type = "now_playing"

    @Composable
    override fun Render(config: CardConfig, ctx: CardContext) {
        val entityId = config.string("entity_id") ?: return
        val prefix = config.string("prefix") ?: "Now playing"
        val e = ctx.entity(entityId)

        val tv = borrowTvEntity(e, config.stringList("tv_entities")) { ctx.entity(it) }
        val title = (tv ?: e)?.attrString("media_title")?.takeIf { it.isNotBlank() }
        val artist = if (tv != null) {
            tv.attrString("media_series_title") ?: tv.attrString("app_name")
        } else {
            e?.attrString("media_artist") ?: e?.attrString("media_series_title")
        }?.takeIf { it.isNotBlank() }

        val placeholder = tv == null && title in listOf("TV", "TV Audio")
        val unavailable = e == null || e.isUnavailable
        val idle = unavailable || title == null || placeholder
        val paused = e?.state == "paused"
        val lead = when {
            unavailable -> "Speaker unavailable"
            idle -> "$prefix: nothing"
            paused -> "Paused: "
            else -> "$prefix: "
        }
        val text = if (idle) lead else lead + listOfNotNull(title, artist).joinToString(" · ")
        val tint = when {
            unavailable -> AstrionTheme.unavailable
            idle -> AstrionTheme.textSecondary
            paused -> AstrionTheme.textSecondary
            else -> AstrionTheme.textPrimary
        }
        val stateIcon = when {
            idle -> Icons.Filled.MusicNote
            paused -> Icons.Filled.Pause
            else -> Icons.Filled.GraphicEq
        }
        val stateTint = when {
            unavailable -> AstrionTheme.unavailable
            idle || paused -> AstrionTheme.textSecondary
            else -> AstrionTheme.good
        }

        if (!config.bool("controls")) {
            InfoLine(stateIcon, text, stateTint, if (paused) "Paused" else "Now playing", config.bool("flush"))
            return
        }

        val live = !unavailable && ctx.connected
        val actualMuted = e?.attrString("is_volume_muted") == "true"
        val mute = rememberOptimistic(actualMuted)
        val muted = mute.show(actualMuted)
        val action = rememberAction(ctx)
        val targets = (listOf(entityId) + (e?.attrStringList("group_members") ?: emptyList())).distinct()

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = Touch.min)
                .then(
                    if (config.bool("flush")) Modifier
                    else Modifier.clip(RoundedCornerShape(Radius.control)).background(AstrionTheme.cardBg)
                )
                .tap(enabled = live, onClickLabel = if (muted) "Unmute speakers" else "Mute speakers") {
                    val want = !muted
                    mute.set(want)
                    action.run(
                        *targets.map { id ->
                            ServiceCall.of("media_player", "volume_mute", id, "is_volume_muted" to want)
                        }.toTypedArray(),
                        onFail = { mute.clear() },
                    )
                }
                .padding(start = Space.m, end = Space.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Space.s),
        ) {
            Icon(stateIcon, contentDescription = null, tint = stateTint, modifier = Modifier.size(16.dp))
            Text(
                text,
                style = AstrionType.label,
                color = tint,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            MuteBadge(muted, live, action.busy, action.failed)
        }
    }

    /** 36dp round state badge: speaker on, or struck through in red when muted. */
    @Composable
    private fun MuteBadge(muted: Boolean, live: Boolean, busy: Boolean, failed: Boolean) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .liveOrDim(live)
                .clip(CircleShape)
                .background(if (muted) AstrionTheme.dangerBg else AstrionTheme.controlBg)
                .then(if (failed) Modifier.border(2.dp, AstrionTheme.danger, CircleShape) else Modifier)
                .semantics { contentDescription = if (muted) "Muted" else "Sound on" },
            contentAlignment = Alignment.Center,
        ) {
            if (busy) {
                PendingSpinner(size = 16.dp, color = AstrionTheme.textOnControl)
            } else {
                Icon(
                    if (muted) Icons.Filled.VolumeOff else Icons.Filled.VolumeUp,
                    contentDescription = null,
                    tint = if (muted) AstrionTheme.danger else AstrionTheme.textOnControl,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
    }
}

/**
 * When a speaker is carrying the TV ("TV" / "TV Audio"), the entity from
 * [candidates] that actually describes what's on. Shared by the media cards.
 */
internal fun borrowTvEntity(
    e: EntityState?,
    candidates: List<String>,
    lookup: (String) -> EntityState?,
): EntityState? {
    val onTvSource = e?.attrString("source") == "TV" ||
        e?.attrString("media_title") in listOf("TV", "TV Audio")
    if (!onTvSource) return null
    val live = candidates.mapNotNull(lookup).filterNot { it.isUnavailable }
    return live.firstOrNull { !it.attrString("media_title").isNullOrBlank() }
        ?: live.firstOrNull { it.state == "playing" }
}

/**
 * Two facts on one row: the next diary entry, and the next alarm.
 *
 *   📅 Next event: Tue 8:15am HQ          ⏰ Next alarm: Tue 7:05am
 *
 * The alarm is the earliest one still to come across `alarm_entities`. It
 * honours HA's switches: "off" while `enabled_entity` is off; while
 * `off_today_entity` is on, today's alarms are skipped except those in
 * `always_entities`. Both facts use the same "Day h:mma" form.
 *
 * Config: { "type": "next_up", "options": {
 *     "calendar_entity": "calendar.work", "title_separator": " - ",
 *     "alarm_entities": [...], "always_entities": [...],
 *     "enabled_entity": "input_boolean.…", "off_today_entity": "input_boolean.…" } }
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
        val dayKey = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
        val dayName = remember { SimpleDateFormat("EEE", Locale.getDefault()) }

        val event = config.string("calendar_entity")?.let {
            nextCalendarEvent(ctx.entity(it), now, config.string("title_separator"))
        }
        val alarm = nextAlarm(config, ctx, now, dayKey, dayName)
        if (event == null && alarm == null) return

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.weight(1f)) {
                if (event != null) {
                    Fact(Icons.Filled.Event, "Next event:", event, AstrionTheme.accent)
                }
            }
            if (alarm != null) {
                Spacer(Modifier.width(Space.m))
                val off = alarm == "off"
                Fact(
                    if (off) Icons.Filled.AlarmOff else Icons.Filled.Alarm,
                    "Next alarm:", alarm,
                    if (off) AstrionTheme.textSecondary else AstrionTheme.on,
                )
            }
        }
    }

    @Composable
    private fun Fact(icon: ImageVector, label: String, value: String, tint: Color) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(Space.xs))
            Text(
                buildAnnotatedString {
                    withStyle(SpanStyle(color = AstrionTheme.textSecondary)) { append("$label ") }
                    withStyle(SpanStyle(color = tint)) { append(value) }
                },
                style = AstrionType.label,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    /** "Tue 7:05am", "off", or null when no alarm is configured or due. */
    private fun nextAlarm(
        config: CardConfig,
        ctx: CardContext,
        nowMs: Long,
        dayKey: SimpleDateFormat,
        dayName: SimpleDateFormat,
    ): String? {
        val ids = config.stringList("alarm_entities")
        if (ids.isEmpty()) return null
        config.string("enabled_entity")?.let { if (ctx.entity(it)?.state == "off") return "off" }
        val offToday = config.string("off_today_entity")?.let { ctx.entity(it)?.state == "on" } == true
        val always = config.stringList("always_entities").toSet()

        val today = dayKey.format(Date(nowMs))
        val next = ids.mapNotNull { id ->
            val iso = ctx.entity(id)?.state ?: return@mapNotNull null
            val t = runCatching { java.time.OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrNull()
                ?: return@mapNotNull null
            if (t <= nowMs) return@mapNotNull null
            if (offToday && id !in always && dayKey.format(Date(t)) == today) return@mapNotNull null
            t
        }.minOrNull() ?: return null

        val d = Date(next)
        return dayName.format(d) + " " + shortTime(d)
    }
}
