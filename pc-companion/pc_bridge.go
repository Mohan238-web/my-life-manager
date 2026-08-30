package main

const pcBridgeScript = `<script id="corex-desktop-bridge-runtime">
(()=>{
 'use strict';
 let revision=-1,lastSnapshot='',saving=false,applying=false,saveTimer=0,pendingSnapshot=null,pendingBaseline='',statusText='Connecting to Companion…';
 const snapshot=()=>{const value={};try{const keys=[];for(let i=0;i<localStorage.length;i++){const key=localStorage.key(i);if(key!==null)keys.push(key)}keys.sort();for(const key of keys)value[key]=localStorage.getItem(key)}catch{}return JSON.stringify(value)};
 const api=async(path,options)=>{const response=await fetch(path,options);const value=await response.json().catch(()=>({}));if(!response.ok)throw new Error(value.error||'Corex Companion request failed.');return value};
 const setStatus=text=>{statusText=text;try{sessionStorage.setItem('corex.pc.desktop.status',text);dispatchEvent(new CustomEvent('corex-pc-sync-status',{detail:{text,revision}}))}catch{}};
 function activeWorkspaceBusy(){
  try{
   const frame=document.querySelector('iframe.frame.active'),doc=frame?.contentDocument,focused=doc?.activeElement;
   const editing=!!focused&&focused!==doc.body&&focused.matches?.('input,textarea,select,[contenteditable="true"],[contenteditable=""]');
   const dialog=!!doc?.querySelector('dialog[open],.modal:not([hidden]),.overlay:not([hidden]),[role="dialog"]:not([hidden])');
   return editing||dialog;
  }catch{return false}
 }
 function publishSnapshot(changedKeys){
  const detail={source:'phone',changedKeys,revision,at:Date.now()};
  try{dispatchEvent(new CustomEvent('corex-pc-snapshot-applied',{detail}))}catch{}
  document.querySelectorAll('iframe.frame').forEach(frame=>{try{frame.contentWindow?.postMessage({type:'corex-pc-snapshot-applied',...detail},'*')}catch{}});
 }
 function applySnapshot(encoded,nextRevision){
  if(activeWorkspaceBusy()){if(!pendingSnapshot)pendingBaseline=snapshot();pendingSnapshot={encoded,nextRevision};setStatus('Phone changes are ready and will apply after the current edit is closed.');return false}
  let next;try{next=JSON.parse(encoded)}catch{return false}if(!next||Array.isArray(next)||typeof next!=='object')return false;
  applying=true;
  try{
   const changedKeys=[],keys=[];for(let i=0;i<localStorage.length;i++)keys.push(localStorage.key(i));
   for(const key of keys)if(key!==null&&!Object.prototype.hasOwnProperty.call(next,key)){localStorage.removeItem(key);changedKeys.push(key)}
   for(const [key,value]of Object.entries(next)){const text=String(value);if(localStorage.getItem(key)!==text)changedKeys.push(key);localStorage.setItem(key,text)}
   revision=Number(nextRevision)||0;lastSnapshot=snapshot();pendingSnapshot=null;pendingBaseline='';
   sessionStorage.setItem('corex.pc.desktop.revision',String(revision));publishSnapshot([...new Set(changedKeys)]);
   setStatus('Synchronized in the background · Revision '+revision);return true;
  }finally{applying=false}
 }
 async function receive(){
  if(saving||applying)return;
  const state=await api('/dashboard/state'),current=snapshot();
  if(Number(state.revision)>0&&state.snapshot&&state.snapshot!==current&&Number(state.revision)!==revision){applySnapshot(state.snapshot,Number(state.revision));return}
  revision=Number(state.revision)||0;lastSnapshot=current;sessionStorage.setItem('corex.pc.desktop.revision',String(revision));setStatus('Synchronized · Revision '+revision)
 }
 async function saveNow(force){
  if(saving||applying)return;clearTimeout(saveTimer);const current=snapshot();if(!force&&current===lastSnapshot)return;
  saving=true;setStatus('Saving PC changes…');
  try{const result=await api('/dashboard/update',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({snapshot:current})});revision=Number(result.revision)||revision;lastSnapshot=current;sessionStorage.setItem('corex.pc.desktop.revision',String(revision));setStatus('PC changes ready for phone · Revision '+revision)}catch(error){setStatus(error.message)}finally{saving=false}
 }
 function queueSave(){if(applying)return;clearTimeout(saveTimer);saveTimer=setTimeout(()=>saveNow(false),2500)}
 setInterval(()=>{if(pendingSnapshot&&!activeWorkspaceBusy()){if(snapshot()!==pendingBaseline){pendingSnapshot=null;pendingBaseline='';setStatus('Your current PC edit was kept and will synchronize to the phone.');queueSave()}else applySnapshot(pendingSnapshot.encoded,pendingSnapshot.nextRevision)}const current=snapshot();if(current!==lastSnapshot)queueSave()},1000);
 setInterval(()=>receive().catch(error=>setStatus(error.message)),7000);
 addEventListener('pagehide',()=>saveNow(false));
 globalThis.CorexPcDesktop={syncNow:async()=>{await saveNow(true);await receive();return statusText},status:()=>statusText};
 const timers=new Map();
 const notify=payload=>{try{const notice=new Notification(payload.title||'Corex',{body:payload.body||'',tag:String(payload.id||'corex-reminder')});notice.onclick=()=>{window.focus();notice.close()}}catch{}};
 globalThis.MLMNativeNotifications={
  schedule(payload={}){const id=String(payload.id||Date.now()),delay=Number(payload.at)-Date.now();if(!(delay>0))return Promise.resolve('past-time');if(timers.has(id))clearTimeout(timers.get(id));timers.set(id,setTimeout(()=>{timers.delete(id);if(Notification.permission==='granted')notify(payload)},Math.min(delay,2147483647)));return Promise.resolve('scheduled-windows')},
  cancel(payload={}){const id=String(payload.id||'');if(timers.has(id))clearTimeout(timers.get(id));timers.delete(id);return Promise.resolve('cancelled')},
  permissionStatus(){return Promise.resolve('Notification'in window?Notification.permission:'denied')},
  requestPermission(){return 'Notification'in window?Notification.requestPermission():Promise.resolve('denied')},
  exactAlarmPermissionStatus(){return Promise.resolve('granted')},requestExactAlarmPermission(){return Promise.resolve('granted')},overlayPermissionStatus(){return Promise.resolve('granted')},requestOverlayPermission(){return Promise.resolve('granted')},deliveryStatus(){return Promise.resolve('Windows notification ready')},testNow(payload={}){if(Notification.permission==='granted')notify(payload);return Promise.resolve('shown')}
 };
 const connectCard=document.getElementById('syncDevicesCard');if(connectCard)connectCard.innerHTML='<h3>Phone connection</h3><p>QR pairing, Wi-Fi, hotspot, USB tethering, connected phones and protected backups are managed in the Companion control centre.</p><div class="actions"><a class="action primary" href="/">Open Companion control centre</a></div>';
 revision=Number(sessionStorage.getItem('corex.pc.desktop.revision'))||-1;receive().catch(error=>setStatus(error.message));
})();
</script>`
