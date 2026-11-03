# Device-side files (HA100 remotes)

These are **not** part of the app. They are pushed to the remotes over adb and
live on the device; they are kept here so the only copy isn't on the hardware.

| File | Goes to | Purpose |
|---|---|---|
| `adbwifi.rc` | `/vendor/etc/init/adbwifi.rc` | Boot hook: sets adbd's TCP port (persisted, so wireless adb is up before boot completes), applies `adbfw.sh` as soon as netd starts and again at boot |
| `adbfw.sh` | `/vendor/bin/adbfw.sh` | Restricts adbd (5555) to the admin workstation |
| `adbfw-off.sh` | `/data/local/tmp/adbfw-off.sh` | Reopens adbd to the whole LAN (recovery) |
| `timesync.rc` | `/vendor/etc/init/timesync.rc` | Init hook: triggers `timesync` on boot (`sys.boot_completed=1`), daemon loop (`timesync_d`), and on property `debug.astrion.timesync=1` (root only: the app can't set it) |
| `timesync.sh` | `/vendor/bin/timesync.sh` | Real-time clock & hardware RTC synchronizer against Home Assistant with NTP fallback |
| `bootguard.rc` | `/vendor/etc/init/bootguard.rc` | Runs `bootguard.sh` at post-fs-data (before system_server), when zygote restarts mid-boot, and at boot completed |
| `bootguard.sh` | `/vendor/bin/bootguard.sh` | Moves `package_cache` / dalvik-cache files with unreadable eMMC sectors (the `de ad ad de` frame) aside and clears the rest of `package_cache`, so a rotten cache entry can't boot-loop system_server. At boot completed it mounts tmpfs over the app's `cache/` (32 MB, art cache) and `files/` (4 MB, entity snapshot), so the app writes nothing to flash; both start empty after a reboot |
| `wifips.rc` / `wifips.sh` | `/vendor/etc/init/wifips.rc`, `/vendor/bin/wifips.sh` | Wi-Fi power save off while the remote charges (pings 42 ms avg / 205 ms max with it on, 23 / 95 ms off), back on when it's lifted, for battery. Re-checked every 20 s because the driver turns it back on after a reconnect |

## Why the firewall exists

The remotes ship a `userdebug` build: `ro.debuggable=1`, `ro.adb.secure`
unset, and an **empty** `/data/misc/adb/`. adbd therefore accepts any client on
the LAN with no key authorisation — anyone on the network gets a root shell.
`ro.adb.secure` is a read-only build property and cannot be enabled at runtime,
so the restriction has to be applied at the network layer instead.

`adbfw.sh` matches on the workstation's **MAC** as well as its IP. The
workstation is on DHCP, and pinning to an IP alone means a new lease locks
everyone out of a device that can then only be recovered over USB.

The design fails **open**: if the script can't run, adb is reachable exactly as
it was before, rather than the device becoming unreachable.

## Installing and checking

`files.txt` lists every file above with where it goes and its mode. Two
scripts work from it, so what's on a remote always comes from the repo:

```sh
device/install.sh -n 10.0.0.113        # dry run: print every adb command
device/install.sh 10.0.0.113           # push files.txt, re-apply the firewall, push device-113.json
device/install.sh --apk app/build/outputs/apk/release/app-release.apk --layout 10.0.0.113
device/status.sh 10.0.0.113            # read-only
```

The repo's `adbfw.sh` and `config/dashboard.json` are sanitised templates.
The real ones (the admin allow-list, the Plex host, the calendar) live in
`device/local/`, which is gitignored; both scripts use a file there in place
of the template, and `install.sh` refuses to push a template that still has
its placeholders. See `local/README.md`.

`install.sh` needs a root adb shell (the userdebug build gives one), remounts
`/vendor` read-write only while it copies, and leaves it read-only again. The
`.rc` files take effect on the next reboot. `--apk` also AOT-compiles the app,
grants it `WRITE_SETTINGS` and whitelists it from Doze; `--layout` pushes the
shared `config/dashboard.json`.

`status.sh` changes nothing. It prints each file on the remote as `ok`,
`DIFFERS` (with the diff against the repo) or `MISSING`, then the kernel
wakelocks (`/sys/power/wake_lock`), the screen timeout, whether the app is on
the Doze whitelist, and which `report_entity` each config file names.

Nothing here, and nothing in the app, releases the kernel's `suspend_blocker`
wakelock. If `status.sh` shows it missing from `/sys/power/wake_lock`, it was
released by hand; reboot the remote to get it back.

## Why wireless adb comes up early

`sys.boot_completed` is only set once the home app has started and gone idle.
If the home app fails (see `docs/POWER.md`, "Making it the home app"), a hook
that waits for it never runs and the remote is unreachable without USB. The
hook therefore also sets `persist.adb.tcp.port`, which adbd reads when it
starts on later boots, and applies the firewall as soon as netd is running.
The first boot after installing the new `adbwifi.rc` still waits for boot to
complete, as before.

## Changing the allowed workstation

Edit `ALLOW_MAC` / `ALLOW_IP` in `adbfw.sh`, push it again, and re-run it.

## Recovery

While you still have access: `adb shell /data/local/tmp/adbfw-off.sh`, or
`iptables -F INPUT`. iptables rules are kernel-memory only, so a **reboot also
clears them** — but the boot hook puts them straight back, so to get in from a
different machine you must first remove `start adbfw` from
`/vendor/etc/init/adbwifi.rc`. If you are locked out entirely, USB adb is
unaffected: it never goes through the INPUT chain.

## Per-remote setup after installing the app

```sh
adb -s <ip>:5555 install -r app-release.apk
adb -s <ip>:5555 shell cmd package compile -m speed -f com.custom.astrion   # AOT; default is interpreted
adb -s <ip>:5555 shell appops set com.custom.astrion WRITE_SETTINGS allow   # lets the app hold the screen timeout
```

`config/dashboard.json` is a sanitised copy of the live config: the Plex host
and calendar entity are placeholders. It names no remote, so the same file can
go to both. Each remote's identity (`power.report_entity` / `report_name`, so
HA can tell the remotes apart) is in its own `config/device-<last octet>.json`,
pushed as `/sdcard/astrion/device.json`:

```sh
adb -s 10.0.0.113:5555 push config/dashboard.json  /sdcard/astrion/dashboard.json
adb -s 10.0.0.113:5555 push config/device-113.json /sdcard/astrion/device.json
```

The app picks both up within a second (it watches the folder). See
`docs/POWER.md`, "Per-remote settings".

Both device files also set `screen_timeout_seconds: -1` (never time out)
for the first deployment of the new dock handling, so a remote that
misjudges its dock stays reachable. Remove it once the remotes have proved
themselves; the shared 120 s then applies off the dock.
