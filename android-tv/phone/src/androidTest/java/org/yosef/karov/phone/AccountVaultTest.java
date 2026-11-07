package org.yosef.karov.phone;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.platform.app.InstrumentationRegistry;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.yosef.karov.Crypto;

public final class AccountVaultTest {
    @Test
    public void logoutAndUserSwitchPreserveLegacyAndEraseAccountCache() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences =
                context.getSharedPreferences("paired-televisions", Context.MODE_PRIVATE);
        String original = preferences.getString("sealed", null);
        try {
            PhoneVault vault = new PhoneVault(context);
            JSONObject legacy = record("טלוויזיה מקומית");
            JSONObject cloud = record("טלוויזיה בחשבון");
            vault.put(legacy);
            vault.accountSession(session("first"));
            vault.accountDevices(new JSONArray().put(cloud));
            PhoneVault reopened = new PhoneVault(context);
            assertEquals(
                    cloud.getString("secret"),
                    reopened.accountDevice(cloud.getString("device")).getString("secret"));
            assertTrue(reopened.accountDevice(cloud.getString("device")).getBoolean("account"));
            assertFalse(preferences.getString("sealed", "").contains(cloud.getString("secret")));
            assertFalse(
                    preferences
                            .getString("sealed", "")
                            .contains(reopened.accountSession().getString("token")));

            reopened.accountSession(session("second"));
            assertEquals(0, reopened.accountDevices().length());
            reopened.accountDevices(new JSONArray().put(cloud));
            reopened.accountSession(null);
            PhoneVault loggedOut = new PhoneVault(context);
            assertNull(loggedOut.accountSession());
            assertEquals(0, loggedOut.accountDevices().length());
            assertEquals(
                    legacy.getString("secret"),
                    loggedOut.get(legacy.getString("device")).getString("secret"));
        } finally {
            // הבדיקה משחזרת את הכספת המקורית גם בכישלון, בלי למחוק את הזיווגים שהיו במכשיר.
            SharedPreferences.Editor editor = preferences.edit();
            if (original == null) editor.remove("sealed");
            else editor.putString("sealed", original);
            assertTrue(editor.commit());
        }
    }

    @Test
    public void forcedPasswordChangeClearsCloudCredentialsOnly() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences =
                context.getSharedPreferences("paired-televisions", Context.MODE_PRIVATE);
        String original = preferences.getString("sealed", null);
        try {
            PhoneVault vault = new PhoneVault(context);
            JSONObject legacy = record("מקומית");
            vault.put(legacy);
            JSONObject session = session("forced");
            vault.accountSession(session);
            vault.accountDevices(new JSONArray().put(record("ענן")));
            vault.accountUser(session.getJSONObject("user").put("mustChangePassword", true));
            assertEquals(0, vault.accountDevices().length());
            assertNotNull(vault.get(legacy.getString("device")));
            assertNotNull(vault.accountSession());
        } finally {
            SharedPreferences.Editor editor = preferences.edit();
            if (original == null) editor.remove("sealed");
            else editor.putString("sealed", original);
            assertTrue(editor.commit());
        }
    }

    private JSONObject session(String username) throws Exception {
        return new JSONObject()
                .put("token", Crypto.secret())
                .put("expiresAt", System.currentTimeMillis() + 100000)
                .put(
                        "user",
                        new JSONObject()
                                .put("id", Crypto.id())
                                .put("username", username)
                                .put("role", "user")
                                .put("mustChangePassword", false)
                                .put("disabled", false));
    }

    private JSONObject record(String name) throws Exception {
        return new JSONObject()
                .put("device", Crypto.id())
                .put("controller", Crypto.id())
                .put("secret", Crypto.secret())
                .put("name", name);
    }
}
