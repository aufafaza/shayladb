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

    void releaseRowId(RowId rowId);

    void insertAt(RowId rowId, List<Object> values);

    // versi yang bawa LSN log perubahan dari CCM, halaman yang berubah ditandai LSN ini
    // biar BufferPool flush WAL sampai LSN itu dulu sebelum halamannya ditulis ke disk (#54)
    // default nya ngabaikan LSN biar implementasi lain kayak mock test tetap jalan
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

    // tolak kalau ada baris lain yang primary key nya sama dengan values, baris ignoreRowId dilewatin
    // dipanggil QP sebelum INSERT dan UPDATE ngubah data, ignoreRowId diisi baris yang lagi di update, null buat INSERT
    // ga dicek di insertAt karena insertAt juga dipakai undo redo FRM dan relokasi UPDATE yang baris lamanya masih ada
    default void checkPrimaryKey(String table, List<Object> values, RowId ignoreRowId) {
    }

    List<RowId> searchIndex(String indexName, Object key);

    List<RowId> searchIndexRange(String indexName, Object low, Object high, boolean lowInclusive, boolean highInclusive);
}