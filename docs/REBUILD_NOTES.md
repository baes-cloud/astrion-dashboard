# UI rebuild notes (September 2026)

This is a ground-up UI rebuild driven by `docs/UI_AUDIT_2026-09.md`. It is
checked item by item against `docs/REBUILD_FEATURE_INVENTORY.md`, where every
pre-existing capability is ticked with its new location. Nothing was dropped.
14 items are marked `[~]`: the capability is kept, but how it looks or is
operated changed on purpose.

**The code was written without a compiler.** This container cannot reach
dl.google.com, so Gradle could not run. Every file was reviewed by hand
against the pinned versions (Compose BOM 2024.09.02 → ui/foundation 1.7.x,
material3 1.3.x; Kotlin 2.0.20; coroutines 1.8.1). Run `./gradlew
assembleDebug` before anything else; the most likely failure points are
listed in [Compile-risk list](#compile-risk-list).

No dependencies were added and the build files are unchanged. The
`dashboard.json` schema is unchanged too; the only new option is
`picture_elements.dim`, which is optional and defaults to `true`.

---

## 1. Design system

Everything lives in `ui/`. No card contains a `Color(0x…)`, a literal
`fontSize` or an ad-hoc corner radius. A `grep` for any of these outside
`ui/Theme.kt` should return nothing.

### Tokens (`ui/Theme.kt`)

**Colour.** The page is a deep ink-teal. Surfaces step up in tone to show
layering (page → sunken → card → raised → control → pressed). There are no
shadows, blurs or glows. Each signal colour has exactly one meaning:

| Colour | Hex | Means |
|---|---|---|
| amber | `on` `#FFC24B` | ON |
| blue | `accent` `#7AB0FF` / `accentStrong` | selected, navigation, pending |
| green | `good` | healthy, playing, locked |
| red | `danger` | failure, destructive |
| lilac | `unavailable` `#C0AEE3` | unavailable (used for nothing else) |

Unavailable is never shown by colour alone: it always comes with a cloud-off
icon and the word.

Contrast was computed per pair:

| Pair | Ratio |
|---|---|
| card vs page | 1.35:1 (was 1.14:1) |
| textPrimary on card | 10.5:1 |
| textSecondary on card | 6.4:1 |
| unavailable on card | 6.0:1 |
| selected chip (white on accentStrong) | 5.7:1 |

**Type (`AstrionType`).** The main roles are hero 44, display 30, headline 19,
title 16, body / bodyStrong 14, label 12 and section 12 caps. Special roles
(header, shout, button, and the alarm clock and countdown) are also tokens.
12sp is the floor.

**Other scales:**

| Scale | Values |
|---|---|
| Space | 2 / 4 / 8 / 12 / 16 / 20; `gutter` 10, `card` 14 |
| Radius | card 18, control 12, small 8, sheet 22; pills use `RoundedCornerShape(percent = 50)` |
| Touch | min 48, compact 44 |

### Components (`ui/CardKit.kt`, `ui/Components.kt`)

- **`AstrionButton` / `IconAction` / `ChoiceChip` / `AstrionSwitch`**: one
  family with a `Tone` (Neutral, Accent, On, Good, Danger, Sunken, Ghost). They
  share state styling:
  - pressed: one tone lighter, no ripple;
  - pending: a spinner;
  - failed: a 2dp red outline for 2.5 s;
  - disabled: 40 % opacity and inert.
- **`HoldButton`**: press-and-hold with a sweeping fill, for alarm stop, front
  door unlock and vacuum rooms. A quick tap calls `onQuickTap` so the control
  can explain itself.
- **`AstrionCard`, `IconWell`, `StateLine`, `UnavailableBadge`, `SectionLabel`,
  `LevelBar`, `PendingSpinner`**: the shared building blocks.
- **`tap` / `tapAndHold` / `holdOnly`**: haptic tick plus the click;
  `liveOrDim(live)` gives the disabled look.

### Feedback: tap → optimistic → pending → failure (`ui/Actions.kt`, `ui/Feedback.kt`)

- `rememberOptimistic(actual)` shows the requested value immediately, until
  HA reports it, the call fails, or 6 s pass.
- `rememberAction(ctx).run(calls…, onFail)` sends the calls inside the tap
  handler (UNDISPATCHED, so order is kept and nothing is cancelled). After
  250 ms it exposes `busy`. On a refusal or unsent call it exposes `failed`
  and rolls back the optimistic value.
- `HaClient` now registers every `call_service` id and reads HA's `result`.
  A refusal ("Home Assistant refused Kitchen (light.toggle): …") or an unsent
  call ("Not connected — … wasn't sent") is published on `HaClient.errors`,
  and `FeedbackStrip` shows it at the bottom of the screen. This covers
  hardware-button actions too.

### Overlays (`ui/Overlay.kt`)

`OverlayController` is hosted once at the root by MainActivity.
`AstrionSheet` is a bottom sheet with a scrim, a 48dp close button and a
scrollable body. `OptionList` gives 48dp choice rows.

The three popups that were `Dialog`s (light colour, vacuum, media browser) and
the two Material `DropdownMenu`s (source, cleaning mode) are now sheets or
inline lists in the Activity's own window. The physical buttons keep working
while they are open, and BACK closes them.

### Navigation (`ui/Navigation.kt`)

- **Page picker.** Every page header is a button with a chevron. It opens the
  page picker, which lists each page and the physical button that opens it.
  Pages without a `clock_header` get a built-in header, so there is always a
  way to change page by touch.
- **Button map** ("What do the buttons do?"). It is generated from the live
  hotkeys, long-presses, double-taps and the IR toggle, so it is always
  current. Open it from the page picker, or hold ☰.

### Performance

- **Per-entity state.** `HaClient.cell(id)` gives one `State` per entity, and
  `CardContext.entity(id)` reads it. A radar target moving now recomposes only
  its own dot, instead of every card on the page 8 times a second. All cards
  were moved to `ctx.entity`.
- **One image pipeline** (`ui/Bitmaps.kt`):
  - every image is decoded at the size it is drawn (album tiles 128 px,
    posters 128×184, full art 480);
  - opaque images use RGB_565;
  - one 12 MB LRU cache is shared by the floorplan, icons, art, covers and
    posters;
  - a cached image appears on the first frame.
- **App-scope caches.** The forecast (30 min), Plex rows (5 min) and music
  shelves (10 min) are cached, so Main and the TV page don't reload or reflow
  on every visit.
- **Cheaper drawing.**
  - The alarm glow rings are gone.
  - The alarm ripple only animates while ringing.
  - The media backdrop scrim, the light fills, the scene tiles and the voice
    halo are opaque blends (`lerp`, or a Multiply `ColorFilter`) rather than
    alpha layers.
  - Weather glyphs are vector icons instead of colour emoji.
- **Connection.** `ensureAlive()` pings on resume and reconnects a dead
  socket. A server-side close now reconnects (it used to stay stuck). Late
  callbacks from a replaced socket are ignored.

---

## 2. What changed per page

### Header (every page)

Tap the title or date for the page picker. There is no page name on Main (it
shows the date), as before.

### Main

- **Weather:** Material glyphs tinted from the theme, cached forecast, and
  "Tue 7:05am" for both next event and next alarm.
- **Lock:**
  - Unlock is press-and-hold; Lock stays a tap.
  - The segments are 44dp tall, and the current side is raised and inert.
  - "Locking…" and "Unlocking…" show as states.
  - A refusal outlines the control in red.
- **Floorplan:**
  - Each bulb reads its own entity.
  - A tap flips the bulb instantly, spins if HA is slow and outlines red if
    refused.
  - Long-press opens the colour sheet.
  - Unavailable lights show a lilac cloud-off glyph and ignore taps.
  - All icons dim when the socket is down.
  - The photo is darkened for a dark room (set `"dim": false` to turn this
    off).
  - The vacuum has a 44dp target and opens a sheet.
- **Now-playing strip:**
  - Shows playing, paused, nothing, or "Speaker unavailable".
  - The whole 48dp strip mutes or unmutes the whole group, optimistically.

### TV

- The now-playing card collapses to one line ("Nothing playing on the TV")
  when idle, so the posters start on the first screen.
- Posters are sized and cached, and the rows are cached. Episode or year is a
  badge on the poster.
- Cold-start progress appears in the feedback strip, and the tapped poster
  spins. A second tap during a start is ignored and says why.

### Media

- **Player:** optimistic play/pause, per-control pending and failure states,
  darkened art backdrop, and a sheet for the source list.
- **Swipe stack:** Player / Media are segmented tabs instead of 7dp dots.
  Children are top-aligned, so there are no blank bands.
- **Shelves:** sized and cached covers. A tapped cover spins until HA accepts,
  then the strip says "Playing …".
- **Playlists:** tiles use a minimum height, and show pending and failure.
- **Speakers:**
  - Join / Leave is a real button (it used to look disabled).
  - Grouping and mute are optimistic.
  - The mute button shows the current state.
  - No speaker uses a light-bulb icon.

### Climate

- **Setpoint:** taps add up. The value shows at once in blue with a spinner,
  and one call is sent 600 ms after the last tap.
- **Off state:**
  - The setpoint is muted and the line under it says "Off".
  - No mode or fan chip is lit; the power button is the only lit control.
  - Tapping power turns the unit back on (`climate.turn_on`).
- **Modes:** every HVAC mode is shown, in rows of three when there are more
  than four.
- **Blinds, fan and switch:** one tile shape, 44dp controls, opening and
  closing states, and failure outlines.

### Alarm, IR and voice

- **Alarm:**
  - While ringing, OK or any of the four shortcut buttons snoozes (the popup
    says so).
  - The snooze label uses the timer's real length.
  - "Hide until it rings again" is 48dp tall.
  - The glow is gone.
- **IR Mode:** an accent-edged panel and component buttons. Behaviour is
  unchanged, except that the ☰ tap now toggles on release (see below).
- **Voice:** tokens, and the phase art is downsampled.

### Hardware buttons

- Holding a button with a long-press shows a "Holding X…" bar after 250 ms.
- When a hold or double-tap fires, you get a haptic tick and the strip names
  the action (e.g. "Hold Light · Long lights").
- **Flagged, not changed:** 🔇 MUTE sends `HOME` to the TV before toggling the
  speaker mute (`DashboardConfig.kt`, comment "FLAG"). The button map shows it
  as "TV · Home + Mute / unmute speakers".
- **Behaviour change:** ☰ now also has a hold action (the button map), unless
  your config binds a long-press to ☰. A key with a hold action fires its tap
  on release, so the IR toggle happens when you let go of ☰ rather than when
  you press it.

---

## 3. Compile-risk list

These are the places most likely to fail the first build, most likely first.
Each one is a local fix.

1. **Material icon names** (material-icons-extended 1.7). All of these are
   believed to exist; if any doesn't resolve, swap in a neighbour:
   `CloudOff`, `ErrorOutline`, `CheckCircle`, `Info`, `NightsStay`,
   `WbCloudy`, `Dehaze`, `Umbrella`, `FlashOn`, `AcUnit`, `Grain`,
   `Thermostat`, `Radio`, `Tv`, `ExpandLess`, `ExpandMore`, `AlarmOff`,
   `GraphicEq`, `LinkOff`, `SettingsRemote`.
2. **`combinedClickable` / `clickable`** with `interactionSource = …,
   indication = null` (in `Components.kt`, `ButtonGridCard.kt`,
   `SceneGridCard.kt`). These use foundation 1.7 signatures with named
   arguments. If an overload is ambiguous, add `onClickLabel = null`.
3. **`CoroutineStart.UNDISPATCHED`** in `Actions.kt`. At worst this produces
   an opt-in *warning* on coroutines 1.8.
4. **Private nested types in private companion properties.**
   `MediaShelvesCard.cache` holds `Shelf` and `PlexCard.shelfCache` holds
   `ShelfLoad`. This should be legal (same effective visibility). If the
   compiler objects, make those data classes `internal`.
5. **`rememberUpdatedState(::commit)`** on a local function, then calling the
   delegated function-typed value (`BubbleLightCard.kt`, `LightGroupCard.kt`).
   If this doesn't resolve, wrap it: `rememberUpdatedState { f: Float -> commit(f) }`.
6. **`ConcurrentHashMap.getOrPut`** in `HaClient.cell`. This relies on the
   stdlib `ConcurrentMap.getOrPut` extension.
7. **`Modifier.weight` scopes.** Every `weight` sits in a Row or Column scope.
   `TileRow` takes a `RowScope` lambda for exactly this reason. Check any
   "unresolved reference: weight" against that.
8. **Unused imports and variables** (e.g. `muteEntity`, `Color` in
   `VoiceOverlay`) are warnings only.

**Runtime checks** (these compile, but are worth watching):

- Lazy rows have no item keys, to avoid a duplicate-key crash on repeated
  content ids.
- The `ensureAlive` ping expects HA's pong to echo the id, which is standard.

---

## 4. Device test checklist (on the remote)

Install, then walk through these with HA connected unless stated.

**Navigation and keys**

- [ ] Every page's header shows a chevron. Tapping it opens "Go to page",
      which lists the 4 pages and their buttons. Tapping a page goes there
      and the sheet closes.
- [ ] "What do the buttons do?" lists every button, with Tap / Hold / Double
      matching your `dashboard.json`.
- [ ] Hold ☰ for 1.5 s: a "Holding Menu ☰…" bar fills, then the button map
      opens.
- [ ] Tap ☰: IR Mode opens (on release). Tap ☰ again: it closes. In IR Mode
      the D-pad, volume, mute, CH, power, home and back blast IR, and the
      last-key box updates.
- [ ] With a sheet open, press volume: the Sonos changes (not the remote's
      own volume). Press BACK: the sheet closes and the TV does not go back.
- [ ] Hold Light, Curtain, Music or Aircon: the bar fills. When the hold
      fires you feel a tick and the strip says "Hold … · <action>".
- [ ] Double-tap Music: next track, and the strip says so. A single Music
      tap still opens Media (after about 0.3 s).
- [ ] Leave the remote for more than 30 s, then wake it: it lands on Main,
      and any open sheet is gone.

**Feedback**

- [ ] Tap a floorplan bulb: it turns amber immediately.
- [ ] Unplug that light (or pick one that is unavailable): lilac cloud icon,
      and taps do nothing.
- [ ] Turn off HA's WiFi or stop HA: the red banner appears, controls dim,
      and a hardware press shows "Not connected — … wasn't sent".
- [ ] Bind a button to a bogus service in `dashboard.json`: pressing it shows
      "Home Assistant refused …".
- [ ] Check whether the remote vibrates on taps (`adb shell dumpsys
      vibrator`). If it has no motor, the pressed tone and the spinner are
      the only tap feedback.

**Main**

- [ ] Lock: tapping Unlock says "Hold Unlock to open the door". Holding it
      unlocks. Lock is a tap. Hold the padlock to toggle keep-unlocked (it
      turns red and says "held open").
- [ ] Now-playing strip: tap mutes the Club and every grouped speaker, and
      the badge turns red at once. Paused music reads "Paused: …".
- [ ] Floorplan photo is darker than before; radar dots and the vacuum are
      still in place. Tapping the robot opens the vacuum sheet. Room buttons
      need a hold.
- [ ] Weather row shows icons (no emoji). Next event and next alarm read
      "Tue 7:05am".

**TV**

- [ ] With nothing playing, one line "Nothing playing on the TV" and posters
      on the first screen.
- [ ] With something playing, the big poster and title as before.
- [ ] Tap a poster with the TV off: the strip goes "Starting… → Waking the
      TV… → Opening Plex…" and the poster spins. A second tap says "Already
      starting".
- [ ] Leave and come back: posters appear instantly (cached).

**Media**

- [ ] Player / Media tabs switch the stack. Shelves sit at the top with no
      blank band. Tapping a cover spins it, then "Playing …".
- [ ] Speakers: Join / Leave responds instantly. Mute shows the red
      struck-through state. An unavailable speaker says so in lilac.

**Climate**

- [ ] Press + three times quickly: 28 → 29.5 in blue with a spinner, then
      one call, and HA settles on 29.5.
- [ ] Turn off: setpoint greys, "Off", no chip lit. Power turns it back on.
      All HVAC modes are visible.
- [ ] Blinds: up / stop / down work (Bed stays inverted), and the state reads
      "Opening…" / "N% open".

**Alarm** (trigger `input_boolean.work_alarm_ringing`)

- [ ] The popup wakes the screen. Press OK (or Light): it snoozes and the
      strip says "Snoozed".
- [ ] The snoozed view shows a draining ring and no animated ripple.
- [ ] "Hold to stop" needs about 1 s. A quick tap shows the hint.

**Performance**

- [ ] With people moving in front of the radars, scroll Main and tap
      things: no stutter compared with v1.1.0.
- [ ] Swipe the shelves back and forth: covers don't reload.
