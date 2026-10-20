# WadSpaces for Android

Your wadspaces on your phone:
- **Make one** with the phone's Builder: a template, its Extensions and its customization. It writes the same documents as the WadSpaces Client's board.
- **Build it on one of your machines**, open it there, or **stream it into the app**.

The phone reaches the machine over your Tailscale network. The machine has to trust the phone once: it asks, in the HUD and in Machines → Tailnet. See `../wadspaces/host/TAILNET.md`.

| | |
|---|---|
| `core/` | Plain Kotlin, tested on the laptop: Firebase over REST (GitHub sign-in, Firestore), the library, the Builder's documents (a port of wad-board's sync, checked against wadspaces' goldens), and wadd's remote API. |
| `app/` | The Android app (Jetpack Compose): sign-in, Home, Machines, the Builder, building and streaming. |
| `build.sh` | `./build.sh` runs the core's tests and builds `app/build/outputs/apk/debug/app-debug.apk`. In a wadspace with the **Android** tool it uses that tool's JDK and SDK; on the laptop, Android Studio's JDK (Flatpak) and `~/Android/Sdk`. |
| `install.sh` | `adb install` to the phone plugged in, then opens the app. |
| `scripts/sync-wadspaces.sh` | Takes what the app shares with `../wadspaces`: the catalog, starters, goldens, wallpapers, and the Firebase settings (into the gitignored `wadspaces.properties`). Run it after those change there. |

Kotlin 2.4.21, AGP 9.4.1 (it compiles Kotlin itself), Gradle 9.8.2, compileSdk 36, minSdk 26. Gradle needs a full JDK (with `javac`): the Android tool's, Android Studio's, or Fedora's `java-25-openjdk-devel`.

**Trying the screens without an account or a tailnet:** a debug build has a made-up library and machine:

    adb shell am start -n io.wadspaces.android/.MainActivity --ez fake true [--es screen machines|builder|wadspace:vanua-academy]
