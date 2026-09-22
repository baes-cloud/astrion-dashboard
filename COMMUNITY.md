# A fully custom, native dashboard app for the Sanytron/Astrion HA100 remote

*A from-scratch Android app that replaces the stock HaRemote dashboard with a fast,
fully configurable native UI — no cloud, no vendor lock-in, no laggy WebView.*

---

## Why

The HA100 is a lovely bit of hardware (480×800 touchscreen + real buttons) held back
by its software: the stock app only renders 11 hardcoded card types from your Lovelace
config and drops everything else, and running a real HA dashboard through Fully Kiosk
or the Companion app is far too slow on the 2015-era MT6580 SoC with 1 GB RAM.

So this replaces the *rendering* layer entirely, while speaking to Home Assistant
exactly the same way the stock app does.

## How it talks to Home Assistant

One plain **WebSocket to `ws://<ha-host>:8123/api/websocket`** — the standard HA API,
nothing custom:

```
┌──────────────┐      auth (long-lived token)      ┌────────────────┐
│  HA100 app   │ ────────────────────────────────► │ Home Assistant │
│  (native,    │ ◄──────────────────────────────── │                │
│   Compose)   │      auth_ok                      └────────────────┘
│              │
│              │  get_states            → seeds an in-memory entity map
│              │  subscribe_events      → state_changed keeps it live
│              │  call_service          → every button/slider/tap
│              │  weather.get_forecasts → forecast card (return_response)
│              │  media_player/browse_media → media library dialog
│              │  ping/pong             → 30s heartbeat + auto-reconnect
└──────────────┘
```

- The entity map is a Kotlin `StateFlow`; every card observes it, so any state change
  in HA repaints only the affected cards, instantly.
- Album art / camera-style images are fetched over plain HTTP with the same token.
- The optional Plex card talks **directly to the Plex server's HTTP API** (posters,
  On Deck, Recently Added) and starts playback by deep-linking the TV's Plex app via
  HA's `remote.turn_on` — the app itself never transcodes anything.

## Why it's fast on 1 GB / MT6580

| Technique | Effect |
|---|---|
| Native Jetpack Compose, no WebView | no JS engine, no DOM, no card_mod CSS |
| One WebSocket, one entity map | no polling, no REST chatter |
| Plain `Column` + `verticalScroll` per page | no heavy list machinery for ~10 cards |
| `LazyRow` only where there are many images (Plex posters) | offscreen posters never decode |
| Posters requested pre-scaled from the server (~140×210) | tiny decode, tiny memory |
| "Blur" = downscale-to-32px + upscale | real blur APIs don't exist on Android 8.1 |
| Icons: vector Material icons + user PNGs from /sdcard | no icon fonts, no network icons |
| Config = one small JSON file read at launch/resume | no YAML parsing, no HA dashboard fetch |

Cold start to fully rendered is ~1–2 s; card taps fire a service call in single-digit ms.

## The card system

Cards are tiny Kotlin classes registered in an open registry — adding a new card type
is one class + one registration line. Current set:

| Card type | What it does | HA services used |
|---|---|---|
| `clock_weather` | Current conditions + forecast. Full variant: big clock, date, N-day forecast rows with min/max gradient bars. Compact variant (`show_time: false`, `bare: true`) is a single strip: icon, whole-degree temperature and condition, today's range bar on the week's scale, then five day columns (day / icon / high/low) | `weather.get_forecasts` |
| `light` | Simple light tile: icon + name/state, tap to toggle | `light.toggle` |
| `bubble_light` | Bubble-card-style light pill: tap-to-toggle icon, drag anywhere to dim, fill tinted with the light's live `rgb_color`; **long-press opens a colour/brightness popup** (swatches + colour-temp presets, shown only if the light supports them) | `light.turn_on/off` (`brightness_pct`, `rgb_color`, `color_temp_kelvin`) |
| `picture_elements` | Floorplan image from `/sdcard/astrion/floorplan.png` with tappable light icons at % positions (glow when on); optional `radars` overlay plotting mmWave presence dots; optional `vacuum` overlay (robot glyph at its current room or dock — tap for full controls). Every overlay is laid out on the rectangle the image is actually drawn in, so icons never drift when the card changes shape; with `pin: fill` the plan covers its slot but crops at most `max_crop` (default 12%) before letterboxing | `light.toggle`, `light.turn_off` |
| `climate` | Setpoint steppers (respects the entity's real `target_temp_step` + min/max), HVAC mode chips, fan mode chips, dedicated off button | `climate.set_temperature`, `set_hvac_mode`, `set_fan_mode`, `turn_off` |
| `cover` | Mushroom-horizontal tile: icon + name/state left, open/stop/close right | `cover.open/stop/close_cover` |
| `fan` | Toggle tile + speed slider | `fan.turn_on/off`, `fan.set_percentage` |
| `switch` | Toggle tile with icon + configurable on-colour (e.g. dark red for a heater) | `switch.toggle` |
| `media_player` (compact) | One row: round art, title/artist, vol−/vol+; tap body = play/pause; blurred-art background | `media_player.*` |
| `media_player` (full) | Big art, centred now-playing, vol−/prev/play/next/vol+, configurable action buttons at the top (e.g. Group/Ungroup scripts) | `media_player.*`, any script |
| `speaker_group` | Sonos-style speaker list: tick = joined to the master player (join/unjoin fires immediately), live volume bar + mute/vol buttons per speaker | `media_player.join/unjoin`, `volume_set`, `volume_mute` |
| `source_select` | Dropdown source picker for a media player | `media_player.select_source` |
| `scene_grid` | Scene/script tiles — grid or horizontally swipeable row, per-tile colours, can be **pinned to the bottom** of a page | `scene.turn_on` / `script.turn_on` (by domain) |
| `button_grid` | Generic grid of buttons, each firing any service, with optional PNG icons from /sdcard; `tile_height` / `icon_size` / `spacing` to shrink it where space is tight | anything |
| `plex` | Native Plex poster rows (On Deck, Continue Watching, Recently Released, Recently Added TV / Movies — any library path, up to 30 each). One tap plays the exact item **even from a cold, switched-off TV**: wakes it over `androidtv_remote`, waits for ADB, then fires a Plex deep link that foregrounds Plex over any app and resumes at the stored offset | Plex HTTP API, `media_player.turn_on`, `androidtv.adb_command` |
| `tv_remote` | On-screen D-pad/transport remote with a configurable command map | `remote.send_command` |
| `vacuum` | Robot-vacuum map (rotated to match the floorplan), start/pause/dock/locate controls, cleaning-mode dropdown, per-room segment-clean buttons. The same options map doubles as the `picture_elements` floorplan's `vacuum` overlay | `vacuum.*`, `vacuum.send_command` (`app_segment_clean`) |
| `monitor` | Read-only sensor list with units | none (read-only) |
| `row` | Lays any two+ cards side by side | – |
| `stack` | Joins cards vertically into ONE card — one shape, no gaps; children drop their own background (`flush`) | – |
| `swipe_stack` | Stacks cards behind each other with a title and tappable pagination dots — e.g. the full player with music shelves swiped in behind it | – |
| `clock_header` | Slim page header: page name (or live date via `date_format`) left, time right, optional next diary entry centred | – |
| `section` | A section label ("Blinds") | – |
| `lock` | Door lock: state + "3 min ago", Lock/Unlock segmented toggle (the live side is inert, so a stray tap can't re-fire the bolt). Optional `hold_entity`: hold the padlock to toggle e.g. a "keep unlocked" helper, which turns the padlock red | `lock.lock` / `lock.unlock`, `input_boolean.toggle` |
| `media_shelves` | Swipeable album-art rows from HA's `browse_media` (Spotify albums, Sonos favourites / playlists…); one tap plays | `media_player.play_media` |
| `next_up` | One row: `Next event: Tue 8:15 HQ` left, `Next alarm: 7:05 Tue` right — earliest alarm across timestamp sensors, respecting enabled / off-for-today switches | – |
| `now_playing` | One-line "Now playing: …" (borrows the TV's title when the speaker carries TV audio). With `controls: true` the whole strip is one tap that mutes the player **and every speaker grouped with it** | `media_player.volume_mute` |
| `calendar_line` | The next diary entry on its own line: day, start time, location | –|

## Pages & navigation

The UI is a horizontal **pager** with dot indicators — swipe between pages, tap a dot,
or press a physical shortcut button. Example layout:

```
   TV / Plex  ◄──►  Main  ◄──►  Media (Sonos)  ◄──►  Climate
   now playing       glance panel:     player ⇄ music      aircon + fan modes
   Plex poster rows  date/weather/     shelves (swipe)     blinds
   (cold-start play) next event+alarm  playlists, group
                     lock · floorplan
                     mute strip
```

## Physical buttons

The HA100's buttons arrive as ordinary Android key events (keycode map extracted from
the stock firmware), so the app intercepts them in `dispatchKeyEvent` — **before** the
OS — and routes them through a config-defined table. Every button supports a **tap**
action and an optional **long-press (1.5 s)** action; keys *without* a long-press
binding keep Android's native auto-repeat (hold-to-scroll / hold-to-ramp).

| Button | Tap | Hold (1.5 s) |
|---|---|---|
| D-pad ↑↓←→ | TV `DPAD_*` (auto-repeats while held) | – |
| OK | TV `DPAD_CENTER` | play / pause the speakers |
| Back / Home / Power | TV `BACK` / `HOME` / `POWER` | – |
| Vol ± | speaker `volume_up/down` (auto-repeats) | – |
| 🔇 Mute | mute / unmute the speakers (reads live state) | – |
| ☰ Menu | open / close **IR Mode** | – |
| CH ▲ / CH ▼ | brightness up/down scripts | open / close blinds scripts |
| Light | → Main page | `script.long_lights` |
| Curtain | → TV / Plex page | `script.long_curtain` |
| Music | → Media page · **double-tap: next track** | `script.long_music`, then drops any other speakers from the group |
| Aircon | → Climate page | `script.long_aircon` |
| Red / Green / Blue / Yellow | launch Netflix / Plex / ABC / VLC on the TV | `script.long_red` … `script.long_yellow` |

Double-tap is opt-in per key (`doubleHotkeys`): a key that has one waits ~0.3 s
after release to see whether a second tap is coming, so everything else stays
instant.

App launches use `media_player.select_source` with the package name against the
ADB Android-TV media player; TV keys use `remote.send_command` against the Android-TV
remote entity. The `script.long_*` targets are just HA scripts — put anything in them.

## User configuration — no rebuild, ever

Everything above is driven by **one JSON file on shared storage**:

```
/sdcard/astrion/dashboard.json      the whole layout + button map
/sdcard/astrion/floorplan.png       your floorplan render
/sdcard/astrion/icons/*.png         custom button icons
```

- On first launch the app **writes its built-in default config out** to that file, so
  there is always something to edit.
- The file is **re-read every time the app returns to the foreground** — edit it with
  `adb push`, or any on-device file manager, then reopen the app. No rebuild, no
  reinstall.
- If the JSON is broken, the app falls back to its compiled-in defaults and shows a
  small banner saying why — it never crashes over config.

Schema sketch:

```json
{
  "startPage": 1,
  "pages": [
    { "name": "Lights", "cards": [
      { "type": "bubble_light", "options": { "entity_id": "light.living_room", "name": "Living Room" } },
      { "type": "scene_grid",   "options": { "layout": "row", "pin": "bottom",
          "scenes": [ { "entity_id": "scene.movie", "name": "Movie", "color": "#663F51B5" } ] } }
    ] }
  ],
  "hotkeys":     [ { "key": "LIGHT", "page": "Lights" },
                   { "key": "UP", "service": "remote.send_command",
                     "entityId": "remote.tv", "data": { "command": "DPAD_UP" } } ],
  "longHotkeys":   [ { "key": "PAGE_UP", "service": "script.open_blinds" } ],
  "doubleHotkeys": [ { "key": "SCENE", "service": "media_player.media_next_track",
                       "entityId": "media_player.club" } ],
  "ir_mode": { "toggle_key": "MENU", "repeat": 1, "codes": { "POWER": "0xE0E040BF" } },
  "alarm":   { "ringing_entity": "input_boolean.work_alarm_ringing",
               "snooze_timer": "timer.work_alarm_snooze", "info_entity": "sensor.work_start",
               "snooze": { "service": "script.turn_on", "entity_id": "script.work_alarm_snooze" },
               "stop":   { "service": "script.turn_on", "entity_id": "script.work_alarm_stop" } }
}
```

Key names for `hotkeys` / `longHotkeys` / `doubleHotkeys`: `UP DOWN LEFT RIGHT CENTER, PAGE_UP PAGE_DOWN,
VOLUME_UP VOLUME_DOWN MUTE, BACK HOME POWER VOICE MENU, LIGHT CURTAIN SCENE AC,
CUSTOM_1..CUSTOM_4` (the colour row).

## Alarm popup

A near-full-screen popup that mirrors a Home Assistant alarm: it is up while an
`input_boolean` "ringing" flag is on and shows a draining countdown ring while a
snooze `timer` runs — so snoozing or stopping from a phone updates every remote.
It **wakes the screen from sleep** (driven by a background listener on HA state,
because Compose stops drawing while the display is off), keeps it on, and
re-asserts every 20 s while ringing. Snooze is one tap; stop is a ~1 s
**press-and-hold** with a fill, since "stop for today" cancels later alarms too.
Full spec for rebuilding it on other screens (e.g. ESPHome): `docs/ALARM_POPUP_SPEC.md`.

## IR Mode

A tap of ☰ turns the remote into a plain IR handset for a Samsung TV: D-pad, OK,
volume, mute, channel, power, home and back blast NEC-style Samsung32 codes through
the HA100's own emitter (`ConsumerIrManager` → `consumerir.mt6580` → `/dev/irtx`),
one frame per press. Any code can be overridden in `ir_mode.codes`. Another tap of
☰ closes it.

## Locking down wireless ADB (`device/`)

The HA100 ships a `userdebug` build whose adbd accepts **any** client on the LAN
with no key authorisation — a root shell for anyone on your network. `device/`
has a small iptables script, re-applied at every boot, that lets only your admin
machine (matched by MAC as well as IP, so a new DHCP lease can't lock you out)
reach port 5555. It fails open, and USB adb is unaffected. See `device/README.md`.

## Coexisting with the stock app

The stock HaRemote app stays installed as the home/launcher app (the device firmware
expects it). A button mapped in **Key Mapper** (or any launcher shortcut) opens this
app on demand — both live side by side.

## Stack

Kotlin + Jetpack Compose (Material 3), OkHttp WebSocket, kotlinx.serialization.
minSdk 26 / targetSdk 34, one activity, zero native code, ~20 MB debug APK.
