#!/usr/bin/env bash
# What's actually on one HA100 remote: the device-side files (files.txt)
# against the repo, then the power settings that decide whether it stays
# online. Changes nothing.
#
#   usage: device/status.sh <ip>
set -euo pipefail
cd "$(dirname "$0")"
[ $# -eq 1 ] || { sed -n '6p' "$0" >&2; exit 2; }
case $1 in *:*) TARGET=$1 ;; *) TARGET=$1:5555 ;; esac
PKG=com.custom.astrion

adb connect "$TARGET" >/dev/null
sh_() { adb -s "$TARGET" shell "$@" </dev/null | tr -d '\r'; }

echo "== Device files ($TARGET vs repo)"
grep -v '^#' files.txt | while read -r src dest _; do
  [ -n "$src" ] || continue
  want=$(md5sum "$src" | cut -d' ' -f1)
  got=$(sh_ "md5sum $dest 2>/dev/null" | cut -d' ' -f1)
  if [ -z "$got" ]; then state=MISSING
  elif [ "$got" = "$want" ]; then state=ok
  else state=DIFFERS
  fi
  printf '  %-8s %s\n' "$state" "$dest"
  if [ "$state" = DIFFERS ]; then
    adb -s "$TARGET" shell cat "$dest" </dev/null | tr -d '\r' | diff -u --label "repo/$src" --label "$dest" "$src" - | sed 's/^/      /' || true
  fi
done

echo "== Wakelocks (/sys/power/wake_lock)"
echo "  $(sh_ cat /sys/power/wake_lock)"

echo "== Screen timeout"
echo "  $(sh_ settings get system screen_off_timeout) ms"

echo "== Doze whitelist"
w=$(sh_ dumpsys deviceidle whitelist | grep $PKG || true)
echo "  ${w:-NOT whitelisted ($PKG)}"

echo "== Battery identity"
for f in device.json dashboard.json; do
  e=$(sh_ "grep -o '\"report_entity\": *\"[^\"]*\"' /sdcard/astrion/$f 2>/dev/null" || true)
  echo "  $f: ${e:-no report_entity}"
done
