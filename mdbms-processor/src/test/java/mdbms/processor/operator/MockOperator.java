package mdbms.processor.operator;

import java.util.ArrayList;
import java.util.List;

import mdbms.models.query.ColumnReference;
import mdbms.models.schema.ColumnDefinition;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.ResultTableSchema;

final class MockOperator implements IQueryOperator {
    private ResultTableSchema tableSchema;
    List<ResultRow> resultRows = new ArrayList<>();

    boolean isDone = true;
    int currentRowIndex = 0;

    public MockOperator() {
        tableSchema = new ResultTableSchema();
    }

    public void addColumn(ColumnReference column) {
        if (column.getTableName() != null) {
            tableSchema.addColumn(column.getTableName(), new ColumnDefinition(column.getColumnName(), null, 0, false));
        } else {
            tableSchema.addColumn(new ColumnDefinition(column.getColumnName(), null, 0, false));
        }
    }

    public IResultRow newRow(Object... values) {
        if (values.length != tableSchema.getColumns().size()) {
            throw new IllegalArgumentException("Invalid value count, expected " + tableSchema.getColumns().size());
        }

        ResultRow resultRow = new ResultRow(tableSchema);
        int i = 0;
        for (ColumnDefinition columnDefinition : tableSchema.getColumns()) {
            resultRow.set(tableSchema.getColumnTableOrigin(columnDefinition), columnDefinition.name(), values[i++]);
        }
        resultRows.add(resultRow);
        return resultRow;
    }

    public IResultRow getRow(int index) {
        return resultRows.get(index);
    }

    @Override
    public void open() {
        currentRowIndex = 0;
        isDone = resultRows.isEmpty();
    }

    @Override
    public IResultRow next() {
        if (isDone || currentRowIndex >= resultRows.size()) {
            isDone = true;
            return null;
        }
        IResultRow row = getRow(currentRowIndex++);
        if (currentRowIndex >= resultRows.size())
            isDone = true;
        return row;
    }

    @Override
    public void close() {
    }
}
