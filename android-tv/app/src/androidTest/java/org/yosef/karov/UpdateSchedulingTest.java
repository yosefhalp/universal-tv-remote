package org.yosef.karov;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.yosef.karov.updates.UpdateEngine;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class UpdateSchedulingTest {
    @Test
    public void verifiedUpdateWaitsForTheFamilySessionToEnd() throws Exception {
        assertTrue(
                android.os.Build.HARDWARE.equals("ranchu")
                        || android.os.Build.HARDWARE.equals("goldfish"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UpdateEngine engine = UpdateEngine.get(context);
        // מכינים עדכון ממתין באמצעות הורדה אמיתית. לא משנים הרשאות ולא נוגעים בכספת הזיווג.
        context.getSharedPreferences("karov-updates", Context.MODE_PRIVATE)
                .edit()
                .putLong("attemptAt", 0)
                .commit();
        engine.automatic(true);
        engine.busy(true);
        CountDownLatch finished = new CountDownLatch(1);
        engine.background(finished::countDown);
        assertTrue(finished.await(90, TimeUnit.SECONDS));
        assertEquals("deferred", engine.info().getString("state"));
        assertTrue(context.getFileStreamPath("karov-update.apk").isFile());
    }
}
