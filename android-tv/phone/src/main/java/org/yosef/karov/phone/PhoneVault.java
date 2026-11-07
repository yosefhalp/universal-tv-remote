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
    private final SharedPreferences prefs;
    private final SecretKey key;
    private JSONObject data;

    PhoneVault(Context context) throws Exception {
        prefs = context.getSharedPreferences("paired-televisions", Context.MODE_PRIVATE);
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias("karov-phone-vault")) {
            KeyGenerator generator =
                    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(
                    new KeyGenParameterSpec.Builder(
                                    "karov-phone-vault",
                                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build());
            generator.generateKey();
        }
        key = (SecretKey) store.getKey("karov-phone-vault", null);
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

    synchronized JSONArray devices() throws Exception {
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

    synchronized JSONObject get(String device) throws Exception {
        JSONObject value = data.getJSONObject("devices").optJSONObject(device);
        return value == null ? null : new JSONObject(value.toString());
    }

    synchronized void put(JSONObject record) throws Exception {
        if (!Crypto.validId(record.getString("device"))
                || !Crypto.validId(record.getString("controller"))) throw new Exception("BAD_ID");
        Crypto.decode(record.getString("secret"), 32);
        JSONObject next = new JSONObject(data.toString());
        next.getJSONObject("devices")
                .put(record.getString("device"), new JSONObject(record.toString()));
        save(next);
    }

    synchronized void remove(String id) throws Exception {
        JSONObject next = new JSONObject(data.toString());
        next.getJSONObject("devices").remove(id);
        if (id.equals(next.optString("last"))) next.remove("last");
        save(next);
    }

    synchronized String last() {
        return data.optString("last", "");
    }

    synchronized void last(String id) throws Exception {
        JSONObject next = new JSONObject(data.toString()).put("last", id);
        save(next);
    }
}
