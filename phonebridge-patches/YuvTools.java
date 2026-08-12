package com.phonebridge.app;

import android.graphics.Rect;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import java.nio.ByteBuffer;

final class YuvTools {
    static final class Nv21Frame {
        final byte[] data;
        final int width;
        final int height;
        Nv21Frame(byte[] data, int width, int height) {
            this.data = data;
            this.width = width;
            this.height = height;
        }
    }

    private YuvTools() {}

    /**
     * Extract CameraX OUTPUT_IMAGE_FORMAT_NV21 without depending on a phone vendor's
     * native YUV layout. CameraX 1.5+ guarantees the ImageProxy data is formatted as
     * NV21 even if image.getImage() still exposes the original camera buffer.
     */
    static Nv21Frame fromCameraXStandardNv21(ImageProxy image) {
        Rect crop = image.getCropRect();
        int left = crop.left & ~1;
        int top = crop.top & ~1;
        int width = crop.width() & ~1;
        int height = crop.height() & ~1;
        if (width <= 0 || height <= 0) {
            left = 0;
            top = 0;
            width = image.getWidth() & ~1;
            height = image.getHeight() & ~1;
        }

        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) {
            throw new IllegalArgumentException("CameraX NV21 planes unavailable");
        }

        int ySize = width * height;
        byte[] out = new byte[ySize + ySize / 2];

        // Y plane.
        copyPlane(planes[0], left, top, width, height, out, 0, 1);

        // CameraX OUTPUT_IMAGE_FORMAT_NV21 guarantees V/U interleaving. Prefer a
        // direct row copy from plane[2] (V pointer); if a device exposes a shortened
        // plane view, fall back to reading the guaranteed V and U views separately.
        ImageProxy.PlaneProxy vPlane = planes[2];
        ImageProxy.PlaneProxy uPlane = planes[1];
        ByteBuffer vb = vPlane.getBuffer().duplicate();
        ByteBuffer ub = uPlane.getBuffer().duplicate();
        final int vBase = vb.position();
        final int uBase = ub.position();
        final int vLimit = vb.limit();
        final int uLimit = ub.limit();
        final int vRowStride = vPlane.getRowStride();
        final int uRowStride = uPlane.getRowStride();
        final int vPixelStride = vPlane.getPixelStride();
        final int uPixelStride = uPlane.getPixelStride();
        final int chromaLeft = left / 2;
        final int chromaTop = top / 2;
        final int chromaRows = height / 2;
        final int chromaPairs = width / 2;

        for (int r = 0; r < chromaRows; r++) {
            int dstRow = ySize + r * width;
            int direct = vBase + (chromaTop + r) * vRowStride + chromaLeft * vPixelStride;
            if (vPixelStride == 2 && direct >= vBase && direct + width <= vLimit) {
                ByteBuffer row = vb.duplicate();
                row.position(direct);
                row.get(out, dstRow, width);
                continue;
            }

            for (int c = 0; c < chromaPairs; c++) {
                int vp = vBase + (chromaTop + r) * vRowStride + (chromaLeft + c) * vPixelStride;
                int up = uBase + (chromaTop + r) * uRowStride + (chromaLeft + c) * uPixelStride;
                if (vp < vBase || vp >= vLimit || up < uBase || up >= uLimit) {
                    throw new IllegalArgumentException("CameraX NV21 chroma stride exceeds buffer");
                }
                out[dstRow + c * 2] = vb.get(vp);
                out[dstRow + c * 2 + 1] = ub.get(up);
            }
        }
        return new Nv21Frame(out, width, height);
    }

    static Nv21Frame toNv21(ImageProxy image) {
        Rect crop = image.getCropRect();
        int left = crop.left & ~1;
        int top = crop.top & ~1;
        int width = crop.width() & ~1;
        int height = crop.height() & ~1;
        if (width <= 0 || height <= 0) {
            left = 0;
            top = 0;
            width = image.getWidth() & ~1;
            height = image.getHeight() & ~1;
        }

        int ySize = width * height;
        byte[] out = new byte[ySize + ySize / 2];
        ImageProxy.PlaneProxy[] planes = image.getPlanes();
        if (planes == null || planes.length < 3) throw new IllegalArgumentException("Camera YUV planes unavailable");

        copyPlane(planes[0], left, top, width, height, out, 0, 1);
        copyPlane(planes[2], left / 2, top / 2, width / 2, height / 2, out, ySize, 2);
        copyPlane(planes[1], left / 2, top / 2, width / 2, height / 2, out, ySize + 1, 2);
        return new Nv21Frame(out, width, height);
    }

    private static void copyPlane(ImageProxy.PlaneProxy plane,
                                  int cropX, int cropY, int width, int height,
                                  byte[] out, int offset, int outputStride) {
        ByteBuffer buffer = plane.getBuffer().duplicate();
        final int base = buffer.position();
        final int limit = buffer.limit();
        final int rowStride = plane.getRowStride();
        final int pixelStride = plane.getPixelStride();
        int dst = offset;

        for (int r = 0; r < height; r++) {
            int rowBase = base + (cropY + r) * rowStride + cropX * pixelStride;
            for (int c = 0; c < width; c++) {
                int src = rowBase + c * pixelStride;
                if (src < base || src >= limit) {
                    throw new IllegalArgumentException("Camera plane stride exceeds buffer");
                }
                out[dst] = buffer.get(src);
                dst += outputStride;
            }
        }
    }

    static Nv21Frame rotateNv21(Nv21Frame src, int degrees) {
        int rotation = ((degrees % 360) + 360) % 360;
        if (rotation == 0) return src;
        if (rotation != 90 && rotation != 180 && rotation != 270) return src;

        final int w = src.width;
        final int h = src.height;
        final int ySize = w * h;
        byte[] out = new byte[src.data.length];
        int i = 0;

        if (rotation == 90) {
            for (int x = 0; x < w; x++)
                for (int y = h - 1; y >= 0; y--)
                    out[i++] = src.data[y * w + x];
            int cw = w / 2, ch = h / 2;
            for (int x = 0; x < cw; x++) {
                for (int y = ch - 1; y >= 0; y--) {
                    int p = ySize + y * w + x * 2;
                    out[i++] = src.data[p];
                    out[i++] = src.data[p + 1];
                }
            }
            return new Nv21Frame(out, h, w);
        }

        if (rotation == 270) {
            for (int x = w - 1; x >= 0; x--)
                for (int y = 0; y < h; y++)
                    out[i++] = src.data[y * w + x];
            int cw = w / 2, ch = h / 2;
            for (int x = cw - 1; x >= 0; x--) {
                for (int y = 0; y < ch; y++) {
                    int p = ySize + y * w + x * 2;
                    out[i++] = src.data[p];
                    out[i++] = src.data[p + 1];
                }
            }
            return new Nv21Frame(out, h, w);
        }

        for (int p = ySize - 1; p >= 0; p--) out[i++] = src.data[p];
        for (int p = src.data.length - 2; p >= ySize; p -= 2) {
            out[i++] = src.data[p];
            out[i++] = src.data[p + 1];
        }
        return new Nv21Frame(out, w, h);
    }

    static Nv21Frame mirrorHorizontal(Nv21Frame src) {
        int w = src.width, h = src.height, ySize = w * h;
        byte[] out = new byte[src.data.length];
        for (int y = 0; y < h; y++) {
            int row = y * w;
            for (int x = 0; x < w; x++) out[row + x] = src.data[row + (w - 1 - x)];
        }
        for (int y = 0; y < h / 2; y++) {
            int row = ySize + y * w;
            for (int x = 0; x < w / 2; x++) {
                int srcPair = row + (w / 2 - 1 - x) * 2;
                int dstPair = row + x * 2;
                out[dstPair] = src.data[srcPair];
                out[dstPair + 1] = src.data[srcPair + 1];
            }
        }
        return new Nv21Frame(out, w, h);
    }
}
