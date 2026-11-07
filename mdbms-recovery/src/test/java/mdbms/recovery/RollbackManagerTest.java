package mdbms.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RollbackManagerTest {

    @TempDir
    Path tempDir;

    private WalFileHandler walFileHandler;
    private LogManager logManager;
    private InMemoryStorageStub storageStub;
    private RollbackManager rollbackManager;

    @BeforeEach
    void setUp() {
        Path walPath = tempDir.resolve("rollback_test.log");
        walFileHandler = new WalFileHandler(walPath);
        logManager = new LogManager(walFileHandler);
        storageStub = new InMemoryStorageStub();
        rollbackManager = new RollbackManager(logManager, storageStub, storageStub);
    }

    @AfterEach
    void tearDown() {
        if (walFileHandler != null) {
            walFileHandler.close();
        }
    }

    @Test
    void tesUrutan() {
        int txId = 1;
        RowId r1 = new RowId("users", 0, 0);
        RowId r2 = new RowId("users", 0, 1);

        // Keadaan awal sebelum transaksi 1: r1 sudah ada dengan ["Bob", 25]
        storageStub.putDirect(r1, List.of("Bob", 25));

        logManager.appendTransactionLog(txId, LogType.BEGIN);

        //INSERT r2 = ["Alice", 20]
        //UPDATE r1 dari ["Bob", 25] menjadi ["Bob", 26]
        //DELETE r1 (yang saat itu bernilai ["Bob", 26])
        storageStub.insertAt(r2, List.of("Alice", 20));
        logManager.appendDataLog(txId, LogType.INSERT, "users", r2, null, new RecordImage(List.of("Alice", 20)));
        storageStub.updateByRowId(r1, List.of("Bob", 26));
        logManager.appendDataLog(
            txId,
            LogType.UPDATE,
            "users",
            r1,
            new RecordImage(List.of("Bob", 25)),
            new RecordImage(List.of("Bob", 26))
        );

        
        storageStub.deleteByRowId(r1);
        logManager.appendDataLog(
            txId,
            LogType.DELETE,
            "users",
            r1,
            new RecordImage(List.of("Bob", 26)),
            null
        );

        storageStub.clearOperationHistory();

        RecoveryResult result = rollbackManager.rollback(txId);

        assertTrue(result.isSuccess());
        assertEquals(1, result.getMaxTxId());
        assertTrue(result.getMessage().contains("txId=1"));

        // Cek urutan undo, harus terbalik
        assertEquals(List.of("INSERT_AT:0:0", "UPDATE:0:0", "DELETE:0:1"), storageStub.getOperationHistory());

        // r1 kembali ke ["Bob", 25] dan r2 terhapus
        assertEquals(List.of("Bob", 25), storageStub.getRow(r1));
        assertNull(storageStub.getRow(r2));

        // Buffer di-flush dan WAL mencatat ABORT
        assertEquals(1, storageStub.getFlushCount());
        List<LogRecord> txLogs = logManager.readLogs(txId);
        assertEquals(LogType.ABORT, txLogs.get(txLogs.size() - 1).getType());
        assertEquals(result.getLsn(), txLogs.get(txLogs.size() - 1).getLsn());
        assertTrue(walFileHandler.getFlushedLsn() >= result.getLsn());
    }

    @Test
    void tesIsolation() {
        int tx1 = 1;
        int tx2 = 2;
        RowId r1 = new RowId("users", 0, 0);
        RowId r2 = new RowId("users", 0, 1);

        storageStub.putDirect(r1, List.of("Tx1_Old"));
        storageStub.putDirect(r2, List.of("Tx2_Old"));

        logManager.appendTransactionLog(tx1, LogType.BEGIN);
        logManager.appendTransactionLog(tx2, LogType.BEGIN);

        // Tx1 mengubah r1
        storageStub.updateByRowId(r1, List.of("Tx1_New"));
        logManager.appendDataLog(
            tx1,
            LogType.UPDATE,
            "users",
            r1,
            new RecordImage(List.of("Tx1_Old")),
            new RecordImage(List.of("Tx1_New"))
        );

        // Tx2 mengubah r2
        storageStub.updateByRowId(r2, List.of("Tx2_New"));
        logManager.appendDataLog(
            tx2,
            LogType.UPDATE,
            "users",
            r2,
            new RecordImage(List.of("Tx2_Old")),
            new RecordImage(List.of("Tx2_New"))
        );

        // Rollback hanya tx1
        RecoveryResult result = rollbackManager.rollback(tx1);
        assertTrue(result.isSuccess());

        // Data tx1 dikembalikan, sedangkan data tx2 tidak berubah
        assertEquals(List.of("Tx1_Old"), storageStub.getRow(r1));
        assertEquals(List.of("Tx2_New"), storageStub.getRow(r2));

        // Log tx2 tidak memiliki ABORT
        List<LogRecord> tx2Logs = logManager.readLogs(tx2);
        assertEquals(2, tx2Logs.size());
        assertTrue(tx2Logs.stream().noneMatch(r -> r.getType() == LogType.ABORT));
    }

    @Test
    void tesInvalidId() {
        RowId r1 = new RowId("users", 0, 0);
        storageStub.putDirect(r1, List.of("Initial"));
        logManager.appendTransactionLog(1, LogType.BEGIN);
        storageStub.clearOperationHistory();

        RecoveryResult unknownResult = rollbackManager.rollback(999);
        assertFalse(unknownResult.isSuccess());
        assertEquals(-1L, unknownResult.getLsn());
        assertTrue(unknownResult.getMessage().contains("Unknown transaction"));
        assertTrue(unknownResult.getMessage().contains("txId=999"));

        RecoveryResult negativeResult = rollbackManager.rollback(-5);
        assertFalse(negativeResult.isSuccess());
        assertTrue(negativeResult.getMessage().contains("Unknown transaction"));

        // Tidak ada operasi storage maupun log tambahan untuk 999
        assertTrue(storageStub.getOperationHistory().isEmpty());
        assertEquals(0, storageStub.getFlushCount());
        assertTrue(logManager.readLogs(999).isEmpty());
    }

    @Test
    void tesIdempotence() {
        int txId = 3;
        RowId r1 = new RowId("users", 0, 0);
        storageStub.putDirect(r1, List.of("Original"));

        logManager.appendTransactionLog(txId, LogType.BEGIN);
        storageStub.updateByRowId(r1, List.of("Modified"));
        logManager.appendDataLog(
            txId,
            LogType.UPDATE,
            "users",
            r1,
            new RecordImage(List.of("Original")),
            new RecordImage(List.of("Modified"))
        );

        RecoveryResult first = rollbackManager.rollback(txId);
        assertTrue(first.isSuccess());
        assertEquals(List.of("Original"), storageStub.getRow(r1));

        // Ubah nilai di storage setelah rollback pertama untuk memastikan rollback kedua tidak menyentuhnya
        storageStub.putDirect(r1, List.of("SubsequentValue"));
        storageStub.clearOperationHistory();
        int logCountAfterFirst = logManager.readLogs(txId).size();

        RecoveryResult second = rollbackManager.rollback(txId);
        assertFalse(second.isSuccess());
        assertTrue(second.getMessage().contains("already been undone"));
        assertEquals(List.of("SubsequentValue"), storageStub.getRow(r1));
        assertTrue(storageStub.getOperationHistory().isEmpty());
        assertEquals(logCountAfterFirst, logManager.readLogs(txId).size());
    }

    @Test
    void tesKonkurensi() throws Exception {
        int txId = 10;
        RowId r1 = new RowId("items", 0, 0);
        RowId r2 = new RowId("items", 0, 1);

        logManager.appendTransactionLog(txId, LogType.BEGIN);
        logManager.appendDataLog(txId, LogType.INSERT, "items", r1, null, new RecordImage(List.of("A")));
        logManager.appendDataLog(txId, LogType.INSERT, "items", r2, null, new RecordImage(List.of("B")));

        CountDownLatch firstUndoEntered = new CountDownLatch(1);
        CountDownLatch allowFirstUndoFinish = new CountDownLatch(1);
        AtomicInteger undoCallCount = new AtomicInteger(0);
        AtomicBoolean statusReturnedBeforeRelease = new AtomicBoolean(false);
        AtomicBoolean accessProtectedDuringUndo = new AtomicBoolean(false);

        RollbackManager controlledManager = new RollbackManager(logManager, storageStub, storageStub) {
            @Override
            protected boolean undo(DataChangeLogRecord change) {
                int count = undoCallCount.incrementAndGet();
                accessProtectedDuringUndo.set(isAccessProtected() && isRollingBack(txId));
                if (count == 1) {
                    firstUndoEntered.countDown();
                    try {
                        allowFirstUndoFinish.await(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                return super.undo(change);
            }
        };

        ExecutorService executor = Executors.newFixedThreadPool(2);
        Future<RecoveryResult> primaryFuture = executor.submit(() -> {
            RecoveryResult res = controlledManager.rollback(txId);
            statusReturnedBeforeRelease.set(allowFirstUndoFinish.getCount() > 0);
            return res;
        });

        assertTrue(firstUndoEntered.await(5, TimeUnit.SECONDS));
        assertTrue(controlledManager.isRollingBack(txId));
        assertTrue(controlledManager.isAccessProtected());

        // Panggilan rollback kedua saat transaksi yang sama sedang di-undo tidak boleh mengubah apa pun
        RecoveryResult duplicateWhileActive = controlledManager.rollback(txId);
        assertFalse(duplicateWhileActive.isSuccess());
        assertTrue(duplicateWhileActive.getMessage().contains("already being undone"));

        // Pastikan primaryFuture belum selesai sebelum seluruh undo selesai
        assertFalse(primaryFuture.isDone());

        allowFirstUndoFinish.countDown();
        RecoveryResult primaryResult = primaryFuture.get(5, TimeUnit.SECONDS);
        executor.shutdown();

        assertTrue(primaryResult.isSuccess());
        assertTrue(accessProtectedDuringUndo.get());
        assertFalse(statusReturnedBeforeRelease.get());
        assertEquals(2, undoCallCount.get());
        assertFalse(controlledManager.isRollingBack(txId));
        assertFalse(controlledManager.isAccessProtected());
    }

    private static final class InMemoryStorageStub implements IDataAccess, IDurableStorage {
        private final Map<RowId, List<Object>> rows = new HashMap<>();
        private final List<String> operationHistory = Collections.synchronizedList(new ArrayList<>());
        private final AtomicInteger flushCount = new AtomicInteger(0);

        void putDirect(RowId rid, List<Object> values) {
            rows.put(rid, List.copyOf(values));
        }

        List<Object> getRow(RowId rid) {
            return rows.get(rid);
        }

        List<String> getOperationHistory() {
            return List.copyOf(operationHistory);
        }

        void clearOperationHistory() {
            operationHistory.clear();
        }

        int getFlushCount() {
            return flushCount.get();
        }

        @Override
        public void insertAt(RowId rowId, List<Object> values) {
            rows.put(rowId, List.copyOf(values));
            operationHistory.add("INSERT_AT:" + rowId.blockNumber() + ":" + rowId.slot());
        }

        @Override
        public Row updateByRowId(RowId rowId, List<Object> values) {
            rows.put(rowId, List.copyOf(values));
            operationHistory.add("UPDATE:" + rowId.blockNumber() + ":" + rowId.slot());
            return new Row(rowId, List.copyOf(values));
        }

        @Override
        public Row deleteByRowId(RowId rowId) {
            List<Object> removed = rows.remove(rowId);
            operationHistory.add("DELETE:" + rowId.blockNumber() + ":" + rowId.slot());
            return new Row(rowId, removed != null ? removed : List.of());
        }

        @Override
        public void flushBuffer() {
            flushCount.incrementAndGet();
        }

        @Override
        public BufferStats getBufferStats() {
            return null;
        }

        @Override
        public Rows readBlock(DataRetrieval request) {
            return new Rows(List.of());
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
            List<Object> vals = rows.get(rowId);
            return vals != null ? new Row(rowId, vals) : null;
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
            boolean highInclusive
        ) {
            return List.of();
        }
    }
}
