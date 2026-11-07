package org.yosef.karov;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.yosef.karov.updates.UpdateEngine;
import org.yosef.karov.updates.UpdateSpec;

import java.io.*;
import java.security.MessageDigest;

public final class UpdateCompatibilityTest {
    private UpdateSpec metadata(Context context, File file) throws Exception {
        PackageInfo candidate =
                context.getPackageManager()
                        .getPackageArchiveInfo(
                                file.getAbsolutePath(),
                                PackageManager.GET_SIGNATURES
                                        | PackageManager.GET_SIGNING_CERTIFICATES);
        assertNotNull(candidate);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[32768];
            int count;
            while ((count = input.read(bytes)) != -1) digest.update(bytes, 0, count);
        }
        StringBuilder hex = new StringBuilder();
        for (byte value : digest.digest())
            hex.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return new UpdateSpec(
                new JSONObject()
                        .put("applicationId", context.getPackageName())
                        .put("versionCode", candidate.getLongVersionCode())
                        .put("versionName", candidate.versionName)
                        .put("minSdk", 28)
                        .put("bytes", file.length())
                        .put("sha256", hex.toString())
                        .put("path", "karov-tv-" + candidate.versionName + ".apk"),
                context.getPackageName());
    }

    @Test
    public void verifiesNewApkAndRejectsDifferentSignerOnOlderAndroid() throws Exception {
        // קובצי הבדיקה מועתקים מראש לתיקיית האפליקציה באמולטור בלבד. אין כאן התקנה או שינוי
        // בזיווגים.
        assertTrue(
                android.os.Build.HARDWARE.equals("ranchu")
                        || android.os.Build.HARDWARE.equals("goldfish"));
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        File good = new File(context.getExternalFilesDir(null), "verification.apk");
        File wrong = new File(context.getExternalFilesDir(null), "wrong-signer.apk");
        UpdateEngine.verify(context, good, metadata(context, good));
        assertThrows(
                Exception.class,
                () -> UpdateEngine.verify(context, wrong, metadata(context, wrong)));
    }
}
