package mdbms.processor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import mdbms.models.ColumnDefinition;
import mdbms.models.RowId;

public class ResultRow implements IResultRow {
    private final ResultTableSchema tableSchema;
    private final Map<String, Map<String, Object>> values = new HashMap<>();
    private RowId rowId;

    public ResultRow(ResultTableSchema schema) {
        if (schema == null) {
            throw new NullPointerException("Schema cannot be null");
        }
        this.tableSchema = Objects.requireNonNull(schema, "schema");
        for (ColumnDefinition column : schema.getColumns()) {
            String tableName = schema.getColumnTableOrigin(column);
            if (!values.containsKey(tableName)) {
                values.put(tableName, new HashMap<>());
            }
            values.get(tableName).put(column.name(), null);
        }
    }

    public ResultRow(ResultTableSchema schema, List<Object> values) {
        this(schema);
        for (ColumnDefinition column : schema.getColumns()) {
            String tableName = schema.getColumnTableOrigin(column);
            this.values.get(tableName).put(column.name(), null);
        }
    }

    @Override
    public ResultTableSchema getSchema() {
        return tableSchema;
    }

    @Override
    public Object get(String columnName) {
        if (columnName == null) {
            throw new NullPointerException("Cannot get null columnName");
        }

        ColumnDefinition matchingColumn = null;
        for (ColumnDefinition column : tableSchema.getColumns()) {
            if (column.name().equals(columnName)) {
                if (matchingColumn != null) {
                    throw new IllegalArgumentException("Ambiguous column name: " + columnName);
                }
                matchingColumn = column;
            }
        }

        if (matchingColumn == null) {
            return null;
        }

        return get(tableSchema.getColumnTableOrigin(matchingColumn), columnName);
    }

    @Override
    public Object get(String tableName, String columnName) {
        if (tableName == null) {
            throw new NullPointerException("tableName cannot be null");
        }
        if (columnName == null) {
            throw new NullPointerException("columnName cannot be null");
        }

        Map<String, Object> tableValues = values.get(tableName);
        if (tableValues == null)
            return null;
        else
            return tableValues.get(columnName);
    }

    public void set(String tableName, String columnName, Object value) {
        if (tableName == null) {
            throw new NullPointerException("tableName cannot be null");
        }
        if (columnName == null) {
            throw new NullPointerException("columnName cannot be null");
        }

        if (!values.containsKey(tableName)) {
            throw new IllegalArgumentException("There is no table named " + tableName);
        }
        if (!values.get(tableName).containsKey(columnName)) {
            throw new IllegalArgumentException("There is no column from table " + tableName + " named " + columnName);
        }
        values.get(tableName).put(columnName, value);
    }

    @Override
    public RowId getRowId() {
        return rowId;
    }

    public void setRowId(RowId rowId) {
        this.rowId = rowId;
    }
}
