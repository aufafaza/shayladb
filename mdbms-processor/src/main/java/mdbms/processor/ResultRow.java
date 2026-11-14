package mdbms.processor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import mdbms.models.data.RowId;
import mdbms.models.schema.ColumnDefinition;
import mdbms.processor.exception.AmbiguousColumnException;
import mdbms.processor.exception.InvalidColumnReferenceException;

public class ResultRow implements IResultRow {
    public final static ResultRow EMPTY = new ResultRow(new ResultTableSchema());

    private final ResultTableSchema tableSchema;
    private final Map<String, Map<String, Object>> values = new HashMap<>();
    private RowId rowId;


    public ResultRow(ResultTableSchema schema) {
        Objects.requireNonNull(schema);
        
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
        Objects.requireNonNull(values, "values");
        if (values.size() != schema.getColumns().size()) {
            throw new IllegalArgumentException("Value count differs from result schema");
        }
        for (int i = 0; i < schema.getColumns().size(); i++) {
            ColumnDefinition column = schema.getColumns().get(i);
            String tableName = schema.getColumnTableOrigin(column);
            this.values.get(tableName).put(column.name(), values.get(i));
        }
    }

    @Override
    public ResultTableSchema getSchema() {
        return tableSchema;
    }

    @Override
    public Object get(String columnName) {
        Objects.requireNonNull(columnName);

        ColumnDefinition matchingColumn = null;
        for (ColumnDefinition column : tableSchema.getColumns()) {
            if (column.name().equals(columnName)) {
                if (matchingColumn != null) {
                    throw new AmbiguousColumnException(columnName);
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
        Objects.requireNonNull(tableName);
        Objects.requireNonNull(columnName);

        Map<String, Object> tableValues = values.get(tableName);
        if (tableValues == null)
            return null;
        else
            return tableValues.get(columnName);
    }

    public void set(String tableName, String columnName, Object value) {
        Objects.requireNonNull(tableName);
        Objects.requireNonNull(columnName);

        if (!values.containsKey(tableName) || !values.get(tableName).containsKey(columnName)) {
            throw new InvalidColumnReferenceException(tableName, columnName);
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
