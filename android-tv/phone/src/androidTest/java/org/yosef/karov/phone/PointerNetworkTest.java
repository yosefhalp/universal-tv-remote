package org.yosef.karov.phone;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.matcher.ViewMatchers.*;

import static org.junit.Assert.*;

import android.graphics.RectF;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;

import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.rule.ActivityTestRule;

import org.hamcrest.Matcher;
import org.junit.Rule;
import org.junit.Test;

public final class PointerNetworkTest {
    @Rule
    public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class);

    @Test
    public void twoFingerScrollReachesThePairedTv() {
        // נדרש זיווג שמור ו-PointerProbeActivity בסטרימר; מונה הגלילה נבדק שם אחרי הריצה.
        assertTrue(
                android.os.Build.HARDWARE.equals("ranchu")
                        || android.os.Build.HARDWARE.equals("goldfish"));
        long until = SystemClock.elapsedRealtime() + 20000;
        boolean ready = false;
        while (!ready && SystemClock.elapsedRealtime() < until) {
            try {
                onView(withText("● מחוברים לטלוויזיה"))
                        .check(
                                androidx.test.espresso.assertion.ViewAssertions.matches(
                                        isDisplayed()));
                ready = true;
            } catch (AssertionError | androidx.test.espresso.NoMatchingViewException e) {
                SystemClock.sleep(200);
            }
        }
        assertTrue(ready);
        onView(withContentDescription("מגע ישיר")).perform(click());
        onView(org.hamcrest.Matchers.instanceOf(PointerSurface.class))
                .perform(
                        new ViewAction() {
                            public Matcher<View> getConstraints() {
                                return isDisplayed();
                            }

                            public String getDescription() {
                                return "גלילה בשתי אצבעות על תמונת הטלוויזיה";
                            }

                            public void perform(UiController ui, View view) {
                                try {
                                    PointerSurface screen = (PointerSurface) view;
                                    for (int i = 0; screen.getDrawable() == null && i < 40; i++)
                                        ui.loopMainThreadForAtLeast(250);
                                    assertNotNull(
                                            "נדרשת תמונת סטרימר טרייה לפני המחווה",
                                            screen.getDrawable());
                                    RectF b = screen.imageBounds();
                                    float x = b.left + b.width() * 1400 / 1920f,
                                            y = b.top + b.height() * .7f;
                                    int[] origin = new int[2];
                                    view.getLocationOnScreen(origin);
                                    x += origin[0];
                                    y += origin[1];
                                    long start = SystemClock.uptimeMillis();
                                    MotionEvent first =
                                            MotionEvent.obtain(
                                                    start, start, MotionEvent.ACTION_DOWN, x, y, 0);
                                    first.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                                    assertTrue(ui.injectMotionEvent(first));
                                    first.recycle();
                                    ui.loopMainThreadForAtLeast(50);
                                    for (int step = 0; step <= 11; step++) {
                                        int action =
                                                step == 0
                                                        ? MotionEvent.ACTION_POINTER_DOWN
                                                                | (1
                                                                        << MotionEvent
                                                                                .ACTION_POINTER_INDEX_SHIFT)
                                                        : step == 11
                                                                ? MotionEvent.ACTION_POINTER_UP
                                                                        | (1
                                                                                << MotionEvent
                                                                                        .ACTION_POINTER_INDEX_SHIFT)
                                                                : MotionEvent.ACTION_MOVE;
                                        MotionEvent.PointerProperties[] properties =
                                                new MotionEvent.PointerProperties[2];
                                        MotionEvent.PointerCoords[] coords =
                                                new MotionEvent.PointerCoords[2];
                                        for (int i = 0; i < 2; i++) {
                                            properties[i] = new MotionEvent.PointerProperties();
                                            properties[i].id = i;
                                            properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
                                            coords[i] = new MotionEvent.PointerCoords();
                                            coords[i].x = x + i * 40;
                                            coords[i].y =
                                                    y - Math.min(10, step) * b.height() * .03f;
                                            coords[i].pressure = 1;
                                            coords[i].size = 1;
                                        }
                                        MotionEvent e =
                                                MotionEvent.obtain(
                                                        start,
                                                        SystemClock.uptimeMillis(),
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
                                        ui.injectMotionEvent(e);
                                        e.recycle();
                                        ui.loopMainThreadForAtLeast(35);
                                    }
                                    MotionEvent last =
                                            MotionEvent.obtain(
                                                    start,
                                                    SystemClock.uptimeMillis(),
                                                    MotionEvent.ACTION_UP,
                                                    x,
                                                    y - b.height() * .3f,
                                                    0);
                                    last.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);
                                    ui.injectMotionEvent(last);
                                    last.recycle();
                                    ui.loopMainThreadForAtLeast(2000);
                                } catch (
                                        androidx.test.espresso.InjectEventSecurityException error) {
                                    throw new AssertionError(error);
                                }
                            }
                        });
    }
}
