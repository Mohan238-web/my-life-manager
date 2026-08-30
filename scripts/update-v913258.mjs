import fs from 'node:fs';

const input=process.argv[2],output=process.argv[3];
if(!input||!output)throw new Error('Usage: node update-v913258.mjs input.html output.html');
let html=fs.readFileSync(input,'utf8');

html=html.replaceAll('9.13.257','9.13.258');

const sectionStart=html.indexOf('<section class="card" id="syncDevicesCard">');
if(sectionStart<0)throw new Error('Connect PC card not found');
const sectionEnd=html.indexOf('</section>',sectionStart);
if(sectionEnd<0)throw new Error('Connect PC card end not found');
const card=`<section class="card" id="syncDevicesCard"><h3>Connect PC</h3><p>Choose how this phone reaches Corex PC Companion. Both methods stay local, encrypted and cloud-free.</p>
<div class="settings-grid corex-pc-status-grid"><div class="settings-stat"><b>Connection</b><small id="pcConnectionState">Not paired</small></div><div class="settings-stat"><b>Selected method</b><small id="pcTransportState">Wi-Fi / hotspot</small></div></div>
<div class="corex-pc-methods" role="group" aria-label="PC connection method"><button class="action on" type="button" data-pc-transport="Wi-Fi / hotspot"><span aria-hidden="true">⌁</span><b>Wi-Fi / Hotspot</b><small>Both devices use the same wireless network</small></button><button class="action" type="button" data-pc-transport="USB tethering"><span aria-hidden="true">⌘</span><b>USB tethering</b><small>Connect the cable and enable USB tethering</small></button></div>
<div class="corex-pc-scan-row"><button class="action primary" id="pcScanQr" type="button">Scan PC QR code</button><span>Uses the phone camera and fills the address, port and PIN automatically.</span></div>
<div class="corex-pc-pair-grid"><label><span>PC address</span><input id="pcHost" inputmode="decimal" autocomplete="off" placeholder="192.168.1.20"></label><label><span>Port</span><input id="pcPort" inputmode="numeric" autocomplete="off" value="47625"></label><label><span>6-digit PIN</span><input id="pcPin" inputmode="numeric" autocomplete="one-time-code" maxlength="6" placeholder="000000"></label></div>
<div class="actions corex-pc-actions"><button class="action primary" id="pcConnect" type="button">Connect securely</button><button class="action" id="pcSyncNow" type="button">Sync now</button><button class="action danger" id="pcDisconnect" type="button">Disconnect PC</button></div>
<div class="status" id="pcPairingStatus">Open Corex PC Companion, then scan its QR code or enter the displayed address and PIN.</div>
<div class="status" id="pcLastSyncStatus">No PC synchronization yet.</div>
<p class="corex-pc-security-note">Corex encrypts record contents before they travel across the selected local connection. The PC keeps a protected safety version before every edit or deletion.</p></section>`;
html=html.slice(0,sectionStart)+card+html.slice(sectionEnd+'</section>'.length);

html=html.replace(
  "transport.textContent=state.transport||'Wi-Fi / hotspot / USB';",
  "transport.textContent=localStorage.getItem('corex.pc.transport')||state.transport||'Wi-Fi / hotspot';"
);
html=html.replace(
  "native.pair(cleanHost,cleanPort,cleanPin,snapshot())",
  "native.pair(cleanHost,cleanPort,cleanPin,localStorage.getItem('corex.pc.transport')||'Wi-Fi / hotspot',snapshot())"
);

const addition=`
<style id="corex-pc-correction-v913258-style">
#settingsOverlay #settingsGroupNav{grid-template-columns:repeat(5,minmax(0,1fr))!important;align-items:stretch!important}
#settingsOverlay #settingsGroupNav button{display:grid!important;grid-template-rows:28px 24px!important;align-items:center!important;justify-items:center!important;gap:3px!important;height:66px!important;min-height:66px!important;padding:6px 2px!important;line-height:1.1!important}
#settingsOverlay #settingsGroupNav .settings-section-icon{align-self:center!important}
#settingsOverlay #settingsGroupNav .settings-section-label{display:flex!important;align-items:center!important;justify-content:center!important;width:100%!important;height:24px!important;overflow:visible!important;text-overflow:clip!important;white-space:normal!important;text-align:center!important;line-height:1.1!important}
@media(max-width:720px){#settingsOverlay #settingsGroupNav{grid-template-columns:repeat(5,minmax(0,1fr))!important;grid-auto-rows:66px!important;gap:2px!important}#settingsOverlay #settingsGroupNav button{font-size:9px!important}}
@media(max-width:360px){#settingsOverlay #settingsGroupNav{grid-template-columns:repeat(5,minmax(0,1fr))!important}#settingsOverlay #settingsGroupNav button{font-size:8px!important}}
.corex-pc-methods{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px;margin:14px 0}
.corex-pc-methods .action{display:grid!important;grid-template-columns:32px minmax(0,1fr);grid-template-rows:auto auto;column-gap:10px;min-height:78px!important;padding:12px!important;text-align:left!important;background:var(--surface)!important;color:var(--text)!important;border:1px solid var(--line)!important}
.corex-pc-methods .action>span{grid-row:1/3;align-self:center;font-size:25px;color:var(--muted)}
.corex-pc-methods .action>b{align-self:end}.corex-pc-methods .action>small{align-self:start;color:var(--muted);font-size:11px;line-height:1.35}
.corex-pc-methods .action.on{border:2px solid var(--accent)!important;background:var(--accentSoft)!important;color:var(--accent)!important;box-shadow:0 4px 14px rgba(18,35,29,.08)!important}
.corex-pc-scan-row{display:grid;grid-template-columns:minmax(180px,.65fr) minmax(0,1fr);align-items:center;gap:12px;margin:12px 0;padding:12px;border:1px solid var(--line);border-radius:15px;background:var(--surface2)}
.corex-pc-scan-row .action{min-height:52px!important}.corex-pc-scan-row span{color:var(--muted);font-size:12px;line-height:1.45}
@media(max-width:620px){.corex-pc-methods,.corex-pc-scan-row{grid-template-columns:1fr}.corex-pc-methods .action{min-height:84px!important}}
</style>
<script id="corex-pc-correction-v913258-runtime">
(()=>{
 const card=document.getElementById('syncDevicesCard'),native=globalThis.CorexPcNative;
 if(!card)return;
 const methodButtons=[...card.querySelectorAll('[data-pc-transport]')],transport=document.getElementById('pcTransportState'),scan=document.getElementById('pcScanQr'),status=document.getElementById('pcPairingStatus');
 let selected=localStorage.getItem('corex.pc.transport')||'Wi-Fi / hotspot';
 const renderMethod=()=>{methodButtons.forEach(button=>{const on=button.dataset.pcTransport===selected;button.classList.toggle('on',on);button.setAttribute('aria-pressed',String(on))});if(transport)transport.textContent=selected};
 methodButtons.forEach(button=>button.addEventListener('click',()=>{selected=button.dataset.pcTransport||'Wi-Fi / hotspot';localStorage.setItem('corex.pc.transport',selected);renderMethod();if(status)status.textContent=selected==='USB tethering'?'Connect the USB cable, enable USB tethering on this phone, then scan the PC QR code.':'Connect both devices to the same Wi-Fi or phone hotspot, then scan the PC QR code.'}));
 scan?.addEventListener('click',()=>{if(!native?.scanQr){status.textContent='QR scanning needs the Corex Android app.';return}status.textContent='Opening the camera to scan the Companion QR code…';native.scanQr()});
 const original=globalThis.CorexPcConnection?.onPairLink;
 if(globalThis.CorexPcConnection)globalThis.CorexPcConnection.onPairLink=details=>{original?.call(globalThis.CorexPcConnection,details);if(status)status.textContent='QR scanned successfully. Confirm the connection method, then tap Connect securely.'};
 renderMethod();
})();
</script>
`;
html=html.replace('</body></html>',addition+'</body></html>');

if(!html.includes("const VERSION='9.13.258'"))throw new Error('Version update failed');
if(!html.includes('id="pcScanQr"')||!html.includes('data-pc-transport="USB tethering"'))throw new Error('Connection controls missing');
fs.writeFileSync(output,html);
