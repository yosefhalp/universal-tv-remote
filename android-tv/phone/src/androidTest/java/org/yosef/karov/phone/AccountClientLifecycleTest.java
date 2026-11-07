package org.yosef.karov.phone;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;

import androidx.test.platform.app.InstrumentationRegistry;

import okhttp3.OkHttpClient;

import org.junit.Test;

public final class AccountClientLifecycleTest {
    @Test
    public void closingPooledTlsOnMainThreadDoesNotBlockOrCrash() throws Exception {
        var instrumentation = InstrumentationRegistry.getInstrumentation();
        Context original = instrumentation.getTargetContext();
        String name = "account-lifecycle-test-" + System.nanoTime();
        Context isolated =
                new ContextWrapper(original) {
                    @Override
                    public SharedPreferences getSharedPreferences(String ignored, int mode) {
                        return original.getSharedPreferences(name, mode);
                    }
                };
        AccountClient client = new AccountClient(new PhoneVault(isolated));
        try {
            // בקשת קריאה ציבורית יוצרת חיבור TLS אמיתי. הכספת מבודדת ואין שימוש בחשבון או בזיווג
            // שמורים.
            assertTrue(client.request("GET", "/status", null, false).has("setupRequired"));
            var field = AccountClient.class.getDeclaredField("http");
            field.setAccessible(true);
            OkHttpClient http = (OkHttpClient) field.get(client);
            assertTrue(http.connectionPool().connectionCount() > 0);
            instrumentation.runOnMainSync(client::close);
            instrumentation.runOnMainSync(client::close);
            long deadline = android.os.SystemClock.elapsedRealtime() + 5000;
            while (http.connectionPool().connectionCount() > 0
                    && android.os.SystemClock.elapsedRealtime() < deadline)
                android.os.SystemClock.sleep(25);
            assertEquals(0, http.connectionPool().connectionCount());
            assertThrows(Exception.class, () -> client.request("GET", "/status", null, false));
        } finally {
            client.close();
            original.deleteSharedPreferences(name);
        }
    }
}
