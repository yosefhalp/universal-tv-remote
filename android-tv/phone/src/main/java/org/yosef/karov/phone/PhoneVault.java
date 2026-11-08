package org.yosef.karov.phone;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONArray;
import org.json.JSONObject;
import org.yosef.karov.Crypto;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class PhoneVault {
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;
    private final SecretKey key;
    private JSONObject data;

    PhoneVault(Context context) throws Exception {
        prefs = context.getSharedPreferences("paired-televisions", Context.MODE_PRIVATE);
        synchronized (LOCK) {
            KeyStore store = KeyStore.getInstance("AndroidKeyStore");
            store.load(null);
            if (!store.containsAlias("karov-phone-vault")) {
                KeyGenerator generator =
                        KeyGenerator.getInstance(
                                KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
                generator.init(
                        new KeyGenParameterSpec.Builder(
                                        "karov-phone-vault",
                                        KeyProperties.PURPOSE_ENCRYPT
                                                | KeyProperties.PURPOSE_DECRYPT)
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .build());
                generator.generateKey();
            }
            key = (SecretKey) store.getKey("karov-phone-vault", null);
            load();
        }
    }

    // כל מופע קורא את המסמך העדכני תחת אותה נעילה, כדי שמסך ישן לא ידרוס חשבון חדש.
    private void load() throws Exception {
        String sealed = prefs.getString("sealed", "");
        if (sealed.isEmpty()) data = new JSONObject().put("devices", new JSONObject());
        else {
            String[] parts = sealed.split("\\.");
            if (parts.length != 2) throw new Exception("STORAGE_UNAVAILABLE");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key,
                    new GCMParameterSpec(128, Crypto.decode(parts[0], 12)));
            data =
                    new JSONObject(
                            new String(
                                    cipher.doFinal(Crypto.decode(parts[1], 0)),
                                    java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    private void save(JSONObject next) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        String value =
                Crypto.b64(cipher.getIV())
                        + "."
                        + Crypto.b64(cipher.doFinal(Crypto.bytes(next.toString())));
        if (!prefs.edit().putString("sealed", value).commit())
            throw new Exception("STORAGE_UNAVAILABLE");
        data = next;
    }

    JSONArray devices() throws Exception {
        synchronized (LOCK) {
            load();
            JSONArray result = new JSONArray();
            JSONObject devices = data.getJSONObject("devices");
            for (java.util.Iterator<String> ids = devices.keys(); ids.hasNext(); ) {
                String id = ids.next();
                // הרשימה לתצוגה אינה כוללת סודות; הם נקראים רק בעת פתיחת חיבור.
                result.put(
                        new JSONObject()
                                .put("device", id)
                                .put("name", devices.getJSONObject(id).getString("name")));
            }
            return result;
        }
    }

    JSONObject get(String device) throws Exception {
        synchronized (LOCK) {
            load();
            JSONObject value = data.getJSONObject("devices").optJSONObject(device);
            return value == null ? null : new JSONObject(value.toString());
        }
    }

    void put(JSONObject record) throws Exception {
        synchronized (LOCK) {
            load();
            if (!Crypto.validId(record.getString("device"))
                    || !Crypto.validId(record.getString("controller")))
                throw new Exception("BAD_ID");
            Crypto.decode(record.getString("secret"), 32);
            JSONObject next = new JSONObject(data.toString());
            next.getJSONObject("devices")
                    .put(record.getString("device"), new JSONObject(record.toString()));
            save(next);
        }
    }

    void remove(String id) throws Exception {
        synchronized (LOCK) {
            load();
            JSONObject next = new JSONObject(data.toString());
            next.getJSONObject("devices").remove(id);
            if (id.equals(next.optString("last"))) next.remove("last");
            save(next);
        }
    }

    String last() throws Exception {
        synchronized (LOCK) {
            load();
            return data.optString("last", "");
        }
    }

    void last(String id) throws Exception {
        synchronized (LOCK) {
            load();
            JSONObject next = new JSONObject(data.toString()).put("last", id);
            save(next);
        }
    }

    // החשבון נשמר בנפרד מהזיווגים המקומיים, בתוך אותה כספת מוצפנת של Android.
    JSONObject accountSession() throws Exception {
        synchronized (LOCK) {
            load();
            JSONObject session = data.optJSONObject("accountSession");
            return session == null ? null : new JSONObject(session.toString());
        }
    }

    void accountSession(JSONObject session) throws Exception {
        synchronized (LOCK) {
            load();
            JSONObject next = new JSONObject(data.toString());
            next.remove("accountDevices");
            next.put("accountRevision", Crypto.id());
            if (session == null) next.remove("accountSession");
            else next.put("accountSession", new JSONObject(session.toString()));
            save(next);
        }
    }

    void accountUser(JSONObject user) throws Exception {
        synchronized (LOCK) {
            load();
            JSONObject next = new JSONObject(data.toString());
            next.getJSONObject("accountSession").put("user", new JSONObject(user.toString()));
            if (user.optBoolean("mustChangePassword")) next.remove("accountDevices");
            save(next);
        }
    }

    JSONArray accountDevices() throws Exception {
        synchronized (LOCK) {
            load();
            JSONArray values = data.optJSONArray("accountDevices");
            return values == null ? new JSONArray() : new JSONArray(values.toString());
        }
    }

    void accountDevices(JSONArray values) throws Exception {
        synchronized (LOCK) {
            load();
            for (int i = 0; i < values.length(); i++) {
                JSONObject record = values.getJSONObject(i);
                if (!Crypto.validId(record.getString("device"))
                        || !Crypto.validId(record.getString("controller")))
                    throw new Exception("BAD_ID");
                Crypto.decode(record.getString("secret"), 32);
            }
            JSONObject next = new JSONObject(data.toString());
            next.put("accountDevices", new JSONArray(values.toString()));
            save(next);
        }
    }

    JSONObject accountDevice(String id) throws Exception {
        synchronized (LOCK) {
            load();
            JSONArray values = accountDevices();
            for (int i = 0; i < values.length(); i++) {
                JSONObject record = values.getJSONObject(i);
                if (id.equals(record.optString("device"))) {
                    record.put("account", true);
                    return record;
                }
            }
            return null;
        }
    }

    String accountRevision() throws Exception {
        synchronized (LOCK) {
            load();
            return data.optString("accountRevision", "");
        }
    }

    static final class AccountSnapshot {
        final String revision;
        final JSONObject session;

        private AccountSnapshot(String revision, JSONObject session) {
            this.revision = revision;
            this.session = session;
        }
    }

    // הטוקן וגרסת החשבון נקראים יחד, כדי שבקשה ישנה לעולם לא תישלח בשם חשבון חדש.
    AccountSnapshot accountSnapshot() throws Exception {
        synchronized (LOCK) {
            load();
            JSONObject session = data.optJSONObject("accountSession");
            return new AccountSnapshot(
                    data.optString("accountRevision", ""),
                    session == null ? null : new JSONObject(session.toString()));
        }
    }

    private void requireRevision(String expected) throws Exception {
        if (!expected.equals(data.optString("accountRevision", "")))
            throw new Exception("ACCOUNT_SYNC_CHANGED");
    }

    AccountSnapshot accountSession(String expectedRevision, JSONObject session) throws Exception {
        synchronized (LOCK) {
            load();
            requireRevision(expectedRevision);
            accountSession(session);
            return accountSnapshot();
        }
    }

    void accountUser(String expectedRevision, JSONObject user) throws Exception {
        synchronized (LOCK) {
            load();
            requireRevision(expectedRevision);
            JSONObject session = data.optJSONObject("accountSession");
            if (session == null
                    || !user.getString("id").equals(session.getJSONObject("user").getString("id")))
                throw new Exception("ACCOUNT_SYNC_CHANGED");
            accountUser(user);
        }
    }

    void accountDevices(String expectedRevision, JSONArray values) throws Exception {
        synchronized (LOCK) {
            load();
            requireRevision(expectedRevision);
            JSONObject session = data.optJSONObject("accountSession");
            if (session == null || session.getJSONObject("user").optBoolean("mustChangePassword"))
                throw new Exception("ACCOUNT_SYNC_CHANGED");
            accountDevices(values);
        }
    }
}
