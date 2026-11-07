package org.yosef.karov;

import static org.junit.Assert.*;

import org.bouncycastle.crypto.agreement.srp.SRP6Client;
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.util.BigIntegers;
import org.junit.Test;

import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;

public class PairingTest {
    @Test
    public void libraryClientAuthenticatesAndReplayFails() throws Exception {
        exchange("12345678", true);
    }

    @Test
    public void wrongCodeNeverReceivesServerProofOrKey() throws Exception {
        exchange("12345679", false);
    }

    private void exchange(String password, boolean correct) throws Exception {
        // הלקוח העצמאי מהספרייה בודק את עטיפת השרת של האפליקציה ואת אורך המפתח.
        String identity = "karov:cccccccccccccccccccccccccccccccc";
        byte[] provisionalSalt = new byte[16];
        // השרת שולח מלח רק לאחר A. לקוח ספרייה שני שומר אותו מעריך פרטי עבור המלח האמיתי.
        final BigInteger[] captured = new BigInteger[1];
        SRP6Client stable =
                new SRP6Client() {
                    @Override
                    protected BigInteger selectPrivateValue() {
                        if (captured[0] == null) captured[0] = super.selectPrivateValue();
                        return captured[0];
                    }
                };
        stable.init(SRP6StandardGroups.rfc5054_3072, new SHA256Digest(), new SecureRandom());
        BigInteger a =
                stable.generateClientCredentials(
                        provisionalSalt,
                        identity.getBytes(StandardCharsets.UTF_8),
                        password.getBytes(StandardCharsets.UTF_8));
        SrpExchange server = new SrpExchange(identity, "12345678", a.toString(16));
        stable.generateClientCredentials(
                server.salt,
                identity.getBytes(StandardCharsets.UTF_8),
                password.getBytes(StandardCharsets.UTF_8));
        stable.calculateSecret(new BigInteger(server.publicValue, 16));
        String proof = stable.calculateClientEvidenceMessage().toString(16);
        if (!correct) {
            assertThrows(Exception.class, () -> server.finish(proof));
            return;
        }
        byte[][] result = server.finish(proof);
        assertTrue(stable.verifyServerEvidenceMessage(new BigInteger(1, result[0])));
        assertArrayEquals(
                BigIntegers.asUnsignedByteArray(32, stable.calculateSessionKey()), result[1]);
        assertThrows(Exception.class, () -> server.finish(proof));
    }

    @Test
    public void invalidPublicValuesAreRejected() {
        for (String value :
                new String[] {
                    "0",
                    "-1",
                    "gg",
                    "f".repeat(769),
                    SRP6StandardGroups.rfc5054_3072.getN().toString(16)
                })
            assertThrows(Exception.class, () -> new SrpExchange("identity", "12345678", value));
    }

    @Test
    public void attemptsSurviveReconnectAndRotationExpiresOldWindow() {
        PairingWindow window = new PairingWindow("old-version", 2000);
        window.prefix = "12";
        window.registered = true;
        assertFalse(window.attempt("old-version", "13", 1000));
        for (int i = 0; i < 5; i++) {
            assertTrue(window.attempt("old-version", "12", 1000));
            window.registered = false;
            assertFalse(window.attempt("old-version", "12", 1000));
            window.registered = true;
        }
        assertFalse(window.attempt("old-version", "12", 1000));
        assertFalse(window.valid("new-version", 1000));
        assertFalse(window.valid("old-version", 2000));
        assertTrue(window.code().matches("[0-9]{8}"));
    }
}
