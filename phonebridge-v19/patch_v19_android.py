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
main=rm(main,'import androidx.core.content.ContextCompat;\n','''import androidx.core.content.ContextCompat;\n\nimport com.journeyapps.barcodescanner.ScanContract;\nimport com.journeyapps.barcodescanner.ScanOptions;\n''','zxing imports')
main=rm(main,'    private boolean torch;\n    private int zoomProgress;\n','''    private boolean torch;\n    private int zoomProgress;\n    private ActivityResultLauncher<ScanOptions> qrScanner;\n    private String pendingQrToken = "";\n''','qr fields')
main=rm(main,'        getWindow().setStatusBarColor(Color.rgb(247, 248, 250));\n', '''        qrScanner = registerForActivityResult(new ScanContract(), result -> {\n            if (result != null && result.getContents() != null) handlePairingQr(result.getContents());\n        });\n        getWindow().setStatusBarColor(Color.rgb(247, 248, 250));\n''','qr launcher')
main=rm(main,'''        Button discover = button("Find PC");\n        discover.setOnClickListener(v -> discoverPc());\n        connectionCard.addView(discover, new LinearLayout.LayoutParams(-1, dp(46)));\n''','''        LinearLayout pairRow = row();\n        Button discover = button("Find PC");\n        discover.setOnClickListener(v -> discoverPc());\n        pairRow.addView(discover, weight());\n        Button scanQr = button("Scan pairing QR");\n        scanQr.setOnClickListener(v -> scanPairingQr());\n        pairRow.addView(scanQr, weight());\n        connectionCard.addView(pairRow, new LinearLayout.LayoutParams(-1, dp(48)));\n''','scan button')

anchor='''    private void maybeAutoConnect() {\n'''
methods='''    private void scanPairingQr() {\n        if (service != null && service.isStreaming()) {\n            status.setText("Disconnect PhoneBridge before scanning a new PC QR");\n            return;\n        }\n        ScanOptions options = new ScanOptions();\n        options.setDesiredBarcodeFormats(ScanOptions.QR_CODE);\n        options.setPrompt("Scan the PhoneBridge pairing QR shown on the PC");\n        options.setBeepEnabled(false);\n        options.setOrientationLocked(false);\n        qrScanner.launch(options);\n    }\n\n    private void handlePairingQr(String contents) {\n        try {\n            Uri uri = Uri.parse(contents == null ? "" : contents.trim());\n            if (!"phonebridge".equalsIgnoreCase(uri.getScheme()) || !"pair".equalsIgnoreCase(uri.getHost())) {\n                status.setText("This is not a PhoneBridge pairing QR");\n                return;\n            }\n            String h = uri.getQueryParameter("host");\n            String token = uri.getQueryParameter("token");\n            String port = uri.getQueryParameter("port");\n            if (h == null || h.trim().isEmpty() || token == null || token.length() < 24 || token.length() > 128 || (port != null && !"8989".equals(port))) {\n                status.setText("PhoneBridge pairing QR is invalid or incomplete");\n                return;\n            }\n            pendingQrToken = token;\n            host.setText(h.trim());\n            prefs().edit().putString(KEY_HOST, h.trim()).apply();\n            refreshAddressSummary();\n            status.setText("QR scanned • connecting securely…");\n            if (service != null && !service.isStreaming() && hasRequiredPermissions()) {\n                startConnection(h.trim(), pin.getText().toString().trim(), false);\n            } else {\n                status.setText("QR scanned • tap Connect when PhoneBridge is ready");\n            }\n        } catch (Exception e) {\n            status.setText("Could not read the PhoneBridge pairing QR");\n        }\n    }\n\n'''+anchor
if anchor not in main: raise SystemExit('maybeAutoConnect anchor')
main=main.replace(anchor,methods,1)

main=rm(main,'''        if (p.length() != 6) {\n            status.setText("Set the six-digit PIN in Settings");\n            showSettings(true);\n            return;\n        }\n        saveTrustedPc();\n        startConnection(h, p, false);\n''','''        if (p.length() != 6 && pendingQrToken.isEmpty()) {\n            status.setText("Set the six-digit PIN or scan the PC pairing QR in Settings");\n            showSettings(true);\n            return;\n        }\n        if (p.length() == 6) saveTrustedPc(); else prefs().edit().putString(KEY_HOST, h).apply();\n        startConnection(h, p, false);\n''','toggle qr')
main=rm(main,'        service.startStreaming(h, 8989, p);\n','''        String qrToken = pendingQrToken;\n        pendingQrToken = "";\n        service.startStreaming(h, 8989, p, qrToken);\n''','start with token')
main=rm(main,'''            if (value != null && value.startsWith("Connected")) {\n                saveTrustedPc();\n                applyRememberedLiveControls();\n            }\n''','''            if (value != null && value.startsWith("Connected")) {\n                if (pin.getText().toString().trim().length() == 6) saveTrustedPc();\n                applyRememberedLiveControls();\n            }\n            if (value != null && value.startsWith("QR pairing complete")) {\n                String trusted = prefs().getString(KEY_PIN, "");\n                if (trusted.length() == 6 && !trusted.equals(pin.getText().toString())) pin.setText(trusted);\n                pendingQrToken = "";\n            }\n''','qr trusted ui')
main=main.replace('PhoneBridge v1.8 • UI & saved settings','PhoneBridge v1.9 • QR pairing + saved settings')

svc=rm(svc,'    private volatile AudioRecord audioRecord;\n','''    private volatile AudioRecord audioRecord;\n    private volatile String currentPairPin = "";\n    private volatile String oneTimePairToken = "";\n    private volatile String currentPairHost = "";\n''','service pairing fields')
old='''    public void startStreaming(String host, int port, String pin) {\n        if (!streaming.compareAndSet(false, true)) return;\n        startForeground(7, buildNotification("Connecting to " + host));\n        acquireWakeLock();\n        startedAt = System.currentTimeMillis();\n        bytesSent = 0;\n        videoFramesSent = 0;\n        audioPacketsSent = 0;\n        notifyStatus("Connecting…");\n        networkExecutor.execute(() -> connectLoop(host, port, pin));\n        if (videoEnabled) startCamera();\n        if (shouldCaptureAudio()) startAudio();\n    }\n'''
new='''    public void startStreaming(String host, int port, String pin) {\n        startStreaming(host, port, pin, "");\n    }\n\n    public void startStreaming(String host, int port, String pin, String pairToken) {\n        if (!streaming.compareAndSet(false, true)) return;\n        currentPairHost = host == null ? "" : host;\n        currentPairPin = pin == null ? "" : pin;\n        oneTimePairToken = pairToken == null ? "" : pairToken;\n        startForeground(7, buildNotification("Connecting to " + host));\n        acquireWakeLock();\n        startedAt = System.currentTimeMillis();\n        bytesSent = 0;\n        videoFramesSent = 0;\n        audioPacketsSent = 0;\n        notifyStatus("Connecting…");\n        networkExecutor.execute(() -> connectLoop(host, port));\n        if (videoEnabled) startCamera();\n        if (shouldCaptureAudio()) startAudio();\n    }\n'''
svc=rm(svc,old,new,'startStreaming overload')
svc=rm(svc,'    private void connectLoop(String host, int port, String pin) {\n','    private void connectLoop(String host, int port) {\n','connect loop signature')
svc=rm(svc,'                Protocol.writeJson(out, Protocol.TYPE_PAIR, "{\\\"pin\\\":\\\"" + escape(pin) + "\\\"}");\n', '''                String token = oneTimePairToken;\n                if (token != null && !token.isEmpty()) {\n                    Protocol.writeJson(out, Protocol.TYPE_PAIR, "{\\\"token\\\":\\\"" + escape(token) + "\\\"}");\n                } else {\n                    Protocol.writeJson(out, Protocol.TYPE_PAIR, "{\\\"pin\\\":\\\"" + escape(currentPairPin) + "\\\"}");\n                }\n''','pair json')

anchor2='''    private void handleControl(String json) {\n'''
helper='''    private String jsonString(String json, String key) {\n        try {\n            String token = "\\\"" + key + "\\\":\\\"";\n            int p = json.indexOf(token); if (p < 0) return ""; p += token.length();\n            int e = json.indexOf('"', p); if (e < 0) return "";\n            return json.substring(p, e).replace("\\\\\\\"", "\\\"").replace("\\\\\\\\", "\\\\");\n        } catch (Exception ignored) { return ""; }\n    }\n\n'''+anchor2
if anchor2 not in svc: raise SystemExit('handleControl anchor')
svc=svc.replace(anchor2,helper,1)
svc=rm(svc,'''    private void handleControl(String json) {\n        if (json.contains("\\\"cmd\\\":\\\"streamConfig\\\"")) {\n''','''    private void handleControl(String json) {\n        if (json.contains("\\\"cmd\\\":\\\"qrTrusted\\\"")) {\n            String trusted = jsonString(json, "pin");\n            if (trusted.matches("\\\\d{6}")) {\n                currentPairPin = trusted;\n                oneTimePairToken = "";\n                getSharedPreferences("phonebridge_v1", MODE_PRIVATE).edit()\n                        .putString("trusted_host", currentPairHost)\n                        .putString("trusted_pin", trusted)\n                        .apply();\n                notifyStatus("QR pairing complete • trusted PC saved");\n            }\n        }\n        if (json.contains("\\\"cmd\\\":\\\"streamConfig\\\"")) {\n''','qr trusted control')
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
