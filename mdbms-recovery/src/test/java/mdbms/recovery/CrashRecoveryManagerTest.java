package mdbms.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.io.IOException;
import java.io.UncheckedIOException;
import mdbms.api.IDataAccess;
import mdbms.api.IDurableStorage;
import mdbms.models.access.BufferStats;
import mdbms.models.access.DataDeletion;
import mdbms.models.access.DataRetrieval;
import mdbms.models.access.DataWrite;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.data.Rows;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CrashRecoveryManagerTest {

    @TempDir
    Path tempDir;

    private WalFileHandler walFileHandler;
    private LogManager logManager;

    @BeforeEach
    void setUp() {
        walFileHandler = new WalFileHandler(tempDir.resolve("recovery.log"));
        logManager = new LogManager(walFileHandler);
    }

    @AfterEach
    void tearDown() {
        walFileHandler.close();
    }

    @Test
    void emptyLogReturnsSuccessWithoutFlushingOrWritingAbort() {
        TestDurableStorage durableStorage = new TestDurableStorage();
        CrashRecoveryManager recoveryManager = recoveryManager(null, null, durableStorage);

        RecoveryResult result = recoveryManager.performCrashRecovery();

        assertTrue(result.isSuccess());
        assertEquals(0L, result.getLsn());
        assertEquals(0, result.getMaxTxId());
        assertEquals(0, durableStorage.flushCount);
        assertTrue(logManager.readLogs(null).isEmpty());
    }

    @Test
    void redoesCommittedChangesAndOnlyUndoesTransactionsActiveAtCrash() {
        TestDataAccess dataAccess = new TestDataAccess();
        TestRollbackManager rollbackManager = new TestRollbackManager(logManager, true);
        TestDurableStorage durableStorage = new TestDurableStorage();
        CrashRecoveryManager recoveryManager =
                recoveryManager(rollbackManager, dataAccess, durableStorage);

        appendUpdate(1, "committed");
        logManager.appendTransactionLog(1, LogType.COMMIT);
        appendUpdate(2, "active");
        appendUpdate(3, "already-aborted");
        logManager.appendTransactionLog(3, LogType.ABORT);

        RecoveryResult result = recoveryManager.performCrashRecovery();

        assertTrue(result.isSuccess());
        assertEquals(List.of(List.of("committed")), dataAccess.updatedValues);
        assertEquals(List.of(3L), rollbackManager.undoneLsns);
        assertEquals(1, durableStorage.flushCount);
        assertEquals(LogType.ABORT, logManager.readLogs(2).get(1).getType());
        assertEquals(1, durableStorage.rebuildCount);
    }

    @Test
    void undoesActiveChangesInReverseLsnOrderAndFlushesAbort() {
        TestDurableStorage durableStorage = new TestDurableStorage();
        TestRollbackManager rollbackManager = new TestRollbackManager(logManager, true);
        CrashRecoveryManager recoveryManager = recoveryManager(rollbackManager, new TestDataAccess(), durableStorage);
        logManager.appendTransactionLog(7, LogType.BEGIN);
        appendUpdate(7, "first");
        appendUpdate(7, "second");

        RecoveryResult result = recoveryManager.performCrashRecovery();

        assertTrue(result.isSuccess());
        assertEquals(List.of(3L, 2L), rollbackManager.undoneLsns);
        assertEquals(1, durableStorage.flushCount);
        assertEquals(4L, result.getLsn());
        assertTrue(walFileHandler.getFlushedLsn() >= result.getLsn());
        assertEquals(LogType.ABORT, logManager.readLogs(7).get(3).getType());
    }

    @Test
    void completedCheckpointSkipsEarlierChangesAndRecoversLaterCommittedChanges() {
        TestDataAccess dataAccess = new TestDataAccess();
        CheckpointManager checkpointManager =
                new CheckpointManager(logManager, null, null, null, null);
        CrashRecoveryManager recoveryManager =
                new CrashRecoveryManager(logManager, null, checkpointManager, dataAccess, new TestDurableStorage());

        appendUpdate(1, "before-checkpoint");
        logManager.appendTransactionLog(1, LogType.COMMIT);
        logManager.appendCheckpointLog(List.of(2));
        appendUpdate(2, "after-checkpoint");
        logManager.appendTransactionLog(2, LogType.COMMIT);

        RecoveryResult result = recoveryManager.performCrashRecovery();

        assertTrue(result.isSuccess());
        assertEquals(List.of(List.of("after-checkpoint")), dataAccess.updatedValues);
    }

    @Test
    void redoFailureReturnsFailureWithoutUndoingOrFlushing() {
        TestDurableStorage durableStorage = new TestDurableStorage();
        TestRollbackManager rollbackManager = new TestRollbackManager(logManager, true);
        CrashRecoveryManager recoveryManager =
                recoveryManager(rollbackManager, new TestDataAccess(), durableStorage);

        logManager.appendTransactionLog(4, LogType.BEGIN);
        logManager.appendDataLog(4, LogType.INSERT, "users", null, null, new RecordImage(List.of("A")));
        logManager.appendTransactionLog(4, LogType.COMMIT);

        RecoveryResult result = recoveryManager.performCrashRecovery();

        assertFalse(result.isSuccess());
        assertEquals(-1L, result.getLsn());
        assertTrue(rollbackManager.undoneLsns.isEmpty());
        assertEquals(0, durableStorage.flushCount);
        assertEquals(3, logManager.readLogs(null).size());
    }

    @Test
    void undoFailureReturnsFailureWithoutFlushingOrAppendingAbort() {
        TestDurableStorage durableStorage = new TestDurableStorage();
        TestRollbackManager rollbackManager = new TestRollbackManager(logManager, false);
        CrashRecoveryManager recoveryManager =
                recoveryManager(rollbackManager, new TestDataAccess(), durableStorage);
        appendUpdate(5, "uncommitted");

        RecoveryResult result = recoveryManager.performCrashRecovery();

        assertFalse(result.isSuccess());
        assertEquals(-1L, result.getLsn());
        assertEquals(1, rollbackManager.undoneLsns.size());
        assertEquals(0, durableStorage.flushCount);
        assertEquals(1, logManager.readLogs(null).size());
    }

    @Test
    void usesRecordApplierForUndoWhenRollbackManagerIsAbsent() {
        TestDataAccess dataAccess = new TestDataAccess();
        CrashRecoveryManager recoveryManager = recoveryManager(null, dataAccess, new TestDurableStorage());
        appendUpdate(6, "uncommitted");

        RecoveryResult result = recoveryManager.performCrashRecovery();

        assertTrue(result.isSuccess());
        assertEquals(List.of(List.of("before")), dataAccess.updatedValues);
    }

    private CrashRecoveryManager recoveryManager(
            RollbackManager rollbackManager,
            IDataAccess dataAccess,
            IDurableStorage durableStorage) {
        return new CrashRecoveryManager(logManager, rollbackManager, null, dataAccess, durableStorage);
    }

    private DataChangeLogRecord appendUpdate(int txId, String afterValue) {
        return logManager.appendDataLog(
                txId,
                LogType.UPDATE,
                "users",
                new RowId("users", 0, txId),
                new RecordImage(List.of("before")),
                new RecordImage(List.of(afterValue)));
    }

    private static final class TestRollbackManager extends RollbackManager {
        private final boolean undoResult;
        private final List<Long> undoneLsns = new ArrayList<>();

        private TestRollbackManager(LogManager logManager, boolean undoResult) {
            super(logManager);
            this.undoResult = undoResult;
        }

        @Override
        protected boolean undo(DataChangeLogRecord change) {
            undoneLsns.add(change.getLsn());
            return undoResult;
        }
    }

    private static final class TestDurableStorage implements IDurableStorage {
        private int flushCount;
        private int rebuildCount;

        @Override
        public void flushBuffer() {
            flushCount++;
        }

        @Override
        public void invalidateAndReload(java.util.Set<String> affectedFiles) {
        }

        @Override
        public void rebuildIndexesAndReloadCatalog() {
            rebuildCount++;
        }

        @Override
        public BufferStats getBufferStats() {
            return null;
        }
    }

    private static final class TestDataAccess implements IDataAccess {
        private final List<List<Object>> updatedValues = new ArrayList<>();
        private boolean throwIoOnUpdate = false;

        @Override
        public Rows readBlock(DataRetrieval request) {
            return null;
        }

        @Override
        public List<RowId> writeBlock(DataWrite request) {
            return List.of();
        }

        @Override
        public int deleteBlock(DataDeletion request) {
            return 0;
        }

        @Override
        public Row fetchByRowId(RowId rowId) {
            return null;
        }

        @Override
        public RowId reserveRowId(String table, List<Object> values) {
            return null;
        }

        @Override
        public void releaseRowId(RowId rowId) {
        }

        @Override
        public void insertAt(RowId rowId, List<Object> values) {
        }

        @Override
        public Row updateByRowId(RowId rowId, List<Object> values) {
            if (throwIoOnUpdate) {
                throw new UncheckedIOException(new IOException("Simulated disk I/O error during redo"));
            }
            updatedValues.add(new ArrayList<>(values));
            return null;
        }

        @Override
        public Row deleteByRowId(RowId rowId) {
            return null;
        }

        @Override
        public List<RowId> searchIndex(String indexName, Object key) {
            return List.of();
        }

        @Override
        public List<RowId> searchIndexRange(
                String indexName,
                Object low,
                Object high,
                boolean lowInclusive,
                boolean highInclusive) {
            return List.of();
        }
    }

    @Test
    void missingStorageServicesReturnFailure() {
        appendUpdate(1, "committed");
        logManager.appendTransactionLog(1, LogType.COMMIT);

        CrashRecoveryManager missingDataAccess = recoveryManager(null, null, new TestDurableStorage());
        assertFalse(missingDataAccess.performCrashRecovery().isSuccess());

        CrashRecoveryManager missingDurableStorage = recoveryManager(null, new TestDataAccess(), null);
        assertFalse(missingDurableStorage.performCrashRecovery().isSuccess());
    }

    @Test
    void ioExceptionDuringRedoReturnsFailure() {
        TestDataAccess failingDataAccess = new TestDataAccess();
        failingDataAccess.throwIoOnUpdate = true;
        TestDurableStorage durableStorage = new TestDurableStorage();
        CrashRecoveryManager recoveryManager = recoveryManager(null, failingDataAccess, durableStorage);

        appendUpdate(1, "committed");
        logManager.appendTransactionLog(1, LogType.COMMIT);

        RecoveryResult result = recoveryManager.performCrashRecovery();

        assertFalse(result.isSuccess());
        assertEquals(0, durableStorage.flushCount);
    }
}