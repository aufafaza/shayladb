package mdbms.storage.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.storage.block.BlockManager;
import mdbms.storage.block.BufferPool;
import mdbms.storage.exception.StorageException;
import mdbms.storage.serializer.BinaryRecordSerializer;
import mdbms.storage.serializer.IRecordSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeapFileTest {

    @TempDir
    Path tempDir;

    private BufferPool pool;
    private IRecordSerializer serializer;
    private HeapFile heapFile;

    @BeforeEach
    void setUp(){
        TableSchema schema = new TableSchema(
            "users",
            List.of(
                new ColumnDefinition("id", DataType.INTEGER, 0, false),
                new ColumnDefinition("name", DataType.VARCHAR, 3500, false)
            ),
            List.of("id"),
            List.of()
        );

        pool = new BufferPool(new BlockManager(tempDir.toString()), 8);
        serializer = new BinaryRecordSerializer();
        heapFile = new HeapFile(schema, pool, serializer);
    }

    // a new heap file starts without any block
    @Test
    void newHeapFileHasNoBlock(){
        assertEquals(0, heapFile.blockCount());
    }

    // an inserted row can be fetched with the same values
    @Test
    void insertAndFetchRow(){
        List<Object> values = List.of(1, "alice");

        RowId rowId = heapFile.insert(values);
        Row row = heapFile.fetch(rowId);

        assertEquals("users", rowId.tableName());
        assertEquals(values, row.values());
        assertEquals(rowId, row.rowId());
        assertEquals(1, heapFile.blockCount());
    }

    // small rows use the same block when there is enough space
    @Test
    void insertUsesExistingBlockWhenSpaceIsAvailable(){
        RowId first = heapFile.insert(List.of(1, "alice"));
        RowId second = heapFile.insert(List.of(2, "bob"));

        assertEquals(first.blockNumber(), second.blockNumber());
        assertNotEquals(first.slot(), second.slot());
        assertEquals(1, heapFile.blockCount());
    }

    // reserve only prepares a slot and insertAt writes the row later
    @Test
    void reserveThenInsertAtWritesRow(){
        List<Object> values = List.of(1, "alice");

        RowId rowId = heapFile.reserve(values);

        try (RowIterator iterator = heapFile.scan()){
            assertFalse(iterator.hasNext());
        }

        heapFile.insertAt(rowId, values);

        Row row = heapFile.fetch(rowId);

        assertEquals(rowId, row.rowId());
        assertEquals(values, row.values());
    }

    // a released reservation can be used again by insertAt
    @Test
    void releaseReservationAllowsSlotToBeUsedAgain(){
        RowId rowId = heapFile.reserve(List.of(1, "alice"));

        heapFile.releaseReservation(rowId);
        heapFile.insertAt(rowId, List.of(1, "bob"));

        Row row = heapFile.fetch(rowId);

        assertEquals(rowId, row.rowId());
        assertEquals(List.of(1, "bob"), row.values());
    }

    // insertAt cannot overwrite a slot that already contains a row
    @Test
    void insertAtRejectsOccupiedSlot(){
        RowId rowId = heapFile.insert(List.of(1, "alice"));

        assertThrows(
            StorageException.class,
            () -> heapFile.insertAt(rowId, List.of(2, "bob"))
        );
    }

    // a reserved slot rejects a row that is larger than its reservation
    @Test
    void insertAtRejectsRowLargerThanReservation(){
        RowId rowId = heapFile.reserve(List.of(1, "a"));
        String largeName = "x".repeat(1000);

        assertThrows(
            StorageException.class,
            () -> heapFile.insertAt(rowId, List.of(1, largeName))
        );
    }

    // an update keeps the same RowId when the new row still fits
    @Test
    void updateInPlaceKeepsSameRowId(){
        RowId rowId = heapFile.insert(List.of(1, "alice"));

        Row updated = heapFile.update(rowId, List.of(1, "alice updated"));

        assertEquals(rowId, updated.rowId());
        assertEquals(List.of(1, "alice updated"), updated.values());
        assertEquals(updated.values(), heapFile.fetch(rowId).values());
    }

    // an update moves the row when the current block has no space
    @Test
    void updateMovesRowWhenCurrentBlockHasNoSpace(){
        String firstValue = "a".repeat(1400);
        String secondValue = "b".repeat(1400);
        String largerValue = "c".repeat(3000);

        RowId firstId = heapFile.insert(List.of(1, firstValue));
        RowId secondId = heapFile.insert(List.of(2, secondValue));

        assertEquals(firstId.blockNumber(), secondId.blockNumber());

        Row updated = heapFile.update(firstId, List.of(1, largerValue));

        assertNotEquals(firstId, updated.rowId());
        assertEquals(List.of(1, largerValue), updated.values());
        assertEquals(List.of(1, largerValue), heapFile.fetch(updated.rowId()).values());
        assertTrue(heapFile.blockCount() > 1);
    }

    // delete returns the old row and removes it from the scan result
    @Test
    void deleteReturnsRowAndRemovesItFromScan(){
        RowId firstId = heapFile.insert(List.of(1, "alice"));
        RowId secondId = heapFile.insert(List.of(2, "bob"));

        Row deleted = heapFile.delete(firstId);

        assertEquals(firstId, deleted.rowId());
        assertEquals(List.of(1, "alice"), deleted.values());

        try (RowIterator iterator = heapFile.scan()){
            assertTrue(iterator.hasNext());

            Row remaining = iterator.next();

            assertEquals(secondId, remaining.rowId());
            assertEquals(List.of(2, "bob"), remaining.values());
            assertFalse(iterator.hasNext());
        }
    }
}