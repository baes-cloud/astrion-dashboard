# Work-alarm popup — spec for other screens

A brief for building the same alarm popup on another display (e.g. an ESPHome
touchscreen). It describes the version running on the two Astrion remotes: what
it shows, which Home Assistant entities drive it, and the traps found while
building and testing it.

## The one rule: mirror Home Assistant, keep no state of your own

Everything the alarm does lives in Home Assistant, in
`/config/packages/work_alarms.yaml`. The popup only **reads** HA state and
**calls** two scripts. It never decides when an alarm rings or tracks a snooze
itself. That keeps every screen and the phone in step. Snooze on the phone and
every screen switches to "snoozed". Stop it anywhere and every popup closes.

## Entities

| Entity | Use |
|---|---|
| `input_boolean.work_alarm_ringing` | **Show the popup while this is `on`.** The ring script turns it on. Only "stop" turns it off. It **stays on through a snooze**. |
| `timer.work_alarm_snooze` | `active` = snoozed. Attribute `finishes_at` (ISO-8601 with offset) = when it rings again. `idle` = not snoozed. |
| `sensor.work_start` | What the alarm is for. State = start time (ISO-8601, UTC). Attributes `summary` (e.g. "Head Office") and `location` (e.g. "HQ", may be empty). |
| `script.work_alarm_snooze` | **Snooze button.** Pauses the Club, starts the 5-min timer and notifies the phone. It does nothing unless ringing is on. |
| `script.work_alarm_stop` | **Dismiss button.** Turns ringing off, cancels the timer, pauses the Club and turns **on** `input_boolean.work_alarms_off_today`, which also cancels today's second alarm. |

Call the scripts as `script.turn_on` with `entity_id: script.work_alarm_snooze`
(or `…_stop`). Calling the script by name as its own action works too.

Optional, for a "next alarm" line: `sensor.work_alarm_1`, `sensor.work_alarm_2`
and `sensor.work_alarm_wfh` (timestamps, UTC ISO-8601, `unavailable` when not
set), `input_boolean.work_alarms_enabled` (master switch) and
`input_boolean.work_alarms_off_today`.

## States

```
ringing == off                      → HIDDEN (normal screen)
ringing == on  and timer != active  → RINGING
ringing == on  and timer == active  → SNOOZED (countdown to finishes_at)
```

Recompute this from those two entities every time either one changes. The
popup should not keep its own idea of which state it's in.

## What it shows

Nearly full screen: a dark scrim over the whole display and a rounded panel
inset about 14 px on every side. Taps on the scrim do nothing, so nothing
underneath can be hit by mistake. From top to bottom:

1. **A large alarm icon** in a soft circle. It pulses in amber while RINGING and
   switches to a still, blue "snooze" icon while SNOOZED.
2. **"ALARM"** (amber) or **"SNOOZED"** (blue): small caps, letter-spaced.
3. **The current time, very large** (about 72 px light weight, e.g. `7:05`), with
   `AM`/`PM` small underneath.
4. **What it's for:** `summary · location` (e.g. "Head Office · HQ"),
   then a smaller line "Starts at 8:15 AM" taken from `sensor.work_start`'s
   state. Convert it from UTC to local time.
5. SNOOZED only: **"Back in 4:32"**, counting down each second to
   `finishes_at`.
6. Empty space pushing the buttons to the bottom, where a thumb finds them:
   - RINGING: **"Snooze 5 min"**, a big primary button about 76 px tall and full
     width. **One tap.**
   - Always: **"Hold to dismiss for today"**, full width, about 64 px, red-tinted.
     **Press and hold for about 0.9 s.** A red fill sweeps across while it's held.
     Letting go early cancels it and the fill springs back.
   - SNOOZED only: a small text button, "Hide until it rings again". It hides
     the popup on that screen only. It reappears when the alarm rings again
     (when the snooze timer ends, or on the next alarm).

### Why Dismiss is a hold

Dismiss runs "stop for today", which also cancels the second alarm. One
half-asleep tap on the wrong button would mean sleeping through a shift.
Snooze is the safe choice, so it gets the big single-tap button.

## Waking the screen

This part matters most, and is the easiest to get wrong.

- When `work_alarm_ringing` turns `on`, **turn the screen and backlight on**,
  even from sleep, and keep them on while it's RINGING.
- **Re-assert that about every 20 s while RINGING.** Someone pressing a sleep or
  power button shouldn't be able to leave a ringing alarm on a dark screen.
- Drive this from the state-change handler, **not from the popup's drawing
  code**. On the remotes the UI stops drawing when the display sleeps, so the
  popup itself could never wake the screen. A background listener on the HA
  state does it instead.
- In SNOOZED state the screen may time out as normal. It wakes again when the
  timer ends and ringing resumes.
- When ringing goes `off`, clear everything and return to normal behaviour.

On the remotes, both screens woke from sleep within 1 s of the flag turning
on.

## Optional: a "next event / next alarm" line

On the normal home screen there's a line reading
`Next event: Tue 8:15 HQ` on the left and `Next alarm: 7:05 Tue` on the right.

- **Next alarm** = the earliest time still in the future across
  `sensor.work_alarm_1`, `_2` and `_wfh`.
- If `work_alarms_enabled` is `off`, show **"off"**.
- If `work_alarms_off_today` is `on`, skip **today's** alarm 1 and 2, but
  **not** the WFH alarm. "Stop for today" never cancels the WFH alarm, and HA
  applies the same rule.

## Testing — read this before touching anything

- **Never test Dismiss against the real script before the day's alarms have
  run.** It switches `work_alarms_off_today` on, which **cancels that morning's
  real alarms**. Our test did exactly that at 3 am, and it had to be switched
  back off by hand.
- **Safe test:** turn `input_boolean.work_alarm_ringing` on directly. The
  popup appears and the screen wakes, and nothing plays, because only the ring
  script plays music. Turn it off again to close the popup. For the SNOOZED
  view, start `timer.work_alarm_snooze` directly, then cancel it.
- If a real Snooze or Dismiss does get pressed during testing, afterwards make
  sure `input_boolean.work_alarms_off_today` is `off`,
  `input_boolean.work_alarm_ringing` is `off`, and `timer.work_alarm_snooze` is
  `idle`.
- **Test Snooze while music is playing:** the real snooze script also pauses
  the Club and sends a phone notification.

## ESPHome notes (suggestions — check against your ESPHome version)

- Read state with `platform: homeassistant` sensors: a `binary_sensor` for
  `input_boolean.work_alarm_ringing`, and `text_sensor`s for
  `timer.work_alarm_snooze` (state, plus `attribute: finishes_at`) and for
  `sensor.work_start` (state, plus `attribute: summary` / `location`).
- Call the scripts with `homeassistant.action` (older versions:
  `homeassistant.service`) using `action: script.turn_on` and
  `data: { entity_id: script.work_alarm_snooze }`. **In HA, enable "Allow the
  device to perform Home Assistant actions"** in the ESPHome device's options,
  or the calls are silently refused.
- With LVGL, build the popup as its own page or a top-layer object, shown and
  hidden from the `binary_sensor`'s `on_state`. Use `on_short_click` for
  Snooze and `on_long_press` for Dismiss, and set `long_press_time` to about
  `900ms` (the default is shorter).
- Wake the screen with `light.turn_on` on the backlight (plus `lvgl.resume` if
  you pause LVGL when idle). Run an `interval:` of about 20 s that re-asserts
  it while ringing is on.
- The ISO timestamps are UTC, so make sure the device's `time:` component has
  the right timezone before formatting "Starts at" and the countdown.
