package com.example.lightroomclone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;

import com.example.lightroomclone.core.ToneCurve;

final class CurveView extends View {
    interface Listener {
        void onCurveChanged(boolean finished);
    }

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint curvePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint activePointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private ToneCurve curve;
    private Listener listener;
    private int activePoint = -1;

    CurveView(Context context, ToneCurve curve) {
        super(context);
        this.curve = curve;
        setBackgroundColor(Color.rgb(28, 31, 38));
        gridPaint.setColor(Color.rgb(62, 68, 78));
        gridPaint.setStrokeWidth(1f);
        curvePaint.setColor(Color.rgb(95, 179, 243));
        curvePaint.setStrokeWidth(dp(2));
        curvePaint.setStyle(Paint.Style.STROKE);
        curvePaint.setStrokeCap(Paint.Cap.ROUND);
        curvePaint.setStrokeJoin(Paint.Join.ROUND);
        pointPaint.setColor(Color.WHITE);
        pointPaint.setStyle(Paint.Style.FILL);
        activePointPaint.setColor(Color.rgb(95, 179, 243));
        activePointPaint.setStyle(Paint.Style.FILL);
        setMinimumHeight(dp(170));
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    void setCurve(ToneCurve curve) {
        this.curve = curve;
        activePoint = -1;
        invalidate();
    }

    void setCurveColor(int color) {
        curvePaint.setColor(color);
        activePointPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float left = dp(14);
        float top = dp(12);
        float right = getWidth() - dp(14);
        float bottom = getHeight() - dp(12);

        for (int i = 0; i <= 4; i++) {
            float x = left + (right - left) * i / 4f;
            float y = top + (bottom - top) * i / 4f;
            canvas.drawLine(x, top, x, bottom, gridPaint);
            canvas.drawLine(left, y, right, y, gridPaint);
        }

        canvas.drawLine(left, bottom, right, top, gridPaint);
        float previousX = toScreenX(0, left, right);
        float previousY = toScreenY(curve.map(0), top, bottom);
        for (int value = 1; value <= 255; value++) {
            float nextX = toScreenX(value, left, right);
            float nextY = toScreenY(curve.map(value), top, bottom);
            canvas.drawLine(previousX, previousY, nextX, nextY, curvePaint);
            previousX = nextX;
            previousY = nextY;
        }

        for (int i = 0; i < curve.pointCount(); i++) {
            Paint paint = i == activePoint ? activePointPaint : pointPaint;
            canvas.drawCircle(toScreenX(curve.getX(i), left, right),
                    toScreenY(curve.getY(i), top, bottom), dp(i == activePoint ? 8 : 7), paint);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float left = dp(14);
        float top = dp(12);
        float right = getWidth() - dp(14);
        float bottom = getHeight() - dp(12);

        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            getParent().requestDisallowInterceptTouchEvent(true);
            int valueX = toCurveX(event.getX(), left, right);
            int valueY = toCurveY(event.getY(), top, bottom);
            int nearest = nearestPoint(event.getX(), event.getY(), left, top, right, bottom);
            if (nearest >= 0) {
                activePoint = nearest;
            } else {
                activePoint = curve.addPoint(valueX, valueY);
            }
            updatePoint(event.getX(), event.getY(), left, top, right, bottom, false);
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_MOVE && activePoint >= 0) {
            updatePoint(event.getX(), event.getY(), left, top, right, bottom, false);
            return true;
        }
        if (event.getAction() == MotionEvent.ACTION_UP || event.getAction() == MotionEvent.ACTION_CANCEL) {
            if (activePoint >= 0) {
                updatePoint(event.getX(), event.getY(), left, top, right, bottom, true);
            }
            activePoint = -1;
            getParent().requestDisallowInterceptTouchEvent(false);
            invalidate();
            return true;
        }
        return true;
    }

    private void updatePoint(float touchX, float touchY, float left, float top, float right, float bottom,
            boolean finished) {
        curve.setPoint(activePoint, toCurveX(touchX, left, right), toCurveY(touchY, top, bottom));
        invalidate();
        if (listener != null) {
            listener.onCurveChanged(finished);
        }
    }

    private int nearestPoint(float touchX, float touchY, float left, float top, float right, float bottom) {
        int best = -1;
        float bestDistance = dp(20) * dp(20);
        for (int i = 0; i < curve.pointCount(); i++) {
            float dx = touchX - toScreenX(curve.getX(i), left, right);
            float dy = touchY - toScreenY(curve.getY(i), top, bottom);
            float distance = dx * dx + dy * dy;
            if (distance < bestDistance) {
                bestDistance = distance;
                best = i;
            }
        }
        return best;
    }

    private int toCurveX(float screenX, float left, float right) {
        float normalized = (screenX - left) / Math.max(1f, right - left);
        return clamp(Math.round(normalized * 255f));
    }

    private int toCurveY(float screenY, float top, float bottom) {
        float normalized = 1f - (screenY - top) / Math.max(1f, bottom - top);
        return clamp(Math.round(normalized * 255f));
    }

    private float toScreenX(int value, float left, float right) {
        return left + (right - left) * value / 255f;
    }

    private float toScreenY(int value, float top, float bottom) {
        return bottom - (bottom - top) * value / 255f;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private static int clamp(int value) {
        if (value < 0) {
            return 0;
        }
        if (value > 255) {
            return 255;
        }
        return value;
    }
}
