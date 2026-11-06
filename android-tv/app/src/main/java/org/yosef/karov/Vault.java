package org.yosef.karov;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URI;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class Vault {
    private static Vault instance;

    public static synchronized Vault open(Context context) throws Exception {
        if (instance == null) instance = new Vault(context.getApplicationContext());
        return instance;
    }

    private final SharedPreferences prefs;
    private JSONObject data;
    private final SecretKey key;

    private Vault(Context context) throws Exception {
        prefs = context.getSharedPreferences("karov-private", Context.MODE_PRIVATE);
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (!store.containsAlias("karov-storage")) {
            KeyGenerator generator =
                    KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            generator.init(
                    new KeyGenParameterSpec.Builder(
                                    "karov-storage",
                                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                            .build());
            generator.generateKey();
        }
        key = (SecretKey) store.getKey("karov-storage", null);
        String value = prefs.getString("vault", null);
        if (value == null) {
            data =
                    new JSONObject()
                            .put("device", Crypto.id())
                            .put("token", Crypto.secret())
                            .put("name", "הטלוויזיה של סבתא")
                            .put("server", "")
                            .put("enabled", false)
                            .put("controllers", new JSONObject());
            save();
        } else {
            String[] parts = value.split("\\.");
            if (parts.length != 2) throw new Exception("VAULT_CORRUPT");
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

    private synchronized void save() throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        String sealed =
                Crypto.b64(cipher.getIV())
                        + "."
                        + Crypto.b64(cipher.doFinal(Crypto.bytes(data.toString())));
        if (!prefs.edit().putString("vault", sealed).commit())
            throw new Exception("STORAGE_FAILED");
    }

    public synchronized String get(String name) {
        return data.optString(name, "");
    }

    public synchronized boolean enabled() {
        return data.optBoolean("enabled");
    }

    public synchronized void enable(boolean enabled) throws Exception {
        data.put("enabled", enabled);
        save();
    }

    public synchronized void configure(String url, String name) throws Exception {
        URI uri = new URI(url.trim());
        boolean local =
                BuildConfig.DEBUG
                        && "http".equals(uri.getScheme())
                        && java.util.Arrays.asList("10.0.2.2", "127.0.0.1", "localhost")
                                .contains(uri.getHost());
        if ((!"https".equals(uri.getScheme()) && !local)
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null
                || (uri.getPath() != null
                        && !uri.getPath().isEmpty()
                        && !uri.getPath().equals("/"))) throw new Exception("INVALID_SERVER");
        String server = uri.toString().replaceAll("/+$", "");
        if (!get("server").isEmpty()
                && !get("server").equals(server)
                && data.getJSONObject("controllers").length() > 0)
            throw new Exception("SERVER_HAS_PAIRINGS");
        data.put("server", server)
                .put(
                        "name",
                        name.trim().isEmpty()
                                ? "הטלוויזיה"
                                : name.substring(0, Math.min(name.length(), 80)));
        save();
    }

    public synchronized String pairing() throws Exception {
        data.put("pairSecret", Crypto.secret())
                .put("pairExpires", System.currentTimeMillis() + 600000);
        save();
        JSONObject pair =
                new JSONObject()
                        .put("v", 1)
                        .put("device", get("device"))
                        .put("name", get("name"))
                        .put("secret", get("pairSecret"))
                        .put("expires", data.getLong("pairExpires"));
        return get("server") + "/#pair=" + Crypto.b64(Crypto.bytes(pair.toString()));
    }

    public synchronized String secretFor(String controller) throws Exception {
        if (controller.equals("pair"))
            return data.optLong("pairExpires") > System.currentTimeMillis()
                    ? get("pairSecret")
                    : "";
        JSONObject record = data.getJSONObject("controllers").optJSONObject(controller);
        return record == null ? "" : record.optString("secret");
    }

    public synchronized void enroll(String id, String secret, String name) throws Exception {
        if (!Crypto.validId(id)) throw new Exception("BAD_ID");
        Crypto.decode(secret, 32);
        JSONObject controllers = data.getJSONObject("controllers");
        if (controllers.length() >= 8 || controllers.has(id))
            throw new Exception("CONTROLLER_LIMIT");
        controllers.put(
                id,
                new JSONObject()
                        .put("name", name.substring(0, Math.min(name.length(), 40)))
                        .put("secret", secret));
        data.remove("pairSecret");
        data.remove("pairExpires");
        save();
    }

    public synchronized JSONArray controllers() throws Exception {
        JSONArray list = new JSONArray();
        JSONObject controllers = data.getJSONObject("controllers");
        for (java.util.Iterator<String> keys = controllers.keys(); keys.hasNext(); ) {
            String id = keys.next();
            list.put(
                    new JSONObject()
                            .put("id", id)
                            .put("name", controllers.getJSONObject(id).getString("name")));
        }
        return list;
    }

    public synchronized void revoke(String id) throws Exception {
        data.getJSONObject("controllers").remove(id);
        save();
    }

    public synchronized void revokeAll() throws Exception {
        data.put("controllers", new JSONObject());
        data.remove("pairSecret");
        data.remove("pairExpires");
        save();
    }

    public synchronized void put(String name, String value) throws Exception {
        data.put(name, value);
        save();
    }
}
