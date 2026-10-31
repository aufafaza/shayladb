package mdbms.concurrency;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import mdbms.models.AccessStatus;
import mdbms.models.DataItem;
import mdbms.models.LogRecord;
import mdbms.models.LogType;
import mdbms.models.RecordImage;
import mdbms.models.RecoveryResult;
import mdbms.models.RowId;
import mdbms.models.Transaction;
import mdbms.models.TransactionState;
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
        assertNotEquals(ccm.beginTransaction(), ccm.beginTransaction());
    }

    @Test
    void commitLogsReleasesAndRemovesTransaction() {
        int txId = ccm.beginTransaction();
        Transaction tx = ccm.getTransaction(txId);

        ccm.commitTransaction(txId);

        assertEquals(TransactionState.COMMITTED, tx.getState());
        assertEquals(List.of(LogType.COMMIT), frm.transactionLogs);
        assertEquals(List.of(txId), protocol.released);
        assertNull(ccm.getTransaction(txId));
    }

    @Test
    void commitAbortsWhenLogWriteFails() {
        frm.logSucceeds = false;
        int txId = ccm.beginTransaction();
        Transaction tx = ccm.getTransaction(txId);

        ccm.commitTransaction(txId);

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
