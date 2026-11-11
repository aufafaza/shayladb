package mdbms.models.query;

import java.util.Objects;

// sementara ini dlu
public final class ColumnReference {
    private final String tableName;
    private final String columnName;

    public ColumnReference(String tableName, String columnName) {
        this.tableName = tableName;
        this.columnName = Objects.requireNonNull(columnName);
    }

    public String getTableName() {
        return tableName;
    }

    public String getColumnName() {
        return columnName;
    }

    //SELECT * remains unresolved until the binder expands it from the catalog.
    public boolean isWildcard() {
        return "*".equals(columnName);
    }

    // BindingContext uses ColumnReference as a Map key in the class diagram.
    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        return other instanceof ColumnReference reference
            && Objects.equals(tableName, reference.tableName)
            && columnName.equals(reference.columnName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableName, columnName);
    }

    @Override
    public String toString() {
        return tableName == null ? columnName : tableName + "." + columnName;
    }
}
