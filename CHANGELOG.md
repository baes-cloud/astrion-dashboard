# Changelog

## Unreleased

Speed, memory and reliability work, mostly invisible but felt on the HA100's
MT6580, a battery pass (`docs/POWER.md`), and a usability pass that followed
a design review and a review of two weeks of real use. Home Assistant
companion automations and scripts are in `docs/HOME_ASSISTANT.md`.

### Lit rooms

- The floor plan can light each room in its lamps' real colour and
  brightness, and shade rooms with nothing on as the sun goes down (gradually,
  following the sun's elevation from 6° to -6°). Set up with a
  `lit_rooms` block on the `picture_elements` card (room outlines plus the
  lights in each); the bundled dashboard has it for the flat. Redraws only
  when a light changes, with a short fade, so it costs nothing at rest.
- With lit rooms on, a lit bulb icon takes its light's colour.
- One light that drives several fittings (the six downlights) can glow from
  each of them with `glow_spots` on its floorplan element.
- The glow is faint by day (`day_glow`, default 0.3 of full strength) and
  comes up with the dark, on the same sun curve as the shade. Full-strength
  pools bleached the bright daytime plan.
- Nights are darker and warmer: unlit rooms shade to `shade_alpha` 0.8, a
  pool tints the plan in its light's colour like lamplight on a surface
  instead of washing it towards white, and a room with any light on keeps a
  soft glow across the whole room (`room_glow`).
- Night glow toned down, as lit rooms looked washed out: `glow` 0.6 → 0.45,
  `room_glow` 0.3 → 0.15, pools cut less of the shade and add less white
  highlight, and the whole-room tint is fainter.

### Other changes

- The aircon's target temperature uses Syne's lighter cut, matching the
  weather temperature; the bold cut was very wide and odd in numerals.
- README rewritten for the current app, with new screenshots.

### Plex tiles and the October 2026 Plex app

Plex for Android TV 2026.19 (rolled out 3 October 2026) is a rewrite that
ignores the `plex://server://…` links the Plex tiles send, so a tap did
nothing while Plex was open. Its own links only reach an item's page, and
casting it through HA plays a single item without autoplay or Back. The fix
was to keep the classic app: Plex 10.30.8 for Android TV (January 2026),
reinstalled with Play Store auto-update turned off for Plex. The tiles work
unchanged with it.

### Code audit (October 2026)

Everything from the October 2026 code audit (`audit/astrion-code-audit.md`
in the project files), in its suggested order. No setting or config key
changes; `dashboard.json` files keep working as they are.

#### Battery

- The vacuum icon on the floor plan rocks in the draw phase, and only while
  its page is on screen, instead of recomposing ~60 times a second for the
  whole clean (B-1).
- The screensaver redraws only when the minute or what it shows changes (B-2).
- The alarm and alert checks read only their own entities, so other HA
  updates no longer re-run them (B-3).
- The wake word sends 80 ms audio frames instead of 32 ms: 12 Wi-Fi sends a
  second instead of 31 (B-4).
- The wake word, alert and screensaver timers wait for the next thing that
  can change instead of polling every few seconds (B-5).
- The mic level while listening only redraws the voice overlay (B-6).
- One shared minute clock for all clocks and "ago" labels, paused while the
  dashboard isn't showing, replaces five tickers (B-7).

#### Memory

- HA updates no longer copy every entity into a new map, up to 8 times a
  second (M-1).
- Audio frames reuse one buffer instead of allocating twice per frame (M-2).
- Cover art is cached per size and loaded once when several cards ask (M-3).
- The vacuum map, voice images and floor plan decode downsampled and off the
  main thread; an edited floor plan no longer leaves the old copy in memory
  (M-4, M-5, M-6).
- One OkHttp connection pool instead of three; timestamps stay numbers
  instead of round-tripping through ISO strings (M-7, M-8).

#### Code health

- CI builds, unit-tests and lints every push (A-6), and rejects unused
  imports (A-5, 61 removed). Unit tests now cover alerts, the next alarm,
  the alarm popup, key timing, dock/battery rules, config options and the
  bundled layout.
- The live `device/config/dashboard.json` is bundled in the APK as the
  default and fallback layout; the 700-line compiled copy is gone (A-8).
- `MainActivity` is ~300 lines lighter: key timing, motion wake, alarm state
  and battery rules are separate, tested classes (A-1). The HA connection
  belongs to the app rather than the Activity (A-2, first part).
- Config feature blocks are parsed once into typed options (A-3); shared
  helpers replace duplicated date, colour and service-call code (A-4).
- Toolchain: AGP 8.13.2, Gradle 8.14.5, Kotlin 2.3.21, Compose BOM
  2025.08.00, compileSdk 36 (A-7). targetSdk stays 34.

### Battery (measured)

- **The screen now actually turns off off the dock.** The stock HaRemote app
  writes the system screen timeout as "never", so an undocked remote stayed
  lit until it ran flat. Measured: −6 % an hour, with the screen on 100 % of
  the time and everything else under 1 mAh. The app now holds the timeout at
  `power.screen_timeout_seconds` (120) and puts it back whenever something
  changes it. Needs a one-off `adb shell appops set com.custom.astrion
  WRITE_SETTINGS allow`.
- **Screensaver off the dock too.** It comes on dimmed after 90 s idle
  (`screensaver.undocked_idle_seconds`), then the screen goes off. Docked
  behaviour is unchanged.
- **The screensaver redraws once a minute** instead of every second (about
  36 ms of UI thread per frame, all night on the dock). A running timer and
  the track position tick in their own small scopes.
- **The wake word streams audio continuously** (about 32 KB/s; 685 MB uploaded in
  10 h on one remote). `voice.wake_word_undocked_minutes` is now set to 2 in
  the shipped config.
- **Playhead noise is ignored.** A paused Cast/Plex session moved
  `media_position` about four times a second all day. Those diffs are
  stored, but they no longer redraw anything.
- `dock_fault` also covers a remote that reports charging while its level
  falls (`dock_draining`), the signature of worn or dirty dock contacts.
- The motion-wake note was wrong for the HA100: its accelerometer is not a
  wake-up sensor (flags `0x0`), so it never kept the CPU awake.

### Speed

- **Pre-compile after installing.** Android installed the APK as `quicken`
  (interpreted), and 89 % of frames were janky. Now `cmd package compile -m
  speed -f com.custom.astrion` runs after every install (see README).
- **Page keys are faster.** Every page stays composed and only the current
  one is drawn; page keys jump on key-down instead of key-up plus the
  double-tap window. Worst frame went from 522 ms to 200 ms.

### Usability and look

- **App-wide Material theme in Astrion colours:** a visible light press
  ripple, menus in Manrope with 12 dp corners, and real 18 ms haptics (the
  platform's LongPress was two 1 ms pulses). Holding a hardware key for its
  1.5 s long-press now buzzes too.
- **Popups are drawn in the main window:** light, vacuum and media browser.
  As separate Dialog windows they blocked the hardware keys and the idle
  timer. BACK and the page keys close them.
- **Feedback:**
  - Hardware-key actions show a short confirmation pill.
  - Failed service calls say so.
  - Calls made while offline are queued for 30 s instead of dropped.
- **The aircon setpoint is debounced:** one `set_temperature` about 0.9 s
  after the last tap, instead of one per tap, which tripped the Fujitsu cloud's
  rate limit.
- **Light group popups list their member lights:** tap a member to toggle it,
  hold to open its own popup.
- **Floorplan bulbs** buzz on tap and show a "sent" ring.
- **Header bar:** the clock is centred and a phone-style battery shows the
  remote's charge, with a bolt while charging.
- **Every page fits one screen:**
  - Thinner header band; next event and next alarm share one line, with icons.
  - One-line lock card.
  - Tighter Plex captions (`tightTextStyle`).
  - Favourites can wrap (`button_grid.label_lines`).
  - `media_player.art_aspect` option.
  - Compact `switch` tiles; speaker panels with full-height volume buttons.
  - Compact blind tiles and aircon card (Climate needs no scrolling).
- **Screensaver key handling:** `keys_pass_through` can list the keys that
  still act when they wake the screensaver. The shipped config passes volume,
  mute, page keys and voice, while OK, the D-pad and the app keys only wake.
  Pressing OK to wake a docked remote was switching the TV on (21 of 68
  Google TV sessions played nothing). MUTE no longer sends HOME to the TV.
- A `dashboard.json` `screensaver` block now overrides individual defaults
  instead of replacing all of them.
- The shipped config sets the night screensaver to 12 %: the old 5 %
  (12/255) read as a black panel on the HA100.
- **IR popup:**
  - Netflix, YouTube and Plex removed.
  - New Eye Comfort button.
  - Buttons can be guarded with `unless` (entity/attribute/is) plus `unless_text`.
- **Voice:** each conversation is logged to `/sdcard/astrion/voice/log.txt`.
- New switch tile icons: `music`, `night`.

### Final pass (2026-10-05)

- **The TV card shows what's on, and no longer controls it.** Its mute,
  previous, play, next and volume buttons are gone, with the code that routed
  them (`volume_entity`, the feature-based transport targeting). The hardware
  keys do the controlling. What's left is session information: the artwork at
  its own shape (a Plex poster is 2:3, where the old 1.6:1 hero cropped it to a
  band), the show, episode, season and episode number and rating, a Playing /
  Paused line with the app, and the position under a hairline that ticks only
  while playing and on screen. A paused Plex session left open in the
  background no longer passes for what's on the TV: a paused session only
  shows while its app is the one in front. With nothing playing, the card
  shows the app in front (its logo and name), and while the TV is off, the
  placeholder. The app tiles moved under the session, and the one in front is
  outlined. `entity_id` is now the TV's ADB entity, read for power and the app
  in front; its screen-grab `entity_picture` is no longer used as artwork. The
  card is about 100dp shorter, so the first Plex row shows in full.
- **Fan row on the Climate page.** The `fan` card is now one line the height
  of a blind row: speed down and up (taps settle before sending, as the aircon
  setpoint does) and power. Tapping the name opens a popup with everything:
  speed as eight bars, the fan's preset modes, side-to-side and up-and-down
  swing, the sleep timer with its time left, the room temperature and any
  fault. Optional `swing_entity`, `timer_entity`, `timer_left_entity`,
  `temperature_entity` and `problem_entity` add the extras. Speeds are sent as
  HA rounds them (37 % is speed 3 of 8, 38 % is speed 4).
- **The RMM dots only stream while the floorplan is on screen.** The stream
  sends two frames a second (about 6 KB/s, radar diagnostics included). With
  every page kept composed, it ran on other pages, under the screensaver and,
  because Compose doesn't run with the display off, with the screen off.
  Screen state is now handled in `HaClient` (`startForegroundSubscription`,
  `setForeground`). Frames that only jitter below 0.1 % don't redraw.
- **Plex rows and media shelves refresh on every visit to their page** again
  (`rememberPageVisits`). Since pages stay composed, they had only loaded at
  app start.
- Config: removed the Plex "Continue Watching" row (it mostly duplicated On
  Deck), and two settings the app never read (`clock_header.weather_entity`,
  and a climate `step` the aircon's own 1° step overrides).

### Battery

- **Docked means charging.** Plugged in no longer counts as docked unless the
  battery is actually charging (or full) and has been for a few seconds. A
  remote sitting badly on its dock was treated as on mains: screen held on and
  the wake word streaming, with every flicker of contact restarting the
  undocked wake word window.
- **Motion-wake listens for 5 minutes** after the screen goes off
  (`power.motion_wake_minutes`), never on the dock.
- **With the screen off, HA only sends what the alarm and alerts need.**
  After 30 s dark (`power.screen_off_filter_seconds`) the subscription narrows
  to their entities, and the full one comes back on wake. The radar sensors
  were waking the Wi-Fi radio several times a second all night.
- Entity updates publish when they arrive instead of from a loop every
  120 ms; one heartbeat instead of two.
- The dashboard isn't drawn under the screensaver, and the screensaver
  redraws once a second rather than on every HA update.
- **Battery in HA:** `power.report_entity` publishes the remote's battery, with
  a `dock_fault` attribute for "plugged in but not charging".
- **Ready to be the home app:** a HOME entry (off until enabled over adb) and
  `singleTask`, plus `astrion.open_settings` and `astrion.launch` actions to
  replace Key Mapper. `device/adbwifi.rc` brings wireless adb up before boot
  completes, so a home app that fails to start no longer locks you out.

### Changed

- **Media shelves load together** and each shows as soon as it arrives (the
  Spotify row takes ~1.3 s through HA and used to hold up the whole page);
  the last result is kept, so revisits show the shelves at once.
- **Zones fit on one screen:** `speaker_group` takes `"compact": true`: the
  master's card holds the group, its controls on one row across the top and
  each speaker as a darker panel inside it; `"height"` makes it fill the
  screen, the panels sharing the space.
- **The Main page no longer shifts as it loads:** the weather card reserves
  its forecast's space until the forecast arrives, and the forecast and the
  decoded floorplan are kept between visits.
- The now-playing mute badge is a rounded rectangle.
- **Install the release build** (`./gradlew assembleRelease`, see README).
  It's minified and signed with your debug key: ~1.7 MB against ~17 MB for
  debug, and Compose runs much faster outside a debuggable build.
- **Cards only redraw for their own entities.** A change anywhere in HA used
  to redraw every card that read any entity (several times a second with the
  floorplan's radar sensors); now each card follows just the entities it shows.
- **Less traffic from HA:** entities arrive via `subscribe_entities`
  (compact diffs) instead of the full old + new state of every change.
- **Cover art is cached** in memory and on disk (up to 500 MB) for media
  shelves, Plex posters and now-playing art, and decoded at display size, so
  pages and the screensaver show art instantly and it's downloaded once.
- Waking the screen no longer re-reads `dashboard.json` unless the file
  changed.
- The weather card's clock ticks on the minute.

### Fixed

- Requests made while connecting (e.g. the forecast at startup) were sent
  before authentication, which makes HA drop the connection; they now wait
  for it. A failed forecast retries after a minute instead of 30, and empty
  media shelves reload when HA comes back.
- Alarms and alerts are more reliable off the dock: a foreground service keeps
  the app from being killed, and it asks once to be exempt from battery
  optimisation so Doze doesn't cut the connection with the screen off.
- Reconnects immediately when the network returns, and backs off (up to a
  minute) while HA is unreachable instead of retrying every 3 s.
- Entities deleted in HA disappear from the app instead of lingering.
- The motion sensor only runs while the screen is off, where it's needed.

## v1.2.0 — 2026-09-27

A redesigned TV page and Main page, alert popups, a docked screensaver and
wake word, and a reworked Media page (Player / Media / Zones tabs, a Music
Assistant "Random albums" shelf, neater shelves).

### New (2026-09-27)

- **Alert popups.** An `alerts` list in dashboard.json raises a near-full-screen
  popup in the alarm's style while an entity condition holds (`state`,
  `for_seconds`, `unless`), with action buttons and Hide. `alarm` wakes the
  screen and keeps it on, `warning` wakes it once, `info` waits to be seen.
  Taps land even over the screensaver.
- **TV card** (`media_player` `"variant": "tv"`): a wide hero with the poster,
  title and series of what's on (from the first `art_entities` entry that is
  playing), a `placeholder` image when nothing is, a plain mute / prev / play /
  next / volume row (`volume_entity`), and an `apps` row of launcher tiles
  across the top — logo-only with `icon`, or letter badge / wordmark.
- **Scene pills** (`scene_grid` `"style": "pill"`): a scrolling row of pills with
  a glowing colour dot; the most recently activated scene is lit.
- **Floorplan overlay and stretch** (`picture_elements`): `overlay` floats
  another card (e.g. `now_playing`) over the plan — at a % row or pinned to the
  bottom edge — and `stretch` fills the slot without cropping, keeping the
  lowest icon clear of a bottom overlay.
- `button_grid` buttons take `color` / `text_color`.

### Changed (2026-09-27)

- **Door lock** is one button showing Locked / Unlocked that toggles it.
- **Plex shelves** load in parallel and reappear instantly on revisits.

### Fixed (2026-09-27)

- The wake word no longer streams audio after Home Assistant has ended the run
  (HA logged "binary message for non-existing handler" hundreds of times a
  day): frames stop with the run, the hour-long wait is rolled over before
  HA's timeout, and frames from a previous connection are dropped.
- A heartbeat could ping a reconnected socket before authenticating (HA then
  drops the connection), and a server-side close never reconnected.
- Long scene / app labels no longer break mid-word.

### New

- **Docked screensaver.** Leave the remote in its dock for 45 s and it switches
  to a black screen with a big faded clock, the date and weather, and only
  what's relevant right now: what's playing, running timers, the next alarm
  and diary entry if they're soon, and alerts such as the front door being
  unlocked. The backlight dims, and at night it goes warm amber and dimmer
  still. A touch wakes it without doing anything else, a button press wakes it
  and does its normal job (`keys_pass_through: false` to only wake), and
  lifting the remote off the dock takes it down. Configure it with the
  `screensaver` block (see `COMMUNITY.md`).
- **Docked wake word.** `voice.wake_word`: `"docked"` (default) listens for the
  pipeline's wake word while on the charger and for
  `voice.wake_word_undocked_minutes` (default 10) after it's lifted off;
  `"always"` listens on battery too; `"off"` disables it.
- **Tabbed `swipe_stack`.** With `titles`, the page switcher is a segmented tab
  bar (the current tab filled blue) instead of a caption and dots; without
  titles the dots remain. A tab can hold several cards with
  `{ "type": "column", "options": { "spacing": 10, "cards": [ ... ] } }`, and
  shorter tabs now sit at the top of the pager.
- **Music Assistant shelves.** A `media_shelves` row with
  `"source": "music_assistant"` fills itself from `music_assistant.get_library`
  (e.g. `"order_by": "random"` for a fresh random pick of albums on each visit)
  and plays with `music_assistant.play_media` on the MA player given in
  `"player"`. Needs `config_entry_id`; optional `media_type`, `limit`,
  `favorite`.
- `HaClient.callServiceForResponse()` for any service that returns data.

### Changed

- **Shelf tiles have captions**: up to two lines in a muted tone, fixed at two
  lines tall so a row stays aligned.
- **Shelf headings** are small uppercase labels followed by a hairline across
  the rest of the width.
- **Shelves scroll themselves only inside a fixed-height parent**
  (`swipe_stack` `height`); otherwise they take their full height and the page
  scrolls.
- `speaker_group` `"title": ""` hides the heading.

## v1.1.0 — 2026-09-22

A big one: a redesigned home page, a proper TV/Plex page, music shelves, an alarm
popup, working IR Mode, and a floorplan that finally puts every icon where it
belongs.

### New

- **Alarm popup.** A near-full-screen alarm that mirrors a Home Assistant
  alarm package: ringing while an `input_boolean` is on, a draining countdown
  ring while a snooze `timer` runs. It wakes the screen from sleep, keeps it on
  while ringing (and turns it back on if someone switches it off), and brings
  the app to the front. Snooze is one tap; stop is a ~1 s press-and-hold, since
  it cancels the rest of the day's alarms too. Design: warm "dawn" light, a
  pulsing alarm glyph, big clock, the shift on a small card, and glowing
  full-width buttons. Spec for other screens: `docs/ALARM_POPUP_SPEC.md`.
- **TV / Plex page** (on the Curtain button): what's on the TV, with the real
  episode/film title and poster, plus Plex poster rows — On Deck, Continue
  Watching, Recently Released, Recently Added TV and Movies, 15 each.
- **Cold-start Plex playback.** Tapping a poster now works even when the TV is
  off or on another app: it wakes the TV over `androidtv_remote`, waits for ADB,
  then fires a Plex deep link that opens the item and resumes where you were.
  Previously playback only worked if Plex was already playing something.
- **Music shelves.** Swipe the Sonos player to reveal album-art rows from HA's
  `browse_media` (Spotify albums, favourite songs, favourite playlists). One tap
  plays.
- **Home page redesign.** A glance panel (date and time; weather now with a
  range bar for today and five day columns; next diary entry as
  `Tue 8:15 HQ`; next alarm) above three cards: door lock, floorplan, and a
  now-playing strip.
- **Door-lock card.** State and "3 min ago", a Lock/Unlock toggle whose live
  side is inert, and an optional hold-the-padlock gesture for a helper such as
  "keep unlocked" (which turns the padlock red).
- **One-tap mute strip.** Tapping the now-playing strip mutes or unmutes the
  speaker **and every speaker grouped with it**, all to the same state.
- **Double-tap hotkeys** (`doubleHotkeys`). The Music button now skips the track
  on a double-tap. Opt-in per key, so every other button stays instant.
- **New cards:** `stack`, `swipe_stack`, `clock_header`, `section`, `lock`,
  `media_shelves`, `next_up`, `now_playing`, `calendar_line` (29 card types in
  all).
- **`device/` — lock down wireless ADB.** The HA100's build accepts adb
  connections from anyone on the LAN with no authorisation. An iptables script,
  re-applied at every boot, limits port 5555 to your admin machine (by MAC and
  IP). It fails open, and USB adb is unaffected.

### Fixed

- **Floorplan icons drifting off their rooms.** The image was cropped to fill its
  card while icons, radar dots and the vacuum were placed on the card itself,
  so whenever the card's shape changed they drifted — and the crop cut off real
  rooms. Overlays now sit on the image rectangle, and the plan crops at most 12%.
- **IR Mode.** The toggle had silently stopped matching any key after ☰ got its
  own key name; it now opens and closes on a tap of ☰. It sent every code twice
  (the Samsung counted both); now one frame per press. Mute and CH ▲/▼ are
  intercepted in IR Mode instead of also firing their normal actions.
- **Mute button** on units whose 🔇 reports `KEYCODE_VOLUME_MUTE` (164) rather
  than `KEYCODE_MUTE`.
- **Blinds wired backwards** — per-cover `invert_position` and `invert_buttons`.
- **TV "now playing"** showed "TV" instead of the title — it now borrows the
  title and poster from whichever TV entity has them.

### Changed

- A shared palette, type scale and card building blocks (`ui/Theme.kt`,
  `ui/CardKit.kt`) replace ~70 scattered colour literals; unavailable entities
  say so in words, and taps are gated on the connection being live.
- The lock's Lock/Unlock is a quiet segmented toggle rather than a bright
  button — it had been the loudest thing on the home page.
- `button_grid` takes `tile_height`, `icon_size` and `spacing`.
- The page header can show the live date and the next diary entry.

### Known issues

- **Remote clocks drift** when the remote can't reach an internet time server
  (for example, blocked by a firewall). Ours ran 44–60 s fast, which shows up in
  every displayed time and makes the snooze countdown end early (the alarm
  itself still resumes on Home Assistant's clock). Let the remote reach NTP, or
  run a local time server.
- No APK is attached: the HA URL and token are compiled in, so build your own
  with your `secrets.properties`.

## v1.0.3 — 2026-08-14

Light zones, embossed scenes, floorplan long-press, and more.
