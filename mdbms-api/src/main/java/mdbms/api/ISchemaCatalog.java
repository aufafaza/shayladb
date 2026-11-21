package mdbms.api;

import java.util.List;
import mdbms.models.schema.IndexInfo;
import mdbms.models.schema.TableSchema;
import mdbms.models.schema.DropBehavior;

public interface ISchemaCatalog{
    TableSchema getTableSchema(String table);

    List<String> listTable();

    void updateTableSchema(TableSchema schema);

    void dropTable(String table);

    /** Catalogs with incoming foreign keys must implement CASCADE atomically. */
    default void dropTable(String table, DropBehavior behavior) {
        java.util.Objects.requireNonNull(behavior, "behavior");
        for (String name : listTable()) {
            if (name.equalsIgnoreCase(table)) continue;
            if (getTableSchema(name).foreignKeys().stream()
                    .anyMatch(key -> key.referencedTable().equalsIgnoreCase(table))) {
                if (behavior == DropBehavior.RESTRICT) {
                    throw new IllegalArgumentException("Cannot drop table '" + table + "': referenced by '" + name + "'");
                }
                throw new UnsupportedOperationException("This catalog does not support DROP TABLE CASCADE with dependent constraints");
            }
        }
        dropTable(table);
    }

    List<IndexInfo> getIndexes(String table);

    IndexInfo createIndex(String name, String table, String column);
}
