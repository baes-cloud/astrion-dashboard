# Rebuild feature inventory

Exhaustive list of every capability in the app **before** the UI rebuild (baseline `01e806a`),
written before any code was changed. Every item must survive the rebuild. At the end of the
rebuild each item is ticked and annotated with where the new code provides it
(`file` / symbol). Nothing may be dropped silently.

Legend: `[ ]` not yet verified · `[x]` kept (with location) · `[~]` kept with a deliberate,
documented behaviour change · `[!]` could not be kept (must be escalated, never silent).

---

## A. App shell, pages and navigation

- [ ] A1. Four default pages in order TV / Main / Media / Climate; `startPage` = 1 (Main).
- [ ] A2. Pages come from `/sdcard/astrion/dashboard.json`; compiled `DashboardConfig.default` is the fallback.
- [ ] A3. Missing file → defaults written to disk + notice "Wrote defaults…"; unwritable → notice "Can't access…".
- [ ] A4. Malformed JSON → built-in defaults + notice "dashboard.json invalid (…)".
- [ ] A5. Bare top-level JSON array → single page named "Main".
- [ ] A6. Config reloaded on every `onResume` (adb push + app switch applies edits); hotkeys rebound on reload.
- [ ] A7. Unknown top-level keys carried through verbatim into `AppConfig.options` (`ir_mode`, `voice`, `alarm`, …).
- [ ] A8. Card pinning per page: `pin: "top"` (fixed header band), `pin: "bottom"` (fixed footer band), `pin: "fill"` (middle card absorbs remaining height; page stops scrolling).
- [ ] A9. Middle section scrolls vertically when no `fill` card.
- [ ] A10. Unknown card type renders an inline "Unknown card type: X" warning instead of vanishing.
- [ ] A11. Pager swipe between pages disabled (horizontal drags reserved for in-card gestures).
- [ ] A12. Hotkey page navigation (`page` by name, case-insensitive) jumps the pager.
- [ ] A13. Cold arrival: resume after ≥ 30 s away → jump to `startPage`.
- [ ] A14. Kiosk fullscreen (status + nav bars hidden, immersive sticky).
- [ ] A15. Storage permission requested at first launch; dashboard reloaded when granted.
- [ ] A16. Connection banner: nothing when CONNECTED; small "Connecting…" pill for CONNECTING/AUTHENTICATING; loud red bar for AUTH_FAILED ("Auth failed — check token"), ERROR ("Connection error — retrying"), DISCONNECTED ("Disconnected — controls inactive"); overlays the page (no reflow).
- [ ] A17. Config-notice banner, tap to dismiss (re-appears on next resume if still broken).
- [ ] A18. Controls gate their taps on `ctx.connected` (socket down = inactive).

## B. Home Assistant client

- [ ] B1. WebSocket auth handshake with long-lived token from `BuildConfig` (secrets.properties).
- [ ] B2. `get_states` seed, `subscribe_events state_changed` live updates.
- [ ] B3. Coalesced publishing (≤ 1 publish / 120 ms).
- [ ] B4. Heartbeat ping every 30 s; auto-reconnect 3 s after a failure.
- [ ] B5. `call_service` (with `service_data`, `target.entity_id`).
- [ ] B6. `toggle(entityId)` helper (domain.toggle).
- [ ] B7. `fetchBitmap` — authed HTTP image fetch (entity_picture, proxy paths).
- [ ] B8. `browseMedia` (`media_player/browse_media`) with 8 s timeout.
- [ ] B9. `getForecast` (`weather.get_forecasts`, `return_response`).
- [ ] B10. Streaming subscriptions (`startSubscription` / `endSubscription`) for Assist pipeline.
- [ ] B11. Binary audio frames (`sendAudioChunk`).
- [ ] B12. `authedUrl`, `bearerToken`, `playMedia`.

## C. Physical buttons (MainActivity + HardwareKeys)

- [ ] C1. Keycode map incl. both 91 and 164 → MUTE, 82 → MENU, 133 → VOICE, 134–137 shortcut, 138–141 colour.
- [ ] C2. Short-press bindings (`hotkeys`); short-only keys fire on every ACTION_DOWN (hold-to-repeat, e.g. volume).
- [ ] C3. Long-press bindings (`longHotkeys`), 1.5 s hold; short action fires on release when a long binding exists.
- [ ] C4. Double-tap bindings (`doubleHotkeys`), 280 ms window, single tap deferred; different key doesn't consume it.
- [ ] C5. `then` chained follow-up actions on any hotkey.
- [ ] C6. Pseudo-service `astrion.toggle_mute` (reads live `is_volume_muted`).
- [ ] C7. Pseudo-service `astrion.unjoin_others` (unjoins every other `group_members` entry).
- [ ] C8. Unmapped keys: logged; toast only on debug builds; passed to the OS.
- [ ] C9. VOICE key: start listening / stop listening / cancel; RECORD_AUDIO requested on first use.
- [ ] C10. Default hotkeys: D-pad/CENTER/HOME/BACK/POWER → `remote.send_command` on `remote.the_club_tvv`; MUTE → `remote.send_command HOME` **then** `astrion.toggle_mute` on club (flagged in audit F-C13; behaviour must stay unchanged); VOLUME ± → club volume; PAGE ± → club brightness scripts; LIGHT→Main, CURTAIN→TV, SCENE→Media, AC→Climate; CUSTOM_1..4 → `select_source` Netflix/Plex/ABC iView/VLC on the ADB entity.
- [ ] C11. Default long-presses: CENTER → club play/pause; PAGE_UP/DOWN → open/close blinds; LIGHT/CURTAIN/AC → long_* scripts; SCENE → `script.long_music` then `astrion.unjoin_others`; CUSTOM_1..4 → long_red/green/blue/yellow.
- [ ] C12. Default double-tap: SCENE → club next track.

## D. IR Mode

- [ ] D1. Toggle key from `ir_mode.toggle_key` (default MENU), `toggle_long` (default false: short tap), 500 ms debounce.
- [ ] D2. While on, UP/DOWN/LEFT/RIGHT/CENTER/VOL±/MUTE/PAGE±/POWER/HOME/BACK are swallowed and blasted as Samsung IR (both DOWN/UP consumed; blast on DOWN).
- [ ] D3. `ir_mode.codes` overrides per key; `ir_mode.repeat` (1–5) frames per press.
- [ ] D4. Overlay: "IR MODE" header, emitter status line (`IrBlaster.statusLine()`), last-key echo ("KEY → 0x…", "no IR code mapped", "transmit FAILED"), "Press a hardware button…" placeholder.
- [ ] D5. On-screen network buttons from `ir_mode.buttons` (default 9): kinds `ir` (named or hex code), `app` (play_media app on `tv_entity`), `source` (select_source), `command` (remote.send_command on `remote_entity`), `service` (+ `entity_id`); feedback text "Sent/Launching/Input →/Failed/not configured".
- [ ] D6. "Exit IR Mode" button; scrim swallows taps; in-window overlay (not a Dialog).
- [ ] D7. Toast "No IR emitter available on this device" when opening without an emitter.

## E. Alarm popup

- [ ] E1. Config `alarm`: `ringing_entity`, `snooze_timer`, `info_entity`, `snooze{service,entity_id}`, `stop{service,entity_id}`.
- [ ] E2. Ringing while ringing_entity == on; snoozed while snooze timer `active` (with `finishes_at`); ring drains over timer `duration`.
- [ ] E3. Wakes screen (wake lock + TURN_SCREEN_ON/SHOW_WHEN_LOCKED/KEEP_SCREEN_ON), re-asserted every 20 s while ringing; brings app to front if backgrounded; flags cleared when stopped.
- [ ] E4. Snooze = one tap; Stop = ~0.9 s press-and-hold with fill + hint "Keep holding — stops alarms for today" on a quick tap.
- [ ] E5. Snoozed view: countdown ring "BACK IN", "Hide until it rings again" (hides until next ring).
- [ ] E6. Shows big clock, date, "WAKE UP"/"SNOOZING", shift card (title, place, "starts h:mm a").
- [ ] E7. Pulsing ripple while ringing; overlay above dashboard and IR mode; in-window.

## F. Voice

- [ ] F1. Assist pipeline session (`voice.pipeline` optional), phase images from `voice.image_dir` (`listening/processing|thinking/speaking/done/error/idle.png`), fallback mic orb.
- [ ] F2. Overlay: phase label, transcript, reply, error; Stop/Close button; mic-level pulse.

## G. Screen / power

- [ ] G1. Motion wake via (wake-up) accelerometer, threshold 0.9 m/s², 2 s cooldown.
- [ ] G2. Screen sleep left to system timeout (6 min); nothing keeps it on except the ringing alarm.

## H. Cards (29 registered types) and their options/controls

- [ ] H1. `light` — tap tile toggles; on/off tint. Options: entity_id, name.
- [ ] H2. `bubble_light` — pill = brightness slider (drag, tap-to-set, 5 % floor), bulb tap toggles, bulb long-press → colour/brightness detail, 3 colour presets when on & colour-capable, live rgb fill tint, `dimmable:false` → tap toggles. Options: entity_id, name, dimmable.
- [ ] H3. `light_zones` — titled zones of bubble pills; zone master switch (turn_on/turn_off all). Options: zones[{title, lights[{entity_id,name,dimmable}]}].
- [ ] H4. `light_group` — auto-split dimmable (2-col tiles with slider, long-press detail) vs on/off-only (toggle rows). Options: lights[{entity_id,name}].
- [ ] H5. `scene_grid` — grid (`columns`) or `layout:"row"` (fits ≤5, else 4.5 peek), `title`, scenes[{entity_id,name,color,icon(mood/night/white/day/club/off)}]; activation = `<domain>.turn_on`; 3D sink press.
- [ ] H6. `tv_remote` — power, D-pad + OK, back/home/menu, app buttons (default 4 or `apps` [{name,app}|{name,service,entity_id,data}]). Options: name, remote_entity, mute_entity, media_entity, commands{key→command}.
- [ ] H7. `media_player` compact — art with playing ring, title/artist, vol−, play/pause, vol+.
- [ ] H8. `media_player` full — optional source dropdown (`source_entity`), `top_buttons` [{name,service,entity_id,data}], big art or placeholder, title/artist, transport row (vol−, prev, play/pause, next, vol+), `show_controls:false` display-only; blurred-art background; `tv_entity`/`tv_entities` title/poster borrowing when source is TV.
- [ ] H9. `climate` — name, off button, setpoint ± (entity `target_temp_step` > `step` option > 1.0, clamped to min/max), current temp, HVAC mode chips (from `hvac_modes`), fan chips (`fan_modes` option, default low/medium/high/auto). Options: entity_id, name, step, fan_modes.
- [ ] H10. `cover` — open/stop/close, position "N% open", filled/outlined blinds glyph, `invert_position`, `invert_buttons`. Options: entity_id, name.
- [ ] H11. `fan` — tap name toggles, % readout, slower/faster by `step` (default 20).
- [ ] H12. `switch` — tap toggles, `icon` (heater/heat, fan, bulb/light, default power), `on_color`.
- [ ] H13. `lock` — padlock, name, state + "N min ago" (`show_age`), segmented Lock/Unlock with live side inert, locking/unlocking shown, `hold_entity` padlock hold toggles helper (red padlock "held open"), `flush`.
- [ ] H14. `clock_weather` — full: big time, date, condition, temp, diary line (`calendar_entity`, `title_separator`), forecast rows (`forecast_rows`) with range bars, watermark (`watermark_size`); dense (`show_time:false`): now column (glyph, temp, condition, today range bar) + next-days columns (`chip_days`, default 5); `show_date`, `bare`, `time_format`. Forecast via service, refreshed every 30 min.
- [ ] H15. `picture_elements` — floorplan image (`image`), sampled decode, `aspect`, `max_crop` (default 12 %), `fill`/`pin:fill`, `flush`; elements [{entity_id,left,top}|{service,targets,icon:"power"}] 40 dp icons: tap toggle, long-press light → detail popup; on/off/unavailable styling; radars (`radars` list or legacy `radar`) with prefix, targets, unit/units_per_metre, origin_left/top, scale_x/scale_x_right/scale_y, top_offset_left, rotation, flip_x/y, blend, color, accent_color, label; vacuum overlay (entity_id, room_entity, room_positions, dock_position) with docked/moving icon and rocking animation while cleaning; tap → vacuum popup.
- [ ] H16. `row` — children side by side, equal weight.
- [ ] H17. `swipe_stack` — children one behind another, swipeable, `titles`, tappable dots, fixed `height`.
- [ ] H18. `stack` — children joined into one card (`flush` passed down), child `pin:fill` absorbs height.
- [ ] H19. `monitor` — title + list of entity values with units, "Unavailable" rows.
- [ ] H20. `button_grid` — `columns`, `title`, `tile_height`, `icon_size`, `spacing`, buttons [{name, icon(png path), service, entity_id, data}].
- [ ] H21. `plex` — host, token, play_entity, adb_entity, tv_entity, wake_timeout (5–60 s), limit (1–30), machine_id, rows[{title,path}]; poster rows; tap plays (session path, or cold-start: wake TV → wait ADB → deep link); status lines ("Starting…", "Waking the TV…", "Opening Plex…", "TV didn't wake — try again", "Plex server unreachable"); loading / empty states; resume flag.
- [ ] H22. `media_shelves` — entity_id, limit, rows[{title,content_id,content_type}] via browse_media; playable items only; tap → play_media; art or note glyph.
- [ ] H23. `section` — heading text.
- [ ] H24. `clock_header` — title or live date (`date_format`), time (12/24), optional next diary entry (`calendar_entity`, `title_separator`).
- [ ] H25. `calendar_line` — next diary entry line (entity_id, title_separator, flush).
- [ ] H26. `now_playing` — "Now playing: title · artist" / "nothing", tv_entities borrowing, `prefix`, `flush`; `controls:true` → whole strip taps mute/unmute club + all group members to the same state, mute badge.
- [ ] H27. `next_up` — next event + next alarm (alarm_entities, always_entities, enabled_entity "off", off_today_entity skip rules), calendar_entity, title_separator.
- [ ] H28. `speaker_group` — title, master (name, icon), speakers[{entity_id,name,icon(sub/play3/play1/move/lamp)}]; per speaker: level %, Muted, Unavailable, read-only level bar, join/leave toggle (join on master / unjoin), mute, vol−, vol+; master chip.
- [ ] H29. `source_select` — current source, dropdown of `source_list`, select_source; "No sources (device off?)".
- [ ] H30. `vacuum` — name, state, map image entity (rotation `map_rotation`, `map_height`), start/pause/home/locate, cleaning-mode (fan speed) picker, room buttons → `app_segment_clean`.
- [ ] H31. Light detail popup — brightness % headline, vertical brightness pill (drag/tap), power toggle, 10 colour swatches (colour lights), 4 colour-temperature presets (Candle 2200 / Warm 2700 / Neutral 4000 / Cool 5500 K).
- [ ] H32. Media browser component (drill-down browse, back, close, play) — present in code (currently unused by any card).
- [ ] H33. Unavailable entity handling: cover/climate/lock/speaker/media/fan/switch/bubble/monitor/floorplan show "Unavailable" and gate taps.
- [ ] H34. `humanise()` and `weatherLabel()` for HA state strings.

## I. Images and performance behaviours

- [ ] I1. Floorplan + grid icons decoded off-thread with `inSampleSize`.
- [ ] I2. Plex posters requested server-scaled, LRU cached.
- [ ] I3. Media art blurred background via 32 px downscale.

## J. Build / device

- [ ] J1. `secrets.properties` → BuildConfig HA_URL / HA_TOKEN (untouched).
- [ ] J2. `device/` ADB firewall scripts (untouched).
