package mdbms.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;

import mdbms.api.IConcurrencyControl;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.data.DataItem;
import mdbms.models.data.RowId;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
import mdbms.models.log.TransactionLogRecord;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;
import mdbms.models.transaction.TransactionState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;


class ConcurrencyControlManagerTest {

    private static final DataItem ITEM = new DataItem("users", new RowId("users", 0, 0));

    private RecordingRecoveryManager frm;
    private StubProtocol protocol;
    private IConcurrencyControl ccm;

    private List<String> events;

    @BeforeEach
    void setUp() {
        events = new ArrayList<>();
        frm = new RecordingRecoveryManager(events);
        protocol = new StubProtocol(events);
        ccm = new ConcurrencyControlManager(protocol, frm);
    }

    @Test
    void beginTransactionCreatesActiveTransaction() {
        int txId = ccm.beginTransaction();

        Transaction tx = ccm.getTransaction(txId);
        assertNotNull(tx);
        assertEquals(txId, tx.getId());
        assertEquals(TransactionState.ACTIVE, tx.getState());
    }

    @Test
    void beginTransactionAssignsUniqueIds() {
        int first = ccm.beginTransaction();
        int second = ccm.beginTransaction();

        assertEquals(1, first);
        assertEquals(first + 1, second);
        assertNotEquals(first, second);
    }

    @Test
    void commitLogsReleasesAndRemovesTransaction() {
        int txId = ccm.beginTransaction();
        Transaction tx = ccm.getTransaction(txId);

        ccm.commitTransaction(txId);

        assertEquals(TransactionState.COMMITTED, tx.getState());
        assertEquals(List.of(LogType.BEGIN, LogType.COMMIT), frm.transactionLogsType);
        assertEquals(List.of(txId), protocol.released);
        assertNull(ccm.getTransaction(txId));
    }

    @Test 
    void writeLogsContainsWriteSpecification() {
        int txId = ccm.beginTransaction();
        Transaction tx = ccm.getTransaction(txId);

        ccm.requestWrite(txId, ITEM, null, 10);
        ccm.requestWrite(txId, ITEM, 10, 12);
        ccm.requestWrite(txId, ITEM, 12, null);

        assertEquals(List.of(LogType.BEGIN, LogType.INSERT, LogType.UPDATE, LogType.DELETE), frm.transactionLogsType);
        assertEquals(ITEM.getTableName(), ((DataChangeLogRecord)frm.transactionLog.get(1)).getTable());
        assertEquals(ITEM.getRowId(), ((DataChangeLogRecord)frm.transactionLog.get(1)).getRowId());
        assertEquals(tx.getId(), ((DataChangeLogRecord)frm.transactionLog.get(1)).getTxId());

        assertEquals(null, ((DataChangeLogRecord)frm.transactionLog.get(1)).getBeforeImage());
        assertEquals(new RecordImage(List.of(10)), ((DataChangeLogRecord)frm.transactionLog.get(1)).getAfterImage());
        
        assertEquals(new RecordImage(List.of(10)), ((DataChangeLogRecord)frm.transactionLog.get(2)).getBeforeImage());
        assertEquals(new RecordImage(List.of(12)), ((DataChangeLogRecord)frm.transactionLog.get(2)).getAfterImage());
        
        assertEquals(new RecordImage(List.of(12)), ((DataChangeLogRecord)frm.transactionLog.get(3)).getBeforeImage());
        assertEquals(null, ((DataChangeLogRecord)frm.transactionLog.get(3)).getAfterImage());
    }

    @Test
    void commitAbortsWhenLogWriteFails() {
        int txId = ccm.beginTransaction();
        Transaction tx = ccm.getTransaction(txId);
        frm.logSucceeds = false;

        assertThrows(IllegalStateException.class, () -> ccm.commitTransaction(txId));

        assertEquals(TransactionState.ABORTED, tx.getState());
        assertEquals(List.of(txId), frm.rolledBack);
        assertNull(ccm.getTransaction(txId));
    }

    @Test
    void commitUnknownTransactionThrows() {
        assertThrows(IllegalArgumentException.class, () -> ccm.commitTransaction(99));
    }

    @Test
    void abortRollsBackReleasesAndRemovesTransaction() {
        int txId = ccm.beginTransaction();
        Transaction tx = ccm.getTransaction(txId);

        ccm.abortTransaction(txId, "test");

        assertEquals(TransactionState.ABORTED, tx.getState());
        assertEquals(List.of(LogType.BEGIN, LogType.ABORT), frm.transactionLogsType);
        assertEquals(List.of(txId), frm.rolledBack);
        assertEquals(List.of(txId), protocol.released);
        assertNull(ccm.getTransaction(txId));
    }

    @Test
    void disconnectRollbackFailureRetainsProtectionAndFailsEngine() {
        int txId = ccm.beginTransaction();
        Transaction tx = ccm.getTransaction(txId);
        frm.rollbackSucceeds = false;

        IllegalStateException failure = assertThrows(IllegalStateException.class,
                () -> ccm.abortTransaction(txId, "disconnect"));

        assertTrue(failure.getMessage().contains("Rollback failed"));
        assertEquals(TransactionState.FAILED, tx.getState());
        assertEquals(List.of(txId), frm.rolledBack);
        assertTrue(protocol.released.isEmpty());
        assertSame(tx, ccm.getTransaction(txId));
        assertTrue(((ConcurrencyControlManager) ccm).isFailed());
        assertThrows(IllegalStateException.class, ccm::beginTransaction);
    }

    @Test
    void requestsReturnProtocolDecision() {
        int txId = ccm.beginTransaction();

        protocol.status = AccessStatus.GRANTED;
        assertEquals(AccessStatus.GRANTED, ccm.requestRead(txId, ITEM));

        protocol.status = AccessStatus.WAITING;
        assertEquals(AccessStatus.WAITING, ccm.requestWrite(txId, ITEM));
        assertEquals(TransactionState.ACTIVE, ccm.getTransaction(txId).getState());
    }

    @Test
    void protocolAbortAbortsTransaction() {
        int txId = ccm.beginTransaction();
        Transaction tx = ccm.getTransaction(txId);
        protocol.status = AccessStatus.ABORTED;

        assertEquals(AccessStatus.ABORTED, ccm.requestWrite(txId, ITEM));

        assertEquals(TransactionState.ABORTED, tx.getState());
        assertTrue(frm.rolledBack.contains(txId));
        assertNull(ccm.getTransaction(txId));
    }

    @Test
    void requestForFinishedTransactionIsAborted() {
        int txId = ccm.beginTransaction();
        ccm.commitTransaction(txId);

        assertEquals(AccessStatus.ABORTED, ccm.requestRead(txId, ITEM));
    }

    @Test
    void requestForAbortedTransactionIsAborted() {
        int txId = ccm.beginTransaction();
        ccm.abortTransaction(txId, "test");

        assertEquals(AccessStatus.ABORTED, ccm.requestRead(txId, ITEM));
    }

    @Test
    void beginAndAbortWithoutFailureRecoveryManager() {
        IConcurrencyControl noFrmCcm = new ConcurrencyControlManager(protocol);

        int txId = assertDoesNotThrow(noFrmCcm::beginTransaction);
        assertEquals(1, txId);
        assertDoesNotThrow(() -> noFrmCcm.abortTransaction(txId, "test"));
        assertNull(noFrmCcm.getTransaction(txId));
    }

    @Test
    void timestampViolationAbortsThroughRollback() {
        ConcurrencyControlManager ccm = new ConcurrencyControlManager(new TimestampsBasedProtocol(), frm);
        int older = ccm.beginTransaction();
        int newer = ccm.beginTransaction();
        Transaction olderTx = ccm.getTransaction(older);
        assertEquals(AccessStatus.GRANTED, ccm.requestRead(newer, ITEM));

        assertEquals(AccessStatus.ABORTED, ccm.requestWrite(older, ITEM));

        assertEquals(TransactionState.ABORTED, olderTx.getState());
        assertEquals(List.of(older), frm.rolledBack);
        assertNull(ccm.getTransaction(older));
        assertEquals(TransactionState.ACTIVE, ccm.getTransaction(newer).getState());
    }

    @Test
    void timestampUpdatesNeverMoveBackwards() {
        TimestampManager timestamps = new TimestampManager();
        timestamps.updateReadTS(ITEM, 20);
        timestamps.updateReadTS(ITEM, 10);
        timestamps.updateWriteTS(ITEM, 30);
        timestamps.updateWriteTS(ITEM, 15);
        assertEquals(20, timestamps.getReadTS(ITEM));
        assertEquals(30, timestamps.getWriteTS(ITEM));
    }

    @Test
    void deadlockVictimIsRolledBackAndReleasesItsLocks() {
        LockManager locks = new LockManager();
        ConcurrencyControlManager lockCcm = new ConcurrencyControlManager(new LockBasedProtocol(locks), frm);
        int first = lockCcm.beginTransaction();
        int second = lockCcm.beginTransaction();
        Transaction victim = lockCcm.getTransaction(second);
        DataItem other = new DataItem("users", new RowId("users", 0, 1));
        assertEquals(AccessStatus.GRANTED, lockCcm.requestWrite(first, ITEM));
        assertEquals(AccessStatus.GRANTED, lockCcm.requestWrite(second, other));
        assertEquals(AccessStatus.WAITING, lockCcm.requestWrite(second, ITEM));

        lockCcm.requestWrite(first, other);

        assertEquals(TransactionState.ABORTED, victim.getState());
        assertEquals(List.of(second), frm.rolledBack);
        assertNull(lockCcm.getTransaction(second));
        assertTrue(locks.getLockTableSnapshot().values().stream()
                .flatMap(List::stream)
                .noneMatch(lock -> lock.getTransaction() == victim));
        assertEquals(AccessStatus.GRANTED, lockCcm.requestWrite(first, other));
    }

    @Test
    void dataStaysProtectedWhileFailedUntilRollbackFinishes() {
        LockManager locks = new LockManager();
        ConcurrencyControlManager lockCcm = new ConcurrencyControlManager(new LockBasedProtocol(locks), frm);
        int writer = lockCcm.beginTransaction();
        int reader = lockCcm.beginTransaction();
        Transaction writerTx = lockCcm.getTransaction(writer);
        assertEquals(AccessStatus.GRANTED, lockCcm.requestWrite(writer, ITEM));

        TransactionState[] stateDuringRollback = new TransactionState[1];
        AccessStatus[] readDuringRollback = new AccessStatus[1];
        frm.onRollback = id -> {
            stateDuringRollback[0] = writerTx.getState();
            readDuringRollback[0] = lockCcm.requestRead(reader, ITEM);
        };

        lockCcm.abortTransaction(writer, "User Request");

        assertEquals(TransactionState.FAILED, stateDuringRollback[0]);
        assertEquals(AccessStatus.WAITING, readDuringRollback[0]);
        assertEquals(TransactionState.ABORTED, writerTx.getState());
        assertEquals(AccessStatus.GRANTED, lockCcm.requestRead(reader, ITEM));
    }

    @Test
    void resourcesAreNotReleasedWhenRollbackFails() {
        int txId = ccm.beginTransaction();
        Transaction tx = ccm.getTransaction(txId);
        frm.rollbackSucceeds = false;

        assertThrows(IllegalStateException.class, () -> ccm.abortTransaction(txId, "permintaan pengguna"));

        assertEquals(TransactionState.FAILED, tx.getState());
        assertTrue(protocol.released.isEmpty());
        assertNotNull(ccm.getTransaction(txId));
        assertEquals(AccessStatus.ABORTED, ccm.requestWrite(txId, ITEM));
    }

    @Test
    void otherTransactionSeesDataOnlyAfterRollbackOfWriter() {
        LockManager locks = new LockManager();
        ConcurrencyControlManager lockCcm = new ConcurrencyControlManager(new LockBasedProtocol(locks), frm);
        int writer = lockCcm.beginTransaction();
        int reader = lockCcm.beginTransaction();
        assertEquals(AccessStatus.GRANTED, lockCcm.requestWrite(writer, ITEM, 10, 20));
        assertEquals(AccessStatus.WAITING, lockCcm.requestRead(reader, ITEM));

        lockCcm.abortTransaction(writer, "permintaan pengguna");
        AccessStatus afterAbort = lockCcm.requestRead(reader, ITEM);


        assertEquals(AccessStatus.GRANTED, afterAbort);
    }

    @Test
    void waitingLockRequestsAreRemovedAfterGrantAndRelease() {
        LockManager locks = new LockManager();
        Transaction first = new Transaction(1, 1);
        Transaction second = new Transaction(2, 2);
        assertEquals(AccessStatus.GRANTED, locks.acquireLock(first, ITEM, Locktype.EXCLUSIVE));
        assertEquals(AccessStatus.WAITING, locks.acquireLock(second, ITEM, Locktype.SHARED));
        assertEquals(ITEM, locks.getWaitingRequestsSnapshot().get(second).getData());
        locks.releaseAllLocks(first);
        assertEquals(AccessStatus.GRANTED, locks.acquireLock(second, ITEM, Locktype.SHARED));
        assertTrue(locks.getWaitingRequestsSnapshot().isEmpty());
        locks.releaseAllLocks(second);
        assertTrue(locks.getLockTableSnapshot().isEmpty());
    }

    @Test
    void defaultLockProtocolDetectsDeadlockUsingItsOwnLockManager() {
        LockBasedProtocol locks = new LockBasedProtocol();
        Transaction first = new Transaction(1, 1);
        Transaction second = new Transaction(2, 2);
        DataItem other = new DataItem("users", new RowId("users", 0, 1));
        assertEquals(AccessStatus.GRANTED, locks.requestWrite(first, ITEM));
        assertEquals(AccessStatus.GRANTED, locks.requestWrite(second, other));
        assertEquals(AccessStatus.WAITING, locks.requestWrite(second, ITEM));
        assertEquals(AccessStatus.WAITING, locks.requestWrite(first, other));
        assertEquals(TransactionState.ABORTED, second.getState());
        assertEquals(AccessStatus.GRANTED, locks.requestWrite(first, other));
    }

    // commit harus flush dulu baru lepas akses
    @Test
    void commitFlushesLogBeforeReleasingAccess() {
        int txId = ccm.beginTransaction();

        ccm.commitTransaction(txId);

        assertEquals(List.of("log BEGIN", "log COMMIT", "flush", "release " + txId), events);
    }

    // flush gagal tapi rollback berhasil, transaksi bersih ter abort dan error asli ga hilang
    @Test
    void commitFlushFailureRollsBackBeforeReleaseAndNeverReportsSuccess() {
        int txId = ccm.beginTransaction();
        Transaction tx = ccm.getTransaction(txId);
        frm.flushSucceeds = false;

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> ccm.commitTransaction(txId));

        assertTrue(failure.getMessage().contains("commit log"));
        assertEquals(TransactionState.ABORTED, tx.getState());
        assertEquals(List.of("log BEGIN", "log COMMIT", "flush", "rollback " + txId, "release " + txId), events);
        assertNull(ccm.getTransaction(txId));
        assertFalse(((ConcurrencyControlManager) ccm).isFailed());
    }

    // exception dari append COMMIT disimpan sebagai cause
    @Test
    void commitLogExceptionKeepsOriginalCause() {
        int txId = ccm.beginTransaction();
        RuntimeException appendFailure = new RuntimeException("disk full");
        frm.commitLogFailure = appendFailure;

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> ccm.commitTransaction(txId));

        assertSame(appendFailure, failure.getCause());
        assertEquals(List.of(txId), frm.rolledBack);
        assertNull(ccm.getTransaction(txId));
    }

    // COMMIT udah ketulis tapi flush gagal dan rollback nolak, hasilnya ga pasti jadi engine failed dan lock tetap dipegang
    @Test
    void uncertainCommitOutcomeRetainsProtectionAndFailsEngine() throws Exception {
        ConcurrencyControlManager manager = (ConcurrencyControlManager) ccm;
        int txId = ccm.beginTransaction();
        Transaction tx = ccm.getTransaction(txId);
        frm.flushSucceeds = false;
        frm.rollbackSucceeds = false;

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> ccm.commitTransaction(txId));

        assertTrue(failure.getMessage().contains("commit log"));
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(manager.isFailed());
        assertEquals(TransactionState.FAILED, tx.getState());
        assertTrue(protocol.released.isEmpty());
        assertSame(tx, ccm.getTransaction(txId));
        assertThrows(IllegalStateException.class, ccm::beginTransaction);
        assertThrows(IllegalStateException.class, manager::awaitNoActiveTransactions);

        // abort ulang ga boleh rollback kedua kali
        assertThrows(IllegalStateException.class, () -> ccm.abortTransaction(txId, "retry"));
        assertEquals(List.of(txId), frm.rolledBack);
    }

    // rollback gagal waktu abort biasa, akses tetap diproteksi
    @Test
    void failedRollbackRetainsProtectionAndFailsEngine() {
        int txId = ccm.beginTransaction();
        frm.rollbackSucceeds = false;

        assertThrows(IllegalStateException.class, () -> ccm.abortTransaction(txId, "test"));

        assertTrue(((ConcurrencyControlManager) ccm).isFailed());
        assertTrue(protocol.released.isEmpty());
        assertEquals(TransactionState.FAILED, ccm.getTransaction(txId).getState());
    }

    // rollback yang lempar exception juga diperlakukan sama
    @Test
    void rollbackExceptionRetainsProtectionAndFailsEngine() {
        int txId = ccm.beginTransaction();
        RuntimeException undoFailure = new RuntimeException("undo failed");
        frm.rollbackFailure = undoFailure;

        IllegalStateException failure = assertThrows(IllegalStateException.class, () -> ccm.abortTransaction(txId, "test"));

        assertSame(undoFailure, failure.getCause());
        assertTrue(((ConcurrencyControlManager) ccm).isFailed());
        assertTrue(protocol.released.isEmpty());
    }

    // abort selesai rollback dulu baru lepas akses
    @Test
    void abortRollsBackBeforeReleasingAccess() {
        int txId = ccm.beginTransaction();

        ccm.abortTransaction(txId, "test");

        assertEquals(List.of("log BEGIN", "rollback " + txId, "release " + txId), events);
    }

    // waiter checkpoint dibangunin pas engine masuk failed state
    @Test
    void awaitNoActiveTransactionsWakesUpWhenEngineFails() throws Exception {
        ConcurrencyControlManager manager = (ConcurrencyControlManager) ccm;
        int txId = ccm.beginTransaction();
        frm.rollbackSucceeds = false;
        List<Throwable> outcome = new ArrayList<>();
        Thread waiter = new Thread(() -> {
            try {
                manager.awaitNoActiveTransactions();
            } catch (Throwable t) {
                synchronized (outcome) { outcome.add(t); }
            }
        });
        waiter.start();
        Thread.sleep(50);

        assertThrows(IllegalStateException.class, () -> ccm.abortTransaction(txId, "test"));
        waiter.join(2000);

        assertFalse(waiter.isAlive());
        synchronized (outcome) {
            assertEquals(1, outcome.size());
            assertTrue(outcome.get(0) instanceof IllegalStateException);
        }
    }

    private static final class StubProtocol implements ConcurrencyProtocol {
        private AccessStatus status = AccessStatus.GRANTED;
        private final List<Integer> released = new ArrayList<>();
        private final List<String> events;

        private StubProtocol(List<String> events) {
            this.events = events;
        }

        @Override
        public AccessStatus requestRead(Transaction tx, DataItem data) {
            return status;
        }

        @Override
        public AccessStatus requestWrite(Transaction tx, DataItem data) {
            return status;
        }

        @Override
        public AccessStatus requestWrite(int txId, DataItem data, Object oldVal, Object newVal) {
            return status;
        }

        @Override
        public void releaseResources(Transaction tx) {
            released.add(tx.getId());
            events.add("release " + tx.getId());
        }
    }

    private static final class RecordingRecoveryManager implements IFailureRecoveryManager {
        private boolean logSucceeds = true;
        private boolean flushSucceeds = true;
        private boolean rollbackSucceeds = true;
        private RuntimeException commitLogFailure;
        private RuntimeException rollbackFailure;
        private IntConsumer onRollback;
        private final List<LogType> transactionLogsType = new ArrayList<>();
        private final List<LogRecord> transactionLog = new ArrayList<>();
        private final List<Integer> rolledBack = new ArrayList<>();
        private final List<String> events;

        private RecordingRecoveryManager(List<String> events) {
            this.events = events;
        }

        private RecoveryResult result(boolean success) {
            return new RecoveryResult(success, 1L, 0, "test");
        }

        @Override
        public RecoveryResult writeTransactionLog(int txId, LogType eventType) {
            transactionLogsType.add(eventType);
            transactionLog.add(new TransactionLogRecord(transactionLogsType.size(), txId, eventType, Instant.now()));
            events.add("log " + eventType);
            if (eventType == LogType.COMMIT && commitLogFailure != null) throw commitLogFailure;
            return result(logSucceeds);
        }

        @Override
        public RecoveryResult writeDataLog(
            int txId,
            LogType opType,
            String table,
            RowId rid,
            RecordImage beforeImg,
            RecordImage afterImg
        ) {
            transactionLogsType.add(opType);
            transactionLog.add(new DataChangeLogRecord(transactionLogsType.size() + 1, 
                                                        txId, 
                                                        opType, 
                                                        Instant.now(), 
                                                        table, 
                                                        rid, 
                                                        beforeImg, 
                                                        afterImg));
            return result(true);
        }

        @Override
        public boolean flushLogUpTo(long lsn) {
            events.add("flush");
            return flushSucceeds;
        }

        @Override
        public RecoveryResult rollbackTransaction(int txId) {
            rolledBack.add(txId);
            events.add("rollback " + txId);
            if (onRollback != null) {
                onRollback.accept(txId);
            }
            if (rollbackFailure != null) throw rollbackFailure;
            if (!rollbackSucceeds) return result(false);
            transactionLogsType.add(LogType.ABORT);
            transactionLog.add(new TransactionLogRecord(transactionLogsType.size(), txId, LogType.ABORT, Instant.now()));
            return result(rollbackSucceeds);
        }

        @Override
        public RecoveryResult createCheckpoint() {
            return result(true);
        }

        @Override
        public RecoveryResult recoverOnStartup() {
            return result(true);
        }

        @Override
        public RecoveryResult recoverToPointInTime(Instant targetTime) {
            return result(true);
        }

        @Override
        public RecoveryResult recoverToTransaction(int targetTxId) {
            return result(true);
        }

        @Override
        public List<LogRecord> readLogs(Integer filterTxId) {
            return List.of();
        }
    }
}
