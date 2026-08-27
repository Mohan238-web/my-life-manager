# Corex Android

Offline Android wrapper for Corex v9.13.247. The Android package ID remains
`com.mohan.mylifemanager` so existing local data can carry forward.

## Included

- The complete consolidated HTML app in `app/src/main/assets/index.html`.
- Native Android reminders with **Open** and **Dismiss** actions.
- Exact alarms when Android allows them and a persistent WorkManager fallback otherwise.
- Exact in-app routing for Notes, To‑Do, Expense, Focus/Priority/Habit, Trading and Mileage reminder destinations.
- The approved full-blue metallic fingerprint launcher and splash artwork.
- A smooth native splash that remains until the web app is ready.
- First-run Android notification permission and persistent Open/Dismiss reminders.
- Direct live-camera capture for camera-labelled photo controls, with file selection kept separate.
- A GitHub Actions APK build that does not start or use an Android emulator.

## Build

Push to the build branch, open **Actions → Build Corex APK**, then download the
`Corex-v9.13.247-APK` artifact. The debug APK installs directly after Android
permits installation from your browser or file manager. No emulator is used.
