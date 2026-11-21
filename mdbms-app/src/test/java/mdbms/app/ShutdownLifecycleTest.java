package mdbms.app;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.DataInputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import mdbms.concurrency.ConcurrencyControlManager;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.net.DBMSServer;
import mdbms.storage.StorageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class ShutdownLifecycleTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Test
    void shutdownAbortsOwnersAndWaitersAndReopensDurableData(@TempDir Path directory)
            throws Exception {
        DBMSConfig config = new DBMSConfig(directory.toString(), 16, ConcurrencyAlgorithm.LOCK_BASED);
        StorageManager storage = new StorageManager(directory.toString(), 16, 4);
        storage.updateTableSchema(new TableSchema("users", List.of(
                new ColumnDefinition("id", DataType.INTEGER, 0, false),
                new ColumnDefinition("name", DataType.VARCHAR, 50, false)), List.of("id"), List.of()));
        DBMS dbms = new DBMS(config);
        dbms.start();
        ConcurrencyControlManager concurrency = (ConcurrencyControlManager) dbms.getConcurrencyControl();
        DBMSServer server = new DBMSServer(0, dbms.getQueryProcessor(), concurrency);
        dbms.registerServer(server);
        Future<?> serverFuture = executor.submit(() -> { server.start(); return null; });
        try {
            assertTrue(server.awaitReady(5, TimeUnit.SECONDS));
            int port = server.getBoundPort();
            try (Socket idle = connect(port); Socket owner = connect(port); Socket waiter = connect(port)) {
                assertTrue(query(owner, "INSERT INTO users VALUES (1, 'Committed');").contains("WRITE"));
                assertTrue(query(owner, "BEGIN").contains("BEGIN"));
                assertTrue(query(owner, "UPDATE users SET name = 'Uncommitted' WHERE id = 1;").contains("WRITE"));
                assertTrue(query(waiter, "BEGIN").contains("BEGIN"));
                send(waiter, "UPDATE users SET name = 'Waiting' WHERE id = 1;");
                assertTrue(waitFor(concurrency::getWaitingRequestCount, 1, 5_000));
                // An active checkpoint waiting for these transactions must also be cancelled.
                Thread checkpoint = new Thread(() -> {
                    try { dbms.checkpoint(); } catch (IllegalStateException expected) { }
                });
                checkpoint.start();
                assertTrue(waitFor(() -> !concurrency.isAcceptingTransactions(), 2_000));
                dbms.shutdown();
                checkpoint.join(2_000);
                org.junit.jupiter.api.Assertions.assertFalse(checkpoint.isAlive());
                dbms.shutdown();
                org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class, dbms::start);
                org.junit.jupiter.api.Assertions.assertEquals(0, concurrency.getActiveTransactionCount());
                org.junit.jupiter.api.Assertions.assertEquals(0, concurrency.getWaitingRequestCount());
                org.junit.jupiter.api.Assertions.assertEquals(0, concurrency.getLockCount());
                org.junit.jupiter.api.Assertions.assertEquals(0, server.getActiveSessionCount());
                org.junit.jupiter.api.Assertions.assertEquals(DBMSServer.State.STOPPED, server.getState());
                serverFuture.get(2, TimeUnit.SECONDS);
            }
            DBMS reopened = new DBMS(config);
            reopened.start();
            DBMSServer replacement = new DBMSServer(port, reopened.getQueryProcessor(), reopened.getConcurrencyControl());
            reopened.registerServer(replacement);
            Future<?> replacementFuture = executor.submit(() -> { replacement.start(); return null; });
            try {
                assertTrue(replacement.awaitReady(5, TimeUnit.SECONDS));
                try (Socket client = connect(port)) {
                    String rows = query(client, "SELECT name FROM users WHERE id = 1;");
                    assertTrue(rows.contains("Committed"), rows);
                    org.junit.jupiter.api.Assertions.assertFalse(rows.contains("Uncommitted"), rows);
                    org.junit.jupiter.api.Assertions.assertFalse(rows.contains("Waiting"), rows);
                }
            } finally {
                reopened.shutdown();
                replacementFuture.get(2, TimeUnit.SECONDS);
            }
        } finally {
            try { dbms.shutdown(); } finally { executor.shutdownNow(); }
        }
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(5_000);
        return socket;
    }

    private static String query(Socket socket, String sql) throws IOException {
        send(socket, sql);
        return readFrame(socket);
    }

    private static void send(Socket socket, String sql) throws IOException {
        byte[] payload = ("{\"sql\":\""
                + sql.replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"}").getBytes(StandardCharsets.UTF_8);
        socket.getOutputStream().write(ByteBuffer.allocate(4).putInt(payload.length).array());
        socket.getOutputStream().write(payload);
        socket.getOutputStream().flush();
    }

    private static String readFrame(Socket socket) throws IOException {
        DataInputStream input = new DataInputStream(socket.getInputStream());
        int length = input.readInt();
        byte[] payload = input.readNBytes(length);
        return new String(payload, StandardCharsets.UTF_8);
    }

    private static boolean waitFor(java.util.function.Supplier<Integer> valueSupplier,
            int expected, long timeoutMillis) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            if (valueSupplier.get() == expected) {
                return true;
            }
            Thread.sleep(10);
        }
        return valueSupplier.get() == expected;
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
