package mdbms.storage;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.ComparisonOperator;
import mdbms.models.Condition;
import mdbms.models.DataDeletion;
import mdbms.models.DataRetrieval;
import mdbms.models.DataWrite;
import mdbms.models.Row;
import mdbms.models.RowId;
import mdbms.models.TableSchema;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class StorageManagerTest {

    private static final RowId ROW_ID = new RowId("users", 0, 0);
    private static final Condition CONDITION =
            new Condition("id", ComparisonOperator.values()[0], 1);

    private StorageManager storage;

    @BeforeEach
    void setUp() {
        storage = new StorageManager("data", 16, 4);
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
    void statisticsAndBufferStatsAreNull() {
        assertNull(storage.getStatistics("users"));
        assertDoesNotThrow(storage::flushBuffer);
        assertNull(storage.getBufferStats());
    }
}
