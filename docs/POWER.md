# Battery life

What the app does to save power, what you can tune, and the device-side steps
(making it the home app, retiring Key Mapper and the stock HaRemote app) that
the app can't do for itself.

## What the app does

- **Docked means charging.** The remote counts as docked only when it's
  plugged in *and* the battery is actually charging (or full) and has stayed
  that way for `dock_debounce_seconds`. A remote seated badly on its dock used
  to count as docked: screen held on, wake word streaming, every flicker of
  contact restarting the 10-minute undocked wake word window. It drained
  faster than the dock could fill it.
- **Motion-wake is time-limited.** The accelerometer that wakes the screen
  when you pick the remote up listens for `motion_wake_minutes` after the
  screen goes off, and never on the dock. On the HA100 it is *not* a wake-up
  sensor (`dumpsys sensorservice` shows flags `0x0`), so it only sees a
  pick-up while the CPU is already awake (the wake word, off the dock); on a
  device with a wake-up accelerometer it would otherwise keep the SoC from
  ever sleeping.
- **The screen actually turns off.** The stock HaRemote app writes the
  system screen timeout as "never" (2147483647), so an undocked remote stayed
  lit until it ran flat: measured at 6% an hour with the screen on and
  everything else under 1 mAh. The app now holds the timeout at
  `screen_timeout_seconds` and puts it back whenever something changes it.
  Needs a one-off grant per remote:
  `adb shell appops set com.custom.astrion WRITE_SETTINGS allow`.
- **Screensaver off the dock too.** Put down off the dock, a dimmed
  screensaver comes up after `screensaver.undocked_idle_seconds` (10), and
  the screen then goes off at the timeout. Docked, it is as before.
- **Quiet HA connection with the screen off.** After `screen_off_filter_seconds`
  dark, the HA subscription narrows to the alarm's and the alerts' entities.
  Otherwise every change in HA (the floorplan's radar sensors alone send
  several a second) wakes the Wi-Fi radio and the CPU all night. Waking the
  screen re-subscribes to everything; the snapshot that brings refreshes
  whatever was missed.
- **Nothing runs while HA is quiet.** Entity updates are published when they
  arrive instead of by a loop every 120 ms, and there's one heartbeat (OkHttp's
  WebSocket ping, every 30 s) instead of two.
- **The screensaver draws less.** The dashboard underneath stays alive (so a
  wake is instant) but isn't drawn, and the screensaver samples HA once a
  second with its clock rather than redrawing on every update.

## `power` block in dashboard.json

All keys are optional.

```json
"power": {
  "motion_wake_minutes": 5,
  "screen_off_filter_seconds": 30,
  "screen_off_entities": [],
  "dock_debounce_seconds": 5,
  "screen_timeout_seconds": 30,
  "report_entity": "sensor.lounge_remote_battery",
  "report_name": "Lounge remote battery"
}
```

| Key | Default | |
|---|---|---|
| `motion_wake_minutes` | 5 | How long after the screen goes off a pick-up still wakes it. `0` turns motion-wake off; `-1` keeps it on as long as the screen is off (the old behaviour, and the old drain). |
| `screen_off_filter_seconds` | 30 | How long the screen must be off before the HA subscription narrows. `-1` never narrows. |
| `screen_off_entities` | – | Extra entities to keep live with the screen off, on top of the alarm's and alerts'. |
| `dock_debounce_seconds` | 5 | How long it must be charging before it counts as docked. Lifting it off counts at once. |
| `screen_timeout_seconds` | 30 | The system screen timeout, enforced (see above). Docked, the screen stays on regardless. `0` leaves the setting alone. |
| `report_entity` | – | Publish this remote's battery to HA under this id (see below). Give each remote its own. |
| `report_name` | – | Friendly name for that entity. |

### Battery in Home Assistant

With `report_entity` set, the remote posts its battery to HA (`POST
/api/states`) whenever it changes and on every reconnect. The state is the
percentage, and the attributes are `plugged`, `charging`, `status`
(`charging` / `full` / `discharging` / `not_charging`), `docked` and
**`dock_fault`**. `dock_fault` is true when the remote is plugged in but not
charging, i.e. sitting on the dock wrong. An automation can then tell you:

```yaml
- alias: Remote on its dock but not charging
  triggers:
    - trigger: template
      value_template: "{{ state_attr('sensor.lounge_remote_battery', 'dock_fault') }}"
      for: "00:02:00"
  actions:
    - action: notify.notify
      data:
        message: "The lounge remote is on its dock but isn't charging. Reseat it."
```

States set this way vanish when HA restarts until the remote next reconnects
(it does so on its own within a minute).

## Making it the home app

The stock HaRemote app is the launcher today, and Key Mapper is the way back
to it and into Settings. Both run all the time. HaRemote in particular is
built to hold its own HA connection subscribed to every state change, with
full old and new states, so it may well be using more battery than this app.
Check with the diagnostics below.

Once this app is the launcher, neither is needed:

- **A crash relaunches it.** Android restarts the home app on its own.
- **Settings** is a built-in action, `astrion.open_settings`; bind it to a
  long press. **BACK** (a real Android Back key on the HA100) returns from
  Settings to the dashboard.
- **Other apps** open with `astrion.launch` and `"data": { "package": "..." }`.

Note that the HA100's "home" button is *not* Android's Home key (it sends
keycode 131, F1), which is why something has to provide the way back today.

### Why it "wouldn't boot" last time

Android only sets `sys.boot_completed` once the home app has started and gone
idle. If the home app crashes or hangs at start, the boot animation can sit
there, **and the wireless adb hook in `device/adbwifi.rc` used to wait for
that same property**, so the remote looked bricked rather than stuck. The
manifest also had no HOME entry and no `singleTask`, so as a launcher it
could be started twice (two HA connections, two wake words).

This version fixes the app side (a HOME entry that is off until you enable it,
and `singleTask`) and the adb side (`adbwifi.rc` now persists the adb port so
wireless adb comes up before boot completes; push the updated file, see
`device/README.md`).

### Switching over, reversibly

Keep a **USB cable** handy for the first reboot.

1. Install this build and open it once (grant storage, mic and the battery
   exemption if asked).
2. Note the current launcher, to go back to:
   ```sh
   adb shell cmd package resolve-activity --brief \
     -a android.intent.action.MAIN -c android.intent.category.HOME
   ```
3. Enable the HOME entry and select it:
   ```sh
   adb shell pm enable com.custom.astrion/.HomeAlias
   adb shell cmd package set-home-activity com.custom.astrion/.HomeAlias
   ```
4. Test it without rebooting: this should bring up the dashboard:
   ```sh
   adb shell am start -a android.intent.action.MAIN -c android.intent.category.HOME
   ```
5. Reboot with USB attached and capture the log in case it sticks:
   ```sh
   adb reboot && adb wait-for-device && adb logcat -b all > boot.txt
   ```
   If it hangs, `boot.txt` (search for `FATAL`, `ANR` and `ActivityManager`)
   says why.
6. **Undo** at any point: `adb shell pm disable com.custom.astrion/.HomeAlias`
   (HaRemote becomes the launcher again).

Once it boots cleanly and you're happy:

- Bind the replacements in `dashboard.json`, for example:
  ```json
  "longHotkeys": [ { "key": "MUTE", "service": "astrion.open_settings" } ]
  ```
  Remove Key Mapper's own 🔇 mapping first. While Key Mapper is installed it
  swallows that key system-wide, so the binding would never fire.
- Uninstall Key Mapper, or turn off its accessibility service. Besides the
  memory, an enabled accessibility service makes Compose apps (this one
  included) do accessibility bookkeeping on every frame.
- Disable HaRemote (reversible): `adb shell pm disable-user --user 0 com.aiks.HaRemote`.
  Undo with `adb shell pm enable com.aiks.HaRemote`.

Optional: on this userdebug build you can make one physical button a true
Android Home key everywhere by editing the keypad's key layout file (`getevent
-l` shows the button's scancode; the layout is `mt_gpio_kpd.kl` under
`/system/usr/keylayout` or `/vendor/usr/keylayout`). Apps never see Home, so
that button stops working for anything else in the dashboard.

## Device settings worth changing

The screen is by far the biggest cost. After that, anything that keeps the
radio or CPU awake.

```sh
adb shell appops set com.custom.astrion WRITE_SETTINGS allow  # the app then holds the timeout itself
adb shell settings put system screen_brightness 90          # 0-255
adb shell settings put global wifi_scan_always_enabled 0
adb shell settings put global ble_scan_always_enabled 0
adb shell settings put secure location_providers_allowed -gps
adb shell settings put secure location_providers_allowed -network
```

- **Bluetooth:** this app doesn't use it. Turn it off in Settings.
- **Wi-Fi:** a strong 2.4 GHz signal and a DHCP reservation. On the router,
  IGMP snooping or multicast-to-unicast stops Sonos and mDNS multicast waking
  every sleeping client.
- **Background apps:** `adb shell ps -A` for anything that shouldn't be there.
  Some MediaTek test builds ship a logger that runs all the time
  (`ps -A | grep -i log`).

## Finding what's draining it

```sh
adb shell dumpsys batterystats --reset      # then leave it off the dock ~1 h
adb shell dumpsys batterystats > bs.txt     # per-app CPU, wakelocks, Wi-Fi packets
adb shell dumpsys sensorservice             # accelerometer wakeUp flag, active listeners
adb shell dumpsys power | grep -i wake_lock
adb shell "cat /proc/net/tcp /proc/net/tcp6 | grep -i :1FBB"   # sockets to HA :8123 (uid column)
adb shell dumpsys battery                   # plugged / status / level right now
```
