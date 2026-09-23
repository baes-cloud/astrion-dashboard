# Rebuild feature inventory

Exhaustive list of every capability in the app **before** the UI rebuild (baseline `01e806a`),
written before any code was changed. Every item must survive the rebuild. At the end of the
rebuild each item is ticked and annotated with where the new code provides it
(`file` / symbol). Nothing may be dropped silently.

**Status at the end of the rebuild: every item kept — 0 dropped.** Items
marked `[~]` keep the capability but deliberately change how it looks or is
operated; each says exactly what changed. Nothing is `[!]`.

Legend: `[ ]` not yet verified · `[x]` kept (with location) · `[~]` kept with a deliberate,
documented behaviour change · `[!]` could not be kept (must be escalated, never silent).

---

## A. App shell, pages and navigation

- [x] A1. Four default pages in order TV / Main / Media / Climate; `startPage` = 1 (Main).
  → `config/DashboardConfig.kt` (unchanged pages/order/startPage)
- [x] A2. Pages come from `/sdcard/astrion/dashboard.json`; compiled `DashboardConfig.default` is the fallback.
  → `config/DashboardLoader.kt` (unchanged)
- [x] A3. Missing file → defaults written to disk + notice "Wrote defaults…"; unwritable → notice "Can't access…".
  → `DashboardLoader.load` (unchanged); notice shown by `Dashboard.kt` ConfigNoticeBanner
- [x] A4. Malformed JSON → built-in defaults + notice "dashboard.json invalid (…)".
  → `DashboardLoader.load` (unchanged)
- [x] A5. Bare top-level JSON array → single page named "Main".
  → `DashboardLoader.parse` (unchanged)
- [x] A6. Config reloaded on every `onResume` (adb push + app switch applies edits); hotkeys rebound on reload.
  → `MainActivity.onResume` → `reloadDashboard` → `bindHotkeys` (unchanged)
- [x] A7. Unknown top-level keys carried through verbatim into `AppConfig.options` (`ir_mode`, `voice`, `alarm`, …).
  → `DashboardLoader.parse` (unchanged)
- [x] A8. Card pinning per page: `pin: "top"` (fixed header band), `pin: "bottom"` (fixed footer band), `pin: "fill"` (middle card absorbs remaining height; page stops scrolling).
  → `ui/Dashboard.kt` PageContent (pinnedTop / pinnedBottom / fill, unchanged semantics)
- [x] A9. Middle section scrolls vertically when no `fill` card.
  → `ui/Dashboard.kt` PageContent verticalScroll
- [x] A10. Unknown card type renders an inline "Unknown card type: X" warning instead of vanishing.
  → `ui/Dashboard.kt` UnknownCard (restyled, same message)
- [x] A11. Pager swipe between pages disabled (horizontal drags reserved for in-card gestures).
  → `ui/Dashboard.kt` HorizontalPager `userScrollEnabled = false` — plus NEW touch navigation: header → page picker (`ui/Navigation.kt`)
- [x] A12. Hotkey page navigation (`page` by name, case-insensitive) jumps the pager.
  → `MainActivity.runHotkey` + `Dashboard` LaunchedEffect(navTarget)
- [x] A13. Cold arrival: resume after ≥ 30 s away → jump to `startPage`.
  → `MainActivity.onResume` (COLD_ARRIVAL_MS; now also dismisses a stale sheet)
- [x] A14. Kiosk fullscreen (status + nav bars hidden, immersive sticky).
  → `MainActivity.onCreate` systemUiVisibility (unchanged)
- [x] A15. Storage permission requested at first launch; dashboard reloaded when granted.
  → `MainActivity.storagePermission` (unchanged)
- [x] A16. Connection banner: nothing when CONNECTED; small "Connecting…" pill for CONNECTING/AUTHENTICATING; loud red bar for AUTH_FAILED ("Auth failed — check token"), ERROR ("Connection error — retrying"), DISCONNECTED ("Disconnected — controls inactive"); overlays the page (no reflow).
  → `ui/Dashboard.kt` ConnectionBanner — same states and wording, pill now has a spinner, failures on `dangerStrong`
- [x] A17. Config-notice banner, tap to dismiss (re-appears on next resume if still broken).
  → `ui/Dashboard.kt` ConfigNoticeBanner
- [x] A18. Controls gate their taps on `ctx.connected` (socket down = inactive).
  → every card computes `live = !unavailable && ctx.connected`; controls are now also visibly dimmed (`liveOrDim`)

## B. Home Assistant client

- [x] B1. WebSocket auth handshake with long-lived token from `BuildConfig` (secrets.properties).
  → `ha/HaClient.kt` sendAuth (now on the listener's own socket)
- [x] B2. `get_states` seed, `subscribe_events state_changed` live updates.
  → `HaClient.requestStates` / `subscribeStateChanges` / `onResult` / `onEvent`
- [x] B3. Coalesced publishing (≤ 1 publish / 120 ms).
  → `HaClient.startPublisher` (still 120 ms; now also writes per-entity cells)
- [x] B4. Heartbeat ping every 30 s; auto-reconnect 3 s after a failure.
  → `HaClient.startHeartbeat` / `scheduleReconnect` (+ de-duplicated, + server close now reconnects, + `ensureAlive` on resume)
- [x] B5. `call_service` (with `service_data`, `target.entity_id`).
  → `HaClient.callService` (same message; now also watches the result and reports refusals)
- [x] B6. `toggle(entityId)` helper (domain.toggle).
  → `HaClient.toggle` (unchanged)
- [x] B7. `fetchBitmap` — authed HTTP image fetch (entity_picture, proxy paths).
  → `HaClient.fetchBitmap(path, targetPx = 0)` — same signature compatible, now sized + cached
- [x] B8. `browseMedia` (`media_player/browse_media`) with 8 s timeout.
  → `HaClient.browseMedia` (unchanged)
- [x] B9. `getForecast` (`weather.get_forecasts`, `return_response`).
  → `HaClient.getForecast` (unchanged)
- [x] B10. Streaming subscriptions (`startSubscription` / `endSubscription`) for Assist pipeline.
  → `HaClient.startSubscription` / `endSubscription` (unchanged)
- [x] B11. Binary audio frames (`sendAudioChunk`).
  → `HaClient.sendAudioChunk` (unchanged)
- [x] B12. `authedUrl`, `bearerToken`, `playMedia`.
  → `HaClient.authedUrl` / `bearerToken` / `playMedia` (unchanged)

## C. Physical buttons (MainActivity + HardwareKeys)

- [x] C1. Keycode map incl. both 91 and 164 → MUTE, 82 → MENU, 133 → VOICE, 134–137 shortcut, 138–141 colour.
  → `input/HardwareKeys.kt` (unchanged)
- [x] C2. Short-press bindings (`hotkeys`); short-only keys fire on every ACTION_DOWN (hold-to-repeat, e.g. volume).
  → `MainActivity.dispatchKeyEvent` short-only path (unchanged)
- [x] C3. Long-press bindings (`longHotkeys`), 1.5 s hold; short action fires on release when a long binding exists.
  → `MainActivity.dispatchKeyEvent` + LONG_PRESS_MS 1500 (unchanged) — plus NEW hold-progress bar and hold confirmation
- [x] C4. Double-tap bindings (`doubleHotkeys`), 280 ms window, single tap deferred; different key doesn't consume it.
  → `MainActivity.dispatchKeyEvent` DOUBLE_TAP_MS 280 (unchanged) — plus double-tap confirmation in the strip
- [x] C5. `then` chained follow-up actions on any hotkey.
  → `MainActivity.runHotkey` `then` loop (unchanged)
- [x] C6. Pseudo-service `astrion.toggle_mute` (reads live `is_volume_muted`).
  → `MainActivity.runAction` astrion.toggle_mute (unchanged)
- [x] C7. Pseudo-service `astrion.unjoin_others` (unjoins every other `group_members` entry).
  → `MainActivity.runAction` astrion.unjoin_others (unchanged)
- [x] C8. Unmapped keys: logged; toast only on debug builds; passed to the OS.
  → `MainActivity.dispatchKeyEvent` unmapped branch (unchanged)
- [x] C9. VOICE key: start listening / stop listening / cancel; RECORD_AUDIO requested on first use.
  → `MainActivity.onVoiceKey` (unchanged)
- [x] C10. Default hotkeys: D-pad/CENTER/HOME/BACK/POWER → `remote.send_command` on `remote.the_club_tvv`; MUTE → `remote.send_command HOME` **then** `astrion.toggle_mute` on club (flagged in audit F-C13; behaviour must stay unchanged); VOLUME ± → club volume; PAGE ± → club brightness scripts; LIGHT→Main, CURTAIN→TV, SCENE→Media, AC→Climate; CUSTOM_1..4 → `select_source` Netflix/Plex/ABC iView/VLC on the ADB entity.
  → `DashboardConfig.hotkeys` — unchanged; MUTE→HOME flagged in a comment, NOT changed
- [x] C11. Default long-presses: CENTER → club play/pause; PAGE_UP/DOWN → open/close blinds; LIGHT/CURTAIN/AC → long_* scripts; SCENE → `script.long_music` then `astrion.unjoin_others`; CUSTOM_1..4 → long_red/green/blue/yellow.
  → `DashboardConfig.longHotkeys` (unchanged)
- [x] C12. Default double-tap: SCENE → club next track.
  → `DashboardConfig.doubleHotkeys` (unchanged)

## D. IR Mode

- [~] D1. Toggle key from `ir_mode.toggle_key` (default MENU), `toggle_long` (default false: short tap), 500 ms debounce.
  → `MainActivity.bindIrToggle` — same key/option/debounce. Because the toggle key now ALSO has a hold action (open the button map, only when the config gives that key no long-press), the tap toggles on release instead of on press-down.
- [x] D2. While on, UP/DOWN/LEFT/RIGHT/CENTER/VOL±/MUTE/PAGE±/POWER/HOME/BACK are swallowed and blasted as Samsung IR (both DOWN/UP consumed; blast on DOWN).
  → `MainActivity.dispatchKeyEvent` IR intercept (unchanged, still first after the alarm snooze check)
- [x] D3. `ir_mode.codes` overrides per key; `ir_mode.repeat` (1–5) frames per press.
  → `MainActivity.bindHotkeys` irCodes / irRepeat (unchanged)
- [x] D4. Overlay: "IR MODE" header, emitter status line (`IrBlaster.statusLine()`), last-key echo ("KEY → 0x…", "no IR code mapped", "transmit FAILED"), "Press a hardware button…" placeholder.
  → `ir/IrModeOverlay.kt` header, StateLine status, last-key box
- [x] D5. On-screen network buttons from `ir_mode.buttons` (default 9): kinds `ir` (named or hex code), `app` (play_media app on `tv_entity`), `source` (select_source), `command` (remote.send_command on `remote_entity`), `service` (+ `entity_id`); feedback text "Sent/Launching/Input →/Failed/not configured".
  → `ir/IrModeOverlay.kt` press() (unchanged logic, AstrionButton grid)
- [x] D6. "Exit IR Mode" button; scrim swallows taps; in-window overlay (not a Dialog).
  → `ir/IrModeOverlay.kt` Exit button, scrim swallow, in-window
- [~] D7. Toast "No IR emitter available on this device" when opening without an emitter.
  → `MainActivity.toggleIrMode` — same text, shown in the in-app feedback strip instead of an Android Toast

## E. Alarm popup

- [x] E1. Config `alarm`: `ringing_entity`, `snooze_timer`, `info_entity`, `snooze{service,entity_id}`, `stop{service,entity_id}`.
  → `MainActivity.alarmOptions` / `fireAlarmAction` (unchanged)
- [x] E2. Ringing while ringing_entity == on; snoozed while snooze timer `active` (with `finishes_at`); ring drains over timer `duration`.
  → `MainActivity.alarmUiState` (now reads the alarm's own entity cells)
- [x] E3. Wakes screen (wake lock + TURN_SCREEN_ON/SHOW_WHEN_LOCKED/KEEP_SCREEN_ON), re-asserted every 20 s while ringing; brings app to front if backgrounded; flags cleared when stopped.
  → `MainActivity.watchAlarm` / `wakeForAlarm` (unchanged)
- [x] E4. Snooze = one tap; Stop = ~0.9 s press-and-hold with fill + hint "Keep holding — stops alarms for today" on a quick tap.
  → `ui/AlarmOverlay.kt` PillButton + HoldToStop (same 900 ms hold and hint) — plus NEW: OK / shortcut buttons snooze while ringing (`MainActivity.SNOOZE_KEYS`)
- [x] E5. Snoozed view: countdown ring "BACK IN", "Hide until it rings again" (hides until next ring).
  → `ui/AlarmOverlay.kt` SnoozeRing + 'Hide until it rings again' (now 48dp)
- [x] E6. Shows big clock, date, "WAKE UP"/"SNOOZING", shift card (title, place, "starts h:mm a").
  → `ui/AlarmOverlay.kt` (snooze label now uses the timer's real length)
- [x] E7. Pulsing ripple while ringing; overlay above dashboard and IR mode; in-window.
  → `ui/AlarmOverlay.kt` Ripple (animates only while ringing); overlay order in `MainActivity.setContent` keeps alarm above dashboard, sheets and IR

## F. Voice

- [x] F1. Assist pipeline session (`voice.pipeline` optional), phase images from `voice.image_dir` (`listening/processing|thinking/speaking/done/error/idle.png`), fallback mic orb.
  → `voice/VoiceSession.kt` (unchanged) + `voice/VoiceOverlay.kt` loadPhaseImage (now downsampled/cached)
- [x] F2. Overlay: phase label, transcript, reply, error; Stop/Close button; mic-level pulse.
  → `voice/VoiceOverlay.kt`

## G. Screen / power

- [x] G1. Motion wake via (wake-up) accelerometer, threshold 0.9 m/s², 2 s cooldown.
  → `MainActivity.setupMotionWake` / `wakeScreen` (unchanged)
- [x] G2. Screen sleep left to system timeout (6 min); nothing keeps it on except the ringing alarm.
  → unchanged (only the ringing alarm holds the screen on)

## H. Cards (29 registered types) and their options/controls

- [x] H1. `light` — tap tile toggles; on/off tint. Options: entity_id, name.
  → `cards/impl/LightCard.kt` (optimistic)
- [x] H2. `bubble_light` — pill = brightness slider (drag, tap-to-set, 5 % floor), bulb tap toggles, bulb long-press → colour/brightness detail, 3 colour presets when on & colour-capable, live rgb fill tint, `dimmable:false` → tap toggles. Options: entity_id, name, dimmable.
  → `cards/impl/BubbleLightCard.kt` — all behaviours kept; long-press opens `LightDetailSheet` (in-window)
- [x] H3. `light_zones` — titled zones of bubble pills; zone master switch (turn_on/turn_off all). Options: zones[{title, lights[{entity_id,name,dimmable}]}].
  → `cards/impl/LightZonesCard.kt` (master is an AstrionSwitch in the section header)
- [x] H4. `light_group` — auto-split dimmable (2-col tiles with slider, long-press detail) vs on/off-only (toggle rows). Options: lights[{entity_id,name}].
  → `cards/impl/LightGroupCard.kt`
- [x] H5. `scene_grid` — grid (`columns`) or `layout:"row"` (fits ≤5, else 4.5 peek), `title`, scenes[{entity_id,name,color,icon(mood/night/white/day/club/off)}]; activation = `<domain>.turn_on`; 3D sink press.
  → `cards/impl/SceneGridCard.kt` (grid / row layouts, title, colours, icons, 3D sink)
- [x] H6. `tv_remote` — power, D-pad + OK, back/home/menu, app buttons (default 4 or `apps` [{name,app}|{name,service,entity_id,data}]). Options: name, remote_entity, mute_entity, media_entity, commands{key→command}.
  → `cards/impl/TvRemoteCard.kt` (all options incl. commands, apps, media_entity; `mute_entity` still read, still unused as before)
- [x] H7. `media_player` compact — art with playing ring, title/artist, vol−, play/pause, vol+.
  → `cards/impl/MediaPlayerCard.kt` compact
- [~] H8. `media_player` full — optional source dropdown (`source_entity`), `top_buttons` [{name,service,entity_id,data}], big art or placeholder, title/artist, transport row (vol−, prev, play/pause, next, vol+), `show_controls:false` display-only; blurred-art background; `tv_entity`/`tv_entities` title/poster borrowing when source is TV.
  → `cards/impl/MediaPlayerCard.kt` full — all controls/options kept; source dropdown → `SourceSheet`; a display-only card (`show_controls:false`) with nothing on collapses to one line instead of the big placeholder
- [~] H9. `climate` — name, off button, setpoint ± (entity `target_temp_step` > `step` option > 1.0, clamped to min/max), current temp, HVAC mode chips (from `hvac_modes`), fan chips (`fan_modes` option, default low/medium/high/auto). Options: entity_id, name, step, fan_modes.
  → `cards/impl/ClimateCard.kt` — all options kept; setpoint now debounced; `off` is not a mode chip (the power button turns off, and now also back on); all other modes shown (previously only the first four)
- [x] H10. `cover` — open/stop/close, position "N% open", filled/outlined blinds glyph, `invert_position`, `invert_buttons`. Options: entity_id, name.
  → `cards/impl/TileCards.kt` CoverCard
- [x] H11. `fan` — tap name toggles, % readout, slower/faster by `step` (default 20).
  → `cards/impl/TileCards.kt` FanCard
- [x] H12. `switch` — tap toggles, `icon` (heater/heat, fan, bulb/light, default power), `on_color`.
  → `cards/impl/TileCards.kt` SwitchCard (`on_color` now also accepts #RRGGBB as opaque)
- [~] H13. `lock` — padlock, name, state + "N min ago" (`show_age`), segmented Lock/Unlock with live side inert, locking/unlocking shown, `hold_entity` padlock hold toggles helper (red padlock "held open"), `flush`.
  → `cards/impl/LockCard.kt` — all options kept; Unlock is now press-and-hold (600 ms), Lock stays a tap
- [~] H14. `clock_weather` — full: big time, date, condition, temp, diary line (`calendar_entity`, `title_separator`), forecast rows (`forecast_rows`) with range bars, watermark (`watermark_size`); dense (`show_time:false`): now column (glyph, temp, condition, today range bar) + next-days columns (`chip_days`, default 5); `show_date`, `bare`, `time_format`. Forecast via service, refreshed every 30 min.
  → `cards/impl/ClockWeatherCard.kt` — all options kept; condition glyphs are Material icons instead of emoji; the diary / next-alarm time now reads 'Tue 7:05am'
- [~] H15. `picture_elements` — floorplan image (`image`), sampled decode, `aspect`, `max_crop` (default 12 %), `fill`/`pin:fill`, `flush`; elements [{entity_id,left,top}|{service,targets,icon:"power"}] 40 dp icons: tap toggle, long-press light → detail popup; on/off/unavailable styling; radars (`radars` list or legacy `radar`) with prefix, targets, unit/units_per_metre, origin_left/top, scale_x/scale_x_right/scale_y, top_offset_left, rotation, flip_x/y, blend, color, accent_color, label; vacuum overlay (entity_id, room_entity, room_positions, dock_position) with docked/moving icon and rocking animation while cleaning; tap → vacuum popup.
  → `cards/impl/PictureElementsCard.kt` — every option kept (+ optional `dim`, default true); unavailable lights are no longer tappable; vacuum opens in a sheet
- [x] H16. `row` — children side by side, equal weight.
  → `cards/impl/RowCard.kt`
- [~] H17. `swipe_stack` — children one behind another, swipeable, `titles`, tappable dots, fixed `height`.
  → `cards/impl/SwipeStackCard.kt` — same options; titled stacks show segmented tabs instead of dots (dots kept when no titles); children top-aligned
- [x] H18. `stack` — children joined into one card (`flush` passed down), child `pin:fill` absorbs height.
  → `cards/impl/StackCard.kt`
- [x] H19. `monitor` — title + list of entity values with units, "Unavailable" rows.
  → `cards/impl/MonitorCard.kt`
- [x] H20. `button_grid` — `columns`, `title`, `tile_height`, `icon_size`, `spacing`, buttons [{name, icon(png path), service, entity_id, data}].
  → `cards/impl/ButtonGridCard.kt` (`tile_height` is now a minimum, so larger system text grows the tile)
- [~] H21. `plex` — host, token, play_entity, adb_entity, tv_entity, wake_timeout (5–60 s), limit (1–30), machine_id, rows[{title,path}]; poster rows; tap plays (session path, or cold-start: wake TV → wait ADB → deep link); status lines ("Starting…", "Waking the TV…", "Opening Plex…", "TV didn't wake — try again", "Plex server unreachable"); loading / empty states; resume flag.
  → `cards/impl/PlexCard.kt` — all options and both play paths kept; status lines moved to the feedback strip + a spinner on the tapped poster; subtitle is a badge on the poster
- [x] H22. `media_shelves` — entity_id, limit, rows[{title,content_id,content_type}] via browse_media; playable items only; tap → play_media; art or note glyph.
  → `cards/impl/MediaShelvesCard.kt`
- [x] H23. `section` — heading text.
  → `cards/impl/SectionCard.kt`
- [x] H24. `clock_header` — title or live date (`date_format`), time (12/24), optional next diary entry (`calendar_entity`, `title_separator`).
  → `cards/impl/ClockHeaderCard.kt` (+ tap for page picker)
- [x] H25. `calendar_line` — next diary entry line (entity_id, title_separator, flush).
  → `cards/impl/InfoLineCards.kt` CalendarLineCard
- [x] H26. `now_playing` — "Now playing: title · artist" / "nothing", tv_entities borrowing, `prefix`, `flush`; `controls:true` → whole strip taps mute/unmute club + all group members to the same state, mute badge.
  → `cards/impl/InfoLineCards.kt` NowPlayingLineCard (+ paused / unavailable states)
- [x] H27. `next_up` — next event + next alarm (alarm_entities, always_entities, enabled_entity "off", off_today_entity skip rules), calendar_entity, title_separator.
  → `cards/impl/InfoLineCards.kt` NextUpCard
- [x] H28. `speaker_group` — title, master (name, icon), speakers[{entity_id,name,icon(sub/play3/play1/move/lamp)}]; per speaker: level %, Muted, Unavailable, read-only level bar, join/leave toggle (join on master / unjoin), mute, vol−, vol+; master chip.
  → `cards/impl/SpeakerGroupCard.kt` (icon keys kept; 'move' / 'lamp' now draw speaker glyphs, not phone / bulb)
- [~] H29. `source_select` — current source, dropdown of `source_list`, select_source; "No sources (device off?)".
  → `cards/impl/SourceSelectCard.kt` — dropdown → sheet (`SourceSheet`)
- [~] H30. `vacuum` — name, state, map image entity (rotation `map_rotation`, `map_height`), start/pause/home/locate, cleaning-mode (fan speed) picker, room buttons → `app_segment_clean`.
  → `cards/impl/VacuumCard.kt` — all options; cleaning-mode picker inline; room buttons are press-and-hold
- [~] H31. Light detail popup — brightness % headline, vertical brightness pill (drag/tap), power toggle, 10 colour swatches (colour lights), 4 colour-temperature presets (Candle 2200 / Warm 2700 / Neutral 4000 / Cool 5500 K).
  → `cards/impl/LightDetailSheet.kt` — every control kept; a tap at the bottom of the pill floors at 5 % instead of turning the light off (the power button turns it off)
- [x] H32. Media browser component (drill-down browse, back, close, play) — present in code (currently unused by any card).
  → `cards/impl/MediaBrowser.kt` (now an AstrionSheet; still unused by any card, as before)
- [x] H33. Unavailable entity handling: cover/climate/lock/speaker/media/fan/switch/bubble/monitor/floorplan show "Unavailable" and gate taps.
  → all cards via `StateLine(…, StateKind.Unavailable)` / `UnavailableBadge` (+ bubble / light / light_group / lock / speakers / TV idle line)
- [x] H34. `humanise()` and `weatherLabel()` for HA state strings.
  → `ui/Theme.kt` humanise / weatherLabel (unchanged)

## I. Images and performance behaviours

- [x] I1. Floorplan + grid icons decoded off-thread with `inSampleSize`.
  → `ui/Bitmaps.kt` decodeSampled / rememberSampledBitmap (+ cache)
- [x] I2. Plex posters requested server-scaled, LRU cached.
  → `cards/impl/PlexCard.kt` thumbUrl (now 128×184) + `ImageCache`
- [x] I3. Media art blurred background via 32 px downscale.
  → `cards/impl/MediaPlayerCard.kt` backdrop (32 px, darkened with a Multiply tint)

## J. Build / device

- [x] J1. `secrets.properties` → BuildConfig HA_URL / HA_TOKEN (untouched).
  → untouched
- [x] J2. `device/` ADB firewall scripts (untouched).
  → untouched

## K. Added by the rebuild (no config needed; all optional)

- Page picker on every page header; built-in header on pages without a `clock_header`.
- Button map (generated from the live config): from the page picker, or hold the IR toggle key.
- Hardware hold progress bar; hold / double-tap confirmation (haptic + strip).
- Alarm snooze from OK / Light / Curtain / Music / Aircon while ringing.
- BACK closes an open sheet.
- Optimistic state, pending spinner and red-outline failure on every control; every refused / unsent HA call explained in the feedback strip.
- Socket liveness probe on resume; server-side close now reconnects.
- Per-entity recomposition; shared sized image cache; app-scope caches for forecast, Plex rows and music shelves.
- New optional option: `picture_elements.dim` (default `true`) — darken the floorplan photo.
