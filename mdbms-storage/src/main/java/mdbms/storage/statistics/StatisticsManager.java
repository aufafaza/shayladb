package mdbms.storage.statistics;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import mdbms.models.data.Row;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.Statistic;
import mdbms.models.schema.TableSchema;
import mdbms.storage.block.BlockManager;
import mdbms.storage.catalog.CatalogManager;
import mdbms.storage.crud.HeapFile;
import mdbms.storage.exception.StorageErrorCode;
import mdbms.storage.exception.StorageException;
public final class StatisticsManager {
    private final CatalogManager catalog;
    private final Map<String, Statistic> cache = new HashMap<>();
    private static int estimateEncodedValSize(Object val, ColumnDefinition col){ //helper buat estimate size payload yang dipake record serializer
        if(val == null) return 0;
        return switch(col.type()){
            case INTEGER, FLOAT -> Integer.BYTES;
            case CHAR -> col.length();
            case VARCHAR -> Integer.BYTES + val.toString().getBytes(StandardCharsets.UTF_8).length;
        };
    }
    private static long estimateSchemaRecSize(TableSchema schema){ //helper buat estimate record size schema
        long size = 0;
        for(ColumnDefinition c: schema.columns()){
            size += switch(c.type()){
                case INTEGER, FLOAT -> Integer.BYTES;
                case CHAR -> c.length();
                case VARCHAR -> Integer.BYTES + c.length();
            };
        }
        return size;
    }
    public StatisticsManager(CatalogManager catalog){
        if(catalog == null) throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION, "StatisticsManager needs a catalog");
        this.catalog = catalog;
    }
    public synchronized Statistic getStatistics(String table, HeapFile file){
        if(table == null || table.isBlank()) throw new StorageException(StorageErrorCode.TABLE_NOT_FOUND, "Table name must not be null or blank");
        if(file == null) throw new StorageException(StorageErrorCode.TABLE_NOT_FOUND, "HeapFile is unavailable for table " + table);
        Statistic cached = cache.get(table);
        if(cached != null ) return cached;
        TableSchema schema = catalog.getSchema(table);
        Statistic result = compute(file, schema);
        cache.put(table, result);
        return result;
    }
    public synchronized void invalidate(String table){
        if(table == null || table.isBlank()) throw new StorageException(StorageErrorCode.TABLE_NOT_FOUND, "Table name must not be null or blank");
        cache.remove(table);
    }
    private synchronized Statistic compute(HeapFile file, TableSchema schema){
        if(file == null || schema == null) throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION, "Can't compute stats without a heap file and/or table schema");
        Map<String, Set<Object>> distinctVals = new HashMap<>();
        for(ColumnDefinition c: schema.columns()){
            distinctVals.put(c.name(), new HashSet<>());
        }
        long rows = 0;
        long totalRecBytes = 0;
        try(var iterator = file.scan()){
            while(iterator.hasNext()){
                Row row = iterator.next();
                if(row == null) throw new StorageException(StorageErrorCode.IO_FAILURE, "HeapFile returned a null row for table " + schema.tableName());
                if(row.values() == null || row.values().size() != schema.columns().size()){
                    throw new StorageException(StorageErrorCode.SCHEMA_VIOLATION, "Row does not match schema for table " + schema.tableName());
                }
                rows++;
                for(int i = 0; i < schema.columns().size(); i++){
                    Object val = row.values().get(i);
                    if(val != null) distinctVals.get(schema.columns().get(i).name()).add(val);
                    totalRecBytes += estimateEncodedValSize(val, schema.columns().get(i));
                }
            }
        }
        if(rows > Integer.MAX_VALUE){
            throw new StorageException(StorageErrorCode.IO_FAILURE, "Table " + schema.tableName() + " has more rows than data type of nr can represent");
        }
        int nr = (int) rows;
        long avgRecSize = nr == 0 ? estimateSchemaRecSize(schema) : (totalRecBytes + rows - 1)/rows;
        int lr = (int) avgRecSize;
        int fr = lr == 0 ? 0 : BlockManager.BLOCK_SIZE / lr;
        int br = file.blockCount();
        Map<String, Integer> counts = new HashMap<>();
        distinctVals.forEach((col, vals) -> counts.put(col, vals.size()));
        return new Statistic(schema.tableName(), nr, br, lr, fr, Map.copyOf(counts));
    }
}
