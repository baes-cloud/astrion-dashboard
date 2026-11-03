#!/system/bin/sh
# eMMC bad-block quarantine, bootloop self-heal, and tmpfs cache mount for HA100.
#
# The failing eMMC on .113 hands back sectors starting de ad ad de instead of
# an I/O error when it can't read them (nothing in dmesg). Every file this
# touches is one Android rebuilds (package_cache, dalvik-cache, oat), so a bad
# copy is moved to /data/local/.badblocks_quarantine as evidence and the rest
# of package_cache is cleared, so system_server never parses a rotten entry.
#
# chattr +i on the quarantined copies is belt and braces only: the eMMC's own
# FTL maps logical to physical blocks, so the filesystem can't pin a weak NAND
# block, and a rewrite lands wherever the controller puts it.

QUAR="/data/local/.badblocks_quarantine"
PAT=$(printf "\336\255\255\336")
PKG="com.custom.astrion"

mkdir -p "$QUAR"
chmod 700 "$QUAR"

# F2FS writes out of place (no in-place updates). Harmless; see the FTL note.
if [ -f /sys/fs/f2fs/mmcblk0p23/ipu_policy ]; then
    echo 0 > /sys/fs/f2fs/mmcblk0p23/ipu_policy
fi

quarantine_file() {
    src="$1"
    [ -f "$src" ] || return 0
    base=$(echo "$src" | tr "/" "_")
    dst="$QUAR/${base}.$(date +%s)"
    chattr -i "$src" 2>/dev/null
    mv "$src" "$dst" 2>/dev/null || return 0
    chmod 000 "$dst" 2>/dev/null
    chattr +i "$dst" 2>/dev/null
    log -t bootguard "Quarantined corrupt file: $src -> $dst"
}

clear_package_cache() {
    if [ -d /data/system/package_cache ]; then
        for f in $(find /data/system/package_cache -type f 2>/dev/null); do
            if grep -q "$PAT" "$f" 2>/dev/null; then
                quarantine_file "$f"
            else
                rm -f "$f"
            fi
        done
        rm -rf /data/system/package_cache/*
    fi
}

heal_dalvik_and_oat() {
    for f in /data/dalvik-cache/arm/* /data/app/*/oat/arm/*; do
        [ -f "$f" ] || continue
        if grep -q "$PAT" "$f" 2>/dev/null; then
            quarantine_file "$f"
        fi
    done
}

mount_astrion_tmpfs() {
    appdir="/data/data/$PKG"
    [ -d "$appdir" ] || return 0
    uid=$(stat -c %u "$appdir" 2>/dev/null)
    gid=$(stat -c %g "$appdir" 2>/dev/null)
    [ -n "$uid" ] || return 0
    cache_gid=$((gid + 10000))
    ctx=$(ls -Zd "$appdir" | cut -d" " -f1)

    if ! grep -q "$appdir/cache" /proc/mounts 2>/dev/null; then
        rm -rf "$appdir/cache/art"/* "$appdir/cache/json"/* 2>/dev/null
        mount -t tmpfs -o "size=32m,mode=2771,uid=$uid,gid=$cache_gid" tmpfs "$appdir/cache"
        mkdir -p "$appdir/cache/art" "$appdir/cache/json"
        chown "$uid:$cache_gid" "$appdir/cache" "$appdir/cache/art" "$appdir/cache/json"
        chmod 2771 "$appdir/cache"
        chmod 2700 "$appdir/cache/art" "$appdir/cache/json"
        chcon -R "$ctx" "$appdir/cache" 2>/dev/null
    fi

    if ! grep -q "$appdir/files" /proc/mounts 2>/dev/null; then
        mkdir -p "$appdir/files"
        mount -t tmpfs -o "size=4m,mode=0771,uid=$uid,gid=$gid" tmpfs "$appdir/files"
        chown "$uid:$gid" "$appdir/files"
        chmod 0771 "$appdir/files"
        chcon -R "$ctx" "$appdir/files" 2>/dev/null
    fi
}

case "${1:-late}" in
    early)
        clear_package_cache
        ;;
    heal)
        if [ "$(getprop sys.boot_completed)" != "1" ]; then
            clear_package_cache
            heal_dalvik_and_oat
        fi
        ;;
    late)
        clear_package_cache
        mount_astrion_tmpfs
        ;;
esac
exit 0
