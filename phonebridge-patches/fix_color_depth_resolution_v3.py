from pathlib import Path
import sys

p = Path(sys.argv[1])
s = p.read_text(encoding='utf-8')

# CameraX 1.5+ resolution selector imports.
if 'import androidx.camera.core.resolutionselector.ResolutionSelector;\n' not in s:
    s = s.replace('import androidx.camera.core.UseCase;\n',
                  'import androidx.camera.core.UseCase;\n'
                  'import androidx.camera.core.resolutionselector.ResolutionSelector;\n'
                  'import androidx.camera.core.resolutionselector.ResolutionStrategy;\n')

# Avoid the oversized square mode observed on-device. Use a bounded 16:9 analysis stream.
old_resolution = '''                ImageAnalysis analysis = new ImageAnalysis.Builder()\n                        .setTargetResolution(new Size(1280, 720))\n                        .setTargetRotation(targetRotation)\n'''
new_resolution = '''                ResolutionSelector resolutionSelector = new ResolutionSelector.Builder()\n                        .setResolutionStrategy(new ResolutionStrategy(\n                                new Size(1280, 720),\n                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))\n                        .build();\n\n                ImageAnalysis analysis = new ImageAnalysis.Builder()\n                        .setResolutionSelector(resolutionSelector)\n                        .setTargetRotation(targetRotation)\n'''
if old_resolution not in s:
    raise SystemExit('Expected CameraX target-resolution block not found')
s = s.replace(old_resolution, new_resolution)

# Do not rely on ImageProxy.toBitmap() choosing a high-color-depth Bitmap implementation
# on every vendor device. Copy the CameraX RGBA_8888 plane explicitly into ARGB_8888.
s = s.replace('            Bitmap source = image.toBitmap();\n',
              '            Bitmap source = rgbaPlaneToArgb8888(image);\n')

helper_anchor = '    private void processFrame(ImageProxy image) {\n'
helper = r'''    private Bitmap rgbaPlaneToArgb8888(ImageProxy image) {
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        if (planes == null || planes.length != 1) {
            throw new IllegalArgumentException("Expected one RGBA camera plane");
        }

        final int width = image.getWidth();
        final int height = image.getHeight();
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("Invalid camera frame size");

        ImageProxy.PlaneProxy plane = planes[0];
        java.nio.ByteBuffer src = plane.getBuffer().duplicate();
        final int base = src.position();
        final int limit = src.limit();
        final int rowStride = plane.getRowStride();
        final int pixelStride = plane.getPixelStride();
        if (pixelStride < 4 || rowStride < width * pixelStride) {
            throw new IllegalArgumentException("Invalid RGBA camera stride");
        }

        // Android ARGB_8888 uses 8 bits per channel. On little-endian Android devices,
        // copyPixelsFromBuffer consumes bytes as R,G,B,A, matching CameraX RGBA_8888.
        java.nio.ByteBuffer tight = java.nio.ByteBuffer.allocateDirect(width * height * 4);
        for (int y = 0; y < height; y++) {
            int row = base + y * rowStride;
            for (int x = 0; x < width; x++) {
                int pos = row + x * pixelStride;
                if (pos < base || pos + 3 >= limit) {
                    throw new IllegalArgumentException("RGBA camera plane exceeds buffer");
                }
                tight.put(src.get(pos));
                tight.put(src.get(pos + 1));
                tight.put(src.get(pos + 2));
                tight.put(src.get(pos + 3));
            }
        }
        tight.flip();
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        bitmap.copyPixelsFromBuffer(tight);
        bitmap.setHasAlpha(false);
        return bitmap;
    }

'''
if helper_anchor not in s:
    raise SystemExit('processFrame anchor not found')
s = s.replace(helper_anchor, helper + helper_anchor, 1)

# Increase JPEG quality after forcing 720p. This is still much lower bandwidth than the
# accidental 2448x2448 stream, but avoids unnecessary chroma/blocking loss.
s = s.replace('    private volatile int jpegQuality = 78;\n',
              '    private volatile int jpegQuality = 92;\n')

p.write_text(s, encoding='utf-8')
print(f'Patched ARGB8888 color depth and 720p resolution: {p}')
