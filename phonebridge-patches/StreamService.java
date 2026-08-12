package com.phonebridge.app;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.YuvImage;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Binder;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.camera.core.Camera;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.Preview;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;

import com.google.common.util.concurrent.ListenableFuture;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public class StreamService extends LifecycleService {
    public interface Listener {
        void onStatus(String status);
        void onStats(String text);
    }

    public final class LocalBinder extends Binder {
        public StreamService getService() { return StreamService.this; }
    }

    private static final String CHANNEL = "phonebridge_stream";
    private final IBinder binder = new LocalBinder();
    private final ExecutorService cameraExecutor = Executors.newSingleThreadExecutor();
    private final ExecutorService networkExecutor = Executors.newCachedThreadPool();
    private final AtomicBoolean streaming = new AtomicBoolean(false);
    private final Object outputLock = new Object();

    private volatile Listener listener;
    private volatile PreviewView previewView;
    private volatile DataOutputStream output;
    private volatile Socket socket;
    private volatile AudioRecord audioRecord;
    private volatile Camera camera;
    private volatile int lensFacing = CameraSelector.LENS_FACING_BACK;
    private volatile int jpegQuality = 78;
    private volatile int maxFps = 20;
    private volatile boolean muted = false;
    private volatile boolean videoEnabled = true;
    private volatile boolean audioEnabled = true;
    private volatile boolean mirror = false;
    private volatile long lastFrameNs = 0;
    private volatile long bytesSent = 0;
    private volatile long startedAt = 0;

    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }

    @Override public IBinder onBind(@NonNull Intent intent) {
        super.onBind(intent);
        return binder;
    }

    public void setListener(Listener listener) { this.listener = listener; }

    public void attachPreview(PreviewView view) {
        previewView = view;
        if (streaming.get()) restartCamera();
    }

    public void startStreaming(String host, int port, String pin) {
        if (!streaming.compareAndSet(false, true)) return;
        startForeground(7, buildNotification("Connecting to " + host));
        startedAt = System.currentTimeMillis();
        bytesSent = 0;
        notifyStatus("Connecting…");
        networkExecutor.execute(() -> connectLoop(host, port, pin));
        if (videoEnabled) startCamera();
        if (audioEnabled) startAudio();
    }

    public void stopStreaming() {
        streaming.set(false);
        try { if (socket != null) socket.close(); } catch (IOException ignored) {}
        stopAudio();
        stopCamera();
        output = null;
        notifyStatus("Stopped");
        stopForeground(STOP_FOREGROUND_REMOVE);
        stopSelf();
    }

    public boolean isStreaming() { return streaming.get(); }
    public void setMuted(boolean value) { muted = value; }
    public boolean isMuted() { return muted; }
    public boolean isVideoEnabled() { return videoEnabled; }
    public boolean isAudioEnabled() { return audioEnabled; }

    public void setVideoEnabled(boolean value) {
        if (videoEnabled == value) return;
        videoEnabled = value;
        if (!streaming.get()) return;
        if (value) startCamera(); else stopCamera();
        notifyStatus(value ? "Camera sharing on" : "Camera sharing off");
    }

    public void setAudioEnabled(boolean value) {
        if (audioEnabled == value) return;
        audioEnabled = value;
        if (!streaming.get()) return;
        if (value) startAudio(); else stopAudio();
        notifyStatus(value ? "Microphone sharing on" : "Microphone sharing off");
    }
    public boolean isMirrored() { return mirror; }
    public void setMirrored(boolean value) { mirror = value; }
    public void setJpegQuality(int quality) { jpegQuality = Math.max(40, Math.min(95, quality)); }
    public void setMaxFps(int fps) { maxFps = Math.max(5, Math.min(30, fps)); }

    public void switchCamera() {
        lensFacing = lensFacing == CameraSelector.LENS_FACING_BACK
                ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        if (streaming.get()) restartCamera();
    }

    public void setTorch(boolean enabled) {
        Camera c = camera;
        if (c != null && c.getCameraInfo().hasFlashUnit()) c.getCameraControl().enableTorch(enabled);
    }

    public void setZoom(float linearZoom) {
        Camera c = camera;
        if (c != null) c.getCameraControl().setLinearZoom(Math.max(0f, Math.min(1f, linearZoom)));
    }

    private void connectLoop(String host, int port, String pin) {
        int attempt = 0;
        while (streaming.get()) {
            try {
                Socket s = new Socket();
                s.setTcpNoDelay(true);
                s.setKeepAlive(true);
                s.connect(new InetSocketAddress(host, port), 4000);
                socket = s;
                DataOutputStream out = new DataOutputStream(s.getOutputStream());
                output = out;
                Protocol.writeJson(out, Protocol.TYPE_HELLO,
                        "{\"protocol\":1,\"device\":\"" + escape(Build.MODEL) +
                                "\",\"app\":\"PhoneBridge\",\"video\":\"jpeg\",\"audio\":\"pcm16\",\"audioRate\":48000,\"audioChannels\":1}");
                Protocol.writeJson(out, Protocol.TYPE_PAIR, "{\"pin\":\"" + escape(pin) + "\"}");
                notifyStatus("Connected");
                updateNotification("Connected to " + host);
                readControlLoop(new DataInputStream(s.getInputStream()));
            } catch (Exception e) {
                if (!streaming.get()) break;
                attempt++;
                notifyStatus("Reconnecting (" + attempt + ")…");
                output = null;
                try { Thread.sleep(Math.min(5000, 700L * attempt)); } catch (InterruptedException ignored) {}
            }
        }
    }

    private void readControlLoop(DataInputStream in) throws IOException {
        byte[] magic = new byte[4];
        while (streaming.get()) {
            in.readFully(magic);
            if (magic[0] != 'P' || magic[1] != 'B' || magic[2] != 'R' || magic[3] != '1') throw new IOException("Bad magic");
            int type = in.readUnsignedByte();
            in.readUnsignedByte();
            in.readUnsignedShort();
            int len = in.readInt();
            if (len < 0 || len > 1024 * 1024) throw new IOException("Invalid control size");
            byte[] payload = new byte[len];
            in.readFully(payload);
            if (type == Protocol.TYPE_PING) sendRecord(Protocol.TYPE_PONG, payload);
            else if (type == Protocol.TYPE_CONTROL) handleControl(new String(payload, StandardCharsets.UTF_8));
        }
    }

    private void handleControl(String json) {
        if (json.contains("\"cmd\":\"video\",\"value\":true") || json.contains("\"video\":true")) setVideoEnabled(true);
        if (json.contains("\"cmd\":\"video\",\"value\":false") || json.contains("\"video\":false")) setVideoEnabled(false);
        if (json.contains("\"cmd\":\"audio\",\"value\":true") || json.contains("\"audio\":true")) setAudioEnabled(true);
        if (json.contains("\"cmd\":\"audio\",\"value\":false") || json.contains("\"audio\":false")) setAudioEnabled(false);
        if (json.contains("\"camera\"") || json.contains("\"cmd\":\"camera\"")) switchCamera();
        if (json.contains("\"mute\":true") || json.contains("\"cmd\":\"mute\",\"value\":true")) muted = true;
        if (json.contains("\"mute\":false") || json.contains("\"cmd\":\"mute\",\"value\":false")) muted = false;
        if (json.contains("\"torch\":true") || json.contains("\"cmd\":\"torch\",\"value\":true")) setTorch(true);
        if (json.contains("\"torch\":false") || json.contains("\"cmd\":\"torch\",\"value\":false")) setTorch(false);
        if (json.contains("\"cmd\":\"zoom\"")) {
            try {
                int p = json.indexOf("\"value\":");
                if (p >= 0) {
                    p += 8;
                    int e = p;
                    while (e < json.length() && "0123456789.-".indexOf(json.charAt(e)) >= 0) e++;
                    setZoom(Float.parseFloat(json.substring(p, e)));
                }
            } catch (Exception ignored) {}
        }
    }

    private void startCamera() {
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                ProcessCameraProvider provider = future.get();
                provider.unbindAll();
                Preview preview = new Preview.Builder().build();
                PreviewView pv = previewView;
                if (pv != null) preview.setSurfaceProvider(pv.getSurfaceProvider());

                ImageAnalysis analysis = new ImageAnalysis.Builder()
                        .setTargetResolution(new Size(1280, 720))
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                        .build();
                analysis.setAnalyzer(cameraExecutor, this::processFrame);

                CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
                camera = provider.bindToLifecycle(this, selector, preview, analysis);
            } catch (Exception e) {
                notifyStatus("Camera error: " + e.getMessage());
            }
        }, cameraExecutor);
    }

    private void restartCamera() {
        stopCamera();
        startCamera();
    }

    private void stopCamera() {
        try {
            ProcessCameraProvider.getInstance(this).get(2, TimeUnit.SECONDS).unbindAll();
        } catch (Exception ignored) {}
        camera = null;
    }

    private void processFrame(ImageProxy image) {
        try {
            if (!streaming.get() || !videoEnabled || output == null) return;
            long now = System.nanoTime();
            long interval = 1_000_000_000L / Math.max(1, maxFps);
            if (now - lastFrameNs < interval) return;
            lastFrameNs = now;

            byte[] nv21 = YuvTools.toNv21(image);
            YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, image.getWidth(), image.getHeight(), null);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(Math.max(64 * 1024, nv21.length / 4));
            yuv.compressToJpeg(new Rect(0, 0, image.getWidth(), image.getHeight()), jpegQuality, jpeg);
            byte[] data = jpeg.toByteArray();
            sendRecord(Protocol.TYPE_VIDEO_JPEG, data);
            maybeStats();
        } catch (Exception ignored) {
        } finally {
            image.close();
        }
    }

    private void startAudio() {
        if (!audioEnabled || audioRecord != null) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return;
        final int rate = 48000;
        int min = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(min * 2, rate / 10 * 2);
        audioRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize);
        audioRecord.startRecording();
        networkExecutor.execute(() -> {
            byte[] buffer = new byte[3840];
            while (streaming.get() && audioRecord != null) {
                int n = audioRecord.read(buffer, 0, buffer.length, AudioRecord.READ_BLOCKING);
                if (n > 0 && audioEnabled && !muted && output != null) {
                    byte[] packet = new byte[n];
                    System.arraycopy(buffer, 0, packet, 0, n);
                    sendRecord(Protocol.TYPE_AUDIO_PCM16, packet);
                }
            }
        });
    }

    private void stopAudio() {
        AudioRecord ar = audioRecord;
        audioRecord = null;
        if (ar != null) {
            try { ar.stop(); } catch (Exception ignored) {}
            ar.release();
        }
    }

    private void sendRecord(int type, byte[] payload) {
        DataOutputStream out = output;
        if (out == null) return;
        try {
            synchronized (outputLock) { Protocol.writeRecord(out, type, payload); }
            bytesSent += payload.length + 12L;
        } catch (IOException e) {
            try { if (socket != null) socket.close(); } catch (IOException ignored) {}
            output = null;
        }
    }

    private void maybeStats() {
        long elapsed = Math.max(1, System.currentTimeMillis() - startedAt);
        if ((System.currentTimeMillis() / 1000) % 2 != 0) return;
        double mbps = (bytesSent * 8.0 / elapsed) / 1000.0;
        Listener l = listener;
        if (l != null) l.onStats(String.format(Locale.US, "%.1f kbps • %d fps cap • Q%d", mbps, maxFps, jpegQuality));
    }

    private void notifyStatus(String text) {
        Listener l = listener;
        if (l != null) l.onStatus(text);
    }

    private void createChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "PhoneBridge streaming", NotificationManager.IMPORTANCE_LOW));
    }

    private android.app.Notification buildNotification(String text) {
        Intent launch = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.presence_video_online)
                .setContentTitle("PhoneBridge")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    private void updateNotification(String text) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.notify(7, buildNotification(text));
    }

    private static String escape(String s) { return s == null ? "" : s.replace("\\", "\\\\").replace("\"", "\\\""); }

    @Override public void onDestroy() {
        stopStreaming();
        cameraExecutor.shutdownNow();
        networkExecutor.shutdownNow();
        super.onDestroy();
    }
}
