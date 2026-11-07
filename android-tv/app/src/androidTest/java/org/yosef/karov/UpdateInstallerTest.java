package org.yosef.karov;

import static org.junit.Assert.*;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.File;
import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;
import org.yosef.karov.updates.UpdateEngine;

public final class UpdateInstallerTest {
  @Test
  public void failedLocalBridgeFallsBackToSystemInstallerOnEmulator() throws Throwable {
    // בדיקת התקנה ידנית באמולטור בלבד, כאשר השרת מציע גרסה גבוהה יותר והרשאת ההתקנה פעילה.
    // אין מתן הרשאות או אישור חלון מערכת מתוך הבדיקה. התקנה מוצלחת עשויה לעצור אותה.
    assertTrue(
        android.os.Build.HARDWARE.equals("ranchu") || android.os.Build.HARDWARE.equals("goldfish"));
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    UpdateEngine engine = UpdateEngine.get(context);
    assertTrue("יש לאשר התקנת עדכונים במסך ההגדרות לפני הבדיקה", engine.permission());
    AtomicBoolean failedBridge = new AtomicBoolean();
    engine.localInstaller(
        new UpdateEngine.LocalInstaller() {
          @Override
          public boolean available() {
            return true;
          }

          @Override
          public void install(File apk) throws Exception {
            failedBridge.set(true);
            throw new IOException("כשל חיבור מקומי יזום בבדיקת האמולטור");
          }
        });
    boolean automatic = engine.automatic();
    try {
      engine.automatic(false);
      CountDownLatch finished = new CountDownLatch(1);
      assertTrue(engine.request(true, false, finished::countDown));
      assertTrue(finished.await(90, TimeUnit.SECONDS));
      assertTrue("מסלול החיבור המקומי צריך להיכשל לפני המעבר למתקין", failedBridge.get());
      assertTrue(
          engine.info().toString(),
          java.util.Set.of("installing", "confirmation", "current")
              .contains(engine.info().getString("state")));
    } finally {
      engine.automatic(automatic);
      engine.localInstaller(null);
    }
  }
}
