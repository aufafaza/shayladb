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
import mdbms.processor.operator.CreateTableOperator;
import mdbms.recovery.FailureRecoveryManager;
import mdbms.storage.StorageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(value = 20, unit = TimeUnit.SECONDS)
class NetworkSessionIT {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Test
    void twoClientsCommitThenRollbackOwnerConflictAndOtherClientProgresses(@TempDir Path directory)
            throws Exception {
        DBMSConfig config = new DBMSConfig(
                directory.toString(), 16, ConcurrencyAlgorithm.LOCK_BASED);
        FailureRecoveryManager recovery = new FailureRecoveryManager();
        StorageManager storage = new StorageManager(directory.toString(), 16, 8);
        storage.attachRecoveryManager(recovery);
        new CreateTableOperator(storage).execute(new TableSchema("users", List.of(
                new ColumnDefinition("id", DataType.INTEGER, 0, false),
                new ColumnDefinition("name", DataType.VARCHAR, 50, false)),
                List.of("id"), List.of()));

        DBMS dbms = new DBMS(config, storage, recovery);
        dbms.start();
        ConcurrencyControlManager concurrency =
                (ConcurrencyControlManager) dbms.getConcurrencyControl();
        DBMSServer server = new DBMSServer(
                0, dbms.getQueryProcessor(), dbms.getConcurrencyControl());
        Future<?> serverFuture = executor.submit(() -> {
            server.start();
            return null;
        });

        try {
            assertTrue(server.awaitReady(5, TimeUnit.SECONDS));
            int port = server.getBoundPort();

            try (Socket client = connect(port);
                 Socket owner = connect(port);
                 Socket contender = connect(port)) {
                String insert = query(client, "INSERT INTO users VALUES (1, 'Committed');");
                assertTrue(insert.contains("\"status\":\"WRITE\""),
                        "initial committed write should succeed: " + insert);

                assertTrue(query(owner, "BEGIN").contains("BEGIN"),
                        "owner should begin");
                assertTrue(query(owner,
                        "UPDATE users SET name = 'OwnerUncommitted' WHERE id = 1;")
                        .contains("\"status\":\"WRITE\""),
                        "owner update should acquire lock");

                assertTrue(query(contender, "BEGIN").contains("BEGIN"),
                        "contender should begin");
                contender.setSoTimeout(5_000);
                send(contender, "UPDATE users SET name = 'ContenderWins' WHERE id = 1;");

                assertTrue(waitFor(concurrency::getWaitingRequestCount, 1, 5_000),
                        "contender should wait behind the owner's lock");

                owner.close();
                assertTrue(waitFor(concurrency::getActiveTransactionCount, 1, 5_000),
                        "owner disconnect should abort and leave only contender");

                String contenderUpdate = readFrame(contender);
                assertTrue(contenderUpdate.contains("\"status\":\"WRITE\""),
                        "contender update should progress after owner rollback: " + contenderUpdate);

                String contenderCommit = query(contender, "COMMIT");
                assertTrue(contenderCommit.contains("\"status\":\"OK\"")
                                || contenderCommit.contains("COMMIT"),
                        "contender commit should succeed: " + contenderCommit);

                String selected = query(client,
                        "SELECT name FROM users WHERE id = 1;");
                assertTrue(selected.contains("ContenderWins"),
                        "contender committed value should be visible: " + selected);
            }

            assertTrue(waitFor(() -> server.getActiveClientCount() == 0
                    && server.getActiveSessionCount() == 0, 5_000),
                    "all network sessions should be removed");
            assertTrue(waitFor(() -> concurrency.getActiveTransactionCount() == 0
                    && concurrency.getLockCount() == 0
                    && concurrency.getWaitingRequestCount() == 0, 5_000),
                    "transactions, locks, and waiting requests should be empty");
        } finally {
            server.shutdown();
            dbms.shutdown();
            serverFuture.get(5, TimeUnit.SECONDS);
            executor.shutdownNow();
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
