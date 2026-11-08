package org.yosef.karov.phone;

import okhttp3.*;

import org.json.*;

import java.util.concurrent.TimeUnit;

// קריאות החשבון נעשות ברקע בלבד. סיסמאות וטוקנים אינם נכתבים ליומן או לכתובת.
final class AccountClient {
    private final PhoneVault vault;
    private final Object lifecycle = new Object();
    private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final OkHttpClient http;

    AccountClient(PhoneVault vault) {
        this(
                vault,
                new OkHttpClient.Builder()
                        .callTimeout(15, TimeUnit.SECONDS)
                        .followRedirects(false)
                        .followSslRedirects(false)
                        .build());
    }

    // הזרקת תעבורה מאפשרת לבדוק תגובות מאוחרות בלי לפנות לשרת או לחשבון אמיתי.
    AccountClient(PhoneVault vault, OkHttpClient http) {
        this.vault = vault;
        this.http = http;
    }

    String token() throws Exception {
        JSONObject session = vault.accountSession();
        return session == null ? null : session.getString("token");
    }

    synchronized JSONObject request(
            String method, String path, JSONObject body, boolean authenticated) throws Exception {
        return requestTimed(method, path, body, authenticated, 15000, vault.accountSnapshot());
    }

    private JSONObject requestTimed(
            String method,
            String path,
            JSONObject body,
            boolean authenticated,
            long timeoutMillis,
            PhoneVault.AccountSnapshot snapshot)
            throws Exception {
        requireSnapshot(snapshot);
        String revision = snapshot.revision;
        Request.Builder request =
                new Request.Builder()
                        .url(Pairing.SERVER + "/v1/account" + path)
                        .header("Cache-Control", "no-store");
        if (authenticated) {
            String token = snapshot.session == null ? null : snapshot.session.getString("token");
            if (token == null) throw new Exception("ACCOUNT_UNAUTHORIZED");
            request.header("Authorization", "Bearer " + token);
        }
        RequestBody payload =
                body == null
                        ? null
                        : RequestBody.create(
                                MediaType.parse("application/json; charset=utf-8"),
                                body.toString());
        request.method(method, payload);
        Call call = http.newCall(request.build());
        call.timeout().timeout(Math.max(1, timeoutMillis), TimeUnit.MILLISECONDS);
        requireSnapshot(snapshot);
        try (Response response = call.execute()) {
            requireRevision(revision);
            if (response.code() == 401 && authenticated) {
                commitSession(revision, null);
                throw new Exception("ACCOUNT_UNAUTHORIZED");
            }
            if (response.body() == null || response.body().contentLength() > 131072)
                throw new Exception("BAD_ACCOUNT_RESPONSE");
            java.io.InputStream stream = response.body().byteStream();
            java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int count;
            while ((count = stream.read(buffer)) != -1) {
                if (bytes.size() + count > 131072) throw new Exception("BAD_ACCOUNT_RESPONSE");
                bytes.write(buffer, 0, count);
            }
            JSONObject value = new JSONObject(bytes.toString("UTF-8"));
            requireRevision(revision);
            if (!response.isSuccessful())
                throw new Exception(value.optString("error", "ACCOUNT_FAILED"));
            return value;
        } catch (java.io.IOException error) {
            throw new Exception("NETWORK");
        }
    }

    synchronized void authenticate(boolean register, JSONObject credentials) throws Exception {
        PhoneVault.AccountSnapshot snapshot = vault.accountSnapshot();
        JSONObject session =
                requestTimed(
                        "POST",
                        register ? "/register" : "/login",
                        credentials,
                        false,
                        15000,
                        snapshot);
        refresh(commitSession(snapshot.revision, session));
    }

    synchronized void refresh() throws Exception {
        refresh(vault.accountSnapshot());
    }

    synchronized void refresh(PhoneVault.AccountSnapshot snapshot) throws Exception {
        requireSnapshot(snapshot);
        if (snapshot.session == null) return;
        String revision = snapshot.revision;
        JSONObject user =
                requestTimed("GET", "/me", null, true, 15000, snapshot).getJSONObject("user");
        commitUser(revision, user);
        if (!user.optBoolean("mustChangePassword")) {
            JSONArray values =
                    requestTimed("GET", "/devices", null, true, 15000, snapshot)
                            .getJSONArray("devices");
            commitDevices(revision, values);
        }
    }

    synchronized void password(String current, String next) throws Exception {
        PhoneVault.AccountSnapshot snapshot = vault.accountSnapshot();
        JSONObject session =
                requestTimed(
                        "POST",
                        "/password",
                        new JSONObject().put("currentPassword", current).put("newPassword", next),
                        true,
                        15000,
                        snapshot);
        refresh(commitSession(snapshot.revision, session));
    }

    synchronized void logout() throws Exception {
        PhoneVault.AccountSnapshot snapshot = vault.accountSnapshot();
        String revision = snapshot.revision;
        try {
            requestTimed("POST", "/logout", new JSONObject(), true, 15000, snapshot);
        } finally {
            // תשובה או שגיאה ממסך ישן אינן רשאיות לנקות כניסה חדשה ממסך אחר.
            synchronized (lifecycle) {
                if (!closed.get() && revision.equals(vault.accountRevision()))
                    vault.accountSession(revision, null);
            }
        }
    }

    synchronized JSONObject prepare(String device, String name, PhoneVault.AccountSnapshot snapshot)
            throws Exception {
        requireSnapshot(snapshot);
        if (snapshot.session == null) throw new Exception("ACCOUNT_UNAUTHORIZED");
        if (snapshot.session.getJSONObject("user").optBoolean("mustChangePassword"))
            throw new Exception("PASSWORD_CHANGE_REQUIRED");
        return requestTimed(
                "POST",
                "/devices/prepare",
                new JSONObject().put("device", device).put("name", name),
                true,
                15000,
                snapshot);
    }

    void requireSnapshot(PhoneVault.AccountSnapshot snapshot) throws Exception {
        requireRevision(snapshot.revision);
    }

    // זהות החשבון נבדקת גם לפני כתיבה לכספת ולא רק בעת התחלת בקשת הרשת.
    private void requireRevision(String revision) throws Exception {
        if (closed.get()) throw new Exception("CANCELLED");
        if (!revision.equals(vault.accountRevision())) throw new Exception("ACCOUNT_SYNC_CHANGED");
    }

    void requireSession(String token, String owner) throws Exception {
        if (closed.get()) throw new Exception("CANCELLED");
        JSONObject session = vault.accountSession();
        if (session == null
                || !token.equals(session.optString("token"))
                || !owner.equals(session.getJSONObject("user").optString("id")))
            throw new Exception("ACCOUNT_SYNC_CHANGED");
        if (session.getJSONObject("user").optBoolean("mustChangePassword"))
            throw new Exception("PASSWORD_CHANGE_REQUIRED");
    }

    private PhoneVault.AccountSnapshot commitSession(String revision, JSONObject session)
            throws Exception {
        synchronized (lifecycle) {
            requireRevision(revision);
            return vault.accountSession(revision, session);
        }
    }

    private void commitUser(String revision, JSONObject user) throws Exception {
        synchronized (lifecycle) {
            requireRevision(revision);
            vault.accountUser(revision, user);
        }
    }

    private void commitDevices(String revision, JSONArray values) throws Exception {
        synchronized (lifecycle) {
            requireRevision(revision);
            vault.accountDevices(revision, values);
        }
    }

    // ממתינים זמן מוגבל לאישור שמגיע מתור הטלוויזיה. הכנה בלבד לעולם אינה נחשבת הצלחה.
    synchronized JSONObject awaitDevice(
            String device, String token, String owner, java.util.function.BooleanSupplier current)
            throws Exception {
        PhoneVault.AccountSnapshot snapshot = vault.accountSnapshot();
        requireSession(token, owner);
        return awaitDevice(device, snapshot, current);
    }

    synchronized JSONObject awaitDevice(
            String device,
            PhoneVault.AccountSnapshot snapshot,
            java.util.function.BooleanSupplier current)
            throws Exception {
        requireSnapshot(snapshot);
        String revision = snapshot.revision;
        if (snapshot.session == null) throw new Exception("ACCOUNT_UNAUTHORIZED");
        String token = snapshot.session.getString("token");
        String owner = snapshot.session.getJSONObject("user").getString("id");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) {
            requireSession(token, owner);
            requireRevision(revision);
            if (!current.getAsBoolean()) throw new Exception("CANCELLED");
            try {
                JSONObject user =
                        requestTimed("GET", "/me", null, true, pollTimeout(deadline), snapshot)
                                .getJSONObject("user");
                commitUser(revision, user);
                if (user.optBoolean("mustChangePassword"))
                    throw new Exception("PASSWORD_CHANGE_REQUIRED");
                JSONArray values =
                        requestTimed("GET", "/devices", null, true, pollTimeout(deadline), snapshot)
                                .getJSONArray("devices");
                requireSession(token, owner);
                if (!current.getAsBoolean()) throw new Exception("CANCELLED");
                commitDevices(revision, values);
                JSONObject record = vault.accountDevice(device);
                if (record != null) return record;
            } catch (Exception error) {
                if (!"NETWORK".equals(error.getMessage())) throw error;
            }
            long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (remaining > 0) Thread.sleep(Math.min(1000, remaining));
        }
        throw new Exception("ACCOUNT_LINK_PENDING");
    }

    private long pollTimeout(long deadline) throws Exception {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (remaining <= 0) throw new Exception("ACCOUNT_LINK_PENDING");
        return Math.min(5000, remaining);
    }

    void close() {
        synchronized (lifecycle) {
            if (!closed.compareAndSet(false, true)) return;
        }
        // סגירת TLS עשויה לשלוח הודעה ברשת. גם בעת סיבוב או שינוי גודל אסור לבצע אותה בתהליכון
        // הממשק.
        Thread cleanup =
                new Thread(
                        () -> {
                            http.dispatcher().cancelAll();
                            http.dispatcher().executorService().shutdown();
                            http.connectionPool().evictAll();
                        },
                        "karov-account-close");
        cleanup.setDaemon(true);
        cleanup.start();
    }
}
