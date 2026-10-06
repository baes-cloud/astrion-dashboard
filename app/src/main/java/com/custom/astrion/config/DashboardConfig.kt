package com.custom.astrion.config

import com.custom.astrion.cards.CardConfig

/**
 * What's compiled in. The real layout is device/config/dashboard.json, which
 * the build bundles as an asset: DashboardLoader writes it out to
 * /sdcard/astrion/dashboard.json when that file doesn't exist yet, and falls
 * back to it when the file can't be read. So the layout lives in one place.
 *
 * Only two things stay here: [fallback], for when even the bundled layout
 * won't parse, and [screensaverDefaults], which every config's `screensaver`
 * block is merged over.
 */
object DashboardConfig {

    private const val WEATHER = "weather.forecast_home"
    private const val CLUB_MEDIA = "media_player.club"
    private const val FRONT_LOCK = "lock.lock_pro_0fa0"
    private const val CALENDAR = "calendar.work"

    // When the speakers are just carrying TV audio, the media cards read the
    // show/film title and poster from these instead of showing the speaker's
    // useless "TV" / "TV Audio" title.
    private val CLUB_TV_MEDIA = listOf(
        "media_player.plex_plex_for_android_tv_tv",
        "media_player.the_club_tv",
    )

    /**
     * The `screensaver` block a config's own keys are laid over, so
     * dashboard.json only has to name what it changes (e.g. just
     * `keys_pass_through`), and a file written before the screensaver
     * existed still gets one.
     */
    val screensaverDefaults: Map<String, Any?> = mapOf(
        "enabled" to true,
        // "docked" = only while on the charger; "always" = whenever idle.
        "trigger" to "docked",
        "idle_seconds" to 45,
        // Backlight while showing, 0–1. Night = sun.sun below the horizon.
        "brightness" to 0.22,
        "night_brightness" to 0.05,
        "keep_screen_on" to true,
        "keys_pass_through" to true,
        "time_format" to 12,
        "weather_entity" to WEATHER,
        // Checked first; then any other player that's playing (media_any).
        "media_entities" to listOf(CLUB_MEDIA) + CLUB_TV_MEDIA,
        "media_any" to true,
        "timers" to true,
        "calendar_entity" to CALENDAR,
        "title_separator" to " - ",
        "event_within_hours" to 12,
        "alarm_entities" to listOf("sensor.work_alarm_1", "sensor.work_alarm_2", "sensor.work_alarm_wfh"),
        "always_entities" to listOf("sensor.work_alarm_wfh"),
        "enabled_entity" to "input_boolean.work_alarms_enabled",
        "off_today_entity" to "input_boolean.work_alarms_off_today",
        "alarm_within_hours" to 12,
        // Shown only while the entity is in (one of) `state`. {name} and
        // {state} are filled in; icon: lock_open, vacuum, timer, info, music,
        // warning (default).
        "alerts" to listOf(
            mapOf(
                "entity_id" to FRONT_LOCK, "state" to "unlocked",
                "text" to "Front door unlocked", "icon" to "lock_open",
            ),
            mapOf(
                "entity_id" to "vacuum.roborock_qrevo_master",
                "state" to listOf("cleaning", "returning"),
                "text" to "Vacuum {state}", "icon" to "vacuum", "warn" to false,
            ),
        ),
    )

    /** A clock and nothing else: the app still starts, and the banner says why. */
    val fallback = AppConfig(
        pages = listOf(PageConfig("Main", listOf(CardConfig("clock_header", emptyMap())))),
    )
}
