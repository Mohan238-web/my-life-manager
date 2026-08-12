from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')

# CameraX 1.5+ provides a standardized NV21 analysis output. This is preferred here
# because the user's phone produces visibly corrupted colors through the RGBA analysis
# conversion path even though the PreviewView itself is correct.
s = s.replace(
    '.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)',
    '.setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_NV21)')

old_frame = '''            // CameraX performs the device-specific color conversion to a defined RGBA_8888 buffer.
            // This avoids vendor-specific Y/U/V row/pixel-stride and chroma-offset behavior.
            Bitmap source = rgbaPlaneToArgb8888(image);
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

new_frame = '''            // CameraX 1.5+ normalizes analyzer frames to NV21 for us. Read that
            // normalized ImageProxy rather than the vendor camera's native YUV/RGBA layout.
            YuvTools.Nv21Frame frame = YuvTools.fromCameraXStandardNv21(image);
            int rotation = image.getImageInfo().getRotationDegrees();
            if (rotation != 0) frame = YuvTools.rotateNv21(frame, rotation);
            if (mirror && lensFacing == CameraSelector.LENS_FACING_FRONT) {
                frame = YuvTools.mirrorHorizontal(frame);
            }

            YuvImage yuv = new YuvImage(frame.data, ImageFormat.NV21, frame.width, frame.height, null);
            ByteArrayOutputStream jpeg = new ByteArrayOutputStream(Math.max(64 * 1024, frame.data.length / 3));
            if (!yuv.compressToJpeg(new Rect(0, 0, frame.width, frame.height), jpegQuality, jpeg)) {
                throw new IOException("NV21 JPEG encoder rejected camera frame");
            }
            byte[] data = jpeg.toByteArray();
'''

if old_frame not in s:
    raise SystemExit('Expected ARGB8888 frame block not found; refusing partial NV21 patch')
s = s.replace(old_frame, new_frame)

# The old explicit RGBA helper may remain in source but must no longer be called. Keeping it
# avoids fragile source surgery while CI verifies the active frame path is standardized NV21.

p.write_text(s, encoding='utf-8')
print(f'Patched standardized CameraX NV21 sender path: {p}')
