package mdbms.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.TableSchema;

public class ResultTableSchema {
    private final String tableName;
    private final List<ColumnDefinition> columns = new ArrayList<>();
    private final Map<ColumnDefinition, String> columnTableOrigin = new IdentityHashMap<>();

    public ResultTableSchema() {
        this("");
    }

    public ResultTableSchema(String tableName) {
        this.tableName = Objects.requireNonNull(tableName, "tableName");
    }

    public ResultTableSchema(TableSchema tableSchema) {
        this.tableName = tableSchema.tableName();
        for (ColumnDefinition columnDefinition : tableSchema.columns()) {
            addColumn(columnDefinition);
        }
    }

    public List<ColumnDefinition> getColumns() {
        return Collections.unmodifiableList(columns);
    }

    public String getTableName() {
        return tableName;
    }

    public String getColumnTableOrigin(ColumnDefinition columnDefinition) {
        Objects.requireNonNull(columnDefinition);
        return columnTableOrigin.getOrDefault(columnDefinition, "");
    }

    public String getColumnTableOrigin(String columnName) {
        Objects.requireNonNull(columnName);
        for (ColumnDefinition column : columns) {
            if (column.name().equalsIgnoreCase(columnName)) {
                return getColumnTableOrigin(column);
            }
        }
        return "";
    }

    public String getColumnTableOrigin(int columnIndex) {
        return getColumnTableOrigin(columns.get(columnIndex));
    }

    public void addColumn(ColumnDefinition columnDefinition) {
        addColumn(tableName, columnDefinition);
    }

    public void addColumn(String originTableName, ColumnDefinition columnDefinition) {
        Objects.requireNonNull(originTableName);
        Objects.requireNonNull(columnDefinition);
        columns.add(columnDefinition);
        columnTableOrigin.put(columnDefinition, originTableName);
    }

    public boolean hasAmbiguousColumn() {
        for (int i = 0; i < columns.size(); i++) {
            String name = columns.get(i).name();
            for (int j = i + 1; j < columns.size(); j++) {
                if (name.equalsIgnoreCase(columns.get(j).name())) {
                    return true;
                }
            }
        }
        return false;
    }

    public boolean isColumnAmbiguous(String columnName) {
        boolean hasColumn = false;
        for (int i = 0; i < columns.size(); i++) {
            String name = columns.get(i).name();
            if (name.equalsIgnoreCase(columnName)) {
                if (!hasColumn)
                    hasColumn = true;
                else
                    return true;
            }
        }
        return false;
    }
}
