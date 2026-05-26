package com.example.lightroomclone;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

import com.example.lightroomclone.core.ColorAdjustments;

final class LocalAdjustOverlayView extends View {
    private final ColorAdjustments adjustments;
    private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    LocalAdjustOverlayView(Context context, ColorAdjustments adjustments) {
        super(context);
        this.adjustments = adjustments;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(2));
        fillPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int count = adjustments.localCount > 0 ? adjustments.localCount
                : (adjustments.localEnabled > 0.5f ? 1 : 0);
        if (count <= 0) {
            return;
        }
        int width = getWidth();
        int height = getHeight();
        float base = Math.min(width, height);
        for (int i = 0; i < count; i++) {
            boolean active = i == adjustments.activeLocalIndex;
            float cx = (adjustments.localCount > 0 ? adjustments.localXs[i] : adjustments.localX) * width;
            float cy = (adjustments.localCount > 0 ? adjustments.localYs[i] : adjustments.localY) * height;
            float radius = (adjustments.localCount > 0 ? adjustments.localRadii[i] : adjustments.localRadius) * base;
            float feather = (adjustments.localCount > 0 ? adjustments.localFeathers[i] : adjustments.localFeather);
            strokePaint.setColor(active ? Color.rgb(92, 200, 255) : Color.argb(190, 238, 244, 255));
            strokePaint.setAlpha(active ? 235 : 150);
            canvas.drawCircle(cx, cy, radius, strokePaint);
            strokePaint.setAlpha(active ? 150 : 90);
            canvas.drawCircle(cx, cy, radius * Math.max(0.1f, 1f - feather), strokePaint);
            fillPaint.setColor(active ? Color.rgb(92, 200, 255) : Color.WHITE);
            fillPaint.setAlpha(active ? 230 : 150);
            canvas.drawCircle(cx, cy, dp(active ? 6 : 4), fillPaint);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
