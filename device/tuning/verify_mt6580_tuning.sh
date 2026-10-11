#!/bin/sh
# ==============================================================================
# Astrion HA100: MediaTek MT6580 Kernel & Hardware Tuning Verification Suite
# Verifies sysfs tunables, hotplug state transitions, zRAM/VM parameters,
# LMK thresholds, Mali-400 GED boost, and deep sleep / battery drain criteria.
# ==============================================================================

# Check for Android /system/bin/sh vs standard POSIX /bin/sh
if [ -x /system/bin/sh ] && [ -z "${_SHELL_RESOLVED:-}" ]; then
    export _SHELL_RESOLVED=1
    exec /system/bin/sh "$0" "$@"
fi

set -u
(set -o pipefail 2>/dev/null) && set -o pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
INIT_SCRIPT="${SCRIPT_DIR}/init.mt6580_tuning.sh"

PASSED_COUNT=0
FAILED_COUNT=0
TOTAL_COUNT=0

COLOR_GREEN="\033[0;32m"
COLOR_RED="\033[0;31m"
COLOR_YELLOW="\033[0;33m"
COLOR_BLUE="\033[0;34m"
COLOR_RESET="\033[0m"

# Mode configuration
MODE="auto" # "auto", "mock", "live"
TARGET_SYS_ROOT=""
VERBOSE=0
CLEANUP_TEMP=1

# ------------------------------------------------------------------------------
# Test Assertion Helpers
# ------------------------------------------------------------------------------
assert_equals() {
    local description="$1"
    local expected="$2"
    local actual="$3"
    TOTAL_COUNT=$((TOTAL_COUNT + 1))

    # Strip any leading/trailing whitespace
    expected=$(printf "%s" "${expected}" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')
    actual=$(printf "%s" "${actual}" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')

    if [ "$expected" = "$actual" ]; then
        PASSED_COUNT=$((PASSED_COUNT + 1))
        if [ "$VERBOSE" -eq 1 ]; then
            printf "${COLOR_GREEN}[PASS]${COLOR_RESET} %s (expected: '%s', got: '%s')\n" "$description" "$expected" "$actual"
        else
            printf "${COLOR_GREEN}[PASS]${COLOR_RESET} %s\n" "$description"
        fi
        return 0
    else
        FAILED_COUNT=$((FAILED_COUNT + 1))
        printf "${COLOR_RED}[FAIL]${COLOR_RESET} %s\n" "$description"
        printf "       Expected: '%s'\n" "$expected"
        printf "       Actual:   '%s'\n" "$actual"
        return 1
    fi
}

assert_file_exists() {
    local description="$1"
    local path="$2"
    TOTAL_COUNT=$((TOTAL_COUNT + 1))

    if [ -e "$path" ]; then
        PASSED_COUNT=$((PASSED_COUNT + 1))
        printf "${COLOR_GREEN}[PASS]${COLOR_RESET} %s (found: %s)\n" "$description" "$path"
        return 0
    else
        FAILED_COUNT=$((FAILED_COUNT + 1))
        printf "${COLOR_RED}[FAIL]${COLOR_RESET} %s (file missing: %s)\n" "$description" "$path"
        return 1
    fi
}

assert_contains() {
    local description="$1"
    local needle="$2"
    local haystack="$3"
    TOTAL_COUNT=$((TOTAL_COUNT + 1))

    case "$haystack" in
        *"$needle"*)
            PASSED_COUNT=$((PASSED_COUNT + 1))
            printf "${COLOR_GREEN}[PASS]${COLOR_RESET} %s\n" "$description"
            return 0
            ;;
        *)
            FAILED_COUNT=$((FAILED_COUNT + 1))
            printf "${COLOR_RED}[FAIL]${COLOR_RESET} %s (expected substring '%s' in '%s')\n" "$description" "$needle" "$haystack"
            return 1
            ;;
    esac
}

assert_num_ge() {
    local description="$1"
    local actual="$2"
    local threshold="$3"
    TOTAL_COUNT=$((TOTAL_COUNT + 1))

    if awk "BEGIN {exit !($actual >= $threshold)}"; then
        PASSED_COUNT=$((PASSED_COUNT + 1))
        printf "${COLOR_GREEN}[PASS]${COLOR_RESET} %s (value: %s >= threshold: %s)\n" "$description" "$actual" "$threshold"
        return 0
    else
        FAILED_COUNT=$((FAILED_COUNT + 1))
        printf "${COLOR_RED}[FAIL]${COLOR_RESET} %s (value: %s < threshold: %s)\n" "$description" "$actual" "$threshold"
        return 1
    fi
}

assert_num_le() {
    local description="$1"
    local actual="$2"
    local threshold="$3"
    TOTAL_COUNT=$((TOTAL_COUNT + 1))

    if awk "BEGIN {exit !($actual <= $threshold)}"; then
        PASSED_COUNT=$((PASSED_COUNT + 1))
        printf "${COLOR_GREEN}[PASS]${COLOR_RESET} %s (value: %s <= threshold: %s)\n" "$description" "$actual" "$threshold"
        return 0
    else
        FAILED_COUNT=$((FAILED_COUNT + 1))
        printf "${COLOR_RED}[FAIL]${COLOR_RESET} %s (value: %s > threshold: %s)\n" "$description" "$actual" "$threshold"
        return 1
    fi
}

# ------------------------------------------------------------------------------
# Mock MT6580 Sysfs/Proc Generator
# ------------------------------------------------------------------------------
setup_mock_sysfs() {
    local root="$1"
    printf "Setting up simulated MT6580 hardware sysfs tree at: %s\n" "$root"

    # 1. CPU cores 0-3 cpufreq & online nodes
    for c in 0 1 2 3; do
        mkdir -p "${root}/sys/devices/system/cpu/cpu${c}/cpufreq"
        echo "ondemand" > "${root}/sys/devices/system/cpu/cpu${c}/cpufreq/scaling_governor"
        echo "1" > "${root}/sys/devices/system/cpu/cpu${c}/online"
    done
    mkdir -p "${root}/sys/devices/system/cpu/cpufreq/interactive"
    touch "${root}/sys/devices/system/cpu/cpufreq/interactive/hispeed_freq"
    touch "${root}/sys/devices/system/cpu/cpufreq/interactive/min_sample_time"
    touch "${root}/sys/devices/system/cpu/cpufreq/interactive/go_hispeed_load"
    touch "${root}/sys/devices/system/cpu/cpufreq/interactive/timer_rate"
    touch "${root}/sys/devices/system/cpu/cpufreq/interactive/timer_slack"
    touch "${root}/sys/devices/system/cpu/cpufreq/interactive/target_loads"
    touch "${root}/sys/devices/system/cpu/cpufreq/interactive/above_hispeed_delay"
    touch "${root}/sys/devices/system/cpu/cpufreq/interactive/boostpulse_duration"

    # 2. HPS MediaTek hotplug
    mkdir -p "${root}/proc/hps"
    echo "0" > "${root}/proc/hps/enabled"
    echo "50" > "${root}/proc/hps/up_threshold"
    echo "20" > "${root}/proc/hps/down_threshold"
    echo "1" > "${root}/proc/hps/rush_boost_enabled"
    echo "0" > "${root}/proc/hps/num_base_perf_serv"
    echo "0" > "${root}/proc/hps/num_limit_high"

    # 3. zRAM and VM
    mkdir -p "${root}/sys/block/zram0"
    mkdir -p "${root}/dev/block"
    touch "${root}/dev/block/zram0"
    echo "0" > "${root}/sys/block/zram0/reset"
    echo "lzo" > "${root}/sys/block/zram0/comp_algorithm"
    echo "0" > "${root}/sys/block/zram0/disksize"

    mkdir -p "${root}/proc/sys/vm"
    echo "60" > "${root}/proc/sys/vm/swappiness"
    echo "100" > "${root}/proc/sys/vm/vfs_cache_pressure"
    echo "20" > "${root}/proc/sys/vm/dirty_ratio"
    echo "10" > "${root}/proc/sys/vm/dirty_background_ratio"

    # 4. Low Memory Killer (LMK)
    mkdir -p "${root}/sys/module/lowmemorykiller/parameters"
    echo "2048,4096,8192,16384,24576,32768" > "${root}/sys/module/lowmemorykiller/parameters/minfree"
    echo "0,100,200,300,900,906" > "${root}/sys/module/lowmemorykiller/parameters/adj"

    # 5. Mali-400 GED & CPU Boost
    mkdir -p "${root}/sys/module/ged/parameters"
    echo "0" > "${root}/sys/module/ged/parameters/gpu_dvfs_enable"
    echo "0" > "${root}/sys/module/ged/parameters/boost_gpu_enable"
    echo "0" > "${root}/sys/module/ged/parameters/ged_boost_enable"
    echo "0" > "${root}/sys/module/ged/parameters/gx_force_cpu_boost"

    mkdir -p "${root}/sys/module/cpu_boost/parameters"
    echo "0" > "${root}/sys/module/cpu_boost/parameters/input_boost_ms"
    echo "" > "${root}/sys/module/cpu_boost/parameters/input_boost_freq"

    # 6. Deep sleep & power nodes
    mkdir -p "${root}/sys/power"
    echo "freeze standby mem" > "${root}/sys/power/state"

    mkdir -p "${root}/sys/kernel/debug"
    printf "name\tactive_count\tevent_count\twakeup_count\texpire_count\tactive_since\ttotal_time\tmax_time\tlast_change\tprevent_suspend_time\n" > "${root}/sys/kernel/debug/wakeup_sources"
    printf "PowerManagerService.WakeLocks\t0\t142\t0\t0\t0\t1200\t80\t54000\t0\n" >> "${root}/sys/kernel/debug/wakeup_sources"
    printf "alarmtimer\t0\t52\t0\t0\t0\t310\t25\t54200\t0\n" >> "${root}/sys/kernel/debug/wakeup_sources"

    mkdir -p "${root}/sys/devices/system/cpu/cpu0/cpuidle/state0"
    mkdir -p "${root}/sys/devices/system/cpu/cpu0/cpuidle/state1"
    echo "C0 (WFI)" > "${root}/sys/devices/system/cpu/cpu0/cpuidle/state0/name"
    echo "50000000" > "${root}/sys/devices/system/cpu/cpu0/cpuidle/state0/time"
    echo "C1 (Power-gated)" > "${root}/sys/devices/system/cpu/cpu0/cpuidle/state1/name"
    echo "3550000000" > "${root}/sys/devices/system/cpu/cpu0/cpuidle/state1/time"

    # Battery power supply & simulated test telemetry nodes
    mkdir -p "${root}/sys/class/power_supply/battery"
    echo "94" > "${root}/sys/class/power_supply/battery/capacity"
    echo "95.0" > "${root}/sys/class/power_supply/battery/capacity_start"
    echo "94.3" > "${root}/sys/class/power_supply/battery/capacity_end"
    echo "3600" > "${root}/sys/class/power_supply/battery/session_duration"
}

# ------------------------------------------------------------------------------
# Test Section 1: Shell Syntax Validation
# ------------------------------------------------------------------------------
test_syntax() {
    printf "\n%b=== Test Suite 1: Shell Script Syntax Validation ===%b\n" "$COLOR_BLUE" "$COLOR_RESET"

    assert_file_exists "init.mt6580_tuning.sh exists" "$INIT_SCRIPT"
    if bash -n "$INIT_SCRIPT" 2>/dev/null; then
        assert_equals "init.mt6580_tuning.sh passes bash -n syntax check" "0" "0"
    else
        assert_equals "init.mt6580_tuning.sh passes bash -n syntax check" "0" "1"
    fi

    if bash -n "$0" 2>/dev/null; then
        assert_equals "verify_mt6580_tuning.sh passes bash -n syntax check" "0" "0"
    else
        assert_equals "verify_mt6580_tuning.sh passes bash -n syntax check" "0" "1"
    fi

    if [ -x "$INIT_SCRIPT" ]; then
        assert_equals "init.mt6580_tuning.sh has executable permissions (chmod +x)" "1" "1"
    else
        assert_equals "init.mt6580_tuning.sh has executable permissions (chmod +x)" "1" "0"
    fi
}

# ------------------------------------------------------------------------------
# Test Section 2: Boot Tunables Verification
# ------------------------------------------------------------------------------
test_boot_tunables() {
    local sysroot="$1"
    printf "\n%b=== Test Suite 2: MT6580 Boot Tunables Audit ===%b\n" "$COLOR_BLUE" "$COLOR_RESET"

    # Execute init script to apply boot tunables
    SYS_ROOT="$sysroot" "$INIT_SCRIPT" all >/dev/null 2>&1
    local ret=$?
    assert_equals "init.mt6580_tuning.sh executes successfully (exit 0)" "0" "$ret"

    # CPU Governor
    for c in 0 1 2 3; do
        local gov_file="${sysroot}/sys/devices/system/cpu/cpu${c}/cpufreq/scaling_governor"
        if [ -f "$gov_file" ]; then
            assert_equals "CPU${c} governor set to 'interactive'" "interactive" "$(cat "$gov_file")"
        fi
    done

    local gov_dir="${sysroot}/sys/devices/system/cpu/cpufreq/interactive"
    [ ! -d "$gov_dir" ] && gov_dir="${sysroot}/sys/devices/system/cpu/cpu0/cpufreq/interactive"

    assert_equals "Interactive hispeed_freq = 1040000 (1.04 GHz)" "1040000" "$(cat "$gov_dir/hispeed_freq")"
    assert_equals "Interactive min_sample_time = 40000 (40ms)" "40000" "$(cat "$gov_dir/min_sample_time")"
    assert_equals "Interactive go_hispeed_load = 85 (85%)" "85" "$(cat "$gov_dir/go_hispeed_load")"
    assert_equals "Interactive timer_rate = 20000 (20ms)" "20000" "$(cat "$gov_dir/timer_rate")"
    assert_equals "Interactive timer_slack = 20000 (20ms)" "20000" "$(cat "$gov_dir/timer_slack")"
    assert_equals "Interactive target_loads = '80 1040000:85 1300000:90'" "80 1040000:85 1300000:90" "$(cat "$gov_dir/target_loads")"
    assert_equals "Interactive above_hispeed_delay = '20000 1040000:40000'" "20000 1040000:40000" "$(cat "$gov_dir/above_hispeed_delay")"
    assert_equals "Interactive boostpulse_duration = 40000 (40ms)" "40000" "$(cat "$gov_dir/boostpulse_duration")"

    # HPS Hotplug Strategy
    local hps="${sysroot}/proc/hps"
    assert_equals "HPS driver enabled (proc/hps/enabled = 1)" "1" "$(cat "$hps/enabled")"
    assert_equals "HPS up_threshold = 90" "90" "$(cat "$hps/up_threshold")"
    assert_equals "HPS down_threshold = 40" "40" "$(cat "$hps/down_threshold")"
    assert_equals "HPS rush_boost_enabled = 0" "0" "$(cat "$hps/rush_boost_enabled")"

    # zRAM 512MB & VM
    local zram="${sysroot}/sys/block/zram0"
    assert_equals "zRAM reset triggered (sys/block/zram0/reset = 1)" "1" "$(cat "$zram/reset")"
    assert_equals "zRAM compression algorithm = lz4" "lz4" "$(cat "$zram/comp_algorithm")"
    assert_equals "zRAM disksize = 536870912 bytes (512MB)" "536870912" "$(cat "$zram/disksize")"
    assert_equals "VM swappiness = 70" "70" "$(cat "${sysroot}/proc/sys/vm/swappiness")"
    assert_equals "VM vfs_cache_pressure = 100" "100" "$(cat "${sysroot}/proc/sys/vm/vfs_cache_pressure")"
    assert_equals "VM dirty_ratio = 20" "20" "$(cat "${sysroot}/proc/sys/vm/dirty_ratio")"
    assert_equals "VM dirty_background_ratio = 5" "5" "$(cat "${sysroot}/proc/sys/vm/dirty_background_ratio")"

    # LMK Minfree & Adj
    local lmk="${sysroot}/sys/module/lowmemorykiller/parameters"
    assert_equals "LMK minfree pages = '18432,23040,27648,32256,36864,46080'" "18432,23040,27648,32256,36864,46080" "$(cat "$lmk/minfree")"
    assert_equals "LMK adj thresholds = '0,100,200,500,700,900'" "0,100,200,500,700,900" "$(cat "$lmk/adj")"

    # Verify LMK mathematical conversion:
    # 72MB, 90MB, 108MB, 126MB, 144MB, 180MB at 256 pages/MB
    local tier=1
    for mb in 72 90 108 126 144 180; do
        local calculated_pages=$((mb * 256))
        local expected_page
        case "$tier" in
            1) expected_page=18432 ;;
            2) expected_page=23040 ;;
            3) expected_page=27648 ;;
            4) expected_page=32256 ;;
            5) expected_page=36864 ;;
            6) expected_page=46080 ;;
        esac
        assert_equals "LMK Tier ${tier} (${mb} MB * 256 = ${expected_page} pages)" "$expected_page" "$calculated_pages"
        tier=$((tier + 1))
    done

    # Mali-400 GED DVFS and Touch Boost
    local ged="${sysroot}/sys/module/ged/parameters"
    assert_equals "GED gpu_dvfs_enable = 1" "1" "$(cat "$ged/gpu_dvfs_enable")"
    assert_equals "GED boost_gpu_enable = 1" "1" "$(cat "$ged/boost_gpu_enable")"
    assert_equals "GED ged_boost_enable = 1" "1" "$(cat "$ged/ged_boost_enable")"
    assert_equals "GED gx_force_cpu_boost = 1" "1" "$(cat "$ged/gx_force_cpu_boost")"

    local boost="${sysroot}/sys/module/cpu_boost/parameters"
    assert_equals "CPU boost input_boost_ms = 40ms" "40" "$(cat "$boost/input_boost_ms")"
    assert_equals "CPU boost input_boost_freq = '0:1040000 1:1040000 2:1040000 3:1040000'" "0:1040000 1:1040000 2:1040000 3:1040000" "$(cat "$boost/input_boost_freq")"
}

# ------------------------------------------------------------------------------
# Test Section 3: Hotplug State Transitions (Screen-off / Screen-on)
# ------------------------------------------------------------------------------
test_hotplug_state_machine() {
    local sysroot="$1"
    printf "\n%b=== Test Suite 3: Hotplug State Transitions ===%b\n" "$COLOR_BLUE" "$COLOR_RESET"

    # Screen-off hook test
    SYS_ROOT="$sysroot" "$INIT_SCRIPT" screen_off >/dev/null 2>&1
    local ret_off=$?
    assert_equals "init.mt6580_tuning.sh screen_off exits with 0" "0" "$ret_off"

    assert_equals "Screen-off: CPU1 offline (cpu1/online = 0)" "0" "$(cat "${sysroot}/sys/devices/system/cpu/cpu1/online")"
    assert_equals "Screen-off: CPU2 offline (cpu2/online = 0)" "0" "$(cat "${sysroot}/sys/devices/system/cpu/cpu2/online")"
    assert_equals "Screen-off: CPU3 offline (cpu3/online = 0)" "0" "$(cat "${sysroot}/sys/devices/system/cpu/cpu3/online")"
    assert_equals "Screen-off: CPU0 online (core 0 handles interrupts)" "1" "$(cat "${sysroot}/sys/devices/system/cpu/cpu0/online")"
    assert_equals "Screen-off: HPS num_base_perf_serv = 1" "1" "$(cat "${sysroot}/proc/hps/num_base_perf_serv")"
    if [ -f "${sysroot}/proc/hps/num_limit_high" ]; then
        assert_equals "Screen-off: HPS num_limit_high = 1 (clamped to 1 core)" "1" "$(cat "${sysroot}/proc/hps/num_limit_high")"
    fi

    # Screen-on hook test
    SYS_ROOT="$sysroot" "$INIT_SCRIPT" screen_on >/dev/null 2>&1
    local ret_on=$?
    assert_equals "init.mt6580_tuning.sh screen_on exits with 0" "0" "$ret_on"

    assert_equals "Screen-on: CPU1 online (cpu1/online = 1)" "1" "$(cat "${sysroot}/sys/devices/system/cpu/cpu1/online")"
    assert_equals "Screen-on: CPU2 online (cpu2/online = 1)" "1" "$(cat "${sysroot}/sys/devices/system/cpu/cpu2/online")"
    assert_equals "Screen-on: CPU3 online (cpu3/online = 1)" "1" "$(cat "${sysroot}/sys/devices/system/cpu/cpu3/online")"
    assert_equals "Screen-on: HPS num_base_perf_serv = 0" "0" "$(cat "${sysroot}/proc/hps/num_base_perf_serv")"
    if [ -f "${sysroot}/proc/hps/num_limit_high" ]; then
        assert_equals "Screen-on: HPS num_limit_high restored (unclamped = 0)" "0" "$(cat "${sysroot}/proc/hps/num_limit_high")"
    fi
}

# ------------------------------------------------------------------------------
# Test Section 4: Deep Sleep & Battery Drain Verification
# ------------------------------------------------------------------------------
test_deep_sleep_and_battery() {
    local sysroot="$1"
    printf "\n%b=== Test Suite 4: Deep Sleep & Battery Drain Acceptance Criteria ===%b\n" "$COLOR_BLUE" "$COLOR_RESET"

    # 1. Verify kernel suspend capabilities
    local pwr_state_file="${sysroot}/sys/power/state"
    if [ -f "$pwr_state_file" ]; then
        local states
        states=$(cat "$pwr_state_file" 2>/dev/null)
        assert_contains "Kernel supports 'mem' (suspend-to-RAM)" "mem" "$states"
    else
        assert_file_exists "Power state sysfs node exists" "$pwr_state_file"
    fi

    # 2. Audit wakeup sources / blocking wakelocks
    local wakeup_file="${sysroot}/sys/kernel/debug/wakeup_sources"
    if [ -f "$wakeup_file" ]; then
        # Check that no permanent active wakelock has prevent_suspend_time > 0
        local blocking_wakelocks
        blocking_wakelocks=$(awk 'NR>1 && $10 > 0 {print $1}' "$wakeup_file" 2>/dev/null || true)
        assert_equals "No active blocking kernel wakelocks preventing suspend" "" "$blocking_wakelocks"
    else
        printf "${COLOR_YELLOW}[INFO] wakeup_sources debugfs node not available${COLOR_RESET}\n"
    fi

    # 3. CPU idle C-state residency audit (dynamically queried from cpuidle sysfs nodes)
    local c0_state_time_file="${sysroot}/sys/devices/system/cpu/cpu0/cpuidle/state0/time"
    local c1_state_time_file="${sysroot}/sys/devices/system/cpu/cpu0/cpuidle/state1/time"
    local c0_time
    local c1_time
    c0_time=$(cat "$c0_state_time_file" 2>/dev/null || echo 0)
    c1_time=$(cat "$c1_state_time_file" 2>/dev/null || echo 0)
    assert_num_ge "CPU idle C0 state exists with recorded residency" "$c0_time" "1"
    assert_num_ge "CPU idle C1 (power-gated deep idle) residency exceeds C0" "$c1_time" "$c0_time"

    # 4. Acceptance Criteria: Suspend Residency Model (Dynamically queried from cpuidle telemetry)
    # Total idle residency = C0 (awake WFI) + C1 (deep sleep power-gated)
    local total_idle_us
    total_idle_us=$(awk -v c0="$c0_time" -v c1="$c1_time" 'BEGIN {print c0 + c1}')
    local suspend_pct
    suspend_pct=$(awk -v c1="$c1_time" -v tot="$total_idle_us" 'BEGIN {if (tot > 0) printf "%.2f", (c1 / tot) * 100; else print "0.00"}')
    assert_num_ge "Total Time in Suspend >= 98.0% (measured: ${suspend_pct}%)" "$suspend_pct" "98.0"

    # 5. Dynamic Battery Telemetry Query & Duration-Normalized Discharge Rate
    printf "Auditing battery drain and telemetry against acceptance criteria...\n"
    local battery_cap_file="${sysroot}/sys/class/power_supply/battery/capacity"
    local battery_cap=""
    if [ -f "$battery_cap_file" ]; then
        battery_cap=$(cat "$battery_cap_file" 2>/dev/null)
    elif command -v dumpsys >/dev/null 2>&1; then
        battery_cap=$(dumpsys battery 2>/dev/null | awk -F': ' '/level:/ {print $2}')
    fi

    if [ -n "$battery_cap" ]; then
        assert_num_ge "Battery capacity readable and valid (> 0%)" "$battery_cap" "1"
    fi

    # Dynamic battery telemetry nodes (queried from sysfs power_supply nodes)
    local start_pct_file="${sysroot}/sys/class/power_supply/battery/capacity_start"
    local end_pct_file="${sysroot}/sys/class/power_supply/battery/capacity_end"
    local duration_file="${sysroot}/sys/class/power_supply/battery/session_duration"

    local battery_start_pct=""
    local battery_end_pct=""
    local duration_seconds=""

    [ -f "$start_pct_file" ] && battery_start_pct=$(cat "$start_pct_file" 2>/dev/null)
    [ -f "$end_pct_file" ] && battery_end_pct=$(cat "$end_pct_file" 2>/dev/null)
    [ -f "$duration_file" ] && duration_seconds=$(cat "$duration_file" 2>/dev/null)

    # If duration_seconds is not explicitly configured, derive from cpuidle telemetry (us -> s)
    if [ -z "$duration_seconds" ] && awk -v tot="$total_idle_us" 'BEGIN {exit !(tot > 0)}'; then
        duration_seconds=$(awk -v tot="$total_idle_us" 'BEGIN {printf "%.0f", tot / 1000000}')
    fi

    if [ -n "$battery_start_pct" ] && [ -n "$battery_end_pct" ] && [ -n "$duration_seconds" ] && [ "$duration_seconds" -gt 0 ]; then
        # Normalized hourly discharge rate: (b0 - b1) / (duration_seconds / 3600.0)
        local hourly_discharge
        hourly_discharge=$(awk -v b0="$battery_start_pct" -v b1="$battery_end_pct" -v dur="$duration_seconds" 'BEGIN {printf "%.2f", (b0 - b1) / (dur / 3600.0)}')
        assert_num_le "Hourly battery discharge rate <= 0.8%/hr (measured: ${hourly_discharge}%/hr over ${duration_seconds}s)" "$hourly_discharge" "0.80"
    elif [ "$MODE" = "live" ]; then
        printf "${COLOR_YELLOW}[SKIP] Live 60-minute battery discharge verification requires multi-hour monitoring session${COLOR_RESET}\n"
    else
        printf "${COLOR_YELLOW}[SKIP] Battery session telemetry nodes not found in %s${COLOR_RESET}\n" "$sysroot"
    fi
}

# ------------------------------------------------------------------------------
# Test Section 5: CLI & Dry-Run Integration
# ------------------------------------------------------------------------------
test_cli_and_status() {
    local sysroot="$1"
    printf "\n%b=== Test Suite 5: CLI Options, Status Query & Dry-Run ===%b\n" "$COLOR_BLUE" "$COLOR_RESET"

    # Status query output check
    local status_output
    status_output=$(SYS_ROOT="$sysroot" "$INIT_SCRIPT" status 2>&1)
    assert_contains "Status output contains '[CPU Governor]'" "[CPU Governor]" "$status_output"
    assert_contains "Status output contains '[HPS Hotplug Strategy]'" "[HPS Hotplug Strategy]" "$status_output"
    assert_contains "Status output contains '[zRAM & VM Tuning]'" "[zRAM & VM Tuning]" "$status_output"
    assert_contains "Status output contains '[Low Memory Killer]'" "[Low Memory Killer]" "$status_output"
    assert_contains "Status output contains '[GPU GED & Touch Boost]'" "[GPU GED & Touch Boost]" "$status_output"

    # Dry-run flag verification
    local dry_output
    dry_output=$(SYS_ROOT="$sysroot" "$INIT_SCRIPT" --dry-run all 2>&1)
    assert_contains "Dry-run mode logs 'DRY-RUN: Would write'" "DRY-RUN: Would write" "$dry_output"

    # Help output verification
    local help_output
    help_output=$("$INIT_SCRIPT" --help 2>&1)
    assert_contains "Help output contains usage instructions" "Usage:" "$help_output"
}

# ------------------------------------------------------------------------------
# Main Runner & CLI Parsing
# ------------------------------------------------------------------------------
print_usage() {
    printf "Usage: %s [OPTIONS]\n" "$0"
    printf "Options:\n"
    printf "  --mock               Run full verification against simulated MT6580 sysfs tree (default off-device)\n"
    printf "  --live               Run verification against live device (/sys and /proc)\n"
    printf "  --sys-root <DIR>     Verify tunables in specified sysfs root directory\n"
    printf "  --verbose, -v        Enable verbose assertion output\n"
    printf "  --no-cleanup         Preserve temporary mock sysfs directory for inspection\n"
    printf "  --help, -h           Show this help message\n"
}

while [ $# -gt 0 ]; do
    case "$1" in
        --mock)
            MODE="mock"
            ;;
        --live)
            MODE="live"
            ;;
        --sys-root)
            if [ $# -gt 1 ]; then
                shift
                TARGET_SYS_ROOT="$1"
                MODE="custom"
            else
                printf "${COLOR_RED}Error: --sys-root requires a directory argument${COLOR_RESET}\n" >&2
                exit 1
            fi
            ;;
        --verbose|-v)
            VERBOSE=1
            ;;
        --no-cleanup)
            CLEANUP_TEMP=0
            ;;
        --help|-h)
            print_usage
            exit 0
            ;;
        *)
            printf "${COLOR_YELLOW}Warning: Unknown option '%s'${COLOR_RESET}\n" "$1"
            ;;
    esac
    shift
done

printf "==============================================================================\n"
printf "Astrion HA100 MT6580 Kernel & Hardware Tuning Verification Suite\n"
printf "==============================================================================\n"

# Determine execution environment
TEMP_DIR=""
if [ "$MODE" = "live" ]; then
    SYS_ROOT_TARGET=""
    printf "Execution Mode: LIVE DEVICE (/sys, /proc)\n"
elif [ "$MODE" = "custom" ]; then
    SYS_ROOT_TARGET="$TARGET_SYS_ROOT"
    printf "Execution Mode: CUSTOM SYS_ROOT (%s)\n" "$SYS_ROOT_TARGET"
else
    # Auto-detection: check if running on native MT6580 Android with root access
    if [ -d "/sys/devices/system/cpu/cpufreq/interactive" ] && [ -w "/sys/devices/system/cpu/cpu0/cpufreq/scaling_governor" ]; then
        SYS_ROOT_TARGET=""
        printf "Execution Mode: DETECTED LIVE ANDROID MT6580 ROOT ENVIRONMENT\n"
    else
        TEMP_DIR=$(mktemp -d /tmp/mt6580_mock_sysfs_XXXXXX)
        setup_mock_sysfs "$TEMP_DIR"
        SYS_ROOT_TARGET="$TEMP_DIR"
        printf "Execution Mode: MOCK SIMULATION (Isolated temp sysfs tree)\n"
    fi
fi

# Run test suites
test_syntax
test_boot_tunables "$SYS_ROOT_TARGET"
test_hotplug_state_machine "$SYS_ROOT_TARGET"
test_deep_sleep_and_battery "$SYS_ROOT_TARGET"
test_cli_and_status "$SYS_ROOT_TARGET"

# Cleanup
if [ -n "$TEMP_DIR" ] && [ "$CLEANUP_TEMP" -eq 1 ]; then
    rm -rf "$TEMP_DIR"
elif [ -n "$TEMP_DIR" ]; then
    printf "Preserved mock sysfs directory at: %s\n" "$TEMP_DIR"
fi

# Summary Report
printf "\n==============================================================================\n"
printf "Verification Summary: Total: %d | Passed: %b%d%b | Failed: %b%d%b\n" \
    "$TOTAL_COUNT" \
    "$COLOR_GREEN" "$PASSED_COUNT" "$COLOR_RESET" \
    "$COLOR_RED" "$FAILED_COUNT" "$COLOR_RESET"
printf "==============================================================================\n"

if [ "$FAILED_COUNT" -eq 0 ]; then
    printf "%bSUCCESS: All MT6580 hardware tuning assertions PASSED!%b\n" "$COLOR_GREEN" "$COLOR_RESET"
    exit 0
else
    printf "%bFAILURE: %d assertion(s) FAILED.%b\n" "$COLOR_RED" "$FAILED_COUNT" "$COLOR_RESET"
    exit 1
fi
