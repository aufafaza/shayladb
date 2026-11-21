package mdbms.app;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import mdbms.api.IConcurrencyControl;
import mdbms.models.data.RowId;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
import mdbms.models.query.ExecutionResult;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.recovery.FailureRecoveryFacade;
import mdbms.recovery.WalFileHandler;
import mdbms.storage.StorageManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DBMSRecoveryWiringTest {
    @TempDir Path directory;

    @Test
    void defaultEngineLogsCommitsAndActuallyUndoesAbortsAcrossRestart() throws IOException {
        createStorage(directory);
        DBMS engine = new DBMS(config(directory));
        int previousTx;
        engine.start();
        try {
            IConcurrencyControl ccm = engine.getConcurrencyControl();
            int committed = ccm.beginTransaction();
            assertOk(engine.getQueryProcessor().executeQuery("INSERT INTO users VALUES (1, 'kept');", committed));
            ccm.commitTransaction(committed);

            int aborted = ccm.beginTransaction();
            assertOk(engine.getQueryProcessor().executeQuery("INSERT INTO users VALUES (2, 'removed');", aborted));
            ccm.abortTransaction(aborted, "wiring test");

            previousTx = ccm.beginTransaction();
            assertEquals(List.of(List.of(1, "kept")), select(engine, previousTx).rows());
            ccm.commitTransaction(previousTx);
            String wal = Files.readString(directory.resolve(WalFileHandler.DEFAULT_WAL_PATH));
            assertTrue(wal.contains("|INSERT|"));
            assertTrue(wal.contains("|COMMIT|"));
            assertTrue(wal.contains("|ABORT|"));
            assertFalse(wal.contains("MOCK"));
        } finally {
            engine.shutdown();
        }

        DBMS restarted = new DBMS(config(directory));
        restarted.start();
        try {
            int tx = restarted.getConcurrencyControl().beginTransaction();
            assertTrue(tx > previousTx);
            assertEquals(List.of(List.of(1, "kept")), select(restarted, tx).rows());
            restarted.getConcurrencyControl().commitTransaction(tx);
        } finally {
            restarted.shutdown();
        }
    }

    @Test
    void startupRecoversExistingWalAndRestoresTheTransactionWatermark() {
        StorageManager storage = createStorage(directory);
        try (FailureRecoveryFacade recovery = recovery(directory, storage)) {
            storage.attachRecoveryManager(recovery);
            storage.createIndex("idx_users_id", "users", "id");
            insertLogged(storage, recovery, 100, List.of(1, "committed"));
            assertTrue(recovery.writeTransactionLog(100, LogType.COMMIT).isSuccess());
            insertLogged(storage, recovery, 200, List.of(2, "incomplete"));
            storage.flushBuffer();
        }

        DBMS engine = new DBMS(config(directory));
        engine.start();
        try {
            int tx = engine.getConcurrencyControl().beginTransaction();
            assertEquals(201, tx);
            assertEquals(List.of(List.of(1, "committed")), select(engine, tx).rows());
            engine.getConcurrencyControl().commitTransaction(tx);

            StorageManager reopened = new StorageManager(directory.toString(), 8, 4);
            assertEquals(1, reopened.searchIndex("idx_users_id", 1).size());
            assertTrue(reopened.searchIndex("idx_users_id", 2).isEmpty());
        } finally {
            engine.shutdown();
        }
    }

    @Test
    void storageAndTransactionsShareOneFacadeAndRecoveryRunsOnce() {
        StorageManager storage = new StorageManager(directory.toString(), 8, 4);
        TrackingRecovery recovery = new TrackingRecovery(directory, storage);
        DBMS engine = new DBMS(config(directory), storage, recovery);
        engine.start();
        try {
            engine.start();
            assertEquals(1, recovery.recoveries);
            assertTrue(recovery.flushes > 0, "storage must flush WAL through the shared facade");
            int tx = engine.getConcurrencyControl().beginTransaction();
            engine.getConcurrencyControl().commitTransaction(tx);
            assertEquals(List.of(LogType.BEGIN, LogType.COMMIT), recovery.readLogs(tx).stream()
                    .map(record -> record.getType()).toList());
        } finally {
            engine.shutdown();
        }
        assertEquals(1, recovery.closes);
        assertNull(engine.getQueryProcessor());
        assertNull(engine.getConcurrencyControl());
    }

    @Test
    void failedRecoveryClosesWalWithoutPublishingServices() {
        StorageManager storage = createStorage(directory);
        TrackingRecovery recovery = new TrackingRecovery(directory, storage);
        recovery.failRecovery = true;
        DBMS engine = new DBMS(config(directory), storage, recovery);

        assertThrows(IllegalStateException.class, engine::start);

        assertEquals(1, recovery.recoveries);
        assertEquals(1, recovery.closes);
        assertFalse(engine.isRunning());
        assertNull(engine.getQueryProcessor());
        assertNull(engine.getConcurrencyControl());
    }

    @Test
    void independentDatabaseDirectoriesHaveIndependentWals() throws IOException {
        Path firstDirectory = directory.resolve("first.log");
        Path secondDirectory = directory.resolve("second");
        createStorage(firstDirectory);
        createStorage(secondDirectory);
        DBMS first = new DBMS(config(firstDirectory));
        DBMS second = new DBMS(config(secondDirectory));
        try {
            first.start();
            second.start();
            commitEmptyTransaction(first);
            commitEmptyTransaction(first);
            commitEmptyTransaction(second);

            assertEquals(4, Files.readAllLines(firstDirectory.resolve("wal.log")).size());
            assertEquals(2, Files.readAllLines(secondDirectory.resolve("wal.log")).size());
        } finally {
            first.shutdown();
            second.shutdown();
        }
    }

    @Test
    void shutdownBeforeStartClosesTheOwnedWal() {
        StorageManager storage = createStorage(directory);
        TrackingRecovery recovery = new TrackingRecovery(directory, storage);
        DBMS engine = new DBMS(config(directory), storage, recovery);

        engine.shutdown();

        assertEquals(1, recovery.closes);
        assertEquals(0, recovery.recoveries);
        assertFalse(engine.isRunning());
    }

    @Test
    void corruptWalPreventsEngineConstruction() throws IOException {
        Files.writeString(directory.resolve("wal.log"), "corrupt record\nalso corrupt\n");

        assertThrows(IllegalStateException.class, () -> new DBMS(config(directory)));
    }

    private static DBMSConfig config(Path directory) {
        return new DBMSConfig(directory.toString(), 8, ConcurrencyAlgorithm.LOCK_BASED);
    }

    private static StorageManager createStorage(Path directory) {
        StorageManager storage = new StorageManager(directory.toString(), 8, 4);
        storage.updateTableSchema(new TableSchema("users", List.of(
                new ColumnDefinition("id", DataType.INTEGER, 0, false),
                new ColumnDefinition("name", DataType.VARCHAR, 50, false)), List.of("id"), List.of()));
        return storage;
    }

    private static FailureRecoveryFacade recovery(Path directory, StorageManager storage) {
        return new FailureRecoveryFacade(directory.resolve("wal.log"), directory.resolve("snapshots"), storage, storage);
    }

    private static void insertLogged(StorageManager storage, FailureRecoveryFacade recovery, int tx, List<Object> values) {
        assertTrue(recovery.writeTransactionLog(tx, LogType.BEGIN).isSuccess());
        RowId rid = storage.reserveRowId("users", values);
        assertTrue(recovery.writeDataLog(tx, LogType.INSERT, "users", rid, null, new RecordImage(values)).isSuccess());
        storage.insertAt(rid, values);
    }

    private static ExecutionResult select(DBMS engine, int tx) {
        ExecutionResult result = engine.getQueryProcessor().executeQuery("SELECT id, name FROM users ORDER BY id;", tx);
        assertOk(result);
        return result;
    }

    private static void commitEmptyTransaction(DBMS engine) {
        int tx = engine.getConcurrencyControl().beginTransaction();
        engine.getConcurrencyControl().commitTransaction(tx);
    }

    private static void assertOk(ExecutionResult result) {
        assertTrue(result.success(), result.message());
    }

    private static class TrackingRecovery extends FailureRecoveryFacade {
        int recoveries;
        int flushes;
        int closes;
        boolean failRecovery;

        TrackingRecovery(Path directory, StorageManager storage) {
            super(directory.resolve("wal.log"), directory.resolve("snapshots"), storage, storage);
        }

        @Override public RecoveryResult recoverOnStartup() {
            recoveries++;
            return failRecovery ? new RecoveryResult(false, -1L, 0, "injected recovery failure") : super.recoverOnStartup();
        }

        @Override public boolean flushLogUpTo(long lsn) {
            flushes++;
            return super.flushLogUpTo(lsn);
        }

        @Override public void close() {
            closes++;
            super.close();
        }
    }
}
