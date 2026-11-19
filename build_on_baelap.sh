#!/usr/bin/env bash
# Build astrion-v3-test on baelap in an isolated directory (never touches ~/src/astrion-dashboard).
set -euo pipefail
HOST=baelap
SRC=/home/bae/Documents/claude/astrion-v3-test
if [ "$#" -eq 0 ]; then
  TASKS=(testDebugUnitTest assembleRelease)
else
  TASKS=("$@")
fi
rsync -a --delete \
  --exclude '/app/build/' --exclude '/build/' --exclude '/.gradle/' --exclude '/.idea/' \
  --exclude '/.kotlin/' --exclude '/screenshots/' --exclude '/reference/' \
  "$SRC/" "$HOST:src/astrion-v3-test/"
ssh "$HOST" "cd ~/src/astrion-v3-test && JAVA_HOME=~/buildtools/jdk17 ANDROID_HOME=~/Android/Sdk \
  ./gradlew --console=plain -q ${TASKS[*]}"
if [[ " ${TASKS[*]} " == *" assembleRelease "* ]]; then
  mkdir -p "$SRC/app/build/outputs/apk/release"
  rsync -a "$HOST:src/astrion-v3-test/app/build/outputs/apk/release/" "$SRC/app/build/outputs/apk/release/"
  ls -lh "$SRC/app/build/outputs/apk/release/app-release.apk"
fi
