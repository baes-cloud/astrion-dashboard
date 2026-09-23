# Astrion Custom — UI audit, September 2026

Read-only audit of `main` at `c13e114` (v1.1.0 plus screenshot commits), dated 2026-09-23.
It covers everything under `app/src/main/java/`, the compiled default layout
(`config/DashboardConfig.kt`) and every image in `screenshots/`. It also checks the
2026-08-17 critique (`docs/UI_CRITIQUE.md`) item by item.

**Evidence rules.** Claims about the look come from the screenshots, named inline. Claims
marked *(code)* come from reading source only. Two screenshots are older than v1.1.0:
`light-control.png` and `robovac-control.png` still show the removed page dots and the old
header. I use them only for the Light dialog and the Vacuum popup. I did not have the live
`/sdcard/astrion/dashboard.json`, so the information architecture below is the compiled
default: TV / Main / Media / Climate. The prior audit's Lights page no longer exists in it.

Device constraints assumed throughout: 480×800 at 220 dpi override (349×582 dp), MT6580,
1 GB, Android 8.1, held in one hand, dim room, glance-and-fix use, cold arrival after the
6-minute sleep.

---

## 1. Verdict

**What's strong.** v1.1.0 fixed a lot of the prior report, and more carefully than most
teams would:

- There is now a vocabulary for "unavailable":
  - cover, climate, lock, speaker and media cards gate their taps and say the word;
  - the floorplan swaps a missing light for a `HelpOutline` glyph.
- The connection banner overlays the page instead of pushing it down.
- Bitmap decoding for the grid and the floorplan is off-thread and downsampled.
- The lock card and the alarm popup are the best-designed things in the app:
  - the lock's live side is inert;
  - the alarm is hold-to-stop, with a fixed hint slot so nothing jumps.
- Main (`main.png`) is a real glance screen: date, weather, next event, next alarm, lock,
  a live floorplan and what's playing, all on one screen with no scrolling.

**Biggest problem.** The design system was built but not adopted, and the performance fix
was declared but not delivered:

- **`AstrionCard` has zero callers.**
- **The four type tokens have 12 uses against 138 literal `fontSize`s.**
- **192 `Color(0x…)` literals remain outside `Theme.kt`.** About 30 of them duplicate an
  existing token exactly. One is the old failing blue `0xFF4C6EF5`, still in
  `VacuumCard.kt:248`.

On performance: `CardContext.entity(id)` still reads the whole-map `State`. So every card
on the visible page that reads any entity recomposes on every entity publish. The client
publishes up to about 8 times a second (`HaClient.kt:56`, `PUBLISH_INTERVAL_MS = 120`),
and Main is exactly the page where radar dots move. The comment in `Card.kt:49–62` says
the opposite.

On UX, the biggest new gap: the page dots were removed while pager swipe stays disabled.
**The touchscreen now has no way to change page.** A guest who doesn't know what the four
shortcut buttons do has no route off the page they landed on.

---

## 2. Scorecard

| Area | Score | Justification |
|---|---|---|
| Visuals | **7/10** | Coherent dark-teal palette with clear amber-for-on. `main.png` and `alarm-ringing.png` are genuinely good. Weaknesses: card edges at 1.14:1, blank bands on the Media shelves, a bright photo floorplan in a dark room, and a Climate card that looks "on" while off. |
| Consistency | **5/10** | Tokens exist but are bypassed: 20 distinct font sizes, 18 corner radii, 192 colour literals. The shared scaffold is unused, and each card draws its own container. |
| Function / UX | **6/10** | Unavailable handling, the lock and the alarm are big wins. Against that: no touch navigation, no pending or failure state, climate stepper taps that don't add up, three popups that are `Dialog`s (they take the hardware keys away), and no hardware snooze. |
| Accessibility | **5/10** | Body text contrast is excellent (11.3:1). But the new "Unavailable" label renders at **1.70:1**, the actionable "Join" at 2.59:1, and Plex captions at 10–11sp. Several fixed-height boxes have no room for larger text, and floorplan icons announce raw entity ids. |
| Performance | **5/10** | Real bitmap wins. Undone by page-wide recomposition at up to about 8 Hz on Main, full-resolution album-art decodes (about 30 per shelf page, with no cache), a poster cache smaller than the poster count, and a README that installs the **debug** APK. |

---

## 3. Status of the prior critique (2026-08-17)

**Totals: 9 fixed · 12 partial · 5 outstanding**, plus 1 declined by the owner, 1 overtaken
and 1 I can't verify. Within the prior report's own grouping: of its top 10 issues, 2 are
fixed, 7 partial and 1 outstanding.

| # | Prior recommendation | Status | Evidence now |
|---|---|---|---|
| 1 | Wire `isUnavailable`; dim, label and gate | **Partial** | Done in `TileCards.kt:55`, `ClimateCard.kt:93`, `LockCard.kt:74`, `SpeakerGroupCard.kt:131`, `MediaPlayerCard.kt:78`. Floorplan shows `HelpOutline` (`PictureElementsCard.kt:167–188`) **but still toggles on tap** (`:206–208`, no `enabled`). The label is dimmed with its card, so it reads at 1.70:1 (F-A1). The full media card shows a raw friendly name, not the word (`tv-plex-rows.png`). |
| 2 | First key after sleep is swallowed | **Partial** | Cold-arrival reset to `startPage` after 30 s away (`MainActivity.kt:130, 314–317`). Arrival is now predictable, but TV / Media / Climate still take two presses from cold. |
| 3 | No feedback between tap and HA | **Partial** | `Modifier.tap` adds a haptic tick (`CardKit.kt:158`). The most-tapped control, the floorplan bulb, uses raw `detectTapGestures` and gets no tick (`PictureElementsCard.kt:199`). There are no pending or failure states; `callService` is fire-and-forget (`HaClient.kt:116`). It is not verified that the HA100 has a vibration motor at all (F-C4). |
| 4 | Main can't show whether music is playing; whole-row hidden toggle | **Partial** | Compact media gained a play ring and play/pause (`MediaPlayerCard.kt:204–234`), but Main no longer uses it. The new `now_playing` strip shows no play state: a **paused** track still reads "Now playing: …" (`InfoLineCards.kt:313`). The whole row is again a hidden toggle, now mute (`:343`), though with a visible state badge. |
| 5 | Fifth scene invisible | **Fixed** | `SceneGridCard.kt:120–123` fits up to 5, else 4.5 with a peek. The Lights page is gone from the default config. |
| 6 | Sub-48dp targets | **Partial** | Colour dots use a 44dp `TouchTarget` (`BubbleLightCard.kt:271`), zone toggle is 52×32, the speaker slider is gone. New small targets: lock chips 58×**30** (`LockCard.kt:213–214`), swipe-stack dots 22dp (`SwipeStackCard.kt:343`), now-playing strip about 34dp tall, vacuum icon 30dp, "Hide until it rings again" about 31dp. |
| 7 | Slider tap commits; left edge turns light off | **Partial** | Floor at 5% in `BubbleLightCard.kt:102`. Tap still commits (`:179–183`). `LightDetailDialog.kt` (now the floorplan's long-press) has no floor: a tap at the bottom turns the light off (`:71–76` region). |
| 8 | Physical buttons and long-presses undiscoverable; CURTAIN mismatch | **Outstanding** | No help overlay. Hardware long-press fires with no haptic and nothing on screen (`MainActivity.kt:688–693`). CURTAIN now opens the TV page (`DashboardConfig.kt:529`), and the blinds are still behind AC. |
| 9 | `CardContext` unstable, rebuilt per frame | **Partial** | Now `@Stable`, `remember`ed, and holds a `State` (`Card.kt:355`, `Dashboard.kt:58`). But `entity(id)` reads `entitiesState.value`, the whole map, so the claimed narrow subscription doesn't happen (F-P1). |
| 10 | `ButtonGridCard` decodes on the composition thread; floorplan not downsampled | **Fixed** | `rememberSampledBitmap` (`Bitmaps.kt`), used at `ButtonGridCard.kt:118` and `PictureElementsCard.kt:92`. |
| QW1 | Debug "Unmapped key" toast | **Fixed** | `DEBUG_KEYS = BuildConfig.DEBUG` (`MainActivity.kt:100`). |
| QW2 | `Fan_only` / `Partlycloudy` | **Fixed** | `humanise()` and `weatherLabel()` in `Theme.kt:86–104`. `climate.png` shows "Fan only"; `main.png` shows "Partly cloudy". |
| QW3 | Show fifth scene | **Fixed** | See 5. |
| QW4 | Floor the slider tap | **Partial** | Floored in `BubbleLightCard`, not in `LightDetailDialog`. |
| QW5 | Haptics in the shared click path | **Partial** | In `tap` / `tapAndHold`; missing on the floorplan icons and Plex status flow; hardware keys have none. |
| QW6 | 44dp hit box for colour dots | **Fixed** | `BubbleLightCard.kt:271`. |
| QW7 | Selected-chip contrast (`4C6EF5` → `3B5BDB`) | **Partial** | Token fixed (`Theme.kt:55`, now 5.67:1). `0xFF4C6EF5` survives in `VacuumCard.kt:248` (vacuum Play, 4.32:1). |
| QW8 | Delete duplicate `floorplan_current.png` | **Can't verify** | Device-side file; not in the repo. |
| QW9 | Swap CURTAIN / AC | **Outstanding** | CURTAIN → TV/Plex, AC → Climate (which holds the blinds). |
| S1 | Real theme object | **Partial** | `AstrionTheme` exists, but 192 literals remain; about 30 exactly duplicate tokens (e.g. `0xFFE6F0F1` in `ClimateCard.kt:111`, `0xFF1B343D` in `MediaPlayerCard.kt:139`, `0xFF6EA8FE` in `MediaPlayerCard.kt:400`). Type roles `display` and `title` are never used. |
| S2 | Shared card scaffold | **Outstanding in practice** | `AstrionCard` (`CardKit.kt:246`) has **0 callers**. Every card still builds its own clip, background and padding. Its KDoc promises "one hairline edge" that was removed (`Theme.kt:41–44`). |
| S3 | In-flight state in `CardContext` | **Outstanding** | No `call()` API and no result tracking. `HaClient.callService` doesn't register a pending id, so HA error results are dropped. |
| S4 | Banner: overlay, loud failures, quiet "Connecting" pill, thread into cards | **Fixed** | `Dashboard.kt:90–101, 186–227`; `ctx.connected` gates taps. Caveat: gated controls don't *look* disabled (F-C2). |
| S5 | Dismissible config notice | **Fixed** | `Dashboard.kt:236–248`. |
| S6 | Content descriptions; colour not the only signal | **Partial** | Many added (media transport, climate, cover, speaker, lock). Non-colour signals added: blinds filled/outlined, link/link-off plus a word, lock icon plus state text. 23 `contentDescription = null` remain, including all four vacuum controls (`VacuumCard.kt:252`), and floorplan icons announce `light.send_nudes`-style ids (`PictureElementsCard.kt:192`). |
| S7 | 48dp-tall tap row for the page dots | **Overtaken, and worse** | Page dots were **removed**; the pager still has `userScrollEnabled = false` (`Dashboard.kt:84`). The touchscreen has no page navigation at all (F-C1). |
| G1 | IR Mode: `toggle_long: true` | **Outstanding (reversed)** | Now a deliberate short tap of ☰ (`DashboardConfig.kt:614`). One stray tap still takes over the D-pad, volume, Home, Back and Power. |
| G2 | 1dp hairline card border | **Declined (documented)** | `Theme.kt:41–44`: tried and rejected as noise. Reasonable, but see F-V2. |
| G3 | Remove the whole-row play/pause toggle | **Fixed then re-used** | Removed on compact media; the new strip makes the whole row a *mute* toggle. Mute is less destructive and its state is visible, so acceptable. |

---

## 4. Findings

Severity is judged for this device and this room. "Quick" means about an hour or less.

### 4.1 Function / UX

**F-C1 · High · The touchscreen has no page navigation.**
- *Evidence:* `Dashboard.kt:78–88`. The page indicator from 1.0.x is gone (compare the old
  `light-control.png` with the current `main.png`) and `userScrollEnabled = false`.
- *Why it matters here:* The old dots were already a poor target. Now there is nothing at
  all. Pages are reachable only through CURTAIN→TV, LIGHT→Main, SCENE→Media and AC→Climate,
  and two of those labels don't describe their page. A guest, or the owner with the remote
  upside-down in the dark, is stuck on whatever page cold-arrival picked.
- *Recommendation:* Make the existing `clock_header` title a 48dp-tall tap target that
  opens a four-row page picker. It is already pinned on every page, and the header is where
  the eye goes for "where am I". Use the in-window overlay pattern, not a `Dialog`. That is
  one composable, and it costs no pixels:
  ```kotlin
  // ClockHeaderCard: wrap the Row
  Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).tap { ctx.openPagePicker() }, …)
  // CardContext gains: val openPagePicker: () -> Unit  (hoisted from Dashboard)
  ```

**F-C2 · High · Disabled controls look live, and on the floorplan they aren't even disabled.**
- *Evidence:*
  - `Modifier.tap(enabled = false)` → `clickable(enabled = false)`, which has **no visual
    effect**. The KDoc at `CardKit.kt:153` wrongly says it "greys the interaction out".
  - With the socket down, every transport button, mode chip, cover button and lock chip
    stays full-colour. Only the banner at the top says anything.
  - The floorplan ignores `ctx.connected` and `elUnavailable` entirely
    (`PictureElementsCard.kt:199–223`).
- *Why it matters:* This is the prior #1 problem moved from entity level to control level.
  A thumb at the bottom of the screen doesn't see a banner at the top. A silent missing
  haptic is not a signal anyone notices.
- *Recommendation:* One modifier, used everywhere `live` is computed:
  ```kotlin
  fun Modifier.liveOrDim(live: Boolean) = if (live) this else this.alpha(0.45f)
  ```
  Apply it per control, not per card; see F-A1 for why. On the floorplan, gate taps with
  `enabled = live && !elUnavailable` and move to `tapAndHold` so the tick comes along too.

**F-C3 · High · Three popups are `Dialog`s, so the hardware buttons stop working while they're open.**
- *Evidence:* `PictureElementsCard.kt:252` (Vacuum), `LightDetailDialog.kt:93` (colour and
  brightness), `MediaBrowser.kt:77`. The codebase itself documents the trap three times
  (`IrModeOverlay.kt:125–128`, `AlarmOverlay.kt:122–123`, `MainActivity.kt:254–256`): a
  `Dialog` owns its own window, so `MainActivity.dispatchKeyEvent` stops seeing keys.
- *Why it matters:* While the colour popup is open, volume keys change the *Android system*
  volume instead of the Sonos, page buttons do nothing, and D-pad presses don't reach the TV.
  These are exactly the moments someone reaches for a physical button mid-task. *(Inferred
  from Android window focus rules; not captured on the device.)*
- *Recommendation:* Extract one `AstrionSheet(onDismiss) { … }` from `IrModeOverlay` (the
  scrim that swallows taps plus the rounded panel). Render it from the card via a
  `CardContext.showOverlay(content)` slot that `MainActivity` hosts in its `Box`. Map BACK
  to dismiss while a sheet is open.

**F-C4 · High (verify first) · The whole feedback strategy rests on a vibration motor nobody has confirmed.**
- *Evidence:* `CardKit.kt:137–143` makes haptics *the* tap confirmation. On API 27,
  `HapticFeedbackType.LongPress` → `View.performHapticFeedback` is a silent no-op when there
  is no vibrator or the system "touch vibration" setting is off. Nothing in the repo
  (`ARCHITECTURE.md` hardware list, `reference/`) says the HA100 has a motor.
- *Why it matters:* If it doesn't, prior #3 is effectively still open: no tick, no pixels.
- *Recommendation:* Check with `adb shell dumpsys vibrator` and
  `settings get system haptic_feedback_enabled`. Whatever the result, add a visual press
  state that outlasts the finger. The scene tile's 5dp sink (`SceneGridCard.kt`) is the
  pattern: a 150 ms scale or brightness pulse in `Modifier.tap`, costing one `Animatable`.

**F-C5 · Med · No pending or failure state for fire-and-forget actions that take seconds.**
- *Evidence:*
  - `HaClient.callService` sends and forgets; HA's `result` with `success: false` is never
    read (`HaClient.kt:116–131`).
  - Actions with multi-second effects give no on-screen acknowledgement: music shelves
    (`MediaShelvesCard.kt:109`), playlists (`ButtonGridCard.kt:95`), vacuum room buttons,
    speaker Join.
  - Tapping an album cover happens on the "Media" side of the swipe stack, where you can't
    even see the player change.
- *Recommendation:*
  - `suspend fun callServiceResult(call): Boolean` that registers a pending id, like
    `browseMedia` already does (`HaClient.kt:163–183`).
  - A `ctx.call(call)` that shows a 2-second bottom toast strip ("Couldn't reach Sonos") on
    failure only. Successes stay silent; the entity change is the confirmation.
  - For shelves and playlists, hold a per-tile "Starting…" overlay until `media_title`
    changes or 6 s pass.

**F-C6 · Med · Climate steppers drop taps; the "off" aircon looks on.**
- *Evidence:*
  - `ClimateCard.kt:134–160`: each tap sends `target ± step` from *HA's* last-echoed
    target. Three fast taps before HA answers all send the same value (+0.5 once, not +1.5).
  - `climate.png`: the unit is off (red power glyph), but a 44sp white **28°** dominates the
    card. The brightest element on the page is the selected fan speed "Auto" in solid blue.
  - `modes.take(4)` (`:170`) silently drops any mode after the fourth. The screenshot shows
    Cool / Heat / Fan only / Dry with none selected, and no "Off" or "Auto" chip.
- *Why it matters:* "Is the aircon on?" is a glance question, and this card answers it
  wrongly at a glance.
- *Recommendation:* Optimistic, debounced setpoint:
  ```kotlin
  var pending by remember(target) { mutableStateOf<Double?>(null) }
  val shown = pending ?: target
  LaunchedEffect(pending) { pending?.let { delay(600); setTemp(it) } }
  Stepper(…) { shown?.let { pending = it + step } }
  ```
  When `isOff`, render the setpoint in `textMuted` with "Off" in place of "Now 20.1°", and
  drop the fan-row highlight. Lay the modes out in two rows of three, or filter `off`
  (power has its own button), instead of `take(4)`.

**F-C7 · Med · The alarm can't be snoozed from the physical buttons.**
- *Evidence:* While `AlarmOverlay` is up, `dispatchKeyEvent` routes keys as normal
  (`MainActivity.kt:620–733`). CENTER sends DPAD_CENTER to the TV; volume changes the Sonos.
- *Why it matters:* At 7am the remote is in the hand and the thumb is on the D-pad. This
  device's whole point is buttons.
- *Recommendation:* While `alarm.ringing`, make CENTER and all four shortcut keys fire
  `onSnooze()` and swallow the event. Never map *stop* to a key; hold-to-stop on glass is
  right. About 8 lines at the top of `dispatchKeyEvent`.

**F-C8 · Med · Front door unlocks on a single 30dp-tall tap.**
- *Evidence:* `LockCard.kt:195–198, 213–214`. The inert live side is a good idea, but
  "Unlock" is a 58×30 chip that fires immediately.
- *Why it matters:* This is the one control here with a real-world security cost, on a
  device that lives on a sofa arm and gets picked up by anyone. The alarm popup already has
  the right primitive: hold with a fill (`AlarmOverlay.kt:409–475`).
- *Recommendation:* Hold-to-unlock with a 600 ms fill inside the chip; keep Lock as a tap.
  Raise the chips to `height(40.dp)` and leave the visual pill at 30 with a
  `TouchTarget`-style wrapper.

**F-C9 · Med · Idle TV page: a raw entity name is the hero, and useful content is below the fold.**
- *Evidence:* `tv-plex-rows.png` shows "Plex (Plex for Android (TV) - TV)" in 20sp bold
  with "—" under it. When nothing plays, the Plex client entity is `unavailable`
  (`PlexCard.kt:63–66` says so), and `MediaPlayerCard.kt:100–102` falls back to
  `friendlyName`. The full card is about 470dp tall (`tv-plex.png`), so On Deck starts at
  y≈540px.
- *Recommendation:* In `MediaPlayerCard`, when `unavailable || state in (off, idle)` and
  `show_controls == false`, collapse to a one-line `InfoLine("TV idle")`. The poster rows
  then start on the first screen. Separately, "On Deck" and "Continue Watching" show the
  same first three items (`tv-plex-rows.png`); drop one.

**F-C10 · Med · Plex cold-start feedback appears off-screen and isn't debounced.**
- *Evidence:* `PlexCard.kt:217–218` inserts the status line at the *top* of the Plex column.
  Tap a poster in row 3 or 4 and the "Waking the TV…" line is scrolled out of view, and its
  arrival shifts every row by about 20dp. A second tap during the 5–25 s wake launches a
  second sequence; nothing checks `status != null`.
- *Recommendation:*
  - Render the status as an overlay on the tapped poster (a scrim plus a spinner), or as a
    fixed bottom strip.
  - `if (status != null) return` at the top of `play()`.

**F-C11 · Med · Hardware long-press is still invisible, and there are three hold lengths.**
- *Evidence:*
  - Hardware hold is 1.5 s with no haptic and nothing on screen when it fires
    (`MainActivity.kt:104, 688–693`).
  - On-screen long-press is the platform default, about 500 ms.
  - Alarm stop is 900 ms (`AlarmOverlay.kt:477`).
  - The config comment says "~500ms hold" (`DashboardConfig.kt:563`).
- *Recommendation:* When a long hotkey fires, call
  `window.decorView.performHapticFeedback(LONG_PRESS)` and show a 1.5 s bottom chip with the
  action name ("Blinds opening"). Consider 900 ms for hardware holds to match the alarm.
  Then do the prior #8 help sheet (generated from `hotkeys` / `longHotkeys` /
  `doubleHotkeys`), opened from the page picker in F-C1.

**F-C12 · Low · Vacuum room buttons start cleaning on one tap, with no labels for the controls.**
- *Evidence:* `VacuumCard.kt:117–126`; `robovac-control.png`. The four round controls have
  `contentDescription = null` (`:252`).
- *Recommendation:* Hold-to-start for room buttons (the same primitive as F-C8), or at least
  a 3-second "Cleaning Kitchen · Undo" strip that sends `return_to_base` if tapped.

**F-C13 · Low · The `MUTE` hotkey sends `HOME` to the TV.**
- *Evidence:* `DashboardConfig.kt:518` `tvKey("MUTE", "HOME")` plus `astrion.toggle_mute`.
  The comment only describes the speaker mute.
- *Recommendation:* Check this is intended. If it is, say why in the comment. Pressing 🔇
  and having the TV jump to its home screen reads as a bug.

### 4.2 Performance on the MT6580

**F-P1 · High · Every entity-reading card on the visible page recomposes on every publish (up to about 8 Hz).**
- *Evidence:*
  - `Card.kt:365`: `fun entity(id) = entitiesState.value[id]` reads the whole-map `State`.
  - `HaClient.kt:329–335` publishes a fresh `HashMap` every 120 ms whenever *any* entity in
    HA changed, not just those on screen.
  - On Main, `clock_header`, `clock_weather`, `next_up`, `lock`, `picture_elements` (15
    icons plus 9 radar dots plus the vacuum) and `now_playing` all read entities, so all six
    recompose on every publish.
  - Each pass also allocates: `NextUpCard.nextAlarm` builds 3 `SimpleDateFormat`s and parses
    `OffsetDateTime`s (`InfoLineCards.kt:471–484`); `nextCalendarLine` builds 3 more
    (`ClockWeatherCard.kt:566–574`); the weather row re-lays out colour emoji.
  - The `RadarDot` isolation (`PictureElementsCard.kt:423–426`) can't help, because its
    parent reads the map at `:162`.
  - Minor side note: with foundation 1.7 the pager composes only the visible page, so the
    `Card.kt:54–56` claim about recomposing "the three pages you cannot see" is out of date.
    The cost is concentrated on whichever page is showing.
- *Why it matters:* Main is `startPage` and the most-viewed screen. Any busy sensor anywhere
  in the house (power meters, radar) keeps it recomposing all the time. That is dropped
  frames on the one animation that matters, the vacuum rock and taps, and battery drain on
  a device that stays awake.
- *Recommendation:* Per-entity state holders, so reading one entity subscribes to one entity:
  ```kotlin
  // HaClient
  private val cells = ConcurrentHashMap<String, MutableState<EntityState?>>()
  fun cell(id: String): State<EntityState?> = cells.getOrPut(id) { mutableStateOf(entityStore[id]) }
  // publisher: for each dirty id → cells[id]?.value = entityStore[id]   (on Main thread)
  // CardContext
  fun entity(id: String): EntityState? = client.cell(id).value
  ```
  Keep `entities` for the few genuine whole-map readers (the alarm watcher). As a stop-gap:
  `val e by remember(id) { derivedStateOf { ctx.entities[id] } }` in the six Main cards,
  and hoist the `SimpleDateFormat`s into `remember`.

**F-P2 · High · Album art and shelf covers are decoded at full resolution with no cache.**
- *Evidence:* `HaClient.fetchBitmap` → `BitmapFactory.decodeByteArray(bytes)` with no
  options (`HaClient.kt:150`). Callers:
  - `MediaShelvesCard.Tile` (`:132–135`): up to 3 rows × 10 covers. Spotify art is
    typically 640×640 → 1.6 MB ARGB each, for an 85dp (117px) tile.
  - `MediaPlayerCard` (`:119`) and the vacuum map (`VacuumCard.kt:113–116`), which is also
    re-rotated on the main-thread side.
  - Tiles use `remember`, so `LazyRow` disposal re-downloads and re-decodes on every scroll
    back.
- *Why it matters:* About 30 × 1.6 MB ≈ 48 MB transient on a 1 GB device, with a heap limit
  likely in the 128–192 MB range *(inferred)*. The result is GC pauses mid-swipe, and a real
  chance of OOM once the Plex poster cache (F-P3) is also resident.
- *Recommendation:* Give `fetchBitmap(path, targetPx)` the same bounds-then-`inSampleSize`
  logic as `decodeSampled`, plus `inPreferredConfig = RGB_565` for opaque art. Add a shared
  byte-sized LRU (`LruCache<String, Bitmap>(8 * 1024 * 1024)`, `sizeOf = byteCount`) used by
  shelves, posters and art.

**F-P3 · Med · Plex poster cache holds 48 but the page shows 75, so it thrashes.**
- *Evidence:* `PlexCard.kt:120–121`, a 48-entry LRU. 5 rows × `limit` 15 = 75 posters
  (`DashboardConfig.kt:175`). Posters are requested at 200×300 (`:309`) for a
  92×132dp = 126×181px tile, so 240 KB each against about 90 KB needed.
- *Recommendation:* Request `width=128&height=184` and size the cache in bytes (F-P2). Drop
  the duplicated row (F-C9); that saves 15 fetches.

**F-P4 · Med · Page content reloads on every visit; Main's weather pops in on every cold arrival.**
- *Evidence:* The pager composes only the current page (foundation 1.7 default), so all of
  these start empty again each time:
  - `ClockWeatherCard`'s `forecast` (`:99–107`);
  - `PlexCard`'s shelves ("Plex — loading…", `:220`);
  - `MediaShelvesCard` ("Loading…").
  Cold arrival navigates to Main (`MainActivity.kt:316`), so the forecast columns and today
  bar appear after a websocket round-trip. Main's `pin: fill` floorplan then resizes under
  your eyes. *(Inferred from code; I didn't capture the transition.)*
- *Recommendation:* Keep fetched data in app scope with a TTL: a `ForecastRepo` or
  `PlexRepo` object keyed by entity or host, read with `collectAsState`. The alternative,
  `beyondViewportPageCount = 3`, keeps all four pages alive, which costs RAM this device
  doesn't have. Prefer the repo.

**F-P5 · Med · The documented install is a debug build, without R8.**
- *Evidence:* `README.md` steps 3–4 (`assembleDebug`, `app-debug.apk`). `app/build.gradle.kts`
  has `isMinifyEnabled = false` even for release, and pulls in
  `material-icons-extended` (thousands of icon classes).
- *Why it matters:* Debug Compose is noticeably slower (debuggable ART and extra checks), and
  an unshrunk extended-icons dex slows the MT6580's first launch and class loading. On this
  SoC that is a UX issue, not only a build one.
- *Recommendation:* `isMinifyEnabled = true`, `isShrinkResources = true`, a release signing
  config, and `androidx.profileinstaller` with a baseline profile covering Main. Update the
  README to `assembleRelease`.

**F-P6 · Low · Synchronous config reload on every resume.**
- *Evidence:* `MainActivity.kt:310, 327–331`: `DashboardLoader.load()` reads and parses JSON
  on the main thread in `onResume`, i.e. in the wake path. It produces a new `AppConfig`
  instance, so the whole tree recomposes once.
- *Recommendation:* Check the file's `lastModified()` first and skip the parse when it
  hasn't changed.

**F-P7 · Low · The alarm ripple keeps animating while snoozed.**
- *Evidence:* `AlarmOverlay.kt:252–258` runs `rememberInfiniteTransition` whatever
  `ringing` is; `drawBehind` just returns early. That redraws every frame for the whole
  snooze while the screen is on.
- *Recommendation:* `if (!ringing) { StaticGlyph(); return }` before creating the
  transition.

### 4.3 Visuals

**F-V1 · Med · The floorplan is the brightest thing in the room.**
- *Evidence:* `main.png`. The photoreal render's white bed, tiles and bathroom fixtures sit
  at near-white luminance, in the middle of an otherwise dark-teal UI, and cover about 60%
  of the screen.
- *Why it matters:* In the dark it is a light source, and it competes with the amber "on"
  bulbs, the one signal that should pop.
- *Recommendation:*
  ```kotlin
  Image(…, colorFilter = ColorFilter.tint(Color(0xFF8A9499), BlendMode.Multiply))
  ```
  Roughly halves the luminance and keeps amber-on at full strength, since icons draw above
  the image. Or ship a pre-darkened PNG, which costs nothing at runtime.

**F-V2 · Med · Cards barely separate from the page.**
- *Evidence:* card to page 1.14:1 (`1B343D` / `122A32`), `cardBgAlt` to page 1.21:1,
  `cardBgAlt` to `cardBg` 1.06:1. The lock's segmented track (`controlSunken` on `cardBgAlt`)
  is 1.14:1 (all computed). In `climate.png` and `sonos-group.png` the cards read, but
  mostly from the 10dp gaps. The hairline was tried and rejected (`Theme.kt:41–44`).
- *Recommendation:* Raise the fill instead of stroking: `cardBg = 0xFF203D47` gives about
  1.3:1 against the page. Merge `cardBgAlt` into it, since no one can see a 1.06:1
  difference. That also removes a token no card needs.

**F-V3 · Low–Med · The Media shelves page has dead bands.**
- *Evidence:* `sonos-media.png`: about 50px of blank above "Spotify Albums" and about 60px
  below the last row. `SwipeStackCard` fixes `height = 420` (`DashboardConfig.kt:384`) and
  `HorizontalPager` centres shorter children vertically by default.
- *Recommendation:* `HorizontalPager(verticalAlignment = Alignment.Top, …)` at
  `SwipeStackCard.kt:360`. That one parameter pins the shelves to the top.

**F-V4 · Low · Iconography mismatches.**
- *Evidence:* `sonos-group.png`:
  - Bedroom speaker uses a **light bulb** (`"lamp" → Icons.Filled.Lightbulb`,
    `SpeakerGroupCard.kt:116`), which on this app means "light".
  - Bathroom uses `PhoneBluetoothSpeaker` (reads as a phone).
  - The mute button shows `VolumeOff` in both states, so it's unclear whether it's a state
    or an action.
  - Weather uses colour emoji (`ClockWeatherCard.kt:533–546`). On 8.1 these are the 2017
    Noto bitmaps: bright, saturated, inconsistent with the monochrome Material glyphs
    everywhere else.
- *Recommendation:* `Speaker` / `SpeakerGroup` variants only on the speaker card; mute shows
  `VolumeUp` when live and `VolumeOff` (red) when muted. Consider Material `WbSunny` /
  `Cloud` / `Grain` icons tinted `textSecondary` for weather; they also cost less to draw
  than emoji text layout.

**F-V5 · Low · Date and time formats disagree on one line.**
- *Evidence:* `main.png`: "Next event: **Wed 8:30** CTH" against "Next alarm: **7:20 Wed**".
  Neither has AM/PM (`InfoLineCards.kt:483–484`, `ClockWeatherCard.kt:576–577`), while the
  header says "8:25 PM".
- *Recommendation:* Day first on both ("Wed 7:20"). Use a small "a"/"p" suffix or 24h. An
  alarm time without am/pm is exactly the ambiguity you don't want at night.

**F-V6 · Low · Page naming drift.**
- *Evidence:* Page `TV`, header "Plex", button CURTAIN. Page `Media`, header "Sonos", swipe
  titles "Player" / "Media", button SCENE (music). The "Media" swipe title on a page called
  Media, under a header called Sonos, is three names for one place.
- *Recommendation:* Header title = page name. Rename swipe titles to "Now playing" /
  "Library".

### 4.4 Style consistency and token discipline

**F-S1 · Med · The design system is declared, not adopted.**

Evidence *(code)*:

| Token or component | Defined | Used | Literal bypasses |
|---|---|---|---|
| `AstrionCard` | `CardKit.kt:246` | **0** | every card builds its own `clip`, `background`, `padding` |
| `TouchTarget` | `CardKit.kt:279` | 1 (`BubbleLightCard.kt:271`) | lock chips, swipe dots, alarm "Hide" |
| Type roles `display` / `title` / `body` / `label` | `Theme.kt:74–77` | 0 / 0 / 1 / 11 | 138 literal `fontSize`s across **20 sizes** (10, 11, 12, 13, 14, 15, 16, 17, 18, 19, 20, 22, 23, 26, 28, 30, 32, 34, 44, 88) |
| Colours | `Theme.kt` | — | **192** `Color(0x…)` outside `Theme.kt`. Top offenders: `AlarmOverlay` 21 (a deliberate local palette, fine), `MediaPlayerCard` 16, `IrModeOverlay` 15, `LightGroupCard` 15, `PictureElementsCard` 14, `VoiceOverlay` 13, `VacuumCard` 13, `ClockWeatherCard` 13 |
| Corner radii | — | — | **18 values**: 3, 4, 5, 8, 10, 11, 12, 14, 15, 16, 17, 18, 20, 22, 24, 30, 32, 90 |

Concrete drift you can see:
- Card corners: 18dp for cover, lock and speaker (`TileCards.kt:84`, `LockCard.kt:109`,
  `SpeakerGroupCard.kt:155`); 20dp for climate, media and vacuum (`ClimateCard.kt:100`,
  `MediaPlayerCard.kt:138`, `VacuumCard.kt:78`); 14dp for the info strips
  (`InfoLineCards.kt:216`).
- Section labels: four copies of the same style with different sizes and colours.
  - `SectionCard.kt:401`: `0xFF9FBAC0`, 12sp.
  - `ButtonGridCard.kt:74`: same.
  - `PlexCard.kt:240` and `MediaShelvesCard.kt:123`: same, each re-declared.
  - `SpeakerGroupCard.kt:97`: `textSecondary`, **13sp**.
  - `SwipeStackCard.kt:326`: `textSecondary`, 12sp.
- Card title size: 15sp (lock, speaker), 16sp (cover), 18sp (climate), 20sp (full media).
- Exact token duplicates written as literals: `0xFFE6F0F1` (= `textPrimary`) at
  `ClimateCard.kt:111`, `MediaPlayerCard.kt:281,377`, `VacuumCard.kt:134`, `MediaBrowser.kt`
  (4×); `0xFF1B343D` (= `cardBg`) at `MediaPlayerCard.kt:139`, `ClockWeatherCard.kt:127`,
  `VacuumCard.kt:79`, `PictureElementsCard.kt:257`, `MediaBrowser.kt:83`; `0xFF6EA8FE`
  (= `accent`) at `MediaPlayerCard.kt:400`, `VacuumCard.kt:198`, `MediaBrowser.kt:107`.

*Why it matters:* Every fix the prior report asked for "in one place" has had to be made in
N places, and some copies were missed: the old blue in Vacuum, no slider floor in the light
dialog, no haptics on the floorplan. On a 349dp screen, 15/16/18sp titles side by side read
as noise, not hierarchy.

*Recommendation:*
1. Migrate the cards to `AstrionCard`, one PR per card. It already covers shape, padding
   and unavailable.
2. Add `AstrionTheme.section` (12sp SemiBold, 1sp tracking, `textSecondary`) and a
   `SectionLabel()` composable, and delete the six copies.
3. Add `AstrionTheme.corner = 18.dp`, `cornerSmall = 12.dp`, `cornerChip = 10.dp`.
4. Enforce it with a Gradle `grep` task, or detekt `ForbiddenPattern` on `Color\(0x` and
   `fontSize = \d+\.sp` outside `ui/`. Allow-list `AlarmOverlay`'s private palette.

**F-S2 · Low · A misleading KDoc and three stacked doc comments.**
- *Evidence:* `CardKit.kt:239` ("one hairline edge", which doesn't exist). `CardKit.kt:153`
  ("greys the interaction out", which it doesn't; F-C2). `ClockWeatherCard.kt:528–560`: three
  consecutive KDoc blocks on one function, two of them describing behaviour it no longer has.
- *Recommendation:* Delete the stale ones. Comments here are unusually good, which is
  exactly why a wrong one misleads.

### 4.5 Accessibility

**F-A1 · High · The "Unavailable" label is dimmed along with its card, down to 1.70:1.**
- *Evidence:* `UnavailableLabel` uses `AstrionTheme.unavailable` `#7B8C96`: 3.76:1 on
  `cardBg`, already under AA for 12–13sp. Then `dimIfUnavailable` applies `alpha(0.4f)` to
  the *whole* card that contains it (`TileCards.kt:83`, `LockCard.kt:106`,
  `ClimateCard.kt:99`, `SpeakerGroupCard.kt:154`). Blended over `pageBg` that is effectively
  `#3C515A` on `#162E36`: **1.70:1** on `cardBg` cards and **1.66:1** on `cardBgAlt` cards
  (cover, lock). Card names drop to 3.12:1.
- *Why it matters:* The word was added because dimming alone reads as "off". At 1.7:1 in a
  dim room the word is gone, and the card is back to looking off.
- *Recommendation:* Dim the *controls*, not the card. Draw the label at full alpha, with an
  icon so it isn't colour alone:
  ```kotlin
  @Composable fun UnavailableLabel(size: TextUnit = AstrionTheme.label) = Row(verticalAlignment = CenterVertically) {
      Icon(Icons.Filled.CloudOff, null, tint = AstrionTheme.textSecondary, modifier = Modifier.size(13.dp))
      Spacer(Modifier.width(4.dp))
      Text("Unavailable", color = AstrionTheme.textSecondary, fontSize = size, fontWeight = FontWeight.Medium)
  }
  ```
  `textSecondary` is 5.64:1. Keep the `unavailable` colour for icons only.

**F-A2 · Med · Actionable text drawn in the "muted" colour.**
- *Evidence:* "Join" (`SpeakerGroupCard.kt:286`) is `textMuted` `#5A7783` on `cardBgAlt`:
  **2.59:1**. In `sonos-group.png` the Join buttons look disabled, even though they are the
  only way to add a speaker. "Master" uses the same styling for a non-interactive chip, so
  action and inert label look identical. Forecast lows (`/6`, `/10`) are 3.44:1 on
  `pinnedTopBg`, and "Now playing: nothing" is 2.59:1 (acceptable for a null state, but
  borderline).
- *Recommendation:* Join → `textSecondary` (5.33:1) with a `controlBg` fill. Master →
  no fill, text only. Reserve `textMuted` for things you never tap.

**F-A3 · Med · Sub-12sp text on the pages you read from the sofa.**
- *Evidence:*
  - Plex poster title 11sp and subtitle **10sp** (`PlexCard.kt:282, 291`), visible as small
    captions in `tv-plex.png`.
  - Forecast day names 11sp (`ClockWeatherCard.kt:364`).
  - "Connecting…" 11sp (`Dashboard.kt:202`).
  - IR emitter status 11sp; "BACK IN" 10sp; vacuum "Cleaning mode" 11sp.
  - At 220 dpi, 10sp is about 14px tall.
- *Recommendation:* `label` (12sp) is the floor. For posters, drop the subtitle line and
  render "S4E2" as a small badge on the poster itself, which also saves height.

**F-A4 · Med · Fixed heights with no room for larger text.**
- *Evidence:*
  - Playlist tiles are `height(56.dp)`, padding 6+6, icon 26, spacer 3, 12sp label ≈ 45dp
    of content in 44dp: **already over by about a dp at 1.0× scale** (`ButtonGridCard.kt:129`,
    `DashboardConfig.kt:437–439`).
  - Lock chips `height(30.dp)` with 12sp; the alarm pills 64dp.
  - Any system font scale above 1.0 (8.1 offers up to 1.3) clips these labels.
- *Recommendation:* Use `heightIn(min = …)` in place of `height(…)` for anything that
  contains text. For the playlist grid, `tile_height` 60 or `icon_size` 24.

**F-A5 · Low · Screen-reader labels are raw ids or missing.**
- *Evidence:*
  - Floorplan icons: `contentDescription = entityId` (`PictureElementsCard.kt:192`), so
    TalkBack reads "light dot send underscore nudes".
  - Vacuum controls have no descriptions at all (`VacuumCard.kt:252`).
  - The now-playing strip's description sits on the badge, not on the whole tappable row.
- *Recommendation:* `"${entity.friendlyName}, ${if (on) "on" else "off"}"`. Add labels
  to the four vacuum controls. Put `Modifier.semantics(mergeDescendants = true)` on the strip.

---

## 5. Prioritised action list

### Quick wins (about an hour or less each), in order

1. **Un-dim the "Unavailable" label** (F-A1). Move `dimIfUnavailable` from the card
   container to the control row; restyle the label to `textSecondary` plus an icon. Four
   files, a few lines each. Fixes a 1.7:1 regression in the headline v1.1.0 feature.
2. **Floorplan taps: gate and tick** (F-C2, prior #1/#3). Replace `pointerInput` with
   `tapAndHold(enabled = ctx.connected && !elUnavailable, …)` at
   `PictureElementsCard.kt:199`.
3. **`liveOrDim(live)` on every gated control** (F-C2). One modifier, applied wherever
   `live` is already computed. Also fix the `tap` KDoc.
4. **Hardware snooze** (F-C7). While ringing, CENTER and shortcut keys → `onSnooze()`.
5. **Climate** (F-C6). Debounced optimistic setpoint, muted setpoint when off, no `take(4)`.
6. **`HorizontalPager(verticalAlignment = Alignment.Top)`** in `SwipeStackCard` (F-V3).
7. **Idle TV hero collapses to one line** (F-C9), and drop the duplicate "Continue
   Watching" row.
8. **Plex: debounce `play()`, and show status on the tapped poster** (F-C10).
9. **Join to `textSecondary`; Bedroom speaker icon; unify "Wed 7:20" formats** (F-A2,
   F-V4, F-V5).
10. **Replace `0xFF4C6EF5` in `VacuumCard.kt:248` with `accentStrong`**, and sweep the ~30
    exact token duplicates (F-S1). Mechanical find-and-replace.
11. **Darken the floorplan** with a Multiply `ColorFilter` (F-V1).
12. **Check the HA100 actually vibrates** (F-C4) with `adb shell dumpsys vibrator`.

### Larger improvements, in order

1. **Per-entity subscriptions** (F-P1). `HaClient.cell(id)` backed by per-id `MutableState`
   and `CardContext.entity()` reading it. That stops Main recomposing at up to about 8 Hz
   and makes the `RadarDot` isolation work as intended. The highest-value engineering item
   in this report.
2. **One sampled, cached image pipeline** (F-P2, F-P3). `fetchBitmap(path, targetPx)` with
   `inSampleSize`, RGB_565 for opaque art, and a byte-sized shared `LruCache`. Smaller Plex
   transcode sizes.
3. **Touch navigation plus a key map** (F-C1, F-C11, prior #8). Header → page-picker sheet;
   the same sheet lists every tap / hold / double binding from config. Plus a haptic and a
   bottom chip when a hardware hold fires.
4. **Replace the three `Dialog`s with an in-window `AstrionSheet`** (F-C3), so hardware keys
   keep working inside popups and BACK closes them.
5. **Action results and failure feedback** (F-C5, prior S3). `callServiceResult` in
   `HaClient`, `ctx.call()` with a failure-only toast strip, and per-tile "Starting…" states
   for shelves, playlists and Plex.
6. **Adopt the design system for real** (F-S1). Migrate cards to `AstrionCard`; add
   `SectionLabel`, corner and type tokens; add a lint gate against `Color(0x` and
   `fontSize = N.sp` outside `ui/`. Merge `cardBgAlt` into a slightly lifted `cardBg`
   (F-V2).
7. **Release build with R8 and a baseline profile** (F-P5), and change the README install
   steps to match.
8. **App-scope caches for forecast, Plex and shelves** (F-P4), so the landing page doesn't
   reflow on every cold arrival.
9. **Hold-to-act for the consequential controls** (F-C8, F-C12). Front-door unlock and
   vacuum room starts, reusing the alarm's `HoldToStop` primitive as a shared
   `HoldButton`.

---

*Method note:* contrast ratios are WCAG 2.x relative luminance, computed from the hex
values in `Theme.kt` and the card files. Alpha-dimmed cases are blended over `pageBg`
`#122A32`. Token and literal counts come from `grep` over `app/src/main/java`, excluding
`ui/Theme.kt`.
