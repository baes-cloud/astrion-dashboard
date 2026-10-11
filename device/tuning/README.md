# Astrion HA100: MediaTek MT6580 Kernel & Hardware Tuning Suite

This directory contains the system-level tuning scripts and verification test harness for the Astrion HA100 smart home dashboard running Android 8.1 (API 27) on a MediaTek MT6580 quad-core ARM Cortex-A7 SoC with a Mali-400 GPU and 1GB RAM.

## Files

- `init.mt6580_tuning.sh`: Boot tuning and runtime hotplug script. Applies interactive CPU governor settings, HPS hotplug rules, 512MB LZ4 zRAM, VM swappiness tuning, LMK minfree recalibration, and Mali-400 GPU GED DVFS boost.
- `verify_mt6580_tuning.sh`: Standalone audit and test harness that asserts all sysfs tunables, validates syntax, tests hotplug state transitions, and verifies deep sleep and battery drain models against acceptance criteria.
- `mt6580_tuning.rc`: Android init service definition triggering on `sys.boot_completed=1`.

## Tunables Specification

### 1. Interactive CPU Governor (`/sys/devices/system/cpu/cpufreq/interactive/`)
- `hispeed_freq`: `1040000` (1.04 GHz)
- `min_sample_time`: `40000` (40 ms)
- `go_hispeed_load`: `85` (85%)
- `timer_rate`: `20000` (20 ms)
- `timer_slack`: `20000` (20 ms)
- `target_loads`: `"80 1040000:85 1300000:90"`
- `above_hispeed_delay`: `"20000 1040000:40000"`
- `boostpulse_duration`: `40000` (40 ms)

### 2. MediaTek HPS (Hotplug Strategy) (`/proc/hps/`)
- `enabled`: `1`
- `up_threshold`: `90`
- `down_threshold`: `40`
- `rush_boost_enabled`: `0`
- Screen-off state: Takes cores 1-3 offline (`cpu1/online=0`, `cpu2/online=0`, `cpu3/online=0`) and sets `num_base_perf_serv=1`. Core 0 handles interrupts and wakeups.
- Screen-on state: Brings cores 1-3 online (`cpu1/online=1`, `cpu2/online=1`, `cpu3/online=1`) and sets `num_base_perf_serv=0`.

### 3. zRAM 512MB LZ4 & VM Memory Subsystem
- Reset device: `/sys/block/zram0/reset=1`
- Compression algorithm: `/sys/block/zram0/comp_algorithm=lz4`
- Disk size: `/sys/block/zram0/disksize=536870912` (512 MB)
- Virtual memory tunables:
  - `vm.swappiness`: `70`
  - `vm.vfs_cache_pressure`: `100`
  - `vm.dirty_ratio`: `20`
  - `vm.dirty_background_ratio`: `5`

### 4. Low Memory Killer (LMK) Recalibration (`/sys/module/lowmemorykiller/parameters/`)
Recalibrated for 1GB RAM (4KB pages, 256 pages/MB):
- `minfree`: `18432,23040,27648,32256,36864,46080` (72MB, 90MB, 108MB, 126MB, 144MB, 180MB)
- `adj`: `0,100,200,500,700,900`

### 5. Mali-400 GPU GED DVFS and Touch Boost
- MediaTek GED:
  - `gpu_dvfs_enable`: `1`
  - `boost_gpu_enable`: `1`
  - `ged_boost_enable`: `1`
  - `gx_force_cpu_boost`: `1`
- Touch input boost:
  - `input_boost_ms`: `40` (40 ms boost duration)
  - `input_boost_freq`: `"0:1040000 1:1040000 2:1040000 3:1040000"`

## Verification

Run the verification test suite:
```sh
./device/tuning/verify_mt6580_tuning.sh
```

Verbose mode:
```sh
./device/tuning/verify_mt6580_tuning.sh --verbose
```

On a live device (as root):
```sh
./device/tuning/verify_mt6580_tuning.sh --live
```
