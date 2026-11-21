package mdbms.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.access.DataRetrieval;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.storage.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// bagian SM dari #55, UPDATE yang harus pindah halaman ketahuan dulu sebelum ada yang diubah
// relokasi nya sendiri dijalanin pemanggil lewat reserveRowId, insertAt, deleteByRowId setelah dikunci dan dicatat
class StorageRelocationTest {
    private static final String TABLE = "users";
    private static final String INDEX = "idx_users_id";
    private static final int ROWS = 30;

    @TempDir Path directory;
    private StorageManager storage;

    @BeforeEach
    void setUp() {
        storage = open();
        storage.updateTableSchema(new TableSchema(TABLE, List.of(
                new ColumnDefinition("id", DataType.INTEGER, 0, false),
                new ColumnDefinition("name", DataType.VARCHAR, 200, false)),
                List.of("id"), List.of()));
        storage.createIndex(INDEX, TABLE, "id");
        // nama 120 karakter biar halaman pertama penuh dan nama 200 karakter ga muat lagi di tempat
        for (int id = 1; id <= ROWS; id++) {
            RowId rowId = storage.reserveRowId(TABLE, values(id, 120));
            storage.insertAt(rowId, values(id, 120));
        }
    }

    @Test
    void canUpdateInPlaceTellsWhetherTheNewValueFitsWithoutChangingAnything() throws Exception {
        RowId first = rowIdOf(1);
        List<Row> before = rows();
        storage.flushBuffer();
        byte[] fileBefore = Files.readAllBytes(directory.resolve(TABLE + ".tbl"));

        assertTrue(storage.canUpdateInPlace(first, values(1, 120)));
        assertTrue(storage.canUpdateInPlace(first, values(1, 10)));
        assertFalse(storage.canUpdateInPlace(first, values(1, 200)));

        assertEquals(before, rows());
        storage.flushBuffer();
        assertTrue(Arrays.equals(fileBefore, Files.readAllBytes(directory.resolve(TABLE + ".tbl"))));
    }

    @Test
    void updateInPlaceChangesHeapAndIndexWhenItFits() {
        RowId first = rowIdOf(1);

        assertTrue(storage.updateInPlace(first, values(1, 10)));

        assertEquals(values(1, 10), storage.fetchByRowId(first).values());
        assertEquals(List.of(first), storage.searchIndex(INDEX, 1));
        assertIndexMatchesHeap();
    }

    @Test
    void updateInPlaceRefusesInsteadOfMovingTheRow() {
        RowId first = rowIdOf(1);
        List<Row> before = rows();

        assertFalse(storage.updateInPlace(first, values(1, 200)));

        // ga ada baris yang pindah atau berubah, beda sama updateByRowId yang langsung mindahin
        assertEquals(before, rows());
        assertEquals(List.of(first), storage.searchIndex(INDEX, 1));
        assertIndexMatchesHeap();
    }

    @Test
    void updateInPlaceCanStillRefuseWhenSpaceIsTakenAfterTheCheck() {
        // insert nyari ruang mulai dari halaman terakhir, jadi pakai baris yang ada di halaman terakhir
        RowId last = rowIdOf(ROWS);
        List<Object> longer = values(ROWS, 200);
        List<Object> before = storage.fetchByRowId(last).values();
        assertTrue(storage.canUpdateInPlace(last, longer));

        // ruang yang tadi cukup keburu dipakai baris baru di halaman yang sama
        int id = 100;
        while (storage.canUpdateInPlace(last, longer)) {
            RowId filler = storage.reserveRowId(TABLE, values(id, 20));
            assertEquals(last.blockNumber(), filler.blockNumber(), "baris pengisi harus masuk halaman yang sama");
            storage.insertAt(filler, values(id++, 20));
        }

        assertFalse(storage.updateInPlace(last, longer));
        assertEquals(before, storage.fetchByRowId(last).values());
        assertEquals(List.of(last), storage.searchIndex(INDEX, ROWS));
        assertIndexMatchesHeap();
    }

    @Test
    void relocationStepsKeepIndexValidEvenWhileBothLocationsExist() {
        RowId oldRid = rowIdOf(1);
        List<Object> moved = values(1, 200);
        assertFalse(storage.canUpdateInPlace(oldRid, moved));

        RowId newRid = storage.reserveRowId(TABLE, moved);
        assertNotEquals(oldRid.blockNumber(), newRid.blockNumber());

        // index non unique, jadi pas dua lokasi sempat punya key yang sama ga ada konflik
        storage.insertAt(newRid, moved);
        assertEquals(sorted(List.of(oldRid, newRid)), sorted(storage.searchIndex(INDEX, 1)));

        storage.deleteByRowId(oldRid);
        assertEquals(List.of(newRid), storage.searchIndex(INDEX, 1));
        assertEquals(moved, storage.fetchByRowId(newRid).values());
        assertEquals(ROWS, rows().size());
        assertIndexMatchesHeap();

        // tetap sama abis flush dan dibuka ulang
        storage.flushBuffer();
        StorageManager reopened = open();
        assertEquals(moved, reopened.fetchByRowId(newRid).values());
        assertEquals(List.of(newRid), reopened.searchIndex(INDEX, 1));
    }

    @Test
    void undoingARelocationRestoresTheRowAtItsOriginalRowId() {
        RowId oldRid = rowIdOf(1);
        List<Object> original = storage.fetchByRowId(oldRid).values();
        List<Object> moved = values(1, 200);
        RowId newRid = storage.reserveRowId(TABLE, moved);
        storage.insertAt(newRid, moved);
        storage.deleteByRowId(oldRid);

        // urutan undo relokasi FRM, hapus di lokasi baru, lepas pesanan kalau masih ada, taruh lagi isi lama di lokasi lama
        // lokasi baru yang udah dihapus masih nahan ruangnya, jadi boleh dilepas sekali, lepas kedua kalinya ditolak
        storage.deleteByRowId(newRid);
        storage.releaseRowId(newRid);
        StorageException notReserved = assertThrows(StorageException.class, () -> storage.releaseRowId(newRid));
        assertTrue(notReserved.getMessage().toLowerCase().contains("not reserved"),
                "pesan nya harus cocok sama RecordApplier.isNotReservedError");
        storage.insertAt(oldRid, original);

        assertEquals(original, storage.fetchByRowId(oldRid).values());
        assertEquals(List.of(oldRid), storage.searchIndex(INDEX, 1));
        assertEquals(ROWS, rows().size());
        assertIndexMatchesHeap();

        // undo nya diulang tetap aman
        assertNull(storage.deleteByRowId(newRid));
        storage.insertAt(oldRid, original);
        assertEquals(ROWS, rows().size());
        assertIndexMatchesHeap();
    }

    @Test
    void failureAfterReservingTheDestinationLeavesNothingBehind() {
        RowId oldRid = rowIdOf(1);
        List<Row> before = rows();
        RowId newRid = storage.reserveRowId(TABLE, values(1, 200));

        // gagal sebelum log ditulis, pemanggil cukup lepas pesanannya
        storage.releaseRowId(newRid);

        assertEquals(before, rows());
        assertEquals(List.of(oldRid), storage.searchIndex(INDEX, 1));
        assertEquals(0, storage.releaseOrphanReservations());
    }

    @Test
    void missingRowsAndInvalidValuesAreRejected() {
        RowId first = rowIdOf(1);
        storage.deleteByRowId(first);

        assertThrows(StorageException.class, () -> storage.canUpdateInPlace(first, values(1, 10)));
        assertThrows(StorageException.class, () -> storage.updateInPlace(first, values(1, 10)));

        RowId second = rowIdOf(2);
        List<Object> wrongType = List.of("bukan angka", "x");
        assertThrows(RuntimeException.class, () -> storage.canUpdateInPlace(second, wrongType));
        assertThrows(RuntimeException.class, () -> storage.updateInPlace(second, wrongType));
    }

    // FRM tiruan yang selalu nerima flush WAL, WAL nya sendiri bukan bagian yang dites di sini
    private StorageManager open() {
        StorageManager manager = new StorageManager(directory.toString(), 8, 4);
        manager.attachRecoveryManager((IFailureRecoveryManager) Proxy.newProxyInstance(
                IFailureRecoveryManager.class.getClassLoader(),
                new Class<?>[] {IFailureRecoveryManager.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? true : null));
        return manager;
    }

    private static List<Object> values(int id, int nameLength) {
        StringBuilder name = new StringBuilder("user" + id + "-");
        while (name.length() < nameLength) name.append('x');
        return List.of(id, name.toString());
    }

    private List<Row> rows() {
        List<Row> rows = new ArrayList<>(storage.readBlock(new DataRetrieval(TABLE, List.of(), List.of())).rows());
        rows.sort(Comparator.comparing(row -> (Integer) row.values().get(0)));
        return rows;
    }

    private RowId rowIdOf(int id) {
        return rows().stream().filter(row -> row.values().get(0).equals(id)).findFirst().orElseThrow().rowId();
    }

    private static List<RowId> sorted(List<RowId> rowIds) {
        List<RowId> copy = new ArrayList<>(rowIds);
        copy.sort(Comparator.comparing(RowId::blockNumber).thenComparing(RowId::slot));
        return copy;
    }

    // tiap baris heap ketemu lewat index di RowId yang sama dan index ga punya entri lebih
    private void assertIndexMatchesHeap() {
        List<Row> rows = rows();
        for (Row row : rows) {
            assertEquals(List.of(row.rowId()), storage.searchIndex(INDEX, row.values().get(0)), "index id=" + row.values().get(0));
        }
        assertEquals(rows.size(), storage.searchIndexRange(INDEX, Integer.MIN_VALUE, Integer.MAX_VALUE, true, true).size());
    }
}
