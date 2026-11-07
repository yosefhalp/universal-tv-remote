package org.yosef.karov;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class CryptoTest {
    @Test
    public void nativePhoneUsesClientDirectionAndReadsAgentVector() throws Exception {
        JSONObject f = fixture();
        Crypto.Session client =
                new Crypto.Session(
                        f.getString("secret"),
                        f.getString("device"),
                        f.getString("controller"),
                        f.getString("clientNonce"),
                        f.getString("agentNonce"),
                        true);
        assertEquals(f.getString("clientProof"), client.proof("client"));
        assertEquals(
                f.getJSONObject("value").toString(),
                client.open(f.getJSONObject("fromAgent")).toString());
        Crypto.Session agent = session(f);
        JSONObject command = new JSONObject().put("text", "שלום מהטלפון");
        assertEquals(command.toString(), agent.open(client.seal(command)).toString());
    }

    private JSONObject fixture() throws Exception {
        try (java.io.InputStream in = getClass().getResourceAsStream("/protocol-vector.json")) {
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private Crypto.Session session(JSONObject f) throws Exception {
        return new Crypto.Session(
                f.getString("secret"),
                f.getString("device"),
                f.getString("controller"),
                f.getString("clientNonce"),
                f.getString("agentNonce"));
    }

    @Test
    public void javascriptAndJavaAgreeOnProofsAndUnicodeCiphertext() throws Exception {
        // הווקטור נוצר ב-WebCrypto וכולל מפתחות בדיקה פומביים בלבד. שני הצדדים חייבים להפיק אותם
        // בתים.
        JSONObject f = fixture();
        Crypto.Session session = session(f);
        assertEquals(f.getString("agentProof"), session.proof("agent"));
        assertTrue(session.verify("client", f.getString("clientProof")));
        assertEquals("שלום 🚀", session.open(f.getJSONObject("fromClient")).getString("text"));
        assertEquals(
                f.getJSONObject("fromAgent").getString("b"),
                session.seal(f.getJSONObject("value")).getString("b"));
        assertThrows(Exception.class, () -> session.open(f.getJSONObject("fromClient")));
    }

    @Test
    public void changedSequenceIdentityAndCiphertextAreRejected() throws Exception {
        JSONObject f = fixture();
        JSONObject packet = new JSONObject(f.getJSONObject("fromClient").toString());
        packet.put("s", 1);
        assertThrows(Exception.class, () -> session(f).open(packet));
        packet.put("s", 0);
        byte[] raw = Crypto.decode(packet.getString("b"), 0);
        raw[0] ^= 1;
        packet.put("b", Crypto.b64(raw));
        assertThrows(Exception.class, () -> session(f).open(packet));
        f.put("device", "11111111111111111111111111111111");
        assertFalse(session(f).verify("client", f.getString("clientProof")));
    }

    @Test
    public void adbParserRejectsTruncationLengthAndTampering() throws Exception {
        byte[] message = LocalAdb.packet(LocalAdb.WRTE, 1, 2, new byte[] {1, 2, 3});
        assertArrayEquals(
                new byte[] {1, 2, 3},
                LocalAdb.read(new java.io.ByteArrayInputStream(message)).data);
        message[24] ^= 1;
        assertThrows(
                java.io.IOException.class,
                () -> LocalAdb.read(new java.io.ByteArrayInputStream(message)));
        assertThrows(
                java.io.IOException.class,
                () -> LocalAdb.read(new java.io.ByteArrayInputStream(new byte[23])));
        byte[] huge = LocalAdb.packet(LocalAdb.WRTE, 1, 2, new byte[0]);
        java.nio.ByteBuffer.wrap(huge)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
                .putInt(12, Integer.MAX_VALUE);
        assertThrows(
                java.io.IOException.class,
                () -> LocalAdb.read(new java.io.ByteArrayInputStream(huge)));
    }
}
