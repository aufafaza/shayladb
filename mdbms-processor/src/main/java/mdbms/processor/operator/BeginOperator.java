package mdbms.processor.operator;

import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.ResultTableSchema;

public class BeginOperator implements IQueryOperator {
    private final int transactionId;
    private boolean isDone;

    public BeginOperator(int transactionId) {
        this.transactionId = transactionId;
    }

    public void open() {
        isDone = false;
    }

    public IResultRow next() {
        if (isDone) return null;
        isDone = true;

        String message = "BEGIN TRANSACTION " + transactionId;

        ResultTableSchema schema = new ResultTableSchema();
        schema.addColumn(new ColumnDefinition("message", DataType.VARCHAR, message.length(), false));
        ResultRow result = new ResultRow(schema);
        result.set(schema.getTableName(), "message", message);
        return result;
    }

    public void close() {
    }
}
