package com.deviceagent.app;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;

/** Line icons drawn on a 24-unit grid, so they stay crisp at any density and take any tint. */
final class AppIcons extends Drawable {
    static final int LIVE = 0, ACTIVITY = 1, ASSISTANTS = 2, SETTINGS = 3, CHEVRON = 4, SEARCH = 5;

    private static final float[] SLIDER_Y = {6.5f, 12f, 17.5f}, SLIDER_X = {15.5f, 8.5f, 13.5f};
    private final int kind;
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF oval = new RectF();
    private boolean filled;

    AppIcons(int kind, int color) {
        this.kind = kind;
        stroke.setStyle(Paint.Style.STROKE); stroke.setStrokeCap(Paint.Cap.ROUND); stroke.setStrokeJoin(Paint.Join.ROUND);
        fill.setStyle(Paint.Style.FILL);
        setColor(color);
    }

    void setColor(int color) { stroke.setColor(color); fill.setColor(color); invalidateSelf(); }

    /** Active tab icons draw solid where the shape allows it. */
    void setFilled(boolean value) { filled = value; invalidateSelf(); }

    @Override public void draw(Canvas canvas) {
        float size = Math.min(getBounds().width(), getBounds().height());
        float u = size / 24f;
        canvas.save();
        canvas.translate(getBounds().left + (getBounds().width() - size) / 2f, getBounds().top + (getBounds().height() - size) / 2f);
        canvas.scale(u, u);
        stroke.setStrokeWidth(1.8f);
        path.reset();
        switch (kind) {
            case LIVE:
                // A dot with two waves on each side: something is on air.
                canvas.drawCircle(12, 12, 2.4f, fill);
                arc(canvas, 12, 12, 6f, 135, 90); arc(canvas, 12, 12, 6f, -45, 90);
                arc(canvas, 12, 12, 9.6f, 140, 80); arc(canvas, 12, 12, 9.6f, -40, 80);
                break;
            case ACTIVITY:
                // A pulse trace.
                path.moveTo(2.5f, 12.5f); path.lineTo(7f, 12.5f); path.lineTo(9.5f, 6f);
                path.lineTo(14f, 18.5f); path.lineTo(16.5f, 12.5f); path.lineTo(21.5f, 12.5f);
                canvas.drawPath(path, stroke);
                break;
            case ASSISTANTS:
                // A four-point spark with a small companion spark.
                spark(path, 10.5f, 13f, 8f, 2.2f);
                spark(path, 18.5f, 5.5f, 3.6f, 1.1f);
                canvas.drawPath(path, filled ? fill : stroke);
                break;
            case SETTINGS:
                // Three sliders.
                for (int i = 0; i < 3; i++) {
                    canvas.drawLine(3.5f, SLIDER_Y[i], SLIDER_X[i] - 2.6f, SLIDER_Y[i], stroke);
                    canvas.drawLine(SLIDER_X[i] + 2.6f, SLIDER_Y[i], 20.5f, SLIDER_Y[i], stroke);
                    canvas.drawCircle(SLIDER_X[i], SLIDER_Y[i], 2.6f, filled ? fill : stroke);
                }
                break;
            case CHEVRON:
                path.moveTo(9.5f, 6f); path.lineTo(15.5f, 12f); path.lineTo(9.5f, 18f);
                canvas.drawPath(path, stroke);
                break;
            case SEARCH:
                canvas.drawCircle(10.5f, 10.5f, 6f, stroke);
                canvas.drawLine(15f, 15f, 20f, 20f, stroke);
                break;
            default:
        }
        canvas.restore();
    }

    private void arc(Canvas canvas, float cx, float cy, float r, float start, float sweep) {
        oval.set(cx - r, cy - r, cx + r, cy + r);
        canvas.drawArc(oval, start, sweep, false, stroke);
    }

    private static void spark(Path p, float cx, float cy, float r, float waist) {
        p.moveTo(cx, cy - r);
        p.quadTo(cx + waist * 0.35f, cy - waist * 0.35f, cx + r, cy);
        p.quadTo(cx + waist * 0.35f, cy + waist * 0.35f, cx, cy + r);
        p.quadTo(cx - waist * 0.35f, cy + waist * 0.35f, cx - r, cy);
        p.quadTo(cx - waist * 0.35f, cy - waist * 0.35f, cx, cy - r);
        p.close();
    }

    @Override public void setAlpha(int alpha) { stroke.setAlpha(alpha); fill.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { stroke.setColorFilter(filter); fill.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return -1; }
    @Override public int getIntrinsicHeight() { return -1; }
}
