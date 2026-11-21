package mdbms.processor.operator;

import mdbms.api.IConcurrencyControl;
import mdbms.processor.IResultRow;
import mdbms.processor.exception.TransactionCompletionException;

public class CommitOperator implements IQueryOperator, IQueryMessage {
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
    }

    public IResultRow next() {
        if (isDone) return null;
        isDone = true;

        try {
            concurrencyControlManager.commitTransaction(transactionId);
            message = "COMMIT transaction " + transactionId;
        } catch (RuntimeException e) {
            // lempar biar hasil query gagal, jangan sampai klien ngira COMMIT berhasil
            message = "COMMIT rejected: " + e.getMessage();
            throw new TransactionCompletionException(message, e);
        }
        return null;
    }

    public void close() {
    }

    @Override
    public String getMessage() {
        return message;
    }
}
