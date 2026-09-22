package com.custom.astrion.config

import com.custom.astrion.cards.CardConfig

/**
 * Compiled-in FALLBACK layout. At runtime DashboardLoader reads the live layout
 * from /sdcard/astrion/dashboard.json (and writes this out as the initial file
 * when none exists). Edit the JSON and reopen the app to change things.
 *
 * Four swipeable pages, each also reachable by a physical shortcut button:
 *   0  TV       — Curtain button — TV now-playing + Plex shelves
 *   1  Main     — Light button   — clock/weather, floorplan, compact media
 *   2  Media    — Music button   — full player + group/ungroup + playlists + Sonos shelves
 *   3  Climate  — Aircon button  — aircon, blinds
 *
 * The physical D-pad / home / back keys drive the Android TV directly, and the
 * four colour buttons (red/green/blue/yellow) launch Netflix/Plex/ABC/VLC.
 */
object DashboardConfig {

    private const val WEATHER = "weather.forecast_home"
    private const val CLIMATE = "climate.aircon"
    private const val COVER = "cover.blinds"
    private const val CLUB_MEDIA = "media_player.club"
    private const val FRONT_LOCK = "lock.lock_pro_0fa0"

    // Plex server on the LAN. Plain http: no TLS handshake to pay for on the
    // MT6580, and the server allows unauthenticated local access.
    private const val PLEX_HOST = "http://10.0.0.10:32400"

    // Spotify library root, as exposed through the club's browse_media tree.
    private const val SPOTIFY_USER = "spotify://YOUR_SPOTIFY_ACCOUNT_ID"

    /**
     * Slim header pinned to the top of the pages that have no clock of their
     * own (Main has the big clock/weather card): time on the left, page name
     * on the right. Kiosk mode hides the status bar, so without this those
     * pages show no time at all.
     */
    private fun headerClock(
        title: String,
        dateFormat: String? = null,
        calendarEntity: String? = null,
    ) = CardConfig(
        type = "clock_header",
        options = mapOf(
            "pin" to "top", "time_format" to 12, "title" to title,
            "weather_entity" to WEATHER,
        )
            + (dateFormat?.let { mapOf("date_format" to it) } ?: emptyMap())
            // Events are named "<what> - <where>"; HA's own `location`
            // attribute is empty, so the venue can only be trimmed off the
            // title. Drop title_separator to show titles verbatim.
            + (calendarEntity?.let {
                mapOf("calendar_entity" to it, "title_separator" to " - ")
            } ?: emptyMap()),
    )

    // HA's Plex *client* entity for the club TV. Carries the real episode /
    // film title and poster, and is what actually starts playback:
    //   play_media  plex://<machineId>/<ratingKey>[?resume=1]
    private const val PLEX_CLIENT = "media_player.plex_plex_for_android_tv_tv"

    // When the speakers are just carrying TV audio, the media cards read the
    // show/film title and poster from these instead of showing the speaker's
    // useless "TV" / "TV Audio" title. The Plex client entity carries the
    // titles; the Android-TV entity is the fallback for non-Plex sources.
    private val CLUB_TV_MEDIA = listOf(
        PLEX_CLIENT,
        "media_player.the_club_tv",
    )
    private const val TV_REMOTE = "remote.the_club_tvv"
    // Same Google TV Streamer as TV_REMOTE, but its media_player entity: this
    // is the one that takes media_player.turn_on while the TV is in standby.
    private const val TV_REMOTE_MEDIA = "media_player.the_club_tvv"
    private const val TV_MEDIA = "media_player.club_android_tv_10_0_0_248_club_tv" // ADB integration with full app access
    private const val ICONS = "/sdcard/astrion/icons"        // playlist button PNGs
    private const val CALENDAR = "calendar.work"
    // Samsung Serif — the IR Mode popup's network-control target.
    private const val SAMSUNG_TV = "media_player.the_serif_qa55ls01dawxxy"
    private const val SAMSUNG_REMOTE = "remote.the_serif_qa55ls01dawxxy"

    /**
     * One options map, used both by the standalone `vacuum` card at the bottom
     * of Main and by the floorplan's "vacuum" overlay — so the entity/map/room
     * list stays in one place. The floorplan-only keys (room_entity,
     * room_positions, dock_position) are simply ignored by the standalone card.
     */
    private val VACUUM_OPTIONS: Map<String, Any?> = mapOf(
        "entity_id" to "vacuum.roborock_qrevo_master",
        "name" to "Vacuum",
        "map_image" to "image.kitchen_roborock_qrevo_master_map_0_custom",
        "map_rotation" to 90, // matches the floorplan card's orientation above
        "rooms" to listOf(
            mapOf("name" to "Club", "id" to 17),
            mapOf("name" to "Kitchen", "id" to 18),
            mapOf("name" to "Bedroom", "id" to 22),
            mapOf("name" to "Bathroom", "id" to 16),
            mapOf("name" to "Office", "id" to 21),
        ),
        // Coarse room-based position on the (non-scale) floorplan — reuses the
        // same % spots as that room's light icon. The vacuum has no live X/Y
        // in HA, only a "current room" sensor, so room-level is as precise as
        // it gets (and matches a stylised floorplan better than pixels would).
        "room_entity" to "sensor.roborock_qrevo_master_current_room",
        "room_positions" to mapOf(
            "Club" to listOf(42, 44),
            "Kitchen" to listOf(69, 57),
            "Bedroom" to listOf(29, 90),
            "Bathroom" to listOf(57, 90),
            "Office" to listOf(88, 82),
        ),
        // Left wall of the bedroom, near the window/plant, above the bed.
        "dock_position" to listOf(6, 63),
    )

    /** Big "turn everything off" target list, from the original dashboard. */
    private val ALL_LIGHTS = listOf(
        "light.apollo_r_pro_1_f5c680_rgb_light",
        "light.art_pendanta",
        "light.art_pendants",
        "light.bar_lights",
        "light.bathroom_downlights",
        "light.bathroom_mirror_led",
        "light.bed_lamp_left",
        "light.bed_lamp_right",
        "light.bedroom_lights",
        "light.club_accent_lights",
        "light.club_led_group",
        "light.philips_hue_omniglow_lightstrip",
        "light.downlights",
        "light.hue_play",
        "light.hue_records",
        "light.kitchen",
        "light.office_hue_lightstrip_solo",
        "light.office_lights",
        "light.play_art",
        "light.play_tv",
        "light.tv_art_light",
        "light.couch",
        "light.wardrobe",
    )

    // ---- Page 0: TV ---------------------------------------------------------
    // Replaces the old Lights page (the floorplan on Main covers the lights).
    private val tvPage = PageConfig(
        name = "TV",
        cards = listOf(
            headerClock("Plex"),
            // Now-playing for the TV itself: same big-art layout as the media
            // card, but display-only — no source picker, no transport row.
            // Reads the Plex client entity, which carries the real episode /
            // film title and poster.
            CardConfig(
                type = "media_player",
                options = mapOf(
                    "entity_id" to PLEX_CLIENT,
                    "variant" to "full",
                    "show_controls" to false,
                ),
            ),
            // Tap a poster and it starts on the club TV.
            CardConfig(
                type = "plex",
                options = mapOf(
                    "host" to PLEX_HOST,
                    "play_entity" to PLEX_CLIENT,
                    // Cold-start path: wake the TV over androidtv_remote (which
                    // answers from standby), then fire a Plex deep link over
                    // ADB. PLEX_CLIENT alone only works once a session already
                    // exists — see PlexCard's docs.
                    "tv_entity" to TV_REMOTE_MEDIA,
                    "adb_entity" to TV_MEDIA,
                    "wake_timeout" to 25,
                    "limit" to 15,
                    "rows" to listOf(
                        mapOf("title" to "On Deck", "path" to "/library/onDeck"),
                        // The server's own home hub, so it matches what the
                        // Plex app shows. Overlaps On Deck heavily by design —
                        // On Deck is "next up", this is "part-way through".
                        mapOf("title" to "Continue Watching", "path" to "/hubs/home/continueWatching"),
                        // Sections: 1 = Movies, 2 = TV Shows. `newest` is the
                        // section's "Recently Released" view — episodes by air
                        // date, as opposed to when they landed on the server.
                        mapOf("title" to "Recently Released", "path" to "/library/sections/2/newest"),
                        mapOf("title" to "Recently Added TV", "path" to "/library/sections/2/recentlyAdded"),
                        mapOf("title" to "Recently Added Movies", "path" to "/library/sections/1/recentlyAdded"),
                    ),
                ),
            ),
        ),
    )

    // ---- Page 1: Main -------------------------------------------------------
    private val mainPage = PageConfig(
        name = "Main",
        cards = listOf(
            // Main's header carries the date rather than the page name —
            // there is only one Main and the floorplan says so — plus the next
            // diary entry between the date and the time. Main only: the other
            // pages pass no calendar and the middle slot stays empty.
            headerClock("Main", dateFormat = "EEE, d MMM"),
            // Time and date both live in the header above now, so this card
            // keeps only what the header can't hold: condition, temperature,
            // the next diary entry and the forecast.
            CardConfig(
                type = "clock_weather",
                options = mapOf(
                    "entity_id" to WEATHER, "time_format" to 12, "forecast_rows" to 2,
                    "show_time" to false,
                    "show_date" to false,
                    // No watermark: in the three-line layout it sat behind
                    // today's range bar and read as clutter, and it only
                    // repeated the condition glyph already on the left.
                    "watermark_size" to 0,
                    // Pinned into the header band: the top of Main is one
                    // glance panel (date, next event, time, weather) and the
                    // body below it is the control card.
                    "bare" to true,
                    "pin" to "top",
                ),
            ),
            // Below the weather, still in the top band: what's next in the
            // diary on the left, when the next work alarm goes off on the right.
            CardConfig(
                type = "next_up",
                options = mapOf(
                    "pin" to "top",
                    "calendar_entity" to CALENDAR,
                    // Events are "<what> - <where>" with a site code in
                    // location; the trimmed title is only the fallback.
                    "title_separator" to " - ",
                    "alarm_entities" to listOf("sensor.work_alarm_1", "sensor.work_alarm_2", "sensor.work_alarm_wfh"),
                    "always_entities" to listOf("sensor.work_alarm_wfh"),
                    "enabled_entity" to "input_boolean.work_alarms_enabled",
                    "off_today_entity" to "input_boolean.work_alarms_off_today",
                ),
            ),
            // Three separate cards: lock, floorplan, now playing. (They were
            // briefly one joined `stack`; separate reads better here.)
            CardConfig(
                type = "lock",
                options = mapOf(
                    "entity_id" to FRONT_LOCK, "name" to "Front Door",
                    // Hold the padlock to toggle this; it turns the padlock red.
                    "hold_entity" to "input_boolean.front_door_keep_unlocked",
                ),
            ),
            CardConfig(
                type = "picture_elements",
                options = mapOf(
                    "image" to "/sdcard/astrion/floorplan.png",
                    // Absorbs the leftover vertical space so Main ends exactly
                    // at the bottom of the screen.
                    "pin" to "fill",
                    "aspect" to 1.3,
                    "elements" to listOf(
                        // Positions are % of the border-cropped floorplan image.
                        // Spaced at least ~15% apart so the 40dp icons never
                        // overlap, and kept off the edges so none get clipped.
                        elem("light.hue_play", 16, 9),
                        elem("light.tv_art_lights", 45, 11),
                        elem("light.art_group", 64, 8),
                        elem("light.bar_spotlights", 85, 13),
                        elem("light.couch", 27, 30),
                        elem("light.downlights", 65, 28),
                        // Console candles on the sideboard (right wall).
                        elem("light.kitchen_console_candles", 93, 33),
                        elem("light.club_led_group", 12, 46),
                        elem("light.bedroom_cupboard_light", 51, 54),
                        elem("light.kitchen_group", 73, 54),
                        elem("light.send_nudes", 28, 64),
                        elem("light.office_lights", 93, 67),
                        elem("light.wardrobes", 77, 86),
                        elem("light.bathroom_downlights", 57, 90),
                        elem("light.bedroom_lights", 29, 90),
                    ),
                    // mmWave presence dots — one block per LD2450 sensor. Tune
                    // each one's origin/scale/rotation until a real person
                    // lands in the right spot. `origin_*` is where that sensor
                    // physically sits on the plan (%); +Y runs away from its
                    // face, so `rotation` turns its axes to match the plan.
                    "radars" to listOf(
                        // Club / living area — Apollo R-PRO-1, reports METRES.
                        mapOf(
                            "prefix" to "sensor.club_apollo_r_pro_1_ld2450_target",
                            "targets" to 3,
                            "unit" to "m",
                            "origin_left" to 53,
                            "origin_top" to 10,
                            "scale_x" to 8.66,
                            "scale_x_right" to 17.32, // right half stretched wider
                            "scale_y" to 9.01,
                            "top_offset_left" to 7.88, // nudge left-side dots down
                            "rotation" to 0,
                            "flip_x" to false,
                            "flip_y" to false,
                            "blend" to "overlay", // pop over light icons
                            "color" to "#D9155E6E",
                            "accent_color" to "#FF33CBDA",
                        ),
                        // Bedroom — bare ESPHome LD2450, reports MILLIMETRES.
                        // Mounted beside the TV at the middle of the bedroom's
                        // TOP wall, aimed down the plan at the bed — so
                        // rotation 0 (its +Y forward axis points south, same
                        // convention as the club Apollo).
                        mapOf(
                            "prefix" to "sensor.ld2450_tracker_target",
                            "targets" to 3,
                            "unit" to "mm",
                            "origin_left" to 29,
                            "origin_top" to 56,
                            "scale_x" to 9.0,
                            "scale_y" to 9.0,
                            "rotation" to 0,
                            "flip_x" to false,
                            "flip_y" to false,
                            "blend" to "overlay",
                            "color" to "#D96E2C7A", // purple = bedroom
                            "accent_color" to "#FFB06CD9",
                            "label" to "B",
                        ),
                        // Office / wardrobe — bare ESPHome LD2450, MILLIMETRES.
                        // Mounted on the office's right wall facing LEFT across
                        // the room, so rotation 90 points +Y west on screen.
                        mapOf(
                            "prefix" to "sensor.ld2450_tracker_2_target",
                            "targets" to 3,
                            "unit" to "mm",
                            "origin_left" to 97,
                            "origin_top" to 78,
                            "scale_x" to 9.0,
                            "scale_y" to 9.0,
                            "rotation" to 90,
                            "flip_x" to false,
                            "flip_y" to false,
                            "blend" to "overlay",
                            "color" to "#D9146B4A", // green = office
                            "accent_color" to "#FF4FD98C",
                            "label" to "O",
                        ),
                    ),
                    // Robot-vacuum icon overlaid on the same floorplan — tap it
                    // to open the full vacuum controls in a popup.
                    "vacuum" to VACUUM_OPTIONS,
                ),
            ),
            // Closes the page: a thin mini-player — what's on, plus mute and
            // play/pause.
            CardConfig(
                type = "now_playing",
                options = mapOf(
                    "entity_id" to CLUB_MEDIA, "tv_entities" to CLUB_TV_MEDIA,
                    "controls" to true,
                ),
            ),
            // (The standalone vacuum card was removed earlier — the vacuum is
            // reached via the robot icon on the floorplan, which opens the same
            // controls in a popup. Main fits without scrolling.)
        ),
    )

    /** A card as a container child (`row`, `stack`, `swipe_stack` take maps). */
    private fun CardConfig.asChild(): Map<String, Any?> = mapOf("type" to type, "options" to options)

    private fun elem(entityId: String, left: Int, top: Int): Map<String, Any?> =
        mapOf("entity_id" to entityId, "left" to left, "top" to top)

    // ---- Page 2: Media ------------------------------------------------------
    private val mediaPage = PageConfig(
        name = "Media",
        cards = listOf(
            headerClock("Sonos"),
            // The big player and the browse shelves occupy the same slot,
            // swiped between (or reached by tapping the dots) — the two
            // stacked made the page about twice the height of the screen.
            CardConfig(
                type = "swipe_stack",
                options = mapOf(
                    // Tall enough for the full player: 297dp of album art at
                    // 1.2:1 plus its title block and transport row. The shelves
                    // page is shorter and simply leaves space beneath.
                    "titles" to listOf("Player", "Media"),
                    "height" to 420,
                    "cards" to listOf(
                        mapOf(
                            "type" to "media_player",
                            "options" to mapOf(
                                "entity_id" to CLUB_MEDIA,
                                "tv_entities" to CLUB_TV_MEDIA,
                                "variant" to "full",
                            ),
                        ),
                        // Sonos/Spotify shelves via HA browse_media — one tap
                        // queues the item on the club. A playlist just starts
                        // rather than opening.
                        mapOf(
                            "type" to "media_shelves",
                            "options" to mapOf(
                                "entity_id" to CLUB_MEDIA,
                                "limit" to 10,
                                "rows" to listOf(
                        // Spotify's own "recently played" only exposes individual
                        // tracks through HA — the album/playlist it came from
                        // isn't in the payload — so this shows saved albums,
                        // which is where most of that shelf's items live.
                                    mapOf(
                                        "title" to "Spotify Albums",
                                        "content_id" to "$SPOTIFY_USER/current_user_saved_albums",
                                        "content_type" to "spotify://current_user_saved_albums",
                                    ),
                                    mapOf(
                                        "title" to "Favourite Songs",
                                        "content_id" to "object.item.audioItem.musicTrack",
                                        "content_type" to "favorites_folder",
                                    ),
                                    mapOf(
                                        "title" to "Favourite Playlists",
                                        "content_id" to "object.container.playlistContainer",
                                        "content_type" to "favorites_folder",
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            // Playlist buttons — EDIT the service names to your real scripts.
            CardConfig(
                type = "button_grid",
                options = mapOf(
                    "title" to "Playlists",
                    "columns" to 3,
                    // Shrunk from the 68dp default so the first row clears the
                    // bottom of the screen: this grid sits under the 420dp
                    // media stack and was being cut through the middle.
                    "tile_height" to 56,
                    "icon_size" to 26,
                    "spacing" to 8,
                    "buttons" to listOf(
                        playlist("Disco", "disco.png", "script.play_disco"),
                        playlist("House", "house.png", "script.play_house"),
                        playlist("MoS", "mos.png", "script.play_mos"),
                        playlist("Purple Disco", "turntable.png", "script.play_pdm"),
                        playlist("Dimitri From", "paris.png", "script.play_dfp"),
                        playlist("Trap", "trap.png", "script.play_trap"),
                    ),
                ),
            ),
            // Sonos speakers: tick = joined to the club (join/unjoin fires
            // immediately), with a live volume bar + mute/vol buttons each.
            CardConfig(
                type = "speaker_group",
                options = mapOf(
                    "title" to "Club Group",
                    "master" to CLUB_MEDIA,
                    "name" to "Club",
                    "icon" to "sub",       // Sonos Sub
                    "speakers" to listOf(
                        mapOf("entity_id" to "media_player.living_room_sonos", "name" to "Living Room", "icon" to "play3"),
                        mapOf("entity_id" to "media_player.bathroom_sonos", "name" to "Bathroom", "icon" to "move"),
                        mapOf("entity_id" to "media_player.bedroom_sonos", "name" to "Bedroom", "icon" to "lamp"),
                        mapOf("entity_id" to "media_player.office_sonos", "name" to "Office", "icon" to "play1"),
                    ),
                ),
            ),
        ),
    )

    private fun playlist(name: String, iconFile: String, service: String): Map<String, Any?> =
        mapOf("name" to name, "icon" to "$ICONS/$iconFile", "service" to service)

    // ---- Page 3: Climate ----------------------------------------------------
    private val climatePage = PageConfig(
        name = "Climate",
        cards = listOf(
            headerClock("Climate"),
            CardConfig(
                type = "climate",
                options = mapOf("entity_id" to CLIMATE, "name" to "Aircon", "step" to 0.5),
            ),
            CardConfig(type = "section", options = mapOf("title" to "Blinds")),
            // Covers stacked (not side by side), lounge first then bedroom.
            // Sofa and Bed report position backwards (100 = shut); Bed also
            // runs its motor backwards, so its up/down are swapped too. Sheer
            // is wired the normal way round and needs neither.
            CardConfig(
                type = "cover",
                options = mapOf("entity_id" to COVER, "name" to "Sofa", "invert_position" to true),
            ),
            CardConfig(
                type = "cover",
                options = mapOf("entity_id" to "cover.club_sheer_blinds", "name" to "Sheer"),
            ),
            CardConfig(
                type = "cover",
                options = mapOf(
                    "entity_id" to "cover.smart_blinds_curtain", "name" to "Bed",
                    "invert_position" to true, "invert_buttons" to true,
                ),
            ),
        ),
    )

    // ---- Hotkeys (physical buttons → actions) -------------------------------
    private val hotkeys = listOf(
        // D-pad + home/back/power drive the Android TV.
        tvKey("UP", "DPAD_UP"),
        tvKey("DOWN", "DPAD_DOWN"),
        tvKey("LEFT", "DPAD_LEFT"),
        tvKey("RIGHT", "DPAD_RIGHT"),
        tvKey("CENTER", "DPAD_CENTER"),
        tvKey("HOME", "HOME"),
        tvKey("BACK", "BACK"),
        tvKey("POWER", "POWER"),
        // Mute also mutes/unmutes the club speakers, matching whatever they're
        // currently set to (astrion.toggle_mute reads the live state).
        tvKey("MUTE", "HOME").copy(
            then = listOf(HotkeyConfig("", service = "astrion.toggle_mute", entityId = CLUB_MEDIA)),
        ),
        // Volume → club media player.
        HotkeyConfig("VOLUME_UP", service = "media_player.volume_up", entityId = CLUB_MEDIA),
        HotkeyConfig("VOLUME_DOWN", service = "media_player.volume_down", entityId = CLUB_MEDIA),
        // Page up/down → the club brightness scripts.
        HotkeyConfig("PAGE_UP", service = "script.turn_on", entityId = "script.increase_club_brightness_on_lights_only"),
        HotkeyConfig("PAGE_DOWN", service = "script.turn_on", entityId = "script.decrease_club_brightness_on_lights_only"),
        // Shortcut buttons → pages.
        HotkeyConfig("LIGHT", page = "Main"),      // light button → Main
        HotkeyConfig("CURTAIN", page = "TV"),      // curtain button → TV/Plex
        HotkeyConfig("SCENE", page = "Media"),     // music button (keycode 136)
        HotkeyConfig("AC", page = "Climate"),      // aircon button
        // Colour buttons launch apps on the TV.
        appKey("CUSTOM_1", "com.netflix.ninja"),   // red    → Netflix
        appKey("CUSTOM_2", "com.plexapp.android"),  // green  → Plex
        appKey("CUSTOM_3", "au.net.abc.iview"),     // blue   → ABC iView
        appKey("CUSTOM_4", "org.videolan.vlc"),     // yellow → VLC
    )

    private fun tvKey(key: String, command: String) = HotkeyConfig(
        key = key, service = "remote.send_command", entityId = TV_REMOTE,
        data = mapOf("command" to command),
    )

    /**
     * Launch a TV app by package name.
     *
     * Uses the ADB integration's `select_source`, NOT `media_player.play_media`
     * on the androidtv_remote entity (`media_player.the_club_tvv`). That entity
     * advertises PLAY_MEDIA and HA accepts the call with a 200, but the Google
     * TV Streamer silently ignores it: androidtv_remote forwards media_content_id
     * to the device as an app *link*, so it only works with a real deep-link URI
     * (`https://www.netflix.com/title/...`) — a bare package name is dropped with
     * no error anywhere. Verified on-device 2026-08-17.
     *
     * The ADB entity has no PLAY_MEDIA at all (play_media there 500s); its
     * `select_source` takes the bare package and launches all four apps.
     */
    private fun appKey(key: String, appId: String) = HotkeyConfig(
        key = key, service = "media_player.select_source", entityId = TV_MEDIA,
        data = mapOf("source" to appId),
    )

    // ---- Long-press bindings (~500ms hold) → scripts ------------------------
    // D-pad and the eight bottom buttons each fire a script on long press,
    // while a normal tap keeps its usual action.
    private val longHotkeys = listOf(
        // NOTE: no long-press on the D-pad arrows or volume — those keys
        // auto-repeat while held (hold-to-scroll on the TV, hold-to-ramp
        // volume). Long-press is reserved for keys where repeat isn't useful.
        // OK held → toggle play/pause on the club media player.
        HotkeyConfig("CENTER", service = "media_player.media_play_pause", entityId = CLUB_MEDIA),
        // CH (page) rocker long-press → blinds.
        longKey("PAGE_UP", "script.open_blinds"),
        longKey("PAGE_DOWN", "script.close_blinds"),
        // Shortcut row.
        longKey("LIGHT", "script.long_lights"),
        longKey("CURTAIN", "script.long_curtain"),
        // Music held: the script joins the speakers to the TV, then anything
        // still grouped to the club gets dropped out of that group.
        longKey("SCENE", "script.long_music").copy(
            then = listOf(HotkeyConfig("", service = "astrion.unjoin_others", entityId = CLUB_MEDIA)),
        ),
        longKey("AC", "script.long_aircon"),
        // Colour row.
        longKey("CUSTOM_1", "script.long_red"),
        longKey("CUSTOM_2", "script.long_green"),
        longKey("CUSTOM_3", "script.long_blue"),
        longKey("CUSTOM_4", "script.long_yellow"),
    )

    private fun longKey(key: String, script: String) = HotkeyConfig(key = key, service = script)

    // ---- Double-tap bindings -------------------------------------------------
    // Only the music button. Everything else keeps an instant single press —
    // a key listed here cannot act until the double-tap window closes.
    private val doubleHotkeys = listOf(
        HotkeyConfig("SCENE", service = "media_player.media_next_track", entityId = CLUB_MEDIA),
    )

    // ---- IR Mode -------------------------------------------------------------
    // Toggled by the ☰ MENU button (keycode 82).
    //
    // An earlier note here claimed keycode 82 was permanently unusable because
    // Key Mapper swallowed it system-wide. That was wrong about which key: the
    // mapping Key Mapper actually holds is on keycode 164, the 🔇 button (a
    // short press to open settings and a long press to launch HaRemote), which
    // is why MUTE was the unreliable one. ☰ is unclaimed and reaches
    // dispatchKeyEvent normally.
    private val irMode: Map<String, Any?> = mapOf(
        // The ☰ menu button, SHORT press: one tap opens IR Mode, the next tap
        // closes it. (MENU is excluded from MainActivity.IR_INTERCEPTED for
        // exactly that reason — every other button is swallowed and blasted.)
        "toggle_key" to "MENU",
        "toggle_long" to false,
        // Frames per press. ONE: the Samsung reads every full frame as its
        // own press, so the 2 this used to be made each button act twice.
        "repeat" to 1,
        "tv_entity" to SAMSUNG_TV,
        "remote_entity" to SAMSUNG_REMOTE,
        // Override any Samsung hex here if a button doesn't respond — no
        // rebuild needed. See docs/IR_CAPTURE.md for capturing real codes.
        "codes" to emptyMap<String, Any?>(),
        "buttons" to listOf(
            mapOf("name" to "Netflix", "app" to "Netflix"),
            mapOf("name" to "YouTube", "app" to "YouTube"),
            mapOf("name" to "Plex", "app" to "Plex"),
            mapOf("name" to "TV", "source" to "TV"),
            mapOf("name" to "HDMI", "source" to "HDMI"),
            mapOf("name" to "Source", "ir" to "SOURCE"),
            mapOf("name" to "Menu", "ir" to "MENU"),
            mapOf("name" to "Guide", "ir" to "GUIDE"),
            mapOf("name" to "Exit", "ir" to "EXIT"),
        ),
    )

    // ---- Work alarm popup -----------------------------------------------------
    // Mirrors HA's work-alarm package (/config/packages/work_alarms.yaml): the
    // popup is up while `ringing_entity` is on, shows "snoozed" while the timer
    // runs, and its two buttons run the package's own scripts — so stopping or
    // snoozing from the phone updates the remotes too, and vice versa.
    private val alarm: Map<String, Any?> = mapOf(
        "ringing_entity" to "input_boolean.work_alarm_ringing",
        "snooze_timer" to "timer.work_alarm_snooze",
        "info_entity" to "sensor.work_start",
        "snooze" to mapOf("service" to "script.turn_on", "entity_id" to "script.work_alarm_snooze"),
        "stop" to mapOf("service" to "script.turn_on", "entity_id" to "script.work_alarm_stop"),
    )

    // ---- Voice assistant -----------------------------------------------------
    private val voice: Map<String, Any?> = mapOf(
        // null/absent = HA's preferred pipeline. Set to a pipeline id to pin one.
        "pipeline" to null,
        // Drop listening.png / processing.png / speaking.png in here.
        "image_dir" to "/sdcard/astrion/voice",
    )

    val default = AppConfig(
        pages = listOf(tvPage, mainPage, mediaPage, climatePage),
        startPage = 1, // open on Main
        hotkeys = hotkeys,
        longHotkeys = longHotkeys,
        doubleHotkeys = doubleHotkeys,
        options = mapOf("ir_mode" to irMode, "voice" to voice, "alarm" to alarm),
    )
}