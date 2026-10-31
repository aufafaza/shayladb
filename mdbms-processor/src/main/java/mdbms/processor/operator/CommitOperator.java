package mdbms.processor.operator;

import mdbms.api.IConcurrencyControl;
import mdbms.models.ColumnDefinition;
import mdbms.models.DataType;
import mdbms.processor.IResultRow;
import mdbms.processor.ResultRow;
import mdbms.processor.ResultTableSchema;
import mdbms.processor.IResultRow;

// TODO BUTUH ClientHandler / QueryProcessor menjalankan CommitOperator setelah tiap query di luar BEGIN (auto-commit) dari QP1
public class CommitOperator implements IQueryOperator {
    private final IConcurrencyControl concurrencyControlManager;
    private final int transactionId;
    private boolean isDone;
    private String message;

    public CommitOperator(IConcurrencyControl concurrencyControlManager, int transactionId) {
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
            concurrencyControlManager.commitTransaction(transactionId);
            // TODO BUTUH commitTransaction yang mengembalikan status dari CCM. (sekarang kalau log COMMIT gagal, CCM abort tanpa info)
            message = "COMMIT transaction " + transactionId;
        } catch (IllegalArgumentException | IllegalStateException e) {
            message = "COMMIT rejected: " + e.getMessage();
        }
        // TODO BUTUH ClientSession.clearTransaction() setelah COMMIT dari QP1

        ResultTableSchema schema = new ResultTableSchema();
        schema.addColumn(new ColumnDefinition("message", DataType.VARCHAR, message.length(), false));
        ResultRow result = new ResultRow(schema);
        result.set(schema.getTableName(), "message", message);
        return result;
        // TODO BUTUH ResultRow dari QP2 untuk return pesan hasil
        // return null;
    }

    public void close() {
    }

    public String getMessage() {
        return message;
    }
}
