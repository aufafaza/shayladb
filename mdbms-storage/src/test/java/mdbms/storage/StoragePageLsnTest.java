package mdbms.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

// test #54 bagian SM, LSN dari CCM harus nempel di halaman yang berubah
// jadi tiap halaman itu mau ditulis ke disk, WAL di flush dulu sampai LSN tersebut
class StoragePageLsnTest {

    private static final int SMALL_BUFFER = 3;
    private static final String TABLE = "notes";

    @TempDir Path directory;
    private final List<Long> requestedLsns = new ArrayList<>();
    private StorageManager storage;

    @BeforeEach
    void setUp() {
        storage = new StorageManager(directory.toString(), SMALL_BUFFER, 4);
        storage.attachRecoveryManager((IFailureRecoveryManager) Proxy.newProxyInstance(
                IFailureRecoveryManager.class.getClassLoader(),
                new Class<?>[] {IFailureRecoveryManager.class},
                (p, method, args) -> {
                    if (method.getName().equals("flushLogUpTo")) {
                        requestedLsns.add((Long) args[0]);
                        return true;
                    }
                    return method.getReturnType() == boolean.class ? true : null;
                }));
        storage.updateTableSchema(new TableSchema(TABLE, List.of(
                new ColumnDefinition("id", DataType.INTEGER, 0, false),
                new ColumnDefinition("body", DataType.VARCHAR, 3500, false)),
                List.of("id"), List.of()));
    }

    // baris dimasukin lewat jalur QP, pesan RowId dulu baru diisi dengan LSN log INSERT nya
    private RowId insert(int id, String body, long lsn) {
        List<Object> values = List.of(id, body);
        RowId rowId = storage.reserveRowId(TABLE, values);
        storage.insertAt(rowId, values, lsn);
        return rowId;
    }

    // flush semua halaman lalu balikin LSN yang diminta ke WAL selama flush itu
    private List<Long> flushAndCollect() {
        requestedLsns.clear();
        storage.flushBuffer();
        return new ArrayList<>(requestedLsns);
    }

    @Test
    void insertAtMarksPageWithItsLsn() {
        insert(1, "a", 101);

        assertTrue(flushAndCollect().contains(101L));
    }

    @Test
    void updateInPlaceMarksPageWithItsLsn() {
        RowId rowId = insert(1, "a", 101);
        flushAndCollect();

        assertTrue(storage.updateInPlace(rowId, List.of(1, "b"), 202));

        assertEquals(List.of(202L), flushAndCollect());
    }

    @Test
    void deleteMarksPageWithItsLsn() {
        RowId rowId = insert(1, "a", 101);
        flushAndCollect();

        storage.deleteByRowId(rowId, 303);

        assertEquals(List.of(303L), flushAndCollect());
    }

    // update yang bikin baris pindah halaman nandain halaman lama dan halaman tujuan dengan LSN yang sama
    @Test
    void relocatingUpdateMarksBothPagesWithItsLsn() {
        RowId moving = insert(1, "a".repeat(1900), 101);
        insert(2, "b".repeat(1900), 102);
        flushAndCollect();

        Row moved = storage.updateByRowId(moving, List.of(1, "c".repeat(3000)), 404);

        assertNotEquals(moving.blockNumber(), moved.rowId().blockNumber());
        assertEquals(List.of(404L, 404L), flushAndCollect());
    }

    // halaman yang di evict karena buffer penuh juga minta WAL di flush sampai LSN nya dulu
    // halaman mana yang jadi korban tergantung urutan LRU, jadi yang dicek tiap LSN yang diminta pas eviction itu LSN halaman asli
    @Test
    void evictionForcesWalThroughPageLsn() {
        List<Long> pageLsns = List.of(501L, 502L, 503L, 504L, 505L);
        for (int i = 0; i < pageLsns.size(); i++) {
            insert(i + 1, "x".repeat(3000), pageLsns.get(i));
        }

        assertFalse(requestedLsns.isEmpty(), "buffer 3 frame dengan 5 halaman harus sempat evict");
        assertTrue(pageLsns.containsAll(requestedLsns), "eviction minta LSN yang bukan punya halaman: " + requestedLsns);

        List<Long> forced = new ArrayList<>(requestedLsns);
        forced.addAll(flushAndCollect());
        assertTrue(forced.containsAll(pageLsns), "tiap halaman harus minta WAL sampai LSN nya: " + forced);
    }

    // jalur tanpa LSN kayak redo undo FRM tetap jalan dan ga minta flush WAL buat LSN palsu
    @Test
    void pathsWithoutLsnStillWork() {
        List<Object> values = List.of(1, "a");
        RowId rowId = storage.reserveRowId(TABLE, values);
        storage.insertAt(rowId, values);

        assertFalse(flushAndCollect().stream().anyMatch(lsn -> lsn > 0));
        assertEquals(values, storage.fetchByRowId(rowId).values());
    }
}
