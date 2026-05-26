package com.example.lightroomclone.core;

public final class ColorAdjustments {
    public static final int MIX_RED = 0;
    public static final int MIX_ORANGE = 1;
    public static final int MIX_YELLOW = 2;
    public static final int MIX_GREEN = 3;
    public static final int MIX_AQUA = 4;
    public static final int MIX_BLUE = 5;
    public static final int MIX_PURPLE = 6;
    public static final int MIX_MAGENTA = 7;
    public static final int MIX_COUNT = 8;

    public float brightness;
    public float contrast;
    public float saturation;
    public float temperature;
    public float tint;
    public float exposure;
    public float highlights;
    public float shadows;
    public float fade;
    public float vignette;
    public float dehaze;
    public float ambiance;
    public final float[] mixHue = new float[MIX_COUNT];
    public final float[] mixSaturation = new float[MIX_COUNT];
    public final float[] mixLuminance = new float[MIX_COUNT];

    public ColorAdjustments copy() {
        ColorAdjustments copy = new ColorAdjustments();
        copy.brightness = brightness;
        copy.contrast = contrast;
        copy.saturation = saturation;
        copy.temperature = temperature;
        copy.tint = tint;
        copy.exposure = exposure;
        copy.highlights = highlights;
        copy.shadows = shadows;
        copy.fade = fade;
        copy.vignette = vignette;
        copy.dehaze = dehaze;
        copy.ambiance = ambiance;
        System.arraycopy(mixHue, 0, copy.mixHue, 0, mixHue.length);
        System.arraycopy(mixSaturation, 0, copy.mixSaturation, 0, mixSaturation.length);
        System.arraycopy(mixLuminance, 0, copy.mixLuminance, 0, mixLuminance.length);
        return copy;
    }

    public void reset() {
        brightness = 0f;
        contrast = 0f;
        saturation = 0f;
        temperature = 0f;
        tint = 0f;
        exposure = 0f;
        highlights = 0f;
        shadows = 0f;
        fade = 0f;
        vignette = 0f;
        dehaze = 0f;
        ambiance = 0f;
        for (int i = 0; i < MIX_COUNT; i++) {
            mixHue[i] = 0f;
            mixSaturation[i] = 0f;
            mixLuminance[i] = 0f;
        }
    }
}
