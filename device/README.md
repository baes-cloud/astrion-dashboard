# Device-side files (HA100 remotes)

These are **not** part of the app. They are pushed to the remotes over adb and
live on the device; they are kept here so the only copy isn't on the hardware.

| File | Goes to | Purpose |
|---|---|---|
| `adbwifi.rc` | `/vendor/etc/init/adbwifi.rc` | Boot hook: sets adbd's TCP port, then applies `adbfw.sh` |
| `adbfw.sh` | `/vendor/bin/adbfw.sh` | Restricts adbd (5555) to the admin workstation |
| `adbfw-off.sh` | `/data/local/tmp/adbfw-off.sh` | Reopens adbd to the whole LAN (recovery) |

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

## Installing

```sh
adb -s <ip>:5555 shell mount -o rw,remount /vendor
adb -s <ip>:5555 push adbfw.sh   /vendor/bin/adbfw.sh
adb -s <ip>:5555 push adbwifi.rc /vendor/etc/init/adbwifi.rc
adb -s <ip>:5555 shell 'chmod 755 /vendor/bin/adbfw.sh; mount -o ro,remount /vendor'
adb -s <ip>:5555 shell /vendor/bin/adbfw.sh
```

## Changing the allowed workstation

Edit `ALLOW_MAC` / `ALLOW_IP` in `adbfw.sh`, push it again, and re-run it.

## Recovery

While you still have access: `adb shell /data/local/tmp/adbfw-off.sh`, or
`iptables -F INPUT`. iptables rules are kernel-memory only, so a **reboot also
clears them** — but the boot hook puts them straight back, so to get in from a
different machine you must first remove `start adbfw` from
`/vendor/etc/init/adbwifi.rc`. If you are locked out entirely, USB adb is
unaffected: it never goes through the INPUT chain.
