package com.zhundian.clock;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.View;

public final class SevenSegmentView extends View {
    private static final String[] SEGMENTS = {
            "abcdef", "bc", "abdeg", "abcdg", "bcfg",
            "acdfg", "acdefg", "abc", "abcdefg", "abcdfg"
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String time = "00:00:00.000";
    private float digitWidth;
    private float digitHeight;
    private float gap;
    private float separatorWidth;
    private float displayScale = 1f;
    private int currentSize = 1;

    public SevenSegmentView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        paint.setColor(Color.rgb(155, 255, 103));
        paint.setStyle(Paint.Style.FILL);
        paint.setShadowLayer(dp(5), 0, 0, Color.rgb(72, 221, 54));
        setSize(1);
    }

    public void setSize(int size) {
        currentSize = Math.max(0, Math.min(2, size));
        digitWidth = dp((12 + currentSize * 2) * displayScale);
        digitHeight = dp((22 + currentSize * 4) * displayScale);
        gap = dp(1.4f * displayScale);
        separatorWidth = dp((5 + currentSize) * displayScale);
        requestLayout();
        invalidate();
    }

    public void setDisplayScale(float scale) {
        displayScale = Math.max(.6f, Math.min(2.2f, scale));
        setSize(currentSize);
    }

    public void setTime(String value) {
        if (!time.equals(value)) {
            time = value;
            invalidate();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        float width = 9 * digitWidth + 3 * separatorWidth + 11 * gap + dp(2 * displayScale);
        setMeasuredDimension(resolveSize((int) Math.ceil(width), widthMeasureSpec),
                resolveSize((int) Math.ceil(digitHeight + dp(8 * displayScale)), heightMeasureSpec));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float x = dp(1 * displayScale);
        float y = dp(4 * displayScale);
        for (int i = 0; i < time.length(); i++) {
            char character = time.charAt(i);
            if (character >= '0' && character <= '9') {
                drawDigit(canvas, x, y, SEGMENTS[character - '0']);
                x += digitWidth + gap;
            } else {
                float radius = dp(1.65f * displayScale);
                if (character == ':') {
                    canvas.drawCircle(x + separatorWidth / 2, y + digitHeight * .34f, radius, paint);
                    canvas.drawCircle(x + separatorWidth / 2, y + digitHeight * .69f, radius, paint);
                } else {
                    canvas.drawCircle(x + separatorWidth / 2, y + digitHeight - radius, radius, paint);
                }
                x += separatorWidth + gap;
            }
        }
    }

    private void drawDigit(Canvas canvas, float x, float y, String enabled) {
        float thickness = digitWidth * .24f;
        float middle = y + digitHeight / 2;
        float radius = thickness * .32f;
        if (enabled.indexOf('a') >= 0) segment(canvas, x + thickness / 2, y, x + digitWidth - thickness / 2, y + thickness, radius);
        if (enabled.indexOf('g') >= 0) segment(canvas, x + thickness / 2, middle - thickness / 2, x + digitWidth - thickness / 2, middle + thickness / 2, radius);
        if (enabled.indexOf('d') >= 0) segment(canvas, x + thickness / 2, y + digitHeight - thickness, x + digitWidth - thickness / 2, y + digitHeight, radius);
        if (enabled.indexOf('f') >= 0) segment(canvas, x, y + thickness / 2, x + thickness, middle, radius);
        if (enabled.indexOf('b') >= 0) segment(canvas, x + digitWidth - thickness, y + thickness / 2, x + digitWidth, middle, radius);
        if (enabled.indexOf('e') >= 0) segment(canvas, x, middle, x + thickness, y + digitHeight - thickness / 2, radius);
        if (enabled.indexOf('c') >= 0) segment(canvas, x + digitWidth - thickness, middle, x + digitWidth, y + digitHeight - thickness / 2, radius);
    }

    private void segment(Canvas canvas, float left, float top, float right, float bottom, float radius) {
        canvas.drawRoundRect(left, top, right, bottom, radius, radius, paint);
    }

    private float dp(float value) {
        return value * getResources().getDisplayMetrics().density;
    }
}
