package mdbms.storage;

import java.util.List;
import mdbms.api.IDataAccess;
import mdbms.api.ISchemaCatalog;
import mdbms.api.IStatisticsProvider;
import mdbms.api.IDurableStorage;
import mdbms.api.IFailureRecoveryManager;
import mdbms.models.BufferStats;
import mdbms.models.DataDeletion;
import mdbms.models.DataRetrieval;
import mdbms.models.DataWrite;
import mdbms.models.IndexInfo;
import mdbms.models.Row;
import mdbms.models.RowId;
import mdbms.models.Rows;
import mdbms.models.Statistic;
import mdbms.models.TableSchema;

public final class StorageManager implements IDataAccess, ISchemaCatalog, IStatisticsProvider, IDurableStorage{

    public StorageManager(String dataDirectory, int bufferCapacity, int indexMaxDegree){}
    
    public void attachRecoveryManager(IFailureRecoveryManager frm){}

    @Override
    public Rows readBlock(DataRetrieval request){
        return new Rows(List.of());
    }

    @Override
    public List<RowId> writeBlock(DataWrite request){
        return List.of();
    }

    @Override
    public int deleteBlock(DataDeletion request){
        return 0;
    }

    @Override
    public Row fetchByRowId(RowId rowId){
        return new Row(rowId, List.of());
    }

    @Override
    public void insertAt(RowId rowId, List<Object> values){}

    @Override
    public Row updateByRowId(RowId rowId, List<Object> values){
        return new Row(rowId, List.copyOf(values));
    }

    @Override
    public Row deleteByRowId(RowId rowId){
        return new Row(rowId, List.of());
    }

    @Override
    public List<RowId> searchIndex(String indexName, Object key){
        return List.of();
    }

    @Override
    public List<RowId> searchIndexRange(String indexName, Object low, Object high, boolean lowInclusive, boolean highInclusive){
        return List.of();
    }

    @Override
    public TableSchema getTableSchema(String table){
        return null;
    }

    @Override
    public List<String> listTable(){
        return List.of();
    }

    @Override
    public void updateTableSchema(TableSchema schema){}

    @Override
    public List<IndexInfo> getIndexes(String table){
        return List.of();
    }

    @Override
    public IndexInfo createIndex(String name, String table, String column){
        return null;
    }

    @Override
    public Statistic getStatistics(String table){
        return null;
    }

    @Override
    public void flushBuffer(){}

    @Override
    public BufferStats getBufferStats(){
        return null;
    }
}