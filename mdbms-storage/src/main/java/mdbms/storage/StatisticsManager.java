package mdbms.storage;
import java.util.*;
import mdbms.models.schema.*;
import mdbms.storage.block.*;
import mdbms.models.data.*;
import java.nio.charset.*;

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
    private static int estimateSchemaRecSize(TableSchema schema){ //helper buat estimate record size schema
        long size = 0;
        for(ColumnDefinition c: schema.columns()){
            size += switch(c.type()){
                case INTEGER, FLOAT -> Integer.BYTES;
                case CHAR -> c.length();
                case VARCHAR -> Integer.BYTES + c.length();
            };
        }
        return (int) Math.min(size, Integer.MAX_VALUE);
    }
    public StatisticsManager(CatalogManager catalog){
        this.catalog=Objects.requireNonNull(catalog);
    }
    public synchronized Statistic getStatistics(String table, HeapFile file){
        Objects.requireNonNull(table);
        Objects.requireNonNull(file);
        Statistic cached = cache.get(table);
        if(cached != null ) return cached;
        TableSchema schema = catalog.getSchema(table);
        Statistic result = compute(file, schema);
        cache.put(table, result);
        return result;
    }
    public synchronized void invalidate(String table){
        Objects.requireNonNull(table);
        cache.remove(table);
    }
    private synchronized Statistic compute(HeapFile file, TableSchema schema){
        Objects.requireNonNull(file, "file");
        Objects.requireNonNull(schema, "schema");
        Map<String, Set<Object>> distinctVals = new HashMap<>();
        for(ColumnDefinition c: schema.columns()){
            distinctVals.put(c.name(), new HashSet<>());
        }
        long rows = 0;
        long totalRecBytes = 0;
        try(var iterator = file.scan()){
            while(iterator.hasNext()){
                Row row = Objects.requireNonNull(iterator.next(), "heapscan returned a null row");
                if(row.values() == null || row.values().size() != schema.columns().size()){
                    throw new IllegalStateException("Row does not match schema for table " + schema.tableName());
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
            throw new IllegalStateException("Table row count exceeds statistic capacity: " + schema.tableName());
        }
        int nr = (int) rows;
        int lr = nr == 0 ? estimateSchemaRecSize(schema) : (int) Math.min(Integer.MAX_VALUE, (totalRecBytes + rows - 1)/rows);
        int fr = lr == 0 ? 0 : BlockManager.BLOCK_SIZE / lr;
        int br = file.blockCount();
        Map<String, Integer> counts = new HashMap<>();
        distinctVals.forEach((col, vals) -> counts.put(col, vals.size()));
        return new Statistic(schema.tableName(), nr, br, lr, fr, Map.copyOf(counts));
    }
}
