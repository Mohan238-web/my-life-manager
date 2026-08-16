from pathlib import Path
import sys
root=Path(sys.argv[1])
mainp=root/'android/app/src/main/java/com/phonebridge/app/MainActivity.java'
svcp=root/'android/app/src/main/java/com/phonebridge/app/StreamService.java'
gradlep=root/'android/app/build.gradle.kts'
main=mainp.read_text(encoding='utf-8-sig').replace('\r\n','\n')
svc=svcp.read_text(encoding='utf-8-sig').replace('\r\n','\n')
gradle=gradlep.read_text(encoding='utf-8-sig').replace('\r\n','\n')

def rm(text,old,new,label):
    if old not in text: raise SystemExit('v1.9 android anchor missing: '+label)
    return text.replace(old,new,1)

main=rm(main,'import android.os.IBinder;\n','import android.os.IBinder;\nimport android.net.Uri;\n','uri import')
main=rm(main,'import androidx.activity.ComponentActivity;\n','import androidx.activity.ComponentActivity;\nimport androidx.activity.result.ActivityResultLauncher;\n','activity result import')
main=rm(main,'import androidx.core.content.ContextCompat;\n','''import androidx.core.content.ContextCompat;

import com.journeyapps.barcodescanner.ScanContract;
import com.journeyapps.barcodescanner.ScanOptions;
''','zxing imports')
main=rm(main,'    private boolean torch;\n    private int zoomProgress;\n','''    private boolean torch;
    private int zoomProgress;
    private ActivityResultLauncher<ScanOptions> qrScanner;
    private String pendingQrToken = "";
''','qr fields')
main=rm(main,'        getWindow().setStatusBarColor(Color.rgb(247, 248, 250));\n', '''        qrScanner = registerForActivityResult(new ScanContract(), result -> {
            if (result != null && result.getContents() != null) handlePairingQr(result.getContents());
        });
        getWindow().setStatusBarColor(Color.rgb(247, 248, 250));
''','qr launcher')
main=rm(main,'''        Button discover = button("Find PC");
        discover.setOnClickListener(v -> discoverPc());
        connectionCard.addView(discover, new LinearLayout.LayoutParams(-1, dp(46)));
''','''        LinearLayout pairRow = row();
        Button discover = button("Find PC");
        discover.setOnClickListener(v -> discoverPc());
        pairRow.addView(discover, weight());
        Button scanQr = button("Scan pairing QR");
        scanQr.setOnClickListener(v -> scanPairingQr());
        pairRow.addView(scanQr, weight());
        connectionCard.addView(pairRow, new LinearLayout.LayoutParams(-1, dp(48)));
''','scan button')

anchor='''    private void maybeAutoConnect() {
'''
methods='''    private void scanPairingQr() {
        if (service != null && service.isStreaming()) {
            status.setText("Disconnect PhoneBridge before scanning a new PC QR");
            return;
        }
        ScanOptions options = new ScanOptions();
        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);
        options.setPrompt("Scan the PhoneBridge pairing QR shown on the PC");
        options.setBeepEnabled(false);
        options.setOrientationLocked(false);
        qrScanner.launch(options);
    }

    private void handlePairingQr(String contents) {
        try {
            Uri uri = Uri.parse(contents == null ? "" : contents.trim());
            if (!"phonebridge".equalsIgnoreCase(uri.getScheme()) || !"pair".equalsIgnoreCase(uri.getHost())) {
                status.setText("This is not a PhoneBridge pairing QR");
                return;
            }
            String h = uri.getQueryParameter("host");
            String token = uri.getQueryParameter("token");
            String port = uri.getQueryParameter("port");
            if (h == null || h.trim().isEmpty() || token == null || token.length() < 24 || token.length() > 128 || (port != null && !"8989".equals(port))) {
                status.setText("PhoneBridge pairing QR is invalid or incomplete");
                return;
            }
            pendingQrToken = token;
            host.setText(h.trim());
            prefs().edit().putString(KEY_HOST, h.trim()).apply();
            refreshAddressSummary();
            status.setText("QR scanned • connecting securely…");
            if (service != null && !service.isStreaming() && hasRequiredPermissions()) {
                startConnection(h.trim(), pin.getText().toString().trim(), false);
            } else {
                status.setText("QR scanned • tap Connect when PhoneBridge is ready");
            }
        } catch (Exception e) {
            status.setText("Could not read the PhoneBridge pairing QR");
        }
    }

'''+anchor
if anchor not in main: raise SystemExit('maybeAutoConnect anchor')
main=main.replace(anchor,methods,1)

main=rm(main,'        if (p.length() != 6) { status.setText("Set the six-digit PIN in Settings"); showSettings(true); return; }\n        saveTrustedPc(); startConnection(h, p, false);\n','''        if (p.length() != 6 && pendingQrToken.isEmpty()) { status.setText("Set the six-digit PIN or scan the PC pairing QR in Settings"); showSettings(true); return; }
        if (p.length() == 6) saveTrustedPc(); else prefs().edit().putString(KEY_HOST, h).apply();
        startConnection(h, p, false);
''','toggle qr')
main=rm(main,'        service.startStreaming(h, 8989, p); refreshButtons();\n','''        String qrToken = pendingQrToken;
        pendingQrToken = "";
        service.startStreaming(h, 8989, p, qrToken); refreshButtons();
''','start with token')
main=rm(main,'            if (value != null && value.startsWith("Connected")) { saveTrustedPc(); applyRememberedLiveControls(); }\n','''            if (value != null && value.startsWith("Connected")) {
                if (pin.getText().toString().trim().length() == 6) saveTrustedPc();
                applyRememberedLiveControls();
            }
            if (value != null && value.startsWith("QR pairing complete")) {
                String trusted = prefs().getString(KEY_PIN, "");
                if (trusted.length() == 6 && !trusted.equals(pin.getText().toString())) pin.setText(trusted);
                pendingQrToken = "";
            }
''','qr trusted ui')
main=main.replace('PhoneBridge v1.8 • UI & saved settings','PhoneBridge v1.9 • QR pairing + saved settings')

svc=rm(svc,'    private volatile AudioRecord audioRecord;\n','''    private volatile AudioRecord audioRecord;
    private volatile String currentPairPin = "";
    private volatile String oneTimePairToken = "";
    private volatile String currentPairHost = "";
''','service pairing fields')
old='''    public void startStreaming(String host, int port, String pin) {
        if (!streaming.compareAndSet(false, true)) return;
        startForeground(7, buildNotification("Connecting to " + host));
        acquireWakeLock();
        startedAt = System.currentTimeMillis();
        bytesSent = 0;
        videoFramesSent = 0;
        audioPacketsSent = 0;
        notifyStatus("Connecting…");
        networkExecutor.execute(() -> connectLoop(host, port, pin));
        if (videoEnabled) startCamera();
        if (shouldCaptureAudio()) startAudio();
    }
'''
new='''    public void startStreaming(String host, int port, String pin) {
        startStreaming(host, port, pin, "");
    }

    public void startStreaming(String host, int port, String pin, String pairToken) {
        if (!streaming.compareAndSet(false, true)) return;
        currentPairHost = host == null ? "" : host;
        currentPairPin = pin == null ? "" : pin;
        oneTimePairToken = pairToken == null ? "" : pairToken;
        startForeground(7, buildNotification("Connecting to " + host));
        acquireWakeLock();
        startedAt = System.currentTimeMillis();
        bytesSent = 0;
        videoFramesSent = 0;
        audioPacketsSent = 0;
        notifyStatus("Connecting…");
        networkExecutor.execute(() -> connectLoop(host, port));
        if (videoEnabled) startCamera();
        if (shouldCaptureAudio()) startAudio();
    }
'''
svc=rm(svc,old,new,'startStreaming overload')
svc=rm(svc,'    private void connectLoop(String host, int port, String pin) {\n','    private void connectLoop(String host, int port) {\n','connect loop signature')
svc=rm(svc,'                Protocol.writeJson(out, Protocol.TYPE_PAIR, "{\\\"pin\\\":\\\"" + escape(pin) + "\\\"}");\n', '''                String token = oneTimePairToken;
                if (token != null && !token.isEmpty()) {
                    Protocol.writeJson(out, Protocol.TYPE_PAIR, "{\\\"token\\\":\\\"" + escape(token) + "\\\"}");
                } else {
                    Protocol.writeJson(out, Protocol.TYPE_PAIR, "{\\\"pin\\\":\\\"" + escape(currentPairPin) + "\\\"}");
                }
''','pair json')

anchor2='''    private void handleControl(String json) {
'''
helper='''    private String jsonString(String json, String key) {
        try {
            String token = "\\\"" + key + "\\\":\\\"";
            int p = json.indexOf(token); if (p < 0) return ""; p += token.length();
            int e = json.indexOf('"', p); if (e < 0) return "";
            return json.substring(p, e).replace("\\\\\\\"", "\\\"").replace("\\\\\\\\", "\\\\");
        } catch (Exception ignored) { return ""; }
    }

'''+anchor2
if anchor2 not in svc: raise SystemExit('handleControl anchor')
svc=svc.replace(anchor2,helper,1)
svc=rm(svc,'''    private void handleControl(String json) {
        if (json.contains("\\\"cmd\\\":\\\"streamConfig\\\"")) {
''','''    private void handleControl(String json) {
        if (json.contains("\\\"cmd\\\":\\\"qrTrusted\\\"")) {
            String trusted = jsonString(json, "pin");
            if (trusted.matches("\\\\d{6}")) {
                currentPairPin = trusted;
                oneTimePairToken = "";
                getSharedPreferences("phonebridge_v1", MODE_PRIVATE).edit()
                        .putString("trusted_host", currentPairHost)
                        .putString("trusted_pin", trusted)
                        .apply();
                notifyStatus("QR pairing complete • trusted PC saved");
            }
        }
        if (json.contains("\\\"cmd\\\":\\\"streamConfig\\\"")) {
''','qr trusted control')
svc=rm(svc,'        streaming.set(false);\n','        streaming.set(false);\n        oneTimePairToken = "";\n','clear token stop')

if 'com.journeyapps:zxing-android-embedded' not in gradle:
    idx=gradle.find('dependencies {')
    if idx<0: raise SystemExit('Gradle dependencies block missing')
    pos=idx+len('dependencies {')
    gradle=gradle[:pos]+'\n    implementation("com.journeyapps:zxing-android-embedded:4.3.0")'+gradle[pos:]

for marker in ['Scan pairing QR','startStreaming(h, 8989, p, qrToken)','qrTrusted','oneTimePairToken','zxing-android-embedded']:
    text=main+'\n'+svc+'\n'+gradle
    if marker not in text: raise SystemExit('v1.9 required marker missing: '+marker)
mainp.write_text(main,encoding='utf-8',newline='\n')
svcp.write_text(svc,encoding='utf-8',newline='\n')
gradlep.write_text(gradle,encoding='utf-8',newline='\n')
print('Applied PhoneBridge v1.9 Android one-time QR pairing; camera/audio media pipeline untouched')
