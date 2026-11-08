package org.yosef.karov;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.yosef.karov.updates.UpdateEngine;

import java.io.File;
import java.lang.reflect.Field;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class UpdateSchedulingTest {
    @Test
    public void televisionBackgroundOnlyDownloadsEvenWhenInstallIsExplicitlyRequested()
            throws Exception {
        assertTrue(
                android.os.Build.HARDWARE.equals("ranchu")
                        || android.os.Build.HARDWARE.equals("goldfish"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        UpdateEngine engine = UpdateEngine.get(context);
        assertTrue(engine.television());
        assertFalse(
                "תזמון הרקע פעיל בגרסת TV release; גרסת debug מדלגת עליו בכוונה",
                context.getPackageName().endsWith(".debug"));
        PackageInstaller installer = context.getPackageManager().getPackageInstaller();
        assertTrue("יש להכין סביבת בדיקה ללא סשן התקנה קיים", installer.getMySessions().isEmpty());
        assertFalse(engine.info().getBoolean("working"));
        long installedBefore =
                context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0)
                        .getLongVersionCode();
        SharedPreferences prefs =
                context.getSharedPreferences("karov-updates", Context.MODE_PRIVATE);
        boolean automatic = engine.automatic();
        boolean hadAttempt = prefs.contains("attemptAt");
        long attemptAt = prefs.getLong("attemptAt", 0);
        // קוראים רק מצב פרטי של המחלקה שלנו כדי להשיבו בסיום, בלי להוסיף API תפעולי לצורכי בדיקה.
        Field busyField = UpdateEngine.class.getDeclaredField("busy");
        busyField.setAccessible(true);
        boolean busy = busyField.getBoolean(engine);
        Field localField = UpdateEngine.class.getDeclaredField("localInstaller");
        localField.setAccessible(true);
        UpdateEngine.LocalInstaller previousLocal =
                (UpdateEngine.LocalInstaller) localField.get(engine);
        AtomicBoolean queried = new AtomicBoolean(), invoked = new AtomicBoolean();
        engine.localInstaller(
                new UpdateEngine.LocalInstaller() {
                    @Override
                    public boolean available() {
                        queried.set(true);
                        return true;
                    }

                    @Override
                    public void install(File apk) {
                        invoked.set(true);
                        throw new IllegalStateException("התקנת רקע מקומית אינה מותרת בטלוויזיה");
                    }
                });
        try {
            // מכינים עדכון אמיתי חדש יותר, בלי לשנות הרשאות ובלי לגעת בכספת הזיווג.
            prefs.edit().putLong("attemptAt", 0).commit();
            engine.automatic(true);
            engine.busy(true);
            CountDownLatch downloaded = new CountDownLatch(1);
            engine.background(downloaded::countDown);
            assertTrue("הורדת הרקע חייבת להסתיים", downloaded.await(90, TimeUnit.SECONDS));
            assertTrue(
                    "תזמון הרקע חייב לבצע בדיקה ולא לדלג עליה", prefs.getLong("attemptAt", 0) > 0);
            assertDownloadOnly(context, engine, installer, installedBefore, queried, invoked);

            // גם בלי עזרה פעילה ועם automatic=true, דגל install מפורש אינו מאפשר התקנה ברקע.
            engine.busy(false);
            CountDownLatch explicit = new CountDownLatch(1);
            assertTrue(engine.request(true, true, explicit::countDown));
            assertTrue("בקשת הרקע הישירה חייבת להסתיים", explicit.await(90, TimeUnit.SECONDS));
            assertDownloadOnly(context, engine, installer, installedBefore, queried, invoked);
        } finally {
            engine.cancelDownload();
            // מונעים הפעלת עבודה חדשה בעת השבת busy, ואז מחזירים בדיוק את העדפת המשתמש הקודמת.
            engine.automatic(false);
            engine.busy(busy);
            engine.localInstaller(previousLocal);
            engine.automatic(automatic);
            SharedPreferences.Editor restore = prefs.edit();
            if (hadAttempt) restore.putLong("attemptAt", attemptAt);
            else restore.remove("attemptAt");
            restore.commit();
        }
    }

    private static void assertDownloadOnly(
            Context context,
            UpdateEngine engine,
            PackageInstaller installer,
            long installedBefore,
            AtomicBoolean queried,
            AtomicBoolean invoked)
            throws Exception {
        JSONObject info = engine.info();
        assertEquals("ready", info.getString("state"));
        assertFalse(info.getBoolean("working"));
        assertFalse(info.getBoolean("localInstall"));
        assertEquals("system-confirmation", info.getString("installMode"));
        File apk = context.getFileStreamPath("karov-update.apk");
        assertTrue(apk.isFile());
        assertTrue(apk.length() > 0);
        SharedPreferences prefs =
                context.getSharedPreferences("karov-updates", Context.MODE_PRIVATE);
        JSONObject spec = new JSONObject(prefs.getString("spec", "{}"));
        assertTrue(
                "השרת חייב להציע גרסה חדשה יותר, כדי שהבדיקה תוכיח הורדה ללא התקנה",
                spec.getLong("versionCode") > installedBefore);
        assertEquals(
                installedBefore,
                context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0)
                        .getLongVersionCode());
        assertTrue(
                "אין ליצור סשן PackageInstaller מתוך עבודת רקע",
                installer.getMySessions().isEmpty());
        assertFalse("אין לשמור סשן התקנה מתוך עבודת רקע", prefs.contains("session"));
        assertFalse("אין ליצור אסימון אישור להתקנת רקע", prefs.contains("nonce"));
        assertNull(engine.confirmation());
        assertFalse("אין אפילו לבדוק זמינות התקנה מקומית בטלוויזיה", queried.get());
        assertFalse("אין לקרוא למתקין המקומי מתוך עבודת רקע", invoked.get());
    }
}
