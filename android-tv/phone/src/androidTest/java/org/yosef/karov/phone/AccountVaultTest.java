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
    public void staleSyncSnapshotCannotSendPrepareUnderNewAccount() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences =
                context.getSharedPreferences("paired-televisions", Context.MODE_PRIVATE);
        String original = preferences.getString("sealed", null);
        AccountClient client = null;
        try {
            PhoneVault first = new PhoneVault(context);
            JSONObject accountA = session("snapshot-a"), accountB = session("snapshot-b");
            first.accountSession(accountA);
            PhoneVault.AccountSnapshot intent = first.accountSnapshot();
            PhoneVault second = new PhoneVault(context);
            second.accountSession(accountB);
            assertTrue(accountA.getString("token").equals(intent.session.getString("token")));
            java.util.concurrent.atomic.AtomicInteger requests =
                    new java.util.concurrent.atomic.AtomicInteger();
            okhttp3.OkHttpClient http =
                    new okhttp3.OkHttpClient.Builder()
                            .addInterceptor(chain -> {
                                requests.incrementAndGet();
                                throw new java.io.IOException("UNEXPECTED_TEST_TRAFFIC");
                            })
                            .build();
            client = new AccountClient(first, http);
            AccountClient active = client;
            expectChanged(() -> active.prepare(Crypto.id(), "טלוויזיה מקומית", intent));
            assertEquals("אין לשלוח פעולת סנכרון ישנה בשם החשבון החדש", 0, requests.get());
            assertTrue(
                    accountB.getString("token").equals(second.accountSession().getString("token")));
        } finally {
            if (client != null) client.close();
            SharedPreferences.Editor editor = preferences.edit();
            if (original == null) editor.remove("sealed");
            else editor.putString("sealed", original);
            assertTrue(editor.commit());
        }
    }

    @Test
    public void accountSyncWaitsForConfirmedServerDeviceBeforeSuccess() throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences =
                context.getSharedPreferences("paired-televisions", Context.MODE_PRIVATE);
        String original = preferences.getString("sealed", null);
        AccountClient client = null;
        try {
            PhoneVault vault = new PhoneVault(context);
            JSONObject session = session("poll"), device = record("אישור מאוחר");
            vault.accountSession(session);
            String me = new JSONObject().put("user", session.getJSONObject("user")).toString();
            String linked = new JSONObject().put("devices", new JSONArray().put(device)).toString();
            java.util.concurrent.atomic.AtomicInteger lists =
                    new java.util.concurrent.atomic.AtomicInteger();
            okhttp3.OkHttpClient http =
                    new okhttp3.OkHttpClient.Builder()
                            .addInterceptor(chain -> {
                                boolean listing =
                                        chain.request().url().encodedPath().endsWith("/devices");
                                String body =
                                        listing
                                                ? (lists.incrementAndGet() == 1
                                                        ? "{\"devices\":[]}"
                                                        : linked)
                                                : me;
                                return new okhttp3.Response.Builder()
                                        .request(chain.request())
                                        .protocol(okhttp3.Protocol.HTTP_1_1)
                                        .code(200)
                                        .message("test")
                                        .body(
                                                okhttp3.ResponseBody.create(
                                                        okhttp3.MediaType.parse("application/json"),
                                                        body))
                                        .build();
                            })
                            .build();
            client = new AccountClient(vault, http);
            JSONObject confirmed =
                    client.awaitDevice(
                            device.getString("device"),
                            session.getString("token"),
                            session.getJSONObject("user").getString("id"),
                            () -> true);
            assertTrue("רשימה ריקה ראשונה אינה אישור סנכרון", lists.get() >= 2);
            assertEquals(device.getString("device"), confirmed.getString("device"));
            assertNotNull(vault.accountDevice(device.getString("device")));
        } finally {
            if (client != null) client.close();
            SharedPreferences.Editor editor = preferences.edit();
            if (original == null) editor.remove("sealed");
            else editor.putString("sealed", original);
            assertTrue(editor.commit());
        }
    }

    @Test
    public void staleActivityCannotOverwriteNewAccountOrEraseItOnLateUnauthorized()
            throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences =
                context.getSharedPreferences("paired-televisions", Context.MODE_PRIVATE);
        String original = preferences.getString("sealed", null);
        try {
            PhoneVault older = new PhoneVault(context);
            JSONObject first = session("first"), second = session("second");
            older.accountSession(first);
            String revision = older.accountRevision();
            PhoneVault newer = new PhoneVault(context);
            newer.accountSession(second);
            JSONObject cloud = record("ענן בחשבון החדש");
            newer.accountDevices(new JSONArray().put(cloud));

            expectChanged(() -> older.accountUser(revision, first.getJSONObject("user")));
            expectChanged(
                    () -> older.accountDevices(revision, new JSONArray().put(record("ענן ישן"))));
            expectChanged(() -> older.accountSession(revision, null));
            expectChanged(() -> older.accountSession(revision, first));
            JSONObject local = record("זיווג ממסך ישן");
            older.put(local);
            older.last(local.getString("device"));
            PhoneVault checked = new PhoneVault(context);
            assertTrue(
                    second.getString("token").equals(checked.accountSession().getString("token")));
            assertNotNull(checked.accountDevice(cloud.getString("device")));
            assertNotNull(checked.get(local.getString("device")));
            assertEquals(local.getString("device"), checked.last());

            checked.accountSession(null);
            String guestRevision = checked.accountRevision();
            newer.accountSession(first);
            newer.accountSession(null);
            expectChanged(() -> checked.accountSession(guestRevision, second));
            assertNull(checked.accountSession());
        } finally {
            SharedPreferences.Editor editor = preferences.edit();
            if (original == null) editor.remove("sealed");
            else editor.putString("sealed", original);
            assertTrue(editor.commit());
        }
    }

    @Test
    public void closedActivityCannotCommitLateSuccessfulLogin() throws Exception {
        lateResponseDoesNotReplaceNewSession(true);
    }

    @Test
    public void lateUnauthorizedResponseCannotLogOutNewSession() throws Exception {
        lateResponseDoesNotReplaceNewSession(false);
    }

    private void lateResponseDoesNotReplaceNewSession(boolean closeDuringLogin) throws Exception {
        Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
        SharedPreferences preferences =
                context.getSharedPreferences("paired-televisions", Context.MODE_PRIVATE);
        String original = preferences.getString("sealed", null);
        java.util.concurrent.CountDownLatch started = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure =
                new java.util.concurrent.atomic.AtomicReference<>();
        Thread worker = null;
        AccountClient client = null;
        try {
            PhoneVault older = new PhoneVault(context);
            JSONObject first = session("old"), second = session("new");
            older.accountSession(closeDuringLogin ? null : first);
            // התגובה נעצרת בתוך תעבורה מדומה. שום בקשה אינה יוצאת לשרת האמיתי.
            okhttp3.OkHttpClient http =
                    new okhttp3.OkHttpClient.Builder()
                            .addInterceptor(chain -> {
                                started.countDown();
                                try {
                                    if (!release.await(5, java.util.concurrent.TimeUnit.SECONDS))
                                        throw new java.io.IOException("TEST_WAIT");
                                } catch (InterruptedException error) {
                                    Thread.currentThread().interrupt();
                                    throw new java.io.IOException("TEST_INTERRUPTED");
                                }
                                return new okhttp3.Response.Builder()
                                        .request(chain.request())
                                        .protocol(okhttp3.Protocol.HTTP_1_1)
                                        .code(closeDuringLogin ? 200 : 401)
                                        .message("test")
                                        .body(
                                                okhttp3.ResponseBody.create(
                                                        okhttp3.MediaType.parse("application/json"),
                                                        closeDuringLogin
                                                                ? first.toString()
                                                                : "{\"error\":\"UNAUTHORIZED\"}"))
                                        .build();
                            })
                            .build();
            client = new AccountClient(older, http);
            AccountClient active = client;
            worker =
                    new Thread(() -> {
                        try {
                            if (closeDuringLogin) active.authenticate(false, new JSONObject());
                            else active.refresh();
                        } catch (Throwable error) {
                            failure.set(error);
                        }
                    });
            worker.start();
            assertTrue(
                    "הבקשה המדומה צריכה להתחיל",
                    started.await(5, java.util.concurrent.TimeUnit.SECONDS));
            if (closeDuringLogin) client.close();
            PhoneVault newer = new PhoneVault(context);
            newer.accountSession(second);
            release.countDown();
            worker.join(5000);
            assertFalse("אין להשאיר תעבורה מדומה ממתינה", worker.isAlive());
            assertNotNull(failure.get());
            assertTrue(second.getString("token").equals(newer.accountSession().getString("token")));
        } finally {
            release.countDown();
            if (client != null) client.close();
            if (worker != null) worker.join(5000);
            SharedPreferences.Editor editor = preferences.edit();
            if (original == null) editor.remove("sealed");
            else editor.putString("sealed", original);
            assertTrue(editor.commit());
        }
    }

    private interface CheckedAction {
        void run() throws Exception;
    }

    private void expectChanged(CheckedAction action) throws Exception {
        try {
            action.run();
            fail("תגובה ישנה אינה רשאית לשנות את החשבון החדש");
        } catch (Exception error) {
            assertEquals("ACCOUNT_SYNC_CHANGED", error.getMessage());
        }
    }

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
