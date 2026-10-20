#!/usr/bin/env bash
# Builds the app (a debug APK) and runs the core's tests, from the command
# line. In a wadspace with the android tool: its JDK and SDK
# (/usr/local/jdk, /usr/local/android-sdk). On the laptop: Android Studio's
# own JDK (its Flatpak) and ~/Android/Sdk.
#
#   ./build.sh            core tests + app/build/outputs/apk/debug/app-debug.apk
#   ./build.sh test       core tests only
#   ./build.sh <tasks…>   any Gradle tasks
set -euo pipefail
cd "$(dirname "$0")"
if [ -n "${JAVA_HOME_WADSPACES:-}" ]; then
  export JAVA_HOME="$JAVA_HOME_WADSPACES"
elif [ -x /usr/local/jdk/bin/javac ]; then
  export JAVA_HOME=/usr/local/jdk
else
  export JAVA_HOME=/var/lib/flatpak/app/com.google.AndroidStudio/current/active/files/extra/jbr
fi
if [ -z "${ANDROID_HOME:-}" ]; then
  if [ -d /usr/local/android-sdk/platforms ]; then ANDROID_HOME=/usr/local/android-sdk; else ANDROID_HOME="$HOME/Android/Sdk"; fi
fi
export ANDROID_HOME
[ -x "$JAVA_HOME/bin/javac" ] || { echo "no JDK at $JAVA_HOME (the android tool, Android Studio's Flatpak, or set JAVA_HOME_WADSPACES)" >&2; exit 1; }
[ -d "$ANDROID_HOME/platforms" ] || { echo "no Android SDK at $ANDROID_HOME" >&2; exit 1; }
echo "sdk.dir=$ANDROID_HOME" > local.properties
[ -f wadspaces.properties ] || echo "note: no wadspaces.properties (scripts/sync-wadspaces.sh): signing in won't work" >&2
case "${1:-}" in
  "") set -- :core:test :app:assembleDebug ;;
  test) set -- :core:test ;;
esac
./gradlew --console=plain "$@"
