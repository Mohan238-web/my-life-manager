import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import zlib from 'node:zlib';

const root = path.resolve(import.meta.dirname, '..');
const app = path.join(root, 'corex-v9.13.249', 'app');
const partsDir = path.join(app, 'src', 'main', 'assets_parts');
const partFiles = fs.readdirSync(partsDir).filter(name => name.startsWith('index.html.gz.b64.part-')).sort();
const packed = partFiles.map(name => fs.readFileSync(path.join(partsDir, name), 'utf8')).join('');
const html = zlib.gunzipSync(Buffer.from(packed, 'base64')).toString('utf8');

assert.match(html, /const VERSION='9\.13\.260'/);
assert.match(html, /corex-cleanup-v913255-shell-runtime/);
assert.match(html, /corex-v913256-shell-runtime/);
assert.match(html, /Corex v9\.13\.260/);
assert.match(html, /id:'connection',label:'PC'/);
assert.match(html, /data-settings-group="advanced"/);
assert.match(html, /button\.remove\(\)/);
assert.match(html, /About & reset'[\s\S]*?data-corex-retired/);
assert.match(html, /async function restoreSnapshot\(\)/,
  'hiding Recovery navigation must not delete recovery data support');
assert.doesNotMatch(html, />My Life Manager</);
assert.doesNotMatch(html, /Shared from My Life Manager/);
assert.doesNotMatch(html, /Open My Life Manager to review this reminder/);
assert.match(html, /label:'Alert',title:'Reminders',heads:\['Global Reminder Center','Permissions'\]/);
assert.match(html, /label:'PC',title:'Connect PC',heads:\['Connect PC'\]/);
assert.match(html, /label:'About',title:'About Corex',heads:\['About & reset'\]/);
assert.match(html, /priority-bottom-overlay-delivery/);
assert.match(html, /Show Priority reminder now/);
assert.match(html, /memory:'keep'/);
assert.match(html, /PERFORMANCE_REPAIR_253/);
assert.match(html, /async function waitSuspend\(index,requestId,timeout=600\)/);
assert.match(html, /await waitSuspend\(index,requestId\);if\(!allowActive&&index===active\)return false/,
  'an iframe that becomes active during suspension must not be unloaded');
assert.match(html, /deliveryStatus\(\)/);
assert.match(html, /workspace-native-reminder-test/);
assert.match(html, /window\.MLMRequestNativeReminder=reminder=>scheduleGlobalReminder/);
const globalSchedule = html.match(/async function scheduleGlobalReminder\(reminder=\{\}\)\{([\s\S]*?)\n\}/)?.[1] || '';
const nativeBridgeStart = globalSchedule.indexOf("if(native.kind==='native-bridge')");
const nativeScheduleCall = globalSchedule.indexOf('native.api.schedule', nativeBridgeStart);
assert.ok(nativeBridgeStart >= 0 && nativeScheduleCall > nativeBridgeStart);
assert.doesNotMatch(globalSchedule.slice(nativeBridgeStart, nativeScheduleCall), /requestPermission|requestOverlayPermission/,
  'the parent must not pause for Android permissions before native storage');

const focusMatch = html.match(/\{"id":"focus","name":"Focus Ledger","data":"([^"]+)"/);
assert.ok(focusMatch, 'Focus Ledger payload is embedded');
const focus = zlib.gunzipSync(Buffer.from(focusMatch[1], 'base64')).toString('utf8');

assert.match(focus, /result\.reminderEnabled = !!prior\.reminderEnabled/);
assert.match(focus, /Navigation, autosave and temporary UI state must never cancel them/);
assert.match(focus, /title:`Priority \$\{index\+1\}`/);
assert.match(focus, /source:'focus-priority'/);
assert.match(focus, /priorityIndex:index/);
assert.match(focus, /pendingPriorityReminders/);
assert.doesNotMatch(focus, /Show bottom reminder now/);
assert.match(focus, /corex-v913256-focus-ui/);
assert.match(focus, /\.mlm-habit-fail-form textarea\{[\s\S]*?height:64px!important/);
assert.doesNotMatch(focus, /Android did not confirm an exact alarm/,
  'the WebView timeout must not turn off a natively stored reminder');
assert.match(focus, /await parent\.MLMRequestNativeReminder\(reminder\)/);
assert.match(focus, /result\?\.ok&&String\(result\.delivery\|\|''\)\.startsWith\('native'\)/);
assert.match(focus, /#habitPracticeApp #habitReminderBar,\.hp-reminder-bar\{display:none!important\}/);
assert.match(focus, /corex-cleanup-v913255-focus-runtime/);
assert.doesNotMatch(focus, /Managed by My Life Manager/);

const scheduleBlock = focus.match(/function scheduleTodayAlarms\(\)\{([\s\S]*?)\n\}/)?.[1] || '';
assert.ok(scheduleBlock, 'Priority scheduling function exists');
assert.doesNotMatch(scheduleBlock, /workspace-native-reminder-cancel/,
  'navigation/reschedule path must not cancel an enabled native reminder');
assert.match(focus, /Reminder turned off for Priority/,
  'explicit bell-off behavior remains');
assert.match(focus, /data-habit-done[\s\S]*workspace-native-reminder-cancel/,
  'completing a Priority still cancels only that Priority');

function compileInlineScripts(document, label) {
  const scripts = [...document.matchAll(/<script(?:\s[^>]*)?>([\s\S]*?)<\/script>/gi)];
  assert.ok(scripts.length, `${label} contains scripts`);
  scripts.forEach((match, index) => {
    try {
      new vm.Script(match[1], {filename: `${label}-script-${index + 1}.js`});
    } catch (error) {
      throw new Error(`${label} script ${index + 1} failed syntax validation: ${error.message}`);
    }
  });
}

compileInlineScripts(html, 'Corex shell');
compileInlineScripts(focus, 'Focus Ledger');

const overlay = fs.readFileSync(path.join(app, 'src', 'main', 'java', 'com', 'mohan',
  'mylifemanager', 'ReminderOverlayService.java'), 'utf8');
assert.match(overlay, /Gravity\.BOTTOM/);
assert.match(overlay, /outer\.setOrientation\(LinearLayout\.VERTICAL\)/);
assert.match(overlay, /topRow\.setOrientation\(LinearLayout\.HORIZONTAL\)/);
assert.match(overlay, /buttons\.setOrientation\(LinearLayout\.HORIZONTAL\)/);
assert.match(overlay, /COREX REMINDER/);
assert.match(overlay, /widthPixels - dp\(16\)/);
assert.match(overlay, /new LinearLayout\.LayoutParams\(dp\(72\), dp\(72\)\)/);
assert.match(overlay, /text\(reminderTitle\(payload\), 21, BLUE, true\)/);
assert.match(overlay, /new LinearLayout\.LayoutParams\(0, dp\(48\), 1f\)/);
assert.match(overlay, /buttonRow\.setMargins\(0, dp\(12\), 0, 0\)/);
assert.match(overlay, /return "Priority " \+ \(index \+ 1\)/);
assert.match(overlay, /reminderTime\(payload\)/);
assert.match(overlay, /actionButton\("Dismiss"/);
assert.match(overlay, /openLabel\(payload\)/);
assert.match(overlay, /ReminderStore\.remove\(this, id\)/,
  'scheduled reminder is removed only after the overlay is visible or fallback succeeds');
assert.match(overlay, /overlay-visible/);

const bridge = fs.readFileSync(path.join(app, 'src', 'main', 'java', 'com', 'mohan',
  'mylifemanager', 'NativeNotificationsBridge.java'), 'utf8');
assert.match(bridge, /ensureReminderPermissionsForSchedule\(\)/);
assert.match(bridge, /String testNow\(String payload\)/);
assert.match(bridge, /Register the reminder before opening Android Settings/);
assert.match(bridge, /String deliveryStatus\(\)/);
const nativeSchedule = bridge.match(/public String schedule\(String payload\) \{([\s\S]*?)\n    \}/)?.[1] || '';
assert.ok(nativeSchedule.indexOf('ReminderScheduler.schedule(activity, payload)') >= 0);
assert.ok(nativeSchedule.indexOf('ReminderScheduler.schedule(activity, payload)') < nativeSchedule.indexOf('ensureReminderPermissionsForSchedule()'),
  'native fallback must be stored before opening exact-alarm permission');
const nativeTest = bridge.match(/public String testNow\(String payload\) \{([\s\S]*?)\n    \}/)?.[1] || '';
assert.ok(nativeTest.indexOf('ReminderStore.put(activity, reminder)') < nativeTest.indexOf('requestOverlayPermission()'),
  'immediate test must be saved before opening overlay permission');

const activity = fs.readFileSync(path.join(app, 'src', 'main', 'java', 'com', 'mohan',
  'mylifemanager', 'MainActivity.java'), 'utf8');
assert.match(activity, /pendingReminderPermissionStep/);
assert.match(activity, /openOverlayPermissionForScheduledReminder\(\)/);
assert.match(activity, /ReminderScheduler\.reconcileStored\(this\)/);
assert.match(activity, /addJavascriptInterface\(pcBridge, "CorexPcNative"\)/);
assert.match(activity, /"corex"\.equalsIgnoreCase\(data\.getScheme\(\)\)/);
assert.match(activity, /dispatchPendingPcPair\(\)/);

assert.match(html, /corex-pc-connection-v913257-runtime/);
assert.match(html, /id="pcScanQr"/);
assert.match(html, /data-pc-transport="USB tethering"/);
assert.match(html, /native\.scanQr\(\)/);
assert.match(html, /grid-template-columns:repeat\(5,minmax\(0,1fr\)\)!important/);
assert.match(html, /corex-approved-settings-v913260-style/);
assert.match(html, /flex-direction:row!important/);
assert.match(html, /settings-section-icon\{[\s\S]*?flex:0 0 28px!important/);
assert.match(html, /class="premium-dot"/);
assert.match(html, /font-size:8\.2px!important/);
assert.match(html, /const labels=\{general:'General',reminders:'Alert',data:'Data',security:'Security',connection:'PC'\}/);
assert.match(html, /<h3>Connect PC<\/h3>/);
assert.match(html, /id="pcHost"/);
assert.match(html, /id="pcPin"/);
assert.match(html, /Connect securely/);
assert.match(html, /Wi-Fi, hotspot and USB tethering/);
assert.match(html, /native\.pair\(cleanHost,cleanPort,cleanPin,localStorage\.getItem\('corex\.pc\.transport'\)[\s\S]*?snapshot\(\)\)/);
assert.match(html, /native\.queueSnapshot\(value\)/);
assert.match(html, /corex-pc-snapshot-applied/);
assert.match(html, /activeWorkspaceBusy\(\)/);
assert.doesNotMatch(html, /PC changes applied\. Reopening Corex/);

const pcStore = fs.readFileSync(path.join(app, 'src', 'main', 'java', 'com', 'mohan',
  'mylifemanager', 'CorexConnectionStore.java'), 'utf8');
assert.match(pcStore, /PBKDF2WithHmacSHA256/);
assert.match(pcStore, /AES\/GCM\/NoPadding/);
assert.match(pcStore, /120000/);
assert.match(pcStore, /\/api\/v1\/sync\/exchange/);
assert.match(pcStore, /PENDING_SNAPSHOT/);

const pcBridge = fs.readFileSync(path.join(app, 'src', 'main', 'java', 'com', 'mohan',
  'mylifemanager', 'CorexPcBridge.java'), 'utf8');
assert.match(pcBridge, /scheduleBackgroundSync/);
assert.match(pcBridge, /NetworkType\.CONNECTED/);
assert.match(pcBridge, /CorexSyncWorker/);

const scheduler = fs.readFileSync(path.join(app, 'src', 'main', 'java', 'com', 'mohan',
  'mylifemanager', 'ReminderScheduler.java'), 'utf8');
assert.match(scheduler, /static void reconcileStored\(Context context\)/);
assert.match(scheduler, /ReminderDelivery\.deliver\(context, payload\.toString\(\)\)/);
assert.match(scheduler, /scheduleDeliveryWatchdog/);
assert.match(scheduler, /hasFreshActive/);

const delivery = fs.readFileSync(path.join(app, 'src', 'main', 'java', 'com', 'mohan',
  'mylifemanager', 'ReminderDelivery.java'), 'utf8');
const optimisticRemove = delivery.match(/if \(ReminderOverlayService\.show[\s\S]*?return true;/)?.[0] || '';
assert.doesNotMatch(optimisticRemove, /ReminderStore\.remove\(context, id\)/,
  'delivery must not delete the alarm before the overlay confirms visibility');
const reminderOverlay = fs.readFileSync(path.join(app, 'src', 'main', 'java', 'com', 'mohan',
  'mylifemanager', 'ReminderOverlayService.java'), 'utf8');
assert.match(reminderOverlay, /R\.drawable\.corex_icon_v249_art_webp/,
  'the approved notification fingerprint must remain unchanged');
assert.match(reminderOverlay, /R\.drawable\.ic_stat_fingerprint/);

const manifest = fs.readFileSync(path.join(app, 'src', 'main', 'AndroidManifest.xml'), 'utf8');
for (const permission of ['POST_NOTIFICATIONS', 'USE_EXACT_ALARM', 'SYSTEM_ALERT_WINDOW',
  'FOREGROUND_SERVICE', 'FOREGROUND_SERVICE_SPECIAL_USE', 'RECEIVE_BOOT_COMPLETED',
  'INTERNET', 'ACCESS_NETWORK_STATE']) {
  assert.match(manifest, new RegExp(`android\\.permission\\.${permission}`));
}
assert.match(manifest, /ReminderOverlayService/);
assert.match(manifest, /ReminderDeliveryWatchdogReceiver/);
assert.match(manifest, /BootReceiver/);
assert.match(manifest, /android:scheme="corex" android:host="pair"/);

const alarmReceiver = fs.readFileSync(path.join(app, 'src', 'main', 'java', 'com', 'mohan',
  'mylifemanager', 'NotificationAlarmReceiver.java'), 'utf8');
assert.match(alarmReceiver, /alarm-received/);

const watchdog = fs.readFileSync(path.join(app, 'src', 'main', 'java', 'com', 'mohan',
  'mylifemanager', 'ReminderDeliveryWatchdogReceiver.java'), 'utf8');
assert.match(watchdog, /notification-watchdog/);
assert.match(watchdog, /NotificationPublisher\.show/);

const gradle = fs.readFileSync(path.join(app, 'build.gradle.kts'), 'utf8');
assert.match(gradle, /versionCode = 913260/);
assert.match(gradle, /versionName = "9\.13\.260-corex-pc"/);
assert.match(gradle, /zxing-android-embedded:4\.3\.0/);
const launcherSource = path.join(app, 'src', 'main', 'res', 'drawable-nodpi', 'corex_icon_v260_art.webp.b64');
assert.ok(fs.existsSync(launcherSource), 'the selected full-bleed premium launcher art must be packaged');
const launcherBytes = Buffer.from(fs.readFileSync(launcherSource, 'utf8').replace(/\s+/g, ''), 'base64');
assert.equal(launcherBytes.subarray(0, 4).toString('ascii'), 'RIFF');
assert.equal(launcherBytes.subarray(8, 12).toString('ascii'), 'WEBP');
const launcherForeground = fs.readFileSync(path.join(app, 'src', 'main', 'res', 'drawable',
  'corex_icon_v250_foreground.xml'), 'utf8');
assert.match(launcherForeground, /@drawable\/corex_icon_v260_art/);

const expenseMatch = html.match(/\{"id":"expense","name":"Money","data":"([^"]+)"/);
assert.ok(expenseMatch, 'Expense Manager payload is embedded');
const expense = zlib.gunzipSync(Buffer.from(expenseMatch[1], 'base64')).toString('utf8');
assert.match(expense, /corex-cleanup-v913255-expense/);
assert.match(expense, /#driveSettingsModal,#pcSettingsModal,#pinSettingsModal/);

const notesMatch = html.match(/\{"id":"notes","name":"Notes","data":"([^"]+)"/);
assert.ok(notesMatch, 'Notes payload is embedded');
const notes = zlib.gunzipSync(Buffer.from(notesMatch[1], 'base64')).toString('utf8');
assert.match(notes, /corex-cleanup-v913255-notes/);
assert.match(notes, /corex-v913256-notes-ui/);
assert.match(notes, /#toolsDialog \.dialog-scroll\.tools-grid/);
assert.match(notes, /grid-auto-rows:48px!important/);
assert.match(notes, /height:48px!important/);
assert.match(notes, /class:'reminder-time'/);
assert.match(notes, /data-priority="\$\{escTodo\(t\.priority\)\}"/);
assert.match(notes, /note-card\[data-priority="Critical"\],[\s\S]*todo-card\[data-priority="Critical"\]/);
assert.match(notes, /\[data-saved-action166="move"\]/);
assert.match(notes, /Search note titles, text, actions and categories/);

const tradingMatch = html.match(/\{"id":"trading","name":"Trading Journal","data":"([^"]+)"/);
assert.ok(tradingMatch, 'Trading Journal payload is embedded');
const trading = zlib.gunzipSync(Buffer.from(tradingMatch[1], 'base64')).toString('utf8');
assert.match(trading, /Checking app storage/);

const mileageMatch = html.match(/\{"id":"mileage","name":"Mileage","data":"([^"]+)"/);
assert.ok(mileageMatch, 'Mileage payload is embedded');
const mileage = zlib.gunzipSync(Buffer.from(mileageMatch[1], 'base64')).toString('utf8');

compileInlineScripts(expense, 'Money');
compileInlineScripts(trading, 'Trading Journal');
compileInlineScripts(notes, 'Notes');
compileInlineScripts(mileage, 'Mileage');

const pcMain = fs.readFileSync(path.join(root, 'pc-companion', 'main.go'), 'utf8');
const pcQR = fs.readFileSync(path.join(root, 'pc-companion', 'qr.go'), 'utf8');
const pcDashboard = fs.readFileSync(path.join(root, 'pc-companion', 'dashboard.go'), 'utf8');
assert.match(pcMain, /const \([\s\S]*companionVersion = "1\.1\.2"/);
assert.match(pcMain, /pbkdf2SHA256/);
assert.match(pcMain, /cipher\.NewGCM/);
assert.match(pcMain, /Crypt|protect\(plain\)/);
assert.match(pcMain, /\/api\/v1\/sync\/exchange/);
assert.match(pcMain, /backupLocked\(\)/);
assert.match(pcQR, /Version 5-L/);
assert.match(pcQR, /reedSolomonRemainder/);
assert.match(pcDashboard, /Open full Corex/);
assert.match(pcDashboard, /Focus, Priorities, Notes, To‑Do, Expense Manager, Trading Journal and Mileage/);
assert.match(pcDashboard, /Advanced data — technical use only/);
assert.doesNotMatch(pcDashboard, /<main class="card main"><h2>Corex records<\/h2>/);
assert.match(pcDashboard, /Start Corex Companion with Windows/);
assert.match(pcDashboard, /Enable Windows reminders/);
assert.match(pcDashboard, /Companion controls/);

const pcDesktopBridge = fs.readFileSync(path.join(root, 'pc-companion', 'pc_bridge.go'), 'utf8');
assert.match(pcDesktopBridge, /corex-desktop-bridge-runtime/);
assert.match(pcDesktopBridge, /MLMNativeNotifications/);
assert.match(pcDesktopBridge, /CorexPcDesktop/);
assert.match(pcDesktopBridge, /activeWorkspaceBusy\(\)/);
assert.match(pcDesktopBridge, /corex-pc-snapshot-applied/);
assert.doesNotMatch(pcDesktopBridge, /corexPcDesktopBar/);
assert.doesNotMatch(pcDesktopBridge, /location\.reload\(/);
assert.match(pcMain, /\/dashboard\/restore/);

const pcNative = fs.readFileSync(path.join(app, 'src', 'main', 'java', 'com', 'mohan',
  'mylifemanager', 'CorexPcBridge.java'), 'utf8');
const mainActivity = fs.readFileSync(path.join(app, 'src', 'main', 'java', 'com', 'mohan',
  'mylifemanager', 'MainActivity.java'), 'utf8');
assert.match(pcNative, /public void scanQr\(\)/);
assert.match(mainActivity, /IntentIntegrator/);
assert.match(mainActivity, /This is not a Corex PC Companion QR code/);

console.log('Corex v9.13.260 and PC Companion v1.1.2 source verification passed');
