from pathlib import Path
import sys

root = Path(sys.argv[1] if len(sys.argv) > 1 else "PhoneBridge")
main_path = root / "android/app/src/main/java/com/phonebridge/app/MainActivity.java"
svc_path = root / "android/app/src/main/java/com/phonebridge/app/StreamService.java"
main = main_path.read_text(encoding="utf-8-sig").replace("\r\n", "\n")
svc = svc_path.read_text(encoding="utf-8-sig").replace("\r\n", "\n")

def rep(text, old, new, label):
    if old not in text:
        raise SystemExit(f"v1.2 patch anchor missing: {label}")
    return text.replace(old, new, 1)

main = rep(main, "import android.widget.LinearLayout;\n",
           "import android.widget.LinearLayout;\nimport android.widget.ArrayAdapter;\nimport android.widget.Spinner;\n", "main imports")
main = rep(main, '    private static final String KEY_AUTO = "auto_reconnect";\n',
           '    private static final String KEY_AUTO = "auto_reconnect";\n'
           '    private static final String KEY_RELEASE_MIC = "release_mic_background";\n'
           '    private static final String KEY_AUDIO_PROFILE = "audio_profile";\n', "main prefs")
main = rep(main, "    private CheckBox autoReconnect;\n",
           "    private CheckBox autoReconnect;\n    private CheckBox releaseMicBackground;\n    private Spinner audioProfile;\n", "main fields")
main = rep(main,
'''            service.setListener(MainActivity.this);
            service.attachPreview(preview);
            bound = true;
''',
'''            service.setListener(MainActivity.this);
            service.attachPreview(preview);
            service.setUiForeground(true);
            service.setReleaseMicWhenBackground(releaseMicBackground == null || releaseMicBackground.isChecked());
            service.setAudioProfile(audioProfile == null ? 0 : audioProfile.getSelectedItemPosition());
            bound = true;
''', "service connected")
main = main.replace('TextView version = text("  v1.0", 12, false);',
                    'TextView version = text("  v1.2", 12, false);')
main = rep(main,
'''        root.addView(autoReconnect);

        LinearLayout controls = row();
''',
'''        root.addView(autoReconnect);

        releaseMicBackground = new CheckBox(this);
        releaseMicBackground.setText("Release phone microphone when I use other apps (recommended)");
        releaseMicBackground.setTextColor(Color.rgb(45, 48, 54));
        releaseMicBackground.setChecked(prefs().getBoolean(KEY_RELEASE_MIC, true));
        releaseMicBackground.setOnCheckedChangeListener((buttonView, isChecked) -> {
            prefs().edit().putBoolean(KEY_RELEASE_MIC, isChecked).apply();
            if (service != null) service.setReleaseMicWhenBackground(isChecked);
        });
        root.addView(releaseMicBackground);

        LinearLayout audioRow = row();
        TextView audioLabel = text("Phone microphone profile", 12, false);
        audioLabel.setTextColor(Color.rgb(85, 90, 100));
        audioRow.addView(audioLabel, new LinearLayout.LayoutParams(0, -2, 1f));
        audioProfile = new Spinner(this);
        String[] profiles = {"Balanced", "Meeting", "Studio"};
        ArrayAdapter<String> profileAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, profiles);
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        audioProfile.setAdapter(profileAdapter);
        audioProfile.setSelection(Math.max(0, Math.min(2, prefs().getInt(KEY_AUDIO_PROFILE, 0))));
        audioProfile.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                prefs().edit().putInt(KEY_AUDIO_PROFILE, position).apply();
                if (service != null) service.setAudioProfile(position);
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) {}
        });
        audioRow.addView(audioProfile, new LinearLayout.LayoutParams(0, -2, 1f));
        root.addView(audioRow);

        TextView coexistNote = text("Background camera stays connected. In recommended mode PhoneBridge releases the phone mic when you switch apps so chat/recorder apps can use it.", 11, false);
        coexistNote.setTextColor(Color.rgb(95, 100, 110));
        root.addView(coexistNote);

        LinearLayout controls = row();
''', "coexist UI")
main = rep(main,
'''        service.attachPreview(preview);
        ContextCompat.startForegroundService(this, new Intent(this, StreamService.class));
''',
'''        service.attachPreview(preview);
        service.setUiForeground(true);
        service.setReleaseMicWhenBackground(releaseMicBackground == null || releaseMicBackground.isChecked());
        service.setAudioProfile(audioProfile == null ? 0 : audioProfile.getSelectedItemPosition());
        ContextCompat.startForegroundService(this, new Intent(this, StreamService.class));
''', "connection options")
main = rep(main, "    @Override protected void onDestroy() {\n",
'''    @Override protected void onStart() {
        super.onStart();
        if (service != null) {
            service.setListener(this);
            service.attachPreview(preview);
            service.setReleaseMicWhenBackground(releaseMicBackground == null || releaseMicBackground.isChecked());
            service.setAudioProfile(audioProfile == null ? 0 : audioProfile.getSelectedItemPosition());
            service.setUiForeground(true);
        }
    }

    @Override protected void onStop() {
        if (service != null) {
            service.attachPreview(null);
            service.setUiForeground(false);
        }
        super.onStop();
    }

    @Override protected void onDestroy() {
''', "activity lifecycle")
main = rep(main, "        if (bound) unbindService(connection);\n",
           "        if (service != null) service.setListener(null);\n        if (bound) unbindService(connection);\n", "listener cleanup")

svc = rep(svc, "import android.media.AudioFormat;\n",
          "import android.media.AudioFormat;\nimport android.media.AudioManager;\n", "audio manager")
svc = rep(svc, '    private static final String CHANNEL = "phonebridge_stream";\n',
'''    private static final String CHANNEL = "phonebridge_stream";
    private static final String ACTION_STOP = "com.phonebridge.app.STOP";
    private static final String ACTION_TOGGLE_MIC = "com.phonebridge.app.TOGGLE_MIC";
    public static final int AUDIO_PROFILE_BALANCED = 0;
    public static final int AUDIO_PROFILE_MEETING = 1;
    public static final int AUDIO_PROFILE_STUDIO = 2;
''', "service constants")
svc = rep(svc, "    private volatile PreviewView previewView;\n",
'''    private volatile PreviewView previewView;
    private volatile boolean uiForeground = true;
    private volatile boolean releaseMicWhenBackground = true;
    private volatile int audioProfile = AUDIO_PROFILE_BALANCED;
    private volatile int lastSurfaceRotation = Surface.ROTATION_0;
    private volatile PowerManager.WakeLock wakeLock;
''', "service fields")
svc = rep(svc, "                final int rotation = UseCase.snapToSurfaceRotation(orientation);\n",
          "                final int rotation = UseCase.snapToSurfaceRotation(orientation);\n                lastSurfaceRotation = rotation;\n", "rotation state")
svc = rep(svc,
'''    @Override public IBinder onBind(@NonNull Intent intent) {
        super.onBind(intent);
        return binder;
    }

''',
'''    @Override public IBinder onBind(@NonNull Intent intent) {
        super.onBind(intent);
        return binder;
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);
        if (intent != null) {
            String action = intent.getAction();
            if (ACTION_STOP.equals(action)) {
                stopStreaming();
                return START_NOT_STICKY;
            }
            if (ACTION_TOGGLE_MIC.equals(action)) setAudioEnabled(!audioEnabled);
        }
        return START_STICKY;
    }

    @Override public void onTaskRemoved(Intent rootIntent) {
        if (streaming.get()) updateNotification(backgroundNotificationText());
        super.onTaskRemoved(rootIntent);
    }

''', "sticky service")
svc = rep(svc,
'''    public void attachPreview(PreviewView view) {
        previewView = view;
        if (streaming.get() && videoEnabled) startCamera();
    }

''',
'''    public void attachPreview(PreviewView view) {
        previewView = view;
        if (view != null && view.getDisplay() != null) lastSurfaceRotation = view.getDisplay().getRotation();
        if (streaming.get() && videoEnabled) startCamera();
    }

    public void setUiForeground(boolean foreground) {
        uiForeground = foreground;
        if (!streaming.get()) return;
        if (audioEnabled) {
            if (shouldCaptureAudio()) startAudio();
            else {
                stopAudio();
                updateNotification(backgroundNotificationText());
                notifyStatus("Background camera active • phone microphone released");
            }
        }
    }

    public void setReleaseMicWhenBackground(boolean release) {
        releaseMicWhenBackground = release;
        if (!streaming.get() || !audioEnabled) return;
        if (shouldCaptureAudio()) startAudio(); else stopAudio();
        updateNotification(backgroundNotificationText());
    }

    public void setAudioProfile(int profile) {
        int normalized = Math.max(AUDIO_PROFILE_BALANCED, Math.min(AUDIO_PROFILE_STUDIO, profile));
        if (audioProfile == normalized) return;
        audioProfile = normalized;
        if (audioRecord != null) {
            stopAudio();
            if (streaming.get() && audioEnabled && shouldCaptureAudio()) startAudio();
        }
    }

    private boolean shouldCaptureAudio() {
        return audioEnabled && (!releaseMicWhenBackground || uiForeground);
    }

''', "coexist API")
svc = rep(svc, '        startForeground(7, buildNotification("Connecting to " + host));\n',
          '        startForeground(7, buildNotification("Connecting to " + host));\n        acquireWakeLock();\n', "wake acquire")
svc = rep(svc, "        if (audioEnabled) startAudio();\n",
          "        if (shouldCaptureAudio()) startAudio();\n", "start audio guard")
svc = rep(svc, "        stopCamera();\n        output = null;\n",
          "        stopCamera();\n        releaseWakeLock();\n        output = null;\n", "wake release")
svc = rep(svc,
'''        if (!streaming.get()) return;
        if (value) startAudio(); else stopAudio();
        notifyStatus(value ? "Microphone sharing on" : "Microphone sharing off");
''',
'''        if (!streaming.get()) return;
        if (value && shouldCaptureAudio()) startAudio(); else stopAudio();
        notifyStatus(value ? (shouldCaptureAudio() ? "Microphone sharing on" : "PC microphone paused • phone microphone free") : "Microphone sharing off");
        updateNotification(backgroundNotificationText());
''', "audio toggle")
svc = svc.replace('                updateNotification("Connected to " + host);\n',
                  '                updateNotification(backgroundNotificationText());\n', 1)
svc = rep(svc,
'''                PreviewView pv = previewView;
                int targetRotation = Surface.ROTATION_0;
                if (pv != null && pv.getDisplay() != null) targetRotation = pv.getDisplay().getRotation();

                Preview preview = new Preview.Builder()
                        .setTargetRotation(targetRotation)
                        .build();
                if (pv != null) preview.setSurfaceProvider(pv.getSurfaceProvider());
''',
'''                PreviewView pv = previewView;
                int targetRotation = lastSurfaceRotation;
                if (pv != null && pv.getDisplay() != null) {
                    targetRotation = pv.getDisplay().getRotation();
                    lastSurfaceRotation = targetRotation;
                }

                Preview preview = null;
                if (pv != null) {
                    preview = new Preview.Builder()
                            .setTargetRotation(targetRotation)
                            .build();
                    preview.setSurfaceProvider(pv.getSurfaceProvider());
                }
''', "headless preview")
svc = rep(svc,
'''                CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
                camera = provider.bindToLifecycle(this, selector, preview, analysis);
                notifyStatus("Camera active • streaming video");
''',
'''                CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
                camera = preview != null
                        ? provider.bindToLifecycle(this, selector, preview, analysis)
                        : provider.bindToLifecycle(this, selector, analysis);
                notifyStatus(preview != null ? "Camera active • streaming video" : "Camera active in background");
''', "analysis only")

a0 = svc.index("    private void startAudio() {")
a1 = svc.index("    private void attachAudioEffects", a0)
svc = svc[:a0] + '''    private void startAudio() {
        if (!shouldCaptureAudio() || audioRecord != null) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            notifyStatus("Microphone permission is not granted");
            return;
        }
        final int rate = 48000;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(min * 2, rate / 10 * 2);

        int source = MediaRecorder.AudioSource.MIC;
        if (audioProfile == AUDIO_PROFILE_MEETING) source = MediaRecorder.AudioSource.VOICE_COMMUNICATION;
        else if (audioProfile == AUDIO_PROFILE_STUDIO) {
            try {
                AudioManager am = getSystemService(AudioManager.class);
                String supported = am == null ? null : am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED);
                if ("true".equalsIgnoreCase(supported)) source = MediaRecorder.AudioSource.UNPROCESSED;
            } catch (Exception ignored) {}
        }

        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(rate)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build();
        AudioRecord.Builder builder = new AudioRecord.Builder()
                .setAudioSource(source)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize);
        if (Build.VERSION.SDK_INT >= 30) builder.setPrivacySensitive(false);

        AudioRecord ar;
        try { ar = builder.build(); }
        catch (Exception e) {
            notifyStatus("Microphone could not initialize: " + e.getClass().getSimpleName());
            return;
        }
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            ar.release();
            notifyStatus("Microphone could not initialize");
            return;
        }
        audioRecord = ar;
        attachAudioEffects(ar.getAudioSessionId());
        ar.startRecording();
        updateNotification(backgroundNotificationText());
        networkExecutor.execute(() -> {
            byte[] buffer = new byte[3840];
            while (streaming.get() && audioRecord == ar) {
                int n = ar.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (n > 0 && audioEnabled && !muted && output != null) {
                    if (audioProfile != AUDIO_PROFILE_STUDIO) applySilenceGate(buffer, n);
                    byte[] packet = new byte[n];
                    System.arraycopy(buffer, 0, packet, 0, n);
                    sendRecord(Protocol.TYPE_AUDIO_PCM16, packet);
                    audioPacketsSent++;
                }
            }
        });
    }

''' + svc[a1:]
svc = rep(svc, "    private void attachAudioEffects(int sessionId) {\n",
          "    private void attachAudioEffects(int sessionId) {\n        if (audioProfile == AUDIO_PROFILE_STUDIO) return;\n", "studio effects")
svc = rep(svc,
'''        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId);
                if (echoCanceler != null) echoCanceler.setEnabled(true);
            }
        } catch (Exception ignored) {}
''',
'''        if (audioProfile == AUDIO_PROFILE_MEETING) try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId);
                if (echoCanceler != null) echoCanceler.setEnabled(true);
            }
        } catch (Exception ignored) {}
''', "meeting AEC")
svc = rep(svc, "    private void createChannel() {\n",
'''    private String backgroundNotificationText() {
        if (!streaming.get()) return "Ready";
        if (!audioEnabled) return "Camera streaming • PC microphone off";
        if (!shouldCaptureAudio()) return "Camera streaming • phone microphone free";
        return "Camera + microphone streaming";
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        try {
            PowerManager pm = getSystemService(PowerManager.class);
            if (pm != null) {
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PhoneBridge:Streaming");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
            }
        } catch (Exception ignored) {}
    }

    private void releaseWakeLock() {
        PowerManager.WakeLock wl = wakeLock;
        wakeLock = null;
        if (wl != null && wl.isHeld()) try { wl.release(); } catch (Exception ignored) {}
    }

    private void createChannel() {
''', "background helpers")
svc = rep(svc,
'''        PendingIntent pi = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("PhoneBridge")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
''',
'''        PendingIntent pi = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent micIntent = new Intent(this, StreamService.class).setAction(ACTION_TOGGLE_MIC);
        PendingIntent micPi = PendingIntent.getService(this, 1, micIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Intent stopIntent = new Intent(this, StreamService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 2, stopIntent, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("PhoneBridge")
                .setContentText(text)
                .setContentIntent(pi)
                .addAction(0, audioEnabled ? "PC mic off" : "PC mic on", micPi)
                .addAction(0, "Disconnect", stopPi)
                .setOngoing(true)
                .build();
''', "notification actions")

combined = main + "\n" + svc
for marker in ["OUTPUT_IMAGE_FORMAT_NV21","fromCameraXStandardNv21","OrientationEventListener",
               "ContextCompat.getMainExecutor(this)","START_STICKY","setPrivacySensitive(false)",
               "bindToLifecycle(this, selector, analysis)","release_mic_background"]:
    if marker not in combined:
        raise SystemExit(f"v1.2 required marker missing: {marker}")
if "OUTPUT_IMAGE_FORMAT_RGBA_8888" in svc:
    raise SystemExit("Regression: RGBA CameraX path returned")

main_path.write_text(main, encoding="utf-8", newline="\n")
svc_path.write_text(svc, encoding="utf-8", newline="\n")
print("Applied PhoneBridge v1.2 Android background/coexistence patch")
