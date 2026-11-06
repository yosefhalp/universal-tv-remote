package org.yosef.karov;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;

import java.util.concurrent.*;

public final class LocalAdbTest {
    @Test
    public void capturesThroughPreviouslyApprovedKey() throws Exception {
        // ההרשאה חייבת להינתן קודם דרך חלון Android הרגיל; הבדיקה אינה משנה הרשאות.
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(
                BuildConfig.DEBUG
                        && (android.os.Build.HARDWARE.equals("ranchu")
                                || android.os.Build.HARDWARE.equals("goldfish")));
        LocalAdb adb = LocalAdb.get(context);
        adb.start();
        for (int i = 0; i < 50 && !adb.ready(); i++) Thread.sleep(200);
        assertTrue(adb.status(), adb.ready());
        CountDownLatch received = new CountDownLatch(1);
        Object[] result = new Object[2];
        adb.capture(
                () -> true,
                (value, error) -> {
                    result[0] = value;
                    result[1] = error;
                    received.countDown();
                });
        assertTrue(received.await(15, TimeUnit.SECONDS));
        assertNull(result[1]);
        JSONObject frame = (JSONObject) result[0];
        assertTrue(frame.getInt("width") > 0);
        assertTrue(frame.getString("jpeg").startsWith("/9j/"));
    }
}
