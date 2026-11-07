package mdbms.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.data.DataItem;
import mdbms.models.data.RowId;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
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

    @BeforeEach
    void setUp() {
        frm = new RecordingRecoveryManager();
        protocol = new StubProtocol();
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
        assertEquals(List.of(LogType.BEGIN, LogType.COMMIT), frm.transactionLogs);
        assertEquals(List.of(txId), protocol.released);
        assertNull(ccm.getTransaction(txId));
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
        assertEquals(List.of(LogType.BEGIN, LogType.ABORT), frm.transactionLogs);
        assertEquals(List.of(txId), frm.rolledBack);
        assertEquals(List.of(txId), protocol.released);
        assertNull(ccm.getTransaction(txId));
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

    private static final class StubProtocol implements ConcurrencyProtocol {
        private AccessStatus status = AccessStatus.GRANTED;
        private final List<Integer> released = new ArrayList<>();

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
        }
    }

    private static final class RecordingRecoveryManager implements IFailureRecoveryManager {
        private boolean logSucceeds = true;
        private final List<LogType> transactionLogs = new ArrayList<>();
        private final List<Integer> rolledBack = new ArrayList<>();

        private RecoveryResult result(boolean success) {
            return new RecoveryResult(success, 1L, 0, "test");
        }

        @Override
        public RecoveryResult writeTransactionLog(int txId, LogType eventType) {
            transactionLogs.add(eventType);
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
            return result(true);
        }

        @Override
        public boolean flushLogUpTo(long lsn) {
            return true;
        }

        @Override
        public RecoveryResult rollbackTransaction(int txId) {
            rolledBack.add(txId);
            transactionLogs.add(LogType.ABORT);
            return result(true);
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
