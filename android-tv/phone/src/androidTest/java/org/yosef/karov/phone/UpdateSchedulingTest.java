package org.yosef.karov.phone;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.yosef.karov.updates.UpdateEngine;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class UpdateSchedulingTest {
    @Test
    public void backgroundDownloadDefersInstallationDuringHelp() throws Exception {
        assertTrue(
                android.os.Build.HARDWARE.equals("ranchu")
                        || android.os.Build.HARDWARE.equals("goldfish"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UpdateEngine engine = UpdateEngine.get(context);
        // מדמים חלוף מרווח הבדיקה בלבד. ההורדה, חתימת APK והתקנת Android נשארות אמיתיות.
        context.getSharedPreferences("karov-updates", Context.MODE_PRIVATE)
                .edit()
                .putLong("attemptAt", 0)
                .commit();
        engine.automatic(true);
        engine.busy(true);
        CountDownLatch complete = new CountDownLatch(1);
        engine.background(complete::countDown);
        assertTrue(complete.await(90, TimeUnit.SECONDS));
        JSONObject info = engine.info();
        assertEquals("deferred", info.getString("state"));
        assertNotEquals(info.getString("version"), info.getString("latest"));
        assertTrue(context.getFileStreamPath("karov-update.apk").isFile());
        // סיום התהליך משאיר עדכון ממתין. בהפעלה הבאה נבדק שהוא ממשיך בלי פעולת התקנה מהמשתמש.
    }
}
