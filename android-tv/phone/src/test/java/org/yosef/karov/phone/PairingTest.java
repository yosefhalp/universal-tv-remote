package org.yosef.karov.phone;

import static org.junit.Assert.*;

import org.bouncycastle.crypto.agreement.srp.*;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.util.BigIntegers;
import org.json.JSONObject;
import org.junit.Test;
import org.yosef.karov.Crypto;

import java.math.BigInteger;
import java.security.SecureRandom;

public class PairingTest {
    @Test
    public void qrIsValidatedBeforeConnectingAndForeignUrlsAreRejected() throws Exception {
        long now = 1000;
        JSONObject value =
                new JSONObject()
                        .put("v", 1)
                        .put("device", Crypto.id())
                        .put("secret", Crypto.secret())
                        .put("name", "סבתא")
                        .put("expires", 5000);
        String fragment = "#pair=" + Crypto.b64(Crypto.bytes(value.toString()));
        assertEquals("סבתא", Pairing.fromQr(Pairing.SERVER + "/" + fragment, now).name);
        for (String prefix :
                new String[] {
                    "https://evil.example/",
                    "http://tv.yosefapk.top/",
                    "https://name@tv.yosefapk.top/",
                    "https://tv.yosefapk.top/path",
                    "https://tv.yosefapk.top/?x=1"
                }) assertThrows(Exception.class, () -> Pairing.fromQr(prefix + fragment, now));
        assertThrows(Exception.class, () -> Pairing.fromQr(Pairing.SERVER + "/" + fragment, 5000));
    }

    @Test
    public void shortCodeKeepsLeadingZeroAndRejectsMalformedInputs() throws Exception {
        assertEquals("01234567", Pairing.normalizeCode("01-234-567"));
        for (String code : new String[] {"123", "123456789", "12x345678"})
            assertThrows(Exception.class, () -> Pairing.normalizeCode(code));
    }

    @Test
    public void nativeClientMatchesLibraryServerAndRejectsChangedEvidence() throws Exception {
        exchange(false);
        exchange(true);
    }

    private void exchange(boolean tamper) throws Exception {
        String device = Crypto.id(), code = "12345678";
        PhoneSrp client = new PhoneSrp(device, code);
        byte[] salt = new byte[16];
        new SecureRandom().nextBytes(salt);
        SRP6VerifierGenerator generator = new SRP6VerifierGenerator();
        generator.init(SRP6StandardGroups.rfc5054_3072, new SHA256Digest());
        BigInteger verifier =
                generator.generateVerifier(
                        salt, Crypto.bytes("karov:" + device), Crypto.bytes(code));
        SRP6Server server = new SRP6Server();
        server.init(
                SRP6StandardGroups.rfc5054_3072, verifier, new SHA256Digest(), new SecureRandom());
        String publicValue = server.generateServerCredentials().toString(16);
        server.calculateSecret(new BigInteger(client.publicValue, 16));
        String proof = client.challenge(Crypto.b64(salt), publicValue);
        assertTrue(server.verifyClientEvidenceMessage(new BigInteger(proof, 16)));
        byte[] evidence =
                BigIntegers.asUnsignedByteArray(32, server.calculateServerEvidenceMessage());
        if (tamper) {
            evidence[0] ^= 1;
            assertThrows(Exception.class, () -> client.finish(Crypto.b64(evidence)));
        } else
            assertEquals(
                    Crypto.b64(BigIntegers.asUnsignedByteArray(32, server.calculateSessionKey())),
                    client.finish(Crypto.b64(evidence)));
        assertThrows(Exception.class, () -> client.finish(Crypto.b64(evidence)));
    }
}
