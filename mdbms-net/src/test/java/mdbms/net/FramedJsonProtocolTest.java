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
    void preservesSqlEscapedQuoteAndQuotedSemicolonInFramedRequest() throws Exception {
        String sql = "SELECT 'it''s; still part of the string';";
        TestSocket socket = new TestSocket(frame("{\"sql\":\"" + sql + "\"}"));
        FramedJsonProtocol protocol = new FramedJsonProtocol(socket, socket.getInputStream());

        assertEquals(sql, protocol.readSql());
    }

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

    @Test
    void disconnectCleanupContinuesWhenTransactionRollbackFails() {
        TestSocket socket = new TestSocket(frame("{\"sql\":\"BEGIN\"}"));
        TrackingSessionManager sessionManager = new TrackingSessionManager();
        int[] abortAttempts = {0};
        IConcurrencyControl concurrencyControl = new IConcurrencyControl() {
            @Override public int beginTransaction() { return 42; }
            @Override public void commitTransaction(int txId) { }
            @Override public void abortTransaction(int txId, String reason) {
                abortAttempts[0]++;
                throw new IllegalStateException("rollback failed");
            }
            @Override public AccessStatus requestRead(int txId, DataItem data) { return AccessStatus.ABORTED; }
            @Override public AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal) {
                return AccessStatus.ABORTED;
            }
            @Override public AccessStatus requestWrite(int txId, DataItem data) { return AccessStatus.ABORTED; }
            @Override public Transaction getTransaction(int txId) { return null; }
            @Override public boolean verifySchedule(List<Operation> manualSchedule) { return false; }
        };
        IQueryProcessor processor = (query, txId) -> new ExecutionResult(true, null, null, -1, null);

        new ClientHandler(socket, sessionManager, processor, concurrencyControl).run();

        assertEquals(1, abortAttempts[0]);
        assertTrue(sessionManager.removedSession);
        assertFalse(sessionManager.session.isTransacting());
        assertTrue(socket.closed);
    }

    @Test
    void beginAndRepeatedBeginHaveDefinedLifecycleOutcomes() throws Exception {
        TestSocket socket = new TestSocket(concat(
                frame("{\"sql\":\"BEGIN\"}"),
                frame("{\"sql\":\"BEGIN\"}"),
                frame("{\"sql\":\"COMMIT\"}")));
        IConcurrencyControl control = new IConcurrencyControl() {
            private int nextId = 42;
            private final java.util.Set<Integer> active = new java.util.HashSet<>();

            @Override public int beginTransaction() {
                active.add(nextId);
                return nextId++;
            }
            @Override public void commitTransaction(int txId) { active.remove(txId); }
            @Override public void abortTransaction(int txId, String reason) { active.remove(txId); }
            @Override public AccessStatus requestRead(int txId, DataItem data) { return AccessStatus.GRANTED; }
            @Override public AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal) {
                return AccessStatus.GRANTED;
            }
            @Override public AccessStatus requestWrite(int txId, DataItem data) { return AccessStatus.GRANTED; }
            @Override public Transaction getTransaction(int txId) { return null; }
            @Override public boolean verifySchedule(List<Operation> manualSchedule) { return false; }
        };

        new ClientHandler(socket, new SessionManager(), (query, txId) ->
                new ExecutionResult(true, null, null, -1, null), control).run();

        ByteArrayInputStream responses = new ByteArrayInputStream(socket.output.toByteArray());
        assertTrue(readFrame(responses).contains("BEGIN transaction 42"));
        assertTrue(readFrame(responses).contains("\"code\":\"TRANSACTION_STATE\""));
        assertTrue(readFrame(responses).contains("COMMIT transaction 42"));
    }

    @Test
    void commitAndAbortWithoutBeginHaveDefinedLifecycleOutcomes() throws Exception {
        TestSocket socket = new TestSocket(concat(
                frame("{\"sql\":\"COMMIT\"}"),
                frame("{\"sql\":\"ABORT\"}")));

        new ClientHandler(socket, new SessionManager(), (query, txId) ->
                new ExecutionResult(true, null, null, -1, null), rejectingControl()).run();

        ByteArrayInputStream responses = new ByteArrayInputStream(socket.output.toByteArray());
        assertTrue(readFrame(responses).contains("No active transaction"));
        assertTrue(readFrame(responses).contains("No active transaction"));
    }

    @Test
    void statementFailureAbortsExplicitTransactionOnce() throws Exception {
        TestSocket socket = new TestSocket(concat(
                frame("{\"sql\":\"BEGIN\"}"),
                frame("{\"sql\":\"SELECT 1\"}")));
        int[] abortAttempts = {0};
        IConcurrencyControl control = controlWithAbortCounter(abortAttempts);

        new ClientHandler(socket, new SessionManager(), (query, txId) ->
                new ExecutionResult(false, null, null, 0, "statement failed"), control).run();

        ByteArrayInputStream responses = new ByteArrayInputStream(socket.output.toByteArray());
        assertTrue(readFrame(responses).contains("BEGIN transaction"));
        assertTrue(readFrame(responses).contains("statement failed"));
        assertEquals(1, abortAttempts[0]);
    }

    @Test
    void commitFailureReturnsFailedOutcomeAndDoesNotClearThroughSuccessPath() throws Exception {
        TestSocket socket = new TestSocket(concat(
                frame("{\"sql\":\"BEGIN\"}"),
                frame("{\"sql\":\"COMMIT\"}")));
        int[] commits = {0};
        IConcurrencyControl control = new IConcurrencyControl() {
            @Override public int beginTransaction() { return 42; }
            @Override public void commitTransaction(int txId) {
                commits[0]++;
                throw new IllegalStateException("commit log failed");
            }
            @Override public void abortTransaction(int txId, String reason) { }
            @Override public AccessStatus requestRead(int txId, DataItem data) { return AccessStatus.GRANTED; }
            @Override public AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal) {
                return AccessStatus.GRANTED;
            }
            @Override public AccessStatus requestWrite(int txId, DataItem data) { return AccessStatus.GRANTED; }
            @Override public Transaction getTransaction(int txId) { return null; }
            @Override public boolean verifySchedule(List<Operation> manualSchedule) { return false; }
        };

        new ClientHandler(socket, new SessionManager(), (query, txId) ->
                new ExecutionResult(true, null, null, -1, null), control).run();

        ByteArrayInputStream responses = new ByteArrayInputStream(socket.output.toByteArray());
        String beginResponse = readFrame(responses);
        String commitResponse = readFrame(responses);
        assertTrue(beginResponse.contains("BEGIN transaction"));
        assertTrue(commitResponse.contains("\"status\":\"ERROR\""));
        assertTrue(commitResponse.contains("COMMIT failed"));
        assertEquals(1, commits[0]);
    }

    @Test
    void autocommitInvokesCommitOnce() throws Exception {
        TestSocket socket = new TestSocket(frame("{\"sql\":\"INSERT INTO users VALUES (1)\"}"));
        int[] beginAttempts = {0};
        int[] commitAttempts = {0};
        int[] abortAttempts = {0};
        IConcurrencyControl control = controlWithLifecycleCounters(beginAttempts, commitAttempts, abortAttempts);

        new ClientHandler(socket, new SessionManager(), (query, txId) ->
                new ExecutionResult(true, null, null, 1, null), control).run();

        assertEquals(1, beginAttempts[0]);
        assertEquals(1, commitAttempts[0]);
        assertEquals(0, abortAttempts[0]);
    }

    @Test
    void explicitMultiStatementTransactionCommitsOnceWithoutPerStatementCommit() throws Exception {
        TestSocket socket = new TestSocket(concat(
                frame("{\"sql\":\"BEGIN\"}"),
                frame("{\"sql\":\"INSERT INTO users VALUES (1)\"}"),
                frame("{\"sql\":\"UPDATE users SET id = 2\"}"),
                frame("{\"sql\":\"COMMIT\"}")));
        int[] beginAttempts = {0};
        int[] commitAttempts = {0};
        int[] abortAttempts = {0};
        IConcurrencyControl control = controlWithLifecycleCounters(beginAttempts, commitAttempts, abortAttempts);

        new ClientHandler(socket, new SessionManager(), (query, txId) ->
                new ExecutionResult(true, null, null, 1, null), control).run();

        assertEquals(1, beginAttempts[0]);
        assertEquals(1, commitAttempts[0]);
        assertEquals(0, abortAttempts[0]);
    }

    @Test
    void explicitMultiStatementFailureAbortsOnce() throws Exception {
        TestSocket socket = new TestSocket(concat(
                frame("{\"sql\":\"BEGIN\"}"),
                frame("{\"sql\":\"INSERT INTO users VALUES (1)\"}"),
                frame("{\"sql\":\"SELECT FROM\"}")));
        int[] beginAttempts = {0};
        int[] commitAttempts = {0};
        int[] abortAttempts = {0};
        IConcurrencyControl control = controlWithLifecycleCounters(beginAttempts, commitAttempts, abortAttempts);

        new ClientHandler(socket, new SessionManager(), (query, txId) ->
                query.startsWith("SELECT") ? new ExecutionResult(false, null, null, 0, "statement failed")
                        : new ExecutionResult(true, null, null, 1, null), control).run();

        assertEquals(1, beginAttempts[0]);
        assertEquals(0, commitAttempts[0]);
        assertEquals(1, abortAttempts[0]);
    }

    @Test
    void disconnectDuringAutocommitExecutionAbortsAdmittedTransactionOnce() throws Exception {
        TestSocket socket = new TestSocket(frame("{\"sql\":\"SELECT 1\"}"));
        SessionManager sessionManager = new SessionManager();
        java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        int[] abortAttempts = {0};
        IConcurrencyControl control = new IConcurrencyControl() {
            @Override public int beginTransaction() { return 42; }
            @Override public void commitTransaction(int txId) { }
            @Override public void abortTransaction(int txId, String reason) { abortAttempts[0]++; }
            @Override public AccessStatus requestRead(int txId, DataItem data) { return AccessStatus.GRANTED; }
            @Override public AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal) {
                return AccessStatus.GRANTED;
            }
            @Override public AccessStatus requestWrite(int txId, DataItem data) { return AccessStatus.GRANTED; }
            @Override public Transaction getTransaction(int txId) { return null; }
            @Override public boolean verifySchedule(List<Operation> manualSchedule) { return false; }
        };
        IQueryProcessor processor = (query, txId) -> {
            entered.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ExecutionResult(true, null, null, -1, null);
        };

        ClientHandler handler = new ClientHandler(socket, sessionManager, processor, control);
        java.util.concurrent.ExecutorService executor =
                java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<?> run = executor.submit(handler);
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            handler.disconnect();
            release.countDown();
            run.get(5, java.util.concurrent.TimeUnit.SECONDS);
        } finally {
            release.countDown();
            executor.shutdownNow();
        }

        assertEquals(1, abortAttempts[0]);
        assertEquals(0, sessionManager.getSessionCount());
    }

    private IConcurrencyControl controlWithLifecycleCounters(
            int[] beginAttempts, int[] commitAttempts, int[] abortAttempts) {
        return new IConcurrencyControl() {
            private Transaction transaction;

            @Override public int beginTransaction() {
                beginAttempts[0]++;
                transaction = new Transaction(42, 1L);
                return 42;
            }
            @Override public void commitTransaction(int txId) {
                commitAttempts[0]++;
                transaction = null;
            }
            @Override public void abortTransaction(int txId, String reason) {
                abortAttempts[0]++;
                transaction = null;
            }
            @Override public AccessStatus requestRead(int txId, DataItem data) { return AccessStatus.GRANTED; }
            @Override public AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal) {
                return AccessStatus.GRANTED;
            }
            @Override public AccessStatus requestWrite(int txId, DataItem data) { return AccessStatus.GRANTED; }
            @Override public Transaction getTransaction(int txId) {
                return transaction != null && transaction.getId() == txId ? transaction : null;
            }
            @Override public boolean verifySchedule(List<Operation> manualSchedule) { return false; }
        };
    }

    private IConcurrencyControl rejectingControl() {
        return new IConcurrencyControl() {
            @Override public int beginTransaction() { throw new IllegalStateException("paused"); }
            @Override public void commitTransaction(int txId) { }
            @Override public void abortTransaction(int txId, String reason) { }
            @Override public AccessStatus requestRead(int txId, DataItem data) { return AccessStatus.ABORTED; }
            @Override public AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal) {
                return AccessStatus.ABORTED;
            }
            @Override public AccessStatus requestWrite(int txId, DataItem data) { return AccessStatus.ABORTED; }
            @Override public Transaction getTransaction(int txId) { return null; }
            @Override public boolean verifySchedule(List<Operation> manualSchedule) { return false; }
        };
    }

    private IConcurrencyControl controlWithAbortCounter(int[] abortAttempts) {
        return controlWithLifecycleCounters(new int[1], new int[1], abortAttempts);
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
        private boolean closed;

        private TestSocket(byte[] input) {
            this.input = new ByteArrayInputStream(input);
        }

        @Override public InputStream getInputStream() { return input; }
        @Override public OutputStream getOutputStream() { return output; }
        @Override public void setSoTimeout(int timeout) { this.timeout = timeout; }
        @Override public int getSoTimeout() { return timeout; }
        @Override public void close() throws IOException {
            closed = true;
            super.close();
        }
    }

    private static final class TrackingSessionManager extends SessionManager {
        private ClientSession session;
        private boolean removedSession;

        @Override
        public ClientSession newSession() {
            session = super.newSession();
            return session;
        }

        @Override
        public boolean removeSession(String sessionId) {
            removedSession = true;
            return super.removeSession(sessionId);
        }
    }
}
