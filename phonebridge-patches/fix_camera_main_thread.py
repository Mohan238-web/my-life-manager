from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')

if 'import androidx.core.content.ContextCompat;' not in s:
    s = s.replace('import androidx.core.app.NotificationCompat;\n', 'import androidx.core.app.NotificationCompat;\nimport androidx.core.content.ContextCompat;\n')

s = s.replace('import java.util.concurrent.TimeUnit;\n', '')
s = s.replace('    private volatile long bytesSent = 0;\n    private volatile long startedAt = 0;\n',
              '    private volatile long bytesSent = 0;\n    private volatile long startedAt = 0;\n    private volatile long videoFramesSent = 0;\n    private volatile long lastFrameErrorReportMs = 0;\n')
s = s.replace('        bytesSent = 0;\n', '        bytesSent = 0;\n        videoFramesSent = 0;\n', 1)

s = s.replace('''    public void attachPreview(PreviewView view) {
        previewView = view;
        if (streaming.get()) restartCamera();
    }
''', '''    public void attachPreview(PreviewView view) {
        previewView = view;
        if (streaming.get() && videoEnabled) startCamera();
    }
''')

s = s.replace('''    public void switchCamera() {
        lensFacing = lensFacing == CameraSelector.LENS_FACING_BACK
                ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        if (streaming.get()) restartCamera();
    }
''', '''    public void switchCamera() {
        lensFacing = lensFacing == CameraSelector.LENS_FACING_BACK
                ? CameraSelector.LENS_FACING_FRONT : CameraSelector.LENS_FACING_BACK;
        if (streaming.get() && videoEnabled) startCamera();
    }
''')

old = '''    private void startCamera() {
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
'''

new = '''    private void startCamera() {
        if (!videoEnabled || !streaming.get()) return;
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            notifyStatus("Camera permission is not granted");
            return;
        }

        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                if (!videoEnabled || !streaming.get()) return;
                ProcessCameraProvider provider = future.get();

                // CameraX bind/unbind APIs are @MainThread.
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
                notifyStatus("Camera active • streaming video");
            } catch (Exception e) {
                String detail = e.getMessage();
                if (detail == null || detail.isEmpty()) detail = e.getClass().getSimpleName();
                notifyStatus("Camera error: " + detail);
            }
        }, ContextCompat.getMainExecutor(this));
    }

    private void restartCamera() {
        startCamera();
    }

    private void stopCamera() {
        camera = null;
        ListenableFuture<ProcessCameraProvider> future = ProcessCameraProvider.getInstance(this);
        future.addListener(() -> {
            try {
                future.get().unbindAll();
            } catch (Exception e) {
                String detail = e.getMessage();
                if (detail == null || detail.isEmpty()) detail = e.getClass().getSimpleName();
                notifyStatus("Camera stop error: " + detail);
            }
        }, ContextCompat.getMainExecutor(this));
    }
'''

if old not in s:
    raise SystemExit('Camera lifecycle block not found; refusing unsafe partial patch')
s = s.replace(old, new)

s = s.replace('''            sendRecord(Protocol.TYPE_VIDEO_JPEG, data);
            maybeStats();
        } catch (Exception ignored) {
        } finally {
''', '''            sendRecord(Protocol.TYPE_VIDEO_JPEG, data);
            videoFramesSent++;
            maybeStats();
        } catch (Exception e) {
            long ms = System.currentTimeMillis();
            if (ms - lastFrameErrorReportMs > 2000) {
                lastFrameErrorReportMs = ms;
                String detail = e.getMessage();
                if (detail == null || detail.isEmpty()) detail = e.getClass().getSimpleName();
                notifyStatus("Video frame error: " + detail);
            }
        } finally {
''')

s = s.replace('''        if (l != null) l.onStats(String.format(Locale.US, "%.1f kbps • %d fps cap • Q%d", mbps, maxFps, jpegQuality));
''', '''        if (l != null) l.onStats(String.format(Locale.US, "%.1f kbps • %d video frames • %d fps cap • Q%d", mbps, videoFramesSent, maxFps, jpegQuality));
''')

p.write_text(s, encoding='utf-8')
print(f'Patched CameraX lifecycle on main thread: {p}')