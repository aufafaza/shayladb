package mdbms.storage.crud;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.NoSuchElementException;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.models.schema.TableSchema;
import mdbms.storage.block.BlockManager;
import mdbms.storage.block.BufferPool;
import mdbms.storage.serializer.BinaryRecordSerializer;
import mdbms.storage.serializer.IRecordSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HeapFileRowIteratorTest {

    @TempDir
    Path tempDir;

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

        BufferPool pool = new BufferPool(new BlockManager(tempDir.toString()), 8);
        IRecordSerializer serializer = new BinaryRecordSerializer();

        heapFile = new HeapFile(schema, pool, serializer);
    }

    // an empty heap file has no row to iterate
    @Test
    void emptyHeapFileHasNoNextRow(){
        try (RowIterator iterator = heapFile.scan()){
            assertFalse(iterator.hasNext());
        }
    }

    // calling hasNext twice does not skip the current row
    @Test
    void repeatedHasNextDoesNotAdvanceIterator(){
        RowId firstId = heapFile.insert(List.of(1, "alice"));
        RowId secondId = heapFile.insert(List.of(2, "bob"));

        try (RowIterator iterator = heapFile.scan()){
            assertTrue(iterator.hasNext());
            assertTrue(iterator.hasNext());

            Row first = iterator.next();
            Row second = iterator.next();

            assertEquals(firstId, first.rowId());
            assertEquals(secondId, second.rowId());
            assertFalse(iterator.hasNext());
        }
    }

    // deleted and reserved slots are skipped during scan
    @Test
    void iteratorSkipsDeletedAndReservedSlots(){
        RowId deletedId = heapFile.insert(List.of(1, "deleted"));
        heapFile.reserve(List.of(2, "reserved"));
        RowId activeId = heapFile.insert(List.of(3, "active"));

        heapFile.delete(deletedId);

        try (RowIterator iterator = heapFile.scan()){
            assertTrue(iterator.hasNext());

            Row row = iterator.next();

            assertEquals(activeId, row.rowId());
            assertEquals(List.of(3, "active"), row.values());
            assertFalse(iterator.hasNext());
        }
    }

    // rows from more than one block are returned in scan order
    @Test
    void iteratorReadsRowsAcrossMultipleBlocks(){
        String firstValue = "a".repeat(1800);
        String secondValue = "b".repeat(1800);
        String thirdValue = "c".repeat(1800);

        RowId firstId = heapFile.insert(List.of(1, firstValue));
        RowId secondId = heapFile.insert(List.of(2, secondValue));
        RowId thirdId = heapFile.insert(List.of(3, thirdValue));

        assertTrue(heapFile.blockCount() > 1);

        List<RowId> rowIds = new ArrayList<>();

        try (RowIterator iterator = heapFile.scan()){
            while (iterator.hasNext()){
                rowIds.add(iterator.next().rowId());
            }
        }

        assertEquals(List.of(firstId, secondId, thirdId), rowIds);
    }

    // next throws when the iterator has no more rows
    @Test
    void nextThrowsWhenNoRowExists(){
        try (RowIterator iterator = heapFile.scan()){
            assertThrows(NoSuchElementException.class, iterator::next);
        }
    }

    // close stops the iterator even when rows still exist
    @Test
    void closeStopsIteration(){
        heapFile.insert(List.of(1, "alice"));

        RowIterator iterator = heapFile.scan();

        assertTrue(iterator.hasNext());

        iterator.close();

        assertFalse(iterator.hasNext());
        assertThrows(NoSuchElementException.class, iterator::next);
    }
}