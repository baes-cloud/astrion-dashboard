# Astrion Custom (BæoRemote)

A native, high-performance Home Assistant dashboard and launcher for the **Sanytron Astrion HA100** smart remote, written from scratch to replace the stock `HaRemote` app.

It talks directly to Home Assistant over the standard WebSocket API, renders custom tactile Jetpack Compose UI across four dedicated hardware dashboards, and routes physical buttons with zero vendor cloud dependencies.

| BÆOVISION (TV) | BÆOREMOTE (Main) | BÆOSOUND (Media) | BÆOLIVING (Climate) |
|---|---|---|---|
| ![BÆOVISION](screenshots/tv-page.png) | ![BÆOREMOTE](screenshots/main.png) | ![BÆOSOUND](screenshots/media-page.png) | ![BÆOLIVING](screenshots/climate.png) |

---

## The Four Dedicated Hardware Dashboards

The app is built around four primary pages, reachable with dedicated physical shortcut buttons (`CURTAIN`, `LIGHT`, `SCENE`, `AC`) and anchored by the signature fixed-width **BÆO** wordmark footer:

1. **BÆOVISION (`TV`):** Instant TV state, playback controls, fast native app launchers (Netflix, Plex, ABC iview, Jellyfin), and Plex shelves (*On Deck* and *Recently Released*) where a single tap deep-links into the exact media episode or movie.
2. **BÆOREMOTE (`Main`):** Five-day weather forecast, front door lock/unlock status, scene presets with illuminated active badges, and a live, interactive apartment floorplan with tappable lights and mmWave radar presence tracking.
3. **BÆOSOUND (`Media`):** Club Sonos multi-room audio with high-precision volume slider, playback controls, Music Assistant media shelves, and grouped zone selection.
4. **BÆOLIVING (`Climate`):** Air conditioning temperature steppers with target readouts, HVAC/fan modes, and motorized blind controls.

---

### BÆOSOUND (Multi-Room Media Stack)

The media dashboard features three dedicated tabs tailored for single-touch music management:

| Player (Now Playing) | Media (Shelves & Playlists) | Zones (BÆOLINK Multi-Room) |
|---|---|---|
| ![Sonos Player](screenshots/sonos-player.png) | ![Sonos Media](screenshots/sonos-media.png) | ![Sonos Zones](screenshots/sonos-group.png) |

* **Player:** Full-bleed album art, live progress, tactile haptic transport controls (prev / play / next), volume steppers, and one-touch genre preset shortcuts.
* **Media:** Live Music Assistant shelves displaying dynamically fetched random album picks, favourite tracks, and playlists with cached artwork.
* **Zones:** Real-time multi-room speaker grouping with individual room volume sliders and join/link toggles.

---

### Dynamic Floorplan & LitRooms Lighting

The interactive floorplan dynamically responds to solar elevation and lighting state, shifting seamlessly between Day, Dusk, and Night:

![LitRooms Dynamic Lighting States](screenshots/lit-rooms.png)

| Day (Solar Off) | Dusk (Twilight Shading) | Night (Atmospheric Glow) |
|---|---|---|
| ![Floorplan Day](screenshots/floorplan-day.png) | ![Floorplan Dusk](screenshots/floorplan-dusk.png) | ![Floorplan Night](screenshots/floorplan-night.png) |

* **Atmospheric Shading:** Sun-synchronized ambient floor shading with automatic light halos that illuminate rooms when active.
* **Interactive Control:** Tap any bulb to toggle room lighting, or long-press to open the full-screen brightness and color tuning slider.
* **mmWave Radar Presence:** Real-time target tracking dots driven by LD2450 sensors plotted over the apartment layout.

---

## Screensavers & System Overlays

| Screensaver (Day) | Screensaver (Night) | Floating Volume OSD |
|---|---|---|
| ![Screensaver Day](screenshots/screensaver-day.png) | ![Screensaver Night](screenshots/screensaver-night.png) | ![Volume OSD](screenshots/volume-osd.png) |

* **Contextual Screensavers:**
  * **Day Mode:** Crisp high-contrast mint and slate digits with live weather, temperature, date, and battery indicator.
  * **Night Mode:** Warm Baby Doll rose radial gradient (`#0C0E10` to `#050607`) with OLED-safe low luminance, upcoming alarm countdowns (within 12 hours), and active media playback art and progress.
* **Floating Volume Capsule (OSD):** Changing volume or muting on any remote button summons a TV-style floating HUD displaying speaker entity, mute indicator, and an interactive draggable volume slider that smoothly auto-dismisses.
* **Physical Key Pass-Through:** Configured hardware keys (`VOLUME_UP`, `VOLUME_DOWN`, `MUTE`, `LIGHT`, `CURTAIN`, `SCENE`, `AC`, `VOICE`) wake the screen and immediately execute on the very first press without being swallowed.

---

### Additional Feature Screenshots

| IR Mode | Light Popup | Vacuum Popup |
|---|---|---|
| ![IR Mode](screenshots/ir-mode.png) | ![Light popup](screenshots/light-control.png) | ![Vacuum popup](screenshots/robovac-control.png) |
| **Washer Done Alert** | **Alarm Ringing** | **Alarm Snoozed** |
| ![Washer done alert](screenshots/alert-washer.png) | ![Alarm ringing](screenshots/alarm-ringing.png) | ![Alarm snoozed](screenshots/alarm-snoozed.png) |

![Hold to stop the alarm](screenshots/alarm-hold-to-stop.gif)

---

## Architecture & System Design

Astrion is built for the resource-constrained MediaTek MT6580 SoC (4× ARM Cortex-A7 @ 1.3GHz, Mali-400 GPU, 1 GB RAM, Android 8.1 Go Edition):

* **Architecture:** Modernized **MVI (Model-View-Intent)** with Kotlin Coroutines, `StateFlow`, and `SharedFlow`. ViewModels (`DashboardViewModel`, `PowerViewModel`, `InputViewModel`) decouple state cleanly from the view layer.
* **Dependency Injection:** Lightweight **Koin** DI (`ViewModelModule`, `RepositoryModule`, `NetworkModule`, `HardwareModule`).
* **Design System (Bæo Design System v2.0):** Centralized semantic design tokens grounded in Porter's Paints architectural swatches (`Yacht Race`, `Gunmetal Grey`, `Hailstorm`, `Baby Doll`). Monotonic luminance ramp (`surfacePrimary`, `surfaceCard`, `surfaceElevated`, `controlActiveBg`) with WCAG 2.1 AAA contrast compliance.
* **Fast TV App Launches:** Colored hardware keys (`CUSTOM_1` – `CUSTOM_3`) trigger native non-blocking `media_player.select_source` intents, launching Netflix, Plex, and ABC iview in under 150ms without blocking Home Assistant's ADB connection.
* **Selective WebSocket State Publishing:** `HaClient` updates its internal store in-place for all entities while filtering Compose snapshot writes and `StateFlow` emissions to observed UI, screensaver, and alarm/alert entities—eliminating main-thread recomposition and GC churn from chatty background sensors.
* **Dock & Off-Dock Power Management:** Asymmetric dock/undock debounce (`dock_debounce_seconds: 2`, `undock_debounce_seconds: 15`) absorbs pogo-pin contact drops on the dock while allowing the display, `rmm/stream` radar subscription, and entity filter to sleep cleanly when undocked.
* **Boot Persistence & Launcher Recovery:** Configured with `RECEIVE_BOOT_COMPLETED`, automatic `BootReceiver` relaunch, and `.HomeAlias` default launcher locking so the remote always boots straight into Astrion even after low-battery shutdowns.
* **RTC Drift Prevention:** Root `astrion_timesync` init daemon synchronizes the system clock and hardware RTC from the local Home Assistant HTTP `Date:` header on boot, on WebSocket reconnect (`debug.astrion.timesync=1`), and every 10 minutes without requiring external internet NTP access.

---

## Build and Install

### Prerequisites
* JDK 17
* Android SDK (API 27+)
* Remote build script or Gradle: `/home/bae/Documents/claude/remote-build.sh ./gradlew assembleRelease`

### Setup Instructions

1. **Add your Home Assistant details:** Copy `secrets.properties.example` to `secrets.properties` (gitignored):
   ```properties
   haUrl=http://<your-ha-ip>:8123
   haToken=<long-lived access token>
   ```

2. **Build the release APK:**
   ```bash
   /home/bae/Documents/claude/remote-build.sh ./gradlew assembleRelease
   ```

3. **Install over ADB and compile ahead-of-time (AOT):**
   ```bash
   adb install -r app/build/outputs/apk/release/app-release.apk
   adb shell cmd package compile -m speed -f com.custom.astrion
   ```

4. **Set as Default Launcher and Lock Permissions:**
   ```bash
   adb shell pm enable com.custom.astrion/.HomeAlias
   adb shell cmd package set-home-activity com.custom.astrion/.HomeAlias
   adb shell appops set com.custom.astrion WRITE_SETTINGS allow
   adb shell settings put global ntp_polling_interval 1800000
   ```

---

## Configuration

Layout, buttons, and entities are managed via `/sdcard/astrion/dashboard.json`:

| File | Role |
|---|---|
| `/sdcard/astrion/dashboard.json` | Live configuration on the remote |
| `device/config/dashboard.json` | Bundled default configuration inside the APK |
| `/sdcard/astrion/floorplan.png` | Apartment floorplan image |

Push new configurations over ADB:
```bash
adb push device/config/dashboard.json /sdcard/astrion/dashboard.json
```

---

## Physical Hardware Button Mapping

The HA100 physical button matrix:

| Physical Key | Android Keycode | Default Mapping / Action |
|---|---|---|
| `LIGHT` | 134 | Navigate to **BÆOREMOTE** (Main Page) |
| `CURTAIN` | 135 | Navigate to **BÆOVISION** (TV Page) |
| `SCENE` | 136 | Navigate to **BÆOSOUND** (Media Page) |
| `AC` | 137 | Navigate to **BÆOLIVING** (Climate Page) |
| `CUSTOM_1` (Red) | 138 | Launch Netflix (`com.netflix.ninja`) |
| `CUSTOM_2` (Yellow) | 139 | Launch Plex (`com.plexapp.android`) |
| `CUSTOM_3` (Cyan) | 140 | Launch ABC iview (`au.net.abc.iview`) |
| `CUSTOM_4` (Blue) | 141 | Run TV Ambient Show script |
| `VOLUME_UP` / `DOWN` | 24 / 25 | Adjust volume + trigger floating Volume OSD |
| `MUTE` | 91 / 164 | Toggle mute + trigger floating Volume OSD |
| `MENU` (☰) | 82 | Toggle IR Blaster Mode (Samsung TV control) |
| `VOICE` (🎤) | 133 | Trigger Home Assistant Assist satellite session |
| `D-PAD` & `CENTER` | 19–23 | TV navigation & select commands |

---

## Project Structure

All application source code is located under `app/src/main/java/com/custom/astrion/`:

```
com.custom.astrion/
├── AstrionApp.kt            # App entry point, Koin DI & Registry initialization
├── MainActivity.kt          # Window host, gesture router, lifecycle management
├── BootReceiver.kt          # Automatic relaunch on system boot & recovery
├── viewmodel/               # DashboardViewModel, PowerViewModel, InputViewModel
├── di/                      # Koin Dependency Injection modules
├── ha/                      # Home Assistant WebSocket & HTTP client
├── cards/                   # CardRegistry and implementation renderers
├── config/                  # AppConfigRepository and reactive config loader
├── ui/                      # Dashboard, PageName, Screensaver, VolumePopup, Theme
├── input/                   # HardwareKeys, KeyDispatcher, and hotkey routing
├── power/                   # Battery state machine, dock detection, motion wake
├── voice/                   # Home Assistant Assist satellite audio streaming
└── ir/                      # Built-in IR blaster transmitter & Samsung codes
```

---

## Notes & Licensing

* **Licence:** MIT ([`LICENSE`](LICENSE)). Manrope and Syne fonts are used under the SIL Open Font License ([`licenses/`](licenses)).
* **Wireless ADB Security:** The HA100 firmware runs `userdebug`. See [`device/README.md`](device/README.md) for network lockdown instructions.
* **Plex for Android TV:** Deep linking uses standard Android intents supported by classic Plex for Android TV (10.x).
