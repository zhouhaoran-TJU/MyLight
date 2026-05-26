package com.example.lightroomclone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import com.example.lightroomclone.core.GeometryAdjustments;

final class CropOverlayView extends View {
    interface Listener {
        void onCropChanged(boolean finished);
    }

    private static final int HANDLE_NONE = 0;
    private static final int HANDLE_MOVE = 1;
    private static final int HANDLE_LEFT = 2;
    private static final int HANDLE_TOP = 3;
    private static final int HANDLE_RIGHT = 4;
    private static final int HANDLE_BOTTOM = 5;
    private static final int HANDLE_TOP_LEFT = 6;
    private static final int HANDLE_TOP_RIGHT = 7;
    private static final int HANDLE_BOTTOM_LEFT = 8;
    private static final int HANDLE_BOTTOM_RIGHT = 9;

    private final GeometryAdjustments geometry;
    private final Paint dimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF imageBounds = new RectF();
    private final RectF cropBounds = new RectF();
    private Listener listener;
    private int imageWidth = 1;
    private int imageHeight = 1;
    private int activeHandle = HANDLE_NONE;
    private float lastX;
    private float lastY;

    CropOverlayView(Context context, GeometryAdjustments geometry) {
        super(context);
        this.geometry = geometry;
        setWillNotDraw(false);
        dimPaint.setColor(Color.argb(115, 0, 0, 0));
        borderPaint.setColor(Color.WHITE);
        borderPaint.setStrokeWidth(dp(2));
        borderPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setColor(Color.argb(150, 255, 255, 255));
        gridPaint.setStrokeWidth(dp(1));
        handlePaint.setColor(Color.WHITE);
        handlePaint.setStyle(Paint.Style.FILL);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setImageSize(int width, int height) {
        imageWidth = Math.max(1, width);
        imageHeight = Math.max(1, height);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getVisibility() != VISIBLE) {
            return;
        }
        computeRects();
        canvas.drawRect(imageBounds.left, imageBounds.top, imageBounds.right, cropBounds.top, dimPaint);
        canvas.drawRect(imageBounds.left, cropBounds.bottom, imageBounds.right, imageBounds.bottom, dimPaint);
        canvas.drawRect(imageBounds.left, cropBounds.top, cropBounds.left, cropBounds.bottom, dimPaint);
        canvas.drawRect(cropBounds.right, cropBounds.top, imageBounds.right, cropBounds.bottom, dimPaint);

        for (int i = 1; i <= 2; i++) {
            float x = cropBounds.left + cropBounds.width() * i / 3f;
            float y = cropBounds.top + cropBounds.height() * i / 3f;
            canvas.drawLine(x, cropBounds.top, x, cropBounds.bottom, gridPaint);
            canvas.drawLine(cropBounds.left, y, cropBounds.right, y, gridPaint);
        }
        canvas.drawRect(cropBounds, borderPaint);
        drawHandle(canvas, cropBounds.left, cropBounds.top);
        drawHandle(canvas, cropBounds.right, cropBounds.top);
        drawHandle(canvas, cropBounds.left, cropBounds.bottom);
        drawHandle(canvas, cropBounds.right, cropBounds.bottom);
        drawHandle(canvas, cropBounds.centerX(), cropBounds.top);
        drawHandle(canvas, cropBounds.centerX(), cropBounds.bottom);
        drawHandle(canvas, cropBounds.left, cropBounds.centerY());
        drawHandle(canvas, cropBounds.right, cropBounds.centerY());
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (getVisibility() != VISIBLE) {
            return false;
        }
        computeRects();
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            activeHandle = hitHandle(event.getX(), event.getY());
            if (activeHandle == HANDLE_NONE) {
                return false;
            }
            lastX = event.getX();
            lastY = event.getY();
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE && activeHandle != HANDLE_NONE) {
            updateCrop(event.getX(), event.getY());
            lastX = event.getX();
            lastY = event.getY();
            notifyChanged(false);
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            if (activeHandle != HANDLE_NONE) {
                updateCrop(event.getX(), event.getY());
                notifyChanged(true);
            }
            activeHandle = HANDLE_NONE;
            getParent().requestDisallowInterceptTouchEvent(false);
            return true;
        }
        return true;
    }

    private void updateCrop(float x, float y) {
        float dx = (x - lastX) / Math.max(1f, imageBounds.width());
        float dy = (y - lastY) / Math.max(1f, imageBounds.height());
        float left = geometry.cropLeft;
        float top = geometry.cropTop;
        float right = geometry.cropRight;
        float bottom = geometry.cropBottom;

        if (activeHandle == HANDLE_MOVE) {
            float width = right - left;
            float height = bottom - top;
            left = clamp(left + dx, 0f, 1f - width);
            top = clamp(top + dy, 0f, 1f - height);
            right = left + width;
            bottom = top + height;
        } else if (geometry.cropMode != GeometryAdjustments.CROP_FREE) {
            RectF locked = lockedAspectRect(left, top, right, bottom, dx, dy);
            left = locked.left;
            top = locked.top;
            right = locked.right;
            bottom = locked.bottom;
        } else {
            if (activeHandle == HANDLE_LEFT || activeHandle == HANDLE_TOP_LEFT
                    || activeHandle == HANDLE_BOTTOM_LEFT) {
                left += dx;
            }
            if (activeHandle == HANDLE_RIGHT || activeHandle == HANDLE_TOP_RIGHT
                    || activeHandle == HANDLE_BOTTOM_RIGHT) {
                right += dx;
            }
            if (activeHandle == HANDLE_TOP || activeHandle == HANDLE_TOP_LEFT
                    || activeHandle == HANDLE_TOP_RIGHT) {
                top += dy;
            }
            if (activeHandle == HANDLE_BOTTOM || activeHandle == HANDLE_BOTTOM_LEFT
                    || activeHandle == HANDLE_BOTTOM_RIGHT) {
                bottom += dy;
            }
        }
        geometry.setCropRect(left, top, right, bottom);
        invalidate();
    }

    private RectF lockedAspectRect(float left, float top, float right, float bottom, float dx, float dy) {
        float sourceAspect = imageWidth / (float) Math.max(1, imageHeight);
        float normalizedAspect = geometry.targetAspect(sourceAspect) / sourceAspect;
        if (isCornerHandle(activeHandle)) {
            return lockedCornerRect(left, top, right, bottom, dx, dy, normalizedAspect);
        }
        return lockedEdgeRect(left, top, right, bottom, dx, dy, normalizedAspect);
    }

    private RectF lockedCornerRect(float left, float top, float right, float bottom, float dx, float dy,
            float aspect) {
        float anchorX;
        float anchorY;
        float targetX;
        float targetY;
        if (activeHandle == HANDLE_TOP_LEFT) {
            anchorX = right;
            anchorY = bottom;
            targetX = left + dx;
            targetY = top + dy;
        } else if (activeHandle == HANDLE_TOP_RIGHT) {
            anchorX = left;
            anchorY = bottom;
            targetX = right + dx;
            targetY = top + dy;
        } else if (activeHandle == HANDLE_BOTTOM_LEFT) {
            anchorX = right;
            anchorY = top;
            targetX = left + dx;
            targetY = bottom + dy;
        } else {
            anchorX = left;
            anchorY = top;
            targetX = right + dx;
            targetY = bottom + dy;
        }

        float signX = targetX >= anchorX ? 1f : -1f;
        float signY = targetY >= anchorY ? 1f : -1f;
        float width = Math.abs(targetX - anchorX);
        float height = Math.abs(targetY - anchorY);
        if (width / Math.max(0.001f, height) > aspect) {
            height = width / aspect;
        } else {
            width = height * aspect;
        }
        float maxWidth = signX > 0f ? 1f - anchorX : anchorX;
        float maxHeight = signY > 0f ? 1f - anchorY : anchorY;
        if (width > maxWidth) {
            width = maxWidth;
            height = width / aspect;
        }
        if (height > maxHeight) {
            height = maxHeight;
            width = height * aspect;
        }
        return rectFromCorners(anchorX, anchorY, anchorX + signX * width, anchorY + signY * height);
    }

    private RectF lockedEdgeRect(float left, float top, float right, float bottom, float dx, float dy,
            float aspect) {
        float centerX = (left + right) * 0.5f;
        float centerY = (top + bottom) * 0.5f;
        if (activeHandle == HANDLE_LEFT || activeHandle == HANDLE_RIGHT) {
            if (activeHandle == HANDLE_LEFT) {
                left += dx;
            } else {
                right += dx;
            }
            float width = Math.abs(right - left);
            float height = width / aspect;
            top = centerY - height * 0.5f;
            bottom = centerY + height * 0.5f;
        } else {
            if (activeHandle == HANDLE_TOP) {
                top += dy;
            } else {
                bottom += dy;
            }
            float height = Math.abs(bottom - top);
            float width = height * aspect;
            left = centerX - width * 0.5f;
            right = centerX + width * 0.5f;
        }
        return fitRect(left, top, right, bottom, aspect);
    }

    private RectF fitRect(float left, float top, float right, float bottom, float aspect) {
        float width = Math.min(1f, Math.abs(right - left));
        float height = width / aspect;
        if (height > 1f) {
            height = 1f;
            width = height * aspect;
        }
        float centerX = clamp((left + right) * 0.5f, width * 0.5f, 1f - width * 0.5f);
        float centerY = clamp((top + bottom) * 0.5f, height * 0.5f, 1f - height * 0.5f);
        return new RectF(centerX - width * 0.5f, centerY - height * 0.5f,
                centerX + width * 0.5f, centerY + height * 0.5f);
    }

    private RectF rectFromCorners(float ax, float ay, float bx, float by) {
        return new RectF(Math.min(ax, bx), Math.min(ay, by), Math.max(ax, bx), Math.max(ay, by));
    }

    private boolean isCornerHandle(int handle) {
        return handle == HANDLE_TOP_LEFT || handle == HANDLE_TOP_RIGHT
                || handle == HANDLE_BOTTOM_LEFT || handle == HANDLE_BOTTOM_RIGHT;
    }

    private int hitHandle(float x, float y) {
        float radius = dp(26);
        if (near(x, y, cropBounds.left, cropBounds.top, radius)) {
            return HANDLE_TOP_LEFT;
        }
        if (near(x, y, cropBounds.right, cropBounds.top, radius)) {
            return HANDLE_TOP_RIGHT;
        }
        if (near(x, y, cropBounds.left, cropBounds.bottom, radius)) {
            return HANDLE_BOTTOM_LEFT;
        }
        if (near(x, y, cropBounds.right, cropBounds.bottom, radius)) {
            return HANDLE_BOTTOM_RIGHT;
        }
        if (Math.abs(x - cropBounds.left) <= radius && y >= cropBounds.top && y <= cropBounds.bottom) {
            return HANDLE_LEFT;
        }
        if (Math.abs(x - cropBounds.right) <= radius && y >= cropBounds.top && y <= cropBounds.bottom) {
            return HANDLE_RIGHT;
        }
        if (Math.abs(y - cropBounds.top) <= radius && x >= cropBounds.left && x <= cropBounds.right) {
            return HANDLE_TOP;
        }
        if (Math.abs(y - cropBounds.bottom) <= radius && x >= cropBounds.left && x <= cropBounds.right) {
            return HANDLE_BOTTOM;
        }
        if (cropBounds.contains(x, y)) {
            return HANDLE_MOVE;
        }
        return HANDLE_NONE;
    }

    private void computeRects() {
        float viewWidth = getWidth();
        float viewHeight = getHeight();
        float imageAspect = imageWidth / (float) imageHeight;
        float viewAspect = viewWidth / Math.max(1f, viewHeight);
        float width = viewWidth;
        float height = viewHeight;
        if (viewAspect > imageAspect) {
            width = viewHeight * imageAspect;
        } else {
            height = viewWidth / imageAspect;
        }
        float left = (viewWidth - width) / 2f;
        float top = (viewHeight - height) / 2f;
        imageBounds.set(left, top, left + width, top + height);
        cropBounds.set(
                imageBounds.left + imageBounds.width() * geometry.cropLeft,
                imageBounds.top + imageBounds.height() * geometry.cropTop,
                imageBounds.left + imageBounds.width() * geometry.cropRight,
                imageBounds.top + imageBounds.height() * geometry.cropBottom);
    }

    private void notifyChanged(boolean finished) {
        invalidate();
        if (listener != null) {
            listener.onCropChanged(finished);
        }
    }

    private void drawHandle(Canvas canvas, float x, float y) {
        canvas.drawCircle(x, y, dp(5), handlePaint);
    }

    private boolean near(float x, float y, float targetX, float targetY, float radius) {
        float dx = x - targetX;
        float dy = y - targetY;
        return dx * dx + dy * dy <= radius * radius;
    }

    private float clamp(float value, float min, float max) {
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
