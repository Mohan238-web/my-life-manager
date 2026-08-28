# Corex Android

Offline Android wrapper for Corex v9.13.249. The Android package ID remains
`com.mohan.mylifemanager` so existing local data can carry forward.

## Included

- The complete consolidated HTML app in `app/src/main/assets/index.html`.
- Native Android reminders with **Open** and **Dismiss** actions.
- Exact alarms when Android allows them and a persistent WorkManager fallback otherwise.
- Exact in-app routing for Notes, To‑Do, Expense, Focus/Priority/Habit, Trading and Mileage reminder destinations.
- The approved full-size blue metallic fingerprint launcher artwork.
- A neutral startup surface with the custom blue blocking cover removed.
- First-run Notification, Alarms & reminders, and overlay permission guidance.
- Priority bell controls wired directly to Android scheduling for all three priorities.
- A passed clock time automatically schedules its next valid occurrence.
- A phone-friendly 3 × 2 Settings menu with larger icons and labels.
- Persistent bottom Open/Dismiss reminder cards over Corex or another app.
- Direct live-camera capture for camera-labelled photo controls, with file selection kept separate.
- A GitHub Actions APK build that does not start or use an Android emulator.

## Build

Push to the build branch, open **Actions → Build Corex APK**, then download the
`Corex-v9.13.249-APK` artifact. The debug APK installs directly after Android
permits installation from your browser or file manager. No emulator is used.
