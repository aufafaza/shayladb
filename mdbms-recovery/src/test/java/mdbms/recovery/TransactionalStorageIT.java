package mdbms.recovery;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;
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
 * Integration test untuk Issue #56:
 * Memverifikasi WAL-before-data saat eviction/flush, penolakan WAL-force,
 * outcome kegagalan pada 4 fase transaksi, pembersihan reservasi gagal/orphan,
 * serta konsistensi heap & derived B+Tree index dengan buffer kecil (3 frame) pada file asli.
 */
class TransactionalStorageIT {

    private static final String TABLE = "produk";
    private static final String INDEX_ID = "idx_produk_id";
    private static final int SMALL_BUFFER_CAPACITY = 3;
    private static final int INDEX_MAX_DEGREE = 4;
    private static final int BLOCK_SIZE = 4096;

    private static ClassLoader siblingClassLoader;

    @TempDir
    Path tempDir;

    // Kriteria 1 & 6: WAL wajib di-force hingga pageLsn sebelum write pada jalur LRU eviction & explicit flush
    @Test
    void walIsForcedThroughPageLsnBeforeWriteOnEvictionAndExplicitFlush() throws Exception {
        Path dir = tempDir.resolve("c1_wal_before_write_lsn");
        Files.createDirectories(dir);

        // Gunakan BufferPool asli (kapasitas 3 frame) + FailureRecoveryFacade asli dengan LSN non-nol
        Object blockManager = createBlockManager(dir);
        Object bufferPool = createBufferPool(blockManager, SMALL_BUFFER_CAPACITY);
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), null, null);
        TrackingRecoveryManager spyFrm = new TrackingRecoveryManager(frm);
        invokeAttachOnBufferPool(bufferPool, spyFrm);

        try {
            String fileName = "wal_order.tbl";
            // Alokasikan 4 blok untuk memaksa LRU eviction pada buffer berkapasitas 3 frame
            List<Long> pageLsns = new ArrayList<>();
            for (int blk = 0; blk < 4; blk++) {
                RecoveryResult logRes = frm.writeDataLog(
                        1, LogType.INSERT, TABLE, new RowId(TABLE, blk, 0),
                        null, new RecordImage(List.of(blk, "payload-" + blk)));
                assertTrue(logRes.isSuccess());
                assertTrue(logRes.getLsn() > 0);
                pageLsns.add(logRes.getLsn());

                Object appended = invokeBufferPoolAppend(bufferPool, fileName);
                invokeBufferPoolUnpin(bufferPool, appended, false, 0L);

                byte[] pageData = new byte[BLOCK_SIZE];
                Arrays.fill(pageData, (byte) (blk + 1));
                invokeBufferPoolWrite(bufferPool, fileName, blk, pageData, logRes.getLsn());
            }

            // Saat blok ke-3 (index 3) masuk ke buffer kapasitas 3, blok 0 wajib di-evict setelah WAL di-force >= pageLsn[0]
            assertTrue(
                    spyFrm.requestedFlushLsns.stream().anyMatch(lsn -> lsn >= pageLsns.get(0)),
                    "LRU eviction harus memanggil flushLogUpTo(pageLsn) untuk blok korban"
            );

            invokeBufferPoolFlushAll(bufferPool);

            // Verifikasi penolakan LSN yang belum pernah ditulis ke WAL mencegah penulisan halaman
            byte[] badData = new byte[BLOCK_SIZE];
            Arrays.fill(badData, (byte) 99);
            long unloggedLsn = pageLsns.get(pageLsns.size() - 1) + 50;
            invokeBufferPoolWrite(bufferPool, fileName, 3, badData, unloggedLsn);

            Path filePath = dir.resolve(fileName);
            byte[] diskBeforeBadFlush = Files.readAllBytes(filePath);
            assertThrows(RuntimeException.class, () -> invokeBufferPoolFlushAll(bufferPool),
                    "flushBuffer harus gagal jika pageLsn belum tertulis di WAL");
            assertArrayEquals(diskBeforeBadFlush, Files.readAllBytes(filePath),
                    "File disk tidak boleh berubah ketika WAL-force menolak LSN yang belum tercatat");

            // Perbaiki pageLsn blok 3 dengan LSN valid lalu flush eksplisit -> seluruh 4 blok terpersist ke disk
            RecoveryResult fixLog = frm.writeDataLog(
                    1, LogType.UPDATE, TABLE, new RowId(TABLE, 3, 0),
                    new RecordImage(List.of(3, "payload-3")),
                    new RecordImage(List.of(3, "fixed-3")));
            assertTrue(fixLog.isSuccess());
            // Re-init pool pada file yang sama dengan LSN valid untuk memverifikasi explicit flush
            Object freshPool = createBufferPool(blockManager, SMALL_BUFFER_CAPACITY);
            invokeAttachOnBufferPool(freshPool, spyFrm);
            byte[] validPage3 = new byte[BLOCK_SIZE];
            Arrays.fill(validPage3, (byte) 42);
            invokeBufferPoolWrite(freshPool, fileName, 3, validPage3, fixLog.getLsn());
            invokeBufferPoolFlushAll(freshPool);

            assertTrue(spyFrm.requestedFlushLsns.contains(fixLog.getLsn()));
            byte[] persisted = Files.readAllBytes(filePath);
            assertEquals(4 * BLOCK_SIZE, persisted.length);
            assertEquals((byte) 42, persisted[3 * BLOCK_SIZE]);
        } finally {
            frm.close();
        }
    }

    // Kriteria 2 & 6: WAL-force rejection mencegah data write pada eviction & explicit flush serta menjaga dirty state
    @Test
    void walForceRejectionPreventsDataWriteAndPreservesRecoverableDirtyState() throws Exception {
        Path dir = tempDir.resolve("c2_wal_rejection");
        Object sm = createInitializedStorage(dir);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da, ds);
        TrackingRecoveryManager spyFrm = new TrackingRecoveryManager(frm);
        attachRecoveryManagerToStorage(sm, spyFrm);

        try {
            // Seed awal yang terpersist ke disk
            int txSeed = 1;
            frm.writeTransactionLog(txSeed, LogType.BEGIN);
            RowId r1 = insertLogged(frm, da, txSeed, List.of(1, "A".repeat(450)));
            frm.writeTransactionLog(txSeed, LogType.COMMIT);
            ds.flushBuffer();

            Path tblFile = dir.resolve(TABLE + ".tbl");
            assertTrue(Files.exists(tblFile));

            // Kasus 2a: Penolakan WAL-force pada explicit flushBuffer()
            int tx2 = 2;
            frm.writeTransactionLog(tx2, LogType.BEGIN);
            List<Object> updated1 = List.of(1, "B".repeat(450));
            assertTrue(da.canUpdateInPlace(r1, updated1));
            assertTrue(frm.writeDataLog(tx2, LogType.UPDATE, TABLE, r1,
                    new RecordImage(List.of(1, "A".repeat(450))),
                    new RecordImage(updated1)).isSuccess());
            assertTrue(da.updateInPlace(r1, updated1));

            byte[] diskBeforeExplicitReject = Files.readAllBytes(tblFile);
            spyFrm.rejectWalFlush.set(true);

            assertThrows(RuntimeException.class, ds::flushBuffer,
                    "Explicit flushBuffer harus gagal saat WAL-force ditolak");
            assertArrayEquals(diskBeforeExplicitReject, Files.readAllBytes(tblFile),
                    "File .tbl tidak boleh berubah ketika WAL-force ditolak saat explicit flush");
            // Dirty state di buffer harus tetap utuh dan terbaca
            assertEquals(updated1, da.fetchByRowId(r1).values());

            // Aktifkan kembali WAL -> flush berhasil dan data terpersist
            spyFrm.rejectWalFlush.set(false);
            frm.writeTransactionLog(tx2, LogType.COMMIT);
            ds.flushBuffer();

            // Kasus 2b: Penolakan WAL-force saat LRU eviction (memaksa akses > 3 halaman)
            int tx3 = 3;
            frm.writeTransactionLog(tx3, LogType.BEGIN);
            // Tulis cukup baris sampai ada beberapa halaman dirty & penuh di buffer (kapasitas 3 frame)
            List<Integer> insertedIds = new ArrayList<>();
            for (int id = 2; id <= 25; id++) {
                insertLogged(frm, da, tx3, List.of(id, ("P" + id + "_").concat("X".repeat(450))));
                insertedIds.add(id);
            }
            // Pastikan blok 0 kotor lagi di buffer sebelum memicu eviction
            List<Object> updatedAgain = List.of(1, "C".repeat(450));
            assertTrue(frm.writeDataLog(tx3, LogType.UPDATE, TABLE, r1,
                    new RecordImage(updated1), new RecordImage(updatedAgain)).isSuccess());
            assertTrue(da.updateInPlace(r1, updatedAgain));

            byte[] diskBeforeEvictionReject = Files.readAllBytes(tblFile);
            spyFrm.rejectWalFlush.set(true);

            // Paksa eviction lewat full scan (membaca banyak halaman heap ke buffer 3 frame)
            assertThrows(RuntimeException.class,
                    () -> da.readBlock(new DataRetrieval(TABLE, List.of(), List.of())),
                    "Eviction frame dirty harus gagal saat WAL-force ditolak");
            assertArrayEquals(diskBeforeEvictionReject, Files.readAllBytes(tblFile),
                    "File .tbl tidak boleh berubah ketika WAL-force ditolak saat LRU eviction");

            // Pulihkan WAL -> dirty state tetap recoverable, commit & flush berhasil
            spyFrm.rejectWalFlush.set(false);
            frm.writeTransactionLog(tx3, LogType.COMMIT);
            ds.flushBuffer();

            // Verifikasi lewat reopen StorageManager baru dari file asli
            Object smReopened = createRealStorageManager(dir);
            IDataAccess daReopened = (IDataAccess) smReopened;
            Map<Integer, List<Object>> persistedRows = readRowsById(daReopened);
            assertEquals(updatedAgain, persistedRows.get(1));
            for (int id : insertedIds) {
                assertTrue(persistedRows.containsKey(id), "Baris id=" + id + " harus terpersist setelah WAL pulih");
                assertEquals(1, daReopened.searchIndex(INDEX_ID, id).size());
            }
        } finally {
            frm.close();
        }
    }

    // Kriteria 5 & 6: INSERT, in-place UPDATE, relocating UPDATE, DELETE, commit, abort, eviction (> 3 frame), reopen
    @Test
    void crudAllocationInPlaceAndRelocatingUpdateMaintainHeapAndIndexAcrossEvictionAndReopen() {
        Path dir = tempDir.resolve("c5_crud_eviction_reopen");
        Object sm = createInitializedStorage(dir);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da, ds);
        attachRecoveryManagerToStorage(sm, frm);

        try {
            // Tx 1 (Committed): INSERT banyak baris agar melewati kapasitas 3 frame (memicu eviction nyata)
            int tx1 = 1;
            frm.writeTransactionLog(tx1, LogType.BEGIN);
            RowId ridShort = insertLogged(frm, da, tx1, List.of(1, "short"));
            Map<Integer, RowId> rowIds = new HashMap<>();
            rowIds.put(1, ridShort);
            for (int id = 2; id <= 35; id++) {
                rowIds.put(id, insertLogged(frm, da, tx1, List.of(id, "F".repeat(450))));
            }
            frm.writeTransactionLog(tx1, LogType.COMMIT);
            assertTrue(Files.size(dir.resolve(TABLE + ".tbl")) > SMALL_BUFFER_CAPACITY * BLOCK_SIZE
                    || rowIds.values().stream().map(RowId::blockNumber).distinct().count() > SMALL_BUFFER_CAPACITY,
                    "Dataset uji harus melampaui kapasitas buffer 3 frame");

            // Tx 2 (Committed): in-place UPDATE pada id=2, relocating UPDATE pada id=1, DELETE pada id=3
            int tx2 = 2;
            frm.writeTransactionLog(tx2, LogType.BEGIN);

            // 2a. In-place UPDATE (id=2)
            RowId rid2 = rowIds.get(2);
            List<Object> before2 = List.of(2, "F".repeat(450));
            List<Object> after2 = List.of(2, "U".repeat(400));
            assertTrue(da.canUpdateInPlace(rid2, after2));
            assertTrue(frm.writeDataLog(tx2, LogType.UPDATE, TABLE, rid2,
                    new RecordImage(before2), new RecordImage(after2)).isSuccess());
            assertTrue(da.updateInPlace(rid2, after2));

            // 2b. Relocating UPDATE (id=1: dari "short" ke payload besar agar pindah halaman)
            List<Object> before1 = List.of(1, "short");
            List<Object> after1 = List.of(1, "R".repeat(550));
            assertFalse(da.canUpdateInPlace(ridShort, after1),
                    "Halaman 0 sudah penuh sehingga payload besar harus memicu relokasi");
            RowId newRid1 = da.reserveRowId(TABLE, after1);
            assertNotEquals(ridShort.blockNumber(), newRid1.blockNumber());
            assertTrue(frm.writeRelocationLog(tx2, TABLE, ridShort, newRid1,
                    new RecordImage(before1), new RecordImage(after1)).isSuccess());
            da.insertAt(newRid1, after1);
            da.deleteByRowId(ridShort);

            // 2c. DELETE (id=3)
            RowId rid3 = rowIds.get(3);
            assertTrue(frm.writeDataLog(tx2, LogType.DELETE, TABLE, rid3,
                    new RecordImage(List.of(3, "F".repeat(450))), null).isSuccess());
            da.deleteByRowId(rid3);

            frm.writeTransactionLog(tx2, LogType.COMMIT);

            // Tx 3 (Aborted): INSERT id=99, UPDATE id=4, DELETE id=5 -> semua harus kembali seperti semula
            int tx3 = 3;
            frm.writeTransactionLog(tx3, LogType.BEGIN);
            insertLogged(frm, da, tx3, List.of(99, "TempAborted"));

            RowId rid4 = rowIds.get(4);
            List<Object> before4 = List.of(4, "F".repeat(450));
            List<Object> after4 = List.of(4, "AbortedUpdate");
            assertTrue(frm.writeDataLog(tx3, LogType.UPDATE, TABLE, rid4,
                    new RecordImage(before4), new RecordImage(after4)).isSuccess());
            assertTrue(da.updateInPlace(rid4, after4));

            RowId rid5 = rowIds.get(5);
            assertTrue(frm.writeDataLog(tx3, LogType.DELETE, TABLE, rid5,
                    new RecordImage(List.of(5, "F".repeat(450))), null).isSuccess());
            da.deleteByRowId(rid5);

            assertTrue(frm.rollbackTransaction(tx3).isSuccess());
            ds.flushBuffer();

            // Verifikasi sebelum reopen
            assertHeapAndIndexAgree(da, 1, 35, after1, after2);
            assertTrue(da.searchIndex(INDEX_ID, 3).isEmpty());
            assertTrue(da.searchIndex(INDEX_ID, 99).isEmpty());
            assertEquals(List.of(4, "F".repeat(450)), da.fetchByRowId(da.searchIndex(INDEX_ID, 4).get(0)).values());
            assertEquals(List.of(5, "F".repeat(450)), da.fetchByRowId(da.searchIndex(INDEX_ID, 5).get(0)).values());
        } catch (IOException e) {
            throw new RuntimeException(e);
        } finally {
            frm.close();
        }

        // Reopen StorageManager baru dari disk & pastikan heap + index (exact + range) tetap 100% cocok
        Object smReopened = createRealStorageManager(dir);
        IDataAccess daReopened = (IDataAccess) smReopened;
        assertHeapAndIndexAgree(daReopened, 1, 35, List.of(1, "R".repeat(550)), List.of(2, "U".repeat(400)));
        assertEquals(4, daReopened.searchIndexRange(INDEX_ID, 1, 5, true, true).size(),
                "Range scan [1..5] harus menemukan 4 baris (1, 2, 4, 5 karena 3 terhapus)");
        assertTrue(daReopened.searchIndex(INDEX_ID, 99).isEmpty());
    }

    // Kriteria 3: Kegagalan sebelum logging, setelah logging, saat alokasi, dan saat index maintenance
    @Test
    void failuresAcrossBeforeLoggingAfterLoggingAllocationAndIndexMaintenanceHaveDefinedOutcomes() {
        Path dir = tempDir.resolve("c3_failure_outcomes");
        Object sm = createInitializedStorage(dir);
        IDataAccess da = (IDataAccess) sm;
        IDurableStorage ds = (IDurableStorage) sm;
        FailureRecoveryFacade frm = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da, ds);
        TrackingRecoveryManager spyFrm = new TrackingRecoveryManager(frm);
        attachRecoveryManagerToStorage(sm, spyFrm);

        try {
            // Seed awal (20 baris) supaya buffer 3 frame penuh dan halaman indeks punya banyak cabang
            int txSeed = 1;
            frm.writeTransactionLog(txSeed, LogType.BEGIN);
            for (int id = 1; id <= 20; id++) {
                insertLogged(frm, da, txSeed, List.of(id, "S".repeat(450)));
            }
            frm.writeTransactionLog(txSeed, LogType.COMMIT);
            ds.flushBuffer();

            // 3a. Gagal SEBELUM logging (misal validasi/konflik terjadi setelah reserveRowId, sebelum writeDataLog)
            int txBeforeLog = 2;
            frm.writeTransactionLog(txBeforeLog, LogType.BEGIN);
            RowId reservedBeforeLog = da.reserveRowId(TABLE, List.of(101, "BeforeLog"));
            // Kegagalan terjadi -> lepas reservasi dan abort transaksi
            da.releaseRowId(reservedBeforeLog);
            assertTrue(frm.rollbackTransaction(txBeforeLog).isSuccess());
            assertTrue(da.searchIndex(INDEX_ID, 101).isEmpty());
            assertFalse(readRowsById(da).containsKey(101));

            // 3b. Gagal SETELAH logging (log sudah ditulis, mutasi parsial terjadi, lalu rollback)
            int txAfterLog = 3;
            frm.writeTransactionLog(txAfterLog, LogType.BEGIN);
            RowId rAfterLog = da.reserveRowId(TABLE, List.of(102, "AfterLog"));
            assertTrue(frm.writeDataLog(txAfterLog, LogType.INSERT, TABLE, rAfterLog,
                    null, new RecordImage(List.of(102, "AfterLog"))).isSuccess());
            da.insertAt(rAfterLog, List.of(102, "AfterLog"));
            // Simulasi kegagalan sebelum commit -> rollback FRM wajib membersihkan heap & indeks
            assertTrue(frm.rollbackTransaction(txAfterLog).isSuccess());
            assertTrue(da.searchIndex(INDEX_ID, 102).isEmpty());
            assertFalse(readRowsById(da).containsKey(102));

            // 3c. Gagal SAAT ALOKASI (buffer 3 frame penuh dengan halaman dirty, WAL menolak saat reserveRowId evict)
            int txAllocFail = 4;
            frm.writeTransactionLog(txAllocFail, LogType.BEGIN);
            // Buat frame dirty baru di buffer, lalu aktifkan penolakan WAL
            RowId rDirty = insertLogged(frm, da, txAllocFail, List.of(103, "D".repeat(450)));
            spyFrm.rejectWalFlush.set(true);
            // Paksa akses/alokasi melampaui 3 frame hingga eviction memicu kegagalan saat alokasi
            RuntimeException allocEx = assertThrows(RuntimeException.class, () -> {
                for (int k = 200; k < 240; k++) {
                    da.reserveRowId(TABLE, List.of(k, "AllocFail".concat("Z".repeat(450))));
                }
            });
            assertTrue(allocEx.getMessage() != null);
            // Pulihkan WAL lalu abort transaksi -> rDirty dibatalkan, tidak ada baris 103 atau 200..239
            spyFrm.rejectWalFlush.set(false);
            assertTrue(frm.rollbackTransaction(txAllocFail).isSuccess());
            assertFalse(readRowsById(da).containsKey(103));
            assertTrue(da.searchIndex(INDEX_ID, 103).isEmpty());
            assertTrue(da.searchIndex(INDEX_ID, 200).isEmpty());

            // 3d. Gagal SAAT INDEX MAINTENANCE (afterWrite gagal karena eviction halaman indeks ditolak WAL)
            // Buat satu halaman heap dirty di buffer, lalu cari target id yang memicu eviction ketika indexManager.onInsert menulis
            int txIdxFail = 5;
            frm.writeTransactionLog(txIdxFail, LogType.BEGIN);
            RowId rIdxTarget = da.reserveRowId(TABLE, List.of(104, "IdxFail"));
            assertTrue(frm.writeDataLog(txIdxFail, LogType.INSERT, TABLE, rIdxTarget,
                    null, new RecordImage(List.of(104, "IdxFail"))).isSuccess());

            // Paksa eviction saat insertAt menyentuh indeks dengan mengisi 3 frame dirty lalu menolak WAL
            spyFrm.rejectWalFlush.set(true);
            try {
                // Jika insertAt sendiri gagal (karena heap/index eviction), SM otomatis meng-undo heap;
                // jika muat di frame yg ada, akses indeks berikutnya akan gagal -> tetap di-rollback.
                da.insertAt(rIdxTarget, List.of(104, "IdxFail"));
                da.searchIndex(INDEX_ID, 1);
            } catch (RuntimeException expected) {
                // Kegagalan tertangkap sesuai definisi outcome transaksi
            } finally {
                spyFrm.rejectWalFlush.set(false);
            }
            assertTrue(frm.rollbackTransaction(txIdxFail).isSuccess());
            ds.flushBuffer();

            // Pastikan seluruh 20 baris committed tetap utuh dan tidak ada sisa transaksi gagal
            Map<Integer, List<Object>> finalRows = readRowsById(da);
            assertEquals(20, finalRows.size());
            for (int id = 1; id <= 20; id++) {
                assertTrue(finalRows.containsKey(id));
                assertEquals(1, da.searchIndex(INDEX_ID, id).size());
            }
            assertFalse(finalRows.containsKey(104));
            assertTrue(da.searchIndex(INDEX_ID, 104).isEmpty());
        } finally {
            frm.close();
        }
    }

    // Kriteria 4: Reservasi gagal (eksplisit maupun orphan pasca-crash lintas halaman) tidak mengekspos baris / membocorkan ruang
    @Test
    void failedAndCrashOrphanReservationsAcrossPagesDoNotExposeRowsOrLeakSpace() throws IOException {
        Path dir = tempDir.resolve("c4_orphan_reservations");
        Object sm1 = createInitializedStorage(dir);
        IDataAccess da1 = (IDataAccess) sm1;
        IDurableStorage ds1 = (IDurableStorage) sm1;
        FailureRecoveryFacade frm1 = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da1, ds1);
        attachRecoveryManagerToStorage(sm1, frm1);

        Path tblFile = dir.resolve(TABLE + ".tbl");
        int maxOrphanBlock;
        int orphansOnLastBlock;
        RowId orphanOnEarlierPage;
        RowId orphanOnLastPage;
        try {
            // Seed 10 baris lintas beberapa halaman (committed)
            frm1.writeTransactionLog(1, LogType.BEGIN);
            for (int id = 1; id <= 10; id++) {
                insertLogged(frm1, da1, 1, List.of(id, "M".repeat(450)));
            }
            frm1.writeTransactionLog(1, LogType.COMMIT);

            // 4a. Reservasi dilepas secara eksplisit berulang kali -> tidak mengekspos baris & tidak membocorkan ukuran file
            RowId warmUp = da1.reserveRowId(TABLE, List.of(500, "T".repeat(550)));
            da1.releaseRowId(warmUp);
            ds1.flushBuffer();
            long sizeAfterWarmUp = Files.size(tblFile);

            for (int i = 0; i < 20; i++) {
                RowId tempRes = da1.reserveRowId(TABLE, List.of(500 + i, "T".repeat(550)));
                da1.releaseRowId(tempRes);
            }
            ds1.flushBuffer();
            assertEquals(10, readRowsById(da1).size());
            assertTrue(da1.searchIndex(INDEX_ID, 500).isEmpty());
            assertEquals(sizeAfterWarmUp, Files.size(tblFile),
                    "20x reserve+release (~11 KB) tidak boleh menambah ukuran file .tbl");

            // 4b. Simulasi crash meninggalkan 12 orphan reservation besar lintas halaman (tanpa insertAt / releaseRowId)
            frm1.writeTransactionLog(2, LogType.BEGIN);
            Set<Integer> orphanBlocks = new HashSet<>();
            List<RowId> orphans = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                RowId orphan = da1.reserveRowId(TABLE, List.of(600 + i, "O".repeat(550)));
                orphans.add(orphan);
                orphanBlocks.add(orphan.blockNumber());
            }
            assertTrue(orphanBlocks.size() >= 2, "Orphan reservation uji harus tersebar minimal di 2 halaman");
            maxOrphanBlock = orphans.stream().mapToInt(RowId::blockNumber).max().orElseThrow();
            orphansOnLastBlock = (int) orphans.stream().filter(r -> r.blockNumber() == maxOrphanBlock).count();
            orphanOnEarlierPage = orphans.stream().filter(r -> r.blockNumber() < maxOrphanBlock).findFirst().orElseThrow();
            orphanOnLastPage = orphans.stream().filter(r -> r.blockNumber() == maxOrphanBlock).findFirst().orElseThrow();

            // Flush WAL + buffer sebelum simulasi crash agar flag reserved tertulis di disk
            frm1.flushLogUpTo(frm1.getLogManager().getCurrentLsn());
            ds1.flushBuffer();
        } finally {
            frm1.close();
        }

        // Reopen & jalankan crash recovery -> rebuildIndexesAndReloadCatalog() wajib melepas semua orphan reservation
        Object sm2 = createRealStorageManager(dir);
        IDataAccess da2 = (IDataAccess) sm2;
        IDurableStorage ds2 = (IDurableStorage) sm2;
        FailureRecoveryFacade frm2 = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da2, ds2);
        attachRecoveryManagerToStorage(sm2, frm2);
        try {
            assertTrue(frm2.recoverOnStartup().isSuccess());
            assertEquals(10, readRowsById(da2).size());
            assertTrue(da2.searchIndex(INDEX_ID, 600).isEmpty());
            assertTrue(da2.searchIndex(INDEX_ID, 611).isEmpty());
        } finally {
            frm2.close();
        }

        // Reopen instance ke-3 tanpa recovery untuk memastikan seluruh pelepasan orphan sudah durable di disk
        Object sm3 = createRealStorageManager(dir);
        IDataAccess da3 = (IDataAccess) sm3;
        assertEquals(0, invokeReleaseOrphanReservations(sm3),
                "Tidak boleh ada sisa orphan reservation di halaman mana pun setelah crash recovery");
        assertThrows(RuntimeException.class, () -> da3.releaseRowId(orphanOnEarlierPage),
                "Slot orphan pada halaman awal seharusnya sudah tidak berstatus reserved");
        assertThrows(RuntimeException.class, () -> da3.releaseRowId(orphanOnLastPage),
                "Slot orphan pada halaman akhir seharusnya sudah tidak berstatus reserved");

        // Ruang pada blok terakhir dipakai kembali lebih dulu karena HeapFile.findBlockWithSpace mulai dari blok terakhir
        int expectedReusable = Math.max(1, orphansOnLastBlock - 1);
        List<RowId> reused = new ArrayList<>();
        try {
            for (int i = 0; i < expectedReusable; i++) {
                RowId nextAlloc = da3.reserveRowId(TABLE, List.of(700 + i, "N".repeat(550)));
                reused.add(nextAlloc);
                assertEquals(
                        maxOrphanBlock,
                        nextAlloc.blockNumber(),
                        "Ruang orphan yang sudah dilepas di blok " + maxOrphanBlock + " harus terpakai kembali"
                );
            }

            // Setelah blok terakhir penuh, ruang orphan di halaman awal juga harus terjangkau (tanpa menambah blok baru)
            RowId earlierAlloc = null;
            for (int i = 0; i < 12 && earlierAlloc == null; i++) {
                RowId nextAlloc = da3.reserveRowId(TABLE, List.of(720 + i, "N".repeat(550)));
                reused.add(nextAlloc);
                assertTrue(nextAlloc.blockNumber() <= maxOrphanBlock,
                        "Tidak boleh menambah blok baru selama halaman awal masih punya ruang orphan yang dilepas");
                if (nextAlloc.blockNumber() < maxOrphanBlock) {
                    earlierAlloc = nextAlloc;
                }
            }
            assertTrue(earlierAlloc != null,
                    "Ruang orphan yang dilepas di halaman awal (mis. blok " + orphanOnEarlierPage.blockNumber()
                            + ") harus dapat dipakai kembali");
        } finally {
            for (RowId rid : reused) {
                da3.releaseRowId(rid);
            }
        }
    }

    // Kriteria 5 & 6: Kebijakan derived index -> file indeks tertinggal saat crash dibangun ulang & terpersist
    @Test
    void staleDerivedIndexIsRebuiltAndPersistedDuringCrashRecovery() throws Exception {
        Path dir = tempDir.resolve("c5_derived_index_rebuild");
        Path staleBackupDir = tempDir.resolve("stale_idx_backup");
        Files.createDirectories(staleBackupDir);

        Object sm1 = createInitializedStorage(dir);
        IDataAccess da1 = (IDataAccess) sm1;
        IDurableStorage ds1 = (IDurableStorage) sm1;
        FailureRecoveryFacade frm1 = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da1, ds1);
        attachRecoveryManagerToStorage(sm1, frm1);

        List<Path> indexFiles;
        try {
            // Tx 1 (Committed): seed 5 baris lalu flush ke disk
            frm1.writeTransactionLog(1, LogType.BEGIN);
            Map<Integer, RowId> rids = new HashMap<>();
            for (int id = 1; id <= 5; id++) {
                rids.put(id, insertLogged(frm1, da1, 1, List.of(id, "Init-" + id)));
            }
            frm1.writeTransactionLog(1, LogType.COMMIT);
            ds1.flushBuffer();

            // Deteksi semua file indeks milik tabel di direktori data dan simpan salinan lamanya
            indexFiles = findIndexFiles(dir);
            assertFalse(indexFiles.isEmpty(), "File indeks B+Tree harus terbentuk di disk");
            for (Path idxFile : indexFiles) {
                Files.copy(idxFile, staleBackupDir.resolve(idxFile.getFileName()));
            }

            // Tx 2 (Committed): tambah baris 6..15 (melewati kapasitas buffer 3 frame) dan hapus baris 2
            frm1.writeTransactionLog(2, LogType.BEGIN);
            for (int id = 6; id <= 15; id++) {
                insertLogged(frm1, da1, 2, List.of(id, "Post-" + id + "_" + "V".repeat(400)));
            }
            assertTrue(frm1.writeDataLog(2, LogType.DELETE, TABLE, rids.get(2),
                    new RecordImage(List.of(2, "Init-2")), null).isSuccess());
            da1.deleteByRowId(rids.get(2));
            frm1.writeTransactionLog(2, LogType.COMMIT);
            ds1.flushBuffer();

            // Tx 3 (Uncommitted): tulis baris 99 ke disk sebelum crash
            frm1.writeTransactionLog(3, LogType.BEGIN);
            insertLogged(frm1, da1, 3, List.of(99, "UncommittedCrash"));
            frm1.flushLogUpTo(frm1.getLogManager().getCurrentLsn());
            ds1.flushBuffer();
        } finally {
            frm1.close();
        }

        // Timpa file indeks di disk dengan versi lama (simulasi halaman indeks tertinggal dari heap saat crash)
        for (Path idxFile : indexFiles) {
            Files.write(idxFile, Files.readAllBytes(staleBackupDir.resolve(idxFile.getFileName())));
        }

        // Reopen & jalankan crash recovery
        Object sm2 = createRealStorageManager(dir);
        IDataAccess da2 = (IDataAccess) sm2;
        IDurableStorage ds2 = (IDurableStorage) sm2;
        FailureRecoveryFacade frm2 = new FailureRecoveryFacade(dir, dir.resolve("snapshot"), da2, ds2);
        attachRecoveryManagerToStorage(sm2, frm2);
        try {
            assertTrue(frm2.recoverOnStartup().isSuccess());
            verifyRecoveredDerivedIndex(da2);
        } finally {
            frm2.close();
        }

        // Reopen instance ke-3 tanpa recovery untuk membuktikan indeks hasil rebuild sudah durable di disk
        Object sm3 = createRealStorageManager(dir);
        verifyRecoveredDerivedIndex((IDataAccess) sm3);
    }

    // --- Helper Verifikasi & Setup ---

    private static void verifyRecoveredDerivedIndex(IDataAccess da) {
        Map<Integer, List<Object>> heap = readRowsById(da);
        assertEquals(14, heap.size(), "Harus ada 14 baris (1..15 kecuali 2; 99 di-undo)");
        assertFalse(heap.containsKey(2));
        assertFalse(heap.containsKey(99));
        assertTrue(da.searchIndex(INDEX_ID, 2).isEmpty());
        assertTrue(da.searchIndex(INDEX_ID, 99).isEmpty());

        for (int id = 1; id <= 15; id++) {
            if (id == 2) {
                continue;
            }
            List<RowId> idx = da.searchIndex(INDEX_ID, id);
            assertEquals(1, idx.size(), "Indeks untuk id=" + id + " harus pulih tepat 1 entri");
            assertEquals(heap.get(id), da.fetchByRowId(idx.get(0)).values());
        }
        assertEquals(14, da.searchIndexRange(INDEX_ID, 1, 15, true, true).size());
    }

    private static void assertHeapAndIndexAgree(
            IDataAccess da, int minId, int maxId, List<Object> expected1, List<Object> expected2) {
        Map<Integer, List<Object>> heap = readRowsById(da);
        assertEquals(expected1, heap.get(1));
        assertEquals(expected2, heap.get(2));
        for (int id = minId; id <= maxId; id++) {
            if (id == 3) {
                assertFalse(heap.containsKey(3));
                assertTrue(da.searchIndex(INDEX_ID, 3).isEmpty());
                continue;
            }
            assertTrue(heap.containsKey(id), "Heap harus berisi id=" + id);
            List<RowId> idx = da.searchIndex(INDEX_ID, id);
            assertEquals(1, idx.size(), "Indeks harus tepat 1 entri untuk id=" + id);
            assertEquals(heap.get(id), da.fetchByRowId(idx.get(0)).values());
        }
    }

    private static RowId insertLogged(FailureRecoveryFacade frm, IDataAccess da, int txId, List<Object> values) {
        RowId rid = da.reserveRowId(TABLE, values);
        RecoveryResult res = frm.writeDataLog(txId, LogType.INSERT, TABLE, rid, null, new RecordImage(values));
        assertTrue(res.isSuccess());
        da.insertAt(rid, values);
        return rid;
    }

    private static Map<Integer, List<Object>> readRowsById(IDataAccess da) {
        Map<Integer, List<Object>> result = new HashMap<>();
        Rows rows = da.readBlock(new DataRetrieval(TABLE, List.of(), List.of()));
        if (rows != null && rows.rows() != null) {
            for (Row r : rows.rows()) {
                if (r != null && r.values() != null && !r.values().isEmpty()) {
                    result.put((Integer) r.values().get(0), r.values());
                }
            }
        }
        return result;
    }

    private static List<Path> findIndexFiles(Path dir) throws IOException {
        List<Path> found = new ArrayList<>();
        try (Stream<Path> stream = Files.list(dir)) {
            stream.filter(Files::isRegularFile).forEach(p -> {
                String name = p.getFileName().toString().toLowerCase();
                if (name.contains(INDEX_ID.toLowerCase()) || name.endsWith(".idx")) {
                    found.add(p);
                }
            });
        }
        return found;
    }

    private static Object createInitializedStorage(Path dir) {
        Object sm = createRealStorageManager(dir);
        ISchemaCatalog catalog = (ISchemaCatalog) sm;
        if (catalog.getTableSchema(TABLE) == null) {
            TableSchema schema = new TableSchema(
                    TABLE,
                    List.of(
                            new ColumnDefinition("id", DataType.INTEGER, 0, false),
                            new ColumnDefinition("deskripsi", DataType.VARCHAR, 600, false)
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

    // Wrapper tipis di atas FailureRecoveryFacade asli untuk merekam LSN & menyuntikkan penolakan WAL-force
    private static final class TrackingRecoveryManager implements IFailureRecoveryManager {
        private final FailureRecoveryFacade delegate;
        final AtomicBoolean rejectWalFlush = new AtomicBoolean(false);
        final List<Long> requestedFlushLsns = new ArrayList<>();

        private TrackingRecoveryManager(FailureRecoveryFacade delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean flushLogUpTo(long lsn) {
            requestedFlushLsns.add(lsn);
            if (rejectWalFlush.get()) {
                return false;
            }
            return delegate.flushLogUpTo(lsn);
        }

        @Override public RecoveryResult writeTransactionLog(int txId, LogType type) { return delegate.writeTransactionLog(txId, type); }
        @Override public RecoveryResult writeDataLog(int txId, LogType type, String table, RowId rid, RecordImage beforeImg, RecordImage afterImg) { return delegate.writeDataLog(txId, type, table, rid, beforeImg, afterImg); }
        @Override public RecoveryResult writeRelocationLog(int txId, String table, RowId oldRid, RowId newRid, RecordImage beforeImg, RecordImage afterImg) { return delegate.writeRelocationLog(txId, table, oldRid, newRid, beforeImg, afterImg); }
        @Override public RecoveryResult createCheckpoint() { return delegate.createCheckpoint(); }        @Override public RecoveryResult rollbackTransaction(int txId) { return delegate.rollbackTransaction(txId); }
        @Override public RecoveryResult recoverOnStartup() { return delegate.recoverOnStartup(); }
        @Override public List<LogRecord> readLogs(Integer txId) { return delegate.readLogs(txId); }
        @Override public RecoveryResult recoverToPointInTime(java.time.Instant targetTime) { return delegate.recoverToPointInTime(targetTime); }
        @Override public RecoveryResult recoverToTransaction(int targetTxId) { return delegate.recoverToTransaction(targetTxId); }
    }

    // --- Reflection Helper untuk Modul Sibling (mdbms-storage) ---

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
            siblingClassLoader = new URLClassLoader(urls.toArray(new URL[0]), TransactionalStorageIT.class.getClassLoader());
            return siblingClassLoader;
        } catch (Exception e) {
            throw new RuntimeException("Gagal menyiapkan ClassLoader mdbms-storage", e);
        }
    }

    private static Object createRealStorageManager(Path dataDir) {
        try {
            Class<?> smClass = Class.forName("mdbms.storage.StorageManager", true, getSiblingClassLoader());
            Constructor<?> ctor = smClass.getConstructor(String.class, int.class, int.class);
            return ctor.newInstance(dataDir.toString(), SMALL_BUFFER_CAPACITY, INDEX_MAX_DEGREE);
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

    private static Object createBlockManager(Path dataDir) throws Exception {
        Class<?> bmClass = Class.forName("mdbms.storage.block.BlockManager", true, getSiblingClassLoader());
        return bmClass.getConstructor(String.class).newInstance(dataDir.toString());
    }

    private static Object createBufferPool(Object blockManager, int capacity) throws Exception {
        Class<?> bmClass = Class.forName("mdbms.storage.block.BlockManager", true, getSiblingClassLoader());
        Class<?> bpClass = Class.forName("mdbms.storage.block.BufferPool", true, getSiblingClassLoader());
        return bpClass.getConstructor(bmClass, int.class).newInstance(blockManager, capacity);
    }

    private static void invokeAttachOnBufferPool(Object bp, IFailureRecoveryManager frm) throws Exception {
        bp.getClass().getMethod("attachRecoveryManager", IFailureRecoveryManager.class).invoke(bp, frm);
    }

    private static Object invokeBufferPoolAppend(Object bp, String fileName) throws Exception {
        return bp.getClass().getMethod("appendBlock", String.class).invoke(bp, fileName);
    }

    private static void invokeBufferPoolUnpin(Object bp, Object block, boolean dirty, long pageLsn) throws Exception {
        Class<?> blockClass = Class.forName("mdbms.storage.block.Block", true, getSiblingClassLoader());
        bp.getClass().getMethod("unpin", blockClass, boolean.class, long.class).invoke(bp, block, dirty, pageLsn);
    }

    private static void invokeBufferPoolWrite(Object bp, String fileName, int blockNumber, byte[] data, long pageLsn) throws Exception {
        bp.getClass().getMethod("writeBuffer", String.class, int.class, byte[].class, long.class)
                .invoke(bp, fileName, blockNumber, data, pageLsn);
    }

    private static void invokeBufferPoolFlushAll(Object bp) throws Exception {
        try {
            bp.getClass().getMethod("flushBuffer").invoke(bp);
        } catch (java.lang.reflect.InvocationTargetException e) {
            if (e.getCause() instanceof RuntimeException re) {
                throw re;
            }
            throw e;
        }
    }

    private static int invokeReleaseOrphanReservations(Object sm) {
        try {
            Method m = sm.getClass().getMethod("releaseOrphanReservations");
            return (int) m.invoke(sm);
        } catch (Exception e) {
            throw new RuntimeException("Gagal memanggil StorageManager.releaseOrphanReservations()", e);
        }
    }
}