package mdbms.processor.operator;

import mdbms.api.IConcurrencyControl;
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

        try {
            concurrencyControlManager.abortTransaction(transactionId, "ABORT requested by client");
            message = "ABORT transaction " + transactionId;
        } catch (IllegalArgumentException e) {
            message = "Transaction " + transactionId + " is not active";
        }
        // TODO BUTUH ClientSession.clearTransaction() setelah ABORT dari QP1

        // TODO BUTUH ResultRow dari QP2 untuk return status
        return null;
    }

    public void close() {
    }

    public String getMessage() {
        return message;
    }
}
