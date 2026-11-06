package org.yosef.karov;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;

public final class SetupTest {
    @Test
    public void configureIsolatedEmulator() throws Exception {
        // כלי הכנה לבדיקת מערכת בלבד; המחלקה נמצאת ב-APK בדיקות ואינה נכללת בהתקנה למשתמש.
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        assertTrue(
                "הכנת הבדיקה מותרת רק באמולטור ובגרסת פיתוח",
                BuildConfig.DEBUG
                        && (android.os.Build.HARDWARE.equals("ranchu")
                                || android.os.Build.HARDWARE.equals("goldfish")));
        String server = InstrumentationRegistry.getArguments().getString("server");
        assertNotNull("יש להעביר כתובת שרת בדיקה", server);
        Vault vault = Vault.open(context);
        vault.revokeAll();
        vault.configure(server, "טלוויזיית בדיקה — Android " + android.os.Build.VERSION.RELEASE);
        vault.put("enrollment", InstrumentationRegistry.getArguments().getString("enrollment", ""));
        vault.enable(true);
        vault.pairing();
        assertTrue(vault.enabled());
        assertEquals(0, vault.controllers().length());
        assertFalse(vault.secretFor("pair").isEmpty());
    }
}
