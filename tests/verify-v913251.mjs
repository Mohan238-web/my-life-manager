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

assert.match(html, /const VERSION='9\.13\.253'/);
assert.match(html, /priority-bottom-overlay-delivery/);
assert.match(html, /Show Priority reminder now/);
assert.match(html, /memory:'keep'/);
assert.match(html, /PERFORMANCE_REPAIR_253/);
assert.match(html, /async function waitSuspend\(index,requestId,timeout=600\)/);
assert.match(html, /await waitSuspend\(index,requestId\);if\(!allowActive&&index===active\)return false/,
  'an iframe that becomes active during suspension must not be unloaded');
assert.match(html, /deliveryStatus\(\)/);
assert.match(html, /workspace-native-reminder-test/);

const focusMatch = html.match(/\{"id":"focus","name":"Focus Ledger","data":"([^"]+)"/);
assert.ok(focusMatch, 'Focus Ledger payload is embedded');
const focus = zlib.gunzipSync(Buffer.from(focusMatch[1], 'base64')).toString('utf8');

assert.match(focus, /result\.reminderEnabled = !!prior\.reminderEnabled/);
assert.match(focus, /Navigation, autosave and temporary UI state must never cancel them/);
assert.match(focus, /title:`Priority \$\{index\+1\}`/);
assert.match(focus, /source:'focus-priority'/);
assert.match(focus, /priorityIndex:index/);
assert.match(focus, /pendingPriorityReminders/);
assert.match(focus, /data-test-priority-reminder/);
assert.match(focus, /Android did not confirm an exact alarm\. The reminder remains off\./);
assert.match(focus, /data\.ok&&data\.delivery==='native-exact'/);
assert.match(focus, /#habitPracticeApp #habitReminderBar,\.hp-reminder-bar\{display:none!important\}/);

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
assert.match(overlay, /setOrientation\(LinearLayout\.HORIZONTAL\)/);
assert.match(overlay, /COREX REMINDER/);
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
assert.match(bridge, /error:exact-alarm-permission/);
assert.match(bridge, /String deliveryStatus\(\)/);

const activity = fs.readFileSync(path.join(app, 'src', 'main', 'java', 'com', 'mohan',
  'mylifemanager', 'MainActivity.java'), 'utf8');
assert.match(activity, /pendingReminderPermissionStep/);
assert.match(activity, /openOverlayPermissionForScheduledReminder\(\)/);
assert.match(activity, /ReminderScheduler\.reconcileStored\(this\)/);

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

const manifest = fs.readFileSync(path.join(app, 'src', 'main', 'AndroidManifest.xml'), 'utf8');
for (const permission of ['POST_NOTIFICATIONS', 'USE_EXACT_ALARM', 'SYSTEM_ALERT_WINDOW',
  'FOREGROUND_SERVICE', 'FOREGROUND_SERVICE_SPECIAL_USE', 'RECEIVE_BOOT_COMPLETED']) {
  assert.match(manifest, new RegExp(`android\\.permission\\.${permission}`));
}
assert.match(manifest, /ReminderOverlayService/);
assert.match(manifest, /ReminderDeliveryWatchdogReceiver/);
assert.match(manifest, /BootReceiver/);

const alarmReceiver = fs.readFileSync(path.join(app, 'src', 'main', 'java', 'com', 'mohan',
  'mylifemanager', 'NotificationAlarmReceiver.java'), 'utf8');
assert.match(alarmReceiver, /alarm-received/);

const watchdog = fs.readFileSync(path.join(app, 'src', 'main', 'java', 'com', 'mohan',
  'mylifemanager', 'ReminderDeliveryWatchdogReceiver.java'), 'utf8');
assert.match(watchdog, /notification-watchdog/);
assert.match(watchdog, /NotificationPublisher\.show/);

const gradle = fs.readFileSync(path.join(app, 'build.gradle.kts'), 'utf8');
assert.match(gradle, /versionCode = 913253/);
assert.match(gradle, /versionName = "9\.13\.253-corex"/);

console.log('Corex v9.13.253 verification passed');
