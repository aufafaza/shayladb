package mdbms.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import mdbms.models.data.RowId;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.TransactionLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LogManagerTest {

    @TempDir
    Path tempDir;

    private WalFileHandler walFileHandler;
    private LogManager logManager;

    @BeforeEach
    void setUp() {
        Path logPath = tempDir.resolve("log_manager_test.log");
        walFileHandler = new WalFileHandler(logPath);
        logManager = new LogManager(walFileHandler);
    }

    @AfterEach
    void tearDown() {
        if (walFileHandler != null) {
            walFileHandler.close();
        }
    }

    @Test
    void testAppendTransactionLogIncrementsLsn() {
        TransactionLogRecord r1 = logManager.appendTransactionLog(1, LogType.BEGIN);
        TransactionLogRecord r2 = logManager.appendTransactionLog(1, LogType.COMMIT);

        assertEquals(1L, r1.getLsn());
        assertEquals(2L, r2.getLsn());
        assertEquals(1, logManager.getMaxTxId());
    }

    @Test
    void testAppendDataLogRecordsDetails() {
        RowId rid = new RowId("users", 1, 2);
        RecordImage before = new RecordImage(List.of("oldName"));
        RecordImage after = new RecordImage(List.of("newName"));

        DataChangeLogRecord record = logManager.appendDataLog(
                42, LogType.UPDATE, "users", rid, before, after);

        assertNotNull(record);
        assertEquals(1L, record.getLsn());
        assertEquals(42, record.getTxId());
        assertEquals("users", record.getTable());
        assertEquals(rid, record.getRowId());
        assertEquals(42, logManager.getMaxTxId());
    }

    @Test
    void testReadLogsFilterByTxId() {
        logManager.appendTransactionLog(1, LogType.BEGIN);
        logManager.appendTransactionLog(2, LogType.BEGIN);
        logManager.appendTransactionLog(1, LogType.COMMIT);
        logManager.appendTransactionLog(2, LogType.ABORT);

        List<LogRecord> tx1Logs = logManager.readLogs(1);
        assertEquals(2, tx1Logs.size());
        assertTrue(tx1Logs.stream().allMatch(r -> r.getTxId() == 1));

        List<LogRecord> tx2Logs = logManager.readLogs(2);
        assertEquals(2, tx2Logs.size());
        assertTrue(tx2Logs.stream().allMatch(r -> r.getTxId() == 2));

        List<LogRecord> allLogs = logManager.readLogs(null);
        assertEquals(4, allLogs.size());
    }

    @Test
    void testStateRestoredAfterRestart() {
        logManager.appendTransactionLog(10, LogType.BEGIN);
        logManager.appendTransactionLog(10, LogType.COMMIT);
        logManager.appendTransactionLog(25, LogType.BEGIN);

        walFileHandler.close();

        // Simulasi restart sistem menggunakan berkas WAL yang sama
        WalFileHandler restartHandler = new WalFileHandler(walFileHandler.getLogFilePath());
        LogManager restartedManager = new LogManager(restartHandler);

        assertEquals(25, restartedManager.getMaxTxId());
        TransactionLogRecord nextRec = restartedManager.appendTransactionLog(30, LogType.BEGIN);
        assertEquals(4L, nextRec.getLsn());
        assertEquals(30, restartedManager.getMaxTxId());

        restartHandler.close();
    }

    @Test
    void testConcurrentLoggingProducesUniqueMonotonicLsns() throws InterruptedException {
        int threadCount = 10;
        int logsPerThread = 50;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch finishLatch = new CountDownLatch(threadCount);

        List<Long> generatedLsns = Collections.synchronizedList(new ArrayList<>());

        for (int i = 0; i < threadCount; i++) {
            final int txId = i + 1;
            executor.submit(() -> {
                try {
                    startLatch.await();
                    for (int j = 0; j < logsPerThread; j++) {
                        TransactionLogRecord rec = logManager.appendTransactionLog(txId, LogType.BEGIN);
                        generatedLsns.add(rec.getLsn());
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    finishLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        assertTrue(finishLatch.await(5, TimeUnit.SECONDS));
        executor.shutdown();

        assertEquals(threadCount * logsPerThread, generatedLsns.size());
        Set<Long> uniqueLsns = new HashSet<>(generatedLsns);
        assertEquals(generatedLsns.size(), uniqueLsns.size());
    }

    @Test
    void testFormatLogTable() {
        logManager.appendTransactionLog(1, LogType.BEGIN);
        RowId rid = new RowId("users", 0, 0);
        logManager.appendDataLog(1, LogType.INSERT, "users", rid, null, new RecordImage(List.of("Alice", 20)));
        logManager.appendTransactionLog(1, LogType.COMMIT);

        String tableOutput = logManager.formatLogTable(logManager.readLogs(null));
        assertNotNull(tableOutput);
        assertTrue(tableOutput.contains("LSN"));
        assertTrue(tableOutput.contains("Alice"));
        assertTrue(tableOutput.contains("users"));
    }

    @Test
    void testCommitAndCheckpointThrowWhenWalFlushFails() {
        WalFileHandler failingWal = new WalFileHandler(tempDir.resolve("failing_flush.log")) {
            @Override
            public boolean flushUpTo(long lsn) {
                return false;
            }
        };
        try {
            LogManager failingManager = new LogManager(failingWal);
            failingManager.appendTransactionLog(1, LogType.BEGIN);

            assertThrows(RuntimeException.class, () -> failingManager.appendTransactionLog(1, LogType.COMMIT));
            assertThrows(RuntimeException.class, () -> failingManager.appendCheckpointLog(List.of(1)));
        } finally {
            failingWal.close();
        }
    }

    @Test
    void testAppendRelocationLogRecordsOldAndNewRowIds() {
        RowId oldRid = new RowId("users", 0, 3);
        RowId newRid = new RowId("users", 2, 0);
        RecordImage before = new RecordImage(List.of(1, "A"));
        RecordImage after = new RecordImage(List.of(1, "A_Relocated_Long_Payload"));

        DataChangeLogRecord record = logManager.appendRelocationLog(
                12, "users", oldRid, newRid, before, after);

        assertNotNull(record);
        assertTrue(record.isRelocation());
        assertEquals(oldRid, record.getRowId());
        assertEquals(newRid, record.getNewRowId());
        assertEquals(12, logManager.getMaxTxId());

        String formatted = logManager.formatLogTable(logManager.readLogs(12));
        assertTrue(formatted.contains("0:3->2:0"));
    }

    @Test
    void testConcurrentAppendsPreserveStrictlyMonotonicLsnOrderOnDisk() throws Exception {
        int threadCount = 8;
        int opsPerThread = 25;
        java.util.concurrent.CyclicBarrier barrier = new java.util.concurrent.CyclicBarrier(threadCount);
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(threadCount);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int t = 1; t <= threadCount; t++) {
                final int txId = t;
                futures.add(pool.submit(() -> {
                    barrier.await(5, java.util.concurrent.TimeUnit.SECONDS);
                    logManager.appendTransactionLog(txId, LogType.BEGIN);
                    for (int i = 0; i < opsPerThread; i++) {
                        logManager.appendDataLog(
                                txId, LogType.INSERT, "items", new RowId("items", txId, i),
                                null, new RecordImage(List.of(txId * 100 + i, "val")));
                    }
                    logManager.appendTransactionLog(txId, LogType.COMMIT);
                    return null;
                }));
            }
            for (java.util.concurrent.Future<?> f : futures) {
                f.get(10, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        List<LogRecord> logs = logManager.readLogs(null);
        assertEquals(threadCount * (opsPerThread + 2), logs.size());
        for (int i = 0; i < logs.size(); i++) {
            assertEquals(i + 1L, logs.get(i).getLsn(),
                    "LSN pada berkas WAL harus urut menaik ketat tanpa pembalikan urutan");
        }
    }
}
