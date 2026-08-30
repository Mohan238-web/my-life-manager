import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';

const root = path.resolve(import.meta.dirname, '..');
const htmlPath = path.join(root, 'corex-v9.13.249', 'app', 'src', 'main', 'assets', 'index.html');
let shell = fs.readFileSync(htmlPath, 'utf8');

function pagesRange(source) {
  const marker = 'const pages=';
  const markerIndex = source.indexOf(marker);
  const start = source.indexOf('[', markerIndex);
  if (markerIndex < 0 || start < 0) throw new Error('Embedded pages array not found');
  let depth = 0;
  let quote = null;
  let escaped = false;
  for (let index = start; index < source.length; index += 1) {
    const character = source[index];
    if (quote) {
      if (escaped) escaped = false;
      else if (character === '\\') escaped = true;
      else if (character === quote) quote = null;
      continue;
    }
    if (character === '"' || character === "'") quote = character;
    else if (character === '[') depth += 1;
    else if (character === ']') {
      depth -= 1;
      if (depth === 0) return {start, end: index + 1};
    }
  }
  throw new Error('Embedded pages array is incomplete');
}

function replaceRequired(source, before, after, label = before) {
  if (!source.includes(before)) throw new Error(`Required source fragment missing: ${label}`);
  return source.replace(before, after);
}

function appendBeforeBody(source, addition) {
  const closing = source.lastIndexOf('</body>');
  if (closing < 0) throw new Error('Page body closing tag missing');
  return source.slice(0, closing) + addition + source.slice(closing);
}

function cleanBranding(source) {
  return source
    .replaceAll('My Life Manager', 'Corex')
    .replaceAll('My-Life-Manager', 'Corex');
}

const range = pagesRange(shell);
const pages = JSON.parse(shell.slice(range.start, range.end));
for (const page of pages) {
  let source = zlib.gunzipSync(Buffer.from(page.data, 'base64')).toString('utf8');
  source = cleanBranding(source)
    .replaceAll('Checking browser storage…', 'Checking app storage…')
    .replaceAll('Browser storage used', 'App storage used')
    .replaceAll('Browser storage is full.', 'App storage is full.')
    .replaceAll('Could not save in this browser', 'Could not save in Corex');

  if (page.id === 'focus') {
    source = appendBeforeBody(source, `
<style id="corex-cleanup-v913255-focus">
[data-corex-retired="true"]{display:none!important}
</style>
<script id="corex-cleanup-v913255-focus-runtime">
(()=>{
  const retireCard=id=>document.getElementById(id)?.closest('.card')?.setAttribute('data-corex-retired','true');
  retireCard('testAlertBtn');
  retireCard('lockEnableToggle');
  [...document.querySelectorAll('#screen-settings .card')].find(card=>/About this file/i.test(card.textContent||''))?.setAttribute('data-corex-retired','true');
})();
</script>
`);
  }

  if (page.id === 'expense') {
    source = appendBeforeBody(source, `
<style id="corex-cleanup-v913255-expense">
#driveSettingsModal,#pcSettingsModal,#pinSettingsModal,#pinLockScreen,
button[onclick*="requestNotifications"]{display:none!important}
</style>
`);
  }

  if (page.id === 'notes') {
    source = source
      .replaceAll('Search note titles, text, actions and folders', 'Search note titles, text, actions and categories')
      .replaceAll('My Life Manager System Check', 'Corex System Check');
    source = appendBeforeBody(source, `
<style id="corex-cleanup-v913255-notes">
#foldersBtn,#folderDialog,#moveDialog,#systemCheckBtn,#systemCheckDialog,#notifyBtn,#notifyHint,
[data-note-filter="folder"],[data-note-filter-panel="folder"],#folderFilter,#folderBtn,
[data-property-field="folder"],[data-saved-property166="folder"],[data-saved-action166="move"]{display:none!important}
</style>
`);
  }

  page.data = zlib.gzipSync(Buffer.from(source), {level: 9}).toString('base64');
}

shell = shell.slice(0, range.start) + JSON.stringify(pages) + shell.slice(range.end);
shell = cleanBranding(shell);

shell = replaceRequired(shell,
  '<meta name="application-name" content="Corex"><meta name="mlm-build" content="9.13.236-tool-isolated-back"><title>Corex — Stable HTML v9.13.236</title>',
  '<meta name="application-name" content="Corex"><meta name="mlm-build" content="9.13.255-complete-cleanup"><title>Corex v9.13.255</title>',
  'Corex document metadata');

shell = replaceRequired(shell,
  "{id:'connection',label:'Connect',title:'Connection',heads:['Sync & devices']},\n  {id:'advanced',label:'Advanced',title:'Advanced',heads:['App health check','About & reset']}",
  "{id:'connection',label:'Recovery',title:'Recovery',heads:['Versioned data safety']},\n  {id:'advanced',label:'About',title:'About Corex',heads:['About & reset']}" ,
  'Settings section groups');

shell = shell
  .replaceAll("document.title='Corex — Stable HTML v'+version", "document.title='Corex v'+version")
  .replaceAll("document.title='Corex — Stable HTML v'+VERSION", "document.title='Corex v'+VERSION")
  .replace("const VERSION='9.13.254'", "const VERSION='9.13.255'")
  .replace("version:'9.13.239',name:'Habit Schedule Order and Review Ranges'", "version:'9.13.255',name:'Complete Interface Cleanup'");

const shellCleanup = `
<style id="corex-cleanup-v913255-shell">
[data-corex-retired="true"]{display:none!important}
</style>
<script id="corex-cleanup-v913255-shell-runtime">
(()=>{
  const retiredHeadings=new Set([
    'Language & region','Currency & units','Google Drive Backup','Sync & devices',
    'Data & storage','App health check'
  ]);
  const cards=[...document.querySelectorAll('#settingsOverlay .panelbody > section.card')];
  cards.forEach(card=>{
    const heading=card.querySelector(':scope > h3')?.textContent.trim()||'';
    if(retiredHeadings.has(heading))card.dataset.corexRetired='true';
  });
  const retireRow=id=>document.getElementById(id)?.closest('.row')?.setAttribute('data-corex-retired','true');
  retireRow('globalSnoozeMinutes');
  retireRow('memorySelect');
  const memory=document.getElementById('memorySelect');if(memory)memory.value='keep';
  const back=document.getElementById('globalBackBehavior');
  if(back){back.value='safe';back.querySelector('option[value="history"]')?.remove()}
  document.getElementById('mlmOpenCamera')?.closest('.card')?.setAttribute('data-corex-retired','true');
  const about=cards.find(card=>card.querySelector(':scope > h3')?.textContent.trim()==='About & reset');
  if(about){
    const paragraph=about.querySelector(':scope > p');
    if(paragraph)paragraph.innerHTML='<b>Corex v9.13.255</b> · Private, local-first organization workspace.';
    const stats=about.querySelector('.settings-grid');
    if(stats)stats.innerHTML='<div class="settings-stat"><b>Data policy</b><small>Your tool records remain separate from global settings</small></div><div class="settings-stat"><b>Update policy</b><small>Existing saved data is preserved</small></div>';
  }
  const title=document.getElementById('settingsTitle');if(title)title.textContent='Corex settings';
  const locked=document.getElementById('lockTitle');if(locked&&/Corex is locked/i.test(locked.textContent||''))locked.textContent='Corex is locked';
})();
</script>
`;
shell = appendBeforeBody(shell, shellCleanup);

if (!shell.includes("const VERSION='9.13.255'")) throw new Error('Corex v9.13.255 version marker was not installed');
fs.writeFileSync(htmlPath, shell);
console.log(`Updated ${htmlPath}`);
console.log(`Embedded tools: ${pages.map(page=>page.id).join(', ')}`);
