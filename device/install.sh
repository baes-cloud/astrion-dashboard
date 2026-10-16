#!/usr/bin/env bash
# Installs the device-side files (files.txt) on one HA100 remote, so what's on
# /vendor always comes from the repo. Optionally installs the APK and pushes
# the layout. Check the result with status.sh.
#
#   usage: device/install.sh [-n] [--apk app-release.apk] [--layout] <ip>
#     -n          print what would be done, change nothing
#     --apk F     also install this APK, AOT-compile it, grant WRITE_SETTINGS
#                 and whitelist it from Doze
#     --layout    also push config/dashboard.json as /sdcard/astrion/dashboard.json
#
# A file in device/local/ (gitignored: the real allow-list, the real layout)
# is used in place of the repo template with the same name; see local/README.md.
# config/device-<last octet>.json (this remote's identity) is pushed as
# /sdcard/astrion/device.json whenever it exists. The init .rc files take
# effect on the next reboot; the firewall is re-applied straight away.
set -euo pipefail
HERE=$(cd "$(dirname "$0")" && pwd)
usage() { sed -n '2,/^set /p' "$0" | sed '$d'; }

DRY=0; APK=; LAYOUT=0; IP=
while [ $# -gt 0 ]; do
  case $1 in
    -n) DRY=1 ;;
    --apk) APK=$(realpath "$2"); shift ;;
    --layout) LAYOUT=1 ;;
    -h|--help) usage; exit 0 ;;
    -*) echo "unknown option $1" >&2; exit 2 ;;
    *) IP=$1 ;;
  esac
  shift
done
[ -n "$IP" ] || { usage >&2; exit 2; }
cd "$HERE"

# device/local/<name> if it exists, else the repo template.
pick() { if [ -f "local/$(basename "$1")" ]; then echo "local/$(basename "$1")"; else echo "$1"; fi; }
LAYOUT_SRC=$(pick config/dashboard.json)

# The repo templates are sanitised. Pushing one as it is would lock adb out
# (adbfw.sh allows only its placeholder MAC/IP) or break the live layout.
if grep -q 'AA:BB:CC:DD:EE:FF\|192\.0\.2\.' "$(pick adbfw.sh)"; then
  echo "$(pick adbfw.sh) has the placeholder ALLOW_MAC/ALLOW_IP: put the real one in device/local/adbfw.sh" >&2
  exit 1
fi
if [ $LAYOUT -eq 1 ] && grep -q 'YOUR_\|calendar.your_calendar' "$LAYOUT_SRC"; then
  echo "$LAYOUT_SRC has placeholders: put the real layout in device/local/dashboard.json" >&2
  exit 1
fi
case $IP in *:*) TARGET=$IP ;; *) TARGET=$IP:5555 ;; esac
OCTET=${IP%%:*}; OCTET=${OCTET##*.}
PKG=com.custom.astrion

run() { echo "+ $*"; [ $DRY -eq 1 ] || "$@"; }
sh_() { run adb -s "$TARGET" shell "$@"; }

adb connect "$TARGET" >/dev/null
[ "$(adb -s "$TARGET" shell id -u | tr -d '\r')" = 0 ] || { echo "adb shell on $TARGET isn't root" >&2; exit 1; }

sh_ mount -o rw,remount /vendor
trap '[ $DRY -eq 1 ] || adb -s "$TARGET" shell mount -o ro,remount /vendor' EXIT
grep -v '^#' files.txt | while read -r src dest mode; do
  [ -n "$src" ] || continue
  run adb -s "$TARGET" push "$(pick "$src")" "$dest" </dev/null
  sh_ chmod "$mode" "$dest" </dev/null
done
sh_ /vendor/bin/adbfw.sh

if [ -f "config/device-$OCTET.json" ]; then
  sh_ mkdir -p /sdcard/astrion
  run adb -s "$TARGET" push "config/device-$OCTET.json" /sdcard/astrion/device.json
else
  echo "note: no config/device-$OCTET.json, this remote reports no battery sensor" >&2
fi
if [ $LAYOUT -eq 1 ]; then
  sh_ mkdir -p /sdcard/astrion
  run adb -s "$TARGET" push "$LAYOUT_SRC" /sdcard/astrion/dashboard.json
fi

if [ -n "$APK" ]; then
  run adb -s "$TARGET" install -r "$APK"
  sh_ cmd package compile -m speed -f $PKG
  sh_ appops set $PKG WRITE_SETTINGS allow
  sh_ dumpsys deviceidle whitelist +$PKG
fi

echo "Done. Reboot $TARGET for changed .rc files to take effect; then run device/status.sh $IP."
