package com.example.lightroomclone.core;

public final class GeometryAdjustments {
    public static final int CROP_FREE = 0;
    public static final int CROP_ORIGINAL = 1;
    public static final int CROP_SQUARE = 2;
    public static final int CROP_4_3 = 3;
    public static final int CROP_16_9 = 4;
    public static final int CROP_3_4 = 5;
    public static final int CROP_9_16 = 6;

    public int cropMode = CROP_ORIGINAL;
    public float cropLeft;
    public float cropTop;
    public float cropRight = 1f;
    public float cropBottom = 1f;
    public float cropZoom;
    public float rotateDegrees;
    public int quarterTurns;

    public GeometryAdjustments copy() {
        GeometryAdjustments copy = new GeometryAdjustments();
        copy.cropMode = cropMode;
        copy.cropLeft = cropLeft;
        copy.cropTop = cropTop;
        copy.cropRight = cropRight;
        copy.cropBottom = cropBottom;
        copy.cropZoom = cropZoom;
        copy.rotateDegrees = rotateDegrees;
        copy.quarterTurns = quarterTurns;
        return copy;
    }

    public void reset() {
        cropMode = CROP_ORIGINAL;
        cropLeft = 0f;
        cropTop = 0f;
        cropRight = 1f;
        cropBottom = 1f;
        cropZoom = 0f;
        rotateDegrees = 0f;
        quarterTurns = 0;
    }

    public void setCropRect(float left, float top, float right, float bottom) {
        float minSize = 0.08f;
        cropLeft = clamp01(Math.min(left, right - minSize));
        cropTop = clamp01(Math.min(top, bottom - minSize));
        cropRight = clamp01(Math.max(right, cropLeft + minSize));
        cropBottom = clamp01(Math.max(bottom, cropTop + minSize));
        if (cropRight - cropLeft < minSize) {
            cropRight = Math.min(1f, cropLeft + minSize);
            cropLeft = Math.max(0f, cropRight - minSize);
        }
        if (cropBottom - cropTop < minSize) {
            cropBottom = Math.min(1f, cropTop + minSize);
            cropTop = Math.max(0f, cropBottom - minSize);
        }
    }

    public void resetCropForMode(float sourceAspect) {
        float targetAspect = targetAspect(sourceAspect);
        float width = 1f;
        float height = 1f;
        if (sourceAspect > targetAspect) {
            width = targetAspect / sourceAspect;
        } else {
            height = sourceAspect / targetAspect;
        }
        float left = (1f - width) / 2f;
        float top = (1f - height) / 2f;
        setCropRect(left, top, left + width, top + height);
    }

    public float targetAspect(float sourceAspect) {
        if (cropMode == CROP_SQUARE) {
            return 1f;
        }
        if (cropMode == CROP_4_3) {
            return 4f / 3f;
        }
        if (cropMode == CROP_3_4) {
            return 3f / 4f;
        }
        if (cropMode == CROP_16_9) {
            return 16f / 9f;
        }
        if (cropMode == CROP_9_16) {
            return 9f / 16f;
        }
        return sourceAspect;
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
