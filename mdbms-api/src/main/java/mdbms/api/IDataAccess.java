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

    // true kalau isi baru muat di slot yang sama, false berarti UPDATE harus lewat relokasi (#55)
    // ga ngubah apa apa, dipanggil sebelum minta lock dan nulis log
    default boolean canUpdateInPlace(RowId rowId, List<Object> values) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " does not support canUpdateInPlace");
    }

    default boolean canUpdateInPlace(RowId rowId, List<Object> values, long pageLsn) {
        return canUpdateInPlace(rowId, values);
    }

    // UPDATE yang ga pernah mindahin baris, false kalau ga muat dan ga ada yang berubah
    // beda sama updateByRowId yang diam diam mindahin baris tanpa sempat dikunci dan dicatat
    default boolean updateInPlace(RowId rowId, List<Object> values) {
        throw new UnsupportedOperationException(getClass().getSimpleName() + " does not support updateInPlace");
    }

    default boolean updateInPlace(RowId rowId, List<Object> values, long pageLsn) {
        return updateInPlace(rowId, values);
    }

    Row deleteByRowId(RowId rowId);

    default Row deleteByRowId(RowId rowId, long pageLsn) {
        return deleteByRowId(rowId);
    }

    List<RowId> searchIndex(String indexName, Object key);

    List<RowId> searchIndexRange(String indexName, Object low, Object high, boolean lowInclusive, boolean highInclusive);
}