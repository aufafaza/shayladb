package mdbms.api;

import java.util.List;
import mdbms.models.access.DataDeletion;
import mdbms.models.access.DataRetrieval;
import mdbms.models.access.DataWrite;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.data.Rows;

public interface IDataAccess{
    Rows readBlock(DataRetrieval request);

    List<RowId> writeBlock(DataWrite request);

    int deleteBlock(DataDeletion request);

    Row fetchByRowId(RowId rowId);

    RowId reserveRowId(String table, List<Object> values);

    default RowId reserveRowId(String table, List<Object> values, long pageLsn) {
        return reserveRowId(table, values);
    }

    void releaseRowId(RowId rowId);

    default void releaseRowId(RowId rowId, long pageLsn) {
        releaseRowId(rowId);
    }

    void insertAt(RowId rowId, List<Object> values);

    default void insertAt(RowId rowId, List<Object> values, long pageLsn) {
        insertAt(rowId, values);
    }

    Row updateByRowId(RowId rowId, List<Object> values);

    default Row updateByRowId(RowId rowId, List<Object> values, long pageLsn) {
        return updateByRowId(rowId, values);
    }

    Row deleteByRowId(RowId rowId);

    default Row deleteByRowId(RowId rowId, long pageLsn) {
        return deleteByRowId(rowId);
    }

    List<RowId> searchIndex(String indexName, Object key);

    List<RowId> searchIndexRange(String indexName, Object low, Object high, boolean lowInclusive, boolean highInclusive);
}