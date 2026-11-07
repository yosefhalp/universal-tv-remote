package org.yosef.karov.phone;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONObject;
import org.junit.Test;
import org.yosef.karov.updates.UpdateEngine;
import org.yosef.karov.updates.UpdateSpec;

import java.io.*;
import java.security.MessageDigest;

public final class UpdateVerificationTest {
    private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();

    private File fixture(String name) throws Exception {
        File file = new File(target.getCacheDir(), "test-update.apk");
        try (InputStream input =
                        InstrumentationRegistry.getInstrumentation()
                                .getContext()
                                .getAssets()
                                .open(name);
                OutputStream output = new FileOutputStream(file)) {
            byte[] bytes = new byte[32768];
            int count;
            while ((count = input.read(bytes)) != -1) output.write(bytes, 0, count);
        }
        return file;
    }

    private JSONObject metadata(File file) throws Exception {
        android.content.pm.PackageInfo packageInfo =
                target.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(), 0);
        assertNotNull(packageInfo);
        long version = packageInfo.getLongVersionCode();
        String name = packageInfo.versionName;
        MessageDigest hash = MessageDigest.getInstance("SHA-256");
        try (InputStream input = new FileInputStream(file)) {
            byte[] bytes = new byte[32768];
            int count;
            while ((count = input.read(bytes)) != -1) hash.update(bytes, 0, count);
        }
        StringBuilder digest = new StringBuilder();
        for (byte value : hash.digest())
            digest.append(String.format(java.util.Locale.ROOT, "%02x", value & 255));
        return new JSONObject()
                .put("applicationId", target.getPackageName())
                .put("versionCode", version)
                .put("versionName", name)
                .put("bytes", file.length())
                .put("minSdk", 28)
                .put("path", "karov-phone-" + name + ".apk")
                .put("sha256", digest.toString());
    }

    @Test
    public void validatesSignedUpdateAndRejectsWrongSignerPackageHashAndDowngrade()
            throws Exception {
        // הקבצים נוצרים במעבדה מחבילות חתומות. לא מתקינים אותם ולא נוגעים בזיווגים השמורים.
        assertTrue(
                android.os.Build.HARDWARE.equals("ranchu")
                        || android.os.Build.HARDWARE.equals("goldfish"));
        File file = fixture("next-phone.apk");
        JSONObject valid = metadata(file);
        UpdateEngine.verify(target, file, new UpdateSpec(valid, target.getPackageName()));
        assertThrows(
                Exception.class,
                () ->
                        UpdateEngine.verify(
                                target,
                                file,
                                new UpdateSpec(
                                        new JSONObject(valid.toString())
                                                .put("sha256", "0".repeat(64)),
                                        target.getPackageName())));
        assertThrows(
                Exception.class,
                () ->
                        UpdateEngine.verify(
                                target,
                                file,
                                new UpdateSpec(
                                        new JSONObject(valid.toString()).put("versionCode", 99),
                                        target.getPackageName())));
        for (String name : new String[] {"wrong-signer.apk", "next-tv.apk", "old-phone.apk"}) {
            File wrong = fixture(name);
            JSONObject declared = metadata(wrong);
            assertThrows(
                    name,
                    Exception.class,
                    () ->
                            UpdateEngine.verify(
                                    target,
                                    wrong,
                                    new UpdateSpec(declared, target.getPackageName())));
        }
        file.delete();
    }
}
