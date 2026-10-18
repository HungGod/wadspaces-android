#!/usr/bin/env bash
# Builds the app (a debug APK) and runs the core's tests, from the command
# line: Android Studio's own JDK (its Flatpak), this laptop's Android SDK.
#
#   ./build.sh            core tests + app/build/outputs/apk/debug/app-debug.apk
#   ./build.sh test       core tests only
#   ./build.sh <tasks…>   any Gradle tasks
set -euo pipefail
cd "$(dirname "$0")"
export JAVA_HOME="${JAVA_HOME_WADSPACES:-/var/lib/flatpak/app/com.google.AndroidStudio/current/active/files/extra/jbr}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
[ -x "$JAVA_HOME/bin/javac" ] || { echo "no JDK at $JAVA_HOME (Android Studio's Flatpak, or set JAVA_HOME_WADSPACES)" >&2; exit 1; }
[ -d "$ANDROID_HOME/platforms" ] || { echo "no Android SDK at $ANDROID_HOME" >&2; exit 1; }
echo "sdk.dir=$ANDROID_HOME" > local.properties
[ -f wadspaces.properties ] || echo "note: no wadspaces.properties (scripts/sync-wadspaces.sh): signing in won't work" >&2
case "${1:-}" in
  "") set -- :core:test :app:assembleDebug ;;
  test) set -- :core:test ;;
esac
./gradlew --console=plain "$@"
