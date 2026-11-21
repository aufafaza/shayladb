package mdbms.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.access.DataRetrieval;
import mdbms.models.access.DataWrite;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// test #56 bagian SM, buffer sengaja kecil biar eviction kejadian dan semua dicek lewat file asli di disk
class StorageDurabilityTest {

    private static final int SMALL_BUFFER = 3;
    private static final int ROWS = 120;

    @TempDir Path directory;
    private Wal wal;
    private StorageManager storage;

    // FRM palsu yang bisa disuruh nerima atau nolak flush WAL, tiap panggilan dicatat
    private static final class Wal {
        private boolean accepts = true;
        private int flushCalls;

        private IFailureRecoveryManager proxy() {
            return (IFailureRecoveryManager) Proxy.newProxyInstance(
                    IFailureRecoveryManager.class.getClassLoader(),
                    new Class<?>[] {IFailureRecoveryManager.class},
                    (p, method, args) -> {
                        if (method.getName().equals("flushLogUpTo")) {
                            flushCalls++;
                            return accepts;
                        }
                        return method.getReturnType() == boolean.class ? true : null;
                    });
        }
    }

    @BeforeEach
    void setUp() {
        wal = new Wal();
        storage = open();
    }

    // StorageManager baru di direktori yang sama, dipakai juga buat pura pura restart setelah crash
    private StorageManager open() {
        StorageManager opened = new StorageManager(directory.toString(), SMALL_BUFFER, 4);
        opened.attachRecoveryManager(wal.proxy());
        return opened;
    }

    // tabel dengan kolom lebar biar satu halaman cuma muat belasan baris
    private void createItems() {
        storage.updateTableSchema(new TableSchema("items", List.of(
                new ColumnDefinition("id", DataType.INTEGER, 0, false),
                new ColumnDefinition("label", DataType.VARCHAR, 200, false)),
                List.of("id"), List.of()));
    }

    private static Map<String, Object> item(int id, String label) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("label", label);
        return values;
    }

    // label panjang tetap biar update di tempat dan baris cepat menuhin halaman
    private static String label(String prefix, int id) {
        return String.format("%s-%04d-", prefix, id) + "x".repeat(180);
    }

    private List<RowId> insertItems() {
        List<RowId> rows = new ArrayList<>();
        for (int id = 1; id <= ROWS; id++) {
            rows.add(storage.writeBlock(new DataWrite("items", item(id, label("a", id)), List.of())).get(0));
        }
        return rows;
    }

    private static List<Row> scan(StorageManager storage) {
        return storage.readBlock(new DataRetrieval("items", List.of(), List.of())).rows();
    }

    // tiap baris heap harus ketemu lewat kedua index, dan jumlah entri index sama dengan jumlah baris heap
    private static void assertHeapMatchesIndexes(StorageManager storage) {
        List<Row> rows = scan(storage);
        for (Row row : rows) {
            assertTrue(storage.searchIndex("idx_items_id", row.values().get(0)).contains(row.rowId()),
                    "id index misses " + row.rowId());
            assertTrue(storage.searchIndex("idx_items_label", row.values().get(1)).contains(row.rowId()),
                    "label index misses " + row.rowId());
        }
        assertEquals(rows.size(), storage.searchIndexRange("idx_items_id", null, null, true, true).size());
        assertEquals(rows.size(), storage.searchIndexRange("idx_items_label", null, null, true, true).size());
    }

    // data lebih besar dari buffer tetap utuh di disk setelah eviction dan restart
    @Test
    void dataLargerThanBufferSurvivesEvictionAndReopen() {
        createItems();
        insertItems();
        storage.flushBuffer();

        assertTrue(storage.getStatistics("items").br() > SMALL_BUFFER, "test must exceed buffer capacity");
        assertEquals(ROWS, scan(open()).size());
        assertTrue(wal.flushCalls > 0);
    }

    // WAL nolak flush pas eviction, halaman ga ketulis ke disk, frame tetap dirty, lalu sukses setelah WAL nerima lagi
    @Test
    void walRejectionDuringEvictionKeepsDiskUnchangedAndDirtyState() throws Exception {
        createItems();
        List<RowId> rows = insertItems();
        storage.flushBuffer();
        byte[] onDisk = Files.readAllBytes(directory.resolve("items.tbl"));

        wal.accepts = false;
        int updated = 0;
        StorageException failure = null;
        for (RowId row : rows) {
            int id = updated + 1;
            try {
                storage.updateByRowId(row, List.of(id, label("b", id)));
                updated++;
            } catch (StorageException e) {
                failure = e;
                break;
            }
        }

        assertTrue(failure != null, "eviction must need a WAL flush within " + ROWS + " updates");
        assertEquals(StorageErrorCode.WAL_FLUSH_FAILED, failure.getCode());
        assertArrayEquals(onDisk, Files.readAllBytes(directory.resolve("items.tbl")));
        assertTrue(storage.getBufferStats().dirtyBlocks() > 0);

        StorageException explicit = assertThrows(StorageException.class, storage::flushBuffer);
        assertEquals(StorageErrorCode.WAL_FLUSH_FAILED, explicit.getCode());
        assertArrayEquals(onDisk, Files.readAllBytes(directory.resolve("items.tbl")));

        wal.accepts = true;
        storage.flushBuffer();
        assertEquals(0, storage.getBufferStats().dirtyBlocks());

        List<Row> reopened = scan(open());
        long changed = reopened.stream().filter(r -> ((String) r.values().get(1)).startsWith("b-")).count();
        assertEquals(updated, changed);
        assertEquals(ROWS, reopened.size());
    }

    // insert, update, delete, lalu dibatalin lewat jalur undo FRM, heap dan index tetap sama sebelum dan sesudah restart
    @Test
    void heapAndIndexesAgreeAfterChangesUndoEvictionAndReopen() {
        createItems();
        List<RowId> rows = insertItems();
        storage.createIndex("idx_items_id", "items", "id");
        storage.createIndex("idx_items_label", "items", "label");

        List<Row> deleted = new ArrayList<>();
        for (int i = 0; i < ROWS; i += 7) {
            deleted.add(storage.deleteByRowId(rows.get(i)));
        }
        List<Row> beforeUpdate = new ArrayList<>();
        for (int i = 3; i < ROWS; i += 5) {
            if (i % 7 == 0) continue;
            Row before = storage.fetchByRowId(rows.get(i));
            beforeUpdate.add(before);
            storage.updateByRowId(rows.get(i), List.of(before.values().get(0), label("u", i)));
        }
        RowId extra = storage.writeBlock(new DataWrite("items", item(9999, label("n", 9999)), List.of())).get(0);
        assertHeapMatchesIndexes(storage);

        storage.deleteByRowId(extra);
        for (Row before : beforeUpdate) {
            storage.updateByRowId(before.rowId(), before.values());
        }
        for (Row row : deleted) {
            storage.insertAt(row.rowId(), row.values());
        }
        assertEquals(ROWS, scan(storage).size());
        assertHeapMatchesIndexes(storage);

        storage.flushBuffer();
        StorageManager reopened = open();
        assertEquals(ROWS, scan(reopened).size());
        assertHeapMatchesIndexes(reopened);

        reopened.rebuildIndexesAndReloadCatalog();
        assertHeapMatchesIndexes(reopened);
    }

    // file index yang ketinggalan dari heap setelah crash disamain lagi pas langkah setelah recovery
    @Test
    void staleIndexAfterCrashIsRebuiltBeforeServing() throws Exception {
        createItems();
        insertItems();
        storage.createIndex("idx_items_id", "items", "id");
        storage.createIndex("idx_items_label", "items", "label");
        storage.flushBuffer();
        Path staleId = Files.copy(directory.resolve("idx_items_id.idx"), directory.resolve("stale_id.bak"));
        Path staleLabel = Files.copy(directory.resolve("idx_items_label.idx"), directory.resolve("stale_label.bak"));

        for (int id = ROWS + 1; id <= ROWS + 40; id++) {
            storage.writeBlock(new DataWrite("items", item(id, label("a", id)), List.of()));
        }
        storage.flushBuffer();
        Files.copy(staleId, directory.resolve("idx_items_id.idx"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        Files.copy(staleLabel, directory.resolve("idx_items_label.idx"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        StorageManager restarted = open();
        assertTrue(restarted.searchIndex("idx_items_id", ROWS + 1).isEmpty());

        restarted.rebuildIndexesAndReloadCatalog();

        assertEquals(ROWS + 40, scan(restarted).size());
        assertHeapMatchesIndexes(restarted);
    }

    // katalog udah nyatet index tapi meta nya belum sempat ke flush sebelum crash, block 0 file index jadi nol semua (#62)
    // StorageManager tetap harus bisa dibuka biar FRM bisa recovery, isi index nyusul lewat rebuild
    @Test
    void indexWithBlankMetaAfterCrashOpensAndIsRebuilt() throws Exception {
        createItems();
        insertItems();
        storage.createIndex("idx_items_id", "items", "id");
        storage.flushBuffer();
        overwriteFirstBlock(directory.resolve("idx_items_id.idx"), new byte[4096]);

        StorageManager restarted = open();
        // redo FRM jalan duluan sebelum rebuild dan ikut ngubah index, index yang meta nya nol harus tetap bisa dipakai
        restarted.writeBlock(new DataWrite("items", item(ROWS + 1, label("a", ROWS + 1)), List.of()));
        restarted.rebuildIndexesAndReloadCatalog();

        assertEquals(ROWS + 1, scan(restarted).size());
        for (Row row : scan(restarted)) {
            assertEquals(List.of(row.rowId()), restarted.searchIndex("idx_items_id", row.values().get(0)));
        }
    }

    // file index yang isinya bukan nol tapi bukan index itu rusak beneran, bukan sisa crash, jadi tetap ditolak
    @Test
    void indexWithGarbageMetaIsStillRejected() throws Exception {
        createItems();
        insertItems();
        storage.createIndex("idx_items_id", "items", "id");
        storage.flushBuffer();
        byte[] garbage = new byte[4096];
        java.util.Arrays.fill(garbage, (byte) 7);
        overwriteFirstBlock(directory.resolve("idx_items_id.idx"), garbage);

        StorageException e = assertThrows(StorageException.class, this::open);
        assertEquals(StorageErrorCode.INVALID_BLOCK, e.getCode());
    }

    private static void overwriteFirstBlock(Path file, byte[] block) throws Exception {
        try (java.nio.channels.FileChannel channel = java.nio.channels.FileChannel.open(file,
                java.nio.file.StandardOpenOption.WRITE)) {
            channel.write(java.nio.ByteBuffer.wrap(block), 0);
        }
    }

    // slot dipesan yang dilepas normal ga ninggalin baris yang keliatan
    @Test
    void releasedReservationExposesNothing() {
        createItems();
        insertItems();
        RowId reserved = storage.reserveRowId("items", List.of(500, label("r", 500)));

        assertEquals(ROWS, scan(storage).size());
        storage.releaseRowId(reserved);

        assertEquals(ROWS, scan(storage).size());
    }
}
