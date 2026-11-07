package org.yosef.karov.phone;

import org.json.JSONObject;
import org.yosef.karov.Crypto;

import java.net.URI;
import java.nio.charset.StandardCharsets;

final class Pairing {
    static final String SERVER = "https://tv.yosefapk.top";
    final String device, name, secret, code;

    private Pairing(String device, String name, String secret, String code) {
        this.device = device;
        this.name = name;
        this.secret = secret;
        this.code = code;
    }

    static String normalizeCode(String input) throws Exception {
        String code = input.replaceAll("[\\s-]", "");
        if (!code.matches("[0-9]{8}")) throw new Exception("BAD_CODE");
        return code;
    }

    static Pairing fromCode(JSONObject directory, String input, long now) throws Exception {
        validate(directory, now);
        return new Pairing(
                directory.getString("device"), name(directory), null, normalizeCode(input));
    }

    static Pairing fromQr(String input, long now) throws Exception {
        if (input == null || input.length() > 4096) throw new Exception("BAD_PAIRING");
        URI uri = new URI(input);
        // הסורק מקבל זיווג לשרת המוצר בלבד. אין פתיחת כתובות או ביצוע הוראות מתוך QR זר.
        if (!"https".equals(uri.getScheme())
                || !"tv.yosefapk.top".equals(uri.getHost())
                || uri.getUserInfo() != null
                || uri.getPort() != -1
                || uri.getQuery() != null
                || !(uri.getPath().isEmpty() || uri.getPath().equals("/"))
                || uri.getRawFragment() == null
                || !uri.getRawFragment().startsWith("pair=")) throw new Exception("BAD_PAIRING");
        JSONObject value =
                new JSONObject(
                        new String(
                                Crypto.decode(uri.getRawFragment().substring(5), 0),
                                StandardCharsets.UTF_8));
        if (value.optInt("v") != 1) throw new Exception("BAD_PAIRING");
        validate(value, now);
        String secret = value.getString("secret");
        Crypto.decode(secret, 32);
        return new Pairing(value.getString("device"), name(value), secret, null);
    }

    private static void validate(JSONObject value, long now) throws Exception {
        if (!Crypto.validId(value.optString("device"))) throw new Exception("BAD_PAIRING");
        long expires = value.getLong("expires");
        if (expires <= now || expires > now + 660000) throw new Exception("EXPIRED");
    }

    private static String name(JSONObject value) {
        String name = value.optString("name", "הטלוויזיה");
        return name.substring(0, Math.min(80, name.length()));
    }
}
