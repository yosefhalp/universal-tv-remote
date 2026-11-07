package org.yosef.karov.phone;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.*;
import static androidx.test.espresso.matcher.ViewMatchers.*;

import static org.junit.Assert.*;

import androidx.test.rule.ActivityTestRule;

import org.junit.Rule;
import org.junit.Test;

public final class NativeControllerTest {
    @Rule
    public ActivityTestRule<MainActivity> activity = new ActivityTestRule<>(MainActivity.class);

    @Test
    public void hebrewTextTravelsThroughTheNativeDialog() throws Exception {
        // הסטרימר המדומה צריך להציג קודם את InputProbeActivity. נבדקת גם התוצאה שם לאחר הריצה.
        assertTrue(
                android.os.Build.HARDWARE.equals("ranchu")
                        || android.os.Build.HARDWARE.equals("goldfish"));
        long end = android.os.SystemClock.elapsedRealtime() + 20000;
        boolean ready = false;
        while (!ready && android.os.SystemClock.elapsedRealtime() < end) {
            try {
                onView(withText("● מחוברים לטלוויזיה"))
                        .check(
                                androidx.test.espresso.assertion.ViewAssertions.matches(
                                        isDisplayed()));
                ready = true;
            } catch (AssertionError | androidx.test.espresso.NoMatchingViewException waiting) {
                android.os.SystemClock.sleep(250);
            }
        }
        assertTrue("הזיווג השמור חייב להתחבר לפני בדיקת הכתיבה", ready);
        onView(withText("כתיבה בטלוויזיה")).perform(scrollTo(), click());
        onView(withHint("מה לחפש בטלוויזיה?"))
                .perform(replaceText("שלום מהאפליקציה הנייטיבית"), closeSoftKeyboard());
        onView(withText("שליחת טקסט")).perform(click());
        // נותנים לפקודה המוצפנת להגיע לפני שסיום הבדיקה סוגר את Activity.
        android.os.SystemClock.sleep(1200);
    }
}
