const fs = require('node:fs');
const path = require('node:path');

const root = path.resolve(__dirname, '..');
const read = relative => fs.readFileSync(path.join(root, relative), 'utf8');
const requireText = (text, value, label) => {
  if (!text.includes(value)) throw new Error(`Missing ${label}: ${value}`);
};

const gradle = read('app/build.gradle.kts');
const manifest = read('app/src/main/AndroidManifest.xml');
const activity = read('app/src/main/java/com/mylifemanager/app/MainActivity.java');
const database = read('app/src/main/java/com/mylifemanager/app/data/AppDatabase.java');
const reminders = read('app/src/main/java/com/mylifemanager/app/reminders/ReminderScheduler.java');
const backup = read('app/src/main/java/com/mylifemanager/app/backup/BackupEnvelope.java');
const auth = read('app/src/main/java/com/mylifemanager/app/auth/PasswordHasher.java');
const sync = read('app/src/main/java/com/mylifemanager/app/sync/SyncScheduler.java');
const html = read('app/src/main/assets/index.html');

requireText(gradle, 'minSdk = 23', 'Android 6 minimum');
requireText(gradle, 'targetSdk = 36', 'current target SDK');
requireText(gradle, 'versionCode = 913199', 'final version code');
requireText(manifest, 'android:icon="@mipmap/ic_launcher"', 'branded launcher icon');
requireText(manifest, 'android:roundIcon="@mipmap/ic_launcher_round"', 'round launcher icon');
requireText(manifest, 'android.permission.POST_NOTIFICATIONS', 'notification permission');
requireText(manifest, 'android.permission.RECEIVE_BOOT_COMPLETED', 'reboot permission');
requireText(manifest, 'android.permission.SCHEDULE_EXACT_ALARM', 'exact alarm permission');
requireText(manifest, 'android.hardware.camera" android:required="false"', 'optional camera hardware declaration');
requireText(activity, 'WebViewAssetLoader', 'local asset loader');
requireText(activity, 'setAllowFileAccess(false)', 'file-access lock');
requireText(activity, 'showRecoverableError', 'recoverable error screen');
requireText(activity, 'OnBackPressedCallback', 'Android Back handler');
requireText(database, '@Database', 'Room database');
requireText(reminders, 'setExactAndAllowWhileIdle', 'exact reminder scheduler');
requireText(reminders, 'WorkManager', 'persistent reminder fallback');
requireText(backup, 'Checksums.sha256', 'backup checksum');
requireText(auth, 'PBKDF2WithHmacSHA256', 'password hashing');
requireText(sync, 'NetworkType.CONNECTED', 'offline sync constraint');
requireText(html, "classList.add('mlm-startup-ready')", 'nonblank startup marker');
requireText(html, 'window.MLMHandleAndroidBack', 'HTML Back contract');
if (/<script\b[^>]*\bsrc\s*=/i.test(html)) throw new Error('Remote script dependency detected.');
if (html.includes('${expenseSvg("close")}')) throw new Error('Unrendered expense SVG template found.');

const sizes = { mdpi: 48, hdpi: 72, xhdpi: 96, xxhdpi: 144, xxxhdpi: 192 };
for (const [density, expected] of Object.entries(sizes)) {
  for (const name of ['ic_launcher.png', 'ic_launcher_round.png']) {
    const file = path.join(root, `app/src/main/res/mipmap-${density}/${name}`);
    const png = fs.readFileSync(file);
    if (png.toString('hex', 0, 8) !== '89504e470d0a1a0a') throw new Error(`${file} is not PNG.`);
    const width = png.readUInt32BE(16), height = png.readUInt32BE(20);
    if (width !== expected || height !== expected) throw new Error(`${file} must be ${expected}x${expected}; got ${width}x${height}.`);
  }
}

console.log('Android project validation passed: Room, reminders, backup, auth, offline sync, Back, startup recovery, local-only scripts, and 10 launcher assets.');
