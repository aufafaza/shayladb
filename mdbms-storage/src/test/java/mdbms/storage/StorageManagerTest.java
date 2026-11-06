package mdbms.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
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
}
