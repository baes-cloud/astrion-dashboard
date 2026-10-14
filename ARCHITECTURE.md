# Architecture & reverse-engineering notes

This documents what the stock `com.aiks.HaRemote` app actually does internally
(from decompiling the shipped APK) and why the standalone-app design follows
from it. Useful if you want to extend this project or add the offline IR path.

## How the stock app works

- **Native Android app** (Jetpack Compose), package `com.aiks.HaRemote`. Not a
  WebView dashboard — the only bundled HTML is a trivial login form
  (`assets/LoginIndex.html`) for entering IP + token.
- On login it opens a **standard HA WebSocket** to `<host>/api/websocket` and
  performs the normal handshake: `auth_required` → `auth` (with `access_token`)
  → `auth_ok`, then `get_states`, then `subscribe_events`.
- It fetches your **Lovelace config** via the `lovelace/config` and
  `lovelace/dashboards/list` websocket commands (class
  `LovelaceConfigFetcher`).
- It scans that config for cards whose `type` matches one of **11 hardcoded
  strings** and drops everything else. The recognised cards are converted into
  internal `Device` models by per-type parser classes and rendered by the app's
  own native UI — which is why the on-device look differs entirely from the
  browser.

### The 11 card types (type string → parser)

| `type` | Parser |
|--------|--------|
| `custom:aiks-tv-card` | TvCardConfigParser |
| `custom:aiks-light-card` | LightCardConfigParser |
| `custom:aiks-scene-card` | SceneCardConfigParser |
| `custom:aiks-media-player-card` | MediaPlayCardConfigParser |
| `custom:aiks-climate-card` | AcCardConfigParser |
| `custom:aiks-cover-card` | CurtainCardConfigParser |
| `custom:aiks-fan-card` | FanCardConfigParser |
| `custom:aiks-switch-card` | SwitchCardConfigParser |
| `custom:aiks-switch-monitor-card` | StatisticsCardConfigParser |
| `custom:aiks-host-card` | HostCardConfigParser |
| `custom:aiks-weather-card` | WeatherCardConfigParser |

These are registered as a static list in
`CardConfigParserFactory.<clinit>` — **no dynamic registration, no plugin
loader**. That's the hard wall: nothing outside the APK can add a type, and an
Android app can't inject views into another app's process. Hence a standalone
app rather than a "companion module".

### The Host/Remote card

`custom:aiks-host-card` is the heaviest type: it's backed by a dedicated
`HostActivity` + `HostRemoteActivity` with its own `RemoteKeyEventManager`, a
`StateMachine`/`StateMachineAdapter`, and a built-in `SceneAdapter`. This is the
universal-AV / Harmony-style "activities" card (states that each fire different
command sets). It appears to be **created on-device** rather than authored in
your Lovelace YAML, which is why it may not show up in your HA dashboard.

## HA WebSocket protocol the app uses (all standard)

Confirmed command types (managers: `AuthenticationManager`,
`ServiceControlManager`, `SubscriptionManager`, `HeartbeatManager`,
`HaStatesManager`):

- `auth` / `auth_ok` / `auth_invalid` (with `access_token`)
- `get_states`
- `subscribe_events` for `state_changed` and `entity_registry_updated`
- `call_service` with `domain`, `service`, `service_data`, `target.entity_id`,
  optional `return_response`
- `media_player/browse_media`
- `ping` / `pong` heartbeat
- `auth/current_user`

`HaClient.kt` in this project implements the subset needed for a dashboard
(auth, `subscribe_entities` — a full snapshot, then compact per-entity
diffs, in place of `get_states` + `subscribe_events` — call_service, ping).

## Sanytron-specific integration (custom events) — optional

Beyond the standard protocol, the app fires/subscribes a set of custom events
through its HACS integration, carrying the remote's `serial_number` and `model`:

- `astrion/page_visited` — telemetry of which page is open
- `astrion/navigate_to` — HA can push the remote to a page/scene view
- `astrion/navigate_list_upload` — uploads the room/view page list to HA
- `astrion/control_command` — **IR control** commands (the local IR blaster)
- `astrion/pair_request` + `astrion/submit_pair_data` — IR device pairing

## Offline IR Blaster

The app drives the hardware IR emitter directly via Android's standard `ConsumerIrManager` (see `ir/IrBlaster.kt` and `ir/SamsungTvCodes.kt`).
Pressing `MENU` (keycode 82) toggles the IR Blaster overlay (`IrModeOverlay.kt`), allowing direct physical control of Samsung TVs even when Wi-Fi is down or Home Assistant is offline.

## Physical Buttons

From `reference/device_key_code.json` (HA100 block), buttons surface as ordinary Android keycodes to the focused Activity. Full map is encoded in `input/HardwareKeys.kt`:
- Shortcut keys: `LIGHT` (134), `CURTAIN` (135), `SCENE` (136), `AC` (137)
- Coloured keys: `CUSTOM_1`..`CUSTOM_4` (138–141)
- Navigation: D-pad (`UP`, `DOWN`, `LEFT`, `RIGHT`, `CENTER`: 19–23)
- Media: `VOLUME_UP` (24), `VOLUME_DOWN` (25), `MUTE` (91/164)
- Utility: `MENU` (82), `VOICE` (133)

Pass-through routing in `InputViewModel.kt` ensures essential keys immediately wake the display and execute on the first stroke.

## Modern MVI Architecture & Concurrency

The app is decoupled into a clean Model-View-Intent pattern:
- **`MainActivity.kt`**: Minimal ~220-line composition host, window flags, and gesture routing.
- **ViewModels (`viewmodel/`)**:
  - `DashboardViewModel`: Manages active page, Lovelace/dashboard config, Home Assistant entity state flows, connection monitoring, and tab navigation.
  - `PowerViewModel`: Handles dock charging state, battery telemetry, motion interrupts, and screensaver transitions.
  - `InputViewModel`: Hardware key dispatching, long-press hold timers, pass-through routing, and volume HUD presentation.
- **Dependency Injection**: Modular **Koin** graph (`ViewModelModule`, `RepositoryModule`, `NetworkModule`, `HardwareModule`).
- **Boot Persistence**: `BootReceiver` and `.HomeAlias` ensure automatic recovery and permanent launcher status across reboots.

## Hardware Constraints & MT6580 Kernel Tuning

- SoC: MediaTek MT6580 (4× ARM Cortex-A7 @ 1.3GHz, Mali-400 GPU, 1 GB RAM, 8 GB storage)
- OS: Android 8.1 Go Edition (API 27)
- Display: 480×800 LCD, portrait

System tuning suite in `device/tuning/` (`init.mt6580_tuning.sh`):
1. **Interactive Governor:** `hispeed_freq=1040000`, 40ms touch boost.
2. **HPS Core Management:** Cores 1–3 offline during screen-off to allow deep sleep.
3. **zRAM & Memory:** 512MB LZ4 compressed zRAM with tuned swappiness (`vm.swappiness=70`).
4. **LMK Recalibration:** Protects dashboard foreground memory against low-RAM kills.
5. **NTP Polling:** 30-minute interval prevents crystal oscillator RTC drift.

