#!/usr/bin/env bash
# Puts the debug APK on the phone plugged in (USB debugging on) and opens
# it. ./build.sh first.
set -euo pipefail
cd "$(dirname "$0")"
apk=app/build/outputs/apk/debug/app-debug.apk
[ -f "$apk" ] || { echo "no $apk: ./build.sh first" >&2; exit 1; }
adb install -r "$apk"
adb shell am start -n io.wadspaces.android/.MainActivity
