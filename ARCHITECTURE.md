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

This project ignores all of these; everything works over standard HA services
while online. If you later want the **offline IR blaster**, that's the path to
replicate: subscribe/emit `astrion/control_command` against their integration,
or drive the SoC's IR emitter directly via Android's `ConsumerIrManager` if the
firmware exposes it.

## Physical buttons

From `assets/device_key_code.json` (HA100 block), buttons surface as ordinary
Android keycodes to the focused Activity. Full map is encoded in
`input/HardwareKeys.kt`. The dedicated shortcut keys (LIGHT 134, CURTAIN 135,
SCENE 136, AC 137, CUSTOM_1..4 138–141, VOICE 133) are the most useful to
rebind. A separate `device_firmware_key_config.json` maps some of these at the
firmware level to labels (LIGHT/CURTAIN/AC/MEDIA_PLAY/ONE..FOUR), but at the app
layer they arrive as the keycodes above.

## Hardware constraints

- SoC: MediaTek MT6580 (2015), 1 GB RAM, 8 GB storage
- OS: Android 8.1 (API 27)
- Display: 480×800 LCD, portrait

Keep cards light: avoid heavy animation, large images, or big dependency graphs.
The UI here uses a simple vertical scroll rather than lazy lists, which is fine
for a handful of cards and cheaper on this SoC.
