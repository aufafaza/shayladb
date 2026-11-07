package mdbms.optimizer;

import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;

public class ColumnBinding {
    private final String tableName;
    private final String tableAlias;
    private final ColumnDefinition column;

    public ColumnBinding(String tableName, String tableAlias, ColumnDefinition column) {
        this.tableName = tableName;
        this.tableAlias = tableAlias;
        this.column = column;
    }

    public String getTableName() {
        return tableName;
    }

    public String getTableAlias() {
        return tableAlias;
    }

    public ColumnDefinition getColumn() {
        return column;
    }

    public DataType getDataType() {
        // TODO
        return null;
    }
}
