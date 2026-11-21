package mdbms.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
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
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

public class RollbackManagerTest {
    @Test
    void rollbackRetriesTransientPinConflictUntilFlushSucceeds(@TempDir Path dir) {
        WalFileHandler wal = new WalFileHandler(dir.resolve("wal_pin_retry.log"));
        try {
            LogManager lm = new LogManager(wal);
            RowId rid = new RowId("users", 0, 0);
            lm.appendTransactionLog(1, LogType.BEGIN);
            lm.appendDataLog(1, LogType.INSERT, "users", rid, null, new RecordImage(List.of(1, "A")));

            AtomicInteger flushAttempts = new AtomicInteger(0);
            IDurableStorage flakyPinStorage = new IDurableStorage() {
                @Override
                public void flushBuffer() {
                    if (flushAttempts.incrementAndGet() <= 3) {
                        throw new IllegalStateException("Frame is still pinned: users.tbl block 0");
                    }
                }

                @Override
                public BufferStats getBufferStats() {
                    return null;
                }
            };

            RollbackManager rm = new RollbackManager(lm, new NoOpUndoDataAccess(), flakyPinStorage);
            RecoveryResult result = rm.rollback(1);

            assertTrue(result.isSuccess(), "Rollback harus sukses setelah konflik pin sementara selesai");
            assertEquals(4, flushAttempts.get(), "flushBuffer harus dicoba ulang 3 kali gagal + 1 kali sukses");

            List<LogRecord> txLogs = lm.readLogs(1);
            assertEquals(LogType.ABORT, txLogs.get(txLogs.size() - 1).getType());
        } finally {
            wal.close();
        }
    }

    @Test
    void rollbackDoesNotRetryNonPinFlushError(@TempDir Path dir) {
        WalFileHandler wal = new WalFileHandler(dir.resolve("wal_non_pin_error.log"));
        try {
            LogManager lm = new LogManager(wal);
            RowId rid = new RowId("users", 0, 0);
            lm.appendTransactionLog(1, LogType.BEGIN);
            lm.appendDataLog(1, LogType.INSERT, "users", rid, null, new RecordImage(List.of(1, "A")));

            AtomicInteger flushAttempts = new AtomicInteger(0);
            IDurableStorage failingStorage = new IDurableStorage() {
                @Override
                public void flushBuffer() {
                    flushAttempts.incrementAndGet();
                    throw new IllegalStateException("Disk I/O failure");
                }

                @Override
                public BufferStats getBufferStats() {
                    return null;
                }
            };

            RollbackManager rm = new RollbackManager(lm, new NoOpUndoDataAccess(), failingStorage);
            RecoveryResult result = rm.rollback(1);

            assertFalse(result.isSuccess(), "Rollback harus gagal pada error flush non-pin");
            assertEquals(1, flushAttempts.get(), "Error non-pin tidak boleh di-retry");
            assertTrue(
                    lm.readLogs(1).stream().noneMatch(r -> r.getType() == LogType.ABORT),
                    "Log ABORT tidak boleh ditulis jika flushBuffer gagal"
            );
        } finally {
            wal.close();
        }
    }

    @Test
    void rollbackCompletesAndPreservesInterruptFlagWhenThreadInterrupted(@TempDir Path dir) {
        WalFileHandler wal = new WalFileHandler(dir.resolve("wal_interrupted_rollback.log"));
        try {
            LogManager lm = new LogManager(wal);
            RowId rid = new RowId("users", 0, 0);
            lm.appendTransactionLog(1, LogType.BEGIN);
            lm.appendDataLog(1, LogType.INSERT, "users", rid, null, new RecordImage(List.of(1, "A")));

            AtomicInteger flushAttempts = new AtomicInteger(0);
            IDurableStorage oneTransientPinStorage = new IDurableStorage() {
                @Override
                public void flushBuffer() {
                    // Memicu parkUninterruptibly sekaligus menguji ketahanan WalFileHandler saat thread interrupted
                    if (flushAttempts.incrementAndGet() == 1) {
                        throw new IllegalStateException("Frame is still pinned: users.tbl block 0");
                    }
                }

                @Override
                public BufferStats getBufferStats() {
                    return null;
                }
            };

            RollbackManager rm = new RollbackManager(lm, new NoOpUndoDataAccess(), oneTransientPinStorage);

            Thread.currentThread().interrupt();
            RecoveryResult result;
            try {
                result = rm.rollback(1);
                assertTrue(result.isSuccess(), "Rollback harus tetap tuntas meski thread dalam kondisi interrupted");
                assertTrue(Thread.currentThread().isInterrupted(), "Status interrupt thread harus tetap dipertahankan");
            } finally {
                Thread.interrupted(); // bersihkan interrupt flag agar tidak memengaruhi test berikutnya
            }

            assertEquals(2, flushAttempts.get());
            List<LogRecord> tx1Logs = lm.readLogs(1);
            assertEquals(LogType.ABORT, tx1Logs.get(tx1Logs.size() - 1).getType());

            // Pastikan channel WAL bersama tetap terbuka dan sehat untuk transaksi lain setelahnya
            assertNotNull(lm.appendTransactionLog(2, LogType.BEGIN));
            assertNotNull(lm.appendTransactionLog(2, LogType.COMMIT));
        } finally {
            wal.close();
        }
    }

    private static final class NoOpUndoDataAccess implements IDataAccess {
        @Override public Rows readBlock(DataRetrieval request) { return null; }
        @Override public List<RowId> writeBlock(DataWrite request) { return List.of(); }
        @Override public int deleteBlock(DataDeletion request) { return 0; }
        @Override public Row fetchByRowId(RowId rowId) { return null; }
        @Override public RowId reserveRowId(String table, List<Object> values) { return null; }
        @Override public void releaseRowId(RowId rowId) {}
        @Override public void insertAt(RowId rowId, List<Object> values) {}
        @Override public Row updateByRowId(RowId rowId, List<Object> values) { return null; }
        @Override public Row deleteByRowId(RowId rowId) { return null; }
        @Override public List<RowId> searchIndex(String indexName, Object key) { return List.of(); }
        @Override public List<RowId> searchIndexRange(String indexName, Object low, Object high, boolean lowInc, boolean highInc) { return List.of(); }
    }
}
