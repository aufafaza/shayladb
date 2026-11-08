package mdbms.storage.index;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.IndexType;
import mdbms.models.schema.TableSchema;
import mdbms.storage.block.BlockManager;
import mdbms.storage.block.BufferPool;
import mdbms.storage.catalog.CatalogManager;
import mdbms.storage.crud.RowIterator;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

// test IndexManager dengan CatalogManager dan BufferPool asli di folder sementara
class IndexManagerTest {

    private static final TableSchema USERS = new TableSchema("users", List.of(
            new ColumnDefinition("id", DataType.INTEGER, 0, false),
            new ColumnDefinition("name", DataType.VARCHAR, 10, true),
            new ColumnDefinition("gpa", DataType.FLOAT, 0, true)),
            List.of("id"), List.of());

    @TempDir Path directory;
    private CatalogManager catalog;
    private BufferPool pool;
    private IndexManager manager;

    // katalog berisi tabel users, buffer kecil, dan FRM palsu yang selalu sukses
    @BeforeEach
    void setUp() {
        catalog = new CatalogManager(directory.toString());
        catalog.saveSchema(USERS);
        pool = newPool();
        manager = new IndexManager(catalog, pool, 4);
    }

    // buffer pool baru di folder yang sama dengan FRM palsu
    private BufferPool newPool() {
        BufferPool fresh = new BufferPool(new BlockManager(directory.toString()), 8);
        fresh.attachRecoveryManager((IFailureRecoveryManager) Proxy.newProxyInstance(
                IFailureRecoveryManager.class.getClassLoader(),
                new Class<?>[] {IFailureRecoveryManager.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? true : null));
        return fresh;
    }

    // baris users lengkap di slot tertentu
    private static Row user(int slot, Integer id, String name, Float gpa) {
        return new Row(new RowId("users", 0, slot), Arrays.asList(id, name, gpa));
    }

    // RowIterator sederhana dari list buat ngisi index awal
    private static RowIterator rows(List<Row> rows) {
        Iterator<Row> iterator = rows.iterator();
        return new RowIterator() {
            @Override public boolean hasNext() { return iterator.hasNext(); }
            @Override public Row next() { return iterator.next(); }
            @Override public void close() {}
        };
    }

    // pastiin aksi gagal dengan kode error tertentu
    private static void assertCode(StorageErrorCode code, Executable action) {
        assertEquals(code, assertThrows(StorageException.class, action).getCode());
    }

    // index baru langsung keisi dari baris lama dan tercatat di katalog
    @Test
    void createIndexBuildsFromExistingRows() {
        List<Row> existing = new ArrayList<>();
        for (int i = 0; i < 100; i++) existing.add(user(i, i, "u" + (i % 10), 3.0f));

        IndexInfo info = manager.createIndex("idx_users_name", USERS, "name", rows(existing));

        assertEquals(new IndexInfo("idx_users_name", "users", "name", IndexType.BPLUS_TREE), info);
        assertEquals(List.of(info), catalog.getIndexes("users"));
        assertEquals(10, manager.search("idx_users_name", "u3").size());
        assertEquals(30, manager.searchRange("idx_users_name", "u0", "u2", true, true).size());
    }

    // nama dobel, nama kosong, dan kolom yang ga ada ditolak
    @Test
    void createIndexValidatesInput() {
        manager.createIndex("idx_users_id", USERS, "id", rows(List.of()));

        assertCode(StorageErrorCode.INDEX_ALREADY_EXISTS,
                () -> manager.createIndex("idx_users_id", USERS, "gpa", rows(List.of())));
        assertCode(StorageErrorCode.SCHEMA_VIOLATION, () -> manager.createIndex(" ", USERS, "id", rows(List.of())));
        assertCode(StorageErrorCode.COLUMN_NOT_FOUND,
                () -> manager.createIndex("idx_users_age", USERS, "age", rows(List.of())));
    }

    // kalau pengisian awal gagal, katalog dan file index bersih lagi
    @Test
    void failedBuildLeavesNothingBehind() {
        List<Row> existing = List.of(user(0, 1, "ok", 1.0f), user(1, 2, "kepanjangan", 1.0f));

        assertCode(StorageErrorCode.SCHEMA_VIOLATION,
                () -> manager.createIndex("idx_users_name", USERS, "name", rows(existing)));

        assertTrue(catalog.getIndexes("users").isEmpty());
        assertTrue(Files.notExists(directory.resolve("idx_users_name.idx")));
        assertCode(StorageErrorCode.INDEX_NOT_FOUND, () -> manager.search("idx_users_name", "ok"));
    }

    // insert, update, dan delete baris bikin semua index tabelnya ikut berubah
    @Test
    void rowChangesKeepIndexesInSync() {
        manager.createIndex("idx_users_id", USERS, "id", rows(List.of()));
        manager.createIndex("idx_users_gpa", USERS, "gpa", rows(List.of()));
        Row alice = user(0, 1, "alice", 3.5f);
        Row bob = user(1, 2, "bob", null);

        manager.onInsert(alice, USERS);
        manager.onInsert(bob, USERS);
        assertEquals(List.of(alice.rowId()), manager.search("idx_users_id", 1));
        assertEquals(List.of(alice.rowId()), manager.search("idx_users_gpa", 3.5));

        Row movedAlice = user(5, 1, "alice", 3.75f);
        manager.onUpdate(alice, movedAlice, USERS);
        assertEquals(List.of(movedAlice.rowId()), manager.search("idx_users_id", 1));
        assertTrue(manager.search("idx_users_gpa", 3.5f).isEmpty());
        assertEquals(List.of(movedAlice.rowId()), manager.search("idx_users_gpa", 3.75f));

        manager.onDelete(bob, USERS);
        assertTrue(manager.search("idx_users_id", 2).isEmpty());
    }

    // dropIndex ngapus dari katalog, file, dan daftar index
    @Test
    void dropIndexRemovesEverything() {
        manager.createIndex("idx_users_id", USERS, "id", rows(List.of(user(0, 1, "a", 1.0f))));

        manager.dropIndex("idx_users_id");

        assertTrue(catalog.getIndexes("users").isEmpty());
        assertTrue(Files.notExists(directory.resolve("idx_users_id.idx")));
        assertCode(StorageErrorCode.INDEX_NOT_FOUND, () -> manager.search("idx_users_id", 1));
        assertCode(StorageErrorCode.INDEX_NOT_FOUND, () -> manager.dropIndex("idx_users_id"));
    }

    // dropIndexesOf ngapus semua index satu tabel
    @Test
    void dropIndexesOfTable() {
        manager.createIndex("idx_users_id", USERS, "id", rows(List.of()));
        manager.createIndex("idx_users_name", USERS, "name", rows(List.of()));

        manager.dropIndexesOf("users");

        assertTrue(catalog.getIndexes("users").isEmpty());
        assertCode(StorageErrorCode.INDEX_NOT_FOUND, () -> manager.search("idx_users_name", "a"));
    }

    // clear ngosongin index tabel tapi index nya tetap ada
    @Test
    void clearEmptiesIndexesOfTable() {
        manager.createIndex("idx_users_id", USERS, "id", rows(List.of(user(0, 1, "a", 1.0f))));

        manager.clear("users");

        assertTrue(manager.search("idx_users_id", 1).isEmpty());
        assertEquals(1, catalog.getIndexes("users").size());
    }

    // setelah restart index dibuka lagi dari katalog dan isinya masih ada
    @Test
    void loadAllReopensIndexesAfterRestart() {
        List<Row> existing = new ArrayList<>();
        for (int i = 0; i < 50; i++) existing.add(user(i, i, "n" + i, 2.0f));
        manager.createIndex("idx_users_id", USERS, "id", rows(existing));
        pool.flushBuffer();

        IndexManager restarted = new IndexManager(new CatalogManager(directory.toString()), newPool(), 4);
        restarted.loadAll();

        assertEquals(List.of(new RowId("users", 0, 42)), restarted.search("idx_users_id", 42));
        assertEquals(10, restarted.searchRange("idx_users_id", 10, 20, true, false).size());
    }

    // override degree di bawah 3 ditolak
    @Test
    void rejectsInvalidDegreeOverride() {
        assertCode(StorageErrorCode.SCHEMA_VIOLATION, () -> new IndexManager(catalog, pool, 2));
    }

    // bikin index id dan name, name diurutin belakangan jadi gagal setelah index id udah keubah
    private void createIdAndNameIndexes() {
        manager.createIndex("idx_a_id", USERS, "id", rows(List.of()));
        manager.createIndex("idx_b_name", USERS, "name", rows(List.of()));
    }

    // insert yang gagal di index kedua ga ninggalin entri di index pertama
    @Test
    void failedInsertRollsBackEarlierIndexes() {
        createIdAndNameIndexes();

        assertCode(StorageErrorCode.SCHEMA_VIOLATION, () -> manager.onInsert(user(0, 1, "kepanjangan", 1.0f), USERS));

        assertTrue(manager.search("idx_a_id", 1).isEmpty());
    }

    // update yang gagal di index kedua balikin index pertama ke entri lama
    @Test
    void failedUpdateRollsBackEarlierIndexes() {
        createIdAndNameIndexes();
        Row alice = user(0, 1, "alice", 1.0f);
        manager.onInsert(alice, USERS);

        assertCode(StorageErrorCode.SCHEMA_VIOLATION,
                () -> manager.onUpdate(alice, user(3, 10, "kepanjangan", 1.0f), USERS));

        assertEquals(List.of(alice.rowId()), manager.search("idx_a_id", 1));
        assertTrue(manager.search("idx_a_id", 10).isEmpty());
        assertEquals(List.of(alice.rowId()), manager.search("idx_b_name", "alice"));
    }

    // delete yang gagal di index kedua masukin lagi entri yang udah kehapus di index pertama
    @Test
    void failedDeleteRollsBackEarlierIndexes() {
        createIdAndNameIndexes();
        Row alice = user(0, 1, "alice", 1.0f);
        manager.onInsert(alice, USERS);

        assertCode(StorageErrorCode.SCHEMA_VIOLATION,
                () -> manager.onDelete(user(0, 1, "kepanjangan", 1.0f), USERS));

        assertEquals(List.of(alice.rowId()), manager.search("idx_a_id", 1));
    }
}
