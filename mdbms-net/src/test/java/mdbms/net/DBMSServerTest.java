package mdbms.net;

import static org.junit.jupiter.api.Assertions.*;

import java.io.*;
import java.lang.reflect.Proxy;
import java.net.*;
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

    private Future<?> startAsync(DBMSServer server) {
        return executor.submit(() -> { server.start(); return null; });
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(3_000);
        return socket;
    }

    private static void send(Socket socket, String sql) throws IOException {
        socket.getOutputStream().write(sql.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private static String query(Socket socket, String sql) throws IOException {
        send(socket, sql);
        return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
    }
}
