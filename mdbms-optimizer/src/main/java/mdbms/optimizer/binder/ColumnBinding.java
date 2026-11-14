package mdbms.optimizer.binder;

import java.util.Objects;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;

public class ColumnBinding {
    private final String tableName;
    private final String tableAlias;
    private final ColumnDefinition column;

    public ColumnBinding(String tableName, String tableAlias, ColumnDefinition column) {
        this.tableName = Objects.requireNonNull(tableName, "tableName must not be null");
        this.tableAlias = (tableAlias != null && !tableAlias.isBlank()) ? tableAlias : tableName;
        this.column = Objects.requireNonNull(column, "column must not be null");
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

    public String getColumnName() {
        return column.name();
    }

    public DataType getDataType() {
        return column.type();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ColumnBinding that)) return false;
        return tableName.equalsIgnoreCase(that.tableName)
            && tableAlias.equalsIgnoreCase(that.tableAlias)
            && Objects.equals(column, that.column);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableName.toLowerCase(), tableAlias.toLowerCase(), column);
    }

    @Override
    public String toString() {
        return "ColumnBinding{" +
            "tableName='" + tableName + '\'' +
            ", tableAlias='" + tableAlias + '\'' +
            ", column=" + column +
            '}';
    }
}
