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
    public void delayedDuplicateCloseAfterWriteAckDoesNotBreakFollowingExecutes() throws Exception {
        byte[] echo = new byte[] {'k', 'a', 'r', 'o', 'v', '\n'};
        // סדר החבילות מהסטרימר הפיזי: לאחר ACK לנתונים מתקבלים CLSE רגיל ועוד עותק מאוחר,
        // שנקרא רק אחרי OPEN של הפקודה הבאה על אותו socket. אין שידור חוזר של פקודה.
        FakeSocket socket =
                new FakeSocket(
                        packet(LocalAdb.OKAY, 42, 1, new byte[0]),
                        packet(LocalAdb.WRTE, 42, 1, echo),
                        packet(LocalAdb.CLSE, 42, 1, new byte[0]),
                        packet(LocalAdb.CLSE, 42, 1, new byte[0]),
                        packet(LocalAdb.OKAY, 43, 2, new byte[0]),
                        packet(LocalAdb.WRTE, 43, 2, echo),
                        packet(LocalAdb.CLSE, 43, 2, new byte[0]),
                        packet(LocalAdb.CLSE, 0, 2, new byte[0]),
                        packet(LocalAdb.OKAY, 44, 3, new byte[0]),
                        packet(LocalAdb.WRTE, 44, 3, echo),
                        packet(LocalAdb.CLSE, 44, 3, new byte[0]));
        LocalAdb.StreamHistory history = new LocalAdb.StreamHistory(socket, 7);
        for (int local = 1; local <= 3; local++)
            assertArrayEquals(
                    echo, LocalAdb.execute(socket, "echo karov", 1024, local, history, 7));
        assertEquals(6, socket.writes.size());
        for (int index = 0; index < 3; index++) {
            LocalAdb.Packet open = socket.writes.get(index * 2);
            LocalAdb.Packet ack = socket.writes.get(index * 2 + 1);
            assertEquals(LocalAdb.OPEN, open.command);
            assertEquals(index + 1, open.arg0);
            assertEquals(LocalAdb.OKAY, ack.command);
            assertEquals(index + 1, ack.arg0);
            assertEquals(42 + index, ack.arg1);
        }
    }

    @Test
    public void closedStreamHistoryNeverHidesForeignDataOrUnrecognizedClose() throws Exception {
        for (LocalAdb.Packet foreign :
                new LocalAdb.Packet[] {
                    packet(LocalAdb.CLSE, 43, 1, new byte[0]),
                    packet(LocalAdb.WRTE, 42, 1, new byte[] {1}),
                    packet(LocalAdb.OKAY, 42, 1, new byte[0]),
                    packet(LocalAdb.CLSE, 42, 999, new byte[0]),
                    packet(LocalAdb.CLSE, 0, 999, new byte[0])
                }) {
            FakeSocket socket =
                    new FakeSocket(
                            packet(LocalAdb.OKAY, 42, 1, new byte[0]),
                            packet(LocalAdb.CLSE, 42, 1, new byte[0]),
                            foreign);
            LocalAdb.StreamHistory history = new LocalAdb.StreamHistory(socket, 7);
            LocalAdb.execute(socket, "echo karov", 1024, 1, history, 7);
            assertEquals(
                    "ADB_STREAM",
                    assertThrows(
                                    IOException.class,
                                    () ->
                                            LocalAdb.execute(
                                                    socket, "echo karov", 1024, 2, history, 7))
                            .getMessage());
        }
    }

    @Test
    public void historyRejectsAnotherSocketOrGenerationBeforeSendingOpen() throws Exception {
        FakeSocket first = new FakeSocket(), second = new FakeSocket();
        LocalAdb.StreamHistory history = new LocalAdb.StreamHistory(first, 7);
        assertEquals(
                "ADB_STREAM",
                assertThrows(
                                IOException.class,
                                () -> LocalAdb.execute(second, "echo karov", 1024, 1, history, 7))
                        .getMessage());
        assertEquals(
                "ADB_STREAM",
                assertThrows(
                                IOException.class,
                                () -> LocalAdb.execute(first, "echo karov", 1024, 1, history, 8))
                        .getMessage());
        assertTrue(first.writes.isEmpty());
        assertTrue(second.writes.isEmpty());
    }

    @Test
    public void sharedRawStreamHistoryIsBoundedAndRetainsInstallStreamValidation()
            throws Exception {
        FakeSocket socket = new FakeSocket();
        LocalAdb.StreamHistory history = new LocalAdb.StreamHistory(socket, 7);
        for (int local = 1; local <= LocalAdb.StreamHistory.LIMIT + 1; local++) {
            LocalAdb.StreamState stream = new LocalAdb.StreamState(local);
            assertTrue(
                    history.accept(
                            socket,
                            7,
                            stream,
                            packet(LocalAdb.OKAY, 100 + local, local, new byte[0])));
            assertTrue(
                    history.accept(
                            socket,
                            7,
                            stream,
                            packet(LocalAdb.CLSE, 100 + local, local, new byte[0])));
        }
        // אותו מסנן משמש גם את זרם העלאת העדכון, לפני טיפול ב-ACK או בנתוני הפלט שלו.
        LocalAdb.StreamState upload = new LocalAdb.StreamState(999);
        assertEquals(
                "ADB_STREAM",
                assertThrows(
                                IOException.class,
                                () ->
                                        history.accept(
                                                socket,
                                                7,
                                                upload,
                                                packet(LocalAdb.CLSE, 101, 1, new byte[0])))
                        .getMessage());
        assertFalse(history.accept(socket, 7, upload, packet(LocalAdb.CLSE, 102, 2, new byte[0])));
        assertTrue(
                history.accept(socket, 7, upload, packet(LocalAdb.OKAY, 9999, 999, new byte[0])));
        assertTrue(
                history.accept(
                        socket, 7, upload, packet(LocalAdb.WRTE, 9999, 999, new byte[] {1})));
        assertEquals(
                "ADB_MESSAGE",
                assertThrows(
                                IOException.class,
                                () ->
                                        history.accept(
                                                socket,
                                                7,
                                                upload,
                                                packet(LocalAdb.CLSE, 102, 2, new byte[] {1})))
                        .getMessage());
        assertTrue(history.accept(socket, 7, upload, packet(LocalAdb.CLSE, 0, 999, new byte[0])));
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
