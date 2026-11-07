package org.yosef.karov.phone;

import android.graphics.*;
import android.graphics.drawable.Drawable;

// סמלים וקטוריים באותה שפה חזותית, בלי תלות בגופן או באימוג'י של היצרן.
public final class RemoteIcon extends Drawable {
    private final String name;
    private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);

    public RemoteIcon(String name, int color) {
        this.name = name;
        p.setColor(color);
        p.setStrokeWidth(1.8f);
        p.setStrokeCap(Paint.Cap.ROUND);
        p.setStrokeJoin(Paint.Join.ROUND);
    }

    private void line(Canvas c, float... v) {
        Path path = new Path();
        path.moveTo(v[0], v[1]);
        for (int i = 2; i < v.length; i += 2) path.lineTo(v[i], v[i + 1]);
        c.drawPath(path, p);
    }

    @Override
    public void draw(Canvas c) {
        c.save();
        Rect b = getBounds();
        float size = Math.min(b.width(), b.height());
        c.translate(b.left + (b.width() - size) / 2, b.top + (b.height() - size) / 2);
        c.scale(size / 24f, size / 24f);
        p.setStyle(Paint.Style.STROKE);
        switch (name) {
            case "home" -> {
                line(c, 3, 11, 12, 3, 21, 11);
                line(c, 6, 10, 6, 21, 10, 21, 10, 15, 14, 15, 14, 21, 18, 21, 18, 10);
            }
            case "back" -> {
                line(c, 10, 5, 3, 12, 10, 19);
                line(c, 3, 12, 21, 12);
            }
            case "up" -> line(c, 5, 15, 12, 8, 19, 15);
            case "down" -> line(c, 5, 9, 12, 16, 19, 9);
            case "left" -> line(c, 15, 5, 8, 12, 15, 19);
            case "right" -> line(c, 9, 5, 16, 12, 9, 19);
            case "ok" -> line(c, 5, 12, 10, 17, 20, 7);
            case "more" -> {
                p.setStyle(Paint.Style.FILL);
                for (int x = 5; x <= 19; x += 7) c.drawCircle(x, 12, 1.6f, p);
            }
            case "close" -> {
                line(c, 6, 6, 18, 18);
                line(c, 18, 6, 6, 18);
            }
            case "expand" -> {
                line(c, 9, 3, 3, 3, 3, 9);
                line(c, 15, 3, 21, 3, 21, 9);
                line(c, 3, 15, 3, 21, 9, 21);
                line(c, 21, 15, 21, 21, 15, 21);
            }
            case "pause" -> {
                line(c, 8, 5, 8, 19);
                line(c, 16, 5, 16, 19);
            }
            case "play" -> line(c, 7, 4, 20, 12, 7, 20, 7, 4);
            case "playPause" -> {
                line(c, 3, 5, 13, 12, 3, 19, 3, 5);
                line(c, 17, 5, 17, 19);
                line(c, 21, 5, 21, 19);
            }
            case "rewind" -> {
                line(c, 11, 5, 2, 12, 11, 19, 11, 5);
                line(c, 22, 5, 13, 12, 22, 19, 22, 5);
            }
            case "forward" -> {
                line(c, 2, 5, 11, 12, 2, 19, 2, 5);
                line(c, 13, 5, 22, 12, 13, 19, 13, 5);
            }
            case "apps" -> {
                for (int x = 4; x <= 14; x += 10)
                    for (int y = 4; y <= 14; y += 10) c.drawRoundRect(x, y, x + 6, y + 6, 1, 1, p);
            }
            case "keyboard" -> {
                c.drawRoundRect(2, 5, 22, 19, 2, 2, p);
                for (int x = 6; x <= 18; x += 4) {
                    c.drawPoint(x, 9, p);
                    c.drawPoint(x, 12, p);
                }
                line(c, 7, 16, 17, 16);
            }
            case "touch" -> {
                line(
                        c, 9, 16, 9, 7, 10, 6, 12, 6, 13, 7, 13, 13, 16, 12, 20, 15, 19, 21, 10, 21,
                        5, 16, 6, 14, 9, 16);
                line(c, 6, 3, 4, 5);
                line(c, 11, 1, 11, 3);
                line(c, 16, 3, 18, 5);
            }
            case "pad" -> {
                c.drawRoundRect(3, 3, 21, 21, 3, 3, p);
                line(c, 3, 16, 21, 16);
                line(c, 12, 16, 12, 21);
            }
            case "click" -> {
                line(c, 6, 3, 6, 19, 11, 14, 16, 22, 19, 20, 14, 12, 21, 12, 6, 3);
            }
            case "drag" -> {
                line(c, 4, 8, 4, 3, 9, 3);
                line(c, 4, 3, 11, 10);
                line(c, 15, 21, 20, 21, 20, 16);
                line(c, 13, 14, 20, 21);
            }
            case "scrollUp" -> {
                line(c, 8, 12, 12, 8, 16, 12);
                line(c, 12, 8, 12, 20);
                line(c, 5, 3, 19, 3);
            }
            case "scrollDown" -> {
                line(c, 8, 12, 12, 16, 16, 12);
                line(c, 12, 4, 12, 16);
                line(c, 5, 21, 19, 21);
            }
            case "minus" -> line(c, 5, 12, 19, 12);
            case "plus" -> {
                line(c, 5, 12, 19, 12);
                line(c, 12, 5, 12, 19);
            }
            case "mute" -> {
                line(c, 3, 9, 7, 9, 12, 5, 12, 19, 7, 15, 3, 15, 3, 9);
                line(c, 16, 9, 22, 15);
                line(c, 22, 9, 16, 15);
            }
            case "settings" -> {
                c.drawCircle(12, 12, 4, p);
                for (int i = 0; i < 8; i++) {
                    c.save();
                    c.rotate(i * 45, 12, 12);
                    line(c, 12, 2, 12, 5);
                    c.restore();
                }
            }
            default -> c.drawCircle(12, 12, 8, p);
        }
        c.restore();
    }

    @Override
    public void setAlpha(int alpha) {
        p.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        p.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
