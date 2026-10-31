package mdbms.processor.operator;

import mdbms.api.IConcurrencyControl;
import mdbms.models.ColumnDefinition;
import mdbms.models.DataType;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.ResultTableSchema;
import mdbms.processor.IResultRow;

public class AbortOperator implements IQueryOperator {
    private final IConcurrencyControl concurrencyControlManager;
    private final int transactionId;
    private boolean isDone;
    private String message;

    public AbortOperator(IConcurrencyControl concurrencyControlManager, int transactionId) {
        this.concurrencyControlManager = concurrencyControlManager;
        this.transactionId = transactionId;
    }

    public void open() {
        isDone = false;
        message = null;
    }

    public IResultRow next() {
        if (isDone) return null;
        isDone = true;

        String message;
        try {
            concurrencyControlManager.abortTransaction(transactionId, "ABORT requested by client");
            message = "ABORT transaction " + transactionId;
        } catch (IllegalArgumentException e) {
            message = "Transaction " + transactionId + " is not active";
        }
        // TODO BUTUH ClientSession.clearTransaction() setelah ABORT dari QP1

        ResultTableSchema schema = new ResultTableSchema();
        schema.addColumn(new ColumnDefinition("message", DataType.VARCHAR, message.length(), false));
        ResultRow result = new ResultRow(schema);
        result.set(schema.getTableName(), "message", message);
        return result;
        // TODO BUTUH ResultRow dari QP2 untuk return status
        // return null;
    }

    public void close() {
    }

    public String getMessage() {
        return message;
    }
}
