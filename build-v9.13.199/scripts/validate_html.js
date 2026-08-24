const fs = require('fs');
const zlib = require('zlib');
const vm = require('vm');

const file = 'app/src/main/assets/index.html';
const html = fs.readFileSync(file, 'utf8');
const match = html.match(/const pages=(\[[\s\S]*?\]);\s*const toolMeta/);
if (!match) throw new Error('Embedded page bundle missing');
const pages = JSON.parse(match[1]);
if (pages.length !== 5) throw new Error(`Expected five tools, found ${pages.length}`);
const focus = zlib.gunzipSync(Buffer.from(pages.find(page => page.id === 'focus').data, 'base64')).toString('utf8');
const expense = zlib.gunzipSync(Buffer.from(pages.find(page => page.id === 'expense').data, 'base64')).toString('utf8');
const mileage = zlib.gunzipSync(Buffer.from(pages.find(page => page.id === 'mileage').data, 'base64')).toString('utf8');
const required = [
  'entry.priorities=prioritiesOf(entry);const item=entry.priorities[index]',
  'autosaveDraft()',
  'workspace-native-reminder-schedule',
  'focus-reminder-'
];
for (const text of required) if (!focus.includes(text)) throw new Error(`Focus regression: ${text} missing`);
if (focus.includes('const entry=window.draft,item=prioritiesOf(entry)[index]')) throw new Error('Temporary-copy reminder bug returned');
if (!focus.includes('id="navRecord">Records</span>') || !focus.includes('<h2>Records</h2>')) throw new Error('Focus saved-data label is not Records');
if (!expense.includes('data-page="entries"') || !expense.includes('</span>Records</button>')) throw new Error('Expense saved-data label is not Records');
if (!mileage.includes('data-view="records"') || !mileage.includes('class="tab-icon">▤</span>Records</button>')) throw new Error('Mileage saved-data label is not Records');
if (!html.includes('id="globalAddPosition"') || !html.includes("addPosition:'right'")) throw new Error('Shared Add-button position setting missing or not defaulted to Right');
if (!html.includes('mlm-v913199-home-menu-style-a') || !html.includes('.toptools::before{display:none!important')) throw new Error('Home Menu style A override missing');
if (!html.includes('mlm-scroll-hide-approved') || !html.includes('toolsWithAddButtons')) throw new Error('Top-only directional scroll or three-tool Add-button runtime missing');
if (!html.includes('mlm-android-room-runtime-v913197')) throw new Error('Native Room runtime hook missing');
if (!html.includes('MLMHandleAndroidBack')) throw new Error('Android Back handler missing');
if (!html.includes("result.startsWith('scheduled')")) throw new Error('Native exact/WorkManager reminder result handling missing');
if (html.includes("return {ok:false,reason:'exact-alarm-permission'}")) throw new Error('Exact-alarm permission still blocks WorkManager fallback');
function scripts(source) {
  return [...source.matchAll(/<script\b([^>]*)>([\s\S]*?)<\/script>/gi)]
    .filter(item => !/\bsrc\s*=/.test(item[1]) && !/type=["'](?:application\/json|importmap)["']/.test(item[1]))
    .map(item => item[2]);
}
let parsed = 0;
for (const page of pages) {
  const source = zlib.gunzipSync(Buffer.from(page.data, 'base64')).toString('utf8');
  for (const code of scripts(source)) { new vm.Script(code, {filename: `${page.id}-${++parsed}.js`}); }
}
for (const code of scripts(html)) {
  if (code.includes('const pages=')) continue;
  new vm.Script(code, {filename: `shell-${++parsed}.js`});
}
console.log(`HTML validation passed: five tools, stable Focus reminder, Room hooks, Android Back handler, ${parsed} parsed scripts.`);
