package mdbms.processor.operator;

import mdbms.api.IConcurrencyControl;
import mdbms.models.ColumnDefinition;
import mdbms.models.DataType;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.ResultTableSchema;
import mdbms.processor.IResultRow;

public class BeginOperator implements IQueryOperator {
    private final IConcurrencyControl concurrencyControlManager;
    private int transactionId;
    private boolean isDone;
    private String message;

    public BeginOperator(IConcurrencyControl concurrencyControlManager) {
        this.concurrencyControlManager = concurrencyControlManager;
    }

    public void open() {
        isDone = false;
        message = null;
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

        // TODO BUTUH ResultRow dari QP2 untuk return pesan konfirmasi
        // return null;
    }

    public void close() {
    }

    public int getTransactionId() {
        return transactionId;
    }

    public String getMessage() {
        return message;
    }
}
