package com.example.lightroomclone;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;

import com.example.lightroomclone.core.ColorAdjustments;
import com.example.lightroomclone.core.ColorMath;
import com.example.lightroomclone.core.CurveSet;
import com.example.lightroomclone.core.GeometryAdjustments;

final class ImageProcessor {
    private static final int FAST_LUT_SIZE = 17;

    interface CancelChecker {
        boolean isCancelled();
    }

    private ImageProcessor() {}

    static Bitmap apply(Bitmap source, GeometryAdjustments geometry, ColorAdjustments adjustments,
            CurveSet curves) {
        return apply(source, geometry, adjustments, curves, Math.max(source.getWidth(), source.getHeight()));
    }

    static Bitmap apply(Bitmap source, GeometryAdjustments geometry, ColorAdjustments adjustments,
            CurveSet curves, int maxEdge) {
        return apply(source, geometry, adjustments, curves, maxEdge, () -> false);
    }

    static Bitmap apply(Bitmap source, GeometryAdjustments geometry, ColorAdjustments adjustments,
            CurveSet curves, int maxEdge, CancelChecker cancelChecker) {
        Bitmap renderSource = scaleForRender(source, maxEdge);
        if (cancelChecker.isCancelled()) {
            if (renderSource != source) {
                renderSource.recycle();
            }
            return null;
        }
        Bitmap transformed = transform(renderSource, geometry);
        if (cancelChecker.isCancelled()) {
            if (transformed != source) {
                transformed.recycle();
            }
            if (renderSource != source) {
                renderSource.recycle();
            }
            return null;
        }
        Bitmap output = Bitmap.createBitmap(transformed.getWidth(), transformed.getHeight(), Bitmap.Config.ARGB_8888);
        int width = transformed.getWidth();
        int height = transformed.getHeight();
        int[] pixels = new int[width * height];
        float[] hsvScratch = new float[3];
        ColorAdjustments snapshot = adjustments.copy();
        CurveSet curveSnapshot = curves.copy();
        int[] luminanceCurve = ColorMath.buildLookup(curveSnapshot.luminance);
        int[] redCurve = ColorMath.buildLookup(curveSnapshot.red);
        int[] greenCurve = ColorMath.buildLookup(curveSnapshot.green);
        int[] blueCurve = ColorMath.buildLookup(curveSnapshot.blue);
        boolean colorMixEnabled = ColorMath.hasColorMix(snapshot);
        float[] normalizedX = new float[width];
        float xScale = 1f / Math.max(1, width - 1);
        float yScale = 1f / Math.max(1, height - 1);
        for (int x = 0; x < width; x++) {
            normalizedX[x] = x * xScale;
        }

        transformed.getPixels(pixels, 0, width, 0, 0, width, height);
        for (int y = 0; y < height; y++) {
            if ((y & 15) == 0 && cancelChecker.isCancelled()) {
                output.recycle();
                if (transformed != source) {
                    transformed.recycle();
                }
                if (renderSource != source) {
                    renderSource.recycle();
                }
                return null;
            }
            int row = y * width;
            float normalizedY = y * yScale;
            for (int x = 0; x < width; x++) {
                int index = row + x;
                pixels[index] = ColorMath.adjustArgb(pixels[index], snapshot, luminanceCurve,
                        redCurve, greenCurve, blueCurve,
                        normalizedX[x], normalizedY,
                        hsvScratch, colorMixEnabled);
            }
        }
        output.setPixels(pixels, 0, width, 0, 0, width, height);
        if (transformed != source) {
            transformed.recycle();
        }
        if (renderSource != source) {
            renderSource.recycle();
        }
        return output;
    }

    static Bitmap applyFastPreview(Bitmap source, GeometryAdjustments geometry, ColorAdjustments adjustments,
            CurveSet curves, int maxEdge, CancelChecker cancelChecker) {
        Bitmap renderSource = scaleForRender(source, maxEdge);
        if (cancelChecker.isCancelled()) {
            if (renderSource != source) {
                renderSource.recycle();
            }
            return null;
        }
        Bitmap transformed = transform(renderSource, geometry);
        if (cancelChecker.isCancelled()) {
            if (transformed != source) {
                transformed.recycle();
            }
            if (renderSource != source) {
                renderSource.recycle();
            }
            return null;
        }

        int width = transformed.getWidth();
        int height = transformed.getHeight();
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        int[] pixels = new int[width * height];
        transformed.getPixels(pixels, 0, width, 0, 0, width, height);

        ColorAdjustments snapshot = adjustments.copy();
        float vignette = snapshot.vignette;
        snapshot.vignette = 0f;
        int[] lookup = buildFastLookup(snapshot, curves.copy());
        float xScale = 1f / Math.max(1, width - 1);
        float yScale = 1f / Math.max(1, height - 1);

        for (int y = 0; y < height; y++) {
            if ((y & 15) == 0 && cancelChecker.isCancelled()) {
                output.recycle();
                if (transformed != source) {
                    transformed.recycle();
                }
                if (renderSource != source) {
                    renderSource.recycle();
                }
                return null;
            }
            int row = y * width;
            float normalizedY = y * yScale;
            for (int x = 0; x < width; x++) {
                int index = row + x;
                int argb = pixels[index];
                int alpha = argb & 0xff000000;
                int adjusted = lookup[fastLookupIndex((argb >>> 16) & 0xff,
                        (argb >>> 8) & 0xff, argb & 0xff)];
                if (vignette != 0f) {
                    adjusted = applyFastVignette(adjusted, vignette, x * xScale, normalizedY);
                }
                pixels[index] = alpha | (adjusted & 0x00ffffff);
            }
        }
        output.setPixels(pixels, 0, width, 0, 0, width, height);
        if (transformed != source) {
            transformed.recycle();
        }
        if (renderSource != source) {
            renderSource.recycle();
        }
        return output;
    }

    private static int[] buildFastLookup(ColorAdjustments adjustments, CurveSet curves) {
        int[] lookup = new int[FAST_LUT_SIZE * FAST_LUT_SIZE * FAST_LUT_SIZE];
        float[] hsvScratch = new float[3];
        int[] luminanceCurve = ColorMath.buildLookup(curves.luminance);
        int[] redCurve = ColorMath.buildLookup(curves.red);
        int[] greenCurve = ColorMath.buildLookup(curves.green);
        int[] blueCurve = ColorMath.buildLookup(curves.blue);
        boolean colorMixEnabled = ColorMath.hasColorMix(adjustments);
        for (int r = 0; r < FAST_LUT_SIZE; r++) {
            int red = lutValue(r);
            for (int g = 0; g < FAST_LUT_SIZE; g++) {
                int green = lutValue(g);
                for (int b = 0; b < FAST_LUT_SIZE; b++) {
                    int blue = lutValue(b);
                    int color = 0xff000000 | (red << 16) | (green << 8) | blue;
                    lookup[(r * FAST_LUT_SIZE + g) * FAST_LUT_SIZE + b] = ColorMath.adjustArgb(color,
                            adjustments, luminanceCurve, redCurve, greenCurve, blueCurve,
                            0.5f, 0.5f, hsvScratch, colorMixEnabled);
                }
            }
        }
        return lookup;
    }

    private static int fastLookupIndex(int red, int green, int blue) {
        int r = red * (FAST_LUT_SIZE - 1) / 255;
        int g = green * (FAST_LUT_SIZE - 1) / 255;
        int b = blue * (FAST_LUT_SIZE - 1) / 255;
        return (r * FAST_LUT_SIZE + g) * FAST_LUT_SIZE + b;
    }

    private static int lutValue(int index) {
        return Math.round(index * 255f / (FAST_LUT_SIZE - 1));
    }

    private static int applyFastVignette(int argb, float vignette, float normalizedX, float normalizedY) {
        float dx = normalizedX - 0.5f;
        float dy = normalizedY - 0.5f;
        float edge = smoothstep(0.18f, 0.72f, (float) Math.sqrt(dx * dx + dy * dy));
        float scale = 1f - vignette * 0.65f * edge;
        int red = clampChannel(Math.round(((argb >>> 16) & 0xff) * scale));
        int green = clampChannel(Math.round(((argb >>> 8) & 0xff) * scale));
        int blue = clampChannel(Math.round((argb & 0xff) * scale));
        return (argb & 0xff000000) | (red << 16) | (green << 8) | blue;
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float x = Math.max(0f, Math.min(1f, (value - edge0) / (edge1 - edge0)));
        return x * x * (3f - 2f * x);
    }

    private static int clampChannel(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 255) {
            return 255;
        }
        return value;
    }

    private static Bitmap scaleForRender(Bitmap source, int maxEdge) {
        int width = source.getWidth();
        int height = source.getHeight();
        int largest = Math.max(width, height);
        if (largest <= maxEdge) {
            return source;
        }
        float scale = maxEdge / (float) largest;
        return Bitmap.createScaledBitmap(source, Math.max(1, Math.round(width * scale)),
                Math.max(1, Math.round(height * scale)), true);
    }

    private static Bitmap transform(Bitmap source, GeometryAdjustments geometry) {
        RectF crop = cropRect(source, geometry);
        int width = Math.max(1, Math.round(crop.width()));
        int height = Math.max(1, Math.round(crop.height()));
        Bitmap output = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(output);
        canvas.drawColor(Color.rgb(8, 9, 12));

        float scale = Math.max(width / crop.width(), height / crop.height());
        scale *= 1f + geometry.cropZoom * 0.8f;
        float angle = geometry.rotateDegrees + geometry.quarterTurns * 90f;
        double radians = Math.toRadians(Math.abs(angle % 180f));
        float cover = (float) (Math.abs(Math.cos(radians)) + Math.abs(Math.sin(radians)));
        scale *= Math.max(1f, cover);

        Matrix matrix = new Matrix();
        matrix.postTranslate(-crop.centerX(), -crop.centerY());
        matrix.postScale(scale, scale);
        matrix.postRotate(angle);
        matrix.postTranslate(width / 2f, height / 2f);

        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        canvas.drawBitmap(source, matrix, paint);
        return output;
    }

    private static RectF cropRect(Bitmap source, GeometryAdjustments geometry) {
        float width = source.getWidth();
        float height = source.getHeight();
        return new RectF(
                width * geometry.cropLeft,
                height * geometry.cropTop,
                width * geometry.cropRight,
                height * geometry.cropBottom);
    }
}
