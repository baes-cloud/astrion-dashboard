# Changelog

## Unreleased

Speed, memory and reliability work, mostly invisible but felt on the HA100's
MT6580.

### Changed

- **Media shelves load together** and each shows as soon as it arrives (the
  Spotify row takes ~1.3 s through HA and used to hold up the whole page);
  the last result is kept, so revisits show the shelves at once.
- **Zones fit on one screen:** `speaker_group` takes `"compact": true`, which
  puts the master on one row with its volume buttons and tightens the rest.
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
