#!/bin/sh
# ==============================================================================
# Astrion HA100: MediaTek MT6580 Hardware & Kernel Sysfs Tuning Suite
# Target Architecture: 4x ARM Cortex-A7 @ 1.3GHz, ARM Mali-400 MP2, 1GB LPDDR3
# Target OS: Android 8.1 Oreo (API 27), Linux Kernel ~3.18
# ==============================================================================

# Check for Android /system/bin/sh vs standard POSIX /bin/sh
if [ -x /system/bin/sh ] && [ -z "${_SHELL_RESOLVED:-}" ]; then
    export _SHELL_RESOLVED=1
    exec /system/bin/sh "$0" "$@"
fi

set -u

TAG="mt6580_tuning"
SYS_ROOT="${SYS_ROOT:-}"
SYS_ROOT="${SYS_ROOT%/}"
DRY_RUN="${DRY_RUN:-0}"

# ------------------------------------------------------------------------------
# Logging Helpers
# ------------------------------------------------------------------------------
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

log_err() {
    if command -v log >/dev/null 2>&1; then
        log -p e -t "$TAG" "$*"
    fi
    printf "[%s] [ERROR] %s\n" "$TAG" "$*" >&2
}

# ------------------------------------------------------------------------------
# Defensive Sysfs Node Writer
# ------------------------------------------------------------------------------
write_node() {
    node_path="$1"
    node_val="$2"

    if [ "$DRY_RUN" -eq 1 ]; then
        log_info "DRY-RUN: Would write '$node_val' -> $node_path"
        return 0
    fi

    if [ -e "$node_path" ]; then
        if ! ( printf "%s\n" "$node_val" > "$node_path" ) 2>/dev/null; then
            # Retry with chmod 644 if permission issue
            chmod 644 "$node_path" 2>/dev/null || true
            if ! ( printf "%s\n" "$node_val" > "$node_path" ) 2>/dev/null; then
                log_warn "Failed to write '$node_val' to $node_path"
                return 1
            fi
        fi
        return 0
    else
        log_warn "Node not found: $node_path (skipped)"
        return 0
    fi
}

# ------------------------------------------------------------------------------
# 1. Interactive CPU Governor Configuration
# ------------------------------------------------------------------------------
tune_cpu_interactive() {
    log_info "Configuring CPU governor and interactive governor tunables..."

    # Set governor to interactive across all online/available CPU cores
    cpu_count=0
    for cpu_freq in "${SYS_ROOT}"/sys/devices/system/cpu/cpu[0-3]/cpufreq; do
        if [ -d "$cpu_freq" ]; then
            write_node "$cpu_freq/scaling_governor" "interactive"
            cpu_count=$((cpu_count + 1))
        fi
    done
    log_info "Set interactive governor on $cpu_count CPU core(s)"

    # Determine interactive governor sysfs interface path
    gov_dir="${SYS_ROOT}/sys/devices/system/cpu/cpufreq/interactive"
    if [ ! -d "$gov_dir" ]; then
        gov_dir="${SYS_ROOT}/sys/devices/system/cpu/cpu0/cpufreq/interactive"
    fi

    if [ -d "$gov_dir" ]; then
        # Interactive governor calibration for MT6580 Cortex-A7:
        # hispeed_freq: 1.04 GHz for immediate responsiveness under load
        write_node "$gov_dir/hispeed_freq" "1040000"
        # min_sample_time: 40ms (40000 us) to prevent rapid frequency oscillation
        write_node "$gov_dir/min_sample_time" "40000"
        # go_hispeed_load: 85% CPU load jumps immediately to hispeed_freq
        write_node "$gov_dir/go_hispeed_load" "85"
        # timer_rate & timer_slack: 20ms evaluation interval
        write_node "$gov_dir/timer_rate" "20000"
        write_node "$gov_dir/timer_slack" "20000"
        # target_loads: load thresholds before ramping frequency further
        write_node "$gov_dir/target_loads" "80 1040000:85 1300000:90"
        # above_hispeed_delay: delay before scaling above hispeed_freq
        write_node "$gov_dir/above_hispeed_delay" "20000 1040000:40000"
        # boostpulse_duration: boost pulse window of 40ms
        write_node "$gov_dir/boostpulse_duration" "40000"
        log_info "Interactive governor tunables applied at $gov_dir"
    else
        log_warn "Interactive governor directory not found (neither /cpufreq/interactive nor /cpu0/cpufreq/interactive)"
    fi
}

# ------------------------------------------------------------------------------
# 2. HPS (MediaTek Hotplug Strategy) Configuration
# ------------------------------------------------------------------------------
tune_hps() {
    hps_dir="${SYS_ROOT}/proc/hps"
    if [ -d "$hps_dir" ]; then
        log_info "Configuring MediaTek HPS (Hotplug Strategy)..."
        write_node "$hps_dir/enabled" "1"
        write_node "$hps_dir/up_threshold" "90"
        write_node "$hps_dir/down_threshold" "40"
        write_node "$hps_dir/rush_boost_enabled" "0"
        log_info "HPS enabled (up_threshold=90, down_threshold=40, rush_boost=0)"
    else
        log_warn "HPS proc directory ($hps_dir) not present on this kernel build"
    fi
}

set_screen_off_hotplug() {
    log_info "Triggering screen-off hotplug state (offlining cores 1-3)..."
    write_node "${SYS_ROOT}/sys/devices/system/cpu/cpu1/online" "0"
    write_node "${SYS_ROOT}/sys/devices/system/cpu/cpu2/online" "0"
    write_node "${SYS_ROOT}/sys/devices/system/cpu/cpu3/online" "0"

    num_base="${SYS_ROOT}/proc/hps/num_base_perf_serv"
    if [ -e "$num_base" ]; then
        write_node "$num_base" "1"
    fi
    num_limit="${SYS_ROOT}/proc/hps/num_limit_high"
    if [ -e "$num_limit" ]; then
        write_node "$num_limit" "1"
    fi
    log_info "Screen-off hotplug state active (cores 1-3 offline, max cores clamped to 1)"
}

set_screen_on_hotplug() {
    log_info "Restoring screen-on hotplug state (onlining cores 1-3)..."
    num_base="${SYS_ROOT}/proc/hps/num_base_perf_serv"
    if [ -e "$num_base" ]; then
        write_node "$num_base" "0"
    fi
    num_limit="${SYS_ROOT}/proc/hps/num_limit_high"
    if [ -e "$num_limit" ]; then
        write_node "$num_limit" "0"
    fi

    write_node "${SYS_ROOT}/sys/devices/system/cpu/cpu1/online" "1"
    write_node "${SYS_ROOT}/sys/devices/system/cpu/cpu2/online" "1"
    write_node "${SYS_ROOT}/sys/devices/system/cpu/cpu3/online" "1"
    log_info "Screen-on hotplug state active (cores 1-3 online, max cores unclamped)"
}

# ------------------------------------------------------------------------------
# 3. 512MB LZ4 zRAM & Virtual Memory Swappiness Tuning
# ------------------------------------------------------------------------------
tune_zram_vm() {
    log_info "Configuring 512MB LZ4 zRAM compressed swap..."

    zram_dev="${SYS_ROOT}/dev/block/zram0"
    zram_sys="${SYS_ROOT}/sys/block/zram0"

    if [ "$DRY_RUN" -eq 0 ]; then
        # Deactivate existing swap if present
        if command -v swapoff >/dev/null 2>&1; then
            if [ -b "$zram_dev" ] || [ -e "$zram_dev" ]; then
                swapoff "$zram_dev" 2>/dev/null || true
            fi
        fi
    fi

    if [ -d "$zram_sys" ]; then
        # Reset zRAM device before reconfiguring
        write_node "$zram_sys/reset" "1"
        # Select LZ4 compression algorithm
        write_node "$zram_sys/comp_algorithm" "lz4"
        # Set disk size to 512MB (512 * 1024 * 1024 = 536870912 bytes)
        write_node "$zram_sys/disksize" "536870912"

        # Format and activate swap partition
        if [ "$DRY_RUN" -eq 0 ]; then
            if [ -b "$zram_dev" ] || [ -e "$zram_dev" ]; then
                if command -v mkswap >/dev/null 2>&1; then
                    mkswap "$zram_dev" >/dev/null 2>&1 || true
                fi
                if command -v swapon >/dev/null 2>&1; then
                    swapon "$zram_dev" >/dev/null 2>&1 || true
                fi
            fi
        fi
        log_info "512MB LZ4 zRAM configured successfully"
    else
        log_warn "zRAM sysfs node ($zram_sys) not found"
    fi

    # Virtual Memory Subsystem Calibration
    log_info "Tuning Linux VM swappiness and cache pressure..."
    write_node "${SYS_ROOT}/proc/sys/vm/swappiness" "70"
    write_node "${SYS_ROOT}/proc/sys/vm/vfs_cache_pressure" "100"
    write_node "${SYS_ROOT}/proc/sys/vm/dirty_ratio" "20"
    write_node "${SYS_ROOT}/proc/sys/vm/dirty_background_ratio" "5"
    log_info "VM tuning applied (swappiness=70, vfs_cache_pressure=100, dirty_ratio=20, dirty_bg=5)"
}

# ------------------------------------------------------------------------------
# 4. Low Memory Killer (LMK) Minfree Re-Calibration
# ------------------------------------------------------------------------------
tune_lmk() {
    log_info "Recalibrating Low Memory Killer (LMK) minfree thresholds for 1GB RAM..."

    # Recalibrated page thresholds for 1GB RAM (4KB per page):
    # Tier 1: FOREGROUND_APP      (adj   0):  72 MB -> 18432 pages
    # Tier 2: VISIBLE_APP         (adj 100):  90 MB -> 23040 pages
    # Tier 3: SECONDARY_SERVER    (adj 200): 108 MB -> 27648 pages
    # Tier 4: HIDDEN_APP          (adj 500): 126 MB -> 32256 pages
    # Tier 5: CONTENT_PROVIDER    (adj 700): 144 MB -> 36864 pages
    # Tier 6: EMPTY_APP           (adj 900): 180 MB -> 46080 pages
    lmk_params="${SYS_ROOT}/sys/module/lowmemorykiller/parameters"

    write_node "$lmk_params/minfree" "18432,23040,27648,32256,36864,46080"
    write_node "$lmk_params/adj" "0,100,200,500,700,900"
    log_info "LMK recalibrated: minfree=18432,23040,27648,32256,36864,46080"
}

# ------------------------------------------------------------------------------
# 5. Mali-400 GPU GED DVFS and Touch Input Boost
# ------------------------------------------------------------------------------
tune_gpu_ged_boost() {
    log_info "Configuring MediaTek GED GPU DVFS and touch boost..."

    # MediaTek Game Engine Driver (GED) Parameters
    ged_params="${SYS_ROOT}/sys/module/ged/parameters"
    if [ -d "$ged_params" ]; then
        write_node "$ged_params/gpu_dvfs_enable" "1"
        write_node "$ged_params/boost_gpu_enable" "1"
        write_node "$ged_params/ged_boost_enable" "1"
        write_node "$ged_params/gx_force_cpu_boost" "1"
        log_info "Mali-400 GED GPU DVFS enabled with force CPU boost"
    else
        log_warn "GED parameters directory ($ged_params) not found"
    fi

    # Touch Input Boost Latency Reduction (40ms boost on touch)
    cpu_boost="${SYS_ROOT}/sys/module/cpu_boost/parameters"
    if [ -d "$cpu_boost" ] || [ -e "$cpu_boost/input_boost_ms" ]; then
        write_node "$cpu_boost/input_boost_ms" "40"
        write_node "$cpu_boost/input_boost_freq" "0:1040000 1:1040000 2:1040000 3:1040000"
        log_info "Touch input boost configured: 40ms @ 1.04 GHz across all cores"
    else
        log_warn "CPU boost parameters directory ($cpu_boost) not found"
    fi
}

# ------------------------------------------------------------------------------
# Query & Status Dump
# ------------------------------------------------------------------------------
dump_status() {
    printf "==================================================\n"
    printf "Astrion HA100 MT6580 Kernel Tuning Status\n"
    printf "SYS_ROOT: %s\n" "${SYS_ROOT:-/(native)}"
    printf "==================================================\n"

    printf "\n[CPU Governor]\n"
    for c in 0 1 2 3; do
        gov_f="${SYS_ROOT}/sys/devices/system/cpu/cpu${c}/cpufreq/scaling_governor"
        online_f="${SYS_ROOT}/sys/devices/system/cpu/cpu${c}/online"
        [ -f "$online_f" ] && online_val=$(cat "$online_f" 2>/dev/null) || online_val="1 (core0)"
        [ -f "$gov_f" ] && gov_val=$(cat "$gov_f" 2>/dev/null) || gov_val="N/A"
        printf "  cpu%d: online=%s, governor=%s\n" "$c" "$online_val" "$gov_val"
    done

    gov_dir="${SYS_ROOT}/sys/devices/system/cpu/cpufreq/interactive"
    [ ! -d "$gov_dir" ] && gov_dir="${SYS_ROOT}/sys/devices/system/cpu/cpu0/cpufreq/interactive"
    if [ -d "$gov_dir" ]; then
        printf "  interactive/hispeed_freq:       %s\n" "$(cat "$gov_dir/hispeed_freq" 2>/dev/null || echo N/A)"
        printf "  interactive/min_sample_time:    %s\n" "$(cat "$gov_dir/min_sample_time" 2>/dev/null || echo N/A)"
        printf "  interactive/go_hispeed_load:    %s\n" "$(cat "$gov_dir/go_hispeed_load" 2>/dev/null || echo N/A)"
        printf "  interactive/timer_rate:         %s\n" "$(cat "$gov_dir/timer_rate" 2>/dev/null || echo N/A)"
        printf "  interactive/timer_slack:        %s\n" "$(cat "$gov_dir/timer_slack" 2>/dev/null || echo N/A)"
        printf "  interactive/target_loads:       %s\n" "$(cat "$gov_dir/target_loads" 2>/dev/null || echo N/A)"
        printf "  interactive/above_hispeed_delay:%s\n" "$(cat "$gov_dir/above_hispeed_delay" 2>/dev/null || echo N/A)"
        printf "  interactive/boostpulse_duration:%s\n" "$(cat "$gov_dir/boostpulse_duration" 2>/dev/null || echo N/A)"
    fi

    printf "\n[HPS Hotplug Strategy]\n"
    hps="${SYS_ROOT}/proc/hps"
    if [ -d "$hps" ]; then
        printf "  enabled:              %s\n" "$(cat "$hps/enabled" 2>/dev/null || echo N/A)"
        printf "  up_threshold:         %s\n" "$(cat "$hps/up_threshold" 2>/dev/null || echo N/A)"
        printf "  down_threshold:       %s\n" "$(cat "$hps/down_threshold" 2>/dev/null || echo N/A)"
        printf "  rush_boost_enabled:   %s\n" "$(cat "$hps/rush_boost_enabled" 2>/dev/null || echo N/A)"
        printf "  num_base_perf_serv:   %s\n" "$(cat "$hps/num_base_perf_serv" 2>/dev/null || echo N/A)"
        printf "  num_limit_high:       %s\n" "$(cat "$hps/num_limit_high" 2>/dev/null || echo N/A)"
    else
        printf "  HPS: Not present\n"
    fi

    printf "\n[zRAM & VM Tuning]\n"
    zram="${SYS_ROOT}/sys/block/zram0"
    if [ -d "$zram" ]; then
        printf "  comp_algorithm:       %s\n" "$(cat "$zram/comp_algorithm" 2>/dev/null || echo N/A)"
        printf "  disksize:             %s bytes\n" "$(cat "$zram/disksize" 2>/dev/null || echo N/A)"
    fi
    printf "  vm.swappiness:        %s\n" "$(cat "${SYS_ROOT}/proc/sys/vm/swappiness" 2>/dev/null || echo N/A)"
    printf "  vm.vfs_cache_pressure:%s\n" "$(cat "${SYS_ROOT}/proc/sys/vm/vfs_cache_pressure" 2>/dev/null || echo N/A)"
    printf "  vm.dirty_ratio:       %s\n" "$(cat "${SYS_ROOT}/proc/sys/vm/dirty_ratio" 2>/dev/null || echo N/A)"
    printf "  vm.dirty_bg_ratio:    %s\n" "$(cat "${SYS_ROOT}/proc/sys/vm/dirty_background_ratio" 2>/dev/null || echo N/A)"

    printf "\n[Low Memory Killer]\n"
    lmk="${SYS_ROOT}/sys/module/lowmemorykiller/parameters"
    printf "  minfree:              %s\n" "$(cat "$lmk/minfree" 2>/dev/null || echo N/A)"
    printf "  adj:                  %s\n" "$(cat "$lmk/adj" 2>/dev/null || echo N/A)"

    printf "\n[GPU GED & Touch Boost]\n"
    ged="${SYS_ROOT}/sys/module/ged/parameters"
    if [ -d "$ged" ]; then
        printf "  gpu_dvfs_enable:      %s\n" "$(cat "$ged/gpu_dvfs_enable" 2>/dev/null || echo N/A)"
        printf "  boost_gpu_enable:     %s\n" "$(cat "$ged/boost_gpu_enable" 2>/dev/null || echo N/A)"
        printf "  ged_boost_enable:     %s\n" "$(cat "$ged/ged_boost_enable" 2>/dev/null || echo N/A)"
        printf "  gx_force_cpu_boost:   %s\n" "$(cat "$ged/gx_force_cpu_boost" 2>/dev/null || echo N/A)"
    fi
    boost="${SYS_ROOT}/sys/module/cpu_boost/parameters"
    if [ -d "$boost" ]; then
        printf "  input_boost_ms:       %s\n" "$(cat "$boost/input_boost_ms" 2>/dev/null || echo N/A)"
        printf "  input_boost_freq:     %s\n" "$(cat "$boost/input_boost_freq" 2>/dev/null || echo N/A)"
    fi
    printf "==================================================\n"
}

# ------------------------------------------------------------------------------
# Entrypoint & CLI Parsing
# ------------------------------------------------------------------------------
action="all"

while [ $# -gt 0 ]; do
    case "$1" in
        --sys-root|-r)
            if [ $# -gt 1 ]; then
                shift
                SYS_ROOT="$1"
                SYS_ROOT="${SYS_ROOT%/}"
            else
                log_err "--sys-root requires a directory argument"
                exit 1
            fi
            ;;
        --dry-run|-n)
            DRY_RUN=1
            ;;
        all|boot)
            action="all"
            ;;
        screen_off)
            action="screen_off"
            ;;
        screen_on)
            action="screen_on"
            ;;
        status)
            action="status"
            ;;
        --help|-h)
            printf "Usage: %s [OPTIONS] [COMMAND]\n" "$0"
            printf "Commands:\n"
            printf "  all|boot      Apply all MT6580 boot tunables (default)\n"
            printf "  screen_off    Offline cores 1-3 for deep sleep\n"
            printf "  screen_on     Restore cores 1-3 online\n"
            printf "  status        Display current sysfs tunable values\n"
            printf "Options:\n"
            printf "  --sys-root, -r DIR   Target sysfs root directory\n"
            printf "  --dry-run,  -n       Simulate without modifying files\n"
            printf "  --help,     -h       Show this help message\n"
            exit 0
            ;;
        *)
            log_warn "Unknown argument: $1"
            ;;
    esac
    shift
done

case "$action" in
    all)
        log_info "Applying MT6580 hardware & kernel tuning suite..."
        tune_cpu_interactive
        tune_hps
        tune_zram_vm
        tune_lmk
        tune_gpu_ged_boost
        log_info "MT6580 hardware tuning completed successfully."
        ;;
    screen_off)
        set_screen_off_hotplug
        ;;
    screen_on)
        set_screen_on_hotplug
        ;;
    status)
        dump_status
        ;;
esac

exit 0
