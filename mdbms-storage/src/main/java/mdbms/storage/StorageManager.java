package mdbms.storage;

import java.util.List;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import mdbms.api.IDataAccess;
import mdbms.api.IDurableStorage;
import mdbms.api.IFailureRecoveryManager;
import mdbms.api.ISchemaCatalog;
import mdbms.api.IStatisticsProvider;
import mdbms.models.access.BufferStats;
import mdbms.models.access.DataDeletion;
import mdbms.models.access.DataRetrieval;
import mdbms.models.access.DataWrite;
import mdbms.models.data.Row;
import mdbms.models.data.RowId;
import mdbms.models.data.Rows;
import mdbms.models.data.Condition;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.Statistic;
import mdbms.models.schema.TableSchema;
import mdbms.models.schema.ColumnDefinition;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
import mdbms.storage.crud.ConditionEvaluator;
import mdbms.storage.crud.HeapFile;
import mdbms.storage.crud.RowIterator;
import mdbms.storage.block.BlockManager;
import mdbms.storage.block.BufferPool;
import mdbms.storage.serializer.BinaryRecordSerializer;
import mdbms.storage.serializer.IRecordSerializer;
import mdbms.storage.catalog.CatalogManager;
import mdbms.storage.statistics.StatisticsManager;

public final class StorageManager implements IDataAccess, ISchemaCatalog, IStatisticsProvider, IDurableStorage{
    private final BufferPool bufferPool;
    private final IRecordSerializer serializer;
    private final CatalogManager catalog;
    private final StatisticsManager statistics;
    private final ConditionEvaluator evaluator;
    private final Map<String, HeapFile> tables;

    // TODO tambah field IndexManager pakai indexMaxDegree kalau kelasnya udah diimplementasi SM5
    public StorageManager(String dataDirectory, int bufferCapacity, int indexMaxDegree){
        bufferPool = new BufferPool(new BlockManager(dataDirectory), bufferCapacity);
        serializer = new BinaryRecordSerializer();
        catalog = new CatalogManager(dataDirectory);
        statistics = new StatisticsManager(catalog);
        evaluator = new ConditionEvaluator();
        tables = new HashMap<>();
    }
    
    public void attachRecoveryManager(IFailureRecoveryManager frm){
        bufferPool.attachRecoveryManager(frm);
    }

    @Override
    public Rows readBlock(DataRetrieval request){
        TableSchema schema = requireSchema(request.table());

        HeapFile heapFile = heapFileOf(request.table());
        List<Row> result = new ArrayList<>();

        try (RowIterator iterator = heapFile.scan()){
            while (iterator.hasNext()){
                Row row = iterator.next();

                if (request.conditions() != null && !request.conditions().isEmpty() && !matchesAll(request.conditions(), row, schema)){
                    continue;
                }

                result.add(projectRow(row, schema, request.columns()));
            }
        }

        return new Rows(result);
    }

    @Override
    public List<RowId> writeBlock(DataWrite request){
        TableSchema schema = requireSchema(request.table());

        HeapFile heapFile = heapFileOf(request.table());
        List<RowId> result = new ArrayList<>();

        if (request.conditions() == null || request.conditions().isEmpty()){
            List<Object> values = toPositional(schema, request.values());
            validateRow(schema, values);

            RowId rowId = heapFile.insert(values);
            result.add(rowId);

            afterWrite(request.table(), null, new Row(rowId, values));
            return result;
        }

        try (RowIterator iterator = heapFile.scan()){
            while (iterator.hasNext()){
                Row oldRow = iterator.next();

                if (!matchesAll(request.conditions(), oldRow, schema)){
                    continue;
                }

                List<Object> newValues = new ArrayList<>(oldRow.values());

                for (Map.Entry<String, Object> entry : request.values().entrySet()){
                    int index = columnIndex(schema, entry.getKey());
                    newValues.set(index, entry.getValue());
                }

                validateRow(schema, newValues);

                Row newRow = heapFile.update(oldRow.rowId(), newValues);
                result.add(newRow.rowId());

                afterWrite(request.table(), oldRow, newRow);
            }
        }

        return result;
    }

    @Override
    public int deleteBlock(DataDeletion request){
        TableSchema schema = requireSchema(request.table());

        HeapFile heapFile = heapFileOf(request.table());
        List<RowId> rowsToDelete = new ArrayList<>();

        try (RowIterator iterator = heapFile.scan()){
            while (iterator.hasNext()){
                Row row = iterator.next();

                if (request.conditions() != null
                        && !request.conditions().isEmpty()
                        && !matchesAll(request.conditions(), row, schema)){
                    continue;
                }

                rowsToDelete.add(row.rowId());
            }
        }

        for (RowId rowId : rowsToDelete){
            deleteByRowId(rowId);
        }

        return rowsToDelete.size();
    }

    @Override
    public Row fetchByRowId(RowId rowId){
        HeapFile heapFile = heapFileOf(rowId.tableName());
        return heapFile.fetch(rowId);
    }

    @Override
    public void insertAt(RowId rowId, List<Object> values){
        TableSchema schema = requireSchema(rowId.tableName());

        validateRow(schema, values);

        HeapFile heapFile = heapFileOf(rowId.tableName());
        heapFile.insertAt(rowId, values);

        Row newRow = new Row(rowId, new ArrayList<>(values));

        try{
            afterWrite(rowId.tableName(), null, newRow);
        } catch (RuntimeException e){
            heapFile.delete(rowId);
            throw e;
        }
    }

    @Override
    public Row updateByRowId(RowId rowId, List<Object> values){
        TableSchema schema = requireSchema(rowId.tableName());

        validateRow(schema, values);

        HeapFile heapFile = heapFileOf(rowId.tableName());

        Row oldRow = heapFile.fetch(rowId);
        Row newRow = heapFile.update(rowId, values);

        try{
            afterWrite(rowId.tableName(), oldRow, newRow);
            return newRow;
        } catch (RuntimeException e){
            if (newRow.rowId().equals(oldRow.rowId())){
                heapFile.update(oldRow.rowId(), oldRow.values());
            } else{
                heapFile.delete(newRow.rowId());
                heapFile.insertAt(oldRow.rowId(), oldRow.values());
            }

            throw e;
        }
    }

    @Override
    public Row deleteByRowId(RowId rowId){
        HeapFile heapFile = heapFileOf(rowId.tableName());

        Row oldRow = heapFile.delete(rowId);

        try{
            afterWrite(rowId.tableName(), oldRow, null);
            return oldRow;
        } catch (RuntimeException e){
            heapFile.insertAt(oldRow.rowId(), oldRow.values());
            throw e;
        }
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
        return catalog.tableExists(table) ? catalog.getSchema(table) : null;
    }

    @Override
    public List<String> listTable(){
        return catalog.listTables();
    }

    @Override
    public void updateTableSchema(TableSchema schema){
        catalog.saveSchema(schema);
    }

    @Override
    public void dropTable(String table){
        requireSchema(table);
        catalog.dropTable(table);
    }

    @Override
    public List<IndexInfo> getIndexes(String table){
        requireSchema(table);
        return catalog.getIndexes(table);
    }

    @Override
    public IndexInfo createIndex(String name, String table, String column){
        return null;
    }

    @Override
    public Statistic getStatistics(String table){
        return statistics.getStatistics(table, heapFileOf(table));
    }

    @Override
    public void flushBuffer(){
        bufferPool.flushBuffer();
    }


    @Override
    public BufferStats getBufferStats(){
        return bufferPool.getBufferStats();
    }

    // ambil schema tabel, lempar TABLE_NOT_FOUND kalau ga ada karena getSchema SM4 lempar IllegalArgumentException
    private TableSchema requireSchema(String table){
        if (!catalog.tableExists(table)){
            throw new StorageException(
                StorageErrorCode.TABLE_NOT_FOUND,
                "Table not found: " + table
            );
        }

        return catalog.getSchema(table);
    }

    private HeapFile heapFileOf(String table){
        HeapFile heapFile = tables.get(table);

        if (heapFile != null){
            return heapFile;
        }

        TableSchema schema = requireSchema(table);

        heapFile = new HeapFile(schema, bufferPool, serializer);
        tables.put(table, heapFile);
        return heapFile;
    }

    private Row projectRow(Row row, TableSchema schema, List<String> columns){
        if (columns == null || columns.isEmpty()){
            return row;
        }

        List<Object> projectedValues = new ArrayList<>();
        List<ColumnDefinition> definitions = schema.columns();

        for (String column : columns){
            int index = -1;

            for (int i = 0; i < definitions.size(); i++){
                if (definitions.get(i).name().equals(column)){
                    index = i;
                    break;
                }
            }

            if (index < 0){
                throw new StorageException(
                    StorageErrorCode.COLUMN_NOT_FOUND,
                    "Column not found: " + column
                );
            }

            projectedValues.add(row.values().get(index));
        }

        return new Row(row.rowId(), projectedValues);
    }

    private boolean matchesAll(List<Condition> conditions, Row row, TableSchema schema){
        for (Condition condition : conditions){
            if (!evaluator.evaluate(condition, row, schema)){
                return false;
            }
        }

        return true;
    }

    private int columnIndex(TableSchema schema, String column){
        List<ColumnDefinition> columns = schema.columns();

        for (int i = 0; i < columns.size(); i++){
            if (columns.get(i).name().equals(column)){
                return i;
            }
        }

        throw new StorageException(
            StorageErrorCode.COLUMN_NOT_FOUND,
            "Column not found: " + column
        );
    }

    private List<Object> toPositional(TableSchema schema, Map<String, Object> values){
        for (String column : values.keySet()){
            columnIndex(schema, column);
        }

        List<Object> result = new ArrayList<>();

        for (ColumnDefinition column : schema.columns()){
            result.add(values.get(column.name()));
        }

        return result;
    }

    private void validateRow(TableSchema schema, List<Object> values){
        List<ColumnDefinition> columns = schema.columns();

        if (values.size() != columns.size()){
            throw new StorageException(
                StorageErrorCode.SCHEMA_VIOLATION,
                "Value count does not match schema"
            );
        }

        for (int i = 0; i < columns.size(); i++){
            ColumnDefinition column = columns.get(i);
            Object value = values.get(i);

            if (value == null){
                if (!column.nullable()){
                    throw new StorageException(
                        StorageErrorCode.SCHEMA_VIOLATION,
                        "Column cannot be null: " + column.name()
                    );
                }
                continue;
            }

            switch (column.type()){
                case INTEGER -> {
                    if (!(value instanceof Integer)){
                        throw new StorageException(
                            StorageErrorCode.SCHEMA_VIOLATION,
                            "Invalid type for column: " + column.name()
                        );
                    }
                }

                case FLOAT -> {
                    if (!(value instanceof Float)){
                        throw new StorageException(
                            StorageErrorCode.SCHEMA_VIOLATION,
                            "Invalid type for column: " + column.name()
                        );
                    }
                }

                case CHAR, VARCHAR -> {
                    if (!(value instanceof String)){
                        throw new StorageException(
                            StorageErrorCode.SCHEMA_VIOLATION,
                            "Invalid type for column: " + column.name()
                        );
                    }

                    String stringValue = (String) value;

                    if (column.length() > 0 && stringValue.length() > column.length()){
                        throw new StorageException(
                            StorageErrorCode.SCHEMA_VIOLATION,
                            "Value too long for column: " + column.name()
                        );
                    }
                }
            }
        }
    }

    private void afterWrite(String table, Row oldRow, Row newRow){
        // synchronize indexes and statistics after SM4 SM5 integration
    }
}
