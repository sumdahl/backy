#!/usr/bin/env bash
# Builds the Go upload core into app/libs/ptg.aar. Run after changing anything under ../mobile or ../internal.
set -euo pipefail
cd "$(dirname "$0")/.."
: "${ANDROID_HOME:=$HOME/Android/Sdk}"
export ANDROID_HOME
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"
gomobile bind -target=android/arm64 -androidapi 31 -javapkg global.ptg \
  -trimpath -ldflags="-s -w" -o android/app/libs/ptg.aar ./mobile
