package mdbms.processor.operator;

import mdbms.api.IConcurrencyControl;
import mdbms.models.ColumnDefinition;
import mdbms.models.DataType;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.ResultTableSchema;

public class BeginOperator implements IQueryOperator {
    private final IConcurrencyControl concurrencyControlManager;
    private int transactionId;
    private boolean isDone;

    public BeginOperator(IConcurrencyControl concurrencyControlManager) {
        this.concurrencyControlManager = concurrencyControlManager;
    }

    public void open() {
        isDone = false;
    }

    public IResultRow next() {
        if (isDone) return null;
        isDone = true;

        transactionId = concurrencyControlManager.beginTransaction();
        // TODO BUTUH beginTransaction yang juga catat BEGIN ke FRM dari CCM
        // TODO BUTUH ClientSession menyimpan transactionId ini, dan ClientHandler gunakan untuk query berikutnya
        String message = "BEGIN TRANSACTION " + transactionId;

        ResultTableSchema schema = new ResultTableSchema();
        schema.addColumn(new ColumnDefinition("message", DataType.VARCHAR, message.length(), false));
        ResultRow result = new ResultRow(schema);
        result.set(schema.getTableName(), "message", message);
        return result;
    }

    public void close() {
    }

    public int getTransactionId() {
        return transactionId;
    }
}
