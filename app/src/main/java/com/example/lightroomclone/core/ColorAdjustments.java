package com.example.lightroomclone.core;

public final class ColorAdjustments {
    public static final int MAX_LOCAL_POINTS = 3;
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
    public float sharpness;
    public float noiseReduction;
    public float grain;
    public float localEnabled;
    public float localX = 0.5f;
    public float localY = 0.5f;
    public float localRadius = 0.35f;
    public float localFeather = 0.35f;
    public float localExposure;
    public float localSaturation;
    public int localCount;
    public int activeLocalIndex;
    public final float[] localXs = new float[MAX_LOCAL_POINTS];
    public final float[] localYs = new float[MAX_LOCAL_POINTS];
    public final float[] localRadii = new float[MAX_LOCAL_POINTS];
    public final float[] localFeathers = new float[MAX_LOCAL_POINTS];
    public final float[] localExposures = new float[MAX_LOCAL_POINTS];
    public final float[] localSaturations = new float[MAX_LOCAL_POINTS];
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
        copy.sharpness = sharpness;
        copy.noiseReduction = noiseReduction;
        copy.grain = grain;
        copy.localEnabled = localEnabled;
        copy.localX = localX;
        copy.localY = localY;
        copy.localRadius = localRadius;
        copy.localFeather = localFeather;
        copy.localExposure = localExposure;
        copy.localSaturation = localSaturation;
        copy.localCount = localCount;
        copy.activeLocalIndex = activeLocalIndex;
        System.arraycopy(localXs, 0, copy.localXs, 0, localXs.length);
        System.arraycopy(localYs, 0, copy.localYs, 0, localYs.length);
        System.arraycopy(localRadii, 0, copy.localRadii, 0, localRadii.length);
        System.arraycopy(localFeathers, 0, copy.localFeathers, 0, localFeathers.length);
        System.arraycopy(localExposures, 0, copy.localExposures, 0, localExposures.length);
        System.arraycopy(localSaturations, 0, copy.localSaturations, 0, localSaturations.length);
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
        sharpness = 0f;
        noiseReduction = 0f;
        grain = 0f;
        localEnabled = 0f;
        localX = 0.5f;
        localY = 0.5f;
        localRadius = 0.35f;
        localFeather = 0.35f;
        localExposure = 0f;
        localSaturation = 0f;
        localCount = 0;
        activeLocalIndex = 0;
        for (int i = 0; i < MAX_LOCAL_POINTS; i++) {
            localXs[i] = 0.5f;
            localYs[i] = 0.5f;
            localRadii[i] = 0.35f;
            localFeathers[i] = 0.35f;
            localExposures[i] = 0f;
            localSaturations[i] = 0f;
        }
        for (int i = 0; i < MIX_COUNT; i++) {
            mixHue[i] = 0f;
            mixSaturation[i] = 0f;
            mixLuminance[i] = 0f;
        }
    }
}
