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
    static final int LIVE = 0, ACTIVITY = 1, ASSISTANTS = 2, SETTINGS = 3, CHEVRON = 4, SEARCH = 5,
            ARROW_LEFT = 6, CHEVRON_LEFT = 7, STOP = 8, KEYBOARD = 9, SEND = 10, BACKSPACE = 11,
            RETURN_KEY = 12, HANDOFF = 13, USER = 14, AGENT = 15, CLOSE = 16, HELP = 17;

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
        stroke.setStrokeWidth(1.85f);
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
            case CHEVRON_LEFT:
                path.moveTo(14.5f, 6f); path.lineTo(8.5f, 12f); path.lineTo(14.5f, 18f);
                canvas.drawPath(path, stroke);
                break;
            case ARROW_LEFT:
                canvas.drawLine(19f, 12f, 5.5f, 12f, stroke);
                path.moveTo(11.5f, 6f); path.lineTo(5.5f, 12f); path.lineTo(11.5f, 18f);
                canvas.drawPath(path, stroke);
                break;
            case SEARCH:
                canvas.drawCircle(10.5f, 10.5f, 6f, stroke);
                canvas.drawLine(15f, 15f, 20f, 20f, stroke);
                break;
            case STOP:
                oval.set(6.5f, 6.5f, 17.5f, 17.5f);
                canvas.drawRoundRect(oval, 2.6f, 2.6f, fill);
                break;
            case KEYBOARD:
                oval.set(2.8f, 6f, 21.2f, 18f);
                canvas.drawRoundRect(oval, 2.5f, 2.5f, stroke);
                canvas.drawCircle(6.8f, 9.8f, 0.9f, fill);
                canvas.drawCircle(10.3f, 9.8f, 0.9f, fill);
                canvas.drawCircle(13.7f, 9.8f, 0.9f, fill);
                canvas.drawCircle(17.2f, 9.8f, 0.9f, fill);
                canvas.drawCircle(8.5f, 12.6f, 0.9f, fill);
                canvas.drawCircle(12f, 12.6f, 0.9f, fill);
                canvas.drawCircle(15.5f, 12.6f, 0.9f, fill);
                canvas.drawLine(8.2f, 15.2f, 15.8f, 15.2f, stroke);
                break;
            case SEND:
                stroke.setStrokeWidth(2.1f);
                canvas.drawLine(12f, 18.5f, 12f, 5.5f, stroke);
                path.moveTo(6.5f, 11f); path.lineTo(12f, 5.5f); path.lineTo(17.5f, 11f);
                canvas.drawPath(path, stroke);
                break;
            case BACKSPACE:
                path.moveTo(9.2f, 6.5f); path.lineTo(20.2f, 6.5f); path.lineTo(20.2f, 17.5f);
                path.lineTo(9.2f, 17.5f); path.lineTo(3.8f, 12f); path.close();
                canvas.drawPath(path, stroke);
                canvas.drawLine(12.3f, 9.8f, 16.5f, 14.2f, stroke);
                canvas.drawLine(16.5f, 9.8f, 12.3f, 14.2f, stroke);
                break;
            case RETURN_KEY:
                path.moveTo(18.5f, 6.5f); path.lineTo(18.5f, 13.5f); path.lineTo(6f, 13.5f);
                canvas.drawPath(path, stroke);
                path.reset();
                path.moveTo(10f, 9.5f); path.lineTo(6f, 13.5f); path.lineTo(10f, 17.5f);
                canvas.drawPath(path, stroke);
                break;
            case HANDOFF:
                // Phone frame with outward arrow to top-right.
                path.moveTo(12.2f, 4.5f); path.lineTo(6.8f, 4.5f);
                path.quadTo(4.8f, 4.5f, 4.8f, 6.5f);
                path.lineTo(4.8f, 17.8f);
                path.quadTo(4.8f, 19.8f, 6.8f, 19.8f);
                path.lineTo(13.2f, 19.8f);
                path.quadTo(15.2f, 19.8f, 15.2f, 17.8f);
                path.lineTo(15.2f, 13.5f);
                canvas.drawPath(path, stroke);
                canvas.drawLine(8.8f, 17.1f, 11.2f, 17.1f, stroke);
                canvas.drawLine(12.5f, 11.5f, 19.5f, 4.5f, stroke);
                path.reset();
                path.moveTo(14.8f, 4.5f); path.lineTo(19.5f, 4.5f); path.lineTo(19.5f, 9.2f);
                canvas.drawPath(path, stroke);
                break;
            case USER:
                canvas.drawCircle(12f, 8.2f, 3.3f, filled ? fill : stroke);
                path.moveTo(5.6f, 19f);
                path.quadTo(5.6f, 14.2f, 12f, 14.2f);
                path.quadTo(18.4f, 14.2f, 18.4f, 19f);
                canvas.drawPath(path, stroke);
                break;
            case AGENT:
                spark(path, 12f, 12f, 7.6f, 2.2f);
                canvas.drawPath(path, filled ? fill : stroke);
                break;
            case CLOSE:
                canvas.drawLine(6.5f, 6.5f, 17.5f, 17.5f, stroke);
                canvas.drawLine(17.5f, 6.5f, 6.5f, 17.5f, stroke);
                break;
            case HELP:
                canvas.drawCircle(12f, 12f, 8.8f, stroke);
                path.moveTo(9.4f, 9.6f);
                path.cubicTo(9.4f, 7.8f, 10.6f, 7f, 12f, 7f);
                path.cubicTo(13.6f, 7f, 14.7f, 8f, 14.7f, 9.4f);
                path.cubicTo(14.7f, 10.7f, 13.6f, 11.3f, 12.5f, 12f);
                path.cubicTo(12f, 12.3f, 12f, 12.8f, 12f, 13.6f);
                canvas.drawPath(path, stroke);
                canvas.drawCircle(12f, 16.5f, 1f, fill);
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
