package mdbms.processor.operator;

import mdbms.api.IConcurrencyControl;
import mdbms.models.schema.ColumnDefinition;
import mdbms.models.schema.DataType;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.ResultTableSchema;

public class AbortOperator implements IQueryOperator {
    private final IConcurrencyControl concurrencyControlManager;
    private final int transactionId;
    private boolean isDone;

    public AbortOperator(IConcurrencyControl concurrencyControlManager, int transactionId) {
        this.concurrencyControlManager = concurrencyControlManager;
        this.transactionId = transactionId;
    }

    public void open() {
        isDone = false;
    }

    public IResultRow next() {
        if (isDone) return null;
        isDone = true;

        String message;
        try {
            concurrencyControlManager.abortTransaction(transactionId, "ABORT requested by client");
            message = "ABORT transaction " + transactionId;
        } catch (RuntimeException e) {
            message = "ABORT failed: " + e.getMessage();
        }

        ResultTableSchema schema = new ResultTableSchema();
        schema.addColumn(new ColumnDefinition("message", DataType.VARCHAR, message.length(), false));
        ResultRow result = new ResultRow(schema);
        result.set(schema.getTableName(), "message", message);
        return result;
    }

    public void close() {
    }
}
