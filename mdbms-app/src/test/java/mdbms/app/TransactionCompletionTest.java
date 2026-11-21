package mdbms.app;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import mdbms.api.IFailureRecoveryManager;
import mdbms.concurrency.ConcurrencyControlManager;
import mdbms.concurrency.LockBasedProtocol;
import mdbms.models.access.DataRetrieval;
import mdbms.models.data.DataItem;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
import mdbms.models.query.ExecutionResult;
import mdbms.models.query.ParsedQuery;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.models.transaction.AccessStatus;
import mdbms.models.transaction.Transaction;
import mdbms.optimizer.QueryOptimizer;
import mdbms.optimizer.lexer.ShaylaLexer;
import mdbms.optimizer.parser.ShaylaParser;
import mdbms.processor.QueryProcessor;
import mdbms.processor.operator.CreateTableOperator;
import mdbms.recovery.FailureRecoveryFacade;
import mdbms.storage.StorageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

// bukti issue #60 pake storage, WAL FRM, CCM lock based, sama QueryProcessor yang asli
// cuma FRM yang dibungkus biar bisa disuntik gagal di append COMMIT, force COMMIT, sama rollback
class TransactionCompletionTest {
    private static final String TABLE = "users";
    private static final String INDEX = "idx_users_id";
    private static final int BASELINE_ROWS = 30;

    @TempDir Path directory;

    private StorageManager storage;
    private FailureRecoveryFacade wal;
    private FaultyRecovery recovery;
    private ReleaseRecordingProtocol protocol;
    private ConcurrencyControlManager ccm;
    private QueryProcessor processor;
    private Map<Integer, RowId> baselineRowIds;
    private List<List<Object>> baselineRows;

    @BeforeEach
    void setUp() {
        storage = new StorageManager(directory.resolve("data").toString(), 8, 4);
        wal = new FailureRecoveryFacade(directory.resolve("shayla.log"), directory.resolve("snapshot"), storage, storage);
        recovery = new FaultyRecovery(wal);
        storage.attachRecoveryManager(recovery);
        protocol = new ReleaseRecordingProtocol();
        ccm = new ConcurrencyControlManager(protocol, recovery);

        QueryOptimizer optimizer = new QueryOptimizer(storage, new ShaylaParser(new ShaylaLexer(""))) {
            @Override public ParsedQuery optimizeQuery(ParsedQuery query) { return query; }
        };
        processor = new QueryProcessor(optimizer, storage, storage, recovery, ccm);

        new CreateTableOperator(storage).execute(new TableSchema(TABLE, List.of(
            new ColumnDefinition("id", DataType.INTEGER, 0, false),
            new ColumnDefinition("name", DataType.VARCHAR, 200, false)), List.of("id"), List.of()));
        storage.createIndex(INDEX, TABLE, "id");

        // baris dibikin panjang biar halaman pertama penuh dan UPDATE yang manjangin nama kepaksa pindah halaman
        int tx = ccm.beginTransaction();
        for (int id = 1; id <= BASELINE_ROWS; id++) {
            assertOk(run(tx, "INSERT INTO users VALUES (" + id + ", '" + name(id, 120) + "');"));
        }
        assertOk(run(tx, "COMMIT;"));
        baselineRowIds = rowIdsById();
        baselineRows = heapRows();
        protocol.releases.clear();
        recovery.rollbackCalls.set(0);
        recovery.commitFlushes.set(0);
    }

    // apa pun hasil transaksinya ga boleh ada frame buffer yang masih di pin atau slot dipesan yang ketinggalan
    @AfterEach
    void tearDown() {
        assertEquals(0, storage.getBufferStats().pinnedBlocks(), "ada frame buffer yang ga dilepas");
        assertEquals(0, storage.releaseOrphanReservations(), "ada slot dipesan yang ga dilepas");
        wal.close();
    }

    @Test
    void commitWithManyWritesIsDurableBeforeLocksAreReleased() {
        int tx = ccm.beginTransaction();
        int probe = ccm.beginTransaction();
        DataItem updatedRow = new DataItem(TABLE, baselineRowIds.get(1));

        assertOk(run(tx, "INSERT INTO users VALUES (101, 'baru satu');"));
        assertOk(run(tx, "INSERT INTO users VALUES (102, 'baru dua');"));
        assertOk(run(tx, "UPDATE users SET name = '" + name(1, 200) + "' WHERE id = 1;"));
        assertOk(run(tx, "DELETE FROM users WHERE id = 2;"));
        assertNotEquals(baselineRowIds.get(1), rowIdsById().get(1), "UPDATE harusnya mindahin baris ke halaman lain");

        // pas COMMIT lagi di force, transaksi lain yang mau nulis baris yang sama masih harus nunggu
        recovery.beforeCommitFlush = () -> assertEquals(AccessStatus.WAITING, ccm.requestWrite(probe, updatedRow));
        assertOk(run(tx, "COMMIT;"));

        long commitLsn = lsnOf(tx, LogType.COMMIT);
        Release release = protocol.releaseOf(tx);
        assertTrue(release.flushedLsn() >= commitLsn, "lock dilepas sebelum COMMIT durable");
        assertEquals(0, release.pinnedBlocks(), "masih ada frame di pin pas lock dilepas");
        assertNull(ccm.getTransaction(tx));
        assertEquals(1, recovery.commitFlushes.get());

        List<Integer> ids = heapRows().stream().map(row -> (Integer) row.get(0)).toList();
        assertTrue(ids.containsAll(List.of(1, 101, 102)));
        assertFalse(ids.contains(2));
        assertIndexMatchesHeap();

        // lock udah lepas jadi transaksi yang tadi nunggu sekarang bisa dapet akses
        assertEquals(AccessStatus.GRANTED, ccm.requestWrite(probe, updatedRow));
        ccm.abortTransaction(probe, "selesai probe");
        assertEquals(0, ccm.getActiveTransactionCount());
    }

    @Test
    void abortRevertsInsertUpdateDeleteAndIndexBeforeRelease() {
        int tx = ccm.beginTransaction();
        assertOk(run(tx, "INSERT INTO users VALUES (101, 'baru');"));
        assertOk(run(tx, "INSERT INTO users VALUES (102, 'baru dua');"));
        assertOk(run(tx, "UPDATE users SET name = 'pendek' WHERE id = 3;"));
        assertOk(run(tx, "UPDATE users SET name = 'ganti lagi' WHERE id = 3;"));
        assertOk(run(tx, "DELETE FROM users WHERE id = 2;"));

        assertOk(run(tx, "ABORT;"));

        // isi heap pas lock dilepas udah balik semua, artinya rollback kelar duluan
        Release release = protocol.releaseOf(tx);
        assertEquals(baselineRows, release.heapRows());
        assertEquals(0, release.pinnedBlocks(), "masih ada frame di pin pas lock dilepas");
        assertEquals(baselineRows, heapRows());
        assertEquals(baselineRowIds, rowIdsById());
        assertIndexMatchesHeap();
        assertTrue(hasLog(tx, LogType.ABORT));
        assertFalse(hasLog(tx, LogType.COMMIT));
        assertEquals(1, recovery.rollbackCalls.get());
        assertEquals(0, ccm.getActiveTransactionCount());

        // abis flush dan dibuka ulang isinya tetep sama
        storage.flushBuffer();
        StorageManager reopened = new StorageManager(directory.resolve("data").toString(), 8, 4);
        reopened.attachRecoveryManager(recovery);
        assertEquals(baselineRows, rowsOf(reopened));
    }

    // relokasi sekarang dicatat UPDATE lama, DELETE lama berisi nilai baru, INSERT baru
    // undo DELETE nya nyoba naruh nilai baru yang lebih panjang ke slot lama yang ga muat jadi rollback gagal
    // sampai #55 beres engine masuk failed state di kasus ini, lock tetep dipegang jadi data ga rusak
    @Test
    @Disabled("nunggu #55, log relokasi UPDATE belum bisa di undo")
    void abortRevertsRelocatedUpdate() {
        int tx = ccm.beginTransaction();
        assertOk(run(tx, "UPDATE users SET name = '" + name(1, 200) + "' WHERE id = 1;"));
        assertNotEquals(baselineRowIds.get(1), rowIdsById().get(1), "UPDATE harusnya mindahin baris ke halaman lain");

        assertOk(run(tx, "ABORT;"));

        assertEquals(baselineRows, heapRows());
        assertEquals(baselineRowIds, rowIdsById());
        assertIndexMatchesHeap();
    }

    // perilaku sekarang selama #55 belum beres, rollback relokasi gagal tapi aman karena lock ga dilepas
    @Test
    void failedRelocationRollbackKeepsLocksInsteadOfCorruptingData() {
        int tx = ccm.beginTransaction();
        int probe = ccm.beginTransaction();
        assertOk(run(tx, "UPDATE users SET name = '" + name(1, 200) + "' WHERE id = 1;"));
        DataItem movedRow = new DataItem(TABLE, rowIdsById().get(1));

        assertFalse(run(tx, "ABORT;").success());

        assertTrue(ccm.isFailed());
        assertNull(protocol.releaseOrNull(tx));
        assertEquals(AccessStatus.WAITING, ccm.requestWrite(probe, movedRow));
    }

    @Test
    void failingLaterStatementAbortsEarlierWritesOfTheSameTransaction() {
        int tx = ccm.beginTransaction();
        assertOk(run(tx, "INSERT INTO users VALUES (101, 'baru');"));
        assertOk(run(tx, "UPDATE users SET name = 'ganti' WHERE id = 5;"));

        ExecutionResult failed = run(tx, "INSERT INTO users VALUES ('bukan angka', 'x');");
        assertFalse(failed.success());

        // policy nya statement gagal = seluruh transaksi dibatalin, jadi COMMIT abis itu ga boleh nyimpen apa apa
        assertNull(ccm.getTransaction(tx));
        ExecutionResult commit = run(tx, "COMMIT;");
        assertFalse(commit.success(), commit.message());
        assertEquals(baselineRows, heapRows());
        assertIndexMatchesHeap();
        assertTrue(hasLog(tx, LogType.ABORT));
        assertFalse(hasLog(tx, LogType.COMMIT));
        assertEquals(1, recovery.rollbackCalls.get(), "abort cuma boleh sekali");
    }

    @Test
    void failingParseOrPlanAlsoAbortsTheTransaction() {
        int tx = ccm.beginTransaction();
        assertOk(run(tx, "INSERT INTO users VALUES (101, 'baru');"));

        ExecutionResult failed = run(tx, "INSERT INTO tabel_gaada VALUES (1);");
        assertFalse(failed.success());

        assertNull(ccm.getTransaction(tx));
        assertFalse(run(tx, "COMMIT;").success());
        assertEquals(baselineRows, heapRows());
        assertEquals(1, recovery.rollbackCalls.get());
    }

    @Test
    void failedInsertLogReleasesTheReservedSlotAndAbortsTheTransaction() {
        int tx = ccm.beginTransaction();
        assertOk(run(tx, "UPDATE users SET name = 'ganti' WHERE id = 1;"));

        // slot udah dipesan pas log INSERT nya gagal ditulis
        recovery.failDataLog = true;
        assertFalse(run(tx, "INSERT INTO users VALUES (101, 'baru');").success());
        recovery.failDataLog = false;

        assertNull(ccm.getTransaction(tx));
        assertFalse(ccm.isFailed());
        assertEquals(baselineRows, heapRows());
        assertIndexMatchesHeap();
        assertEquals(1, recovery.rollbackCalls.get(), "abort cuma boleh sekali");
    }

    @Test
    void failedCommitAppendIsReportedAndRolledBack() {
        int tx = ccm.beginTransaction();
        assertOk(run(tx, "INSERT INTO users VALUES (101, 'baru');"));
        assertOk(run(tx, "UPDATE users SET name = 'ganti' WHERE id = 1;"));
        assertOk(run(tx, "DELETE FROM users WHERE id = 2;"));

        recovery.failCommitAppend = true;
        ExecutionResult commit = run(tx, "COMMIT;");

        assertFalse(commit.success(), "COMMIT yang log nya gagal ditulis ga boleh dibilang sukses");
        assertFalse(ccm.isFailed());
        assertEquals(baselineRows, protocol.releaseOf(tx).heapRows());
        assertEquals(baselineRows, heapRows());
        assertEquals(baselineRowIds, rowIdsById());
        assertIndexMatchesHeap();
        assertFalse(hasLog(tx, LogType.COMMIT));
        assertTrue(hasLog(tx, LogType.ABORT));
        assertEquals(1, recovery.rollbackCalls.get());

        // engine masih sehat, transaksi baru jalan normal
        recovery.failCommitAppend = false;
        int next = ccm.beginTransaction();
        assertOk(run(next, "INSERT INTO users VALUES (101, 'lagi');"));
        assertOk(run(next, "COMMIT;"));
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void uncertainCommitKeepsLocksAndMovesEngineToFailedState() throws Exception {
        int tx = ccm.beginTransaction();
        int probe = ccm.beginTransaction();
        DataItem updatedRow = new DataItem(TABLE, baselineRowIds.get(1));
        assertOk(run(tx, "UPDATE users SET name = 'ganti' WHERE id = 1;"));

        // record COMMIT udah masuk WAL tapi force nya dilaporin gagal, jadi hasilnya ga pasti
        recovery.failCommitFlush = true;
        ExecutionResult commit = run(tx, "COMMIT;");

        assertFalse(commit.success(), "COMMIT yang force nya gagal ga boleh dibilang sukses");
        assertTrue(ccm.isFailed());
        assertTrue(hasLog(tx, LogType.COMMIT));
        assertFalse(hasLog(tx, LogType.ABORT));
        assertNull(protocol.releaseOrNull(tx), "lock ga boleh dilepas kalau hasil commit ga pasti");
        assertEquals(AccessStatus.WAITING, ccm.requestWrite(probe, updatedRow));
        assertThrows(IllegalStateException.class, ccm::beginTransaction);
        assertThrows(IllegalStateException.class, ccm::awaitNoActiveTransactions);
    }

    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS)
    void rollbackFailureKeepsLocksAndMovesEngineToFailedState() throws Exception {
        int tx = ccm.beginTransaction();
        int probe = ccm.beginTransaction();
        DataItem updatedRow = new DataItem(TABLE, baselineRowIds.get(1));
        assertOk(run(tx, "UPDATE users SET name = 'ganti' WHERE id = 1;"));

        recovery.failRollback = true;
        ExecutionResult abort = run(tx, "ABORT;");

        assertFalse(abort.success(), "ABORT yang rollback nya gagal ga boleh dibilang sukses");
        assertTrue(ccm.isFailed());
        assertNull(protocol.releaseOrNull(tx), "lock ga boleh dilepas sebelum rollback kelar");
        assertEquals(AccessStatus.WAITING, ccm.requestWrite(probe, updatedRow));
        assertThrows(IllegalStateException.class, ccm::beginTransaction);
        assertThrows(IllegalStateException.class, ccm::awaitNoActiveTransactions);

        // abort ulang dari klien ga boleh nyoba rollback lagi
        assertFalse(run(tx, "ABORT;").success());
        assertEquals(1, recovery.rollbackCalls.get());
    }

    private ExecutionResult run(int tx, String sql) {
        try {
            return processor.executeQuery(sql, tx);
        } catch (RuntimeException e) {
            return new ExecutionResult(false, null, null, 0, "lolos dari QueryProcessor: " + e);
        }
    }

    private static void assertOk(ExecutionResult result) {
        assertTrue(result.success(), result.message());
    }

    private static String name(int id, int length) {
        StringBuilder builder = new StringBuilder("user" + id + "-");
        while (builder.length() < length) builder.append('x');
        return builder.toString();
    }

    private List<List<Object>> heapRows() {
        return rowsOf(storage);
    }

    private static List<List<Object>> rowsOf(StorageManager manager) {
        List<List<Object>> rows = new ArrayList<>();
        for (Row row : manager.readBlock(new DataRetrieval(TABLE, List.of(), List.of())).rows()) {
            rows.add(row.values());
        }
        rows.sort(Comparator.comparing(row -> (Integer) row.get(0)));
        return rows;
    }

    private Map<Integer, RowId> rowIdsById() {
        Map<Integer, RowId> rowIds = new LinkedHashMap<>();
        storage.readBlock(new DataRetrieval(TABLE, List.of(), List.of())).rows().stream()
            .sorted(Comparator.comparing(row -> (Integer) row.values().get(0)))
            .forEach(row -> rowIds.put((Integer) row.values().get(0), row.rowId()));
        return rowIds;
    }

    // tiap baris di heap ketemu lewat index di RowId yang sama, dan index ga punya entri lebih
    private void assertIndexMatchesHeap() {
        Map<Integer, RowId> rowIds = rowIdsById();
        for (Map.Entry<Integer, RowId> entry : rowIds.entrySet()) {
            assertEquals(List.of(entry.getValue()), storage.searchIndex(INDEX, entry.getKey()), "index id=" + entry.getKey());
        }
        List<RowId> all = storage.searchIndexRange(INDEX, Integer.MIN_VALUE, Integer.MAX_VALUE, true, true);
        assertEquals(rowIds.size(), all.size(), "index punya entri yang barisnya udah ga ada");
    }

    private boolean hasLog(int tx, LogType type) {
        return wal.readLogs(tx).stream().anyMatch(record -> record.getType() == type);
    }

    private long lsnOf(int tx, LogType type) {
        return wal.readLogs(tx).stream().filter(record -> record.getType() == type)
            .mapToLong(LogRecord::getLsn).findFirst().orElseThrow();
    }

    private record Release(long flushedLsn, int pinnedBlocks, List<List<Object>> heapRows) {}

    // nyatet kondisi WAL sama heap persis pas CCM ngelepas lock suatu transaksi
    private final class ReleaseRecordingProtocol extends LockBasedProtocol {
        private final Map<Integer, Release> releases = new LinkedHashMap<>();

        @Override
        public void releaseResources(Transaction tx) {
            // pin dihitung duluan sebelum heapRows ikut pin halaman
            int pinned = storage.getBufferStats().pinnedBlocks();
            releases.put(tx.getId(), new Release(wal.getLogManager().getWalFileHandler().getFlushedLsn(), pinned, heapRows()));
            super.releaseResources(tx);
        }

        Release releaseOf(int tx) {
            Release release = releases.get(tx);
            assertNotNull(release, "lock transaksi " + tx + " belum dilepas");
            return release;
        }

        Release releaseOrNull(int tx) {
            return releases.get(tx);
        }
    }

    // FRM asli yang bisa disuntik gagal, selain yang disuntik semuanya diterusin ke facade
    private static final class FaultyRecovery implements IFailureRecoveryManager {
        private final FailureRecoveryFacade delegate;
        private final AtomicInteger rollbackCalls = new AtomicInteger();
        private final AtomicInteger commitFlushes = new AtomicInteger();
        private volatile boolean failCommitAppend;
        private volatile boolean failCommitFlush;
        private volatile boolean failRollback;
        private volatile boolean failDataLog;
        private volatile Runnable beforeCommitFlush = () -> {};
        private volatile long commitLsn = -1;

        FaultyRecovery(FailureRecoveryFacade delegate) {
            this.delegate = delegate;
        }

        @Override
        public RecoveryResult writeTransactionLog(int txId, LogType eventType) {
            if (eventType == LogType.COMMIT && failCommitAppend) {
                return new RecoveryResult(false, -1, txId, "disuntik: append COMMIT gagal");
            }
            RecoveryResult result = delegate.writeTransactionLog(txId, eventType);
            if (eventType == LogType.COMMIT) commitLsn = result.getLsn();
            return result;
        }

        @Override
        public RecoveryResult writeDataLog(int txId, LogType opType, String table, RowId rid,
                RecordImage beforeImg, RecordImage afterImg) {
            if (failDataLog) return new RecoveryResult(false, -1, txId, "disuntik: log data gagal");
            return delegate.writeDataLog(txId, opType, table, rid, beforeImg, afterImg);
        }

        @Override
        public boolean flushLogUpTo(long lsn) {
            if (lsn == commitLsn) {
                beforeCommitFlush.run();
                if (failCommitFlush) return false;
                commitFlushes.incrementAndGet();
            }
            return delegate.flushLogUpTo(lsn);
        }

        @Override
        public RecoveryResult rollbackTransaction(int txId) {
            rollbackCalls.incrementAndGet();
            if (failRollback) return new RecoveryResult(false, -1, txId, "disuntik: rollback gagal");
            return delegate.rollbackTransaction(txId);
        }

        @Override public RecoveryResult createCheckpoint() { return delegate.createCheckpoint(); }
        @Override public RecoveryResult recoverOnStartup() { return delegate.recoverOnStartup(); }
        @Override public RecoveryResult recoverToPointInTime(Instant targetTime) { return delegate.recoverToPointInTime(targetTime); }
        @Override public RecoveryResult recoverToTransaction(int targetTxId) { return delegate.recoverToTransaction(targetTxId); }
        @Override public List<LogRecord> readLogs(Integer filterTxId) { return delegate.readLogs(filterTxId); }
    }
}
