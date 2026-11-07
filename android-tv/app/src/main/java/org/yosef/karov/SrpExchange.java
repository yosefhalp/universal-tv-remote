package org.yosef.karov;

import org.bouncycastle.crypto.agreement.srp.SRP6Server;
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups;
import org.bouncycastle.crypto.agreement.srp.SRP6VerifierGenerator;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.util.BigIntegers;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

// זיווג בקוד קצר משתמש ב-SRP-6a: לא משדרים את הסיסמה ולא גוזרים ממנה ישירות מפתח הצפנה.
// הקבוצה הקבועה היא RFC 5054, בגודל 3072 סיביות; ההוכחות תואמות למימוש Bouncy Castle.
final class SrpExchange {
    private final SRP6Server server = new SRP6Server();
    final byte[] salt = new byte[16];
    final String publicValue;
    private boolean finished;

    SrpExchange(String identity, String password, String clientPublic) throws Exception {
        BigInteger a = parse(clientPublic, 768);
        if (a.signum() <= 0 || a.compareTo(SRP6StandardGroups.rfc5054_3072.getN()) >= 0)
            throw new Exception("BAD_PUBLIC");
        SecureRandom random = new SecureRandom();
        random.nextBytes(salt);
        SRP6VerifierGenerator verifier = new SRP6VerifierGenerator();
        verifier.init(SRP6StandardGroups.rfc5054_3072, new SHA256Digest());
        BigInteger v =
                verifier.generateVerifier(
                        salt,
                        identity.getBytes(StandardCharsets.UTF_8),
                        password.getBytes(StandardCharsets.UTF_8));
        server.init(SRP6StandardGroups.rfc5054_3072, v, new SHA256Digest(), random);
        publicValue = server.generateServerCredentials().toString(16);
        server.calculateSecret(a);
    }

    byte[][] finish(String proof) throws Exception {
        if (finished) throw new Exception("REPLAY");
        finished = true;
        if (!server.verifyClientEvidenceMessage(parse(proof, 64))) throw new Exception("DENIED");
        byte[] evidence =
                BigIntegers.asUnsignedByteArray(32, server.calculateServerEvidenceMessage());
        byte[] key = BigIntegers.asUnsignedByteArray(32, server.calculateSessionKey());
        return new byte[][] {evidence, key};
    }

    private static BigInteger parse(String value, int maximum) throws Exception {
        if (value == null || value.length() > maximum || !value.matches("[0-9a-f]+"))
            throw new Exception("BAD_ENCODING");
        return new BigInteger(value, 16);
    }
}
