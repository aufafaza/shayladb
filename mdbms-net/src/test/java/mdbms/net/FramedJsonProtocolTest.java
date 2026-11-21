package mdbms.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IQueryProcessor;
import mdbms.models.data.DataItem;
import mdbms.models.query.ExecutionResult;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Operation;
import mdbms.models.transaction.Transaction;
import org.junit.jupiter.api.Test;

class FramedJsonProtocolTest {
    @Test
    void readsUnicodeAndMultipleRequestsWithoutScanningSemicolons() throws Exception {
        TestSocket socket = new TestSocket(concat(
                frame("{\"sql\":\"SELECT 'café; 😀'\"}"),
                frame("{\"sql\":\"SELECT '東京; still one request'\"}"),
                frame("{\"sql\":\"SELECT \\\"escaped ; quote\\\"\"}")));
        FramedJsonProtocol protocol = new FramedJsonProtocol(socket, socket.getInputStream());

        assertEquals("SELECT 'café; 😀'", protocol.readSql());
        assertEquals("SELECT '東京; still one request'", protocol.readSql());
        assertEquals("SELECT \"escaped ; quote\"", protocol.readSql());
    }

    @Test
    void writesOneValidLengthPrefixedOutcomeForNullableRows() throws Exception {
        TestSocket socket = new TestSocket(new byte[0]);
        FramedJsonProtocol protocol = new FramedJsonProtocol(socket, socket.getInputStream());

        protocol.writeResponse(FramedJsonProtocol.fromExecutionResult(new ExecutionResult(true,
                List.of("id", "name"), List.of(Arrays.asList(1, null)), -1, null), 12));

        String response = readFrame(socket.output.toByteArray());
        assertEquals("{\"status\":\"RESULT\",\"elapsedMs\":12,\"columns\":[\"id\",\"name\"],\"rows\":[[1,null]]}", response);
    }

    @Test
    void distinguishesEmptyWriteAndAdmissionFailureOutcomes() throws Exception {
        TestSocket socket = new TestSocket(new byte[0]);
        FramedJsonProtocol protocol = new FramedJsonProtocol(socket, socket.getInputStream());

        protocol.writeResponse(FramedJsonProtocol.fromExecutionResult(new ExecutionResult(true,
                null, null, -1, null), 0));
        protocol.writeResponse(FramedJsonProtocol.fromExecutionResult(new ExecutionResult(true,
                null, null, 0, null), 0));
        protocol.writeResponse(FramedJsonProtocol.fromExecutionResult(new ExecutionResult(false,
                null, null, 0, "Transaction admission rejected: paused"), 0));

        ByteArrayInputStream responses = new ByteArrayInputStream(socket.output.toByteArray());
        assertTrue(readFrame(responses).contains("\"status\":\"EMPTY\""));
        assertTrue(readFrame(responses).contains("\"status\":\"WRITE\",\"elapsedMs\":0,\"affectedRows\":0"));
        assertTrue(readFrame(responses).contains("\"code\":\"ADMISSION_REJECTED\""));
    }

    @Test
    void rejectsMalformedAndOversizedFrames() throws Exception {
        TestSocket malformed = new TestSocket(frame("{\"sql\":17}"));
        ProtocolException malformedError = assertThrows(ProtocolException.class,
                () -> new FramedJsonProtocol(malformed, malformed.getInputStream()).readSql());
        assertEquals("INVALID_REQUEST", malformedError.code());
        assertFalse(malformedError.closeConnection());

        TestSocket duplicate = new TestSocket(frame("{\"sql\":\"SELECT 1\",\"sql\":\"SELECT 2\"}"));
        ProtocolException duplicateError = assertThrows(ProtocolException.class,
                () -> new FramedJsonProtocol(duplicate, duplicate.getInputStream()).readSql());
        assertEquals("INVALID_REQUEST", duplicateError.code());
        assertFalse(duplicateError.closeConnection());

        TestSocket trailing = new TestSocket(frame("{\"sql\":\"SELECT 1\"} trailing"));
        ProtocolException trailingError = assertThrows(ProtocolException.class,
                () -> new FramedJsonProtocol(trailing, trailing.getInputStream()).readSql());
        assertEquals("INVALID_REQUEST", trailingError.code());
        assertFalse(trailingError.closeConnection());

        TestSocket oversized = new TestSocket(ByteBuffer.allocate(4)
                .putInt(FramedJsonProtocol.MAX_FRAME_BYTES + 1).array());
        ProtocolException oversizedError = assertThrows(ProtocolException.class,
                () -> new FramedJsonProtocol(oversized, oversized.getInputStream()).readSql());
        assertEquals("REQUEST_TOO_LARGE", oversizedError.code());
        assertTrue(oversizedError.closeConnection());
    }

    @Test
    void rejectsEofDuringAFrameInsteadOfTreatingItAsAQuery() throws Exception {
        TestSocket incomplete = new TestSocket(concat(ByteBuffer.allocate(4).putInt(12).array(),
                "{\"sql\":\"x".getBytes(StandardCharsets.UTF_8)));

        ProtocolException error = assertThrows(ProtocolException.class,
                () -> new FramedJsonProtocol(incomplete, incomplete.getInputStream()).readSql());

        assertEquals("INCOMPLETE_REQUEST", error.code());
        assertTrue(error.closeConnection());
    }

    @Test
    void handlerRepliesWhenTransactionAdmissionFails() throws Exception {
        TestSocket socket = new TestSocket(frame("{\"sql\":\"SELECT 1\"}"));
        IConcurrencyControl rejectingControl = new IConcurrencyControl() {
            @Override public int beginTransaction() { throw new IllegalStateException("paused"); }
            @Override public void commitTransaction(int txId) { }
            @Override public void abortTransaction(int txId, String reason) { }
            @Override public AccessStatus requestRead(int txId, DataItem data) { return AccessStatus.ABORTED; }
            @Override public AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal) { return AccessStatus.ABORTED; }
            @Override public AccessStatus requestWrite(int txId, DataItem data) { return AccessStatus.ABORTED; }
            @Override public Transaction getTransaction(int txId) { return null; }
            @Override public boolean verifySchedule(List<Operation> manualSchedule) { return false; }
        };
        IQueryProcessor unusedProcessor = (query, txId) -> {
            throw new AssertionError("Processor must not run when admission fails");
        };

        new ClientHandler(socket, new SessionManager(), unusedProcessor, rejectingControl).run();

        assertTrue(readFrame(socket.output.toByteArray()).contains("\"code\":\"ADMISSION_REJECTED\""));
    }

    private static byte[] frame(String payload) {
        byte[] body = payload.getBytes(StandardCharsets.UTF_8);
        return concat(ByteBuffer.allocate(4).putInt(body.length).array(), body);
    }

    private static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream result = new ByteArrayOutputStream();
        for (byte[] part : parts) result.writeBytes(part);
        return result.toByteArray();
    }

    private static String readFrame(byte[] bytes) throws IOException {
        return readFrame(new ByteArrayInputStream(bytes));
    }

    private static String readFrame(InputStream input) throws IOException {
        byte[] header = input.readNBytes(4);
        int length = ByteBuffer.wrap(header).getInt();
        return new String(input.readNBytes(length), StandardCharsets.UTF_8);
    }

    private static final class TestSocket extends Socket {
        private final InputStream input;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private int timeout;

        private TestSocket(byte[] input) {
            this.input = new ByteArrayInputStream(input);
        }

        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void setSoTimeout(int timeout) { this.timeout = timeout; }
        @Override public int getSoTimeout() { return timeout; }
    }
}
