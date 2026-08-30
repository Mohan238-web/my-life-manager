package main

const pcBridgeScript = `<style id="corex-desktop-bridge-style">
#corexPcDesktopBar{position:fixed;right:18px;bottom:18px;z-index:2147483000;display:flex;align-items:center;gap:8px;padding:8px;border:1px solid rgba(47,118,109,.28);border-radius:16px;background:rgba(255,255,255,.96);box-shadow:0 12px 36px rgba(20,42,34,.2);font:700 12px/1.2 system-ui,-apple-system,"Segoe UI",sans-serif;color:#17211e}
#corexPcDesktopBar button,#corexPcDesktopBar a{min-height:38px;padding:8px 11px;border:1px solid #d7dfda;border-radius:11px;background:#fff;color:#17211e;text-decoration:none;font:inherit;cursor:pointer}
#corexPcDesktopBar button.primary{border-color:#2f766d;background:#2f766d;color:#fff}#corexPcDesktopState{max-width:190px;color:#2f766d}
@media(max-width:720px){#corexPcDesktopBar{left:8px;right:8px;bottom:8px;justify-content:center;flex-wrap:wrap}#corexPcDesktopState{width:100%;max-width:none;text-align:center}}
@media(prefers-color-scheme:dark){#corexPcDesktopBar{background:rgba(32,39,36,.96);color:#eef4f1}#corexPcDesktopBar button,#corexPcDesktopBar a{background:#202724;color:#eef4f1;border-color:#3b4641}}
</style>
<script id="corex-desktop-bridge-runtime">
(()=>{
 'use strict';
 const bar=document.createElement('div');bar.id='corexPcDesktopBar';bar.innerHTML='<span id="corexPcDesktopState">Connecting to Companion…</span><button class="primary" id="corexPcDesktopSync" type="button">Sync now</button><button id="corexPcWindowsAlerts" type="button">Windows reminders</button><a href="/">Connection & backups</a>';document.body.appendChild(bar);
 const status=document.getElementById('corexPcDesktopState'),syncButton=document.getElementById('corexPcDesktopSync'),alerts=document.getElementById('corexPcWindowsAlerts');
 let revision=-1,lastSnapshot='',saving=false,applying=false,saveTimer=0;
 const snapshot=()=>{const value={};try{const keys=[];for(let i=0;i<localStorage.length;i++){const key=localStorage.key(i);if(key!==null)keys.push(key)}keys.sort();for(const key of keys)value[key]=localStorage.getItem(key)}catch{}return JSON.stringify(value)};
 const api=async(path,options)=>{const response=await fetch(path,options);const value=await response.json().catch(()=>({}));if(!response.ok)throw new Error(value.error||'Corex Companion request failed.');return value};
 const setStatus=text=>{status.textContent=text};
 function applySnapshot(encoded,nextRevision){let next;try{next=JSON.parse(encoded)}catch{return false}if(!next||Array.isArray(next)||typeof next!=='object')return false;applying=true;const keys=[];for(let i=0;i<localStorage.length;i++)keys.push(localStorage.key(i));for(const key of keys)if(key!==null&&!Object.prototype.hasOwnProperty.call(next,key))localStorage.removeItem(key);for(const [key,value]of Object.entries(next))localStorage.setItem(key,String(value));sessionStorage.setItem('corex.pc.desktop.revision',String(nextRevision));applying=false;location.reload();return true}
 async function receive(){if(saving||applying)return;const state=await api('/dashboard/state'),current=snapshot();if(Number(state.revision)>0&&state.snapshot&&state.snapshot!==current&&Number(state.revision)!==revision){setStatus('Receiving phone changes…');applySnapshot(state.snapshot,Number(state.revision));return}revision=Number(state.revision)||0;lastSnapshot=current;sessionStorage.setItem('corex.pc.desktop.revision',String(revision));setStatus('Synchronized · Revision '+revision)}
 async function saveNow(force){if(saving||applying)return;clearTimeout(saveTimer);const current=snapshot();if(!force&&current===lastSnapshot)return;saving=true;setStatus('Saving PC changes…');try{const result=await api('/dashboard/update',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({snapshot:current})});revision=Number(result.revision)||revision;lastSnapshot=current;sessionStorage.setItem('corex.pc.desktop.revision',String(revision));setStatus('PC changes ready for phone · Revision '+revision)}catch(error){setStatus(error.message)}finally{saving=false}}
 function queueSave(){if(applying)return;clearTimeout(saveTimer);saveTimer=setTimeout(()=>saveNow(false),2500)}
 syncButton.onclick=async()=>{await saveNow(true);await receive()};
 setInterval(()=>{const current=snapshot();if(current!==lastSnapshot)queueSave()},1500);setInterval(()=>receive().catch(error=>setStatus(error.message)),7000);
 addEventListener('pagehide',()=>saveNow(false));
 const timers=new Map();
 const notify=payload=>{try{const notice=new Notification(payload.title||'Corex',{body:payload.body||'',tag:String(payload.id||'corex-reminder')});notice.onclick=()=>{window.focus();notice.close()}}catch{}};
 globalThis.MLMNativeNotifications={
  schedule(payload={}){const id=String(payload.id||Date.now()),delay=Number(payload.at)-Date.now();if(!(delay>0))return Promise.resolve('past-time');if(timers.has(id))clearTimeout(timers.get(id));timers.set(id,setTimeout(()=>{timers.delete(id);if(Notification.permission==='granted')notify(payload)},Math.min(delay,2147483647)));return Promise.resolve('scheduled-windows')},
  cancel(payload={}){const id=String(payload.id||'');if(timers.has(id))clearTimeout(timers.get(id));timers.delete(id);return Promise.resolve('cancelled')},
  permissionStatus(){return Promise.resolve('Notification'in window?Notification.permission:'denied')},
  requestPermission(){return 'Notification'in window?Notification.requestPermission():Promise.resolve('denied')},
  exactAlarmPermissionStatus(){return Promise.resolve('granted')},requestExactAlarmPermission(){return Promise.resolve('granted')},overlayPermissionStatus(){return Promise.resolve('granted')},requestOverlayPermission(){return Promise.resolve('granted')},deliveryStatus(){return Promise.resolve('Windows notification ready')},testNow(payload={}){if(Notification.permission==='granted')notify(payload);return Promise.resolve('shown')}
 };
 alerts.onclick=async()=>{if(!('Notification'in window)){setStatus('Windows notifications are unavailable in this browser.');return}const permission=await Notification.requestPermission();setStatus(permission==='granted'?'Windows reminders enabled.':'Allow notifications for this local Corex page.');alerts.textContent=permission==='granted'?'Reminders enabled':'Windows reminders'};
 if('Notification'in window&&Notification.permission==='granted')alerts.textContent='Reminders enabled';
 const connectCard=document.getElementById('syncDevicesCard');if(connectCard)connectCard.innerHTML='<h3>Phone connection</h3><p>This PC uses the Companion control centre for QR pairing, USB tethering, connected phones and protected backups.</p><div class="actions"><a class="action primary" href="/">Open connection & backups</a></div>';
 const connectionLabel=document.querySelector('#settingsGroupNav [data-settings-group="connection"] .settings-section-label');if(connectionLabel)connectionLabel.textContent='Phone link';
 revision=Number(sessionStorage.getItem('corex.pc.desktop.revision'))||-1;receive().catch(error=>setStatus(error.message));
})();
</script>`
