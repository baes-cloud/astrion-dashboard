# Ambient now-playing

A calm, full-screen "now playing" face for when the remote has been idle and
music is playing: the cover large, the text typeset with care, the backdrop in
the cover's own colours. When nothing is playing it shows a rest clock. It
reuses the existing screensaver's idle timer, backlight, key handling and
touch-to-dismiss — it is just a richer idle face, gated on `ambient.enabled`.

## How it fits

- `ui/ambient/AmbientScreen.kt` is hosted by `MainActivity.ScreensaverHost` in
  place of `Screensaver` when `ambient.enabled` is true. Everything about *when*
  idle triggers, the backlight, and dismissal is still the screensaver's.
- `AmbientMachine` decides now-playing vs rest clock vs stand-aside.
  `FlipGuard` absorbs skip-flicker. `scene/SceneBuilder` bakes each track's
  backdrop + cover off the main thread (`Dispatchers.Default`).
- Fonts stay the house system: **Syne** for the title and clock, **Manrope**
  for everything else. No new fonts were bundled (the brief's Jost/DM Sans were
  dropped in favour of the existing design system — decided with Martin).

## Config (`ambient` block in dashboard.json)

Off by default, so the existing screensaver is unchanged until you opt in:

```json
"ambient": {
  "enabled": true,
  "media_players": ["media_player.club", "media_player.the_club_tv"],
  "rest_clock": true,
  "show_album": true,
  "drift": false
}
```

- `media_players` — watched in order; the first one `playing` wins. A paused
  one shows for up to 4 s, then the gap-hold (4 s) keeps the screen through a
  track change before the rest clock.
- `rest_clock` — when nothing plays: `true` shows the minimal Syne clock;
  `false` falls back to the app's existing rich screensaver face (never blank).
- Idle timing is the screensaver's (`screensaver.idle_seconds`, default 45 s).
- `drift` is accepted but the slow pool drift is **not** implemented yet (it was
  marked optional / only-if-it-holds-frame-rate); the key is parsed so a config
  can set it without error.

## Hardware keys while ambient shows

The HA100 has **no dedicated play/pause or next/previous buttons**
(`input/HardwareKeys.kt`), so only these are wired, and they act on the active
player and stay on screen:

- Volume up / down → `media_player.volume_up` / `volume_down`
- Mute (🔇) → `media_player.volume_mute` toggle
- D-pad left / right → `media_player.media_previous_track` / `media_next_track`

Any other key exits to the dashboard. The first touch only dismisses (the
existing `dispatchTouchEvent` swallows it, so it can't hit a dashboard control).

## Tests (JUnit, run on baelap)

Pure-logic units, no device needed:

```bash
~/Documents/claude/astrion-build.sh :app:testDebugUnitTest
```

Covers: Oklab round-trip + neutrality, palette (distinct colours, greyscale →
neutrals, chroma-outranks-size, near-duplicate merge), FlipGuard timing, player
selection, and the idle state machine.

## On-device checks — UNVERIFIED

These targets from the brief have **not** been measured (no device in this
session; the .113 unit is running another branch). Run them on the HA100:

- **Per-track bake < 150 ms** — MET, measured on the HA100 (10.0.0.141,
  release, AOT-compiled): **~104 ms** total (`decode=32ms palette=30ms
  backdrop=41ms`). It is logged with that breakdown; watch it:
  ```bash
  adb logcat -s Ambient:I
  ```
  Two things got it there from an initial ~1400 ms:
  1. The palette work (Oklab + k-means over the analysis bitmap) was ~95% of
     the cost. Fixed by: a 256-entry sRGB→linear lookup table, a real
     early-stop in k-means (the convergence check was a no-op), squared
     distance in the nearest-centre loops (no sqrt), converting each pixel
     once and reusing it for both palette and pool placement, and a 40px
     (not 96px) analysis bitmap.
  2. The **first** bake was interpreted, not compiled — ~1400 ms cold vs
     ~100 ms once JIT-warmed. **AOT-compiling the app removes that cliff**, so
     the very first track is fast too. Do it at install (already in the README
     build steps):
     ```bash
     adb shell cmd package compile -m speed -f com.custom.astrion
     ```
     Without the AOT step, expect a slow first bake per cold start, then ~100 ms.
- **No frame drops below ~30 fps during a track change:**
  ```bash
  adb shell dumpsys gfxinfo com.custom.astrion reset
  # trigger a track change on the ambient screen, then:
  adb shell dumpsys gfxinfo com.custom.astrion
  ```
- **Heap flat across 50 track changes (no bitmap leaks):**
  ```bash
  adb shell dumpsys meminfo com.custom.astrion   # baseline
  # skip through ~50 tracks on the ambient screen, then re-run and compare.
  ```
- **Contrast ≥ 4.5:1 for all three text levels** is enforced in code
  (`SceneBuilder.tintedInk` lifts lightness until WCAG clears 4.5 over the
  veiled background), but confirm by eye against real covers.

Do not quote any of the above numbers as measured until you have run them.
