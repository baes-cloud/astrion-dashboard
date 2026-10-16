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
  run adb -s "$TARGET" push "$src" "$dest" </dev/null
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
  run adb -s "$TARGET" push config/dashboard.json /sdcard/astrion/dashboard.json
fi

if [ -n "$APK" ]; then
  run adb -s "$TARGET" install -r "$APK"
  sh_ cmd package compile -m speed -f $PKG
  sh_ appops set $PKG WRITE_SETTINGS allow
  sh_ dumpsys deviceidle whitelist +$PKG
fi

echo "Done. Reboot $TARGET for changed .rc files to take effect; then run device/status.sh $IP."
