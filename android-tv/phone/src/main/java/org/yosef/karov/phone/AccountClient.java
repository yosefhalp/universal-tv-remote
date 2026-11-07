package org.yosef.karov.phone;

import okhttp3.*;

import org.json.*;

import java.util.concurrent.TimeUnit;

// קריאות החשבון נעשות ברקע בלבד. סיסמאות וטוקנים אינם נכתבים ליומן או לכתובת.
final class AccountClient {
    private final PhoneVault vault;
    private final java.util.concurrent.atomic.AtomicBoolean closed =
            new java.util.concurrent.atomic.AtomicBoolean();
    private final OkHttpClient http =
            new OkHttpClient.Builder()
                    .callTimeout(15, TimeUnit.SECONDS)
                    .followRedirects(false)
                    .followSslRedirects(false)
                    .build();

    AccountClient(PhoneVault vault) {
        this.vault = vault;
    }

    String token() throws Exception {
        JSONObject session = vault.accountSession();
        return session == null ? null : session.getString("token");
    }

    synchronized JSONObject request(
            String method, String path, JSONObject body, boolean authenticated) throws Exception {
        if (closed.get()) throw new Exception("NETWORK");
        Request.Builder request =
                new Request.Builder()
                        .url(Pairing.SERVER + "/v1/account" + path)
                        .header("Cache-Control", "no-store");
        if (authenticated) {
            String token = token();
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
        try (Response response = http.newCall(request.build()).execute()) {
            if (response.code() == 401 && authenticated) {
                vault.accountSession(null);
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
            if (!response.isSuccessful())
                throw new Exception(value.optString("error", "ACCOUNT_FAILED"));
            return value;
        } catch (java.io.IOException error) {
            throw new Exception("NETWORK");
        }
    }

    synchronized void authenticate(boolean register, JSONObject credentials) throws Exception {
        JSONObject session = request("POST", register ? "/register" : "/login", credentials, false);
        vault.accountSession(session);
        refresh();
    }

    synchronized void refresh() throws Exception {
        if (token() == null) return;
        JSONObject user = request("GET", "/me", null, true).getJSONObject("user");
        vault.accountUser(user);
        if (!user.optBoolean("mustChangePassword"))
            vault.accountDevices(request("GET", "/devices", null, true).getJSONArray("devices"));
    }

    synchronized void password(String current, String next) throws Exception {
        JSONObject session =
                request(
                        "POST",
                        "/password",
                        new JSONObject().put("currentPassword", current).put("newPassword", next),
                        true);
        vault.accountSession(session);
        refresh();
    }

    synchronized void logout() throws Exception {
        try {
            request("POST", "/logout", new JSONObject(), true);
        } finally {
            vault.accountSession(null);
        }
    }

    synchronized JSONObject prepare(String device, String name) throws Exception {
        return request(
                "POST",
                "/devices/prepare",
                new JSONObject().put("device", device).put("name", name),
                true);
    }

    void close() {
        if (!closed.compareAndSet(false, true)) return;
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
