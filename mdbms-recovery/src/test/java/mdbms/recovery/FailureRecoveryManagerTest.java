package mdbms.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.DataChangeLogRecord;
import mdbms.models.LogRecord;
import mdbms.models.LogType;
import mdbms.models.RecordImage;
import mdbms.models.RecoveryResult;
import mdbms.models.RowId;
import mdbms.models.TransactionLogRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class FailureRecoveryManagerTest {

    private IFailureRecoveryManager frm;

    @BeforeEach
    void setUp() {
        frm = new FailureRecoveryManager();
    }

    @Test
    void writeTransactionLogTracksMaxTxId() {
        RecoveryResult result = frm.writeTransactionLog(3, LogType.BEGIN);

        assertTrue(result.isSuccess());
        assertEquals(1L, result.getLsn());
        assertEquals(3, result.getMaxTxId());
        assertTrue(result.getMessage().contains("BEGIN"));
    }

    @Test
    void writeDataLogTracksMaxTxId() {
        RecoveryResult result = frm.writeDataLog(
            4,
            LogType.UPDATE,
            "users",
            new RowId("users", 0, 0),
            new RecordImage(List.of("old")),
            new RecordImage(List.of("new"))
        );

        assertTrue(result.isSuccess());
        assertEquals(4, result.getMaxTxId());
        assertTrue(result.getMessage().contains("users"));
    }

    @Test
    void maxTxIdNeverDecreases() {
        frm.writeTransactionLog(9, LogType.BEGIN);

        assertEquals(9, frm.writeTransactionLog(2, LogType.BEGIN).getMaxTxId());
    }

    @Test
    void flushLogUpToAcceptsZeroAndPositiveLsn() {
        assertTrue(frm.flushLogUpTo(0));
        assertTrue(frm.flushLogUpTo(42));
    }

    @Test
    void flushLogUpToRejectsNegativeLsn() {
        assertFalse(frm.flushLogUpTo(-1));
    }

    @Test
    void rollbackTransactionReportsTransactionId() {
        RecoveryResult result = frm.rollbackTransaction(7);

        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains("txId=7"));
    }

    @Test
    void createCheckpointSucceeds() {
        assertTrue(frm.createCheckpoint().isSuccess());
    }

    @Test
    void recoverOnStartupSucceeds() {
        assertTrue(frm.recoverOnStartup().isSuccess());
    }

    @Test
    void recoverToPointInTimeReportsTargetTime() {
        Instant target = Instant.parse("2026-10-31T00:00:00Z");

        RecoveryResult result = frm.recoverToPointInTime(target);

        assertTrue(result.isSuccess());
        assertTrue(result.getMessage().contains(target.toString()));
    }

    @Test
    void recoverToTransactionReturnsTargetAsMaxTxId() {
        RecoveryResult result = frm.recoverToTransaction(5);

        assertTrue(result.isSuccess());
        assertEquals(5, result.getMaxTxId());
        assertTrue(result.getMessage().contains("txId=5"));
    }

    @Test
    void readLogsReturnsTypedDummyRecords() {
        List<LogRecord> logs = frm.readLogs(null);

        assertEquals(3, logs.size());
        assertInstanceOf(TransactionLogRecord.class, logs.get(0));
        assertInstanceOf(DataChangeLogRecord.class, logs.get(1));
        assertEquals(LogType.COMMIT, logs.get(2).getType());
    }

    @Test
    void readLogsAppliesTransactionFilter() {
        assertTrue(frm.readLogs(8).stream().allMatch(log -> log.getTxId() == 8));
    }
}
