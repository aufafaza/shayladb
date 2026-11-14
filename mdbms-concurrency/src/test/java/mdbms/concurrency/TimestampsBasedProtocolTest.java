package mdbms.concurrency;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.data.DataItem;
import mdbms.models.data.RowId;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class TimestampsBasedProtocolTest {

    private static final DataItem ITEM_A = new DataItem("accounts", new RowId("accounts", 1, 0));
    private static final DataItem ITEM_B = new DataItem("accounts", new RowId("accounts", 2, 0));

    private TimestampManager timestampManager;
    private TimestampsBasedProtocol protocol;

    @BeforeEach
    void setUp() {
        timestampManager = new TimestampManager();
        protocol = new TimestampsBasedProtocol(timestampManager);
    }

    @Nested
    @DisplayName("Constructor & Accessor Tests")
    class ConstructorTests {

        @Test
        @DisplayName("Default constructor instantiates valid TimestampManager internally")
        void testConstructor_Default_ShouldInstantiateValidManager() {
            // Arrange & Act
            TimestampsBasedProtocol defaultProto = new TimestampsBasedProtocol();

            // Assert
            assertNotNull(defaultProto.getTimestampManager());
        }

        @Test
        @DisplayName("getTimestampManager returns injected manager")
        void testGetTimestampManager_WithInjectedManager_ShouldReturnSameInstance() {
            // Arrange & Act & Assert
            assertSame(timestampManager, protocol.getTimestampManager());
        }
    }

    @Nested
    @DisplayName("Read Rule Tests (TS vs W-ts & R-ts)")
    class ReadRuleTests {

        @Test
        @DisplayName("requestRead grants when tx timestamp > write timestamp")
        void testRequestRead_WhenTxTimestampGreaterWriteTS_ShouldGrant() {
            // Arrange
            Transaction tx = new Transaction(1, 100L);
            timestampManager.updateWriteTS(ITEM_A, 50L);

            // Act
            AccessStatus status = protocol.requestRead(tx, ITEM_A);

            // Assert
            assertEquals(AccessStatus.GRANTED, status);
        }

        @Test
        @DisplayName("requestRead updates R-ts to max(R-ts, TS) when granted")
        void testRequestRead_WhenGranted_ShouldUpdateReadTimestamp() {
            // Arrange
            Transaction tx = new Transaction(1, 100L);
            timestampManager.updateWriteTS(ITEM_A, 50L);
            timestampManager.updateReadTS(ITEM_A, 40L);

            // Act
            protocol.requestRead(tx, ITEM_A);

            // Assert
            assertEquals(100L, timestampManager.getReadTS(ITEM_A));
        }

        @Test
        @DisplayName("requestRead grants when tx timestamp == write timestamp")
        void testRequestRead_WhenTxTimestampEqualsWriteTS_ShouldGrant() {
            // Arrange
            Transaction tx = new Transaction(1, 100L);
            timestampManager.updateWriteTS(ITEM_A, 100L);

            // Act
            AccessStatus status = protocol.requestRead(tx, ITEM_A);

            // Assert
            assertEquals(AccessStatus.GRANTED, status);
        }

        @Test
        @DisplayName("requestRead aborts when tx timestamp < write timestamp")
        void testRequestRead_WhenTxTimestampLessThanWriteTS_ShouldAbort() {
            // Arrange
            Transaction tx = new Transaction(1, 40L);
            timestampManager.updateWriteTS(ITEM_A, 100L);

            // Act
            AccessStatus status = protocol.requestRead(tx, ITEM_A);

            // Assert
            assertEquals(AccessStatus.ABORTED, status);
        }

        @Test
        @DisplayName("requestRead does not modify read timestamp when aborted")
        void testRequestRead_WhenAborted_ShouldNotModifyReadTimestamp() {
            // Arrange
            Transaction tx = new Transaction(1, 40L);
            timestampManager.updateWriteTS(ITEM_A, 100L);

            // Act
            protocol.requestRead(tx, ITEM_A);

            // Assert
            assertEquals(0L, timestampManager.getReadTS(ITEM_A));
        }

        @Test
        @DisplayName("Older read is granted if TS >= W-ts even if TS < existing R-ts")
        void testRequestRead_WhenTSLessThanExistingReadTSButGreaterOrEqualWriteTS_ShouldGrant() {
            // Arrange: Younger tx (TS=200) already read ITEM_A, so R-ts is 200. W-ts is 50.
            timestampManager.updateWriteTS(ITEM_A, 50L);
            timestampManager.updateReadTS(ITEM_A, 200L);
            Transaction olderTx = new Transaction(1, 120L); // 120 >= 50, but 120 < 200

            // Act
            AccessStatus status = protocol.requestRead(olderTx, ITEM_A);

            // Assert
            assertEquals(AccessStatus.GRANTED, status);
        }

        @Test
        @DisplayName("Older read preserves larger existing R-ts")
        void testRequestRead_WhenOlderReadGranted_ShouldPreserveLargerExistingReadTS() {
            // Arrange
            timestampManager.updateWriteTS(ITEM_A, 50L);
            timestampManager.updateReadTS(ITEM_A, 200L);
            Transaction olderTx = new Transaction(1, 120L);

            // Act
            protocol.requestRead(olderTx, ITEM_A);

            // Assert
            assertEquals(200L, timestampManager.getReadTS(ITEM_A));
        }
    }

    @Nested
    @DisplayName("Write Rule Tests (TS vs R-ts & W-ts)")
    class WriteRuleTests {

        @Test
        @DisplayName("requestWrite grants when tx timestamp >= read timestamp and >= write timestamp")
        void testRequestWrite_WhenTxTimestampGreaterOrEqualBothTS_ShouldGrant() {
            // Arrange
            Transaction tx = new Transaction(1, 100L);
            timestampManager.updateReadTS(ITEM_A, 60L);
            timestampManager.updateWriteTS(ITEM_A, 70L);

            // Act
            AccessStatus status = protocol.requestWrite(tx, ITEM_A);

            // Assert
            assertEquals(AccessStatus.GRANTED, status);
        }

        @Test
        @DisplayName("requestWrite updates write timestamp to tx timestamp when granted")
        void testRequestWrite_WhenGranted_ShouldUpdateWriteTimestamp() {
            // Arrange
            Transaction tx = new Transaction(1, 100L);
            timestampManager.updateReadTS(ITEM_A, 60L);
            timestampManager.updateWriteTS(ITEM_A, 70L);

            // Act
            protocol.requestWrite(tx, ITEM_A);

            // Assert
            assertEquals(100L, timestampManager.getWriteTS(ITEM_A));
        }

        @Test
        @DisplayName("requestWrite grants when tx timestamp == read timestamp and == write timestamp")
        void testRequestWrite_WhenTxTimestampEqualsBothTS_ShouldGrant() {
            // Arrange
            Transaction tx = new Transaction(1, 100L);
            timestampManager.updateReadTS(ITEM_A, 100L);
            timestampManager.updateWriteTS(ITEM_A, 100L);

            // Act
            AccessStatus status = protocol.requestWrite(tx, ITEM_A);

            // Assert
            assertEquals(AccessStatus.GRANTED, status);
        }

        @Test
        @DisplayName("requestWrite aborts when tx timestamp < read timestamp")
        void testRequestWrite_WhenTxTimestampLessThanReadTS_ShouldAbort() {
            // Arrange
            Transaction tx = new Transaction(1, 80L);
            timestampManager.updateReadTS(ITEM_A, 100L);
            timestampManager.updateWriteTS(ITEM_A, 50L);

            // Act
            AccessStatus status = protocol.requestWrite(tx, ITEM_A);

            // Assert
            assertEquals(AccessStatus.ABORTED, status);
        }

        @Test
        @DisplayName("requestWrite does not update write timestamp when aborted due to read timestamp")
        void testRequestWrite_WhenAbortedDueToReadTS_ShouldNotUpdateWriteTimestamp() {
            // Arrange
            Transaction tx = new Transaction(1, 80L);
            timestampManager.updateReadTS(ITEM_A, 100L);
            timestampManager.updateWriteTS(ITEM_A, 50L);

            // Act
            protocol.requestWrite(tx, ITEM_A);

            // Assert
            assertEquals(50L, timestampManager.getWriteTS(ITEM_A));
        }

        @Test
        @DisplayName("requestWrite aborts when tx timestamp < write timestamp (blind write rejected)")
        void testRequestWrite_WhenTxTimestampLessThanWriteTS_ShouldAbort() {
            // Arrange
            Transaction tx = new Transaction(1, 80L);
            timestampManager.updateReadTS(ITEM_A, 50L);
            timestampManager.updateWriteTS(ITEM_A, 100L);

            // Act
            AccessStatus status = protocol.requestWrite(tx, ITEM_A);

            // Assert
            assertEquals(AccessStatus.ABORTED, status);
        }
    }

    @Nested
    @DisplayName("Null, Resource Management, and Overload Tests")
    class LifecycleAndInputTests {

        @Test
        @DisplayName("requestRead returns ABORTED when transaction is null")
        void testRequestRead_WhenTransactionIsNull_ShouldReturnAborted() {
            // Arrange & Act
            AccessStatus status = protocol.requestRead(null, ITEM_A);

            // Assert
            assertEquals(AccessStatus.ABORTED, status);
        }

        @Test
        @DisplayName("requestRead returns ABORTED when data item is null")
        void testRequestRead_WhenDataItemIsNull_ShouldReturnAborted() {
            // Arrange
            Transaction tx = new Transaction(1, 100L);

            // Act
            AccessStatus status = protocol.requestRead(tx, null);

            // Assert
            assertEquals(AccessStatus.ABORTED, status);
        }

        @Test
        @DisplayName("requestWrite returns ABORTED when transaction is null")
        void testRequestWrite_WhenTransactionIsNull_ShouldReturnAborted() {
            // Arrange & Act
            AccessStatus status = protocol.requestWrite(null, ITEM_A);

            // Assert
            assertEquals(AccessStatus.ABORTED, status);
        }

        @Test
        @DisplayName("requestWrite returns ABORTED when data item is null")
        void testRequestWrite_WhenDataItemIsNull_ShouldReturnAborted() {
            // Arrange
            Transaction tx = new Transaction(1, 100L);

            // Act
            AccessStatus status = protocol.requestWrite(tx, null);

            // Assert
            assertEquals(AccessStatus.ABORTED, status);
        }

        @Test
        @DisplayName("requestWrite overload reuses existing registered transaction timestamp")
        void testRequestWrite_WithOldAndNewValuesReusingRegisteredTx_ShouldRespectOriginalTimestamp() {
            // Arrange
            int txId = 42;
            Transaction tx = new Transaction(txId, 200L);
            protocol.requestRead(tx, ITEM_A); // Registers tx with TS=200

            timestampManager.updateReadTS(ITEM_B, 250L); // R-ts is higher than 200

            // Act: Attempt write on ITEM_B
            AccessStatus status = protocol.requestWrite(txId, ITEM_B, "oldVal", "newVal");

            // Assert: Must be ABORTED because registered TS(200) < R-ts(250)
            assertEquals(AccessStatus.ABORTED, status);
        }

        @Test
        @DisplayName("requestWrite overload auto-registers new transaction when txId is not registered")
        void testRequestWrite_WithOldAndNewValuesUnregisteredTx_ShouldAutoRegisterAndGrant() {
            // Arrange
            int newTxId = 99;

            // Act
            AccessStatus status = protocol.requestWrite(newTxId, ITEM_A, null, "initialVal");

            // Assert
            assertEquals(AccessStatus.GRANTED, status);
            assertTrue(timestampManager.getWriteTS(ITEM_A) > 0L);
        }

        @Test
        @DisplayName("releaseResources safely ignores null transaction")
        void testReleaseResources_WhenTransactionIsNull_ShouldNotThrow() {
            // Arrange & Act & Assert
            assertDoesNotThrow(() -> protocol.releaseResources(null));
        }

        @Test
        @DisplayName("releaseResources preserves data item timestamps across transactions")
        void testReleaseResources_WhenInvoked_ShouldPreserveDataTimestamps() {
            // Arrange
            Transaction tx = new Transaction(1, 100L);
            protocol.requestWrite(tx, ITEM_A);

            // Act
            protocol.releaseResources(tx);

            // Assert
            assertEquals(100L, timestampManager.getWriteTS(ITEM_A));
        }
    }

    @Nested
    @DisplayName("Transaction Restart & Rollback Scenarios")
    class TransactionRestartTests {

        @Test
        @DisplayName("Aborted transaction succeeds when restarted with a larger timestamp")
        void testRestartTransaction_WithLargerTimestamp_ShouldSucceed() {
            // Arrange
            Transaction youngerTx = new Transaction(1, 200L);
            protocol.requestRead(youngerTx, ITEM_A); // R-ts(ITEM_A) becomes 200

            Transaction olderTx = new Transaction(2, 100L);
            AccessStatus initialAttempt = protocol.requestWrite(olderTx, ITEM_A);
            assertEquals(AccessStatus.ABORTED, initialAttempt); // 100 < 200 -> Aborted

            // Act: Restart older transaction with a newly assigned, larger timestamp
            Transaction restartedTx = new Transaction(3, 300L); // New timestamp > 200
            AccessStatus retryAttempt = protocol.requestWrite(restartedTx, ITEM_A);

            // Assert
            assertEquals(AccessStatus.GRANTED, retryAttempt);
            assertEquals(300L, timestampManager.getWriteTS(ITEM_A));
        }
    }

    @Nested
    @DisplayName("CCM Integration with Timestamp Protocol (CC-08 Acceptance Criteria)")
    class CcmIntegrationTests {

        @Test
        @DisplayName("CCM generates unique and strictly increasing timestamps for transactions")
        void testBeginTransaction_ViaCCM_ShouldAssignUniqueAndIncreasingTimestamps() {
            // Arrange
            ConcurrencyControlManager ccm = new ConcurrencyControlManager(protocol);

            // Act
            int txId1 = ccm.beginTransaction();
            int txId2 = ccm.beginTransaction();
            int txId3 = ccm.beginTransaction();

            Transaction tx1 = ccm.getTransaction(txId1);
            Transaction tx2 = ccm.getTransaction(txId2);
            Transaction tx3 = ccm.getTransaction(txId3);

            // Assert
            assertNotNull(tx1);
            assertNotNull(tx2);
            assertNotNull(tx3);
            assertTrue(tx1.getTimestamp() < tx2.getTimestamp());
            assertTrue(tx2.getTimestamp() < tx3.getTimestamp());
        }

        @Test
        @DisplayName("CCM automatically aborts and removes transaction from active map on rule violation")
        void testRequestRead_ViolationViaCCM_ShouldRemoveFromActiveTransactions() {
            // Arrange
            RecordingRecoveryManager frm = new RecordingRecoveryManager();
            ConcurrencyControlManager ccm = new ConcurrencyControlManager(protocol, frm);

            int tx1 = ccm.beginTransaction(); // earlier timestamp
            int tx2 = ccm.beginTransaction(); // later timestamp

            // tx2 writes to ITEM_A -> sets writeTS to tx2's timestamp
            ccm.requestWrite(tx2, ITEM_A);

            // Act: tx1 (older) attempts to read ITEM_A written by tx2 (younger) -> violates read rule
            AccessStatus tx1Read = ccm.requestRead(tx1, ITEM_A);

            // Assert
            assertEquals(AccessStatus.ABORTED, tx1Read);
            assertNull(ccm.getTransaction(tx1), "Aborted transaction must be removed from active map");
        }

        @Test
        @DisplayName("CCM triggers FRM rollback when timestamp rule is violated")
        void testRequestRead_ViolationViaCCM_ShouldTriggerFrmRollback() {
            // Arrange
            RecordingRecoveryManager frm = new RecordingRecoveryManager();
            ConcurrencyControlManager ccm = new ConcurrencyControlManager(protocol, frm);

            int tx1 = ccm.beginTransaction();
            int tx2 = ccm.beginTransaction();
            ccm.requestWrite(tx2, ITEM_A);

            // Act
            ccm.requestRead(tx1, ITEM_A);

            // Assert
            assertTrue(frm.rolledBack.contains(tx1), "FRM rollback must be triggered");
        }

        @Test
        @DisplayName("CCM rejects subsequent requests from transaction that was aborted by timestamp violation")
        void testSubsequentRequest_AfterTimestampAbort_ShouldBeRejected() {
            // Arrange
            RecordingRecoveryManager frm = new RecordingRecoveryManager();
            ConcurrencyControlManager ccm = new ConcurrencyControlManager(protocol, frm);

            int tx1 = ccm.beginTransaction();
            int tx2 = ccm.beginTransaction();
            ccm.requestWrite(tx2, ITEM_A);
            ccm.requestRead(tx1, ITEM_A); // aborts tx1

            // Act
            AccessStatus subsequentRead = ccm.requestRead(tx1, ITEM_B);

            // Assert
            assertEquals(AccessStatus.ABORTED, subsequentRead);
        }

        @Test
        @DisplayName("Timestamp protocol operations are non-blocking and cannot cause deadlock")
        void testConcurrentRequests_TimestampProtocol_ShouldNeverDeadlockOrBlock() {
            // Arrange
            ConcurrencyControlManager ccm = new ConcurrencyControlManager(protocol);
            int tx1 = ccm.beginTransaction();
            int tx2 = ccm.beginTransaction();

            // Act: Interleaved conflicting write requests
            AccessStatus s1 = ccm.requestWrite(tx1, ITEM_A);
            AccessStatus s2 = ccm.requestWrite(tx2, ITEM_B);
            AccessStatus s3 = ccm.requestWrite(tx2, ITEM_A);
            AccessStatus s4 = ccm.requestWrite(tx1, ITEM_B);

            // Assert: Neither transaction enters WAITING state (unlike 2PL), eliminating deadlock
            assertTrue(s1 == AccessStatus.GRANTED || s1 == AccessStatus.ABORTED);
            assertTrue(s2 == AccessStatus.GRANTED || s2 == AccessStatus.ABORTED);
            assertTrue(s3 == AccessStatus.GRANTED || s3 == AccessStatus.ABORTED);
            assertTrue(s4 == AccessStatus.GRANTED || s4 == AccessStatus.ABORTED);
        }
    }

    private static final class RecordingRecoveryManager implements IFailureRecoveryManager {
        private final List<Integer> rolledBack = new ArrayList<>();
        private final List<LogType> transactionLogs = new ArrayList<>();

        @Override
        public RecoveryResult writeTransactionLog(int txId, LogType eventType) {
            transactionLogs.add(eventType);
            return new RecoveryResult(true, 1L, 0, "test");
        }

        @Override
        public RecoveryResult writeDataLog(int txId, LogType opType, String table, RowId rid, RecordImage beforeImg, RecordImage afterImg) {
            return new RecoveryResult(true, 1L, 0, "test");
        }

        @Override
        public boolean flushLogUpTo(long lsn) {
            return true;
        }

        @Override
        public RecoveryResult rollbackTransaction(int txId) {
            rolledBack.add(txId);
            transactionLogs.add(LogType.ABORT);
            return new RecoveryResult(true, 1L, 0, "test");
        }

        @Override
        public RecoveryResult createCheckpoint() {
            return new RecoveryResult(true, 1L, 0, "test");
        }

        @Override
        public RecoveryResult recoverOnStartup() {
            return new RecoveryResult(true, 1L, 0, "test");
        }

        @Override
        public RecoveryResult recoverToPointInTime(java.time.Instant targetTime) {
            return new RecoveryResult(true, 1L, 0, "test");
        }

        @Override
        public RecoveryResult recoverToTransaction(int targetTxId) {
            return new RecoveryResult(true, 1L, 0, "test");
        }

        @Override
        public List<LogRecord> readLogs(Integer filterTxId) {
            return List.of();
        }
    }
}
