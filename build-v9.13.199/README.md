# My Life Manager Android v9.13.199

This project packages the approved Android foundation with the v9.13.199 Home Menu update: colour-only active icons, consistent Records labels, directional top-pane hiding, and one shared Add-button position setting. Native persistence, reminders, launcher icon, and device compatibility remain intact.

## What is implemented

- **Room is the durable source of truth.** Managed app records are mirrored immediately into Room. The WebView `localStorage` remains only as a compatibility cache for the existing five tools; Room hydrates that cache at startup.
- **Background-safe autosave.** Every managed durable write is queued on a database executor. `onPause`, `pagehide`, and the HTML background message flush the Room WAL without blocking the UI thread.
- **Native reminders.** Exact `AlarmManager` delivery is used when Android allows it. WorkManager is the persistent fallback. Reminder definitions live in Room and are rescheduled after reboot, time changes, timezone changes, or app replacement.
- **Android Back.** `OnBackPressedDispatcher` first asks the trusted HTML to close dialogs, editors, sheets, settings, and routed pages; only then does it leave the activity.
- **Versioned backup and restore.** Exports include format, backup schema, Room schema, SHA-256 checksum, records, and reminders. Restore rejects newer or damaged backups. Password hashes and cloud tokens are excluded.
- **Secure login storage.** The HTML already derives workspace PINs with PBKDF2. Only the derived hash, random salt, and iteration count can enter Room; plain passwords are never persisted. Future cloud tokens use Android Keystore AES-GCM encryption.
- **Offline-first sync queue.** Room writes enqueue operations. WorkManager waits for connectivity and sends them when a backend is configured with `-PMLM_SYNC_BASE_URL=https://…`. With no endpoint, data stays safely queued and no false cloud-success state is shown.
- **No blank startup.** A native loading panel covers WebView creation. Main-frame, unsafe-navigation, and renderer failures show a recoverable error with Retry.
- **Fast and guarded startup.** Room reads, writes, backup, restore, hashing, and synchronisation stay off the main thread. The WebView loads only the packaged local asset and blocks remote scripts so they cannot reach native bridges; cloud traffic belongs to the constrained native worker.
- **Correct Android branding.** The supplied fingerprint artwork is packaged as density-specific launcher and round-launcher icons. Android notification delivery continues using a separate monochrome status-bar glyph.

## Build

Use Android Studio with JDK 17, or run:

```bash
gradle testDebugUnitTest lintDebug assembleDebug
```

To connect a compatible cloud service:

```bash
gradle assembleRelease -PMLM_SYNC_BASE_URL=https://your-service.example
```

The backend must implement authenticated `POST /v1/sync` with idempotency by operation `id`. It may return `{"updates":[{"key":"…","value":"…","updatedAt":123}]}` (or `deleted:true`) for last-write-wins inbound changes. Until that contract exists, Room remains fully usable offline and queued changes are not discarded.

## Verification

`scripts/validate_html.js` guards the Focus reminder regression and native hooks. Unit tests cover PBKDF2 behavior and backup validation. Instrumented tests verify Room persistence, essential Android services, the branded launcher icon, five embedded tools, nonblank startup, and horizontal-overflow safety. The workflow runs API 23, 28, 33, and 35 across 320×568, 360×640, 393×873, 412×915, and 600×960 displays, validates the APK manifest and ZIP structure, and publishes an installable debug-signed APK plus SHA-256 checksum.

## Runtime requirements

- Android 6.0 (API 23) or newer; Android System WebView must be enabled.
- Notification permission on Android 13+ for visible alerts.
- Exact-alarm access on Android 12+ when precise delivery is required; WorkManager remains the persistent fallback.
- Network access is optional for offline use and required only for configured cloud synchronisation.
- Camera permission is requested only when a camera-driven feature is used.

The generated `STABLE.apk` is debug-signed for direct installation and testing. Publishing to Google Play requires a private release signing key owned by the app publisher.
