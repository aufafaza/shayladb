package mdbms.api;

import java.util.List;
import mdbms.models.DataDeletion;
import mdbms.models.DataRetrieval;
import mdbms.models.DataWrite;
import mdbms.models.Row;
import mdbms.models.RowId;
import mdbms.models.Rows;

public interface IDataAccess{
    Rows readBlock(DataRetrieval request);

    List<RowId> writeBlock(DataWrite request);

    int deleteBlock(DataDeletion request);

    Row fetchByRowId(RowId rowId);

    void insertAt(RowId rowId, List<Object> values);

    Row updateByRowId(RowId rowId, List<Object> values);

    Row deleteByRowId(RowId rowId);

    List<RowId> searchIndex(String indexName, Object key);

    List<RowId> searchIndexRange(String indexName, Object low, Object high, boolean lowInclusive, boolean highInclusive);
}