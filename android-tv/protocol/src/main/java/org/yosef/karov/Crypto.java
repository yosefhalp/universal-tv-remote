package org.yosef.karov;

import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

public final class Crypto {
    private static final SecureRandom RANDOM = new SecureRandom();

    public static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    public static String b64(byte[] value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value);
    }

    public static byte[] decode(String value, int length) throws Exception {
        if (value == null || value.length() > 1500000 || !value.matches("[A-Za-z0-9_-]+"))
            throw new Exception("BAD_ENCODING");
        byte[] result = Base64.getUrlDecoder().decode(value);
        if ((length > 0 && result.length != length) || !b64(result).equals(value))
            throw new Exception("BAD_ENCODING");
        return result;
    }

    public static String secret() {
        byte[] data = new byte[32];
        RANDOM.nextBytes(data);
        return b64(data);
    }

    public static String id() {
        byte[] data = new byte[16];
        RANDOM.nextBytes(data);
        StringBuilder result = new StringBuilder();
        for (byte item : data) result.append(String.format("%02x", item));
        return result.toString();
    }

    public static boolean validId(String id) {
        return id != null && id.matches("[a-f0-9]{32}");
    }

    private static byte[] hmac(byte[] key, byte[] message) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(message);
    }

    private static byte[] derive(byte[] secret, byte[] salt, String info) throws Exception {
        // דרוש בלוק HKDF אחד בלבד למפתח בן 256 ביט. התווית מפרידה בין אימות ושני כיווני התעבורה.
        byte[] label = bytes(info);
        byte[] input = new byte[label.length + 1];
        System.arraycopy(label, 0, input, 0, label.length);
        input[label.length] = 1;
        return hmac(hmac(salt, secret), input);
    }

    public static final class Session {
        private final String transcript;
        private final byte[] proofKey, clientKey, agentKey;
        private final boolean clientSide;
        private long sent = 0, received = 0;

        public Session(
                String secret,
                String device,
                String controller,
                String clientNonce,
                String agentNonce)
                throws Exception {
            this(secret, device, controller, clientNonce, agentNonce, false);
        }

        // אותו פרוטוקול משמש בשני ה-APK. רק כיוון המפתחות משתנה בין הטלפון לסטרימר.
        public Session(
                String secret,
                String device,
                String controller,
                String clientNonce,
                String agentNonce,
                boolean clientSide)
                throws Exception {
            this.clientSide = clientSide;
            if (!validId(device) || !("pair".equals(controller) || validId(controller)))
                throw new Exception("BAD_ID");
            byte[] salt = new byte[64];
            System.arraycopy(decode(clientNonce, 32), 0, salt, 0, 32);
            System.arraycopy(decode(agentNonce, 32), 0, salt, 32, 32);
            transcript =
                    "TVSUPPORT/1|"
                            + device
                            + "|"
                            + controller
                            + "|"
                            + clientNonce
                            + "|"
                            + agentNonce;
            byte[] raw = decode(secret, 32);
            proofKey = derive(raw, salt, transcript + "|proof");
            clientKey = derive(raw, salt, transcript + "|client");
            agentKey = derive(raw, salt, transcript + "|agent");
        }

        public String proof(String role) throws Exception {
            return b64(hmac(proofKey, bytes(transcript + "|" + role)));
        }

        public boolean verify(String role, String proof) throws Exception {
            return MessageDigest.isEqual(
                    decode(proof, 32), hmac(proofKey, bytes(transcript + "|" + role)));
        }

        private Cipher cipher(int mode, byte[] key, String direction, long sequence)
                throws Exception {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] iv = ByteBuffer.allocate(12).putInt(0).putLong(sequence).array();
            cipher.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            cipher.updateAAD(bytes(transcript + "|" + direction + "|" + sequence));
            return cipher;
        }

        public synchronized JSONObject seal(JSONObject value) throws Exception {
            if (sent >= 9007199254740991L) throw new Exception("COUNTER_EXHAUSTED");
            long sequence = sent++;
            byte[] raw = bytes(value.toString());
            if (raw.length > 1000000) throw new Exception("MESSAGE_TOO_LARGE");
            return new JSONObject()
                    .put("t", "data")
                    .put("s", sequence)
                    .put(
                            "b",
                            b64(
                                    cipher(
                                                    Cipher.ENCRYPT_MODE,
                                                    clientSide ? clientKey : agentKey,
                                                    clientSide ? "client" : "agent",
                                                    sequence)
                                            .doFinal(raw)));
        }

        public synchronized JSONObject open(JSONObject value) throws Exception {
            Object sequence = value.opt("s");
            if (!"data".equals(value.optString("t"))
                    || !(sequence instanceof Number)
                    || ((Number) sequence).doubleValue() != received)
                throw new Exception("REPLAY_OR_ORDER");
            byte[] raw =
                    cipher(
                                    Cipher.DECRYPT_MODE,
                                    clientSide ? agentKey : clientKey,
                                    clientSide ? "agent" : "client",
                                    received)
                            .doFinal(decode(value.getString("b"), 0));
            JSONObject result = new JSONObject(new String(raw, StandardCharsets.UTF_8));
            received++;
            return result;
        }
    }

    private Crypto() {}
}
