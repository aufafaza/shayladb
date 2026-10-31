package mdbms.models;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class LogRecordTest {

    private static final Instant NOW = Instant.parse("2026-10-31T00:00:00Z");

    @Test
    void transactionLogRecordKeepsFields() {
        LogRecord log = new TransactionLogRecord(1L, 2, LogType.BEGIN, NOW);

        assertEquals(1L, log.getLsn());
        assertEquals(2, log.getTxId());
        assertEquals(LogType.BEGIN, log.getType());
        assertEquals(NOW, log.getTimestamp());
    }

    @Test
    void dataChangeLogRecordKeepsImages() {
        RowId rowId = new RowId("users", 0, 0);
        RecordImage before = new RecordImage(List.of("old"));
        RecordImage after = new RecordImage(List.of("new"));

        DataChangeLogRecord log = new DataChangeLogRecord(1L, 2, LogType.UPDATE, NOW, "users", rowId, before, after);

        assertEquals("users", log.getTable());
        assertEquals(rowId, log.getRowId());
        assertEquals(before, log.getBeforeImage());
        assertEquals(after, log.getAfterImage());
    }

    @Test
    void checkpointLogRecordHasCheckpointType() {
        assertEquals(LogType.CHECKPOINT, new CheckpointLogRecord(1L, NOW, List.of()).getType());
    }

    @Test
    void checkpointLogRecordCopiesActiveTransactions() {
        List<Integer> active = new ArrayList<>(List.of(1));

        CheckpointLogRecord log = new CheckpointLogRecord(1L, NOW, active);
        active.add(2);

        assertEquals(List.of(1), log.getActiveTxIds());
        assertThrows(UnsupportedOperationException.class, () -> log.getActiveTxIds().add(3));
    }

    @Test
    void recoveryResultKeepsFields() {
        RecoveryResult result = new RecoveryResult(true, 3L, 4, "done");

        assertEquals(true, result.isSuccess());
        assertEquals(3L, result.getLsn());
        assertEquals(4, result.getMaxTxId());
        assertEquals("done", result.getMessage());
    }
}
