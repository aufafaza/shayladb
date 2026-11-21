package mdbms.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import mdbms.api.IConcurrencyControl;
import mdbms.api.IDataAccess;
import mdbms.api.IDurableStorage;
import mdbms.api.IFailureRecoveryManager;
import mdbms.api.ISchemaCatalog;
import mdbms.models.access.DataRetrieval;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.data.Rows;
import mdbms.models.log.LogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import mdbms.models.log.RecoveryResult;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Integration test untuk Audit Poin 3, 4, dan 5 (FR-05 Historical Recovery).
 */
class HistoricalRecoveryIT {

    private static final String TABLE = "produk";
    private static final String INDEX_ID = "idx_produk_id";
    private static ClassLoader siblingClassLoader;

    @TempDir
    Path tempDir;

    // Poin 3: Maintenance barrier menolak historical recovery pada live DB tanpa barrier / saat ada transaksi aktif
    @Test
    void historicalRecoveryIsBlockedWithoutMaintenanceBarrierOrWithActiveTransactions() throws Exception {
        Path dbDir = tempDir.resolve("p3_barrier");
        Path snapDir = dbDir.resolve("snapshot");
        Object sm = createInitializedStorage(dbDir);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dbDir, snapDir, da, ds);
        attachRecoveryManager(sm, frm);
        IConcurrencyControl ccm = createLockBasedCcm(frm);
        invokeMethod(ccm, "initAfterRecovery");

        try {
            // 1. Panggilan langsung tanpa coordinator & tanpa offline maintenance mode -> DITOLAK
            RecoveryResult noBarrier = frm.recoverToTransaction(1);
            assertFalse(noBarrier.isSuccess(), "Panggilan langsung tanpa maintenance barrier wajib ditolak");

            // Seed baseline snapshot awal
            frm.createBaselineSnapshot();

            // Hubungkan live maintenance coordinator (admission dijeda DAN activeTx == 0)
            frm.attachMaintenanceCoordinator(() ->
                    !((boolean) invokeMethod(ccm, "isAcceptingTransactions"))
                            && ((int) invokeMethod(ccm, "getActiveTransactionCount")) == 0);

            int tx1 = ccm.beginTransaction();
            insertLogged(frm, da, tx1, List.of(1, "ROW_1"));
            ccm.commitTransaction(tx1);

            int tx2 = ccm.beginTransaction();
            insertLogged(frm, da, tx2, List.of(2, "ROW_2"));
            ccm.commitTransaction(tx2);

            // 2. Admission masih terbuka -> DITOLAK tanpa mengubah data
            RecoveryResult openAdmission = frm.recoverToTransaction(tx1);
            assertFalse(openAdmission.isSuccess());
            assertEquals(2, readRowsById(da).size());

            // 3. Admission dijeda tetapi masih ada transaksi aktif -> DITOLAK
            int activeTx = ccm.beginTransaction();
            invokeMethod(ccm, "pauseTransactions");
            RecoveryResult withActiveTx = frm.recoverToTransaction(tx1);
            assertFalse(withActiveTx.isSuccess());
            assertEquals(2, readRowsById(da).size());

            // 4. Transaksi aktif selesai + admission tetap dijeda -> BERHASIL
            ccm.abortTransaction(activeTx, "Selesai sebelum maintenance");
            RecoveryResult allowed = frm.recoverToTransaction(tx1);
            assertTrue(allowed.isSuccess(), "Harus berhasil saat maintenance barrier terpenuhi: " + allowed.getMessage());
            assertEquals(1, readRowsById(da).size());
            assertEquals(List.of(1, "ROW_1"), readRowsById(da).get(1));
        } finally {
            frm.close();
        }
    }

    // Poin 4: Manifest baseline, pembersihan file basi, validasi target out-of-range sebelum mutasi, & batas replay
    @Test
    void outOfRangeTargetsAreRejectedBeforeMutatingActiveStorageAndBaselineBoundaryIsRespected() throws Exception {
        Path dbDir = tempDir.resolve("p4_baseline_validation");
        Path snapDir = dbDir.resolve("snapshot");
        Object sm = createInitializedStorage(dbDir);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dbDir, snapDir, da, ds);
        attachRecoveryManager(sm, frm);

        try {
            // Taruh file basi di direktori snapshot untuk memastikan createSnapshot membersihkannya
            Files.createDirectories(snapDir);
            Path staleFile = snapDir.resolve("stale_old_table.tbl");
            Files.writeString(staleFile, "STALE");

            // Tx1 committed SEBELUM baseline snapshot
            frm.writeTransactionLog(1, LogType.BEGIN);
            insertLogged(frm, da, 1, List.of(1, "PRE_BASELINE"));
            frm.writeTransactionLog(1, LogType.COMMIT);
            Instant beforeBaseline = Instant.now().minusSeconds(60);

            // Buat baseline snapshot setelah Tx1 (snapshot berisi id=1)
            frm.createBaselineSnapshot();
            assertFalse(Files.exists(staleFile), "File snapshot basi harus dihapus saat createSnapshot()");
            assertTrue(Files.exists(snapDir.resolve(CheckpointManager.SNAPSHOT_META_FILE)),
                    "Manifest snapshot.meta harus dibuat");

            // Tx2 committed SESUDAH baseline snapshot
            Thread.sleep(15);
            frm.writeTransactionLog(2, LogType.BEGIN);
            insertLogged(frm, da, 2, List.of(2, "POST_BASELINE_TX2"));
            frm.writeTransactionLog(2, LogType.COMMIT);
            Instant afterTx2 = Instant.now();

            // Tx3 committed SESUDAH Tx2
            Thread.sleep(15);
            frm.writeTransactionLog(3, LogType.BEGIN);
            insertLogged(frm, da, 3, List.of(3, "POST_BASELINE_TX3"));
            frm.writeTransactionLog(3, LogType.COMMIT);
            ds.flushBuffer();

            // Out-of-range PITR (sebelum baseline) -> DITOLAK & storage aktif tetap berisi 3 baris
            RecoveryResult oldPitr = frm.recoverToPointInTimeOffline(beforeBaseline);
            assertFalse(oldPitr.isSuccess(), "Target PITR sebelum baseline wajib ditolak");
            assertEquals(3, readRowsById(da).size(), "Storage aktif tidak boleh berubah saat target PITR ditolak");

            // Out-of-range txId (tx1 commit sebelum baselineLsn) -> DITOLAK & storage aktif tetap berisi 3 baris
            RecoveryResult oldTx = frm.recoverToTransactionOffline(1);
            assertFalse(oldTx.isSuccess(), "Target transaksi sebelum baseline wajib ditolak");
            assertEquals(3, readRowsById(da).size(), "Storage aktif tidak boleh berubah saat target transaksi ditolak");

            // Target valid (afterTx2) -> restore baseline (id=1) + replay hanya sesudah baseline (id=2), id=3 hilang
            RecoveryResult validPitr = frm.recoverToPointInTimeOffline(afterTx2);
            assertTrue(validPitr.isSuccess(), "PITR valid harus berhasil: " + validPitr.getMessage());
            Map<Integer, List<Object>> afterRestore = readRowsById(da);
            assertEquals(2, afterRestore.size());
            assertEquals(List.of(1, "PRE_BASELINE"), afterRestore.get(1));
            assertEquals(List.of(2, "POST_BASELINE_TX2"), afterRestore.get(2));
            assertFalse(afterRestore.containsKey(3));
            assertTrue(da.searchIndex(INDEX_ID, 3).isEmpty());
        } finally {
            frm.close();
        }

        // Poin 5a: Verifikasi restart pasca-historical recovery tetap valid dan menerima transaksi baru
        Object smReopen = createRealStorageManager(dbDir);
        IDataAccess da2 = (IDataAccess) smReopen;
        IDurableStorage ds2 = (IDurableStorage) smReopen;
        FailureRecoveryFacade frm2 = new FailureRecoveryFacade(dbDir, snapDir, da2, ds2);
        attachRecoveryManager(smReopen, frm2);
        try {
            assertTrue(frm2.recoverOnStartup().isSuccess());
            Map<Integer, List<Object>> afterRestart = readRowsById(da2);
            assertEquals(2, afterRestart.size());
            assertEquals(List.of(1, "PRE_BASELINE"), afterRestart.get(1));
            assertEquals(List.of(2, "POST_BASELINE_TX2"), afterRestart.get(2));

            // Transaksi baru setelah restart memakai LSN lanjutan yang konsisten
            frm2.writeTransactionLog(4, LogType.BEGIN);
            insertLogged(frm2, da2, 4, List.of(4, "NEW_AFTER_PITR_RESTART"));
            frm2.writeTransactionLog(4, LogType.COMMIT);
            ds2.flushBuffer();
            assertEquals(3, readRowsById(da2).size());
        } finally {
            frm2.close();
        }
    }

    // Poin 5b: Kegagalan append/flush saat WAL rewrite gagal secara eksplisit & menjaga WAL lama tetap utuh
    @Test
    void walRewriteFailurePropagatesVisiblyAndPreservesOriginalWal() throws Exception {
        Path dbDir = tempDir.resolve("p5_wal_rewrite_fail");
        Path snapDir = dbDir.resolve("snapshot");
        Path walFile = dbDir.resolve(WalFileHandler.DEFAULT_WAL_PATH);

        Object sm = createInitializedStorage(dbDir);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;

        WalFileHandler failingRewriteWal = new WalFileHandler(walFile) {
            @Override
            protected WalFileHandler createStagingHandler(Path tempPath) {
                return new WalFileHandler(tempPath) {
                    @Override
                    public boolean flushUpTo(long lsn) {
                        return false; // Simulasikan kegagalan fsync pada berkas WAL hasil rewrite
                    }
                };
            }
        };

        LogManager lm = new LogManager(failingRewriteWal);
        RecordApplier applier = new RecordApplier(da);
        CheckpointManager cm = new CheckpointManager(lm, ds, applier, dbDir.toString(), snapDir.toString());
        FailureRecoveryFacade frm = new FailureRecoveryFacade(
                lm, new RollbackManager(lm, applier, ds), cm,
                new CrashRecoveryManager(lm, new RollbackManager(lm, applier, ds), cm, applier, ds), da, ds);
        attachRecoveryManager(sm, frm);

        try {
            frm.createBaselineSnapshot();

            frm.writeTransactionLog(1, LogType.BEGIN);
            insertLogged(frm, da, 1, List.of(1, "KEEP_ME"));
            frm.writeTransactionLog(1, LogType.COMMIT);

            frm.writeTransactionLog(2, LogType.BEGIN);
            insertLogged(frm, da, 2, List.of(2, "TRUNCATE_ME"));
            frm.writeTransactionLog(2, LogType.COMMIT);

            byte[] originalWalBytes = Files.readAllBytes(walFile);

            RecoveryResult res = frm.recoverToTransactionOffline(1);
            assertFalse(res.isSuccess(), "Kegagalan flush saat WAL rewrite wajib dilaporkan gagal");
            assertEquals(originalWalBytes.length, Files.readAllBytes(walFile).length,
                    "Berkas WAL asli tidak boleh hilang/tertimpa ketika staging rewrite gagal");
        } finally {
            frm.close();
        }
    }

    // --- Helpers ---

    private static RowId insertLogged(FailureRecoveryFacade frm, IDataAccess da, int txId, List<Object> vals) {
        RowId rid = da.reserveRowId(TABLE, vals);
        assertTrue(frm.writeDataLog(txId, LogType.INSERT, TABLE, rid, null, new RecordImage(vals)).isSuccess());
        da.insertAt(rid, vals);
        return rid;
    }

    private static Map<Integer, List<Object>> readRowsById(IDataAccess da) {
        Map<Integer, List<Object>> map = new HashMap<>();
        Rows rows = da.readBlock(new DataRetrieval(TABLE, List.of(), List.of()));
        if (rows != null && rows.rows() != null) {
            for (Row r : rows.rows()) {
                if (r != null && r.values() != null && !r.values().isEmpty()) {
                    map.put((Integer) r.values().get(0), r.values());
                }
            }
        }
        return map;
    }

    private static Object createInitializedStorage(Path dir) {
        Object sm = createRealStorageManager(dir);
        ISchemaCatalog catalog = (ISchemaCatalog) sm;
        if (catalog.getTableSchema(TABLE) == null) {
            catalog.updateTableSchema(new TableSchema(
                    TABLE,
                    List.of(
                            new ColumnDefinition("id", DataType.INTEGER, 0, false),
                            new ColumnDefinition("payload", DataType.VARCHAR, 200, false)
                    ),
                    List.of("id"),
                    List.of()
            ));
        }
        if (catalog.getIndexes(TABLE).stream().noneMatch(i -> INDEX_ID.equals(i.indexName()))) {
            catalog.createIndex(INDEX_ID, TABLE, "id");
        }
        ((IDurableStorage) sm).flushBuffer();
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
            siblingClassLoader = new URLClassLoader(urls.toArray(new URL[0]), HistoricalRecoveryIT.class.getClassLoader());
            return siblingClassLoader;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Object createRealStorageManager(Path dataDir) {
        try {
            Class<?> smClass = Class.forName("mdbms.storage.StorageManager", true, getSiblingClassLoader());
            Constructor<?> ctor = smClass.getConstructor(String.class, int.class, int.class);
            return ctor.newInstance(dataDir.toString(), 16, 4);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void attachRecoveryManager(Object sm, IFailureRecoveryManager frm) {
        try {
            sm.getClass().getMethod("attachRecoveryManager", IFailureRecoveryManager.class).invoke(sm, frm);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static IConcurrencyControl createLockBasedCcm(IFailureRecoveryManager frm) {
        try {
            ClassLoader cl = getSiblingClassLoader();
            Class<?> protocolInterface = Class.forName("mdbms.concurrency.ConcurrencyProtocol", true, cl);
            Object protocol = Class.forName("mdbms.concurrency.LockBasedProtocol", true, cl)
                    .getDeclaredConstructor().newInstance();
            Class<?> ccmClass = Class.forName("mdbms.concurrency.ConcurrencyControlManager", true, cl);
            return (IConcurrencyControl) ccmClass.getConstructor(protocolInterface, IFailureRecoveryManager.class)
                    .newInstance(protocol, frm);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Object invokeMethod(Object target, String name) {
        try {
            Method m = target.getClass().getMethod(name);
            return m.invoke(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}