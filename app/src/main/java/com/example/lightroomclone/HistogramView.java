package com.example.lightroomclone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

final class HistogramView extends View {
    private final Paint backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint lumaPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint redPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint greenPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bluePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int[] luminance = new int[256];
    private final int[] red = new int[256];
    private final int[] green = new int[256];
    private final int[] blue = new int[256];

    HistogramView(Context context) {
        super(context);
        backgroundPaint.setColor(Color.argb(160, 10, 13, 19));
        backgroundPaint.setStyle(Paint.Style.FILL);
        lumaPaint.setColor(Color.argb(170, 238, 243, 249));
        redPaint.setColor(Color.argb(120, 237, 82, 82));
        greenPaint.setColor(Color.argb(120, 91, 190, 117));
        bluePaint.setColor(Color.argb(120, 88, 138, 239));
        setWillNotDraw(false);
    }

    void setHistogram(int[] nextLuminance, int[] nextRed, int[] nextGreen, int[] nextBlue) {
        System.arraycopy(nextLuminance, 0, luminance, 0, luminance.length);
        System.arraycopy(nextRed, 0, red, 0, red.length);
        System.arraycopy(nextGreen, 0, green, 0, green.length);
        System.arraycopy(nextBlue, 0, blue, 0, blue.length);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float radius = dp(8);
        canvas.drawRoundRect(0, 0, getWidth(), getHeight(), radius, radius, backgroundPaint);
        drawBars(canvas, red, redPaint);
        drawBars(canvas, green, greenPaint);
        drawBars(canvas, blue, bluePaint);
        drawBars(canvas, luminance, lumaPaint);
    }

    private void drawBars(Canvas canvas, int[] values, Paint paint) {
        int max = 1;
        for (int value : values) {
            max = Math.max(max, value);
        }
        float width = getWidth();
        float height = getHeight();
        float xScale = width / values.length;
        for (int i = 0; i < values.length; i++) {
            float x = i * xScale;
            float barHeight = height * values[i] / max;
            canvas.drawLine(x, height, x, height - barHeight, paint);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
