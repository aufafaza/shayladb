package mdbms.optimizer.binder;

import java.util.Objects;
import mdbms.models.schema.TableSchema;

public class TableBinding {
    private final String tableName;
    private final String alias;
    private final TableSchema schema;

    public TableBinding(String tableName, String alias, TableSchema schema) {
        this.tableName = Objects.requireNonNull(tableName, "tableName must not be null");
        this.alias = (alias != null && !alias.isBlank()) ? alias : tableName;
        this.schema = Objects.requireNonNull(schema, "schema must not be null");
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

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TableBinding that)) return false;
        return tableName.equalsIgnoreCase(that.tableName)
            && alias.equalsIgnoreCase(that.alias)
            && Objects.equals(schema, that.schema);
    }

    @Override
    public int hashCode() {
        return Objects.hash(tableName.toLowerCase(), alias.toLowerCase(), schema);
    }

    @Override
    public String toString() {
        return "TableBinding{" +
            "tableName='" + tableName + '\'' +
            ", alias='" + alias + '\'' +
            ", schema=" + schema +
            '}';
    }
}
