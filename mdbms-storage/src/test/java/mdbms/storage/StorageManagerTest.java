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
import mdbms.models.schema.TableSchema;
import mdbms.storage.block.Block;
import mdbms.storage.block.BlockManager;
import mdbms.storage.block.BufferPool;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class StorageManagerTest {

    private static final RowId ROW_ID = new RowId("users", 0, 0);
    private static final Condition CONDITION =
            new Condition("id", ComparisonOperator.values()[0], 1);

    private StorageManager storage;
    @TempDir Path directory;

    @BeforeEach
    void setUp() {
        storage = new StorageManager(directory.toString(), 16, 4);
    }

    @Test
    void attachRecoveryManagerAcceptsImplementation() {
        IFailureRecoveryManager frm = (IFailureRecoveryManager) Proxy.newProxyInstance(
                IFailureRecoveryManager.class.getClassLoader(),
                new Class<?>[] {IFailureRecoveryManager.class},
                (proxy, method, args) -> null);

        assertDoesNotThrow(() -> storage.attachRecoveryManager(frm));
    }

    @Test
    void readBlockReturnsNoRows() {
        DataRetrieval request = new DataRetrieval("users", List.of("id"), List.of(CONDITION));

        assertTrue(storage.readBlock(request).rows().isEmpty());
    }

    @Test
    void writeBlockReturnsNoRowIds() {
        DataWrite request = new DataWrite("users", Map.of("id", 1), List.of());

        assertTrue(storage.writeBlock(request).isEmpty());
    }

    @Test
    void deleteBlockDeletesNothing() {
        assertEquals(0, storage.deleteBlock(new DataDeletion("users", List.of(CONDITION))));
    }

    @Test
    void fetchByRowIdReturnsEmptyRowWithSameId() {
        Row row = storage.fetchByRowId(ROW_ID);

        assertEquals(ROW_ID, row.rowId());
        assertTrue(row.values().isEmpty());
    }

    @Test
    void insertAtDoesNotThrow() {
        assertDoesNotThrow(() -> storage.insertAt(ROW_ID, List.of(1, "alice")));
    }

    @Test
    void updateByRowIdReturnsNewValues() {
        Row row = storage.updateByRowId(ROW_ID, List.of(1, "alice"));

        assertEquals(ROW_ID, row.rowId());
        assertEquals(List.of(1, "alice"), row.values());
    }

    @Test
    void updateByRowIdCopiesValues() {
        List<Object> values = new ArrayList<>(List.of(1));

        Row row = storage.updateByRowId(ROW_ID, values);
        values.add(2);

        assertEquals(List.of(1), row.values());
    }

    @Test
    void updateByRowIdRejectsNullValues() {
        assertThrows(NullPointerException.class, () -> storage.updateByRowId(ROW_ID, null));
    }

    @Test
    void deleteByRowIdReturnsEmptyRowWithSameId() {
        Row row = storage.deleteByRowId(ROW_ID);

        assertEquals(ROW_ID, row.rowId());
        assertTrue(row.values().isEmpty());
    }

    @Test
    void indexSearchesReturnNoRowIds() {
        assertTrue(storage.searchIndex("idx_users_id", 1).isEmpty());
        assertTrue(storage.searchIndexRange("idx_users_id", 1, 10, true, false).isEmpty());
    }

    @Test
    void schemaCatalogIsEmpty() {
        assertNull(storage.getTableSchema("users"));
        assertTrue(storage.listTable().isEmpty());
        assertTrue(storage.getIndexes("users").isEmpty());
        assertNull(storage.createIndex("idx_users_id", "users", "id"));
    }

    @Test
    void updateTableSchemaDoesNotThrow() {
        TableSchema schema = new TableSchema("users", List.of(), List.of(), List.of());

        assertDoesNotThrow(() -> storage.updateTableSchema(schema));
    }

    @Test
    void tableStatisticsRemainUnimplementedAndBufferStatsAreReal() {
        assertNull(storage.getStatistics("users"));
        assertDoesNotThrow(storage::flushBuffer);
        assertEquals(new BufferStats(16, 0, 0, 0, 0), storage.getBufferStats());
    }

    @Test
    void rejectsInvalidBufferConfiguration() {
        assertThrows(StorageException.class, () -> new StorageManager(directory.toString(), 0, 4));
        assertThrows(StorageException.class, () -> storage.attachRecoveryManager(null));
    }

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
