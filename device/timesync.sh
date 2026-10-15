#!/system/bin/sh
# ==============================================================================
# Astrion HA100: Real-Time Clock & Hardware RTC Synchronization Service
# Synchronizes Linux kernel system clock and hardware RTC against local Home
# Assistant server (http://10.0.0.20:8123) with NTP fallback.
# ==============================================================================

TAG="astrion_timesync"
HA_HOST="10.0.0.20"
HA_PORT="8123"

log_info() {
    if command -v log >/dev/null 2>&1; then
        log -p i -t "$TAG" "$*"
    fi
    printf "[%s] [INFO] %s\n" "$TAG" "$*"
}

log_warn() {
    if command -v log >/dev/null 2>&1; then
        log -p w -t "$TAG" "$*"
    fi
    printf "[%s] [WARN] %s\n" "$TAG" "$*" >&2
}

sync_against_ha() {
    # Send HTTP HEAD request to HA server with 3-second timeout
    date_line=$(printf "HEAD / HTTP/1.1\r\nHost: %s:%s\r\nConnection: close\r\n\r\n" "$HA_HOST" "$HA_PORT" | toybox nc -w 3 "$HA_HOST" "$HA_PORT" 2>/dev/null | grep -i "^Date:" | tr -d "\r")
    
    if [ -z "$date_line" ]; then
        return 1
    fi

    date_str="${date_line#*: }"
    epoch=$(TZ=UTC toybox date -D "%a, %d %b %Y %H:%M:%S GMT" -d "$date_str" +%s 2>/dev/null)

    if [ -z "$epoch" ] || [ "$epoch" -lt 1700000000 ] 2>/dev/null; then
        return 1
    fi

    current=$(toybox date +%s)
    diff=$((epoch - current))
    if [ "$diff" -lt 0 ]; then
        abs_diff=$((-diff))
    else
        abs_diff=$diff
    fi

    if [ "$abs_diff" -ge 2 ]; then
        log_info "Drift of ${abs_diff}s detected (sys: $current, ha: $epoch). Setting clock..."
        toybox date -u @"$epoch"
        toybox hwclock -w 2>/dev/null
        log_info "Clock synchronized to $(toybox date)"
    else
        log_info "Clock within tolerance (${abs_diff}s drift). Syncing RTC."
        toybox hwclock -w 2>/dev/null
    fi
    return 0
}

sync_fallback_ntp() {
    log_warn "Home Assistant unreachable at $HA_HOST:$HA_PORT. Triggering system NTP poll..."
    am broadcast -a com.android.server.NetworkTimeUpdateService.action.POLL >/dev/null 2>&1
}

run_sync() {
    if sync_against_ha; then
        return 0
    else
        sync_fallback_ntp
        return 1
    fi
}

if [ "${1:-}" = "daemon" ]; then
    log_info "Starting timesync daemon (interval: 900s)..."
    # Wait for network interface to come up on initial boot
    sleep 10
    while true; do
        run_sync
        sleep 900
    done
else
    run_sync
fi
