package mdbms.processor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import mdbms.models.ColumnDefinition;

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

    public List<ColumnDefinition> getColumns() {
        return Collections.unmodifiableList(columns);
    }

    public String getTableName() {
        return tableName;
    }

    public String getColumnTableOrigin(ColumnDefinition columnDefinition) {
        if (columnDefinition == null) {
            throw new NullPointerException("columnDefinition cannot be null");
        }
        return columnTableOrigin.get(columnDefinition);
    }

    public void addColumn(ColumnDefinition columnDefinition) {
        addColumn(tableName, columnDefinition);
    }

    public void addColumn(String originTableName, ColumnDefinition columnDefinition) {
        if (originTableName == null) {
            throw new NullPointerException("originTableName cannot be null");
        }
        if (columnDefinition == null) {
            throw new NullPointerException("columnDefinition cannot be null");
        }
        columns.add(columnDefinition);
        columnTableOrigin.put(columnDefinition, originTableName);
    }

    public boolean hasAmbiguousColumn() {
        for (int i = 0; i < columns.size(); i++) {
            String name = columns.get(i).name();
            for (int j = i + 1; j < columns.size(); j++) {
                if (name.equals(columns.get(j).name())) {
                    return true;
                }
            }
        }
        return false;
    }
}
