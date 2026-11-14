package mdbms.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.access.BufferStats;
import mdbms.models.access.DataDeletion;
import mdbms.models.access.DataRetrieval;
import mdbms.models.access.DataWrite;
import mdbms.models.data.ComparisonOperator;
import mdbms.models.data.Condition;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.IndexType;
import mdbms.models.schema.TableSchema;
import mdbms.storage.block.Block;
import mdbms.storage.block.BlockManager;
import mdbms.storage.block.BufferPool;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.api.io.TempDir;

// test StorageManager hasil integrasi SM1 sampai SM4, CRUD asli lewat HeapFile dan buffer lewat BufferPool
class StorageManagerTest {

    private static final RowId MISSING_ROW = new RowId("ghost", 0, 0);
    private static final Condition ID_IS_1 = new Condition("id", ComparisonOperator.EQ, 1);

    private StorageManager storage;
    @TempDir Path directory;

    @BeforeEach
    void setUp() {
        storage = new StorageManager(directory.toString(), 16, 4);
    }

    // bikin tabel users dengan kolom INTEGER, VARCHAR, dan FLOAT
    private void createUsers() {
        storage.updateTableSchema(new TableSchema("users", List.of(
                new ColumnDefinition("id", DataType.INTEGER, 0, false),
                new ColumnDefinition("name", DataType.VARCHAR, 20, false),
                new ColumnDefinition("gpa", DataType.FLOAT, 0, true)),
                List.of("id"), List.of()));
    }

    // susun map kolom ke nilai sesuai urutan kolom users
    private static Map<String, Object> user(int id, String name, Float gpa) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", id);
        values.put("name", name);
        values.put("gpa", gpa);
        return values;
    }

    // ambil semua baris users tanpa kondisi
    private List<Row> readUsers() {
        return storage.readBlock(new DataRetrieval("users", List.of(), List.of())).rows();
    }

    // pastiin aksi gagal dengan StorageException kode tertentu
    private static void assertCode(StorageErrorCode code, Executable action) {
        StorageException e = assertThrows(StorageException.class, action);
        assertEquals(code, e.getCode());
    }

    // FRM apa pun bisa dipasang ke StorageManager
    @Test
    void attachRecoveryManagerAcceptsImplementation() {
        IFailureRecoveryManager frm = (IFailureRecoveryManager) Proxy.newProxyInstance(
                IFailureRecoveryManager.class.getClassLoader(),
                new Class<?>[] {IFailureRecoveryManager.class},
                (proxy, method, args) -> null);

        assertDoesNotThrow(() -> storage.attachRecoveryManager(frm));
    }

    // tabel yang ga ada selalu kasih TABLE_NOT_FOUND, kecuali getTableSchema yang balikin null buat QP
    @Test
    void missingTableIsReportedAsTableNotFound() {
        assertNull(storage.getTableSchema("ghost"));
        assertTrue(storage.listTable().isEmpty());

        assertCode(StorageErrorCode.TABLE_NOT_FOUND,
                () -> storage.readBlock(new DataRetrieval("ghost", List.of(), List.of())));
        assertCode(StorageErrorCode.TABLE_NOT_FOUND,
                () -> storage.writeBlock(new DataWrite("ghost", Map.of("id", 1), List.of())));
        assertCode(StorageErrorCode.TABLE_NOT_FOUND,
                () -> storage.deleteBlock(new DataDeletion("ghost", List.of())));
        assertCode(StorageErrorCode.TABLE_NOT_FOUND, () -> storage.fetchByRowId(MISSING_ROW));

        // reserveRowId rejects a missing table
        assertCode(StorageErrorCode.TABLE_NOT_FOUND, () -> storage.reserveRowId("ghost", List.of(1)));

        // releaseRowId rejects a RowId from a missing table
        assertCode(StorageErrorCode.TABLE_NOT_FOUND, () -> storage.releaseRowId(MISSING_ROW));

        assertCode(StorageErrorCode.TABLE_NOT_FOUND, () -> storage.insertAt(MISSING_ROW, List.of(1)));
        assertCode(StorageErrorCode.TABLE_NOT_FOUND, () -> storage.updateByRowId(MISSING_ROW, List.of(1)));
        assertCode(StorageErrorCode.TABLE_NOT_FOUND, () -> storage.deleteByRowId(MISSING_ROW));
        assertCode(StorageErrorCode.TABLE_NOT_FOUND, () -> storage.getIndexes("ghost"));
        assertCode(StorageErrorCode.TABLE_NOT_FOUND, () -> storage.getStatistics("ghost"));
        assertCode(StorageErrorCode.TABLE_NOT_FOUND, () -> storage.dropTable("ghost"));
    }

    // tabel baru kebaca lagi lewat katalog
    @Test
    void createdTableAppearsInCatalog() {
        createUsers();

        assertEquals(List.of("users"), storage.listTable());
        assertEquals("users", storage.getTableSchema("users").tableName());
        assertTrue(storage.getIndexes("users").isEmpty());
    }

    // alur lengkap insert, baca, update, lalu hapus pakai kondisi
    @Test
    void insertReadUpdateDeleteRoundTrip() {
        createUsers();
        RowId first = storage.writeBlock(new DataWrite("users", user(1, "alice", 3.5f), List.of())).get(0);
        storage.writeBlock(new DataWrite("users", user(2, "bob", null), List.of()));

        assertEquals(List.of(1, "alice", 3.5f), storage.fetchByRowId(first).values());
        assertEquals(2, readUsers().size());

        Row updated = storage.updateByRowId(first, List.of(1, "alicia", 3.75f));
        assertEquals(List.of(1, "alicia", 3.75f), storage.fetchByRowId(updated.rowId()).values());

        List<Row> matched = storage.readBlock(
                new DataRetrieval("users", List.of("name"), List.of(ID_IS_1))).rows();
        assertEquals(1, matched.size());
        assertEquals(List.of("alicia"), matched.get(0).values());

        assertEquals(1, storage.deleteBlock(new DataDeletion("users", List.of(ID_IS_1))));
        List<Row> remaining = readUsers();
        assertEquals(1, remaining.size());
        assertEquals(Arrays.asList(2, "bob", null), remaining.get(0).values());
    }

    // reserveRowId can prepare a RowId before the row is written
    @Test
    void reserveRowIdThenInsertAtStoresRow() {
        createUsers();
        List<Object> values = Arrays.asList(1, "alice", 3.5f);
        RowId rowId = storage.reserveRowId("users", values);

        assertTrue(readUsers().isEmpty());

        storage.insertAt(rowId, values);
        Row row = storage.fetchByRowId(rowId);

        assertEquals(rowId, row.rowId());
        assertEquals(values, row.values());
        assertEquals(1, readUsers().size());
    }

    // releaseRowId cancels a reservation and the same RowId can be used again
    @Test
    void releaseRowIdCancelsReservation() {
        createUsers();
        List<Object> values = Arrays.asList(1, "alice", 3.5f);
        RowId rowId = storage.reserveRowId("users", values);
        storage.releaseRowId(rowId);

        assertTrue(readUsers().isEmpty());

        storage.insertAt(rowId, values);
        Row row = storage.fetchByRowId(rowId);

        assertEquals(rowId, row.rowId());
        assertEquals(values, row.values());
    }

    // update does not process the same row again after it moves to another block
    @Test
    void updateDoesNotProcessMovedRowTwice() {
        storage.updateTableSchema(new TableSchema(
                "large_users",
                List.of(
                        new ColumnDefinition("id", DataType.INTEGER, 0, false),
                        new ColumnDefinition("payload", DataType.VARCHAR, 3500, false)),
                List.of("id"),
                List.of()));

        Map<String, Object> first = new LinkedHashMap<>();
        first.put("id", 1);
        first.put("payload", "a");

        Map<String, Object> second = new LinkedHashMap<>();
        second.put("id", 2);
        second.put("payload", "b".repeat(1800));

        storage.writeBlock(new DataWrite("large_users", first, List.of()));
        storage.writeBlock(new DataWrite("large_users", second, List.of()));

        Map<String, Object> update = Map.of("payload", "c".repeat(3000));
        List<RowId> updated = storage.writeBlock(
                new DataWrite("large_users", update, List.of(ID_IS_1)));

        assertEquals(1, updated.size());

        List<Row> rows = storage.readBlock(
                new DataRetrieval("large_users", List.of(), List.of())).rows();

        assertEquals(2, rows.size());

        List<Row> matched = storage.readBlock(
                new DataRetrieval("large_users", List.of(), List.of(ID_IS_1))).rows();

        assertEquals(1, matched.size());
        assertEquals("c".repeat(3000), matched.get(0).values().get(1));
    }

    // kolom FLOAT cuma terima Float, Double dari luar ditolak
    @Test
    void floatColumnRejectsDouble() {
        createUsers();
        Map<String, Object> values = user(1, "alice", null);
        values.put("gpa", 3.5);

        assertCode(StorageErrorCode.SCHEMA_VIOLATION,
                () -> storage.writeBlock(new DataWrite("users", values, List.of())));
        assertTrue(readUsers().isEmpty());
    }

    // checks VARCHAR length using UTF-8 bytes instead of character count
    @Test
    void varcharLengthUsesUtf8Bytes() {
        createUsers();

        Map<String, Object> values = user(1, "é".repeat(11), 3.5f);

        assertCode(StorageErrorCode.SCHEMA_VIOLATION, () -> storage.writeBlock(new DataWrite("users", values, List.of())));

        assertTrue(readUsers().isEmpty());
    }

    // FRM palsu yang selalu bilang log udah aman biar flush bisa jalan
    private static IFailureRecoveryManager acceptingFrm() {
        return (IFailureRecoveryManager) Proxy.newProxyInstance(
                IFailureRecoveryManager.class.getClassLoader(),
                new Class<?>[] {IFailureRecoveryManager.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? true : null);
    }

    // index dibuat dari baris lama lalu ikut berubah tiap insert, update, dan delete
    @Test
    void indexFollowsCrud() {
        createUsers();
        storage.writeBlock(new DataWrite("users", user(1, "alice", 3.5f), List.of()));
        RowId bob = storage.writeBlock(new DataWrite("users", user(2, "bob", null), List.of())).get(0);

        IndexInfo info = storage.createIndex("idx_users_id", "users", "id");
        assertEquals(new IndexInfo("idx_users_id", "users", "id", IndexType.BPLUS_TREE), info);
        assertEquals(List.of(info), storage.getIndexes("users"));
        assertEquals(List.of(bob), storage.searchIndex("idx_users_id", 2));

        RowId cici = storage.writeBlock(new DataWrite("users", user(3, "cici", 2.0f), List.of())).get(0);
        assertEquals(List.of(cici), storage.searchIndex("idx_users_id", 3));

        Row moved = storage.updateByRowId(bob, List.of(20, "bob", 1.0f));
        assertTrue(storage.searchIndex("idx_users_id", 2).isEmpty());
        assertEquals(List.of(moved.rowId()), storage.searchIndex("idx_users_id", 20));

        storage.deleteBlock(new DataDeletion("users", List.of(ID_IS_1)));
        assertTrue(storage.searchIndex("idx_users_id", 1).isEmpty());
        assertEquals(List.of(cici, moved.rowId()), storage.searchIndexRange("idx_users_id", null, null, true, true));
    }

    // index ke tabel atau nama yang ga ada kasih kode error yang jelas, drop tabel ikut ngapus index dan file tabelnya
    @Test
    void indexErrorsAndDropTable() {
        assertCode(StorageErrorCode.TABLE_NOT_FOUND, () -> storage.createIndex("idx_ghost_id", "ghost", "id"));
        assertCode(StorageErrorCode.INDEX_NOT_FOUND, () -> storage.searchIndex("idx_users_id", 1));

        createUsers();

        // insert one row to create the table file
        storage.writeBlock(
                new DataWrite("users", user(1, "alice", 3.5f), List.of()));

        storage.createIndex("idx_users_id", "users", "id");
        assertCode(StorageErrorCode.INDEX_ALREADY_EXISTS, () -> storage.createIndex("idx_users_id", "users", "name"));
        assertCode(StorageErrorCode.COLUMN_NOT_FOUND, () -> storage.createIndex("idx_users_age", "users", "age"));

        storage.dropTable("users");
        assertCode(StorageErrorCode.INDEX_NOT_FOUND, () -> storage.searchIndex("idx_users_id", 1));

        // dropping a table must remove its catalog entry and .tbl file
        assertNull(storage.getTableSchema("users"));
        assertFalse(Files.exists(directory.resolve("users.tbl")));
    }

    // setelah flush dan StorageManager dibikin ulang, index dibuka lagi dari katalog
    @Test
    void indexSurvivesRestart() {
        storage.attachRecoveryManager(acceptingFrm());
        createUsers();
        RowId alice = storage.writeBlock(new DataWrite("users", user(1, "alice", 3.5f), List.of())).get(0);
        storage.createIndex("idx_users_gpa", "users", "gpa");
        storage.flushBuffer();

        StorageManager restarted = new StorageManager(directory.toString(), 16, 4);

        assertEquals(List.of(alice), restarted.searchIndex("idx_users_gpa", 3.5));
    }

    // flush tanpa halaman dirty aman dan statistik buffer masih kosong
    @Test
    void bufferStatsStartEmpty() {
        assertDoesNotThrow(storage::flushBuffer);
        assertEquals(new BufferStats(16, 0, 0, 0, 0), storage.getBufferStats());
    }

    // kapasitas buffer nol dan FRM null ditolak
    @Test
    void rejectsInvalidBufferConfiguration() {
        assertThrows(StorageException.class, () -> new StorageManager(directory.toString(), 0, 4));
        assertThrows(StorageException.class, () -> storage.attachRecoveryManager(null));
    }

    // flush dirty nunggu WAL FRM dulu baru nulis ke disk
    @Test
    void delegatesAttachmentFlushAndLiveStatistics() throws Exception {
        Field field = StorageManager.class.getDeclaredField("bufferPool");
        field.setAccessible(true);
        BufferPool pool = (BufferPool) field.get(storage);
        Block block = pool.appendBlock("users.tbl");
        block.data[0] = 42;
        byte[] expected = block.data.clone();
        pool.unpin(block, true, 23);
        assertEquals(new BufferStats(16, 1, 1, 0, 0), storage.getBufferStats());
        StorageException failure = assertThrows(StorageException.class, storage::flushBuffer);
        assertEquals(StorageErrorCode.WAL_FLUSH_FAILED, failure.getCode());

        List<Long> flushed = new ArrayList<>();
        BlockManager disk = new BlockManager(directory.toString());
        IFailureRecoveryManager frm = (IFailureRecoveryManager) Proxy.newProxyInstance(
                IFailureRecoveryManager.class.getClassLoader(),
                new Class<?>[] {IFailureRecoveryManager.class},
                (proxy, method, args) -> {
                    assertEquals("flushLogUpTo", method.getName());
                    assertEquals(0, disk.readBlock("users.tbl", 0)[0]);
                    flushed.add((Long) args[0]);
                    return true;
                });
        storage.attachRecoveryManager(frm);
        storage.flushBuffer();
        assertEquals(List.of(23L), flushed);
        assertArrayEquals(expected, disk.readBlock("users.tbl", 0));
        assertEquals(0, storage.getBufferStats().dirtyBlocks());
    }

    // adds an index that always fails during insert
    @SuppressWarnings("unchecked")
    private void addFailingInsertIndex() throws Exception {
        Field managerField = StorageManager.class.getDeclaredField("indexManager");
        managerField.setAccessible(true);
        mdbms.storage.index.IndexManager manager =
                (mdbms.storage.index.IndexManager) managerField.get(storage);

        Field indexesField = mdbms.storage.index.IndexManager.class.getDeclaredField("indexes");
        indexesField.setAccessible(true);
        Map<String, mdbms.storage.index.Index> indexes =
                (Map<String, mdbms.storage.index.Index>) indexesField.get(manager);

        mdbms.storage.index.Index failingIndex = new mdbms.storage.index.Index() {
            private final IndexInfo info =
                    new IndexInfo("idx_fail", "users", "id", IndexType.BPLUS_TREE);

            @Override
            public void create() {}

            @Override
            public void insert(Object key, RowId rowId) {
                throw new RuntimeException("forced index failure");
            }

            @Override
            public void delete(Object key, RowId rowId) {}

            @Override
            public List<RowId> search(Object key) {
                return List.of();
            }

            @Override
            public List<RowId> searchRange(
                    Object low, Object high, boolean lowInclusive, boolean highInclusive) {
                return List.of();
            }

            @Override
            public void clear() {}

            @Override
            public void destroy() {}

            @Override
            public IndexInfo getInfo() {
                return info;
            }
        };

        indexes.put("idx_fail", failingIndex);
    }

    // failed index maintenance removes the row already inserted into the heap
    @Test
    void failedIndexMaintenanceRollsBackInsert() throws Exception {
        createUsers();
        addFailingInsertIndex();

        RuntimeException error = assertThrows(
                RuntimeException.class,
                () -> storage.writeBlock(
                        new DataWrite("users", user(1, "alice", 3.5f), List.of())));

        assertEquals("forced index failure", error.getMessage());
        assertTrue(readUsers().isEmpty());
    }

    // dropping a table clears its cached statistics
    @Test
    void dropTableInvalidatesStatisticsCache() {
        createUsers();
        storage.writeBlock(new DataWrite("users", user(1, "alice", 3.5f), List.of()));

        assertEquals(1, storage.getStatistics("users").nr());

        storage.dropTable("users");
        createUsers();

        assertEquals(0, storage.getStatistics("users").nr());
        assertEquals(0, storage.getStatistics("users").br());
    }

    // salin semua file biasa dari satu folder ke folder lain, file dengan nama sama ditimpa
    private static void copyFiles(Path from, Path to) throws Exception {
        Files.createDirectories(to);
        try (var files = Files.list(from)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                Files.copy(file, to.resolve(file.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            }
        }
    }

    // file index lama yang dibalikin bikin index ketinggalan, rebuildIndexes nyamain lagi dengan isi heap
    @Test
    void rebuildIndexesFixesStaleIndexFile(@TempDir Path backup) throws Exception {
        storage.attachRecoveryManager(acceptingFrm());
        createUsers();
        RowId alice = storage.writeBlock(new DataWrite("users", user(1, "alice", 3.5f), List.of())).get(0);
        storage.createIndex("idx_users_id", "users", "id");
        storage.flushBuffer();
        Files.copy(directory.resolve("idx_users_id.idx"), backup.resolve("idx_users_id.idx"));

        RowId bob = storage.writeBlock(new DataWrite("users", user(2, "bob", null), List.of())).get(0);
        storage.flushBuffer();
        Files.copy(backup.resolve("idx_users_id.idx"), directory.resolve("idx_users_id.idx"),
                StandardCopyOption.REPLACE_EXISTING);

        StorageManager restarted = new StorageManager(directory.toString(), 16, 4);
        restarted.attachRecoveryManager(acceptingFrm());
        assertTrue(restarted.searchIndex("idx_users_id", 2).isEmpty());

        restarted.rebuildIndexes();

        assertEquals(List.of(alice), restarted.searchIndex("idx_users_id", 1));
        assertEquals(List.of(bob), restarted.searchIndex("idx_users_id", 2));
        assertEquals(List.of(alice, bob), restarted.searchIndexRange("idx_users_id", null, null, true, true));
    }

    // file index yang hilang tapi masih tercatat di katalog dibikin kosong lalu diisi lagi lewat rebuild
    @Test
    void missingIndexFileIsRecreatedAndRebuilt() throws Exception {
        storage.attachRecoveryManager(acceptingFrm());
        createUsers();
        RowId alice = storage.writeBlock(new DataWrite("users", user(1, "alice", 3.5f), List.of())).get(0);
        storage.createIndex("idx_users_name", "users", "name");
        storage.flushBuffer();
        Files.delete(directory.resolve("idx_users_name.idx"));

        StorageManager restarted = new StorageManager(directory.toString(), 16, 4);
        restarted.attachRecoveryManager(acceptingFrm());
        assertTrue(restarted.searchIndex("idx_users_name", "alice").isEmpty());

        restarted.rebuildIndexes();

        assertEquals(List.of(alice), restarted.searchIndex("idx_users_name", "alice"));
    }

    // setelah file data dibalikin ke snapshot, reloadFromDisk bikin heap, katalog, index, dan statistik ikut balik
    @Test
    void reloadFromDiskFollowsRestoredSnapshot(@TempDir Path snapshot) throws Exception {
        storage.attachRecoveryManager(acceptingFrm());
        createUsers();
        RowId alice = storage.writeBlock(new DataWrite("users", user(1, "alice", 3.5f), List.of())).get(0);
        storage.createIndex("idx_users_id", "users", "id");
        storage.flushBuffer();
        copyFiles(directory, snapshot);

        storage.writeBlock(new DataWrite("users", user(2, "bob", null), List.of()));
        storage.updateTableSchema(new TableSchema("logs", List.of(
                new ColumnDefinition("id", DataType.INTEGER, 0, false)), List.of("id"), List.of()));
        storage.flushBuffer();
        assertEquals(2, storage.getStatistics("users").nr());

        copyFiles(snapshot, directory);
        storage.reloadFromDisk();

        assertEquals(List.of(alice), readUsers().stream().map(Row::rowId).toList());
        assertEquals(List.of(alice), storage.searchIndex("idx_users_id", 1));
        assertTrue(storage.searchIndex("idx_users_id", 2).isEmpty());
        assertEquals(1, storage.getStatistics("users").nr());
        assertEquals(List.of("users"), storage.listTable());

        RowId cici = storage.writeBlock(new DataWrite("users", user(3, "cici", 2.0f), List.of())).get(0);
        assertEquals(List.of(cici), storage.searchIndex("idx_users_id", 3));
    }

    // reloadFromDisk buang perubahan di buffer yang belum di flush karena isi disk yang jadi acuan
    @Test
    void reloadFromDiskDropsUnflushedChanges() {
        storage.attachRecoveryManager(acceptingFrm());
        createUsers();
        RowId alice = storage.writeBlock(new DataWrite("users", user(1, "alice", 3.5f), List.of())).get(0);
        storage.createIndex("idx_users_id", "users", "id");
        storage.flushBuffer();

        storage.writeBlock(new DataWrite("users", user(2, "bob", null), List.of()));
        storage.reloadFromDisk();

        assertEquals(List.of(alice), readUsers().stream().map(Row::rowId).toList());
        assertTrue(storage.searchIndex("idx_users_id", 2).isEmpty());
        assertEquals(0, storage.getBufferStats().dirtyBlocks());
    }

    // deleteByRowId kedua kali di baris yang sama balikin null tanpa error, index dan data ga berubah lagi
    @Test
    void deleteByRowIdIsIdempotentForRedo() {
        createUsers();
        RowId alice = storage.writeBlock(new DataWrite("users", user(1, "alice", 3.5f), List.of())).get(0);
        RowId bob = storage.writeBlock(new DataWrite("users", user(2, "bob", null), List.of())).get(0);
        storage.createIndex("idx_users_id", "users", "id");

        assertEquals(List.of(1, "alice", 3.5f), storage.deleteByRowId(alice).values());
        assertNull(storage.deleteByRowId(alice));

        assertEquals(List.of(bob), readUsers().stream().map(Row::rowId).toList());
        assertTrue(storage.searchIndex("idx_users_id", 1).isEmpty());
        assertEquals(List.of(bob), storage.searchIndex("idx_users_id", 2));
    }

    // deleteByRowId ke block atau slot yang belum pernah ada juga balikin null, misalnya undo INSERT yang ga sempat ke disk
    @Test
    void deleteByRowIdOnMissingBlockOrSlotReturnsNull() {
        createUsers();
        storage.writeBlock(new DataWrite("users", user(1, "alice", 3.5f), List.of()));

        assertNull(storage.deleteByRowId(new RowId("users", 7, 0)));
        assertNull(storage.deleteByRowId(new RowId("users", 0, 40)));
        assertEquals(1, readUsers().size());
        assertCode(StorageErrorCode.TABLE_NOT_FOUND, () -> storage.deleteByRowId(MISSING_ROW));
    }

    // bikin tabel dengan nama yang udah ada ditolak dan schema lama tetap utuh, setelah di drop namanya boleh dipakai lagi
    @Test
    void createExistingTableIsRejected() {
        createUsers();
        storage.writeBlock(new DataWrite("users", user(1, "alice", 3.5f), List.of()));
        TableSchema other = new TableSchema("users", List.of(
                new ColumnDefinition("code", DataType.CHAR, 4, false)), List.of("code"), List.of());

        assertCode(StorageErrorCode.TABLE_ALREADY_EXISTS, () -> storage.updateTableSchema(other));
        assertEquals(3, storage.getTableSchema("users").columns().size());
        assertEquals(1, readUsers().size());

        storage.dropTable("users");
        storage.updateTableSchema(other);
        assertEquals(other, storage.getTableSchema("users"));
        assertTrue(readUsers().isEmpty());
    }
}
