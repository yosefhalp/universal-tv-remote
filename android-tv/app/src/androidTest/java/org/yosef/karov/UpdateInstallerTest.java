package org.yosef.karov;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.yosef.karov.updates.UpdateEngine;

import java.io.File;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UpdateInstallerTest {
    @Test
    public void televisionManualUpdateSkipsAvailableLocalInstaller() throws Throwable {
        // בדיקת התקנה ידנית באמולטור בלבד, כאשר השרת מציע גרסה גבוהה יותר והרשאת ההתקנה פעילה.
        // אין מתן הרשאות או אישור חלון מערכת מתוך הבדיקה. התקנה מוצלחת עשויה לעצור אותה.
        assertTrue(
                android.os.Build.HARDWARE.equals("ranchu")
                        || android.os.Build.HARDWARE.equals("goldfish"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UpdateEngine engine = UpdateEngine.get(context);
        assertTrue("הבדיקה מיועדת לאפליקציית הטלוויזיה", engine.television());
        assertTrue("יש לאשר התקנת עדכונים במסך ההגדרות לפני הבדיקה", engine.permission());
        AtomicBoolean queriedBridge = new AtomicBoolean(), invokedBridge = new AtomicBoolean();
        engine.localInstaller(
                new UpdateEngine.LocalInstaller() {
                    @Override
                    public boolean available() {
                        queriedBridge.set(true);
                        return true;
                    }

                    @Override
                    public void install(File apk) throws Exception {
                        invokedBridge.set(true);
                        throw new IllegalStateException("התקנה מקומית אינה מותרת בעדכון טלוויזיה");
                    }
                });
        boolean automatic = engine.automatic();
        try {
            engine.automatic(false);
            assertFalse(engine.info().getBoolean("localInstall"));
            assertEquals("system-confirmation", engine.info().getString("installMode"));
            CountDownLatch finished = new CountDownLatch(1);
            assertTrue(engine.request(true, false, finished::countDown));
            assertTrue(finished.await(90, TimeUnit.SECONDS));
            assertFalse("אין לבדוק זמינות התקנה מקומית בטלוויזיה", queriedBridge.get());
            assertFalse("אין למסור APK לחיבור מקומי גם כשהוא זמין", invokedBridge.get());
            assertTrue(
                    engine.info().toString(),
                    java.util.Set.of("installing", "confirmation")
                            .contains(engine.info().getString("state")));
        } finally {
            engine.automatic(automatic);
            engine.localInstaller(null);
        }
    }
}
