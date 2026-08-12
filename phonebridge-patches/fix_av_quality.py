from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')

# Imports.
s = s.replace('import android.media.MediaRecorder;\n',
              'import android.media.MediaRecorder;\n'
              'import android.media.audiofx.AcousticEchoCanceler;\n'
              'import android.media.audiofx.AutomaticGainControl;\n'
              'import android.media.audiofx.NoiseSuppressor;\n')
s = s.replace('import android.util.Size;\n', 'import android.util.Size;\nimport android.view.Surface;\n')

# Audio effect state.
s = s.replace('    private volatile AudioRecord audioRecord;\n    private volatile Camera camera;\n',
              '    private volatile AudioRecord audioRecord;\n'
              '    private volatile NoiseSuppressor noiseSuppressor;\n'
              '    private volatile AcousticEchoCanceler echoCanceler;\n'
              '    private volatile AutomaticGainControl automaticGainControl;\n'
              '    private volatile Camera camera;\n')
s = s.replace('    private volatile long lastFrameErrorReportMs = 0;\n',
              '    private volatile long lastFrameErrorReportMs = 0;\n'
              '    private volatile long audioPacketsSent = 0;\n'
              '    private volatile double silenceFloorRms = 180.0;\n')
s = s.replace('        videoFramesSent = 0;\n', '        videoFramesSent = 0;\n        audioPacketsSent = 0;\n', 1)

# Keep CameraX target rotation aligned with the phone display. RotationDegrees on ImageProxy
# then tells us how to transform the actual YUV buffer before JPEG encoding.
old = '''                Preview preview = new Preview.Builder().build();
                PreviewView pv = previewView;
                if (pv != null) preview.setSurfaceProvider(pv.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build();
'''
new = '''                PreviewView pv = previewView;
                int targetRotation = Surface.ROTATION_0;
                if (pv != null && pv.getDisplay() != null) targetRotation = pv.getDisplay().getRotation();

                Preview preview = new Preview.Builder()
                        .setTargetRotation(targetRotation)
                        .build();
                if (pv != null) preview.setSurfaceProvider(pv.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setTargetRotation(targetRotation)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build();
'''
if old not in s:
    raise SystemExit('Expected CameraX analysis block not found; refusing partial A/V patch')
s = s.replace(old, new)

# Use stride/offset-safe NV21 conversion and apply CameraX rotation metadata.
old = '''            byte[] nv21 = YuvTools.toNv21(image);
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(Math.max(64 * 1024, nv21.length / 4));
            yuv.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), jpegQuality, jpeg);
            byte[] data = jpeg.toByteArray();
'''
new = '''            YuvTools.Nv21Frame frame = YuvTools.toNv21(image);
            int rotation = image.getImageInfo().getRotationDegrees();
            if (rotation != 0) frame = YuvTools.rotateNv21(frame, rotation);
            if (mirror && lensFacing == CameraSelector.LENS_FACING_FRONT) frame = YuvTools.mirrorHorizontal(frame);

            YuvImage yuv = new YuvImage(frame.data, ImageFormat.NV21, frame.width, frame.height, null);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(Math.max(64 * 1024, frame.data.length / 4));
            if (!yuv.compressToJpeg(new Rect(0, 0, frame.width, frame.height), jpegQuality, jpeg)) {
                throw new IOException("JPEG encoder rejected camera frame");
            }
            byte[] data = jpeg.toByteArray();
'''
if old not in s:
    raise SystemExit('Expected processFrame NV21 block not found; refusing partial A/V patch')
s = s.replace(old, new)

# Replace raw microphone path with platform effects + conservative adaptive silence gate.
a = s.index('    private void startAudio() {')
b = s.index('    private void stopAudio() {', a)
new_audio = '''    private void startAudio() {
        if (!audioEnabled || audioRecord != null) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            notifyStatus("Microphone permission is not granted");
            return;
        }
        final int rate = 48000;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(min * 2, rate / 10 * 2);
        AudioRecord ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
            ar.release();
            notifyStatus("Microphone could not initialize");
            return;
        }
        audioRecord = ar;
        attachAudioEffects(ar.getAudioSessionId());
        ar.startRecording();
        networkExecutor.execute(() -> {
            byte[] buffer = new byte[3840];
            while (streaming.get() && audioRecord == ar) {
                int n = ar.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (n > 0 && audioEnabled && !muted && output != null) {
                    applySilenceGate(buffer, n);
                    byte[] packet = new byte[n];
                    System.arraycopy(buffer, 0, packet, 0, n);
                    sendRecord(Protocol.TYPE_AUDIO_PCM16, packet);
                    audioPacketsSent++;
                }
            }
        });
    }

    private void attachAudioEffects(int sessionId) {
        try {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId);
                if (noiseSuppressor != null) noiseSuppressor.setEnabled(true);
            }
        } catch (Exception ignored) {}
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId);
                if (echoCanceler != null) echoCanceler.setEnabled(true);
            }
        } catch (Exception ignored) {}
        try {
            if (AutomaticGainControl.isAvailable()) {
                automaticGainControl = AutomaticGainControl.create(sessionId);
                // AGC commonly pumps room noise upward during silence.
                if (automaticGainControl != null) automaticGainControl.setEnabled(false);
            }
        } catch (Exception ignored) {}
    }

    private void applySilenceGate(byte[] pcm, int length) {
        int samples = length / 2;
        if (samples <= 0) return;
        double sum = 0.0;
        int peak = 0;
        for (int i = 0; i + 1 < length; i += 2) {
            int sample = (short) ((pcm[i] & 0xff) | (pcm[i + 1] << 8));
            int a = Math.abs(sample);
            if (a > peak) peak = a;
            sum += (double) sample * sample;
        }
        double rms = Math.sqrt(sum / samples);

        // Learn the idle floor slowly, but only from quiet blocks. This keeps normal speech intact.
        if (rms < silenceFloorRms * 2.2) silenceFloorRms = silenceFloorRms * 0.97 + rms * 0.03;
        double gate = Math.max(220.0, Math.min(700.0, silenceFloorRms * 1.8));
        if (rms < gate && peak < gate * 4.0) {
            java.util.Arrays.fill(pcm, 0, length, (byte) 0);
        }
    }

'''
s = s[:a] + new_audio + s[b:]

old = '''    private void stopAudio() {
        AudioRecord ar = audioRecord;
        audioRecord = null;
        if (ar != null) {
            try { ar.stop(); } catch (Exception ignored) {}
            ar.release();
        }
    }
'''
new = '''    private void stopAudio() {
        AudioRecord ar = audioRecord;
        audioRecord = null;
        if (ar != null) {
            try { ar.stop(); } catch (Exception ignored) {}
            ar.release();
        }
        try { if (noiseSuppressor != null) noiseSuppressor.release(); } catch (Exception ignored) {}
        try { if (echoCanceler != null) echoCanceler.release(); } catch (Exception ignored) {}
        try { if (automaticGainControl != null) automaticGainControl.release(); } catch (Exception ignored) {}
        noiseSuppressor = null;
        echoCanceler = null;
        automaticGainControl = null;
    }
'''
if old not in s:
    raise SystemExit('Expected stopAudio block not found; refusing partial A/V patch')
s = s.replace(old, new)

s = s.replace('"%.1f kbps • %d video frames • %d fps cap • Q%d", mbps, videoFramesSent, maxFps, jpegQuality',
              '"%.1f kbps • %d video frames • %d audio packets • %d fps cap • Q%d", mbps, videoFramesSent, audioPacketsSent, maxFps, jpegQuality')

p.write_text(s, encoding='utf-8')
print(f'Patched PhoneBridge color/rotation/audio quality: {p}')
