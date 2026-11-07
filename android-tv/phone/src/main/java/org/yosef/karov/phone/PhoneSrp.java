package org.yosef.karov.phone;

import org.bouncycastle.crypto.agreement.srp.SRP6Client;
import org.bouncycastle.crypto.agreement.srp.SRP6StandardGroups;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.util.BigIntegers;
import org.yosef.karov.Crypto;

import java.math.BigInteger;
import java.security.SecureRandom;

final class PhoneSrp {
    private final SRP6Client client;
    private final byte[] identity, password;
    final String publicValue;
    private boolean challenged, finished;

    PhoneSrp(String device, String code) throws Exception {
        if (!Crypto.validId(device)) throw new Exception("BAD_ID");
        identity = Crypto.bytes("karov:" + device);
        password = Crypto.bytes(Pairing.normalizeCode(code));
        client =
                new SRP6Client() {
                    private BigInteger once;

                    @Override
                    protected BigInteger selectPrivateValue() {
                        // A נשלח לפני שהסטרימר מחזיר מלח. שומרים אותו מעריך רק בתוך ניסיון יחיד זה.
                        if (once == null) once = super.selectPrivateValue();
                        return once;
                    }
                };
        client.init(SRP6StandardGroups.rfc5054_3072, new SHA256Digest(), new SecureRandom());
        publicValue =
                client.generateClientCredentials(new byte[16], identity, password).toString(16);
    }

    String challenge(String salt, String value) throws Exception {
        if (challenged || value == null || !value.matches("[0-9a-f]{1,768}"))
            throw new Exception("BAD_HANDSHAKE");
        challenged = true;
        BigInteger b = new BigInteger(value, 16);
        if (b.signum() <= 0 || b.compareTo(SRP6StandardGroups.rfc5054_3072.getN()) >= 0)
            throw new Exception("BAD_PUBLIC");
        client.generateClientCredentials(Crypto.decode(salt, 16), identity, password);
        client.calculateSecret(b);
        return client.calculateClientEvidenceMessage().toString(16);
    }

    String finish(String proof) throws Exception {
        if (!challenged || finished) throw new Exception("BAD_HANDSHAKE");
        finished = true;
        if (!client.verifyServerEvidenceMessage(new BigInteger(1, Crypto.decode(proof, 32))))
            throw new Exception("BAD_IDENTITY");
        return Crypto.b64(BigIntegers.asUnsignedByteArray(32, client.calculateSessionKey()));
    }
}
