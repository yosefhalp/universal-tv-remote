package org.yosef.karov.phone;

import static org.junit.Assert.*;

import android.graphics.Bitmap;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;

import java.util.ArrayList;

// מחוות Android אמיתיות מועברות לרכיב; תוצאת הרשת נבדקת בנפרד מול הסטרימר המדומה.
public final class PointerSurfaceTest {
    private PointerSurface surface;
    private final ArrayList<JSONObject> sent = new ArrayList<>();
    private long time;

    private void setup() throws Exception {
        var context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        surface = new PointerSurface(context, sent::add);
        new FrameLayout(context).addView(surface);
        surface.setImageBitmap(Bitmap.createBitmap(1600, 900, Bitmap.Config.RGB_565));
        surface.layout(0, 0, 1600, 1200);
        surface.geometry(
                new JSONObject()
                        .put("displayWidth", 1920)
                        .put("displayHeight", 1080)
                        .put("rotation", 0));
        surface.allowed(true);
        time = SystemClock.uptimeMillis();
    }

    private void event(int action, float x, float y) {
        MotionEvent event = MotionEvent.obtain(time, time += 80, action, x, y, 0);
        surface.onTouchEvent(event);
        event.recycle();
    }

    private void run(Checked task) {
        InstrumentationRegistry.getInstrumentation()
                .runOnMainSync(() -> {
                    try {
                        setup();
                        task.run();
                    } catch (Exception e) {
                        throw new AssertionError(e);
                    }
                });
    }

    private interface Checked {
        void run() throws Exception;
    }

    @Test
    public void directAndRelativeModesKeepTheirDifferentMeanings() {
        run(() -> {
            RectF b = surface.imageBounds();
            float x = b.left + b.width() * .3f, y = b.top + b.height() * .4f;
            event(MotionEvent.ACTION_DOWN, x, y);
            event(MotionEvent.ACTION_UP, x, y);
            assertEquals(.3, sent.get(0).getJSONArray("points").getJSONArray(0).getDouble(0), .001);
            surface.mode(true);
            sent.clear();
            event(MotionEvent.ACTION_DOWN, x, y);
            event(MotionEvent.ACTION_MOVE, x + 160, y);
            event(MotionEvent.ACTION_UP, x + 160, y);
            assertTrue("הזזת סמן בלבד אינה לחיצה בטלוויזיה", sent.isEmpty());
            surface.clickPointer();
            assertEquals(.4, sent.get(0).getJSONArray("points").getJSONArray(0).getDouble(0), .001);
            sent.clear();
            surface.drag(true);
            event(MotionEvent.ACTION_DOWN, x, y);
            event(MotionEvent.ACTION_MOVE, x + 160, y);
            event(MotionEvent.ACTION_UP, x + 160, y);
            assertEquals("drag", sent.get(0).getString("kind"));
            PointerSurface previous = surface;
            setup();
            sent.clear();
            surface.copyPositionFrom(previous);
            surface.clickPointer();
            assertEquals(.5, sent.get(0).getJSONArray("points").getJSONArray(0).getDouble(0), .001);
        });
    }

    @Test
    public void cancellationAndGeometryChangesDiscardUnsentTouches() {
        run(() -> {
            event(MotionEvent.ACTION_DOWN, 300, 400);
            event(MotionEvent.ACTION_MOVE, 500, 400);
            event(MotionEvent.ACTION_CANCEL, 500, 400);
            event(MotionEvent.ACTION_UP, 500, 400);
            assertTrue(sent.isEmpty());
            event(MotionEvent.ACTION_DOWN, 300, 400);
            surface.geometry(
                    new JSONObject()
                            .put("displayWidth", 1080)
                            .put("displayHeight", 1920)
                            .put("rotation", 1));
            event(MotionEvent.ACTION_UP, 300, 400);
            assertTrue(sent.isEmpty());
            surface.allowed(false);
            event(MotionEvent.ACTION_DOWN, 300, 400);
            event(MotionEvent.ACTION_UP, 300, 400);
            assertTrue(sent.isEmpty());
        });
    }

    @Test
    public void twoFingersScrollWithoutAnExtraTap() {
        run(() -> {
            event(MotionEvent.ACTION_DOWN, 900, 700);
            pair(
                    MotionEvent.ACTION_POINTER_DOWN | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    700);
            pair(MotionEvent.ACTION_MOVE, 500);
            pair(
                    MotionEvent.ACTION_POINTER_UP | (1 << MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                    500);
            event(MotionEvent.ACTION_UP, 900, 500);
            assertEquals(1, sent.size());
            assertEquals("scroll", sent.get(0).getString("kind"));
            assertTrue(sent.get(0).getDouble("dy") > 0);
        });
    }

    private void pair(int action, float y) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[2];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[2];
        for (int i = 0; i < 2; i++) {
            properties[i] = new MotionEvent.PointerProperties();
            properties[i].id = i;
            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords();
            coords[i].x = 900 + i * 100;
            coords[i].y = y;
            coords[i].pressure = 1;
            coords[i].size = 1;
        }
        MotionEvent event =
                MotionEvent.obtain(
                        time,
                        time += 80,
                        action,
                        2,
                        properties,
                        coords,
                        0,
                        0,
                        1,
                        1,
                        0,
                        0,
                        android.view.InputDevice.SOURCE_TOUCHSCREEN,
                        0);
        surface.onTouchEvent(event);
        event.recycle();
    }
}
