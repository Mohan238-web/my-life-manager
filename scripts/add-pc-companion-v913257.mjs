import fs from 'node:fs';
import path from 'node:path';

const file=path.resolve('corex-v9.13.249/app/src/main/assets/index.html');
let html=fs.readFileSync(file,'utf8');
const oldStart='<section class="card" id="syncDevicesCard">';
const start=html.indexOf(oldStart);
if(start<0)throw new Error('Sync & devices card not found');
const end=html.indexOf('</section>',start);
if(end<0)throw new Error('Sync & devices card end not found');
const card=`<section class="card" id="syncDevicesCard"><h3>Connect PC</h3><p>Securely connect Corex to the Windows PC Companion through the same Wi-Fi, phone hotspot, or USB tethering. No cloud account or internet connection is required.</p>
<div class="settings-grid corex-pc-status-grid"><div class="settings-stat"><b>Connection</b><small id="pcConnectionState">Not paired</small></div><div class="settings-stat"><b>Method</b><small id="pcTransportState">Wi-Fi / hotspot / USB</small></div></div>
<div class="corex-pc-pair-grid"><label><span>PC address</span><input id="pcHost" inputmode="decimal" autocomplete="off" placeholder="192.168.1.20"></label><label><span>Port</span><input id="pcPort" inputmode="numeric" autocomplete="off" value="47625"></label><label><span>6-digit PIN</span><input id="pcPin" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="000000"></label></div>
<div class="actions corex-pc-actions"><button class="action primary" id="pcConnect" type="button">Connect securely</button><button class="action" id="pcSyncNow" type="button">Sync now</button><button class="action danger" id="pcDisconnect" type="button">Disconnect PC</button></div>
<div class="status" id="pcPairingStatus">On the PC, open Corex PC Companion. Scan its QR with the phone camera or enter the displayed address and PIN here.</div>
<div class="status" id="pcLastSyncStatus">No PC synchronization yet.</div>
<p class="corex-pc-security-note">Corex encrypts record contents before they travel across the local network. The PC keeps a protected safety version before every edit or deletion.</p></section>`;
if(!html.slice(start,end).includes('<h3>Connect PC</h3>'))html=html.slice(0,start)+card+html.slice(end+'</section>'.length);

html=html.replaceAll("'9.13.256'","'9.13.257'")
  .replaceAll('9.13.256-complete-cleanup','9.13.257-secure-pc-connection')
  .replaceAll("version:'9.13.256'","version:'9.13.257'")
  .replaceAll('Corex v9.13.256','Corex v9.13.257')
  .replaceAll('Version 9.13.256','Version 9.13.257')
  .replace("name:'Complete Interface Cleanup'","name:'Secure PC Companion Connection'");

html=html.replace("'Language & region','Currency & units','Google Drive Backup','Sync & devices',","'Language & region','Currency & units','Google Drive Backup',");
html=html.replace("connection:'<svg", "connection:'<svg");
html=html.replace("{id:'connection',label:'Recovery',title:'Recovery',heads:['Versioned data safety']}","{id:'connection',label:'Connect PC',title:'Connect PC',heads:['Connect PC']}");
html=html.replaceAll('#settingsGroupNav [data-settings-group="connection"],\n#settingsGroupNav [data-settings-group="advanced"]{display:none!important}','#settingsGroupNav [data-settings-group="advanced"]{display:none!important}');
html=html.replace('grid-template-columns:repeat(4,minmax(0,1fr))!important','grid-template-columns:repeat(5,minmax(0,1fr))!important');
html=html.replace("document.querySelectorAll('#settingsGroupNav [data-settings-group=\"connection\"],#settingsGroupNav [data-settings-group=\"advanced\"]')","document.querySelectorAll('#settingsGroupNav [data-settings-group=\"advanced\"]')");

const addition=String.raw`
<style id="corex-pc-connection-v913257-style">
#syncDevicesCard{max-width:900px}.corex-pc-status-grid{grid-template-columns:repeat(2,minmax(0,1fr))}.corex-pc-pair-grid{display:grid;grid-template-columns:minmax(0,1.5fr) 110px minmax(140px,.8fr);gap:10px;margin:12px 0}.corex-pc-pair-grid label{display:grid;gap:5px;color:var(--muted);font-size:11px;font-weight:800}.corex-pc-pair-grid input{width:100%;min-height:48px;border:1px solid var(--line);border-radius:13px;background:var(--surface);color:var(--text);padding:9px 11px;font:750 15px/1.2 inherit}.corex-pc-pair-grid #pcPin{letter-spacing:.16em;font-size:17px}.corex-pc-actions .action{min-height:48px}.corex-pc-security-note{margin:12px 0 0;color:var(--muted);font-size:12px}.corex-pc-working{opacity:.68;pointer-events:none}
@media(max-width:620px){.corex-pc-pair-grid{grid-template-columns:minmax(0,1fr) 90px}.corex-pc-pair-grid label:last-child{grid-column:1/-1}.corex-pc-actions{display:grid!important;grid-template-columns:1fr 1fr}.corex-pc-actions .danger{grid-column:1/-1}.corex-pc-status-grid{grid-template-columns:1fr}}
</style>
<script id="corex-pc-connection-v913257-runtime">
(()=>{
 'use strict';
 const native=globalThis.CorexPcNative,$=id=>document.getElementById(id),card=$('syncDevicesCard');
 if(!card)return;
 const host=$('pcHost'),port=$('pcPort'),pin=$('pcPin'),connect=$('pcConnect'),sync=$('pcSyncNow'),disconnect=$('pcDisconnect'),status=$('pcPairingStatus'),last=$('pcLastSyncStatus'),connection=$('pcConnectionState'),transport=$('pcTransportState');
 let paired=false,applying=false,syncTimer=0,lastSnapshotHash='';
 const snapshot=()=>{const value={};try{for(let i=0;i<localStorage.length;i++){const key=localStorage.key(i);if(key!==null)value[key]=localStorage.getItem(key)}}catch{}return JSON.stringify(value)};
 const simpleHash=value=>{let hash=2166136261;for(let i=0;i<value.length;i++){hash^=value.charCodeAt(i);hash=Math.imul(hash,16777619)}return(hash>>>0).toString(16)};
 const setWorking=value=>card.classList.toggle('corex-pc-working',!!value);
 const formatTime=value=>value?new Date(Number(value)).toLocaleString():'Never';
 function render(state={}){
  paired=!!state.paired;connection.textContent=paired?'Connected to '+(state.serverName||'Corex PC'):'Not paired';transport.textContent=state.transport||'Wi-Fi / hotspot / USB';
  if(state.host)host.value=state.host;if(state.port)port.value=state.port;
  sync.disabled=!paired;disconnect.disabled=!paired;connect.textContent=paired?'Pair another PC':'Connect securely';
  last.textContent=state.lastSync?'Last synchronized: '+formatTime(state.lastSync)+(state.revision?' · Revision '+state.revision:''):'No PC synchronization yet.';
  if(state.lastError&&!state.message)status.textContent=state.lastError;
 }
 function applyIncoming(encoded){
  if(!encoded)return;let next;try{next=JSON.parse(encoded)}catch{return}if(!next||Array.isArray(next)||typeof next!=='object')return;
  applying=true;try{const current=snapshot();try{sessionStorage.setItem('corex.pc.previousSnapshot',current)}catch{};const keys=[];for(let i=0;i<localStorage.length;i++)keys.push(localStorage.key(i));for(const key of keys)if(key!==null&&!Object.prototype.hasOwnProperty.call(next,key))localStorage.removeItem(key);for(const [key,value] of Object.entries(next))localStorage.setItem(key,String(value));status.textContent='PC changes applied. Reopening Corex…';setTimeout(()=>location.reload(),120)}catch(error){status.textContent='PC changes could not be applied: '+error.message}finally{applying=false}
 }
 const events={
  state:event=>render(event),working:event=>{setWorking(true);status.textContent=event.message||'Connecting…'},paired:event=>{setWorking(false);render(event);pin.value='';status.textContent='Connected securely. Wi-Fi, hotspot and USB tethering now use the same protected connection.'},synced:event=>{setWorking(false);render(event);status.textContent=event.changed?'PC changes received securely.':'Phone and PC are synchronized.';if(event.snapshot)applyIncoming(event.snapshot)},incoming:event=>{setWorking(false);render(event);status.textContent='Encrypted PC changes received.';applyIncoming(event.snapshot)},disconnected:event=>{setWorking(false);render(event);status.textContent='PC disconnected. Corex data remains safely on this phone.'},error:event=>{setWorking(false);render(event);status.textContent=event.message||'PC connection failed.'}
 };
 const api={
  onNativeEvent(event){try{(events[event?.type]||render)(event||{})}catch{}},
  onPairLink(details={}){host.value=details.host||'';port.value=details.port||'47625';pin.value=String(details.code||'').replace(/\D/g,'').slice(0,6);try{openSettings();MLMSelectSettingsGroup?.('connection')}catch{}status.textContent='QR received from Corex PC Companion. Check the address, then tap Connect securely.';setTimeout(()=>connect.focus(),160)}
 };
 globalThis.CorexPcConnection=api;
 function readState(){if(!native){status.textContent='Install the Corex Android app to connect with the Windows Companion.';connect.disabled=true;sync.disabled=true;disconnect.disabled=true;return}try{render(JSON.parse(native.state()||'{}'))}catch{}}
 connect.addEventListener('click',()=>{if(!native)return;const cleanHost=host.value.trim(),cleanPort=Number(port.value)||47625,cleanPin=pin.value.replace(/\D/g,'');if(!cleanHost){status.textContent='Enter the PC address shown by Corex Companion.';host.focus();return}if(cleanPin.length!==6){status.textContent='Enter the six-digit PIN shown by Corex Companion.';pin.focus();return}setWorking(true);status.textContent='Pairing securely with the PC…';native.pair(cleanHost,cleanPort,cleanPin,snapshot())});
 sync.addEventListener('click',()=>{if(!native||!paired)return;setWorking(true);status.textContent='Synchronizing with Corex Companion…';native.syncNow(snapshot())});
 disconnect.addEventListener('click',()=>{if(!native||!paired)return;if(confirm('Disconnect this PC? Corex records will remain on the phone and PC.'))native.disconnect()});
 const queue=()=>{if(!native||!paired||applying)return;clearTimeout(syncTimer);syncTimer=setTimeout(()=>{const value=snapshot(),hash=simpleHash(value);if(hash===lastSnapshotHash)return;lastSnapshotHash=hash;try{native.queueSnapshot(value)}catch{}},2500)};
 try{const saveState=document.getElementById('globalSaveState');if(saveState)new MutationObserver(queue).observe(saveState,{attributes:true,childList:true,subtree:true,characterData:true})}catch{}
 addEventListener('visibilitychange',()=>{if(document.visibilityState==='hidden')queue()});addEventListener('pagehide',queue);setInterval(()=>{if(document.visibilityState==='visible')queue()},30000);
 readState();
})();
</script>`;
if(!html.includes('corex-pc-connection-v913257-runtime'))html=html.replace('</body></html>',addition+'\n</body></html>');
fs.writeFileSync(file,html);
