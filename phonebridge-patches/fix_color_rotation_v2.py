from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')

# Imports for CameraX RGBA bitmap path and continuous orientation tracking.
if 'import android.graphics.Bitmap;\n' not in s:
    s = s.replace('import android.graphics.ImageFormat;\n', 'import android.graphics.Bitmap;\nimport android.graphics.ImageFormat;\nimport android.graphics.Matrix;\n')
if 'import android.view.OrientationEventListener;\n' not in s:
    s = s.replace('import android.view.Surface;\n', 'import android.view.Surface;\nimport android.view.OrientationEventListener;\n')
if 'import androidx.camera.core.UseCase;\n' not in s:
    s = s.replace('import androidx.camera.core.Preview;\n', 'import androidx.camera.core.Preview;\nimport androidx.camera.core.UseCase;\n')

# Keep a live ImageAnalysis reference and orientation listener.
s = s.replace('    private volatile Camera camera;\n',
              '    private volatile Camera camera;\n'
              '    private volatile ImageAnalysis imageAnalysis;\n'
              '    private volatile OrientationEventListener orientationEventListener;\n')

# Create a continuous physical-orientation listener. This handles reverse portrait/landscape
# even when Android does not rotate/recreate the Activity UI.
old_oncreate = '''    @Override public void onCreate() {
        super.onCreate();
        createChannel();
    }
'''
new_oncreate = '''    @Override public void onCreate() {
        super.onCreate();
        createChannel();
        orientationEventListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) return;
                final int rotation = UseCase.snapToSurfaceRotation(orientation);
                final ImageAnalysis analysis = imageAnalysis;
                if (analysis != null && analysis.getTargetRotation() != rotation) {
                    analysis.setTargetRotation(rotation);
                }
            }
        };
        if (orientationEventListener.canDetectOrientation()) orientationEventListener.enable();
    }
'''
if old_oncreate not in s:
    raise SystemExit('onCreate block not found')
s = s.replace(old_oncreate, new_oncreate)

# Use CameraX RGBA conversion rather than device-dependent YUV/NV21 chroma conversion.
s = s.replace('.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)',
              '.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)')

# Save the currently bound analysis use case so the OrientationEventListener can update it dynamically.
needle = '''                analysis.setAnalyzer(cameraExecutor, this::processFrame);

                CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
'''
replacement = '''                analysis.setAnalyzer(cameraExecutor, this::processFrame);
                imageAnalysis = analysis;

                CameraSelector selector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();
'''
if needle not in s:
    raise SystemExit('analysis analyzer block not found')
s = s.replace(needle, replacement)

# Clear analysis reference on stop.
s = s.replace('''    private void stopCamera() {
        camera = null;
''', '''    private void stopCamera() {
        camera = null;
        imageAnalysis = null;
''')

# Replace the manual NV21 path with CameraX RGBA -> Bitmap -> rotation/mirror -> JPEG.
old_frame = '''            YuvTools.Nv21Frame frame = YuvTools.toNv21(image);
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
new_frame = '''            // CameraX performs the device-specific color conversion to a defined RGBA_8888 buffer.
            // This avoids vendor-specific Y/U/V row/pixel-stride and chroma-offset behavior.
            Bitmap source = image.toBitmap();
            Bitmap outputBitmap = source;
            int rotation = image.getImageInfo().getRotationDegrees();
            boolean shouldMirror = mirror && lensFacing == CameraSelector.LENS_FACING_FRONT;
            if (rotation != 0 || shouldMirror) {
                Matrix matrix = new Matrix();
                if (rotation != 0) matrix.postRotate(rotation);
                if (shouldMirror) matrix.postScale(-1f, 1f);
                outputBitmap = Bitmap.createBitmap(source, 0, 0, source.getWidth(), source.getHeight(), matrix, true);
            }

            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(Math.max(64 * 1024,
                    outputBitmap.getWidth() * outputBitmap.getHeight() / 3));
            if (!outputBitmap.compress(Bitmap.CompressFormat.JPEG, jpegQuality, jpeg)) {
                if (outputBitmap != source) outputBitmap.recycle();
                source.recycle();
                throw new IOException("Bitmap JPEG encoder rejected camera frame");
            }
            byte[] data = jpeg.toByteArray();
            if (outputBitmap != source) outputBitmap.recycle();
            source.recycle();
'''
if old_frame not in s:
    raise SystemExit('Expected NV21 frame block not found; refusing partial patch')
s = s.replace(old_frame, new_frame)

# Clean up listener on service destruction.
old_destroy = '''    @Override public void onDestroy() {
        stopStreaming();
        cameraExecutor.shutdownNow();
        networkExecutor.shutdownNow();
        super.onDestroy();
    }
'''
new_destroy = '''    @Override public void onDestroy() {
        if (orientationEventListener != null) orientationEventListener.disable();
        orientationEventListener = null;
        stopStreaming();
        cameraExecutor.shutdownNow();
        networkExecutor.shutdownNow();
        super.onDestroy();
    }
'''
if old_destroy not in s:
    raise SystemExit('onDestroy block not found')
s = s.replace(old_destroy, new_destroy)

p.write_text(s, encoding='utf-8')
print(f'Patched RGBA color path and continuous rotation: {p}')
