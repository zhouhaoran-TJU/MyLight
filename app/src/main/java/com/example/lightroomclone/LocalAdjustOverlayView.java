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
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    LocalAdjustOverlayView(Context context, ColorAdjustments adjustments) {
        super(context);
        this.adjustments = adjustments;
        strokePaint.setStyle(Paint.Style.STROKE);
        strokePaint.setStrokeWidth(dp(2));
        fillPaint.setStyle(Paint.Style.FILL);
        labelPaint.setTextSize(dp(12));
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFakeBoldText(true);
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
            strokePaint.setColor(active ? Color.rgb(90, 230, 190) : Color.rgb(89, 199, 255));
            strokePaint.setAlpha(active ? 235 : 150);
            strokePaint.setShadowLayer(dp(8), 0, 0, strokePaint.getColor());
            canvas.drawCircle(cx, cy, radius, strokePaint);
            strokePaint.setAlpha(active ? 150 : 90);
            canvas.drawCircle(cx, cy, radius * Math.max(0.1f, 1f - feather), strokePaint);
            strokePaint.clearShadowLayer();
            fillPaint.setColor(active ? Color.rgb(90, 230, 190) : Color.WHITE);
            fillPaint.setAlpha(active ? 230 : 150);
            canvas.drawCircle(cx, cy, dp(active ? 6 : 4), fillPaint);
            float labelX = Math.min(width - dp(16), Math.max(dp(16), cx + dp(18)));
            float labelY = Math.max(dp(18), cy - dp(14));
            fillPaint.setColor(Color.argb(active ? 230 : 170, 6, 11, 18));
            canvas.drawCircle(labelX, labelY - dp(4), dp(11), fillPaint);
            labelPaint.setColor(active ? Color.WHITE : Color.rgb(210, 225, 240));
            canvas.drawText(String.valueOf(i + 1), labelX, labelY, labelPaint);
        }
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
