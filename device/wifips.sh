#!/system/bin/sh
# Wi-Fi power save on the HA100 follows the dock.
#
# With power save on, the radio dozes between beacons and pings to the remote
# run 40 ms on average with 200 ms spikes (2026-11-04: 42 ms avg, 205 ms max;
# off: 23 ms, 95 ms). Off costs battery, so it is off only while the remote
# is charging and back on when it's lifted. The driver turns power save back
# on after every reconnect, hence the loop rather than a one-off.

IFACE=$(getprop wifi.interface)
[ -n "$IFACE" ] || IFACE=wlan0

charging() {
    for s in ac usb wireless; do
        [ "$(cat /sys/class/power_supply/$s/online 2>/dev/null)" = 1 ] && return 0
    done
    return 1
}

while :; do
    if charging; then want=off; else want=on; fi
    case "$(iw dev "$IFACE" get power_save 2>/dev/null)" in
        *": $want"*) ;;
        *": o"*) iw dev "$IFACE" set power_save "$want" && log -p i -t wifips "power save $want" ;;
    esac
    sleep 20
done
