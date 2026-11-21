package mdbms.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mdbms.api.IDataAccess;
import mdbms.api.IDurableStorage;
import mdbms.api.ISchemaCatalog;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.access.DataRetrieval;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.data.Rows;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FailureRecoveryFacadeIT {

    private static final String TABLE = "mahasiswa";
    private static final String INDEX_NIM = "idx_mahasiswa_nim";
    private static final int BUFFER_CAPACITY = 16;
    private static final int INDEX_MAX_DEGREE = 4;

    private static ClassLoader siblingClassLoader;

    @TempDir
    Path tempDir;

    // BEGIN/data/COMMIT menghasilkan LSN valid & kegagalan WAL force terpropagasi
    @Test
    void transactionAndDataLogsProduceMonotonicLsnsAndPropagateForceFailure() {
        Path dir = tempDir.resolve("c1_lsn_force");
        Object sm = createInitializedStorage(dir);
        FailureRecoveryFacade frm = new FailureRecoveryFacade(
            dir, dir.resolve("snapshot"), (IDataAccess) sm, (IDurableStorage) sm
        );
        attachRecoveryManagerToStorage(sm, frm);
        try {
            RecoveryResult begin = frm.writeTransactionLog(1, LogType.BEGIN);
            RowId rid = ((IDataAccess) sm).reserveRowId(TABLE, List.of(101, "Andi", 90));
            RecoveryResult ins = frm.writeDataLog(
                1, LogType.INSERT, TABLE, rid, null, new RecordImage(List.of(101, "Andi", 90))
            );
            ((IDataAccess) sm).insertAt(rid, List.of(101, "Andi", 90));
            RecoveryResult commit = frm.writeTransactionLog(1, LogType.COMMIT);

            assertTrue(begin.isSuccess() && ins.isSuccess() && commit.isSuccess());
            assertTrue(begin.getLsn() > 0 && ins.getLsn() > begin.getLsn() && commit.getLsn() > ins.getLsn());
            assertTrue(frm.flushLogUpTo(commit.getLsn()));
        } finally {
            frm.close();
        }

        // Uji propagasi gagal WAL force
        WalFileHandler failingWal = new WalFileHandler(dir.resolve("failing_wal.log")) {
            @Override
            public boolean flushUpTo(long lsn) {
                return false;
            }
        };
        LogManager failingLm = new LogManager(failingWal);
        FailureRecoveryFacade failingFrm = new FailureRecoveryFacade(
            failingLm,
            new RollbackManager(failingLm, (IDataAccess) sm, (IDurableStorage) sm),
            new CheckpointManager(failingLm, (IDurableStorage) sm, new RecordApplier((IDataAccess) sm),
                dir.toString(), dir.resolve("snapshot").toString()),
            new CrashRecoveryManager(failingLm, null, null, (IDataAccess) sm, (IDurableStorage) sm),
            (IDataAccess) sm,
            (IDurableStorage) sm
        );
        attachRecoveryManagerToStorage(sm, failingFrm);
        try {
            assertTrue(failingFrm.writeTransactionLog(2, LogType.BEGIN).isSuccess());
            RecoveryResult failedCommit = failingFrm.writeTransactionLog(2, LogType.COMMIT);
            assertFalse(failedCommit.isSuccess());
            assertFalse(failingFrm.flushLogUpTo(1L));
        } finally {
            failingWal.close();
        }
    }

    // Undo/Redo INSERT, UPDATE, DELETE pada storage asli aman diulang (idempotent)
    @Test
    void repeatedRedoAndUndoOnRealStorageAreIdempotent() {
        Path dir = tempDir.resolve("c2_idempotent");
        Object sm = createInitializedStorage(dir);
        IDataAccess dataAccess = (IDataAccess) sm;
        RecordApplier applier = new RecordApplier(dataAccess);

        RowId r1 = dataAccess.reserveRowId(TABLE, List.of(101, "Andi", 100));
        dataAccess.releaseRowId(r1);

        DataChangeLogRecord insertRec = new DataChangeLogRecord(
            1L, 1, LogType.INSERT, Instant.now(), TABLE, r1, null, new RecordImage(List.of(101, "Andi", 100))
        );
        DataChangeLogRecord updateRec = new DataChangeLogRecord(
            2L, 1, LogType.UPDATE, Instant.now(), TABLE, r1,
            new RecordImage(List.of(101, "Andi", 100)), new RecordImage(List.of(101, "Andi", 150))
        );
        DataChangeLogRecord deleteRec = new DataChangeLogRecord(
            3L, 1, LogType.DELETE, Instant.now(), TABLE, r1, new RecordImage(List.of(101, "Andi", 150)), null
        );

        // Repeated Redo INSERT -> UPDATE -> DELETE
        assertTrue(applier.redo(insertRec));
        assertTrue(applier.redo(insertRec));
        assertEquals(List.of(101, "Andi", 100), readRowsByNim(dataAccess).get(101));

        assertTrue(applier.redo(updateRec));
        assertTrue(applier.redo(updateRec));
        assertEquals(List.of(101, "Andi", 150), readRowsByNim(dataAccess).get(101));

        assertTrue(applier.redo(deleteRec));
        assertTrue(applier.redo(deleteRec));
        assertNull(readRowsByNim(dataAccess).get(101));

        // Repeated Undo DELETE -> UPDATE -> INSERT
        assertTrue(applier.undo(deleteRec));
        assertTrue(applier.undo(deleteRec));
        assertEquals(List.of(101, "Andi", 150), readRowsByNim(dataAccess).get(101));

        assertTrue(applier.undo(updateRec));
        assertTrue(applier.undo(updateRec));
        assertEquals(List.of(101, "Andi", 100), readRowsByNim(dataAccess).get(101));

        assertTrue(applier.undo(insertRec));
        assertTrue(applier.undo(insertRec));
        assertNull(readRowsByNim(dataAccess).get(101));
    }

    // Replay tidak menambah log data duplikat & skema/data/indeks konsisten pasca-recovery
    @Test
    void replayDoesNotAppendDuplicateDataLogsAndRestoresConsistentCatalogState() {
        Path dir = tempDir.resolve("c3_c4_replay_consistency");
        Object sm1 = createInitializedStorage(dir);
        IDataAccess da1 = (IDataAccess) sm1;
        IDurableStorage ds1 = (IDurableStorage) sm1;
        FailureRecoveryFacade frm1 = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da1, ds1);
        attachRecoveryManagerToStorage(sm1, frm1);
        long dataLogCountBefore;
        try {
            // Tx 1 committed tanpa flush buffer
            frm1.writeTransactionLog(1, LogType.BEGIN);
            RowId r1 = da1.reserveRowId(TABLE, List.of(201, "Budi", 80));
            frm1.writeDataLog(1, LogType.INSERT, TABLE, r1, null, new RecordImage(List.of(201, "Budi", 80)));
            da1.insertAt(r1, List.of(201, "Budi", 80));
            frm1.writeTransactionLog(1, LogType.COMMIT);

            // Tx 2 uncommitted tapi ter-flush ke disk
            frm1.writeTransactionLog(2, LogType.BEGIN);
            RowId r2 = da1.reserveRowId(TABLE, List.of(202, "Citra", 50));
            RecoveryResult u2 = frm1.writeDataLog(
                2, LogType.INSERT, TABLE, r2, null, new RecordImage(List.of(202, "Citra", 50))
            );
            da1.insertAt(r2, List.of(202, "Citra", 50));
            frm1.flushLogUpTo(u2.getLsn());
            ds1.flushBuffer();

            dataLogCountBefore = frm1.readLogs(null).stream()
                .filter(r -> r instanceof DataChangeLogRecord)
                .count();
        } finally {
            frm1.close();
        }

        // Reopen instance baru dan jalankan recovery
        Object sm2 = createRealStorageManager(dir);
        IDataAccess da2 = (IDataAccess) sm2;
        IDurableStorage ds2 = (IDurableStorage) sm2;
        ISchemaCatalog catalog2 = (ISchemaCatalog) sm2;
        FailureRecoveryFacade frm2 = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da2, ds2);
        attachRecoveryManagerToStorage(sm2, frm2);
        try {
            RecoveryResult rec = frm2.recoverOnStartup();
            assertTrue(rec.isSuccess());

            // Tidak boleh ada DataChangeLogRecord baru akibat replay
            long dataLogCountAfter = frm2.readLogs(null).stream()
                .filter(r -> r instanceof DataChangeLogRecord)
                .count();
            assertEquals(dataLogCountBefore, dataLogCountAfter);

            assertNotNull(catalog2.getTableSchema(TABLE));
            Map<Integer, List<Object>> rows = readRowsByNim(da2);
            assertEquals(List.of(201, "Budi", 80), rows.get(201));
            assertNull(rows.get(202));

            // Verifikasi metadata indeks & isi B+Tree pulih konsisten pasca-recovery
            assertTrue(
                catalog2.getIndexes(TABLE).stream().anyMatch(idx -> INDEX_NIM.equals(idx.indexName())),
                "Indeks idx_mahasiswa_nim harus tetap terdaftar di katalog"
            );

            List<RowId> committedIdx = da2.searchIndex(INDEX_NIM, 201);
            assertFalse(committedIdx.isEmpty(), "Baris committed (201) harus ditemukan lewat indeks");
            assertEquals(List.of(201, "Budi", 80), da2.fetchByRowId(committedIdx.get(0)).values());

            List<RowId> uncommittedIdx = da2.searchIndex(INDEX_NIM, 202);
            assertTrue(uncommittedIdx.isEmpty(), "Baris uncommitted (202) tidak boleh tersisa di indeks");
        } finally {
            frm2.close();
        }
    }

    // Layanan storage null & kegagalan I/O asli tidak boleh mengembalikan sukses
    @Test
    void missingStorageServicesAndGenuineIoFailuresReturnFailure() {
        Path dir = tempDir.resolve("c5_failures");
        FailureRecoveryFacade noStorageFrm = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), null, null);
        try {
            noStorageFrm.writeTransactionLog(1, LogType.BEGIN);
            noStorageFrm.writeDataLog(
                1, LogType.INSERT, TABLE, new RowId(TABLE, 0, 0), null, new RecordImage(List.of(1, "X", 10))
            );
            assertFalse(noStorageFrm.rollbackTransaction(1).isSuccess());
            assertFalse(noStorageFrm.createCheckpoint().isSuccess());
            assertFalse(noStorageFrm.recoverOnStartup().isSuccess());
        } finally {
            noStorageFrm.close();
        }

        // Simulasi kegagalan I/O asli pada flushBuffer
        Object sm = createInitializedStorage(dir.resolve("io_fail"));
        IDurableStorage failingDurable = new IDurableStorage() {
            @Override
            public void flushBuffer() {
                throw new UncheckedIOException(new IOException("Simulated disk I/O error"));
            }

            @Override
            public void invalidateAndReload(java.util.Set<String> affectedFiles) {
                ((IDurableStorage) sm).invalidateAndReload(affectedFiles);
            }

            @Override
            public void rebuildIndexesAndReloadCatalog() {
                ((IDurableStorage) sm).rebuildIndexesAndReloadCatalog();
            }

            @Override
            public mdbms.models.access.BufferStats getBufferStats() {
                return ((IDurableStorage) sm).getBufferStats();
            }
        };
        FailureRecoveryFacade ioFailFrm = new FailureRecoveryFacade(
            dir.resolve("io_fail"), dir.resolve("io_fail_snap"), (IDataAccess) sm, failingDurable
        );
        attachRecoveryManagerToStorage(sm, ioFailFrm);
        try {
            ioFailFrm.writeTransactionLog(1, LogType.BEGIN);
            RowId rid = ((IDataAccess) sm).reserveRowId(TABLE, List.of(301, "Dina", 70));
            ioFailFrm.writeDataLog(1, LogType.INSERT, TABLE, rid, null, new RecordImage(List.of(301, "Dina", 70)));
            ((IDataAccess) sm).insertAt(rid, List.of(301, "Dina", 70));

            assertFalse(ioFailFrm.rollbackTransaction(1).isSuccess());
            assertFalse(ioFailFrm.createCheckpoint().isSuccess());
            assertFalse(ioFailFrm.recoverOnStartup().isSuccess());
        } finally {
            ioFailFrm.close();
        }
    }

    // Cakupan integrasi nyata untuk recoverToPointInTime & recoverToTransaction
    @Test
    void pointInTimeAndTransactionTargetRecoveryWorkOnRealStorage() throws InterruptedException {
        Path dir = tempDir.resolve("c6_pitr_and_tx_target");
        Object sm = createInitializedStorage(dir);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da, ds);
        attachRecoveryManagerToStorage(sm, frm);

        try {
            // Base snapshot
            frm.writeTransactionLog(1, LogType.BEGIN);
            RowId r0 = da.reserveRowId(TABLE, List.of(100, "Base", 10));
            frm.writeDataLog(1, LogType.INSERT, TABLE, r0, null, new RecordImage(List.of(100, "Base", 10)));
            da.insertAt(r0, List.of(100, "Base", 10));
            frm.writeTransactionLog(1, LogType.COMMIT);
            frm.getCheckpointManager().createSnapshot();

            // Tx 2 commit sebelum targetTime
            frm.writeTransactionLog(2, LogType.BEGIN);
            RowId r1 = da.reserveRowId(TABLE, List.of(101, "Tx2", 20));
            frm.writeDataLog(2, LogType.INSERT, TABLE, r1, null, new RecordImage(List.of(101, "Tx2", 20)));
            da.insertAt(r1, List.of(101, "Tx2", 20));
            frm.writeTransactionLog(2, LogType.COMMIT);

            Thread.sleep(20);
            Instant targetTime = Instant.now();
            Thread.sleep(20);

            // Tx 3 commit setelah targetTime
            frm.writeTransactionLog(3, LogType.BEGIN);
            RowId r2 = da.reserveRowId(TABLE, List.of(102, "Tx3", 30));
            frm.writeDataLog(3, LogType.INSERT, TABLE, r2, null, new RecordImage(List.of(102, "Tx3", 30)));
            da.insertAt(r2, List.of(102, "Tx3", 30));
            frm.writeTransactionLog(3, LogType.COMMIT);
            ds.flushBuffer();

            // Uji recoverToTransaction(2)
            frm.setOfflineMaintenanceMode(true);
            RecoveryResult txTargetRes = frm.recoverToTransaction(2);
            assertTrue(txTargetRes.isSuccess());
            assertTrue(Files.exists(dir.resolve(TABLE + ".tbl")),
                    "File tabel fisik tidak boleh terhapus setelah restoreSnapshot");
            Map<Integer, List<Object>> afterTxTarget = readRowsByNim(da);
            assertEquals(List.of(100, "Base", 10), afterTxTarget.get(100));
            assertEquals(List.of(101, "Tx2", 20), afterTxTarget.get(101));
            assertNull(afterTxTarget.get(102));

            // Tambah Tx 4 setelah targetTime lalu uji recoverToPointInTime(targetTime)
            frm.writeTransactionLog(4, LogType.BEGIN);
            RowId r3 = da.reserveRowId(TABLE, List.of(103, "Tx4", 40));
            frm.writeDataLog(4, LogType.INSERT, TABLE, r3, null, new RecordImage(List.of(103, "Tx4", 40)));
            da.insertAt(r3, List.of(103, "Tx4", 40));
            frm.writeTransactionLog(4, LogType.COMMIT);
            ds.flushBuffer();

            RecoveryResult pitrRes = frm.recoverToPointInTime(targetTime);
            assertTrue(pitrRes.isSuccess());
            assertTrue(Files.exists(dir.resolve(TABLE + ".tbl")),
                    "File tabel fisik tidak boleh terhapus setelah PITR");
            Map<Integer, List<Object>> afterPitr = readRowsByNim(da);
            assertEquals(List.of(100, "Base", 10), afterPitr.get(100));
            assertEquals(List.of(101, "Tx2", 20), afterPitr.get(101));
            assertNull(afterPitr.get(103));
        } finally {
            frm.close();
        }
        // Buka ulang StorageManager baru untuk membuktikan file fisik utuh di disk
        Object smReopened = createRealStorageManager(dir);
        Map<Integer, List<Object>> rowsOnDisk = readRowsByNim((IDataAccess) smReopened);
        assertEquals(List.of(100, "Base", 10), rowsOnDisk.get(100));
        assertEquals(List.of(101, "Tx2", 20), rowsOnDisk.get(101));
        assertNull(rowsOnDisk.get(103));
    }

    private static Object createInitializedStorage(Path dir) {
        Object sm = createRealStorageManager(dir);
        ISchemaCatalog catalog = (ISchemaCatalog) sm;
        if (catalog.getTableSchema(TABLE) == null) {
            TableSchema schema = new TableSchema(
                TABLE,
                List.of(
                    new ColumnDefinition("nim", DataType.INTEGER, 0, false),
                    new ColumnDefinition("nama", DataType.VARCHAR, 64, false),
                    new ColumnDefinition("nilai", DataType.INTEGER, 0, false)
                ),
                List.of("nim"),
                List.of()
            );
            catalog.updateTableSchema(schema);
        }
        if (catalog.getIndexes(TABLE).stream().noneMatch(idx -> INDEX_NIM.equals(idx.indexName()))) {
            catalog.createIndex(INDEX_NIM, TABLE, "nim");
        }
        return sm;
    }

    private static Map<Integer, List<Object>> readRowsByNim(IDataAccess sm) {
        Map<Integer, List<Object>> map = new LinkedHashMap<>();
        Rows rows = sm.readBlock(new DataRetrieval(TABLE, List.of(), List.of()));
        if (rows != null && rows.rows() != null) {
            for (Row row : rows.rows()) {
                if (row != null && row.values() != null && !row.values().isEmpty()) {
                    map.put((Integer) row.values().get(0), row.values());
                }
            }
        }
        return map;
    }

    private static synchronized ClassLoader getSiblingClassLoader() {
        if (siblingClassLoader != null) {
            return siblingClassLoader;
        }
        try {
            List<URL> urls = new ArrayList<>();
            Path cwd = Path.of("").toAbsolutePath().normalize();
            for (String candidate : new String[] {
                "mdbms-storage/target/classes", "../mdbms-storage/target/classes"
            }) {
                Path p = cwd.resolve(candidate).normalize();
                if (Files.exists(p)) {
                    urls.add(p.toUri().toURL());
                }
            }
            siblingClassLoader = new URLClassLoader(urls.toArray(new URL[0]), FailureRecoveryFacadeIT.class.getClassLoader());
            return siblingClassLoader;
        } catch (Exception e) {
            throw new RuntimeException("Gagal menyiapkan ClassLoader mdbms-storage", e);
        }
    }

    private static Object createRealStorageManager(Path dataDir) {
        try {
            Class<?> smClass = Class.forName("mdbms.storage.StorageManager", true, getSiblingClassLoader());
            Constructor<?> ctor = smClass.getConstructor(String.class, int.class, int.class);
            return ctor.newInstance(dataDir.toString(), BUFFER_CAPACITY, INDEX_MAX_DEGREE);
        } catch (Exception e) {
            throw new RuntimeException("Gagal memuat StorageManager. Jalankan 'mvn test-compile' dulu.", e);
        }
    }

    private static void attachRecoveryManagerToStorage(Object sm, IFailureRecoveryManager frm) {
        try {
            Method m = sm.getClass().getMethod("attachRecoveryManager", IFailureRecoveryManager.class);
            m.invoke(sm, frm);
        } catch (Exception e) {
            throw new RuntimeException("Gagal memanggil StorageManager.attachRecoveryManager()", e);
        }
    }
}