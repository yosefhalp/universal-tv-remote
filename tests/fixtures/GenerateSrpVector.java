import org.bouncycastle.crypto.agreement.srp.*;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.util.BigIntegers;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.HexFormat;

// וקטור פומבי לבדיקות בלבד; נוצר בשני מימושי הספרייה ללא שימוש בקוד הדפדפן.
class GenerateSrpVector {
    public static void main(String[] args) throws Exception {
        var h = HexFormat.of();
        var privateA =
                new BigInteger(
                        "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789", 16);
        var privateB =
                new BigInteger(
                        "fedcba9876543210fedcba9876543210fedcba9876543210fedcba9876543210", 16);
        var salt = h.parseHex("000102030405060708090a0b0c0d0e0f");
        String device = "cccccccccccccccccccccccccccccccc", code = "12345678";
        var identity = ("karov:" + device).getBytes(StandardCharsets.UTF_8);
        var password = code.getBytes(StandardCharsets.UTF_8);
        var group = SRP6StandardGroups.rfc5054_3072;
        var generator = new SRP6VerifierGenerator();
        generator.init(group, new SHA256Digest());
        var v = generator.generateVerifier(salt, identity, password);
        var client =
                new SRP6Client() {
                    protected BigInteger selectPrivateValue() {
                        return privateA;
                    }
                };
        var server =
                new SRP6Server() {
                    protected BigInteger selectPrivateValue() {
                        return privateB;
                    }
                };
        client.init(group, new SHA256Digest(), new SecureRandom());
        server.init(group, v, new SHA256Digest(), new SecureRandom());
        var A = client.generateClientCredentials(salt, identity, password);
        var B = server.generateServerCredentials();
        if (!client.calculateSecret(B).equals(server.calculateSecret(A))) throw new Exception("S");
        var M1 = client.calculateClientEvidenceMessage();
        if (!server.verifyClientEvidenceMessage(M1)) throw new Exception("M1");
        var M2 = server.calculateServerEvidenceMessage();
        if (!client.verifyServerEvidenceMessage(M2)) throw new Exception("M2");
        var K = server.calculateSessionKey();
        if (!K.equals(client.calculateSessionKey())) throw new Exception("K");
        var fields = new java.util.LinkedHashMap<String, String>();
        fields.put("source", "Bouncy Castle 1.86; וקטור בדיקה פומבי בלבד");
        fields.put("device", device);
        fields.put("code", code);
        fields.put("salt", h.formatHex(salt));
        fields.put("a", privateA.toString(16));
        fields.put("b", privateB.toString(16));
        fields.put("A", A.toString(16));
        fields.put("B", B.toString(16));
        fields.put("M1", h.formatHex(BigIntegers.asUnsignedByteArray(32, M1)));
        fields.put("M2", h.formatHex(BigIntegers.asUnsignedByteArray(32, M2)));
        fields.put("K", h.formatHex(BigIntegers.asUnsignedByteArray(32, K)));
        var json = new java.util.StringJoiner(",\n", "{\n", "\n}\n");
        fields.forEach((k, value) -> json.add("  \"" + k + "\": \"" + value + "\""));
        Files.writeString(Path.of(args[0]), json.toString());
        System.out.println("וקטור SRP עצמאי אומת ונשמר");
    }
}
