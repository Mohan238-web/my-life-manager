import fs from 'node:fs';

const input=process.argv[2],output=process.argv[3];
if(!input||!output)throw new Error('Usage: node update-v913259.mjs input.html output.html');
let html=fs.readFileSync(input,'utf8');

html=html.replaceAll('9.13.258','9.13.259');
html=html.replace(
  "{id:'reminders',label:'Reminders',title:'Reminders',heads:['Global Reminder Center','Permissions']}",
  "{id:'reminders',label:'Alert',title:'Reminders',heads:['Global Reminder Center','Permissions']}"
);
html=html.replace(
  "{id:'connection',label:'Connect PC',title:'Connect PC',heads:['Connect PC']}",
  "{id:'connection',label:'PC',title:'Connect PC',heads:['Connect PC']}"
);

html=html.replace(
  "let paired=false,applying=false,syncTimer=0,lastSnapshotHash='';",
  "let paired=false,applying=false,syncTimer=0,lastSnapshotHash='',pendingIncoming='',pendingIncomingBaseline='';"
);

const oldIncoming=` function applyIncoming(encoded){
  if(!encoded)return;let next;try{next=JSON.parse(encoded)}catch{return}if(!next||Array.isArray(next)||typeof next!=='object')return;
  applying=true;try{const current=snapshot();try{sessionStorage.setItem('corex.pc.previousSnapshot',current)}catch{};const keys=[];for(let i=0;i<localStorage.length;i++)keys.push(localStorage.key(i));for(const key of keys)if(key!==null&&!Object.prototype.hasOwnProperty.call(next,key))localStorage.removeItem(key);for(const [key,value] of Object.entries(next))localStorage.setItem(key,String(value));status.textContent='PC changes applied. Reopening Corex…';setTimeout(()=>location.reload(),120)}catch(error){status.textContent='PC changes could not be applied: '+error.message}finally{applying=false}
 }`;
const newIncoming=` function activeWorkspaceBusy(){
  try{
   const frame=document.querySelector('iframe.frame.active'),doc=frame?.contentDocument;
   const focused=doc?.activeElement;
   const editing=!!focused&&focused!==doc.body&&focused.matches?.('input,textarea,select,[contenteditable="true"],[contenteditable=""]');
   const dialog=!!doc?.querySelector('dialog[open],.modal:not([hidden]),.overlay:not([hidden]),[role="dialog"]:not([hidden])');
   return editing||dialog;
  }catch{return false}
 }
 function publishIncoming(changedKeys){
  const detail={source:'pc',changedKeys,at:Date.now()};
  try{dispatchEvent(new CustomEvent('corex-pc-snapshot-applied',{detail}))}catch{}
  document.querySelectorAll('iframe.frame').forEach(frame=>{try{frame.contentWindow?.postMessage({type:'corex-pc-snapshot-applied',...detail},'*')}catch{}});
 }
 function applyIncoming(encoded){
  if(!encoded)return;
  if(activeWorkspaceBusy()){if(!pendingIncoming)pendingIncomingBaseline=simpleHash(snapshot());pendingIncoming=encoded;status.textContent='PC changes are ready and will apply after the current edit is closed.';return}
  let next;try{next=JSON.parse(encoded)}catch{return}if(!next||Array.isArray(next)||typeof next!=='object')return;
  applying=true;try{
   const current=snapshot();try{sessionStorage.setItem('corex.pc.previousSnapshot',current)}catch{}
   const changedKeys=[],keys=[];for(let i=0;i<localStorage.length;i++)keys.push(localStorage.key(i));
   for(const key of keys)if(key!==null&&!Object.prototype.hasOwnProperty.call(next,key)){localStorage.removeItem(key);changedKeys.push(key)}
   for(const [key,value] of Object.entries(next)){const text=String(value);if(localStorage.getItem(key)!==text)changedKeys.push(key);localStorage.setItem(key,text)}
   lastSnapshotHash=simpleHash(snapshot());pendingIncoming='';pendingIncomingBaseline='';publishIncoming([...new Set(changedKeys)]);
   status.textContent='PC changes synchronized in the background. Your open page and writing stay in place.';
  }catch(error){status.textContent='PC changes could not be applied: '+error.message}finally{applying=false}
 }`;
if(!html.includes(oldIncoming))throw new Error('Incoming PC reload path not found');
html=html.replace(oldIncoming,newIncoming);
html=html.replace(
  "addEventListener('visibilitychange',()=>{if(document.visibilityState==='hidden')queue()});addEventListener('pagehide',queue);setInterval(()=>{if(document.visibilityState==='visible')queue()},30000);",
  "addEventListener('visibilitychange',()=>{if(document.visibilityState==='hidden')queue()});addEventListener('pagehide',queue);setInterval(()=>{if(pendingIncoming&&!activeWorkspaceBusy()){if(simpleHash(snapshot())!==pendingIncomingBaseline){pendingIncoming='';pendingIncomingBaseline='';status.textContent='Your current edit was kept and will synchronize to the PC.';queue()}else applyIncoming(pendingIncoming)}},1000);setInterval(()=>{if(document.visibilityState==='visible')queue()},30000);"
);

const addition=`
<style id="corex-approved-settings-v913259-style">
#settingsOverlay #settingsGroupNav{
 display:grid!important;grid-template-columns:repeat(5,minmax(0,1fr))!important;grid-auto-rows:56px!important;
 align-items:stretch!important;gap:6px!important;padding:6px!important;overflow:visible!important;
}
#settingsOverlay #settingsGroupNav button{
 display:flex!important;flex-direction:row!important;align-items:center!important;justify-content:center!important;
 width:100%!important;height:56px!important;min-height:56px!important;min-width:0!important;margin:0!important;padding:7px 5px!important;
 gap:5px!important;border:1px solid var(--line)!important;border-radius:12px!important;background:var(--surface)!important;
 color:var(--muted)!important;box-shadow:0 2px 0 color-mix(in srgb,var(--line) 72%,transparent)!important;
 font-size:10.5px!important;line-height:1!important;white-space:nowrap!important;
}
#settingsOverlay #settingsGroupNav button.on{border-color:color-mix(in srgb,var(--accent) 58%,var(--line))!important;background:var(--accentSoft)!important;color:var(--accent)!important}
#settingsOverlay #settingsGroupNav .settings-section-icon{display:grid!important;place-items:center!important;flex:0 0 18px!important;width:18px!important;height:18px!important;align-self:center!important}
#settingsOverlay #settingsGroupNav .settings-section-icon svg{width:18px!important;height:18px!important}
#settingsOverlay #settingsGroupNav .settings-section-label{display:block!important;flex:0 1 auto!important;width:auto!important;height:auto!important;min-width:0!important;overflow:visible!important;text-overflow:clip!important;white-space:nowrap!important;text-align:left!important;line-height:1!important}
@media(max-width:420px){#settingsOverlay #settingsGroupNav{gap:4px!important;padding:5px!important}#settingsOverlay #settingsGroupNav button{gap:3px!important;padding:6px 3px!important;font-size:9.5px!important}#settingsOverlay #settingsGroupNav .settings-section-icon{flex-basis:16px!important;width:16px!important;height:16px!important}#settingsOverlay #settingsGroupNav .settings-section-icon svg{width:16px!important;height:16px!important}}
@media(max-width:340px){#settingsOverlay #settingsGroupNav{gap:3px!important}#settingsOverlay #settingsGroupNav button{font-size:9px!important;padding-inline:2px!important}}
</style>
<script id="corex-approved-settings-v913259-runtime">
(()=>{
 const labels={general:'General',reminders:'Alert',data:'Data',security:'Security',connection:'PC'};
 const titles={general:'General settings',reminders:'Reminders',data:'Data & backup',security:'Security & confirmations',connection:'Connect PC'};
 for(const [id,label] of Object.entries(labels)){
  const button=document.querySelector('#settingsGroupNav [data-settings-group="'+id+'"]');if(!button)continue;
  const text=button.querySelector('.settings-section-label');if(text)text.textContent=label;
  button.setAttribute('aria-label',titles[id]);button.title=titles[id];
 }
})();
</script>
`;
html=html.replace('</body></html>',addition+'</body></html>');

if(!html.includes("const VERSION='9.13.259'"))throw new Error('Version update failed');
if(html.includes('PC changes applied. Reopening Corex'))throw new Error('PC reload wording remains');
if(!html.includes("label:'Alert'")||!html.includes("label:'PC'"))throw new Error('Short settings labels missing');
if(!html.includes('corex-pc-snapshot-applied'))throw new Error('In-place sync event missing');
fs.writeFileSync(output,html);
