package mdbms.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.api.IDurableStorage;
import mdbms.api.IFailureRecoveryManager;
import mdbms.api.ISchemaCatalog;
import mdbms.models.access.DataRetrieval;
import mdbms.models.data.DataItem;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.data.Rows;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.models.transaction.AccessStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test untuk Issue #55: proteksi, logging, commit, rollback, failure cleanup,
 * konsistensi indeks, serta idempotent redo/undo pada variable-width UPDATE yang berpindah halaman.
 */
class RowRelocationIT {

    private static final String TABLE = "dokumen";
    private static final String INDEX_ID = "idx_dokumen_id";
    private static final int BUFFER_CAPACITY = 16;
    private static final int INDEX_MAX_DEGREE = 4;

    private static ClassLoader siblingClassLoader;

    @TempDir
    Path tempDir;

    // Kriteria 1, 2, & 3 (Commit + Indeks): Relokasi dilindungi & dicatat sebelum mutasi,
    // transaksi lain terblokir pada oldRid & newRid, commit menyisakan 1 baris logis + indeks tepat.
    @Test
    void relocatedUpdateProtectsBothLocationsAndCommitsSingleLogicalRowWithValidIndex() {
        Path dir = tempDir.resolve("reloc_commit");
        Object sm = createInitializedStorage(dir);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da, ds);
        attachRecoveryManagerToStorage(sm, frm);
        IConcurrencyControl ccm = createRealConcurrencyControl(frm);
        invokeCcmInitAfterRecovery(ccm);

        try {
            RowId targetOldRid = seedPageZeroAndReturnTarget(frm, da, ccm);
            List<Object> beforeVal = List.of(1, "v1");
            List<Object> afterVal = List.of(1, "X".repeat(500));

            int tx1 = ccm.beginTransaction();

            // 1. Kunci lokasi lama (oldRid)
            assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx1, new DataItem(TABLE, targetOldRid), null, null));

            // 2. Reservasi lokasi tujuan di halaman berbeda
            RowId newRid = da.reserveRowId(TABLE, afterVal);
            assertNotEquals(targetOldRid.blockNumber(), newRid.blockNumber(),
                    "Payload besar harus memaksa alokasi ke halaman berbeda");

            // 3. Lindungi lokasi tujuan (newRid) sebelum mutasi fisik
            assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx1, new DataItem(TABLE, newRid), null, null));

            // 4. Catat log relokasi ke WAL sebelum perubahan fisik pada kedua lokasi
            RecoveryResult logRes = frm.writeRelocationLog(
                    tx1, TABLE, targetOldRid, newRid, new RecordImage(beforeVal), new RecordImage(afterVal));
            assertTrue(logRes.isSuccess());

            // Kriteria 2: Transaksi lain tidak bisa membaca/menulis lokasi lama maupun lokasi tujuan
            int tx2 = ccm.beginTransaction();
            assertTrue(isAccessBlocked(ccm, tx2, targetOldRid, false), "oldRid harus terkunci untuk transaksi lain");
            assertTrue(isAccessBlocked(ccm, tx2, newRid, true), "newRid harus terkunci untuk transaksi lain");

            // 5. Terapkan perubahan fisik pada kedua lokasi
            da.insertAt(newRid, afterVal);
            da.deleteByRowId(targetOldRid);

            // 6. Commit dan flush ke disk
            ccm.commitTransaction(tx1);
            ds.flushBuffer();

            // Kriteria 3: 1 baris logis di heap + entri indeks menunjuk tepat ke newRid
            List<Row> id1Rows = findRowsById(da, 1);
            assertEquals(1, id1Rows.size());
            assertEquals(afterVal, id1Rows.get(0).values());

            List<RowId> idxResult = da.searchIndex(INDEX_ID, 1);
            assertEquals(List.of(newRid), idxResult, "Indeks harus menunjuk tepat ke lokasi baru (newRid)");
            assertEquals(afterVal, da.fetchByRowId(idxResult.get(0)).values());
        } finally {
            frm.close();
        }
    }

    // Kriteria 3 (Rollback + Indeks) & Kriteria 4 (Gagal setelah reservasi, setelah logging, atau di tengah mutasi)
    @Test
    void failureAfterReservationOrBetweenLocationsRollsBackCleanlyWithoutDuplicatesOrOrphans() {
        Path dir = tempDir.resolve("reloc_failure_rollback");
        Object sm = createInitializedStorage(dir);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da, ds);
        attachRecoveryManagerToStorage(sm, frm);
        IConcurrencyControl ccm = createRealConcurrencyControl(frm);
        invokeCcmInitAfterRecovery(ccm);

        try {
            RowId oldRid = seedPageZeroAndReturnTarget(frm, da, ccm);
            List<Object> beforeVal = List.of(1, "v1");
            List<Object> afterVal = List.of(1, "Y".repeat(500));

            // Kasus 4a: Gagal setelah reservasi (sebelum logging) -> releaseRowId tidak mengekspos baris
            RowId orphanCandidate = da.reserveRowId(TABLE, afterVal);
            da.releaseRowId(orphanCandidate);
            assertEquals(1, findRowsById(da, 1).size());
            assertEquals(List.of(oldRid), da.searchIndex(INDEX_ID, 1));

            // Kasus 4b: Gagal di tengah perubahan lokasi (newRid sudah ditulis, oldRid belum dihapus -> duplikat sementara)
            int tx = ccm.beginTransaction();
            assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx, new DataItem(TABLE, oldRid), null, null));
            RowId newRid = da.reserveRowId(TABLE, afterVal);
            assertEquals(AccessStatus.GRANTED, ccm.requestWrite(tx, new DataItem(TABLE, newRid), null, null));
            assertTrue(frm.writeRelocationLog(
                    tx, TABLE, oldRid, newRid, new RecordImage(beforeVal), new RecordImage(afterVal)).isSuccess());

            // Simulasi gagal setelah insertAt(newRid) sebelum deleteByRowId(oldRid)
            da.insertAt(newRid, afterVal);

            // Abort harus membersihkan newRid dan memulihkan oldRid + indeksnya
            ccm.abortTransaction(tx, "Simulasi kegagalan di antara mutasi dua lokasi");

            List<Row> rowsAfterAbort = findRowsById(da, 1);
            assertEquals(1, rowsAfterAbort.size(), "Tidak boleh ada baris duplikat setelah rollback");
            assertEquals(beforeVal, rowsAfterAbort.get(0).values());
            assertEquals(beforeVal, da.fetchByRowId(oldRid).values());
            assertEquals(List.of(oldRid), da.searchIndex(INDEX_ID, 1),
                    "Indeks harus kembali menunjuk tepat ke oldRid setelah rollback");
        } finally {
            frm.close();
        }
    }

    // Kriteria 5: Repeated Redo & Undo pada real storage menangani oldRid dan newRid secara aman
    @Test
    void repeatedRedoAndUndoOnRealStorageHandleRelocationSafely() {
        Path dir = tempDir.resolve("reloc_repeated_redo_undo");
        Object sm = createInitializedStorage(dir);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da, ds);
        attachRecoveryManagerToStorage(sm, frm);
        IConcurrencyControl ccm = createRealConcurrencyControl(frm);
        invokeCcmInitAfterRecovery(ccm);

        try {
            RowId oldRid = seedPageZeroAndReturnTarget(frm, da, ccm);
            List<Object> beforeVal = List.of(1, "v1");
            List<Object> afterVal = List.of(1, "Z".repeat(500));

            RowId newRid = da.reserveRowId(TABLE, afterVal);
            da.releaseRowId(newRid);

            int tx = ccm.beginTransaction();
            RecoveryResult res = frm.writeRelocationLog(
                    tx, TABLE, oldRid, newRid, new RecordImage(beforeVal), new RecordImage(afterVal));
            assertTrue(res.isSuccess());

            DataChangeLogRecord relocRecord = frm.readLogs(tx).stream()
                    .filter(r -> r instanceof DataChangeLogRecord)
                    .map(r -> (DataChangeLogRecord) r)
                    .filter(DataChangeLogRecord::isRelocation)
                    .findFirst()
                    .orElse(null);
            assertNotNull(relocRecord);

            RecordApplier applier = new RecordApplier(da);

            // Repeated Redo (2x)
            assertTrue(applier.redo(relocRecord));
            assertTrue(applier.redo(relocRecord));
            List<Row> afterRedo = findRowsById(da, 1);
            assertEquals(1, afterRedo.size());
            assertEquals(afterVal, afterRedo.get(0).values());
            assertEquals(List.of(newRid), da.searchIndex(INDEX_ID, 1));

            // Repeated Undo (2x)
            assertTrue(applier.undo(relocRecord));
            assertTrue(applier.undo(relocRecord));
            List<Row> afterUndo = findRowsById(da, 1);
            assertEquals(1, afterUndo.size());
            assertEquals(beforeVal, afterUndo.get(0).values());
            assertEquals(List.of(oldRid), da.searchIndex(INDEX_ID, 1));
        } finally {
            frm.close();
        }
    }

    private static RowId seedPageZeroAndReturnTarget(
            FailureRecoveryFacade frm, IDataAccess da, IConcurrencyControl ccm) {
        int seedTx = ccm.beginTransaction();
        RowId targetRid = insertSeedRow(frm, da, ccm, seedTx, List.of(1, "v1"));

        // Isi halaman 0 hingga hampir penuh agar payload besar berikutnya berpindah ke halaman baru
        String filler = "P".repeat(450);
        for (int id = 2; id <= 30; id++) {
            List<Object> vals = List.of(id, filler);
            RowId rid = da.reserveRowId(TABLE, vals);
            if (rid.blockNumber() > targetRid.blockNumber()) {
                da.releaseRowId(rid);
                break;
            }
            long lsnBefore = frm.getLogManager().getCurrentLsn();
            ccm.requestWrite(seedTx, new DataItem(TABLE, rid), null, vals);
            if (frm.getLogManager().getCurrentLsn() == lsnBefore) {
                frm.writeDataLog(seedTx, LogType.INSERT, TABLE, rid, null, new RecordImage(vals));
            }
            da.insertAt(rid, vals);
        }
        ccm.commitTransaction(seedTx);
        return targetRid;
    }

    private static RowId insertSeedRow(
            FailureRecoveryFacade frm, IDataAccess da, IConcurrencyControl ccm, int txId, List<Object> vals) {
        RowId rid = da.reserveRowId(TABLE, vals);
        long lsnBefore = frm.getLogManager().getCurrentLsn();
        ccm.requestWrite(txId, new DataItem(TABLE, rid), null, vals);
        if (frm.getLogManager().getCurrentLsn() == lsnBefore) {
            frm.writeDataLog(txId, LogType.INSERT, TABLE, rid, null, new RecordImage(vals));
        }
        da.insertAt(rid, vals);
        return rid;
    }

    private static List<Row> findRowsById(IDataAccess da, int id) {
        List<Row> matched = new ArrayList<>();
        Rows rows = da.readBlock(new DataRetrieval(TABLE, List.of(), List.of()));
        if (rows != null && rows.rows() != null) {
            for (Row r : rows.rows()) {
                if (r != null && r.values() != null && !r.values().isEmpty()
                        && Integer.valueOf(id).equals(r.values().get(0))) {
                    matched.add(r);
                }
            }
        }
        return matched;
    }

    private static boolean isAccessBlocked(IConcurrencyControl ccm, int txId, RowId rid, boolean write) {
        CompletableFuture<AccessStatus> future = CompletableFuture.supplyAsync(() ->
                write
                        ? ccm.requestWrite(txId, new DataItem(TABLE, rid), null, List.of(999, "conflict"))
                        : ccm.requestRead(txId, new DataItem(TABLE, rid)));
        try {
            AccessStatus status = future.get(150, TimeUnit.MILLISECONDS);
            return status != AccessStatus.GRANTED;
        } catch (TimeoutException e) {
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private static Object createInitializedStorage(Path dir) {
        Object sm = createRealStorageManager(dir);
        ISchemaCatalog catalog = (ISchemaCatalog) sm;
        if (catalog.getTableSchema(TABLE) == null) {
            TableSchema schema = new TableSchema(
                    TABLE,
                    List.of(
                            new ColumnDefinition("id", DataType.INTEGER, 0, false),
                            new ColumnDefinition("konten", DataType.VARCHAR, 600, false)
                    ),
                    List.of("id"),
                    List.of()
            );
            catalog.updateTableSchema(schema);
        }
        if (catalog.getIndexes(TABLE).stream().noneMatch(idx -> INDEX_ID.equals(idx.indexName()))) {
            catalog.createIndex(INDEX_ID, TABLE, "id");
        }
        return sm;
    }

    private static synchronized ClassLoader getSiblingClassLoader() {
        if (siblingClassLoader != null) {
            return siblingClassLoader;
        }
        try {
            List<URL> urls = new ArrayList<>();
            Path cwd = Path.of("").toAbsolutePath().normalize();
            for (String candidate : new String[] {
                "mdbms-storage/target/classes", "../mdbms-storage/target/classes",
                "mdbms-concurrency/target/classes", "../mdbms-concurrency/target/classes"
            }) {
                Path p = cwd.resolve(candidate).normalize();
                if (Files.exists(p)) {
                    urls.add(p.toUri().toURL());
                }
            }
            siblingClassLoader = new URLClassLoader(urls.toArray(new URL[0]), RowRelocationIT.class.getClassLoader());
            return siblingClassLoader;
        } catch (Exception e) {
            throw new RuntimeException("Gagal menyiapkan ClassLoader modul sibling", e);
        }
    }

    private static Object createRealStorageManager(Path dataDir) {
        try {
            Class<?> smClass = Class.forName("mdbms.storage.StorageManager", true, getSiblingClassLoader());
            Constructor<?> ctor = smClass.getConstructor(String.class, int.class, int.class);
            return ctor.newInstance(dataDir.toString(), BUFFER_CAPACITY, INDEX_MAX_DEGREE);
        } catch (Exception e) {
            throw new RuntimeException("Gagal memuat StorageManager", e);
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

    private static IConcurrencyControl createRealConcurrencyControl(IFailureRecoveryManager frm) {
        try {
            ClassLoader cl = getSiblingClassLoader();
            Class<?> protocolInterface = Class.forName("mdbms.concurrency.ConcurrencyProtocol", true, cl);
            Class<?> lockProtocolClass = Class.forName("mdbms.concurrency.LockBasedProtocol", true, cl);
            Object lockProtocol = lockProtocolClass.getDeclaredConstructor().newInstance();
            Class<?> ccmClass = Class.forName("mdbms.concurrency.ConcurrencyControlManager", true, cl);
            Constructor<?> ctor = ccmClass.getConstructor(protocolInterface, IFailureRecoveryManager.class);
            return (IConcurrencyControl) ctor.newInstance(lockProtocol, frm);
        } catch (Exception e) {
            throw new RuntimeException("Gagal memuat ConcurrencyControlManager", e);
        }
    }

    private static void invokeCcmInitAfterRecovery(IConcurrencyControl ccm) {
        try {
            ccm.getClass().getMethod("initAfterRecovery").invoke(ccm);
        } catch (Exception e) {
            throw new RuntimeException("Gagal menjalankan ccm.initAfterRecovery()", e);
        }
    }
}