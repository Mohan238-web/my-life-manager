import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const root = path.resolve(import.meta.dirname, '..');
const app = path.join(root, 'corex-v9.13.249', 'app');
const asset = path.join(app, 'src', 'main', 'assets', 'index.html');
const partsDir = path.join(app, 'src', 'main', 'assets_parts');

function replaceOnce(source, before, after, label) {
  const first = source.indexOf(before);
  if (first < 0) throw new Error(`Missing ${label}`);
  if (source.indexOf(before, first + before.length) >= 0) throw new Error(`Duplicate ${label}`);
  return source.slice(0, first) + after + source.slice(first + before.length);
}

function unpackPage(container, id) {
  const pattern = new RegExp(`(\\{"id":"${id}","name":"[^"]+","data":")([^"]+)(")`);
  const match = container.match(pattern);
  if (!match) throw new Error(`Embedded ${id} page missing`);
  return {
    html: zlib.gunzipSync(Buffer.from(match[2], 'base64')).toString('utf8'),
    replace(next) {
      const packed = zlib.gzipSync(Buffer.from(next), {level: 9, mtime: 0}).toString('base64');
      shell = shell.replace(pattern, `$1${packed}$3`);
    }
  };
}

let shell = fs.readFileSync(asset, 'utf8');

const notesPage = unpackPage(shell, 'notes');
let notes = notesPage.html;
notes = replaceOnce(
  notes,
  "function reminderInfo(value){if(!value)return null;const time=new Date(value).getTime();if(!Number.isFinite(time))return null;const diff=time-Date.now();if(diff<0)return{class:'overdue',text:'Overdue '+relative(Math.abs(diff))};if(diff<86400000)return{class:'due',text:'Due '+relative(diff)};return{class:'',text:'Due '+new Date(time).toLocaleDateString()}}",
  "function reminderInfo(value){if(!value)return null;const time=new Date(value).getTime();if(!Number.isFinite(time))return null;const due=new Date(time),clock=due.toLocaleTimeString([],{hour:'numeric',minute:'2-digit'}),today=new Date(),sameDay=due.getFullYear()===today.getFullYear()&&due.getMonth()===today.getMonth()&&due.getDate()===today.getDate();return{class:'reminder-time',text:sameDay?clock:due.toLocaleDateString()+' · '+clock}}",
  'Notes reminder display'
);
notes = replaceOnce(
  notes,
  'return `<article class="todo-card ${missed?"overdue":""}">',
  'return `<article class="todo-card ${missed?"overdue":""}" data-priority="${escTodo(t.priority)}">',
  'To-Do critical-card identity'
);
notes = replaceOnce(notes, '</body>', `<style id="corex-v913256-notes-ui">
#toolsDialog .dialog-body{height:auto!important;max-height:min(72vh,620px)!important}
#toolsDialog .dialog-scroll.tools-grid{
 display:grid!important;grid-template-columns:1fr!important;grid-auto-rows:48px!important;
 align-content:start!important;gap:8px!important;flex:0 1 auto!important;min-height:0!important;
 max-height:calc(72vh - 88px)!important;padding:12px 14px!important
}
#toolsDialog .dialog-scroll.tools-grid>button{
 display:flex!important;align-items:center!important;width:100%!important;height:48px!important;
 min-height:48px!important;max-height:48px!important;flex:none!important;padding:0 14px!important;
 text-align:left!important
}
#notes .badge.reminder-time,.todo-reminder-pill{
 background:#fff4cf!important;color:#8a5b00!important;font-weight:800!important
}
#notes .note-card[data-priority="Critical"],.todo-card[data-priority="Critical"]{
 border-left:5px solid var(--danger,#b42318)!important
}
#notes .badge.critical,.todo-pill.critical{
 background:#fde8e7!important;color:#9b1c14!important;font-weight:800!important
}
@media(max-width:660px){
 #toolsDialog{height:auto!important;max-height:72vh!important}
 #toolsDialog .dialog-body{height:auto!important;max-height:72vh!important}
}
</style>
</body>`, 'Notes v9.13.256 UI patch');
notesPage.replace(notes);

const focusPage = unpackPage(shell, 'focus');
let focus = focusPage.html;
focus = replaceOnce(
  focus,
  '<button type="button" class="hp-button" data-test-priority-reminder="${index}" ${done?\'disabled\':\'\'}>Show bottom reminder now</button>',
  '',
  'visible Priority test button'
);
focus = replaceOnce(focus, '</body>', `<style id="corex-v913256-focus-ui">
.mlm-habit-fail-dialog{max-height:calc(100dvh - 18px)!important}
.mlm-habit-fail-form{gap:9px!important;padding:14px!important}
.mlm-habit-fail-form textarea{
 height:64px!important;min-height:64px!important;max-height:96px!important;
 padding:8px 10px!important;resize:vertical!important
}
@media(max-height:720px){
 .mlm-habit-fail-form{gap:7px!important;padding:12px!important}
 .mlm-habit-fail-form textarea{height:56px!important;min-height:56px!important;max-height:76px!important}
}
</style>
</body>`, 'Focus v9.13.256 UI patch');
focusPage.replace(focus);

shell = shell.replaceAll("9.13.255", "9.13.256");
shell = replaceOnce(shell, '</body></html>', `<style id="corex-v913256-shell-ui">
#settingsGroupNav [data-settings-group="connection"],
#settingsGroupNav [data-settings-group="advanced"]{display:none!important}
#settingsOverlay #settingsGroupNav{grid-template-columns:repeat(4,minmax(0,1fr))!important}
@media(max-width:360px){#settingsOverlay #settingsGroupNav{grid-template-columns:repeat(2,minmax(0,1fr))!important}}
</style>
<script id="corex-v913256-shell-runtime">
(()=>{
  document.querySelectorAll('#settingsGroupNav [data-settings-group="connection"],#settingsGroupNav [data-settings-group="advanced"]').forEach(button=>button.remove());
  const cards=[...document.querySelectorAll('#settingsOverlay .panelbody > section.card')];
  cards.find(card=>card.querySelector(':scope > h3')?.textContent.trim()==='About & reset')?.setAttribute('data-corex-retired','true');
})();
</script>
</body></html>`, 'Corex shell close');

fs.writeFileSync(asset, shell);

const packed = zlib.gzipSync(Buffer.from(shell), {level: 9, mtime: 0}).toString('base64');
const chunks = [];
for (let offset = 0; offset < packed.length; offset += 30000) chunks.push(packed.slice(offset, offset + 30000));
for (const name of fs.readdirSync(partsDir)) {
  if (/^index\.html\.gz\.b64\.part-\d+$/.test(name)) fs.unlinkSync(path.join(partsDir, name));
}
chunks.forEach((chunk, index) => {
  const name = `index.html.gz.b64.part-${String(index).padStart(2, '0')}`;
  fs.writeFileSync(path.join(partsDir, name), chunk);
});

console.log(`Updated Corex v9.13.256 shell (${shell.length} bytes, ${chunks.length} source parts)`);
