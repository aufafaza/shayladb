package mdbms.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.List;
import mdbms.models.data.RowId;
import mdbms.models.log.CheckpointLogRecord;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.TransactionLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WalFileHandlerTest {

    @TempDir
    Path tempDir;

    private Path logPath;
    private WalFileHandler walHandler;

    @BeforeEach
    void setUp() {
        logPath = tempDir.resolve("wal_test.log");
        walHandler = new WalFileHandler(logPath);
    }

    @AfterEach
    void tearDown() {
        if (walHandler != null) {
            walHandler.close();
        }
    }

    @Test
    void testAppendAndReadTransactionLog() {
        Instant now = Instant.now();
        TransactionLogRecord beginRecord = new TransactionLogRecord(1L, 10, LogType.BEGIN, now);
        TransactionLogRecord commitRecord = new TransactionLogRecord(2L, 10, LogType.COMMIT, now);

        assertTrue(walHandler.append(beginRecord));
        assertTrue(walHandler.append(commitRecord));

        List<LogRecord> records = walHandler.readAll();
        assertEquals(2, records.size());

        assertInstanceOf(TransactionLogRecord.class, records.get(0));
        assertEquals(1L, records.get(0).getLsn());
        assertEquals(10, records.get(0).getTxId());
        assertEquals(LogType.BEGIN, records.get(0).getType());

        assertInstanceOf(TransactionLogRecord.class, records.get(1));
        assertEquals(2L, records.get(1).getLsn());
        assertEquals(LogType.COMMIT, records.get(1).getType());
    }

    @Test
    void testAppendAndReadDataChangeLogWithAbsentImages() {
        Instant now = Instant.now();
        RowId rid = new RowId("students", 0, 1);
        RecordImage newRow = new RecordImage(List.of(101, "Alice", 3.75f));
        DataChangeLogRecord insertRecord = new DataChangeLogRecord(
                1L, 5, LogType.INSERT, now, "students", rid, null, newRow);

        DataChangeLogRecord deleteRecord = new DataChangeLogRecord(
                2L, 5, LogType.DELETE, now, "students", rid, newRow, null);

        assertTrue(walHandler.append(insertRecord));
        assertTrue(walHandler.append(deleteRecord));

        List<LogRecord> records = walHandler.readAll();
        assertEquals(2, records.size());

        DataChangeLogRecord rec1 = assertInstanceOf(DataChangeLogRecord.class, records.get(0));
        assertEquals("students", rec1.getTable());
        assertEquals(0, rec1.getRowId().blockNumber());
        assertEquals(1, rec1.getRowId().slot());
        assertNull(rec1.getBeforeImage());
        assertNotNull(rec1.getAfterImage());
        assertEquals(3, rec1.getAfterImage().values().size());
        assertEquals(101, rec1.getAfterImage().values().get(0));
        assertEquals("Alice", rec1.getAfterImage().values().get(1));

        DataChangeLogRecord rec2 = assertInstanceOf(DataChangeLogRecord.class, records.get(1));
        assertNotNull(rec2.getBeforeImage());
        assertNull(rec2.getAfterImage());
    }

    @Test
    void testAppendAndReadCheckpointLog() {
        Instant now = Instant.now();
        CheckpointLogRecord cpRecord = new CheckpointLogRecord(10L, now, List.of(1, 2, 3));

        assertTrue(walHandler.append(cpRecord));

        List<LogRecord> records = walHandler.readAll();
        assertEquals(1, records.size());
        CheckpointLogRecord parsed = assertInstanceOf(CheckpointLogRecord.class, records.get(0));
        assertEquals(10L, parsed.getLsn());
        assertEquals(List.of(1, 2, 3), parsed.getActiveTxIds());
    }

    @Test
    void testFlushUpTo() {
        TransactionLogRecord rec = new TransactionLogRecord(5L, 1, LogType.BEGIN, Instant.now());
        walHandler.append(rec);

        assertTrue(walHandler.flushUpTo(5L));
        assertTrue(walHandler.flushUpTo(0L));
        assertFalse(walHandler.flushUpTo(-1L));
    }

    @Test
    void testCrashTruncatedLastLineIgnored() throws IOException {
        Instant now = Instant.now();
        TransactionLogRecord rec1 = new TransactionLogRecord(1L, 1, LogType.BEGIN, now);
        walHandler.append(rec1);
        walHandler.close();

        // Simulasi baris terakhir terpotong saat crash
        Files.writeString(logPath, "2|1|INSER", StandardOpenOption.APPEND);

        WalFileHandler recoveryHandler = new WalFileHandler(logPath);
        List<LogRecord> records = recoveryHandler.readAll();

        assertEquals(1, records.size());
        assertEquals(1L, records.get(0).getLsn());
        recoveryHandler.close();
    }

    @Test
    void testCorruptedLineInMiddleThrowsException() throws IOException {
        Instant now = Instant.now();
        TransactionLogRecord rec1 = new TransactionLogRecord(1L, 1, LogType.BEGIN, now);
        TransactionLogRecord rec2 = new TransactionLogRecord(3L, 1, LogType.COMMIT, now);
        walHandler.append(rec1);
        walHandler.close();

        // Sisipkan baris rusak di tengah berkas log
        Files.writeString(logPath, "CORRUPTED_MIDDLE_LINE\n", StandardOpenOption.APPEND);

        WalFileHandler recoveryHandler = new WalFileHandler(logPath);
        recoveryHandler.append(rec2);

        assertThrows(IllegalStateException.class, recoveryHandler::readAll);
        recoveryHandler.close();
    }
}
