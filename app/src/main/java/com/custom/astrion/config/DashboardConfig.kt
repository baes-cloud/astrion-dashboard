package com.custom.astrion.config

import com.custom.astrion.cards.CardConfig

/**
 * Compiled-in FALLBACK layout. At runtime DashboardLoader reads the live layout
 * from /sdcard/astrion/dashboard.json (and writes this out as the initial file
 * when none exists). Edit the JSON and reopen the app to change things.
 *
 * Four swipeable pages, each also reachable by a physical shortcut button:
 *   0  Lights   — Light button   — scenes on top, then brightness sliders
 *   1  Main     — Curtain button — clock/weather, floorplan, compact media
 *   2  Media    — Music button   — full player + group/ungroup + playlists
 *   3  Climate  — Aircon button  — aircon, covers, TV-app launch row
 *
 * The physical D-pad / home / back keys drive the Android TV directly, and the
 * four colour buttons (red/green/blue/yellow) launch Netflix/Plex/ABC/VLC.
 */
object DashboardConfig {

    private const val WEATHER = "weather.forecast_home"
    private const val CLIMATE = "climate.aircon"
    private const val COVER = "cover.blinds"
    private const val CLUB_MEDIA = "media_player.club"
    private const val TV_REMOTE = "remote.the_club_tvv"
    private const val TV_MEDIA = "media_player.the_club_tvv" // app-launch target
    private const val ICONS = "/sdcard/astrion/icons"        // playlist button PNGs
    private const val CALENDAR = "calendar.family"
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

    // ---- Page 0: Lights -----------------------------------------------------
    private val lightsPage = PageConfig(
        name = "Lights",
        cards = listOf(
            // Scenes locked to the bottom (fixed, doesn't scroll with the
            // rest) in its own darker section band — a titled, horizontally
            // swipeable row of tiles.
            CardConfig(
                type = "scene_grid",
                options = mapOf(
                    "pin" to "bottom",
                    "layout" to "row",
                    "scenes" to listOf(
                        mapOf("entity_id" to "scene.night", "name" to "Night", "color" to "#254B6B", "icon" to "night"),
                        mapOf("entity_id" to "scene.white", "name" to "White", "color" to "#636262", "icon" to "white"),
                        mapOf("entity_id" to "script.day", "name" to "Day", "color" to "#6DA8A1", "icon" to "day"),
                        mapOf("entity_id" to "script.club", "name" to "Club", "color" to "#635080", "icon" to "club"),
                        mapOf("entity_id" to "script.off", "name" to "Off", "color" to "#33424A", "icon" to "off"),
                    ),
                ),
            ),
            // Full-width bubble_light pills, grouped into titled zones.
            CardConfig(
                type = "light_zones",
                options = mapOf(
                    "zones" to listOf(
                        mapOf(
                            "title" to "Club",
                            "lights" to listOf(
                                mapOf("entity_id" to "light.downlights", "name" to "Downlights"),
                                mapOf("entity_id" to "light.club_led_group", "name" to "LED Strips"),
                                mapOf("entity_id" to "light.art_group", "name" to "Art"),
                                mapOf("entity_id" to "light.club_accent_lights", "name" to "Accent"),
                            ),
                        ),
                        mapOf(
                            "title" to "Kitchen",
                            "lights" to listOf(
                                mapOf("entity_id" to "light.kitchen_group", "name" to "Kitchen", "dimmable" to false),
                                mapOf("entity_id" to "light.bar_lights", "name" to "Bar", "dimmable" to false),
                                mapOf("entity_id" to "light.kitchen_console_candles", "name" to "Candles", "dimmable" to false),
                            ),
                        ),
                        mapOf(
                            "title" to "Office",
                            "lights" to listOf(
                                mapOf("entity_id" to "light.office_only", "name" to "Office"),
                                mapOf("entity_id" to "light.wardrobes", "name" to "Wardrobe", "dimmable" to false),
                                mapOf("entity_id" to "light.send_nudes", "name" to "Send Nudes", "dimmable" to false),
                            ),
                        ),
                        mapOf(
                            "title" to "Bathroom",
                            "lights" to listOf(
                                mapOf("entity_id" to "light.bathroom_all", "name" to "Bathroom"),
                            ),
                        ),
                        mapOf(
                            "title" to "Bedroom",
                            "lights" to listOf(
                                mapOf("entity_id" to "light.bedroom_lights", "name" to "Bedroom"),
                                mapOf("entity_id" to "light.bedlamps", "name" to "Bed Lamps"),
                                mapOf("entity_id" to "light.bedwardrobe", "name" to "Accent", "dimmable" to false),
                                mapOf("entity_id" to "light.bedroom_grindr", "name" to "Grindr", "dimmable" to false),
                            ),
                        ),
                    ),
                ),
            ),
        ),
    )

    // ---- Page 1: Main -------------------------------------------------------
    private val mainPage = PageConfig(
        name = "Main",
        cards = listOf(
            CardConfig(
                type = "clock_weather",
                options = mapOf(
                    "entity_id" to WEATHER, "time_format" to 12, "forecast_rows" to 2,
                    "calendar_entity" to CALENDAR,
                ),
            ),
            CardConfig(
                type = "picture_elements",
                options = mapOf(
                    "image" to "/sdcard/astrion/floorplan.png",
                    "aspect" to 1.3,
                    "elements" to listOf(
                        // Positions are % of the border-cropped floorplan image.
                        elem("light.hue_play", 16, 9),
                        elem("light.club_led_group", 12, 46),
                        elem("light.couch", 27, 30),
                        elem("light.downlights", 65, 28),
                        elem("light.art_group", 45, 11),
                        elem("light.bar_spotlights", 85, 13),
                        elem("light.kitchen_group", 69, 54),
                        elem("light.office_lights", 88, 78),
                        elem("light.bathroom_downlights", 57, 90),
                        elem("light.bedroom_lights", 29, 90),
                        // Console candles on the new sideboard (right wall).
                        elem("light.kitchen_console_candles", 93, 33),
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
            // Compact player stays on Main.
            CardConfig(type = "media_player", options = mapOf("entity_id" to CLUB_MEDIA)),
            // (The standalone vacuum card was removed from Main — the vacuum is
            // now reached via the robot icon on the floorplan, which opens the
            // same controls in a popup — so Main fits without scrolling.)
        ),
    )

    private fun elem(entityId: String, left: Int, top: Int): Map<String, Any?> =
        mapOf("entity_id" to entityId, "left" to left, "top" to top)

    // ---- Page 2: Media ------------------------------------------------------
    private val mediaPage = PageConfig(
        name = "Media",
        cards = listOf(
            CardConfig(
                type = "media_player",
                options = mapOf(
                    "entity_id" to CLUB_MEDIA,
                    "variant" to "full",
                    // Compact source dropdown built into the top of the card
                    // (used to be a separate "Club source" card below).
                    "source_entity" to CLUB_MEDIA,
                ),
            ),
            // Playlist buttons — EDIT the service names to your real scripts.
            CardConfig(
                type = "button_grid",
                options = mapOf(
                    "title" to "Playlists",
                    "columns" to 3,
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
            // Remaining source pickers at the very bottom (Club's is now
            // built into the media_player card above).
            CardConfig(
                type = "source_select",
                options = mapOf("entity_id" to "media_player.android_tv_10_0_1_248", "name" to "Android TV source"),
            ),
            CardConfig(
                type = "source_select",
                options = mapOf("entity_id" to "media_player.the_serif_qa55ls01dawxxy", "name" to "Serif TV source"),
            ),
        ),
    )

    private fun playlist(name: String, iconFile: String, service: String): Map<String, Any?> =
        mapOf("name" to name, "icon" to "$ICONS/$iconFile", "service" to service)

    // ---- Page 3: Climate ----------------------------------------------------
    private val climatePage = PageConfig(
        name = "Climate",
        cards = listOf(
            CardConfig(
                type = "climate",
                options = mapOf("entity_id" to CLIMATE, "name" to "Aircon", "step" to 0.5),
            ),
            // On/off switch between aircon and covers.
            CardConfig(
                type = "switch",
                options = mapOf(
                    "entity_id" to "switch.bedroom_heater",
                    "name" to "Bedroom Heater",
                    "icon" to "heater",
                    "on_color" to "#B3902828", // semi-transparent dark red
                ),
            ),
            // Covers stacked (not side by side), lounge first then bedroom.
            CardConfig(type = "cover", options = mapOf("entity_id" to COVER, "name" to "Sofa")),
            CardConfig(type = "cover", options = mapOf("entity_id" to "cover.club_sheer_blinds", "name" to "Sheer")),
            CardConfig(type = "cover", options = mapOf("entity_id" to "cover.smart_blinds_curtain", "name" to "Bed")),
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
        tvKey("MUTE", "HOME"),
        // Volume → club media player.
        HotkeyConfig("VOLUME_UP", service = "media_player.volume_up", entityId = CLUB_MEDIA),
        HotkeyConfig("VOLUME_DOWN", service = "media_player.volume_down", entityId = CLUB_MEDIA),
        // Page up/down → the club brightness scripts.
        HotkeyConfig("PAGE_UP", service = "script.turn_on", entityId = "script.increase_club_brightness_on_lights_only"),
        HotkeyConfig("PAGE_DOWN", service = "script.turn_on", entityId = "script.decrease_club_brightness_on_lights_only"),
        // Shortcut buttons → pages.
        HotkeyConfig("LIGHT", page = "Lights"),    // light button
        HotkeyConfig("CURTAIN", page = "Main"),    // curtain button
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

    private fun appKey(key: String, appId: String) = HotkeyConfig(
        key = key, service = "media_player.play_media", entityId = TV_MEDIA,
        data = mapOf("media_content_type" to "app", "media_content_id" to appId),
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
        longKey("SCENE", "script.long_music"),   // music button
        longKey("AC", "script.long_aircon"),
        // Colour row.
        longKey("CUSTOM_1", "script.long_red"),
        longKey("CUSTOM_2", "script.long_green"),
        longKey("CUSTOM_3", "script.long_blue"),
        longKey("CUSTOM_4", "script.long_yellow"),
    )

    private fun longKey(key: String, script: String) = HotkeyConfig(key = key, service = script)

    // ---- IR Mode -------------------------------------------------------------
    // NOT the ☰ button (keycode 82): on real hardware that key is already
    // claimed by Key Mapper as a global "launch Astrion" shortcut, scoped to
    // "any input device" via its accessibility service. That intercepts the
    // raw KeyEvent system-wide BEFORE it ever reaches an app's dispatchKeyEvent
    // — no in-app binding can ever see keycode 82 while Key Mapper runs, so
    // MENU is permanently unusable here (confirmed on-device: the IR popup
    // opened instantly via synthetic injection, but the physical ☰ press did
    // nothing — the key never arrived).
    //
    // Long-press of 🔇 MUTE (keycode 91) instead — confirmed as a separate
    // physical key, and not claimed by Key Mapper. A short tap still mutes
    // the club media player as before.
    private val irMode: Map<String, Any?> = mapOf(
        "toggle_key" to "MUTE",
        "toggle_long" to true,
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

    // ---- Voice assistant -----------------------------------------------------
    private val voice: Map<String, Any?> = mapOf(
        // null/absent = HA's preferred pipeline. Set to a pipeline id to pin one.
        "pipeline" to null,
        // Drop listening.png / processing.png / speaking.png in here.
        "image_dir" to "/sdcard/astrion/voice",
    )

    val default = AppConfig(
        pages = listOf(lightsPage, mainPage, mediaPage, climatePage),
        startPage = 1, // open on Main
        hotkeys = hotkeys,
        longHotkeys = longHotkeys,
        options = mapOf("ir_mode" to irMode, "voice" to voice),
    )
}