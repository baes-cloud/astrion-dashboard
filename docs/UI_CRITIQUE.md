# Astrion Custom — UI / UX critique

Read-only design review. Grounded in the source at `app/src/main/java/com/custom/astrion/`,
the live config pulled from `/sdcard/astrion/dashboard.json`, and **live screenshots captured
over ADB from the remote at `10.0.0.141`** on 2026-08-17 (all four pages, plus a scrolled
Lights page). Where I am inferring rather than having seen something, I say so.

Device facts established on the running unit, not assumed:

```
wm size    → 480x800
wm density → 200 physical, 220 override   ⇒ scale 1.375  ⇒ 349 x 582 dp logical
floorplan  → /sdcard/astrion/floorplan.png = 1089 x 1047 RGB, 1.4 MB on disk
```

349dp of width is the single most important number in this document. A 48dp target is
**14% of the screen width**. There is no room to be careless with size, and no room for a
control that means something different from the one 8dp next to it.

---

## 1. Verdict

This is a genuinely good dashboard that has been built by accretion, and it now has one
systemic flaw and a lot of drift. The visual language is coherent from three feet away —
dark teal, amber-for-on, rounded pills — and the **text contrast is legitimately good**
(primary text measures 11.3:1, secondary 5.6:1; almost everything passes WCAG AA, which is
rare for a hand-rolled dark theme). The hard problems are not aesthetic. They are that the
UI has **no vocabulary for "I don't know"** — an unavailable light is pixel-identical to a
light that is off, across every card — and **no vocabulary for "I heard you"** — there is
not a single spinner, haptic, or optimistic flash between a tap and Home Assistant
answering. In a dark room, on a glance, those two absences turn a working dashboard into a
guessing game.

The one thing most worth fixing: **`EntityState.isUnavailable` is defined at
`ha/HaModels.kt:52` and used by exactly zero of the 21 cards.** The helper was written and
never wired up. Fixing that one thing, properly, in a shared card scaffold, removes the
worst failure mode on the page you use most.

---

## 2. Top 10 issues

Ranked by (impact × how often it bites) ÷ effort.

---

### 1. An unavailable entity is indistinguishable from an off entity — everywhere

**Severity: critical**

`ha/HaModels.kt:52` defines the helper:

```kotlin
val isUnavailable: Boolean get() = state == "unavailable" || state == "unknown"
```

`grep -rn 'isUnavailable'` across the whole source tree returns **one hit: the definition.**
No card imports it. No card calls it.

The consequence, card by card — five different behaviours for one condition:

| Card | File | What an unavailable entity looks like |
|---|---|---|
| `bubble_light` / `light_zones` | `BubbleLightCard.kt:66` | `e?.isOn == true` → false → **renders exactly as "Off"** |
| `switch` | `TileCards.kt:155` | same — **renders as "Off"** |
| `fan` | `TileCards.kt:108` | same — **renders as "Off"** |
| `cover` | `TileCards.kt:49` | `e?.state ?: "—"` → prints the **raw string `unavailable`** |
| `climate` | `ClimateCard.kt:129` | setpoint shows `—`, and the steppers **silently no-op** (`target?.let`) |
| `monitor` | `MonitorCard.kt:66` | `—`, via its own inline string comparison that ignores the helper |
| `picture_elements` | `PictureElementsCard.kt:124` | dot renders **dark = off** |

**Why it matters here.** The brief says entities go unavailable regularly in this install.
The Lights page is the page the LIGHT button lands on and the one most reached for. You
glance at it in a dark room, see "Accent — Off", and tap the bulb. The service call goes
into the void. Nothing changes. You tap again, harder. There is no state, no error, no
retry — because as far as the UI is concerned nothing happened. The floorplan on the Main
page has the same failure with 11 icons at once: a Zigbee light that dropped off the mesh
is a dark icon, identical to a light you turned off deliberately, so the floorplan actively
lies about the state of the house.

**Recommended change.** Wire the existing helper. In the shared scaffold (see §4), when
`isUnavailable`, render the card at `alpha = 0.4f`, replace the state line with the literal
text `Unavailable`, and gate the click — `.clickable(enabled = !unavailable)`. Three
properties, one place. Do *not* rely on the dimming alone: greyed-out is exactly what "off"
already looks like on this palette, so the word has to be there.

---

### 2. The first physical keypress after the screen sleeps is swallowed

**Severity: critical**

Observed directly, not inferred. My capture session began with the screen asleep
(`screencap` returned a 3.2 KB uniform-black PNG). I then injected the four page keys in
order with 3s between each:

| Injected | Binding in live config | Page actually shown |
|---|---|---|
| `134` LIGHT | → `Lights` | **`Main`** ← wrong |
| `135` CURTAIN | → `Main` | `Main` ✓ |
| `136` SCENE | → `Media` | `Media` ✓ |
| `137` AC | → `Climate` | `Climate` ✓ |

The last three are correct, which rules out a config problem. The first one woke the screen
and **did not navigate**.

I then reproduced it cleanly at the end of the session, after the screen had slept again:

```
press 135 (CURTAIN → Main)  → screenshot md5 9abcbe84… == the previous Lights screenshot, byte-identical
dumpsys power               → mWakefulness=Awake      (i.e. that press only woke it)
press 135 again             → Main page renders
```

Two independent reproductions. With the screen off the Activity is not resumed, so
`dispatchKeyEvent` (`MainActivity.kt:326`) never sees the event — the system consumes it as
a wake. `wakeScreen()` at `MainActivity.kt:424` only acquires a wake lock; there is no path
that replays the key that caused the wake. **Every page button needs two presses from cold.**

**Why it matters here.** The brief describes the dominant usage pattern precisely: the
screen sleeps after 6 minutes, the user arrives cold with no memory of context, and wants
to fix one thing fast. That user's very first action — the deliberate press of the LIGHT
button — does nothing except turn the screen on, leaving them on whatever page they
abandoned. They then have to orient, realise nothing happened, and press again. This tax is
paid on **essentially every cold arrival**, which is most arrivals. Motion-wake helps when
the remote is picked up, but a remote sitting on the arm of the sofa that you reach over and
press is the exact case that fails.

**Recommended change.** In `dispatchKeyEvent`, record the keycode and timestamp of any event
arriving while `!powerManager.isInteractive`; on the next `onResume` within ~1500 ms, replay
it through `keyRouter`. Alternatively, and much cheaper: since the remote already has a
motion sensor, treat any wake as "go to `startPage`" so the cold arrival is at least always
at a known, predictable page rather than a random one. The second option is about six lines
and fixes the "arrives cold with no memory of context" problem as a side effect.

---

### 3. Nothing happens between a tap and Home Assistant answering

**Severity: critical**

Audited across the whole tree. `CircularProgressIndicator` appears **once**, in
`MediaBrowser.kt:106`. `performHapticFeedback` appears **zero times**. No card holds a
pending/in-flight state. `HaClient.callService` is fire-and-forget from the card's point of
view — every card calls it and immediately returns.

The one exception is partial and self-defeating: `BubbleLightCard.kt:78`

```kotlin
var dragLevel by remember(level) { mutableStateOf(level) }
```

This gives the brightness slider optimistic feedback *during* a drag, which is good — but
the `remember(level)` key means the moment HA echoes any state back, the local value is
discarded. And it covers only the slider. The bulb toggle, the colour dots, every scene
tile, every transport button, every mode chip, every cover button has nothing at all.

**Why it matters here.** Round-trip to HA over WiFi on an MT6580 is not instant, and this
device is used *mid-conversation, at a glance*. You tap "Day", the tile sinks 5dp (the nice
3D press at `SceneGridCard.kt:181`) and springs back, and then the screen is static for
however long the script takes. There is no way to distinguish "sent, waiting" from "the
websocket is down and I didn't notice the banner". So you tap it again — and on a
`script.turn_on` that means you fire the scene twice.

**Recommended change.** Cheapest high-value version: add
`LocalHapticFeedback.current.performHapticFeedback(HapticFeedbackType.LongPress)` to the
shared click handler in the scaffold. A physical tick is the right confirmation channel for
a device held in the hand in a dark room — it needs no pixels and no glance. Then, for
scene/script tiles specifically, hold a 400 ms "sent" highlight in local state.

---

### 4. The Main page cannot tell you whether the music is playing

**Severity: major**

`MediaPlayerCard.kt:73` computes it:

```kotlin
val playing = e?.state == "playing"
```

`MediaPlayerCard.kt:116` passes it to the full variant. `MediaPlayerCard.kt:118` does not
pass it to the compact one:

```kotlin
CompactContent(title, artist, art, ::mp)
```

`CompactContent` (`:137`) has no `playing` parameter and draws only two volume buttons
(`:166–167`). Confirmed in my Main-page screenshot: the bottom row shows the Gay FM logo,
"Gay FM", "—", and two speaker icons. **There is no play/pause state on the page the user
lands on.**

Worse, `MediaPlayerCard.kt:148` makes the entire row a play/pause toggle:

```kotlin
.clickable { mp("media_play_pause", emptyArray()) }
```

So a full-width, unlabelled, invisible toggle sits directly above the page-indicator dots —
the only touch navigation there is, given swipe is disabled (issue 8). Reaching for the dots
and landing 20dp high pauses the music, and because there is no play state displayed, **the
UI gives you no indication that it just happened.**

**Why it matters here.** "The music is too loud" is named in the brief as a
fix-one-thing-fast task, and the Main page is `startPage`. The card shows artist as `—` when
absent, which reads as "nothing is playing" even while it is.

**Recommended change.** Two things. (a) Pass `playing` into `CompactContent` and swap one of
the two volume buttons for a play/pause `CircleControl`, or at minimum tint the album-art
ring amber while playing so state is readable without reading. (b) Remove the whole-row
`clickable` at `:148` — it is a hidden destructive affordance sitting on the navigation
strip. Explicit buttons only.

---

### 5. The "Off" scene is invisible — the row is sized for 4 tiles and the config has 5

**Severity: major**

`SceneGridCard.kt:106–108`:

```kotlin
val gap = 8.dp
val tileW = (maxWidth - gap * 3) / 4     // exactly 4 fit
```

The live config (`dashboard.json`, Lights page) defines **five** scenes: Night, White, Day,
Club, **Off** (`script.off`, colour `#33424A`). The row is `horizontalScroll` with no edge
fade, no partial-tile peek, no arrow, no dots.

Confirmed in my Lights-page screenshot: four tiles fill the pinned bottom bar edge to edge —
Night, White, Day, Club — and the right edge is flush. Nothing suggests a fifth exists.

**Why it matters here.** "Everything off" is the single most likely thing you want from a
lighting remote at the end of the night, in the dark, and it is the one scene the user
cannot see. It is discoverable only by dragging a strip that gives no hint it can be
dragged. And because this card is `"pin": "bottom"`, it is also the part of the screen the
thumb naturally rests on — so the affordance is missing exactly where the hand already is.

**Recommended change.** Size for 4.5 tiles — `(maxWidth - gap * 4) / 4.5f` — so the fifth
tile always peeks and the row is self-evidently scrollable. One line. Or, better for this
specific case: five scenes at 349dp is ~62dp each, which is still a fine target, so just
divide by `scenes.size.coerceAtMost(5)` and fit them all.

---

### 6. A cluster of sub-48dp targets, and the smallest ones do the most damage

**Severity: major**

Measured from source. Everything below is an interactive control, in `dp`, on a 349dp-wide
screen:

| Size | Control | File |
|---|---|---|
| **22dp** | light colour preset dots (×3, 8dp apart) | `BubbleLightCard.kt:175` |
| **24dp** | speaker volume slider height | `SpeakerGroupCard.kt:239` |
| **26dp** | zone master on/off toggle (44×26) | `LightZonesCard.kt:91` |
| **24dp** | light-group toggle (40×24) | `LightGroupCard.kt:259` |
| 28dp | brightness slider hit area | `BubbleLightCard.kt:193` |
| 32/34/36dp | light-group + speaker-group controls | `LightGroupCard.kt:149,173,239`, `SpeakerGroupCard.kt:288,307` |
| 40dp | climate off, mode chips, compact volume | `ClimateCard.kt:105,207`, `MediaPlayerCard.kt:166` |
| 42dp | cover / switch leading icon | `TileCards.kt:66,172` |
| 44dp | bulb toggle, cover open/stop/close, media art | `BubbleLightCard.kt:142`, `TileCards.kt:211` |

The 40–44dp band is a near-miss and largely forgivable. The **22–26dp cluster is not**, and
it is stacked with consequence:

- The three 22dp colour dots sit at the **top-right of the light pill**, 8dp apart. That is
  the corner a right thumb reaching up from the bottom passes through. Three 22dp targets in
  a 90dp span, each of which immediately recolours a light, with no undo. Visible in my
  Lights screenshot on the "LED Strips" row.
- The 26dp zone toggle is **the highest-consequence control on the page** — it turns an
  entire room's lights off — and it is the second-smallest thing on it. Confirmed on-screen
  next to "CLUB" and "KITCHEN".

**Why it matters here.** One-handed, thumb from below, dim room, at a glance. A thumb
contact patch is roughly 9–11mm; at 220dpi, 26dp is 5.8mm tall. The user is not aiming.

**Recommended change.** Raise the zone toggle at `LightZonesCard.kt:91` to 52×32dp — it is a
pinned, high-consequence control and deserves to be the *largest* thing in the header row,
not the smallest. Raise the colour dots at `:175` to 20dp visual with a 44dp
`Modifier.size(44.dp)` wrapper and `Alignment.Center` (keep them looking small, make them hit
big) — or move them into the long-press detail dialog where they already exist and delete
them from the pill entirely. Wrap `SpeakerGroupCard.kt:239` to 48dp the same way.

---

### 7. Tapping a brightness slider commits instantly, and tapping its left edge turns the light off

**Severity: major**

`BubbleLightCard.kt:201–207`:

```kotlin
.pointerInput(entityId) {
    detectTapGestures { offset ->
        val frac = (offset.x / size.width).coerceIn(0f, 1f)
        dragLevel = frac
        commit(frac)
    }
}
```

and `commit` at `:84–86`:

```kotlin
val pct = (fraction.coerceIn(0f, 1f) * 100).roundToInt()
if (pct <= 0) {
    ctx.client.callService(ServiceCall("light", "turn_off", entityId))
}
```

A single tap anywhere on the 28dp-tall, full-width bar sets brightness immediately. A tap in
the leftmost ~0.5% of that bar **turns the light off**. There is no undo, no confirm, no
previous-value memory.

**Why it matters here.** The pinned scene bar is at the bottom, the light pills scroll above
it, and the slider spans nearly the full 329dp of usable card width. It is the largest
target on the Lights page and the most consequential per-pixel. Scrolling the list with a
thumb that starts its drag *slightly* horizontally, or a tap that lands on the bar instead
of the row while the list is still settling, changes a light. The author is clearly aware of
adjacent-gesture risk — there is a deliberate 28dp dead-zone gutter at `:192` with a comment
explaining it, and the pager's swipe was disabled for the same reason — but tap-to-commit
was left wide open.

**Recommended change.** Floor the tap at a non-zero value: `if (pct in 1..4) pct = 5`, so a
mis-tap at the left edge dims rather than kills. Then require a small drag threshold before
a *tap* commits, or drop `detectTapGestures` from the slider entirely and let the bulb icon
be the only off switch. The icon is already there, at `:142`, and it already toggles.

---

### 8. The physical buttons lie, and nothing on screen explains them

**Severity: major**

Three overlapping discoverability failures, all invisible:

**(a) Two of the four page buttons go somewhere their label doesn't say.** From the live
config's `hotkeys`:

| Physical button (silkscreened label) | Page it opens |
|---|---|
| LIGHT | Lights ✓ |
| **CURTAIN** | **Main** ✗ |
| **SCENE** | **Media** ✗ |
| AC | Climate ✓ |

The curtain controls are actually on the *Climate* page (three `cover` cards, confirmed in
my screenshot), which is the AC button. So pressing the button with a curtain printed on it
takes you to the clock and floorplan, and the curtains live behind the aircon button.

**(b) Every long-press is invisible.** `MainActivity.kt:74` sets `LONG_PRESS_MS = 1500L`. The
live config binds **eleven** long-press actions — `script.long_lights`, `script.long_curtain`,
`script.long_music`, `script.long_aircon`, `script.long_red/green/blue/yellow`,
`script.open_blinds`, `script.close_blinds`, and play/pause on CENTER. Not one is mentioned
anywhere in the UI. There is also no progress indication during the 1.5s hold and no haptic
when it fires (issue 3), so even a user who knows a long-press exists cannot tell whether
they held it long enough.

**(c) `bubble_light` long-press opens a colour dialog** (`BubbleLightCard.kt:148` →
`LightDetailDialog`), and so does long-pressing a floorplan icon
(`PictureElementsCard.kt:151`). Nothing signals either.

**Why it matters here.** The brief asks how a second person in the house would ever discover
these. The answer is: they cannot. Not by exploration, not by pressing things, not by
reading the screen. And (a) actively misleads someone who reasons from the printed labels —
which is precisely what a guest does.

**Recommended change.** Cheapest fix, and it is genuinely cheap: a **help overlay on
long-press of MENU** (or any unbound key) showing a static two-column list of the physical
buttons and what tap/hold does — generated from `config.hotkeys` and `config.longHotkeys`,
which are already parsed and in memory. That is one composable and a `when` branch, it needs
no new config, and it stays correct automatically when the JSON changes.

Separately: consider swapping the CURTAIN and AC bindings so CURTAIN lands on the page that
has the covers on it. That is a one-line config edit, not a code change.

---

### 9. `CardContext` is unstable and rebuilt every frame, defeating the card-level perf work

**Severity: major (performance)**

`cards/Card.kt:46`:

```kotlin
class CardContext(
    val entities: EntityMap,     // = Map<String, EntityState>
    val client: HaClient,
)
```

A plain `class`, not `@Immutable`, holding a `Map` — which Compose treats as an **unstable**
type regardless of annotation. And `ui/Dashboard.kt:56` constructs a new one on every
recomposition, outside `remember`:

```kotlin
val ctx = CardContext(entities = entities, client = client)
```

So every `state_changed` event from anywhere in the HA instance produces a new, unstable
`CardContext`, which is passed to every card, which means **every card on every page
recomposes** — including the cards on the three pages you cannot see, since `HorizontalPager`
keeps neighbours composed.

This is expensive here specifically because of what is on `startPage`. The Main page holds
`picture_elements` with 11 light icons, a vacuum overlay, and **three radar blocks × 3
targets = 18 position sensors** that update continuously whenever anyone moves through the
room. That is a high-frequency event stream driving a full-tree recomposition on a 2015
MT6580 with 1 GB of RAM.

The frustrating part is that the author already solved this *locally*. `RadarDot` was
deliberately extracted with an explicit comment at `PictureElementsCard.kt:371–374`:

> "Splitting this out prevents coordinate updates of target #1 from forcing target #2, #3, or
> the rest of the floorplan card to recompose."

That reasoning is exactly right, and it is nullified one level up: the isolation only works
if what flows into the child is stable, and `CardContext` never is.

**Recommended change.** Stop passing the whole map. Give `CardContext` a stable shape —
`@Stable class CardContext(private val entities: State<EntityMap>, val client: HaClient)`
with a `fun entity(id: String): EntityState?` accessor — and `remember` it in `Dashboard`
keyed only on `client`. Cards then read individual entities inside their own scope, which is
what `RadarDot` already assumes is happening.

---

### 10. `ButtonGridCard` decodes PNGs on the composition thread

**Severity: major (performance), trivial effort**

`ButtonGridCard.kt:91–95`:

```kotlin
val bitmap = remember(iconPath) {
    iconPath?.let {
        runCatching {
            val f = File(it)
            if (f.exists()) BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() else null
```

Synchronous file I/O plus full-resolution bitmap decode inside `remember`, i.e. on the
composition thread, with no `inSampleSize`. Six of them, on the Media page, at first
composition. Pulled from the device: `disco.png` 18 KB, `house.png` 11 KB, `mos.png` 3 KB,
`paris.png` 16 KB, **`trap.png` 78 KB**, `turntable.png` 23 KB — every one decoded at native
resolution and drawn into a 32dp box (`:112`).

This is a straight inconsistency, not an oversight in isolation: `PictureElementsCard.kt:85`
does the identical job **correctly**, with `produceState` + `Dispatchers.IO` and a comment
saying "Decode off-thread safely".

Related, in that same correct path: the floorplan is decoded with no downsampling either.
Measured on the device it is **1089 × 1047**, which is 1089 × 1047 × 4 = **4.56 MB** resident
in ARGB_8888, held for the life of the card, to be drawn into roughly 460 × 442 px. On a 1 GB
device that is worth reclaiming. (There is also a byte-identical `floorplan_current.png`
sitting next to it that nothing in the config references.)

**Recommended change.** Port the `produceState` + `Dispatchers.IO` pattern from
`PictureElementsCard.kt:85` into `ButtonGridCard`. Add `BitmapFactory.Options.inSampleSize`
to both — computed against the target size, it takes the floorplan from 4.56 MB to ~1.1 MB
at `inSampleSize = 2` with no visible difference at 480px wide.

---

## 3. Quick wins

Each of these is under ~20 lines.

1. **Turn off shipped debug UI.** `MainActivity.kt:70` has `DEBUG_KEYS = true`, and `:366`
   fires `Toast.makeText(this, "Unmapped key: $code", ...)` for any unbound button. The
   POWER key is unbound in the live config, so pressing it pops a toast reading
   `Unmapped key: 132` over the dashboard in a dark room. Set it to `false`, or gate it on
   `BuildConfig.DEBUG`. The comment at `:68` already calls it "Temporary".

2. **Fix the raw HA state strings leaking into the UI.** Confirmed on screen: the Climate
   page shows a mode chip labelled **`Fan_only`** (`ClimateCard.kt:152` —
   `replaceFirstChar { it.uppercase() }` doesn't touch the underscore) and the Main page
   shows **`Partlycloudy`** (`ClockWeatherCard.kt:85`). One shared
   `String.humanise() = replace('_',' ').replaceFirstChar(Char::uppercase)`, applied in both
   places.

3. **Make the fifth scene visible.** `SceneGridCard.kt:108` — see issue 5. One line.

4. **Floor the slider tap.** `BubbleLightCard.kt:84` — see issue 7. Two lines.

5. **Add haptics to the shared click path.** See issue 3. The single highest
   feedback-per-line change available, and the right modality for this device.

6. **Give the 22dp colour dots a 44dp hit box.** `BubbleLightCard.kt:174–178` — wrap in a
   `Box(Modifier.size(44.dp), contentAlignment = Alignment.Center)` and move the `clickable`
   to the wrapper. Looks identical, stops being a coin toss.

7. **Fix the lowest-contrast text in the app.** White on `0xFF4C6EF5` (selected mode chip,
   `ClimateCard.kt:209/215`) measures **4.32:1** at 13sp — the only *real* text-contrast
   failure I found, and it is on the element that carries state. Darkening the chip to
   `#3B5BDB` takes it to 5.6:1.

8. **Reclaim ~1.4 MB of storage**: `/sdcard/astrion/floorplan_current.png` is byte-identical
   to `floorplan.png` and referenced by nothing in `dashboard.json`.

9. **Swap CURTAIN and AC page bindings** in `dashboard.json` so the curtain button lands on
   the page with the covers. Config-only, no rebuild.

---

## 4. Structural recommendations

### A real theme object

The palette is **~70 distinct colour literals** across 22 files, with zero shared constants.
Measured, they are not 70 colours — they are about 12 colours with 70 spellings:

**Dark surfaces — nine near-identical teals doing the same job:**
`0xFF122A32` (page bg), `0xFF0E2229` (pinned top), `0xFF13262D` (pinned bottom),
`0xFF1B343D` (×12 — bubble/media/climate card), `0xFF1E3841` (×10 — cover/switch card),
`0xFF1C3740` (×3), `0xFF14262D` (×2), `0xFF152B33` (slider track), `0xFF0F1E24`.

Contrast between the two main card backgrounds, `1B343D` and `1E3841`, is **1.06:1** — they
are the same colour to any human eye, yet they are two independent constants that split the
card set arbitrarily in half. Likewise `0xFF2C4C58` (×9) and `0xFF2C4D59` (×7) differ by one
value in green and one in blue: **1.01:1**. Two constants, nine and seven uses each,
literally indistinguishable.

**Two different blues both meaning "active":** `0xFF6EA8FE` (×12 — page dot, zone toggle) and
`0xFF4C6EF5` (×3 — selected mode chip, play button). Nothing distinguishes their roles.

**Seven near-whites for body text:** `E6F0F1` (×28), `F3F8F9`, `F2F7F8`, `F2F5FA`, `F1F4FA`,
`F0F2F6`, `E8ECF2`, plus `DCF1F4`.

Extract an `AstrionTheme` object with maybe 14 named entries — `pageBg`, `cardBg`,
`cardBgRaised`, `controlBg`, `textPrimary`, `textSecondary`, `accentActive`, `accentOn`,
`stateOn`, `stateOff`, `stateUnavailable`, `danger`, `sceneLip`, `scrim` — and replace the
literals. This is mechanical, and it is the prerequisite for the next two items.

Worth noting while doing it: **card-to-page-background contrast is only 1.14–1.21:1**. Card
edges are nearly invisible; the layout reads as separated mostly because of the 10dp gaps.
On a dim screen at low brightness that separation will be the first thing to disappear.
Consider a 1dp `0x22FFFFFF` hairline border on the card shape rather than lightening the fill.

### A shared card scaffold

The 21 cards were written over months and the drift is measurable:

| Property | Values in use |
|---|---|
| Corner radius | **14 distinct values**: 4, 5, 10, 12, 13, 14, 16, 17, 18, 20, 22, 24, 30, 90 dp |
| Card background | `1B343D` (bubble/media/climate) vs `1E3841` (cover/switch) vs `2A4954` (grid button) |
| Padding | `14.dp` all, `16.dp` all, `h14/v12`, `h16/v12`, `h12/v6`, `10.dp` |
| Title size | 15, 16, 17, 18, 20 sp |
| Long name | `maxLines=1 + Ellipsis` (BubbleLight, MediaPlayer, ButtonGrid) vs `maxLines=1` **without** overflow (CoverCard `TileCards.kt:80`) vs **neither** (FanCard `:131`, SwitchCard `:181`) — so a long name silently clips on covers and reflows the layout on switches |
| "On" state | amber icon bg (bubble) / blue card bg `2B3A67` (fan) / green-or-configured card bg (switch) / **nothing at all** (cover) |
| Unavailable | five behaviours, see issue 1 |

A single `CardScaffold(title, state, unavailable, onClick) { content }` that owns the shape,
padding, title treatment, overflow, on/off encoding, and the unavailable state would collapse
all six rows of that table and make issue 1 a one-place fix. This is the highest-leverage
refactor available and it is not glamorous.

Typography deserves the same: **16 distinct sizes** are in use (10, 11, 12, 13, 14, 15, 16,
17, 18, 19, 20, 22, 26, 32, 34, 44 sp) with eight of them clustered in the 11–18 band doing
broadly the same two jobs. Four roles — `display` 44, `title` 17, `body` 14, `label` 12 —
would cover essentially everything. Note the 11sp uses in `ClockWeatherCard.kt:117,133` are
the weather condition and temperature on the landing page; at 220dpi that is ~15px tall,
and it looks it in the screenshot.

### A consistent "sent" / "in-flight" state

See issue 3. This belongs in `CardContext` — a `fun call(sc: ServiceCall): Job` that exposes
in-flight state the scaffold can render, rather than each card calling
`ctx.client.callService` and forgetting.

### The two banners: one is too quiet, one is too loud

**The connection banner** (`Dashboard.kt:191–210`) is too quiet in the case that matters and
occupies the wrong space in the case that doesn't.

It returns early when `CONNECTED`, which is right. But when *not* connected it inserts a
full-width 12dp-padded bar **above the pager**, inside the root `Column` — so it pushes the
entire dashboard down by ~44dp, reflowing every page. On a 582dp-tall screen that is 7.5% of
the vertical, taken permanently while HA is unreachable, on top of a layout that already
overflows (the Lights page scrolls, the Climate page is cut off mid-card in my screenshot).

Meanwhile the states it distinguishes are wrong for this device. `CONNECTING` and
`AUTHENTICATING` both render `"Connecting…"` on a muted `0xFF3A506B` — a colour that
disappears against the dark UI at a glance. But "connecting" is the *transient, harmless*
state, and it gets a persistent bar. `AUTH_FAILED` and `ERROR` share the same `0xFF7A2E2E`
dark red, which is the same weight as the harmless one.

The deeper problem is what the banner does **not** do: it does not disable or mark the cards.
While disconnected, every light pill, scene tile and mode chip still looks fully live and
fully interactive, and tapping them still fires service calls into a dead socket with — per
issue 3 — no feedback. The banner is the *only* signal, it is a thin strip at the top of a
582dp screen, and the user is looking at the bottom of the screen where their thumb is.

Recommended: overlay the banner rather than inserting it (so the layout doesn't reflow),
make `ERROR`/`AUTH_FAILED` genuinely loud, drop `CONNECTING` to a small non-reserving
indicator next to the page dots at the bottom where the eye already is, and — most
importantly — thread connection state into `CardContext` so the shared scaffold can render
the same disabled treatment it uses for `isUnavailable`. A dead websocket is just
"everything is unavailable at once", so it should reuse issue 1's fix rather than inventing a
second vocabulary.

**The config notice banner** (`Dashboard.kt:212–222`) has the opposite problem: amber text on
`0xFF4A3B1E` at 12sp, no dismiss, no icon, permanent. It is the right idea — `DashboardLoader`
falling back to the compiled default and *telling* you instead of crashing is good design —
but it is a developer-facing diagnostic given the same permanent screen real estate as a
system-down warning. It fires on a JSON typo, which is a thing you fixed thirty seconds ago
and now cannot clear without restarting the app. Make it dismissible (`var shown by
remember { mutableStateOf(true) }` and a tap to hide), since the config reloads on every
`onResume` anyway and it will simply reappear if the file is still broken.

### Accessibility

`contentDescription = null` appears **24 times**; only six controls have a real one, and four
of those are images rather than controls (`PlexCard.kt:209`, `VacuumCard.kt:151`,
`ButtonGridCard.kt:112`, `PictureElementsCard.kt:140`). Every transport button, every
`CircleBtn`, every scene tile, every toggle is unlabelled. TalkBack on this device would
announce a screen of "button, button, button".

More consequential than the screen-reader case, given who uses this: **colour is the sole
carrier of meaning** in several places. On/off is amber-vs-slate on lights, blue-vs-slate on
chips, and background-tint-only on switches — with no icon change, no text change, and in
the cover card's case no encoding at all. The floorplan (11 icons) is the strongest example,
though credit where due: `PictureElementsCard.kt:132–136` *does* swap
`Icons.Filled.Lightbulb` for `Icons.Outlined.Lightbulb`, which is a real non-colour
signal. That pattern should be the rule everywhere, not the exception in one card.

### The swipe trade-off

`Dashboard.kt:86` sets `userScrollEnabled = false`, with a comment explaining that horizontal
drags meant for sliders were being stolen by the pager. That diagnosis is correct and the
decision is defensible — the sliders are the more important gesture, and mis-paging while
dimming a light is worse than not being able to swipe.

But the cost is under-acknowledged. It leaves **the four 8–10dp dots at
`Dashboard.kt:170–179` as the only touch-reachable navigation in the app.** They are 8dp
(inactive) and 10dp (active), with 5dp horizontal padding — so roughly an 18–20dp hit width
in a 5dp-tall strip. That is the smallest target in the entire UI, it is the only way a
guest who doesn't know about the physical buttons can move between pages, and it sits
directly beneath the compact media card's invisible full-row play/pause toggle (issue 4).

The fix is not to re-enable swipe. It is: (a) give the dots a 48dp-tall touch row while
keeping the dots small — the `Row` already has `padding(vertical = 5.dp)`, so change it to
`.height(48.dp)` with centered content and move the `clickable` to a padded wrapper per dot;
and (b) since the page name is already rendered next to the dots at `:182`, make that label
part of the tappable region too. Both are small changes to one composable, and they make the
one remaining navigation affordance actually hittable.

---

## 5. What is genuinely good

Not decoration — these are things I'd tell you not to touch.

**The floorplan is the best idea in the app.** A top-down plan of the actual flat with lights
where the lights physically are, mmWave presence dots moving live, and the robot vacuum
drawn at its current room. It answers "what is on, and where" spatially, in one glance,
with no reading — which is exactly the right interaction for a glance-and-fix device in a
dark room. It also cannot be built in stock HaRemote at all, which makes it the clearest
justification for the whole project. Three radar blocks with per-sensor colours, an affine
transform with independent left/right X scales, and mm-vs-m unit normalisation
(`PictureElementsCard.kt:331`) is real engineering in service of a design goal.

**The 3D scene tiles.** `SceneGridCard.kt:174–197` — a raised face over a darker "lip", where
pressing sinks the face onto the lip by 5dp via `animateDpAsState`. It is a physical,
legible press affordance that costs one animated `Dp` and reads instantly at arm's length.
It is also, notably, the *only* immediate tap feedback in the app; the fact that it's the
one place that feels responsive is not a coincidence.

**Overlays are rendered in-content rather than as `Dialog`s** — `IrModeOverlay.kt:125–128`
and `VoiceOverlay.kt:61–63` both carry a comment explaining that a `Dialog` owns its own
window and would steal focus from `MainActivity`, breaking `dispatchKeyEvent` and making
hardware interception impossible. That is a subtle, correct, hard-won piece of Android
knowledge, and documenting the *why* inline is exactly right — it stops the next person
"cleaning it up" into a `Dialog` and breaking IR mode.

**The IR Mode overlay is the best-designed screen in the app.** A big orange badge, "IR MODE"
in 19sp bold with 2sp letter-spacing, and the subtitle "Hardware buttons blast to the TV". It
is unmistakable that the remote has changed behaviour — which is the whole job of a modal
mode, and the thing most modal modes get wrong. Every other card could learn from how loudly
this one states its state. (Its one problem is the trigger, not the design: the live config
sets `toggle_long: false` on MENU, so a single stray tap of ☰ drops you into a mode that
hijacks D-pad, volume, power, home and back until you find the on-screen Close. Make it
`"toggle_long": true`.)

**Text contrast is good, and that is not an accident.** Primary text on card 11.28:1,
secondary 5.64:1, zone headers 7.31:1, the amber-on bulb 10.69:1, the off bulb 4.88:1. I
went looking for contrast failures and found essentially one (the selected mode chip, 4.32:1).
Whatever process produced `E6F0F1` on `1B343D` was a good one.

**The perf instincts are right, even where the plumbing undercuts them.** `Modifier.offset`
chosen over padding with a comment saying why (`PictureElementsCard.kt:126`); `produceState`
+ `Dispatchers.IO` for bitmap decode; `RadarDot` extracted specifically to scope
recomposition; the album-art blur implemented as a 32px downscale-and-upscale because
`Modifier.blur` is a no-op on API 26 (`MediaPlayerCard.kt:84–92`). Someone was thinking
about this SoC. Issue 9 is not a failure of instinct — it's one stability bug upstream of
otherwise sound work.

**The gesture-conflict awareness.** The 28dp dead-zone gutter on the brightness slider
(`BubbleLightCard.kt:189–192`) and the disabled pager swipe (`Dashboard.kt:82–86`) are both
responses to a real, observed conflict between page-swipe and in-card drag, and both comments
explain the trade rather than just asserting it. The conclusions are right; issue 7 is a gap
in the same line of thinking, not a contradiction of it.

**The config-over-code architecture is the right call.** `/sdcard/astrion/dashboard.json`
reloaded on every `onResume` (`MainActivity.kt:208–213`) means layout, hotkeys, long-press
bindings, IR buttons and radar calibration are all tunable with an `adb push` and an app
switch — no rebuild, no Gradle, no Android Studio. On hardware this slow, with a
1.5-minute build cycle, that is the difference between a project you iterate on and one you
abandon. `DashboardLoader` falling back to the compiled default and surfacing a notice banner
instead of crashing on bad JSON is the correct failure mode.
