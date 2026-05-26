package com.example.lightroomclone.core;

import android.graphics.Color;

public final class ColorMath {
    private static final float[] MIX_CENTERS = {0f, 30f, 60f, 120f, 180f, 230f, 275f, 315f};

    private ColorMath() {}

    public static int adjustArgb(int argb, ColorAdjustments adjustments, CurveSet curves,
            float normalizedX, float normalizedY) {
        return adjustArgb(argb, adjustments, curves, normalizedX, normalizedY, new float[3]);
    }

    public static int adjustArgb(int argb, ColorAdjustments adjustments, CurveSet curves,
            float normalizedX, float normalizedY, float[] hsvScratch) {
        int[] luminanceCurve = buildLookup(curves.luminance);
        int[] redCurve = buildLookup(curves.red);
        int[] greenCurve = buildLookup(curves.green);
        int[] blueCurve = buildLookup(curves.blue);
        return adjustArgb(argb, adjustments, luminanceCurve, redCurve, greenCurve, blueCurve,
                normalizedX, normalizedY, hsvScratch, hasColorMix(adjustments));
    }

    public static int adjustArgb(int argb, ColorAdjustments adjustments, int[] luminanceCurve,
            int[] redCurve, int[] greenCurve, int[] blueCurve, float normalizedX, float normalizedY,
            float[] hsvScratch) {
        return adjustArgb(argb, adjustments, luminanceCurve, redCurve, greenCurve, blueCurve,
                normalizedX, normalizedY, hsvScratch, hasColorMix(adjustments));
    }

    public static int adjustArgb(int argb, ColorAdjustments adjustments, int[] luminanceCurve,
            int[] redCurve, int[] greenCurve, int[] blueCurve, float normalizedX, float normalizedY,
            float[] hsvScratch, boolean colorMixEnabled) {
        int alpha = (argb >>> 24) & 0xff;
        float r = ((argb >>> 16) & 0xff) / 255f;
        float g = ((argb >>> 8) & 0xff) / 255f;
        float b = (argb & 0xff) / 255f;

        float exposureScale = (float) Math.pow(2f, adjustments.exposure);
        r *= exposureScale;
        g *= exposureScale;
        b *= exposureScale;

        r += adjustments.brightness * 0.35f;
        g += adjustments.brightness * 0.35f;
        b += adjustments.brightness * 0.35f;

        float luminance = r * 0.299f + g * 0.587f + b * 0.114f;
        float highlightMask = smoothstep(0.45f, 1f, luminance);
        float shadowMask = 1f - smoothstep(0f, 0.55f, luminance);
        r += adjustments.highlights * 0.28f * highlightMask;
        g += adjustments.highlights * 0.28f * highlightMask;
        b += adjustments.highlights * 0.28f * highlightMask;
        r += adjustments.shadows * 0.32f * shadowMask;
        g += adjustments.shadows * 0.32f * shadowMask;
        b += adjustments.shadows * 0.32f * shadowMask;

        if (adjustments.ambiance != 0f) {
            float ambianceAmount = adjustments.ambiance * 0.28f;
            r += (0.5f - luminance) * ambianceAmount;
            g += (0.5f - luminance) * ambianceAmount;
            b += (0.5f - luminance) * ambianceAmount;
        }

        float contrastScale = adjustments.contrast >= 0f
                ? 1f + adjustments.contrast * 1.6f
                : 1f + adjustments.contrast * 0.85f;
        r = (r - 0.5f) * contrastScale + 0.5f;
        g = (g - 0.5f) * contrastScale + 0.5f;
        b = (b - 0.5f) * contrastScale + 0.5f;

        if (adjustments.dehaze != 0f) {
            float dehazeScale = adjustments.dehaze >= 0f
                    ? 1f + adjustments.dehaze * 0.9f
                    : 1f + adjustments.dehaze * 0.35f;
            r = (r - 0.5f) * dehazeScale + 0.5f - adjustments.dehaze * 0.03f;
            g = (g - 0.5f) * dehazeScale + 0.5f - adjustments.dehaze * 0.03f;
            b = (b - 0.5f) * dehazeScale + 0.5f - adjustments.dehaze * 0.03f;
        }

        luminance = r * 0.299f + g * 0.587f + b * 0.114f;
        float saturationScale = adjustments.saturation >= 0f
                ? 1f + adjustments.saturation * 1.5f
                : 1f + adjustments.saturation;
        r = luminance + (r - luminance) * saturationScale;
        g = luminance + (g - luminance) * saturationScale;
        b = luminance + (b - luminance) * saturationScale;

        if (colorMixEnabled) {
            int mixed = applyColorMix(adjustments, r, g, b, hsvScratch);
            r = ((mixed >>> 16) & 0xff) / 255f + hsvScratch[0];
            g = ((mixed >>> 8) & 0xff) / 255f + hsvScratch[0];
            b = (mixed & 0xff) / 255f + hsvScratch[0];
        }

        r += adjustments.temperature * 0.12f + adjustments.tint * 0.04f;
        g -= adjustments.tint * 0.08f;
        b -= adjustments.temperature * 0.12f;
        b += adjustments.tint * 0.04f;

        if (adjustments.fade > 0f) {
            r = r * (1f - adjustments.fade * 0.35f) + 0.06f * adjustments.fade;
            g = g * (1f - adjustments.fade * 0.35f) + 0.06f * adjustments.fade;
            b = b * (1f - adjustments.fade * 0.35f) + 0.06f * adjustments.fade;
        }

        if (adjustments.vignette != 0f) {
            float dx = normalizedX - 0.5f;
            float dy = normalizedY - 0.5f;
            float edge = smoothstep(0.18f, 0.72f, (float) Math.sqrt(dx * dx + dy * dy));
            float vignetteScale = 1f - adjustments.vignette * 0.65f * edge;
            r *= vignetteScale;
            g *= vignetteScale;
            b *= vignetteScale;
        }

        int ri = redCurve[toChannel(r)];
        int gi = greenCurve[toChannel(g)];
        int bi = blueCurve[toChannel(b)];
        ri = luminanceCurve[ri];
        gi = luminanceCurve[gi];
        bi = luminanceCurve[bi];
        return (alpha << 24) | (ri << 16) | (gi << 8) | bi;
    }

    public static int[] buildLookup(ToneCurve curve) {
        int[] lookup = new int[256];
        for (int i = 0; i < lookup.length; i++) {
            lookup[i] = curve.map(i);
        }
        return lookup;
    }

    public static boolean hasColorMix(ColorAdjustments adjustments) {
        for (int i = 0; i < ColorAdjustments.MIX_COUNT; i++) {
            if (adjustments.mixHue[i] != 0f || adjustments.mixSaturation[i] != 0f
                    || adjustments.mixLuminance[i] != 0f) {
                return true;
            }
        }
        return false;
    }

    public static int toChannel(float value) {
        if (value <= 0f) {
            return 0;
        }
        if (value >= 1f) {
            return 255;
        }
        return Math.round(value * 255f);
    }

    private static int applyColorMix(ColorAdjustments adjustments, float r, float g, float b,
            float[] hsvScratch) {
        int color = Color.rgb(toChannel(r), toChannel(g), toChannel(b));
        Color.colorToHSV(color, hsvScratch);
        float hueShift = 0f;
        float saturationShift = 0f;
        float luminanceShift = 0f;
        float totalWeight = 0f;
        for (int i = 0; i < ColorAdjustments.MIX_COUNT; i++) {
            float weight = hueWeight(hsvScratch[0], MIX_CENTERS[i]);
            if (weight <= 0f) {
                continue;
            }
            totalWeight += weight;
            hueShift += adjustments.mixHue[i] * 36f * weight;
            saturationShift += adjustments.mixSaturation[i] * 0.55f * weight;
            luminanceShift += adjustments.mixLuminance[i] * 0.32f * weight;
        }
        if (totalWeight > 0f) {
            hueShift /= totalWeight;
            saturationShift /= totalWeight;
            luminanceShift /= totalWeight;
        }
        hsvScratch[0] = (hsvScratch[0] + hueShift + 360f) % 360f;
        hsvScratch[1] = clamp01(hsvScratch[1] + saturationShift);
        hsvScratch[2] = clamp01(hsvScratch[2]);
        int mixed = Color.HSVToColor(hsvScratch);
        hsvScratch[0] = luminanceShift;
        return mixed;
    }

    private static float hueWeight(float hue, float center) {
        float distance = Math.abs(hue - center);
        distance = Math.min(distance, 360f - distance);
        return Math.max(0f, 1f - distance / 45f);
    }

    private static float smoothstep(float edge0, float edge1, float value) {
        float x = clamp01((value - edge0) / (edge1 - edge0));
        return x * x * (3f - 2f * x);
    }

    private static float clamp01(float value) {
        if (value < 0f) {
            return 0f;
        }
        if (value > 1f) {
            return 1f;
        }
        return value;
    }
}
