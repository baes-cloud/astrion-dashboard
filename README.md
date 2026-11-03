<div align="center">

<img src="screenshots/hero.png" alt="BÆOREMOTE on the HA100: Main with the floorplan, Vision, Sound with its Media and Zones tabs, and Living" width="100%">

**A fast, native Home Assistant dashboard for the Sanytron Astrion HA100 smart remote.**<br>
Written from scratch in Kotlin and Jetpack Compose to replace the stock `HaRemote` app.<br>
No cloud, no WebView, no vendor lock-in.

[![CI](https://github.com/baes-cloud/astrion-dashboard/actions/workflows/ci.yml/badge.svg)](https://github.com/baes-cloud/astrion-dashboard/actions/workflows/ci.yml)
[![Version](https://img.shields.io/badge/version-v1.6.0-8CBDB5)](CHANGELOG.md)
![Android 8.1](https://img.shields.io/badge/Android-8.1%20(API%2027)-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Jetpack%20Compose-7F52FF?logo=kotlin&logoColor=white)
![Home Assistant](https://img.shields.io/badge/Home%20Assistant-WebSocket%20API-18BCF2?logo=homeassistant&logoColor=white)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue)](LICENSE)

[Features](#features) · [Quick start](#quick-start) · [Configuration](#configuration) · [Buttons](#hardware-buttons) · [Under the hood](#under-the-hood) · [Docs](#documentation) · [Credits](#credits)

</div>

---

## Why this exists

The HA100 is lovely hardware: a 480×800 touchscreen, real buttons, an IR blaster and a microphone. Its software holds it back.

- The stock app renders only **11 hard-coded card types** from your Lovelace config and **silently drops everything else**.
- Running a real HA dashboard through Fully Kiosk or the Companion app is far too slow on a 2015-era **MT6580 with 1 GB of RAM**.

Astrion replaces the whole rendering layer. It still speaks to Home Assistant exactly the way the stock app does, over one standard WebSocket.

|  | Stock `HaRemote` | Astrion |
| --- | --- | --- |
| Card types | 11, hard-coded | Card registry, configured in JSON |
| Layout | Taken from Lovelace and filtered | Your own `dashboard.json`, reloaded live |
| Floorplan | None | Sun-synced, lit rooms, radar, vacuum |
| Music | Basic player | Music Assistant search, queue, shelves, multi-room |
| Plex | None | Search, posters and one-tap play on the TV |
| Screen off the dock | Timeout forced to "never" | Screensaver, then off (measured −6 %/h fixed) |
| Help for guests | None | Built-in guide with a hands-on walkthrough |
| HA goes down | Stuck until rebooted | Reconnects on its own, never needs a reboot |

## How it fits together

```mermaid
flowchart LR
    subgraph Remote["HA100 remote"]
        Keys["Hardware keys"] --> App["Astrion app<br/>(Compose · MVI)"]
        Touch["Touchscreen"] --> App
        App --> IR["IR blaster"]
        Mic["Microphone"] --> App
    end
    App <-->|"WebSocket<br/>subscribe_entities · call_service"| HA["Home Assistant"]
    App -.->|"Assist audio"| HA
    IR -.->|"infrared"| TV["Samsung TV"]
    HA --> Sonos["Sonos · Music Assistant"]
    HA --> GTV["Google TV · Plex"]
    HA --> Home["Lights · blinds · AC · lock<br/>radar · vacuum · intercom"]
```

## Features

### The four pages

Each page has its own hardware key, and you can also swipe sideways to turn the page. The names come from the Bang & Olufsen product line, and a **BÆO** wordmark footer anchors every page.

| | Page | What it does |
| :---: | --- | --- |
| <img src="screenshots/main.png" width="140"> | **BÆOREMOTE** · Main<br>`LIGHT` | Weather, next alarm and event, the Motion Lights switch, a spinning mirror ball, the door lock, scene pills and a **live floorplan**. |
| <img src="screenshots/tv-page.png" width="140"> | **BÆOVISION** · TV<br>`CURTAIN` | Shows what's on the TV. Search, Movies, TV and MTV tiles open your Plex server. Tap a poster on the *On Deck* or *Recently Released* shelf and it wakes the TV, switches to Google TV and starts that exact episode. |
| <img src="screenshots/media-page.png" width="140"> | **BÆOSOUND** · Media<br>`SCENE` | Music search, the Sonos player with a volume fader, four one-touch mixes, the queue, Music Assistant shelves and multi-room Zones. |
| <img src="screenshots/climate.png" width="140"> | **BÆOLIVING** · Climate<br>`AC` | Air-conditioning setpoint steppers, HVAC and fan modes, and motorised blinds. |

### 🌇 A floorplan that looks like your home right now

The plan follows the sun. As it drops, a shade darkens the flat, and every light that's on lifts it again with a pool of its own colour and brightness.

| Day | Dusk | Night |
| :---: | :---: | :---: |
| <img src="screenshots/floorplan-day.png" alt="Day"> | <img src="screenshots/floorplan-dusk.png" alt="Dusk"> | <img src="screenshots/floorplan-night.png" alt="Night"> |

- **Lights as they really look.** Each light gets a hot core and a soft tail in its own colour. Cove strips draw as a lit rail with a wash spilling into the room (`glow_line`). Hidden strips halo the furniture they sit behind (`glow_outline`).
- **Real fixtures.** Lamps, LED bars, downlights, artworks and candles each get their own icon. TVs glow on the plan while they're on.
- **Blinds** are drawn as far down as they really are. **The mirror ball** throws warm specks around the room while it spins.
- **Presence and robots.** LD2450 mmWave radar plots people as live dots, and the robot vacuum shows in its current room.
- **Tap to toggle, long-press for more.** Long-press a light for a bottom sheet with a tall fader, warmth and colour.
- **Colours you can live with.** `recolor` draws one light colour as another on the plan only, so a scene's mint green doesn't clash with teal LED strips.

### 🎵 Multi-room music with BÆOSOUND

| Player | Library | Queue | Media | Zones |
| :---: | :---: | :---: | :---: | :---: |
| <img src="screenshots/media-page.png" alt="Player"> | <img src="screenshots/sonos-browser.png" alt="Library"> | <img src="screenshots/queue.png" alt="Queue"> | <img src="screenshots/sonos-media.png" alt="Media"> | <img src="screenshots/sonos-group.png" alt="Zones"> |

- **Player:** the cover over a blurred wash of its own colours, transport controls, a volume fader with mute, and four one-touch mixes.
- **Library:** browse the Sonos library the way Home Assistant sees it (favourites, music library, Plex, Spotify, radio). Tap to play now, or **+** to queue.
- **Queue:** now playing and up next from Music Assistant. It also shows the track when the Sonos plays something Music Assistant didn't start.
- **Media:** Music Assistant shelves with recently played albums, favourite songs and playlists.
- **Zones:** join or drop each room's speaker with its own volume. Turn on **BÆOLINK** and the music follows you from room to room.

### 🔍 Search everything: music and Plex

| Music search | Plex search | Plex library | A show |
| :---: | :---: | :---: | :---: |
| <img src="screenshots/music-search.png" alt="Music search"> | <img src="screenshots/plex-search.png" alt="Plex search"> | <img src="screenshots/plex-library.png" alt="Plex library"> | <img src="screenshots/plex-show.png" alt="Plex show"> |

- **Music:** type an artist, album, song, playlist or station. Results come from every Music Assistant provider, including your library and Spotify.
- **Plex:** search as you type, or open a library as a poster grid (A–Z, recently added, unwatched) with watched ticks. Films play in one tap, and shows open to seasons and episodes.

### 🙋 A guide for guests (hold ☰)

| Guide | How do I… | On the TV |
| :---: | :---: | :---: |
| <img src="screenshots/guide-home.png" alt="Guide home"> | <img src="screenshots/guide-howto.png" alt="How do I"> | <img src="screenshots/guide-tv.png" alt="TV guide"> |

Hand the remote to anyone and hold **Menu ☰**.

- **How do I…** short answers to the most-asked questions, such as lights, music, the door and a TV that won't respond.
- **Walkthrough:** asks you to press the real buttons and says what each one would do. Nothing is sent to Home Assistant while the guide is open.
- **Every button:** press and hold actions, read live from `dashboard.json`.
- **On the TV:** a one-page version appears on the Samsung Serif, which goes back to what it was showing when you close the guide.

### 🌙 Screensavers, alerts and popups

| Screensaver (day) | Screensaver (night) | Volume OSD | Doorbell |
| :---: | :---: | :---: | :---: |
| <img src="screenshots/screensaver-day.png" alt="Day screensaver"> | <img src="screenshots/screensaver-night.png" alt="Night screensaver"> | <img src="screenshots/volume-osd.png" alt="Volume OSD"> | <img src="screenshots/intercom.png" alt="Doorbell popup"> |

| Alarm ringing | Alarm snoozed | Washer done | IR mode | Light popup | Vacuum |
| :---: | :---: | :---: | :---: | :---: | :---: |
| <img src="screenshots/alarm-ringing.png" alt="Alarm ringing"> | <img src="screenshots/alarm-snoozed.png" alt="Alarm snoozed"> | <img src="screenshots/alert-washer.png" alt="Washer done"> | <img src="screenshots/ir-mode.png" alt="IR mode"> | <img src="screenshots/light-control.png" alt="Light popup"> | <img src="screenshots/robovac-control.png" alt="Vacuum popup"> |

<table><tr>
<td width="260"><img src="screenshots/alarm-hold-to-stop.gif" alt="Hold to stop the alarm"></td>
<td>

- **Night screensaver:** an OLED-safe Baby Doll rose glow, an alarm countdown when one is due within 12 hours, and the art and progress of whatever is playing.
- **Hold to stop:** alarms need a deliberate hold, so a sleepy tap only snoozes.
- **Volume OSD:** any volume or mute press brings up a floating HUD with a draggable slider.
- **Doorbell:** when the intercom rings, the screen wakes with the entrance snapshot. **Let in** calls the lift and opens the main door.
- **IR mode:** press ☰ to drive the Samsung TV over infrared, with no network needed.
- **Voice:** the 🎤 key starts a Home Assistant Assist session.

</td>
</tr></table>

## Quick start

> [!IMPORTANT]
> You need a Home Assistant instance, a [long-lived access token](https://www.home-assistant.io/docs/authentication/#your-account-profile), JDK 17, the Android SDK (API 27+), and `adb` access to the remote.

```bash
# 1. Point the app at Home Assistant (secrets.properties is gitignored)
cp secrets.properties.example secrets.properties
#    haUrl=http://<your-ha-ip>:8123
#    haToken=<long-lived access token>

# 2. Build
./gradlew assembleRelease

# 3. Install, compile ahead of time, and grant permissions
adb install -r app/build/outputs/apk/release/app-release.apk
adb shell cmd package compile -m speed -f com.custom.astrion
adb shell appops set com.custom.astrion WRITE_SETTINGS allow
adb shell settings put global ntp_polling_interval 1800000

# 4. Push your layout
adb push device/config/dashboard.json /sdcard/astrion/dashboard.json
```

Or let the helper script do steps 3 and 4. It also pushes the device-side init scripts, compiles the app ahead of time and adds it to the Doze whitelist:

```bash
device/install.sh --apk app/build/outputs/apk/release/app-release.apk --layout <remote-ip>
```

> [!NOTE]
> Sanytron's `HaRemote` stays the launcher by default, because the HA100 can boot-loop without it. Hold **Back** to open Astrion (via Key Mapper). To make Astrion the launcher, see [`docs/POWER.md`](docs/POWER.md).

> [!TIP]
> Some features lean on Home Assistant automations and scripts, such as switching the Sonos to the TV when the TV plays. [`docs/HOME_ASSISTANT.md`](docs/HOME_ASSISTANT.md) has them ready to paste.

## Configuration

Everything (pages, cards, buttons and entities) lives in one JSON file, and the app reloads it within a second of a change.

| File | Role |
| --- | --- |
| `/sdcard/astrion/dashboard.json` | Live configuration on the remote |
| `device/config/dashboard.json` | Default configuration, bundled in the APK |
| `/sdcard/astrion/device.json` | Per-remote overrides, such as the battery sensor ([`docs/POWER.md`](docs/POWER.md)) |
| `/sdcard/astrion/floorplan.png` | Your floorplan image |

<details>
<summary><b>Example: turn on the ambient now-playing screen</b></summary>

```json
"ambient": {
  "enabled": true,
  "media_players": ["media_player.club", "media_player.the_club_tv"],
  "rest_clock": true,
  "show_album": true
}
```

When the remote is idle and music is playing, the screensaver becomes a full-screen cover with a backdrop in the album's own colours. See [`docs/ambient-nowplaying.md`](docs/ambient-nowplaying.md).
</details>

<details>
<summary><b>Optional: <code>trusted_tls_hosts</code></b></summary>

A top-level list of LAN hosts whose HTTPS certificate is accepted without a name match. Use it for album art from a server you reach by IP, such as Plex. Every other host gets the normal check.
</details>

## Hardware buttons

The layout in [`device/config/dashboard.json`](device/config/dashboard.json):

| Key | Press | Hold | Double press |
| --- | --- | --- | --- |
| `LIGHT` | Main page | All lights off | |
| `CURTAIN` | TV page | Club blinds open or close | |
| `SCENE` | Music page | Speakers play the TV's sound | Next track |
| `AC` | Climate page | Aircon on or off | |
| 🔴 `CUSTOM_1` | Jellyfin on the TV | Bae lighting scene | Toggles a light |
| 🟢 `CUSTOM_2` | Plex on the TV | Green lighting scene | Toggles a light |
| 🔵 `CUSTOM_3` | ABC iview on the TV | Night lights | Toggles a light |
| 🟡 `CUSTOM_4` | Ambient screen on the TV | Day lights | Toggles a light |
| `PAGE_UP` / `PAGE_DOWN` | Lights that are on, brighter / dimmer | Blinds open / close | |
| `VOLUME_UP` / `DOWN` · `MUTE` | Volume and mute, with the OSD | Mute: Android settings | Mute: the stock app |
| `MENU` ☰ | IR mode | Help guide | TV to the PC (HDMI 2) |
| `VOICE` 🎤 | Home Assistant Assist | | |
| D-pad, centre, `HOME`, `BACK`, `POWER` | Drive the Google TV | Centre: play / pause | |

A long press is 0.8 s, and it still counts when it starts on a dark screen. Keys wake the screen and act on the first press. Every mapping, with an optional `label` the guide reads, lives in `hotkeys`, `longHotkeys` and `doubleHotkeys`.

## Under the hood

Astrion is built for a MediaTek MT6580 (4× Cortex-A7 @ 1.3 GHz, Mali-400 GPU, 1 GB RAM) running Android 8.1 Go.

<details>
<summary><b>Architecture and performance</b></summary>

- **MVI** with Kotlin Coroutines, `StateFlow` and `SharedFlow`. `DashboardViewModel`, `PowerViewModel` and `InputViewModel` keep state out of the views.
- **Koin DI**, split into ViewModel, Repository, Network and Hardware modules.
- **Selective state publishing.** `HaClient` keeps every entity up to date but only emits to Compose for entities the UI actually watches, so chatty sensors cost no recomposition. Playhead noise from paused sessions is ignored too.
- **Fast app launches.** The coloured keys send non-blocking `media_player.select_source` calls and open TV apps in under 150 ms.

</details>

<details>
<summary><b>Battery and power</b></summary>

- **The screen really turns off off the dock.** The stock app forced the screen timeout to "never", which cost 6 % an hour measured. Astrion holds it at `power.screen_timeout_seconds` and restores it if anything changes it.
- **Dock-aware debounce** (2 s docking, 15 s undocking) rides out pogo-pin contact drops.
- **The screensaver redraws once a minute**, not every second.
- **Device-side tweaks** in [`device/`](device/README.md): Wi-Fi power save that follows the dock, tmpfs caches that spare the flash, and a boot guard against rotten eMMC cache sectors.

</details>

<details>
<summary><b>Design system: Bæo v2.0</b></summary>

- One `AstrionTheme` API. Every colour and font size is a token in `ui/theme/`.
- **Palette** from Porter's Paints: Yacht Race surfaces, Hailstorm and Gunmetal Grey accents, Baby Doll for attention. Red is for errors only, and warm amber means a light is on.
- **Type:** Syne for wordmarks and clocks, Manrope for everything else.
- **Enforced by tests:** `ThemeLiteralsTest` fails on any hard-coded literal, and `AstrionDesignSystemTest` checks for duplicate tokens and WCAG AA contrast (4.5:1).

</details>

<details>
<summary><b>Reliability and the flash</b></summary>

- **HA outages heal themselves.** Socket callbacks are matched by connection epoch, and a watchdog reconnects after 45 s stuck opening, 90 s with no seed, or 90 s in error. The disk cache never overwrites live state.
- **The app writes nothing to flash.** The HA100's eMMC can rot data at rest, so the art cache and entity snapshot live on tmpfs (`bootguard.sh`), the art cache is capped at 28 MB, and reads no longer touch file times.
- **A rotten cache can't boot-loop it.** Before `system_server` starts, `bootguard.sh` moves aside `package_cache` and dalvik-cache files with unreadable sectors and clears the rest.
- **Health checks from the admin PC.** A separate `remote_health.py` (not in this repo) checks `/system` against a known-good copy, repairs images, relaunches a stuck app and does clean reboots.

</details>

<details>
<summary><b>Clock and security</b></summary>

- **No internet NTP needed.** A root `astrion_timesync` daemon sets the system clock and hardware RTC from Home Assistant's HTTP `Date:` header at boot and every 15 minutes.
- **ADB lockdown.** The firmware is a `userdebug` build, so anyone on the LAN would get a root shell. `adbfw.sh` limits adbd to your workstation by IP and MAC, and fails open so you can't lock yourself out. See [`device/README.md`](device/README.md).

</details>

<details>
<summary><b>Project structure</b></summary>

```text
app/src/main/java/com/custom/astrion/
├── AstrionApp.kt       # Entry point, Koin DI and registry initialisation
├── MainActivity.kt     # Window host, gesture router, lifecycle
├── BootReceiver.kt     # Relaunch on boot and recovery
├── viewmodel/          # Dashboard, Power and Input ViewModels
├── di/                 # Koin modules
├── ha/                 # Home Assistant WebSocket and HTTP client
├── cards/              # CardRegistry and card renderers
├── config/             # AppConfigRepository and reactive config loader
├── ui/                 # Pages, screensaver, ambient, volume popup, theme
├── input/              # Hardware keys, KeyDispatcher, hotkey routing
├── power/              # Battery state machine, dock detection, motion wake
├── voice/              # Assist satellite audio streaming
└── ir/                 # IR blaster and Samsung codes
```

</details>

## Documentation

| Doc | What's in it |
| --- | --- |
| [`CHANGELOG.md`](CHANGELOG.md) | What changed in each release |
| [`ARCHITECTURE.md`](ARCHITECTURE.md) | How the stock app works (reverse-engineered) and why Astrion is a standalone app |
| [`docs/HOME_ASSISTANT.md`](docs/HOME_ASSISTANT.md) | Companion automations, scripts and helpers |
| [`docs/POWER.md`](docs/POWER.md) | Launcher, boot and battery setup |
| [`docs/ambient-nowplaying.md`](docs/ambient-nowplaying.md) | The ambient now-playing screen |
| [`docs/IR_CAPTURE.md`](docs/IR_CAPTURE.md) | Capturing IR codes |
| [`docs/ALARM_POPUP_SPEC.md`](docs/ALARM_POPUP_SPEC.md) | How the alarm popup behaves |
| [`device/README.md`](device/README.md) | Device-side scripts, the ADB firewall and install helpers |

## Credits

- **[@dckiller51](https://github.com/dckiller51)** forked this project into [Astrion Custom Dashboard](https://github.com/dckiller51/astrion-custom-dashboard), a configurable take for anyone's home (browser builder, Activities, Harmony, direct Apple TV and Android TV, an IR database), and kindly credits this repo. Turning Wi-Fi power save off is an idea from their `astrion-tweaks.sh`; here it follows the dock. If you want something you can set up without building it yourself, start there.
- **Sanytron** for the HA100 hardware and its open, root-able firmware.
- The Home Assistant community thread [HA-Remote Control](https://community.home-assistant.io/t/ha-remote-control/922706), where the early owners worked out how the remote ticks.

## Licence

MIT. See [`LICENSE`](LICENSE). Manrope and Syne are used under the SIL Open Font License (see [`licenses/`](licenses/)). Plex deep links target classic Plex for Android TV (10.x).
