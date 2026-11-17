<<<<<<< HEAD
# Game Booster – Play Smoothly (v0.5.0)

A lightweight Android gaming companion built with Kotlin and Jetpack Compose.

## What's new in v0.5.0
- Per-game profiles: Balanced, Battery Saver, and Visual Quality. Choices are stored locally.
- Live connectivity type and metered-network status.
- Battery percentage and charging status.
- Android thermal status where supported (Android 10/API 29+).
- Basic device-aware advice based on actual Android-reported RAM, battery, network and thermal information.
- Refreshes device status while the app is open.

## Existing features
- Device/model/Android version and visible CPU core count.
- Device-wide RAM monitor using ActivityManager.MemoryInfo.
- Discover installed launcher apps; Android game-category apps are suggested first.
- Add/remove apps and launch them from the app.

## Important limitations
- Profiles are saved guidance labels only. This app does not remotely change another game's graphics settings.
- It does not force-stop other apps, magically free RAM, overclock CPU/GPU, unlock FPS, or guarantee better frame rates.
- Network display reports connection type only. It is not a ping test or speed test.
- Thermal status depends on Android version and device manufacturer support.
- This version does not detect a game launched directly from the home screen and does not automatically apply or restore system settings.
- Calls/notifications are not blocked by this version.

## Build
1. Open the extracted folder in Android Studio.
2. Use JDK 17.
3. Allow Gradle sync to finish.
4. Connect an Android device with USB debugging enabled, or use an emulator.
5. Press Run.

## Project
- Namespace/applicationId: `com.playsmoothly.gamebooster`
- compileSdk: 35
- minSdk: 23
- targetSdk: 35
- Kotlin + Jetpack Compose

Build/run verification has not been performed in this environment. Test on a physical low-end phone before publishing.
=======
# Game-Booster
>>>>>>> 7df257273dd8d5f33107e267beabc80ff8fe9183
