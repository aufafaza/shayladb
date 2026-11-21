package mdbms.net;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.lang.reflect.Proxy;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IQueryProcessor;
import mdbms.models.query.ExecutionResult;
import org.junit.jupiter.api.*;

@Timeout(value = 15, unit = TimeUnit.SECONDS)
class DBMSServerTest {
    private static final IQueryProcessor OK_PROCESSOR = (sql, txId) -> new ExecutionResult(true, List.of(), List.of(), 0, "OK");
    private static final IConcurrencyControl STUB_CONCURRENCY = (IConcurrencyControl) Proxy.newProxyInstance(
            IConcurrencyControl.class.getClassLoader(), new Class<?>[] {IConcurrencyControl.class},
            (proxy, method, args) -> "beginTransaction".equals(method.getName()) ? 1 : null);

    private final ExecutorService executor = Executors.newCachedThreadPool();

    @AfterEach
    void tearDown() { executor.shutdownNow(); }

    @Test
    void lifecycleStartServeStopAndRebind() throws Exception {
        DBMSServer unused = new DBMSServer(0, OK_PROCESSOR, STUB_CONCURRENCY);
        assertDoesNotThrow(unused::shutdown);
        assertDoesNotThrow(unused::shutdown);
        assertThrows(IllegalStateException.class, unused::start);

        DBMSServer first = new DBMSServer(0, OK_PROCESSOR, STUB_CONCURRENCY);
        Future<?> firstFuture = startAsync(first);
        int port;
        try {
            assertTrue(first.awaitReady(5, TimeUnit.SECONDS));
            port = first.getBoundPort();
            assertTrue(port > 0 && first.isRunning());
            assertThrows(IllegalStateException.class, first::start);

            try (Socket client = connect(port)) {
                assertEquals("OK", query(client, "SELECT 1;"));
                assertEquals(1, first.getActiveClientCount());
                first.shutdown();
                assertDoesNotThrow(first::shutdown);
                try { assertEquals(-1, client.getInputStream().read()); } catch (IOException ignored) {}
            }
            assertThrows(ConnectException.class, () -> { try (Socket refused = connect(port)) {} });
        } finally { first.shutdown(); }

        firstFuture.get(5, TimeUnit.SECONDS);
        assertEquals(DBMSServer.State.STOPPED, first.getState());
        assertEquals(-1, first.getBoundPort());
        assertEquals(0, first.getActiveClientCount());
        assertTrue(first.isWorkerPoolTerminated());
        assertThrows(IllegalStateException.class, first::start);

        DBMSServer second = new DBMSServer(port, OK_PROCESSOR, STUB_CONCURRENCY);
        Future<?> secondFuture = startAsync(second);
        try {
            assertTrue(second.awaitReady(5, TimeUnit.SECONDS));
            assertEquals(port, second.getBoundPort());
            try (Socket client = connect(port)) { assertEquals("OK", query(client, "SELECT 1;")); }
        } finally { second.shutdown(); }
        secondFuture.get(5, TimeUnit.SECONDS);
        assertTrue(second.isWorkerPoolTerminated());
    }

    @Test
    void repeatedRequestsAndIdleDisconnectsLeaveNoSessionsOrHandlers() throws Exception {
        DBMSServer server = new DBMSServer(0, OK_PROCESSOR, STUB_CONCURRENCY);
        Future<?> future = startAsync(server);

        try {
            assertTrue(server.awaitReady(5, TimeUnit.SECONDS));
            for (int i = 0; i < 25; i++) {
                try (Socket client = connect(server.getBoundPort())) {
                    assertEquals("OK", query(client, "SELECT " + i + ";"));
                }
            }

            for (int i = 0; i < 25; i++) {
                Socket idle = connect(server.getBoundPort());
                idle.close();
            }

            assertTrue(waitFor(() -> server.getActiveClientCount() == 0
                    && server.getActiveHandlerCount() == 0
                    && server.getActiveSessionCount() == 0, 5_000));
        } finally {
            server.shutdown();
        }

        future.get(5, TimeUnit.SECONDS);
        assertEquals(0, server.getActiveClientCount());
        assertEquals(0, server.getActiveHandlerCount());
        assertEquals(0, server.getActiveSessionCount());
    }

    @Test
    void bindFailurePropagatesWithoutLeak() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            DBMSServer server = new DBMSServer(occupied.getLocalPort(), OK_PROCESSOR, STUB_CONCURRENCY);
            assertThrows(BindException.class, server::start);
            assertFalse(server.isRunning());
            assertEquals(DBMSServer.State.FAILED, server.getState());
            assertEquals(-1, server.getBoundPort());
            assertTrue(server.isWorkerPoolTerminated());
            assertFalse(server.awaitReady(1, TimeUnit.SECONDS));
            assertTrue(server.awaitStopped(1, TimeUnit.SECONDS));
            assertDoesNotThrow(server::shutdown);
            assertThrows(IllegalStateException.class, server::start);
        }
    }

    @Test
    void shutdownInterruptsBlockedWorkerAfterTimeout() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), interrupted = new CountDownLatch(1), block = new CountDownLatch(1);
        DBMSServer server = new DBMSServer(0, (sql, txId) -> {
            entered.countDown();
            try { block.await(); } catch (InterruptedException e) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return new ExecutionResult(false, List.of(), List.of(), 0, "interrupted");
        }, STUB_CONCURRENCY);
        server.shutdownTimeoutMs = 150L;

        Future<?> future = startAsync(server);
        assertTrue(server.awaitReady(5, TimeUnit.SECONDS));
        try (Socket client = connect(server.getBoundPort())) {
            send(client, "SELECT 1;");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            server.shutdown();
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
            future.get(5, TimeUnit.SECONDS);
            assertTrue(server.isWorkerPoolTerminated());
        } finally { block.countDown(); }
    }

    @Test
    void rejectsClientsWhenWorkerAndQueueCapacityAreFull() throws Exception {
        CountDownLatch entered = new CountDownLatch(DBMSServer.DEFAULT_POOL_SIZE);
        CountDownLatch block = new CountDownLatch(1);
        DBMSServer server = new DBMSServer(0, (sql, txId) -> {
            entered.countDown();
            try {
                block.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new ExecutionResult(false, List.of(), List.of(), 0, "interrupted");
        }, STUB_CONCURRENCY);

        Future<?> future = startAsync(server);
        assertTrue(server.awaitReady(5, TimeUnit.SECONDS));
        List<Socket> accepted = new CopyOnWriteArrayList<>();
        try {
            for (int i = 0; i < DBMSServer.DEFAULT_POOL_SIZE + DBMSServer.DEFAULT_PENDING_CLIENTS; i++) {
                Socket client = connect(server.getBoundPort());
                accepted.add(client);
                send(client, "SELECT 1;");
            }
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            try (Socket rejected = connect(server.getBoundPort())) {
                send(rejected, "SELECT 1;");
                rejected.setSoTimeout(2_000);
                String response = readFrame(rejected);
                assertTrue(response.contains("\"code\":\"CAPACITY_REACHED\""));
            }
        } finally {
            accepted.forEach(DBMSServerTest::closeQuietly);
            server.shutdown();
            block.countDown();
        }
        future.get(5, TimeUnit.SECONDS);
    }

    @Test
    void servesJsonAndInteractiveClientsOnSeparatePorts() throws Exception {
        DBMSServer jsonServer = new DBMSServer(0, OK_PROCESSOR, STUB_CONCURRENCY);
        InteractiveDBMSServer interactiveServer =
                new InteractiveDBMSServer(0, OK_PROCESSOR, STUB_CONCURRENCY);
        Future<?> jsonFuture = startAsync(jsonServer);
        Future<?> interactiveFuture = startAsync(interactiveServer);

        try {
            assertTrue(jsonServer.awaitReady(5, TimeUnit.SECONDS));
            assertTrue(interactiveServer.awaitReady(5, TimeUnit.SECONDS));
            assertNotEquals(jsonServer.getBoundPort(), interactiveServer.getBoundPort());

            try (Socket client = connect(jsonServer.getBoundPort())) {
                assertEquals("OK", query(client, "SELECT 1;"));
            }

            try (Socket client = connect(interactiveServer.getBoundPort())) {
                client.getOutputStream().write(new byte[] {(byte) 255, (byte) 253, 1});
                client.getOutputStream().write("SELECT 1;\r\n".getBytes(StandardCharsets.UTF_8));
                client.getOutputStream().flush();

                InputStream input = client.getInputStream();
                byte[] negotiation = input.readNBytes(6);
                assertEquals(6, negotiation.length);
                BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
                assertEquals("ShaylaDB> SELECT 1;", reader.readLine());
                assertTrue(reader.readLine().startsWith("Time: "));
                assertEquals("(0 rows affected)", reader.readLine());
                assertEquals("OK", reader.readLine());
            }
        } finally {
            jsonServer.shutdown();
            interactiveServer.shutdown();
        }

        jsonFuture.get(5, TimeUnit.SECONDS);
        interactiveFuture.get(5, TimeUnit.SECONDS);
    }

    private Future<?> startAsync(DBMSServer server) {
        return executor.submit(() -> { server.start(); return null; });
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(3_000);
        return socket;
    }

    private static void send(Socket socket, String sql) throws IOException {
        byte[] payload = ("{\"sql\":\"" + sql.replace("\"", "\\\"") + "\"}")
                .getBytes(StandardCharsets.UTF_8);
        socket.getOutputStream().write(ByteBuffer.allocate(4).putInt(payload.length).array());
        socket.getOutputStream().write(payload);
        socket.getOutputStream().flush();
    }

    private static String query(Socket socket, String sql) throws IOException {
        send(socket, sql);
        String response = readFrame(socket);
        return response.contains("\"message\":\"OK\"") ? "OK" : response;
    }

    private static String readFrame(Socket socket) throws IOException {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        int length = input.readInt();
        byte[] payload = input.readNBytes(length);
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static void closeQuietly(Socket socket) {
        try {
            socket.close();
        } catch (IOException ignored) {
        }
    }

    private static boolean waitFor(java.util.concurrent.Callable<Boolean> condition, long timeoutMillis)
            throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (condition.call()) {
                return true;
            }
            Thread.sleep(10);
        }
        return condition.call();
    }
}
