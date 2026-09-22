You are reviewing the UI and UX of a custom Android app. This is a **read-only
design critique** — do not modify any code, config, or files. Produce a written
report only.

## What this app is

`astrion-app` is a hand-built replacement for the stock `com.aiks.HaRemote` app
that ships on an **Astrion HA100** — a physical Home Assistant remote control:
a handheld slab with a small touchscreen on the top half and a full set of
physical buttons below it (D-pad, volume rocker, channel rocker, mute, menu,
mic, plus eight dedicated shortcut buttons: light / curtain / scene / aircon and
four coloured buttons).

It is NOT a phone app and NOT a wall tablet. Assume:

- **Screen: 480x800 portrait, ~3.5", low DPI.** Small and coarse.
- **Hardware: MediaTek MT6580, 1GB RAM, Android 8.1.** Slow. Animations cost.
- **Held one-handed**, thumb reaching up from the bottom, while the other hand
  does something else. The physical buttons sit under the thumb.
- **Used in a dim, often dark living room** (the owner calls it "the Club"),
  frequently at a glance, sometimes mid-conversation, sometimes to fix one
  thing fast (a light is too bright, the music is too loud).
- **Screen sleeps after 6 minutes** and wakes on motion, so the user often
  arrives at whatever page they left, cold, with no memory of context.
- Physical buttons already handle: D-pad → Android TV, volume → speakers,
  page rocker → brightness, four shortcut buttons → page jumps, four coloured
  buttons → launch TV apps. **Long-press (1.5s) on most buttons fires a second,
  different action.** The touchscreen UI must complement this, not duplicate it.

## Where to look

Repo root: the `astrion-app` project root

Read in this order:

1. `README.md`, `ARCHITECTURE.md`, `COMMUNITY.md` — intent, structure, history.
2. `app/src/main/java/com/custom/astrion/ui/Dashboard.kt` — page shell, pager,
   connection banner, page indicator.
3. `app/src/main/java/com/custom/astrion/config/DashboardConfig.kt` — the actual
   default layout: which cards land on which of the four pages (Lights / Main /
   Media / Climate), in what order, with what options. This is the information
   architecture. Read it carefully.
4. `app/src/main/java/com/custom/astrion/cards/Card.kt` — the card contract.
5. Every file in `app/src/main/java/com/custom/astrion/cards/impl/` — 21 cards.
   Read all of them; the inconsistencies between cards are a large part of what
   you are looking for.
6. `app/src/main/java/com/custom/astrion/ir/IrModeOverlay.kt` and
   `voice/VoiceOverlay.kt` — the two modal overlays.
7. `app/src/main/java/com/custom/astrion/MainActivity.kt` — hardware key
   routing, long-press timing, overlay stacking, motion wake.
8. `screenshots/` — rendered stills and GIFs of several cards in use.

## Optional: capture live screenshots from the real device

Two of these remotes are on the network and reachable over ADB. This is the
single highest-value thing you can do — the code does not tell you how it
actually looks at 480x800.

```bash
adb connect 10.0.0.141
adb -s 10.0.0.141:5555 exec-out screencap -p > /tmp/astrion-current.png
```

You can move between the four pages by injecting the shortcut keycodes, then
screenshotting each one:

- `134` → Lights page
- `135` → Main page
- `136` → Media page
- `137` → Climate page

```bash
adb -s 10.0.0.141:5555 shell input keyevent 135
sleep 2
adb -s 10.0.0.141:5555 exec-out screencap -p > /tmp/astrion-main.png
```

Then read the PNGs with the Read tool and judge them as images.

**Do not** inject keycodes `138`–`141` (the coloured buttons) — those launch
apps on a real television in someone's living room. Avoid keycode `132`
(power). Screenshots and page-nav keys only. Do not call any Home Assistant
service, and do not touch the second remote at `10.0.0.113`.

## What to critique

Go well beyond "the padding is inconsistent." Organise your findings around how
this thing actually gets used.

**Information architecture**
- Are the four pages the right four? Is anything on the wrong page?
- Within a page, is the vertical order right — does the most-reached-for control
  sit where the thumb already is?
- The pager has swipe disabled (see the comment in `Dashboard.kt`) because
  horizontal drags inside sliders were being stolen. Pages are reachable only by
  the dots or a physical button. Is that the right trade? What does it cost a
  user who does not know the buttons exist?

**Glanceability and legibility**
- Font sizes and contrast at 480x800 on a dim screen in a dark room.
- Can you tell system state — is the light on? is it playing? — in under a
  second, without reading?
- The palette is hardcoded as raw `0xFF...` literals scattered across many files
  rather than a theme. Find the actual colours, list them, and assess them as a
  system: contrast ratios, how many near-duplicate teals there are, whether
  "on" and "off" states are reliably distinguishable.

**Touch**
- Measure the real `dp` sizes of tap targets. At this screen size, flag anything
  under ~48dp, and say what it is and where.
- Sliders and drag controls held one-handed on a small screen: are they
  reachable, are they accidentally triggerable, is there a way to undo?

**Hidden affordances**
- Long-press (1.5s) actions are invisible in the UI. So are the physical-button
  bindings. How would a second person in the house ever discover them? Is that
  acceptable, and if not what is the cheapest fix?
- The `bubble_light` long-press opens a colour dialog; nothing signals this.

**Feedback and failure**
- What happens between tapping and Home Assistant confirming? Is there optimistic
  state, a spinner, or nothing?
- Entities go `unavailable` regularly in this install. How does each card render
  that? Is it consistent? (It probably is not — say exactly where it differs.)
- The connection banner and the config-notice banner: too loud, too quiet, right?

**Consistency across cards**
- The 21 cards were written over months. Catalogue the drift: spacing, corner
  radius, title treatment, icon usage, how each represents on/off, how each
  handles a long name, how each handles a missing entity.

**Performance as a UX problem**
- On a 1GB MT6580, jank is a design issue. Flag anything expensive: recomposition
  scope, bitmap decoding in `PictureElementsCard`, list rendering, animation.

**Accessibility**
- Contrast ratios against WCAG AA, tap target sizes, content descriptions for
  screen readers, colour as the sole carrier of meaning.

## Deliverable

**Write your report to `docs/UI_CRITIQUE.md`** in this repo, and print it as your
final message as well. Writing that one file is the only exception to the
read-only rule — do not touch anything else.

Write it incrementally rather than saving it all for the end: once you have
finished reading the code and captured your screenshots, write a first version
of the report to disk, then revise it as you refine your findings. That way a
partial result survives if the run is interrupted.

Structure it as:

1. **Verdict** — three to five sentences. What kind of shape is this UI in, and
   what is the one thing most worth fixing?
2. **Top 10 issues, ranked by (impact × how often it bites) ÷ effort.** For each:
   a one-line title, severity (critical / major / minor), the concrete
   `file.kt:line` evidence, why it matters *for this device in this room*, and a
   specific recommended change. Be concrete — "raise to 48dp" not "improve
   spacing".
3. **Quick wins** — anything fixable in under ~20 lines. Separate list.
4. **Structural recommendations** — things worth doing properly: a real theme
   object, a shared card scaffold, a consistent unavailable-state, etc.
5. **What is genuinely good** — be specific and honest here, not decorative.
   This is a personal project the owner is proud of; tell them what to preserve.

Ground every claim in something you actually read or saw. If you are inferring
how something looks rather than having seen it, say so explicitly. Do not pad
the report, and do not invent problems to reach ten — if you only find six real
issues, report six.
