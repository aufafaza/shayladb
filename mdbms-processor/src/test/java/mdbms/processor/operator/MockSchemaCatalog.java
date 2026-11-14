package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import mdbms.api.ISchemaCatalog;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.IndexType;
import mdbms.models.schema.TableSchema;

final class MockSchemaCatalog implements ISchemaCatalog {
    private final Map<String, TableSchema> tables = new LinkedHashMap<>();
    private final List<IndexInfo> indexes = new ArrayList<>();

    @Override
    public TableSchema getTableSchema(String table) {
        return tables.get(table);
    }

    @Override
    public List<String> listTable() {
        return new ArrayList<>(tables.keySet());
    }

    @Override
    public void updateTableSchema(TableSchema schema) {
        tables.put(schema.tableName(), schema);
    }

    @Override
    public void dropTable(String table) {
        tables.remove(table);
        indexes.removeIf(index -> index.tableName().equals(table));
    }

    @Override
    public List<IndexInfo> getIndexes(String table) {
        return indexes.stream()
                .filter(index -> index.tableName().equals(table))
                .toList();
    }

    @Override
    public IndexInfo createIndex(String name, String table, String column) {
        IndexInfo index = new IndexInfo(name, table, column, IndexType.BPLUS_TREE);
        indexes.add(index);
        return index;
    }
}
