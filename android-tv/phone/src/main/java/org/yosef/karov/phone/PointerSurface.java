package org.yosef.karov.phone;

import android.content.Context;
import android.graphics.*;
import android.view.MotionEvent;
import android.widget.ImageView;

import org.json.*;

import java.util.ArrayList;

// אותה תמונה משמשת למגע ישיר או למשטח מגע. הסמן מקומי; מחווה שלמה נשלחת רק בשחרור.
public final class PointerSurface extends ImageView {
    public interface Sender {
        void send(JSONObject request);
    }

    private final Sender sender;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ArrayList<float[]> path = new ArrayList<>();
    private JSONObject geometry;
    private boolean relative, armed, tracking, scrolling, moved, allowed;
    private float x = .5f, y = .5f, lastX, lastY, startX, startY, scrollX, scrollY;
    private long started;

    public PointerSurface(Context context, Sender sender) {
        super(context);
        this.sender = sender;
        setScaleType(ScaleType.FIT_CENTER);
        setFocusable(true);
        setClickable(true);
        setContentDescription("מסך הטלוויזיה. נגיעה ללחיצה, גרירה להזזה ושתי אצבעות לגלילה");
    }

    public void geometry(JSONObject value) {
        String next =
                value.optInt("displayWidth")
                        + ":"
                        + value.optInt("displayHeight")
                        + ":"
                        + value.optInt("rotation", -1);
        if (geometry != null && !next.equals(geometry.optString("key"))) cancel();
        try {
            geometry =
                    new JSONObject()
                            .put("displayWidth", value.getInt("displayWidth"))
                            .put("displayHeight", value.getInt("displayHeight"))
                            .put("rotation", value.getInt("rotation"))
                            .put("key", next);
        } catch (Exception ignored) {
            geometry = null;
            cancel();
        }
    }

    public void allowed(boolean value) {
        allowed = value;
        if (!value) cancel();
        invalidate();
    }

    public void mode(boolean value) {
        relative = value;
        cancel();
        invalidate();
    }

    public void drag(boolean value) {
        armed = value;
        cancel();
        invalidate();
    }

    public void cancel() {
        tracking = false;
        scrolling = false;
        path.clear();
    }

    public void copyPositionFrom(PointerSurface other) {
        if (other == null) return;
        cancel();
        x = other.x;
        y = other.y;
        invalidate();
    }

    private boolean ready() {
        return allowed && geometry != null && getDrawable() != null;
    }

    public RectF imageBounds() {
        if (getDrawable() == null) return new RectF();
        RectF bounds =
                new RectF(
                        0,
                        0,
                        getDrawable().getIntrinsicWidth(),
                        getDrawable().getIntrinsicHeight());
        getImageMatrix().mapRect(bounds);
        bounds.offset(getPaddingLeft(), getPaddingTop());
        return bounds;
    }

    private static float clamp(float value) {
        return Math.max(0, Math.min(1, value));
    }

    private void point(float px, float py, boolean moving) {
        RectF bounds = imageBounds();
        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        if (relative) {
            if (moving) {
                x = clamp(x + (px - lastX) / bounds.width());
                y = clamp(y + (py - lastY) / bounds.height());
            }
        } else {
            x = clamp((px - bounds.left) / bounds.width());
            y = clamp((py - bounds.top) / bounds.height());
        }
        lastX = px;
        lastY = py;
        invalidate();
    }

    private void record() {
        if (path.size() >= 64) path.remove(1);
        path.add(new float[] {x, y});
    }

    private void send(String kind, ArrayList<float[]> points, int duration, float dx, float dy) {
        if (!ready()) return;
        try {
            JSONArray values = new JSONArray();
            for (float[] point : points) values.put(new JSONArray().put(point[0]).put(point[1]));
            JSONObject request =
                    new JSONObject(geometry.toString())
                            .put("kind", kind)
                            .put("points", values)
                            .put("duration", Math.max(40, Math.min(1500, duration)))
                            .put("dx", dx)
                            .put("dy", dy);
            request.remove("key");
            sender.send(request);
        } catch (Exception ignored) {
            cancel();
        }
    }

    public void clickPointer() {
        ArrayList<float[]> points = new ArrayList<>();
        points.add(new float[] {x, y});
        send("tap", points, 60, 0, 0);
    }

    public void scrollPointer(float dx, float dy) {
        ArrayList<float[]> points = new ArrayList<>();
        points.add(new float[] {x, y});
        send("scroll", points, 300, Math.max(-1, Math.min(1, dx)), Math.max(-1, Math.min(1, dy)));
    }

    @Override
    public boolean performClick() {
        super.performClick();
        clickPointer();
        return true;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!ready()) return false;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_CANCEL) {
            cancel();
            return true;
        }
        if (action == MotionEvent.ACTION_DOWN) {
            if (!imageBounds().contains(event.getX(), event.getY())) return false;
            cancel();
            tracking = true;
            moved = false;
            started = event.getEventTime();
            startX = event.getX();
            startY = event.getY();
            point(startX, startY, false);
            record();
            getParent().requestDisallowInterceptTouchEvent(true);
            return true;
        }
        if (!tracking) return true;
        if (action == MotionEvent.ACTION_POINTER_DOWN && event.getPointerCount() == 2) {
            scrolling = true;
            path.clear();
            scrollX = scrollY = 0;
            lastX = (event.getX(0) + event.getX(1)) / 2;
            lastY = (event.getY(0) + event.getY(1)) / 2;
            return true;
        }
        if (action == MotionEvent.ACTION_MOVE) {
            if (scrolling && event.getPointerCount() >= 2) {
                float cx = (event.getX(0) + event.getX(1)) / 2,
                        cy = (event.getY(0) + event.getY(1)) / 2;
                RectF bounds = imageBounds();
                scrollX -= (cx - lastX) / Math.max(1, bounds.width());
                scrollY -= (cy - lastY) / Math.max(1, bounds.height());
                lastX = cx;
                lastY = cy;
            } else if (!scrolling) {
                if (Math.hypot(event.getX() - startX, event.getY() - startY)
                        > 6 * getResources().getDisplayMetrics().density) moved = true;
                point(event.getX(), event.getY(), true);
                if (!relative || armed) record();
            }
            return true;
        }
        if (action == MotionEvent.ACTION_POINTER_UP && scrolling) {
            if (Math.abs(scrollX) + Math.abs(scrollY) > .015f)
                scrollPointer(scrollX * 2, scrollY * 2);
            cancel();
            return true;
        }
        if (action == MotionEvent.ACTION_UP) {
            if (!scrolling) {
                point(event.getX(), event.getY(), true);
                if (!relative || armed) record();
                int duration = (int) Math.min(1500, event.getEventTime() - started);
                if (!moved) {
                    ArrayList<float[]> tap = new ArrayList<>();
                    tap.add(new float[] {x, y});
                    send("tap", tap, duration >= 500 ? 650 : 60, 0, 0);
                } else if (!relative || armed)
                    send("drag", new ArrayList<>(path), Math.max(100, duration), 0, 0);
            }
            cancel();
            return true;
        }
        return true;
    }

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        if (ready() && event.getActionMasked() == MotionEvent.ACTION_SCROLL) {
            scrollPointer(
                    -event.getAxisValue(MotionEvent.AXIS_HSCROLL) * .5f,
                    -event.getAxisValue(MotionEvent.AXIS_VSCROLL) * .5f);
            return true;
        }
        return super.onGenericMotionEvent(event);
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!ready()) return;
        RectF bounds = imageBounds();
        float px = bounds.left + x * bounds.width(), py = bounds.top + y * bounds.height();
        float density = getResources().getDisplayMetrics().density;
        canvas.save();
        canvas.clipRect(bounds);
        canvas.translate(px, py);
        canvas.scale(density, density);
        Path cursor = new Path();
        cursor.moveTo(0, 0);
        cursor.lineTo(0, 22);
        cursor.lineTo(6, 16);
        cursor.lineTo(11, 25);
        cursor.lineTo(15, 23);
        cursor.lineTo(10, 14);
        cursor.lineTo(19, 14);
        cursor.close();
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2);
        paint.setColor(0xff111216);
        canvas.drawPath(cursor, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(armed ? 0xff6ca9ff : Color.WHITE);
        canvas.drawPath(cursor, paint);
        canvas.restore();
    }
}
