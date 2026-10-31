package mdbms.optimizer;

import mdbms.models.TableSchema;

public class TableBinding {
    private final String tableName;
    private final String alias;
    private final TableSchema schema;

    public TableBinding(String tableName, String alias, TableSchema schema) {
        this.tableName = tableName;
        this.alias = alias;
        this.schema = schema;
    }

    public String getTableName() {
        return tableName;
    }

    public String getAlias() {
        return alias;
    }

    public TableSchema getSchema() {
        return schema;
    }
}
