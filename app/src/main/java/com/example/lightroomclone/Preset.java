package com.example.lightroomclone;

import com.example.lightroomclone.core.ColorAdjustments;
import com.example.lightroomclone.core.CurveSet;

final class Preset {
    final String name;
    final ColorAdjustments adjustments;
    final CurveSet curves;

    Preset(String name, float brightness, float contrast, float saturation, float temperature,
            float tint, float exposure, float highlights, float shadows, float fade, float ambiance,
            int[] curveY) {
        this.name = name;
        this.adjustments = new ColorAdjustments();
        this.adjustments.brightness = brightness;
        this.adjustments.contrast = contrast;
        this.adjustments.saturation = saturation;
        this.adjustments.temperature = temperature;
        this.adjustments.tint = tint;
        this.adjustments.exposure = exposure;
        this.adjustments.highlights = highlights;
        this.adjustments.shadows = shadows;
        this.adjustments.fade = fade;
        this.adjustments.ambiance = ambiance;
        curves = new CurveSet();
        curves.luminance.setFixedPoints(curveY);
    }

    Preset(String name, ColorAdjustments adjustments, CurveSet curves) {
        this.name = name;
        this.adjustments = adjustments.copy();
        this.curves = curves.copy();
    }

    CurveSet newCurves() {
        return curves.copy();
    }

    static Preset[] defaults() {
        return new Preset[] {
            new Preset("Clean", 0f, 0.08f, 0.06f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
                    new int[] {0, 64, 128, 192, 255}),
            new Preset("Vivid", 0.02f, 0.18f, 0.22f, 0.03f, 0f, 0.05f, -0.04f, 0.06f, 0f, 0.16f,
                    new int[] {0, 56, 132, 205, 255}),
            new Preset("Warm", 0.04f, 0.08f, 0.12f, 0.45f, 0.05f, 0.04f, -0.03f, 0.04f, 0.02f, 0.08f,
                    new int[] {4, 66, 132, 198, 255}),
            new Preset("Cool", 0f, 0.1f, 0.04f, -0.38f, -0.03f, 0f, 0f, 0.03f, 0f, 0.04f,
                    new int[] {0, 62, 130, 198, 255}),
            new Preset("Matte", 0.02f, -0.08f, -0.04f, 0.08f, 0f, 0f, -0.08f, 0.14f, 0.42f, 0.1f,
                    new int[] {24, 70, 125, 184, 236}),
            new Preset("Film", -0.01f, 0.04f, -0.12f, 0.18f, 0.08f, -0.02f, -0.06f, 0.1f, 0.28f, 0.12f,
                    new int[] {18, 58, 122, 190, 246}),
            new Preset("Mono", 0.01f, 0.16f, -0.95f, 0f, 0f, 0.03f, -0.02f, 0.05f, 0.08f, 0f,
                    new int[] {8, 60, 128, 204, 255})
        };
    }
}
