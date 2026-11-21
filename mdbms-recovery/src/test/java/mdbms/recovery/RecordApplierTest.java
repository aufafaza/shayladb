package mdbms.recovery;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import mdbms.api.IDataAccess;
import mdbms.models.access.DataDeletion;
import mdbms.models.access.DataRetrieval;
import mdbms.models.access.DataWrite;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.data.Rows;
import mdbms.models.log.DataChangeLogRecord;
import mdbms.models.log.LogType;
import mdbms.models.log.RecordImage;
import org.junit.jupiter.api.Test;

class RecordApplierTest {

    @Test
    void redoAndUndoReturnFalseWhenDataAccessIsNull() {
        RecordApplier applier = new RecordApplier(null);
        RowId rid = new RowId("users", 0, 0);
        DataChangeLogRecord updateRec = new DataChangeLogRecord(
                1L, 1, LogType.UPDATE, Instant.now(), "users", rid,
                new RecordImage(List.of(1, "Old")), new RecordImage(List.of(1, "New")));

        assertFalse(applier.redo(updateRec));
        assertFalse(applier.undo(updateRec));
    }

    @Test
    void idempotentDeleteIgnoresMissingSlotError() {
        StubDataAccess stub = new StubDataAccess();
        stub.deleteException = new IllegalStateException("Slot contains no record");
        RecordApplier applier = new RecordApplier(stub);

        DataChangeLogRecord deleteRec = new DataChangeLogRecord(
                1L, 1, LogType.DELETE, Instant.now(), "users", new RowId("users", 0, 0),
                new RecordImage(List.of(1, "Alice")), null);

        assertTrue(applier.redo(deleteRec));
    }

    @Test
    void idempotentDeletePropagatesGenuineIoException() {
        StubDataAccess stub = new StubDataAccess();
        stub.deleteException = new UncheckedIOException(new IOException("Disk read/write failed"));
        RecordApplier applier = new RecordApplier(stub);

        DataChangeLogRecord deleteRec = new DataChangeLogRecord(
                1L, 1, LogType.DELETE, Instant.now(), "users", new RowId("users", 0, 0),
                new RecordImage(List.of(1, "Alice")), null);

        assertThrows(UncheckedIOException.class, () -> applier.redo(deleteRec));
    }

    @Test
    void idempotentInsertPropagatesGenuineCorruptionException() {
        StubDataAccess stub = new StubDataAccess();
        stub.fetchException = new RuntimeException("Page checksum corrupted");
        RecordApplier applier = new RecordApplier(stub);

        DataChangeLogRecord insertRec = new DataChangeLogRecord(
                1L, 1, LogType.INSERT, Instant.now(), "users", new RowId("users", 0, 0),
                null, new RecordImage(List.of(1, "Alice")));

        assertThrows(RuntimeException.class, () -> applier.redo(insertRec));
    }

    private static final class StubDataAccess implements IDataAccess {
        RuntimeException fetchException;
        RuntimeException deleteException;
        RuntimeException insertFirstException;
        RuntimeException insertSecondException;
        RowId reservedRowIdToReturn = new RowId("users", 0, 0);
        int insertAttempts = 0;
        final List<List<Object>> updatedValues = new ArrayList<>();

        @Override
        public Row fetchByRowId(RowId rowId) {
            if (fetchException != null) {
                throw fetchException;
            }
            return null;
        }

        @Override
        public RowId reserveRowId(String table, List<Object> values) {
            return new RowId(table, 0, 0);
        }

        @Override
        public void releaseRowId(RowId rowId) {
        }

        @Override
        public void insertAt(RowId rowId, List<Object> values) {
            insertAttempts++;
            if (insertAttempts == 1 && insertFirstException != null) {
                throw insertFirstException;
            }
            if (insertAttempts == 2 && insertSecondException != null) {
                throw insertSecondException;
            }
        }

        @Override
        public Row updateByRowId(RowId rowId, List<Object> values) {
            updatedValues.add(new ArrayList<>(values));
            return null;
        }

        @Override
        public Row deleteByRowId(RowId rowId) {
            if (deleteException != null) {
                throw deleteException;
            }
            return null;
        }

        @Override
        public Rows readBlock(DataRetrieval request) {
            return null;
        }

        @Override
        public List<RowId> writeBlock(DataWrite request) {
            return List.of();
        }

        @Override
        public int deleteBlock(DataDeletion request) {
            return 0;
        }

        @Override
        public List<RowId> searchIndex(String indexName, Object key) {
            return List.of();
        }

        @Override
        public List<RowId> searchIndexRange(
                String indexName, Object low, Object high, boolean lowInclusive, boolean highInclusive) {
            return List.of();
        }


        @Test
        void corruptedPageInvalidSlotCountAndEntryAreNotSwallowed() {
            StubDataAccess stub1 = new StubDataAccess();
            stub1.fetchException = new IllegalStateException("Invalid slot count: -5");
            RecordApplier applier1 = new RecordApplier(stub1);
            DataChangeLogRecord rec = new DataChangeLogRecord(
                    1L, 1, LogType.INSERT, Instant.now(), "users", new RowId("users", 0, 0),
                    null, new RecordImage(List.of(1, "Alice")));
            assertThrows(IllegalStateException.class, () -> applier1.redo(rec));

            StubDataAccess stub2 = new StubDataAccess();
            stub2.fetchException = new IllegalStateException("Invalid slot entry at 2");
            RecordApplier applier2 = new RecordApplier(stub2);
            assertThrows(IllegalStateException.class, () -> applier2.redo(rec));
        }

        @Test
        void unknownTableErrorIsNotSwallowed() {
            StubDataAccess stub = new StubDataAccess();
            stub.deleteException = new IllegalArgumentException("Unknown table or table does not exist: ghost");
            RecordApplier applier = new RecordApplier(stub);
            DataChangeLogRecord del = new DataChangeLogRecord(
                    1L, 1, LogType.DELETE, Instant.now(), "ghost", new RowId("ghost", 0, 0),
                    new RecordImage(List.of(1)), null);
            assertThrows(IllegalArgumentException.class, () -> applier.redo(del));
        }

        @Test
        void slotUnavailableConflictOnInsertIsNotSwallowed() {
            StubDataAccess stub = new StubDataAccess();
            stub.fetchException = new IllegalStateException("Invalid slot 0");
            stub.insertFirstException = new IllegalStateException("Slot unavailable for row: 0:0");
            RecordApplier applier = new RecordApplier(stub);
            DataChangeLogRecord rec = new DataChangeLogRecord(
                    1L, 1, LogType.INSERT, Instant.now(), "users", new RowId("users", 0, 0),
                    null, new RecordImage(List.of(1, "Conflict")));
            assertThrows(IllegalStateException.class, () -> applier.redo(rec));
        }

        @Test
        void blockDoesNotExistTriggersAllocationAndInsert() {
            StubDataAccess stub = new StubDataAccess();
            stub.fetchException = new IllegalStateException("Block does not exist: 1");
            stub.insertFirstException = new IllegalStateException("Block does not exist: 1");
            stub.reservedRowIdToReturn = new RowId("users", 1, 0);
            RecordApplier applier = new RecordApplier(stub);

            DataChangeLogRecord rec = new DataChangeLogRecord(
                    1L, 1, LogType.INSERT, Instant.now(), "users", new RowId("users", 1, 0),
                    null, new RecordImage(List.of(1, "NewBlock")));
            assertTrue(applier.redo(rec));
            assertEquals(2, stub.insertAttempts);
        }

        @Test
        void deleteReturningNullSucceedsIdempotently() {
            StubDataAccess stub = new StubDataAccess(); // deleteByRowId mengembalikan null tanpa exception
            RecordApplier applier = new RecordApplier(stub);
            DataChangeLogRecord del = new DataChangeLogRecord(
                    1L, 1, LogType.DELETE, Instant.now(), "users", new RowId("users", 0, 0),
                    new RecordImage(List.of(1, "Gone")), null);
            assertTrue(applier.redo(del));
        }
    }
}