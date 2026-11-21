package mdbms.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import mdbms.api.IDurableStorage;
import mdbms.models.access.BufferStats;
import mdbms.models.data.RowId;
import mdbms.models.log.CheckpointLogRecord;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
import mdbms.models.log.TransactionLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CheckpointManagerTest {

    @TempDir
    Path tempDir;

    private Path dataDir;
    private Path snapshotDir;
    private Path walPath;
    private WalFileHandler walFileHandler;
    private LogManager logManager;
    private TestDurableStorage storageManager;
    private RecordingRecordApplier recordApplier;
    private CheckpointManager checkpointManager;

    @BeforeEach
    void setUp() throws IOException {
        dataDir = tempDir.resolve("data");
        snapshotDir = dataDir.resolve("snapshots");
        walPath = dataDir.resolve("wal.log");
        Files.createDirectories(dataDir);

        walFileHandler = new WalFileHandler(walPath);
        logManager = new LogManager(walFileHandler);
        storageManager = new TestDurableStorage();
        recordApplier = new RecordingRecordApplier();
        checkpointManager = new CheckpointManager(
            logManager,
            storageManager,
            recordApplier,
            dataDir.toString(),
            snapshotDir.toString()
        );
    }

    @AfterEach
    void tearDown() {
        if (walFileHandler != null) {
            walFileHandler.close();
        }
    }

    @Test
    void executeCheckpoint_withExplicitActiveTxIds_flushesAndWritesCheckpointRecord() {
        logManager.appendTransactionLog(1, LogType.BEGIN);
        logManager.appendDataLog(
            1,
            LogType.INSERT,
            "users",
            new RowId("users", 0, 0),
            null,
            new RecordImage(List.of(1, "Alice"))
        );
        logManager.appendTransactionLog(2, LogType.BEGIN);

        RecoveryResult result = checkpointManager.executeCheckpoint(List.of(1, 2));

        assertTrue(result.isSuccess());
        assertEquals(4L, result.getLsn());
        assertEquals(2, result.getMaxTxId());
        assertEquals(1, storageManager.flushCount);
        assertEquals(4L, walFileHandler.getFlushedLsn());

        CheckpointLogRecord lastCp = checkpointManager.findLastCompletedCheckpoint();
        assertNotNull(lastCp);
        assertEquals(4L, lastCp.getLsn());
        assertEquals(List.of(1, 2), lastCp.getActiveTxIds());
    }

    @Test
    void executeCheckpoint_autoDetectsOnlyActiveTransactions() {
        logManager.appendTransactionLog(1, LogType.BEGIN);
        logManager.appendDataLog(
            1, LogType.INSERT, "users", new RowId("users", 0, 0),
            null, new RecordImage(List.of(1, "Alice"))
        );
        logManager.appendTransactionLog(1, LogType.COMMIT);

        logManager.appendTransactionLog(2, LogType.BEGIN);
        logManager.appendTransactionLog(2, LogType.ABORT);

        logManager.appendTransactionLog(3, LogType.BEGIN);
        logManager.appendDataLog(
            3, LogType.UPDATE, "users", new RowId("users", 0, 0),
            new RecordImage(List.of(1, "Alice")), new RecordImage(List.of(1, "Alicia"))
        );
        logManager.appendTransactionLog(4, LogType.BEGIN);

        RecoveryResult result = checkpointManager.executeCheckpoint();

        assertTrue(result.isSuccess());
        CheckpointLogRecord cp = checkpointManager.findLastCompletedCheckpoint();
        assertNotNull(cp);
        assertEquals(List.of(3, 4), cp.getActiveTxIds());
    }

    @Test
    void executeCheckpoint_withNullStorageManager_succeeds() {
        CheckpointManager cmWithoutStorage = new CheckpointManager(
            logManager,
            null,
            recordApplier,
            dataDir.toString(),
            snapshotDir.toString()
        );

        logManager.appendTransactionLog(1, LogType.BEGIN);
        RecoveryResult result = cmWithoutStorage.executeCheckpoint();

        assertTrue(result.isSuccess());
        assertNotNull(cmWithoutStorage.findLastCompletedCheckpoint());
    }

    @Test
    void findLastCompletedCheckpoint_returnsNullWhenNoCheckpointExists() {
        logManager.appendTransactionLog(1, LogType.BEGIN);
        logManager.appendTransactionLog(1, LogType.COMMIT);

        assertNull(checkpointManager.findLastCompletedCheckpoint());
    }

    @Test
    void findLastCompletedCheckpoint_returnsMostRecentCheckpoint() {
        logManager.appendTransactionLog(1, LogType.BEGIN);
        RecoveryResult first = checkpointManager.executeCheckpoint(List.of(1));

        logManager.appendTransactionLog(1, LogType.COMMIT);
        logManager.appendTransactionLog(2, LogType.BEGIN);
        RecoveryResult second = checkpointManager.executeCheckpoint(List.of(2));

        CheckpointLogRecord latest = checkpointManager.findLastCompletedCheckpoint();
        assertNotNull(latest);
        assertEquals(second.getLsn(), latest.getLsn());
        assertTrue(latest.getLsn() > first.getLsn());
        assertEquals(List.of(2), latest.getActiveTxIds());
    }

    @Test
    void createSnapshot_copiesDataFilesAndExcludesWalAndSnapshotDirectory() throws IOException {
        Path tableFile = dataDir.resolve("users.dat");
        Path nestedDir = dataDir.resolve("tables");
        Files.createDirectories(nestedDir);
        Path nestedFile = nestedDir.resolve("orders.dat");

        Files.writeString(tableFile, "users-v1");
        Files.writeString(nestedFile, "orders-v1");
        logManager.appendTransactionLog(1, LogType.BEGIN);

        checkpointManager.createSnapshot();

        assertEquals(1, storageManager.flushCount);
        assertTrue(Files.exists(snapshotDir.resolve("users.dat")));
        assertEquals("users-v1", Files.readString(snapshotDir.resolve("users.dat")));
        assertTrue(Files.exists(snapshotDir.resolve("tables").resolve("orders.dat")));
        assertEquals("orders-v1", Files.readString(snapshotDir.resolve("tables").resolve("orders.dat")));
        assertFalse(Files.exists(snapshotDir.resolve("wal.log")));
    }

    @Test
    void createSnapshot_ignoresWhenPathsAreNull() {
        CheckpointManager cmNullPaths = new CheckpointManager(
            logManager, storageManager, recordApplier, null, null
        );

        cmNullPaths.createSnapshot();

        assertEquals(0, storageManager.flushCount);
    }

    @Test
    void recoverToPointInTime_restoresSnapshotRedoesCommittedOnlyAndTruncatesWal() throws IOException {
        Path tableFile = dataDir.resolve("users.dat");
        Files.writeString(tableFile, "snapshot-state");
        checkpointManager.createSnapshot();

        Files.writeString(tableFile, "modified-after-snapshot");

        Instant t1 = Instant.parse("2026-11-19T10:00:00Z");
        Instant t2 = Instant.parse("2026-11-19T10:00:05Z");
        Instant targetTime = Instant.parse("2026-11-19T10:00:10Z");
        Instant t3 = Instant.parse("2026-11-19T10:00:20Z");

        walFileHandler.append(new TransactionLogRecord(1L, 1, LogType.BEGIN, t1));
        walFileHandler.append(new DataChangeLogRecord(
            2L, 1, LogType.INSERT, t1, "users", new RowId("users", 0, 0),
            null, new RecordImage(List.of(1, "Alice"))
        ));
        walFileHandler.append(new TransactionLogRecord(3L, 1, LogType.COMMIT, t1));

        walFileHandler.append(new TransactionLogRecord(4L, 2, LogType.BEGIN, t2));
        walFileHandler.append(new DataChangeLogRecord(
            5L, 2, LogType.UPDATE, t2, "users", new RowId("users", 0, 0),
            new RecordImage(List.of(1, "Alice")), new RecordImage(List.of(1, "Uncommitted"))
        ));

        walFileHandler.append(new TransactionLogRecord(6L, 3, LogType.BEGIN, t3));
        walFileHandler.append(new DataChangeLogRecord(
            7L, 3, LogType.INSERT, t3, "users", new RowId("users", 0, 1),
            null, new RecordImage(List.of(2, "Bob"))
        ));
        walFileHandler.append(new TransactionLogRecord(8L, 3, LogType.COMMIT, t3));
        walFileHandler.flushUpTo(8L);

        logManager = new LogManager(walFileHandler);
        checkpointManager = new CheckpointManager(
            logManager, storageManager, recordApplier, dataDir.toString(), snapshotDir.toString()
        );

        RecoveryResult result = checkpointManager.recoverToPointInTime(targetTime);

        assertTrue(result.isSuccess());
        assertEquals(5L, result.getLsn());
        assertEquals("snapshot-state", Files.readString(tableFile));
        assertTrue(storageManager.bufferPool.discardedFiles.contains("users.dat"));
        assertEquals(List.of(2L), recordApplier.redoneLsns);

        List<LogRecord> remainingLogs = logManager.readLogs(null);
        assertEquals(5, remainingLogs.size());
        assertEquals(5L, remainingLogs.get(remainingLogs.size() - 1).getLsn());
    }

    @Test
    void recoverToTransaction_restoresSnapshotRedoesUpToTargetCommitAndTruncatesWal() throws IOException {
        Path tableFile = dataDir.resolve("orders.dat");
        Files.writeString(tableFile, "initial-orders");
        checkpointManager.createSnapshot();
        Files.writeString(tableFile, "dirty-orders");

        logManager.appendTransactionLog(1, LogType.BEGIN);
        DataChangeLogRecord tx1Insert = logManager.appendDataLog(
            1, LogType.INSERT, "orders", new RowId("orders", 0, 0),
            null, new RecordImage(List.of(101))
        );
        logManager.appendTransactionLog(1, LogType.COMMIT);

        logManager.appendTransactionLog(2, LogType.BEGIN);
        logManager.appendDataLog(
            2, LogType.UPDATE, "orders", new RowId("orders", 0, 0),
            new RecordImage(List.of(101)), new RecordImage(List.of(999))
        );

        logManager.appendTransactionLog(3, LogType.BEGIN);
        DataChangeLogRecord tx3Delete = logManager.appendDataLog(
            3, LogType.DELETE, "orders", new RowId("orders", 0, 0),
            new RecordImage(List.of(101)), null
        );
        TransactionLogRecord tx3Commit = logManager.appendTransactionLog(3, LogType.COMMIT);

        logManager.appendTransactionLog(4, LogType.BEGIN);
        logManager.appendDataLog(
            4, LogType.INSERT, "orders", new RowId("orders", 0, 1),
            null, new RecordImage(List.of(202))
        );
        logManager.appendTransactionLog(4, LogType.COMMIT);

        RecoveryResult result = checkpointManager.recoverToTransaction(3);

        assertTrue(result.isSuccess());
        assertEquals(tx3Commit.getLsn(), result.getLsn());
        assertEquals("initial-orders", Files.readString(tableFile));
        assertTrue(storageManager.bufferPool.discardedFiles.contains("orders.dat"));
        assertEquals(List.of(tx1Insert.getLsn(), tx3Delete.getLsn()), recordApplier.redoneLsns);

        List<LogRecord> remainingLogs = logManager.readLogs(null);
        assertEquals(tx3Commit.getLsn(), remainingLogs.get(remainingLogs.size() - 1).getLsn());
        assertTrue(remainingLogs.stream().noneMatch(r -> r.getTxId() == 4));
    }

    @Test
    void recoverToTransaction_failsWhenTargetTransactionNeverCommitted() {
        logManager.appendTransactionLog(1, LogType.BEGIN);
        logManager.appendDataLog(
            1, LogType.INSERT, "users", new RowId("users", 0, 0),
            null, new RecordImage(List.of(1, "Alice"))
        );

        RecoveryResult result = checkpointManager.recoverToTransaction(1);

        assertFalse(result.isSuccess());
        assertEquals(-1L, result.getLsn());
        assertTrue(recordApplier.redoneLsns.isEmpty());
    }

    @Test
    void recoverToTransaction_failsWhenTargetTransactionDoesNotExist() {
        logManager.appendTransactionLog(1, LogType.BEGIN);
        logManager.appendTransactionLog(1, LogType.COMMIT);

        RecoveryResult result = checkpointManager.recoverToTransaction(999);

        assertFalse(result.isSuccess());
        assertEquals(-1L, result.getLsn());
        assertTrue(recordApplier.redoneLsns.isEmpty());
    }


    private static class TestBufferPool {
        final Set<String> discardedFiles = new HashSet<>();

        @SuppressWarnings("unused")
        public void discardFile(String fileName) {
            discardedFiles.add(fileName);
        }
    }

    private static class TestDurableStorage implements IDurableStorage {
        final TestBufferPool bufferPool = new TestBufferPool();
        int flushCount = 0;

        @Override
        public void flushBuffer() {
            flushCount++;
        }

        @Override
        public BufferStats getBufferStats() {
            throw new UnsupportedOperationException("Unimplemented method 'getBufferStats'");
        }
    }

    private static class RecordingRecordApplier extends RecordApplier {
        final List<Long> redoneLsns = new ArrayList<>();

        RecordingRecordApplier() {
            super(null);
        }

        @Override
        public boolean redo(DataChangeLogRecord record) {
            redoneLsns.add(record.getLsn());
            return true;
        }
    }
}