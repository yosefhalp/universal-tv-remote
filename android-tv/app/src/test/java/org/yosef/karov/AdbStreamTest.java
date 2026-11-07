package org.yosef.karov;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class AdbStreamTest {
    private static final int LOCAL = 17, PEER = 29;

    private static LocalAdb.Packet packet(int command, int peer, int local, byte[] data) {
        return new LocalAdb.Packet(command, peer, local, data);
    }

    private static LocalAdb.Packet packet(int command) {
        return packet(command, PEER, LOCAL, new byte[0]);
    }

    // החיבור המדומה מכיל תשובות סוכן מוכנות. כל ניסיון להשיב CLSE נכשל בכוונה כדי לגלות אישור
    // מיותר.
    private static final class FakeSocket extends Socket {
        final List<LocalAdb.Packet> writes = new ArrayList<>();
        private final InputStream input;
        private final OutputStream output =
                new OutputStream() {
                    @Override
                    public void write(int value) throws IOException {
                        throw new IOException("בדיקה דורשת הודעה שלמה");
                    }

                    @Override
                    public void write(byte[] bytes, int offset, int length) throws IOException {
                        LocalAdb.Packet sent =
                                LocalAdb.read(
                                        new ByteArrayInputStream(
                                                Arrays.copyOfRange(
                                                        bytes, offset, offset + length)));
                        if (sent.command == LocalAdb.CLSE)
                            throw new IOException("אסור להשיב סגירה לזרם שכבר נסגר");
                        writes.add(sent);
                    }
                };

        FakeSocket(LocalAdb.Packet... packets) throws IOException {
            ByteArrayOutputStream encoded = new ByteArrayOutputStream();
            for (LocalAdb.Packet packet : packets)
                encoded.write(
                        LocalAdb.packet(packet.command, packet.arg0, packet.arg1, packet.data));
            input = new ByteArrayInputStream(encoded.toByteArray());
        }

        @Override
        public InputStream getInputStream() {
            return input;
        }

        @Override
        public OutputStream getOutputStream() {
            return output;
        }
    }

    private static byte[] execute(FakeSocket socket, int limit) throws Exception {
        return LocalAdb.execute(socket, "echo test", limit, LOCAL);
    }

    @Test
    public void emptyOutputFinishesAfterAcceptedOpenWithoutCloseReply() throws Exception {
        FakeSocket socket = new FakeSocket(packet(LocalAdb.OKAY), packet(LocalAdb.CLSE));
        assertArrayEquals(new byte[0], execute(socket, 1024));
        assertEquals(1, socket.writes.size());
        assertEquals(LocalAdb.OPEN, socket.writes.get(0).command);
        assertEquals(LOCAL, socket.writes.get(0).arg0);
        assertEquals(0, socket.writes.get(0).arg1);
    }

    @Test
    public void outputPacketsAreAcknowledgedAndJoinedBeforeClosing() throws Exception {
        FakeSocket socket =
                new FakeSocket(
                        packet(LocalAdb.OKAY),
                        packet(LocalAdb.WRTE, PEER, LOCAL, new byte[] {1, 2}),
                        packet(LocalAdb.WRTE, PEER, LOCAL, new byte[] {3, 4}),
                        packet(LocalAdb.CLSE));
        assertArrayEquals(new byte[] {1, 2, 3, 4}, execute(socket, 4));
        assertEquals(3, socket.writes.size());
        for (LocalAdb.Packet ack : socket.writes.subList(1, 3)) {
            assertEquals(LocalAdb.OKAY, ack.command);
            assertEquals(LOCAL, ack.arg0);
            assertEquals(PEER, ack.arg1);
            assertEquals(0, ack.data.length);
        }
    }

    @Test
    public void legacyZeroSenderCanCloseOnlyAnAlreadyOpenedStream() throws Exception {
        FakeSocket socket =
                new FakeSocket(packet(LocalAdb.OKAY), packet(LocalAdb.CLSE, 0, LOCAL, new byte[0]));
        assertArrayEquals(new byte[0], execute(socket, 1024));
        assertEquals(1, socket.writes.size());
    }

    @Test
    public void closeBeforeOpenIsRefusalAndNeverEmptySuccess() throws Exception {
        for (int sender : new int[] {0, PEER}) {
            FakeSocket socket = new FakeSocket(packet(LocalAdb.CLSE, sender, LOCAL, new byte[0]));
            assertEquals(
                    "ADB_OPEN",
                    assertThrows(IOException.class, () -> execute(socket, 1024)).getMessage());
            assertEquals(1, socket.writes.size());
        }
    }

    @Test
    public void openRequiresOkayAndNonzeroRemoteIdentifier() throws Exception {
        FakeSocket earlyWrite = new FakeSocket(packet(LocalAdb.WRTE));
        assertEquals(
                "ADB_MESSAGE",
                assertThrows(IOException.class, () -> execute(earlyWrite, 1024)).getMessage());
        FakeSocket zeroOkay = new FakeSocket(packet(LocalAdb.OKAY, 0, LOCAL, new byte[0]));
        assertEquals(
                "ADB_STREAM",
                assertThrows(IOException.class, () -> execute(zeroOkay, 1024)).getMessage());
    }

    @Test
    public void otherLocalOrRemoteStreamIsRejectedIncludingClose() throws Exception {
        for (LocalAdb.Packet wrong :
                new LocalAdb.Packet[] {
                    packet(LocalAdb.CLSE, PEER, LOCAL + 1, new byte[0]),
                    packet(LocalAdb.CLSE, 0, LOCAL + 1, new byte[0]),
                    packet(LocalAdb.CLSE, PEER + 1, LOCAL, new byte[0]),
                    packet(LocalAdb.OKAY, PEER + 1, LOCAL, new byte[0]),
                    packet(LocalAdb.WRTE, 0, LOCAL, new byte[] {1}),
                    packet(LocalAdb.WRTE, PEER + 1, LOCAL, new byte[] {1})
                }) {
            FakeSocket socket = new FakeSocket(packet(LocalAdb.OKAY), wrong);
            assertEquals(
                    "ADB_STREAM",
                    assertThrows(IOException.class, () -> execute(socket, 1024)).getMessage());
        }
    }

    @Test
    public void byteLimitAndUnexpectedMessageStillFailClosed() throws Exception {
        FakeSocket overflow =
                new FakeSocket(
                        packet(LocalAdb.OKAY),
                        packet(LocalAdb.WRTE, PEER, LOCAL, new byte[] {1, 2, 3}));
        assertEquals(
                "ADB_LIMIT",
                assertThrows(IOException.class, () -> execute(overflow, 2)).getMessage());
        FakeSocket unknown = new FakeSocket(packet(LocalAdb.OKAY), packet(LocalAdb.AUTH));
        assertEquals(
                "ADB_MESSAGE",
                assertThrows(IOException.class, () -> execute(unknown, 1024)).getMessage());
    }

    @Test
    public void installAndCommandShareOpenAndCloseValidation() throws Exception {
        LocalAdb.StreamState stream = new LocalAdb.StreamState(LOCAL);
        stream.accept(packet(LocalAdb.OKAY));
        stream.accept(packet(LocalAdb.OKAY));
        stream.accept(packet(LocalAdb.WRTE));
        stream.accept(packet(LocalAdb.CLSE, 0, LOCAL, new byte[0]));
        assertEquals(PEER, stream.peer);
        LocalAdb.StreamState refused = new LocalAdb.StreamState(LOCAL);
        assertEquals(
                "ADB_OPEN",
                assertThrows(IOException.class, () -> refused.accept(packet(LocalAdb.CLSE)))
                        .getMessage());
    }

    @Test
    public void diagnosticsContainOnlyExceptionClassAndKnownProtocolCode() {
        assertEquals(
                "IOException:ADB_STREAM",
                LocalAdb.failureDiagnostic(new IOException("ADB_STREAM")));
        assertEquals(
                "SocketTimeoutException:IO_FAILURE",
                LocalAdb.failureDiagnostic(new SocketTimeoutException("פרט שאסור להדפיס")));
        assertEquals(
                "IOException:IO_FAILURE",
                LocalAdb.failureDiagnostic(new IOException("מפתח או פקודה פרטיים")));
        assertEquals("IOException:IO_FAILURE", LocalAdb.failureDiagnostic(new IOException()));
    }
}
