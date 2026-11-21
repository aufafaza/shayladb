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
import mdbms.models.transaction.TransactionState;
import mdbms.optimizer.QueryOptimizer;
import mdbms.optimizer.parser.ShaylaParser;
import mdbms.processor.QueryProcessor;
import mdbms.processor.operator.CreateTableOperator;
import mdbms.recovery.FailureRecoveryFacade;
import mdbms.storage.StorageManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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

        QueryOptimizer optimizer = new QueryOptimizer(storage, new ShaylaParser()) {
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

    @AfterEach
    void tearDown() {
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

    // skenario STO-F1, INSERT dengan primary key yang udah ada ditolak dan baris asli tetap satu
    @Test
    void duplicatePrimaryKeyInsertIsRejected() {
        int tx = ccm.beginTransaction();
        ExecutionResult result = run(tx, "INSERT INTO users VALUES (1, 'Duplicate');");

        assertFalse(result.success());
        assertTrue(result.message().toLowerCase().contains("duplicate primary key"), result.message());
        assertEquals(baselineRows, heapRows());
        assertIndexMatchesHeap();
    }

    // UPDATE yang ganti primary key jadi punya baris lain ditolak, UPDATE kolom lain tetap jalan
    @Test
    void updateToExistingPrimaryKeyIsRejected() {
        int tx = ccm.beginTransaction();
        ExecutionResult result = run(tx, "UPDATE users SET id = 1 WHERE id = 2;");

        assertFalse(result.success());
        assertTrue(result.message().toLowerCase().contains("duplicate primary key"), result.message());
        assertEquals(baselineRows, heapRows());

        int next = ccm.beginTransaction();
        assertOk(run(next, "UPDATE users SET name = 'tetap boleh' WHERE id = 2;"));
        assertOk(run(next, "COMMIT;"));
    }

    @Test
    void abortRevertsRelocatedUpdate() {
        int tx = ccm.beginTransaction();
        assertOk(run(tx, "UPDATE users SET name = '" + name(1, 200) + "' WHERE id = 1;"));
        assertNotEquals(baselineRowIds.get(1), rowIdsById().get(1), "UPDATE harusnya mindahin baris ke halaman lain");

        assertOk(run(tx, "ABORT;"));

        assertEquals(baselineRows, heapRows());
        assertEquals(baselineRowIds, rowIdsById());
        assertIndexMatchesHeap();
    }

    @Test
    void failedRelocationRollbackKeepsLocksInsteadOfCorruptingData() {
        int tx = ccm.beginTransaction();
        int probe = ccm.beginTransaction();
        assertOk(run(tx, "UPDATE users SET name = '" + name(1, 200) + "' WHERE id = 1;"));
        DataItem movedRow = new DataItem(TABLE, rowIdsById().get(1));

        recovery.failRollback = true;

        assertFalse(run(tx, "ABORT;").success());

        assertTrue(ccm.isFailed());
        assertNull(protocol.releaseOrNull(tx));
        assertEquals(AccessStatus.WAITING, ccm.requestWrite(probe, movedRow));
    }

    @Test
    void relocationProtectsAndLogsBothIdentitiesBeforeChangingStorage() {
        int tx = ccm.beginTransaction();
        int probe = ccm.beginTransaction();
        RowId original = baselineRowIds.get(1);
        recovery.beforeRelocationLog = (oldRid, newRid) -> {
            assertEquals(original, oldRid);
            assertNotEquals(oldRid, newRid);
            assertEquals(baselineRows, heapRows(), "no physical change before relocation WAL");
            assertEquals(AccessStatus.WAITING, ccm.requestWrite(probe, new DataItem(TABLE, oldRid)));
            assertEquals(AccessStatus.WAITING, ccm.requestWrite(probe, new DataItem(TABLE, newRid)));
        };

        assertOk(run(tx, "UPDATE users SET name = '" + name(1, 200) + "' WHERE id = 1;"));

        var changes = wal.readLogs(tx).stream()
                .filter(record -> record instanceof mdbms.models.log.DataChangeLogRecord)
                .map(record -> (mdbms.models.log.DataChangeLogRecord) record).toList();
        assertEquals(1, changes.size(), "one relocation record, without extra UPDATE/DELETE/INSERT logs");
        assertTrue(changes.get(0).isRelocation());
        assertEquals(original, changes.get(0).getRowId());
        assertEquals(rowIdsById().get(1), changes.get(0).getNewRowId());
        assertTrue(wal.getLogManager().getWalFileHandler().getFlushedLsn() >= changes.get(0).getLsn());
        assertIndexMatchesHeap();
        assertOk(run(tx, "COMMIT;"));
        ccm.abortTransaction(probe, "probe complete");
    }

    @Test
    void failedRelocationLoggingReleasesReservationWithoutChangingTheRow() {
        int tx = ccm.beginTransaction();
        recovery.failRelocationLog = true;

        assertFalse(run(tx, "UPDATE users SET name = '" + name(1, 200) + "' WHERE id = 1;").success());

        assertEquals(baselineRows, heapRows());
        assertEquals(baselineRowIds, rowIdsById());
        assertIndexMatchesHeap();
        assertEquals(0, storage.releaseOrphanReservations(), "destination reservation must already be released");
        assertNull(ccm.getTransaction(tx));
        assertFalse(ccm.isFailed());
    }

    @Test
    void failureBetweenRelocationInsertAndDeleteUndoesTheDestination() {
        RowId original = baselineRowIds.get(1);
        mdbms.api.IDataAccess failingStorage = (mdbms.api.IDataAccess) java.lang.reflect.Proxy.newProxyInstance(
                mdbms.api.IDataAccess.class.getClassLoader(), new Class<?>[] {mdbms.api.IDataAccess.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("deleteByRowId") && original.equals(args[0])) {
                        throw new IllegalStateException("injected failure after destination insertion");
                    }
                    try {
                        return method.invoke(storage, args);
                    } catch (java.lang.reflect.InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
        QueryOptimizer optimizer = new QueryOptimizer(storage, new ShaylaParser()) {
            @Override public ParsedQuery optimizeQuery(ParsedQuery query) { return query; }
        };
        processor = new QueryProcessor(optimizer, failingStorage, storage, recovery, ccm);
        int tx = ccm.beginTransaction();

        assertFalse(run(tx, "UPDATE users SET name = '" + name(1, 200) + "' WHERE id = 1;").success());

        assertEquals(baselineRows, heapRows());
        assertEquals(baselineRowIds, rowIdsById());
        assertIndexMatchesHeap();
        assertEquals(0, storage.releaseOrphanReservations());
        assertNull(ccm.getTransaction(tx));
        assertFalse(ccm.isFailed());
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
            // The embedded caller owns lifecycle actions, just as NET does for socket clients.
            if (sql.equals("COMMIT;")) {
                ccm.commitTransaction(tx);
                return new ExecutionResult(true, List.of(), List.of(), 0, "COMMIT transaction " + tx);
            }
            if (sql.equals("ABORT;")) {
                ccm.abortTransaction(tx, "ABORT requested by embedded caller");
                return new ExecutionResult(true, List.of(), List.of(), 0, "ABORT transaction " + tx);
            }
            ExecutionResult result = processor.executeQuery(sql, tx);
            Transaction transaction = ccm.getTransaction(tx);
            if (!result.success() && transaction != null && transaction.getState() == TransactionState.ACTIVE) {
                ccm.abortTransaction(tx, "Query execution failed");
            }
            return result;
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

    private record Release(long flushedLsn, List<List<Object>> heapRows) {}

    // nyatet kondisi WAL sama heap persis pas CCM ngelepas lock suatu transaksi
    private final class ReleaseRecordingProtocol extends LockBasedProtocol {
        private final Map<Integer, Release> releases = new LinkedHashMap<>();

        @Override
        public void releaseResources(Transaction tx) {
            releases.put(tx.getId(), new Release(wal.getLogManager().getWalFileHandler().getFlushedLsn(), heapRows()));
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
        private volatile boolean failRelocationLog;
        private volatile java.util.function.BiConsumer<RowId, RowId> beforeRelocationLog = (oldRid, newRid) -> {};
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
            return delegate.writeDataLog(txId, opType, table, rid, beforeImg, afterImg);
        }

        @Override
        public RecoveryResult writeRelocationLog(int txId, String table, RowId oldRid, RowId newRid,
                RecordImage beforeImg, RecordImage afterImg) {
            beforeRelocationLog.accept(oldRid, newRid);
            if (failRelocationLog) return new RecoveryResult(false, -1L, txId, "injected relocation log failure");
            return delegate.writeRelocationLog(txId, table, oldRid, newRid, beforeImg, afterImg);
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
