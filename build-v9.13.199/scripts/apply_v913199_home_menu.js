#!/usr/bin/env node
'use strict';

const fs = require('fs');
const path = require('path');
const zlib = require('zlib');

const file = path.resolve(__dirname, '../app/src/main/assets/index.html');
let html = fs.readFileSync(file, 'utf8');

function replaceOnce(source, before, after, label) {
  const count = source.split(before).length - 1;
  if (count !== 1) throw new Error(`${label}: expected 1 match, found ${count}`);
  return source.replace(before, after);
}

const pagesStart = html.indexOf('const pages=') + 'const pages='.length;
const pagesEnd = html.indexOf(';\nconst toolMeta=', pagesStart);
if (pagesStart < 'const pages='.length || pagesEnd < 0) throw new Error('Embedded page bundle not found');
const pages = JSON.parse(html.slice(pagesStart, pagesEnd));

function updatePage(id, updater) {
  const page = pages.find(item => item.id === id);
  if (!page) throw new Error(`Missing embedded page: ${id}`);
  const source = zlib.gunzipSync(Buffer.from(page.data, 'base64')).toString('utf8');
  const updated = updater(source);
  if (updated === source) throw new Error(`No visible update made to ${id}`);
  page.data = zlib.gzipSync(Buffer.from(updated, 'utf8'), { level: 9 }).toString('base64');
}

updatePage('focus', source => {
  source = replaceOnce(source,
    '<span class="tab-label" id="navRecord">Record</span>',
    '<span class="tab-label" id="navRecord">Records</span>',
    'Focus saved-data tab label');
  source = replaceOnce(source,
    '<div class="eyebrow" id="historyCount">Entries</div>',
    '<div class="eyebrow" id="historyCount">Records</div>',
    'Focus saved-data count label');
  source = replaceOnce(source,
    '<header class="hp-owner-head"><h2>Review</h2><p>Use evidence to improve tomorrow. Review completion and recurring barriers without judging yourself.</p></header>',
    '<header class="hp-owner-head"><h2>Records</h2><p>Use evidence to improve tomorrow. Review completion and recurring barriers without judging yourself.</p></header>',
    'Focus saved-data heading');
  source = replaceOnce(source,
    'records and backup live in Review, and display controls live in the top menu.',
    'records and backup live in Records, and display controls live in the top menu.',
    'Focus ownership help');
  return source;
});

updatePage('expense', source => {
  source = replaceOnce(source,
    '<div><b id="analyticsCount">0</b><small>Entries</small></div>',
    '<div><b id="analyticsCount">0</b><small>Records</small></div>',
    'Expense analytics saved-data label');
  const entryTab = /(<button class="tab" data-page="entries"><span>[\s\S]*?<\/span>)Entries(<\/button>)/;
  if (!entryTab.test(source)) throw new Error('Expense saved-data tab label not found');
  source = source.replace(entryTab, '$1Records$2');
  return source;
});

html = html.slice(0, pagesStart) + JSON.stringify(pages) + html.slice(pagesEnd);

html = replaceOnce(html,
  '<div class="row"><div class="copy"><b>Shell density</b><small>Spacing in the main Settings panel</small></div><select id="globalDensity"><option value="comfortable">Comfortable</option><option value="compact">Compact</option></select></div>\n</section>',
  '<div class="row"><div class="copy"><b>Shell density</b><small>Spacing in the main Settings panel</small></div><select id="globalDensity"><option value="comfortable">Comfortable</option><option value="compact">Compact</option></select></div>\n<div class="row"><div class="copy"><b>Add button position</b><small>One position for the Expense, Notes / To-Do and Mileage Add buttons</small></div><select id="globalAddPosition"><option value="left">Left</option><option value="center">Centre</option><option value="right">Right</option></select></div>\n</section>',
  'Global Add button setting');

html = replaceOnce(html,
  " backBehavior:'safe',density:'comfortable',",
  " backBehavior:'safe',density:'comfortable',addPosition:'right',",
  'Global Add position default');

html = replaceOnce(html,
  "  document.documentElement.dataset.shellDensity=g.density==='compact'?'compact':'comfortable';",
  "  document.documentElement.dataset.shellDensity=g.density==='compact'?'compact':'comfortable';\n  document.documentElement.dataset.shellAddPosition=['left','center','right'].includes(g.addPosition)?g.addPosition:'right';",
  'Apply global Add position');

html = replaceOnce(html,
  '  globalBackBehavior:g.backBehavior,globalDensity:g.density,',
  '  globalBackBehavior:g.backBehavior,globalDensity:g.density,globalAddPosition:g.addPosition,',
  'Sync global Add position');

html = replaceOnce(html,
  " globalBackBehavior:['backBehavior','value'],globalDensity:['density','value'],globalNotifications:['notifications','checked'],",
  " globalBackBehavior:['backBehavior','value'],globalDensity:['density','value'],globalAddPosition:['addPosition','value'],globalNotifications:['notifications','checked'],",
  'Bind global Add position');

const enhancement = String.raw`
<style id="mlm-v913199-home-menu-style-a">
/* Approved Home Menu style A: selected icon uses accent colour only. */
.toptools::before{display:none!important;content:none!important}
.toptools .tool,.toptools .tool:hover,.toptools .tool:focus,.toptools .tool:active,.toptools .tool.active,.toptools .tool[aria-current="page"]{
  border-color:transparent!important;background:transparent!important;box-shadow:none!important
}
.toptools .tool{color:var(--muted)!important}
.toptools .tool.active,.toptools .tool[aria-current="page"]{color:var(--accent)!important}
.toptools .tool.active::after,.toptools .tool[aria-current="page"]::after{display:none!important;content:none!important}

/* The old routine may request a hide; only an approved downward scroll or Notes editor may hide the shell header. */
.shell.nav-auto-hidden:not(.mlm-scroll-hide-approved):not(.editor-focus){grid-template-rows:54px minmax(0,1fr)!important}
.shell.nav-auto-hidden:not(.mlm-scroll-hide-approved):not(.editor-focus) .appbar{display:grid!important}
.shell.nav-auto-hidden:not(.mlm-scroll-hide-approved):not(.editor-focus) .workspace{grid-row:2!important}
.shell.mlm-scroll-hide-approved{grid-template-rows:minmax(0,1fr)!important}
.shell.mlm-scroll-hide-approved .appbar{display:none!important}
.shell.mlm-scroll-hide-approved .workspace{grid-row:1!important}
@media(max-width:720px){.shell.nav-auto-hidden:not(.mlm-scroll-hide-approved):not(.editor-focus){grid-template-rows:52px minmax(0,1fr)!important}}
</style>
<script id="mlm-v913199-home-menu-runtime">
(()=>{
'use strict';
const shell=document.getElementById('shell');
const workspace=document.getElementById('workspace');
if(!shell||!workspace)return;
const toolsWithAddButtons=new Set(['expense','notes','mileage']);
const addSelectors={
  expense:'button.fab[aria-label="Add transaction"]',
  notes:'#newBtn,#todoNewBtn',
  mileage:'#quickAddBtn'
};
const frameState=new WeakMap();
const position=()=>document.documentElement.dataset.shellAddPosition||'right';

function showTop(){
  shell.classList.remove('mlm-scroll-hide-approved','nav-auto-hidden');
  shell.classList.add('nav-manual-visible');
}
function hideTop(){
  if(shell.classList.contains('editor-focus'))return;
  shell.classList.add('mlm-scroll-hide-approved','nav-auto-hidden');
  shell.classList.remove('nav-manual-visible');
}
function scrollTop(doc,target){
  if(target===doc||target===doc.documentElement||target===doc.body)return Number(doc.scrollingElement?.scrollTop||doc.documentElement?.scrollTop||doc.body?.scrollTop||0);
  return Number(target?.scrollTop||0);
}
function direction(doc,target){
  const state=frameState.get(doc)||new WeakMap();
  if(!frameState.has(doc))frameState.set(doc,state);
  const key=target&&typeof target==='object'?target:doc;
  const now=scrollTop(doc,target),before=Number(state.get(key)||0),delta=now-before;
  state.set(key,now);
  if(now<=8)showTop();else if(delta>4)hideTop();else if(delta<-4)showTop();
}
function addStyle(doc){
  let style=doc.getElementById('mlm-global-add-position-style');
  if(!style){style=doc.createElement('style');style.id='mlm-global-add-position-style';(doc.head||doc.documentElement).appendChild(style)}
  style.textContent=[
    'html.mlm-nav-auto-hidden nav.tabbar,html.mlm-nav-auto-hidden #mainNav,html.mlm-nav-auto-hidden #bottom,html.mlm-nav-auto-hidden nav.dock,html.mlm-nav-auto-hidden nav.view-tabs{transform:none!important;opacity:1!important;pointer-events:auto!important}',
    'html[data-mlm-global-add-position="left"] .mlm-global-add-button{left:max(16px,env(safe-area-inset-left))!important;right:auto!important;transform:none!important}',
    'html[data-mlm-global-add-position="center"] .mlm-global-add-button{left:50%!important;right:auto!important;transform:translateX(-50%)!important}',
    'html[data-mlm-global-add-position="right"] .mlm-global-add-button{left:auto!important;right:max(16px,env(safe-area-inset-right))!important;transform:none!important}'
  ].join('');
}
function markAddButtons(frame,doc){
  const tool=frame.dataset.tool;
  if(!toolsWithAddButtons.has(tool))return;
  doc.querySelectorAll(addSelectors[tool]).forEach(button=>button.classList.add('mlm-global-add-button'));
}
function install(frame){
  let doc;
  try{doc=frame.contentDocument}catch{return}
  if(!doc?.documentElement)return;
  addStyle(doc);
  doc.documentElement.dataset.mlmGlobalAddPosition=position();
  markAddButtons(frame,doc);
  if(doc.documentElement.dataset.mlmHomeMenu199==='1')return;
  doc.documentElement.dataset.mlmHomeMenu199='1';
  doc.addEventListener('scroll',event=>direction(doc,event.target),true);
  let touchY=0;
  doc.addEventListener('touchstart',event=>{touchY=Number(event.touches?.[0]?.clientY||0)},{capture:true,passive:true});
  doc.addEventListener('touchmove',event=>{const y=Number(event.touches?.[0]?.clientY||0),delta=touchY-y;touchY=y;if(delta>5)hideTop();else if(delta<-5)showTop()},{capture:true,passive:true});
  new MutationObserver(()=>markAddButtons(frame,doc)).observe(doc.body||doc.documentElement,{childList:true,subtree:true});
}
function refresh(){
  document.querySelectorAll('iframe.frame').forEach(frame=>{
    try{if(frame.contentDocument?.readyState!=='loading')install(frame)}catch{}
    if(frame.dataset.mlmHomeMenu199Hook!=='1'){
      frame.dataset.mlmHomeMenu199Hook='1';
      frame.addEventListener('load',()=>install(frame));
    }
    try{if(frame.contentDocument?.documentElement)frame.contentDocument.documentElement.dataset.mlmGlobalAddPosition=position()}catch{}
  });
}
new MutationObserver(refresh).observe(workspace,{childList:true,subtree:false});
new MutationObserver(refresh).observe(document.documentElement,{attributes:true,attributeFilter:['data-shell-add-position']});
document.getElementById('toolList')?.addEventListener('click',showTop,true);
document.getElementById('settingsBtn')?.addEventListener('click',showTop,true);
refresh();
})();
</script>
`;

if (html.includes('mlm-v913199-home-menu-runtime')) throw new Error('v9.13.199 enhancement already applied');
html = replaceOnce(html, '</body>', enhancement + '\n</body>', 'Append v9.13.199 Home Menu runtime');

fs.writeFileSync(file, html);
console.log('Applied v9.13.199 Home Menu: style A, Records, shared Right Add position, top-only directional scroll hide.');
